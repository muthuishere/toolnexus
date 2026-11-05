//! MCP server authorization (SPEC §2A.2, §7C.2): an [`McpHandler`](super::McpHandler)
//! as an OAuth 2.1 protected resource. Port of `golang/mcp_serverauth.go`.
//!
//! [`McpServerAuth`] is a plain helper that produces the handler's
//! `authenticate` hook and the RFC 9728 metadata handler; the host mounts
//! both. Identity, tenants and token storage stay the host's.
//!
//! ```ignore
//! let auth = McpServerAuth { issuer: "https://login.example".into(),
//!     scopes: [("*".into(), vec!["mcp".into()])].into(), ..Default::default() };
//! let mcp = tk.mcp_handler(McpHandlerConfig { protocol_versions: vec!["2026-07-28".into()],
//!     authenticate: Some(auth.authenticate()), ..Default::default() });
//! let prm = auth.metadata(); // GET /.well-known/oauth-protected-resource[/mcp]
//! ```

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use hyper::body::Body;
use hyper::{Method, Request, Response, StatusCode};
use serde_json::{Map, Value, json};

use super::v2026::decode_header_value;
use super::{
    AuthenticateFn, BoxFuture, McpAuthError, McpAuthFailure, McpBody, McpIdentity,
    McpRequestContext, json_response, plain_error,
};

const PRM_PATH: &str = "/.well-known/oauth-protected-resource";

/// The verified caller. [`McpServerAuth::authenticate`] makes it the identity:
/// `rc.identity_as::<McpPrincipal>()`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpPrincipal {
    pub subject: String,
    pub scopes: Vec<String>,
    pub claims: Map<String, Value>,
}

/// `verify(ctx, token, audience)`: replaces the default RS256/JWKS verifier
/// (opaque tokens, introspection). Any error ⇒ 401 `invalid_token`.
pub type VerifyTokenFn = Arc<
    dyn Fn(Arc<McpRequestContext>, String, String) -> BoxFuture<Result<McpPrincipal, String>>
        + Send
        + Sync,
>;

/// Protects an [`McpHandler`](super::McpHandler) with bearer tokens.
#[derive(Clone, Default)]
pub struct McpServerAuth {
    /// The authorization server's issuer identifier (advertised in the
    /// protected resource metadata, and the JWT `iss` the default verifier
    /// requires).
    pub issuer: String,
    /// The canonical resource URI (PRM `resource`, the JWT `aud`). "" ⇒
    /// derived per request: `<scheme>://<Host><resource_path>` (scheme from
    /// the request URI, else `http` — set `audience` behind TLS).
    pub audience: String,
    /// Where the handler is mounted. Default `/mcp`.
    pub resource_path: String,
    /// `"*"` is required for every request (and advertised as
    /// `scopes_supported`); a tool name adds scopes for that `tools/call`.
    /// Exact match, no hierarchy.
    pub scopes: HashMap<String, Vec<String>>,
    pub verify: Option<VerifyTokenFn>,
    /// Fetches AS metadata and JWKS. `None` ⇒ a 10 s client.
    pub http_client: Option<reqwest::Client>,
}

impl std::fmt::Debug for McpServerAuth {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpServerAuth")
            .field("issuer", &self.issuer)
            .field("audience", &self.audience)
            .field("resource_path", &self.resource_path)
            .field("scopes", &self.scopes)
            .finish_non_exhaustive()
    }
}

/// An RSA public key as (n, e), big-endian.
type RsaParts = (Vec<u8>, Vec<u8>);

#[derive(Default)]
struct KeyCache {
    /// kid ⇒ key; `None` until the first fetch.
    keys: Option<HashMap<String, RsaParts>>,
    fetched: Option<Instant>,
}

struct AuthState {
    cfg: McpServerAuth,
    cache: tokio::sync::Mutex<KeyCache>,
    client: reqwest::Client,
}

pub(super) fn default_client() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap_or_default()
}

