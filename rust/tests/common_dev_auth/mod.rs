//! McpDevAuthServer — DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.
//! Port of `golang/internal/devauth/devauth.go` (SPEC §7C.2). Test tooling, not
//! part of the library: bring your own authorization server in production.
//!
//! An in-process OAuth 2.1 authorization server that auto-approves every
//! request as `dev-user` (no login, no consent): RFC 8414 metadata, JWKS,
//! authorization code + PKCE S256, resource indicators (RFC 8707), `iss` in
//! the response (RFC 9207), refresh-token rotation, pre-registered clients and
//! Client ID Metadata Documents. No dynamic client registration. Plain HTTP on
//! 127.0.0.1, so a toolnexus client needs `allow_http` against it.
//!
//! ```ignore
//! let dev = start_mcp_dev_auth_server(McpDevAuthOptions {
//!     clients: [("my-app".into(), vec!["http://127.0.0.1/callback".into()])].into(),
//!     ..Default::default() }).await?;
//! let auth = McpServerAuth { issuer: dev.issuer.clone(), ..Default::default() };
//! ```

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::{Bytes, Incoming};
use hyper::header::HeaderValue;
use hyper::{Request, Response, StatusCode};
use ring::rand::SystemRandom;
use ring::signature::RsaKeyPair;
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use toolnexus::mcp_handler::McpBody;

// Private copies of the library's crate-internal helpers this server uses.
fn default_client() -> reqwest::Client {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .unwrap_or_default()
}

/// (scheme, authority, path) of an absolute URL, without a URL crate.
fn split_url(u: &str) -> Option<(String, String, String)> {
    let (scheme, rest) = u.split_once("://")?;
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = &rest[..end];
    let tail = &rest[end..];
    let path = &tail[..tail.find(['?', '#']).unwrap_or(tail.len())];
    (!host.is_empty()).then(|| (scheme.to_owned(), host.to_owned(), path.to_owned()))
}

async fn get_json(hc: &reqwest::Client, u: &str) -> Result<Value, String> {
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

fn percent_decode(s: &str) -> Option<Vec<u8>> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hex = |c: u8| (c as char).to_digit(16);
            let (Some(h), Some(l)) = (
                b.get(i + 1).copied().and_then(hex),
                b.get(i + 2).copied().and_then(hex),
            ) else {
                return None;
            };
            out.push((h * 16 + l) as u8);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    Some(out)
}

fn plain_error(status: StatusCode, msg: &str) -> Response<McpBody> {
    let mut r = Response::new(Full::new(Bytes::from(format!("{msg}\n"))).boxed());
    *r.status_mut() = status;
    let h = r.headers_mut();
    h.insert(
        "content-type",
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    h.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    r
}

fn json_response(status: StatusCode, v: &Value) -> Response<McpBody> {
    let mut body = serde_json::to_vec(v).unwrap_or_default();
    body.push(b'\n');
    let mut r = Response::new(Full::new(Bytes::from(body)).boxed());
    *r.status_mut() = status;
    r.headers_mut()
        .insert("content-type", HeaderValue::from_static("application/json"));
    r
}

/// Configures [`start_mcp_dev_auth_server`].
#[derive(Clone, Default)]
pub struct McpDevAuthOptions {
    /// Pre-registered clients: client_id ⇒ allowed redirect URIs (a loopback
    /// redirect matches on any port, RFC 8252 §7.3).
    pub clients: HashMap<String, Vec<String>>,
    /// Confidential clients: client_id ⇒ secret (basic or post).
    pub secrets: HashMap<String, String>,
    /// Lifetime of issued access tokens. `None` ⇒ 1 h.
    pub access_ttl: Option<Duration>,
    /// Stop advertising (and accepting) Client ID Metadata Documents.
    pub no_cimd: bool,
    /// Answer `invalid_target` when a refresh carries `resource` (an
    /// Entra-like provider, for client tests).
    pub reject_refresh_resource: bool,
    /// Fetches Client ID Metadata Documents. `None` ⇒ a 10 s client.
    pub metadata_client: Option<reqwest::Client>,
}

#[derive(Clone)]
struct Grant {
    client_id: String,
    redirect: String,
    challenge: String,
    resource: String,
    scope: String,
}

struct Dev {
    issuer: String,
    opts: McpDevAuthOptions,
    client: reqwest::Client,
    key: RsaKeyPair,
    n: Vec<u8>,
    e: Vec<u8>,
    rng: SystemRandom,
    state: Mutex<DevState>,
}

#[derive(Default)]
struct DevState {
    codes: HashMap<String, Grant>,
    rts: HashMap<String, Grant>,
    auths: usize,
}

/// The running dev authorization server (DEV ONLY). Dropping it (or
/// [`close`](Self::close)) stops it.
pub struct McpDevAuthServer {
    /// `http://127.0.0.1:<port>`.
    pub issuer: String,
    dev: Arc<Dev>,
    stop: CancellationToken,
}

impl std::fmt::Debug for McpDevAuthServer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpDevAuthServer")
            .field("issuer", &self.issuer)
            .finish_non_exhaustive()
    }
}

