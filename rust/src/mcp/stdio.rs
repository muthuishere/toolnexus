//! The frozen (pre-2026) MCP client over stdio, in-house — no MCP SDK on this
//! path (SPEC §2.F, §2.2a).
//!
//! One newline-delimited JSON-RPC connection per server. A caller writes its
//! request straight to the child's stdin and waits on a oneshot; ONE reader
//! task reads stdout and routes each response to its caller by `id`. There is
//! no service task or send queue in between (the latency rmcp's design cost).
//!
//! What it does, matching what the SDK did on this path:
//! - `initialize` (+ the `elicitation` capability, form and url, when a §10
//!   `wait_for` exists) then `notifications/initialized`;
//! - `tools/list` / `resources/list` following `nextCursor`;
//! - `tools/call` with a fresh `_meta.progressToken`; the timeout is an IDLE
//!   timeout that a matching `notifications/progress` resets. On timeout and
//!   on caller cancellation it sends `notifications/cancelled`;
//! - server requests: `ping` → `{}`, `roots/list` → `{roots: []}`,
//!   `elicitation/create` → the §10 bridge (decline without a `wait_for`),
//!   anything else → `-32601` naming the method;
//! - notifications other than progress (logging, list_changed, …) are ignored;
//! - a dead child fails every pending and later call with `Transport closed`;
//! - close: close stdin, wait up to 3 s, then kill the process group / Job
//!   Object and reap it.

use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use process_wrap::tokio::ChildWrapper;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::process::ChildStdin;
use tokio::sync::{Notify, oneshot, watch};
use tokio_util::sync::CancellationToken;

use super::elicit::{answer_to_elicit_result, elicitation_to_request};
use super::wire::{StdioHandoff, lock, spawn_child};
use crate::client::WaitForFn;

/// The protocol version offered in `initialize` (the newest with a handshake).
pub(crate) const LEGACY_PROTOCOL_VERSION: &str = "2025-11-25";

/// Grace between closing stdin and killing the process group.
const CLOSE_GRACE: Duration = Duration::from_secs(3);

/// Why a request failed. The `Display` texts are the ones the SDK path had.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum CallError {
    /// A JSON-RPC error response.
    Rpc {
        code: i64,
        message: String,
        data: Option<Value>,
    },
    /// The child closed stdout / exited, or stdin is gone.
    Closed,
    /// No response and no progress within the idle timeout.
    Timeout(Duration),
    /// The caller's cancellation token fired.
    Cancelled,
}

impl std::fmt::Display for CallError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CallError::Rpc {
                code,
                message,
                data,
            } => {
                write!(f, "Mcp error: {code}: {message}")?;
                if let Some(d) = data {
                    write!(f, "({d})")?;
                }
                Ok(())
            }
            CallError::Closed => f.write_str("Transport closed"),
            CallError::Timeout(t) => write!(
                f,
                "mcp idle timeout after {}ms without progress",
                t.as_millis()
            ),
            CallError::Cancelled => f.write_str("cancelled"),
        }
    }
}

type Reply = Result<Value, CallError>;

struct Slot {
    tx: oneshot::Sender<Reply>,
    /// Signalled by a `notifications/progress` carrying this call's token.
    progress: Option<Arc<Notify>>,
}

/// `None` once the reader has stopped: no new call may register.
type Pending = Mutex<Option<HashMap<i64, Slot>>>;

/// A connected frozen-path stdio server.
pub(crate) struct StdioClient {
    stdin: Arc<tokio::sync::Mutex<Option<ChildStdin>>>,
    pending: Arc<Pending>,
    done: watch::Receiver<bool>,
    child: tokio::sync::Mutex<Option<Box<dyn ChildWrapper>>>,
    next_id: AtomicI64,
    /// Advertise `elicitation` at initialize: a §10 `wait_for` exists.
    elicit: bool,
}

#[derive(Serialize)]
struct OutRequest<'a> {
    jsonrpc: &'static str,
    id: i64,
    method: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    params: Option<&'a Value>,
}

/// One inbound line, split without building a top-level map.
#[derive(Deserialize)]
struct Incoming {
    #[serde(default)]
    id: Option<Value>,
    #[serde(default)]
    method: Option<String>,
    #[serde(default)]
    params: Option<Value>,
    #[serde(default)]
    result: Option<Value>,
    #[serde(default)]
    error: Option<Value>,
}

