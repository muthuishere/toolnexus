//! Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
//!
//! Run: cargo run --example 06-oauth-zero-config   (hermetic: everything runs on 127.0.0.1)
//!
//! The client learns everything from the server's 401: Protected Resource Metadata -> the
//! authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
//! You only say WHO the client is: a pre-registered client_id, or a CIMD client_metadata_url.
//! There is no Dynamic Client Registration.
use mcp2026::{
    self as mcp, Authorizer, Client, DevAuthServer, HttpServer, MemoryTokenStore, OAuth,
    ServerAuth, TokenStore, obj,
};
use serde_json::json;

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    // ---- demo harness: an authorization server + a protected MCP server, both in-process ----
    let dev = DevAuthServer::start().await; // DEV/TEST ONLY: auto-approves everyone as "dev-user"
    dev.register_client("my-app", &["http://127.0.0.1/callback"]); // the AS admin registered "my-app"

    let mut srv = mcp::fixture_server();
    srv.auth = Some(
        ServerAuth::new(&dev.issuer)
            .with_scopes(&[("*", &["mcp:read"]), ("greet", &["mcp:greet"])]),
    ); // greet needs more
    let ts = HttpServer::start(srv).await;
    // ---- end harness ----

    let store = MemoryTokenStore::new(); // implement mcp::TokenStore to persist tokens (keyed by resource)

    // The user code. A real app leaves authorizer at its default: Loopback opens the system
    // browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
    let c = Client::new(format!("{}/mcp", ts.url)).with_auth(OAuth {
        client_id: "my-app".into(),
        store: store.clone(),
        authorizer: Authorizer::loopback(dev.approver()),
        ..Default::default()
    });

    let res = c
        .call_tool("echo", json!({"text": "hello after login"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));

    // Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
    // re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
    let answer = mcp::responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "Ada"}})));
    let res = c.call_tool("greet", json!({}), answer).await?;
    println!("greet: {}", mcp::text(&res));

    // A second client sharing the token store needs no new login.
    let c2 = Client::new(format!("{}/mcp", ts.url)).with_auth(OAuth {
        client_id: "my-app".into(),
        store: store.clone(),
        authorizer: Authorizer::loopback(dev.approver()),
        ..Default::default()
    });
    c2.call_tool("echo", json!({"text": "again"}), None).await?;
    let (logins, _) = dev.stats();
    println!("browser logins: {logins}"); // 1 login + 1 step-up

    let tok = store.load(&format!("{}/mcp", ts.url)).expect("token");
    println!("token stored for this issuer: {}", tok.issuer == dev.issuer); // {:?} of a Token is redacted
    Ok(())
}
