//! Relay (declaration-only) tools and the durable resume path — SPEC §10,
//! ADR-0010. Mirrors golang/relay_test.go. Hermetic: a loopback provider.

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use common::{Resp, serve};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::client::{Client, ClientOptions, Style, WaitForFn, create_client};
use toolnexus::relay::{
    RELAY_IS_ERROR_KEY, RELAY_KIND, RELAY_OUTPUT_KEY, RelayResult, answer_declined, answer_output,
    is_relay_request, relay_answer, relay_calls_of, relay_tool,
};
use toolnexus::{
    Answer, BuiltinsConfig, Request, Tool, ToolSource, Toolkit, ToolkitOptions, auth_required,
    create_toolkit, define_tool, to_anthropic, to_gemini, to_openai,
};

type Call = (&'static str, &'static str, &'static str);

/// One scripted turn per request; past the script, a final text answer.
async fn llm(
    style: Style,
    turns: Vec<Vec<Call>>,
    fin: &'static str,
) -> (String, Arc<Mutex<Vec<Value>>>) {
    let bodies = Arc::new(Mutex::new(Vec::<Value>::new()));
    let b = bodies.clone();
    let turns = Arc::new(turns);
    let srv = serve(move |req| {
        let (b, turns) = (b.clone(), turns.clone());
        async move {
            let i = {
                let mut g = b.lock().unwrap();
                g.push(req.body.clone());
                g.len() - 1
            };
            let out = match style {
                Style::OpenAI => {
                    let msg = if i < turns.len() {
                        let tcs: Vec<Value> = turns[i]
                            .iter()
                            .map(|(id, n, a)| json!({"id": id, "type": "function", "function": {"name": n, "arguments": a}}))
                            .collect();
                        json!({"role": "assistant", "content": null, "tool_calls": tcs})
                    } else {
                        json!({"role": "assistant", "content": fin})
                    };
                    json!({"choices": [{"message": msg}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
                }
                Style::Anthropic => {
                    let (content, stop) = if i < turns.len() {
                        let c: Vec<Value> = turns[i]
                            .iter()
                            .map(|(id, n, a)| {
                                let input: Value = serde_json::from_str(a).unwrap_or(json!({}));
                                json!({"type": "tool_use", "id": id, "name": n, "input": input})
                            })
                            .collect();
                        (c, "tool_use")
                    } else {
                        (vec![json!({"type": "text", "text": fin})], "end_turn")
                    };
                    json!({"content": content, "stop_reason": stop, "usage": {"input_tokens": 1, "output_tokens": 1}})
                }
            };
            Resp::json(out)
        }
    })
    .await;
    (srv.url, bodies)
}

fn client(url: &str, style: Style, wait_for: Option<WaitForFn>) -> Client {
    create_client(ClientOptions {
        base_url: url.into(),
        style,
        model: "stub".into(),
        api_key: "k".into(),
        retries: -1,
        wait_for,
        ..Default::default()
    })
}

fn rt(name: &str) -> Tool {
    relay_tool(name, "relayed to the caller", None)
}

async fn tk(tools: Vec<Tool>) -> Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap()
}

fn answer_with(f: impl Fn(&Request) -> Answer + Send + Sync + 'static) -> Option<WaitForFn> {
    let f = Arc::new(f);
    Some(Arc::new(move |req: Request| {
        let a = f(&req);
        Box::pin(async move { Ok(a) })
    }))
}

fn data(pairs: &[(&str, Value)]) -> Option<Map<String, Value>> {
    Some(
        pairs
            .iter()
            .map(|(k, v)| (k.to_string(), v.clone()))
            .collect(),
    )
}

fn counts(messages: &[Value]) -> (usize, usize, Vec<String>) {
    let (mut calls, mut results, mut ids) = (0, 0, Vec::new());
    for m in messages {
        if let Some(t) = m.get("tool_calls").and_then(Value::as_array) {
            calls += t.len();
        }
        if let Some(id) = m.get("tool_call_id").and_then(Value::as_str) {
            results += 1;
            ids.push(id.to_string());
        }
    }
    (calls, results, ids)
}

#[tokio::test]
async fn relay_call_reaches_host_and_nothing_runs_locally() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", r#"{"q":"weather"}"#)]],
        "it is sunny",
    )
    .await;
    let got = Arc::new(Mutex::new(Request::default()));
    let g = got.clone();
    let c = client(
        &url,
        Style::OpenAI,
        answer_with(move |req| {
            *g.lock().unwrap() = req.clone();
            Answer {
                id: req.id.clone(),
                ok: true,
                data: data(&[(RELAY_OUTPUT_KEY, json!("sunny, 31C"))]),
                ..Default::default()
            }
        }),
    );
    let res = c
        .run("weather?", &tk(vec![rt("lookup")]).await)
        .await
        .unwrap();
    assert!(res.status == "done" && res.text == "it is sunny", "{res:?}");
    let got = got.lock().unwrap().clone();
    assert!(is_relay_request(&got) && got.kind == RELAY_KIND);
    let calls = relay_calls_of(&got);
    assert_eq!(calls.len(), 1);
    assert!(calls[0].id == "c1" && calls[0].name == "lookup");
    assert_eq!(calls[0].input["q"], "weather");
    assert!(calls[0].arguments.contains(r#""weather""#));
    assert!(
        res.tool_calls.len() == 1
            && res.tool_calls[0].output == "sunny, 31C"
            && !res.tool_calls[0].is_error
    );
}

#[test]
fn relay_tool_is_declared_to_every_adapter() {
    let tools = vec![relay_tool(
        "lookup",
        "look something up",
        Some(json!({"type": "object", "properties": {"q": {"type": "string"}}})),
    )];
    for got in [to_openai(&tools), to_anthropic(&tools), to_gemini(&tools)] {
        let raw = Value::Array(got).to_string();
        assert!(
            raw.contains("lookup") && raw.contains("look something up"),
            "{raw}"
        );
    }
    assert_eq!(tools[0].source, ToolSource::Relay);
}

#[tokio::test]
async fn relay_on_anthropic_loop() {
    let (url, bodies) = llm(
        Style::Anthropic,
        vec![vec![("tu_1", "lookup", r#"{"q":"x"}"#)]],
        "done",
    )
    .await;
    let c = client(
        &url,
        Style::Anthropic,
        answer_with(|req| {
            let calls = relay_calls_of(req);
            assert!(calls.len() == 1 && calls[0].id == "tu_1", "{calls:?}");
            answer_output(&req.id, "anthropic-out")
        }),
    );
    let res = c.run("go", &tk(vec![rt("lookup")]).await).await.unwrap();
    assert_eq!(res.status, "done");
    let raw = bodies.lock().unwrap()[1]["messages"].to_string();
    for want in [r#""tool_result""#, r#""tu_1""#, "anthropic-out"] {
        assert!(raw.contains(want), "{raw}");
    }
}

#[tokio::test]
async fn relay_caller_tool_failed_is_an_error_result() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", "{}")]],
        "recovered",
    )
    .await;
    let c = client(
        &url,
        Style::OpenAI,
        answer_with(|req| Answer {
            id: req.id.clone(),
            ok: true,
            data: data(&[
                (RELAY_OUTPUT_KEY, json!("upstream 500")),
                (RELAY_IS_ERROR_KEY, json!(true)),
            ]),
            ..Default::default()
        }),
    );
    let res = c.run("go", &tk(vec![rt("lookup")]).await).await.unwrap();
    assert_eq!(res.status, "done");
    assert!(res.tool_calls.len() == 1 && res.tool_calls[0].is_error);
}

#[tokio::test]
async fn relay_declined_by_caller_continues() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", "{}")]],
        "understood",
    )
    .await;
    let c = client(
        &url,
        Style::OpenAI,
        answer_with(|req| answer_declined(&req.id, "")),
    );
    let res = c.run("go", &tk(vec![rt("lookup")]).await).await.unwrap();
    assert!(res.status == "done" && res.tool_calls.len() == 1 && res.tool_calls[0].is_error);
}

fn abc() -> Vec<Vec<Call>> {
    vec![vec![
        ("c1", "alpha", r#"{"n":1}"#),
        ("c2", "beta", r#"{"n":2}"#),
        ("c3", "gamma", r#"{"n":3}"#),
    ]]
}

#[tokio::test]
async fn three_relay_calls_all_ride_the_surfaced_request() {
    for _ in 0..20 {
        let (url, _) = llm(Style::OpenAI, abc(), "unreachable").await;
        let c = client(&url, Style::OpenAI, None);
        let res = c
            .run("go", &tk(vec![rt("alpha"), rt("beta"), rt("gamma")]).await)
            .await
            .unwrap();
        assert_eq!(res.status, "pending");
        let calls = relay_calls_of(res.pending.as_ref().unwrap());
        let got: Vec<(String, String)> = calls.into_iter().map(|c| (c.id, c.name)).collect();
        assert_eq!(
            got,
            vec![
                ("c1".to_string(), "alpha".to_string()),
                ("c2".into(), "beta".into()),
                ("c3".into(), "gamma".into())
            ],
            "tool-call order, deterministic"
        );
    }
}

#[tokio::test]
async fn resume_fills_every_outstanding_tool_result_slot() {
    let (url, bodies) = llm(Style::OpenAI, abc(), "all three answered").await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("alpha"), rt("beta"), rt("gamma")]).await;
    let halted = c.run("go", &t).await.unwrap();
    assert_eq!(halted.status, "pending");
    let p = halted.pending.clone().unwrap();
    let ans = relay_answer(
        &p.id,
        &[
            RelayResult {
                id: "c1".into(),
                output: "out-alpha".into(),
                is_error: false,
            },
            RelayResult {
                id: "c2".into(),
                output: "out-beta".into(),
                is_error: false,
            },
            RelayResult {
                id: "c3".into(),
                output: "out-gamma".into(),
                is_error: false,
            },
        ],
    );
    let resumed = c
        .run_with_answer(
            CancellationToken::new(),
            &t,
            halted.messages.clone(),
            &p,
            &ans,
        )
        .await
        .unwrap();
    assert!(
        resumed.status == "done" && resumed.text == "all three answered",
        "{resumed:?}"
    );
    let (calls, results, ids) = counts(&resumed.messages);
    assert_eq!(calls, results);
    assert_eq!(ids.len(), 3);
    let raw = Value::Array(resumed.messages.clone()).to_string();
    for w in ["out-alpha", "out-beta", "out-gamma"] {
        assert!(raw.contains(w));
    }
    assert!(
        !raw.contains("relay tool call alpha to the caller"),
        "placeholder survived"
    );
    let last = bodies.lock().unwrap().last().unwrap()["messages"].to_string();
    for w in ["out-alpha", "out-beta", "out-gamma"] {
        assert!(last.contains(w), "{last}");
    }
    // a resume appends no new user turn
    let users = resumed
        .messages
        .iter()
        .filter(|m| m["role"] == "user")
        .count();
    assert_eq!(users, 1);
}

#[tokio::test]
async fn resume_single_call_shorthand() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", "{}")]],
        "answered",
    )
    .await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("lookup")]).await;
    let halted = c.run("go", &t).await.unwrap();
    let p = halted.pending.clone().unwrap();
    let resumed = c
        .run_with_answer(
            CancellationToken::new(),
            &t,
            halted.messages,
            &p,
            &answer_output(&p.id, "single-out"),
        )
        .await
        .unwrap();
    assert_eq!(resumed.status, "done");
    assert!(
        Value::Array(resumed.messages)
            .to_string()
            .contains("single-out")
    );
}

