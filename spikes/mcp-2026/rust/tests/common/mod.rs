#![allow(dead_code)]
//! Shared test env: a dev AS + an OAuth-protected MCP server (TLS) + a CIMD document host.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use mcp2026::{
    Authorizer, Client, DevAuthServer, Error, HttpServer, OAuth, Pending, Response, Server,
    ServerAuth, fixture_server, handler,
};
use serde_json::{Value, json};

pub struct Env {
    pub dev: DevAuthServer,
    pub url: String,    // canonical resource URI
    pub origin: String, // https://127.0.0.1:<port>
    pub cimd_url: String,
    _mcp: HttpServer,
    _cimd: HttpServer,
}

/// cfg runs on the server before it starts serving.
pub async fn new_env(cfg: impl FnOnce(&mut Server)) -> Env {
    let dev = DevAuthServer::start().await;
    let cimd = HttpServer::start_tls(handler(|r: mcp2026::Request| async move {
        let id = format!("https://{}/client.json", r.host);
        Response::json(200, &json!({"client_id": id, "client_name": "toolnexus spike",
            "redirect_uris": ["http://127.0.0.1/callback"], "grant_types": ["authorization_code", "refresh_token"],
            "response_types": ["code"], "token_endpoint_auth_method": "none"}))
    }))
    .await;
    let mut srv =
        fixture_server().tool("whoami", "who is calling", |ctx, _| match &ctx.principal {
            Some(p) => Ok(json!(format!("{} {}", p.subject, p.scopes.join(",")))),
            None => Err("no principal".into()),
        });
    srv.auth = Some(
        ServerAuth::new(&dev.issuer)
            .with_scopes(&[("*", &["mcp:read"]), ("greet", &["mcp:greet"])]),
    );
    cfg(&mut srv);
    let mcp = HttpServer::start_tls(srv).await;
    Env {
        url: format!("{}/mcp", mcp.url),
        origin: mcp.url.clone(),
        cimd_url: format!("{}/client.json", cimd.url),
        dev,
        _mcp: mcp,
        _cimd: cimd,
    }
}

impl Env {
    /// The AS redirect Location without following it (pause mode: the host "receives" the callback).
    pub async fn visit(&self, u: &str) -> String {
        let resp = mcp2026::http::no_redirect_client()
            .get(u)
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status().as_u16(), 302, "authorize");
        resp.headers()["location"].to_str().unwrap().to_string()
    }

    pub fn auto_oauth(&self) -> OAuth {
        OAuth {
            client_metadata_url: self.cimd_url.clone(),
            authorizer: Authorizer::loopback(self.dev.approver()),
            ..Default::default()
        }
    }

    pub fn auto_client(&self) -> Client {
        Client::new(&self.url).with_auth(self.auto_oauth())
    }

    pub fn pause_client(&self) -> Client {
        self.dev
            .register_client("spike-prereg", &["http://127.0.0.1:9/callback"]);
        Client::new(&self.url).with_auth(OAuth {
            client_ids: [(self.dev.issuer.clone(), "spike-prereg".to_string())].into(),
            authorizer: Authorizer::pause("http://127.0.0.1:9/callback"),
            ..Default::default()
        })
    }

    pub fn claims(&self, mutate: impl FnOnce(&mut Value)) -> String {
        let mut c = json!({"iss": self.dev.issuer, "sub": "alice", "aud": self.url, "scope": "mcp:read", "exp": now() + 3600});
        mutate(&mut c);
        self.dev.mint(&c)
    }

    /// A raw tools/call; returns (status, WWW-Authenticate, body).
    pub async fn raw_call(&self, token: &str, tool: &str) -> (u16, String, String) {
        let body = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": tool, "arguments": {"text": "x"}, "_meta": {mcp2026::META_VERSION: mcp2026::PROTOCOL_VERSION}}});
        let mut req = mcp2026::http::default_client()
            .post(&self.url)
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", mcp2026::PROTOCOL_VERSION)
            .header("Mcp-Method", "tools/call")
            .header("Mcp-Name", tool)
            .body(body.to_string());
        if !token.is_empty() {
            req = req.header("Authorization", format!("Bearer {token}"));
        }
        let resp = req.send().await.unwrap();
        let st = resp.status().as_u16();
        let www = resp
            .headers()
            .get("www-authenticate")
            .map(|v| v.to_str().unwrap().to_string())
            .unwrap_or_default();
        (st, www, resp.text().await.unwrap())
    }
}

pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

pub fn pending<T: std::fmt::Debug>(r: Result<T, Error>) -> Pending {
    match r {
        Err(Error::Pending(p)) => p,
        other => panic!("want Pending, got {other:?}"),
    }
}

pub fn expire_token(c: &Client, url: &str) {
    let store = &c.oauth().store;
    let mut t = store.load(url).expect("token");
    t.expiry = Some(SystemTime::now() - Duration::from_secs(60));
    store.save(url, t);
}
