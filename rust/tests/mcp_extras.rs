//! MCP host extras: §1B content parts on every branch, tool-annotation hints,
//! resource tools, per-server readiness (`on_server`) and the legacy SSE
//! fallback. Mirrors golang mcp_test.go / host_builtin_extras_test.go
//! TestMcpResourceTools. Hermetic: the node fixture (skipped without node) and
//! an in-process legacy-SSE MCP server.

use std::path::Path;
use std::sync::{Arc, Mutex};

use serde_json::json;
use toolnexus::ToolContext;
use toolnexus::mcp::*;

fn node_fixture(timeout_ms: u64) -> Option<McpConfig> {
    if std::process::Command::new("node")
        .arg("--version")
        .output()
        .is_err()
    {
        eprintln!("node not on PATH; skipping");
        return None;
    }
    let f = Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/mcp-progress-server.mjs");
    Some(
        parse_mcp_config(&json!({"mcpServers": {
            "progress": {"command": ["node", f.to_string_lossy()], "timeout": timeout_ms},
            "broken": {"command": "/nonexistent/toolnexus-no-such-binary"},
            "off": {"command": "node", "enabled": false},
        }}))
        .unwrap(),
    )
}

#[test]
fn content_parts_on_every_branch() {
    let content = vec![
        json!({"type": "text", "text": "hello"}),
        json!({"type": "image", "data": "iVBORw==", "mimeType": "image/png"}),
        json!({"type": "audio", "data": "AAAA", "mimeType": "audio/wav"}),
        json!({"type": "resource_link", "uri": "https://x/y.pdf", "name": "y.pdf", "mimeType": "application/pdf"}),
        json!({"type": "resource", "resource": {"uri": "file:///b.bin", "mimeType": "application/octet-stream", "blob": "AQI="}}),
        json!({"type": "resource", "resource": {"uri": "file:///t.txt", "text": "embedded"}}),
    ];
    let ok = map_wire_result(false, None, &content);
    assert_eq!(ok.output, "hello\nembedded");
    let kinds: Vec<&str> = ok.parts.iter().map(|p| p.kind.as_str()).collect();
    assert_eq!(kinds, ["image", "audio", "file", "file"]);
    assert_eq!(ok.parts[2].url, "https://x/y.pdf");
    assert_eq!(ok.parts[2].name, "y.pdf");
    assert_eq!(ok.parts[3].data, "AQI=");
    assert_eq!(ok.parts[3].name, "file:///b.bin");

    let err = map_wire_result(true, None, &content);
    assert!(err.is_error);
    assert_eq!(err.output, "hello");
    assert_eq!(err.parts.len(), 4, "error branch keeps parts");

    let st = map_wire_result(false, Some(json!({"b": 1, "a": "<"})), &content);
    assert_eq!(st.output, r#"{"a":"\u003c","b":1}"#);
    assert_eq!(st.parts.len(), 4, "structured branch keeps parts");

    let only = map_wire_result(
        false,
        None,
        &[json!({"type": "image", "data": "iVBORw==", "mimeType": "image/png"})],
    );
    assert_eq!(only.output, "image (image/png, 4 bytes)");
}

#[tokio::test]
async fn resource_tools_over_the_fixture() {
    let Some(cfg) = node_fixture(5000) else {
        return;
    };
    let src = load_mcp(&cfg).await;
    let tools = src.resource_tools();
    let (list, read) = (&tools[0], &tools[1]);
    assert!(list.hints.unwrap().read_only && read.hints.unwrap().read_only);
    let r = list.call(json!({}), ToolContext::default()).await;
    assert!(!r.is_error);
    assert_eq!(
        r.output,
        "progress fixture://hello — hello (text/plain): A fixed text resource."
    );
    let r = read
        .call(
            json!({"server": "progress", "uri": "fixture://hello"}),
            ToolContext::default(),
        )
        .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "hello resource");
    let r = read
        .call(
            json!({"server": "progress", "uri": "fixture://nope"}),
            ToolContext::default(),
        )
        .await;
    assert!(r.is_error);
    let r = read
        .call(
            json!({"server": "nope", "uri": "x"}),
            ToolContext::default(),
        )
        .await;
    assert!(r.is_error);
    assert_eq!(
        r.output,
        "read_mcp_resource: no connected MCP server \"nope\" (connected: progress)"
    );
    src.close().await;
}

