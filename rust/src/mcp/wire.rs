//! MCP 2026-07-28 wire client (SPEC §2.1–§2.6), in-house — no MCP SDK.
//!
//! One [`Session`] per server over Streamable HTTP (stateless `POST`, JSON or
//! SSE replies) or stdio (one JSON-RPC message per line, demultiplexed by
//! `id`). Every request carries the `_meta` triple; HTTP adds
//! `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name`. A request is never
//! re-sent after a lost connection, timeout, 5xx or unreadable reply (§2.6);
//! only a pre-dispatch 401 / 403 `insufficient_scope` is retried, after OAuth.

use std::collections::{BTreeMap, HashMap};
use std::process::Stdio;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::{Map, Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};
use tokio::sync::{oneshot, watch};

use super::oauth::OAuth;
use crate::tool::Request;

pub(crate) type Obj = Map<String, Value>;

pub const PROTOCOL_VERSION: &str = "2026-07-28";
pub const META_VERSION: &str = "io.modelcontextprotocol/protocolVersion";
pub const META_CLIENT_CAPS: &str = "io.modelcontextprotocol/clientCapabilities";
pub const META_CLIENT_INFO: &str = "io.modelcontextprotocol/clientInfo";
pub const SKILLS_EXT: &str = "io.modelcontextprotocol/skills";

/// Why a request failed.
#[derive(Debug, Clone)]
pub(crate) enum WireError {
    /// A JSON-RPC error response.
    Rpc(String),
    /// A non-2xx HTTP reply without a JSON-RPC body, or any 5xx.
    Http(u16),
    /// A 2xx reply that is not a JSON-RPC response for the request.
    NotRpc(String),
    /// OAuth `pause` mode: the request was rejected before dispatch and waits
    /// on this §10 `authorization` request.
    Pending(Box<Request>),
    /// The stdio child exited / closed stdout before replying.
    Exited(String),
    /// No reply within the timeout (never retried).
    Timeout(Duration),
    Other(String),
}

impl std::fmt::Display for WireError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WireError::Rpc(message) => f.write_str(message),
            WireError::Http(s) => write!(f, "HTTP {s}"),
            WireError::NotRpc(m) | WireError::Exited(m) | WireError::Other(m) => f.write_str(m),
            WireError::Pending(_) => f.write_str("authorization pending"),
            WireError::Timeout(d) => write!(f, "timed out after {}ms", d.as_millis()),
        }
    }
}

/// A connection to one server on the 2026 path.
pub(crate) struct Session {
    transport: Transport,
    next_id: AtomicI64,
    pub(crate) timeout: Duration,
    tool_cache: tokio::sync::Mutex<Option<(Vec<Obj>, Instant)>>,
    /// tool name → its `inputSchema` exactly as the server sent it.
    raw_schemas: Mutex<HashMap<String, String>>,
}

enum Transport {
    Http(HttpTransport),
    Stdio(Arc<StdioConn>),
}

struct HttpTransport {
    url: String,
    headers: BTreeMap<String, String>,
    http: reqwest::Client,
    auth: Arc<OAuth>,
}

impl Session {
    pub(crate) fn http(
        url: String,
        headers: BTreeMap<String, String>,
        http: reqwest::Client,
        auth: Arc<OAuth>,
        timeout: Duration,
    ) -> Session {
        Session::new(
            Transport::Http(HttpTransport {
                url,
                headers,
                http,
                auth,
            }),
            timeout,
        )
    }

    pub(crate) fn stdio(
        argv: &[String],
        env: &BTreeMap<String, String>,
        cwd: Option<&str>,
        timeout: Duration,
    ) -> Result<Session, String> {
        Ok(Session::new(
            Transport::Stdio(StdioConn::spawn(argv, env, cwd)?),
            timeout,
        ))
    }

    fn new(transport: Transport, timeout: Duration) -> Session {
        Session {
            transport,
            next_id: AtomicI64::new(0),
            timeout,
            tool_cache: tokio::sync::Mutex::new(None),
            raw_schemas: Mutex::new(HashMap::new()),
        }
    }

