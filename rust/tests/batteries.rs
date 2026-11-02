//! Judge batteries (SPEC §8B "Batteries") against the shared
//! examples/judge/batteries fixtures, plus their loop hooks. Mirrors
//! golang/judge_batteries_test.go. Hermetic.

mod common;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use common::judge::*;
use common::*;
use serde_json::{Value, json};
use toolnexus::classifier::*;
use toolnexus::client::*;
use toolnexus::judge::batteries::*;
use toolnexus::judge::{Bands, recorded, static_classifier};
use toolnexus::{Answer, Request, ToolResult, create_toolkit, define_tool};

fn cases(name: &str) -> Vec<Value> {
    let fx = load(&format!("batteries/{name}"));
    let c = fx["cases"].as_array().cloned().unwrap_or_default();
    assert!(!c.is_empty(), "{name}: no cases");
    c
}

fn fail_classifier(must_not_call: bool) -> Classifier {
    create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        evaluate: Some(Arc::new(move |_, _, _| {
            Box::pin(async move {
                if must_not_call {
                    panic!("classifier must not be called");
                }
                Err("boom".to_string())
            })
        })),
        ..Default::default()
    })
    .unwrap()
}

/// Static over the recorded calls; failing for error cases; panicking if called
/// when the case records no calls.
fn battery_classifier(c: &Value) -> Classifier {
    if c["error"].as_bool().unwrap_or(false) {
        return fail_classifier(false);
    }
    let calls = c["calls"].as_array().cloned().unwrap_or_default();
    if calls.is_empty() {
        return fail_classifier(true);
    }
    let recs = calls
        .iter()
        .map(|k| recorded(&k["state"], questions_of(&k["questions"]), &k["response"]))
        .collect();
    static_classifier(recs).unwrap()
}

fn opt_bands(o: &Value) -> Option<Bands> {
    o.get("bands")
        .filter(|b| b.is_object())
        .map(|b| serde_json::from_value(b.clone()).unwrap())
}

fn opt_str(o: &Value, k: &str) -> String {
    o.get(k).and_then(Value::as_str).unwrap_or("").into()
}

fn on_err(o: &Value) -> Option<OnError> {
    OnError::parse(&opt_str(o, "onError"))
}

fn items(v: &Value) -> Vec<Item> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|m| Item {
            name: opt_str(m, "name"),
            description: opt_str(m, "description"),
        })
        .collect()
}

/// The fixture's `want` against the verdict's JSON form (numbers numerically).
fn assert_verdict(name: &str, want: &Value, verdict: impl serde::Serialize) {
    let got = serde_json::to_value(verdict).unwrap();
    for (k, w) in want.as_object().unwrap() {
        if k == "error" {
            assert_eq!(
                got.get("error").is_some(),
                w.as_bool().unwrap(),
                "{name}: error presence ({got})"
            );
            continue;
        }
        let g = got
            .get(k)
            .unwrap_or_else(|| panic!("{name}: verdict lacks {k} ({got})"));
        assert!(json_eq(g, w), "{name}: {k} = {g}, want {w}");
    }
}

#[tokio::test]
async fn tool_guard() {
    for c in cases("tool-guard.json") {
        let name = c["name"].as_str().unwrap();
        let o = &c["options"];
        let g = ToolGuard::new(
            battery_classifier(&c),
            ToolGuardOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                role: opt_str(o, "role"),
                ask_at: o.get("askAt").and_then(Value::as_f64),
                deny_at: o.get("denyAt").and_then(Value::as_f64),
            },
        )
        .unwrap();
        let i = &c["input"];
        let v = g
            .check(&GuardedCall {
                name: opt_str(i, "name"),
                arguments: i.get("arguments").cloned().unwrap_or(Value::Null),
                description: opt_str(i, "description"),
            })
            .await;
        assert_verdict(name, &c["want"], v);
    }
}

