//! Single-turn translation — the translator path (SPEC §11, ADR-0011).
//!
//! OpenAI-shaped messages and tools go in, exactly ONE provider call happens,
//! and an OpenAI-shaped result comes back. No tool is executed, nothing
//! suspends, no conversation is remembered. Mirrors `golang/translate.go`.

use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use super::{
    AfterLlmEvent, BeforeLlmEvent, Client, ClientError, Style, Usage, add_usage, emit, per_call,
    safe_json_args,
};
use crate::tool::ContentPart;
use crate::toolkit::Toolkit;

/// An OpenAI-shaped chat request, handed over verbatim.
#[derive(Clone, Default)]
pub struct TranslateRequest {
    /// The OpenAI `messages` array, verbatim (non-object entries are dropped).
    pub messages: Vec<Value>,
    /// The OpenAI `tools` array, verbatim. Declaration-only.
    pub tools: Vec<Value>,
    /// Declare a toolkit's tools WITHOUT executing any. Toolkit declarations come first.
    pub toolkit: Option<Toolkit>,
    /// The OpenAI `tool_choice`, verbatim. `None` omits it.
    pub tool_choice: Option<Value>,
    /// Overrides the system prompt. Empty ⇒ system messages / the client's `system_prompt`.
    pub system: String,
    /// Overrides the per-provider default. 0 ⇒ default.
    pub max_tokens: usize,
}

/// One tool call the model asked for, in OpenAI shape.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TranslatedToolCall {
    pub id: String,
    pub name: String,
    /// Arguments as a JSON **string** (the OpenAI wire form).
    pub arguments: String,
}

impl std::fmt::Display for TranslatedToolCall {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}({}) id={}",
            self.name,
            truncate(&self.arguments, 64),
            self.id
        )
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.len() <= n {
        return s.into();
    }
    let mut i = n;
    while !s.is_char_boundary(i) {
        i -= 1;
    }
    format!("{}…", &s[..i])
}

/// An OpenAI-shaped single-turn result.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct TranslateResult {
    pub text: String,
    #[serde(rename = "toolCalls")]
    pub tool_calls: Vec<TranslatedToolCall>,
    /// `stop` | `tool_calls` | `length` | `content_filter`.
    #[serde(rename = "finishReason")]
    pub finish_reason: String,
    pub usage: Usage,
    pub model: String,
    /// The provider's decoded response (`None` when it was not a JSON object).
    #[serde(skip)]
    pub raw: Option<Value>,
}

impl TranslateResult {
    /// The tool calls as an OpenAI `tool_calls` array.
    pub fn tool_calls_json(&self) -> Vec<Value> {
        self.tool_calls
            .iter()
            .map(|tc| json!({"id": tc.id, "type": "function", "function": {"name": tc.name, "arguments": tc.arguments}}))
            .collect()
    }
}

impl Client {
    /// Exactly ONE provider call, returned in OpenAI shape (SPEC §11).
    pub async fn translate(&self, req: TranslateRequest) -> Result<TranslateResult, ClientError> {
        self.translate_ctx(CancellationToken::new(), req).await
    }

    /// [`Client::translate`] with caller cancellation. `timeout_ms` bounds the call.
    pub async fn translate_ctx(
        &self,
        cancel: CancellationToken,
        req: TranslateRequest,
    ) -> Result<TranslateResult, ClientError> {
        let ms = self.opts().timeout_ms;
        let fut = async {
            match self.opts().style {
                Style::Anthropic => self.translate_anthropic(&cancel, req).await,
                Style::OpenAI => self.translate_openai(&cancel, req).await,
            }
        };
        if ms == 0 {
            return fut.await;
        }
        match tokio::time::timeout(Duration::from_millis(ms), fut).await {
            Ok(r) => r,
            Err(_) => Err(ClientError::Transport(format!(
                "translate timeout after {ms}ms"
            ))),
        }
    }

    fn translate_system(&self, req: &TranslateRequest) -> String {
        if !req.system.is_empty() {
            return req.system.clone();
        }
        self.opts().system_prompt.clone()
    }

