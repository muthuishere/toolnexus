//! SPEC §7G conformance: replays `../conformance/media/cases.json` (style
//! engine), `schemas.json` and `routing.json` (read in place, never copied)
//! against an in-process server, plus the embedded-copy and loop checks.

mod common;
mod media_support;

use std::sync::{Arc, Mutex};
use std::time::Duration;

use common::{Req, Resp, oa_calls, oa_text, serve};
use media_support::*;
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{
    BuiltinsConfig, IntoMediaClient, MediaArg, MediaArgs, MediaClient, MediaClientConfig, MediaCtx,
    MediaError, MediaFuture, MediaResult, MediaStyle, ToolContext, ToolSource, ToolkitOptions,
    create_media, create_toolkit, style_media_client,
};

fn style_of(case: &Value, url: &str) -> MediaStyle {
    serde_json::from_value(subst(&case["style"], &[("{server}", url)])).unwrap()
}

#[tokio::test]
async fn conformance_cases() {
    let cases = load("cases.json");
    let cases = cases["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 49, "cases.json case count");
    let mut tool_faces = 0;
    for case in cases {
        let name = case["name"].as_str().unwrap();
        set_env(case, &[]);
        let script: Vec<Value> = case["script"].as_array().unwrap().clone();
        let verb = case["verb"].as_str().unwrap();
        let args: Map<String, Value> = case["args"].as_object().unwrap().clone();

        // function face
        let dir = temp_dir("cases");
        let (url, log) = script_server(script.clone()).await;
        let (cfg, prog) = config(case.get("config"));
        let client = match style_media_client(style_of(case, &url), cfg) {
            Ok(c) => c,
            Err(e) => {
                assert_eq!(case.get("styleError"), Some(&json!(true)), "{name}: {e}");
                assert_eq!(e.to_string(), case["error"].as_str().unwrap(), "{name}");
                assert!(log.lock().unwrap().is_empty());
                clear_env(case);
                continue;
            }
        };
        assert!(case.get("styleError").is_none(), "{name}: style loaded");
        let media = create_media([client]).unwrap();
        let got = media.generate(verb, args.clone()).await;
        check_outcome(name, case, &got, "style", verb, &dir);
        check_requests(name, case, &log.lock().unwrap());
        check_header_patterns(name, case, &log.lock().unwrap());
        check_progress(name, case, &prog);

        // tool face (fresh server, script restarts)
        if let Some(want) = case.get("tool") {
            tool_faces += 1;
            let (url, _log) = script_server(script).await;
            let (cfg, _) = config(case.get("config"));
            let media =
                create_media([style_media_client(style_of(case, &url), cfg).unwrap()]).unwrap();
            let tools = media.tools();
            let tname = tool_name(want, verb);
            let tool = tools
                .iter()
                .find(|t| t.name == tname)
                .unwrap_or_else(|| panic!("{name}: no tool {tname}"));
            assert_eq!(tool.source, ToolSource::Media);
            let r = tool.call(Value::Object(args), ToolContext::default()).await;
            check_tool(name, want, &r, &dir);
        }
        clear_env(case);
    }
    assert_eq!(tool_faces, 45, "cases.json tool-face count");
}

#[tokio::test]
async fn conformance_schemas() {
    let f = load("schemas.json");
    let style: MediaStyle = serde_json::from_value(f["style"].clone()).unwrap();
    let client = style_media_client(style, MediaClientConfig::default()).unwrap();
    let tools = create_media([client]).unwrap().tools();
    let want = f["tools"].as_array().unwrap();
    assert_eq!(want.len(), 4);
    assert_eq!(tools.len(), want.len());
    for (t, w) in tools.iter().zip(want) {
        assert_eq!(t.name, w["name"].as_str().unwrap());
        assert_eq!(t.description, w["description"].as_str().unwrap());
        assert_eq!(serde_json::to_value(t.source).unwrap(), w["source"]);
        assert_eq!(t.input_schema, w["inputSchema"], "{}", t.name);
    }
}

// ---------------------------------------------------------------------------
// routing.json — test-only custom clients
// ---------------------------------------------------------------------------

type Calls = Arc<Mutex<Vec<Value>>>;

struct TestClient {
    name: String,
    verbs: Map<String, Value>,
    calls: Calls,
}

impl TestClient {
    fn from(v: &Value, calls: &Calls) -> Self {
        TestClient {
            name: v["name"].as_str().unwrap().into(),
            verbs: v["verbs"].as_object().unwrap().clone(),
            calls: calls.clone(),
        }
    }
    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        _ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        self.calls
            .lock()
            .unwrap()
            .push(json!({"client": self.name, "verb": verb, "args": args}));
        let out = &self.verbs[verb]["outcome"];
        if let Some(r) = out.get("result") {
            return Ok(serde_json::from_value(r.clone()).unwrap());
        }
        let msg = out["error"].as_str().unwrap().to_string();
        Err(match out["status"].as_str().unwrap() {
            "unavailable" => MediaError::unavailable(msg),
            "timeout" => MediaError::Timeout {
                message: msg,
                job_id: None,
            },
            _ => MediaError::failed(msg),
        })
    }
}

