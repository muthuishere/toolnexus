//! Server-side authorization: the MCP server as an OAuth 2.1 protected resource
//! (MCP 2026-07-28 basic/authorization). One struct, set `Server.auth`, done.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use rsa::pkcs1v15::{Signature, VerifyingKey};
use rsa::signature::Verifier;
use rsa::{BigUint, RsaPublicKey};
use serde_json::{Value, json};

use crate::auth::{PRM_PATH, discover_as, get_json};
use crate::http::{Request, Response};
use crate::{Error, Obj};

/// The authenticated caller, visible to tool handlers via `ToolContext.principal`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Principal {
    pub subject: String,
    pub scopes: Vec<String>,
    pub claims: Obj,
}

/// Replaces the default JWT/JWKS verifier (opaque tokens, introspection, ...).
pub type VerifyFn = Arc<dyn Fn(&str) -> Result<Principal, String> + Send + Sync>;

/// Protects a Server. `scopes["*"]` is required for every request (and advertised as
/// scopes_supported); `scopes[tool]` is additionally required for that tools/call.
#[derive(Default)]
pub struct ServerAuth {
    /// The authorization server's issuer identifier.
    pub issuer: String,
    /// Canonical resource URI; "" = derived from the request (scheme://host/mcp).
    pub audience: String,
    pub scopes: HashMap<String, Vec<String>>,
    pub verify: Option<VerifyFn>,
    /// For AS metadata + JWKS fetches; None = the default client.
    pub http: Option<reqwest::Client>,
    /// JWKS cache by kid; leave at default.
    pub jwks: JwksCache,
}

/// Opaque JWKS cache; always `Default::default()`.
#[derive(Default)]
pub struct JwksCache(tokio::sync::Mutex<Option<(HashMap<String, RsaPublicKey>, Instant)>>);

/// `[("*", &["mcp:read"]), ("greet", &["mcp:greet"])]` -> a scopes map.
pub fn scope_map(pairs: &[(&str, &[&str])]) -> HashMap<String, Vec<String>> {
    pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.iter().map(|s| s.to_string()).collect()))
        .collect()
}

impl ServerAuth {
    pub fn new(issuer: impl Into<String>) -> ServerAuth {
        ServerAuth {
            issuer: issuer.into(),
            ..Default::default()
        }
    }
    pub fn with_scopes(mut self, pairs: &[(&str, &[&str])]) -> ServerAuth {
        self.scopes = scope_map(pairs);
        self
    }

    fn audience(&self, r: &Request) -> String {
        if !self.audience.is_empty() {
            return self.audience.clone();
        }
        format!("{}/mcp", r.origin())
    }

    fn base_scopes(&self) -> Vec<String> {
        self.scopes.get("*").cloned().unwrap_or_default()
    }

    /// RFC 9728 PRM at both the path-inserted and root well-known URIs.
    pub(crate) fn serve_metadata(&self, r: &Request) -> Option<Response> {
        if r.path != PRM_PATH && r.path != format!("{PRM_PATH}/mcp") {
            return None;
        }
        let scopes = self
            .scopes
            .get("*")
            .map(|s| json!(s))
            .unwrap_or(Value::Null);
        Some(Response::json(
            200,
            &json!({"resource": self.audience(r), "authorization_servers": [self.issuer],
                "scopes_supported": scopes, "bearer_methods_supported": ["header"]}),
        ))
    }