#[tokio::test]
async fn on_server_reports_each_server_once() {
    let Some(cfg) = node_fixture(5000) else {
        return;
    };
    type Seen = Arc<Mutex<Vec<(String, McpStatus, usize, bool)>>>;
    let seen: Seen = Arc::default();
    let s = seen.clone();
    let src = load_mcp_with(
        &cfg,
        McpLoadOptions {
            on_server: Some(Arc::new(move |r: McpServerResult| {
                s.lock()
                    .unwrap()
                    .push((r.name, r.status, r.tools.len(), r.error.is_some()));
            })),
        },
    )
    .await;
    let mut got = seen.lock().unwrap().clone();
    got.sort_by(|a, b| a.0.cmp(&b.0));
    assert_eq!(got.len(), 3);
    assert_eq!(got[0], ("broken".into(), McpStatus::Failed, 0, true));
    assert_eq!(got[1], ("off".into(), McpStatus::Disabled, 0, false));
    assert_eq!(got[2].0, "progress");
    assert_eq!(got[2].1, McpStatus::Connected);
    assert_eq!(got[2].2, src.tools.len());
    assert_eq!(src.server_names(), ["progress"]);
    src.close().await;
}

// ---------------------------------------------------------------------------
// legacy SSE fallback against an in-process server
// ---------------------------------------------------------------------------

#[cfg(feature = "mcp-http")]
mod sse_fixture {
    use super::*;
    use serde_json::Value;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::sync::broadcast;

    pub struct Fixture {
        pub url: String,
        pub auth_seen: Arc<Mutex<Vec<String>>>,
    }