/// Start the dev authorization server on `127.0.0.1:<random>` with a fresh
/// 2048-bit RSA key (`kid: "dev"`). DEVELOPMENT AND TESTS ONLY.
pub async fn start_mcp_dev_auth_server(
    opts: McpDevAuthOptions,
) -> std::io::Result<McpDevAuthServer> {
    let io = |e: String| std::io::Error::other(e);
    let (der, n, e) = tokio::task::spawn_blocking(|| {
        use rsa::pkcs8::EncodePrivateKey;
        use rsa::traits::PublicKeyParts;
        let k =
            rsa::RsaPrivateKey::new(&mut rsa::rand_core::OsRng, 2048).map_err(|e| e.to_string())?;
        let der = k.to_pkcs8_der().map_err(|e| e.to_string())?;
        Ok::<_, String>((
            der.as_bytes().to_vec(),
            k.n().to_bytes_be(),
            k.e().to_bytes_be(),
        ))
    })
    .await
    .map_err(|e| io(e.to_string()))?
    .map_err(io)?;
    let key = RsaKeyPair::from_pkcs8(&der).map_err(|e| io(e.to_string()))?;
    let ln = tokio::net::TcpListener::bind("127.0.0.1:0").await?;
    let issuer = format!("http://{}", ln.local_addr()?);
    let dev = Arc::new(Dev {
        issuer: issuer.clone(),
        client: opts.metadata_client.clone().unwrap_or_else(default_client),
        opts,
        key,
        n,
        e,
        rng: SystemRandom::new(),
        state: Mutex::default(),
    });
    let stop = CancellationToken::new();
    let (d2, s2) = (dev.clone(), stop.clone());
    tokio::spawn(async move {
        loop {
            let (stream, _) = tokio::select! {
                _ = s2.cancelled() => return,
                r = ln.accept() => match r { Ok(x) => x, Err(_) => continue },
            };
            let (d, s) = (d2.clone(), s2.clone());
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |req: Request<Incoming>| {
                    let d = d.clone();
                    async move { Ok::<_, std::convert::Infallible>(d.serve(req).await) }
                });
                let conn = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc);
                tokio::select! { _ = conn => {}, _ = s.cancelled() => {} }
            });
        }
    });
    Ok(McpDevAuthServer { issuer, dev, stop })
}

impl McpDevAuthServer {
    /// Stop the server.
    pub fn close(&self) {
        self.stop.cancel();
    }

    /// How many `/authorize` requests were served.
    pub fn authorizations(&self) -> usize {
        self.dev.state.lock().unwrap().auths
    }

    /// Sign an access token with the server's key (tests: forge expired or
    /// wrong-audience tokens).
    pub fn mint(&self, claims: &Map<String, Value>) -> String {
        self.dev.mint(claims)
    }
}

