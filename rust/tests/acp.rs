//! Phase 1: ACP client against the scripted fake agent (examples/fake_acp_agent.rs).
//! Hermetic: no network, no real agent CLI.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use toolnexus::acp::{
    AcpClient, AcpError, AcpOptions, AcpStreamEvent, PermissionOutcome, SUPERSEDES_MARKER,
    permission_handler, render_prompt,
};

fn fake() -> PathBuf {
    // cargo test builds examples next to the test binary's deps dir
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

fn opts(scenario: &str) -> AcpOptions {
    AcpOptions::new(fake().to_string_lossy())
        .args([scenario])
        .cwd(std::env::temp_dir().canonicalize().unwrap())
}

#[tokio::test]
async fn streams_every_update_kind_and_keeps_only_message_text() {
    let c = AcpClient::load(opts("basic")).await.unwrap();
    assert_eq!(c.session_id(), "sess-1");
    assert_eq!(c.agent_info()["agentInfo"]["name"], "fake");
    let mut turn = c.prompt("hi").await.unwrap();
    let mut kinds = Vec::new();
    while let Some(u) = turn.next().await {
        kinds.push(u.kind.clone());
    }
    assert_eq!(
        kinds,
        [
            "agent_thought_chunk",
            "tool_call",
            "tool_call_update",
            "plan",
            "agent_message_chunk",
            "agent_message_chunk"
        ]
    );
    let r = turn.finish().await.unwrap();
    assert_eq!(
        r.text, "Hello, world",
        "thoughts and tool narration never enter the reply"
    );
    assert_eq!(r.stop_reason, "end_turn");
    assert_eq!(r.updates.len(), 6);
    assert_eq!(r.updates[1].update["toolCallId"], "t1");
    c.close().await;
}

#[tokio::test]
async fn permission_default_picks_first_allow() {
    let c = AcpClient::load(opts("permission")).await.unwrap();
    let r = c.prompt_text("go").await.unwrap();
    assert_eq!(r.text, "chose:yes");
    c.close().await;
}

#[tokio::test]
async fn permission_host_callback_sees_all_options_and_decides() {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let s2 = seen.clone();
    let o = opts("permission").on_permission(permission_handler(move |req| {
        let s2 = s2.clone();
        async move {
            *s2.lock().unwrap() = req
                .options
                .iter()
                .map(|o| o.kind.clone())
                .collect::<Vec<_>>();
            assert_eq!(req.tool_call["title"], "rm -rf build");
            PermissionOutcome::Selected("always".into())
        }
    }));
    let c = AcpClient::load(o).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "chose:always");
    assert_eq!(
        *seen.lock().unwrap(),
        ["reject_once", "allow_once", "allow_always"]
    );
    c.close().await;

    let reject = opts("permission").on_permission(permission_handler(|_| async {
        PermissionOutcome::Cancelled
    }));
    let c = AcpClient::load(reject).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "chose:cancelled");
    c.close().await;
}

#[tokio::test]
async fn permission_callback_timeout_is_typed_and_answers_cancelled() {
    let mut o = opts("permission").on_permission(permission_handler(|_| async {
        tokio::time::sleep(Duration::from_secs(5)).await;
        PermissionOutcome::Selected("yes".into())
    }));
    o.permission_timeout = Duration::from_millis(100);
    let c = AcpClient::load(o).await.unwrap();
    let err = c.prompt_text("go").await.unwrap_err();
    assert!(matches!(err, AcpError::PermissionTimeout(_)), "{err}");
    c.close().await;
}

#[tokio::test]
async fn slow_cold_start_within_initialize_timeout() {
    let c = AcpClient::load(opts("echo").args(["echo", "800"]))
        .await
        .unwrap();
    assert_eq!(c.prompt_text("ping").await.unwrap().text, "ping");
    c.close().await;

    let mut short = opts("echo").args(["echo", "800"]);
    short.initialize_timeout = Duration::from_millis(100);
    let err = AcpClient::load(short).await.err().expect("must time out");
    assert!(
        matches!(err, AcpError::Timeout { ref method, .. } if method == "initialize"),
        "{err}"
    );
}

#[tokio::test]
async fn agent_exiting_mid_turn_ends_the_turn() {
    let c = AcpClient::load(opts("exit")).await.unwrap();
    let mut turn = c.prompt("go").await.unwrap();
    let first = turn.next().await.expect("the partial chunk arrives first");
    assert_eq!(first.text(), Some("partial"));
    let start = Instant::now();
    let err = turn.finish().await.unwrap_err();
    assert!(
        matches!(err, AcpError::AgentExited { code: Some(3) }),
        "{err}"
    );
    assert_eq!(err.to_string(), "toolnexus: acp: agent exited with code 3");
    assert!(start.elapsed() < Duration::from_secs(2));
    // the session is dead: the next prompt fails fast
    assert!(matches!(
        c.prompt_text("again").await.unwrap_err(),
        AcpError::AgentExited { .. }
    ));
    c.close().await;
}

