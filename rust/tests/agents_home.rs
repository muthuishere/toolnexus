//! Agent home (SPEC §7E) H1–H7 against examples/persona-agent/fixture.json, and
//! context compaction (SPEC §7F) C1–C6 against examples/compaction. Mirrors
//! golang/agents/{home,compaction,compaction_dialect}_test.go. Hermetic.

mod agents_support;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use agents_support::*;
use serde_json::{Value, json};
use toolnexus::agents::{
    self, BOOTSTRAP_ORDER, CompactInfo, CompactorOptions, FromDirOptions, HEARTBEAT_OK, InboxItem,
    Options, Runtime, StartOptions, State, compactor, compose_soul, estimate_tokens, from_dir,
    memory_tool, start_agent,
};
use toolnexus::client::{BeforeLlmEvent, GenerateFn, LlmOverride};
use toolnexus::{BuiltinsConfig, ToolContext, ToolResult, ToolkitOptions, create_toolkit};

static SEQ: AtomicUsize = AtomicUsize::new(0);

fn temp_dir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!(
        "tn-home-{tag}-{}-{}",
        std::process::id(),
        SEQ.fetch_add(1, Ordering::SeqCst)
    ));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn write_bootstrap(files: &[(&str, &str)]) -> PathBuf {
    let d = temp_dir("boot");
    for (n, b) in files {
        std::fs::write(d.join(n), b).unwrap();
    }
    d
}

fn fixture_files() -> Vec<(String, String)> {
    let p = Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/persona-agent/fixture.json");
    let v: Value = serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap();
    v["bootstrapDir"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_string()))
        .collect()
}

/// The fixture's scripted models. `gate` parks m-heartbeat until cancelled;
/// `rec` captures the last message of every call.
fn home_mock(
    gate: Option<tokio_util::sync::CancellationToken>,
    rec: Arc<Mutex<Vec<String>>>,
) -> GenerateFn {
    script(move |p: Parsed| {
        let gate = gate.clone();
        let rec = rec.clone();
        Box::pin(async move {
            rec.lock().unwrap().push(p.last.clone());
            if let Some(g) = &gate
                && p.model == "m-heartbeat"
            {
                g.cancelled().await;
            }
            Ok(match p.model.as_str() {
                "m-echo-soul" => {
                    let found: Vec<&str> = BOOTSTRAP_ORDER
                        .into_iter()
                        .filter(|f| p.system.contains(&format!("## {f}")))
                        .collect();
                    text(&format!("sections:[{}]", found.join(",")))
                }
                "m-remember" => {
                    if p.tool_msgs.is_empty() {
                        calls(vec![call_part(
                            "memory",
                            json!({"action": "add", "target": "user", "text": "Prefers dark roast"}),
                            "w1",
                        )])
                    } else {
                        text(&format!("saved: {}", p.tool_msgs[0]))
                    }
                }
                "m-recall" => {
                    if p.system.contains("Prefers dark roast") {
                        text("I recall: dark roast")
                    } else {
                        text("no memory")
                    }
                }
                "m-heartbeat" => {
                    if p.system.contains("remind about the 3pm sync")
                        && p.last.contains("Heartbeat")
                    {
                        text("Reminder: 3pm sync 🔔")
                    } else {
                        text(HEARTBEAT_OK)
                    }
                }
                _ => text("ok"),
            })
        })
    })
}