    /// Authenticates the request; on failure returns the 401/403 response to write.
    pub(crate) async fn check(
        &self,
        r: &Request,
        method: &str,
        p: &Obj,
    ) -> Result<Principal, Response> {
        let challenge = |status: u16, extra: &str, scopes: &[String]| {
            let mut v = format!(r#"Bearer resource_metadata="{}{PRM_PATH}/mcp""#, r.origin());
            if !scopes.is_empty() {
                v += &format!(r#", scope="{}""#, scopes.join(" "));
            }
            Response::new(
                status,
                "application/json",
                br#"{"error":"unauthorized"}"#.to_vec(),
            )
            .with_header("WWW-Authenticate", &(v + extra))
        };
        let auth = r.header("Authorization");
        let tok = auth.strip_prefix("Bearer ").unwrap_or_default();
        if tok.is_empty() {
            return Err(challenge(401, "", &self.base_scopes()));
        }
        let pr = match &self.verify {
            Some(v) => v(tok),
            None => self.verify_jwt(tok, &self.audience(r)).await,
        };
        let Ok(pr) = pr else {
            // never echo the token or the detailed reason
            return Err(challenge(
                401,
                r#", error="invalid_token""#,
                &self.base_scopes(),
            ));
        };
        let mut need = self.base_scopes();
        if method == "tools/call" {
            let name = p.get("name").and_then(Value::as_str).unwrap_or_default();
            need.extend(self.scopes.get(name).cloned().unwrap_or_default());
        }
        if need.iter().any(|s| !pr.scopes.contains(s)) {
            return Err(challenge(403, r#", error="insufficient_scope""#, &need));
        }
        Ok(pr)
    }

    // --- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ---

    async fn verify_jwt(&self, tok: &str, aud: &str) -> Result<Principal, String> {
        let parts: Vec<&str> = tok.split('.').collect();
        if parts.len() != 3 {
            return Err("malformed jwt".into());
        }
        let (Some(hdr), Some(claims)) = (decode_seg(parts[0]), decode_seg(parts[1])) else {
            return Err("bad jwt header/claims".into());
        };
        if hdr.get("alg").and_then(Value::as_str) != Some("RS256") {
            return Err("bad jwt header/claims".into());
        }
        let kid = hdr.get("kid").and_then(Value::as_str).unwrap_or_default();
        let key = self.key(kid).await?;
        let sig = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(parts[2])
            .map_err(|e| e.to_string())?;
        let sig = Signature::try_from(sig.as_slice()).map_err(|_| "bad signature".to_string())?;
        VerifyingKey::<sha2::Sha256>::new(key)
            .verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &sig)
            .map_err(|_| "bad signature".to_string())?;
        if claims.get("iss").and_then(Value::as_str) != Some(self.issuer.as_str()) {
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
            .unwrap()
            .as_secs() as i64;
        if claims.get("exp").and_then(Value::as_f64).unwrap_or(0.0) as i64 <= now {
            return Err("expired".into());
        }
        Ok(Principal {
            subject: claims
                .get("sub")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .into(),
            scopes: claims
                .get("scope")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .split_whitespace()
                .map(String::from)
                .collect(),
            claims,
        })
    }

    async fn key(&self, kid: &str) -> Result<RsaPublicKey, String> {
        let mut cache = self.jwks.0.lock().await;
        if let Some((keys, fetched)) = cache.as_ref() {
            if let Some(k) = keys.get(kid) {
                return Ok(k.clone());
            }
            if fetched.elapsed() < Duration::from_secs(30) {
                return Err("unknown kid".into()); // garbage kids must not hammer the AS
            }
        }
        let hc = self
            .http
            .clone()
            .unwrap_or_else(crate::http::default_client);
        let md = discover_as(&hc, &self.issuer)
            .await
            .map_err(|e: Error| e.to_string())?; // same order as the client
        let jwks_uri = md
            .get("jwks_uri")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let jwks = get_json(&hc, jwks_uri).await.map_err(|e| e.to_string())?;
        let mut keys = HashMap::new();
        let d = |s: &Value| {
            base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s.as_str().unwrap_or_default())
        };
        for k in jwks
            .get("keys")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
        {
            if k.get("kty").and_then(Value::as_str) != Some("RSA") {
                continue;
            }
            if let (Ok(n), Ok(e)) = (d(&k["n"]), d(&k["e"]))
                && let Ok(pk) =
                    RsaPublicKey::new(BigUint::from_bytes_be(&n), BigUint::from_bytes_be(&e))
            {
                keys.insert(
                    k.get("kid")
                        .and_then(Value::as_str)
                        .unwrap_or_default()
                        .to_string(),
                    pk,
                );
            }
        }
        let found = keys.get(kid).cloned();
        *cache = Some((keys, Instant::now()));
        found.ok_or_else(|| "unknown kid".into())
    }
}

pub(crate) fn decode_seg(s: &str) -> Option<Obj> {
    let b = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(s)
        .ok()?;
    match serde_json::from_slice(&b).ok()? {
        Value::Object(m) => Some(m),
        _ => None,
    }
}

/// Decodes a JWT's claims WITHOUT verifying it (tests / logging of non-secret claims).
pub fn unverified_claims(jwt: &str) -> Option<Obj> {
    decode_seg(jwt.split('.').nth(1)?)
}
