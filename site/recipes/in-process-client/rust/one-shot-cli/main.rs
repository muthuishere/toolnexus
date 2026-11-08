// Cargo.toml:
//   toolnexus = "0.25"
//   tokio = { version = "1", features = ["full"] }
//   serde_json = "1"
// run: cargo run -- python3 tools/mock-llm/fake_cli.py      (any CLI that reads stdin and prints a reply)
mod oneshot;

use serde_json::json;
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let add = define_tool("add", "Add two numbers.", Some(schema), |a, _ctx| async move {
        Ok::<_, String>((a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0)).to_string())
    });
    let tk = create_toolkit(ToolkitOptions { builtins: Some(BuiltinsConfig::off()), extra_tools: vec![add], ..Default::default() }).await?;
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let (command, args) = argv.split_first().ok_or("usage: main <cli> [args...]")?;
    let r = oneshot::one_shot_client(command.clone(), args.to_vec())
        .run("Use the add tool to add 19 and 23, then tell me the sum.", &tk).await?;
    println!("text: {} toolCalls: {}", json!(r.text), json!(r.tool_calls));
    Ok(())
}
