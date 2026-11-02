//! §10 suspension, offline: the builtin `question` tool asks the human. First with
//! a `wait_for` slot (answered inline), then without one (the run halts durably
//! with `status: "pending"`). Mirrors js/examples/pending.ts.
//!
//!     cargo run --example pending

use std::collections::BTreeMap;
use std::sync::Arc;

use serde_json::json;
use toolnexus::client::{
    GenerateFn, InProcessOptions, InProcessRequest, InProcessResponse, InProcessToolCall,
    WaitForFn, create_in_process_client,
};
use toolnexus::{Answer, BuiltinsConfig, ToolkitOptions, create_toolkit};

fn model() -> GenerateFn {
    Arc::new(|req: InProcessRequest| {
        Box::pin(async move {
            let tool = req.messages.iter().rev().find(|m| m["role"] == "tool");
            Ok(
                match tool.and_then(|m| m["content"].as_str().map(String::from)) {
                    None => InProcessResponse {
                        tool_calls: vec![InProcessToolCall {
                            id: String::new(),
                            name: "question".into(),
                            arguments: json!({"questions": [
                                {"question": "Which environment?", "options": ["staging", "prod"]}
                            ]}),
                        }],
                        ..Default::default()
                    },
                    Some(answer) => InProcessResponse {
                        content: format!("Deploying with answer {answer}"),
                        ..Default::default()
                    },
                },
            )
        })
    })
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig {
            tools: BTreeMap::from([("bash".to_string(), false)]),
            ..Default::default()
        }),
        ..Default::default()
    })
    .await?;

    let wait_for: WaitForFn = Arc::new(|req| {
        Box::pin(async move {
            println!("[wait_for] kind={} prompt={:?}", req.kind, req.prompt);
            let mut data = serde_json::Map::new();
            data.insert("answers".into(), json!([["staging"]]));
            Ok(Answer {
                id: req.id,
                ok: true,
                data: Some(data),
                ..Default::default()
            })
        })
    });
    let answered = create_in_process_client(InProcessOptions {
        generate: Some(model()),
        wait_for: Some(wait_for),
        ..Default::default()
    });
    let r = answered.run("deploy", &tk).await?;
    println!("with wait_for: status={} text={:?}", r.status, r.text);

    let halting = create_in_process_client(InProcessOptions {
        generate: Some(model()),
        ..Default::default()
    });
    let r = halting.run("deploy", &tk).await?;
    let p = r.pending.expect("pending request");
    println!(
        "without wait_for: status={} pending.kind={} pending.prompt={:?}",
        r.status, p.kind, p.prompt
    );
    Ok(())
}
