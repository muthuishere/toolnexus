//! MCP authorization client (SPEC §2A.1, §2A.1a): OAuth 2.1 for remote servers.
//!
//! 401 / 403 `insufficient_scope` → `WWW-Authenticate` → Protected Resource
//! Metadata (RFC 9728) → AS metadata (RFC 8414 / OIDC) → client identity
//! (pre-registered `clientId` / `clientIds[issuer]`, else a Client ID Metadata
//! Document; **never** Dynamic Client Registration) → authorization code +
//! PKCE S256 + `resource` → `iss` / `state` check → token (JSON or form reply)
//! → single-flight refresh → step-up. Two modes: `auto` (loopback callback +
//! `open_url`) and `pause` (a §10 `authorization` request; finish with
//! [`OAuth::resume`]). Tokens, codes, verifiers and secrets are never logged
//! and never appear in error text.

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::future::BoxFuture;
use serde_json::Value;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use super::wire::{Obj, WireError, err_chain, lock, rand_str};
use crate::tool::Request;

/// An OAuth token bound to the issuer that minted it. Its `Debug` form never
/// shows a token value.
#[derive(Clone, Default, PartialEq)]
pub struct Token {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub expires_at: Option<SystemTime>,
    pub scopes: Vec<String>,
    pub issuer: String,
    pub client_id: String,
}

impl std::fmt::Debug for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Token {{ issuer: {:?}, client_id: {:?}, scopes: {:?}, <redacted> }}",
            self.issuer, self.client_id, self.scopes
        )
    }
}

/// Persists tokens per resource (the server URI). Keep them confidential.
pub trait TokenStore: Send + Sync {
    fn load(&self, resource: &str) -> Option<Token>;
    fn save(&self, resource: &str, token: Token);
    fn delete(&self, resource: &str);
}

/// The default store: in memory, per load.
#[derive(Default)]
pub struct MemoryTokenStore {
    m: Mutex<HashMap<String, Token>>,
}

impl MemoryTokenStore {
    pub fn new() -> Arc<MemoryTokenStore> {
        Arc::new(MemoryTokenStore::default())
    }
}

impl TokenStore for MemoryTokenStore {
    fn load(&self, r: &str) -> Option<Token> {
        lock(&self.m).get(r).cloned()
    }
    fn save(&self, r: &str, t: Token) {
        lock(&self.m).insert(r.to_owned(), t);
    }
    fn delete(&self, r: &str) {
        lock(&self.m).remove(r);
    }
}

/// How `auto` mode opens the authorization URL (default: the OS opener). May
/// block until the user is done; tests inject an HTTP GET.
pub type OpenUrlFn = Arc<dyn Fn(String) -> BoxFuture<'static, Result<(), String>> + Send + Sync>;

/// Per-server `auth` from `mcp.json` (§2A.1). Absent ⇒ all defaults: a 401
/// still triggers the flow with a CIMD when the AS supports it.
#[derive(Clone, Default, PartialEq)]
pub struct AuthConfig {
    pub client_id: String,
    /// Never logged.
    pub client_secret: String,
    /// `client_secret_basic` (default with a secret) | `client_secret_post`.
    pub token_auth_method: String,
    /// issuer → pre-registered client id.
    pub client_ids: BTreeMap<String, String>,
    pub client_metadata_url: String,
    pub redirect_uri: String,
    /// `"auto"` (default) | `"pause"` (needs `redirect_uri`).
    pub mode: String,
    pub omit_refresh_resource: bool,
    /// Allow a non-https authorization server (dev/test only).
    pub allow_http: bool,
}

impl std::fmt::Debug for AuthConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AuthConfig")
            .field("client_id", &self.client_id)
            .field("client_ids", &self.client_ids)
            .field("client_metadata_url", &self.client_metadata_url)
            .field("mode", &self.mode)
            .finish_non_exhaustive()
    }
}

