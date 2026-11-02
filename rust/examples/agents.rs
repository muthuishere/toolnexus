//! The §7D agent runtime, offline: a coordinator delegates two lookups to an
//! `explore` sub-agent through the `task` tool (parallel, context-isolated,
//! usage rolled up), then a child that needs approval parks durably and is
//! resumed with the host's Answer. Runs on an in-process model — no key, no
//! network. Mirrors the shared examples/subagent-* fixtures.
//!
//!     cargo run --example agents

use std::sync::Arc;

use serde_json::{Value, json};
use toolnexus::agents::{self, Options, Spec};
use toolnexus::client::{
    GenerateFn, InProcessRequest, InProcessResponse, InProcessToolCall, InProcessUsage,
};

const USAGE: InProcessUsage = InProcessUsage {
    prompt_tokens: 30,
    completion_tokens: 10,
    total_tokens: 40,
};
use toolnexus::{Answer, Request, ToolResult, define_tool, pending};

fn tool_msgs(req: &InProcessRequest) -> Vec<String> {
    req.messages
        .iter()
        .filter(|m| m["role"] == "tool")
        .map(|m| m["content"].as_str().unwrap_or("").to_string())
        .collect()
}

fn call(name: &str, args: Value, id: &str) -> InProcessToolCall {
    InProcessToolCall {
        id: id.into(),
        name: name.into(),
        arguments: args,
    }
}

/// One scripted model per agent, keyed by the model id each agent declares.
fn model() -> GenerateFn {
    Arc::new(|req: InProcessRequest| {
        Box::pin(async move {
            let done = tool_msgs(&req);
            let calls = |c: Vec<InProcessToolCall>| InProcessResponse {
                tool_calls: c,
                usage: Some(USAGE),
                ..Default::default()
            };
            let say = |s: String| InProcessResponse {
                content: s,
                usage: Some(USAGE),
                ..Default::default()
            };
            Ok(match (req.model.as_str(), done.is_empty()) {
                ("m-coordinator", true) => calls(vec![
                    call(
                        "task",
                        json!({"agent": "explore", "prompt": "find A"}),
                        "c1",
                    ),
                    call(
                        "task",
                        json!({"agent": "explore", "prompt": "find B"}),
                        "c2",
                    ),
                ]),
                ("m-coordinator", false) => say(format!("synthesis: {}", done.join(" + "))),
                ("m-explore", true) => calls(vec![call("lookup", json!({"q": "x"}), "e1")]),
                ("m-explore", false) => say(format!("found:{}", done[0])),
                ("m-boss", true) => calls(vec![call(
                    "task",
                    json!({"agent": "asker", "prompt": "get the secret"}),
                    "p1",
                )]),
                ("m-boss", false) => say(format!("boss: {}", done.last().unwrap())),
                ("m-asker", _) if done.iter().any(|d| d.contains("secret-token")) => {
                    say("asker-done: secret-token".into())
                }
                ("m-asker", _) => calls(vec![call("check_secret", json!({}), "a1")]),
                _ => say("ok".into()),
            })
        })
    })
}

#[tokio::main]
async fn main() {
    let lookup = define_tool(
        "lookup",
        "look something up",
        None,
        |a: Value, _| async move { Ok::<_, String>(format!("data({})", a["q"].as_str().unwrap_or(""))) },
    );
    let explore = agents::new(
        "explore",
        Spec {
            does: "read-only research".into(),
            tools: vec![lookup],
            model: "m-explore".into(),
            ..Default::default()
        },
    );
    let coordinator = agents::new(
        "coordinator",
        Spec {
            does: "splits and delegates".into(),
            soul: "Coordinate. Delegate lookups.".into(),
            team: vec![explore],
            model: "m-coordinator".into(),
            ..Default::default()
        },
    );
    let opts = || Options {
        in_process: Some(model()),
        ..Default::default()
    };

    // 1. fan-out through the task tool
    let (r, rt) = coordinator.run(opts(), "answer using two lookups").await;
    println!(
        "[{}] {} (turns {}, subtree tokens {}, own {})",
        r.status, r.text, r.turns, r.total_tokens, r.own_tokens
    );
    for line in rt.trace() {
        println!("  trace: {line}");
    }

    // 2. a child suspends; nobody holds interpreter authority ⇒ durable pending
    let check = toolnexus::Tool {
        execute: Arc::new(|_, ctx| {
            Box::pin(async move {
                if ctx.answer.as_ref().is_some_and(|a| a.ok) {
                    return ToolResult::ok("secret-token");
                }
                pending(Request {
                    kind: "approval".into(),
                    prompt: "approve secret access?".into(),
                    ..Default::default()
                })
            })
        }),
        ..define_tool(
            "check_secret",
            "needs human approval",
            None,
            |_: Value, _| async { Ok::<_, String>(String::new()) },
        )
    };
    let asker = agents::new(
        "asker",
        Spec {
            does: "needs approvals".into(),
            tools: vec![check],
            model: "m-asker".into(),
            ..Default::default()
        },
    );
    let boss = agents::new(
        "boss",
        Spec {
            does: "delegates, no interpreter".into(),
            team: vec![asker],
            model: "m-boss".into(),
            ..Default::default()
        },
    );
    let (r, rt) = boss.run(opts(), "do the secret thing").await;
    let req = r.pending.clone().expect("a durable suspension");
    println!(
        "\n[{}] {:?} at path {}",
        r.status,
        req.prompt,
        req.data
            .as_ref()
            .map(|d| d["path"].to_string())
            .unwrap_or_default()
    );
    let resumed = rt
        .resume(Answer {
            id: req.id,
            ok: true,
            ..Default::default()
        })
        .await
        .expect("resume");
    println!(
        "[{}] {} (turns {})",
        resumed.status, resumed.text, resumed.turns
    );
    rt.close(&rt.root(), None).await;
}
