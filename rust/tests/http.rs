//! HTTP tool tests — mirrors golang/toolnexus_test.go TestHTTPTool, plus body modes.
//! Hermetic: a local TCP server that records each request.

use std::sync::{Arc, Mutex};

use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use toolnexus::ToolContext;
use toolnexus::ToolSource;
use toolnexus::http::{HttpToolOptions, http_tool};

type Seen = Arc<Mutex<Vec<String>>>;

async fn server() -> (String, Seen) {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    let seen: Seen = Arc::default();
    let s2 = seen.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else {
                return;
            };
            let seen = s2.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                // read headers, then content-length bytes
                loop {
                    let n = s.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    if let Some(i) = text.find("\r\n\r\n") {
                        let cl = text
                            .lines()
                            .find_map(|l| {
                                l.to_ascii_lowercase()
                                    .strip_prefix("content-length:")
                                    .map(|v| v.trim().parse::<usize>().unwrap_or(0))
                            })
                            .unwrap_or(0);
                        if buf.len() >= i + 4 + cl {
                            break;
                        }
                    }
                }
                let req = String::from_utf8_lossy(&buf).to_string();
                seen.lock().unwrap().push(req.clone());
                let first = req.lines().next().unwrap_or("").to_owned();
                let (status, body) = if first.contains(" /posts/5") {
                    ("200 OK", r#"{"id":5,"title":"hello"}"#)
                } else if first.contains(" /echo") {
                    ("201 Created", "made")
                } else {
                    ("404 Not Found", "nope")
                };
                let resp = format!(
                    "HTTP/1.1 {status}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = s.write_all(resp.as_bytes()).await;
            });
        }
    });
    (format!("http://{addr}"), seen)
}

#[tokio::test]
async fn http_tool_placeholder_query_env_header_and_errors() {
    // SAFETY: test-local env var, set before any request reads it.
    unsafe { std::env::set_var("TN_HTTP_TOKEN", "tok") };
    let (base, seen) = server().await;
    let mut headers = std::collections::BTreeMap::new();
    headers.insert("Authorization".into(), "Bearer ${TN_HTTP_TOKEN}".into());
    let ok = http_tool(HttpToolOptions {
        name: "get_post".into(),
        method: "GET".into(),
        url: format!("{base}/posts/{{id}}"),
        headers,
        input_schema: Some(
            json!({"type": "object", "properties": {"id": {"type": "number"}, "q": {"type": "string"}}, "required": ["id"]}),
        ),
        ..Default::default()
    });
    assert_eq!(ok.source, ToolSource::Http);
    let r = ok
        .call(json!({"id": 5, "q": "x y"}), ToolContext::default())
        .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, r#"{"id":5,"title":"hello"}"#);
    assert_eq!(r.metadata.as_ref().unwrap()["status"], 200);
    let req = seen.lock().unwrap().last().unwrap().clone();
    assert!(req.starts_with("GET /posts/5?q=x+y HTTP/1.1"), "{req}");
    assert!(req.to_lowercase().contains("authorization: bearer tok"));

    let bad = http_tool(HttpToolOptions {
        name: "b".into(),
        url: format!("{base}/posts/{{id}}"),
        ..Default::default()
    });
    let r = bad.call(json!({"id": 99}), ToolContext::default()).await;
    assert!(r.is_error);
    assert_eq!(r.output, "HTTP 404: nope");
    assert_eq!(r.metadata.as_ref().unwrap()["status"], 404);
}

#[tokio::test]
async fn http_tool_body_modes_and_result_modes() {
    let (base, seen) = server().await;
    let post = http_tool(HttpToolOptions {
        name: "p".into(),
        method: "post".into(),
        url: format!("{base}/echo"),
        query: vec!["tag".into()],
        result_mode: "status+text".into(),
        ..Default::default()
    });
    let r = post
        .call(
            json!({"tag": "t", "b": 2, "a": "<x>"}),
            ToolContext::default(),
        )
        .await;
    assert_eq!(r.output, "201\nmade");
    let req = seen.lock().unwrap().last().unwrap().clone();
    assert!(req.starts_with("POST /echo?tag=t HTTP/1.1"), "{req}");
    assert!(
        req.to_lowercase()
            .contains("content-type: application/json")
    );
    // Go json.Marshal: sorted keys, HTML-escaped
    assert!(req.ends_with(r#"{"a":"\u003cx\u003e","b":2}"#), "{req}");

    let form = http_tool(HttpToolOptions {
        name: "f".into(),
        method: "PUT".into(),
        url: format!("{base}/echo"),
        body: "form".into(),
        ..Default::default()
    });
    form.call(json!({"k": "a b", "z": 1}), ToolContext::default())
        .await;
    let req = seen.lock().unwrap().last().unwrap().clone();
    assert!(req.ends_with("k=a+b&z=1"), "{req}");
    assert!(
        req.to_lowercase()
            .contains("content-type: application/x-www-form-urlencoded")
    );

    let js = http_tool(HttpToolOptions {
        name: "j".into(),
        url: format!("{base}/posts/5"),
        result_mode: "json".into(),
        ..Default::default()
    });
    let r = js.call(json!({}), ToolContext::default()).await;
    assert_eq!(r.output, r#"{"id":5,"title":"hello"}"#);
}
