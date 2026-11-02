//! Core tool types (SPEC §0 item 1, §1): one `Tool` shape for every source.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use tokio_util::sync::CancellationToken;

/// Where a tool came from. Serialized as the lowercase wire names.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ToolSource {
    Mcp,
    Skill,
    Custom,
    Native,
    Http,
    Builtin,
    Agent,
}

/// What a tool returns. Wire keys match every other port.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ToolResult {
    pub output: String,
    #[serde(rename = "isError")]
    pub is_error: bool,
    /// Non-text content (§1B). Empty ⇒ omitted on the wire.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub parts: Vec<ContentPart>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, Value>>,
}

impl ToolResult {
    pub fn ok(output: impl Into<String>) -> Self {
        Self {
            output: output.into(),
            ..Default::default()
        }
    }
    pub fn error(output: impl Into<String>) -> Self {
        Self {
            output: output.into(),
            is_error: true,
            ..Default::default()
        }
    }
    pub fn with_metadata(mut self, metadata: Map<String, Value>) -> Self {
        self.metadata = Some(metadata);
        self
    }
}

/// Per-call context: cancellation, the model's call id when run by a loop,
/// a per-call timeout override (ms, 0 = none) and the §10 answer on re-execution.
#[derive(Debug, Clone, Default)]
pub struct ToolContext {
    pub cancel: CancellationToken,
    pub call_id: Option<String>,
    pub timeout_ms: u64,
    pub answer: Option<Answer>,
}

/// §1B content part: `text | image | file | audio`. Wire keys match Go.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ContentPart {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub text: String,
    #[serde(rename = "mimeType", default, skip_serializing_if = "String::is_empty")]
    pub mime_type: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub data: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
}

/// Advisory tool behaviour hints (0.22.0). Never sent to the model.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToolHints {
    #[serde(rename = "readOnly")]
    pub read_only: bool,
    pub destructive: bool,
    #[serde(rename = "openWorld")]
    pub open_world: bool,
}

/// §10 suspension request. Keys byte-identical across ports.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Request {
    pub id: String,
    pub kind: String,
    pub prompt: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<Map<String, Value>>,
    #[serde(rename = "expiresAt", default, skip_serializing_if = "String::is_empty")]
    pub expires_at: String,
}

/// §10 answer to a [`Request`].
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Answer {
    pub id: String,
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<Map<String, Value>>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub reason: String,
}

static PENDING_SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Build a suspension result (§10): `isError`, output = prompt (+ "\n" + url),
/// `metadata.pending` = the request. An empty id gets `pnd-<ms36>-<seq>`.
pub fn pending(mut req: Request) -> ToolResult {
    if req.id.is_empty() {
        let ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let seq = PENDING_SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
        req.id = format!("pnd-{}-{}", to_base36(ms), seq);
    }
    let mut out = req.prompt.clone();
    if !req.url.is_empty() {
        out.push('\n');
        out.push_str(&req.url);
    }
    let mut meta = Map::new();
    meta.insert(
        "pending".into(),
        serde_json::to_value(&req).unwrap_or(Value::Null),
    );
    ToolResult {
        output: out,
        is_error: true,
        parts: Vec::new(),
        metadata: Some(meta),
    }
}

/// `authorization` suspension at `url` (default prompt as in Go).
pub fn auth_required(url: &str, prompt: &str) -> ToolResult {
    let prompt = if prompt.is_empty() {
        "Authorization required to continue"
    } else {
        prompt
    };
    pending(Request {
        kind: "authorization".into(),
        prompt: prompt.into(),
        url: url.into(),
        ..Default::default()
    })
}

/// The request a result suspends on, if any.
pub fn pending_of(result: &ToolResult) -> Option<Request> {
    let v = result.metadata.as_ref()?.get("pending")?;
    let req: Request = serde_json::from_value(v.clone()).ok()?;
    if req.id.is_empty() && req.kind.is_empty() {
        return None;
    }
    Some(req)
}

fn to_base36(mut n: u64) -> String {
    if n == 0 {
        return "0".into();
    }
    let digits = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut out = Vec::new();
    while n > 0 {
        out.push(digits[(n % 36) as usize]);
        n /= 36;
    }
    out.reverse();
    String::from_utf8(out).unwrap_or_default()
}

/// The boxed future a tool's execute returns.
pub type ToolFuture = Pin<Box<dyn Future<Output = ToolResult> + Send>>;

/// A tool's execute function.
pub type ExecuteFn = Arc<dyn Fn(Value, ToolContext) -> ToolFuture + Send + Sync>;

/// A named, described, schema'd callable (SPEC §1).
#[derive(Clone)]
pub struct Tool {
    pub name: String,
    pub description: String,
    /// JSON-Schema object for the arguments.
    pub input_schema: Value,
    pub source: ToolSource,
    pub execute: ExecuteFn,
    /// Advisory hints (read-only / destructive / open-world); `None` = unknown.
    pub hints: Option<ToolHints>,
}

impl std::fmt::Debug for Tool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Tool")
            .field("name", &self.name)
            .field("source", &self.source)
            .finish_non_exhaustive()
    }
}

impl Tool {
    /// Run the tool. A missing/`null` args value is passed as `{}`.
    pub async fn call(&self, args: Value, ctx: ToolContext) -> ToolResult {
        let args = if args.is_null() {
            Value::Object(Map::new())
        } else {
            args
        };
        (self.execute)(args, ctx).await
    }
}

/// `sanitize(x)` (SPEC §0 item 2): replace anything outside `[a-zA-Z0-9_-]` with `_`.
pub fn sanitize(value: &str) -> String {
    value
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// The schema used when a native tool declares none: an object with no properties.
pub fn empty_schema() -> Value {
    serde_json::json!({"type": "object", "properties": {}, "additionalProperties": false})
}

/// A native tool (SPEC §0 item 8, `source: "native"`): `Ok(text)` ⇒ output,
/// `Err(e)` ⇒ `isError` with `e.to_string()`. `schema: None` ⇒ [`empty_schema`].
pub fn define_tool<F, Fut, E>(
    name: impl Into<String>,
    description: impl Into<String>,
    schema: Option<Value>,
    f: F,
) -> Tool
where
    F: Fn(Value, ToolContext) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<String, E>> + Send + 'static,
    E: std::fmt::Display,
{
    let f = Arc::new(f);
    Tool {
        name: name.into(),
        description: description.into(),
        input_schema: schema.unwrap_or_else(empty_schema),
        source: ToolSource::Native,
        hints: None,
        execute: Arc::new(move |args, ctx| {
            let f = f.clone();
            Box::pin(async move {
                match f(args, ctx).await {
                    Ok(out) => ToolResult::ok(out),
                    Err(e) => ToolResult::error(e.to_string()),
                }
            })
        }),
    }
}

/// [`define_tool`] with the arguments decoded into `T` first. A decode failure
/// is an `isError` result naming the problem; `schema` is advertised verbatim.
pub fn define_typed_tool<T, F, Fut, E>(
    name: impl Into<String>,
    description: impl Into<String>,
    schema: Value,
    f: F,
) -> Tool
where
    T: serde::de::DeserializeOwned + Send + 'static,
    F: Fn(T, ToolContext) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<String, E>> + Send + 'static,
    E: std::fmt::Display,
{
    let f = Arc::new(f);
    define_tool(name, description, Some(schema), move |args, ctx| {
        let f = f.clone();
        async move {
            let input: T =
                serde_json::from_value(args).map_err(|e| format!("invalid arguments: {e}"))?;
            f(input, ctx).await.map_err(|e| e.to_string())
        }
    })
}
