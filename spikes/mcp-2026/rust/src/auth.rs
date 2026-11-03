//! Client-side OAuth for MCP 2026-07-28 (basic/authorization).
//! Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
//! -> client id (pre-registered | CIMD; never DCR) -> code + PKCE S256 + resource (RFC 8707)
//! -> iss (RFC 9207) + state check -> token -> retry with Bearer.

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::future::BoxFuture;
use serde_json::Value;
use sha2::Digest;

use crate::http::{Handler, HttpServer, Request, Response, handler};
use crate::{Error, Obj, b64url, rand_str, text};

/// A token bound to the issuer that minted it and the resource it is for. Never logged.
#[derive(Clone, Default, PartialEq)]
pub struct Token {
    pub access_token: String,
    pub refresh_token: String,
    pub expiry: Option<SystemTime>,
    pub scopes: Vec<String>,
    pub issuer: String,
    pub resource: String,
    pub client_id: String,
}

impl std::fmt::Debug for Token {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Token{{issuer={}, redacted}}", self.issuer)
    }
}

/// Persists tokens per resource. Implementations must keep them confidential.
pub trait TokenStore: Send + Sync {
    fn load(&self, resource: &str) -> Option<Token>;
    fn save(&self, resource: &str, t: Token);
    fn delete(&self, resource: &str);
}

#[derive(Default)]
pub struct MemoryTokenStore {
    m: Mutex<HashMap<String, Token>>,
}

impl MemoryTokenStore {
    pub fn new() -> Arc<MemoryTokenStore> {
        Arc::new(MemoryTokenStore::default())
    }
    /// Every stored token (tests).
    pub fn all(&self) -> Vec<Token> {
        self.m.lock().unwrap().values().cloned().collect()
    }
}

impl TokenStore for MemoryTokenStore {
    fn load(&self, r: &str) -> Option<Token> {
        self.m.lock().unwrap().get(r).cloned()
    }
    fn save(&self, r: &str, t: Token) {
        self.m.lock().unwrap().insert(r.to_string(), t);
    }
    fn delete(&self, r: &str) {
        self.m.lock().unwrap().remove(r);
    }
}

/// Mirrors toolnexus SPEC §10 Request (kind "authorization").
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Pending {
    pub id: String,
    pub kind: String,
    pub prompt: String,
    pub url: String,
    pub expires_at: String,
}

/// Opens an authorization URL (a browser, or a test stand-in). May block until done.
pub type Opener = Arc<dyn Fn(String) -> BoxFuture<'static, Result<(), String>> + Send + Sync>;

/// Wraps an async closure as an [`Opener`].
pub fn opener<F, Fut>(f: F) -> Opener
where
    F: Fn(String) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = Result<(), String>> + Send + 'static,
{
    Arc::new(move |u| Box::pin(f(u)))
}

/// How the user reaches the authorization URL and how the callback comes back.
#[derive(Clone)]
pub enum Authorizer {
    /// Opens a browser and listens on 127.0.0.1:<random>/callback (RFC 8252 §7.3).
    /// `open: None` = the system browser.
    Loopback { open: Option<Opener> },
    /// Never blocks: every authorization becomes `Error::Pending`; finish with `OAuth::resume`.
    Pause { redirect: String },
}

impl Default for Authorizer {
    fn default() -> Self {
        Authorizer::Loopback { open: None }
    }
}

impl Authorizer {
    pub fn loopback(open: Opener) -> Self {
        Authorizer::Loopback { open: Some(open) }
    }
    pub fn pause(redirect: impl Into<String>) -> Self {
        Authorizer::Pause {
            redirect: redirect.into(),
        }
    }
}

pub(crate) type RetryFn = Arc<dyn Fn() -> BoxFuture<'static, Result<Obj, Error>> + Send + Sync>;

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
    retry: RetryFn,
}

#[derive(Default)]
struct State {
    issuer: HashMap<String, String>, // resource -> discovered issuer (only then is a stored token sent)
    flows: HashMap<String, AuthFlow>,
}