#[tokio::test]
async fn resume_on_anthropic_transcript() {
    let (url, _) = llm(
        Style::Anthropic,
        vec![vec![("tu_1", "alpha", "{}"), ("tu_2", "beta", "{}")]],
        "both answered",
    )
    .await;
    let c = client(&url, Style::Anthropic, None);
    let t = tk(vec![rt("alpha"), rt("beta")]).await;
    let halted = c.run("go", &t).await.unwrap();
    assert_eq!(halted.status, "pending");
    let p = halted.pending.clone().unwrap();
    let ans = relay_answer(
        &p.id,
        &[
            RelayResult {
                id: "tu_1".into(),
                output: "out-1".into(),
                is_error: false,
            },
            RelayResult {
                id: "tu_2".into(),
                output: "out-2".into(),
                is_error: false,
            },
        ],
    );
    let resumed = c
        .run_with_answer(CancellationToken::new(), &t, halted.messages, &p, &ans)
        .await
        .unwrap();
    assert_eq!(resumed.status, "done");
    let (mut uses, mut results) = (0, 0);
    for m in &resumed.messages {
        for b in m["content"].as_array().into_iter().flatten() {
            match b["type"].as_str() {
                Some("tool_use") => uses += 1,
                Some("tool_result") => results += 1,
                _ => {}
            }
        }
    }
    assert!(uses == results && uses > 0, "{uses} vs {results}");
}