impl AuthConfig {
    pub(crate) fn from_value(v: &Value) -> AuthConfig {
        let s = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
        let b = |k: &str| v.get(k).and_then(Value::as_bool).unwrap_or(false);
        AuthConfig {
            client_id: s("clientId"),
            client_secret: s("clientSecret"),
            token_auth_method: s("tokenAuthMethod"),
            client_ids: v
                .get("clientIds")
                .and_then(Value::as_object)
                .map(|m| {
                    m.iter()
                        .filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_owned())))
                        .collect()
                })
                .unwrap_or_default(),
            client_metadata_url: s("clientMetadataUrl"),
            redirect_uri: s("redirectUri"),
            mode: s("mode"),
            omit_refresh_resource: b("omitRefreshResource"),
            allow_http: b("allowHttp"),
        }
    }
}

struct AuthFlow {
    resource: String,
    issuer: String,
    client_id: String,
    redirect: String,
    verifier: String,
    state: String,
    token_endpoint: String,
    iss_supported: bool,
    scopes: Vec<String>,
    expires: SystemTime,
}

#[derive(Default)]
struct State {
    /// resource → confirmed issuer (only then is a stored token sent).
    issuer: HashMap<String, String>,
    flows: HashMap<String, AuthFlow>,
}

/// The per-server OAuth engine.
pub(crate) struct OAuth {
    cfg: AuthConfig,
    store: Arc<dyn TokenStore>,
    open_url: Option<OpenUrlFn>,
    http: reqwest::Client,
    st: Mutex<State>,
    /// One login / one refresh at a time (refresh tokens rotate).
    auth_mu: tokio::sync::Mutex<()>,
}

fn expired(t: &Token) -> bool {
    t.expires_at
        .is_some_and(|e| SystemTime::now() + Duration::from_secs(10) > e)
}

fn msg(m: impl Into<String>) -> WireError {
    WireError::Other(m.into())
}

impl OAuth {
    pub(crate) fn new(
        cfg: AuthConfig,
        store: Arc<dyn TokenStore>,
        open_url: Option<OpenUrlFn>,
        http: reqwest::Client,
    ) -> OAuth {
        OAuth {
            cfg,
            store,
            open_url,
            http,
            st: Mutex::new(State::default()),
            auth_mu: tokio::sync::Mutex::new(()),
        }
    }

    fn pause(&self) -> bool {
        self.cfg.mode == "pause"
    }

    /// The access token to send for `resource`, refreshed when expired. "" ⇒ none.
    pub(crate) async fn bearer(&self, resource: &str) -> String {
        let iss = lock(&self.st)
            .issuer
            .get(resource)
            .cloned()
            .unwrap_or_default();
        let Some(t) = self.store.load(resource) else {
            return String::new();
        };
        if iss.is_empty() || t.issuer != iss {
            return String::new(); // only tokens from this server's confirmed AS
        }
        if expired(&t) {
            let _g = self.auth_mu.lock().await; // N callers share one refresh
            let Some(t) = self.store.load(resource) else {
                return String::new();
            };
            if expired(&t) && self.refresh(resource, &t).await.is_err() {
                return String::new();
            }
            return self
                .store
                .load(resource)
                .map(|t| t.access_token)
                .unwrap_or_default();
        }
        t.access_token
    }

    /// The stored token for `resource` regardless of issuer confirmation
    /// (frozen-fallback transport, which carries a static header).
    pub(crate) fn stored_bearer(&self, resource: &str) -> String {
        self.store
            .load(resource)
            .map(|t| t.access_token)
            .unwrap_or_default()
    }

    async fn refresh(&self, resource: &str, t: &Token) -> Result<(), WireError> {
        let Some(rt) = t.refresh_token.clone().filter(|r| !r.is_empty()) else {
            return Err(msg("no refresh token"));
        };
        let md = discover_as(&self.http, &t.issuer).await?;
        let ep = md
            .get("token_endpoint")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned();
        let mut form = vec![
            ("grant_type", "refresh_token".to_owned()),
            ("refresh_token", rt.clone()),
            ("client_id", t.client_id.clone()),
        ];
        if !self.cfg.omit_refresh_resource {
            form.push(("resource", resource.to_owned()));
        }
        match self
            .token_request(&ep, form, &t.issuer, &t.client_id, &t.scopes)
            .await
        {
            Ok(mut nt) => {
                if nt.refresh_token.is_none() {
                    nt.refresh_token = Some(rt);
                }
                self.store.save(resource, nt);
                Ok(())
            }
            Err(e) => {
                self.store.delete(resource);
                Err(e)
            }
        }
    }

