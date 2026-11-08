//! MCP 2026-07-28 client (SPEC §2, §2A): discover-first, MRTR → §10, OAuth,
//! skills extension, tool search, no hidden retry, fallback. Hermetic.

mod common;
mod mcp2026_support;

use std::path::Path;
use std::sync::{Arc, Mutex};

use mcp2026_support::{DevAs, Protect, start, start_as};
use serde_json::{Value, json};
use toolnexus::mcp::{
    McpLoadOptions, McpSource, McpStatus, MemoryTokenStore, TokenStore, load_mcp_with,
    parse_mcp_config,
};
use toolnexus::{Answer, Request, ToolContext, ToolResult, pending_of};

fn tool<'a>(src: &'a McpSource, name: &str) -> &'a toolnexus::Tool {
    src.tools
        .iter()
        .find(|t| t.name == name)
        .unwrap_or_else(|| {
            panic!(
                "no tool {name}: {:?}",
                src.tools.iter().map(|t| &t.name).collect::<Vec<_>>()
            )
        })
}

async fn call(src: &McpSource, name: &str, args: Value) -> ToolResult {
    tool(src, name).call(args, ToolContext::default()).await
}

async fn resume(src: &McpSource, name: &str, args: Value, answer: Answer) -> ToolResult {
    let ctx = ToolContext {
        answer: Some(answer),
        ..Default::default()
    };
    tool(src, name).call(args, ctx).await
}

fn opts() -> McpLoadOptions {
    McpLoadOptions::default()
}

async fn load(cfg: Value, o: McpLoadOptions) -> McpSource {
    load_mcp_with(&parse_mcp_config(&json!({ "mcpServers": cfg })).unwrap(), o).await
}

/// `auto` mode opener for tests: GET the URL and follow the redirect to the loopback.
fn get_opener() -> toolnexus::mcp::OpenUrlFn {
    Arc::new(|u: String| {
        Box::pin(async move {
            reqwest::get(&u)
                .await
                .map(|_| ())
                .map_err(|e| e.to_string())
        })
    })
}

/// The URL the AS redirects to (pause mode), without following it.
async fn callback_of(url: &str) -> String {
    let c = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap();
    let r = c.get(url).send().await.unwrap();
    r.headers()["location"].to_str().unwrap().to_owned()
}

fn answer_auth(id: &str, cb: &str) -> Answer {
    let mut d = serde_json::Map::new();
    d.insert("callbackUrl".into(), json!(cb));
    Answer {
        id: id.into(),
        ok: true,
        data: Some(d),
        reason: String::new(),
    }
}

fn name_answer(id: &str) -> Answer {
    let mut d = serde_json::Map::new();
    d.insert("name".into(), json!("toolnexus"));
    Answer {
        id: id.into(),
        ok: true,
        data: Some(d),
        reason: String::new(),
    }
}

/// A §10 resolver: input → name "toolnexus"; authorization → follow the AS (pause).
fn resolver(asked: Arc<Mutex<Vec<Request>>>) -> toolnexus::client::WaitForFn {
    Arc::new(move |r: Request| {
        let asked = asked.clone();
        Box::pin(async move {
            asked.lock().unwrap().push(r.clone());
            if r.kind == "authorization" {
                let cb = callback_of(&r.url).await;
                return Ok(answer_auth(&r.id, &cb));
            }
            Ok(name_answer(&r.id))
        })
    })
}

// ---------------------------------------------------------------------------
// HTTP: discover, headers, tools, MRTR
// ---------------------------------------------------------------------------

