//! Stateless MCP 2026-07-28 client: Streamable HTTP (or stdio), `_meta` + headers on every
//! request, JSON or SSE responses, MRTR `input_required` rounds via a [`Responder`].

use std::sync::Arc;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::Instant;

use base64::Engine;
use futures_util::future::BoxFuture;
use serde_json::{Value, json};
use tokio::sync::Mutex;

use crate::auth::{OAuth, RetryFn};
use crate::stdio::StdioConn;
use crate::{Error, META_CLIENT_CAPS, META_CLIENT_INFO, META_VERSION, Obj, PROTOCOL_VERSION};

/// Answers one MRTR input request (stands in for toolnexus §10 waitFor): `(key, request) -> response`.
pub type Responder = Arc<dyn Fn(&str, &Obj) -> Result<Obj, Error> + Send + Sync>;

/// Wraps a closure as `Some(Responder)` for [`Client::call_tool`].
pub fn responder<F>(f: F) -> Option<Responder>
where
    F: Fn(&str, &Obj) -> Result<Obj, Error> + Send + Sync + 'static,
{
    Some(Arc::new(f))
}

/// tools/list result + its expiry (the server's ttlMs).
pub(crate) type ToolCache = Option<(Vec<Obj>, Instant)>;

/// A stateless MCP client. Cheap to clone (clones share the id counter, tool cache and auth).
#[derive(Clone)]
pub struct Client {
    pub url: String,
    pub version: String,
    pub info: Value,
    pub caps: Value,
    pub http: reqwest::Client,
    /// None = no authorization (HTTP only; stdio servers take credentials from their env).
    pub auth: Option<Arc<OAuth>>,
    /// `tools()` exposes search_tools + call_tool instead of every server tool (see toolsearch.rs).
    pub tool_search: bool,
    /// What this server is for; shown in search_tools results.
    pub description: String,
    /// Extra headers set last on every HTTP request (negative tests / static credentials).
    pub header_override: Vec<(String, String)>,
    pub(crate) stdio: Option<Arc<StdioConn>>,
    pub(crate) tool_cache: Arc<Mutex<ToolCache>>,
    next_id: Arc<AtomicI64>,
}

impl Client {
    pub fn new(url: impl Into<String>) -> Client {
        Client {
            url: url.into(),
            version: PROTOCOL_VERSION.into(),
            info: json!({"name": "toolnexus-spike-rust", "version": "0.0.1"}),
            caps: json!({"elicitation": {"form": {}}}),
            http: crate::http::default_client(),
            auth: None,
            tool_search: false,
            description: String::new(),
            header_override: vec![],
            stdio: None,
            tool_cache: Arc::new(Mutex::new(None)),
            next_id: Arc::new(AtomicI64::new(0)),
        }
    }

    /// Attaches OAuth (builder style).
    pub fn with_auth(mut self, o: OAuth) -> Client {
        self.auth = Some(Arc::new(o));
        self
    }

    /// The attached OAuth engine (panics if none).
    pub fn oauth(&self) -> &Arc<OAuth> {
        self.auth.as_ref().expect("client has no OAuth")
    }

