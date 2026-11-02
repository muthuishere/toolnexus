//! A2A outbound (SPEC §7A): mirrors golang/a2a_test.go. A loopback fake A2A
//! agent, no external network.

mod common;

use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::*;
use serde_json::{Value, json};
use toolnexus::a2a::{Agent, AgentConfig, parse_agents_config};
use toolnexus::{
    BuiltinsConfig, McpConfigInput, ToolContext, ToolSource, ToolkitOptions, create_toolkit,
};

#[derive(Default)]
struct Seen {
    send: AtomicI64,
    get: AtomicI64,
    auth: Mutex<String>,
}

struct StubTask {
    polls: i64,
    mode: &'static str,
}

/// A fake agent: card `reviewer` with skills review/plan/fail. Message text
/// picks the behaviour: fail ⇒ failed, slow ⇒ stays working, hangsend /
/// hangpoll ⇒ a 300 ms hang then 500, else completed(REVIEWED) after one
/// `working` poll.
async fn start_stub() -> (String, Arc<Seen>) {
    let seen = Arc::new(Seen::default());
    let tasks: Arc<Mutex<HashMap<String, StubTask>>> = Arc::default();
    let counter = Arc::new(AtomicI64::new(0));
    let base: Arc<Mutex<String>> = Arc::default();
    let (s2, b2) = (seen.clone(), base.clone());
    let srv = serve(move |r: Req| {
        let (seen, tasks, counter, base) = (s2.clone(), tasks.clone(), counter.clone(), b2.clone());
        async move {
            if r.method == "GET" && r.path == "/.well-known/agent-card.json" {
                let url = format!("{}/", base.lock().unwrap());
                return Resp::json(json!({
                    "name": "reviewer", "description": "a code reviewer", "version": "1.0.0",
                    "protocolVersion": "0.3.0",
                    "capabilities": {"streaming": false, "pushNotifications": false},
                    "defaultInputModes": ["text/plain"], "defaultOutputModes": ["text/plain"],
                    "url": url,
                    "skills": [
                        {"id": "review", "name": "Review", "description": "Review some code"},
                        {"id": "plan", "name": "Plan", "description": "Plan a task"},
                        {"id": "fail", "name": "Fail", "description": "Always fails"},
                    ],
                }));
            }
            if r.method != "POST" {
                return Resp::status(404, "nope");
            }
            *seen.auth.lock().unwrap() = r.headers.get("authorization").cloned().unwrap_or_default();
            let id = r.body["id"].clone();
            let ok = |result: Value| Resp::json(json!({"jsonrpc": "2.0", "id": id, "result": result}));
            match r.body["method"].as_str().unwrap_or("") {
                "SendMessage" => {
                    seen.send.fetch_add(1, Ordering::SeqCst);
                    let text: String = r.body["params"]["message"]["parts"]
                        .as_array()
                        .map(|ps| ps.iter().filter_map(|p| p["text"].as_str()).collect())
                        .unwrap_or_default();
                    if text.contains("hangsend") {
                        tokio::time::sleep(Duration::from_millis(300)).await;
                        return Resp::status(500, "stub: hangsend");
                    }
                    let mode = if text.contains("fail") {
                        "failed"
                    } else if text.contains("slow") {
                        "slow"
                    } else if text.contains("hangpoll") {
                        "hangpoll"
                    } else {
                        "completed"
                    };
                    let tid = format!("t{}", counter.fetch_add(1, Ordering::SeqCst) + 1);
                    tasks.lock().unwrap().insert(tid.clone(), StubTask { polls: 0, mode });
                    ok(json!({"id": tid, "status": {"state": "submitted"}}))
                }
                "GetTask" => {
                    seen.get.fetch_add(1, Ordering::SeqCst);
                    let tid = r.body["params"]["id"].as_str().unwrap_or("").to_owned();
                    let (polls, mode) = {
                        let mut g = tasks.lock().unwrap();
                        let t = g.get_mut(&tid).unwrap();
                        t.polls += 1;
                        (t.polls, t.mode)
                    };
                    if mode == "hangpoll" && polls >= 2 {
                        tokio::time::sleep(Duration::from_millis(300)).await;
                        return Resp::status(500, "stub: hangpoll");
                    }
                    if mode == "slow" || polls < 2 {
                        return ok(json!({"id": tid, "status": {"state": "working"}}));
                    }
                    if mode == "failed" {
                        return ok(json!({"id": tid, "status": {"state": "failed",
                            "message": {"role": "agent", "parts": [{"kind": "text", "text": "boom"}]}}}));
                    }
                    ok(json!({"id": tid, "status": {"state": "completed"},
                        "artifacts": [{"parts": [{"kind": "text", "text": "REVIEWED"}]}]}))
                }
                _ => Resp::json(json!({"jsonrpc": "2.0", "id": id,
                    "error": {"code": -32601, "message": "unknown method"}})),
            }
        }
    })
    .await;
    *base.lock().unwrap() = srv.url.clone();
    (srv.url, seen)
}