    async fn translate_hooks(
        &self,
        messages: Vec<Value>,
        tools: Vec<Value>,
    ) -> Result<(Vec<Value>, Vec<Value>, String), ClientError> {
        let model = self.opts().model.clone();
        let Some(h) = &self.opts().hooks.before_llm else {
            return Ok((messages, tools, model));
        };
        let ov = h(BeforeLlmEvent {
            messages: messages.clone(),
            tools: tools.clone(),
            model: model.clone(),
            turn: 0,
        })
        .await
        .map_err(ClientError::Hook)?;
        let Some(ov) = ov else {
            return Ok((messages, tools, model));
        };
        Ok((
            ov.messages.unwrap_or(messages),
            ov.tools.unwrap_or(tools),
            ov.model.filter(|m| !m.is_empty()).unwrap_or(model),
        ))
    }

    async fn after_llm_hook(&self, raw: &Option<Value>, model: &str) {
        if let Some(h) = &self.opts().hooks.after_llm {
            let _ = h(AfterLlmEvent {
                response: raw.clone().unwrap_or(Value::Null),
                model: model.into(),
                turn: 0,
            })
            .await;
        }
    }

    async fn post_translate(
        &self,
        endpoint: &str,
        headers: &[(String, String)],
        body: Value,
        model: &str,
        cancel: &CancellationToken,
    ) -> Result<(Value, Option<Value>, Instant), ClientError> {
        let raw = serde_json::to_vec(&body).map_err(|e| ClientError::Decode(e.to_string()))?;
        let t0 = Instant::now();
        let resp = match self.llm_fetch(endpoint, headers, &raw, cancel, None).await {
            Ok(r) => r,
            Err(e) => {
                self.emit_llm(model, "error", t0, 0, 0);
                return Err(e);
            }
        };
        if !(200..300).contains(&resp.status) {
            self.emit_llm(model, "error", t0, 0, 0);
            return Err(Self::provider_error(resp).await);
        }
        let bytes = match resp.read_all().await {
            Ok(b) => b,
            Err(e) => {
                self.emit_llm(model, "error", t0, 0, 0);
                return Err(e);
            }
        };
        let data: Value = match serde_json::from_slice(&bytes) {
            Ok(v @ Value::Object(_)) => v,
            Ok(_) => {
                self.emit_llm(model, "error", t0, 0, 0);
                return Err(ClientError::Decode("response is not an object".into()));
            }
            Err(e) => {
                self.emit_llm(model, "error", t0, 0, 0);
                return Err(ClientError::Decode(e.to_string()));
            }
        };
        let raw = Some(data.clone());
        Ok((data, raw, t0))
    }