#[tokio::test]
async fn resume_across_a_process_boundary() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", "{}")]],
        "resumed elsewhere",
    )
    .await;
    let t = tk(vec![rt("lookup")]).await;
    let halted = client(&url, Style::OpenAI, None)
        .run("go", &t)
        .await
        .unwrap();
    let blob = json!({"messages": halted.messages, "pending": halted.pending}).to_string();
    let v: Value = serde_json::from_str(&blob).unwrap();
    let messages = v["messages"].as_array().unwrap().clone();
    let pending: Request = serde_json::from_value(v["pending"].clone()).unwrap();
    let calls = relay_calls_of(&pending);
    assert!(calls.len() == 1 && calls[0].id == "c1");
    let ans = relay_answer(
        &pending.id,
        &[RelayResult {
            id: "c1".into(),
            output: "cross-process".into(),
            is_error: false,
        }],
    );
    let resumed = client(&url, Style::OpenAI, None)
        .run_with_answer(CancellationToken::new(), &t, messages, &pending, &ans)
        .await
        .unwrap();
    assert!(resumed.status == "done" && resumed.text == "resumed elsewhere");
}

#[tokio::test]
async fn resume_rejects_a_mismatched_answer_and_a_missing_transcript() {
    let (url, _) = llm(Style::OpenAI, vec![vec![("c1", "lookup", "{}")]], "x").await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("lookup")]).await;
    let halted = c.run("go", &t).await.unwrap();
    let p = halted.pending.clone().unwrap();
    let ans = relay_answer(
        "pnd-not-the-one",
        &[RelayResult {
            id: "c1".into(),
            output: "x".into(),
            is_error: false,
        }],
    );
    let err = c
        .run_with_answer(CancellationToken::new(), &t, halted.messages, &p, &ans)
        .await
        .unwrap_err();
    assert!(err.to_string().contains("does not echo"), "{err}");
    let req = Request {
        id: "p1".into(),
        kind: RELAY_KIND.into(),
        ..Default::default()
    };
    let ans = Answer {
        id: "p1".into(),
        ok: true,
        ..Default::default()
    };
    assert!(
        c.run_with_answer(CancellationToken::new(), &t, Vec::new(), &req, &ans)
            .await
            .is_err()
    );
}

