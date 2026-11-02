//! Unified client loop (SPEC §8): mirrors golang client_resilience_test,
//! client_metrics_test, beforellm_parity_test, prompt_cache_test,
//! tool_tiers_test. Hermetic: a loopback fake provider, no network, no key.

mod common;

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::*;
use serde_json::{Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::client::*;
use toolnexus::{Tool, ToolHints, ToolResult, ToolSource, create_toolkit, define_tool};

fn opts(url: &str) -> ClientOptions {
    ClientOptions {
        base_url: url.into(),
        model: "m".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        ..Default::default()
    }
}

fn add_tool() -> Tool {
    define_tool("add", "add", None, |a: Value, _| async move {
        let s = a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0);
        Ok::<_, String>(format!("{}", s as i64))
    })
}

async fn tk_with(tools: Vec<Tool>) -> toolnexus::Toolkit {
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap()
}

// -- resilience --------------------------------------------------------------

#[tokio::test]
async fn retries_on_503_then_succeeds() {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let n = n2.clone();
        async move {
            if n.fetch_add(1, Ordering::SeqCst) < 2 {
                Resp::status(503, "busy")
            } else {
                Resp::json(oa_text("ok"))
            }
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let res = create_client(opts(&srv.url)).run("hi", &tk).await.unwrap();
    assert_eq!(res.text, "ok");
    assert_eq!(n.load(Ordering::SeqCst), 3);
}

#[tokio::test]
async fn retry_exhausted_surfaces_provider_error() {
    let srv = serve(|_| async { Resp::status(503, "{\"error\":\"busy\"}") }).await;
    let tk = empty_toolkit().await;
    let err = create_client(opts(&srv.url))
        .run("hi", &tk)
        .await
        .unwrap_err();
    match err {
        ClientError::Provider(p) => {
            assert_eq!(p.status, 503);
            assert_eq!(
                err_text(&ClientError::Provider(p)),
                "LLM 503: {\"error\":\"busy\"}"
            );
        }
        e => panic!("want provider error, got {e:?}"),
    }
}

fn err_text(e: &ClientError) -> String {
    e.to_string()
}

async fn count_calls(retries: i32) -> usize {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let n = n2.clone();
        async move {
            n.fetch_add(1, Ordering::SeqCst);
            Resp::status(500, "{\"error\":\"boom\"}")
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let _ = create_client(ClientOptions {
        retries,
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await;
    n.load(Ordering::SeqCst)
}

#[tokio::test]
async fn retries_minus_one_means_one_attempt() {
    assert_eq!(count_calls(-1).await, 1);
}

#[tokio::test]
async fn retries_unset_defaults_to_three_attempts() {
    assert_eq!(count_calls(0).await, 3);
}

#[tokio::test]
async fn run_timeout_returns_incomplete_partial() {
    let srv = serve(|_| async {
        tokio::time::sleep(Duration::from_millis(500)).await;
        Resp::json(oa_text("late"))
    })
    .await;
    let tk = empty_toolkit().await;
    let t0 = Instant::now();
    let err = create_client(ClientOptions {
        timeout_ms: 50,
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap_err();
    assert!(t0.elapsed() < Duration::from_millis(400));
    match err {
        ClientError::RunTimeout {
            timeout_ms,
            partial,
        } => {
            assert_eq!(timeout_ms, 50);
            assert_eq!(partial.status, "incomplete");
            assert_eq!(partial.limit, "timeout");
        }
        e => panic!("want RunTimeout, got {e:?}"),
    }
}

#[tokio::test]
async fn cancel_is_not_retried() {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let n = n2.clone();
        async move {
            n.fetch_add(1, Ordering::SeqCst);
            tokio::time::sleep(Duration::from_millis(300)).await;
            Resp::json(oa_text("late"))
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let cancel = CancellationToken::new();
    let c2 = cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(30)).await;
        c2.cancel();
    });
    let err = create_client(opts(&srv.url))
        .run_ctx(cancel, "hi", &tk, vec![])
        .await
        .unwrap_err();
    assert!(matches!(err, ClientError::Cancelled), "{err:?}");
    tokio::time::sleep(Duration::from_millis(50)).await;
    assert_eq!(n.load(Ordering::SeqCst), 1);
}

#[test]
fn retry_after_parsing() {
    let cases: &[(&str, Option<u64>)] = &[
        ("2", Some(2)),
        ("0", Some(0)),
        ("  7  ", Some(7)),
        ("2147483647", Some(2147483647)),
        ("0.5", None),
        ("5.9", None),
        ("-5", None),
        ("+5", None),
        ("2147483648", None),
        ("99999999999999999999", None),
        ("", None),
        ("Wed, 21 Oct 2015 07:28:00 GMT", None),
        ("abc", None),
    ];
    for (input, want) in cases {
        assert_eq!(
            retry_after_seconds(input),
            want.map(Duration::from_secs),
            "{input:?}"
        );
    }
}

#[test]
fn rejected_retry_after_falls_back_to_backoff() {
    let c = create_client(ClientOptions {
        retry_base_ms: 40,
        ..Default::default()
    });
    assert!(c.backoff(0, "0.5") >= Duration::from_millis(40));
    assert_eq!(c.backoff(0, "3"), Duration::from_secs(3));
}

#[tokio::test]
async fn retry_after_header_is_honoured() {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let n = n2.clone();
        async move {
            if n.fetch_add(1, Ordering::SeqCst) == 0 {
                Resp::status(429, "slow down").header("Retry-After", "1")
            } else {
                Resp::json(oa_text("ok"))
            }
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let t0 = Instant::now();
    let res = create_client(opts(&srv.url)).run("hi", &tk).await.unwrap();
    assert_eq!(res.text, "ok");
    assert!(
        t0.elapsed() >= Duration::from_millis(950),
        "Retry-After: 1 must wait ~1s"
    );
}

async fn calls_for(status: u16, extra: Vec<u16>, on_error: Option<OnErrorFn>) -> usize {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let n = n2.clone();
        async move {
            n.fetch_add(1, Ordering::SeqCst);
            Resp::status(status, "x")
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let _ = create_client(ClientOptions {
        retries: 1,
        retryable_statuses: extra,
        on_error,
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await;
    n.load(Ordering::SeqCst)
}

#[tokio::test]
async fn retryable_statuses_add_to_the_default_set() {
    let cf = vec![520, 521, 522, 523, 524, 525, 526, 527];
    assert_eq!(
        calls_for(529, vec![], None).await,
        2,
        "529 retries by default"
    );
    assert_eq!(
        calls_for(520, vec![], None).await,
        1,
        "unlisted 5xx is terminal"
    );
    assert_eq!(
        calls_for(520, cf.clone(), None).await,
        2,
        "opted-in 520 retries"
    );
    assert_eq!(
        calls_for(429, cf, None).await,
        2,
        "a host list never removes 429"
    );
    assert_eq!(calls_for(400, vec![], None).await, 1, "400 is terminal");
}

#[tokio::test]
async fn on_error_overrides_classification() {
    let fail: OnErrorFn = Arc::new(|_| ErrorTier::Fail);
    assert_eq!(calls_for(429, vec![], Some(fail)).await, 1);
    let retry: OnErrorFn = Arc::new(|i: &ErrorInfo| {
        assert_eq!(i.status, 400);
        ErrorTier::Retry
    });
    assert_eq!(calls_for(400, vec![], Some(retry)).await, 2);
    let fail: OnErrorFn = Arc::new(|_| ErrorTier::Fail);
    assert_eq!(calls_for(520, vec![520], Some(fail)).await, 1);
}

#[tokio::test]
async fn provider_error_body_is_redacted() {
    let srv = serve(|_| async {
        Resp::status(
            400,
            "{\"error\":\"bad\",\"user_id\":\"u-123\",\"org_id\":42}",
        )
    })
    .await;
    let tk = empty_toolkit().await;
    let err = create_client(opts(&srv.url))
        .run("hi", &tk)
        .await
        .unwrap_err();
    let s = err.to_string();
    assert!(!s.contains("u-123") && !s.contains("42"), "{s}");
    assert!(s.contains(REDACTED_MARKER));
    assert_eq!(redact_provider_body(401, "secret stuff"), "");
}

// -- the loop ------------------------------------------------------------------

#[tokio::test]
async fn openai_parallel_tool_calls_with_call_ids() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            let ids: Vec<String> = r
                .messages()
                .iter()
                .filter(|m| m["role"] == "tool")
                .map(|m| {
                    format!(
                        "{}={}",
                        m["tool_call_id"].as_str().unwrap(),
                        m["content"].as_str().unwrap()
                    )
                })
                .collect();
            return Resp::json(oa_text(&ids.join(",")));
        }
        Resp::json(oa_calls(&[("c1", "who", "{}"), ("c2", "who", "{}")]))
    })
    .await;
    let who = define_tool(
        "who",
        "returns its call id",
        None,
        |_, ctx: toolnexus::ToolContext| async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            Ok::<_, String>(ctx.call_id.unwrap_or_default())
        },
    );
    let tk = tk_with(vec![who]).await;
    let t0 = Instant::now();
    let res = create_client(opts(&srv.url)).run("go", &tk).await.unwrap();
    assert_eq!(res.text, "c1=c1,c2=c2");
    assert_eq!(res.tool_call_count, 2);
    assert_eq!(res.turns, 2);
    assert!(
        t0.elapsed() < Duration::from_millis(55),
        "calls run concurrently"
    );
}

#[tokio::test]
async fn system_prompt_joins_skills_prompt() {
    let seen = Arc::new(Mutex::new(Value::Null));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        async move {
            *s.lock().unwrap() = r.messages()[0].clone();
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    let examples = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills");
    let tk = create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        skills_dir: vec![examples],
        ..Default::default()
    })
    .await
    .unwrap();
    create_client(ClientOptions {
        system_prompt: "Be brief.".into(),
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap();
    let sys = seen.lock().unwrap().clone();
    assert_eq!(sys["role"], "system");
    assert_eq!(
        sys["content"].as_str().unwrap(),
        format!("Be brief.\n\n{}", tk.skills_prompt())
    );
}

#[tokio::test]
async fn anthropic_loop_and_wire_shape() {
    let bodies = Arc::new(Mutex::new(Vec::<Req>::new()));
    let b2 = bodies.clone();
    let srv = serve(move |r: Req| {
        let b = b2.clone();
        async move {
            b.lock().unwrap().push(r.clone());
            let msgs = r.messages();
            let last = msgs.last().unwrap();
            if last["role"] == "user" && last["content"].is_array() {
                return Resp::json(json!({"content": [{"type": "text", "text": "5"}],
                    "usage": {"input_tokens": 3, "output_tokens": 1, "cache_read_input_tokens": 7, "cache_creation_input_tokens": 2}}));
            }
            Resp::json(json!({"content": [
                {"type": "text", "text": "let me add"},
                {"type": "tool_use", "id": "t1", "name": "add", "input": {"a": 2, "b": 3}}],
                "usage": {"input_tokens": 5, "output_tokens": 4}}))
        }
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let res = create_client(ClientOptions {
        style: Style::Anthropic,
        system_prompt: "sys".into(),
        ..opts(&srv.url)
    })
    .run("add", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "5");
    assert_eq!(res.usage.prompt_tokens, 8);
    assert_eq!(res.usage.cache_read_tokens, 7);
    assert_eq!(res.usage.cache_write_tokens, 2);
    let reqs = bodies.lock().unwrap();
    assert_eq!(reqs[0].path, "/v1/messages");
    assert_eq!(reqs[0].headers["x-api-key"], "k");
    assert_eq!(reqs[0].headers["anthropic-version"], "2023-06-01");
    assert_eq!(reqs[0].body["system"], "sys");
    assert_eq!(reqs[0].body["max_tokens"], 4096);
    assert_eq!(reqs[0].body["tools"][0]["input_schema"]["type"], "object");
    let tr = &reqs[1].messages()[2]["content"][0];
    assert_eq!(tr["type"], "tool_result");
    assert_eq!(tr["tool_use_id"], "t1");
    assert_eq!(tr["content"], "5");
    assert_eq!(tr["is_error"], false);
}

#[tokio::test]
async fn openai_usage_cached_tokens() {
    let srv = serve(|_| async {
        Resp::json(json!({"choices": [{"message": {"content": "ok"}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12, "prompt_tokens_details": {"cached_tokens": 6}}}))
    })
    .await;
    let tk = empty_toolkit().await;
    let res = create_client(opts(&srv.url)).run("hi", &tk).await.unwrap();
    assert_eq!(res.usage.prompt_tokens, 10);
    assert_eq!(res.usage.cache_read_tokens, 6);
    assert_eq!(res.usage.total_tokens, 12);
    let j = serde_json::to_value(res.usage).unwrap();
    assert_eq!(
        j,
        json!({"promptTokens": 10, "completionTokens": 2, "totalTokens": 12, "cacheReadTokens": 6})
    );
}

#[tokio::test]
async fn max_turns_exhausted_is_incomplete() {
    let srv =
        serve(|_| async { Resp::json(oa_calls(&[("c1", "add", "{\"a\":1,\"b\":1}")])) }).await;
    let tk = tk_with(vec![add_tool()]).await;
    let res = create_client(ClientOptions {
        max_turns: 3,
        ..opts(&srv.url)
    })
    .run("loop", &tk)
    .await
    .unwrap();
    assert_eq!(res.turns, 3);
    assert_eq!(res.status, "incomplete");
    assert_eq!(res.limit, "maxTurns");
    assert_eq!(res.tool_call_count, 3);
}

#[tokio::test]
async fn unknown_tool_is_an_error_result_not_a_failure() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            let m = r.messages();
            return Resp::json(oa_text(m.last().unwrap()["content"].as_str().unwrap()));
        }
        Resp::json(oa_calls(&[("c1", "nope", "not json")]))
    })
    .await;
    let tk = empty_toolkit().await;
    let res = create_client(opts(&srv.url)).run("x", &tk).await.unwrap();
    assert_eq!(res.text, "Unknown tool: nope");
    assert!(res.tool_calls[0].is_error);
    assert_eq!(res.tool_calls[0].args, json!({}));
}

#[tokio::test]
async fn run_with_history_and_conversation() {
    let srv =
        serve(|r: Req| async move { Resp::json(oa_text(&format!("n={}", r.messages().len()))) })
            .await;
    let tk = empty_toolkit().await;
    let c = create_client(ClientOptions {
        system_prompt: "S".into(),
        ..opts(&srv.url)
    });
    let r1 = c.run("one", &tk).await.unwrap();
    assert_eq!(r1.text, "n=2"); // system + user
    let r2 = c
        .run_with_history("two", &tk, r1.messages.clone())
        .await
        .unwrap();
    assert_eq!(r2.text, "n=4"); // history (system,user,assistant) + user
    let mut conv = c.conversation(&tk);
    conv.send("a").await.unwrap();
    let r = conv.send("b").await.unwrap();
    assert_eq!(r.text, "n=4");
    conv.reset();
    assert!(conv.messages.is_empty());
}

// -- hooks ---------------------------------------------------------------------

#[tokio::test]
async fn hooks_rewrite_args_and_results() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            return Resp::json(oa_text(
                r.messages().last().unwrap()["content"].as_str().unwrap(),
            ));
        }
        Resp::json(oa_calls(&[("c1", "add", "{\"a\":1,\"b\":1}")]))
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let before: BeforeToolHook = Arc::new(|ev: BeforeToolEvent| {
        Box::pin(async move {
            assert_eq!(ev.id, "c1");
            Ok(Some(ToolOverride {
                args: Some(json!({"a": 40, "b": 2})),
                result: None,
            }))
        })
    });
    let after: AfterToolHook = Arc::new(|ev: AfterToolEvent| {
        Box::pin(async move {
            Ok(Some(ToolOverride {
                args: None,
                result: Some(ToolResult::ok(format!("[{}]", ev.result.output))),
            }))
        })
    });
    let res = create_client(ClientOptions {
        hooks: Hooks {
            before_tool: Some(before),
            after_tool: Some(after),
            ..Default::default()
        },
        ..opts(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "[42]");
    assert_eq!(res.tool_calls[0].args, json!({"a": 40, "b": 2}));
}

#[tokio::test]
async fn before_tool_short_circuit_and_hints() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            return Resp::json(oa_text(
                r.messages().last().unwrap()["content"].as_str().unwrap(),
            ));
        }
        Resp::json(oa_calls(&[("c1", "rm", "{}")]))
    })
    .await;
    let mut rm = define_tool("rm", "remove", None, |_, _| async {
        Ok::<_, String>("REMOVED".into())
    });
    rm.hints = Some(ToolHints {
        destructive: true,
        ..Default::default()
    });
    let tk = tk_with(vec![rm]).await;
    let saw = Arc::new(Mutex::new(None));
    let s2 = saw.clone();
    let before: BeforeToolHook = Arc::new(move |ev: BeforeToolEvent| {
        let s = s2.clone();
        Box::pin(async move {
            *s.lock().unwrap() = ev.hints;
            Ok(Some(ToolOverride {
                args: None,
                result: Some(ToolResult::error("denied")),
            }))
        })
    });
    let res = create_client(ClientOptions {
        hooks: Hooks {
            before_tool: Some(before),
            ..Default::default()
        },
        ..opts(&srv.url)
    })
    .run("x", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "denied");
    assert!(saw.lock().unwrap().unwrap().destructive);
}