fn opts() -> Options {
    Options {
        in_process: Some(home_mock(None, Arc::default())),
        ..Default::default()
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn home_h1_bootstrap_discovery_and_injection() {
    let files = fixture_files();
    let refs: Vec<(&str, &str)> = files
        .iter()
        .map(|(a, b)| (a.as_str(), b.as_str()))
        .collect();
    let dir = write_bootstrap(&refs);
    let (soul, found) = compose_soul(&dir);
    assert_eq!(found.join(","), "SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md");
    let at = |s: &str| soul.find(s).unwrap();
    assert!(at("## SOUL.md") < at("## USER.md") && at("## USER.md") < at("## HEARTBEAT.md"));
    assert!(at("## HEARTBEAT.md") < at("## MEMORY.md"));
    let ava = from_dir(
        &dir,
        FromDirOptions {
            model: "m-echo-soul".into(),
            ..Default::default()
        },
    );
    let (r, _) = ava.run(opts(), "who are you?").await;
    assert_eq!(r.text, "sections:[SOUL.md,USER.md,HEARTBEAT.md,MEMORY.md]");
}

#[test]
fn home_h2_per_file_byte_cap() {
    let big = "x".repeat(3 * 1024 * 1024);
    let dir = write_bootstrap(&[("SOUL.md", &big)]);
    let (soul, _) = compose_soul(&dir);
    assert!(soul.contains("[truncated: exceeds 2 MB bootstrap cap]"));
    assert!(soul.len() <= 2 * 1024 * 1024 + 4096);
    assert_eq!(
        std::fs::read(dir.join("SOUL.md")).unwrap().len(),
        3 * 1024 * 1024
    );
}

#[tokio::test]
async fn home_h3_memory_tool_persists() {
    let dir = write_bootstrap(&[("MEMORY.md", "- Likes green tea.")]);
    let tool = memory_tool(&dir);
    let read = |n: &str| std::fs::read_to_string(dir.join(n)).unwrap_or_default();
    let call = |a: Value| tool.call(a, ToolContext::default());
    call(json!({"action": "add", "text": "Likes hiking"})).await;
    assert!(read("MEMORY.md").contains("- Likes hiking"));
    call(json!({"action": "replace", "text": "green tea", "with": "oolong"})).await;
    assert!(read("MEMORY.md").contains("oolong"));
    call(json!({"action": "remove", "text": "- Likes hiking\n"})).await;
    assert!(!read("MEMORY.md").contains("hiking"));
    let before = read("MEMORY.md");
    let miss: ToolResult =
        call(json!({"action": "replace", "text": "nonexistent", "with": "x"})).await;
    assert!(miss.is_error && read("MEMORY.md") == before);
    let uw = call(json!({"action": "add", "target": "user", "text": "Speaks Tamil"})).await;
    assert!(!uw.is_error && read("USER.md").contains("Speaks Tamil"));
    assert_eq!(read("MEMORY.md"), "- Likes oolong.\n");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn home_h4_frozen_snapshot() {
    let dir = write_bootstrap(&[("USER.md", "The user is new.")]);
    let ava = from_dir(
        &dir,
        FromDirOptions {
            model: "m-remember".into(),
            ..Default::default()
        },
    );
    ava.run(opts(), "note my coffee preference").await;
    assert!(
        std::fs::read_to_string(dir.join("USER.md"))
            .unwrap()
            .contains("Prefers dark roast")
    );
    let ava2 = from_dir(
        &dir,
        FromDirOptions {
            model: "m-recall".into(),
            ..Default::default()
        },
    );
    let (r2, _) = ava2.run(opts(), "what do you know about me?").await;
    assert_eq!(r2.text, "I recall: dark roast");
}

async fn heartbeat_once(heartbeat: &str) -> Vec<String> {
    let dir = write_bootstrap(&[("SOUL.md", "You are Ava."), ("HEARTBEAT.md", heartbeat)]);
    let clk = FakeClock::new();
    let ava = from_dir(
        &dir,
        FromDirOptions {
            model: "m-heartbeat".into(),
            ..Default::default()
        },
    );
    let sa = start_agent(
        &ava,
        StartOptions {
            options: Options {
                clock: Some(clk.clone()),
                ..opts()
            },
            every_ms: 60_000,
            ..Default::default()
        },
    )
    .unwrap();
    clk.wait_armed().await;
    clk.fire();
    sa.next_beat().await;
    let beats = sa.beats();
    sa.stop().await;
    beats
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn home_h5_heartbeat() {
    // H5.1 — a due beat surfaces.
    let beats = heartbeat_once("If it is time, remind about the 3pm sync.").await;
    assert!(
        !beats.is_empty() && beats[0].contains("3pm sync"),
        "{beats:?}"
    );
    // H5.2 — a HEARTBEAT_OK reply stays silent.
    let beats = heartbeat_once("Nothing scheduled today.").await;
    assert!(beats.is_empty(), "{beats:?}");

    // H5.3 — ticks posted while a beat's turn is in flight coalesce into ONE turn.
    let dir = write_bootstrap(&[
        ("SOUL.md", "You are Ava."),
        ("HEARTBEAT.md", "If it is time, remind about the 3pm sync."),
    ]);
    let gate = tokio_util::sync::CancellationToken::new();
    let rec: Arc<Mutex<Vec<String>>> = Arc::default();
    let ava = from_dir(
        &dir,
        FromDirOptions {
            model: "m-heartbeat".into(),
            ..Default::default()
        },
    );
    let rt = Runtime::new(Options {
        in_process: Some(home_mock(Some(gate.clone()), rec.clone())),
        registry: ava.registry(),
        ..Default::default()
    });
    let h = rt.spawn(&rt.root(), &ava.name, None).unwrap();
    let hb = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.";
    rt.post(&h, InboxItem::new("clock", "timer", "tick"));
    rt.wake(&h, hb);
    assert!(await_state(&rt, &h, State::Running).await);
    for _ in 0..3 {
        rt.post(&h, InboxItem::new("clock", "timer", "tick"));
    }
    gate.cancel();
    let r1 = rt.wait(&h, None).await;
    assert!(r1.text.contains("3pm sync"), "{}", r1.text);
    rt.wake(&h, hb);
    let r2 = rt.wait(&h, None).await;
    rt.close(&rt.root(), None).await;
    assert_eq!(r2.turns - r1.turns, 1, "queued ticks coalesce to one turn");
    assert!(
        rec.lock()
            .unwrap()
            .iter()
            .any(|s| s.contains("tick (x3 coalesced)"))
    );
}

#[test]
fn home_h6_memory_opt_out() {
    let dir = write_bootstrap(&[("SOUL.md", "Read-only Ava.")]);
    let ava = from_dir(
        &dir,
        FromDirOptions {
            memory: Some(false),
            ..Default::default()
        },
    );
    assert!(ava.spec.tools.iter().all(|t| t.name != "memory"));
}

#[test]
fn home_h7_dream_is_composition() {
    let dir = write_bootstrap(&[
        ("SOUL.md", "Nightly consolidator."),
        (
            "HEARTBEAT.md",
            "Consolidate: merge duplicate notes into MEMORY.md.",
        ),
    ]);
    let dream = from_dir(
        &dir,
        FromDirOptions {
            model: "m-echo-soul".into(),
            ..Default::default()
        },
    );
    assert!(dream.spec.tools.iter().any(|t| t.name == "memory"));
}

// ---- §7F compaction ----------------------------------------------------------

fn transcript(user_turns: usize) -> Vec<Value> {
    let pad = "pad ".repeat(40);
    let data = "data ".repeat(40);
    let mut m = vec![json!({"role": "system", "content": "You are Ava. SOUL."})];
    for i in 0..user_turns {
        let id = format!("c{i}");
        m.push(json!({"role": "user", "content": format!("question {i} {pad}")}));
        m.push(json!({"role": "assistant", "content": null, "tool_calls": [
            {"id": id, "type": "function", "function": {"name": "lookup", "arguments": "{}"}}]}));
        m.push(
            json!({"role": "tool", "tool_call_id": id, "content": format!("result {i} {data}")}),
        );
        m.push(json!({"role": "assistant", "content": format!("answer {i}")}));
    }
    m
}

fn summarize() -> agents::SummarizeFn {
    Arc::new(|older: Vec<Value>| {
        Box::pin(async move { Ok(format!("summarized {} messages", older.len())) })
    })
}

fn summarize_to(s: &'static str) -> agents::SummarizeFn {
    Arc::new(move |_| Box::pin(async move { Ok(s.to_string()) }))
}

async fn hook_on(o: CompactorOptions, msgs: &[Value]) -> Option<LlmOverride> {
    compactor(o)(BeforeLlmEvent {
        messages: msgs.to_vec(),
        tools: Vec::new(),
        model: String::new(),
        turn: 0,
    })
    .await
    .unwrap()
}

fn copt(max: usize, keep: usize) -> CompactorOptions {
    CompactorOptions {
        max_tokens: max,
        keep_tail: keep,
        summarize: Some(summarize()),
        ..Default::default()
    }
}

fn content(m: &Value) -> &str {
    m["content"].as_str().unwrap_or("")
}

#[tokio::test]
async fn compaction_c1_to_c5() {
    // C1 — no-op below budget.
    assert!(hook_on(copt(100_000, 0), &transcript(3)).await.is_none());
    // C2 — compacts above budget.
    let msgs = transcript(30);
    let before = estimate_tokens(&msgs);
    let out = hook_on(copt(2000, 800), &msgs)
        .await
        .expect("C2 compacts")
        .messages
        .unwrap();
    assert!(estimate_tokens(&out) < before);
    assert!(
        out.iter()
            .any(|m| content(m).starts_with("[Summary of earlier conversation]"))
    );
    // C3 — tool-pair safety; the tail starts at a clean user turn.
    for (i, m) in out.iter().enumerate() {
        if m["role"] == "tool" {
            let id = &m["tool_call_id"];
            assert!(
                out[..i].iter().any(|p| p["role"] == "assistant"
                    && p["tool_calls"]
                        .as_array()
                        .is_some_and(|c| c.iter().any(|c| &c["id"] == id))),
                "orphaned tool result {id}"
            );
        }
    }
    let first_non_system = out.iter().find(|m| m["role"] != "system").unwrap();
    assert_eq!(first_non_system["role"], "user");
    // C4 — leading system prompt preserved verbatim.
    let out = hook_on(copt(2000, 0), &transcript(30))
        .await
        .unwrap()
        .messages
        .unwrap();
    assert!(out[0]["role"] == "system" && content(&out[0]).contains("SOUL"));
    // C5 — flush_to_memory reminder.
    let out = hook_on(
        CompactorOptions {
            flush_to_memory: true,
            ..copt(2000, 0)
        },
        &transcript(30),
    )
    .await
    .unwrap()
    .messages
    .unwrap();
    assert!(
        out.iter()
            .any(|m| content(m).contains("save it with the memory tool"))
    );
}

// C6 — end to end through the shipped client loop via before_llm.
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn compaction_c6_end_to_end() {
    let calls_n = Arc::new(AtomicUsize::new(0));
    let c = calls_n.clone();
    let model: GenerateFn = Arc::new(move |req| {
        let compacted = req
            .messages
            .iter()
            .any(|m| content(m).starts_with("[Summary"));
        let n = c.load(Ordering::SeqCst);
        let msg = if n >= 6 {
            text(&format!("final (compacted={compacted})"))
        } else {
            c.fetch_add(1, Ordering::SeqCst);
            calls(vec![call_part("pad", json!({}), &format!("c{n}"))])
        };
        Box::pin(async move { Ok(answer_with(msg, 50, 10)) })
    });
    let pad = custom_tool("pad", "pads", |_, _| {
        Box::pin(async { ToolResult::ok("x ".repeat(600)) })
    });
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![pad],
        ..Default::default()
    })
    .await
    .unwrap();
    let client = toolnexus::client::create_in_process_client(toolnexus::client::InProcessOptions {
        model: "m".into(),
        generate: Some(model),
        max_turns: 20,
        system_prompt: "You are Ava. SOUL.".into(),
        hooks: toolnexus::client::Hooks {
            before_llm: Some(compactor(copt(600, 250))),
            ..Default::default()
        },
        ..Default::default()
    });
    let r = client.run("start", &tk).await.unwrap();
    assert!(
        r.status == "done" && r.text.starts_with("final"),
        "{}",
        r.text
    );
    assert!(r.text.contains("compacted=true"), "{}", r.text);
    assert!(estimate_tokens(&r.messages) < 4000);
}

#[tokio::test]
async fn compactor_on_compact() {
    let got: Arc<Mutex<Vec<CompactInfo>>> = Arc::default();
    let g = got.clone();
    let msgs = transcript(10);
    let out = hook_on(
        CompactorOptions {
            on_compact: Some(Arc::new(move |i: &CompactInfo| {
                g.lock().unwrap().push(i.clone())
            })),
            ..copt(50, 20)
        },
        &msgs,
    )
    .await
    .unwrap()
    .messages
    .unwrap();
    let got = got.lock().unwrap().clone();
    assert_eq!(got.len(), 1);
    let i = &got[0];
    assert_eq!(i.messages_before, msgs.len());
    assert_eq!(i.messages_after, out.len());
    assert!(i.summarized > 0 && !i.summary.is_empty());
    assert_eq!(i.tokens_before, estimate_tokens(&msgs));
    assert_eq!(i.tokens_after, estimate_tokens(&out));
    assert!(i.tokens_after < i.tokens_before);
    let none = hook_on(
        CompactorOptions {
            on_compact: Some(Arc::new(|_: &CompactInfo| panic!("on_compact on a no-op"))),
            ..copt(1 << 30, 0)
        },
        &msgs,
    )
    .await;
    assert!(none.is_none());
}

#[tokio::test]
async fn compactor_keeps_sole_user_prompt() {
    let data = "data ".repeat(40);
    let mut msgs = vec![
        json!({"role": "system", "content": "SOUL"}),
        json!({"role": "user", "content": "do the task"}),
    ];
    for i in 0..8 {
        let id = format!("c{i}");
        msgs.push(json!({"role": "assistant", "content": null, "tool_calls": [
            {"id": id, "type": "function", "function": {"name": "lookup", "arguments": "{}"}}]}));
        msgs.push(json!({"role": "tool", "tool_call_id": id, "content": data}));
    }
    let info: Arc<Mutex<Option<CompactInfo>>> = Arc::default();
    let i2 = info.clone();
    let out = hook_on(
        CompactorOptions {
            on_compact: Some(Arc::new(move |i: &CompactInfo| {
                *i2.lock().unwrap() = Some(i.clone())
            })),
            ..copt(100, 20)
        },
        &msgs,
    )
    .await
    .unwrap()
    .messages
    .unwrap();
    assert_eq!(out.len(), 3);
    assert_eq!(out[1]["content"], "do the task");
    assert_eq!(
        info.lock().unwrap().as_ref().unwrap().folded.len(),
        msgs.len() - 2
    );
}

#[tokio::test]
async fn compactor_no_op_when_not_smaller() {
    let long: agents::SummarizeFn =
        Arc::new(|_| Box::pin(async { Ok("long summary ".repeat(500)) }));
    let out = hook_on(
        CompactorOptions {
            max_tokens: 10,
            keep_tail: 5,
            summarize: Some(long),
            on_compact: Some(Arc::new(|_: &CompactInfo| panic!("on_compact on a no-op"))),
            ..Default::default()
        },
        &transcript(2),
    )
    .await;
    assert!(out.is_none());
}

#[tokio::test]
async fn compactor_folded_is_verbatim() {
    let msgs = transcript(10);
    let info: Arc<Mutex<Option<CompactInfo>>> = Arc::default();
    let i2 = info.clone();
    hook_on(
        CompactorOptions {
            on_compact: Some(Arc::new(move |i: &CompactInfo| {
                *i2.lock().unwrap() = Some(i.clone())
            })),
            ..copt(50, 20)
        },
        &msgs,
    )
    .await
    .expect("a compaction");
    let info = info.lock().unwrap().clone().unwrap();
    assert!(info.folded.len() == info.summarized && !info.folded.is_empty());
    for (i, m) in info.folded.iter().enumerate() {
        assert_eq!(m, &msgs[1 + i]);
    }
}

#[tokio::test]
async fn anthropic_tool_result_not_used_as_tail_boundary() {
    let pad = |n: usize| "x".repeat(n);
    let msgs = vec![
        json!({"role": "system", "content": "soul"}),
        json!({"role": "user", "content": format!("q1 {}", pad(400))}),
        json!({"role": "assistant", "content": format!("a1 {}", pad(400))}),
        json!({"role": "user", "content": "q2"}),
        json!({"role": "assistant", "content": [{"type": "tool_use", "id": "tu_1", "name": "echo", "input": {"v": 1}}]}),
        json!({"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu_1", "content": "echoed"}]}),
        json!({"role": "assistant", "content": "done"}),
    ];
    let out = hook_on(
        CompactorOptions {
            max_tokens: 50,
            keep_tail: 20,
            summarize: Some(summarize_to("SUMMARY")),
            ..Default::default()
        },
        &msgs,
    )
    .await
    .expect("compaction fires")
    .messages
    .unwrap();
    let s = Value::Array(out.clone()).to_string();
    let has_result = s.contains(r#""type":"tool_result""#);
    let has_use = s.contains(r#""type":"tool_use""#);
    assert!(!(has_result && !has_use), "ORPHANED: {s}");
    assert!(out.len() >= 3, "{s}");
    assert!(s.contains(r#""content":"done""#));
}

#[tokio::test]
async fn openai_dialect_boundaries_unchanged() {
    let msgs = vec![
        json!({"role": "system", "content": "soul"}),
        json!({"role": "user", "content": format!("q1 {}", "x".repeat(400))}),
        json!({"role": "assistant", "content": null, "tool_calls": [{"id": "tc_1", "type": "function"}]}),
        json!({"role": "tool", "tool_call_id": "tc_1", "content": "echoed"}),
        json!({"role": "user", "content": "q2"}),
        json!({"role": "assistant", "content": "done"}),
    ];
    let out = hook_on(
        CompactorOptions {
            max_tokens: 50,
            keep_tail: 20,
            summarize: Some(summarize_to("SUMMARY")),
            ..Default::default()
        },
        &msgs,
    )
    .await
    .unwrap()
    .messages
    .unwrap();
    assert!(Value::Array(out).to_string().contains(r#""content":"q2""#));
}

#[tokio::test]
async fn no_safe_boundary_summarizes_with_empty_tail() {
    let msgs = vec![
        json!({"role": "system", "content": "soul"}),
        json!({"role": "assistant", "content": format!("a {}", "x".repeat(400))}),
        json!({"role": "user", "content": [{"type": "tool_result", "tool_use_id": "tu_1", "content": "echoed"}]}),
        json!({"role": "assistant", "content": "done"}),
    ];
    let out = hook_on(
        CompactorOptions {
            max_tokens: 10,
            keep_tail: 5,
            summarize: Some(summarize_to("SUMMARY")),
            ..Default::default()
        },
        &msgs,
    )
    .await
    .unwrap()
    .messages
    .unwrap();
    let s = Value::Array(out).to_string();
    assert!(!s.contains(r#""type":"tool_result""#), "{s}");
    assert!(s.contains("SUMMARY"));
}

#[test]
fn estimate_tokens_is_ceil_json_len_over_4() {
    let m = vec![json!({"role": "user", "content": "<&>"})];
    // {"content":"<&>","role":"user"} = 31 bytes, no HTML escaping → ceil(31/4) = 8
    assert_eq!(estimate_tokens(&m), 8);
}