fn agent(card: &str, poll: u64) -> Agent {
    Agent {
        card: card.into(),
        poll_every_ms: poll,
        ..Default::default()
    }
}

async fn tk_agents(agents: Vec<Agent>) -> toolnexus::Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        agents,
        ..Default::default()
    })
    .await
    .unwrap()
}

fn card(base: &str) -> String {
    format!("{base}/.well-known/agent-card.json")
}

#[tokio::test]
async fn success_round_trip_and_env_header() {
    let (base, seen) = start_stub().await;
    // SAFETY: test-local env var, set before any thread reads it.
    unsafe { std::env::set_var("TN_A2A_TOKEN", "a2a-secret") };
    let mut ag = agent(&card(&base), 5);
    ag.headers = BTreeMap::from([("Authorization".into(), "Bearer ${TN_A2A_TOKEN}".into())]);
    let tk = tk_agents(vec![ag]).await;

    let review = tk.get("reviewer_review").expect("reviewer_review");
    assert_eq!(review.source, ToolSource::A2a);
    assert_eq!(serde_json::to_value(review.source).unwrap(), json!("a2a"));
    assert!(tk.get("reviewer_plan").is_some());
    let schema = serde_json::to_string(&tk.to_openai()).unwrap();
    assert!(schema.contains("reviewer_review") && schema.contains("reviewer_plan"));

    let r = tk
        .execute(
            "reviewer_review",
            json!({"task": "please review"}),
            ToolContext::default(),
        )
        .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "REVIEWED");
    assert_eq!(seen.send.load(Ordering::SeqCst), 1);
    assert!(seen.get.load(Ordering::SeqCst) >= 2);
    assert_eq!(*seen.auth.lock().unwrap(), "Bearer a2a-secret");
    let m = r.metadata.unwrap();
    assert_eq!(m["state"], "completed");
    assert_eq!(m["agent"], "reviewer");
    assert_ne!(m["taskId"], "");
}

#[tokio::test]
async fn failed_task_is_error() {
    let (base, _) = start_stub().await;
    let tk = tk_agents(vec![agent(&card(&base), 5)]).await;
    let r = tk
        .execute(
            "reviewer_fail",
            json!({"task": "please fail"}),
            ToolContext::default(),
        )
        .await;
    assert!(r.is_error);
    assert!(
        r.output.contains("failed") && r.output.contains("boom"),
        "{}",
        r.output
    );
    assert_eq!(r.metadata.unwrap()["state"], "failed");
}

#[tokio::test]
async fn cancel_stops_polling() {
    let (base, seen) = start_stub().await;
    let tk = tk_agents(vec![agent(&card(&base), 10)]).await;
    let ctx = ToolContext::default();
    let c = ctx.cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(35)).await;
        c.cancel();
    });
    let r = tk
        .execute("reviewer_review", json!({"task": "slow one"}), ctx)
        .await;
    assert!(r.is_error);
    assert_eq!(r.metadata.unwrap()["state"], "canceled");
    tokio::time::sleep(Duration::from_millis(50)).await;
    let after = seen.get.load(Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(80)).await;
    assert_eq!(
        seen.get.load(Ordering::SeqCst),
        after,
        "no polling after abort"
    );
}

async fn wait_calls(c: &AtomicI64, want: i64) {
    let deadline = Instant::now() + Duration::from_secs(2);
    while c.load(Ordering::SeqCst) < want && Instant::now() < deadline {
        tokio::time::sleep(Duration::from_millis(2)).await;
    }
    assert!(c.load(Ordering::SeqCst) >= want, "stub call count");
}