#[tokio::test]
async fn http_discover_first_tools_and_headers() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(json!({"fx": {"url": fx.url}}), opts()).await;
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
    let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, ["fx_echo", "fx_greet"]);
    // discover is the first request, and no initialize is ever sent
    let first = fx.st.lock().unwrap().seen[0].0.clone();
    assert_eq!(first, "server/discover");
    assert!(fx.calls("initialize").is_empty());
    // schema normalized as on the frozen path
    assert_eq!(
        tool(&src, "fx_echo").input_schema,
        json!({"required": ["text"], "type": "object", "properties": {"text": {"type": "string"}}, "additionalProperties": false})
    );

    let r = call(&src, "fx_echo", json!({"text": "hi toolnexus"})).await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, r#"{"text":"hi toolnexus"}"#); // structuredContent wins (§2.F)
    let body = &fx.calls("tools/call")[0];
    let meta = &body["params"]["_meta"];
    assert_eq!(
        meta["io.modelcontextprotocol/protocolVersion"],
        "2026-07-28"
    );
    assert_eq!(
        meta["io.modelcontextprotocol/clientCapabilities"],
        json!({"elicitation": {"form": {}}})
    );
    assert_eq!(
        meta["io.modelcontextprotocol/clientInfo"]["name"],
        "toolnexus"
    );
    let h = &fx.headers("tools/call")[0];
    assert_eq!(h["mcp-protocol-version"], "2026-07-28");
    assert_eq!(h["mcp-method"], "tools/call");
    assert_eq!(h["mcp-name"], "echo");
    src.close().await;
}

#[tokio::test]
async fn mcp_name_sentinel_for_unsafe_names() {
    let fx = start(
        Protect::None,
        None,
        vec![json!({"name": " spaced", "description": "", "inputSchema": {"type": "object"}})],
    )
    .await;
    let src = load(json!({"fx": {"url": fx.url}}), opts()).await;
    let r = call(&src, "fx__spaced", json!({"text": "ok"})).await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(
        fx.headers("tools/call")[0]["mcp-name"],
        "=?base64?IHNwYWNlZA==?="
    );
}

#[tokio::test]
async fn tools_filter_applies_on_the_direct_path() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(
        json!({"fx": {"url": fx.url, "tools": {"greet": false}}}),
        opts(),
    )
    .await;
    let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, ["fx_echo"]);
}

#[tokio::test]
async fn mrtr_round_trip_through_wait_for() {
    let fx = start(Protect::None, None, vec![]).await;
    let asked = Arc::new(Mutex::new(vec![]));
    let src = load(
        json!({"fx": {"url": fx.url}}),
        McpLoadOptions {
            wait_for: Some(resolver(asked.clone())),
            ..opts()
        },
    )
    .await;
    let r = call(&src, "fx_greet", json!({})).await;
    assert_eq!(r.output, "Hello, toolnexus!");
    let asked = asked.lock().unwrap();
    assert_eq!(asked.len(), 1);
    assert_eq!(asked[0].kind, "input");
    assert!(asked[0].id.starts_with("mrtr-"));
    assert_eq!(asked[0].prompt, "What is your name?");
    assert!(asked[0].data.as_ref().unwrap().contains_key("schema"));
    assert_eq!(fx.execs("greet"), 1);
    // the re-sent round echoes requestState and carries the response
    let second = &fx.calls("tools/call")[1]["params"];
    assert_eq!(second["requestState"], "greet-1");
    assert_eq!(
        second["inputResponses"]["name"],
        json!({"action": "accept", "content": {"name": "toolnexus"}})
    );
}

#[tokio::test]
async fn mrtr_without_wait_for_is_pending_then_resumes() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(json!({"fx": {"url": fx.url}}), opts()).await;
    let r = call(&src, "fx_greet", json!({})).await;
    let req = pending_of(&r).expect("pending");
    assert_eq!(req.kind, "input");
    assert_eq!(fx.execs("greet"), 0);
    let r = resume(&src, "fx_greet", json!({}), name_answer(&req.id)).await;
    assert_eq!(r.output, "Hello, toolnexus!");
    assert_eq!(fx.execs("greet"), 1);
    assert_eq!(fx.calls("tools/call").len(), 2);
}

#[tokio::test]
async fn declined_input_maps_to_decline() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(json!({"fx": {"url": fx.url}}), opts()).await;
    let req = pending_of(&call(&src, "fx_greet", json!({})).await).unwrap();
    let a = Answer {
        id: req.id,
        ok: false,
        data: None,
        reason: "declined".into(),
    };
    let _ = resume(&src, "fx_greet", json!({}), a).await;
    assert_eq!(
        fx.calls("tools/call")[1]["params"]["inputResponses"]["name"],
        json!({"action": "decline"})
    );
}