/// (scheme, authority, path) of an absolute URL, without a URL crate.
pub(super) fn split_url(u: &str) -> Option<(String, String, String)> {
    let (scheme, rest) = u.split_once("://")?;
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = &rest[..end];
    let tail = &rest[end..];
    let path = &tail[..tail.find(['?', '#']).unwrap_or(tail.len())];
    (!host.is_empty()).then(|| (scheme.to_owned(), host.to_owned(), path.to_owned()))
}

fn origin(rc: &McpRequestContext) -> String {
    format!("{}://{}", rc.uri.scheme_str().unwrap_or("http"), rc.host())
}

impl McpServerAuth {
    fn path(&self) -> &str {
        if self.resource_path.is_empty() {
            "/mcp"
        } else {
            &self.resource_path
        }
    }

    fn audience_for(&self, rc: &McpRequestContext) -> String {
        if !self.audience.is_empty() {
            return self.audience.clone();
        }
        format!("{}{}", origin(rc), self.path())
    }

    /// The RFC 9728 metadata URL the 401 challenge names.
    pub fn metadata_url(&self, rc: &McpRequestContext) -> String {
        if let Some((scheme, host, path)) = split_url(&self.audience) {
            return format!("{scheme}://{host}{PRM_PATH}{path}");
        }
        format!("{}{PRM_PATH}{}", origin(rc), self.path())
    }

    fn challenge(
        &self,
        rc: &McpRequestContext,
        status: u16,
        scopes: &[String],
        err: &str,
    ) -> McpAuthFailure {
        let mut v = format!("Bearer resource_metadata=\"{}\"", self.metadata_url(rc));
        if !scopes.is_empty() {
            v.push_str(&format!(", scope=\"{}\"", scopes.join(" ")));
        }
        if !err.is_empty() {
            v.push_str(&format!(", error=\"{err}\""));
        }
        Box::new(McpAuthError {
            status,
            challenge: v,
            reason: err.to_owned(),
        })
    }

    /// The `authenticate` hook for [`McpHandlerConfig`](super::McpHandlerConfig).
    /// Missing token ⇒ 401; invalid / expired / wrong audience ⇒ 401
    /// `error="invalid_token"`; a missing scope ⇒ 403
    /// `error="insufficient_scope"` naming the full needed set. Per-tool scopes
    /// come from the validated `Mcp-Method` / `Mcp-Name` headers, so they apply
    /// on the 2026-07-28 wire; older wires check `scopes["*"]` only. The token
    /// and the failure reason are never echoed. One JWKS cache is shared by
    /// every call of the returned hook.
    pub fn authenticate(&self) -> AuthenticateFn {
        let st = Arc::new(AuthState {
            client: self.http_client.clone().unwrap_or_else(default_client),
            cfg: self.clone(),
            cache: tokio::sync::Mutex::default(),
        });
        Arc::new(move |rc| {
            let st = st.clone();
            Box::pin(async move {
                st.authenticate(rc)
                    .await
                    .map(|p| Arc::new(p) as McpIdentity)
            })
        })
    }

    /// The RFC 9728 protected resource metadata handler: `GET
    /// /.well-known/oauth-protected-resource` and
    /// `…/oauth-protected-resource<resource_path>`; anything else 404.
    pub fn metadata(&self) -> McpAuthMetadata {
        McpAuthMetadata { auth: self.clone() }
    }
}

