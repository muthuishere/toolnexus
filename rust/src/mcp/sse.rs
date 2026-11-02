//! Legacy HTTP+SSE MCP client transport (protocol 2024-11-05), the fallback a
//! remote server gets when streamable HTTP fails — as Go does via mcp-go's SSE
//! transport. rmcp ships no SSE client, so this is a minimal one:
//!
//! 1. `GET <url>` with `Accept: text/event-stream`; the first `endpoint` event's
//!    data is the URL (resolved against `<url>`) that client messages POST to.
//! 2. Every `message` event's data is one server JSON-RPC message.
//! 3. Each client message is `POST`ed as JSON to the endpoint (2xx expected).
//!
//! Header values (already `${ENV}`-expanded) are sent on both and never logged.

use std::time::Duration;

use futures_util::StreamExt;
use rmcp::RoleClient;
use rmcp::service::{RxJsonRpcMessage, TxJsonRpcMessage};
use rmcp::transport::Transport;
use tokio::sync::mpsc;

/// A transport error (connect, POST or protocol).
#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct SseError(pub String);

/// The legacy SSE client transport.
pub struct SseClientTransport {
    http: reqwest::Client,
    endpoint: reqwest::Url,
    headers: http::HeaderMap,
    rx: mpsc::Receiver<RxJsonRpcMessage<RoleClient>>,
    reader: tokio::task::JoinHandle<()>,
}

impl Drop for SseClientTransport {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

/// Incremental SSE event parser (`event:` / `data:` lines, blank line dispatches).
#[derive(Default)]
struct SseParser {
    buf: String,
    event: String,
    data: Vec<String>,
}

impl SseParser {
    fn push(&mut self, chunk: &str) -> Vec<(String, String)> {
        self.buf.push_str(chunk);
        let mut out = Vec::new();
        while let Some(i) = self.buf.find('\n') {
            let mut line: String = self.buf.drain(..=i).collect();
            line.pop();
            if line.ends_with('\r') {
                line.pop();
            }
            if line.is_empty() {
                if !self.data.is_empty() || !self.event.is_empty() {
                    let ev = if self.event.is_empty() {
                        "message".to_owned()
                    } else {
                        std::mem::take(&mut self.event)
                    };
                    out.push((ev, self.data.join("\n")));
                }
                self.event.clear();
                self.data.clear();
            } else if let Some(v) = line.strip_prefix("event:") {
                self.event = v.trim_start().to_owned();
            } else if let Some(v) = line.strip_prefix("data:") {
                self.data.push(v.strip_prefix(' ').unwrap_or(v).to_owned());
            }
        }
        out
    }
}

impl SseClientTransport {
    /// Open the event stream and wait (up to 30 s) for the `endpoint` event.
    pub async fn connect(url: &str, headers: http::HeaderMap) -> Result<Self, SseError> {
        let base = reqwest::Url::parse(url).map_err(|e| SseError(format!("invalid url: {e}")))?;
        let http = reqwest::Client::new();
        let resp = http
            .get(base.clone())
            .headers(headers.clone())
            .header("Accept", "text/event-stream")
            .send()
            .await
            .map_err(|e| SseError(e.to_string()))?;
        if !resp.status().is_success() {
            return Err(SseError(format!(
                "unexpected status code: {}",
                resp.status().as_u16()
            )));
        }
        let mut stream = resp.bytes_stream();
        let mut parser = SseParser::default();
        let mut pending: Vec<(String, String)> = Vec::new();
        let endpoint = tokio::time::timeout(Duration::from_secs(30), async {
            loop {
                let Some(chunk) = stream.next().await else {
                    return Err(SseError("stream closed before the endpoint event".into()));
                };
                let chunk = chunk.map_err(|e| SseError(e.to_string()))?;
                let mut events = parser.push(&String::from_utf8_lossy(&chunk)).into_iter();
                while let Some((ev, data)) = events.next() {
                    if ev == "endpoint" {
                        pending.extend(events);
                        return base
                            .join(data.trim())
                            .map_err(|e| SseError(format!("invalid endpoint: {e}")));
                    }
                }
            }
        })
        .await
        .map_err(|_| SseError("timed out waiting for the endpoint event".into()))??;

        let (tx, rx) = mpsc::channel(64);
        let reader = tokio::spawn(async move {
            let deliver = |data: String, tx: &mpsc::Sender<_>| {
                let msg: Option<RxJsonRpcMessage<RoleClient>> = serde_json::from_str(&data).ok();
                let tx = tx.clone();
                async move {
                    match msg {
                        Some(m) => tx.send(m).await.is_ok(),
                        None => true,
                    }
                }
            };
            for (ev, data) in pending {
                if ev == "message" && !deliver(data, &tx).await {
                    return;
                }
            }
            while let Some(Ok(chunk)) = stream.next().await {
                for (ev, data) in parser.push(&String::from_utf8_lossy(&chunk)) {
                    if ev == "message" && !deliver(data, &tx).await {
                        return;
                    }
                }
            }
        });
        Ok(Self {
            http,
            endpoint,
            headers,
            rx,
            reader,
        })
    }

    /// The URL client messages are POSTed to.
    pub fn endpoint(&self) -> &str {
        self.endpoint.as_str()
    }
}

impl Transport<RoleClient> for SseClientTransport {
    type Error = SseError;

    fn send(
        &mut self,
        item: TxJsonRpcMessage<RoleClient>,
    ) -> impl Future<Output = Result<(), Self::Error>> + Send + 'static {
        let req = self
            .http
            .post(self.endpoint.clone())
            .headers(self.headers.clone())
            .header("Content-Type", "application/json")
            .body(serde_json::to_string(&item).unwrap_or_default());
        async move {
            let resp = req.send().await.map_err(|e| SseError(e.to_string()))?;
            if !resp.status().is_success() {
                return Err(SseError(format!(
                    "unexpected status code: {}",
                    resp.status().as_u16()
                )));
            }
            Ok(())
        }
    }

    fn receive(&mut self) -> impl Future<Output = Option<RxJsonRpcMessage<RoleClient>>> + Send {
        self.rx.recv()
    }

    async fn close(&mut self) -> Result<(), Self::Error> {
        self.reader.abort();
        Ok(())
    }
}
