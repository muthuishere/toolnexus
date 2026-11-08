//! Integration suite against the shared mock model server (tools/mock-llm).
//! Starts `python3 tools/mock-llm/mock_llm.py --port 0 --quiet` per test, or
//! uses `TN_MOCK_LLM_URL` when set. Skips (passes with a message) when python3
//! is not available. No network beyond loopback, no key.

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;

use serde_json::{Value, json};
use toolnexus::classifier::{
    ChoiceQuestion, ClassifierBackend, ClassifierSupports, NoulQuestion, ScoreQuestion,
};
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{
    BuiltinsConfig, ClassifierOptions, CloneVoiceInput, ContentPart, ImageInput, MediaOptions,
    Questions, SpeechInput, TalkingInput, Tool, Toolkit, ToolkitOptions, TranscribeInput,
    VideoInput, create_classifier, create_media, create_toolkit, define_tool, load_media_style,
};

fn repo() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..")
}

/// The mock kit directory; `TN_MOCK_KIT_DIR` points the suite at a copy.
fn kit(file: &str) -> PathBuf {
    std::env::var("TN_MOCK_KIT_DIR")
        .ok()
        .filter(|d| !d.is_empty())
        .map_or_else(|| repo().join("tools/mock-llm"), PathBuf::from)
        .join(file)
}

/// The mock's state (faults, counts) is global, so tests run one at a time.
fn lock() -> &'static tokio::sync::Mutex<()> {
    static L: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    L.get_or_init(|| tokio::sync::Mutex::new(()))
}

struct Mock {
    base: String,
    child: Option<Child>,
}

impl Drop for Mock {
    fn drop(&mut self) {
        if let Some(c) = self.child.as_mut() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

impl Mock {
    fn origin(&self) -> &str {
        self.base.trim_end_matches("/v1")
    }
}

fn start_mock() -> Option<Mock> {
    if let Ok(u) = std::env::var("TN_MOCK_LLM_URL")
        && !u.is_empty()
    {
        return Some(Mock {
            base: u.trim_end_matches('/').to_string(),
            child: None,
        });
    }
    let script = kit("mock_llm.py");
    let mut child = match Command::new("python3")
        .arg(&script)
        .args(["--port", "0", "--quiet"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("SKIP mock_llm suite: python3 not available ({e})");
            return None;
        }
    };
    let mut line = String::new();
    BufReader::new(child.stdout.take().unwrap())
        .read_line(&mut line)
        .unwrap();
    let base = line
        .trim()
        .rsplit(' ')
        .next()
        .filter(|u| u.starts_with("http"))
        .unwrap_or_else(|| panic!("unexpected mock banner: {line:?}"))
        .to_string();
    Some(Mock {
        base,
        child: Some(child),
    })
}

macro_rules! mock_or_skip {
    () => {{
        match start_mock() {
            Some(m) => m,
            None => return,
        }
    }};
}

fn add_tool() -> Tool {
    define_tool(
        "add",
        "Add two integers.",
        Some(json!({"type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"]})),
        |a: Value, _| async move {
            let s = a["a"].as_i64().unwrap_or(0) + a["b"].as_i64().unwrap_or(0);
            Ok::<_, String>(s.to_string())
        },
    )
}

async fn toolkit(tools: Vec<Tool>) -> Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap()
}

fn opts(m: &Mock, style: Style) -> ClientOptions {
    ClientOptions {
        base_url: m.base.clone(),
        model: "mock-model".into(),
        api_key: "mock-key".into(),
        style,
        ..Default::default()
    }
}

const PLAIN: &str = "This is a reply from the toolnexus mock model.";

async fn tool_loop(style: Style) {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let tk = toolkit(vec![add_tool()]).await;
    let r = create_client(opts(&m, style))
        .run("add", &tk)
        .await
        .unwrap();
    assert_eq!(r.tool_calls.len(), 1, "{style:?}");
    assert_eq!(r.tool_calls[0].name, "add");
    assert_eq!(r.tool_calls[0].args, json!({"a": 1, "b": 1}));
    assert_eq!(r.tool_calls[0].output, "2");
    assert_eq!(r.text, "Tool result: 2", "{style:?}");
}

#[tokio::test]
async fn openai_tool_loop() {
    tool_loop(Style::OpenAI).await;
}

