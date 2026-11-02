//! A real model driving the toolkit: skills + builtins + a native tool, through the
//! unified client loop. Mirrors golang/examples/openrouter.
//!
//!     OPENROUTER_API_KEY=... cargo run --example agent -- "what is 21 * 2? use the multiply tool"
//!
//! The key is read from the environment by the client (API-key-by-host rule), never printed.

use std::path::PathBuf;

use serde::Deserialize;
use serde_json::json;
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{ToolkitOptions, create_toolkit, define_typed_tool};

#[derive(Deserialize)]
struct Mul {
    a: f64,
    b: f64,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::var_os("OPENROUTER_API_KEY").is_none() {
        println!("set OPENROUTER_API_KEY to run this example against openrouter.ai");
        return Ok(());
    }
    let prompt = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "What is 21 * 2? Use the multiply tool.".into());
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples");
    let multiply = define_typed_tool(
        "multiply",
        "Multiply two numbers",
        json!({"type":"object","properties":{"a":{"type":"number"},"b":{"type":"number"}},"required":["a","b"]}),
        |m: Mul, _ctx| async move { Ok::<_, String>((m.a * m.b).to_string()) },
    );
    let tk = create_toolkit(ToolkitOptions {
        skills_dir: vec![root.join("skills")],
        extra_tools: vec![multiply],
        ..Default::default()
    })
    .await?;
    let client = create_client(ClientOptions {
        base_url: "https://openrouter.ai/api/v1".into(),
        style: Style::OpenAI,
        model: std::env::var("TOOLNEXUS_MODEL").unwrap_or_else(|_| "openai/gpt-4o-mini".into()),
        system_prompt: "You are a concise assistant.".into(),
        ..Default::default()
    });
    let r = client.run(prompt, &tk).await?;
    for c in &r.tool_calls {
        println!("tool {}({}) -> {}", c.name, c.args, c.output);
    }
    println!("{}", r.text);
    println!(
        "turns={} tokens={} status={}",
        r.turns, r.usage.total_tokens, r.status
    );
    Ok(())
}