#[tokio::test]
async fn before_llm_error_stops_every_loop() {
    for (style, streaming) in [
        (Style::OpenAI, false),
        (Style::OpenAI, true),
        (Style::Anthropic, false),
        (Style::Anthropic, true),
    ] {
        let n = Arc::new(AtomicUsize::new(0));
        let n2 = n.clone();
        let srv = serve(move |_| {
            let n = n2.clone();
            async move {
                n.fetch_add(1, Ordering::SeqCst);
                Resp::json(oa_text("never"))
            }
        })
        .await;
        let tk = empty_toolkit().await;
        let h: BeforeLlmHook = Arc::new(|_| Box::pin(async { Err("budget exceeded".to_string()) }));
        let c = create_client(ClientOptions {
            style,
            hooks: Hooks {
                before_llm: Some(h),
                ..Default::default()
            },
            ..opts(&srv.url)
        });
        let err = if streaming {
            let mut rx = c.stream("x", &tk).unwrap();
            let mut err = None;
            while let Some(ev) = rx.recv().await {
                if ev.kind == "error" {
                    err = ev.error;
                }
            }
            err.expect("stream must end in error")
        } else {
            c.run("x", &tk).await.unwrap_err()
        };
        assert_eq!(err.to_string(), "budget exceeded");
        assert_eq!(n.load(Ordering::SeqCst), 0, "{style:?} stream={streaming}");
    }
}