    /// Sends one request; `params` (an object or null) gets the per-request `_meta` merged in.
    ///
    /// Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403
    /// insufficient_scope, carrying WWW-Authenticate — the server rejected it before dispatch, so
    /// the tool never ran. A lost connection, timeout, 5xx or unreadable response is returned as an
    /// error and NEVER retried: the server may already have executed the call.
    pub fn call(&self, method: &str, params: Value) -> BoxFuture<'static, Result<Obj, Error>> {
        let c = self.clone();
        let method = method.to_string();
        Box::pin(async move { c.call_inner(&method, params).await })
    }

    async fn call_inner(&self, method: &str, params: Value) -> Result<Obj, Error> {
        let mut p = match params {
            Value::Object(m) => m,
            _ => Obj::new(),
        };
        p.insert(
            "_meta".into(),
            json!({META_VERSION: self.version, META_CLIENT_CAPS: self.caps, META_CLIENT_INFO: self.info}),
        );
        let id = self.next_id.fetch_add(1, Ordering::SeqCst) + 1;
        let body =
            serde_json::to_vec(&json!({"jsonrpc": "2.0", "id": id, "method": method, "params": p}))
                .unwrap();
        let msg = match &self.stdio {
            Some(s) => s.round_trip(id, body).await?,
            None => self.http_round_trip(method, &p, body).await?,
        };
        if let Some(e) = msg.get("error").filter(|e| !e.is_null()) {
            return Err(Error::Rpc {
                code: e.get("code").and_then(Value::as_i64).unwrap_or(0),
                message: e
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                data: e.get("data").cloned(),
            });
        }
        let Some(Value::Object(mut r)) = msg.get("result").cloned() else {
            return Err(Error::msg("response has neither result nor error")); // hostile/broken server
        };
        r.entry("resultType").or_insert_with(|| json!("complete")); // earlier-protocol servers
        Ok(r)
    }

    async fn http_round_trip(
        &self,
        method: &str,
        params: &Obj,
        body: Vec<u8>,
    ) -> Result<Value, Error> {
        let mut attempt = 0;
        let resp = loop {
            let sent = match &self.auth {
                Some(a) => a.bearer(&self.url).await,
                None => String::new(),
            };
            // never retried on error: the server may have executed it
            let resp = self
                .post(method, params, &body, &sent)
                .await
                .map_err(|e| Error::msg(err_chain(&e)))?;
            let status = resp.status().as_u16();
            let www = resp
                .headers()
                .get("www-authenticate")
                .and_then(|v| v.to_str().ok())
                .unwrap_or_default()
                .to_string();
            let Some(auth) = &self.auth else { break resp };
            if (status != 401 && status != 403) || www.is_empty() {
                break resp;
            }
            if attempt >= 3 {
                // spec: Step-Up retry limit
                return Err(Error::msg(format!(
                    "authorization failed: HTTP {status} after {} attempts",
                    attempt + 1
                )));
            }
            let (c, m, p) = (self.clone(), method.to_string(), params.clone());
            let retry: RetryFn = Arc::new(move || c.call(&m, Value::Object(p.clone())));
            auth.challenge(&self.url, status, &www, &sent, retry)
                .await?;
            attempt += 1;
        };
        let status = resp.status().as_u16();
        let sse = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.starts_with("text/event-stream"));
        let bytes = resp
            .bytes()
            .await
            .map_err(|e| Error::msg(format!("HTTP {status}: {}", err_chain(&e))))?;
        let mut msg = Value::Null;
        if sse {
            // Take the last data: payload that carries a result or error (the final response).
            for line in String::from_utf8_lossy(&bytes).lines() {
                if let Some(d) = line.strip_prefix("data:")
                    && let Ok(v) = serde_json::from_str::<Value>(d)
                    && (v.get("result").is_some() || v.get("error").is_some())
                {
                    msg = v;
                }
            }
        } else {
            msg = serde_json::from_slice(&bytes)
                .map_err(|e| Error::msg(format!("HTTP {status}: {e}")))?;
        }
        let has = |k: &str| msg.get(k).is_some_and(|v| !v.is_null());
        if !has("result") && !has("error") {
            return Err(Error::msg(format!(
                "HTTP {status}: response has neither result nor error"
            )));
        }
        Ok(msg)
    }

    pub(crate) async fn post(
        &self,
        method: &str,
        params: &Obj,
        body: &[u8],
        bearer: &str,
    ) -> Result<reqwest::Response, reqwest::Error> {
        let mut req = self
            .http
            .post(&self.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", &self.version)
            .header("Mcp-Method", method);
        let name_key = match method {
            "tools/call" | "prompts/get" => Some("name"),
            "resources/read" => Some("uri"),
            _ => None,
        };
        if let Some(k) = name_key {
            req = req.header(
                "Mcp-Name",
                encode_header(params.get(k).and_then(Value::as_str).unwrap_or_default()),
            );
        }
        let mut headers = reqwest::header::HeaderMap::new();
        for (k, v) in &self.header_override {
            if let (Ok(k), Ok(v)) = (reqwest::header::HeaderName::try_from(k.as_str()), v.parse()) {
                headers.insert(k, v);
            }
        }
        if !bearer.is_empty() {
            headers.insert("authorization", format!("Bearer {bearer}").parse().unwrap());
        }
        req.headers(headers).body(body.to_vec()).send().await
    }

    /// Runs tools/call, looping over input_required rounds via `respond`.
    pub async fn call_tool(
        &self,
        name: &str,
        args: Value,
        respond: Option<Responder>,
    ) -> Result<Obj, Error> {
        self.call_tool_from(name.to_string(), args, respond, None, None, 0)
            .await
    }

    fn call_tool_from(
        &self,
        name: String,
        args: Value,
        respond: Option<Responder>,
        mut responses: Option<Obj>,
        mut state: Option<Value>,
        mut round: usize,
    ) -> BoxFuture<'static, Result<Obj, Error>> {
        let c = self.clone();
        Box::pin(async move {
            while round < 8 {
                let mut p = Obj::new();
                p.insert("name".into(), json!(name));
                p.insert("arguments".into(), args.clone());
                if let Some(r) = &responses {
                    p.insert("inputResponses".into(), Value::Object(r.clone()));
                }
                if let Some(s) = &state {
                    p.insert("requestState".into(), s.clone());
                }
                let res = c.call("tools/call", Value::Object(p)).await;
                if let Err(Error::Pending(pe)) = &res {
                    // The paused request got 401/403 before dispatch: the server has NOT run this
                    // round. Resume sends exactly this round (same inputResponses/requestState) for
                    // the first time — earlier rounds are not replayed and the user is not re-asked.
                    let (c2, n, a, rs, st, rd, rp) = (
                        c.clone(),
                        name.clone(),
                        args.clone(),
                        responses.clone(),
                        state.clone(),
                        round,
                        respond.clone(),
                    );
                    c.oauth().set_retry(
                        &pe.id,
                        Arc::new(move || {
                            c2.call_tool_from(
                                n.clone(),
                                a.clone(),
                                rp.clone(),
                                rs.clone(),
                                st.clone(),
                                rd,
                            )
                        }),
                    );
                }
                let res = res?;
                if res.get("resultType").and_then(Value::as_str) != Some("input_required") {
                    return Ok(res);
                }
                state = res.get("requestState").cloned().filter(|v| !v.is_null());
                let Some(respond) = &respond else {
                    return Err(Error::msg(format!(
                        "tools/call {name}: server asked for input but no Responder was given"
                    )));
                };
                let mut out = Obj::new();
                if let Some(reqs) = res.get("inputRequests").and_then(Value::as_object) {
                    for (k, v) in reqs {
                        let Some(r) = v.as_object() else {
                            return Err(Error::msg(format!(
                                "tools/call {name}: malformed input request {k:?}"
                            )));
                        };
                        out.insert(k.clone(), Value::Object(respond(k, r)?));
                    }
                }
                responses = Some(out);
                round += 1;
            }
            Err(Error::msg(format!(
                "tools/call {name}: too many input_required rounds"
            )))
        })
    }
}

/// Header-safe value, else the `=?base64?…?=` sentinel.
pub(crate) fn encode_header(v: &str) -> String {
    let safe = v == v.trim()
        && !(v.starts_with("=?base64?") && v.ends_with("?="))
        && v.chars().all(|c| (' '..='~').contains(&c));
    if safe {
        v.to_string()
    } else {
        format!(
            "=?base64?{}?=",
            base64::engine::general_purpose::STANDARD.encode(v)
        )
    }
}

/// An error with its sources (reqwest hides the useful part in `source()`).
pub(crate) fn err_chain(e: &dyn std::error::Error) -> String {
    let mut s = e.to_string();
    let mut cur = e.source();
    while let Some(c) = cur {
        s.push_str(": ");
        s.push_str(&c.to_string());
        cur = c.source();
    }
    s
}