#[tokio::test]
async fn relevance() {
    for f in ["tool-relevance.json", "skill-relevance.json"] {
        for c in cases(f) {
            let name = format!("{f}/{}", c["name"].as_str().unwrap());
            let o = &c["options"];
            let ro = RelevanceOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                role: opt_str(o, "role"),
            };
            let cl = battery_classifier(&c);
            let prompt = opt_str(&c["input"], "prompt");
            let v = if f == "tool-relevance.json" {
                ToolRelevance::new(cl, ro)
                    .unwrap()
                    .select(&prompt, &items(&c["input"]["tools"]))
                    .await
            } else {
                SkillRelevance::new(cl, ro)
                    .unwrap()
                    .select(&prompt, &items(&c["input"]["skills"]))
                    .await
            };
            assert_verdict(&name, &c["want"], v);
        }
    }
}

#[tokio::test]
async fn tool_result_filter() {
    for c in cases("tool-result-filter.json") {
        let o = &c["options"];
        let f = ToolResultFilter::new(
            battery_classifier(&c),
            RelevanceOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                ..Default::default()
            },
        )
        .unwrap();
        let chunks: Vec<String> = c["input"]["chunks"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| x.as_str().unwrap().into())
            .collect();
        let v = f.filter(&c["input"]["query"], &chunks).await;
        assert_verdict(c["name"].as_str().unwrap(), &c["want"], v);
    }
}

#[tokio::test]
async fn is_complete() {
    for c in cases("is-complete.json") {
        let o = &c["options"];
        let ic = IsComplete::new(
            battery_classifier(&c),
            RelevanceOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                ..Default::default()
            },
        )
        .unwrap();
        let v = ic
            .check(
                &opt_str(&c["input"], "task"),
                &opt_str(&c["input"], "answer"),
            )
            .await;
        assert_verdict(c["name"].as_str().unwrap(), &c["want"], v);
    }
}

fn agent_nodes(v: &Value) -> Vec<AgentNode> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|m| AgentNode {
            name: opt_str(m, "name"),
            description: opt_str(m, "description"),
            agents: m.get("agents").map(agent_nodes).unwrap_or_default(),
        })
        .collect()
}

#[tokio::test]
async fn agent_router() {
    for c in cases("agent-router.json") {
        let r = AgentRouter::new(
            battery_classifier(&c),
            RouterOptions {
                bands: opt_bands(&c["options"]),
                ..Default::default()
            },
        );
        let i = &c["input"];
        let v = r
            .pick(
                &opt_str(i, "task"),
                &agent_nodes(&i["agents"]),
                &opt_str(i, "fallback"),
            )
            .await;
        assert_verdict(c["name"].as_str().unwrap(), &c["want"], v);
    }
}

#[tokio::test]
async fn content_guard() {
    for c in cases("content-guard.json") {
        let o = &c["options"];
        let dims = o
            .get("dimensions")
            .and_then(Value::as_array)
            .map(|ds| {
                ds.iter()
                    .map(|d| Dimension {
                        name: opt_str(d, "name"),
                        instructions: opt_str(d, "instructions"),
                    })
                    .collect()
            })
            .unwrap_or_default();
        let g = ContentGuard::new(
            battery_classifier(&c),
            ContentGuardOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                dimensions: dims,
                ..Default::default()
            },
        )
        .unwrap();
        let v = g.check(&opt_str(&c["input"], "text")).await;
        assert_verdict(c["name"].as_str().unwrap(), &c["want"], v);
    }
}

#[tokio::test]
async fn model_router() {
    for c in cases("model-router.json") {
        let ms = c["input"]["models"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| ModelOption {
                id: opt_str(m, "id"),
                description: opt_str(m, "description"),
            })
            .collect();
        let r = ModelRouter::new(
            battery_classifier(&c),
            ms,
            RouterOptions {
                bands: opt_bands(&c["options"]),
                ..Default::default()
            },
        );
        let v = r
            .pick(
                &opt_str(&c["input"], "prompt"),
                &opt_str(&c["input"], "fallback"),
            )
            .await;
        assert_verdict(c["name"].as_str().unwrap(), &c["want"], v);
    }
}

#[test]
fn user_text() {
    for c in cases("user-text-cases.json") {
        let msgs = c["messages"].as_array().unwrap();
        assert_eq!(
            latest_user_text(msgs),
            c["want"].as_str().unwrap(),
            "{}",
            c["name"]
        );
    }
}