#[tokio::test]
async fn resume_refuses_an_ok_answer_with_no_readable_result() {
    let (url, _) = llm(Style::OpenAI, vec![vec![("c1", "lookup", "{}")]], "x").await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("lookup")]).await;
    let halted = c.run("go", &t).await.unwrap();
    let p = halted.pending.clone().unwrap();
    let ans = Answer {
        id: p.id.clone(),
        ok: true,
        data: data(&[("text", json!("x"))]),
        ..Default::default()
    };
    let err = c
        .run_with_answer(CancellationToken::new(), &t, halted.messages, &p, &ans)
        .await
        .unwrap_err()
        .to_string();
    assert!(
        err.contains("carries no result") && err.contains("Keys seen: [text]"),
        "{err}"
    );
}

#[tokio::test]
async fn ask_with_answer_round_trips_through_the_store() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", "{}")]],
        "stored answer",
    )
    .await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("lookup")]).await;
    let halted = c.ask("go", &t, "conv-relay").await.unwrap();
    assert_eq!(halted.status, "pending");
    let p = halted.pending.clone().unwrap();
    let ans = relay_answer(
        &p.id,
        &[RelayResult {
            id: "c1".into(),
            output: "stored-out".into(),
            is_error: false,
        }],
    );
    let resumed = c
        .ask_with_answer(CancellationToken::new(), &t, "conv-relay", &p, &ans)
        .await
        .unwrap();
    assert!(resumed.status == "done" && resumed.text == "stored answer");
    let saved = c.conversation_store().get("conv-relay").unwrap().unwrap();
    assert!(Value::Array(saved).to_string().contains("stored-out"));
    assert!(
        c.ask_with_answer(
            CancellationToken::new(),
            &t,
            "",
            &Request::default(),
            &Answer::default()
        )
        .await
        .is_err()
    );
}

