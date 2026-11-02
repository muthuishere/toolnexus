//! Level-1 surface, the Loop door, completion gate, guardrails, §8 seams on an
//! agent run (examples/agent-hooks H1–H7) and the consumer-issue regressions.
//! Mirrors golang/agents/{agent,astool_ctx,hooks,loop,consumer_issues}_test.go.
//! Hermetic: in-process models for the runtime, a loopback provider for the
//! Loop door (which takes caller-built ClientOptions).

mod agents_support;
mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use agents_support::*;
use common::{Resp, empty_toolkit, serve};
use serde_json::{Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::agents::{
    self, Budget, Completion, Def, Guardrail, LIMIT_COMPLETION, LIMIT_MAX_CHILDREN,
    LIMIT_MAX_CONCURRENT, LIMIT_MAX_DEPTH, LIMIT_MAX_TOKENS, LIMIT_MAX_TOOL_CALLS, LIMIT_MAX_TURNS,
    LIMIT_MAX_WALL_MS, LIMIT_TIMEOUT, Options, RunOpts, Runtime, Spec, State, TASK_STATUS_CLOSED,
    TASK_STATUS_DONE, TASK_STATUS_ERROR, TASK_STATUS_INCOMPLETE, TASK_STATUS_INTERRUPTED,
    TASK_STATUS_PENDING, TASK_STATUS_TIMEOUT, TaskResult,
};
use toolnexus::client::{
    BeforeLlmEvent, BeforeToolEvent, ClientOptions, Hooks, LlmOverride, MetricEvent, Style,
    ToolOverride,
};
use toolnexus::store::{ConversationStore, InMemoryConversationStore};
use toolnexus::{
    Answer, BuiltinsConfig, Request, Tool, ToolContext, ToolHints, ToolResult, ToolkitOptions,
    create_toolkit, pending,
};

fn opts_ux() -> Options {
    Options {
        in_process: Some(mock_ux()),
        ..Default::default()
    }
}

// ---- Level-1 (S10–S13) -------------------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn level1_coding_agent_with_soul_file() {
    let dir = std::env::temp_dir().join(format!("tn-soul-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let soul = dir.join("AGENTS.md");
    std::fs::write(&soul, "You are the CODER. Fix things surgically.").unwrap();
    let explore = agents::new(
        "explore",
        Spec {
            does: "read-only research".into(),
            tools: vec![lookup_tool()],
            model: "m-explore".into(),
            ..Default::default()
        },
    );
    let coder = agents::new(
        "coder",
        Spec {
            does: "implements changes".into(),
            soul_file: soul.to_string_lossy().into(),
            team: vec![explore],
            model: "m-coder".into(),
            budget: Some(Budget {
                max_tokens: 10_000,
                ..Default::default()
            }),
            ..Default::default()
        },
    );
    let (r, _) = coder.run(opts_ux(), "fix the failing test").await;
    assert!(
        r.status == "done" && r.text.contains("bug at line 42"),
        "{r:?}"
    );
    assert!(r.text.contains("[soul:loaded]"), "{}", r.text);
}

#[tokio::test]
async fn team_scoping_registry_closure() {
    let _stranger = agents::new(
        "stranger",
        Spec {
            does: "should be unreachable".into(),
            ..Default::default()
        },
    );
    let explore = agents::new(
        "explore",
        Spec {
            does: "read-only research".into(),
            tools: vec![lookup_tool()],
            model: "m-explore".into(),
            ..Default::default()
        },
    );
    let coder = agents::new(
        "coder",
        Spec {
            does: "implements".into(),
            team: vec![explore],
            model: "m-coder".into(),
            ..Default::default()
        },
    );
    let reg = coder.registry();
    let mut keys: Vec<&String> = reg.keys().collect();
    keys.sort();
    assert_eq!(keys, vec!["coder", "explore"]);
    assert_eq!(reg["coder"].team, vec!["explore".to_string()]);
    assert!(reg["explore"].team.is_empty());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn persona_heartbeat_userland_pattern() {
    let dir = std::env::temp_dir().join(format!("tn-mia-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    for (n, b) in [
        ("SOUL.md", "You are Mia. Warm, brief."),
        ("USER.md", "The user is Muthu."),
        ("MEMORY.md", "- Likes green tea."),
        (
            "HEARTBEAT.md",
            "On heartbeat: if it is watering day, remind to water the plants.",
        ),
    ] {
        std::fs::write(dir.join(n), b).unwrap();
    }
    let mut sections = Vec::new();
    for f in agents::BOOTSTRAP_ORDER {
        if let Ok(b) = std::fs::read_to_string(dir.join(f)) {
            sections.push(format!("## {f}\n\n{}", b.trim()));
        }
    }
    let mia = agents::new(
        "mia",
        Spec {
            does: format!("persona agent from {}", dir.display()),
            soul: sections.join("\n\n"),
            model: "m-mia".into(),
            ..Default::default()
        },
    );
    let (direct, _) = mia.run(opts_ux(), "hello").await;
    assert_eq!(direct.text, "soul-sections:[SOUL.md,USER.md,MEMORY.md]");

    let rt = Runtime::new(Options {
        registry: mia.registry(),
        ..opts_ux()
    });
    let h = rt.spawn(&rt.root(), "mia", None).unwrap();
    let hb = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.";
    let mut reports = Vec::new();
    for _ in 0..3 {
        rt.post(&h, agents::InboxItem::new("clock", "timer", "tick"));
        if rt.state_of(&h) == State::Idle {
            let r = rt.run_turn(&h, hb).await;
            if !r.is_error && !r.text.contains("HEARTBEAT_OK") {
                reports.push(r.text);
            }
        }
    }
    let beats = rt
        .trace()
        .iter()
        .filter(|l| l.contains("idle→running"))
        .count();
    assert!(beats >= 2, "{beats}");
    for rep in reports {
        assert!(rep.contains("water"), "{rep}");
    }
    rt.close(&rt.root(), None).await;
    assert_eq!(rt.state_of(&h), State::Closed);
}

/// A loopback provider whose replies come from `reply(n, body)`; usage 5/5/10.
async fn provider(
    reply: impl Fn(usize, &Value) -> Value + Send + Sync + 'static,
) -> (String, Arc<Mutex<Vec<Value>>>) {
    let seen = Arc::new(Mutex::new(Vec::<Value>::new()));
    let s = seen.clone();
    let reply = Arc::new(reply);
    let srv = serve(move |req| {
        let (s, reply) = (s.clone(), reply.clone());
        async move {
            let n = {
                let mut g = s.lock().unwrap();
                g.push(req.body.clone());
                g.len()
            };
            let msg = reply(n, &req.body);
            Resp::json(json!({
                "choices": [{"message": msg}],
                "usage": {"prompt_tokens": 5, "completion_tokens": 5, "total_tokens": 10},
            }))
        }
    })
    .await;
    (srv.url, seen)
}

fn client_opts(url: &str) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        style: Style::OpenAI,
        model: "m".into(),
        api_key: "x".into(),
        retries: -1,
        ..Default::default()
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn as_tool_inside_classic_api() {
    let explore = agents::new(
        "explore",
        Spec {
            does: "read-only research".into(),
            tools: vec![lookup_tool()],
            model: "m-explore".into(),
            ..Default::default()
        },
    );
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![explore.as_tool(opts_ux())],
        ..Default::default()
    })
    .await
    .unwrap();
    let client = toolnexus::client::create_in_process_client(toolnexus::client::InProcessOptions {
        model: "m-old-api".into(),
        generate: Some(mock_ux()),
        ..Default::default()
    });
    let r = client.run("summarize", &tk).await.unwrap();
    assert!(
        r.text.contains("old-api summary:") && r.text.contains("bug at line 42"),
        "{}",
        r.text
    );
    let meta = r.tool_calls[0].metadata.clone().unwrap();
    assert_eq!(meta["agent"], "explore");
    assert!(meta.contains_key("turns") && meta.contains_key("totalTokens"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn level1_durable_run_resume() {
    let asker = agents::new(
        "asker",
        Spec {
            does: "needs approvals".into(),
            tools: vec![check_secret_tool()],
            model: "m-asker".into(),
            ..Default::default()
        },
    );
    let boss = agents::new(
        "boss",
        Spec {
            does: "delegates, no interpreter".into(),
            team: vec![asker],
            model: "m-approver-parent".into(),
            ..Default::default()
        },
    );
    let (r, rt) = boss
        .run(
            Options {
                in_process: Some(mock_llm(CancellationToken::new())),
                ..Default::default()
            },
            "do the secret thing",
        )
        .await;
    assert_eq!(r.status, "pending");
    rt.resume(Answer {
        id: r.pending.unwrap().id,
        ok: true,
        ..Default::default()
    })
    .await
    .unwrap();
    assert!(trace_has(&rt, "task replay → REATTACH"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn task_description_sorted_team() {
    let mut reg = test_registry();
    reg.get_mut("coordinator").unwrap().team = vec!["explore".into(), "asker".into()];
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        ..Default::default()
    });
    // The description is what the model sees: capture it from the request.
    let seen = Arc::new(Mutex::new(String::new()));
    let s = seen.clone();
    let rt2 = Runtime::new(Options {
        in_process: Some(Arc::new(move |req: toolnexus::client::InProcessRequest| {
            let desc = req
                .tools
                .iter()
                .find(|t| t["function"]["name"] == "task")
                .map(|t| {
                    t["function"]["description"]
                        .as_str()
                        .unwrap_or("")
                        .to_string()
                })
                .unwrap_or_default();
            *s.lock().unwrap() = desc;
            Box::pin(async { Ok(answer(text("ok"))) })
        })),
        registry: {
            let mut r = test_registry();
            r.get_mut("coordinator").unwrap().team = vec!["explore".into(), "asker".into()];
            r
        },
        ..Default::default()
    });
    drop(rt);
    let h = rt2.spawn(&rt2.root(), "coordinator", None).unwrap();
    rt2.run_turn(&h, "go").await;
    let d = seen.lock().unwrap().clone();
    assert!(
        d.ends_with("asker: needs approvals; explore: read-only research"),
        "{d}"
    );
    assert!(d.starts_with("Delegate a subtask"), "{d}");
}

// ---- as_tool_with: calling ctx, gate and ask bridge ------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn as_tool_cancelled_with_calling_ctx() {
    let child = agents::new(
        "child",
        Spec {
            does: "d".into(),
            soul: "s".into(),
            ..Default::default()
        },
    );
    let blocking: toolnexus::client::GenerateFn = Arc::new(|_| {
        Box::pin(async {
            std::future::pending::<()>().await;
            unreachable!()
        })
    });
    let tool = child.as_tool(Options {
        in_process: Some(blocking),
        ..Default::default()
    });
    let cancel = CancellationToken::new();
    let c2 = cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        c2.cancel();
    });
    let start = std::time::Instant::now();
    let r = tool
        .call(
            json!({"prompt": "go"}),
            ToolContext {
                cancel,
                ..Default::default()
            },
        )
        .await;
    assert!(
        start.elapsed() < Duration::from_secs(3),
        "child not cancelled"
    );
    assert!(r.is_error && r.output.starts_with("cancelled"), "{r:?}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn as_tool_with_gate_and_ask_bridge() {
    let ran = Arc::new(AtomicUsize::new(0));
    let r2 = ran.clone();
    let mut danger = toolnexus::define_tool("danger", "mutates", None, move |_: Value, _| {
        let r2 = r2.clone();
        async move {
            r2.fetch_add(1, Ordering::SeqCst);
            Ok::<_, String>("did it".into())
        }
    });
    danger.hints = Some(ToolHints {
        destructive: true,
        ..Default::default()
    });
    let child = agents::new(
        "child",
        Spec {
            does: "d".into(),
            soul: "s".into(),
            tools: vec![danger],
            ..Default::default()
        },
    );
    let gated = Arc::new(Mutex::new(Vec::<String>::new()));
    let asked = Arc::new(AtomicUsize::new(0));
    let (g, a) = (gated.clone(), asked.clone());
    let tool = child.as_tool_with(Arc::new(move |_ctx| {
        let script = Scripted::default();
        let g = g.clone();
        let a = a.clone();
        Options {
            in_process: Some(script.generate(|n| {
                if n == 1 {
                    call_tool("danger", "d1")
                } else {
                    text("child done")
                }
            })),
            hooks: Some(Hooks {
                before_tool: Some(Arc::new(move |ev: BeforeToolEvent| {
                    g.lock().unwrap().push(ev.name.clone());
                    let ro = ev.hints.is_some_and(|h| h.read_only);
                    Box::pin(async move {
                        if !ro {
                            let p = pending(Request {
                                kind: "approval".into(),
                                prompt: format!("allow {}?", ev.name),
                                ..Default::default()
                            });
                            return Ok(Some(ToolOverride {
                                result: Some(p),
                                ..Default::default()
                            }));
                        }
                        Ok(None)
                    })
                })),
                ..Default::default()
            }),
            wait_for: Some(Arc::new(move |req: Request| {
                a.fetch_add(1, Ordering::SeqCst);
                Box::pin(async move {
                    Ok(Answer {
                        id: req.id,
                        ok: true,
                        ..Default::default()
                    })
                })
            })),
            ..Default::default()
        }
    }));
    let r = tool
        .call(json!({"prompt": "go"}), ToolContext::default())
        .await;
    assert!(!r.is_error && r.output == "child done", "{r:?}");
    assert_eq!(*gated.lock().unwrap(), vec!["danger".to_string()]);
    assert_eq!(asked.load(Ordering::SeqCst), 1);
    assert_eq!(ran.load(Ordering::SeqCst), 1);
}

// ---- §8 seams on an agent run (examples/agent-hooks) -------------------------

type Sent = (String, Vec<Value>);

/// Records every request's messages by model; always answers "ok".
#[derive(Clone, Default)]
struct Recording {
    sent: Arc<Mutex<Vec<Sent>>>,
}

impl Recording {
    fn generate(&self) -> toolnexus::client::GenerateFn {
        let sent = self.sent.clone();
        Arc::new(move |req| {
            sent.lock()
                .unwrap()
                .push((req.model.clone(), req.messages.clone()));
            Box::pin(async { Ok(answer(text("ok"))) })
        })
    }
    fn last(&self, model: &str) -> Vec<Value> {
        self.sent
            .lock()
            .unwrap()
            .iter()
            .rev()
            .find(|(m, _)| m == model)
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }
    fn count(&self) -> usize {
        self.sent.lock().unwrap().len()
    }
}

fn hooks_reg(defs: Vec<Def>) -> std::collections::HashMap<String, Def> {
    defs.into_iter().map(|d| (d.name.clone(), d)).collect()
}

fn seed(store: &InMemoryConversationStore, id: &str, pairs: usize) {
    let mut msgs = vec![json!({"role": "system", "content": "SOUL-VERBATIM"})];
    for _ in 0..pairs {
        msgs.push(json!({"role": "user", "content": "q".repeat(200)}));
        msgs.push(json!({"role": "assistant", "content": "a".repeat(200)}));
    }
    store.save(id, &msgs).unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_runtime_hooks_run_in_agent_turn() {
    let rec = Recording::default();
    let saw = Arc::new(AtomicUsize::new(0));
    let s = saw.clone();
    let rt = Runtime::new(Options {
        in_process: Some(rec.generate()),
        registry: hooks_reg(vec![def("a", "x", "soul-a", "m-a")]),
        hooks: Some(Hooks {
            before_llm: Some(Arc::new(move |e: BeforeLlmEvent| {
                s.fetch_add(1, Ordering::SeqCst);
                let mut msgs = e.messages.clone();
                msgs.push(json!({"role": "system", "content": "INJECTED-BY-HOOK"}));
                Box::pin(async move {
                    Ok(Some(LlmOverride {
                        messages: Some(msgs),
                        ..Default::default()
                    }))
                })
            })),
            ..Default::default()
        }),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "a", None).unwrap();
    let r = rt.run_turn(&h, "hello").await;
    assert!(!r.is_error, "{}", r.text);
    assert!(saw.load(Ordering::SeqCst) > 0);
    assert!(
        rec.last("m-a")
            .iter()
            .any(|m| m["content"] == "INJECTED-BY-HOOK")
    );
    rt.close(&rt.root(), None).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_runtime_on_metric_receives_events() {
    let events = Arc::new(Mutex::new(Vec::<String>::new()));
    let e = events.clone();
    let rt = Runtime::new(Options {
        in_process: Some(Recording::default().generate()),
        registry: hooks_reg(vec![def("a", "x", "", "m-a")]),
        on_metric: Some(Arc::new(move |ev: &MetricEvent| {
            e.lock().unwrap().push(ev.event.clone())
        })),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "a", None).unwrap();
    assert!(!rt.run_turn(&h, "hi").await.is_error);
    let got = events.lock().unwrap().join(",");
    assert!(got.contains("llm") && got.contains("run"), "{got}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_def_hooks_replace_runtime_hooks() {
    let fired = Arc::new(Mutex::new(std::collections::HashMap::<String, usize>::new()));
    let mark = |tag: &str| {
        let (f, tag) = (fired.clone(), tag.to_string());
        Hooks {
            before_llm: Some(Arc::new(move |_e: BeforeLlmEvent| {
                *f.lock().unwrap().entry(tag.clone()).or_default() += 1;
                Box::pin(async { Ok(None) })
            })),
            ..Default::default()
        }
    };
    let metrics = Arc::new(AtomicUsize::new(0));
    let m = metrics.clone();
    let rt = Runtime::new(Options {
        in_process: Some(Recording::default().generate()),
        hooks: Some(mark("runtime")),
        on_metric: Some(Arc::new(move |_: &MetricEvent| {
            m.fetch_add(1, Ordering::SeqCst);
        })),
        registry: hooks_reg(vec![
            Def {
                hooks: Some(mark("defA")),
                ..def("a", "x", "", "m-a")
            },
            def("b", "x", "", "m-b"),
        ]),
        ..Default::default()
    });
    let ha = rt.spawn(&rt.root(), "a", None).unwrap();
    let hb = rt.spawn(&rt.root(), "b", None).unwrap();
    assert!(!rt.run_turn(&ha, "hi").await.is_error);
    assert!(!rt.run_turn(&hb, "hi").await.is_error);
    let f = fired.lock().unwrap();
    assert_eq!(f.get("defA"), Some(&1));
    assert_eq!(f.get("runtime"), Some(&1), "not merged into A");
    assert!(metrics.load(Ordering::SeqCst) >= 2);
}

fn summarize_to(s: &'static str) -> agents::SummarizeFn {
    Arc::new(move |_older| Box::pin(async move { Ok(s.to_string()) }))
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_compactor_attached_to_agent_run() {
    let rec = Recording::default();
    let store = Arc::new(InMemoryConversationStore::new());
    let comp = agents::compactor(agents::CompactorOptions {
        max_tokens: 200,
        keep_tail: 80,
        summarize: Some(summarize_to("SUMMARY-OF-HEAD")),
        ..Default::default()
    });
    let rt = Runtime::new(Options {
        in_process: Some(rec.generate()),
        store: Some(store.clone()),
        registry: hooks_reg(vec![Def {
            hooks: Some(Hooks {
                before_llm: Some(comp),
                ..Default::default()
            }),
            ..def("long", "x", "SOUL-VERBATIM", "m-a")
        }]),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "long", None).unwrap();
    seed(&store, &h.id, 40);
    let pre = store.get(&h.id).unwrap().unwrap();
    assert_eq!(pre.len(), 81);
    assert!(!rt.run_turn(&h, "next question").await.is_error);
    let sent = rec.last("m-a");
    assert!(sent.len() < pre.len());
    assert_eq!(sent[0]["content"], "SOUL-VERBATIM");
    assert!(Value::Array(sent).to_string().contains("SUMMARY-OF-HEAD"));
}

/// Recorded on golang main before the §8-seams change — the Rust run must be
/// byte-identical to it (serde_json maps sort keys, as Go's encoder does).
const GOLDEN_UNSET_RUN: &str = r#"{"result":{"status":"done","text":"ok","tokens":40,"turns":1},"stored":[{"content":"soul-a","role":"system"},{"content":"hello","role":"user"},{"content":"ok","role":"assistant"}],"trace":["root/a.1: spawned (depth 1, tokens Infinity)","root/a.1: idle→running (wake)","root/a.1: running→idle (done, turns=1, tokens=40)"],"wire":[{"content":"soul-a","role":"system"},{"content":"hello","role":"user"}]}"#;

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_unset_is_byte_identical_to_pre_change() {
    let rec = Recording::default();
    let store = Arc::new(InMemoryConversationStore::new());
    let rt = Runtime::new(Options {
        in_process: Some(rec.generate()),
        store: Some(store.clone()),
        registry: hooks_reg(vec![def("a", "x", "soul-a", "m-a")]),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "a", None).unwrap();
    let r = rt.run_turn(&h, "hello").await;
    let stored = store.get(&h.id).unwrap().unwrap();
    let got = json!({
        "wire": rec.last("m-a"),
        "stored": stored,
        "trace": rt.trace(),
        "result": {"text": r.text, "status": r.status, "turns": r.turns, "tokens": r.total_tokens},
    })
    .to_string();
    assert_eq!(got, GOLDEN_UNSET_RUN);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_compaction_then_pending_rewind() {
    let store = Arc::new(InMemoryConversationStore::new());
    let comp = agents::compactor(agents::CompactorOptions {
        max_tokens: 200,
        keep_tail: 80,
        summarize: Some(summarize_to("SUMMARY-OF-HEAD")),
        ..Default::default()
    });
    let mut reg = test_registry();
    reg.get_mut("asker").unwrap().hooks = Some(Hooks {
        before_llm: Some(comp),
        ..Default::default()
    });
    let rt = Runtime::new(Options {
        in_process: Some(mock_llm(CancellationToken::new())),
        registry: reg,
        store: Some(store.clone()),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "asker", None).unwrap();
    seed(&store, &h.id, 40);
    let pre = store.get(&h.id).unwrap().unwrap();
    let r = rt.run_turn(&h, "get the secret").await;
    assert_eq!(r.status, "pending", "{r:?}");
    let post = store.get(&h.id).unwrap().unwrap();
    assert_eq!(post.len(), pre.len(), "rewind to the pre-turn checkpoint");
}

fn failing_hooks() -> Hooks {
    Hooks {
        before_llm: Some(Arc::new(|_e: BeforeLlmEvent| {
            Box::pin(async { Err("hook boom".to_string()) })
        })),
        ..Default::default()
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_h7_failing_before_llm_fails_the_loop_run() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "failing",
        Spec {
            does: "x".into(),
            hooks: Some(failing_hooks()),
            ..Default::default()
        },
    );
    let err = a
        .loop_(client_opts(&url), empty_toolkit().await)
        .run(CancellationToken::new(), "go", RunOpts::default())
        .await
        .unwrap_err();
    assert!(err.to_string().contains("hook boom"), "{err}");
    assert_eq!(seen.lock().unwrap().len(), 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn agent_hooks_h7_failing_before_llm_is_an_error_result_at_the_handle_boundary() {
    let rec = Recording::default();
    let rt = Runtime::new(Options {
        in_process: Some(rec.generate()),
        registry: hooks_reg(vec![Def {
            hooks: Some(failing_hooks()),
            ..def("a", "x", "", "m-a")
        }]),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), "a", None).unwrap();
    let r = rt.run_turn(&h, "hello").await;
    assert!(
        r.is_error && r.status == "error" && r.text.contains("hook boom"),
        "{r:?}"
    );
    assert_eq!(rec.count(), 0);
}

// ---- the Loop, completion gate and guardrails --------------------------------------

fn call_todo(todos: Value) -> Value {
    calls(vec![call_part("todowrite", json!({"todos": todos}), "t1")])
}

fn todo(id: &str, t: &str, done: bool) -> Value {
    json!({"id": id, "text": t, "completed": done})
}

async fn todo_toolkit() -> toolnexus::Toolkit {
    let mut tools = std::collections::BTreeMap::new();
    for n in [
        "bash",
        "read",
        "write",
        "edit",
        "glob",
        "grep",
        "webfetch",
        "apply_patch",
        "question",
    ] {
        tools.insert(n.to_string(), false);
    }
    tools.insert("todowrite".to_string(), true);
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig {
            tools,
            ..Default::default()
        }),
        ..Default::default()
    })
    .await
    .unwrap()
}

async fn todowrite_tool() -> Tool {
    todo_toolkit().await.get("todowrite").unwrap()
}

fn seq_reply(replies: Vec<Value>) -> impl Fn(usize, &Value) -> Value + Send + Sync + 'static {
    move |n, _| replies[(n - 1).min(replies.len() - 1)].clone()
}

fn gate(max: usize) -> Option<Completion> {
    Some(Completion::new(agents::all_todos_done, max))
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn loop_without_completion_is_unchanged() {
    let (url, _) = provider(seq_reply(vec![text("hi")])).await;
    let a = agents::new(
        "plain",
        Spec {
            does: "answers".into(),
            soul: "be brief".into(),
            ..Default::default()
        },
    );
    let mut l = a.loop_(client_opts(&url), empty_toolkit().await);
    let out = l
        .run(CancellationToken::new(), "hello", RunOpts::default())
        .await
        .unwrap();
    assert!(
        out.status == "done" && out.attempts == 1 && out.stopped_by.is_empty(),
        "{out:?}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn completion_gate_blocks_then_passes() {
    let (url, _) = provider(seq_reply(vec![
        call_todo(json!([
            todo("1", "draft", true),
            todo("2", "proofread", false)
        ])),
        text("claiming done"),
        call_todo(json!([
            todo("1", "draft", true),
            todo("2", "proofread", true)
        ])),
        text("really done"),
    ]))
    .await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            completion: gate(3),
            ..Default::default()
        },
    );
    let out = a
        .loop_(client_opts(&url), todo_toolkit().await)
        .run(CancellationToken::new(), "do it", RunOpts::default())
        .await
        .unwrap();
    assert_eq!(out.status, "done", "{out:?}");
    assert_eq!(out.attempts, 2);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn completion_gate_stops_loudly() {
    let (url, _) = provider(seq_reply(vec![
        call_todo(json!([todo("1", "never", false)])),
        text("done?"),
    ]))
    .await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            completion: gate(2),
            ..Default::default()
        },
    );
    let out = a
        .loop_(client_opts(&url), todo_toolkit().await)
        .run(CancellationToken::new(), "do it", RunOpts::default())
        .await
        .unwrap();
    assert_eq!(out.status, "incomplete");
    assert_eq!(out.attempts, 2);
    assert!(
        out.stopped_by.contains("failed 2×") && out.stopped_by.contains("never"),
        "{}",
        out.stopped_by
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn completion_requires_bound() {
    let (url, _) = provider(seq_reply(vec![text("x")])).await;
    let a = agents::new(
        "w",
        Spec {
            does: "w".into(),
            completion: gate(0),
            ..Default::default()
        },
    );
    assert!(
        a.loop_(client_opts(&url), empty_toolkit().await)
            .run(CancellationToken::new(), "go", RunOpts::default())
            .await
            .is_err()
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn no_plan_passes() {
    let (url, _) = provider(seq_reply(vec![text("no plan here")])).await;
    let a = agents::new(
        "w",
        Spec {
            does: "w".into(),
            completion: gate(2),
            ..Default::default()
        },
    );
    let out = a
        .loop_(client_opts(&url), empty_toolkit().await)
        .run(CancellationToken::new(), "go", RunOpts::default())
        .await
        .unwrap();
    assert!(out.status == "done" && out.attempts == 1);
}

#[tokio::test]
async fn guardrail_first_deny_wins() {
    let executed = Arc::new(AtomicUsize::new(0));
    let ex = executed.clone();
    let deploy = custom_tool("deploy", "deploys", move |_, _| {
        let ex = ex.clone();
        Box::pin(async move {
            ex.fetch_add(1, Ordering::SeqCst);
            ToolResult::ok("DEPLOYED")
        })
    });
    let deny: Guardrail = Arc::new(|_| "blocked by policy".into());
    let allow: Guardrail = Arc::new(|_| "allow".into());
    let a = agents::new(
        "ops",
        Spec {
            does: "ops".into(),
            tools: vec![deploy],
            guardrails: vec![deny, allow],
            ..Default::default()
        },
    );
    let d = a.registry()["ops"].clone();
    let bt = d
        .hooks
        .and_then(|h| h.before_tool)
        .expect("guardrails reach the Def");
    let ov = bt(BeforeToolEvent {
        name: "deploy".into(),
        args: json!({}),
        id: String::new(),
        turn: 0,
        hints: None,
    })
    .await
    .unwrap()
    .expect("a denial short-circuit");
    let res = ov.result.unwrap();
    assert!(
        res.is_error && res.output.contains("blocked by policy"),
        "{res:?}"
    );
    assert_eq!(executed.load(Ordering::SeqCst), 0);
}

async fn runtime_child(
    spec: Spec,
    script: &Scripted,
    reply: Vec<Value>,
) -> (Runtime, agents::Handle) {
    let child = agents::new("child", spec);
    let r = Runtime::new(Options {
        registry: child.registry(),
        in_process: Some(script.generate(move |n| reply[(n - 1).min(reply.len() - 1)].clone())),
        ..Default::default()
    });
    let h = r.spawn(&r.root(), "child", None).unwrap();
    (r, h)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn completion_gate_reaches_delegated_child() {
    let s = Scripted::default();
    let (r, h) = runtime_child(
        Spec {
            does: "does the work".into(),
            tools: vec![todowrite_tool().await],
            completion: gate(3),
            ..Default::default()
        },
        &s,
        vec![
            call_todo(json!([todo("1", "work", false)])),
            text("claiming done"),
            call_todo(json!([todo("1", "work", true)])),
            text("really done"),
        ],
    )
    .await;
    r.wake(&h, "do the work");
    let res = r.wait(&h, None).await;
    assert_eq!(res.status, "done", "{res:?}");
    assert!(s.n() >= 4, "the gate re-ran inside the runtime turn");
    r.close(&h, None).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn delegated_gate_stops_loudly() {
    let s = Scripted::default();
    let (r, h) = runtime_child(
        Spec {
            does: "works".into(),
            tools: vec![todowrite_tool().await],
            budget: Some(Budget {
                max_turns: 30,
                ..Default::default()
            }),
            completion: gate(2),
            ..Default::default()
        },
        &s,
        vec![
            call_todo(json!([todo("1", "never", false)])),
            text("done?"),
            text("done?"),
            text("done?"),
        ],
    )
    .await;
    r.wake(&h, "go");
    let res = r.wait(&h, None).await;
    assert_eq!(res.status, "incomplete", "{res:?}");
    assert!(
        res.text.contains("completion.verify failed") || res.text.contains("maxTurns"),
        "{}",
        res.text
    );
    r.close(&h, None).await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn suspension_is_not_re_judged_by_the_gate() {
    let asked = Arc::new(AtomicUsize::new(0));
    let a2 = asked.clone();
    let ask = custom_tool("ask_human", "asks", move |_, ctx| {
        let a2 = a2.clone();
        Box::pin(async move {
            if ctx.answer.is_some() {
                return ToolResult::ok("human said yes");
            }
            a2.fetch_add(1, Ordering::SeqCst);
            pending(Request {
                id: "req-1".into(),
                kind: "question".into(),
                prompt: "Proceed?".into(),
                ..Default::default()
            })
        })
    });
    let s = Scripted::default();
    let (r, h) = runtime_child(
        Spec {
            does: "asks".into(),
            tools: vec![ask],
            completion: Some(Completion::new(
                |_| (false, "must never be consulted on a pending".into()),
                2,
            )),
            ..Default::default()
        },
        &s,
        vec![call_tool("ask_human", "a1"), text("finished")],
    )
    .await;
    r.wake(&h, "go");
    let res = r.wait(&h, None).await;
    assert!(asked.load(Ordering::SeqCst) > 0);
    assert_eq!(res.status, "pending", "{res:?}");
    assert_eq!(res.pending.unwrap().prompt, "Proceed?");
    r.close(&h, None).await;
}

#[test]
fn harness_is_a_factory_not_a_second_type() {
    let sp = Spec {
        does: "works".into(),
        soul: "be brief".into(),
        completion: gate(2),
        ..Default::default()
    };
    let h: Spec = agents::harness(sp.clone());
    assert!(h.does == sp.does && h.soul == sp.soul);
    let a = agents::new("worker", h);
    assert_eq!(a.spec.completion.as_ref().unwrap().max_attempts, 2);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_opts_model_reaches_the_wire() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "w",
        Spec {
            does: "w".into(),
            ..Default::default()
        },
    );
    let mut o = client_opts(&url);
    o.model = "default-model".into();
    let mut l = a.loop_(o, empty_toolkit().await);
    l.run(CancellationToken::new(), "a", RunOpts::default())
        .await
        .unwrap();
    l.run(
        CancellationToken::new(),
        "b",
        RunOpts {
            model: "per-run-model".into(),
        },
    )
    .await
    .unwrap();
    let models: Vec<String> = seen
        .lock()
        .unwrap()
        .iter()
        .map(|b| b["model"].as_str().unwrap_or("").to_string())
        .collect();
    assert_eq!(models, vec!["default-model", "per-run-model"]);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn budget_stop_carries_the_verification_reason() {
    let s = Scripted::default();
    let child = agents::new(
        "child",
        Spec {
            does: "w".into(),
            tools: vec![todowrite_tool().await],
            completion: gate(5),
            ..Default::default()
        },
    );
    let r = Runtime::new(Options {
        registry: child.registry(),
        in_process: Some(s.generate(|n| {
            if n == 1 {
                call_todo(json!([todo("1", "never", false)]))
            } else {
                text("claiming done")
            }
        })),
        ..Default::default()
    });
    let h = r.spawn(&r.root(), "child", None).unwrap();
    r.wake(&h, "go");
    let res = r.wait(&h, None).await;
    assert_ne!(res.status, "done");
    assert!(!res.text.contains("maxTurns"), "MASKED: {}", res.text);
    assert!(
        res.text.contains("completion.verify failed") && res.text.contains("still open"),
        "{}",
        res.text
    );
    r.close(&h, None).await;
}

// ---- consumer issues (#87, #88, #90) ---------------------------------------------

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_guardrail_denies_on_the_loop_path_execute_never_entered() {
    let entered = Arc::new(AtomicUsize::new(0));
    let e = entered.clone();
    let danger = custom_tool("rm_rf", "deletes everything", move |_, _| {
        let e = e.clone();
        Box::pin(async move {
            e.fetch_add(1, Ordering::SeqCst);
            ToolResult::ok("deleted")
        })
    });
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![danger],
        ..Default::default()
    })
    .await
    .unwrap();
    let rail: Guardrail = Arc::new(|ev| {
        if ev.name == "rm_rf" {
            "destructive tools are not permitted".into()
        } else {
            "allow".into()
        }
    });
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            guardrails: vec![rail],
            ..Default::default()
        },
    );
    let (url, _) = provider(|n, _| {
        if n == 1 {
            call_tool("rm_rf", "c1")
        } else {
            text("all done")
        }
    })
    .await;
    a.loop_(client_opts(&url), tk)
        .run(CancellationToken::new(), "clean up", RunOpts::default())
        .await
        .unwrap();
    assert_eq!(
        entered.load(Ordering::SeqCst),
        0,
        "the guardrail is decorative"
    );
}

fn system_of(b: &Value) -> String {
    b["messages"]
        .as_array()
        .and_then(|m| m.iter().find(|x| x["role"] == "system"))
        .and_then(|m| m["content"].as_str())
        .unwrap_or("")
        .to_string()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_loop_honours_soul_model_and_budget() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            soul: "You are terse.".into(),
            model: "spec-model".into(),
            budget: Some(Budget {
                max_turns: 3,
                ..Default::default()
            }),
            ..Default::default()
        },
    );
    let mut o = client_opts(&url);
    o.model = String::new();
    a.loop_(o, empty_toolkit().await)
        .run(CancellationToken::new(), "go", RunOpts::default())
        .await
        .unwrap();
    let b = seen.lock().unwrap()[0].clone();
    assert_eq!(system_of(&b), "You are terse.");
    assert_eq!(b["model"], "spec-model");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_caller_system_prompt_wins_over_the_soul() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            soul: "soul prompt".into(),
            ..Default::default()
        },
    );
    let mut o = client_opts(&url);
    o.system_prompt = "caller prompt".into();
    a.loop_(o, empty_toolkit().await)
        .run(CancellationToken::new(), "go", RunOpts::default())
        .await
        .unwrap();
    assert_eq!(system_of(&seen.lock().unwrap()[0]), "caller prompt");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_inherit_sentinel_behaves_like_an_absent_model() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            model: "spec-model".into(),
            ..Default::default()
        },
    );
    let mut o = client_opts(&url);
    o.model = "inherit".into();
    a.loop_(o, empty_toolkit().await)
        .run(CancellationToken::new(), "go", RunOpts::default())
        .await
        .unwrap();
    assert_eq!(seen.lock().unwrap()[0]["model"], "spec-model");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d2_per_call_model_still_wins() {
    let (url, seen) = provider(|_, _| text("ok")).await;
    let a = agents::new(
        "worker",
        Spec {
            does: "works".into(),
            model: "spec-model".into(),
            ..Default::default()
        },
    );
    a.loop_(client_opts(&url), empty_toolkit().await)
        .run(
            CancellationToken::new(),
            "go",
            RunOpts {
                model: "per-call".into(),
            },
        )
        .await
        .unwrap();
    assert_eq!(seen.lock().unwrap()[0]["model"], "per-call");
}

#[tokio::test]
async fn d2_loop_unsupported_names_the_residue() {
    let plain = Spec {
        does: "x".into(),
        soul: "y".into(),
        ..Default::default()
    };
    assert!(agents::loop_unsupported(&plain).is_empty());
    let sp = Spec {
        does: "x".into(),
        tools: vec![lookup_tool()],
        team: vec![agents::new(
            "other",
            Spec {
                does: "o".into(),
                ..Default::default()
            },
        )],
        wait_for: Some(Arc::new(|_| Box::pin(async { Ok(Answer::default()) }))),
        on_metric: Some(Arc::new(|_: &MetricEvent| {})),
        ..Default::default()
    };
    assert_eq!(
        agents::loop_unsupported(&sp),
        vec!["tools", "team", "waitFor", "onMetric"]
    );
    let l = agents::new("w", sp).loop_(client_opts("http://unused"), empty_toolkit().await);
    assert_eq!(l.unsupported().len(), 4);
}

fn ip(s: &Scripted, reply: impl Fn(usize) -> Value + Send + Sync + 'static) -> Options {
    Options {
        in_process: Some(s.generate(reply)),
        ..Default::default()
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_total_tokens_is_the_tree_total_and_own_tokens_is_not() {
    let s = Scripted::default();
    let child = agents::new(
        "child",
        Spec {
            does: "does the sub-task".into(),
            ..Default::default()
        },
    );
    let parent = agents::new(
        "parent",
        Spec {
            does: "delegates".into(),
            team: vec![child],
            ..Default::default()
        },
    );
    let (res, _) = parent
        .run(
            ip(&s, |n| {
                if n == 1 {
                    calls(vec![call_part(
                        "task",
                        json!({"agent": "child", "prompt": "sub"}),
                        "c1",
                    )])
                } else {
                    text("parent done")
                }
            }),
            "delegate it",
        )
        .await;
    assert_eq!(res.status, TASK_STATUS_DONE, "{res:?}");
    assert!(res.own_tokens > 0);
    assert!(res.total_tokens > res.own_tokens, "{res:?}");
    assert!(res.turns >= 2);
}

fn ask_tool(counter: Arc<AtomicUsize>, answers: bool) -> Tool {
    custom_tool("ask_human", "asks", move |_, ctx| {
        let c = counter.clone();
        Box::pin(async move {
            c.fetch_add(1, Ordering::SeqCst);
            if answers && ctx.answer.is_some() {
                return ToolResult::ok("human said yes");
            }
            pending(Request {
                kind: "input".into(),
                prompt: "proceed?".into(),
                ..Default::default()
            })
        })
    })
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_turns_is_cumulative_on_every_status() {
    let s = Scripted::default();
    let a = agents::new(
        "w",
        Spec {
            does: "works".into(),
            tools: vec![ask_tool(Arc::default(), false)],
            ..Default::default()
        },
    );
    let rtm = Runtime::new(Options {
        registry: a.registry(),
        ..ip(&s, |_| call_tool("ask_human", "c1"))
    });
    let h = rtm.spawn(&rtm.root(), "w", None).unwrap();
    let first = rtm.run_turn(&h, "one").await;
    assert_eq!(first.status, TASK_STATUS_PENDING);
    assert!(first.turns > 0);
    assert_eq!(rtm.inspect(&h).turns, first.turns);
    let resumed = rtm
        .resume(Answer {
            id: first.pending.unwrap().id,
            ok: true,
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(
        resumed.turns > first.turns,
        "{} → {}",
        first.turns,
        resumed.turns
    );
}

fn ok_budget_agent() -> (Scripted, Runtime, agents::Handle) {
    let s = Scripted::default();
    let a = agents::new(
        "w",
        Spec {
            does: "works".into(),
            budget: Some(Budget {
                max_tokens: 1,
                ..Default::default()
            }),
            ..Default::default()
        },
    );
    let rtm = Runtime::new(Options {
        registry: a.registry(),
        ..ip(&s, |_| text("ok"))
    });
    let h = rtm.spawn(&rtm.root(), "w", None).unwrap();
    (s, rtm, h)
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_limit_is_named_on_a_budget_stop() {
    let (_s, rtm, h) = ok_budget_agent();
    rtm.run_turn(&h, "one").await;
    let got = rtm.run_turn(&h, "two").await;
    assert_eq!(got.status, TASK_STATUS_INCOMPLETE, "{got:?}");
    assert_eq!(got.limit, LIMIT_MAX_TOKENS);
    assert!(got.total_tokens > 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_resume_returns_the_resumed_result() {
    let asked = Arc::new(AtomicUsize::new(0));
    let s = Scripted::default();
    let a = agents::new(
        "w",
        Spec {
            does: "works".into(),
            tools: vec![ask_tool(asked.clone(), true)],
            ..Default::default()
        },
    );
    let (res, rtm) = a
        .run(
            ip(&s, |n| {
                if n <= 2 {
                    call_tool("ask_human", "c1")
                } else {
                    text("finished after the answer")
                }
            }),
            "go",
        )
        .await;
    assert_eq!(res.status, TASK_STATUS_PENDING, "{res:?}");
    let resumed = rtm
        .resume(Answer {
            id: res.pending.unwrap().id,
            ok: true,
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(resumed.status, TASK_STATUS_DONE, "{resumed:?}");
    assert_eq!(resumed.text, "finished after the answer");
    assert!(resumed.total_tokens > 0);
    assert!(
        asked.load(Ordering::SeqCst) >= 2,
        "the suspended tool re-ran"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_wait_deadline_sets_both_status_and_limit() {
    let block = CancellationToken::new();
    let b2 = block.clone();
    let slow = custom_tool("slow", "never returns", move |_, _| {
        let b = b2.clone();
        Box::pin(async move {
            b.cancelled().await;
            ToolResult::ok("late")
        })
    });
    let s = Scripted::default();
    let a = agents::new(
        "w",
        Spec {
            does: "works".into(),
            tools: vec![slow],
            ..Default::default()
        },
    );
    let rtm = Runtime::new(Options {
        registry: a.registry(),
        ..ip(&s, |_| call_tool("slow", "c1"))
    });
    let h = rtm.spawn(&rtm.root(), "w", None).unwrap();
    rtm.wake(&h, "go");
    let got = rtm.wait(&h, Some(Duration::from_millis(25))).await;
    assert_eq!(got.status, TASK_STATUS_TIMEOUT);
    assert_eq!(got.limit, LIMIT_TIMEOUT);
    block.cancel();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn d3_every_limit_stop_names_its_limit() {
    let (_s, rtm, h) = ok_budget_agent();
    let results: Vec<TaskResult> =
        vec![rtm.run_turn(&h, "one").await, rtm.run_turn(&h, "two").await];
    for got in results {
        match got.status.as_str() {
            "incomplete" | "timeout" => assert!(!got.limit.is_empty(), "{got:?}"),
            _ => assert!(got.limit.is_empty(), "{got:?}"),
        }
    }
    rtm.close(&h, None).await;
    let closed = rtm.run_turn(&h, "three").await;
    assert_eq!(closed.status, TASK_STATUS_CLOSED);
    assert!(closed.limit.is_empty());
}

#[test]
fn d3_agent_status_vocabulary_is_seven_values() {
    let seven = [
        TASK_STATUS_DONE,
        TASK_STATUS_PENDING,
        TASK_STATUS_INCOMPLETE,
        TASK_STATUS_INTERRUPTED,
        TASK_STATUS_CLOSED,
        TASK_STATUS_TIMEOUT,
        TASK_STATUS_ERROR,
    ];
    assert_eq!(seven.len(), 7);
    assert_eq!(TASK_STATUS_TIMEOUT, "timeout");
    for (want, got) in [
        ("maxTurns", LIMIT_MAX_TURNS),
        ("maxTokens", LIMIT_MAX_TOKENS),
        ("maxToolCalls", LIMIT_MAX_TOOL_CALLS),
        ("maxWallMs", LIMIT_MAX_WALL_MS),
        ("maxChildren", LIMIT_MAX_CHILDREN),
        ("maxConcurrent", LIMIT_MAX_CONCURRENT),
        ("maxDepth", LIMIT_MAX_DEPTH),
        ("completion", LIMIT_COMPLETION),
        ("timeout", LIMIT_TIMEOUT),
    ] {
        assert_eq!(got, want);
    }
}