    async fn translate_openai(
        &self,
        cancel: &CancellationToken,
        req: TranslateRequest,
    ) -> Result<TranslateResult, ClientError> {
        let key = self.resolve_key()?;
        let base = self.opts().base_url.trim_end_matches('/');
        let endpoint = if !base.ends_with("/v1") && !base.contains("/chat/") {
            format!("{base}/v1/chat/completions")
        } else {
            format!("{base}/chat/completions")
        };
        let mut declared = Vec::new();
        if let Some(tk) = &req.toolkit {
            declared.extend(tk.to_openai());
        }
        declared.extend(req.tools.iter().cloned());
        let (mut messages, tools, model) = self
            .translate_hooks(normalize_messages(&req.messages), declared)
            .await?;
        let sys = self.translate_system(&req);
        if !sys.is_empty() && !has_system_message(&messages) {
            messages.insert(0, json!({"role": "system", "content": sys}));
        }
        let mut body = Map::new();
        body.insert("model".into(), json!(model));
        body.insert("messages".into(), Value::Array(messages));
        if !tools.is_empty() {
            body.insert("tools".into(), Value::Array(tools));
        }
        if let Some(tc) = &req.tool_choice {
            body.insert("tool_choice".into(), tc.clone());
        }
        if req.max_tokens > 0 {
            body.insert("max_tokens".into(), json!(req.max_tokens));
        }
        let body = self.finalize_body(body);
        let headers = vec![("authorization".to_string(), format!("Bearer {key}"))];
        let (data, raw, t0) = self
            .post_translate(&endpoint, &headers, body, &model, cancel)
            .await?;
        let usage = data.get("usage").cloned().unwrap_or(Value::Null);
        let (p, c) = per_call(&usage, Style::OpenAI);
        self.emit_llm(&model, "ok", t0, p, c);
        let mut out = TranslateResult {
            model: model.clone(),
            raw,
            ..Default::default()
        };
        add_usage(&mut out.usage, &usage, Style::OpenAI);
        self.after_llm_hook(&out.raw, &model).await;
        let Some(ch) = data
            .get("choices")
            .and_then(Value::as_array)
            .and_then(|a| a.first())
        else {
            out.finish_reason = "stop".into();
            return Ok(out);
        };
        let s = |v: Option<&Value>| v.and_then(Value::as_str).unwrap_or("").to_owned();
        out.text = s(ch.pointer("/message/content"));
        for tc in ch
            .pointer("/message/tool_calls")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            out.tool_calls.push(TranslatedToolCall {
                id: s(tc.get("id")),
                name: s(tc.pointer("/function/name")),
                arguments: s(tc.pointer("/function/arguments")),
            });
        }
        out.finish_reason = s(ch.get("finish_reason"));
        if out.finish_reason.is_empty() {
            out.finish_reason = finish_reason_for(!out.tool_calls.is_empty(), "").into();
        }
        Ok(out)
    }

    async fn translate_anthropic(
        &self,
        cancel: &CancellationToken,
        req: TranslateRequest,
    ) -> Result<TranslateResult, ClientError> {
        let key = self.resolve_key()?;
        let base = self.opts().base_url.trim_end_matches('/');
        let endpoint = if base.ends_with("/v1") {
            format!("{base}/messages")
        } else {
            format!("{base}/v1/messages")
        };
        let (msgs, mut system) = openai_messages_to_anthropic(&normalize_messages(&req.messages));
        let s = self.translate_system(&req);
        if !s.is_empty() {
            system = s;
        }
        let mut declared = Vec::new();
        if let Some(tk) = &req.toolkit {
            declared.extend(tk.to_anthropic());
        }
        declared.extend(openai_tools_to_anthropic(&req.tools));
        let (messages, tools, model) = self.translate_hooks(msgs, declared).await?;
        let max_tokens = if req.max_tokens > 0 {
            req.max_tokens
        } else {
            4096
        };
        let mut body = Map::new();
        body.insert("model".into(), json!(model));
        body.insert("max_tokens".into(), json!(max_tokens));
        body.insert("messages".into(), Value::Array(messages));
        if !system.is_empty() {
            body.insert("system".into(), json!(system));
        }
        if !tools.is_empty() {
            body.insert("tools".into(), Value::Array(tools));
        }
        if let Some(tc) = openai_tool_choice_to_anthropic(req.tool_choice.as_ref()) {
            body.insert("tool_choice".into(), tc);
        }
        let body = self.finalize_body(body);
        let headers = vec![
            ("x-api-key".to_string(), key),
            ("anthropic-version".to_string(), "2023-06-01".to_string()),
        ];
        let (data, raw, t0) = self
            .post_translate(&endpoint, &headers, body, &model, cancel)
            .await?;
        let usage = data.get("usage").cloned().unwrap_or(Value::Null);
        let (p, c) = per_call(&usage, Style::Anthropic);
        self.emit_llm(&model, "ok", t0, p, c);
        let mut out = TranslateResult {
            model: model.clone(),
            raw,
            ..Default::default()
        };
        add_usage(&mut out.usage, &usage, Style::Anthropic);
        self.after_llm_hook(&out.raw, &model).await;
        let mut text = String::new();
        for b in data
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let s = |k: &str| b.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
            match b.get("type").and_then(Value::as_str) {
                Some("text") => text.push_str(&s("text")),
                Some("tool_use") => {
                    let args = match b.get("input") {
                        Some(v @ Value::Object(_)) => v.to_string(),
                        _ => "{}".into(),
                    };
                    out.tool_calls.push(TranslatedToolCall {
                        id: s("id"),
                        name: s("name"),
                        arguments: args,
                    });
                }
                _ => {}
            }
        }
        out.text = text;
        let stop = data
            .get("stop_reason")
            .and_then(Value::as_str)
            .unwrap_or("");
        out.finish_reason = finish_reason_for(!out.tool_calls.is_empty(), stop).into();
        Ok(out)
    }
}

// ---- inbound translation: OpenAI shapes → Anthropic-native ----