impl StdioClient {
    /// Start `argv` and run the `initialize` handshake.
    pub(crate) async fn spawn(
        argv: &[String],
        env: &BTreeMap<String, String>,
        cwd: Option<&str>,
        wait_for: Option<WaitForFn>,
    ) -> Result<Arc<StdioClient>, String> {
        let (child, stdin, stdout) = spawn_child(argv, env, cwd)?;
        let c = StdioClient::start(child, stdin, BufReader::new(stdout), wait_for);
        c.initialize().await?;
        Ok(c)
    }

    /// Continue on the discover probe's child (SPEC §2.2a): no new process.
    pub(crate) async fn from_handoff(
        h: StdioHandoff,
        wait_for: Option<WaitForFn>,
    ) -> Result<Arc<StdioClient>, String> {
        let c = StdioClient::start(h.child, h.stdin, h.stdout, wait_for);
        c.initialize().await?;
        Ok(c)
    }

    fn start<R>(
        child: Box<dyn ChildWrapper>,
        stdin: ChildStdin,
        stdout: BufReader<R>,
        wait_for: Option<WaitForFn>,
    ) -> Arc<StdioClient>
    where
        R: AsyncRead + Unpin + Send + 'static,
    {
        let (done_tx, done) = watch::channel(false);
        let c = Arc::new(StdioClient {
            stdin: Arc::new(tokio::sync::Mutex::new(Some(stdin))),
            pending: Arc::new(Mutex::new(Some(HashMap::new()))),
            done,
            child: tokio::sync::Mutex::new(Some(child)),
            next_id: AtomicI64::new(0),
            elicit: wait_for.is_some(),
        });
        tokio::spawn(read_loop(
            stdout,
            c.pending.clone(),
            c.stdin.clone(),
            wait_for,
            done_tx,
        ));
        c
    }

    async fn initialize(&self) -> Result<(), String> {
        let caps = if self.elicit {
            json!({"elicitation": {"form": {}, "url": {}}})
        } else {
            json!({})
        };
        let params = json!({
            "protocolVersion": LEGACY_PROTOCOL_VERSION,
            "capabilities": caps,
            "clientInfo": {"name": "toolnexus", "version": env!("CARGO_PKG_VERSION")},
        });
        let r = self
            .request("initialize", Some(&params), None, None)
            .await
            .map_err(|e| format!("initialize: {e}"))?;
        if !r.is_object() {
            return Err("initialize: expected an initialize result".into());
        }
        self.notify("notifications/initialized", None)
            .await
            .map_err(|e| format!("initialized: {e}"))
    }

    /// Every item of a paginated list (`tools/list` → `tools`, …).
    pub(crate) async fn list_all(&self, method: &str, key: &str) -> Result<Vec<Value>, CallError> {
        let mut out = Vec::new();
        let mut cursor: Option<Value> = None;
        loop {
            let params = cursor.take().map(|c| json!({ "cursor": c }));
            let mut r = self.request(method, params.as_ref(), None, None).await?;
            if let Some(Value::Array(items)) = r.get_mut(key).map(Value::take) {
                out.extend(items);
            }
            match r.get_mut("nextCursor").map(Value::take) {
                Some(c @ Value::String(_)) => cursor = Some(c),
                _ => return Ok(out),
            }
        }
    }

    /// `tools/call` with an idle `timeout` and the caller's cancellation.
    pub(crate) async fn call_tool(
        &self,
        name: &str,
        arguments: Value,
        timeout: Duration,
        cancel: &CancellationToken,
    ) -> Reply {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let params = json!({
            "name": name,
            "arguments": arguments,
            "_meta": {"progressToken": id},
        });
        self.request_id(id, "tools/call", Some(&params), Some(timeout), Some(cancel))
            .await
    }

    /// One request. `idle`: the progress-reset timeout (`None` ⇒ unbounded,
    /// the caller bounds it); `cancel`: the caller's token.
    pub(crate) async fn request(
        &self,
        method: &str,
        params: Option<&Value>,
        idle: Option<Duration>,
        cancel: Option<&CancellationToken>,
    ) -> Reply {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.request_id(id, method, params, idle, cancel).await
    }