#[tokio::test]
async fn before_llm_model_override_every_loop() {
    for (style, streaming) in [
        (Style::OpenAI, false),
        (Style::OpenAI, true),
        (Style::Anthropic, false),
        (Style::Anthropic, true),
    ] {
        let model = Arc::new(Mutex::new(String::new()));
        let m2 = model.clone();
        let srv = serve(move |r: Req| {
            let m = m2.clone();
            async move {
                *m.lock().unwrap() = r.body["model"].as_str().unwrap_or("").into();
                match (r.body["stream"].as_bool().unwrap_or(false), r.path.contains("messages")) {
                    (false, false) => Resp::json(oa_text("ok")),
                    (false, true) => Resp::json(json!({"content": [{"type": "text", "text": "ok"}]})),
                    (true, false) => Resp::sse(sse_lines(&[json!({"choices": [{"delta": {"content": "ok"}}]})])),
                    (true, true) => Resp::sse(sse_lines(&[
                        json!({"type": "content_block_start", "index": 0, "content_block": {"type": "text"}}),
                        json!({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "ok"}}),
                        json!({"type": "message_delta", "delta": {"stop_reason": "end_turn"}}),
                    ])),
                }
            }
        })
        .await;
        let tk = empty_toolkit().await;
        let h: BeforeLlmHook = Arc::new(|ev: BeforeLlmEvent| {
            Box::pin(async move {
                assert_eq!(ev.model, "m");
                Ok(Some(LlmOverride {
                    model: Some("routed".into()),
                    ..Default::default()
                }))
            })
        });
        let c = create_client(ClientOptions {
            style,
            hooks: Hooks {
                before_llm: Some(h),
                ..Default::default()
            },
            ..opts(&srv.url)
        });
        let res = if streaming {
            let mut rx = c.stream("x", &tk).unwrap();
            let mut res = None;
            while let Some(ev) = rx.recv().await {
                if ev.kind == "done" {
                    res = ev.result;
                }
            }
            *res.unwrap()
        } else {
            c.run("x", &tk).await.unwrap()
        };
        assert_eq!(*model.lock().unwrap(), "routed");
        assert_eq!(res.model, "routed", "reported = transmitted");
        assert_eq!(res.text, "ok", "{style:?} stream={streaming}");
    }
}