    /// Handle a 401 / 403 carrying `WWW-Authenticate`. `Ok` ⇒ re-send now;
    /// `Err(Pending)` ⇒ paused (pause mode). `sent` is the token the rejected
    /// request carried ("" ⇒ none).
    pub(crate) async fn challenge(
        &self,
        resource: &str,
        status: u16,
        www: &str,
        sent: &str,
    ) -> Result<(), WireError> {
        let params = parse_challenge(www);
        let p = |k: &str| params.get(k).cloned().unwrap_or_default();
        let _g = self.auth_mu.lock().await; // N concurrent 401s ⇒ one authorization
        let mut cur = self.store.load(resource);
        let known = lock(&self.st)
            .issuer
            .get(resource)
            .cloned()
            .unwrap_or_default();
        if let Some(c) = &cur
            && !known.is_empty()
            && c.issuer == known
            && c.access_token != sent
            && !expired(c)
        {
            return Ok(()); // refreshed / authorized by another caller meanwhile
        }
        if status == 401
            && let Some(c) = cur.clone()
            && c.refresh_token.as_deref().is_some_and(|r| !r.is_empty())
            && p("error") == "invalid_token"
        {
            if self.refresh(resource, &c).await.is_ok() {
                return Ok(());
            }
            cur = None;
        }
        if status == 403 && p("error") != "insufficient_scope" {
            return Err(msg(format!("forbidden: {}", p("error"))));
        }
        let prm = self.fetch_prm(resource, &p("resource_metadata")).await?;
        let issuer = prm
            .get("authorization_servers")
            .and_then(Value::as_array)
            .and_then(|a| a.first())
            .and_then(Value::as_str)
            .ok_or_else(|| msg("PRM lists no authorization_servers"))?
            .to_owned();
        if !self.cfg.allow_http && !issuer.starts_with("https://") {
            return Err(msg("authorization server must use https"));
        }
        let md = discover_as(&self.http, &issuer).await?;
        if !as_strings(md.get("code_challenge_methods_supported"))
            .iter()
            .any(|m| m == "S256")
        {
            return Err(msg(
                "authorization server does not advertise PKCE S256; refusing",
            ));
        }
        lock(&self.st)
            .issuer
            .insert(resource.to_owned(), issuer.clone());
        if status == 401
            && cur.as_ref().is_some_and(|c| c.issuer == issuer)
            && p("error").is_empty()
        {
            return Ok(()); // first request after a restart: the stored token may now be sent
        }
        // scope: the challenge's, else PRM scopes_supported; step-up adds the granted ones
        let mut scopes: Vec<String> = p("scope").split_whitespace().map(String::from).collect();
        if scopes.is_empty() {
            scopes = as_strings(prm.get("scopes_supported"));
        }
        if let Some(c) = &cur
            && status == 403
        {
            for s in &c.scopes {
                if !scopes.contains(s) {
                    scopes.push(s.clone());
                }
            }
        }
        let cimd = md.get("client_id_metadata_document_supported") == Some(&Value::Bool(true));
        let mut client_id = self
            .cfg
            .client_ids
            .get(&issuer)
            .cloned()
            .unwrap_or_default();
        if client_id.is_empty() {
            client_id = self.cfg.client_id.clone();
        }
        if client_id.is_empty() && !self.cfg.client_metadata_url.is_empty() && cimd {
            client_id = self.cfg.client_metadata_url.clone();
        }
        if client_id.is_empty() {
            // no DCR, ever: the user registers an app once and configures its id
            let why = if cimd {
                "clientMetadataUrl is not set"
            } else {
                "it does not support Client ID Metadata Documents"
            };
            return Err(msg(format!(
                "no client identity for {issuer}: {why} and no client id is configured — register an app with {issuer} and set auth.clientId (dynamic client registration is not supported)"
            )));
        }
        let ep = |k: &str| {
            md.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned()
        };
        let (auth_ep, token_ep) = (ep("authorization_endpoint"), ep("token_endpoint"));
        let loopback = if self.pause() {
            None
        } else {
            Some(Loopback::bind().await?)
        };
        let redirect = match &loopback {
            Some(l) => l.redirect_uri(),
            None => {
                if self.cfg.redirect_uri.is_empty() {
                    return Err(msg("auth mode \"pause\" requires redirectUri"));
                }
                self.cfg.redirect_uri.clone()
            }
        };
        let f = AuthFlow {
            resource: resource.to_owned(),
            issuer,
            client_id: client_id.clone(),
            redirect: redirect.clone(),
            verifier: rand_str(32),
            state: rand_str(16),
            token_endpoint: token_ep,
            iss_supported: md.get("authorization_response_iss_parameter_supported")
                == Some(&Value::Bool(true)),
            scopes: scopes.clone(),
            expires: SystemTime::now() + Duration::from_secs(600),
        };
        let mut q: Vec<(&str, String)> = vec![
            ("response_type", "code".into()),
            ("client_id", client_id),
            ("redirect_uri", redirect),
            ("state", f.state.clone()),
            ("code_challenge", pkce_challenge(&f.verifier)),
            ("code_challenge_method", "S256".into()),
            ("resource", resource.to_owned()),
        ];
        if !scopes.is_empty() {
            q.push(("scope", scopes.join(" ")));
        }
        q.sort_by(|a, b| a.0.cmp(b.0));
        let sep = if auth_ep.contains('?') { '&' } else { '?' };
        let req = Request {
            id: format!("auth-{}", rand_str(12)),
            kind: "authorization".into(),
            prompt: format!("Authorize access to {resource}"),
            url: format!(
                "{auth_ep}{sep}{}",
                encode_form(q.iter().map(|(k, v)| (*k, v.as_str())))
            ),
            data: None,
            expires_at: rfc3339(f.expires),
        };
        match loopback {
            None => {
                let mut st = lock(&self.st);
                let now = SystemTime::now();
                st.flows.retain(|_, old| now <= old.expires); // drop abandoned pendings
                st.flows.insert(req.id.clone(), f);
                Err(WireError::Pending(Box::new(req)))
            }
            Some(l) => {
                let open = self
                    .open_url
                    .clone()
                    .unwrap_or_else(|| Arc::new(|u| Box::pin(open_browser(u))));
                let cb = l.wait(open, req.url.clone()).await?;
                self.complete(&f, &cb).await
            }
        }
    }