/// The client-side auth engine; attach with `Client::with_auth`.
pub struct OAuth {
    /// Default: Loopback with the system browser.
    pub authorizer: Authorizer,
    /// Pre-registered client_id for any issuer not in `client_ids`.
    pub client_id: String,
    /// Optional: makes the pre-registered client confidential. Never logged.
    pub client_secret: String,
    /// "none" | "client_secret_basic" | "client_secret_post"; "" = basic if a secret is set, else none.
    pub token_auth_method: String,
    /// Drop the RFC 8707 `resource` from refresh_token requests (authorize + code exchange still
    /// send it). Entra rejects it on refresh (python-sdk #2578). Default: sent everywhere.
    pub omit_refresh_resource: bool,
    pub store: Arc<dyn TokenStore>,
    /// issuer -> pre-registered client_id (bound to that issuer).
    pub client_ids: HashMap<String, String>,
    /// CIMD client_id (HTTPS URL), used when the AS advertises support.
    pub client_metadata_url: String,
    /// For PRM / AS / token calls; None = the default client (timeout, trusts in-process dev TLS).
    pub http: Option<reqwest::Client>,
    /// Allow non-HTTPS AS endpoints (dev only; the spec requires HTTPS).
    pub allow_http: bool,
    /// Internal state (discovered issuers, paused flows, the login/refresh lock). Leave at default.
    pub state: AuthState,
}

/// Opaque per-OAuth runtime state; always `Default::default()`.
#[derive(Default)]
pub struct AuthState {
    st: Mutex<State>,
    auth_mu: tokio::sync::Mutex<()>, // one login / one refresh at a time
}

impl Default for OAuth {
    fn default() -> Self {
        OAuth {
            authorizer: Authorizer::default(),
            client_id: String::new(),
            client_secret: String::new(),
            token_auth_method: String::new(),
            omit_refresh_resource: false,
            store: Arc::new(MemoryTokenStore::default()),
            client_ids: HashMap::new(),
            client_metadata_url: String::new(),
            http: None,
            allow_http: false,
            state: AuthState::default(),
        }
    }
}

fn expired(t: &Token) -> bool {
    t.expiry
        .is_some_and(|e| SystemTime::now() > e - Duration::from_secs(10))
}

impl OAuth {
    fn hc(&self) -> reqwest::Client {
        self.http
            .clone()
            .unwrap_or_else(crate::http::default_client)
    }

