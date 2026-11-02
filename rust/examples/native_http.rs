//! Native and HTTP tools (SPEC §6, §7) side by side, executed through the toolkit.
//!
//!     cargo run --example native_http
//!     cargo run --example native_http -- https://api.github.com/repos/{owner}/{repo}
//!
//! The HTTP call only runs when a URL is passed (it needs the network).

use serde_json::json;
use toolnexus::http::{HttpToolOptions, http_tool};
use toolnexus::{BuiltinsConfig, ToolContext, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let greet = define_tool(
        "greet",
        "Greet someone by name",
        Some(json!({"type":"object","properties":{"name":{"type":"string"}},"required":["name"]})),
        |args, _ctx| async move {
            match args["name"].as_str() {
                Some(n) if !n.is_empty() => Ok(format!("Hello, {n}!")),
                _ => Err("name is required"),
            }
        },
    );
    let url = std::env::args().nth(1);
    let repo = http_tool(HttpToolOptions {
        name: "repo_info".into(),
        description: "Fetch a GitHub repository's metadata".into(),
        url: url
            .clone()
            .unwrap_or_else(|| "https://api.github.com/repos/{owner}/{repo}".into()),
        // Header values expand ${ENV} at call time and are never logged.
        headers: [("User-Agent".to_string(), "toolnexus-example".to_string())].into(),
        input_schema: Some(json!({"type":"object","properties":{
            "owner":{"type":"string"},"repo":{"type":"string"}},"required":["owner","repo"]})),
        ..Default::default()
    });
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![greet, repo],
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await?;

    for t in tk.tools() {
        println!("{} ({:?})", t.name, t.source);
    }
    let ok = tk
        .execute("greet", json!({"name": "Rust"}), ToolContext::default())
        .await;
    println!("greet -> {} (isError={})", ok.output, ok.is_error);
    let bad = tk.execute("greet", json!({}), ToolContext::default()).await;
    println!("greet {{}} -> {} (isError={})", bad.output, bad.is_error);

    if url.is_some() {
        let r = tk
            .execute(
                "repo_info",
                json!({"owner": "muthuishere", "repo": "toolnexus"}),
                ToolContext::default(),
            )
            .await;
        let head: String = r.output.chars().take(200).collect();
        println!("repo_info -> isError={} {head}", r.is_error);
    }
    Ok(())
}