    /// Finish a paused authorization with the URL the AS redirected to. The
    /// caller then sends the paused request.
    pub(crate) async fn resume(&self, id: &str, callback_url: &str) -> Result<(), WireError> {
        let f = lock(&self.st).flows.remove(id);
        let Some(f) = f else {
            return Err(msg(format!("unknown pending authorization {id:?}")));
        };
        if SystemTime::now() > f.expires {
            return Err(msg("pending authorization expired"));
        }
        let _g = self.auth_mu.lock().await;
        self.complete(&f, callback_url).await
    }

    async fn complete(&self, f: &AuthFlow, callback_url: &str) -> Result<(), WireError> {
        let u = url::Url::parse(callback_url).map_err(|_| msg("invalid callback URL"))?;
        let q: Vec<(String, String)> = u
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        let get = |k: &str| q.iter().find(|(a, _)| a == k).map(|(_, v)| v.clone());
        // RFC 9207 — before anything else, errors included
        match get("iss") {
            Some(iss) if iss != f.issuer => {
                return Err(msg(
                    "authorization response iss mismatch (possible mix-up attack)",
                ));
            }
            None if f.iss_supported => return Err(msg("authorization response missing iss")),
            _ => {}
        }
        if get("state").unwrap_or_default() != f.state {
            return Err(msg("authorization response state mismatch"));
        }
        if let Some(e) = get("error").filter(|e| !e.is_empty()) {
            return Err(msg(format!("authorization denied: {e}")));
        }
        let form = vec![
            ("grant_type", "authorization_code".to_owned()),
            ("code", get("code").unwrap_or_default()),
            ("redirect_uri", f.redirect.clone()),
            ("client_id", f.client_id.clone()),
            ("code_verifier", f.verifier.clone()),
            ("resource", f.resource.clone()),
        ];
        let t = self
            .token_request(&f.token_endpoint, form, &f.issuer, &f.client_id, &f.scopes)
            .await?;
        self.store.save(&f.resource, t);
        Ok(())
    }

