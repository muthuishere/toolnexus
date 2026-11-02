//! Content-part emission and the tool-result relocation rule (SPEC §8A).
//! A transcript with no parts goes out untouched (byte-identical string path).

use base64::Engine;
use serde_json::{Map, Value, json};

use super::Style;
use crate::tool::ContentPart;

/// `on_unsupported_part`: fail the request.
pub const UNSUPPORTED_PART_AS_ERROR: &str = "error";
/// `on_unsupported_part`: replace the part with a text placeholder.
pub const UNSUPPORTED_PART_AS_TEXT: &str = "text";

pub(crate) fn part_bytes(p: &ContentPart) -> usize {
    if p.data.is_empty() {
        return 0;
    }
    base64::engine::general_purpose::STANDARD
        .decode(&p.data)
        .map(|b| b.len())
        .unwrap_or(0)
}

/// Construction validity of a part (§1B): exactly one of data/url, a mime type,
/// and (when `max_bytes > 0`) a size cap.
pub fn validate_part(p: &ContentPart, max_bytes: usize) -> Result<(), String> {
    if p.kind == "text" {
        return Ok(());
    }
    if !p.data.is_empty() && !p.url.is_empty() {
        return Err(format!(
            "toolnexus: a {:?} part carries both data and url — exactly one is allowed",
            p.kind
        ));
    }
    if p.data.is_empty() && p.url.is_empty() {
        return Err(format!(
            "toolnexus: a {:?} part carries neither data nor url — exactly one is required",
            p.kind
        ));
    }
    if p.mime_type.is_empty() {
        return Err(format!("toolnexus: a {:?} part needs a mimeType", p.kind));
    }
    if max_bytes > 0 {
        let n = part_bytes(p);
        if n > max_bytes {
            return Err(format!(
                "toolnexus: part is {n} decoded bytes, over the {max_bytes} byte limit"
            ));
        }
    }
    Ok(())
}

fn audio_format(mime: &str) -> String {
    match mime {
        "audio/mpeg" | "audio/mp3" => "mp3".into(),
        "audio/wav" | "audio/x-wav" | "audio/wave" => "wav".into(),
        m => m.strip_prefix("audio/").unwrap_or(m).into(),
    }
}

fn data_url(p: &ContentPart) -> String {
    format!("data:{};base64,{}", p.mime_type, p.data)
}

fn style_name(s: Style) -> &'static str {
    match s {
        Style::Anthropic => "anthropic",
        Style::OpenAI => "openai",
    }
}

fn encode_part(style: Style, p: &ContentPart) -> Result<Value, String> {
    let unsupported = || {
        let mime = if p.mime_type.is_empty() {
            "(no mime type)"
        } else {
            &p.mime_type
        };
        Err(format!(
            "toolnexus: the {:?} style has no block for a {:?} part ({mime})",
            style_name(style),
            p.kind
        ))
    };
    let block = match style {
        Style::Anthropic => match p.kind.as_str() {
            "text" => json!({"type": "text", "text": p.text}),
            "image" | "file" => {
                let t = if p.kind == "image" {
                    "image"
                } else {
                    "document"
                };
                if !p.data.is_empty() {
                    json!({"type": t, "source": {"type": "base64", "media_type": p.mime_type, "data": p.data}})
                } else {
                    json!({"type": t, "source": {"type": "url", "url": p.url}})
                }
            }
            _ => return unsupported(),
        },
        Style::OpenAI => match p.kind.as_str() {
            "text" => json!({"type": "text", "text": p.text}),
            "image" => {
                let url = if p.data.is_empty() {
                    p.url.clone()
                } else {
                    data_url(p)
                };
                json!({"type": "image_url", "image_url": {"url": url}})
            }
            "file" => {
                if p.data.is_empty() {
                    return unsupported();
                }
                let name = if p.name.is_empty() { "file" } else { &p.name };
                json!({"type": "file", "file": {"filename": name, "file_data": data_url(p)}})
            }
            _ => {
                if p.data.is_empty() {
                    return unsupported();
                }
                json!({"type": "input_audio", "input_audio": {"data": p.data, "format": audio_format(&p.mime_type)}})
            }
        },
    };
    Ok(block)
}