#[tokio::test]
async fn resume_supplies_error_results_for_missing_outputs() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "alpha", "{}"), ("c2", "beta", "{}")]],
        "partial",
    )
    .await;
    let c = client(&url, Style::OpenAI, None);
    let t = tk(vec![rt("alpha"), rt("beta")]).await;
    let halted = c.run("go", &t).await.unwrap();
    let p = halted.pending.clone().unwrap();
    let ans = relay_answer(
        &p.id,
        &[RelayResult {
            id: "c1".into(),
            output: "only-alpha".into(),
            is_error: false,
        }],
    );
    let resumed = c
        .run_with_answer(CancellationToken::new(), &t, halted.messages, &p, &ans)
        .await
        .unwrap();
    let (calls, results, _) = counts(&resumed.messages);
    assert_eq!(calls, results);
    assert!(
        Value::Array(resumed.messages)
            .to_string()
            .contains("no result supplied on resume for beta")
    );
}

#[tokio::test]
async fn relay_name_colliding_with_a_builtin_is_rejected() {
    for builtins in [BuiltinsConfig::default(), BuiltinsConfig::off()] {
        let err = create_toolkit(ToolkitOptions {
            builtins: Some(builtins),
            extra_tools: vec![rt("bash")],
            ..Default::default()
        })
        .await
        .unwrap_err();
        assert!(
            err.to_string().contains("collides with a built-in"),
            "{err}"
        );
    }
}

#[tokio::test]
async fn non_relay_tool_may_shadow_a_builtin() {
    let mine = define_tool("bash", "mine", None, |_: Value, _| async {
        Ok::<_, String>("mine".into())
    });
    let t = create_toolkit(ToolkitOptions {
        extra_tools: vec![mine],
        ..Default::default()
    })
    .await
    .unwrap();
    t.close().await;
}

#[tokio::test]
async fn uncalled_relay_tool_is_inert() {
    let run = |tools: Vec<Tool>| async move {
        let (url, _) = llm(Style::OpenAI, vec![], "plain answer").await;
        client(&url, Style::OpenAI, None)
            .run("hello", &tk(tools).await)
            .await
            .unwrap()
    };
    let (base, with) = (run(vec![]).await, run(vec![rt("never_called")]).await);
    assert!(base.text == with.text && base.status == with.status && base.turns == with.turns);
    assert!(with.tool_calls.is_empty());
}