    async fn token_request(
        &self,
        ep: &str,
        mut form: Vec<(&str, String)>,
        issuer: &str,
        client_id: &str,
        scopes: &[String],
    ) -> Result<Token, WireError> {
        let secret = &self.cfg.client_secret;
        let method = if client_id == self.cfg.client_metadata_url || secret.is_empty() {
            "none" // CIMD clients and secretless pre-registered clients are public
        } else if self.cfg.token_auth_method.is_empty() {
            "client_secret_basic"
        } else {
            self.cfg.token_auth_method.as_str()
        };
        if method == "client_secret_post" {
            form.push(("client_secret", secret.clone()));
        }
        if method == "client_secret_basic" {
            form.retain(|(k, _)| *k != "client_id"); // RFC 6749 §2.3.1: one method only
        }
        form.sort_by(|a, b| a.0.cmp(b.0));
        let mut req = self
            .http
            .post(ep)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json") // GitHub answers form-encoded without it
            .body(encode_form(form.iter().map(|(k, v)| (*k, v.as_str()))));
        if method == "client_secret_basic" {
            req = req.basic_auth(query_escape(client_id), Some(query_escape(secret)));
        }
        let resp = req.send().await.map_err(|e| msg(err_chain(&e)))?;
        let status = resp.status().as_u16();
        let form_ct = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.starts_with("application/x-www-form-urlencoded"));
        let body = resp.bytes().await.unwrap_or_default();
        let body = &body[..body.len().min(1 << 20)];
        let mut tr: HashMap<String, String> = HashMap::new();
        let mut ok = false;
        if let Ok(Value::Object(m)) = serde_json::from_slice::<Value>(body) {
            for (k, v) in m {
                let s = match v {
                    Value::String(s) => s,
                    Value::Number(n) => n.to_string(),
                    _ => continue,
                };
                tr.insert(k, s);
            }
            ok = true;
        } else if form_ct {
            // RFC 6749 §5.1 says JSON, but some ASes (GitHub) still default to a form
            tr = url::form_urlencoded::parse(body)
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect();
            ok = true;
        }
        let g = |k: &str| tr.get(k).cloned().unwrap_or_default();
        if !ok || status != 200 || g("access_token").is_empty() {
            // never the body
            return Err(msg(format!("token endpoint: HTTP {status} {}", g("error"))));
        }
        let mut t = Token {
            access_token: g("access_token"),
            refresh_token: Some(g("refresh_token")).filter(|r| !r.is_empty()),
            issuer: issuer.into(),
            client_id: client_id.into(),
            scopes: scopes.to_vec(),
            expires_at: None,
        };
        if !g("scope").is_empty() {
            t.scopes = g("scope").split_whitespace().map(String::from).collect();
        }
        if let Ok(n) = g("expires_in").parse::<f64>()
            && n > 0.0
        {
            t.expires_at = Some(SystemTime::now() + Duration::from_secs(n as u64));
        }
        Ok(t)
    }

    /// The challenge's `resource_metadata`, else the path-inserted well-known,
    /// else the root one. PRM `resource` must equal the server URI.
    async fn fetch_prm(&self, resource: &str, hinted: &str) -> Result<Obj, WireError> {
        let cands = if hinted.is_empty() {
            let u = url::Url::parse(resource).map_err(|e| msg(e.to_string()))?;
            let root = format!("{}{PRM_PATH}", origin(&u));
            let mut c = vec![];
            let p = u.path().trim_end_matches('/');
            if !p.is_empty() {
                c.push(format!("{root}{p}"));
            }
            c.push(root);
            c
        } else {
            vec![hinted.to_owned()]
        };
        let mut last = String::new();
        for c in cands {
            match get_json(&self.http, &c).await {
                Ok(m) => {
                    if m.get("resource").and_then(Value::as_str) != Some(resource) {
                        return Err(msg(format!(
                            "PRM resource {} does not match {resource}",
                            m.get("resource").cloned().unwrap_or(Value::Null)
                        )));
                    }
                    return Ok(m);
                }
                Err(e) => last = e.to_string(),
            }
        }
        Err(msg(format!("protected resource metadata: {last}")))
    }
}