fn placeholder(p: &ContentPart) -> Value {
    json!({"type": "text", "text": format!("[unsupported {} part ({}, {} bytes)]", p.kind, p.mime_type, part_bytes(p))})
}

/// Parse a `content`/`parts` value as content parts (every entry must be a part).
pub(crate) fn parts_of(v: &Value) -> Option<Vec<ContentPart>> {
    let arr = v.as_array()?;
    if arr.is_empty() {
        return None;
    }
    let mut out = Vec::with_capacity(arr.len());
    for e in arr {
        let m = e.as_object()?;
        let kind = m.get("type").and_then(Value::as_str)?;
        if !matches!(kind, "text" | "image" | "file" | "audio") {
            return None;
        }
        let s = |k: &str| {
            m.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned()
        };
        out.push(ContentPart {
            kind: kind.into(),
            text: s("text"),
            mime_type: s("mimeType"),
            data: s("data"),
            url: s("url"),
            name: s("name"),
        });
    }
    Some(out)
}

pub(crate) struct Emitter {
    pub style: Style,
    pub max_bytes: usize,
    pub mode: String,
    pub warned: bool,
}

impl Emitter {
    fn blocks(&mut self, parts: &[ContentPart], attached: bool) -> Result<Vec<Value>, String> {
        let mut out = Vec::with_capacity(parts.len());
        for p in parts {
            validate_part(p, 0)?;
            if self.max_bytes > 0 {
                let n = part_bytes(p);
                if n > self.max_bytes {
                    let e = format!(
                        "toolnexus: part is {n} decoded bytes, over the {} byte limit",
                        self.max_bytes
                    );
                    out.push(self.degrade(p, e, attached)?);
                    continue;
                }
            }
            match encode_part(self.style, p) {
                Ok(b) => out.push(b),
                Err(e) => out.push(self.degrade(p, e, attached)?),
            }
        }
        Ok(out)
    }

    fn degrade(&mut self, p: &ContentPart, err: String, attached: bool) -> Result<Value, String> {
        let strict = match self.mode.as_str() {
            UNSUPPORTED_PART_AS_ERROR => true,
            UNSUPPORTED_PART_AS_TEXT => false,
            _ => attached,
        };
        if strict {
            return Err(err);
        }
        if !self.warned {
            self.warned = true;
            eprintln!("[toolnexus] {err} — replaced with a text placeholder");
        }
        Ok(placeholder(p))
    }

    pub fn emit(&mut self, messages: &[Value]) -> Result<Vec<Value>, String> {
        match self.style {
            Style::Anthropic => self.emit_anthropic(messages),
            Style::OpenAI => self.emit_openai(messages),
        }
    }

    fn emit_openai(&mut self, messages: &[Value]) -> Result<Vec<Value>, String> {
        let mut out: Vec<Value> = Vec::with_capacity(messages.len() + 1);
        let mut relocated: Vec<Value> = Vec::new();
        fn flush(out: &mut Vec<Value>, relocated: &mut Vec<Value>) {
            if !relocated.is_empty() {
                out.push(json!({"role": "user", "content": std::mem::take(relocated)}));
            }
        }
        for raw in messages {
            let Some(m) = raw.as_object() else {
                flush(&mut out, &mut relocated);
                out.push(raw.clone());
                continue;
            };
            let role = m.get("role").and_then(Value::as_str).unwrap_or("");
            if role != "tool" {
                flush(&mut out, &mut relocated);
            }
            if role == "tool" {
                let mut msg = m.clone();
                msg.remove("parts");
                msg.remove("name");
                let tparts = m.get("parts").and_then(parts_of).unwrap_or_default();
                let (text, other): (Vec<_>, Vec<_>) =
                    tparts.into_iter().partition(|p| p.kind == "text");
                if !text.is_empty() {
                    let mut all = vec![ContentPart {
                        kind: "text".into(),
                        text: m
                            .get("content")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .into(),
                        ..Default::default()
                    }];
                    all.extend(text);
                    msg.insert("content".into(), Value::Array(self.blocks(&all, false)?));
                }
                out.push(Value::Object(msg));
                if !other.is_empty() {
                    let id = m.get("tool_call_id").and_then(Value::as_str).unwrap_or("");
                    let name = m.get("name").and_then(Value::as_str).unwrap_or("");
                    relocated.push(
                        json!({"type": "text", "text": format!("Output of tool {name} ({id}):")}),
                    );
                    relocated.extend(self.blocks(&other, false)?);
                }
            } else if let Some(parts) = m.get("content").and_then(parts_of) {
                let mut msg = m.clone();
                msg.insert("content".into(), Value::Array(self.blocks(&parts, true)?));
                out.push(Value::Object(msg));
            } else {
                out.push(raw.clone());
            }
        }
        flush(&mut out, &mut relocated);
        Ok(out)
    }