#[tokio::test]
async fn cancel_stops_the_running_turn() {
    let c = AcpClient::load(opts("cancel")).await.unwrap();
    let mut turn = c.prompt("go").await.unwrap();
    assert_eq!(turn.next().await.unwrap().text(), Some("working"));
    c.cancel().await.unwrap();
    let r = turn.finish().await.unwrap();
    assert_eq!(r.stop_reason, "cancelled");
    c.close().await;
}

#[tokio::test]
async fn unsupported_agent_request_gets_method_not_found() {
    let c = AcpClient::load(opts("fsrequest")).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "fs:-32601");
    c.close().await;
}

#[tokio::test]
async fn relative_cwd_is_refused_before_spawn_and_agent_errors_surface() {
    let err = AcpClient::load(opts("basic").cwd("relative/dir"))
        .await
        .err()
        .unwrap();
    assert!(matches!(err, AcpError::Config(_)), "{err}");
    let err = AcpClient::load(AcpOptions::new("/nonexistent/toolnexus-no-agent"))
        .await
        .err()
        .unwrap();
    assert!(matches!(err, AcpError::Spawn { .. }), "{err}");
}

#[tokio::test]
async fn turns_are_serialized_on_one_session() {
    let c = Arc::new(AcpClient::load(opts("echo")).await.unwrap());
    let mut handles = Vec::new();
    for i in 0..5 {
        let c = c.clone();
        handles.push(tokio::spawn(async move {
            c.prompt_text(&format!("m{i}")).await.unwrap().text
        }));
    }
    let mut got: Vec<String> = Vec::new();
    for h in handles {
        got.push(h.await.unwrap());
    }
    got.sort();
    assert_eq!(
        got,
        ["m0", "m1", "m2", "m3", "m4"],
        "no turn's chunks leak into another"
    );
    c.close().await;
}

#[tokio::test]
async fn generate_renders_like_go() {
    let msgs = vec![
        ("system".to_string(), "be brief".to_string()),
        ("user".to_string(), "first".to_string()),
        ("assistant".to_string(), "ok".to_string()),
        ("user".to_string(), "second".to_string()),
    ];
    let want = format!(
        "system: be brief\nuser: first\nassistant: ok\nuser: second\n\n{SUPERSEDES_MARKER} second"
    );
    assert_eq!(render_prompt(&msgs), want);
    let c = AcpClient::load(opts("echo")).await.unwrap();
    assert_eq!(c.generate(&msgs).await.unwrap(), want);
    c.close().await;
}

#[cfg(unix)]
#[tokio::test]
async fn close_kills_the_agent_process_group() {
    let c = AcpClient::load(opts("echo")).await.unwrap();
    let pgid = c.pid().unwrap() as i32;
    assert_eq!(
        unsafe { libc::killpg(pgid, 0) },
        0,
        "group alive before close"
    );
    c.close().await;
    assert_ne!(
        unsafe { libc::killpg(pgid, 0) },
        0,
        "process group gone after close"
    );
    c.close().await; // idempotent
    assert!(matches!(
        c.prompt_text("x").await.unwrap_err(),
        AcpError::AgentExited { .. }
    ));
}

// ---------------------------------------------------------------------------
// 0.22.0 host hooks (mirrors golang/acp_hooks_test.go)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn on_permission_no_decision_falls_back_to_first_reject() {
    // Go: OnPermission error ⇒ first reject-kind option
    let o = opts("permission")
        .on_permission(permission_handler(|_| async { PermissionOutcome::Reject }));
    let c = AcpClient::load(o).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "chose:no");
    c.close().await;
}

#[tokio::test]
async fn on_permission_empty_answer_falls_back_to_first_reject() {
    let o = opts("permission").on_permission(permission_handler(|_| async {
        PermissionOutcome::Selected(String::new())
    }));
    let c = AcpClient::load(o).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "chose:no");
    c.close().await;
}

#[tokio::test]
async fn on_permission_empty_without_reject_option_cancels() {
    let o = opts("permission_noreject")
        .on_permission(permission_handler(|_| async { PermissionOutcome::Reject }));
    let c = AcpClient::load(o).await.unwrap();
    assert_eq!(c.prompt_text("go").await.unwrap().text, "chose:cancelled");
    c.close().await;
}

