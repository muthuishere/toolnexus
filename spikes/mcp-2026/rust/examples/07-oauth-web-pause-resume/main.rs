//! Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
//!
//! Run: cargo run --example 07-oauth-web-pause-resume   (hermetic: everything runs on 127.0.0.1)
//!
//! A server-side app must never block a request waiting for a login. With Authorizer::Pause the
//! call returns Err(Error::Pending) instead ({id, kind:"authorization", prompt, url, expires_at},
//! the shape of toolnexus SPEC §10 Request). The app sends the browser to pending.url; the
//! authorization server redirects back; the app resumes and gets the ORIGINAL call's result.
//!
//! web_handler packages that whole dance as one handler. The manual version is shown too.
use mcp2026::{self as mcp, Authorizer, Client, DevAuthServer, HttpServer, OAuth, ServerAuth};
use serde_json::json;

#[tokio::main]
async fn main() -> Result<(), mcp::Error> {
    // ---- demo harness: dev authorization server + protected MCP server ----
    let dev = DevAuthServer::start().await; // DEV/TEST ONLY
    dev.register_client(
        "my-web-app",
        &["http://127.0.0.1/tool", "http://127.0.0.1/callback"],
    );
    let mut srv = mcp::fixture_server();
    srv.auth = Some(ServerAuth::new(&dev.issuer));
    let mcp_ts = HttpServer::start(srv).await;
    let mcp_url = format!("{}/mcp", mcp_ts.url);
    // ---- end harness ----

    let ln = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let app = format!("http://{}", ln.local_addr().unwrap());

    // ---- the web app ----
    let c = Client::new(&mcp_url).with_auth(OAuth {
        client_id: "my-web-app".into(),
        authorizer: Authorizer::pause(format!("{app}/tool")),
        ..Default::default()
    });

    // (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
    // with ?code&state, web_handler resumes, and the tool's text is written to the response.
    let cc = c.clone();
    let tool = c.oauth().web_handler(move |r| {
        let c = cc.clone();
        async move {
            c.call_tool(
                "echo",
                json!({"text": r.query_param("text").unwrap_or_default()}),
                None,
            )
            .await
        }
    });
    let _web = HttpServer::serve_on(ln, tool, None);
    // ---- end web app ----

    // A person's browser opens /tool?text=... (dev.client() follows redirects and trusts the dev TLS).
    let browser = dev.client();
    let resp = browser
        .get(format!("{app}/tool?text=resumed+after+consent"))
        .send()
        .await
        .expect("get");
    let status = resp.status().as_u16();
    println!(
        "(a) web_handler: {status} {}",
        resp.text().await.unwrap_or_default()
    );

    // (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
    let c2 = Client::new(&mcp_url).with_auth(OAuth {
        client_id: "my-web-app".into(),
        authorizer: Authorizer::pause("http://127.0.0.1/callback"),
        ..Default::default()
    });
    let r = c2
        .call_tool("echo", json!({"text": "resumed by hand"}), None)
        .await;
    let Some(p) = mcp::pending_of(&r) else {
        panic!("expected a pending authorization, got {r:?}")
    };
    println!(
        "(b) paused: {} - {} - has URL: {}",
        p.kind,
        !p.prompt.is_empty(),
        !p.url.is_empty()
    );

    let callback_url = approve_and_capture_callback(&p.url).await; // the user logs in; you receive the callback URL
    let res = c2.oauth().resume(&p.id, &callback_url).await?;
    println!("(b) resumed: {}", mcp::text(&res));
    Ok(())
}

/// Stands in for a person logging in: opens the authorization URL and returns the URL the
/// authorization server redirected back to (without following it).
async fn approve_and_capture_callback(auth_url: &str) -> String {
    let resp = mcp::http::no_redirect_client()
        .get(auth_url)
        .send()
        .await
        .expect("authorize");
    resp.headers()["location"].to_str().unwrap().to_string()
}
