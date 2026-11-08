//! Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
//!
//! Run: cargo run --example 02-connect-stdio
//!
//! stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
//! through (or captured with Client::stdio_capture) and never parsed. There are no HTTP headers
//! and no OAuth over stdio: a stdio server takes its credentials from its own environment.
use mcp2026::{self as mcp, Client};
use serde_json::json;
use tokio::process::Command;

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    // Demo harness: this same binary is the stdio server when started with SERVE_STDIO=1.
    // In real use the command is your server, e.g. Command::new("npx").args(["-y", "some-mcp-server"]).
    if std::env::var("SERVE_STDIO").as_deref() == Ok("1") {
        mcp::serve_stdio(
            &mcp::fixture_server(),
            tokio::io::stdin(),
            tokio::io::stdout(),
        )
        .await;
        return Ok(());
    }
    let me = std::env::current_exe().expect("current exe");

    // 1. Describe the process to spawn (env, args, working dir: plain tokio::process::Command).
    let mut cmd = Command::new(me);
    cmd.env("SERVE_STDIO", "1");

    // 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
    let c = Client::stdio(cmd)?;

    // 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
    let tools = c.tools().await?;
    println!("tool count: {}", tools.len());

    let res = c
        .call_tool("echo", json!({"text": "hello over stdio"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));

    c.close().await; // stops the server process
    Ok(())
}