macro_rules! test_verb {
    ($f:ident, $verb:literal) => {
        fn $f<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
            Box::pin(self.run($verb, args, ctx))
        }
    };
}

impl MediaClient for TestClient {
    fn name(&self) -> &str {
        &self.name
    }
    fn supports(&self, verb: &str) -> bool {
        self.verbs.contains_key(verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        let a = self.verbs.get(verb)?.get("args")?.as_array()?;
        Some(
            a.iter()
                .map(|x| {
                    MediaArg::new(
                        x["name"].as_str().unwrap(),
                        x["required"].as_bool().unwrap_or(false),
                    )
                })
                .collect(),
        )
    }
    test_verb!(image, "image");
    test_verb!(video, "video");
    test_verb!(speech, "speech");
    test_verb!(music, "music");
    test_verb!(transcribe, "transcribe");
    test_verb!(clone_voice, "cloneVoice");
    test_verb!(talk, "talk");
}

fn test_clients(v: &Value, calls: &Calls) -> Vec<Arc<dyn MediaClient>> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|c| TestClient::from(c, calls).into_media_client())
        .collect()
}

#[tokio::test]
async fn conformance_routing() {
    let f = load("routing.json");
    let cases = f["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 19, "routing.json case count");
    let dir = temp_dir("routing");
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let verb = case["verb"].as_str().unwrap();
        let args = case["args"].as_object().unwrap().clone();
        let calls: Calls = Arc::default();
        let media = create_media(test_clients(&case["clients"], &calls)).unwrap();
        let got = media.generate(verb, args.clone()).await;
        if let Some(want) = case.get("expectCalls") {
            assert_eq!(
                Value::Array(calls.lock().unwrap().clone()),
                *want,
                "{name}: calls"
            );
        }
        check_outcome(name, case, &got, "", verb, &dir);

        if let Some(want) = case.get("tool") {
            let calls: Calls = Arc::default();
            let media = create_media(test_clients(&case["clients"], &calls)).unwrap();
            let tname = tool_name(want, verb);
            let tool = media
                .tools()
                .into_iter()
                .find(|t| t.name == tname)
                .unwrap_or_else(|| panic!("{name}: no tool"));
            let r = tool.call(Value::Object(args), ToolContext::default()).await;
            check_tool(name, want, &r, &dir);
        }
    }

    let sc = f["schemaCases"].as_array().unwrap();
    assert_eq!(sc.len(), 1);
    for case in sc {
        let calls: Calls = Arc::default();
        let tools = create_media(test_clients(&case["clients"], &calls))
            .unwrap()
            .tools();
        let want = case["tools"].as_array().unwrap();
        assert_eq!(tools.len(), want.len());
        for (t, w) in tools.iter().zip(want) {
            assert_eq!(t.name, w["name"].as_str().unwrap());
            assert_eq!(t.description, w["description"].as_str().unwrap());
            assert_eq!(serde_json::to_value(t.source).unwrap(), w["source"]);
            assert_eq!(t.input_schema, w["inputSchema"], "{}", t.name);
        }
    }

    let none: Vec<Arc<dyn MediaClient>> = Vec::new();
    let e = create_media(none).unwrap_err();
    assert_eq!(e.to_string(), f["emptyClients"]["error"].as_str().unwrap());
}

// ---------------------------------------------------------------------------
// embedded copies
// ---------------------------------------------------------------------------

#[test]
fn embedded_verbs_json_is_verbatim() {
    let shared = std::fs::read_to_string(conformance("verbs.json")).unwrap();
    assert_eq!(toolnexus::media::verbs_json(), shared);
}

#[test]
fn embedded_a2e_json_is_verbatim() {
    let p =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/media-styles/a2e.json");
    let shared = std::fs::read_to_string(p).unwrap();
    assert_eq!(toolnexus::media::a2e_style_json(), shared);
}