#[tokio::test]
async fn anthropic_tool_loop() {
    tool_loop(Style::Anthropic).await;
}

#[tokio::test]
async fn streaming_text_both_styles() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let tk = toolkit(vec![]).await;
    for style in [Style::OpenAI, Style::Anthropic] {
        let mut text = String::new();
        let mut done = None;
        let mut rx = create_client(opts(&m, style)).stream("hi", &tk).unwrap();
        while let Some(ev) = rx.recv().await {
            match ev.kind.as_str() {
                "text" => text.push_str(&ev.delta),
                "done" => done = ev.result,
                "error" => panic!("{style:?}: {:?}", ev.error),
                _ => {}
            }
        }
        assert_eq!(text, PLAIN, "{style:?}");
        assert_eq!(done.unwrap().text, PLAIN, "{style:?}");
    }
}

#[tokio::test]
async fn image_attachment_arrives_intact() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let sha = std::fs::read_to_string(repo().join("examples/media/fixture.png.sha256")).unwrap();
    let want = format!("image/png 82 bytes sha256={}", &sha.trim()[..12]);
    let tk = toolkit(vec![]).await;
    for style in [Style::OpenAI, Style::Anthropic] {
        let parts = vec![
            ContentPart::text("describe"),
            ContentPart::from_path(repo().join("examples/media/fixture.png")).unwrap(),
        ];
        let r = create_client(opts(&m, style))
            .run(parts, &tk)
            .await
            .unwrap();
        assert!(r.text.contains(&want), "{style:?}: {}", r.text);
        assert!(r.text.starts_with("Received 1 image(s)"), "{}", r.text);
    }
}

#[tokio::test]
async fn retries_a_429_with_retry_after_zero() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let http = reqwest::Client::new();
    http.post(format!("{}/_mock/reset", m.base))
        .send()
        .await
        .unwrap();
    http.post(format!("{}/_mock/faults", m.base))
        .json(&json!({"path": "/chat/completions", "status": 429, "times": 1, "retryAfter": "0"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    let tk = toolkit(vec![]).await;
    let r = create_client(opts(&m, Style::OpenAI))
        .run("hi", &tk)
        .await
        .unwrap();
    assert_eq!(r.text, PLAIN);
    let counts: Value = http
        .get(format!("{}/_mock/requests", m.base))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(counts["/chat/completions"], 2, "{counts}");
}

fn questions() -> Questions {
    let mut q = Questions::new();
    q.insert(
        "is_refund".into(),
        NoulQuestion {
            instructions: "Is the customer asking for a refund?".into(),
            criteria: None,
        }
        .into(),
    );
    q.insert(
        "department".into(),
        ChoiceQuestion {
            instructions: "Which team should handle this?".into(),
            criteria: [
                ("billing", "a charge, invoice or refund"),
                ("shipping", "a delivery or parcel"),
                ("tech", "the product is broken"),
            ]
            .into_iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
        }
        .into(),
    );
    q.insert(
        "urgency".into(),
        ScoreQuestion {
            instructions: "How urgent is it?".into(),
            criteria: vec!["low".into(), "medium".into(), "high".into()],
        }
        .into(),
    );
    q
}

async fn check_classifier(backend: ClassifierBackend, base_url: String) {
    let c = create_classifier(ClassifierOptions {
        backend,
        base_url,
        api_key_env: "TN_MOCK_LLM_UNSET_KEY".into(),
        supports: (backend == ClassifierBackend::Custom)
            .then_some(ClassifierSupports { images: false }),
        ..Default::default()
    })
    .unwrap();
    let state = json!("I was charged twice for my order, please refund one.");
    let d = c.evaluate(state.clone(), &questions()).await.unwrap();
    assert_eq!(d.noul("is_refund").unwrap().noul, 0.95, "{backend:?}");
    let ch = d.choice("department").unwrap();
    assert!(
        ["billing", "shipping", "tech"].contains(&ch.choice.as_str()),
        "{ch:?}"
    );
    assert_eq!(ch.probabilities.len(), 3);
    let sum: f64 = ch.probabilities.values().sum();
    assert!((sum - 1.0).abs() < 1e-9, "sum {sum}");
    assert_eq!(ch.probabilities[&ch.choice], 0.7);
    assert_eq!(ch.confidence, 0.8);
    let s = d.score("urgency").unwrap();
    assert!((0.0..=2.0).contains(&s.score), "{s:?}");
    assert_eq!(s.confidence, 0.6);
    // deterministic: the same call gives the same answers
    let d2 = c.evaluate(state, &questions()).await.unwrap();
    assert_eq!(d2.choice("department").unwrap().choice, ch.choice);
    assert_eq!(d2.score("urgency").unwrap().score, s.score);
}

#[tokio::test]
async fn classifier_systemone_custom() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    check_classifier(ClassifierBackend::Custom, m.base.clone()).await;
}

#[tokio::test]
async fn classifier_cloudflare() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    check_classifier(
        ClassifierBackend::Cloudflare,
        format!("{}/v1/accounts/acc/ai/run", m.origin()),
    )
    .await;
}

