//! Minimal end-to-end example. Mirrors golang/examples/basic and js/examples/basic.ts.
//!
//!     cargo run --example basic
//!
//! Uses the shared fixtures in ../examples/ (the `everything` MCP server runs via npx;
//! if it cannot start it is reported `failed` and the rest still works).

use std::path::PathBuf;

use serde_json::json;
use toolnexus::{McpConfigInput, ToolContext, ToolkitOptions, create_toolkit};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../examples");
    let tk = create_toolkit(ToolkitOptions {
        mcp_config: Some(McpConfigInput::Path(root.join("mcp.json"))),
        skills_dir: vec![root.join("skills")],
        ..Default::default()
    })
    .await?;

    let status: Vec<String> = tk
        .mcp_status()
        .iter()
        .map(|(k, v)| format!("{k}:{}", v.as_str()))
        .collect();
    println!("MCP status: map[{}]", status.join(" "));

    let tools: Vec<String> = tk
        .tools()
        .iter()
        .map(|t| format!("{} ({})", t.name, json!(t.source).as_str().unwrap_or("")))
        .collect();
    println!("Tools: [{}]", tools.join(" "));

    println!("\nSystem-prompt skill catalog:");
    println!("{}", tk.skills_prompt());

    println!("\nOpenAI tool schema (first 2):");
    let openai: Vec<_> = tk.to_openai().into_iter().take(2).collect();
    println!("{}", serde_json::to_string_pretty(&openai)?);

    // Load a skill (progressive disclosure) — works with no MCP servers running.
    let res = tk
        .execute(
            "skill",
            json!({"name": "hello-world"}),
            ToolContext::default(),
        )
        .await;
    println!("\nskill(hello-world) ->");
    println!("{}", res.output);
    tk.close().await;
    Ok(())
}