const PRM_PATH: &str = "/.well-known/oauth-protected-resource";

fn origin(u: &url::Url) -> String {
    let mut s = format!("{}://{}", u.scheme(), u.host_str().unwrap_or_default());
    if let Some(p) = u.port() {
        s.push_str(&format!(":{p}"));
    }
    s
}

fn pkce_challenge(verifier: &str) -> String {
    use base64::Engine as _;
    use sha2::Digest as _;
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(sha2::Sha256::digest(verifier.as_bytes()))
}

/// RFC 8414 then OIDC, in the spec's order; the metadata issuer must match.
async fn discover_as(hc: &reqwest::Client, issuer: &str) -> Result<Obj, WireError> {
    let u = url::Url::parse(issuer).map_err(|e| msg(e.to_string()))?;
    let base = origin(&u);
    let p = u.path().trim_end_matches('/');
    let cands = if !p.is_empty() {
        vec![
            format!("{base}/.well-known/oauth-authorization-server{p}"),
            format!("{base}/.well-known/openid-configuration{p}"),
            format!("{base}{p}/.well-known/openid-configuration"),
        ]
    } else {
        vec![
            format!("{base}/.well-known/oauth-authorization-server"),
            format!("{base}/.well-known/openid-configuration"),
        ]
    };
    for c in cands {
        if let Ok(m) = get_json(hc, &c).await {
            if m.get("issuer").and_then(Value::as_str) != Some(issuer) {
                return Err(msg(format!(
                    "AS metadata issuer {} != {issuer}",
                    m.get("issuer").cloned().unwrap_or(Value::Null)
                )));
            }
            return Ok(m);
        }
    }
    Err(msg(format!(
        "no authorization server metadata for {issuer}"
    )))
}

async fn get_json(hc: &reqwest::Client, u: &str) -> Result<Obj, WireError> {
    let resp = hc.get(u).send().await.map_err(|e| msg(err_chain(&e)))?;
    if resp.status().as_u16() != 200 {
        return Err(msg(format!("GET {u}: HTTP {}", resp.status().as_u16())));
    }
    match resp.json::<Value>().await {
        Ok(Value::Object(m)) => Ok(m),
        Ok(_) => Err(msg(format!("GET {u}: not a JSON object"))),
        Err(e) => Err(msg(format!("GET {u}: {e}"))),
    }
}

/// Parse `Bearer k="v", k2=v2` (RFC 6750 §3).
pub(crate) fn parse_challenge(h: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let h = h.trim();
    let mut h: &str = h.strip_prefix("Bearer").unwrap_or(h).trim();
    while !h.is_empty() {
        let Some(eq) = h.find('=') else { break };
        let k = h[..eq].trim().to_lowercase();
        h = h[eq + 1..].trim();
        let v;
        if let Some(rest) = h.strip_prefix('"') {
            let b = rest.as_bytes();
            let mut end = 0;
            let mut val = Vec::new();
            while end < b.len() && b[end] != b'"' {
                if b[end] == b'\\' && end + 1 < b.len() {
                    end += 1;
                }
                val.push(b[end]);
                end += 1;
            }
            v = String::from_utf8_lossy(&val).into_owned();
            h = &rest[(end + 1).min(rest.len())..];
        } else {
            let end = h.find(',').unwrap_or(h.len());
            v = h[..end].trim().to_owned();
            h = &h[end..];
        }
        out.insert(k, v);
        h = h.trim().strip_prefix(',').unwrap_or(h.trim()).trim();
    }
    out
}