#[tokio::test]
async fn media_every_verb_and_async_failure() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let style: Value = reqwest::get(format!("{}/_mock/media-style.json", m.base))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let dir = std::env::temp_dir().join(format!("tn-mock-media-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("media-style.json");
    std::fs::write(&path, style.to_string()).unwrap();
    let media = create_media(load_media_style(&path).unwrap(), MediaOptions::default());
    let _ = std::fs::remove_dir_all(&dir);

    let one = |r: &toolnexus::MediaResult, suffix: &str, mime: &str| {
        assert_eq!(r.files.len(), 1, "{r:?}");
        assert!(r.files[0].url.ends_with(suffix), "{r:?}");
        assert_eq!(r.files[0].mime_type, mime, "{r:?}");
    };
    let img = media
        .image(ImageInput {
            prompt: "a red square".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&img, "/files/image.png", "image/png");
    let vid = media
        .video(VideoInput {
            prompt: "a red square spinning".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&vid, "/files/video.mp4", "video/mp4");
    let sp = media
        .speech(SpeechInput {
            text: "hello".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&sp, "/files/speech.wav", "audio/wav");
    let audio = sp.files[0].url.clone();
    let tr = media
        .transcribe(TranscribeInput {
            audio: audio.clone(),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(
        tr.text.as_deref(),
        Some(format!("Mock transcript of {audio}.").as_str())
    );
    let cv = media
        .clone_voice(CloneVoiceInput {
            name: "muthu".into(),
            samples: vec![audio.clone()],
            ..Default::default()
        })
        .await
        .unwrap();
    use sha2::{Digest, Sha256};
    let h = format!("{:x}", Sha256::digest(b"muthu"));
    assert_eq!(
        cv.text.as_deref(),
        Some(format!("voice-{}", &h[..8]).as_str())
    );
    let tk = media
        .talking(TalkingInput {
            image: img.files[0].url.clone(),
            audio,
            ..Default::default()
        })
        .await
        .unwrap();
    one(&tk, "/files/talking.mp4", "video/mp4");

    let err = media
        .image(ImageInput {
            prompt: "please [fail] now".into(),
            ..Default::default()
        })
        .await
        .unwrap_err();
    assert!(err.to_string().contains("mock: job asked to fail"), "{err}");
}

// ---------------------------------------------------------------- round 2: the mixed kit

/// A python child printing its URL on the first stdout line; killed on drop.
struct Proc(Child, #[allow(dead_code)] String);

impl Drop for Proc {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[cfg(feature = "mcp-http")]
fn start_http_mcp() -> Proc {
    let mut child = Command::new("python3")
        .arg(kit("mock_mcp.py"))
        .args(["--http", "--port", "0"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut line = String::new();
    BufReader::new(child.stdout.take().unwrap())
        .read_line(&mut line)
        .unwrap();
    let url = line
        .trim()
        .rsplit(' ')
        .next()
        .unwrap_or_default()
        .to_string();
    assert!(url.starts_with("http"), "unexpected banner {line:?}");
    Proc(child, url)
}

fn py(file: &str, extra: &[&str]) -> Value {
    let mut cmd = vec![json!("python3"), json!(kit(file).to_string_lossy())];
    cmd.extend(extra.iter().map(|a| json!(a)));
    json!({"type": "local", "command": cmd})
}

fn native_mul() -> Tool {
    define_tool(
        "mul",
        "Multiply two integers.",
        Some(json!({"type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"]})),
        |a: Value, _| async move {
            Ok::<_, String>(
                (a["a"].as_i64().unwrap_or(0) * a["b"].as_i64().unwrap_or(0)).to_string(),
            )
        },
    )
}

/// MCP (stdio 2026, stdio legacy, http 2026 when built with mcp-http) + HTTP
/// tool + A2A agent + native tool + skills, all in one toolkit.
async fn mixed_toolkit(m: &Mock) -> (Toolkit, Vec<Proc>) {
    let mut servers = serde_json::Map::new();
    servers.insert("mcpstdio".into(), py("mock_mcp.py", &[]));
    servers.insert("mcplegacy".into(), py("mock_mcp.py", &["--legacy"]));
    #[allow(unused_mut)]
    let mut procs = Vec::new();
    #[cfg(feature = "mcp-http")]
    {
        let p = start_http_mcp();
        servers.insert("mcphttp".into(), json!({"type": "remote", "url": p.1}));
        procs.push(p);
    }
    let http_add = toolnexus::http::http_tool(toolnexus::http::HttpToolOptions {
        name: "http_add".into(),
        description: "Add two integers over HTTP.".into(),
        method: "POST".into(),
        url: format!("{}/tools/add", m.base),
        input_schema: Some(json!({"type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"]})),
        ..Default::default()
    });
    let mut agent =
        toolnexus::a2a::Agent::new(format!("{}/.well-known/agent-card.json", m.origin()));
    agent.poll_every_ms = 20;
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        mcp_config: Some(toolnexus::McpConfigInput::Json(
            json!({"mcpServers": servers}),
        )),
        skills_dir: vec![repo().join("examples/skills")],
        extra_tools: vec![native_mul(), http_add],
        agents: vec![agent],
        ..Default::default()
    })
    .await
    .unwrap();
    (tk, procs)
}

async fn exec(tk: &Toolkit, name: &str, args: Value) -> String {
    let r = tk.execute(name, args, Default::default()).await;
    assert!(!r.is_error, "{name}: {}", r.output);
    r.output
}

#[tokio::test]
async fn mixed_toolkit_every_source_executes() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let (tk, _procs) = mixed_toolkit(&m).await;
    let status = tk.mcp_status();
    let mut want = vec![
        "mcpstdio_add",
        "mcpstdio_echo",
        "mcplegacy_add",
        "mcplegacy_echo",
        "http_add",
        "mockagent_summarize",
        "mul",
        "skill",
    ];
    if cfg!(feature = "mcp-http") {
        want.extend(["mcphttp_add", "mcphttp_echo"]);
    }
    let names: Vec<String> = tk.tools().iter().map(|t| t.name.clone()).collect();
    for w in &want {
        assert!(
            names.iter().any(|n| n == w),
            "missing {w} in {names:?}; mcp {status:?}"
        );
    }

    assert_eq!(
        exec(&tk, "mcpstdio_add", json!({"a": 2, "b": 3})).await,
        "5"
    );
    assert_eq!(
        exec(&tk, "mcplegacy_add", json!({"a": 2, "b": 3})).await,
        "5"
    );
    #[cfg(feature = "mcp-http")]
    {
        assert_eq!(exec(&tk, "mcphttp_add", json!({"a": 2, "b": 3})).await, "5");
        let echo = tk
            .execute("mcphttp_echo", json!({"text": "hi"}), Default::default())
            .await;
        // SPEC §0.4: structuredContent wins, output = its JSON
        assert_eq!(echo.output, r#"{"text":"hi"}"#);
    }
    let http = exec(&tk, "http_add", json!({"a": 2, "b": 3})).await;
    let v: Value = serde_json::from_str(&http).unwrap_or_else(|_| panic!("{http}"));
    assert_eq!(v, json!({"sum": 5}));
    assert_eq!(
        exec(&tk, "mockagent_summarize", json!({"task": "x"})).await,
        "A2A mock done: x"
    );
    assert_eq!(exec(&tk, "mul", json!({"a": 6, "b": 7})).await, "42");
    let skill = exec(&tk, "skill", json!({"name": "hello-world"})).await;
    assert!(skill.contains("# Hello World Skill"), "{skill}");
    assert!(skill.contains("This is the skill body."), "{skill}");
}

#[tokio::test]
async fn client_run_over_mixed_toolkit() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let (tk, _procs) = mixed_toolkit(&m).await;
    let r = create_client(opts(&m, Style::OpenAI))
        .run("use a tool", &tk)
        .await
        .unwrap();
    assert_eq!(r.tool_calls.len(), 1, "{r:?}");
    let call = &r.tool_calls[0];
    assert!(!call.output.is_empty(), "{call:?}");
    assert_eq!(r.text, format!("Tool result: {}", call.output.trim()));
}

#[tokio::test]
async fn one_shot_cli_client() {
    let _g = lock().lock().await;
    if start_mock().is_none() {
        return;
    }
    use toolnexus::client::{
        InProcessOptions, InProcessRequest, InProcessResponse, InProcessToolCall,
        create_in_process_client,
    };
    fn render(req: &InProcessRequest) -> String {
        let mut lines = vec!["Tools you may call:".to_string()];
        for t in &req.tools {
            lines.push(format!(
                "- {}",
                t["function"]["name"].as_str().unwrap_or_default()
            ));
        }
        let mut called = std::collections::HashMap::new();
        for m in &req.messages {
            let s = |v: &Value| v.as_str().map_or_else(|| v.to_string(), String::from);
            if m["role"] == "tool" {
                let id = s(&m["tool_call_id"]);
                let name = called.get(&id).cloned().unwrap_or(id);
                lines.push(format!("[tool {name}] {}", s(&m["content"])));
            } else if let Some(calls) = m["tool_calls"].as_array() {
                for c in calls {
                    called.insert(s(&c["id"]), s(&c["function"]["name"]));
                }
            } else {
                lines.push(format!("[{}] {}", s(&m["role"]), s(&m["content"])));
            }
        }
        lines.join("\n")
    }
    fn parse(out: &str) -> InProcessResponse {
        let r: Value = serde_json::from_str(out.trim()).unwrap_or(Value::Null);
        if let Some(calls) = r["tool_calls"].as_array().filter(|c| !c.is_empty()) {
            return InProcessResponse {
                tool_calls: calls
                    .iter()
                    .map(|c| InProcessToolCall {
                        id: String::new(),
                        name: c["name"].as_str().unwrap_or_default().into(),
                        arguments: c["arguments"].clone(),
                    })
                    .collect(),
                ..Default::default()
            };
        }
        InProcessResponse {
            content: r["content"].as_str().unwrap_or(out.trim()).into(),
            ..Default::default()
        }
    }
    let script = kit("fake_cli.py");
    let generate = std::sync::Arc::new(move |req: InProcessRequest| {
        let script = script.clone();
        Box::pin(async move {
            tokio::task::spawn_blocking(move || {
                use std::io::Write;
                let mut child = Command::new("python3")
                    .arg(&script)
                    .stdin(Stdio::piped())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .spawn()
                    .map_err(|e| e.to_string())?;
                child
                    .stdin
                    .take()
                    .unwrap()
                    .write_all(render(&req).as_bytes())
                    .map_err(|e| e.to_string())?;
                let out = child.wait_with_output().map_err(|e| e.to_string())?;
                Ok(parse(&String::from_utf8_lossy(&out.stdout)))
            })
            .await
            .map_err(|e| e.to_string())?
        }) as _
    });
    let client = create_in_process_client(InProcessOptions {
        model: "fake_cli".into(),
        generate: Some(generate),
        ..Default::default()
    });
    let tk = toolkit(vec![add_tool()]).await;
    let r = client.run("add 19 and 23", &tk).await.unwrap();
    assert_eq!(r.text, "The sum is 42.");
    assert_eq!(r.tool_calls.len(), 1);
    assert_eq!(r.tool_calls[0].args, json!({"a": 19, "b": 23}));
    assert_eq!(r.tool_calls[0].output, "42");
}

#[cfg(feature = "serve")]
mod acp {
    use super::*;
    use std::sync::{Arc, Mutex};
    use toolnexus::acp::{AcpClient, AcpOptions, PermissionOutcome, permission_handler};

    async fn agent(approve: bool, served: &toolnexus::McpServerHandle) -> AcpClient {
        let mut opts = AcpOptions::new("python3")
            .args([kit("fake_acp_agent.py").to_string_lossy().into_owned()]);
        opts.mcp_servers = vec![
            json!({"type": "http", "name": "toolnexus", "url": served.url,
            "headers": [{"name": "Authorization", "value": format!("Bearer {}", served.token)}]}),
        ];
        opts.on_permission = Some(permission_handler(move |req| async move {
            if approve {
                PermissionOutcome::Selected(req.first_allow().unwrap().option_id.clone())
            } else {
                PermissionOutcome::Reject
            }
        }));
        AcpClient::load(opts).await.unwrap()
    }

    fn recording_add(seen: Arc<Mutex<Vec<Value>>>) -> Tool {
        define_tool(
            "add",
            "Add two integers.",
            Some(json!({"type": "object",
                "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
                "required": ["a", "b"]})),
            move |a: Value, _| {
                let seen = seen.clone();
                async move {
                    seen.lock().unwrap().push(a.clone());
                    Ok::<_, String>(
                        (a["a"].as_i64().unwrap_or(0) + a["b"].as_i64().unwrap_or(0)).to_string(),
                    )
                }
            },
        )
    }

    #[tokio::test]
    async fn acp_agent_calls_served_toolkit() {
        let _g = lock().lock().await;
        if start_mock().is_none() {
            return;
        }
        let seen = Arc::new(Mutex::new(Vec::new()));
        let tk = toolkit(vec![recording_add(seen.clone())]).await;
        let served = tk.as_mcp_server(None).await.unwrap();
        let a = Arc::new(agent(true, &served).await);
        let client =
            toolnexus::client::create_in_process_client(toolnexus::client::InProcessOptions {
                model: "fake_acp".into(),
                generate_stream: Some(toolnexus::client::acp_generate_stream(a.clone())),
                ..Default::default()
            });
        let empty = toolkit(vec![]).await;
        let mut rx = client.stream("add 17 and 25", &empty).unwrap();
        let (mut text, mut final_text, mut acp_tool) = (String::new(), None, false);
        while let Some(ev) = rx.recv().await {
            match ev.kind.as_str() {
                "text" => text.push_str(&ev.delta),
                "tool_call" if ev.via == "acp" => acp_tool = true,
                "done" => final_text = ev.result.map(|r| r.text),
                "error" => panic!("{:?}", ev.error),
                _ => {}
            }
        }
        assert_eq!(text, "The sum is 42.");
        assert_eq!(final_text.as_deref(), Some("The sum is 42."));
        assert!(acp_tool, "agent tool call not reported");
        assert_eq!(*seen.lock().unwrap(), vec![json!({"a": 17, "b": 25})]);
        a.close().await;
        served.close();
    }

    #[tokio::test]
    async fn acp_permission_rejected() {
        let _g = lock().lock().await;
        if start_mock().is_none() {
            return;
        }
        let seen = Arc::new(Mutex::new(Vec::new()));
        let tk = toolkit(vec![recording_add(seen.clone())]).await;
        let served = tk.as_mcp_server(None).await.unwrap();
        let a = agent(false, &served).await;
        let r = a.prompt_text("add 17 and 25").await.unwrap();
        assert_eq!(r.text, "Permission denied.");
        assert!(
            seen.lock().unwrap().is_empty(),
            "tool ran despite rejection"
        );
        a.close().await;
        served.close();
    }

    #[tokio::test]
    async fn acp_cancel_ends_turn_cancelled() {
        let _g = lock().lock().await;
        if start_mock().is_none() {
            return;
        }
        let a = AcpClient::load(
            AcpOptions::new("python3")
                .args([kit("fake_acp_agent.py").to_string_lossy().into_owned()]),
        )
        .await
        .unwrap();
        let mut turn = a.prompt("please [wait]").await.unwrap();
        let mut got = String::new();
        while let Some(u) = turn.next().await {
            if let Some(t) = u.text() {
                got.push_str(t);
            }
            if got.contains("Working...") {
                break;
            }
        }
        assert_eq!(got, "Working...");
        a.cancel().await.unwrap();
        let r = tokio::time::timeout(std::time::Duration::from_secs(10), turn.finish())
            .await
            .expect("turn did not end after cancel")
            .unwrap();
        assert_eq!(r.stop_reason, "cancelled");
        a.close().await;
    }
}