fn openai_messages_to_anthropic(messages: &[Value]) -> (Vec<Value>, String) {
    let mut out: Vec<Value> = Vec::new();
    let mut system_parts: Vec<String> = Vec::new();
    let mut pending: Vec<Value> = Vec::new();
    fn flush(out: &mut Vec<Value>, pending: &mut Vec<Value>) {
        if !pending.is_empty() {
            out.push(json!({"role": "user", "content": std::mem::take(pending)}));
        }
    }
    for m in messages {
        let Some(mm) = m.as_object() else { continue };
        let role = mm.get("role").and_then(Value::as_str).unwrap_or("");
        match role {
            "system" | "developer" => {
                flush(&mut out, &mut pending);
                let s = content_text(mm.get("content"));
                if !s.is_empty() {
                    system_parts.push(s);
                }
            }
            "tool" | "function" => {
                let mut block = Map::new();
                block.insert("type".into(), json!("tool_result"));
                block.insert("content".into(), json!(content_text(mm.get("content"))));
                if let Some(id) = mm.get("tool_call_id").and_then(Value::as_str)
                    && !id.is_empty()
                {
                    block.insert("tool_use_id".into(), json!(id));
                }
                pending.push(Value::Object(block));
            }
            "assistant" => {
                flush(&mut out, &mut pending);
                let mut blocks = Vec::new();
                let s = content_text(mm.get("content"));
                if !s.is_empty() {
                    blocks.push(json!({"type": "text", "text": s}));
                }
                for tc in tool_calls_of(mm) {
                    blocks.push(json!({"type": "tool_use", "id": tc.id, "name": tc.name, "input": safe_json_args(&tc.arguments)}));
                }
                if blocks.is_empty() {
                    continue;
                }
                out.push(json!({"role": "assistant", "content": blocks}));
            }
            _ => {
                flush(&mut out, &mut pending);
                match mm.get("content") {
                    Some(Value::Array(raw)) if !raw.is_empty() => {
                        if let Some(content) = translate_content_array(raw) {
                            out.push(json!({"role": "user", "content": content}));
                        }
                    }
                    c => {
                        let s = content_text(c);
                        if !s.is_empty() {
                            out.push(json!({"role": "user", "content": s}));
                        }
                    }
                }
            }
        }
    }
    flush(&mut out, &mut pending);
    (out, system_parts.join("\n\n"))
}

fn openai_tools_to_anthropic(tools: &[Value]) -> Vec<Value> {
    let mut out = Vec::new();
    for t in tools {
        let Some(m) = t.as_object() else { continue };
        let Some(f) = m.get("function").and_then(Value::as_object) else {
            if m.contains_key("name") {
                out.push(t.clone());
            }
            continue;
        };
        let name = f.get("name").and_then(Value::as_str).unwrap_or("");
        if name.is_empty() {
            continue;
        }
        let mut decl = Map::new();
        decl.insert("name".into(), json!(name));
        if let Some(d) = f.get("description").and_then(Value::as_str)
            && !d.is_empty()
        {
            decl.insert("description".into(), json!(d));
        }
        let schema = match f.get("parameters") {
            Some(p @ Value::Object(_)) => p.clone(),
            _ => json!({"type": "object", "properties": {}}),
        };
        decl.insert("input_schema".into(), schema);
        out.push(Value::Object(decl));
    }
    out
}

fn openai_tool_choice_to_anthropic(choice: Option<&Value>) -> Option<Value> {
    match choice? {
        Value::String(s) => match s.as_str() {
            "required" | "any" => Some(json!({"type": "any"})),
            "none" => Some(json!({"type": "none"})),
            _ => None,
        },
        Value::Object(m) => {
            let name = m
                .get("function")
                .and_then(|f| f.get("name"))
                .and_then(Value::as_str)
                .unwrap_or("");
            if name.is_empty() {
                None
            } else {
                Some(json!({"type": "tool", "name": name}))
            }
        }
        _ => None,
    }
}

// ---- small shared helpers ----

fn finish_reason_for(has_tool_calls: bool, provider_stop: &str) -> &'static str {
    if has_tool_calls {
        return "tool_calls";
    }
    match provider_stop {
        "max_tokens" | "length" => "length",
        "refusal" | "content_filter" => "content_filter",
        _ => "stop",
    }
}

fn normalize_messages(messages: &[Value]) -> Vec<Value> {
    messages.iter().filter(|m| m.is_object()).cloned().collect()
}

fn content_text(content: Option<&Value>) -> String {
    match content {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Array(a)) => a
            .iter()
            .filter_map(|p| p.get("text").and_then(Value::as_str))
            .collect::<Vec<_>>()
            .concat(),
        _ => String::new(),
    }
}