#[test]
fn user_text_native_parts() {
    let parts = vec![
        toolnexus::ContentPart {
            kind: "text".into(),
            text: "a".into(),
            ..Default::default()
        },
        toolnexus::ContentPart {
            kind: "image".into(),
            url: "https://x/y.png".into(),
            ..Default::default()
        },
        toolnexus::ContentPart {
            kind: "text".into(),
            text: "b".into(),
            ..Default::default()
        },
    ];
    let msgs = vec![json!({"role": "user", "content": parts})];
    assert_eq!(latest_user_text(&msgs), "a\nb");
}

#[test]
fn on_error_required() {
    let cl = static_classifier(vec![]).unwrap();
    let errs = [
        ToolGuard::new(cl.clone(), Default::default()).err(),
        ToolRelevance::new(cl.clone(), Default::default()).err(),
        SkillRelevance::new(cl.clone(), Default::default()).err(),
        ToolResultFilter::new(
            cl.clone(),
            RelevanceOptions {
                on_error: OnError::parse("maybe"),
                ..Default::default()
            },
        )
        .err(),
        IsComplete::new(cl.clone(), Default::default()).err(),
        ContentGuard::new(cl.clone(), Default::default()).err(),
    ];
    for e in errs {
        let e = e.expect("want onError error").0;
        assert!(e.contains("onError"), "{e}");
    }
}

// ---------------------------------------------------------------- hooks

fn fixed_classifier(answers: Value) -> Classifier {
    create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        evaluate: Some(Arc::new(move |_, _, _| {
            let a = answers.clone();
            Box::pin(async move {
                Decision::from_value(&json!({"model": "m", "answers": a})).map_err(|e| e.0)
            })
        })),
        ..Default::default()
    })
    .unwrap()
}

fn risk_classifier(score: f64) -> Classifier {
    fixed_classifier(
        json!({"risk":{"type":"score","score":score,"confidence":0.9,"probabilities":{"0":0.25,"1":0.25,"2":0.25,"3":0.25},"legend":{"0":"a","1":"b","2":"c","3":"d"}}}),
    )
}

/// A scripted openai endpoint recording every request body: turn 1 calls
/// `deploy`, then answers "done".
async fn recorder() -> (Server, Arc<Mutex<Vec<Value>>>) {
    let bodies = Arc::new(Mutex::new(Vec::<Value>::new()));
    let b2 = bodies.clone();
    let srv = serve(move |r: Req| {
        let n = {
            let mut b = b2.lock().unwrap();
            b.push(r.body.clone());
            b.len()
        };
        async move {
            if n == 1 {
                Resp::json(oa_calls(&[("c1", "deploy", "{}")]))
            } else {
                Resp::json(oa_text("done"))
            }
        }
    })
    .await;
    (srv, bodies)
}

async fn deploy_toolkit(extra: bool) -> toolnexus::Toolkit {
    let mut tools = vec![define_tool(
        "deploy",
        "deploy",
        Some(json!({"type": "object"})),
        |_, _| async { Ok::<_, String>("DEPLOYED".into()) },
    )];
    if extra {
        tools.push(define_tool(
            "send_email",
            "email",
            Some(json!({"type": "object"})),
            |_, _| async { Ok::<_, String>("E".into()) },
        ));
    }
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap()
}

fn client(url: &str, hooks: Hooks, wait_for: Option<WaitForFn>) -> Client {
    create_client(ClientOptions {
        base_url: url.into(),
        model: "configured".into(),
        api_key: "k".into(),
        hooks,
        wait_for,
        max_turns: 4,
        ..Default::default()
    })
}