    /// The token to send for resource, refreshing if expired. "" = send none.
    pub(crate) async fn bearer(&self, resource: &str) -> String {
        let iss = self
            .state
            .st
            .lock()
            .unwrap()
            .issuer
            .get(resource)
            .cloned()
            .unwrap_or_default();
        let Some(t) = self.store.load(resource) else {
            return String::new();
        };
        if iss.is_empty() || t.issuer != iss {
            return String::new(); // only tokens from this server's AS (spec: Token Handling)
        }
        if expired(&t) {
            let _g = self.state.auth_mu.lock().await; // one refresh for N callers (refresh tokens rotate)
            let Some(t) = self.store.load(resource) else {
                return String::new();
            };
            if expired(&t) && self.refresh(&t).await.is_err() {
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

    async fn refresh(&self, t: &Token) -> Result<(), Error> {
        if t.refresh_token.is_empty() {
            return Err(Error::msg("no refresh token"));
        }
        let md = discover_as(&self.hc(), &t.issuer).await?;
        let ep = md
            .get("token_endpoint")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let mut form = vec![
            ("grant_type", "refresh_token".to_string()),
            ("refresh_token", t.refresh_token.clone()),
            ("client_id", t.client_id.clone()),
        ];
        if !self.omit_refresh_resource {
            form.push(("resource", t.resource.clone()));
        }
        match self
            .token_request(&ep, form, &t.issuer, &t.resource, &t.client_id, &t.scopes)
            .await
        {
            Ok(mut nt) => {
                if nt.refresh_token.is_empty() {
                    nt.refresh_token = t.refresh_token.clone();
                }
                self.store.save(&t.resource, nt);
                Ok(())
            }
            Err(e) => {
                self.store.delete(&t.resource);
                Err(e)
            }
        }
    }

    /// Handles a 401/403. Ok = retry the request now; Err(Pending) = paused.
    /// `sent` is the access token the failed request carried ("" = none).
    pub(crate) async fn challenge(
        &self,
        resource: &str,
        status: u16,
        www: &str,
        sent: &str,
        retry: RetryFn,
    ) -> Result<(), Error> {
        let params = parse_challenge(www);
        let p = |k: &str| params.get(k).cloned().unwrap_or_default();
        let _g = self.state.auth_mu.lock().await; // N concurrent 401s -> one authorization
        let mut cur = self.store.load(resource);
        let known = self
            .state
            .st
            .lock()
            .unwrap()
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
            return Ok(()); // someone else refreshed/authorized while we waited
        }
        if status == 401
            && let Some(c) = cur.clone()
            && !c.refresh_token.is_empty()
            && p("error") == "invalid_token"
        {
            if self.refresh(&c).await.is_ok() {
                return Ok(());
            }
            cur = None;
        }
        if status == 403 && p("error") != "insufficient_scope" {
            return Err(Error::msg(format!("forbidden: {}", p("error"))));
        }
        let prm = self.fetch_prm(resource, &p("resource_metadata")).await?;
        let issuer = prm
            .get("authorization_servers")
            .and_then(Value::as_array)
            .and_then(|a| a.first())
            .and_then(Value::as_str) // RFC 9728 §7.6: client's choice; we take the first
            .ok_or_else(|| Error::msg("PRM lists no authorization_servers"))?
            .to_string();
        if !self.allow_http && !issuer.starts_with("https://") {
            return Err(Error::msg("authorization server must use https"));
        }
        let md = discover_as(&self.hc(), &issuer).await?;
        if !as_strings(md.get("code_challenge_methods_supported"))
            .iter()
            .any(|m| m == "S256")
        {
            return Err(Error::msg(
                "authorization server does not advertise PKCE S256; refusing",
            ));
        }
        self.state
            .st
            .lock()
            .unwrap()
            .issuer
            .insert(resource.to_string(), issuer.clone());
        if status == 401
            && cur.as_ref().is_some_and(|c| c.issuer == issuer)
            && p("error").is_empty()
        {
            return Ok(()); // first request after restart: issuer now known, the stored token may be sent
        }
        // Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
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
        let mut client_id = self.client_ids.get(&issuer).cloned().unwrap_or_default();
        if client_id.is_empty() {
            client_id = self.client_id.clone();
        }
        if client_id.is_empty() && !self.client_metadata_url.is_empty() && cimd {
            client_id = self.client_metadata_url.clone();
        }
        if client_id.is_empty() {
            // no DCR, ever: the user registers an app once and configures its id
            let why = if cimd {
                "OAuth.client_metadata_url is not set"
            } else {
                "it does not support Client ID Metadata Documents"
            };
            return Err(Error::msg(format!(
                "no client identity for {issuer}: {why} and no client id is configured — register an app with {issuer} and set OAuth.client_id (dynamic client registration is not supported)"
            )));
        }
        let auth_ep = md
            .get("authorization_endpoint")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let token_ep = md
            .get("token_endpoint")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let session = Session::begin(&self.authorizer).await?;
        let f = AuthFlow {
            resource: resource.to_string(),
            issuer: issuer.clone(),
            client_id: client_id.clone(),
            redirect: session.redirect_uri(),
            verifier: rand_str(32),
            state: rand_str(16),
            token_endpoint: token_ep,
            iss_supported: md.get("authorization_response_iss_parameter_supported")
                == Some(&Value::Bool(true)),
            scopes: scopes.clone(),
            expires: SystemTime::now() + Duration::from_secs(600),
            retry,
        };
        let mut q = BTreeMap::new();
        q.insert("response_type", "code".to_string());
        q.insert("client_id", client_id);
        q.insert("redirect_uri", f.redirect.clone());
        q.insert("state", f.state.clone());
        q.insert(
            "code_challenge",
            b64url(&sha2::Sha256::digest(f.verifier.as_bytes())),
        );
        q.insert("code_challenge_method", "S256".into());
        q.insert("resource", resource.to_string());
        if !scopes.is_empty() {
            q.insert("scope", scopes.join(" "));
        }
        let pending = Pending {
            id: format!("auth-{}", rand_str(12)),
            kind: "authorization".into(),
            url: format!(
                "{auth_ep}?{}",
                encode_form(q.iter().map(|(k, v)| (*k, v.as_str())))
            ),
            prompt: format!("Authorize access to {resource}"),
            expires_at: rfc3339(f.expires),
        };
        match session {
            Session::Pause(_) => {
                let mut st = self.state.st.lock().unwrap();
                let now = SystemTime::now();
                st.flows.retain(|_, old| now <= old.expires); // drop abandoned pendings
                st.flows.insert(pending.id.clone(), f);
                Err(Error::Pending(pending))
            }
            Session::Loopback(l) => {
                let cb = l.wait(&pending).await;
                l.server.close();
                self.complete(&f, &cb?).await
            }
        }
    }

    pub(crate) fn set_retry(&self, id: &str, f: RetryFn) {
        if let Some(fl) = self.state.st.lock().unwrap().flows.get_mut(id) {
            fl.retry = f;
        }
    }

    /// Finishes a paused authorization and re-runs the original operation, returning its result.
    pub async fn resume(&self, id: &str, callback_url: &str) -> Result<Obj, Error> {
        let f = self.state.st.lock().unwrap().flows.remove(id);
        let Some(f) = f else {
            return Err(Error::msg(format!("unknown pending authorization {id:?}")));
        };
        if SystemTime::now() > f.expires {
            return Err(Error::msg("pending authorization expired"));
        }
        self.complete(&f, callback_url).await?;
        (f.retry)().await
    }

    /// `resume` for web apps: the pending is found by the callback's `state`.
    pub async fn resume_callback(&self, callback_url: &str) -> Result<Obj, Error> {
        let u = url::Url::parse(callback_url).map_err(|e| Error::msg(e.to_string()))?;
        let st = u
            .query_pairs()
            .find(|(k, _)| k == "state")
            .map(|(_, v)| v.into_owned())
            .unwrap_or_default();
        let id = {
            let s = self.state.st.lock().unwrap();
            s.flows
                .iter()
                .find(|(_, f)| !st.is_empty() && f.state == st)
                .map(|(k, _)| k.clone())
                .unwrap_or_default()
        };
        self.resume(&id, callback_url).await
    }

    /// Test hook: corrupt a paused flow's PKCE verifier (the AS must then reject the exchange).
    #[doc(hidden)]
    pub fn tamper_verifier(&self, id: &str) {
        if let Some(f) = self.state.st.lock().unwrap().flows.get_mut(id) {
            f.verifier = "not-the-verifier".into();
        }
    }

    /// A handler serving one tool call to a browser user, pausing for authorization when needed:
    /// `call` runs; if it is pending, the browser is redirected to the AS; when the AS redirects
    /// back to this same handler (its URL is the `Authorizer::Pause` redirect), the original call
    /// is resumed and its text result written. Errors become 502.
    pub fn web_handler<F, Fut>(self: &Arc<Self>, call: F) -> Handler
    where
        F: Fn(Request) -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = Result<Obj, Error>> + Send + 'static,
    {
        let o = self.clone();
        let call = Arc::new(call);
        handler(move |r: Request| {
            let (o, call) = (o.clone(), call.clone());
            async move {
                let res = if r.query_param("state").is_some_and(|s| !s.is_empty())
                    && (r.has_query("code") || r.has_query("error"))
                {
                    o.resume_callback(&format!("{}{}", r.origin(), r.uri()))
                        .await
                } else {
                    call(r).await
                };
                match res {
                    Err(Error::Pending(p)) => Response::redirect(&p.url),
                    Err(e) => Response::text(502, &e.to_string()),
                    Ok(v) => Response::text(200, &text(&v)),
                }
            }
        })
    }

    async fn complete(&self, f: &AuthFlow, callback_url: &str) -> Result<(), Error> {
        let u = url::Url::parse(callback_url).map_err(|e| Error::msg(e.to_string()))?;
        let q: Vec<(String, String)> = u
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        let get = |k: &str| q.iter().find(|(a, _)| a == k).map(|(_, v)| v.clone());
        // RFC 9207 §2.4 (spec: Authorization Response Validation) — before anything else, errors included.
        match get("iss") {
            Some(iss) if iss != f.issuer => {
                // simple string comparison, no normalization
                return Err(Error::msg(
                    "authorization response iss mismatch (possible mix-up attack)",
                ));
            }
            None if f.iss_supported => {
                return Err(Error::msg("authorization response missing iss"));
            }
            _ => {}
        }
        if get("state").unwrap_or_default() != f.state {
            return Err(Error::msg("authorization response state mismatch"));
        }
        if let Some(e) = get("error").filter(|e| !e.is_empty()) {
            return Err(Error::msg(format!("authorization denied: {e}")));
        }
        let form = vec![
            ("grant_type", "authorization_code".to_string()),
            ("code", get("code").unwrap_or_default()),
            ("redirect_uri", f.redirect.clone()),
            ("client_id", f.client_id.clone()),
            ("code_verifier", f.verifier.clone()),
            ("resource", f.resource.clone()),
        ];
        let t = self
            .token_request(
                &f.token_endpoint,
                form,
                &f.issuer,
                &f.resource,
                &f.client_id,
                &f.scopes,
            )
            .await?;
        self.store.save(&f.resource, t);
        Ok(())
    }

    async fn token_request(
        &self,
        ep: &str,
        mut form: Vec<(&str, String)>,
        issuer: &str,
        resource: &str,
        client_id: &str,
        scopes: &[String],
    ) -> Result<Token, Error> {
        let method = if client_id == self.client_metadata_url || self.client_secret.is_empty() {
            "none" // CIMD clients and secretless pre-registered clients are public
        } else if self.token_auth_method.is_empty() {
            "client_secret_basic"
        } else {
            self.token_auth_method.as_str()
        };
        if method == "client_secret_post" {
            form.push(("client_secret", self.client_secret.clone()));
        }
        if method == "client_secret_basic" {
            form.retain(|(k, _)| *k != "client_id"); // RFC 6749 §2.3.1: one method only
        }
        let mut sorted: Vec<(&str, String)> = form;
        sorted.sort_by(|a, b| a.0.cmp(b.0));
        let mut req = self
            .hc()
            .post(ep)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json") // GitHub answers form-encoded without it
            .body(encode_form(sorted.iter().map(|(k, v)| (*k, v.as_str()))));
        if method == "client_secret_basic" {
            req = req.basic_auth(
                query_escape(client_id),
                Some(query_escape(&self.client_secret)),
            );
        }
        let resp = req
            .send()
            .await
            .map_err(|e| Error::msg(crate::client::err_chain(&e)))?;
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
            // RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
            tr = url::form_urlencoded::parse(body)
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect();
            ok = true;
        }
        let g = |k: &str| tr.get(k).cloned().unwrap_or_default();
        if !ok || status != 200 || g("access_token").is_empty() {
            // never include the body
            return Err(Error::msg(format!(
                "token endpoint: HTTP {status} {}",
                g("error")
            )));
        }
        let mut t = Token {
            access_token: g("access_token"),
            refresh_token: g("refresh_token"),
            issuer: issuer.into(),
            resource: resource.into(),
            client_id: client_id.into(),
            scopes: scopes.to_vec(),
            expiry: None,
        };
        if !g("scope").is_empty() {
            t.scopes = g("scope").split_whitespace().map(String::from).collect();
        }
        if let Ok(n) = g("expires_in").parse::<f64>()
            && n > 0.0
        {
            t.expiry = Some(SystemTime::now() + Duration::from_secs(n as u64));
        }
        Ok(t)
    }

    /// Header URL if present, else path-inserted well-known, else root (spec: PRM Discovery).
    pub async fn fetch_prm(&self, resource: &str, hinted: &str) -> Result<Obj, Error> {
        let cands = if hinted.is_empty() {
            let u = url::Url::parse(resource).map_err(|e| Error::msg(e.to_string()))?;
            let root = format!("{}{}", origin(&u), PRM_PATH);
            let mut c = vec![];
            let p = u.path().trim_end_matches('/');
            if !p.is_empty() {
                c.push(format!("{root}{p}"));
            }
            c.push(root);
            c
        } else {
            vec![hinted.to_string()]
        };
        let mut last = String::new();
        for c in cands {
            match get_json(&self.hc(), &c).await {
                Ok(m) => {
                    if m.get("resource").and_then(Value::as_str) != Some(resource) {
                        // RFC 9728 §3.3
                        return Err(Error::msg(format!(
                            "PRM resource {} does not match {resource}",
                            m.get("resource").cloned().unwrap_or(Value::Null)
                        )));
                    }
                    return Ok(m);
                }
                Err(e) => last = e.to_string(),
            }
        }
        Err(Error::msg(format!("protected resource metadata: {last}")))
    }
}

pub(crate) const PRM_PATH: &str = "/.well-known/oauth-protected-resource";

fn origin(u: &url::Url) -> String {
    let mut s = format!("{}://{}", u.scheme(), u.host_str().unwrap_or_default());
    if let Some(p) = u.port() {
        s.push_str(&format!(":{p}"));
    }
    s
}

/// RFC 8414 then OIDC endpoints in the spec's order; validates issuer equality.
pub(crate) async fn discover_as(hc: &reqwest::Client, issuer: &str) -> Result<Obj, Error> {
    let u = url::Url::parse(issuer).map_err(|e| Error::msg(e.to_string()))?;
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
                return Err(Error::msg(format!(
                    "AS metadata issuer {} != {issuer}",
                    m.get("issuer").cloned().unwrap_or(Value::Null)
                )));
            }
            return Ok(m);
        }
    }
    Err(Error::msg(format!(
        "no authorization server metadata for {issuer}"
    )))
}