    async fn request_id(
        &self,
        id: i64,
        method: &str,
        params: Option<&Value>,
        idle: Option<Duration>,
        cancel: Option<&CancellationToken>,
    ) -> Reply {
        let mut line = serde_json::to_vec(&OutRequest {
            jsonrpc: "2.0",
            id,
            method,
            params,
        })
        .map_err(|_| CallError::Closed)?;
        line.push(b'\n'); // serde_json never emits a raw newline
        let progress = idle.map(|_| Arc::new(Notify::new()));
        let (tx, mut rx) = oneshot::channel();
        {
            let mut p = lock(&self.pending);
            let Some(map) = p.as_mut() else {
                return Err(CallError::Closed);
            };
            map.insert(
                id,
                Slot {
                    tx,
                    progress: progress.clone(),
                },
            );
        }
        let guard = SlotGuard(&self.pending, id);
        write_line(&self.stdin, &line).await?;
        let never = CancellationToken::new();
        let cancel = cancel.unwrap_or(&never);
        let outcome = match (idle, progress) {
            (Some(idle), Some(progress)) => loop {
                tokio::select! {
                    biased;
                    r = &mut rx => break r.unwrap_or(Err(CallError::Closed)),
                    _ = cancel.cancelled() => break Err(CallError::Cancelled),
                    _ = progress.notified() => continue,
                    _ = tokio::time::sleep(idle) => break Err(CallError::Timeout(idle)),
                }
            },
            _ => tokio::select! {
                biased;
                r = &mut rx => r.unwrap_or(Err(CallError::Closed)),
                _ = cancel.cancelled() => Err(CallError::Cancelled),
            },
        };
        drop(guard);
        let reason = match &outcome {
            Err(CallError::Timeout(_)) => Some("request timeout"),
            Err(CallError::Cancelled) => Some("cancelled"),
            _ => None,
        };
        if let Some(reason) = reason {
            let p = json!({"requestId": id, "reason": reason});
            let _ = self.notify("notifications/cancelled", Some(p)).await;
        }
        outcome
    }

    async fn notify(&self, method: &str, params: Option<Value>) -> Result<(), CallError> {
        let mut msg = json!({"jsonrpc": "2.0", "method": method});
        if let Some(p) = params {
            msg["params"] = p;
        }
        let mut line = serde_json::to_vec(&msg).map_err(|_| CallError::Closed)?;
        line.push(b'\n');
        write_line(&self.stdin, &line).await
    }

    /// Close stdin, give the child [`CLOSE_GRACE`] to exit, then kill its
    /// process group / Job Object and reap it. Idempotent.
    pub(crate) async fn close(&self) {
        self.stdin.lock().await.take();
        let Some(mut child) = self.child.lock().await.take() else {
            return;
        };
        let mut done = self.done.clone();
        let _ = tokio::time::timeout(CLOSE_GRACE, done.wait_for(|d| *d)).await;
        // the reader saw EOF or the grace ran out: stop the whole group
        // (also any grandchild the server left behind) and reap the leader
        let _ = child.start_kill();
        let _ = child.wait().await;
    }
}

/// Removes the call's slot however the caller's future ends.
struct SlotGuard<'a>(&'a Pending, i64);
impl Drop for SlotGuard<'_> {
    fn drop(&mut self) {
        if let Some(m) = lock(self.0).as_mut() {
            m.remove(&self.1);
        }
    }
}

async fn write_line(
    stdin: &tokio::sync::Mutex<Option<ChildStdin>>,
    line: &[u8],
) -> Result<(), CallError> {
    let mut w = stdin.lock().await;
    let Some(w) = w.as_mut() else {
        return Err(CallError::Closed);
    };
    w.write_all(line).await.map_err(|_| CallError::Closed)?;
    w.flush().await.map_err(|_| CallError::Closed)
}

fn rpc_error(e: &Value) -> CallError {
    CallError::Rpc {
        code: e.get("code").and_then(Value::as_i64).unwrap_or(0),
        message: e
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        data: e.get("data").filter(|d| !d.is_null()).cloned(),
    }
}