#[tokio::test]
async fn on_update_sees_every_update_with_raw_params() {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let s2 = seen.clone();
    let o = opts("basic").on_update(move |p| s2.lock().unwrap().push(p.clone()));
    let c = AcpClient::load(o).await.unwrap();
    let r = c.prompt_text("hi").await.unwrap();
    assert_eq!(r.text, "Hello, world", "the hook does not change the reply");
    let seen = seen.lock().unwrap().clone();
    let kinds: Vec<&str> = seen
        .iter()
        .map(|p| p["update"]["sessionUpdate"].as_str().unwrap())
        .collect();
    assert_eq!(
        kinds,
        [
            "agent_thought_chunk",
            "tool_call",
            "tool_call_update",
            "plan",
            "agent_message_chunk",
            "agent_message_chunk"
        ]
    );
    assert!(seen.iter().all(|p| p["sessionId"] == "sess-1"));
    c.close().await;
}

#[tokio::test]
async fn generate_cancellable_sends_session_cancel_and_returns_cancelled() {
    let c = AcpClient::load(opts("cancel")).await.unwrap();
    let token = tokio_util::sync::CancellationToken::new();
    let t2 = token.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(200)).await;
        t2.cancel();
    });
    let start = Instant::now();
    let err = c
        .generate_cancellable(&[("user".into(), "work".into())], &token)
        .await
        .unwrap_err();
    assert!(matches!(err, AcpError::Cancelled), "{err:?}");
    // the fake ends the turn only on session/cancel, so returning proves it was sent
    assert!(start.elapsed() < Duration::from_secs(5));
    c.close().await;
}

#[tokio::test]
async fn generate_cancellable_without_cancel_is_generate() {
    let c = AcpClient::load(opts("echo")).await.unwrap();
    let token = tokio_util::sync::CancellationToken::new();
    let msgs = [("user".to_owned(), "ping".to_owned())];
    let a = c.generate_cancellable(&msgs, &token).await.unwrap();
    let b = c.generate(&msgs).await.unwrap();
    assert_eq!(a, b);
    c.close().await;
}

#[tokio::test]
async fn generate_stream_emits_text_and_agent_tool_events_via_acp() {
    let c = AcpClient::load(opts("toolstream")).await.unwrap();
    let token = tokio_util::sync::CancellationToken::new();
    let mut events = Vec::new();
    let text = c
        .generate_stream(&[("user".into(), "go".into())], &token, |e| events.push(e))
        .await
        .unwrap();
    assert_eq!(text, "done");
    assert_eq!(
        events,
        vec![
            AcpStreamEvent::ToolCall {
                id: "t1".into(),
                name: "Read file".into(),
                args: serde_json::json!({"path": "a.txt"}),
                via: "acp"
            },
            AcpStreamEvent::ToolResult {
                id: "t1".into(),
                name: "Read file".into(),
                output: "file body".into(),
                is_error: false,
                via: "acp"
            },
            AcpStreamEvent::ToolCall {
                id: "t2".into(),
                name: "execute".into(),
                args: serde_json::Value::Null,
                via: "acp"
            },
            AcpStreamEvent::ToolResult {
                id: "t2".into(),
                name: "execute".into(),
                output: "boom".into(),
                is_error: true,
                via: "acp"
            },
            AcpStreamEvent::Text {
                delta: "done".into()
            },
        ],
        "thoughts and in-progress updates are not emitted"
    );
    c.close().await;
}

#[tokio::test]
async fn client_streams_an_acp_agent_through_the_in_process_seam() {
    use toolnexus::client::{
        InProcessOptions, acp_generate, acp_generate_stream, create_in_process_client,
    };
    let c = Arc::new(AcpClient::load(opts("toolstream")).await.unwrap());
    let client = create_in_process_client(InProcessOptions {
        generate: Some(acp_generate(c.clone())),
        generate_stream: Some(acp_generate_stream(c.clone())),
        ..Default::default()
    });
    let tk = toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap();
    let mut rx = client.stream("go", &tk).unwrap();
    let (mut text, mut calls, mut results) = (String::new(), Vec::new(), 0);
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => text.push_str(&ev.delta),
            "tool_call" => {
                assert_eq!(ev.via, "acp");
                calls.push(ev.name.clone());
            }
            "tool_result" => results += 1,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    assert_eq!(text, "done");
    assert_eq!(calls, vec!["Read file", "execute"]);
    assert_eq!(results, 2);
    c.close().await;
}
