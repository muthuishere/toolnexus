//! §10 suspension (SPEC §0 item 12): mirrors golang pending_test.go and
//! suspension_pathb_test.go. A real loop against a stub provider.

mod common;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use common::*;
use serde_json::{Value, json};
use toolnexus::client::*;
use toolnexus::{
    Answer, Request, Tool, ToolContext, ToolResult, ToolSource, auth_required, create_toolkit,
    pending, pending_of,
};

fn stub_llm_handler(r: Req) -> Resp {
    if r.has_role("tool") {
        let last = r.messages().last().cloned().unwrap();
        return Resp::json(oa_text(&format!(
            "Done: {}",
            last["content"].as_str().unwrap_or("")
        )));
    }
    Resp::json(oa_calls(&[("c1", "get_balance", "{}")]))
}

async fn balance_toolkit(authed: Arc<AtomicBool>) -> toolnexus::Toolkit {
    let balance = Tool {
        concurrency_safe: None,
        name: "get_balance".into(),
        description: "Return the account balance. Requires login first.".into(),
        input_schema: json!({"type": "object", "properties": {}, "additionalProperties": false}),
        source: ToolSource::Custom,
        hints: None,
        execute: Arc::new(move |_, ctx: ToolContext| {
            let authed = authed.clone();
            Box::pin(async move {
                if !authed.load(Ordering::SeqCst) {
                    return auth_required(
                        "https://example.com/login?token=abc",
                        "Log in to view your balance",
                    );
                }
                let id = ctx.answer.map(|a| a.id).unwrap_or_default();
                ToolResult::ok(format!("balance: 67,417 (resolved via answer {id})"))
            })
        }),
    };
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![balance],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn base(url: &str) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        model: "stub".into(),
        api_key: "k".into(),
        ..Default::default()
    }
}

#[tokio::test]
async fn wait_for_resolves_and_retries() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let authed = Arc::new(AtomicBool::new(false));
    let tk = balance_toolkit(authed.clone()).await;
    let seen = Arc::new(Mutex::new(None::<Request>));
    let s2 = seen.clone();
    let wait: WaitForFn = Arc::new(move |req: Request| {
        let (authed, s) = (authed.clone(), s2.clone());
        Box::pin(async move {
            *s.lock().unwrap() = Some(req.clone());
            authed.store(true, Ordering::SeqCst); // the human logged in
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(wait),
        ..base(&srv.url)
    })
    .run("balance?", &tk)
    .await
    .unwrap();
    assert_eq!(res.status, "done");
    let req = seen.lock().unwrap().clone().unwrap();
    assert_eq!(req.kind, "authorization");
    assert_eq!(req.url, "https://example.com/login?token=abc");
    assert!(req.id.starts_with("pnd-"));
    assert_eq!(
        res.text,
        format!("Done: balance: 67,417 (resolved via answer {})", req.id)
    );
}

#[tokio::test]
async fn no_wait_for_halts_durably() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let tk = balance_toolkit(Arc::new(AtomicBool::new(false))).await;
    let res = create_client(base(&srv.url))
        .run("balance?", &tk)
        .await
        .unwrap();
    assert_eq!(res.status, "pending");
    let p = res.pending.clone().unwrap();
    assert_eq!(p.kind, "authorization");
    assert_eq!(res.text, "Log in to view your balance");
    let last = res.messages.last().unwrap();
    assert_eq!(last["role"], "tool");
    assert_eq!(last["content"], "Log in to view your balance");
}

