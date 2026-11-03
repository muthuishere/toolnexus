//! SPEC §7G conformance: replays `../conformance/media/cases.json` and
//! `schemas.json` (read in place, never copied) against an in-process server.

mod common;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::{Req, Resp, oa_calls, oa_text, serve};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{
    BuiltinsConfig, Media, MediaError, MediaOptions, MediaStyle, ToolContext, ToolSource,
    ToolkitOptions, create_media, create_toolkit,
};

fn load(name: &str) -> Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../conformance/media")
        .join(name);
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

type Log = Arc<Mutex<Vec<Req>>>;

/// A fresh server answering `script` in order (last entry repeats).
async fn script_server(script: Vec<Value>) -> (String, Log) {
    let log: Log = Arc::default();
    let l2 = log.clone();
    let base: Arc<std::sync::OnceLock<String>> = Arc::default();
    let b2 = base.clone();
    let srv = serve(move |r: Req| {
        let log = l2.clone();
        let script = script.clone();
        let base = b2.clone();
        async move {
            let n = {
                let mut g = log.lock().unwrap();
                g.push(r);
                g.len() - 1
            };
            let e = &script[n.min(script.len() - 1)];
            let status = e["status"].as_u64().unwrap() as u16;
            let mut resp = match e.get("rawBody").and_then(Value::as_str) {
                Some(raw) => Resp::status(status, raw),
                None => {
                    let mut r = Resp::json(e["body"].clone());
                    r.status = status;
                    r
                }
            };
            if let Some(h) = e.get("headers").and_then(Value::as_object) {
                let url = base.get().cloned().unwrap_or_default();
                for (k, v) in h {
                    resp = resp.header(k, &v.as_str().unwrap().replace("{server}", &url));
                }
            }
            resp
        }
    })
    .await;
    base.set(srv.url.clone()).unwrap();
    (srv.url, log)
}

fn style_for(case: &Value, url: &str) -> MediaStyle {
    let mut s = case["style"].clone();
    let b = s["baseUrl"].as_str().unwrap().replace("{server}", url);
    s["baseUrl"] = json!(b);
    serde_json::from_value(s).unwrap()
}

fn progress_media(style: MediaStyle) -> (Media, Arc<Mutex<Vec<String>>>) {
    let prog: Arc<Mutex<Vec<String>>> = Arc::default();
    let p2 = prog.clone();
    let m = create_media(
        style,
        MediaOptions {
            on_progress: Some(Arc::new(move |p| p2.lock().unwrap().push(p.status.clone()))),
            ..Default::default()
        },
    );
    (m, prog)
}