    async fn read_request(s: &mut tokio::net::TcpStream) -> Option<(String, String, String)> {
        let mut buf = Vec::new();
        let mut chunk = [0u8; 4096];
        loop {
            let n = s.read(&mut chunk).await.ok()?;
            if n == 0 {
                return None;
            }
            buf.extend_from_slice(&chunk[..n]);
            let text = String::from_utf8_lossy(&buf).to_string();
            if let Some(i) = text.find("\r\n\r\n") {
                let head = text[..i].to_owned();
                let cl = head
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse::<usize>().unwrap_or(0))
                    })
                    .unwrap_or(0);
                if buf.len() >= i + 4 + cl {
                    let body = String::from_utf8_lossy(&buf[i + 4..i + 4 + cl]).to_string();
                    let first = head.lines().next().unwrap_or("").to_owned();
                    return Some((first, head, body));
                }
            }
        }
    }

    fn respond(msg: &Value) -> Option<Value> {
        let id = msg.get("id")?.clone();
        let result = match msg["method"].as_str()? {
            "initialize" => json!({
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "sse-fixture", "version": "1.0.0"}
            }),
            "tools/list" => json!({"tools": [
                {"name": "pic", "description": "returns a picture", "inputSchema": {"type": "object", "properties": {}}, "annotations": {"readOnlyHint": true}},
                {"name": "plain", "inputSchema": {"type": "object"}, "annotations": {"openWorldHint": false}},
                {"name": "bare", "inputSchema": {"type": "object"}}
            ]}),
            "tools/call" => json!({"content": [
                {"type": "text", "text": "see"},
                {"type": "image", "data": "iVBORw==", "mimeType": "image/png"},
                {"type": "resource_link", "uri": "https://x/y.pdf", "name": "y.pdf", "mimeType": "application/pdf"}
            ]}),
            _ => {
                return Some(
                    json!({"jsonrpc": "2.0", "id": id, "error": {"code": -32601, "message": "nope"}}),
                );
            }
        };
        Some(json!({"jsonrpc": "2.0", "id": id, "result": result}))
    }

    pub async fn start() -> Fixture {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        let (out_tx, _) = broadcast::channel::<String>(64);
        let auth_seen: Arc<Mutex<Vec<String>>> = Arc::default();
        let auth = auth_seen.clone();
        tokio::spawn(async move {
            loop {
                let Ok((mut s, _)) = l.accept().await else {
                    return;
                };
                let out_tx = out_tx.clone();
                let auth = auth.clone();
                tokio::spawn(async move {
                    let Some((first, head, body)) = read_request(&mut s).await else {
                        return;
                    };
                    if let Some(a) = head
                        .lines()
                        .find(|l| l.to_ascii_lowercase().starts_with("authorization:"))
                    {
                        auth.lock().unwrap().push(a[14..].trim().to_owned());
                    }
                    if first.starts_with("GET /sse") {
                        let mut rx = out_tx.subscribe();
                        let _ = s
                            .write_all(b"HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ncache-control: no-cache\r\n\r\nevent: endpoint\ndata: /messages?sessionId=1\n\n")
                            .await;
                        while let Ok(m) = rx.recv().await {
                            let frame = format!("event: message\ndata: {m}\n\n");
                            if s.write_all(frame.as_bytes()).await.is_err() {
                                return;
                            }
                        }
                    } else if first.starts_with("POST /messages") {
                        let _ = s
                            .write_all(b"HTTP/1.1 202 Accepted\r\ncontent-length: 0\r\nconnection: close\r\n\r\n")
                            .await;
                        if let Ok(msg) = serde_json::from_str::<Value>(&body)
                            && let Some(r) = respond(&msg)
                        {
                            let _ = out_tx.send(r.to_string());
                        }
                    } else {
                        // the streamable-HTTP attempt: a legacy server refuses it
                        let _ = s
                            .write_all(b"HTTP/1.1 405 Method Not Allowed\r\ncontent-length: 0\r\nconnection: close\r\n\r\n")
                            .await;
                    }
                });
            }
        });
        Fixture {
            url: format!("http://{addr}/sse"),
            auth_seen,
        }
    }
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn remote_falls_back_to_legacy_sse_with_hints_and_parts() {
    // SAFETY: test-local env var.
    unsafe { std::env::set_var("TN_SSE_TEST_TOKEN", "fake-sse-token") };
    let fx = sse_fixture::start().await;
    let cfg = parse_mcp_config(&json!({"mcpServers": {"legacy": {
        "url": fx.url, "headers": {"Authorization": "Bearer ${TN_SSE_TEST_TOKEN}"}, "timeout": 5000
    }}}))
    .unwrap();
    let src = load_mcp(&cfg).await;
    assert_eq!(
        src.status["legacy"],
        McpStatus::Connected,
        "{:?}",
        src.errors
    );
    let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, ["legacy_pic", "legacy_plain", "legacy_bare"]);

    let pic = src.tools[0].hints.expect("annotated");
    assert!(pic.read_only && !pic.destructive && pic.open_world);
    let plain = src.tools[1].hints.expect("annotated");
    assert!(!plain.read_only && plain.destructive && !plain.open_world);
    assert!(src.tools[2].hints.is_none(), "no annotations ⇒ unknown");

    let r = src.tools[0].call(json!({}), ToolContext::default()).await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "see");
    assert_eq!(r.parts.len(), 2);
    assert_eq!(r.parts[0].kind, "image");
    assert_eq!(r.parts[1].url, "https://x/y.pdf");

    let auth = fx.auth_seen.lock().unwrap().clone();
    assert!(
        auth.iter().all(|a| a == "Bearer fake-sse-token") && auth.len() >= 3,
        "{auth:?}"
    );
    src.close().await;
}

#[cfg(feature = "mcp-http")]
#[tokio::test]
async fn remote_with_neither_transport_fails_naming_both() {
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = l.local_addr().unwrap();
    drop(l);
    let cfg = parse_mcp_config(
        &json!({"mcpServers": {"dead": {"url": format!("http://{addr}/mcp"), "timeout": 3000}}}),
    )
    .unwrap();
    let src = load_mcp(&cfg).await;
    assert_eq!(src.status["dead"], McpStatus::Failed);
    let e = &src.errors["dead"];
    assert!(
        e.contains("streamable-http failed") && e.contains("sse start failed"),
        "{e}"
    );
}