#[tokio::test]
async fn tool_guard_hook() {
    let tk = deploy_toolkit(false).await;
    let ran = Arc::new(AtomicBool::new(false));
    let r2 = ran.clone();
    let next: BeforeToolHook = Arc::new(move |_| {
        r2.store(true, Ordering::SeqCst);
        Box::pin(async { Ok(None) })
    });
    let guard = |score: f64| {
        ToolGuard::new(
            risk_classifier(score),
            ToolGuardOptions {
                on_error: Some(OnError::Closed),
                ..Default::default()
            },
        )
        .unwrap()
    };

    // ask: halts pending with the guard's Request; next not called.
    let (srv, _) = recorder().await;
    let hooks = Hooks {
        before_tool: Some(guard(1.8).as_hook(Some(next.clone()))),
        ..Default::default()
    };
    let r = client(&srv.url, hooks, None)
        .run("ship it", &tk)
        .await
        .unwrap();
    assert_eq!(r.status, "pending");
    let p = r.pending.unwrap();
    assert_eq!(
        (p.id.as_str(), p.kind.as_str()),
        ("toolguard:c1", "approval")
    );
    assert_eq!(p.prompt, "Approve the call to deploy? (medium risk)");
    let d = p.data.unwrap();
    assert_eq!(
        (d["tool"].clone(), d["reason"].clone()),
        (json!("deploy"), json!("medium risk"))
    );
    assert!(!ran.load(Ordering::SeqCst));

    // deny: short-circuits.
    let (srv, _) = recorder().await;
    let hooks = Hooks {
        before_tool: Some(guard(2.9).as_hook(Some(next.clone()))),
        ..Default::default()
    };
    let r = client(&srv.url, hooks, None)
        .run("ship it", &tk)
        .await
        .unwrap();
    assert!(!ran.load(Ordering::SeqCst));
    assert_eq!(r.tool_calls.len(), 1);
    assert_eq!(r.tool_calls[0].output, "denied by tool guard: high risk");

    // allow: next runs and the tool runs.
    let (srv, _) = recorder().await;
    let hooks = Hooks {
        before_tool: Some(guard(0.1).as_hook(Some(next))),
        ..Default::default()
    };
    let r = client(&srv.url, hooks, None)
        .run("ship it", &tk)
        .await
        .unwrap();
    assert!(ran.load(Ordering::SeqCst));
    assert_eq!(r.tool_calls[0].output, "DEPLOYED");

    // approved through waitFor: the tool runs once.
    let (srv, _) = recorder().await;
    let wait: WaitForFn = Arc::new(|q: Request| {
        Box::pin(async move {
            Ok(Answer {
                id: q.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let hooks = Hooks {
        before_tool: Some(guard(1.8).as_hook(None)),
        ..Default::default()
    };
    let r = client(&srv.url, hooks, Some(wait))
        .run("ship it", &tk)
        .await
        .unwrap();
    assert_ne!(r.status, "pending");
    assert_eq!(r.tool_calls[0].output, "DEPLOYED");
}

#[tokio::test]
async fn before_llm_model_override_is_per_turn() {
    let (srv, bodies) = recorder().await;
    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let s2 = seen.clone();
    let hooks = Hooks {
        before_llm: Some(Arc::new(|ev: BeforeLlmEvent| {
            Box::pin(async move {
                Ok((ev.turn == 0).then(|| LlmOverride {
                    model: Some("small-fast".into()),
                    ..Default::default()
                }))
            })
        })),
        after_llm: Some(Arc::new(move |ev: AfterLlmEvent| {
            s2.lock().unwrap().push(ev.model);
            Box::pin(async { Ok(()) })
        })),
        ..Default::default()
    };
    client(&srv.url, hooks, None)
        .run("go", &deploy_toolkit(false).await)
        .await
        .unwrap();
    let b = bodies.lock().unwrap();
    assert_eq!(b.len(), 2);
    assert_eq!(
        (b[0]["model"].clone(), b[1]["model"].clone()),
        (json!("small-fast"), json!("configured"))
    );
    assert_eq!(*seen.lock().unwrap(), vec!["small-fast", "configured"]);
}

#[tokio::test]
async fn model_router_hook() {
    let models = vec![
        ModelOption {
            id: "small-fast".into(),
            description: "cheap".into(),
        },
        ModelOption {
            id: "large-reasoning".into(),
            description: "dear".into(),
        },
    ];
    let sure = json!({"model":{"type":"choice","choice":"small-fast","confidence":0.91,"probabilities":{"small-fast":0.91,"large-reasoning":0.09}}});
    let unsure = json!({"model":{"type":"choice","choice":"small-fast","confidence":0.6,"probabilities":{"small-fast":0.6,"large-reasoning":0.4}}});
    let tk = deploy_toolkit(false).await;
    for (ans, want) in [(sure.clone(), "small-fast"), (unsure.clone(), "configured")] {
        let (srv, bodies) = recorder().await;
        let r = ModelRouter::new(fixed_classifier(ans), models.clone(), Default::default());
        let hooks = Hooks {
            before_llm: Some(r.as_hook(None)),
            ..Default::default()
        };
        client(&srv.url, hooks, None)
            .run("capital of France?", &tk)
            .await
            .unwrap();
        for b in bodies.lock().unwrap().iter() {
            assert_eq!(b["model"], want);
        }
    }
    // unsure, or sure of the configured model itself: no override at all.
    for a in [unsure, sure.clone()] {
        let ev = BeforeLlmEvent {
            messages: vec![json!({"role": "user", "content": "x"})],
            tools: vec![],
            model: "small-fast".into(),
            turn: 0,
        };
        let ov = ModelRouter::new(fixed_classifier(a), models.clone(), Default::default())
            .as_hook(None)(ev)
        .await
        .unwrap();
        assert!(ov.is_none());
    }
    // next's model wins over the router's.
    let (srv, bodies) = recorder().await;
    let next_saw = Arc::new(Mutex::new(String::new()));
    let n2 = next_saw.clone();
    let next: BeforeLlmHook = Arc::new(move |ev: BeforeLlmEvent| {
        *n2.lock().unwrap() = ev.model.clone();
        Box::pin(async {
            Ok(Some(LlmOverride {
                model: Some("pinned".into()),
                ..Default::default()
            }))
        })
    });
    let r = ModelRouter::new(fixed_classifier(sure), models, Default::default());
    let hooks = Hooks {
        before_llm: Some(r.as_hook(Some(next))),
        ..Default::default()
    };
    client(&srv.url, hooks, None).run("x", &tk).await.unwrap();
    assert_eq!(*next_saw.lock().unwrap(), "small-fast");
    assert_eq!(bodies.lock().unwrap()[0]["model"], "pinned");
}

#[tokio::test]
async fn tool_relevance_hook() {
    let tk = deploy_toolkit(true).await;
    let rel = ToolRelevance::new(
        fixed_classifier(
            json!({"deploy":{"type":"noul","noul":0.9},"send_email":{"type":"noul","noul":0.05}}),
        ),
        RelevanceOptions {
            on_error: Some(OnError::Open),
            ..Default::default()
        },
    )
    .unwrap();
    let (srv, bodies) = recorder().await;
    let hooks = Hooks {
        before_llm: Some(rel.as_hook(None)),
        ..Default::default()
    };
    client(&srv.url, hooks, None)
        .run("ship it", &tk)
        .await
        .unwrap();
    let tools = bodies.lock().unwrap()[0]["tools"]
        .as_array()
        .unwrap()
        .clone();
    assert_eq!(tools.len(), 1);
    assert_eq!(provider_tool(&tools[0]).name, "deploy");
}

#[tokio::test]
async fn tool_relevance_hook_cases() {
    let fx = load("batteries/tool-relevance.json");
    let hc = fx["hookCases"].as_array().unwrap();
    assert!(!hc.is_empty());
    for c in hc {
        let o = &c["options"];
        let r = ToolRelevance::new(
            battery_classifier(c),
            RelevanceOptions {
                on_error: on_err(o),
                bands: opt_bands(o),
                role: opt_str(o, "role"),
            },
        )
        .unwrap();
        let e = &c["event"];
        let tools = e["tools"].as_array().cloned().unwrap_or_default();
        let ev = BeforeLlmEvent {
            messages: e["messages"].as_array().cloned().unwrap_or_default(),
            tools: tools.clone(),
            model: opt_str(e, "model"),
            turn: e["turn"].as_u64().unwrap_or(0) as usize,
        };
        let ov = r.as_hook(None)(ev).await.unwrap();
        let got = match ov.and_then(|o| o.tools) {
            None => Value::Null,
            Some(kept) => Value::Array(
                kept.iter()
                    .filter_map(|k| tools.iter().position(|t| t == k))
                    .map(|i| json!(i))
                    .collect(),
            ),
        };
        let want = c["want"].get("tools").cloned().unwrap_or(Value::Null);
        assert!(json_eq(&got, &want), "{}: {got} want {want}", c["name"]);
    }
}

#[tokio::test]
async fn content_guard_hook() {
    let g = ContentGuard::new(
        fixed_classifier(
            json!({"harmful":{"type":"noul","noul":0.96},"prompt_injection":{"type":"noul","noul":0.9}}),
        ),
        ContentGuardOptions {
            on_error: Some(OnError::Closed),
            ..Default::default()
        },
    )
    .unwrap();
    let (srv, bodies) = recorder().await;
    let hooks = Hooks {
        before_llm: Some(g.as_hook(None)),
        ..Default::default()
    };
    let e = client(&srv.url, hooks, None)
        .run("idiot", &deploy_toolkit(false).await)
        .await
        .unwrap_err()
        .to_string();
    assert!(
        e.contains("content guard blocked: harmful, prompt_injection"),
        "{e}"
    );
    assert!(bodies.lock().unwrap().is_empty());

    let called = Arc::new(AtomicBool::new(false));
    let c2 = called.clone();
    let g = ContentGuard::new(
        fixed_classifier(
            json!({"harmful":{"type":"noul","noul":0.5},"prompt_injection":{"type":"noul","noul":0.1}}),
        ),
        ContentGuardOptions {
            on_error: Some(OnError::Closed),
            ..Default::default()
        },
    )
    .unwrap();
    let h = g.as_hook(Some(Arc::new(move |_| {
        c2.store(true, Ordering::SeqCst);
        Box::pin(async { Ok(None) })
    })));
    let ev = |t: &str| BeforeLlmEvent {
        messages: vec![json!({"role": "user", "content": t})],
        tools: vec![],
        model: String::new(),
        turn: 0,
    };
    h(ev("meh")).await.unwrap();
    assert!(called.load(Ordering::SeqCst), "review must delegate");

    let ge = ContentGuard::new(
        fail_classifier(false),
        ContentGuardOptions {
            on_error: Some(OnError::Closed),
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(
        ge.as_hook(None)(ev("x")).await.unwrap_err(),
        "content guard blocked: classifier error"
    );
}

#[tokio::test]
async fn tool_result_filter_hook() {
    let f = ToolResultFilter::new(
        fixed_classifier(
            json!({"0":{"type":"noul","noul":0.9},"1":{"type":"noul","noul":0.05},"2":{"type":"noul","noul":0.5}}),
        ),
        RelevanceOptions {
            on_error: Some(OnError::Open),
            ..Default::default()
        },
    )
    .unwrap();
    let saw = Arc::new(Mutex::new(String::new()));
    let s2 = saw.clone();
    let h = f.as_hook(Some(Arc::new(move |ev: AfterToolEvent| {
        *s2.lock().unwrap() = ev.result.output.clone();
        Box::pin(async { Ok(None) })
    })));
    let ev = |r: ToolResult| AfterToolEvent {
        name: "t".into(),
        args: Value::Null,
        result: r,
        id: "c1".into(),
        turn: 0,
    };
    let ov = h(ev(ToolResult::ok("a\n\nb\n\nc"))).await.unwrap().unwrap();
    assert_eq!(ov.result.unwrap().output, "a\n\nc");
    assert_eq!(*saw.lock().unwrap(), "a\n\nc");

    let fe = ToolResultFilter::new(
        fail_classifier(true),
        RelevanceOptions {
            on_error: Some(OnError::Closed),
            ..Default::default()
        },
    )
    .unwrap();
    let image = ToolResult {
        output: "a\n\nb".into(),
        parts: vec![toolnexus::ContentPart {
            kind: "image".into(),
            ..Default::default()
        }],
        ..Default::default()
    };
    let errored = ToolResult {
        output: "a\n\nb".into(),
        is_error: true,
        ..Default::default()
    };
    for r in [ToolResult::ok("one"), errored, image] {
        assert!(fe.as_hook(None)(ev(r)).await.unwrap().is_none());
    }
}
