//! DevAuthServer — DEV/TEST ONLY. NEVER USE IN PRODUCTION.
//! A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user"
//! (no login, no consent screen). It exists so local dev and tests can exercise the real MCP
//! auth flow: RFC 8414 metadata, JWKS, auth code + PKCE S256, resource (RFC 8707), iss in the
//! response (RFC 9207), refresh-token rotation, pre-registered + CIMD clients.
//!
//! ```ignore
//! let dev = DevAuthServer::start().await;          // https://127.0.0.1:<port>/dev
//! srv.auth = Some(ServerAuth::new(&dev.issuer));
//! ```

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use base64::Engine;
use rsa::RsaPrivateKey;
use rsa::pkcs1v15::SigningKey;
use rsa::signature::{SignatureEncoding, Signer};
use rsa::traits::PublicKeyParts;
use serde_json::{Value, json};
use sha2::Digest;

use crate::auth::{Opener, encode_form, get_json, opener};
use crate::http::{Handler, HttpServer, IntoHandler, Request, Response};
use crate::{b64url, rand_str};

/// Decoded form / query parameters (in order).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Form(pub Vec<(String, String)>);

impl Form {
    pub fn parse(s: &[u8]) -> Form {
        Form(
            url::form_urlencoded::parse(s)
                .map(|(k, v)| (k.into_owned(), v.into_owned()))
                .collect(),
        )
    }
    pub fn get(&self, k: &str) -> String {
        self.0
            .iter()
            .find(|(a, _)| a == k)
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }
    pub fn has(&self, k: &str) -> bool {
        self.0.iter().any(|(a, _)| a == k)
    }
    fn set(&mut self, k: &str, v: &str) {
        self.0.retain(|(a, _)| a != k);
        self.0.push((k.into(), v.into()));
    }
}

#[derive(Clone)]
struct DevCode {
    client_id: String,
    redirect: String,
    challenge: String,
    resource: String,
    scope: String,
}

#[derive(Default)]
struct DevState {
    clients: HashMap<String, Vec<String>>, // pre-registered client_id -> redirect URIs
    secrets: HashMap<String, String>,      // confidential clients: client_id -> secret
    access_ttl: u64,
    iss_override: String,
    no_cimd: bool,
    reject_refresh_resource: bool,
    last_token_form: Form,
    last_authorize_query: Form,
    authorizations: usize,
    refreshes: usize,
    codes: HashMap<String, DevCode>,
    rts: HashMap<String, DevCode>,
}

struct Inner {
    issuer: String,
    key: RsaPrivateKey,
    st: Mutex<DevState>,
}

/// A running dev authorization server (TLS on 127.0.0.1). Cheap to clone.
#[derive(Clone)]
pub struct DevAuthServer {
    /// `https://127.0.0.1:<port>/dev`
    pub issuer: String,
    inner: Arc<Inner>,
    srv: Arc<HttpServer>,
}

impl DevAuthServer {
    /// Starts a TLS dev AS; the default HTTP client in this process trusts it from now on.
    pub async fn start() -> DevAuthServer {
        crate::http::trust_dev_cert();
        let key = tokio::task::spawn_blocking(|| {
            RsaPrivateKey::new(&mut rand::thread_rng(), 2048).unwrap()
        })
        .await
        .unwrap();
        // Bind first so the issuer (which embeds the port) is known before serving.
        let ln = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let issuer = format!("https://{}/dev", ln.local_addr().unwrap());
        let inner = Arc::new(Inner {
            issuer: issuer.clone(),
            key,
            st: Mutex::new(DevState {
                access_ttl: 3600,
                ..Default::default()
            }),
        });
        let h = inner.clone().into_handler();
        let acc = crate::http::dev_acceptor();
        let srv = Arc::new(HttpServer::serve_on(ln, h, Some(acc)));
        DevAuthServer { issuer, inner, srv }
    }