#[tokio::test]
async fn real_tool_still_runs_alongside_a_relay_call() {
    for with_wait in [true, false] {
        let (url, _) = llm(
            Style::OpenAI,
            vec![vec![("c1", "realtool", "{}"), ("c2", "relayed", "{}")]],
            "done",
        )
        .await;
        let ran = Arc::new(AtomicUsize::new(0));
        let r = ran.clone();
        let real = define_tool("realtool", "runs", None, move |_: Value, _| {
            let r = r.clone();
            async move {
                r.fetch_add(1, Ordering::SeqCst);
                Ok::<_, String>("ran".into())
            }
        });
        let w = if with_wait {
            answer_with(|req| answer_output(&req.id, "relayed"))
        } else {
            None
        };
        client(&url, Style::OpenAI, w)
            .run("go", &tk(vec![real, rt("relayed")]).await)
            .await
            .unwrap();
        assert_eq!(ran.load(Ordering::SeqCst), 1, "waitFor={with_wait}");
    }
}

#[tokio::test]
async fn relay_suspension_is_not_a_tool_error_and_may_repeat() {
    let (url, _) = llm(
        Style::OpenAI,
        vec![
            vec![("c1", "step", r#"{"i":1}"#)],
            vec![("c2", "step", r#"{"i":2}"#)],
            vec![("c3", "step", r#"{"i":3}"#)],
        ],
        "three rounds",
    )
    .await;
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let res = client(
        &url,
        Style::OpenAI,
        answer_with(move |req| {
            n2.fetch_add(1, Ordering::SeqCst);
            answer_output(&req.id, "round")
        }),
    )
    .run("go", &tk(vec![rt("step")]).await)
    .await
    .unwrap();
    assert!(res.status == "done" && n.load(Ordering::SeqCst) == 3 && res.tool_calls.len() == 3);
    assert!(res.tool_calls.iter().all(|c| !c.is_error));
}

#[tokio::test]
async fn relay_and_auth_suspension_in_the_same_turn() {
    let auth = Tool {
        name: "needs_login".into(),
        description: "auth".into(),
        input_schema: json!({"type": "object", "properties": {}}),
        source: ToolSource::Custom,
        hints: None,
        execute: Arc::new(|_, _| {
            Box::pin(async { auth_required("https://example.test/login", "log in first") })
        }),
    };
    let (url, _) = llm(
        Style::OpenAI,
        vec![vec![("c1", "needs_login", "{}"), ("c2", "relayed", "{}")]],
        "x",
    )
    .await;
    let res = client(&url, Style::OpenAI, None)
        .run("go", &tk(vec![auth, rt("relayed")]).await)
        .await
        .unwrap();
    assert_eq!(res.status, "pending");
    let p = res.pending.unwrap();
    assert_eq!(p.kind, "authorization", "first-in-order wins");
    assert!(
        relay_calls_of(&p).is_empty(),
        "the merge contaminated the auth request"
    );
}

#[tokio::test]
async fn relay_round_trips_through_conversation_store() {
    let (url, bodies) = llm(
        Style::OpenAI,
        vec![vec![("c1", "lookup", r#"{"q":"x"}"#)]],
        "first answer",
    )
    .await;
    let c = client(
        &url,
        Style::OpenAI,
        answer_with(|req| answer_output(&req.id, "relayed-value")),
    );
    let t = tk(vec![rt("lookup")]).await;
    c.ask("first", &t, "conv-1").await.unwrap();
    c.ask("second", &t, "conv-1").await.unwrap();
    let last = bodies.lock().unwrap().last().unwrap()["messages"].to_string();
    for w in [r#""tool_calls""#, r#""c1""#, "relayed-value", r#""lookup""#] {
        assert!(last.contains(w), "{last}");
    }
}

#[tokio::test]
async fn relay_answer_with_a_non_string_output_is_a_loud_error_result() {
    let (url, _) = llm(Style::OpenAI, vec![vec![("c1", "lookup", "{}")]], "ok").await;
    let res = client(
        &url,
        Style::OpenAI,
        answer_with(|req| Answer {
            id: req.id.clone(),
            ok: true,
            data: data(&[(RELAY_OUTPUT_KEY, json!(42))]),
            ..Default::default()
        }),
    )
    .run("go", &tk(vec![rt("lookup")]).await)
    .await
    .unwrap();
    let tc = &res.tool_calls[0];
    assert!(tc.is_error && tc.output.contains("non-string"), "{tc:?}");
}
