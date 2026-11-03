//! Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
//!
//! Run: cargo run --example 01-connect-http
//!
//! The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
//! Every request carries its own protocol version, so "connecting" is just Client::new(url).
use mcp2026::{self as mcp, Client, HttpServer};
use serde_json::{Value, json};

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    // A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
    // In real use this is someone else's server, e.g. "https://api.example.com/mcp".
    let ts = HttpServer::start(mcp::fixture_server()).await;

    // 1. Point a client at the server's MCP endpoint. No handshake happens here.
    let c = Client::new(format!("{}/mcp", ts.url));

    // 2. Optional: server/discover tells you which versions and capabilities the server has.
    let info = c.call("server/discover", Value::Null).await?;
    let versions: Vec<&str> = info["supportedVersions"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str())
        .collect();
    println!("supported versions: [{}]", versions.join(" "));

    // 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
    let tools = c.tools().await?;
    let names: Vec<&str> = tools.iter().filter_map(|t| t["name"].as_str()).collect();
    println!("tools: {}", names.join(", "));

    // 4. Call a tool. mcp::text returns the first text content of the result.
    let res = c
        .call_tool("echo", json!({"text": "hello over HTTP"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));
    Ok(())
}