    fn st(&self) -> std::sync::MutexGuard<'_, DevState> {
        self.inner.st.lock().unwrap()
    }

    /// The AS admin pre-registers a client id with its redirect URIs.
    pub fn register_client(&self, id: &str, redirects: &[&str]) {
        self.st()
            .clients
            .insert(id.into(), redirects.iter().map(|s| s.to_string()).collect());
    }
    /// Makes a registered client confidential (secret via basic or post).
    pub fn set_secret(&self, id: &str, secret: &str) {
        self.st().secrets.insert(id.into(), secret.into());
    }
    /// Access token lifetime in seconds (default 3600).
    pub fn set_access_ttl(&self, secs: u64) {
        self.st().access_ttl = secs;
    }
    /// Test knob: send this iss in the authorization response instead of the real one.
    pub fn set_iss_override(&self, iss: &str) {
        self.st().iss_override = iss.into();
    }
    /// Test knob: don't advertise client_id_metadata_document_supported.
    pub fn set_no_cimd(&self, v: bool) {
        self.st().no_cimd = v;
    }
    /// Test knob (Entra-like): invalid_target if a refresh_token grant carries resource.
    pub fn set_reject_refresh_resource(&self, v: bool) {
        self.st().reject_refresh_resource = v;
    }
    /// (authorizations, refreshes) so far.
    pub fn stats(&self) -> (usize, usize) {
        let s = self.st();
        (s.authorizations, s.refreshes)
    }
    pub fn last_authorize_query(&self) -> Form {
        self.st().last_authorize_query.clone()
    }
    pub fn last_token_form(&self) -> Form {
        self.st().last_token_form.clone()
    }

    pub fn close(&self) {
        self.srv.close();
    }

    /// A client that trusts the dev TLS and follows redirects (a test "browser").
    pub fn client(&self) -> reqwest::Client {
        crate::http::default_client()
    }

    /// Headless "browser" for dev/tests: visits an authorization URL and follows the redirect
    /// back to the callback.
    pub async fn approve(&self, u: &str) -> Result<(), String> {
        self.client()
            .get(u)
            .send()
            .await
            .map(|_| ())
            .map_err(|e| crate::client::err_chain(&e))
    }

    /// `approve` as a LoopbackAuthorizer opener: `Authorizer::loopback(dev.approver())`.
    pub fn approver(&self) -> Opener {
        let d = self.clone();
        opener(move |u| {
            let d = d.clone();
            async move { d.approve(&u).await }
        })
    }

    /// Signs an access token directly (tests: forge wrong audience / expired tokens).
    pub fn mint(&self, claims: &Value) -> String {
        self.inner.mint(claims)
    }
}

impl Inner {
    fn mint(&self, claims: &Value) -> String {
        let h = json!({"alg": "RS256", "kid": "dev", "typ": "JWT"});
        let input = format!(
            "{}.{}",
            b64url(h.to_string().as_bytes()),
            b64url(claims.to_string().as_bytes())
        );
        let sig = SigningKey::<sha2::Sha256>::new(self.key.clone()).sign(input.as_bytes());
        format!("{input}.{}", b64url(&sig.to_bytes()))
    }

    async fn handle(self: Arc<Self>, r: Request) -> Response {
        let iss = &self.issuer;
        match r.path.as_str() {
            "/.well-known/oauth-authorization-server/dev" => {
                let no_cimd = self.st.lock().unwrap().no_cimd;
                Response::json(
                    200,
                    &json!({"issuer": iss, "authorization_endpoint": format!("{iss}/authorize"),
                    "token_endpoint": format!("{iss}/token"), "jwks_uri": format!("{iss}/jwks"),
                    "response_types_supported": ["code"], "grant_types_supported": ["authorization_code", "refresh_token"],
                    "code_challenge_methods_supported": ["S256"],
                    "token_endpoint_auth_methods_supported": ["none", "client_secret_basic", "client_secret_post"],
                    "authorization_response_iss_parameter_supported": true,
                    "client_id_metadata_document_supported": !no_cimd}),
                )
            }
            "/dev/jwks" => Response::json(
                200,
                &json!({"keys": [{"kty": "RSA", "kid": "dev", "alg": "RS256", "use": "sig",
                "n": b64url(&self.key.n().to_bytes_be()), "e": b64url(&self.key.e().to_bytes_be())}]}),
            ),
            "/dev/authorize" => self.authorize(&r).await,
            "/dev/token" => self.token(&r),
            _ => Response::text(404, "404 page not found"),
        }
    }

    async fn authorize(&self, r: &Request) -> Response {
        let q = Form::parse(r.query.as_bytes());
        let allowed = {
            let mut s = self.st.lock().unwrap();
            s.authorizations += 1;
            s.last_authorize_query = q.clone();
            s.clients
                .get(&q.get("client_id"))
                .cloned()
                .unwrap_or_default()
        };
        let (cid, redirect) = (q.get("client_id"), q.get("redirect_uri"));
        if !redirect_allowed(&cid, &redirect, allowed).await {
            return Response::text(400, "invalid_client or redirect_uri"); // never redirect to an unverified URI
        }
        let back = |pairs: &[(&str, &str)]| {
            Response::redirect(&format!(
                "{redirect}?{}",
                encode_form(pairs.iter().copied())
            ))
        };
        let state = q.get("state");
        if q.get("response_type") != "code"
            || q.get("code_challenge_method") != "S256"
            || q.get("code_challenge").is_empty()
        {
            return back(&[
                ("error", "invalid_request"),
                ("iss", &self.issuer),
                ("state", &state),
            ]);
        }
        if q.get("resource").is_empty() {
            return back(&[
                ("error", "invalid_target"),
                ("iss", &self.issuer),
                ("state", &state),
            ]);
        }
        let code = rand_str(16);
        let iss = {
            let mut s = self.st.lock().unwrap();
            s.codes.insert(
                code.clone(),
                DevCode {
                    client_id: cid,
                    redirect: redirect.clone(),
                    challenge: q.get("code_challenge"),
                    resource: q.get("resource"),
                    scope: q.get("scope"),
                },
            );
            if s.iss_override.is_empty() {
                self.issuer.clone()
            } else {
                s.iss_override.clone()
            }
        };
        back(&[("code", &code), ("iss", &iss), ("state", &state)])
    }