#[tokio::test]
async fn conformance_cases() {
    let cases = load("cases.json");
    let cases = cases["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 34, "conformance case count");
    for case in cases {
        let name = case["name"].as_str().unwrap();
        if let Some(env) = case.get("env").and_then(Value::as_object) {
            for (k, v) in env {
                // SAFETY: the only test in this binary touching the env.
                unsafe { std::env::set_var(k, v.as_str().unwrap()) };
            }
        }
        let script: Vec<Value> = case["script"].as_array().unwrap().clone();
        let verb = case["verb"].as_str().unwrap();
        let args: Map<String, Value> = case["args"].as_object().unwrap().clone();

        // function face
        let (url, log) = script_server(script.clone()).await;
        let (media, prog) = progress_media(style_for(case, &url));
        let got = media.generate(verb, args.clone()).await;
        match (case.get("result"), case.get("error")) {
            (Some(want), _) => {
                let got = got.unwrap_or_else(|e| panic!("{name}: {e}"));
                assert_eq!(serde_json::to_value(&got).unwrap(), *want, "{name}");
            }
            (None, Some(want)) => {
                let e = got.expect_err(name);
                assert_eq!(e.to_string(), want.as_str().unwrap(), "{name}");
            }
            _ => panic!("{name}: no result/error"),
        }
        let reqs = log.lock().unwrap().clone();
        if let Some(want) = case.get("expectRequests").and_then(Value::as_array) {
            assert_eq!(reqs.len(), want.len(), "{name}: request count");
            for (r, w) in reqs.iter().zip(want) {
                assert_eq!(r.method, w["method"].as_str().unwrap(), "{name}");
                assert_eq!(r.path, w["path"].as_str().unwrap(), "{name}");
                if let Some(b) = w.get("body") {
                    assert_eq!(r.body, *b, "{name}: body");
                    assert_eq!(
                        r.headers.get("content-type").map(String::as_str),
                        Some("application/json"),
                        "{name}"
                    );
                } else {
                    assert!(r.raw.is_empty(), "{name}: poll has a body");
                    assert!(!r.headers.contains_key("content-type"), "{name}");
                }
            }
        }
        if let Some(h) = case.get("expectHeaders").and_then(Value::as_object) {
            assert!(!reqs.is_empty());
            for r in &reqs {
                for (k, v) in h {
                    assert_eq!(
                        r.headers.get(&k.to_ascii_lowercase()).map(String::as_str),
                        Some(v.as_str().unwrap()),
                        "{name}: header {k} on {}",
                        r.path
                    );
                }
            }
        }
        if let Some(want) = case.get("expectProgress").and_then(Value::as_array) {
            let want: Vec<String> = want.iter().map(|v| v.as_str().unwrap().into()).collect();
            assert_eq!(*prog.lock().unwrap(), want, "{name}: progress");
        }

        // tool face (fresh server, script restarts)
        let Some(want) = case.get("tool") else {
            continue;
        };
        let (url, _log) = script_server(script).await;
        let media = create_media(style_for(case, &url), MediaOptions::default());
        let tools = media.tools();
        let tool = tools
            .iter()
            .find(|t| t.name == want["name"].as_str().unwrap())
            .unwrap_or_else(|| panic!("{name}: no tool"));
        assert_eq!(tool.source, ToolSource::Media);
        let r = tool.call(Value::Object(args), ToolContext::default()).await;
        let mut want = want.clone();
        want.as_object_mut().unwrap().remove("name");
        assert_eq!(serde_json::to_value(&r).unwrap(), want, "{name}: tool");
    }
}

#[tokio::test]
async fn conformance_schemas() {
    let f = load("schemas.json");
    let style: MediaStyle = serde_json::from_value(f["style"].clone()).unwrap();
    let tools = create_media(style, MediaOptions::default()).tools();
    let want = f["tools"].as_array().unwrap();
    assert_eq!(tools.len(), want.len());
    for (t, w) in tools.iter().zip(want) {
        assert_eq!(t.name, w["name"].as_str().unwrap());
        assert_eq!(t.description, w["description"].as_str().unwrap());
        assert_eq!(serde_json::to_value(t.source).unwrap(), w["source"]);
        assert_eq!(t.input_schema, w["inputSchema"], "{}", t.name);
    }
}

fn slow_style(url: &str) -> MediaStyle {
    serde_json::from_value(json!({"baseUrl": url, "verbs": {"image": {
        "mode": "async", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
        "idPath": "id", "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"],
        "failed": [], "intervalMs": 5, "timeoutMs": 60000}, "resultPath": "u"}}}))
    .unwrap()
}

#[tokio::test]
async fn cancel_stops_polling() {
    let (url, log) = script_server(vec![
        json!({"status": 200, "body": {"id": "c1"}}),
        json!({"status": 200, "body": {"st": "running"}}),
    ])
    .await;
    let media = create_media(slow_style(&url), MediaOptions::default());
    let tok = CancellationToken::new();
    let t2 = tok.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(60)).await;
        t2.cancel();
    });
    let mut args = Map::new();
    args.insert("prompt".into(), json!("x"));
    let e = media
        .generate_with("image", args.clone(), None, &tok)
        .await
        .unwrap_err();
    assert_eq!(e, MediaError::Cancelled);
    // A poll already in flight at cancel may still land: settle, then baseline.
    tokio::time::sleep(Duration::from_millis(50)).await;
    let n = log.lock().unwrap().len();
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert!(
        log.lock().unwrap().len() <= n + 1,
        "polling continued after cancel"
    );

    // tool face: isError with the cancellation message
    let ctx = ToolContext::default();
    ctx.cancel.cancel();
    let r = media.tools()[0].call(Value::Object(args), ctx).await;
    assert!(r.is_error);
    assert_eq!(r.output, MediaError::Cancelled.to_string());
}

