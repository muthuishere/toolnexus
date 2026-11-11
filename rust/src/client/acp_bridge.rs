//! ACP agent as an in-process model (Go `ACPClient.Generate` / `GenerateStream`):
//! pass [`acp_generate`] / [`acp_generate_stream`] as `InProcessOptions.generate`
//! / `.generate_stream` and a `Client` built on them runs and streams. The
//! agent's own tool calls arrive as `tool_call` / `tool_result` events with
//! `via: "acp"` — the agent ran them; the loop never does.

use std::sync::Arc;

use serde_json::Value;
use tokio_util::sync::CancellationToken;

use super::StreamEvent;
use super::inprocess::{GenerateFn, GenerateStreamFn, InProcessRequest, InProcessResponse};
use crate::acp::{AcpClient, AcpStreamEvent, render_prompt};
use crate::mcp::describe_part;
use crate::tool::ContentPart;

/// Flatten loop messages into ACP `(role, content)` pairs. In array content a
/// text part renders as its text and each media part as its §1B description
/// (never its bytes), joined by `\n`; anything else is JSON-encoded.
pub fn acp_messages(req: &InProcessRequest) -> Vec<(String, String)> {
    acp_prompt(req).0
}

/// [`acp_messages`] plus the media parts, in message order, to travel as
/// native ACP blocks (SPEC §8C).
fn acp_prompt(req: &InProcessRequest) -> (Vec<(String, String)>, Vec<ContentPart>) {
    let mut parts = Vec::new();
    let msgs = req
        .messages
        .iter()
        .map(|m| {
            let role = m
                .get("role")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            let content = match m.get("content") {
                Some(Value::String(s)) => s.clone(),
                Some(Value::Null) | None => String::new(),
                Some(Value::Array(items)) => items
                    .iter()
                    .map(|item| match wire_part(item) {
                        Some(p) if p.kind == "text" => p.text,
                        Some(p) => {
                            let d = describe_part(&p);
                            parts.push(p);
                            d
                        }
                        None => item.to_string(),
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
                Some(other) => other.to_string(),
            };
            (role, content)
        })
        .collect();
    (msgs, parts)
}

/// `data:<mime>;base64,<payload>` with a non-empty mime ⇒ (mime, payload);
/// anything else is not a data URL (SPEC §8C).
fn parse_base64_data_url(u: &str) -> Option<(&str, &str)> {
    let (meta, payload) = u.strip_prefix("data:")?.split_once(',')?;
    let mime = meta.strip_suffix(";base64")?;
    (!mime.is_empty()).then_some((mime, payload))
}

/// One OpenAI chat content block read back into a §1B part.
fn wire_part(item: &Value) -> Option<ContentPart> {
    let s = |ptr: &str| item.pointer(ptr).and_then(Value::as_str).unwrap_or("");
    match item.get("type").and_then(Value::as_str)? {
        "text" => Some(ContentPart::text(s("/text"))),
        "image_url" => {
            let u = s("/image_url/url");
            if let Some((mime, data)) = parse_base64_data_url(u) {
                return Some(ContentPart {
                    kind: "image".into(),
                    mime_type: mime.into(),
                    data: data.into(),
                    ..Default::default()
                });
            }
            // URL-only (any other URL, a non-base64 data: URL included): an
            // EMPTY mimeType, never inferred from the URL (SPEC §8C).
            (!u.is_empty()).then(|| ContentPart {
                kind: "image".into(),
                url: u.into(),
                ..Default::default()
            })
        }
        "input_audio" => {
            let mime = match s("/input_audio/format") {
                "mp3" => "audio/mpeg".to_owned(),
                f => format!("audio/{f}"),
            };
            Some(ContentPart {
                kind: "audio".into(),
                mime_type: mime,
                data: s("/input_audio/data").into(),
                ..Default::default()
            })
        }
        "file" => {
            let (mime, data) = parse_base64_data_url(s("/file/file_data"))?;
            let kind = if mime.starts_with("image/") {
                "image"
            } else if mime.starts_with("audio/") {
                "audio"
            } else {
                "file"
            };
            Some(ContentPart {
                kind: kind.into(),
                mime_type: mime.into(),
                data: data.into(),
                name: s("/file/filename").into(),
                ..Default::default()
            })
        }
        _ => None,
    }
}

/// One turn over the bridge: rendered text + native media blocks; inbound
/// media comes back as the response's `files`.
async fn turn<F>(
    client: &AcpClient,
    req: &InProcessRequest,
    cancel: &CancellationToken,
    emit: F,
) -> Result<InProcessResponse, String>
where
    F: FnMut(AcpStreamEvent),
{
    let (msgs, parts) = acp_prompt(req);
    let t = client
        .generate_turn(&render_prompt(&msgs), &parts, cancel, emit)
        .await
        .map_err(|e| e.to_string())?;
    Ok(InProcessResponse {
        content: t.text,
        files: t.files,
        ..Default::default()
    })
}

fn to_stream_event(ev: AcpStreamEvent) -> StreamEvent {
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
        AcpStreamEvent::ToolCallArgs {
            id,
            name,
            args,
            via,
        } => StreamEvent {
            kind: "tool_call_args".into(),
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
            parts,
            is_error,
            via,
        } => StreamEvent {
            kind: "tool_result".into(),
            id,
            name,
            output,
            parts,
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
        Box::pin(async move { turn(&client, &req, &CancellationToken::new(), |_| {}).await })
    })
}

/// A streaming, cancellable in-process `generate_stream` over one warm ACP session.
pub fn acp_generate_stream(client: Arc<AcpClient>) -> GenerateStreamFn {
    Arc::new(move |cancel, req: InProcessRequest, emit| {
        let client = client.clone();
        Box::pin(async move { turn(&client, &req, &cancel, |ev| emit(to_stream_event(ev))).await })
    })
}