    fn token(&self, r: &Request) -> Response {
        let mut f = Form::parse(&r.body);
        let bad = |e: &str| Response::json(400, &json!({"error": e}));
        let mut s = self.st.lock().unwrap();
        s.last_token_form = f.clone();
        let basic = r.header("Authorization");
        if let Some(b) = basic.strip_prefix("Basic ") {
            let dec = base64::engine::general_purpose::STANDARD
                .decode(b)
                .unwrap_or_default();
            let dec = String::from_utf8_lossy(&dec).into_owned();
            let (id, sec) = dec.split_once(':').unwrap_or((&dec, ""));
            let un = |x: &str| Form::parse(format!("x={x}").as_bytes()).get("x");
            let (id, sec) = (un(id), un(sec));
            let want = s.secrets.get(&id).cloned().unwrap_or_default();
            if (!f.get("client_id").is_empty() && f.get("client_id") != id)
                || want.is_empty()
                || want != sec
            {
                return Response::json(401, &json!({"error": "invalid_client"}));
            }
            f.set("client_id", &id);
        } else if let Some(want) = s.secrets.get(&f.get("client_id"))
            && !want.is_empty()
            && f.get("client_secret") != *want
        {
            return Response::json(401, &json!({"error": "invalid_client"}));
        }
        let c = match f.get("grant_type").as_str() {
            "authorization_code" => {
                let Some(c) = s.codes.remove(&f.get("code")) else {
                    return bad("invalid_grant");
                }; // single use
                if b64url(&sha2::Sha256::digest(f.get("code_verifier").as_bytes())) != c.challenge {
                    return bad("invalid_grant"); // PKCE verified here
                }
                if f.get("client_id") != c.client_id
                    || f.get("redirect_uri") != c.redirect
                    || f.get("resource") != c.resource
                {
                    return bad("invalid_grant");
                }
                c
            }
            "refresh_token" => {
                let Some(c) = s.rts.get(&f.get("refresh_token")).cloned() else {
                    return bad("invalid_grant");
                };
                if f.get("client_id") != c.client_id {
                    return bad("invalid_grant");
                }
                s.rts.remove(&f.get("refresh_token")); // rotation (required for public clients)
                let res = f.get("resource");
                if !res.is_empty() && (res != c.resource || s.reject_refresh_resource) {
                    return bad("invalid_target");
                }
                s.refreshes += 1;
                c
            }
            _ => return bad("unsupported_grant_type"),
        };
        let ttl = s.access_ttl;
        let rt = rand_str(24);
        s.rts.insert(rt.clone(), c.clone());
        drop(s);
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let at = self.mint(
            &json!({"iss": self.issuer, "sub": "dev-user", "aud": c.resource, "scope": c.scope,
            "client_id": c.client_id, "jti": rand_str(8), "exp": now + ttl, "iat": now}),
        );
        Response::json(
            200,
            &json!({"access_token": at, "token_type": "Bearer", "expires_in": ttl,
            "refresh_token": rt, "scope": c.scope}),
        )
    }
}

impl IntoHandler for Arc<Inner> {
    fn into_handler(self) -> Handler {
        Arc::new(move |r| Box::pin(self.clone().handle(r)))
    }
}

/// Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3).
async fn redirect_allowed(cid: &str, redirect: &str, mut allowed: Vec<String>) -> bool {
    if cid.starts_with("https://") {
        // Client ID Metadata Document
        let Ok(doc) = get_json(&crate::http::default_client(), cid).await else {
            return false;
        };
        let s = |k: &str| {
            doc.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string()
        };
        if s("client_id") != cid || s("client_name").is_empty() {
            return false;
        }
        allowed = doc
            .get("redirect_uris")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
    }
    let Ok(ru) = url::Url::parse(redirect) else {
        return false;
    };
    let loopback = match ru.host() {
        Some(url::Host::Ipv4(ip)) => ip.is_loopback(),
        Some(url::Host::Ipv6(ip)) => ip.is_loopback(),
        _ => false,
    };
    allowed.iter().any(|a| {
        if a == redirect {
            return true;
        }
        let Ok(au) = url::Url::parse(a) else {
            return false;
        };
        loopback
            && au.host_str() == ru.host_str()
            && au.scheme() == ru.scheme()
            && au.path() == ru.path()
    })
}
