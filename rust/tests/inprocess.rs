//! In-process models (SPEC §8): the same loop, a host function as the provider.
//! Mirrors golang inprocess_test.go.

use std::sync::{Arc, Mutex};

use serde_json::{Value, json};
use toolnexus::client::*;
use toolnexus::{create_toolkit, define_tool};

async fn tk() -> toolnexus::Toolkit {
    let add = define_tool("add", "add", None, |a: Value, _| async move {
        Ok::<_, String>(format!(
            "{}",
            a["a"].as_i64().unwrap_or(0) + a["b"].as_i64().unwrap_or(0)
        ))
    });
    create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        extra_tools: vec![add],
        ..Default::default()
    })
    .await
    .unwrap()
}

fn answer_for(req: &InProcessRequest) -> InProcessResponse {
    let tool_result = req
        .messages
        .iter()
        .rev()
        .find(|m| m["role"] == "tool")
        .and_then(|m| m["content"].as_str().map(String::from));
    match tool_result {
        Some(r) => InProcessResponse {
            content: format!("sum={r}"),
            usage: Some(InProcessUsage {
                prompt_tokens: 3,
                completion_tokens: 2,
                total_tokens: 0,
            }),
            ..Default::default()
        },
        None => InProcessResponse {
            tool_calls: vec![InProcessToolCall {
                id: String::new(),
                name: "add".into(),
                arguments: json!({"a": 2, "b": 3}),
            }],
            ..Default::default()
        },
    }
}

#[tokio::test]
async fn generate_drives_the_loop() {
    let seen = Arc::new(Mutex::new(Vec::<String>::new()));
    let s2 = seen.clone();
    let generate: GenerateFn = Arc::new(move |req: InProcessRequest| {
        s2.lock().unwrap().push(req.model.clone());
        assert!(!req.tools.is_empty());
        let a = answer_for(&req);
        Box::pin(async move { Ok(a) })
    });
    let c = create_in_process_client(InProcessOptions {
        model: "local".into(),
        generate: Some(generate),
        system_prompt: "S".into(),
        ..Default::default()
    });
    let res = c.run("add", &tk().await).await.unwrap();
    assert_eq!(res.text, "sum=5");
    assert_eq!(res.tool_calls[0].output, "5");
    assert_eq!(res.usage.total_tokens, 5);
    assert_eq!(*seen.lock().unwrap(), ["local", "local"]);
    // ids default to call_<index>
    assert_eq!(res.messages[2]["tool_calls"][0]["id"], "call_0");
}

#[tokio::test]
async fn stream_without_generate_stream_is_an_error() {
    let generate: GenerateFn = Arc::new(|req: InProcessRequest| {
        let a = answer_for(&req);
        Box::pin(async move { Ok(a) })
    });
    let c = create_in_process_client(InProcessOptions {
        generate: Some(generate),
        ..Default::default()
    });
    let mut rx = c.stream("x", &tk().await).unwrap();
    let mut err = None;
    while let Some(ev) = rx.recv().await {
        if ev.kind == "error" {
            err = ev.error;
        }
    }
    assert!(
        err.unwrap()
            .to_string()
            .contains("does not support streaming")
    );
}

#[tokio::test]
async fn generate_stream_streams_text_and_side_events() {
    let gs: GenerateStreamFn = Arc::new(|_cancel, req: InProcessRequest, emit: EmitFn| {
        let a = answer_for(&req);
        Box::pin(async move {
            if a.tool_calls.is_empty() {
                emit(StreamEvent::text("sum="));
                emit(StreamEvent::text("5"));
                emit(StreamEvent {
                    kind: "tool_call".into(),
                    name: "agent_read".into(),
                    via: "acp".into(),
                    ..Default::default()
                });
            }
            Ok(InProcessResponse {
                content: String::new(),
                ..a
            })
        })
    });
    let c = create_in_process_client(InProcessOptions {
        generate_stream: Some(gs),
        ..Default::default()
    });
    let mut rx = c.stream("add", &tk().await).unwrap();
    let (mut text, mut acp, mut done) = (String::new(), false, None);
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => text.push_str(&ev.delta),
            "tool_call" if ev.via == "acp" => acp = true,
            "done" => done = ev.result,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    assert_eq!(text, "sum=5");
    assert!(acp, "non-text events are forwarded");
    assert_eq!(done.unwrap().text, "sum=5");
    // run() also works with only generate_stream
    assert_eq!(c.run("add", &tk().await).await.unwrap().tool_calls.len(), 1);
}

