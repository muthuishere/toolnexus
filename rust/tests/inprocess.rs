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