async fn read_loop<R>(
    mut stdout: BufReader<R>,
    pending: Arc<Pending>,
    stdin: Arc<tokio::sync::Mutex<Option<ChildStdin>>>,
    wait_for: Option<WaitForFn>,
    done: watch::Sender<bool>,
) where
    R: AsyncRead + Unpin + Send + 'static,
{
    let mut buf = Vec::with_capacity(8192);
    loop {
        buf.clear();
        match stdout.read_until(b'\n', &mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        // anything that is not a JSON-RPC message (log noise) is skipped
        let Ok(m) = serde_json::from_slice::<Incoming>(&buf) else {
            continue;
        };
        match (m.method, m.id) {
            // a response to one of our requests
            (None, Some(id)) => {
                let Some(id) = id.as_i64() else { continue };
                let slot = lock(&pending).as_mut().and_then(|p| p.remove(&id));
                if let Some(slot) = slot {
                    let reply = match (m.error.filter(|e| !e.is_null()), m.result) {
                        (Some(e), _) => Err(rpc_error(&e)),
                        (None, Some(r)) => Ok(r),
                        (None, None) => Ok(Value::Null),
                    };
                    let _ = slot.tx.send(reply);
                }
            }
            (Some(method), None) => {
                if method == "notifications/progress" {
                    let token = m
                        .params
                        .as_ref()
                        .and_then(|p| p.get("progressToken"))
                        .and_then(Value::as_i64);
                    if let Some(t) = token
                        && let Some(n) = lock(&pending)
                            .as_ref()
                            .and_then(|p| p.get(&t))
                            .and_then(|s| s.progress.clone())
                    {
                        n.notify_one();
                    }
                }
                // logging, list_changed and the rest: nothing to do
            }
            (Some(method), Some(id)) => {
                server_request(&method, id, m.params, &stdin, &wait_for).await;
            }
            (None, None) => {}
        }
    }
    // the child is gone: every pending and later call fails, never hangs
    lock(&pending).take();
    let _ = done.send(true);
}

/// Answer a server → client request (what the SDK's default handler did).
async fn server_request(
    method: &str,
    id: Value,
    params: Option<Value>,
    stdin: &Arc<tokio::sync::Mutex<Option<ChildStdin>>>,
    wait_for: &Option<WaitForFn>,
) {
    let line = match method {
        "ping" => reply_line(&id, json!({"result": {}})),
        "roots/list" => reply_line(&id, json!({"result": {"roots": []}})),
        "elicitation/create" => {
            let Some(w) = wait_for.clone() else {
                let line = reply_line(&id, json!({"result": {"action": "decline"}}));
                let _ = write_line(stdin, &line).await;
                return;
            };
            // it may wait on a human: off the reader, which keeps routing
            let params = params.unwrap_or(Value::Null);
            let stdin = stdin.clone();
            tokio::spawn(async move {
                let body = match w(elicitation_to_request(&params)).await {
                    Ok(answer) => json!({"result": answer_to_elicit_result(&answer)}),
                    Err(e) => json!({"error": {"code": -32603, "message": e}}),
                };
                let line = reply_line(&id, body);
                let _ = write_line(&stdin, &line).await;
            });
            return;
        }
        other => reply_line(&id, json!({"error": {"code": -32601, "message": other}})),
    };
    let _ = write_line(stdin, &line).await;
}

/// A response line to a server request: `{jsonrpc, id}` + `body`.
fn reply_line(id: &Value, body: Value) -> Vec<u8> {
    let mut m = serde_json::Map::new();
    m.insert("jsonrpc".into(), json!("2.0"));
    m.insert("id".into(), id.clone());
    if let Value::Object(o) = body {
        m.extend(o);
    }
    let mut line = serde_json::to_vec(&Value::Object(m)).unwrap_or_default();
    line.push(b'\n');
    line
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn error_texts_match_the_sdk_path() {
        let rpc = |data| CallError::Rpc {
            code: -32602,
            message: "bad".into(),
            data,
        };
        assert_eq!(rpc(None).to_string(), "Mcp error: -32602: bad");
        assert_eq!(
            rpc(Some(json!({"z": 1}))).to_string(),
            r#"Mcp error: -32602: bad({"z":1})"#
        );
        assert_eq!(CallError::Closed.to_string(), "Transport closed");
        assert_eq!(
            CallError::Timeout(Duration::from_millis(300)).to_string(),
            "mcp idle timeout after 300ms without progress"
        );
        assert_eq!(CallError::Cancelled.to_string(), "cancelled");
        assert_eq!(
            rpc_error(&json!({"code": -32601, "message": "m", "data": null})),
            CallError::Rpc {
                code: -32601,
                message: "m".into(),
                data: None
            }
        );
    }
}
