//! Shared hermetic scaffolding for the §7D–§7F tests: a scripted in-process
//! model (openai wire shape, keyed by `body.model`) standing in for Go's
//! `mockLLM` RoundTripper — zero network, zero cost.
#![allow(dead_code)]

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::agents::{Clock, Def, Handle, Runtime, State};
use toolnexus::client::{
    BoxFuture, GenerateFn, InProcessRequest, InProcessResponse, InProcessToolCall, InProcessUsage,
};
use toolnexus::{Tool, ToolResult, ToolSource, pending};

/// What the model was sent, parsed like Go's `parseReq`.
#[derive(Debug, Clone, Default)]
pub struct Parsed {
    pub model: String,
    pub messages: Vec<Value>,
    /// role=="tool" contents, in order.
    pub tool_msgs: Vec<String>,
    /// The (last) system message content.
    pub system: String,
    /// Content of the last message.
    pub last: String,
}

pub fn parse(req: &InProcessRequest) -> Parsed {
    let mut p = Parsed {
        model: req.model.clone(),
        messages: req.messages.clone(),
        ..Default::default()
    };
    for m in &req.messages {
        let content = m
            .get("content")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        match m.get("role").and_then(Value::as_str) {
            Some("tool") => p.tool_msgs.push(content),
            Some("system") => p.system = content,
            _ => {}
        }
    }
    if let Some(l) = req.messages.last() {
        p.last = l
            .get("content")
            .and_then(Value::as_str)
            .unwrap_or("")
            .into();
    }
    p
}

/// A scripted assistant message → the in-process answer (usage 30/10/40).
pub fn answer(msg: Value) -> InProcessResponse {
    answer_with(msg, 30, 10)
}

pub fn answer_with(msg: Value, prompt: i64, completion: i64) -> InProcessResponse {
    let tool_calls = msg
        .get("tool_calls")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .map(|c| InProcessToolCall {
                    id: c.get("id").and_then(Value::as_str).unwrap_or("").into(),
                    name: c["function"]["name"].as_str().unwrap_or("").into(),
                    arguments: c["function"]["arguments"].clone(),
                })
                .collect()
        })
        .unwrap_or_default();
    InProcessResponse {
        content: msg
            .get("content")
            .and_then(Value::as_str)
            .unwrap_or("")
            .into(),
        tool_calls,
        usage: Some(InProcessUsage {
            prompt_tokens: prompt,
            completion_tokens: completion,
            total_tokens: prompt + completion,
        }),
    }
}

pub fn text(s: &str) -> Value {
    json!({"role": "assistant", "content": s})
}

pub fn call_part(name: &str, args: Value, id: &str) -> Value {
    json!({"id": id, "type": "function", "function": {"name": name, "arguments": args.to_string()}})
}

pub fn calls(parts: Vec<Value>) -> Value {
    json!({"role": "assistant", "content": null, "tool_calls": parts})
}

pub fn call_tool(name: &str, id: &str) -> Value {
    calls(vec![call_part(name, json!({}), id)])
}

/// A generate fn from an async script over the parsed request.
pub fn script<F>(f: F) -> GenerateFn
where
    F: Fn(Parsed) -> BoxFuture<Result<Value, String>> + Send + Sync + 'static,
{
    let f = Arc::new(f);
    Arc::new(move |req: InProcessRequest| {
        let f = f.clone();
        Box::pin(async move { f(parse(&req)).await.map(answer) })
    })
}

/// A generate fn from a numbered (1-based) reply script; records every request.
#[derive(Clone, Default)]
pub struct Scripted {
    pub seen: Arc<Mutex<Vec<Parsed>>>,
}

impl Scripted {
    pub fn generate(&self, reply: impl Fn(usize) -> Value + Send + Sync + 'static) -> GenerateFn {
        let seen = self.seen.clone();
        let reply = Arc::new(reply);
        Arc::new(move |req: InProcessRequest| {
            let p = parse(&req);
            let n = {
                let mut s = seen.lock().unwrap();
                s.push(p);
                s.len()
            };
            let msg = reply(n);
            Box::pin(async move { Ok(answer_with(msg, 5, 5)) })
        })
    }
    pub fn n(&self) -> usize {
        self.seen.lock().unwrap().len()
    }
    pub fn models(&self) -> Vec<String> {
        self.seen
            .lock()
            .unwrap()
            .iter()
            .map(|p| p.model.clone())
            .collect()
    }
    pub fn systems(&self) -> Vec<String> {
        self.seen
            .lock()
            .unwrap()
            .iter()
            .map(|p| p.system.clone())
            .collect()
    }
}