impl Drop for McpDevAuthServer {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn rand_hex(n: usize) -> String {
    let mut b = vec![0u8; n];
    let _ = ring::rand::SecureRandom::fill(&SystemRandom::new(), &mut b);
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// `application/x-www-form-urlencoded` ⇒ first value per key.
fn parse_form(s: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for kv in s.split('&').filter(|kv| !kv.is_empty()) {
        let (k, v) = kv.split_once('=').unwrap_or((kv, ""));
        let dec = |x: &str| {
            percent_decode(&x.replace('+', " "))
                .map(|b| String::from_utf8_lossy(&b).into_owned())
                .unwrap_or_default()
        };
        out.entry(dec(k)).or_insert_with(|| dec(v));
    }
    out
}

/// Query-escape (Go `url.QueryEscape`).
fn query_escape(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

fn is_loopback(host: &str) -> bool {
    host.trim_start_matches('[')
        .trim_end_matches(']')
        .parse::<IpAddr>()
        .is_ok_and(|ip| ip.is_loopback())
}

/// Host without the port (IPv6 brackets kept off).
fn hostname(authority: &str) -> String {
    let a = authority.rsplit('@').next().unwrap_or(authority);
    if let Some(rest) = a.strip_prefix('[') {
        return rest.split(']').next().unwrap_or("").to_owned();
    }
    a.split(':').next().unwrap_or("").to_owned()
}

impl Dev {
    fn mint(&self, claims: &Map<String, Value>) -> String {
        let h = json!({"alg": "RS256", "typ": "JWT", "kid": "dev"});
        let input = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(h.to_string()),
            URL_SAFE_NO_PAD.encode(Value::Object(claims.clone()).to_string())
        );
        let mut sig = vec![0u8; self.key.public().modulus_len()];
        let _ = self.key.sign(
            &ring::signature::RSA_PKCS1_SHA256,
            &self.rng,
            input.as_bytes(),
            &mut sig,
        );
        format!("{input}.{}", URL_SAFE_NO_PAD.encode(sig))
    }

    async fn serve(self: Arc<Self>, req: Request<Incoming>) -> Response<McpBody> {
        let iss = &self.issuer;
        match req.uri().path() {
            "/.well-known/oauth-authorization-server" => json_response(
                StatusCode::OK,
                &json!({"issuer": iss, "authorization_endpoint": format!("{iss}/authorize"),
                    "token_endpoint": format!("{iss}/token"), "jwks_uri": format!("{iss}/jwks"),
                    "response_types_supported": ["code"],
                    "grant_types_supported": ["authorization_code", "refresh_token"],
                    "code_challenge_methods_supported": ["S256"],
                    "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
                    "authorization_response_iss_parameter_supported": true,
                    "client_id_metadata_document_supported": !self.opts.no_cimd}),
            ),
            "/jwks" => json_response(
                StatusCode::OK,
                &json!({"keys": [{"kty": "RSA", "kid": "dev", "alg": "RS256", "use": "sig",
                    "n": URL_SAFE_NO_PAD.encode(&self.n), "e": URL_SAFE_NO_PAD.encode(&self.e)}]}),
            ),
            "/authorize" => self.authorize(req).await,
            "/token" => self.token(req).await,
            _ => plain_error(StatusCode::NOT_FOUND, "404 page not found"),
        }
    }

    async fn authorize(&self, req: Request<Incoming>) -> Response<McpBody> {
        let q = parse_form(req.uri().query().unwrap_or(""));
        let g = |k: &str| q.get(k).cloned().unwrap_or_default();
        self.state.lock().unwrap().auths += 1;
        let (cid, redirect) = (g("client_id"), g("redirect_uri"));
        if !self.redirect_allowed(&cid, &redirect).await {
            // never redirect to an unverified URI
            return plain_error(StatusCode::BAD_REQUEST, "invalid_client or redirect_uri");
        }
        let back = |mut v: Vec<(&str, String)>| {
            v.push(("state", g("state")));
            v.push(("iss", self.issuer.clone()));
            v.sort_by(|a, b| a.0.cmp(b.0));
            let qs: Vec<String> = v
                .iter()
                .map(|(k, x)| format!("{}={}", query_escape(k), query_escape(x)))
                .collect();
            let sep = if redirect.contains('?') { '&' } else { '?' };
            let loc = format!("{redirect}{sep}{}", qs.join("&"));
            let mut r = Response::new(Full::new(Bytes::new()).boxed());
            *r.status_mut() = StatusCode::FOUND;
            if let Ok(hv) = HeaderValue::from_str(&loc) {
                r.headers_mut().insert("location", hv);
            }
            r
        };
        if g("response_type") != "code"
            || g("code_challenge_method") != "S256"
            || g("code_challenge").is_empty()
        {
            return back(vec![("error", "invalid_request".into())]);
        }
        if g("resource").is_empty() {
            return back(vec![("error", "invalid_target".into())]);
        }
        let code = rand_hex(16);
        self.state.lock().unwrap().codes.insert(
            code.clone(),
            Grant {
                client_id: cid,
                redirect: redirect.clone(),
                challenge: g("code_challenge"),
                resource: g("resource"),
                scope: g("scope"),
            },
        );
        back(vec![("code", code)])
    }

    async fn redirect_allowed(&self, cid: &str, redirect: &str) -> bool {
        let mut allowed = self.opts.clients.get(cid).cloned().unwrap_or_default();
        if cid.starts_with("https://") || cid.starts_with("http://") {
            // Client ID Metadata Document
            if self.opts.no_cimd {
                return false;
            }
            let Ok(doc) = get_json(&self.client, cid).await else {
                return false;
            };
            let s = |k: &str| doc.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
            if s("client_id") != cid || s("client_name").is_empty() {
                return false;
            }
            allowed = doc
                .get("redirect_uris")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .filter_map(|x| x.as_str().map(str::to_owned))
                        .collect()
                })
                .unwrap_or_default();
        }
        let Some((rs, ra, rp)) = split_url(redirect) else {
            return false;
        };
        let rh = hostname(&ra);
        for a in &allowed {
            if a == redirect {
                return true;
            }
            let Some((as_, aa, ap)) = split_url(a) else {
                continue;
            };
            if is_loopback(&rh) && hostname(&aa) == rh && as_ == rs && ap == rp {
                return true;
            }
        }
        false
    }