#[tokio::test]
async fn no_hidden_retry_on_502_or_unreadable_reply() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(json!({"fx": {"url": fx.url}}), opts()).await;
    fx.st.lock().unwrap().fail_502_after_exec = true;
    let r = call(&src, "fx_echo", json!({"text": "x"})).await;
    assert!(r.is_error && r.output.contains("502"), "{}", r.output);
    assert_eq!(fx.execs("echo"), 1);
    fx.st.lock().unwrap().fail_502_after_exec = false;
    fx.st.lock().unwrap().garbage_after_exec = true;
    let r = call(&src, "fx_echo", json!({"text": "x"})).await;
    assert!(r.is_error, "{}", r.output);
    assert_eq!(fx.execs("echo"), 2);
}

#[tokio::test]
async fn unknown_tool_is_a_tool_error() {
    let fx = start(Protect::None, None, vec![]).await;
    let r = call(
        &load(json!({"fx": {"url": fx.url}}), opts()).await,
        "fx_echo",
        json!({}),
    )
    .await;
    assert!(!r.is_error);
    // tool search: an unknown server tool name is a tool error
    let r = toolnexus::Toolkit::execute(
        &toolnexus::create_toolkit(toolnexus::ToolkitOptions {
            mcp_config: Some(toolnexus::McpConfigInput::Json(
                json!({"mcpServers": {"fx": {"url": fx.url, "toolSearch": true}}}),
            )),
            builtins: Some(toolnexus::BuiltinsConfig::off()),
            ..Default::default()
        })
        .await
        .unwrap(),
        "fx_call_tool",
        json!({"name": "nope"}),
        ToolContext::default(),
    )
    .await;
    assert!(r.is_error);
    assert_eq!(r.output, r#"unknown tool "nope" on fx"#);
}

// ---------------------------------------------------------------------------
// fallback, failures
// ---------------------------------------------------------------------------

#[tokio::test]
async fn discover_5xx_tries_fallback_and_connection_refused_fails() {
    // 500 on discover ⇒ frozen path (which then fails on this server: not MCP at all)
    let s = common::serve(|_r| async { common::Resp::status(500, "boom") }).await;
    let src = load(
        json!({"old": {"url": format!("{}/mcp", s.url), "timeout": 2000}}),
        opts(),
    )
    .await;
    assert_eq!(src.status["old"], McpStatus::Failed);
    #[cfg(feature = "mcp-http")]
    assert!(
        src.errors["old"].contains("streamable-http"),
        "{}",
        src.errors["old"]
    );
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = l.local_addr().unwrap();
    drop(l);
    let src = load(
        json!({"dead": {"url": format!("http://{addr}/mcp")}}),
        opts(),
    )
    .await;
    assert_eq!(src.status["dead"], McpStatus::Failed);
    assert!(
        !src.errors["dead"].contains("streamable-http"),
        "{}",
        src.errors["dead"]
    );
}

// ---------------------------------------------------------------------------
// skills extension, tool search
// ---------------------------------------------------------------------------

#[tokio::test]
async fn remote_skills_join_the_skill_tool() {
    let fx = start(Protect::None, None, vec![]).await;
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        mcp_config: Some(toolnexus::McpConfigInput::Json(
            json!({"mcpServers": {"fx": {"url": fx.url}}}),
        )),
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let local = toolnexus::skills::load_skills(&[
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills")
    ]);
    let remote = &tk.skill_source().expect("skill tool").skills["hello-world"];
    let l = &local.skills["hello-world"];
    assert_eq!(remote.content, l.content);
    assert_eq!(remote.description, l.description);
    assert_eq!(remote.base, "skill://hello-world/");
    assert_eq!(remote.resources, ["scripts/greet.sh"]);
    let r = tk
        .execute(
            "skill",
            json!({"name": "hello-world"}),
            ToolContext::default(),
        )
        .await;
    assert!(!r.is_error && r.output.contains(&l.content), "{}", r.output);
}

#[test]
fn skill_uri_containment() {
    let ex: Value = serde_json::from_str(
        &std::fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/mcp-2026/expected.json"),
        )
        .unwrap(),
    )
    .unwrap();
    for u in ex["skillUriRejected"].as_array().unwrap() {
        assert!(
            toolnexus::mcp::parse_skill_uri(u.as_str().unwrap()).is_err(),
            "{u}"
        );
    }
    assert_eq!(
        toolnexus::mcp::parse_skill_uri("skill://hello-world/scripts/greet.sh").unwrap(),
        ("hello-world".to_owned(), "scripts/greet.sh".to_owned())
    );
}

