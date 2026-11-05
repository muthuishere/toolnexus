//! conformance/acp-client/cases.json (SPEC §8C): the ACP client passthrough,
//! agent exit, unknown agent requests, ACP streaming through the in-process
//! seam, and `Toolkit::as_mcp_server`. Hermetic: the fake agent is
//! examples/fake_acp_agent.rs.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use toolnexus::acp::{AcpClient, AcpOptions};
use toolnexus::client::*;
use toolnexus::create_toolkit;

fn cases() -> Value {
    serde_json::from_str(
        &std::fs::read_to_string(
            PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../conformance/acp-client/cases.json"),
        )
        .unwrap(),
    )
    .unwrap()
}

fn fake() -> PathBuf {
    let mut p = std::env::current_exe().unwrap();
    p.pop();
    if p.ends_with("deps") {
        p.pop();
    }
    p.push("examples");
    p.push(if cfg!(windows) {
        "fake_acp_agent.exe"
    } else {
        "fake_acp_agent"
    });
    assert!(p.exists(), "fake agent not built at {p:?}");
    p
}

fn opts(fake_env: &Value) -> AcpOptions {
    let mut o = AcpOptions::new(fake().to_string_lossy())
        .args(["echo"])
        .cwd(std::env::temp_dir().canonicalize().unwrap());
    if let Some(m) = fake_env.as_object() {
        for (k, v) in m {
            o = o.env(k, v.as_str().unwrap());
        }
    }
    o
}

fn record_file(tag: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!(
        "tn-acp-record-{tag}-{}-{}.jsonl",
        std::process::id(),
        uuid_ish()
    ));
    let _ = std::fs::remove_file(&p);
    p
}

fn uuid_ish() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}

fn recorded(p: &PathBuf, method: &str) -> Option<Value> {
    std::fs::read_to_string(p)
        .ok()?
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .find(|v| v["method"] == method)
        .map(|v| v["params"].clone())
}

#[tokio::test]
async fn passthrough_sends_options_verbatim() {
    let case = cases()["passthrough"].clone();
    let o = &case["options"];
    let rec = record_file("pt");
    let mut opt = opts(&json!({})).env("TN_FAKE_RECORD", rec.to_string_lossy());
    opt.mcp_servers = o["mcpServers"].as_array().unwrap().clone();
    opt.session_meta = Some(o["sessionMeta"].clone());
    opt.client_caps_meta = Some(o["clientCapsMeta"].clone());
    opt.model = Some(o["model"].as_str().unwrap().into());
    let c = AcpClient::load(opt).await.unwrap();
    c.close().await;
    let init = recorded(&rec, "initialize").unwrap();
    assert_eq!(
        init["clientCapabilities"],
        case["expectInitializeClientCapabilities"]
    );
    let sn = recorded(&rec, "session/new").unwrap();
    assert_eq!(sn["mcpServers"], o["mcpServers"], "verbatim and in order");
    assert_eq!(sn["_meta"], o["sessionMeta"]);
    let sc = recorded(&rec, "session/set_config_option").unwrap();
    for (k, v) in case["expectSetConfigOption"].as_object().unwrap() {
        assert_eq!(&sc[k], v);
    }
    assert!(recorded(&rec, "session/set_model").is_none());
    let _ = std::fs::remove_file(&rec);
}

#[tokio::test]
async fn passthrough_falls_back_to_set_model() {
    let case = cases()["passthrough"].clone();
    let fb = &case["fallbackSetModel"];
    let rec = record_file("fb");
    let mut opt = opts(&fb["fakeEnv"]).env("TN_FAKE_RECORD", rec.to_string_lossy());
    opt.model = Some(case["options"]["model"].as_str().unwrap().into());
    let c = AcpClient::load(opt).await.unwrap();
    c.close().await;
    let sm = recorded(&rec, "session/set_model").unwrap();
    for (k, v) in fb["expectSetModel"].as_object().unwrap() {
        assert_eq!(&sm[k], v);
    }
    let _ = std::fs::remove_file(&rec);
}