// ---------------------------------------------------------------------------
// loop, cancellation, tools
// ---------------------------------------------------------------------------

fn style_client(v: Value) -> toolnexus::StyleMediaClient {
    style_media_client(
        serde_json::from_value(v).unwrap(),
        MediaClientConfig::default(),
    )
    .unwrap()
}

fn slow_style(url: &str) -> toolnexus::StyleMediaClient {
    style_client(json!({"baseUrl": url, "verbs": {"image": {
        "mode": "async", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
        "idPath": "id", "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"],
        "failed": [], "intervalMs": 5, "timeoutMs": 60000}, "resultPath": "u"}}}))
}

fn prompt(p: &str) -> MediaArgs {
    let mut a = Map::new();
    a.insert("prompt".into(), json!(p));
    a
}

#[tokio::test]
async fn cancel_stops_polling() {
    let (url, log) = script_server(vec![
        json!({"status": 200, "body": {"id": "c1"}}),
        json!({"status": 200, "body": {"st": "running"}}),
    ])
    .await;
    let media = create_media([slow_style(&url)]).unwrap();
    let tok = CancellationToken::new();
    let t2 = tok.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(60)).await;
        t2.cancel();
    });
    let e = media
        .generate_with("image", prompt("x"), None, &tok)
        .await
        .unwrap_err();
    assert_eq!(e, MediaError::Cancelled);
    assert_eq!(e.status(), "cancelled");
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
    let r = media.tools()[0].call(Value::Object(prompt("x")), ctx).await;
    assert!(r.is_error);
    assert_eq!(r.output, MediaError::Cancelled.to_string());
}