impl AuthState {
    async fn authenticate(
        &self,
        rc: Arc<McpRequestContext>,
    ) -> Result<McpPrincipal, McpAuthFailure> {
        let a = &self.cfg;
        let star: Vec<String> = a.scopes.get("*").cloned().unwrap_or_default();
        let tok = rc
            .header("authorization")
            .strip_prefix("Bearer ")
            .unwrap_or("")
            .to_owned();
        if tok.trim().is_empty() {
            return Err(a.challenge(&rc, 401, &star, ""));
        }
        let aud = a.audience_for(&rc);
        let verified = match &a.verify {
            Some(v) => v(rc.clone(), tok, aud).await,
            None => self.verify_jwt(&tok, &aud).await,
        };
        let Ok(p) = verified else {
            return Err(a.challenge(&rc, 401, &star, "invalid_token"));
        };
        let mut need = star;
        if rc.header("mcp-method") == "tools/call" {
            if let Some(name) = decode_header_value(rc.header("mcp-name")) {
                for s in a.scopes.get(&name).into_iter().flatten() {
                    if !need.contains(s) {
                        need.push(s.clone());
                    }
                }
            }
        }
        if need.iter().any(|s| !p.scopes.contains(s)) {
            return Err(a.challenge(&rc, 403, &need, "insufficient_scope"));
        }
        Ok(p)
    }

    async fn verify_jwt(&self, tok: &str, aud: &str) -> Result<McpPrincipal, String> {
        let parts: Vec<&str> = tok.split('.').collect();
        if parts.len() != 3 {
            return Err("malformed jwt".into());
        }
        let seg = |s: &str| -> Option<Map<String, Value>> {
            let b = URL_SAFE_NO_PAD.decode(s).ok()?;
            serde_json::from_slice::<Value>(&b)
                .ok()?
                .as_object()
                .cloned()
        };
        let (Some(hdr), Some(claims)) = (seg(parts[0]), seg(parts[1])) else {
            return Err("bad jwt header or claims".into());
        };
        if hdr.get("alg").and_then(Value::as_str) != Some("RS256") {
            return Err("bad jwt header or claims".into());
        }
        let kid = hdr.get("kid").and_then(Value::as_str).unwrap_or("");
        let (n, e) = self.key(kid).await?;
        let sig = URL_SAFE_NO_PAD
            .decode(parts[2])
            .map_err(|e| e.to_string())?;
        let signed = format!("{}.{}", parts[0], parts[1]);
        ring::signature::RsaPublicKeyComponents { n: &n, e: &e }
            .verify(
                &ring::signature::RSA_PKCS1_2048_8192_SHA256,
                signed.as_bytes(),
                &sig,
            )
            .map_err(|_| "bad signature".to_owned())?;
        if claims.get("iss").and_then(Value::as_str) != Some(self.cfg.issuer.as_str()) {
            return Err("wrong issuer".into());
        }
        let aud_ok = match claims.get("aud") {
            Some(Value::String(s)) => s == aud,
            Some(Value::Array(a)) => a.iter().any(|x| x.as_str() == Some(aud)),
            _ => false,
        };
        if !aud_ok {
            return Err("wrong audience".into());
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let exp = claims.get("exp").and_then(Value::as_f64).unwrap_or(0.0) as i64;
        if exp <= now {
            return Err("expired".into());
        }
        Ok(McpPrincipal {
            subject: claims
                .get("sub")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned(),
            scopes: claims
                .get("scope")
                .and_then(Value::as_str)
                .unwrap_or("")
                .split_whitespace()
                .map(str::to_owned)
                .collect(),
            claims,
        })
    }

    /// The JWKS key for `kid`; an unknown kid refetches at most every 30 s.
    async fn key(&self, kid: &str) -> Result<RsaParts, String> {
        let mut c = self.cache.lock().await;
        if let Some(k) = c.keys.as_ref().and_then(|m| m.get(kid)) {
            return Ok(k.clone());
        }
        if c.keys.is_some()
            && c.fetched
                .is_some_and(|t| t.elapsed() < Duration::from_secs(30))
        {
            return Err("unknown kid".into());
        }
        let md = discover_as(&self.client, &self.cfg.issuer).await?;
        let jwks_uri = md.get("jwks_uri").and_then(Value::as_str).unwrap_or("");
        let jwks = get_json(&self.client, jwks_uri).await?;
        let mut keys = HashMap::new();
        for k in jwks
            .get("keys")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let s = |f: &str| k.get(f).and_then(Value::as_str).unwrap_or("").to_owned();
            if s("kty") != "RSA" {
                continue;
            }
            if let (Ok(n), Ok(e)) = (
                URL_SAFE_NO_PAD.decode(s("n")),
                URL_SAFE_NO_PAD.decode(s("e")),
            ) {
                let strip = |v: Vec<u8>| v.into_iter().skip_while(|b| *b == 0).collect::<Vec<u8>>();
                keys.insert(s("kid"), (strip(n), strip(e)));
            }
        }
        c.fetched = Some(Instant::now());
        let found = keys.get(kid).cloned();
        c.keys = Some(keys);
        found.ok_or_else(|| "unknown kid".into())
    }
}