fn item_lines(s: &str) -> usize {
    s.lines()
        .filter(|l| {
            let d: String = l.chars().take_while(|c| c.is_ascii_digit()).collect();
            !d.is_empty() && l[d.len()..].starts_with('.')
        })
        .count()
}

/// Go's `mockLLM`: the fixture models. `slow` parks m-slow until cancelled.
pub fn mock_llm(slow: CancellationToken) -> GenerateFn {
    script(move |p: Parsed| {
        let slow = slow.clone();
        Box::pin(async move {
            tokio::time::sleep(Duration::from_millis(5)).await;
            Ok(match p.model.as_str() {
                "m-coordinator" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![
                            call_part(
                                "task",
                                json!({"agent": "explore", "prompt": "find A"}),
                                "c1",
                            ),
                            call_part(
                                "task",
                                json!({"agent": "explore", "prompt": "find B"}),
                                "c2",
                            ),
                        ])
                    } else {
                        text(&format!("synthesis: {}", p.tool_msgs.join(" + ")))
                    }
                }
                "m-explore" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part("lookup", json!({"q": "x"}), "e1")])
                    } else {
                        text(&format!("found:{}", p.tool_msgs[0]))
                    }
                }
                "m-approver-parent" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part(
                            "task",
                            json!({"agent": "asker", "prompt": "get the secret"}),
                            "p1",
                        )])
                    } else {
                        text(&format!("parent-final: {}", p.tool_msgs.last().unwrap()))
                    }
                }
                "m-asker" => {
                    if p.tool_msgs.iter().any(|m| m.contains("secret-token")) {
                        text("asker-done: secret-token")
                    } else {
                        calls(vec![call_part("check_secret", json!({}), "a1")])
                    }
                }
                "m-peer" => text(&format!("processed {} items", item_lines(&p.last))),
                "m-count" => text(&format!("msgs:{}", p.messages.len())),
                "m-rogue" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part(
                            "task",
                            json!({"agent": "stranger", "prompt": "hi"}),
                            "r1",
                        )])
                    } else {
                        text(&format!("rogue-saw: {}", p.tool_msgs[0]))
                    }
                }
                "m-loop" => calls(vec![call_part(
                    "lookup",
                    json!({"q": "again"}),
                    &format!("l{}", p.tool_msgs.len()),
                )]),
                "m-slow" => {
                    slow.cancelled().await;
                    text("slow-done")
                }
                _ => text("ok"),
            })
        })
    })
}

/// Go's `mockUX`: the Level-1 / bridge models.
pub fn mock_ux() -> GenerateFn {
    script(|p: Parsed| {
        Box::pin(async move {
            tokio::time::sleep(Duration::from_millis(2)).await;
            Ok(match p.model.as_str() {
                "m-coder" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part(
                            "task",
                            json!({"agent": "explore", "prompt": "find the bug"}),
                            "t1",
                        )])
                    } else {
                        let soul = if p.system.contains("You are the CODER") {
                            "loaded"
                        } else {
                            "missing"
                        };
                        text(&format!("fixed using: {} [soul:{soul}]", p.tool_msgs[0]))
                    }
                }
                "m-explore" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part("lookup", json!({"q": "bug"}), "e1")])
                    } else {
                        text(&format!("bug at line 42 ({})", p.tool_msgs[0]))
                    }
                }
                "m-mia" => {
                    if p.last.contains("Heartbeat") {
                        let ticks = p.last.contains("channel=timer");
                        if p.system.contains("water the plants") && ticks {
                            text("Reminder: water the plants!")
                        } else {
                            text("HEARTBEAT_OK")
                        }
                    } else {
                        let found: Vec<&str> = ["SOUL.md", "USER.md", "MEMORY.md"]
                            .into_iter()
                            .filter(|f| p.system.contains(&format!("## {f}")))
                            .collect();
                        text(&format!("soul-sections:[{}]", found.join(",")))
                    }
                }
                "m-old-api" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part(
                            "explore",
                            json!({"prompt": "scan the repo"}),
                            "b1",
                        )])
                    } else {
                        text(&format!("old-api summary: {}", p.tool_msgs[0]))
                    }
                }
                _ => text("ok"),
            })
        })
    })
}