    pub(crate) fn auth(&self) -> Option<&Arc<OAuth>> {
        match &self.transport {
            Transport::Http(h) => Some(&h.auth),
            Transport::Stdio(_) => None,
        }
    }

    /// A tool's `inputSchema` as the server sent it (last `tools/list`).
    pub(crate) fn raw_schema(&self, tool: &str) -> Option<String> {
        lock(&self.raw_schemas).get(tool).cloned()
    }

    /// The discover reply came back and the 2026 path continues: let the
    /// stdio reader go on serving. No-op for HTTP.
    pub(crate) fn release_probe(&self) {
        if let Transport::Stdio(c) = &self.transport {
            c.release();
        }
    }

    /// Hand a stdio child whose discover reply was delivered to the frozen
    /// SDK path, alive and with no reader of ours left on stdout (§2.2a).
    pub(crate) async fn take_stdio(&self) -> Option<StdioHandoff> {
        match &self.transport {
            Transport::Stdio(c) => c.handoff().await,
            Transport::Http(_) => None,
        }
    }

    /// Stop a stdio child (close stdin, kill after 2 s). No-op for HTTP.
    pub(crate) async fn close(&self) {
        if let Transport::Stdio(s) = &self.transport {
            s.close().await;
        }
    }

    /// One request with the session timeout.
    pub(crate) async fn call(&self, method: &str, params: Value) -> Result<Obj, WireError> {
        self.call_within(method, params, self.timeout).await
    }