/// AS metadata in the SPEC §2A.1 step 3 order, with a matching `issuer`.
async fn discover_as(hc: &reqwest::Client, issuer: &str) -> Result<Map<String, Value>, String> {
    let (scheme, host, path) = split_url(issuer).ok_or("bad issuer")?;
    let origin = format!("{scheme}://{host}");
    let path = path.trim_end_matches('/');
    let mut cands = vec![
        format!("{origin}/.well-known/oauth-authorization-server{path}"),
        format!("{origin}/.well-known/openid-configuration{path}"),
    ];
    if !path.is_empty() {
        cands.push(format!("{origin}{path}/.well-known/openid-configuration"));
    }
    for c in cands {
        if let Ok(Value::Object(md)) = get_json(hc, &c).await {
            if md.get("issuer").and_then(Value::as_str) == Some(issuer) {
                return Ok(md);
            }
        }
    }
    Err(format!(
        "authorization server metadata not found for {issuer}"
    ))
}

pub(super) async fn get_json(hc: &reqwest::Client, u: &str) -> Result<Value, String> {
    let resp = hc
        .get(u)
        .header("accept", "application/json")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if resp.status() != reqwest::StatusCode::OK {
        return Err(format!("GET {u}: HTTP {}", resp.status().as_u16()));
    }
    resp.json::<Value>().await.map_err(|e| e.to_string())
}

/// The RFC 9728 metadata handler returned by [`McpServerAuth::metadata`].
/// Mount it with hyper (`hyper::service::Service`) or call
/// [`McpAuthMetadata::handle`].
#[derive(Clone, Debug)]
pub struct McpAuthMetadata {
    auth: McpServerAuth,
}

impl McpAuthMetadata {
    /// Serve one request: the PRM document for GET on either well-known path.
    pub async fn handle<B>(&self, req: Request<B>) -> Response<McpBody>
    where
        B: Body + Send + 'static,
    {
        let (parts, _) = req.into_parts();
        let a = &self.auth;
        let p = parts.uri.path();
        if parts.method != Method::GET || (p != PRM_PATH && p != format!("{PRM_PATH}{}", a.path()))
        {
            return plain_error(StatusCode::NOT_FOUND, "404 page not found");
        }
        let rc = McpRequestContext {
            method: parts.method.clone(),
            uri: parts.uri.clone(),
            headers: parts.headers.clone(),
            identity: None,
            session_id: String::new(),
            meta: None,
        };
        let scopes = a.scopes.get("*").cloned().unwrap_or_default();
        json_response(
            StatusCode::OK,
            &json!({"resource": a.audience_for(&rc), "authorization_servers": [a.issuer],
                    "scopes_supported": scopes, "bearer_methods_supported": ["header"]}),
        )
    }
}

impl<B> hyper::service::Service<Request<B>> for McpAuthMetadata
where
    B: Body + Send + 'static,
{
    type Response = Response<McpBody>;
    type Error = std::convert::Infallible;
    type Future = BoxFuture<Result<Response<McpBody>, std::convert::Infallible>>;

    fn call(&self, req: Request<B>) -> Self::Future {
        let me = self.clone();
        Box::pin(async move { Ok(me.handle(req).await) })
    }
}