    async fn token(&self, req: Request<Incoming>) -> Response<McpBody> {
        let basic = req
            .headers()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Basic "))
            .and_then(|b| STANDARD.decode(b.trim()).ok())
            .and_then(|b| String::from_utf8(b).ok())
            .and_then(|s| s.split_once(':').map(|(a, b)| (a.to_owned(), b.to_owned())));
        let body = match Limited::new(req.into_body(), 1 << 20).collect().await {
            Ok(b) => b.to_bytes(),
            Err(_) => Bytes::new(),
        };
        let mut f = parse_form(&String::from_utf8_lossy(&body));
        let g = |f: &HashMap<String, String>, k: &str| f.get(k).cloned().unwrap_or_default();
        let bad = |e: &str| json_response(StatusCode::BAD_REQUEST, &json!({"error": e}));
        let unauthorized = || {
            json_response(
                StatusCode::UNAUTHORIZED,
                &json!({"error": "invalid_client"}),
            )
        };
        let secrets = &self.opts.secrets;
        if let Some((id, sec)) = basic {
            let unq = |x: &str| {
                percent_decode(&x.replace('+', " "))
                    .map(|b| String::from_utf8_lossy(&b).into_owned())
                    .unwrap_or_default()
            };
            let (id, sec) = (unq(&id), unq(&sec));
            let form_id = g(&f, "client_id");
            let want = secrets.get(&id).cloned().unwrap_or_default();
            if (!form_id.is_empty() && form_id != id) || want.is_empty() || want != sec {
                return unauthorized();
            }
            f.insert("client_id".into(), id);
        } else if let Some(want) = secrets.get(&g(&f, "client_id")).filter(|w| !w.is_empty()) {
            if &g(&f, "client_secret") != want {
                return unauthorized();
            }
        }
        let mut st = self.state.lock().unwrap();
        let grant = match g(&f, "grant_type").as_str() {
            "authorization_code" => {
                let Some(gr) = st.codes.remove(&g(&f, "code")) else {
                    return bad("invalid_grant");
                };
                let sum =
                    ring::digest::digest(&ring::digest::SHA256, g(&f, "code_verifier").as_bytes());
                if URL_SAFE_NO_PAD.encode(sum.as_ref()) != gr.challenge
                    || g(&f, "client_id") != gr.client_id
                    || g(&f, "redirect_uri") != gr.redirect
                    || g(&f, "resource") != gr.resource
                {
                    return bad("invalid_grant");
                }
                gr
            }
            "refresh_token" => {
                let rt = g(&f, "refresh_token");
                match st.rts.get(&rt) {
                    Some(gr) if g(&f, "client_id") == gr.client_id => {}
                    _ => return bad("invalid_grant"),
                }
                let gr = st.rts.remove(&rt).expect("present");
                let res = g(&f, "resource");
                if !res.is_empty() && (res != gr.resource || self.opts.reject_refresh_resource) {
                    return bad("invalid_target");
                }
                gr
            }
            _ => return bad("unsupported_grant_type"),
        };
        let ttl = self
            .opts
            .access_ttl
            .filter(|d| !d.is_zero())
            .unwrap_or(Duration::from_secs(3600));
        let rt = rand_hex(24);
        st.rts.insert(rt.clone(), grant.clone());
        drop(st);
        let now = now_secs();
        let claims = json!({"iss": self.issuer, "sub": "dev-user", "aud": grant.resource,
            "scope": grant.scope, "client_id": grant.client_id, "jti": rand_hex(8),
            "exp": now + ttl.as_secs() as i64, "iat": now});
        let Value::Object(claims) = claims else {
            unreachable!()
        };
        let at = self.mint(&claims);
        json_response(
            StatusCode::OK,
            &json!({"access_token": at, "token_type": "Bearer", "expires_in": ttl.as_secs(),
                    "refresh_token": rt, "scope": grant.scope}),
        )
    }
}