pub fn lookup_tool() -> Tool {
    toolnexus::define_tool(
        "lookup",
        "look something up",
        Some(json!({"type": "object", "properties": {"q": {"type": "string"}}})),
        |args: Value, _| async move {
            let q = match args.get("q") {
                Some(Value::String(s)) => s.clone(),
                Some(v) => v.to_string(),
                None => "<nil>".into(),
            };
            Ok::<_, String>(format!("data({q})"))
        },
    )
}

pub fn custom_tool(
    name: &str,
    desc: &str,
    f: impl Fn(Value, toolnexus::ToolContext) -> BoxFuture<ToolResult> + Send + Sync + 'static,
) -> Tool {
    Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: name.into(),
        description: desc.into(),
        input_schema: json!({"type": "object", "properties": {}}),
        source: ToolSource::Custom,
        hints: None,
        execute: Arc::new(f),
    }
}

pub fn check_secret_tool() -> Tool {
    custom_tool("check_secret", "needs human approval", |_, ctx| {
        Box::pin(async move {
            if ctx.answer.as_ref().is_some_and(|a| a.ok) {
                return ToolResult::ok("secret-token");
            }
            pending(toolnexus::Request {
                kind: "approval".into(),
                prompt: "approve secret access?".into(),
                ..Default::default()
            })
        })
    })
}

pub fn def(name: &str, does: &str, soul: &str, model: &str) -> Def {
    Def {
        name: name.into(),
        does: does.into(),
        soul: soul.into(),
        model: model.into(),
        ..Default::default()
    }
}

/// Mirrors the shared fixture registries (team wiring included).
pub fn test_registry() -> std::collections::HashMap<String, Def> {
    let mut m = std::collections::HashMap::new();
    let mut add = |d: Def| {
        m.insert(d.name.clone(), d);
    };
    add(Def {
        team: vec!["explore".into()],
        ..def(
            "coordinator",
            "splits and delegates",
            "coordinate",
            "m-coordinator",
        )
    });
    add(Def {
        tools: vec![lookup_tool()],
        ..def("explore", "read-only research", "explore", "m-explore")
    });
    add(Def {
        team: vec!["asker".into()],
        ..def(
            "approverParent",
            "delegates, holds approval authority",
            "",
            "m-approver-parent",
        )
    });
    add(Def {
        tools: vec![check_secret_tool()],
        ..def("asker", "needs approvals", "", "m-asker")
    });
    add(def("peer", "team member", "", "m-peer"));
    add(def(
        "counter",
        "reports transcript length",
        "count",
        "m-count",
    ));
    add(Def {
        team: vec!["explore".into()],
        ..def("rogue", "tasks outside its team", "", "m-rogue")
    });
    add(Def {
        tools: vec![lookup_tool()],
        ..def("looper", "never finishes", "", "m-loop")
    });
    add(def("slow", "slow worker", "", "m-slow"));
    m
}

pub fn trace_has(rt: &Runtime, sub: &str) -> bool {
    rt.trace().iter().any(|l| l.contains(sub))
}

pub async fn await_state(rt: &Runtime, h: &Handle, want: State) -> bool {
    for _ in 0..400 {
        if rt.state_of(h) == want {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(2)).await;
    }
    false
}

/// A minimal virtual clock: `after` fires only on `fire()`.
pub struct FakeClock {
    base: Instant,
    inner: Mutex<(Duration, Vec<tokio::sync::oneshot::Sender<()>>)>,
}

impl FakeClock {
    pub fn new() -> Arc<Self> {
        Arc::new(FakeClock {
            base: Instant::now(),
            inner: Mutex::new((Duration::ZERO, Vec::new())),
        })
    }
    pub fn fire(&self) {
        let mut g = self.inner.lock().unwrap();
        g.0 += Duration::from_secs(3600);
        for tx in g.1.drain(..) {
            let _ = tx.send(());
        }
    }
    pub fn armed(&self) -> usize {
        self.inner.lock().unwrap().1.len()
    }
    pub async fn wait_armed(&self) {
        for _ in 0..500 {
            if self.armed() > 0 {
                return;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
        panic!("the virtual clock was never armed");
    }
}

impl Clock for FakeClock {
    fn now(&self) -> Instant {
        self.base + self.inner.lock().unwrap().0
    }
    fn after(&self, _d: Duration) -> BoxFuture<()> {
        let (tx, rx) = tokio::sync::oneshot::channel();
        self.inner.lock().unwrap().1.push(tx);
        Box::pin(async move {
            if rx.await.is_err() {
                std::future::pending::<()>().await;
            }
        })
    }
}