// -- streaming -----------------------------------------------------------------

#[tokio::test]
async fn stream_openai_text_tools_usage_done() {
    let srv = serve(|r: Req| async move {
        assert_eq!(r.body["stream"], true);
        assert_eq!(r.body["stream_options"]["include_usage"], true);
        if r.has_role("tool") {
            return Resp::sse(sse_lines(&[
                json!({"choices": [{"delta": {"content": "The sum "}}]}),
                json!({"choices": [{"delta": {"content": "is 5"}}]}),
                json!({"choices": [], "usage": {"prompt_tokens": 4, "completion_tokens": 3, "total_tokens": 7}}),
            ]));
        }
        Resp::sse(sse_lines(&[
            json!({"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c1", "function": {"name": "add", "arguments": "{\"a\":"}}]}}]}),
            json!({"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": "2,\"b\":3}"}}]}}]}),
            json!({"choices": [], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}),
        ]))
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let mut rx = create_client(opts(&srv.url)).stream("add", &tk).unwrap();
    let mut kinds = Vec::new();
    let mut text = String::new();
    let mut result = None;
    while let Some(ev) = rx.recv().await {
        kinds.push(ev.kind.clone());
        match ev.kind.as_str() {
            "text" => text.push_str(&ev.delta),
            "tool_call" => {
                assert_eq!(ev.name, "add");
                assert_eq!(ev.args, json!({"a": 2, "b": 3}));
            }
            "tool_result" => assert_eq!(ev.output, "5"),
            "done" => result = ev.result,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    assert_eq!(
        kinds,
        ["tool_call", "tool_result", "text", "text", "usage", "done"]
    );
    assert_eq!(text, "The sum is 5");
    let r = result.unwrap();
    assert_eq!(r.text, "The sum is 5");
    assert_eq!(r.usage.total_tokens, 9);
    // the assistant tool-call turn has null content when no text streamed
    assert_eq!(r.messages[1]["content"], Value::Null);
}

#[tokio::test]
async fn stream_anthropic_tool_use() {
    let srv = serve(|r: Req| async move {
        let msgs = r.messages();
        if msgs.len() > 1 {
            return Resp::sse(sse_lines(&[
                json!({"type": "message_start", "message": {"usage": {"input_tokens": 2, "output_tokens": 0}}}),
                json!({"type": "content_block_start", "index": 0, "content_block": {"type": "text"}}),
                json!({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "5"}}),
                json!({"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 1}}),
            ]));
        }
        Resp::sse(sse_lines(&[
            json!({"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": "t1", "name": "add"}}),
            json!({"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{\"a\":2,"}}),
            json!({"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "\"b\":3}"}}),
            json!({"type": "message_delta", "delta": {"stop_reason": "tool_use"}}),
        ]))
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let c = create_client(ClientOptions {
        style: Style::Anthropic,
        ..opts(&srv.url)
    });
    let mut text = String::new();
    let res = c
        .ask_stream("add", &tk, "", |d| text.push_str(d))
        .await
        .unwrap();
    assert_eq!(text, "5");
    assert_eq!(res.text, "5");
    assert_eq!(res.tool_calls[0].output, "5");
    assert_eq!(res.usage.prompt_tokens, 2);
    assert_eq!(res.usage.completion_tokens, 1);
}

// -- conversation store ----------------------------------------------------------

#[tokio::test]
async fn ask_remembers_by_id_and_stream_with_id() {
    let srv = serve(|r: Req| async move {
        let n = r.messages().len();
        if r.body["stream"] == true {
            return Resp::sse(sse_lines(&[
                json!({"choices": [{"delta": {"content": format!("n={n}")}}]}),
            ]));
        }
        Resp::json(oa_text(&format!("n={n}")))
    })
    .await;
    let tk = empty_toolkit().await;
    let c = create_client(opts(&srv.url));
    assert_eq!(c.ask("one", &tk, "conv").await.unwrap().text, "n=1");
    assert_eq!(c.ask("two", &tk, "conv").await.unwrap().text, "n=3");
    assert_eq!(c.ask("other", &tk, "x").await.unwrap().text, "n=1");
    let mut got = String::new();
    let r = c
        .ask_stream("three", &tk, "conv", |d| got.push_str(d))
        .await
        .unwrap();
    assert_eq!(r.text, "n=5");
    assert_eq!(got, "n=5");
    let saved = c.conversation_store().get("conv").unwrap().unwrap();
    assert_eq!(saved.len(), 6);
    let ids: Vec<String> = c
        .conversation_store()
        .list()
        .unwrap()
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(ids.len(), 2);
}

#[tokio::test]
async fn custom_and_file_conversation_store() {
    let dir = std::env::temp_dir().join(format!("tn-store-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let store = Arc::new(toolnexus::store::FileConversationStore::new(&dir).unwrap());
    let srv =
        serve(|r: Req| async move { Resp::json(oa_text(&format!("n={}", r.messages().len()))) })
            .await;
    let tk = empty_toolkit().await;
    let c = create_client(ClientOptions {
        store: Some(store.clone()),
        ..opts(&srv.url)
    });
    c.ask("a", &tk, "s1").await.unwrap();
    // a fresh client over the same directory continues the conversation
    let c2 = create_client(ClientOptions {
        store: Some(Arc::new(
            toolnexus::store::FileConversationStore::new(&dir).unwrap(),
        )),
        ..opts(&srv.url)
    });
    assert_eq!(c2.ask("b", &tk, "s1").await.unwrap().text, "n=3");
    use toolnexus::store::ConversationStore;
    let raw: Value = serde_json::from_slice(&std::fs::read(dir.join("s1.json")).unwrap()).unwrap();
    assert_eq!(raw["version"], 1);
    assert!(store.get("../escape").is_err(), "ids are validated");
    assert_eq!(store.get("missing").unwrap(), None);
    store.delete("s1").unwrap();
    assert_eq!(store.get("s1").unwrap(), None);
    let _ = std::fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn interrupted_turn_is_saved_sealed() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            return Resp::status(400, "nope");
        }
        Resp::json(oa_calls(&[("c1", "add", "{}")]))
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let c = create_client(opts(&srv.url));
    assert!(c.ask("x", &tk, "id1").await.is_err());
    let saved = c.conversation_store().get("id1").unwrap().unwrap();
    // user, assistant(tool_calls), tool result — a valid transcript to continue
    assert_eq!(saved.len(), 3);
    assert_eq!(saved[2]["tool_call_id"], "c1");
}

// -- metrics ----------------------------------------------------------------------

#[tokio::test]
async fn on_metric_fires() {
    let srv = serve(|r: Req| async move {
        if !r.has_role("tool") {
            return Resp::json(json!({"choices": [{"message": {"content": null, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "add", "arguments": "{\"a\":2,\"b\":3}"}}]}}],
                "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9}}));
        }
        Resp::json(json!({"choices": [{"message": {"content": "5"}}],
            "usage": {"prompt_tokens": 6, "completion_tokens": 1, "total_tokens": 7}}))
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let events = Arc::new(Mutex::new(Vec::<MetricEvent>::new()));
    let e2 = events.clone();
    let c = create_client(ClientOptions {
        model: "gpt-x".into(),
        on_metric: Some(Arc::new(move |e: &MetricEvent| {
            e2.lock().unwrap().push(e.clone())
        })),
        ..opts(&srv.url)
    });
    c.run("add them", &tk).await.unwrap();
    let ev = events.lock().unwrap();
    let llm: Vec<_> = ev.iter().filter(|e| e.event == "llm").collect();
    assert_eq!(llm.len(), 2);
    assert!(llm.iter().all(|e| e.model == "gpt-x" && e.status == "ok"));
    let tool = ev.iter().find(|e| e.event == "tool").unwrap();
    assert_eq!(
        (tool.tool.as_str(), tool.source.as_str(), tool.is_error),
        ("add", "native", false)
    );
    let run = ev.last().unwrap();
    assert_eq!(run.event, "run");
    assert_eq!((run.turns, run.tool_calls, run.total_tokens), (2, 1, 16));
    assert!(run.error.is_empty());
}

const JS_METRICS_REFERENCE: &str = "# HELP toolnexus_llm_requests_total Total LLM requests.
# TYPE toolnexus_llm_requests_total counter
toolnexus_llm_requests_total{model=\"gpt-x\",status=\"ok\"} 1
# HELP toolnexus_llm_tokens_total Total tokens, by type.
# TYPE toolnexus_llm_tokens_total counter
toolnexus_llm_tokens_total{type=\"completion\"} 2
toolnexus_llm_tokens_total{type=\"prompt\"} 3
# HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.
# TYPE toolnexus_llm_request_duration_seconds histogram
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.05\"} 0
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.1\"} 0
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.25\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"0.5\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"1\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"2.5\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"5\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"10\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"30\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"60\"} 1
toolnexus_llm_request_duration_seconds_bucket{model=\"gpt-x\",le=\"+Inf\"} 1
toolnexus_llm_request_duration_seconds_sum{model=\"gpt-x\"} 0.12
toolnexus_llm_request_duration_seconds_count{model=\"gpt-x\"} 1
# HELP toolnexus_tool_calls_total Total tool calls.
# TYPE toolnexus_tool_calls_total counter
toolnexus_tool_calls_total{tool=\"add\",source=\"native\",is_error=\"false\",pending=\"false\"} 1
# HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.
# TYPE toolnexus_tool_duration_seconds histogram
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"0.05\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"0.1\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"0.25\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"0.5\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"1\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"2.5\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"5\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"10\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"30\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"60\"} 1
toolnexus_tool_duration_seconds_bucket{tool=\"add\",le=\"+Inf\"} 1
toolnexus_tool_duration_seconds_sum{tool=\"add\"} 0.04
toolnexus_tool_duration_seconds_count{tool=\"add\"} 1
# HELP toolnexus_run_errors_total Total run errors.
# TYPE toolnexus_run_errors_total counter
";

#[test]
fn metrics_byte_parity() {
    let c = create_client(opts("http://x"));
    assert!(
        c.metrics()
            .lines()
            .all(|l| l.is_empty() || l.starts_with('#'))
    );
    c.record_metric(MetricEvent {
        event: "llm".into(),
        model: "gpt-x".into(),
        status: "ok".into(),
        ms: 120,
        prompt_tokens: 3,
        completion_tokens: 2,
        ..Default::default()
    });
    c.record_metric(MetricEvent {
        event: "tool".into(),
        tool: "add".into(),
        source: "native".into(),
        ms: 40,
        ..Default::default()
    });
    c.record_metric(MetricEvent {
        event: "run".into(),
        model: "gpt-x".into(),
        turns: 2,
        tool_calls: 1,
        total_tokens: 16,
        ms: 200,
        ..Default::default()
    });
    assert_eq!(c.metrics(), JS_METRICS_REFERENCE);
}

// -- request shaping + prompt cache ---------------------------------------------------

#[tokio::test]
async fn request_params_and_body_transform() {
    let seen = Arc::new(Mutex::new(Value::Null));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        async move {
            *s.lock().unwrap() = r.body.clone();
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    let tk = empty_toolkit().await;
    let mut params = serde_json::Map::new();
    params.insert("temperature".into(), json!(0.2));
    params.insert("messages".into(), json!("ignored"));
    let bt: BodyTransformFn = Arc::new(|mut b: Value| {
        b["x_extra"] = json!(true);
        Some(b)
    });
    create_client(ClientOptions {
        request_params: params,
        body_transform: Some(bt),
        headers: HashMap::from([("X-Trace".to_string(), "t1".to_string())]),
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap();
    let b = seen.lock().unwrap().clone();
    assert_eq!(b["temperature"], 0.2);
    assert!(b["messages"].is_array());
    assert_eq!(b["x_extra"], true);
    assert!(b.get("tools").is_none(), "empty toolkit ⇒ no tools key");
}

#[tokio::test]
async fn prompt_cache_marks_anthropic_prefix_without_mutating_transcript() {
    let seen = Arc::new(Mutex::new(Value::Null));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        async move {
            *s.lock().unwrap() = r.body.clone();
            Resp::json(json!({"content": [{"type": "text", "text": "ok"}],
                "usage": {"input_tokens": 1, "output_tokens": 1, "cache_read_input_tokens": 9}}))
        }
    })
    .await;
    let tk = tk_with(vec![add_tool()]).await;
    let res = create_client(ClientOptions {
        style: Style::Anthropic,
        system_prompt: "SYS".into(),
        prompt_cache: Some(PromptCacheOptions {
            key: String::new(),
            long_ttl: true,
        }),
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap();
    let b = seen.lock().unwrap().clone();
    let mark = json!({"type": "ephemeral", "ttl": "1h"});
    assert_eq!(b["tools"][0]["cache_control"], mark);
    assert_eq!(
        b["system"],
        json!([{"type": "text", "text": "SYS", "cache_control": mark}])
    );
    assert_eq!(
        b["messages"][0]["content"],
        json!([{"type": "text", "text": "hi", "cache_control": mark}])
    );
    assert_eq!(
        res.messages[0]["content"], "hi",
        "transcript is not mutated"
    );
    assert_eq!(res.usage.cache_read_tokens, 9);
}

#[tokio::test]
async fn prompt_cache_openai_key_and_off_is_byte_identical() {
    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let s = s2.clone();
        async move {
            s.lock().unwrap().push(r.raw.clone());
            Resp::json(oa_text("ok"))
        }
    })
    .await;
    let tk = empty_toolkit().await;
    create_client(opts(&srv.url)).run("hi", &tk).await.unwrap();
    create_client(ClientOptions {
        prompt_cache: Some(PromptCacheOptions::default()),
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap();
    create_client(ClientOptions {
        prompt_cache: Some(PromptCacheOptions {
            key: "sess-1".into(),
            long_ttl: false,
        }),
        ..opts(&srv.url)
    })
    .run("hi", &tk)
    .await
    .unwrap();
    let s = seen.lock().unwrap();
    assert_eq!(s[0], s[1], "openai without a key: byte-identical");
    let b: Value = serde_json::from_str(&s[2]).unwrap();
    assert_eq!(b["prompt_cache_key"], "sess-1");
}

// -- tool tiers ----------------------------------------------------------------------

fn named(name: &str, desc: &str) -> Tool {
    let n = name.to_owned();
    define_tool(name, desc, None, move |_, _| {
        let n = n.clone();
        async move { Ok::<_, String>(format!("{n} ok")) }
    })
}

async fn tier_toolkit() -> toolnexus::Toolkit {
    tk_with(vec![
        named("alpha_read", "Read alpha."),
        named("zeta_read", "Read zeta."),
        named("lint", "Lint the code.\nSecond line."),
        named("deploy", "Deploy the service."),
    ])
    .await
}

#[tokio::test]
async fn tool_tier_plan_and_description() {
    let tk = tier_toolkit().await;
    let c = create_client(ClientOptions {
        tool_tiers: Some(ToolTierOptions {
            core: vec!["zeta_read".into(), "alpha_read".into()],
            deferred: vec!["deploy".into()],
            ..Default::default()
        }),
        ..opts("http://x")
    });
    let got = c.request_tools(&tk);
    let names: Vec<&str> = got.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(
        names,
        ["alpha_read", "zeta_read", "tool_search", "call_tool"]
    );
    let desc = got[2].description.clone();
    assert!(desc.contains("- lint — Lint the code."), "{desc}");
    assert!(desc.contains("(name only): deploy"));
    assert!(!desc.contains("Deploy the service"));
    let plan = c.tool_tier_plan(&tk).unwrap();
    assert_eq!(plan["deploy"], ToolTier::Deferred);
    assert_eq!(plan["lint"], ToolTier::Listed);
    assert_eq!(plan["alpha_read"], ToolTier::Core);
    tk.register([named("late", "A late tool.")]);
    let again = c.request_tools(&tk);
    assert_eq!(again.len(), 4);
    assert_eq!(again[2].description, desc, "frozen after a late register");
    let r = c.tool_search(&tk, &json!({"query": "late"}));
    assert!(r.output.contains("\"name\": \"late\""), "{}", r.output);
    c.reset_tool_tiers(&tk);
    assert!(c.tool_tier_plan(&tk).unwrap().contains_key("late"));
}

#[tokio::test]
async fn tool_tier_budget_demotes_least_used() {
    let tk = tier_toolkit().await;
    let uses: Arc<HashMap<String, usize>> = Arc::new(HashMap::from([
        ("alpha_read".into(), 9),
        ("zeta_read".into(), 1),
        ("lint".into(), 5),
        ("deploy".into(), 0),
    ]));
    let u2 = uses.clone();
    let c = create_client(ClientOptions {
        tool_tiers: Some(ToolTierOptions {
            core: vec!["alpha_read".into()],
            budget_tokens: 1,
            usage_count: Some(Arc::new(move |n: &str| u2.get(n).copied().unwrap_or(0))),
            ..Default::default()
        }),
        ..opts("http://x")
    });
    let plan = c.tool_tier_plan(&tk).unwrap();
    assert!(plan.values().all(|t| *t == ToolTier::Deferred), "{plan:?}");
}

#[tokio::test]
async fn tool_tier_loop_call_tool_unwraps() {
    let tool_arrays = Arc::new(Mutex::new(Vec::<String>::new()));
    let t2 = tool_arrays.clone();
    let srv = serve(move |r: Req| {
        let t = t2.clone();
        async move {
            t.lock().unwrap().push(r.body["tools"].to_string());
            let tools_msgs: Vec<Value> = r
                .messages()
                .into_iter()
                .filter(|m| m["role"] == "tool")
                .collect();
            match tools_msgs.len() {
                0 => Resp::json(oa_calls(&[(
                    "s1",
                    "tool_search",
                    "{\"query\":\"select:deploy\"}",
                )])),
                1 => Resp::json(oa_calls(&[(
                    "c1",
                    "call_tool",
                    "{\"name\":\"deploy\",\"arguments\":{\"env\":\"prod\"}}",
                )])),
                _ => Resp::json(oa_text(
                    tools_msgs.last().unwrap()["content"].as_str().unwrap(),
                )),
            }
        }
    })
    .await;
    let mut deploy = define_tool(
        "deploy",
        "Deploy the service.",
        None,
        |a: Value, _| async move {
            Ok::<_, String>(format!("deployed {}", a["env"].as_str().unwrap_or("")))
        },
    );
    deploy.hints = Some(ToolHints {
        destructive: true,
        ..Default::default()
    });
    let tk = tk_with(vec![named("lint", "Lint."), deploy]).await;
    let seen = Arc::new(Mutex::new(Vec::<(String, bool)>::new()));
    let s2 = seen.clone();
    let before: BeforeToolHook = Arc::new(move |ev: BeforeToolEvent| {
        let s = s2.clone();
        Box::pin(async move {
            s.lock()
                .unwrap()
                .push((ev.name, ev.hints.map(|h| h.destructive).unwrap_or(false)));
            Ok(None)
        })
    });
    let res = create_client(ClientOptions {
        tool_tiers: Some(ToolTierOptions {
            core: vec!["lint".into()],
            ..Default::default()
        }),
        hooks: Hooks {
            before_tool: Some(before),
            ..Default::default()
        },
        ..opts(&srv.url)
    })
    .run("deploy", &tk)
    .await
    .unwrap();
    assert_eq!(res.text, "deployed prod");
    assert_eq!(*seen.lock().unwrap(), [("deploy".to_string(), true)]);
    let arrays = tool_arrays.lock().unwrap();
    assert!(
        arrays.windows(2).all(|w| w[0] == w[1]),
        "tools array is stable"
    );
    assert_eq!(
        res.tool_calls[1].args,
        json!({"name": "deploy", "arguments": {"env": "prod"}})
    );
}

#[tokio::test]
async fn tool_tier_stream_names_real_tool() {
    let srv = serve(|r: Req| async move {
        if r.has_role("tool") {
            return Resp::sse(sse_lines(&[json!({"choices": [{"delta": {"content": "ok"}}]})]));
        }
        Resp::sse(sse_lines(&[json!({"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "c1",
            "function": {"name": "call_tool", "arguments": "{\"name\":\"lint\",\"arguments\":{}}"}}]}}]})]))
    })
    .await;
    let tk = tk_with(vec![named("lint", "Lint.")]).await;
    let mut rx = create_client(ClientOptions {
        tool_tiers: Some(ToolTierOptions::default()),
        ..opts(&srv.url)
    })
    .stream("x", &tk)
    .unwrap();
    let mut evs = Vec::new();
    while let Some(ev) = rx.recv().await {
        if ev.kind == "tool_call" || ev.kind == "tool_result" {
            evs.push((ev.kind.clone(), ev.name.clone(), ev.via.clone()));
        }
    }
    assert_eq!(
        evs,
        [
            (
                "tool_call".to_string(),
                "lint".to_string(),
                "call_tool".to_string()
            ),
            (
                "tool_result".to_string(),
                "lint".to_string(),
                "call_tool".to_string()
            )
        ]
    );
}

#[tokio::test]
async fn tool_search_json_is_html_escaped() {
    let amp = define_tool(
        "amp",
        "Use <a & b> here",
        Some(json!({"type": "object", "properties": {"x": {"type": "string"}}})),
        |_, _| async { Ok::<_, String>(String::new()) },
    );
    let tk = tk_with(vec![amp]).await;
    let c = create_client(ClientOptions {
        tool_tiers: Some(ToolTierOptions::default()),
        ..opts("http://x")
    });
    let r = c.tool_search(&tk, &json!({"query": "select:amp"}));
    let want = "Loaded tools (call by name, or with call_tool):\n[\n  {\n    \"description\": \"Use \\u003ca \\u0026 b\\u003e here\",\n    \"inputSchema\": {\n      \"properties\": {\n        \"x\": {\n          \"type\": \"string\"\n        }\n      },\n      \"type\": \"object\"\n    },\n    \"name\": \"amp\"\n  }\n]";
    assert_eq!(r.output, want);
}

#[test]
fn tool_source_wire_name() {
    let t = Tool {
        source: ToolSource::Http,
        ..named("h", "h")
    };
    assert_eq!(serde_json::to_value(t.source).unwrap(), "http");
}