    fn emit_anthropic(&mut self, messages: &[Value]) -> Result<Vec<Value>, String> {
        let mut out = Vec::with_capacity(messages.len());
        for raw in messages {
            let Some(m) = raw.as_object() else {
                out.push(raw.clone());
                continue;
            };
            if let Some(parts) = m.get("content").and_then(parts_of) {
                let mut msg = m.clone();
                msg.insert("content".into(), Value::Array(self.blocks(&parts, true)?));
                out.push(Value::Object(msg));
                continue;
            }
            let Some(inner) = m.get("content").and_then(Value::as_array) else {
                out.push(raw.clone());
                continue;
            };
            let mut changed = false;
            let mut new_inner = Vec::with_capacity(inner.len());
            for b in inner {
                let Some(bm) = b.as_object() else {
                    new_inner.push(b.clone());
                    continue;
                };
                let tparts = bm.get("parts").and_then(parts_of).unwrap_or_default();
                if tparts.is_empty() {
                    if bm.contains_key("parts") {
                        let mut nb = bm.clone();
                        nb.remove("parts");
                        nb.remove("name");
                        new_inner.push(Value::Object(nb));
                        changed = true;
                        continue;
                    }
                    new_inner.push(b.clone());
                    continue;
                }
                changed = true;
                let mut nb: Map<String, Value> = bm.clone();
                nb.remove("parts");
                nb.remove("name");
                let mut lead = Vec::new();
                if let Some(s) = bm.get("content").and_then(Value::as_str)
                    && !s.is_empty()
                {
                    lead.push(ContentPart {
                        kind: "text".into(),
                        text: s.into(),
                        ..Default::default()
                    });
                }
                lead.extend(tparts);
                nb.insert("content".into(), Value::Array(self.blocks(&lead, false)?));
                new_inner.push(Value::Object(nb));
            }
            if !changed {
                out.push(raw.clone());
                continue;
            }
            let mut msg = m.clone();
            msg.insert("content".into(), Value::Array(new_inner));
            out.push(Value::Object(msg));
        }
        Ok(out)
    }
}

/// Whether any message carries content parts (else the wire is the transcript as-is).
pub(crate) fn any_parts(messages: &[Value]) -> bool {
    messages.iter().any(|m| {
        let Some(mm) = m.as_object() else {
            return false;
        };
        if mm.contains_key("parts") {
            return true;
        }
        if let Some(c) = mm.get("content") {
            if parts_of(c).is_some() {
                return true;
            }
            if let Some(blocks) = c.as_array() {
                return blocks
                    .iter()
                    .any(|b| b.as_object().is_some_and(|bm| bm.contains_key("parts")));
            }
        }
        false
    })
}

/// Attach a tool result's parts to its transcript entry (with the tool name as the
/// relocation label carrier).
pub(crate) fn with_tool_parts(mut entry: Value, name: &str, parts: &[ContentPart]) -> Value {
    if parts.is_empty() {
        return entry;
    }
    if let Some(m) = entry.as_object_mut() {
        m.insert(
            "parts".into(),
            serde_json::to_value(parts).unwrap_or(Value::Null),
        );
        m.insert("name".into(), json!(name));
    }
    entry
}
