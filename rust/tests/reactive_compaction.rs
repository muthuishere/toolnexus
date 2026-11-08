//! Reactive compaction on context overflow (SPEC §7F.1, issue #194): replays
//! `conformance/reactive_compaction/cases.json` — detection, and every loop
//! case through `run` AND `stream`, in both styles, against a fake provider.

mod common;

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use common::*;
use serde_json::{Value, json};
use toolnexus::client::*;
use toolnexus::store::{ConversationStore, InMemoryConversationStore};
use toolnexus::{create_toolkit, define_tool, is_context_overflow};

fn fixture() -> Value {
    let p = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../conformance/reactive_compaction/cases.json"
    );
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

#[test]
fn detection_matches_fixture() {
    let f = fixture();
    for c in f["detection"].as_array().unwrap() {
        let got = is_context_overflow(
            c["status"].as_u64().unwrap() as u16,
            c["body"].as_str().unwrap(),
        );
        assert_eq!(got, c["overflow"].as_bool().unwrap(), "{}", c["name"]);
    }
}

#[test]
fn message_is_pinned() {
    let e = ClientError::ContextOverflow(toolnexus::ContextOverflowError {
        compactions: 3,
        status: 400,
        body: String::new(),
    });
    assert_eq!(
        e.to_string(),
        "context overflow: 3 reactive compaction(s) did not make the prompt fit"
    );
}

fn reply(style: Style, stream: bool, step: &str, n: usize) -> Resp {
    let id = format!("c{n}");
    match (style, stream, step) {
        (Style::OpenAI, false, "text") => Resp::json(oa_text("done")),
        (Style::OpenAI, false, _) => Resp::json(oa_calls(&[(&id, "t", "{}")])),
        (Style::OpenAI, true, "text") => Resp::sse(sse_lines(&[
            json!({"choices": [{"delta": {"content": "done"}}]}),
        ])),
        (Style::OpenAI, true, _) => Resp::sse(sse_lines(&[json!({"choices": [{"delta": {
            "tool_calls": [{"index": 0, "id": id, "type": "function",
                "function": {"name": "t", "arguments": "{}"}}]}}]})])),
        (Style::Anthropic, false, "text") => {
            Resp::json(json!({"content": [{"type": "text", "text": "done"}]}))
        }
        (Style::Anthropic, false, _) => Resp::json(json!({"content": [
            {"type": "tool_use", "id": id, "name": "t", "input": {}}]})),
        (Style::Anthropic, true, "text") => Resp::sse(sse_lines(&[
            json!({"type": "content_block_start", "index": 0, "content_block": {"type": "text"}}),
            json!({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "done"}}),
            json!({"type": "message_delta", "delta": {"stop_reason": "end_turn"}}),
        ])),
        (Style::Anthropic, true, _) => Resp::sse(sse_lines(&[
            json!({"type": "content_block_start", "index": 0, "content_block": {"type": "tool_use", "id": id, "name": "t"}}),
            json!({"type": "content_block_delta", "index": 0, "delta": {"type": "input_json_delta", "partial_json": "{}"}}),
            json!({"type": "message_delta", "delta": {"stop_reason": "tool_use"}}),
        ])),
    }
}

