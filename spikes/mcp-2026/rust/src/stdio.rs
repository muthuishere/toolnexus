//! stdio transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC message per
//! line on stdin/stdout. stderr is the server's log stream: passed through (or captured), never
//! parsed. No headers, no auth — the `_meta` carries the version.

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, Command};
use tokio::sync::{oneshot, watch};

use crate::http::DEFAULT_TIMEOUT;
use crate::server::{Server, ToolContext, rpc_message, unsupported};
use crate::{Client, Error, META_VERSION, Obj, PROTOCOL_VERSION};

pub(crate) struct StdioConn {
    child: tokio::sync::Mutex<Option<Child>>,
    stdin: tokio::sync::Mutex<Option<ChildStdin>>,
    pending: Mutex<HashMap<String, oneshot::Sender<Value>>>,
    err: Mutex<Option<String>>, // set once stdout closes
    done: watch::Receiver<bool>,
}

impl Client {
    /// Starts `cmd` and returns a Client with the same API as `Client::new`. The server's stderr
    /// passes through to ours. Call `close` to stop the process.
    pub fn stdio(cmd: Command) -> Result<Client, Error> {
        Self::spawn_stdio(cmd, None)
    }

    /// Like `stdio`, but the server's stderr is captured into `sink` (never parsed).
    pub fn stdio_capture(cmd: Command, sink: Arc<Mutex<String>>) -> Result<Client, Error> {
        Self::spawn_stdio(cmd, Some(sink))
    }

    fn spawn_stdio(mut cmd: Command, sink: Option<Arc<Mutex<String>>>) -> Result<Client, Error> {
        cmd.stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .kill_on_drop(true);
        if sink.is_some() {
            cmd.stderr(Stdio::piped());
        }
        let mut child = cmd
            .spawn()
            .map_err(|e| Error::msg(format!("spawn stdio server: {e}")))?;
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().unwrap();
        if let (Some(sink), Some(mut err)) = (sink, child.stderr.take()) {
            tokio::spawn(async move {
                let mut buf = vec![0u8; 4096];
                use tokio::io::AsyncReadExt;
                while let Ok(n) = err.read(&mut buf).await {
                    if n == 0 {
                        break;
                    }
                    sink.lock()
                        .unwrap()
                        .push_str(&String::from_utf8_lossy(&buf[..n]));
                }
            });
        }
        let (tx, rx) = watch::channel(false);
        let conn = Arc::new(StdioConn {
            child: tokio::sync::Mutex::new(Some(child)),
            stdin: tokio::sync::Mutex::new(stdin),
            pending: Mutex::new(HashMap::new()),
            err: Mutex::new(None),
            done: rx,
        });
        let c2 = conn.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stdout).lines();
            let mut why = "stdio server closed its stdout".to_string();
            loop {
                match lines.next_line().await {
                    Ok(Some(line)) => {
                        let Ok(m) = serde_json::from_str::<Value>(&line) else {
                            continue;
                        };
                        // not a response (notification / server request / noise): ignored
                        if m.get("method").is_some() || m.get("id").is_none_or(Value::is_null) {
                            continue;
                        }
                        let key = m["id"].to_string();
                        if let Some(ch) = c2.pending.lock().unwrap().remove(&key) {
                            let _ = ch.send(m);
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        why = format!("stdio read: {e}");
                        break;
                    }
                }
            }
            *c2.err.lock().unwrap() = Some(why);
            let _ = tx.send(true);
        });
        let mut c = Client::new("");
        c.stdio = Some(conn);
        Ok(c)
    }

    /// Stops a stdio server (close stdin, then kill after 2 s); no-op for HTTP clients.
    pub async fn close(&self) {
        let Some(s) = &self.stdio else { return };
        s.stdin.lock().await.take();
        let mut done = s.done.clone();
        let exited = tokio::time::timeout(Duration::from_secs(2), done.wait_for(|d| *d))
            .await
            .is_ok();
        if let Some(mut child) = s.child.lock().await.take() {
            if !exited {
                let _ = child.kill().await;
            }
            let _ = child.wait().await;
        }
    }
}

impl StdioConn {
    /// Writes one line and waits for the response with the same id. Never retried.
    pub(crate) async fn round_trip(&self, id: i64, mut body: Vec<u8>) -> Result<Value, Error> {
        let key = id.to_string();
        let (tx, rx) = oneshot::channel();
        {
            if let Some(e) = self.err.lock().unwrap().clone() {
                return Err(Error::Other(e));
            }
            self.pending.lock().unwrap().insert(key.clone(), tx);
        }
        let _cleanup = Cleanup(self, key);
        body.push(b'\n'); // serde_json never emits a raw newline
        {
            let mut w = self.stdin.lock().await;
            let Some(w) = w.as_mut() else {
                return Err(Error::msg("stdio client closed"));
            };
            w.write_all(&body)
                .await
                .map_err(|e| Error::msg(e.to_string()))?;
            w.flush().await.map_err(|e| Error::msg(e.to_string()))?;
        }
        let mut done = self.done.clone();
        tokio::select! {
            r = rx => {
                let m = r.map_err(|_| Error::msg("stdio server closed its stdout"))?;
                let has = |k: &str| m.get(k).is_some_and(|v| !v.is_null());
                if !has("result") && !has("error") {
                    return Err(Error::msg("response has neither result nor error"));
                }
                Ok(m)
            }
            _ = done.wait_for(|d| *d) => Err(Error::Other(self.err.lock().unwrap().clone().unwrap_or_default())),
            _ = tokio::time::sleep(DEFAULT_TIMEOUT) => Err(Error::msg("stdio request timed out")),
        }
    }
}

struct Cleanup<'a>(&'a StdioConn, String);
impl Drop for Cleanup<'_> {
    fn drop(&mut self) {
        self.0.pending.lock().unwrap().remove(&self.1);
    }
}

/// Serves `s` as newline-delimited JSON-RPC over in/out until `input` closes. No auth and no HTTP
/// headers exist on stdio; only the `_meta` protocol version is checked.
pub async fn serve_stdio<R, W>(s: &Server, input: R, mut out: W)
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut lines = BufReader::new(input).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let msg = match serde_json::from_str::<Value>(&line) {
            Ok(Value::Object(req)) => {
                let id = req.get("id").cloned().unwrap_or(Value::Null);
                if id.is_null() {
                    continue;
                }
                let method = req
                    .get("method")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let params: Obj = req
                    .get("params")
                    .and_then(Value::as_object)
                    .cloned()
                    .unwrap_or_default();
                let mv = params
                    .get("_meta")
                    .and_then(|m| m.get(META_VERSION))
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                if mv != PROTOCOL_VERSION {
                    rpc_message(id, None, Some(unsupported(mv)))
                } else {
                    match s.dispatch(&ToolContext::default(), method, &params) {
                        Ok(v) => rpc_message(id, Some(v), None),
                        Err(e) => rpc_message(id, None, Some(e)),
                    }
                }
            }
            _ => {
                json!({"jsonrpc": "2.0", "id": null, "error": {"code": -32700, "message": "Parse error"}})
            }
        };
        let mut b = serde_json::to_vec(&msg).unwrap();
        b.push(b'\n');
        if out.write_all(&b).await.is_err() || out.flush().await.is_err() {
            return;
        }
    }
}