#[tokio::test]
async fn generate_error_fails_the_run() {
    let generate: GenerateFn = Arc::new(|_| Box::pin(async { Err("model crashed".to_string()) }));
    let c = create_in_process_client(InProcessOptions {
        generate: Some(generate),
        ..Default::default()
    });
    let err = c.run("x", &tk().await).await.unwrap_err();
    assert!(err.to_string().contains("model crashed"), "{err}");
}

/// conformance/acp-client/cases.json `stream.inProcess`: the generate blocks
/// after each text delta until the consumer has received it, so a buffered
/// stream fails with `notIncrementalError`.
#[tokio::test]
async fn generate_stream_is_incremental() {
    let cases: Value = serde_json::from_str(
        &std::fs::read_to_string("../conformance/acp-client/cases.json").unwrap(),
    )
    .unwrap();
    let case = cases["stream"]["inProcess"].clone();
    let wait = std::time::Duration::from_millis(case["waitMs"].as_u64().unwrap());
    let not_incr = case["notIncrementalError"].as_str().unwrap().to_owned();
    // count of text deltas the consumer has seen
    let (seen_tx, seen_rx) = tokio::sync::watch::channel(0usize);
    let emits = case["emits"].as_array().unwrap().clone();
    let returns = case["returns"]["content"].as_str().unwrap().to_owned();
    let gs: GenerateStreamFn = Arc::new(move |_c, _req, emit: EmitFn| {
        let (emits, returns, not_incr) = (emits.clone(), returns.clone(), not_incr.clone());
        let mut seen = seen_rx.clone();
        Box::pin(async move {
            let mut texts = 0usize;
            for e in &emits {
                let s = |k: &str| e[k].as_str().unwrap_or("").to_owned();
                let ev = StreamEvent {
                    kind: s("type"),
                    delta: s("delta"),
                    id: s("id"),
                    name: s("name"),
                    via: s("via"),
                    args: e.get("args").cloned().unwrap_or(Value::Null),
                    ..Default::default()
                };
                emit(ev);
                if e["awaitConsumer"] == json!(true) {
                    texts += 1;
                    let ok = tokio::time::timeout(wait, seen.wait_for(|n| *n >= texts)).await;
                    if !matches!(ok, Ok(Ok(_))) {
                        return Err(not_incr.clone());
                    }
                }
            }
            Ok(InProcessResponse {
                content: returns,
                ..Default::default()
            })
        })
    });
    let c = create_in_process_client(InProcessOptions {
        generate_stream: Some(gs),
        ..Default::default()
    });
    let mut rx = c
        .stream(case["prompt"].as_str().unwrap(), &tk().await)
        .unwrap();
    let (mut deltas, mut pass, mut done) = (Vec::<String>::new(), Vec::new(), None);
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => {
                deltas.push(ev.delta.clone());
                seen_tx.send_modify(|n| *n += 1);
            }
            "thought" => pass.push(json!({"type": "thought", "delta": ev.delta, "via": ev.via})),
            "tool_call" => {
                pass.push(json!({"type": "tool_call", "id": ev.id, "name": ev.name, "via": ev.via}))
            }
            "done" => done = ev.result,
            "error" => panic!("{:?}", ev.error),
            _ => {}
        }
    }
    assert_eq!(json!(deltas), case["expectTextDeltas"]);
    assert_eq!(json!(pass), case["expectPassThrough"]);
    let done = done.expect("done");
    assert_eq!(done.text, case["expectDoneText"].as_str().unwrap());
    assert_eq!(
        done.tool_calls.len() as u64,
        case["expectDoneToolCalls"].as_u64().unwrap()
    );
}