fn tool_calls_of(m: &Map<String, Value>) -> Vec<TranslatedToolCall> {
    let Some(raw) = m.get("tool_calls").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for tc in raw {
        let Some(f) = tc.get("function").filter(|f| f.is_object()) else {
            continue;
        };
        let mut args = f
            .get("arguments")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        if args.is_empty()
            && let Some(o @ Value::Object(_)) = f.get("arguments")
        {
            args = o.to_string();
        }
        out.push(TranslatedToolCall {
            id: tc.get("id").and_then(Value::as_str).unwrap_or("").into(),
            name: f.get("name").and_then(Value::as_str).unwrap_or("").into(),
            arguments: args,
        });
    }
    out
}

fn has_system_message(messages: &[Value]) -> bool {
    messages.iter().any(|m| {
        matches!(
            m.get("role").and_then(Value::as_str),
            Some("system") | Some("developer")
        )
    })
}

fn translate_content_array(raw: &[Value]) -> Option<Value> {
    let parts = content_array_parts(raw);
    if parts.is_empty() {
        return None;
    }
    if parts.iter().all(|p| p.kind == "text") {
        let s: String = parts.iter().map(|p| p.text.as_str()).collect();
        return if s.is_empty() { None } else { Some(json!(s)) };
    }
    let mut blocks = Vec::new();
    let mut pending = String::new();
    for p in &parts {
        if p.kind == "text" {
            pending.push_str(&p.text);
            continue;
        }
        if !pending.is_empty() {
            blocks.push(json!({"type": "text", "text": std::mem::take(&mut pending)}));
        }
        blocks
            .push(emit::encode_part(Style::Anthropic, p).unwrap_or_else(|_| emit::placeholder(p)));
    }
    if !pending.is_empty() {
        blocks.push(json!({"type": "text", "text": pending}));
    }
    Some(Value::Array(blocks))
}

fn content_array_parts(raw: &[Value]) -> Vec<ContentPart> {
    let mut out = Vec::new();
    for e in raw {
        let Some(m) = e.as_object() else { continue };
        let s = |k: &str| m.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
        let kind = s("type");
        let canonical = |kind: &str| ContentPart {
            kind: kind.into(),
            mime_type: s("mimeType"),
            data: s("data"),
            url: s("url"),
            name: s("name"),
            ..Default::default()
        };
        match kind.as_str() {
            "text" => out.push(ContentPart {
                kind: "text".into(),
                text: s("text"),
                ..Default::default()
            }),
            "image" | "audio" => out.push(canonical(&kind)),
            "file" => {
                if let Some(f) = m.get("file").and_then(Value::as_object) {
                    let fd = f.get("file_data").and_then(Value::as_str).unwrap_or("");
                    let mut p = from_wire_url("file", fd);
                    if p.name.is_empty() {
                        p.name = f
                            .get("filename")
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .into();
                    }
                    out.push(p);
                } else {
                    out.push(canonical("file"));
                }
            }
            "image_url" => {
                let u = m
                    .get("image_url")
                    .and_then(|v| v.get("url"))
                    .and_then(Value::as_str)
                    .unwrap_or("");
                out.push(from_wire_url("image", u));
            }
            "input_audio" => {
                let ia = m.get("input_audio");
                let g = |k: &str| {
                    ia.and_then(|v| v.get(k))
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned()
                };
                let format = g("format");
                let mime = if format == "mp3" {
                    "audio/mpeg".to_string()
                } else {
                    format!("audio/{format}")
                };
                out.push(ContentPart {
                    kind: "audio".into(),
                    mime_type: mime,
                    data: g("data"),
                    ..Default::default()
                });
            }
            _ => {
                let t = s("text");
                if !t.is_empty() {
                    out.push(ContentPart {
                        kind: "text".into(),
                        text: t,
                        ..Default::default()
                    });
                }
            }
        }
    }
    out
}

/// Parse a base64 `data:` URL into (mime, payload).
fn parse_data_url(u: &str) -> Option<(String, String)> {
    let rest = u.strip_prefix("data:")?;
    let (meta, payload) = rest.split_once(',')?;
    if !meta.contains(";base64") {
        return None;
    }
    let mime = meta.strip_suffix(";base64").unwrap_or(meta);
    if mime.is_empty() {
        return None;
    }
    Some((mime.into(), payload.into()))
}

fn from_wire_url(kind: &str, u: &str) -> ContentPart {
    if let Some((mime, data)) = parse_data_url(u) {
        return ContentPart {
            kind: kind.into(),
            mime_type: mime,
            data,
            ..Default::default()
        };
    }
    ContentPart {
        kind: kind.into(),
        url: u.into(),
        ..Default::default()
    }
}