#[tokio::test]
async fn passthrough_bare() {
    let bare = cases()["passthrough"]["bareExpect"].clone();
    let rec = record_file("bare");
    let opt = opts(&json!({})).env("TN_FAKE_RECORD", rec.to_string_lossy());
    let c = AcpClient::load(opt).await.unwrap();
    c.close().await;
    let sn = recorded(&rec, "session/new").unwrap();
    assert_eq!(sn["mcpServers"], bare["mcpServers"]);
    assert_eq!(
        sn.get("_meta").is_some(),
        bare["sessionNewHasMeta"].as_bool().unwrap()
    );
    let init = recorded(&rec, "initialize").unwrap();
    assert_eq!(
        init["clientCapabilities"].get("_meta").is_some(),
        bare["clientCapabilitiesHasMeta"].as_bool().unwrap()
    );
    assert!(recorded(&rec, "session/set_config_option").is_none());
    let _ = std::fs::remove_file(&rec);
}

#[tokio::test]
async fn agent_exit_fails_promptly() {
    let case = cases()["agentExit"].clone();
    let c = AcpClient::load(opts(&case["fakeEnv"])).await.unwrap();
    let t0 = Instant::now();
    let err = c
        .generate(&[("user".into(), "hi".into())])
        .await
        .unwrap_err();
    assert!(
        err.to_string()
            .contains(case["expectError"].as_str().unwrap()),
        "{err}"
    );
    assert!(t0.elapsed() < Duration::from_secs(10));
    c.close().await;
}

#[tokio::test]
async fn unknown_request_gets_method_not_found() {
    let case = cases()["unknownRequest"].clone();
    assert_eq!(case["expectCode"], json!(-32601));
    let c = AcpClient::load(opts(&case["fakeEnv"])).await.unwrap();
    let text = c.generate(&[("user".into(), "hi".into())]).await.unwrap();
    assert_eq!(text, case["expectText"].as_str().unwrap());
    c.close().await;
}

async fn empty_tk() -> toolnexus::Toolkit {
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap()
}

#[tokio::test]
async fn stream_acp_drip_is_incremental() {
    let case = cases()["stream"]["acpDrip"].clone();
    let acp = Arc::new(AcpClient::load(opts(&case["fakeEnv"])).await.unwrap());
    let c = create_in_process_client(InProcessOptions {
        generate_stream: Some(acp_generate_stream(acp.clone())),
        ..Default::default()
    });
    let tk = empty_tk().await;
    let mut rx = c.stream("go", &tk).unwrap();
    let (mut deltas, mut thoughts, mut first, mut done) = (Vec::new(), Vec::new(), None, None);
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => {
                first.get_or_insert_with(Instant::now);
                deltas.push(ev.delta.clone());
            }
            "thought" => {
                thoughts.push(json!({"type": "thought", "delta": ev.delta, "via": ev.via}))
            }
            "done" => done = Some((Instant::now(), ev.result)),
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    assert_eq!(json!(deltas), case["chunks"]);
    assert_eq!(thoughts, vec![case["expectThought"].clone()]);
    let (at, res) = done.expect("done");
    let lead = at - first.unwrap();
    assert!(
        lead >= Duration::from_millis(case["minLeadMs"].as_u64().unwrap()),
        "first delta only {lead:?} before done"
    );
    assert_eq!(res.unwrap().text, case["expectDoneText"].as_str().unwrap());
    let r = c.run("again", &tk).await.unwrap();
    assert_eq!(r.text, case["expectDoneText"].as_str().unwrap());
    acp.close().await;
}