#[tokio::test]
async fn typed_verb_and_provider_options() {
    let (url, log) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/a.gif"}}),
    ])
    .await;
    let style: MediaStyle = serde_json::from_value(json!({"baseUrl": url, "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"},
        "args": {"prompt": "p", "seed": "opts.seed"}, "resultPath": "u"}}}))
    .unwrap();
    let media = Media::new(style, MediaOptions::default());
    let mut po = Map::new();
    po.insert("opts".into(), json!("overwritten"));
    let r = media
        .image(toolnexus::ImageInput {
            prompt: "cat".into(),
            seed: Some(3),
            provider_options: Some(po),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(r.files[0].mime_type, "image/gif");
    assert_eq!(
        log.lock().unwrap()[0].body,
        json!({"p": "cat", "opts": "overwritten"})
    );
}

#[tokio::test]
async fn run_result_files_and_parts() {
    // the media provider
    let (murl, _) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/a.png"}}),
    ])
    .await;
    let style: MediaStyle = serde_json::from_value(json!({"baseUrl": murl, "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"},
        "args": {"prompt": "p"}, "resultPath": "u"}}}))
    .unwrap();
    let media = create_media(style, MediaOptions::default());
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        media: Some(media),
        ..Default::default()
    })
    .await
    .unwrap();
    assert_eq!(
        tk.tools()
            .iter()
            .map(|t| t.name.clone())
            .collect::<Vec<_>>(),
        vec!["generate_image".to_string()]
    );

    // scripted LLM: one tool call, then a final answer
    let turn = Arc::new(Mutex::new(0usize));
    let llm = serve(move |_r: Req| {
        let turn = turn.clone();
        async move {
            let mut t = turn.lock().unwrap();
            *t += 1;
            if *t == 1 {
                Resp::json(oa_calls(&[(
                    "call_7",
                    "generate_image",
                    r#"{"prompt":"cat"}"#,
                )]))
            } else {
                Resp::json(oa_text("here it is"))
            }
        }
    })
    .await;
    let client = create_client(ClientOptions {
        base_url: llm.url,
        style: Style::OpenAI,
        model: "m".into(),
        api_key: "YOUR_KEY_HERE".into(),
        ..Default::default()
    });
    let r = client.run("draw", &tk).await.unwrap();
    assert_eq!(r.text, "here it is");
    let v = serde_json::to_value(&r).unwrap();
    assert_eq!(
        v["files"],
        json!([{"type": "image", "mimeType": "image/png", "url": "https://x/a.png",
                "from": "generate_image", "callId": "call_7"}])
    );
    assert_eq!(
        v["toolCalls"][0]["parts"],
        json!([{"type": "image", "mimeType": "image/png", "url": "https://x/a.png"}])
    );
}

#[tokio::test]
async fn run_without_media_has_empty_files() {
    let llm = serve(|_r: Req| async { Resp::json(oa_text("hi")) }).await;
    let client = create_client(ClientOptions {
        base_url: llm.url,
        style: Style::OpenAI,
        model: "m".into(),
        api_key: "YOUR_KEY_HERE".into(),
        ..Default::default()
    });
    let tk = common::empty_toolkit().await;
    let r = client.run("x", &tk).await.unwrap();
    let v = serde_json::to_value(&r).unwrap();
    assert_eq!(v["files"], json!([]));
}

#[test]
fn embedded_verb_table_matches_verbs_json() {
    let mut want = load("verbs.json");
    want.as_object_mut().unwrap().remove("_doc");
    // arrays compare in order, so verb and arg order are asserted too
    assert_eq!(toolnexus::media::canonical_verbs(), want);
}

#[tokio::test]
async fn media_tool_wins_over_extra_tool_of_same_name() {
    let (murl, _) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/a.png"}}),
    ])
    .await;
    let style: MediaStyle = serde_json::from_value(json!({"baseUrl": murl, "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"},
        "args": {"prompt": "p"}, "resultPath": "u"}}}))
    .unwrap();
    let extra = toolnexus::define_tool("generate_image", "impostor", None, |_a, _c| async {
        Ok::<_, String>("extra".to_string())
    });
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        media: Some(create_media(style, MediaOptions::default())),
        extra_tools: vec![extra],
        ..Default::default()
    })
    .await
    .unwrap();
    let tools = tk.tools();
    assert_eq!(tools.len(), 1);
    assert_eq!(tools[0].source, ToolSource::Media);
}

// Media tools are concurrency-safe (SPEC §7G, §8 Tool scheduling): each call is an independent remote job.
#[test]
fn media_tools_are_concurrency_safe() {
    let style: MediaStyle = serde_json::from_str("{\"baseUrl\":\"http://unused\",\"verbs\":{\"image\":{\"mode\":\"sync\",\"kind\":\"image\",\"start\":{\"path\":\"/i\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"},\"video\":{\"mode\":\"sync\",\"kind\":\"video\",\"start\":{\"path\":\"/v\"},\"args\":{\"prompt\":\"p\"},\"resultPath\":\"u\"},\"speech\":{\"mode\":\"sync\",\"kind\":\"audio\",\"start\":{\"path\":\"/s\"},\"args\":{\"text\":\"t\"},\"resultPath\":\"u\"},\"transcribe\":{\"mode\":\"sync\",\"kind\":\"text\",\"start\":{\"path\":\"/r\"},\"args\":{\"audio\":\"a\"},\"resultPath\":\"u\"},\"cloneVoice\":{\"mode\":\"sync\",\"kind\":\"text\",\"start\":{\"path\":\"/c\"},\"args\":{\"name\":\"n\"},\"resultPath\":\"id\"},\"talking\":{\"mode\":\"sync\",\"kind\":\"video\",\"start\":{\"path\":\"/t\"},\"args\":{\"image\":\"i\"},\"resultPath\":\"u\"}}}").unwrap();
    let tools = create_media(style, MediaOptions::default()).tools();
    assert_eq!(tools.len(), 6);
    for t in &tools {
        assert!(toolnexus::is_concurrency_safe(t, &json!({})), "{}", t.name);
    }
}

