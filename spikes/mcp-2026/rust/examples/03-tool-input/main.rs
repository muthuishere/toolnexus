//! Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
//!
//! Run: cargo run --example 03-tool-input
//!
//! In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
//! RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
//! The client answers them and re-sends the same call with inputResponses (+ the opaque
//! requestState). call_tool runs those rounds for you: you only supply a Responder callback.
use mcp2026::{self as mcp, Client, HttpServer, obj};
use serde_json::json;

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    let ts = HttpServer::start(mcp::fixture_server()).await; // `greet` asks for the user's name
    let c = Client::new(format!("{}/mcp", ts.url));

    // The Responder is called once per input request. key identifies the request inside the
    // round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
    // In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    let ask_user = mcp::responder(|_key, req| {
        let message = req["params"]["message"].as_str().unwrap_or_default();
        println!(
            "server asks ({}): {message}",
            req["method"].as_str().unwrap_or_default()
        );
        // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
        Ok(obj!({"action": "accept", "content": {"name": "Ada"}}))
    });

    let res = c.call_tool("greet", json!({}), ask_user).await?;
    println!("result: {}", mcp::text(&res));
    Ok(())
}
