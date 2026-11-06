// Cargo.toml:
//   toolnexus = "0.25"
//   tokio = { version = "1", features = ["full"] }
//   serde_json = "1"
// run: cargo run
use std::sync::Arc;

use serde_json::json;
use toolnexus::client::{
    EmitFn, GenerateStreamFn, InProcessOptions, InProcessRequest, InProcessResponse,
    InProcessToolCall, StreamEvent, create_in_process_client,
};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let add = define_tool("add", "Add two numbers.", Some(schema), |a, _ctx| async move {
        Ok::<_, String>((a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0)).to_string())
    });

    // Your "model": first turn asks for a tool, second turn streams the answer.
    let model: GenerateStreamFn = Arc::new(|_cancel, req: InProcessRequest, emit: EmitFn| {
        Box::pin(async move {
            let last = req.messages.last().cloned().unwrap_or_default();
            if last["role"] != "tool" {
                let call = InProcessToolCall { id: String::new(), name: "add".into(), arguments: json!({"a": 2, "b": 3}) };
                return Ok(InProcessResponse { tool_calls: vec![call], ..Default::default() });
            }
            let sum = last["content"].as_str().unwrap_or_default().to_string();
            let words = ["The ", "sum ", "is ", &sum, "."];
            for w in words {
                emit(StreamEvent::text(w)); // deltas must add up to content
            }
            Ok(InProcessResponse { content: words.concat(), ..Default::default() })
        })
    });
    let client = create_in_process_client(InProcessOptions {
        model: "my-model".into(),
        generate_stream: Some(model),
        ..Default::default()
    });

    let toolkit = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![add],
        ..Default::default()
    })
    .await?;
    let mut events = client.stream("What is 2 + 3?", &toolkit)?;
    while let Some(ev) = events.recv().await {
        match ev.kind.as_str() {
            "text" => print!("{}", ev.delta),
            "tool_call" => println!("[calling {}]", ev.name),
            _ => {}
        }
    }
    println!(); // The sum is 5.
    Ok(())
}