#[tokio::test]
async fn plain_generate_still_refuses_stream() {
    assert_eq!(
        cases()["stream"]["inProcess"]["plainGenerateStillRefuses"],
        json!(true)
    );
    let g: GenerateFn = Arc::new(|_| {
        Box::pin(async {
            Ok(InProcessResponse {
                content: "x".into(),
                ..Default::default()
            })
        })
    });
    let c = create_in_process_client(InProcessOptions {
        generate: Some(g),
        ..Default::default()
    });
    let mut rx = c.stream("q", &empty_tk().await).unwrap();
    let mut errored = false;
    while let Some(ev) = rx.recv().await {
        if ev.kind == "error" {
            errored = true;
        }
    }
    assert!(errored, "a plain generate must not fake a stream");
}

#[cfg(feature = "serve")]
#[tokio::test]
async fn as_mcp_server_conformance() {
    let case = cases()["asMcpServer"].clone();
    let echo = toolnexus::define_tool(
        "echo",
        "echo back the text",
        Some(
            json!({"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}),
        ),
        |a: Value, _| async move { Ok::<_, String>(a["text"].as_str().unwrap_or("").to_owned()) },
    );
    let tk = create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        skills_dir: vec![PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples/skills")],
        extra_tools: vec![echo],
        ..Default::default()
    })
    .await
    .unwrap();
    let h = tk.as_mcp_server(None).await.unwrap();
    let h2 = tk.as_mcp_server(None).await.unwrap();
    let re = |k: &str| regex::Regex::new(case[k].as_str().unwrap()).unwrap();
    assert!(re("urlPattern").is_match(&h.url), "{}", h.url);
    assert!(re("tokenPattern").is_match(&h.token));
    assert!(!format!("{h:?}").contains(&h.token), "token never logged");
    if case["twoServersDistinctTokens"] == json!(true) {
        assert_ne!(h.token, h2.token);
    }
    h2.close();

    let http = reqwest::Client::new();
    let body = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}});
    let r = http.post(&h.url).json(&body).send().await.unwrap();
    assert_eq!(r.status().as_u16() as u64, case["noTokenStatus"]);
    assert_eq!(
        r.headers()["www-authenticate"],
        case["noTokenHeader"]["WWW-Authenticate"].as_str().unwrap()
    );
    let r = http
        .post(&h.url)
        .bearer_auth("0".repeat(64))
        .json(&body)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status().as_u16() as u64, case["wrongTokenStatus"]);
    let r = http.get(&h.url).bearer_auth(&h.token).send().await.unwrap();
    assert_eq!(r.status().as_u16() as u64, case["getWithTokenStatus"]);
    let other = h.url.replace("/mcp", "/other");
    let r = http
        .post(&other)
        .bearer_auth(&h.token)
        .json(&body)
        .send()
        .await
        .unwrap();
    assert_eq!(r.status().as_u16() as u64, case["otherPathStatus"]);

    // the port's own MCP client
    let consumer = create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        mcp_config: Some(toolnexus::McpConfigInput::Json(
            json!({"mcpServers": {"gw": {
                "type": "remote", "url": h.url,
                "headers": {"Authorization": format!("Bearer {}", h.token)}
            }}}),
        )),
        ..Default::default()
    })
    .await
    .unwrap();
    let names: Vec<String> = consumer.tools().iter().map(|t| t.name.clone()).collect();
    for want in case["toolsListContains"].as_array().unwrap() {
        let n = format!("gw_{}", want.as_str().unwrap());
        assert!(names.contains(&n), "{n} not in {names:?}");
    }
    let call = &case["callEcho"];
    let r = consumer
        .execute(
            &format!("gw_{}", call["name"].as_str().unwrap()),
            call["arguments"].clone(),
            toolnexus::ToolContext::default(),
        )
        .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, call["expectText"].as_str().unwrap());
    consumer.close().await;

    h.close();
    h.close(); // idempotent
    if case["afterCloseRefused"] == json!(true) {
        tokio::time::sleep(Duration::from_millis(50)).await;
        let r = http
            .post(&h.url)
            .bearer_auth(&h.token)
            .json(&body)
            .timeout(Duration::from_secs(2))
            .send()
            .await;
        assert!(r.is_err(), "listener still answering after close");
    }
}