// §7G: style headers never reach a different origin than baseUrl's.
#[tokio::test]
async fn cross_origin_redirect_drops_style_headers() {
    let (burl, blog) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/a.png"}}),
    ])
    .await;
    let target = format!("{burl}/landed");
    let a = serve(move |r: Req| {
        let t = target.clone();
        async move {
            if r.path == "/same" {
                Resp::status(307, "").header("Location", "/i")
            } else if r.path == "/i" {
                Resp::status(302, "").header("Location", &t)
            } else {
                Resp::status(500, "unexpected")
            }
        }
    })
    .await;
    let style: MediaStyle = serde_json::from_value(json!({"baseUrl": a.url,
        "headers": {"Authorization": "Bearer YOUR_KEY_HERE", "X-Style": "s"},
        "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/same"},
        "args": {"prompt": "p"}, "resultPath": "u"}}}))
    .unwrap();
    let mut args = Map::new();
    args.insert("prompt".into(), json!("x"));
    let r = create_media(style, MediaOptions::default())
        .generate("image", args)
        .await
        .unwrap();
    assert_eq!(r.files[0].url, "https://x/a.png");
    let reqs = blog.lock().unwrap().clone();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].path, "/landed");
    assert!(!reqs[0].headers.contains_key("authorization"));
    assert!(!reqs[0].headers.contains_key("x-style"));
}

// §7G: each request is bounded by the time left until the job deadline.
#[tokio::test]
async fn hung_server_bounded_by_deadline() {
    let hang = serve(|_r: Req| async {
        tokio::time::sleep(Duration::from_secs(30)).await;
        Resp::status(200, "{}")
    })
    .await;
    let style: MediaStyle =
        serde_json::from_value(json!({"baseUrl": hang.url, "verbs": {"image": {
        "mode": "async", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
        "idPath": "id", "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"],
        "intervalMs": 5, "timeoutMs": 1000}, "resultPath": "u"}}}))
        .unwrap();
    let mut args = Map::new();
    args.insert("prompt".into(), json!("x"));
    let t0 = std::time::Instant::now();
    let e = create_media(style, MediaOptions::default())
        .generate("image", args.clone())
        .await
        .unwrap_err();
    assert_eq!(e.to_string(), "image: timed out after 1000ms");
    assert!(t0.elapsed() < Duration::from_secs(5));

    // a hung poll reports the job id
    let n = Arc::new(Mutex::new(0usize));
    let poll_hang = serve(move |_r: Req| {
        let n = n.clone();
        async move {
            let first = {
                let mut g = n.lock().unwrap();
                *g += 1;
                *g == 1
            };
            if !first {
                tokio::time::sleep(Duration::from_secs(30)).await;
            }
            Resp::json(json!({"id": "a/b"}))
        }
    })
    .await;
    let style: MediaStyle =
        serde_json::from_value(json!({"baseUrl": poll_hang.url, "verbs": {"image": {
        "mode": "async", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
        "idPath": "id", "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"],
        "intervalMs": 5, "timeoutMs": 1000}, "resultPath": "u"}}}))
        .unwrap();
    let t0 = std::time::Instant::now();
    let e = create_media(style, MediaOptions::default())
        .generate("image", args)
        .await
        .unwrap_err();
    assert_eq!(e.to_string(), "job a/b timed out after 1000ms");
    assert!(t0.elapsed() < Duration::from_secs(5));
}

#[tokio::test]
async fn typed_verb_with_cancel() {
    let (url, _log) = script_server(vec![
        json!({"status": 200, "body": {"id": "c1"}}),
        json!({"status": 200, "body": {"st": "running"}}),
    ])
    .await;
    let media = create_media(slow_style(&url), MediaOptions::default());
    let tok = CancellationToken::new();
    tok.cancel();
    let e = media
        .image_with(
            toolnexus::ImageInput {
                prompt: "x".into(),
                ..Default::default()
            },
            &tok,
        )
        .await
        .unwrap_err();
    assert_eq!(e, MediaError::Cancelled);
}