#[tokio::test]
async fn cancel_mid_get_task() {
    let (base, seen) = start_stub().await;
    let tk = tk_agents(vec![agent(&card(&base), 5)]).await;
    let ctx = ToolContext::default();
    let c = ctx.cancel.clone();
    let tk2 = tk.clone();
    let h = tokio::spawn(async move {
        tk2.execute("reviewer_review", json!({"task": "hangpoll"}), ctx)
            .await
    });
    wait_calls(&seen.get, 2).await;
    c.cancel();
    let r = h.await.unwrap();
    assert!(r.is_error);
    assert_eq!(r.output, "A2A task t1 canceled");
    assert_eq!(r.metadata.unwrap()["state"], "canceled");
    let after = seen.get.load(Ordering::SeqCst);
    tokio::time::sleep(Duration::from_millis(400)).await;
    assert_eq!(seen.get.load(Ordering::SeqCst), after);
}

#[tokio::test]
async fn cancel_mid_send_message() {
    let (base, seen) = start_stub().await;
    let tk = tk_agents(vec![agent(&card(&base), 5)]).await;
    let ctx = ToolContext::default();
    let c = ctx.cancel.clone();
    let tk2 = tk.clone();
    let h = tokio::spawn(async move {
        tk2.execute("reviewer_review", json!({"task": "hangsend"}), ctx)
            .await
    });
    wait_calls(&seen.send, 1).await;
    c.cancel();
    let r = h.await.unwrap();
    assert!(r.is_error);
    assert_eq!(r.output, "A2A task  canceled", "empty task id, js parity");
    assert_eq!(r.metadata.unwrap()["state"], "canceled");
    assert_eq!(seen.get.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn timeout_reports_state() {
    let (base, _) = start_stub().await;
    let tk = tk_agents(vec![agent(&card(&base), 5)]).await;
    let ctx = ToolContext {
        timeout_ms: 40,
        ..Default::default()
    };
    let r = tk
        .execute("reviewer_review", json!({"task": "slow"}), ctx)
        .await;
    assert!(r.is_error);
    assert!(
        r.output
            .starts_with("A2A task t1 timed out after 40ms (state=working)"),
        "{}",
        r.output
    );
}

#[tokio::test]
async fn config_block_enabled_disabled() {
    let (base, _) = start_stub().await;
    let mk = |entry: Value| async move {
        create_toolkit(ToolkitOptions {
            builtins: Some(BuiltinsConfig::off()),
            mcp_config: Some(McpConfigInput::Json(json!({"agents": {"rev": entry}}))),
            ..Default::default()
        })
        .await
        .unwrap()
    };
    let off = mk(json!({"card": card(&base), "disabled": true})).await;
    assert!(off.get("reviewer_review").is_none());
    let on = mk(json!({"card": card(&base), "pollEvery": 5})).await;
    assert!(on.get("reviewer_review").is_some());
}

#[tokio::test]
async fn add_agent_runtime() {
    let (base, _) = start_stub().await;
    let tk = tk_agents(vec![]).await;
    assert!(tk.get("reviewer_review").is_none());
    tk.add_agent(agent(&card(&base), 5)).await.unwrap();
    assert!(tk.get("reviewer_review").is_some());
    assert!(tk.get("reviewer_plan").is_some());
    // Bare card URL form (re-adds; first name wins).
    tk.add_agent(card(&base).as_str()).await.unwrap();
}

#[tokio::test]
async fn unreachable_agent_is_isolated() {
    let tk = tk_agents(vec![agent(
        "http://127.0.0.1:1/.well-known/agent-card.json",
        5,
    )])
    .await;
    assert!(tk.tools().is_empty());
}

#[test]
fn parse_agents_config_precedence() {
    let cfg = |card: &str, enabled: Option<bool>, disabled: Option<bool>| AgentConfig {
        card: card.into(),
        enabled,
        disabled,
        ..Default::default()
    };
    let parsed = parse_agents_config(&BTreeMap::from([
        ("a".into(), cfg("http://x/1", None, None)),
        ("b".into(), cfg("http://x/2", None, Some(true))),
        ("c".into(), cfg("http://x/3", Some(false), None)),
        ("d".into(), cfg("http://x/4", Some(true), Some(true))),
    ]));
    assert_eq!(parsed.len(), 1);
    assert_eq!(parsed[0].card, "http://x/1");
}
