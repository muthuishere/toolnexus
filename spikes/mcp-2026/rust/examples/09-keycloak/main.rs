//! Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
//!
//! Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
//!
//!     cd ../golang/keycloak && docker compose up -d && cd -
//!     KEYCLOAK=1 cargo run --example 09-keycloak                  # logs in through your system browser
//!     KEYCLOAK=1 HEADLESS_LOGIN=1 cargo run --example 09-keycloak # scripted login (used by the test)
//!
//! The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
//! aud = http://127.0.0.1:7199/mcp into tokens; ServerAuth.audience must equal it.
mod headless;

use mcp2026::{self as mcp, Authorizer, Client, OAuth, ServerAuth, obj};
use serde_json::{Value, json};

const ISSUER: &str = "http://localhost:8180/realms/toolnexus"; // exactly the `iss` Keycloak puts in tokens
const MCP_ADDR: &str = "127.0.0.1:7199";
const RESOURCE: &str = "http://127.0.0.1:7199/mcp";

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    if std::env::var("KEYCLOAK").as_deref() != Ok("1") {
        println!("skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running");
        return Ok(());
    }

    // ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
    let mut srv = mcp::fixture_server().tool("whoami", "Who is calling", |ctx, _| {
        let p = ctx.principal.clone().unwrap_or_default();
        // subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
        let azp = p
            .claims
            .get("azp")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        Ok(json!(format!(
            "client={azp} scopes={} subject-set={}",
            p.scopes.join(" "),
            !p.subject.is_empty()
        )))
    });
    srv.auth = Some(ServerAuth {
        audience: RESOURCE.into(), // = the audience mapper's included.custom.audience
        ..ServerAuth::new(ISSUER).with_scopes(&[("*", &["mcp:tools"]), ("greet", &["mcp:greet"])])
    });
    let _server = headless::bind_retry(MCP_ADDR, srv).await;

    // ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
    let authorizer = if std::env::var("HEADLESS_LOGIN").as_deref() == Ok("1") {
        Authorizer::loopback(headless::login("alice", "alice-test-pw")) // throwaway local test user
    } else {
        Authorizer::default() // system browser
    };
    let c = Client::new(RESOURCE).with_auth(OAuth {
        client_ids: [(ISSUER.to_string(), "toolnexus-mcp".to_string())].into(), // client id bound to THIS issuer
        allow_http: true, // dev Keycloak is plain HTTP; never in production
        authorizer,
        ..Default::default()
    });

    let res = c.call_tool("whoami", Value::Null, None).await?;
    println!("whoami: {}", mcp::text(&res));

    // greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
    let answer =
        mcp::responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "Alice"}})));
    let res = c.call_tool("greet", json!({}), answer).await?;
    println!("greet: {}", mcp::text(&res));
    Ok(())
}