#[tokio::test]
async fn typed_verb_and_provider_options() {
    let (url, log) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/a.gif"}}),
    ])
    .await;
    let media = create_media([style_client(json!({"baseUrl": url, "verbs": {"image": {
        "mode": "sync", "kind": "image", "start": {"path": "/i"},
        "args": {"prompt": "p", "seed": "opts.seed"}, "resultPath": "u"}}}))])
    .unwrap();
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
async fn typed_talk_and_music_verbs() {
    let (url, log) = script_server(vec![
        json!({"status": 200, "body": {"u": "https://x/v.mp4"}}),
    ])
    .await;
    let media = create_media([style_client(json!({"baseUrl": url, "verbs": {
        "talk": {"mode": "sync", "kind": "video", "start": {"path": "/t"},
                 "args": {"image": "i", "text": "t"}, "resultPath": "u"},
        "music": {"mode": "sync", "kind": "audio", "start": {"path": "/m"},
                  "args": {"prompt": "p", "instrumental": "inst"}, "resultPath": "u"}}}))])
    .unwrap();
    let r = media
        .talk(toolnexus::TalkInput {
            image: "https://x/f.png".into(),
            text: Some("hi".into()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(r.files[0].kind, "file");
    media
        .music(toolnexus::MusicInput {
            prompt: "lofi".into(),
            instrumental: Some(true),
            ..Default::default()
        })
        .await
        .unwrap();
    let reqs = log.lock().unwrap();
    assert_eq!(reqs[0].body, json!({"i": "https://x/f.png", "t": "hi"}));
    assert_eq!(reqs[1].body, json!({"p": "lofi", "inst": true}));
}

/// A custom client returning one image and one audio file inline.
struct TwoFiles;

impl TwoFiles {
    async fn run(
        &self,
        _verb: &str,
        _args: MediaArgs,
        _ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        Ok(MediaResult {
            files: vec![
                toolnexus::MediaFile {
                    kind: "image".into(),
                    mime_type: "image/png".into(),
                    data: "SU1BR0VCWVRFUw==".into(),
                    ..Default::default()
                },
                toolnexus::MediaFile {
                    kind: "audio".into(),
                    mime_type: "audio/mpeg".into(),
                    data: "QVVESU9CWVRFUw==".into(),
                    ..Default::default()
                },
            ],
            status: "completed".into(),
            ..Default::default()
        })
    }
}

impl MediaClient for TwoFiles {
    fn name(&self) -> &str {
        "two"
    }
    fn supports(&self, verb: &str) -> bool {
        verb == "image"
    }
    fn image<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        Box::pin(self.run("image", args, ctx))
    }
}

#[tokio::test]
async fn run_result_files_and_parts_and_model_visibility() {
    let media = create_media([TwoFiles]).unwrap();
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
    let seen: Arc<Mutex<Vec<String>>> = Arc::default();
    let s2 = seen.clone();
    let llm = serve(move |r: Req| {
        let seen = s2.clone();
        async move {
            let n = {
                let mut g = seen.lock().unwrap();
                g.push(r.raw.clone());
                g.len()
            };
            if n == 1 {
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
    // RunResult.files and toolCalls[].parts keep every file …
    assert_eq!(
        v["files"],
        json!([
            {"type": "image", "mimeType": "image/png", "data": "SU1BR0VCWVRFUw==",
             "from": "generate_image", "callId": "call_7"},
            {"type": "audio", "mimeType": "audio/mpeg", "data": "QVVESU9CWVRFUw==",
             "from": "generate_image", "callId": "call_7"}
        ])
    );
    assert_eq!(v["toolCalls"][0]["parts"].as_array().unwrap().len(), 2);
    assert_eq!(v["toolCalls"][0]["metadata"]["client"], "two");
    // … but only the image reaches the model (§7G.6)
    let second = seen.lock().unwrap()[1].clone();
    assert!(second.contains("SU1BR0VCWVRFUw=="), "image part not sent");
    assert!(
        !second.contains("QVVESU9CWVRFUw=="),
        "audio part leaked to the model"
    );
}

/// D9 (§7G.12): a media tool result with no image part (modelParts `[]`)
/// sends the plain `output` string as the tool-result content in every
/// message style — never an empty content array, never a placeholder.
#[tokio::test]
async fn media_result_without_image_parts_sends_plain_output_every_style() {
    let calls: Calls = Arc::default();
    let client_def = json!([{"name": "c", "verbs": {"talk": {"outcome": {"result": {
        "files": [{"type": "file", "mimeType": "video/mp4", "url": "https://x/c.mp4"}],
        "status": "completed"}}}}}]);
    for style in [Style::OpenAI, Style::Anthropic] {
        let media = create_media(test_clients(&client_def, &calls)).unwrap();
        let tk = create_toolkit(ToolkitOptions {
            builtins: Some(BuiltinsConfig::off()),
            media: Some(media),
            ..Default::default()
        })
        .await
        .unwrap();
        let wire: Arc<Mutex<Vec<Value>>> = Arc::default();
        let w2 = wire.clone();
        let llm = serve(move |r: Req| {
            let w = w2.clone();
            async move {
                let anthropic = r.path.contains("messages");
                if r.messages().len() > 1 {
                    *w.lock().unwrap() = r.messages();
                    if anthropic {
                        return Resp::json(json!({"content": [{"type": "text", "text": "ok"}]}));
                    }
                    return Resp::json(oa_text("ok"));
                }
                let args = json!({"image": "https://x/f.png"});
                if anthropic {
                    return Resp::json(json!({"content": [{"type": "tool_use", "id": "t1",
                        "name": "talking_video", "input": args}]}));
                }
                Resp::json(oa_calls(&[("t1", "talking_video", &args.to_string())]))
            }
        })
        .await;
        let r = create_client(ClientOptions {
            base_url: llm.url,
            style,
            model: "m".into(),
            api_key: "YOUR_KEY_HERE".into(),
            ..Default::default()
        })
        .run("animate", &tk)
        .await
        .unwrap();
        assert_eq!(r.text, "ok", "{style:?}");
        let w = wire.lock().unwrap().clone();
        let output = "video ready: https://x/c.mp4";
        match style {
            Style::OpenAI => {
                assert_eq!(
                    w[2],
                    json!({"role": "tool", "tool_call_id": "t1", "content": output})
                );
                assert_eq!(w.len(), 3, "no relocated user message: {w:?}");
            }
            Style::Anthropic => {
                let tr = &w[2]["content"][0];
                assert_eq!(tr["type"], "tool_result");
                assert_eq!(tr["content"], json!(output), "{tr}");
            }
        }
    }
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

#[tokio::test]
async fn media_tool_wins_over_extra_tool_of_same_name() {
    let extra = toolnexus::define_tool("generate_image", "impostor", None, |_a, _c| async {
        Ok::<_, String>("extra".to_string())
    });
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        media: Some(create_media([TwoFiles]).unwrap()),
        extra_tools: vec![extra],
        ..Default::default()
    })
    .await
    .unwrap();
    let tools = tk.tools();
    assert_eq!(tools.len(), 1);
    assert_eq!(tools[0].source, ToolSource::Media);
}

// Media tools are concurrency-safe (SPEC §7G.6, §8 Tool scheduling).
#[test]
fn media_tools_are_concurrency_safe() {
    let mut verbs = Map::new();
    for (v, k) in [
        ("image", "image"),
        ("video", "video"),
        ("speech", "audio"),
        ("music", "audio"),
        ("transcribe", "text"),
        ("cloneVoice", "text"),
        ("talk", "video"),
    ] {
        verbs.insert(
            v.into(),
            json!({"mode": "sync", "kind": k, "start": {"path": "/x"}, "args": {}, "resultPath": "u"}),
        );
    }
    let tools = create_media([style_client(
        json!({"baseUrl": "http://unused", "verbs": verbs}),
    )])
    .unwrap()
    .tools();
    assert_eq!(tools.len(), 7);
    for t in &tools {
        assert!(toolnexus::is_concurrency_safe(t, &json!({})), "{}", t.name);
    }
}

// §7G.8: style headers never reach a different origin than baseUrl's.
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
    let client = style_client(json!({"baseUrl": a.url,
        "headers": {"Authorization": "Bearer YOUR_KEY_HERE", "X-Style": "s"},
        "verbs": {"image": {"mode": "sync", "kind": "image", "start": {"path": "/same"},
        "args": {"prompt": "p"}, "resultPath": "u"}}}));
    let r = create_media([client])
        .unwrap()
        .generate("image", prompt("x"))
        .await
        .unwrap();
    assert_eq!(r.files[0].url, "https://x/a.png");
    let reqs = blog.lock().unwrap().clone();
    assert_eq!(reqs.len(), 1);
    assert_eq!(reqs[0].path, "/landed");
    assert!(!reqs[0].headers.contains_key("authorization"));
    assert!(!reqs[0].headers.contains_key("x-style"));
}

// §7G.8: each request is bounded by the time left until the job deadline.
#[tokio::test]
async fn hung_server_bounded_by_deadline() {
    let hang = serve(|_r: Req| async {
        tokio::time::sleep(Duration::from_secs(30)).await;
        Resp::status(200, "{}")
    })
    .await;
    let mk = |url: &str| {
        style_client(json!({"baseUrl": url, "verbs": {"image": {
        "mode": "async", "kind": "image", "start": {"path": "/s"}, "args": {"prompt": "p"},
        "idPath": "id", "poll": {"path": "/p/{id}", "statusPath": "st", "done": ["ok"],
        "intervalMs": 5, "timeoutMs": 1000}, "resultPath": "u"}}}))
    };
    let t0 = std::time::Instant::now();
    let e = create_media([mk(&hang.url)])
        .unwrap()
        .generate("image", prompt("x"))
        .await
        .unwrap_err();
    assert_eq!(e.to_string(), "image: timed out after 1000ms");
    assert_eq!(e.status(), "timeout");
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
    let t0 = std::time::Instant::now();
    let e = create_media([mk(&poll_hang.url)])
        .unwrap()
        .generate("image", prompt("x"))
        .await
        .unwrap_err();
    assert_eq!(e.to_string(), "job a/b timed out after 1000ms");
    assert_eq!(e.job_id(), Some("a/b"));
    assert!(t0.elapsed() < Duration::from_secs(5));
}

#[tokio::test]
async fn typed_verb_with_cancel() {
    let (url, _log) = script_server(vec![
        json!({"status": 200, "body": {"id": "c1"}}),
        json!({"status": 200, "body": {"st": "running"}}),
    ])
    .await;
    let media = create_media([slow_style(&url)]).unwrap();
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

#[test]
fn load_media_style_rejects_old_talking_verb() {
    let d = temp_dir("style");
    let p = d.join("s.json");
    std::fs::write(
        &p,
        r#"{"baseUrl":"http://x","verbs":{"talking":{"mode":"sync","kind":"video","start":{"path":"/t"},"args":{},"resultPath":"u"}}}"#,
    )
    .unwrap();
    let e = toolnexus::load_media_style(&p).unwrap_err();
    assert_eq!(e.to_string(), "style: unknown verb \"talking\"");
}

#[test]
fn a2e_preset_loads_through_load_media_style() {
    let p =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/media-styles/a2e.json");
    let s = toolnexus::load_media_style(p).unwrap();
    assert_eq!(s.name.as_deref(), Some("a2e"));
    assert!(s.verbs.contains_key("talk"));
}