#[tokio::test]
async fn tool_search_exposes_two_tools_and_runs_hits() {
    let fx = start(Protect::None, None, vec![]).await;
    let src = load(
        json!({"fx": {"url": fx.url, "toolSearch": true, "description": "the fixture"}}),
        opts(),
    )
    .await;
    let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, ["fx_search_tools", "fx_call_tool"]); // direct names hidden
    let s = tool(&src, "fx_search_tools");
    assert_eq!(
        s.description,
        "Search the tools of the fixture by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."
    );
    assert_eq!(
        s.input_schema,
        json!({"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]})
    );
    assert_eq!(
        tool(&src, "fx_call_tool").description,
        "Run a tool found with search_tools."
    );
    let r = call(&src, "fx_search_tools", json!({"query": "echo text"})).await;
    let hits: Value = serde_json::from_str(&r.output).unwrap();
    assert_eq!(hits[0]["name"], "echo");
    assert_eq!(hits[0]["server"], "the fixture");
    assert!(
        r.output.starts_with(r#"[{"name":"echo","description":"#),
        "{}",
        r.output
    );
    assert_eq!(
        call(&src, "fx_search_tools", json!({"query": "zzz"}))
            .await
            .output,
        "[]"
    );
    let r = call(
        &src,
        "fx_call_tool",
        json!({"name": "echo", "arguments": {"text": "via search"}}),
    )
    .await;
    assert_eq!(r.output, r#"{"text":"via search"}"#);
    // MRTR through call_tool, pending + resume
    let req = pending_of(&call(&src, "fx_call_tool", json!({"name": "greet"})).await).unwrap();
    let r = resume(
        &src,
        "fx_call_tool",
        json!({"name": "greet"}),
        name_answer(&req.id),
    )
    .await;
    assert_eq!(r.output, "Hello, toolnexus!");
    // the `tools` filter applies before search
    let src = load(
        json!({"fx": {"url": fx.url, "toolSearch": true, "tools": {"greet": false}}}),
        opts(),
    )
    .await;
    let r = call(&src, "fx_search_tools", json!({"query": "greet"})).await;
    assert_eq!(r.output, "[]");
    let r = call(&src, "fx_call_tool", json!({"name": "greet"})).await;
    assert_eq!(r.output, r#"unknown tool "greet" on fx"#);
    // and so does disableTools
    let src = load(
        json!({"fx": {"url": fx.url, "toolSearch": true}}),
        McpLoadOptions {
            disable_tools: vec!["fx_echo".into()],
            ..opts()
        },
    )
    .await;
    let r = call(&src, "fx_call_tool", json!({"name": "echo"})).await;
    assert_eq!(r.output, r#"unknown tool "echo" on fx"#);
}

// ---------------------------------------------------------------------------
// stdio
// ---------------------------------------------------------------------------

fn node() -> bool {
    std::process::Command::new("node")
        .arg("--version")
        .output()
        .is_ok()
}

fn stdio_cfg(extra: &[&str], timeout: u64) -> Value {
    let f = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/mcp2026-stdio-server.mjs");
    let mut cmd = vec!["node".to_owned(), f.to_string_lossy().into_owned()];
    cmd.extend(extra.iter().map(|s| s.to_string()));
    json!({"st": {"command": cmd, "timeout": timeout}})
}

#[tokio::test]
async fn stdio_direct_path_demux_mrtr_and_dead_child() {
    if !node() {
        return;
    }
    let src = load(stdio_cfg(&[], 5000), opts()).await;
    assert_eq!(src.status["st"], McpStatus::Connected, "{:?}", src.errors);
    assert_eq!(
        call(&src, "st_echo", json!({"text": "hi"})).await.output,
        r#"{"text":"hi"}"#
    );
    // concurrent calls come back out of order and are demultiplexed by id
    let (a, b) = tokio::join!(
        call(&src, "st_slow", json!({"ms": 300, "text": "first"})),
        call(&src, "st_slow", json!({"ms": 10, "text": "second"}))
    );
    assert_eq!((a.output.as_str(), b.output.as_str()), ("first", "second"));
    let req = pending_of(&call(&src, "st_greet", json!({})).await).unwrap();
    assert_eq!(
        resume(&src, "st_greet", json!({}), name_answer(&req.id))
            .await
            .output,
        "Hello, toolnexus!"
    );
    // a dead child fails the pending call instead of hanging
    let r = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        call(&src, "st_crash", json!({})),
    )
    .await
    .expect("no hang");
    assert!(r.is_error, "{}", r.output);
    let r = call(&src, "st_echo", json!({"text": "x"})).await;
    assert!(r.is_error);
    src.close().await;
}

#[tokio::test]
async fn stdio_silent_discover_falls_back() {
    if !node() {
        return;
    }
    let t0 = std::time::Instant::now();
    let src = load(stdio_cfg(&["--ignore-discover"], 800), opts()).await;
    // the frozen path then also gets no answer to initialize ⇒ failed, isolated
    assert_eq!(src.status["st"], McpStatus::Failed);
    assert!(t0.elapsed() >= std::time::Duration::from_millis(800));
}

/// SPEC §2.2a: a fast discover error keeps the probe child for `initialize`
/// (one spawn); discover silence kills it and the frozen path respawns (two).
async fn legacy_stdio_spawns(mode: &str) -> usize {
    let f = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../examples/mcp-2026/legacy-stdio/server.mjs")
        .canonicalize()
        .unwrap();
    let count = std::env::temp_dir().join(format!(
        "toolnexus-rust-spawns-{mode}-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_file(&count);
    let cfg = json!({"leg": {
        "command": ["node", f.to_string_lossy()],
        "env": {"SPAWN_COUNT_FILE": count.to_string_lossy(), "DISCOVER_MODE": mode},
        "timeout": 1500,
    }});
    let src = load(cfg, opts()).await;
    assert_eq!(src.status["leg"], McpStatus::Connected, "{:?}", src.errors);
    let r = call(&src, "leg_echo", json!({"text": "hi"})).await;
    assert_eq!(r.output, "echo:hi");
    src.close().await;
    let n = std::fs::read_to_string(&count)
        .unwrap()
        .lines()
        .filter(|l| l.starts_with("start"))
        .count();
    let _ = std::fs::remove_file(&count);
    n
}

#[tokio::test]
async fn legacy_stdio_discover_error_reuses_the_probe_child() {
    if !node() {
        return;
    }
    assert_eq!(legacy_stdio_spawns("error").await, 1);
}

#[tokio::test]
async fn legacy_stdio_discover_silence_respawns() {
    if !node() {
        return;
    }
    assert_eq!(legacy_stdio_spawns("silent").await, 2);
}

// ---------------------------------------------------------------------------
// OAuth
// ---------------------------------------------------------------------------

fn auth_server(fx_url: &str, auth: Value) -> Value {
    json!({"fx": {"url": fx_url, "auth": auth, "timeout": 5000}})
}

async fn protected(protect: Protect) -> (Arc<DevAs>, mcp2026_support::Fixture) {
    let a = start_as().await;
    let fx = start(protect, Some(a.clone()), vec![]).await;
    (a, fx)
}

#[tokio::test]
async fn oauth_auto_login_step_up_and_mrtr() {
    let (a, fx) = protected(Protect::All).await;
    let store = MemoryTokenStore::new();
    let asked = Arc::new(Mutex::new(vec![]));
    let src = load(
        auth_server(
            &fx.url,
            json!({"clientId": "dev-client", "allowHttp": true}),
        ),
        McpLoadOptions {
            open_url: Some(get_opener()),
            token_store: Some(store.clone()),
            wait_for: Some(resolver(asked.clone())),
            ..opts()
        },
    )
    .await;
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
    let t = store.load(&fx.url).expect("token stored");
    assert_eq!(t.issuer, a.issuer());
    assert!(
        !format!("{t:?}").contains("secret"),
        "Debug never shows a token"
    );
    assert_eq!(
        call(&src, "fx_echo", json!({"text": "x"})).await.output,
        r#"{"text":"x"}"#
    );
    // greet needs mcp:greet ⇒ 403 insufficient_scope ⇒ step-up with the union
    let r = call(&src, "fx_greet", json!({})).await;
    assert_eq!(r.output, "Hello, toolnexus!");
    assert_eq!(
        a.st.lock().unwrap().authorize_scopes.last().unwrap(),
        "mcp:read mcp:greet"
    );
    assert_eq!(fx.execs("greet"), 1);
    assert_eq!(
        asked.lock().unwrap().len(),
        1,
        "only the MRTR input was asked"
    );
}

#[tokio::test]
async fn oauth_form_encoded_token_response() {
    let (a, fx) = protected(Protect::All).await;
    a.knobs(|k| k.form_encoded = true);
    let src = load(
        auth_server(
            &fx.url,
            json!({"clientId": "dev-client", "allowHttp": true}),
        ),
        McpLoadOptions {
            open_url: Some(get_opener()),
            ..opts()
        },
    )
    .await;
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
}

#[tokio::test]
async fn oauth_errors_fail_the_server_and_store_nothing() {
    type Knob = fn(&mut mcp2026_support::Knobs);
    let cases: Vec<(Knob, Value, &str)> = vec![
        (
            |k| k.wrong_iss = true,
            json!({"clientId": "dev-client"}),
            "authorization response iss mismatch (possible mix-up attack)",
        ),
        (
            |k| k.wrong_state = true,
            json!({"clientId": "dev-client"}),
            "authorization response state mismatch",
        ),
        (
            |k| k.s256 = false,
            json!({"clientId": "dev-client"}),
            "authorization server does not advertise PKCE S256; refusing",
        ),
        (
            |_| {},
            json!({}),
            "and set auth.clientId (dynamic client registration is not supported)",
        ),
    ];
    for (knob, mut auth, want) in cases {
        let (a, fx) = protected(Protect::All).await;
        a.knobs(knob);
        auth["allowHttp"] = json!(true);
        let store = MemoryTokenStore::new();
        let src = load(
            auth_server(&fx.url, auth),
            McpLoadOptions {
                open_url: Some(get_opener()),
                token_store: Some(store.clone()),
                ..opts()
            },
        )
        .await;
        assert_eq!(src.status["fx"], McpStatus::Failed);
        let e = &src.errors["fx"];
        assert!(e.contains(want), "{e}");
        if want.starts_with("and set") {
            assert!(e.contains("register an app with"), "{e}");
        }
        assert!(store.load(&fx.url).is_none());
        for v in &a.st.lock().unwrap().issued_values {
            assert!(!e.contains(v.as_str()));
        }
    }
}

#[tokio::test]
async fn oauth_pause_at_load_without_wait_for_fails_isolated() {
    let (_a, fx) = protected(Protect::All).await;
    let src = load(
        auth_server(&fx.url, json!({"clientId": "dev-client", "allowHttp": true, "mode": "pause", "redirectUri": "http://127.0.0.1:9/cb"})),
        opts(),
    )
    .await;
    assert_eq!(src.status["fx"], McpStatus::Failed);
    assert_eq!(src.errors["fx"], "authorization pending");
}

#[tokio::test]
async fn oauth_pause_at_load_resolves_through_wait_for() {
    let (_a, fx) = protected(Protect::All).await;
    let asked = Arc::new(Mutex::new(vec![]));
    let src = load(
        auth_server(&fx.url, json!({"clientId": "dev-client", "allowHttp": true, "mode": "pause", "redirectUri": "http://127.0.0.1:9/cb"})),
        McpLoadOptions { wait_for: Some(resolver(asked.clone())), ..opts() },
    )
    .await;
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
    let r = &asked.lock().unwrap()[0];
    assert_eq!(r.kind, "authorization");
    assert!(r.id.starts_with("auth-"));
    assert_eq!(r.prompt, format!("Authorize access to {}", fx.url));
    assert!(!r.expires_at.is_empty());
}

#[tokio::test]
async fn oauth_pause_in_tool_call_then_resume_runs_once() {
    let (_a, fx) = protected(Protect::CallsOnly).await;
    let src = load(
        auth_server(&fx.url, json!({"clientId": "dev-client", "allowHttp": true, "mode": "pause", "redirectUri": "http://127.0.0.1:9/cb"})),
        opts(),
    )
    .await;
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
    let r = call(&src, "fx_echo", json!({"text": "paid"})).await;
    let req = pending_of(&r).expect("pending");
    assert_eq!(req.kind, "authorization");
    assert_eq!(fx.execs("echo"), 0);
    // unknown id / denied answers
    let cb = callback_of(&req.url).await;
    let denied = Answer {
        id: "auth-nope".into(),
        ..answer_auth("auth-nope", &cb)
    };
    let r2 = resume(&src, "fx_echo", json!({"text": "paid"}), denied).await;
    assert!(
        pending_of(&r2).is_some(),
        "an unknown id starts a fresh call"
    );
    let r = resume(
        &src,
        "fx_echo",
        json!({"text": "paid"}),
        answer_auth(&req.id, &cb),
    )
    .await;
    assert_eq!(r.output, r#"{"text":"paid"}"#);
    assert_eq!(fx.execs("echo"), 1);
    // a denied pause ⇒ authorization denied
    let req = pending_of(&r2).unwrap();
    let no = Answer {
        id: req.id.clone(),
        ok: false,
        data: None,
        reason: String::new(),
    };
    let r = resume(&src, "fx_echo", json!({"text": "paid"}), no).await;
    assert_eq!(r.output, "authorization denied: cancelled");
}

#[tokio::test]
async fn oauth_pause_in_mrtr_round_one_resumes_same_round() {
    let (_a, fx) = protected(Protect::CallsOnly).await;
    let src = load(
        auth_server(&fx.url, json!({"clientId": "dev-client", "allowHttp": true, "mode": "pause", "redirectUri": "http://127.0.0.1:9/cb", "clientIds": {}})),
        opts(),
    )
    .await;
    // first authorize (with the greet scope up front via step-up on 403)
    let mut r = call(&src, "fx_greet", json!({})).await;
    let mut input_asks = 0;
    for _ in 0..6 {
        let Some(req) = pending_of(&r) else { break };
        let ans = if req.kind == "authorization" {
            answer_auth(&req.id, &callback_of(&req.url).await)
        } else {
            input_asks += 1;
            fx.st.lock().unwrap().revoke_on_input_required = false;
            name_answer(&req.id)
        };
        if req.kind == "authorization" && input_asks == 0 {
            // revoke all tokens when the server asks for input ⇒ round 1 gets 401
            fx.st.lock().unwrap().revoke_on_input_required = true;
        }
        r = resume(&src, "fx_greet", json!({}), ans).await;
    }
    assert_eq!(r.output, "Hello, toolnexus!");
    assert_eq!(input_asks, 1, "never re-asked");
    assert_eq!(fx.execs("greet"), 1);
    let calls = fx.calls("tools/call");
    let last = &calls.last().unwrap()["params"];
    assert_eq!(last["requestState"], "greet-1");
    assert_eq!(
        last["inputResponses"]["name"]["content"]["name"],
        "toolnexus"
    );
}

#[tokio::test]
async fn oauth_refresh_is_single_flight() {
    let (a, fx) = protected(Protect::All).await;
    let store = MemoryTokenStore::new();
    let src = Arc::new(
        load(
            auth_server(
                &fx.url,
                json!({"clientId": "dev-client", "allowHttp": true}),
            ),
            McpLoadOptions {
                open_url: Some(get_opener()),
                token_store: Some(store.clone()),
                ..opts()
            },
        )
        .await,
    );
    assert_eq!(src.status["fx"], McpStatus::Connected, "{:?}", src.errors);
    let mut t = store.load(&fx.url).unwrap();
    t.expires_at = Some(std::time::SystemTime::now());
    store.save(&fx.url, t);
    let mut set = tokio::task::JoinSet::new();
    for i in 0..100 {
        let src = src.clone();
        set.spawn(async move { call(&src, "fx_echo", json!({"text": format!("{i}")})).await });
    }
    while let Some(r) = set.join_next().await {
        assert!(!r.unwrap().is_error);
    }
    assert_eq!(a.stats().1, 1, "one refresh for 100 callers");
    assert_eq!(fx.execs("echo"), 100);
}

#[tokio::test]
async fn oauth_omit_refresh_resource() {
    for omit in [false, true] {
        let (a, fx) = protected(Protect::All).await;
        a.knobs(|k| k.reject_refresh_resource = true);
        let store = MemoryTokenStore::new();
        let src = load(
            auth_server(
                &fx.url,
                json!({"clientId": "dev-client", "allowHttp": true, "omitRefreshResource": omit}),
            ),
            McpLoadOptions {
                open_url: Some(get_opener()),
                token_store: Some(store.clone()),
                ..opts()
            },
        )
        .await;
        let mut t = store.load(&fx.url).unwrap();
        t.expires_at = Some(std::time::SystemTime::now());
        store.save(&fx.url, t);
        let r = call(&src, "fx_echo", json!({"text": "x"})).await;
        // without omit the refresh fails; the client then re-authorizes (auto) — both succeed,
        // but only `omit` succeeds by refresh alone
        assert!(!r.is_error, "{}", r.output);
        let (tokens, refreshes) = a.stats();
        assert_eq!(refreshes, 1);
        assert_eq!(tokens, if omit { 2 } else { 3 }, "omit={omit}");
    }
}

#[tokio::test]
async fn search_hit_input_schema_is_byte_exact_as_sent() {
    // a server whose schema has "type" before "properties" (not sorted)
    let s = common::serve(|r: common::Req| async move {
        let id = r.body["id"].to_string();
        let body = match r.body["method"].as_str().unwrap_or("") {
            "server/discover" => format!(r#"{{"jsonrpc":"2.0","id":{id},"result":{{"supportedVersions":["2026-07-28"],"capabilities":{{}}}}}}"#),
            "tools/list" => format!(r#"{{"jsonrpc":"2.0","id":{id},"result":{{"tools":[{{"name":"lookup","description":"Look up a word.","inputSchema":{{"type":"object","properties":{{"word":{{"type":"string"}}}},"required":["word"]}}}}]}}}}"#),
            _ => format!(r#"{{"jsonrpc":"2.0","id":{id},"error":{{"code":-32601,"message":"Method not found"}}}}"#),
        };
        common::Resp { status: 200, headers: vec![("Content-Type".into(), "application/json".into())], body }
    })
    .await;
    let src = load(json!({"raw": {"url": format!("{}/mcp", s.url), "toolSearch": true, "description": "words"}}), opts()).await;
    let r = call(&src, "raw_search_tools", json!({"query": "word"})).await;
    assert_eq!(
        r.output,
        r#"[{"name":"lookup","description":"Look up a word.","inputSchema":{"type":"object","properties":{"word":{"type":"string"}},"required":["word"]},"server":"words"}]"#
    );
}