    /// One request; `params` (object or null) gets the `_meta` triple merged in.
    pub(crate) async fn call_within(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Obj, WireError> {
        self.call_raw(method, params, timeout).await.map(|(r, _)| r)
    }

    /// [`Session::call_within`] plus the response message exactly as received.
    async fn call_raw(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<(Obj, String), WireError> {
        let mut p = match params {
            Value::Object(m) => m,
            _ => Obj::new(),
        };
        p.insert(
            "_meta".into(),
            json!({
                META_VERSION: PROTOCOL_VERSION,
                META_CLIENT_CAPS: {"elicitation": {"form": {}}},
                META_CLIENT_INFO: {"name": "toolnexus", "version": env!("CARGO_PKG_VERSION")},
            }),
        );
        let id = self.next_id.fetch_add(1, Ordering::SeqCst) + 1;
        let body =
            serde_json::to_vec(&json!({"jsonrpc": "2.0", "id": id, "method": method, "params": p}))
                .unwrap_or_default();
        // The timeout bounds each exchange with the server, never an OAuth
        // login in between (a user may take minutes to authorize).
        let (msg, raw) = match &self.transport {
            Transport::Stdio(s) => tokio::time::timeout(timeout, s.round_trip(id, body))
                .await
                .map_err(|_| WireError::Timeout(timeout))??,
            Transport::Http(h) => h.round_trip(method, &p, body, timeout).await?,
        };
        if let Some(e) = msg.get("error").filter(|e| !e.is_null()) {
            return Err(WireError::Rpc(
                e.get("message")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
            ));
        }
        let Some(Value::Object(mut r)) = msg.get("result").cloned() else {
            return Err(WireError::NotRpc(
                "response has neither result nor error".into(),
            ));
        };
        let rt = r
            .entry("resultType")
            .or_insert_with(|| json!("complete"))
            .clone();
        match rt.as_str() {
            Some("complete" | "input_required") => Ok((r, raw)),
            _ => Err(WireError::Other(format!("unsupported resultType {rt}"))),
        }
    }

    /// `tools/list`, cached for the server's `ttlMs` (no hint ⇒ once per load).
    pub(crate) async fn list_tools(&self, force: bool) -> Result<Vec<Obj>, WireError> {
        let mut cache = self.tool_cache.lock().await;
        if let Some((tools, exp)) = cache.as_ref()
            && !force
            && Instant::now() < *exp
        {
            return Ok(tools.clone());
        }
        let (r, raw) = self
            .call_raw("tools/list", Value::Null, self.timeout)
            .await?;
        *lock(&self.raw_schemas) = raw_input_schemas(&raw);
        let tools: Vec<Obj> = r
            .get("tools")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(|t| t.as_object().cloned()).collect())
            .unwrap_or_default();
        let exp = match r.get("ttlMs").and_then(Value::as_f64) {
            Some(ms) if ms > 0.0 => Instant::now() + Duration::from_millis(ms as u64),
            // no hint ⇒ fetched once per load
            _ => Instant::now() + Duration::from_secs(60 * 60 * 24 * 365),
        };
        *cache = Some((tools.clone(), exp));
        Ok(tools)
    }
}

impl HttpTransport {
    async fn round_trip(
        &self,
        method: &str,
        params: &Obj,
        body: Vec<u8>,
        timeout: Duration,
    ) -> Result<(Value, String), WireError> {
        let mut attempt = 0;
        let resp = loop {
            let sent = self.auth.bearer(&self.url).await;
            // never retried on error: the server may have executed it
            let resp = tokio::time::timeout(timeout, self.post(method, params, &body, &sent))
                .await
                .map_err(|_| WireError::Timeout(timeout))?
                .map_err(|e| WireError::Other(err_chain(&e)))?;
            let status = resp.status().as_u16();
            let www = resp
                .headers()
                .get("www-authenticate")
                .and_then(|v| v.to_str().ok())
                .unwrap_or_default()
                .to_owned();
            if (status != 401 && status != 403) || www.is_empty() {
                break resp;
            }
            if attempt >= 3 {
                return Err(WireError::Other(format!(
                    "authorization failed: HTTP {status} after {} attempts",
                    attempt + 1
                )));
            }
            self.auth.challenge(&self.url, status, &www, &sent).await?;
            attempt += 1;
        };
        let status = resp.status().as_u16();
        if status >= 500 {
            return Err(WireError::Http(status));
        }
        let sse = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.starts_with("text/event-stream"));
        let bytes = tokio::time::timeout(timeout, resp.bytes())
            .await
            .map_err(|_| WireError::Timeout(timeout))?
            .map_err(|e| WireError::Other(format!("HTTP {status}: {}", err_chain(&e))))?;
        let mut msg = Value::Null;
        let mut raw = String::new();
        if sse {
            // the last `data:` payload carrying a result or error is the response
            for line in String::from_utf8_lossy(&bytes).lines() {
                if let Some(d) = line.strip_prefix("data:")
                    && let Ok(v) = serde_json::from_str::<Value>(d.trim())
                    && (v.get("result").is_some() || v.get("error").is_some())
                {
                    msg = v;
                    raw = d.trim().to_owned();
                }
            }
        } else {
            msg = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
            raw = String::from_utf8_lossy(&bytes).into_owned();
        }
        let has = |k: &str| msg.get(k).is_some_and(|v| !v.is_null());
        if !has("result") && !has("error") {
            if !(200..300).contains(&status) {
                return Err(WireError::Http(status));
            }
            return Err(WireError::NotRpc(format!(
                "HTTP {status}: response has neither result nor error"
            )));
        }
        Ok((msg, raw))
    }

    async fn post(
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
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
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
        for (k, v) in &self.headers {
            req = req.header(k.as_str(), v.as_str());
        }
        if !bearer.is_empty() {
            req = req.header("Authorization", format!("Bearer {bearer}"));
        }
        req.body(body.to_vec()).send().await
    }
}

