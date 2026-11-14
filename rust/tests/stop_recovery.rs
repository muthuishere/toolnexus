//! SPEC §8 "Stop-reason recovery": replays conformance/stop_recovery/cases.json in both
//! styles and both loops (run, stream) against a hermetic fake provider.
mod common;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use common::{Resp, serve};
use serde_json::{Value, json};
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{create_toolkit, define_tool};

fn fixture() -> Value {
    let p =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../conformance/stop_recovery/cases.json");
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}

fn calls(r: &Value) -> Vec<Value> {
    r.get("toolCalls")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
}

fn openai_full(r: &Value) -> Value {
    let fr = match s(r, "stop") {
        "truncated" => "length",
        "tool" => "tool_calls",
        _ => "stop",
    };
    let mut msg = json!({"role": "assistant", "content": s(r, "text")});
    if !s(r, "thinking").is_empty() {
        msg["reasoning_content"] = json!(s(r, "thinking"));
    }
    let tcs: Vec<Value> = calls(r)
        .iter()
        .enumerate()
        .map(|(i, c)| json!({"id": format!("c{i}"), "type": "function", "function": {"name": s(c, "name"), "arguments": s(c, "arguments")}}))
        .collect();
    if !tcs.is_empty() {
        msg["tool_calls"] = json!(tcs);
    }
    json!({"choices": [{"message": msg, "finish_reason": fr}], "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
}

fn openai_sse(r: &Value) -> String {
    let fr = match s(r, "stop") {
        "truncated" => "length",
        "tool" => "tool_calls",
        _ => "stop",
    };
    let mut chunks = Vec::new();
    if !s(r, "thinking").is_empty() {
        chunks.push(json!({"choices": [{"delta": {"reasoning_content": s(r, "thinking")}}]}));
    }
    if !s(r, "text").is_empty() {
        chunks.push(json!({"choices": [{"delta": {"content": s(r, "text")}}]}));
    }
    for (i, c) in calls(r).iter().enumerate() {
        chunks.push(json!({"choices": [{"delta": {"tool_calls": [{"index": i, "id": format!("c{i}"), "function": {"name": s(c, "name"), "arguments": s(c, "arguments")}}]}}]}));
    }
    chunks.push(json!({"choices": [{"delta": {}, "finish_reason": fr}]}));
    common::sse_lines(&chunks)
}

fn anth_stop(r: &Value) -> &'static str {
    match s(r, "stop") {
        "truncated" => "max_tokens",
        "tool" => "tool_use",
        _ => "end_turn",
    }
}

fn anth_input(raw: &str) -> Value {
    match serde_json::from_str::<Value>(raw) {
        Ok(v @ Value::Object(_)) => v,
        _ => json!(raw),
    }
}

fn anthropic_full(r: &Value) -> Value {
    let mut content = Vec::new();
    if !s(r, "thinking").is_empty() {
        content.push(json!({"type": "thinking", "thinking": s(r, "thinking"), "signature": "sig"}));
    }
    if !s(r, "text").is_empty() {
        content.push(json!({"type": "text", "text": s(r, "text")}));
    }
    for (i, c) in calls(r).iter().enumerate() {
        content.push(json!({"type": "tool_use", "id": format!("c{i}"), "name": s(c, "name"), "input": anth_input(s(c, "arguments"))}));
    }
    json!({"content": content, "stop_reason": anth_stop(r), "usage": {"input_tokens": 1, "output_tokens": 1}})
}

fn anthropic_sse(r: &Value) -> String {
    let mut ev = vec![json!({"type": "message_start", "message": {"usage": {"input_tokens": 1}}})];
    let mut idx = 0;
    if !s(r, "thinking").is_empty() {
        ev.push(json!({"type": "content_block_start", "index": idx, "content_block": {"type": "thinking", "thinking": ""}}));
        ev.push(json!({"type": "content_block_delta", "index": idx, "delta": {"type": "thinking_delta", "thinking": s(r, "thinking")}}));
        ev.push(json!({"type": "content_block_delta", "index": idx, "delta": {"type": "signature_delta", "signature": "sig"}}));
        ev.push(json!({"type": "content_block_stop", "index": idx}));
        idx += 1;
    }
    if !s(r, "text").is_empty() {
        ev.push(json!({"type": "content_block_start", "index": idx, "content_block": {"type": "text", "text": ""}}));
        ev.push(json!({"type": "content_block_delta", "index": idx, "delta": {"type": "text_delta", "text": s(r, "text")}}));
        ev.push(json!({"type": "content_block_stop", "index": idx}));
        idx += 1;
    }
    for (i, c) in calls(r).iter().enumerate() {
        ev.push(json!({"type": "content_block_start", "index": idx, "content_block": {"type": "tool_use", "id": format!("c{i}"), "name": s(c, "name"), "input": {}}}));
        ev.push(json!({"type": "content_block_delta", "index": idx, "delta": {"type": "input_json_delta", "partial_json": s(c, "arguments")}}));
        ev.push(json!({"type": "content_block_stop", "index": idx}));
        idx += 1;
    }
    ev.push(json!({"type": "message_delta", "delta": {"stop_reason": anth_stop(r)}, "usage": {"output_tokens": 1}}));
    ev.push(json!({"type": "message_stop"}));
    let mut out = String::new();
    for e in ev {
        out.push_str(&format!("event: {}\ndata: {e}\n\n", s(&e, "type")));
    }
    out
}

#[tokio::test]
async fn stop_recovery_conformance() {
    let fx = fixture();
    let texts = fx["messages"].clone();
    for case in fx["cases"].as_array().unwrap() {
        for style in [Style::OpenAI, Style::Anthropic] {
            for streaming in [false, true] {
                let label = format!("{} {style:?} stream={streaming}", s(case, "name"));
                let responses = case["responses"].as_array().unwrap().clone();
                let bodies: Arc<Mutex<Vec<Value>>> = Arc::default();
                let b2 = bodies.clone();
                let srv = serve(move |req| {
                    let bodies = b2.clone();
                    let responses = responses.clone();
                    async move {
                        let mut b = bodies.lock().unwrap();
                        let i = b.len();
                        b.push(req.body.clone());
                        let r = &responses[i.min(responses.len() - 1)];
                        match (style, streaming) {
                            (Style::OpenAI, false) => Resp::json(openai_full(r)),
                            (Style::OpenAI, true) => Resp::sse(openai_sse(r)),
                            (Style::Anthropic, false) => Resp::json(anthropic_full(r)),
                            (Style::Anthropic, true) => Resp::sse(anthropic_sse(r)),
                        }
                    }
                })
                .await;
                let runs: Arc<Mutex<Vec<Value>>> = Arc::default();
                let r2 = runs.clone();
                let echo = define_tool(
                    "echo",
                    "echo",
                    Some(json!({"type": "object", "properties": {}})),
                    move |a: Value, _| {
                        let runs = r2.clone();
                        async move {
                            runs.lock().unwrap().push(a);
                            Ok::<_, String>("ok".to_string())
                        }
                    },
                );
                let tk = create_toolkit(toolnexus::ToolkitOptions {
                    builtins: Some(toolnexus::BuiltinsConfig::off()),
                    extra_tools: vec![echo],
                    ..Default::default()
                })
                .await
                .unwrap();
                let c = create_client(ClientOptions {
                    base_url: srv.url.clone(),
                    style,
                    model: "m".into(),
                    api_key: "k".into(),
                    retry_base_ms: 1,
                    max_turns: case.get("maxTurns").and_then(Value::as_u64).unwrap_or(0) as usize,
                    stop_recovery: case["stopRecovery"].as_bool().unwrap(),
                    ..Default::default()
                });
                let res = if streaming {
                    let mut rx = c.stream("go", &tk).unwrap();
                    let mut res = None;
                    while let Some(ev) = rx.recv().await {
                        if ev.kind == "done" {
                            res = ev.result;
                        }
                        assert_ne!(ev.kind, "error", "{label}: {:?}", ev.error);
                    }
                    *res.expect("done")
                } else {
                    c.run("go", &tk).await.unwrap()
                };
                let exp = &case["expect"];
                assert_eq!(res.turns as u64, exp["turns"].as_u64().unwrap(), "{label}");
                assert_eq!(res.text, s(exp, "text"), "{label}");
                let bodies = bodies.lock().unwrap().clone();
                assert_eq!(
                    bodies.len() as u64,
                    exp["turns"].as_u64().unwrap(),
                    "{label}"
                );
                let want: Vec<Value> = exp["recoveries"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|k| json!({"role": "user", "content": texts[k.as_str().unwrap()]}))
                    .collect();
                let got: Vec<Value> = bodies
                    .iter()
                    .skip(1)
                    .filter_map(|b| b["messages"].as_array().and_then(|m| m.last()).cloned())
                    .filter(|m| {
                        m["content"]
                            .as_str()
                            .is_some_and(|t| texts.as_object().unwrap().values().any(|v| v == t))
                    })
                    .collect();
                assert_eq!(got, want, "{label}");
                assert_eq!(
                    *runs.lock().unwrap(),
                    exp["toolRuns"].as_array().unwrap().clone(),
                    "{label}"
                );
            }
        }
    }
}

/// Issue #235 / SPEC §8.x: an empty anthropic text block beside tool_use must never be
/// stored in the assistant message, or the next request is rejected with a 400.
#[tokio::test]
async fn anthropic_empty_text_block_is_not_stored() {
    for streaming in [false, true] {
        let bodies: Arc<Mutex<Vec<Value>>> = Arc::default();
        let b2 = bodies.clone();
        let srv = serve(move |req| {
            let bodies = b2.clone();
            async move {
                let i = {
                    let mut b = bodies.lock().unwrap();
                    b.push(req.body.clone());
                    b.len() - 1
                };
                if i > 0 {
                    let done = json!({"text": "done", "stop": "end"});
                    return if streaming {
                        Resp::sse(anthropic_sse(&done))
                    } else {
                        Resp::json(anthropic_full(&done))
                    };
                }
                if !streaming {
                    return Resp::json(json!({
                        "content": [
                            {"type": "text", "text": ""},
                            {"type": "tool_use", "id": "toolu_0", "name": "echo", "input": {}}
                        ],
                        "stop_reason": "tool_use",
                        "usage": {"input_tokens": 1, "output_tokens": 1}
                    }));
                }
                let ev = [
                    json!({"type": "message_start", "message": {"usage": {"input_tokens": 1}}}),
                    json!({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
                    json!({"type": "content_block_stop", "index": 0}),
                    json!({"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "toolu_0", "name": "echo", "input": {}}}),
                    json!({"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{}"}}),
                    json!({"type": "content_block_stop", "index": 1}),
                    json!({"type": "message_delta", "delta": {"stop_reason": "tool_use"}, "usage": {"output_tokens": 1}}),
                    json!({"type": "message_stop"}),
                ];
                let mut out = String::new();
                for e in ev {
                    out.push_str(&format!("event: {}\ndata: {e}\n\n", s(&e, "type")));
                }
                Resp::sse(out)
            }
        })
        .await;
        let echo = define_tool(
            "echo",
            "echo",
            Some(json!({"type": "object", "properties": {}})),
            |_: Value, _| async { Ok::<_, String>("ok".to_string()) },
        );
        let tk = create_toolkit(toolnexus::ToolkitOptions {
            builtins: Some(toolnexus::BuiltinsConfig::off()),
            extra_tools: vec![echo],
            ..Default::default()
        })
        .await
        .unwrap();
        let c = create_client(ClientOptions {
            base_url: srv.url.clone(),
            style: Style::Anthropic,
            model: "m".into(),
            api_key: "k".into(),
            retry_base_ms: 1,
            ..Default::default()
        });
        let res = if streaming {
            let mut rx = c.stream("go", &tk).unwrap();
            let mut res = None;
            while let Some(ev) = rx.recv().await {
                if ev.kind == "done" {
                    res = ev.result;
                }
                assert_ne!(ev.kind, "error", "stream: {:?}", ev.error);
            }
            *res.expect("done")
        } else {
            c.run("go", &tk).await.unwrap()
        };
        let bodies = bodies.lock().unwrap().clone();
        assert_eq!(res.text, "done", "stream={streaming}");
        assert_eq!(bodies.len(), 2, "stream={streaming}");
        let msgs = bodies[1]["messages"].as_array().unwrap();
        for m in msgs {
            for b in m["content"].as_array().into_iter().flatten() {
                assert!(
                    !(b["type"] == "text" && b["text"] == ""),
                    "stream={streaming}: empty text block sent: {msgs:?}"
                );
            }
        }
    }
}
