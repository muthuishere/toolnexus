//! Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
//!
//! GitHub supports neither DCR nor CIMD, so you register an OAuth App once
//! (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
//! enable "expiring user tokens" if you want refresh) and pass its client id + secret.
//!
//!     sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- cargo run --example 10-github
//!
//! Credentials are read from the environment and never printed; neither is any token.
//! BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
//! otherwise the system browser opens.
use mcp2026::{self as mcp, Authorizer, Client, OAuth, opener};
use serde_json::{Value, json};

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    let (id, secret) = (
        std::env::var("GITHUB_MCP_CLIENT_ID").unwrap_or_default(),
        std::env::var("GITHUB_MCP_CLIENT_SECRET").unwrap_or_default(),
    );
    if id.is_empty() || secret.is_empty() {
        println!("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...");
        return Ok(());
    }

    let mut login = Authorizer::default(); // system browser + http://127.0.0.1:<random>/callback
    if std::env::var("BROWSER").as_deref() == Ok("chrome-agent") {
        login = Authorizer::loopback(opener(|u| async move {
            let st = tokio::process::Command::new("chrome-agent")
                .args(["goto", &u])
                .status()
                .await;
            st.map(|_| ()).map_err(|e| e.to_string())
        }));
    }

    let c = Client::new("https://api.githubcopilot.com/mcp/").with_auth(OAuth {
        client_id: id,
        client_secret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
        authorizer: login,
        ..Default::default()
    });

    let tools = c.tools().await?;
    println!("tools listed: {}", !tools.is_empty());

    let res = c.call_tool("get_me", json!({}), None).await?; // read-only: who am I
    println!(
        "get_me: {} | isError: {}", // content withheld on purpose
        res["resultType"].as_str().unwrap_or_default(),
        res.get("isError") == Some(&Value::Bool(true))
    );
    Ok(())
}