/// The raw `inputSchema` text of every tool in a `tools/list` response.
fn raw_input_schemas(raw: &str) -> HashMap<String, String> {
    use serde_json::value::RawValue;
    #[derive(serde::Deserialize)]
    struct Msg<'a> {
        #[serde(borrow)]
        result: Res<'a>,
    }
    #[derive(serde::Deserialize)]
    struct Res<'a> {
        #[serde(borrow, default)]
        tools: Vec<RawTool<'a>>,
    }
    #[derive(serde::Deserialize)]
    struct RawTool<'a> {
        #[serde(default)]
        name: String,
        #[serde(rename = "inputSchema", borrow, default)]
        input_schema: Option<&'a RawValue>,
    }
    serde_json::from_str::<Msg>(raw)
        .map(|m| {
            m.result
                .tools
                .into_iter()
                .filter_map(|t| Some((t.name, t.input_schema?.get().to_owned())))
                .collect()
        })
        .unwrap_or_default()
}

/// Header-safe value, else the `=?base64?…?=` sentinel (§2.3).
pub(crate) fn encode_header(v: &str) -> String {
    let safe = v == v.trim()
        && !(v.starts_with("=?base64?") && v.ends_with("?="))
        && v.chars().all(|c| (' '..='~').contains(&c));
    if safe {
        v.to_owned()
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

// ---------------------------------------------------------------------------
// stdio
// ---------------------------------------------------------------------------

/// A stdio child: one JSON-RPC message per line, responses demultiplexed by
/// `id`, stderr passed through and never parsed, a dead child fails every
/// pending call.
pub(crate) struct StdioConn {
    child: tokio::sync::Mutex<Option<Child>>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    pending: Mutex<HashMap<String, oneshot::Sender<(Value, String)>>>,
    err: Mutex<Option<String>>,
    done: watch::Receiver<bool>,
    /// The probe gate (SPEC §2.2a): after delivering its FIRST reply (the
    /// `server/discover` answer) the reader parks here, out of `read()`, until
    /// the probe says continue (`None`, the 2026 path) or hands the buffered
    /// stdout over to the frozen SDK path (`Some(tx)`), after which it exits.
    gate: Mutex<Option<oneshot::Sender<Option<oneshot::Sender<StdoutReader>>>>>,
}

/// The child's stdout with whatever the probe reader had already buffered.
pub(crate) type StdoutReader = BufReader<ChildStdout>;

/// A live stdio child taken over by the frozen SDK path: no reader of ours is
/// left on its stdout. Dropping it kills the child (`kill_on_drop`).
pub(crate) struct StdioHandoff {
    pub(crate) child: Child,
    pub(crate) stdin: ChildStdin,
    pub(crate) stdout: StdoutReader,
}

impl StdioConn {
    fn spawn(
        argv: &[String],
        env: &BTreeMap<String, String>,
        cwd: Option<&str>,
    ) -> Result<Arc<StdioConn>, String> {
        let Some((prog, args)) = argv.split_first() else {
            return Err("no command".into());
        };
        let mut cmd = tokio::process::Command::new(prog);
        cmd.args(args)
            .envs(env.iter())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .kill_on_drop(true);
        if let Some(cwd) = cwd {
            cmd.current_dir(cwd);
        }
        #[cfg(unix)]
        cmd.process_group(0);
        let mut child = cmd.spawn().map_err(|e| format!("start {prog:?}: {e}"))?;
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().ok_or("no stdout")?;
        let (tx, rx) = watch::channel(false);
        let (gate_tx, gate_rx) = oneshot::channel::<Option<oneshot::Sender<StdoutReader>>>();
        let mut gate_rx = Some(gate_rx);
        let conn = Arc::new(StdioConn {
            gate: Mutex::new(Some(gate_tx)),
            child: tokio::sync::Mutex::new(Some(child)),
            stdin: tokio::sync::Mutex::new(stdin),
            pending: Mutex::new(HashMap::new()),
            err: Mutex::new(None),
            done: rx,
        });
        let c2 = conn.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            let mut why = "stdio server exited before replying".to_owned();
            loop {
                match lines.next_line().await {
                    Ok(Some(line)) => {
                        let Ok(m) = serde_json::from_str::<Value>(&line) else {
                            continue;
                        };
                        // notifications, server requests and noise are ignored
                        if m.get("method").is_some() || m.get("id").is_none_or(Value::is_null) {
                            continue;
                        }
                        let key = m["id"].to_string();
                        let Some(ch) = lock(&c2.pending).remove(&key) else {
                            continue;
                        };
                        let _ = ch.send((m, line));
                        // first reply delivered: park (not in read()) until the probe decides
                        if let Some(g) = gate_rx.take()
                            && let Ok(Some(hand)) = g.await
                        {
                            let _ = hand.send(lines.into_inner());
                            why = "stdio handed to the frozen path".to_owned();
                            break;
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        why = format!("stdio read: {e}");
                        break;
                    }
                }
            }
            *lock(&c2.err) = Some(why);
            lock(&c2.pending).clear();
            let _ = tx.send(true);
        });
        Ok(conn)
    }

    async fn round_trip(&self, id: i64, mut body: Vec<u8>) -> Result<(Value, String), WireError> {
        let key = id.to_string();
        let (tx, rx) = oneshot::channel();
        {
            if let Some(e) = lock(&self.err).clone() {
                return Err(WireError::Exited(e));
            }
            lock(&self.pending).insert(key.clone(), tx);
        }
        let _cleanup = Cleanup(self, key);
        body.push(b'\n'); // serde_json never emits a raw newline
        {
            let mut w = self.stdin.lock().await;
            let Some(w) = w.as_mut() else {
                return Err(WireError::Other("stdio client closed".into()));
            };
            if let Err(e) = w.write_all(&body).await {
                return Err(self.exited_or(e.to_string()));
            }
            if let Err(e) = w.flush().await {
                return Err(self.exited_or(e.to_string()));
            }
        }
        let mut done = self.done.clone();
        tokio::select! {
            r = rx => r.map_err(|_| self.exited_or("stdio server exited before replying".into())),
            _ = done.wait_for(|d| *d) => Err(self.exited_or(String::new())),
        }
    }

    /// Release the probe gate: the reader keeps serving this session.
    fn release(&self) {
        if let Some(g) = lock(&self.gate).take() {
            let _ = g.send(None);
        }
    }

    /// Take the live child for the frozen path. Only valid once the reader has
    /// delivered the discover reply (it is then parked on the gate).
    async fn handoff(&self) -> Option<StdioHandoff> {
        let g = lock(&self.gate).take()?;
        let (tx, rx) = oneshot::channel();
        g.send(Some(tx)).ok()?;
        let stdout = rx.await.ok()?;
        let stdin = self.stdin.lock().await.take()?;
        let child = self.child.lock().await.take()?;
        Some(StdioHandoff {
            child,
            stdin,
            stdout,
        })
    }

    fn exited_or(&self, fallback: String) -> WireError {
        match lock(&self.err).clone() {
            Some(e) => WireError::Exited(e),
            None if fallback.is_empty() => WireError::Exited("stdio server exited".into()),
            None => WireError::Exited(fallback),
        }
    }

    async fn close(&self) {
        self.stdin.lock().await.take();
        let mut done = self.done.clone();
        let exited = tokio::time::timeout(Duration::from_secs(2), done.wait_for(|d| *d))
            .await
            .is_ok();
        if let Some(mut child) = self.child.lock().await.take() {
            if !exited {
                let _ = child.kill().await;
            }
            let _ = child.wait().await;
        }
    }
}

struct Cleanup<'a>(&'a StdioConn, String);
impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        lock(&self.0.pending).remove(&self.1);
    }
}

pub(crate) fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// `n` random bytes, base64url (no padding).
pub(crate) fn rand_str(n: usize) -> String {
    let mut b = Vec::with_capacity(n + 16);
    while b.len() < n {
        b.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    }
    b.truncate(n);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

pub(crate) fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest as _;
    sha2::Sha256::digest(b)
        .iter()
        .map(|x| format!("{x:02x}"))
        .collect()
}
