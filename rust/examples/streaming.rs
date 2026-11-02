//! Streaming the client loop (SPEC §8 Streaming), offline: an in-process model
//! calls a native tool, then streams its answer token by token.
//!
//!     cargo run --example streaming

use std::sync::Arc;

use serde_json::json;
use toolnexus::client::{
    EmitFn, GenerateStreamFn, InProcessOptions, InProcessRequest, InProcessResponse,
    InProcessToolCall, StreamEvent, create_in_process_client,
};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let add = define_tool("add", "Add a and b", None, |a, _c| async move {
        let s = a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0);
        Ok::<_, String>(s.to_string())
    });
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![add],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await?;

    // A scripted model: first turn asks for `add`, second streams the answer.
    let model: GenerateStreamFn = Arc::new(|_cancel, req: InProcessRequest, emit: EmitFn| {
        Box::pin(async move {
            let tool = req.messages.iter().rev().find(|m| m["role"] == "tool");
            match tool.and_then(|m| m["content"].as_str().map(String::from)) {
                None => Ok(InProcessResponse {
                    tool_calls: vec![InProcessToolCall {
                        id: String::new(),
                        name: "add".into(),
                        arguments: json!({"a": 2, "b": 3}),
                    }],
                    ..Default::default()
                }),
                Some(sum) => {
                    for w in ["The ", "sum ", "is ", sum.as_str(), "."] {
                        emit(StreamEvent::text(w));
                    }
                    Ok(InProcessResponse::default())
                }
            }
        })
    });
    let client = create_in_process_client(InProcessOptions {
        model: "scripted".into(),
        generate_stream: Some(model),
        ..Default::default()
    });

    let mut rx = client.stream("add 2 and 3", &tk)?;
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => print!("{}", ev.delta),
            "tool_call" => println!("[tool_call {} {}]", ev.name, ev.args),
            "tool_result" => println!("[tool_result {} -> {}]", ev.name, ev.output),
            "done" => println!("\n[done turns={}]", ev.result.map(|r| r.turns).unwrap_or(0)),
            "error" => eprintln!("\n[error {:?}]", ev.error),
            _ => {}
        }
    }
    Ok(())
}