async fn replay(case: &Value, f: &Value, style: Style, stream: bool) {
    let name = format!(
        "{} [{style:?} stream={stream}]",
        case["name"].as_str().unwrap()
    );
    let script: Vec<String> = case["script"]
        .as_array()
        .unwrap()
        .iter()
        .map(|s| s.as_str().unwrap().to_owned())
        .collect();
    let ov = match style {
        Style::OpenAI => f["overflowBodies"]["openai"].clone(),
        Style::Anthropic => f["overflowBodies"]["anthropic"].clone(),
    };
    let bad = f["bad400"].clone();
    let calls = Arc::new(AtomicUsize::new(0));
    let c2 = calls.clone();
    let srv = serve(move |_r: Req| {
        let n = c2.fetch_add(1, Ordering::SeqCst);
        let step = script.get(n).cloned().unwrap_or_else(|| "text".into());
        let (ov, bad) = (ov.clone(), bad.clone());
        async move {
            match step.as_str() {
                "overflow" => Resp::status(
                    ov["status"].as_u64().unwrap() as u16,
                    ov["body"].as_str().unwrap(),
                ),
                "bad400" => Resp::status(
                    bad["status"].as_u64().unwrap() as u16,
                    bad["body"].as_str().unwrap(),
                ),
                s => reply(style, stream, s, n),
            }
        }
    })
    .await;
    let tk = create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![define_tool("t", "t", None, |_: Value, _| async move {
            Ok::<_, String>("ok".to_string())
        })],
        ..Default::default()
    })
    .await
    .unwrap();
    let hook_calls = Arc::new(Mutex::new(0usize));
    let noop = case["hookNoop"].as_bool().unwrap_or(false);
    let compact_on_overflow: Option<BeforeLlmHook> = if case["option"].as_bool().unwrap() {
        let h = hook_calls.clone();
        Some(Arc::new(move |ev: BeforeLlmEvent| {
            *h.lock().unwrap() += 1;
            Box::pin(async move {
                if noop {
                    return Ok(None);
                }
                Ok(Some(LlmOverride {
                    messages: Some(ev.messages[1..].to_vec()),
                    ..Default::default()
                }))
            }) as BoxFuture<_>
        }))
    } else {
        None
    };
    let history: Vec<Value> = (0..8)
        .map(|i| json!({"role": if i % 2 == 0 {"user"} else {"assistant"}, "content": format!("h{i}")}))
        .collect();
    let store = Arc::new(InMemoryConversationStore::new());
    store.save("conv", &history).unwrap();
    let client = create_client(ClientOptions {
        base_url: srv.url.clone(),
        model: "m".into(),
        api_key: "k".into(),
        retry_base_ms: 1,
        style,
        max_turns: 20,
        store: Some(store),
        compact_on_overflow,
        ..Default::default()
    });
    let out: Result<RunResult, ClientError> = if stream {
        let mut rx = client.stream_with_id("go", &tk, "conv").unwrap();
        let mut out = None;
        while let Some(ev) = rx.recv().await {
            if let Some(e) = ev.error {
                out = Some(Err(e));
            } else if ev.kind == "done"
                && let Some(r) = ev.result
            {
                out = Some(Ok(*r));
            }
        }
        out.expect("stream ended without result")
    } else {
        client.run_with_history("go", &tk, history).await
    };
    let exp = &case["expect"];
    if let Some(text) = exp["text"].as_str() {
        let r = out.unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(r.text, text, "{name}");
        assert_eq!(
            *hook_calls.lock().unwrap() as u64,
            exp["compactions"].as_u64().unwrap(),
            "{name}"
        );
    } else {
        let e = out
            .err()
            .unwrap_or_else(|| panic!("{name}: expected error"));
        match exp["error"].as_str().unwrap() {
            "LlmHttpError" => match e {
                ClientError::Provider(p) => {
                    assert_eq!(p.status as u64, exp["status"].as_u64().unwrap(), "{name}")
                }
                e => panic!("{name}: got {e:?}"),
            },
            _ => match &e {
                ClientError::ContextOverflow(c) => {
                    assert_eq!(
                        c.compactions as u64,
                        exp["compactions"].as_u64().unwrap(),
                        "{name}"
                    );
                    assert_eq!(e.to_string(), exp["message"].as_str().unwrap(), "{name}");
                    assert_eq!(c.status, 400, "{name}");
                }
                e => panic!("{name}: got {e:?}"),
            },
        }
    }
    assert_eq!(
        calls.load(Ordering::SeqCst) as u64,
        case["calls"].as_u64().unwrap(),
        "{name}: calls"
    );
}

#[tokio::test]
async fn loops_replay_fixture() {
    let f = fixture();
    for case in f["loops"].as_array().unwrap() {
        for style in [Style::OpenAI, Style::Anthropic] {
            for stream in [false, true] {
                replay(case, &f, style, stream).await;
            }
        }
    }
}

#[tokio::test]
async fn retry_does_not_consume_a_turn_or_rerun_before_llm() {
    let calls = Arc::new(AtomicUsize::new(0));
    let c2 = calls.clone();
    let srv = serve(move |_r: Req| {
        let n = c2.fetch_add(1, Ordering::SeqCst);
        async move {
            if n == 0 {
                Resp::status(400, "context_length_exceeded")
            } else {
                Resp::json(oa_text("done"))
            }
        }
    })
    .await;
    let before = Arc::new(AtomicUsize::new(0));
    let b2 = before.clone();
    let hooks = Hooks {
        before_llm: Some(Arc::new(move |_| {
            b2.fetch_add(1, Ordering::SeqCst);
            Box::pin(async { Ok(None) })
        })),
        ..Default::default()
    };
    let client = create_client(ClientOptions {
        base_url: srv.url.clone(),
        model: "m".into(),
        api_key: "k".into(),
        hooks,
        max_turns: 1,
        compact_on_overflow: Some(Arc::new(|ev: BeforeLlmEvent| {
            Box::pin(async move {
                Ok(Some(LlmOverride {
                    messages: Some(ev.messages[1..].to_vec()),
                    ..Default::default()
                }))
            })
        })),
        ..Default::default()
    });
    let hist = vec![json!({"role": "user", "content": "h0"})];
    let r = client
        .run_with_history("go", &empty_toolkit().await, hist)
        .await
        .unwrap();
    assert_eq!(r.text, "done");
    assert_eq!(r.turns, 1);
    assert_eq!(before.load(Ordering::SeqCst), 1);
    // the compacted transcript flows into RunResult.messages
    assert_eq!(r.messages[0]["content"], "go");
}