fn as_strings(v: Option<&Value>) -> Vec<String> {
    v.and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default()
}

fn encode_form<'a>(pairs: impl Iterator<Item = (&'a str, &'a str)>) -> String {
    let mut s = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in pairs {
        s.append_pair(k, v);
    }
    s.finish()
}

fn query_escape(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// UTC RFC 3339, seconds precision.
fn rfc3339(t: SystemTime) -> String {
    let secs = t.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
    let (days, rem) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}

/// `auto` mode: a one-shot listener on `http://127.0.0.1:<random>/callback`.
struct Loopback {
    listener: tokio::net::TcpListener,
    port: u16,
}

impl Loopback {
    async fn bind() -> Result<Loopback, WireError> {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(|e| msg(format!("loopback listener: {e}")))?;
        let port = listener
            .local_addr()
            .map_err(|e| msg(e.to_string()))?
            .port();
        Ok(Loopback { listener, port })
    }

    fn redirect_uri(&self) -> String {
        format!("http://127.0.0.1:{}/callback", self.port)
    }

    /// Open the URL, then wait (5 min) for the AS to redirect back.
    async fn wait(self, open: OpenUrlFn, url: String) -> Result<String, WireError> {
        let timeout = Duration::from_secs(300);
        let port = self.port;
        let accept = async move {
            loop {
                let Ok((mut s, _)) = self.listener.accept().await else {
                    continue;
                };
                let mut buf = Vec::new();
                let mut tmp = [0u8; 4096];
                while !buf.windows(4).any(|w| w == b"\r\n\r\n") && buf.len() < 64 * 1024 {
                    match s.read(&mut tmp).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => buf.extend_from_slice(&tmp[..n]),
                    }
                }
                let head = String::from_utf8_lossy(&buf).into_owned();
                let target = head
                    .lines()
                    .next()
                    .and_then(|l| l.split(' ').nth(1))
                    .unwrap_or("")
                    .to_owned();
                let ok = target == "/callback" || target.starts_with("/callback?");
                let (code, body) = if ok {
                    (
                        "200 OK",
                        "Authorization received. You can close this window.",
                    )
                } else {
                    ("404 Not Found", "404 page not found")
                };
                let _ = s
                    .write_all(
                        format!(
                            "HTTP/1.1 {code}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                            body.len()
                        )
                        .as_bytes(),
                    )
                    .await;
                let _ = s.shutdown().await;
                if ok {
                    return format!("http://127.0.0.1:{port}{target}");
                }
            }
        };
        tokio::pin!(accept);
        // the opener may block until done (tests follow redirects)
        let mut opened = tokio::spawn(open(url));
        tokio::select! {
            u = &mut accept => { opened.abort(); Ok(u) }
            r = &mut opened => {
                if let Ok(Err(e)) = r {
                    return Err(msg(format!("open authorization URL: {e}")));
                }
                tokio::select! {
                    u = &mut accept => Ok(u),
                    _ = tokio::time::sleep(timeout) => Err(msg("authorization timed out")),
                }
            }
            _ = tokio::time::sleep(timeout) => Err(msg("authorization timed out")),
        }
    }
}

async fn open_browser(u: String) -> Result<(), String> {
    let mut cmd = if cfg!(target_os = "macos") {
        std::process::Command::new("open")
    } else if cfg!(windows) {
        let mut c = std::process::Command::new("rundll32");
        c.arg("url.dll,FileProtocolHandler");
        c
    } else {
        std::process::Command::new("xdg-open")
    };
    cmd.arg(u).spawn().map(|_| ()).map_err(|e| e.to_string())
}
