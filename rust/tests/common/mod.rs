//! A tiny hermetic HTTP/1.1 server for client tests (no network beyond loopback).
#![allow(dead_code)]

pub mod judge;

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde_json::Value;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[derive(Debug, Clone)]
pub struct Req {
    pub method: String,
    /// Path plus `?query` when present.
    pub path: String,
    /// Lowercased header names.
    pub headers: HashMap<String, String>,
    pub body: Value,
    pub raw: String,
}

impl Req {
    pub fn messages(&self) -> Vec<Value> {
        self.body
            .get("messages")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
    }
    pub fn has_role(&self, role: &str) -> bool {
        self.messages()
            .iter()
            .any(|m| m.get("role").and_then(Value::as_str) == Some(role))
    }
}

#[derive(Debug, Clone)]
pub struct Resp {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: String,
}

impl Resp {
    pub fn json(v: Value) -> Self {
        Resp {
            status: 200,
            headers: vec![("Content-Type".into(), "application/json".into())],
            body: v.to_string(),
        }
    }
    pub fn status(status: u16, body: &str) -> Self {
        Resp {
            status,
            headers: vec![],
            body: body.into(),
        }
    }
    pub fn sse(body: String) -> Self {
        Resp {
            status: 200,
            headers: vec![("Content-Type".into(), "text/event-stream".into())],
            body,
        }
    }
    pub fn header(mut self, k: &str, v: &str) -> Self {
        self.headers.push((k.into(), v.into()));
        self
    }
}

pub type Handler = Arc<dyn Fn(Req) -> Pin<Box<dyn Future<Output = Resp> + Send>> + Send + Sync>;

pub struct Server {
    pub url: String,
}

/// Start a server; `h` answers every request.
pub async fn serve<F, Fut>(h: F) -> Server
where
    F: Fn(Req) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Resp> + Send + 'static,
{
    let h: Handler = Arc::new(move |r| Box::pin(h(r)));
    let l = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else {
                return;
            };
            let h = h.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut tmp = [0u8; 8192];
                let head_end;
                loop {
                    let n = match s.read(&mut tmp).await {
                        Ok(0) | Err(_) => return,
                        Ok(n) => n,
                    };
                    buf.extend_from_slice(&tmp[..n]);
                    if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
                        head_end = i + 4;
                        break;
                    }
                }
                let head = String::from_utf8_lossy(&buf[..head_end]).into_owned();
                let mut lines = head.split("\r\n");
                let first = lines.next().unwrap_or("");
                let mut parts = first.split(' ');
                let method = parts.next().unwrap_or("").to_owned();
                let path = parts.next().unwrap_or("").to_owned();
                let mut headers = HashMap::new();
                for l in lines {
                    if let Some((k, v)) = l.split_once(':') {
                        headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_owned());
                    }
                }
                let len: usize = headers
                    .get("content-length")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0);
                while buf.len() < head_end + len {
                    let n = match s.read(&mut tmp).await {
                        Ok(0) | Err(_) => break,
                        Ok(n) => n,
                    };
                    buf.extend_from_slice(&tmp[..n]);
                }
                let raw = String::from_utf8_lossy(&buf[head_end..]).into_owned();
                let body = serde_json::from_str(&raw).unwrap_or(Value::Null);
                let resp = h(Req {
                    method,
                    path,
                    headers,
                    body,
                    raw,
                })
                .await;
                let mut out = format!(
                    "HTTP/1.1 {} X\r\nContent-Length: {}\r\nConnection: close\r\n",
                    resp.status,
                    resp.body.len()
                );
                for (k, v) in &resp.headers {
                    out.push_str(&format!("{k}: {v}\r\n"));
                }
                out.push_str("\r\n");
                out.push_str(&resp.body);
                let _ = s.write_all(out.as_bytes()).await;
                let _ = s.shutdown().await;
            });
        }
    });
    Server {
        url: format!("http://{addr}"),
    }
}

/// An OpenAI chat-completions answer with a final text.
pub fn oa_text(text: &str) -> Value {
    serde_json::json!({
        "choices": [{"message": {"role": "assistant", "content": text}}],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
    })
}

/// An OpenAI answer calling tools: `(id, name, argsJSON)`.
pub fn oa_calls(calls: &[(&str, &str, &str)]) -> Value {
    let tcs: Vec<Value> = calls
        .iter()
        .map(|(id, n, a)| serde_json::json!({"id": id, "type": "function", "function": {"name": n, "arguments": a}}))
        .collect();
    serde_json::json!({
        "choices": [{"message": {"role": "assistant", "content": null, "tool_calls": tcs}}],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
    })
}

pub fn sse_lines(chunks: &[Value]) -> String {
    let mut s = String::new();
    for c in chunks {
        s.push_str(&format!("data: {c}\n\n"));
    }
    s.push_str("data: [DONE]\n\n");
    s
}

pub async fn empty_toolkit() -> toolnexus::Toolkit {
    toolnexus::create_toolkit(toolnexus::ToolkitOptions {
        builtins: Some(toolnexus::BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap()
}
