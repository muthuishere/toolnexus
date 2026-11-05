//! ACP agent as an in-process model (Go `ACPClient.Generate` / `GenerateStream`):
//! pass [`acp_generate`] / [`acp_generate_stream`] as `InProcessOptions.generate`
//! / `.generate_stream` and a `Client` built on them runs and streams. The
//! agent's own tool calls arrive as `tool_call` / `tool_result` events with
//! `via: "acp"` — the agent ran them; the loop never does.

use std::sync::Arc;

use serde_json::Value;

use super::StreamEvent;
use super::inprocess::{GenerateFn, GenerateStreamFn, InProcessRequest, InProcessResponse};
use crate::acp::{AcpClient, AcpStreamEvent};

/// Flatten loop messages into ACP `(role, content)` pairs; array content keeps its text parts.
pub fn acp_messages(req: &InProcessRequest) -> Vec<(String, String)> {
    req.messages
        .iter()
        .map(|m| {
            let role = m
                .get("role")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            let content = match m.get("content") {
                Some(Value::String(s)) => s.clone(),
                Some(Value::Array(parts)) => parts
                    .iter()
                    .filter_map(|p| p.get("text").and_then(Value::as_str))
                    .collect::<Vec<_>>()
                    .join("\n"),
                _ => String::new(),
            };
            (role, content)
        })
        .collect()
}

pub(crate) fn to_stream_event(ev: AcpStreamEvent) -> StreamEvent {
    match ev {
        AcpStreamEvent::Text { delta } => StreamEvent::text(delta),
        AcpStreamEvent::Thought { delta, via } => StreamEvent {
            kind: "thought".into(),
            delta,
            via: via.into(),
            ..Default::default()
        },
        AcpStreamEvent::ToolCall {
            id,
            name,
            args,
            via,
        } => StreamEvent {
            kind: "tool_call".into(),
            id,
            name,
            args,
            via: via.into(),
            ..Default::default()
        },
        AcpStreamEvent::ToolResult {
            id,
            name,
            output,
            is_error,
            via,
        } => StreamEvent {
            kind: "tool_result".into(),
            id,
            name,
            output,
            is_error,
            via: via.into(),
            ..Default::default()
        },
    }
}

/// A non-streaming in-process `generate` over one warm ACP session.
pub fn acp_generate(client: Arc<AcpClient>) -> GenerateFn {
    Arc::new(move |req: InProcessRequest| {
        let client = client.clone();
        Box::pin(async move {
            let text = client
                .generate(&acp_messages(&req))
                .await
                .map_err(|e| e.to_string())?;
            Ok(InProcessResponse {
                content: text,
                ..Default::default()
            })
        })
    })
}

/// A streaming, cancellable in-process `generate_stream` over one warm ACP session.
pub fn acp_generate_stream(client: Arc<AcpClient>) -> GenerateStreamFn {
    Arc::new(move |cancel, req: InProcessRequest, emit| {
        let client = client.clone();
        Box::pin(async move {
            let text = client
                .generate_stream(&acp_messages(&req), &cancel, |ev| emit(to_stream_event(ev)))
                .await
                .map_err(|e| e.to_string())?;
            Ok(InProcessResponse {
                content: text,
                ..Default::default()
            })
        })
    })
}