#[tokio::test]
async fn declined_answer_feeds_back_an_error() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let tk = balance_toolkit(Arc::new(AtomicBool::new(false))).await;
    let wait: WaitForFn = Arc::new(|req: Request| {
        Box::pin(async move {
            Ok(Answer {
                id: req.id,
                ok: false,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(wait),
        ..base(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(
        res.text,
        "Done: declined/expired: Log in to view your balance"
    );
    assert!(res.tool_calls[0].is_error);
}

#[tokio::test]
async fn second_suspension_is_unresolved_never_loops() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let tk = balance_toolkit(Arc::new(AtomicBool::new(false))).await; // never authed
    let wait: WaitForFn = Arc::new(|req: Request| {
        Box::pin(async move {
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(wait),
        ..base(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "Done: unresolved: Log in to view your balance");
}

#[tokio::test]
async fn wait_for_ctx_wins_and_sees_cancellation() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let authed = Arc::new(AtomicBool::new(false));
    let tk = balance_toolkit(authed.clone()).await;
    let plain: WaitForFn = Arc::new(|_| Box::pin(async { Err("plain must not be used".into()) }));
    let ctxw: WaitForCtxFn = Arc::new(move |cancel, req: Request| {
        let authed = authed.clone();
        Box::pin(async move {
            assert!(!cancel.is_cancelled());
            authed.store(true, Ordering::SeqCst);
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(plain),
        wait_for_ctx: Some(ctxw),
        ..base(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.status, "done");

    // a cancelled turn ends a pending question
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let tk = balance_toolkit(Arc::new(AtomicBool::new(false))).await;
    let ctxw: WaitForCtxFn = Arc::new(|cancel, _req| {
        Box::pin(async move {
            cancel.cancelled().await;
            Err("cancelled".into())
        })
    });
    let cancel = tokio_util::sync::CancellationToken::new();
    let c2 = cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        c2.cancel();
    });
    let err = create_client(ClientOptions {
        wait_for_ctx: Some(ctxw),
        ..base(&srv.url)
    })
    .run_ctx(cancel, "x", &tk, vec![])
    .await
    .unwrap_err();
    assert!(
        matches!(err, ClientError::WaitFor(_) | ClientError::Cancelled),
        "{err:?}"
    );
}

#[tokio::test]
async fn suspension_metric_is_pending_not_error_and_after_tool_skips_it() {
    let srv = serve(|r| async move { stub_llm_handler(r) }).await;
    let authed = Arc::new(AtomicBool::new(false));
    let tk = balance_toolkit(authed.clone()).await;
    let events = Arc::new(Mutex::new(Vec::<MetricEvent>::new()));
    let e2 = events.clone();
    let after_saw = Arc::new(Mutex::new(Vec::<ToolResult>::new()));
    let a2 = after_saw.clone();
    let after: AfterToolHook = Arc::new(move |ev: AfterToolEvent| {
        let a = a2.clone();
        Box::pin(async move {
            a.lock().unwrap().push(ev.result);
            Ok(None)
        })
    });
    let wait: WaitForFn = Arc::new(move |req: Request| {
        let authed = authed.clone();
        Box::pin(async move {
            authed.store(true, Ordering::SeqCst);
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(wait),
        hooks: Hooks {
            after_tool: Some(after),
            ..Default::default()
        },
        on_metric: Some(Arc::new(move |e: &MetricEvent| {
            e2.lock().unwrap().push(e.clone())
        })),
        ..base(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.status, "done");
    let ev = events.lock().unwrap();
    let suspend = ev
        .iter()
        .find(|e| e.event == "tool" && e.pending)
        .expect("a pending-tagged tool metric");
    assert!(!suspend.is_error, "a suspension is not a tool error");
    let saw = after_saw.lock().unwrap();
    assert_eq!(saw.len(), 1, "afterTool sees only the resolved result");
    assert!(pending_of(&saw[0]).is_none());
}

fn two_questions(r: Req) -> Resp {
    if r.has_role("tool") {
        return Resp::json(oa_text("answered"));
    }
    Resp::json(oa_calls(&[
        ("c1", "ask", "{\"q\":\"First?\"}"),
        ("c2", "ask", "{\"q\":\"Second?\"}"),
    ]))
}

fn ask_tool() -> Tool {
    toolnexus::define_tool("ask", "ask the human", None, |_, _| async {
        Ok::<_, String>(String::new())
    })
}

async fn ask_toolkit() -> toolnexus::Toolkit {
    let mut t = ask_tool();
    t.execute = Arc::new(|args: Value, ctx: ToolContext| {
        Box::pin(async move {
            if let Some(a) = ctx.answer {
                return ToolResult::ok(serde_json::to_string(&a.data).unwrap());
            }
            pending(Request {
                kind: "question".into(),
                prompt: args["q"].as_str().unwrap_or("").into(),
                ..Default::default()
            })
        })
    });
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![t],
        ..Default::default()
    })
    .await
    .unwrap()
}

#[tokio::test]
async fn concurrent_suspensions_surface_first() {
    let srv = serve(|r| async move { two_questions(r) }).await;
    let tk = ask_toolkit().await;
    let res = create_client(base(&srv.url))
        .run("ask two", &tk)
        .await
        .unwrap();
    assert_eq!(res.status, "pending");
    assert_eq!(res.pending.unwrap().prompt, "First?");
    assert!(res.messages.iter().all(|m| m["tool_call_id"] != "c2"));
}

#[tokio::test]
async fn concurrent_suspensions_surface_first_streaming() {
    let srv = serve(|_| async {
        Resp::sse(sse_lines(&[
            json!({"choices": [{"delta": {"tool_calls": [
                {"index": 0, "id": "c1", "type": "function", "function": {"name": "ask", "arguments": "{\"q\":\"First?\"}"}},
                {"index": 1, "id": "c2", "type": "function", "function": {"name": "ask", "arguments": "{\"q\":\"Second?\"}"}}]}}]}),
            json!({"choices": [{"delta": {}}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}),
        ]))
    })
    .await;
    let tk = ask_toolkit().await;
    let mut rx = create_client(base(&srv.url))
        .stream("ask two", &tk)
        .unwrap();
    let mut res = None;
    let mut pendings = Vec::new();
    let mut saw_c2_result = false;
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "done" => res = ev.result,
            "pending" => pendings.push(ev.request.unwrap().prompt),
            "tool_result" if ev.id == "c2" => saw_c2_result = true,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    let res = res.unwrap();
    assert_eq!(res.status, "pending");
    assert_eq!(res.pending.unwrap().prompt, "First?");
    assert!(!saw_c2_result);
    assert!(
        pendings.contains(&"First?".to_string()),
        "{{type:pending}} streamed"
    );
    assert!(res.messages.iter().all(|m| m["tool_call_id"] != "c2"));
}

#[tokio::test]
async fn wait_for_answer_data_reaches_the_tool() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            let tools: Vec<String> = r
                .messages()
                .iter()
                .filter(|m| m["role"] == "tool")
                .map(|m| m["content"].as_str().unwrap().to_owned())
                .collect();
            return Resp::json(oa_text(&tools.join("|")));
        }
        two_questions(r)
    })
    .await;
    let tk = ask_toolkit().await;
    let wait: WaitForFn = Arc::new(|req: Request| {
        Box::pin(async move {
            let mut data = serde_json::Map::new();
            data.insert("a".into(), json!(req.prompt));
            Ok(Answer {
                id: req.id,
                ok: true,
                data: Some(data),
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        wait_for: Some(wait),
        ..base(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "{\"a\":\"First?\"}|{\"a\":\"Second?\"}");
}

// -- path B: a beforeTool hook raises the suspension ------------------------------

async fn deploy_toolkit() -> toolnexus::Toolkit {
    let deploy = toolnexus::define_tool(
        "deploy",
        "deploy",
        Some(json!({"type": "object"})),
        |_, _| async { Ok::<_, String>("DEPLOYED".into()) },
    );
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![deploy],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn deploy_llm(r: Req) -> Resp {
    if r.has_role("tool") {
        return Resp::json(oa_text("done"));
    }
    Resp::json(oa_calls(&[("c1", "deploy", "{\"env\":\"prod\"}")]))
}

fn approval_hook() -> BeforeToolHook {
    Arc::new(|ev: BeforeToolEvent| {
        Box::pin(async move {
            Ok(Some(ToolOverride {
                args: None,
                result: Some(pending(Request {
                    id: "appr-1".into(),
                    kind: "approval".into(),
                    prompt: format!("Approve {}?", ev.name),
                    ..Default::default()
                })),
            }))
        })
    })
}

#[tokio::test]
async fn before_tool_pending_halts_durably_without_wait_for() {
    let srv = serve(|r| async move { deploy_llm(r) }).await;
    let tk = deploy_toolkit().await;
    let res = create_client(ClientOptions {
        hooks: Hooks {
            before_tool: Some(approval_hook()),
            ..Default::default()
        },
        max_turns: 4,
        ..base(&srv.url)
    })
    .run("ship it", &tk)
    .await
    .unwrap();
    assert_eq!(res.status, "pending");
    let p = res.pending.unwrap();
    assert_eq!((p.id.as_str(), p.kind.as_str()), ("appr-1", "approval"));
    assert!(
        res.tool_calls.iter().all(|c| c.output != "DEPLOYED"),
        "the tool must not run"
    );
}

#[tokio::test]
async fn before_tool_pending_resolved_runs_the_tool_once() {
    let srv = serve(|r| async move { deploy_llm(r) }).await;
    let tk = deploy_toolkit().await;
    let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let c2 = calls.clone();
    let wait: WaitForFn = Arc::new(move |req: Request| {
        let c = c2.clone();
        Box::pin(async move {
            c.fetch_add(1, Ordering::SeqCst);
            Ok(Answer {
                id: req.id,
                ok: true,
                ..Default::default()
            })
        })
    });
    let res = create_client(ClientOptions {
        hooks: Hooks {
            before_tool: Some(approval_hook()),
            ..Default::default()
        },
        wait_for: Some(wait),
        max_turns: 4,
        ..base(&srv.url)
    })
    .run("ship it", &tk)
    .await
    .unwrap();
    assert_eq!(res.status, "done");
    assert_eq!(res.tool_calls[0].output, "DEPLOYED");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}

#[test]
fn pending_wire_keys() {
    let mut data = serde_json::Map::new();
    data.insert("k".into(), json!("v"));
    let req = Request {
        id: "r1".into(),
        kind: "input".into(),
        prompt: "p".into(),
        url: "u".into(),
        data: Some(data.clone()),
        expires_at: "2026-01-01T00:00:00Z".into(),
    };
    assert_eq!(
        serde_json::to_string(&req).unwrap(),
        r#"{"id":"r1","kind":"input","prompt":"p","url":"u","data":{"k":"v"},"expiresAt":"2026-01-01T00:00:00Z"}"#
    );
    let ans = Answer {
        id: "r1".into(),
        ok: true,
        data: Some(data),
        ..Default::default()
    };
    assert_eq!(
        serde_json::to_string(&ans).unwrap(),
        r#"{"id":"r1","ok":true,"data":{"k":"v"}}"#
    );
    let r = auth_required("https://x", "");
    assert_eq!(r.output, "Authorization required to continue\nhttps://x");
    assert!(r.is_error);
}
