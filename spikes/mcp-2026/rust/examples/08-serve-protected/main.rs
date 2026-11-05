//! Example 08 — serve MCP tools behind OAuth: one issuer line, per-tool scopes, the caller's
//! identity in the handler, and a custom verify hook for opaque tokens.
//!
//! Run: cargo run --example 08-serve-protected   (hermetic: everything runs on 127.0.0.1)
//!
//! With Server.auth set the server: serves Protected Resource Metadata at
//! /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
//! token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
//! issuer's JWKS (signature, iss, aud = this server's URL, exp).
use std::collections::HashMap;
use std::sync::Arc;

use mcp2026::{
    self as mcp, Authorizer, Client, DevAuthServer, HttpServer, OAuth, Principal, Server,
    ServerAuth,
};
use serde_json::{Value, json};

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    let dev = DevAuthServer::start().await; // DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
    dev.register_client("my-app", &["http://127.0.0.1/callback"]);

    // ---- (1) the server: issuer one-liner + per-tool scope + Principal ----
    let mut srv = Server::new()
        .tool("whoami", "Who is calling", |ctx, _| {
            let p = ctx.principal.clone().unwrap_or_default(); // subject, scopes, claims of the verified token
            Ok(json!(format!(
                "hello {} ({})",
                p.subject,
                p.scopes.join(" ")
            )))
        })
        .tool("delete_all", "Dangerous admin tool", |_, _| {
            Ok(json!("deleted"))
        });
    srv.auth = Some(
        ServerAuth::new(&dev.issuer) // the whole required config
            .with_scopes(&[
                ("*", &["mcp:read"]),           // every request
                ("delete_all", &["mcp:admin"]), // additionally required for this tool
            ]),
    );
    let ts = HttpServer::start(srv).await; // production: HttpServer::bind(addr, srv)
    // ---- end server ----

    // No token: 401 with WWW-Authenticate pointing at the resource metadata.
    let http = reqwest::Client::new();
    let resp = http
        .post(format!("{}/mcp", ts.url))
        .header("Content-Type", "application/json")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "tools/list")
        .body(r#"{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}"#)
        .send()
        .await
        .expect("post");
    let www = resp
        .headers()
        .get("www-authenticate")
        .and_then(|v| v.to_str().ok())
        .unwrap_or_default();
    println!(
        "no token: {} | WWW-Authenticate has resource_metadata: {}",
        resp.status().as_u16(),
        www.contains("resource_metadata=")
    );

    // The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
    let prm: Value = http
        .get(format!(
            "{}/.well-known/oauth-protected-resource/mcp",
            ts.url
        ))
        .send()
        .await
        .expect("prm")
        .json()
        .await
        .expect("json");
    let scopes: Vec<&str> = prm["scopes_supported"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .collect();
    println!(
        "metadata: issuer ok: {} | scopes: [{}]",
        prm["authorization_servers"][0] == dev.issuer.as_str(),
        scopes.join(" ")
    );

    // A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
    let c = Client::new(format!("{}/mcp", ts.url)).with_auth(OAuth {
        client_id: "my-app".into(),
        authorizer: Authorizer::loopback(dev.approver()),
        ..Default::default()
    });
    let res = c.call_tool("whoami", Value::Null, None).await?;
    println!("whoami: {}", mcp::text(&res));
    let res = c.call_tool("delete_all", Value::Null, None).await?;
    println!("delete_all after step-up: {}", mcp::text(&res));
    let res = c.call_tool("whoami", Value::Null, None).await?;
    println!("whoami: {}", mcp::text(&res));

    // ---- (2) custom verify: opaque API keys / token introspection instead of JWT ----
    let keys: HashMap<String, Principal> = HashMap::from([(
        // e.g. a lookup in your DB, or an RFC 7662 introspection call
        "svc-key-123".to_string(),
        Principal {
            subject: "billing-service".into(),
            scopes: vec!["mcp:read".into()],
            ..Default::default()
        },
    )]);
    let mut srv2 = Server::new().tool("whoami", "Who is calling", |ctx, _| {
        Ok(json!(format!(
            "hello {}",
            ctx.principal
                .as_ref()
                .map(|p| p.subject.as_str())
                .unwrap_or_default()
        )))
    });
    srv2.auth = Some(ServerAuth {
        verify: Some(Arc::new(move |token: &str| {
            keys.get(token)
                .cloned()
                .ok_or_else(|| "unknown key".to_string())
        })), // -> 401 invalid_token
        ..ServerAuth::new(&dev.issuer).with_scopes(&[("*", &["mcp:read"])]) // issuer still advertised in the metadata
    });
    let ts2 = HttpServer::start(srv2).await;

    // A service caller with a static key: no OAuth, just an Authorization header on every request.
    let mut svc = Client::new(format!("{}/mcp", ts2.url));
    svc.header_override = vec![("Authorization".into(), "Bearer svc-key-123".into())];
    let res = svc.call_tool("whoami", Value::Null, None).await?;
    println!("verify hook: {}", mcp::text(&res));

    let mut bad = Client::new(format!("{}/mcp", ts2.url));
    bad.header_override = vec![("Authorization".into(), "Bearer wrong".into())];
    let r = bad.call_tool("whoami", Value::Null, None).await;
    println!("wrong key rejected: {}", r.is_err());
    Ok(())
}