pub(crate) async fn get_json(hc: &reqwest::Client, u: &str) -> Result<Obj, Error> {
    let resp = hc
        .get(u)
        .send()
        .await
        .map_err(|e| Error::msg(crate::client::err_chain(&e)))?;
    if resp.status().as_u16() != 200 {
        return Err(Error::msg(format!(
            "GET {u}: HTTP {}",
            resp.status().as_u16()
        )));
    }
    match resp.json::<Value>().await {
        Ok(Value::Object(m)) => Ok(m),
        Ok(_) => Err(Error::msg(format!("GET {u}: not a JSON object"))),
        Err(e) => Err(Error::msg(format!("GET {u}: {e}"))),
    }
}

/// Parses `Bearer k="v", k2=v2` (RFC 6750 §3 / RFC 9110 auth-param).
pub fn parse_challenge(h: &str) -> HashMap<String, String> {
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
            v = h[..end].trim().to_string();
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

pub(crate) fn encode_form<'a>(pairs: impl Iterator<Item = (&'a str, &'a str)>) -> String {
    let mut s = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in pairs {
        s.append_pair(k, v);
    }
    s.finish()
}

fn query_escape(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// UTC RFC 3339 (seconds precision), no external time crate.
fn rfc3339(t: SystemTime) -> String {
    let secs = t.duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
    let (days, rem) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    // civil_from_days (H. Hinnant)
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

// ---- authorizer sessions ----

enum Session {
    Pause(String),
    Loopback(LoopbackSession),
}

struct LoopbackSession {
    server: HttpServer,
    rx: tokio::sync::Mutex<tokio::sync::mpsc::Receiver<String>>,
    open: Opener,
}

impl Session {
    async fn begin(a: &Authorizer) -> Result<Session, Error> {
        match a {
            Authorizer::Pause { redirect } => Ok(Session::Pause(redirect.clone())),
            Authorizer::Loopback { open } => {
                let (tx, rx) = tokio::sync::mpsc::channel::<String>(1);
                let h = handler(move |r: Request| {
                    let tx = tx.clone();
                    async move {
                        if r.path != "/callback" {
                            return Response::text(404, "404 page not found");
                        }
                        let _ = tx.try_send(format!("http://{}{}", r.host, r.uri()));
                        Response::text(200, "Authorization received. You can close this window.")
                    }
                });
                let server = HttpServer::bind("127.0.0.1:0", h)
                    .await
                    .map_err(|e| Error::msg(e.to_string()))?;
                let open = open.clone().unwrap_or_else(|| opener(open_browser));
                Ok(Session::Loopback(LoopbackSession {
                    server,
                    rx: tokio::sync::Mutex::new(rx),
                    open,
                }))
            }
        }
    }
    fn redirect_uri(&self) -> String {
        match self {
            Session::Pause(r) => r.clone(),
            Session::Loopback(l) => format!("http://{}/callback", l.server.addr),
        }
    }
}

impl LoopbackSession {
    async fn wait(&self, p: &Pending) -> Result<String, Error> {
        let mut rx = self.rx.lock().await;
        let timeout = Duration::from_secs(300);
        // the opener may block (tests follow redirects synchronously)
        let mut opened = tokio::spawn((self.open)(p.url.clone()));
        tokio::select! {
            Some(u) = rx.recv() => { opened.abort(); Ok(u) }
            r = &mut opened => {
                // a failed opener must not leave us waiting 5 minutes for a callback that cannot come
                if let Ok(Err(e)) = r {
                    return Err(Error::msg(format!("open authorization URL: {e}")));
                }
                tokio::select! {
                    Some(u) = rx.recv() => Ok(u),
                    _ = tokio::time::sleep(timeout) => Err(Error::msg("authorization timed out")),
                }
            }
            _ = tokio::time::sleep(timeout) => Err(Error::msg("authorization timed out")),
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
