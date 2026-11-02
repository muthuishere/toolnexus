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
    /// A remote A2A agent's skill (§7A), wire name `a2a`.
    A2a,
    /// Declaration-only: the call is surfaced to the host (§10 relay).
    Relay,
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

/// A §1B edge-constructor failure: an unknown extension, an unreadable
/// source, a missing mime type or a malformed `data:` URL. `kind` is one of
/// `extension | read | mime | dataurl`; the message wording matches Go.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("toolnexus: {msg}")]
pub struct PartError {
    pub kind: &'static str,
    pub msg: String,
}

fn part_err(kind: &'static str, msg: String) -> PartError {
    PartError { kind, msg }
}

/// image/* ⇒ image, audio/* ⇒ audio, everything else ⇒ file.
fn part_type_for_mime(mime: &str) -> &'static str {
    if mime.starts_with("image/") {
        "image"
    } else if mime.starts_with("audio/") {
        "audio"
    } else {
        "file"
    }
}

fn encode_part(raw: &[u8], mime: &str, kind: &str) -> ContentPart {
    use base64::Engine as _;
    ContentPart {
        kind: kind.into(),
        mime_type: mime.into(),
        data: base64::engine::general_purpose::STANDARD.encode(raw),
        ..Default::default()
    }
}

fn unknown_extension(name: &str) -> PartError {
    let ext = std::path::Path::new(name)
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    if ext.is_empty() {
        part_err(
            "extension",
            format!("{name} has no extension — pass explicit bytes with a mimeType instead"),
        )
    } else {
        part_err(
            "extension",
            format!(
                "unknown media extension {ext:?} — pass explicit bytes with a mimeType instead"
            ),
        )
    }
}

/// The §1B edge constructors. They normalise at construction, so a path or a
/// stream never enters a part: the bytes are read and base64-encoded now.
impl ContentPart {
    /// A text part.
    pub fn text(s: impl Into<String>) -> Self {
        Self {
            kind: "text".into(),
            text: s.into(),
            ..Default::default()
        }
    }

    /// A part from a filesystem path. The mime type comes from the fixed
    /// extension table (png/jpg/jpeg/gif/webp/pdf/mp3/wav); an unknown one is
    /// an error naming it. A `file` part keeps the base name.
    pub fn from_path(path: impl AsRef<std::path::Path>) -> Result<Self, PartError> {
        let path = path.as_ref();
        let shown = path.display().to_string();
        let (mime, kind) =
            crate::builtins::lookup_media(&shown).ok_or_else(|| unknown_extension(&shown))?;
        let raw = std::fs::read(path)
            .map_err(|e| part_err("read", format!("cannot read {shown}: {e}")))?;
        let mut p = encode_part(&raw, mime, kind);
        if kind == "file" {
            p.name = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
        }
        Ok(p)
    }

    /// A part from bytes with an explicit mime type.
    pub fn from_bytes(raw: impl AsRef<[u8]>, mime_type: &str) -> Result<Self, PartError> {
        if mime_type.is_empty() {
            return Err(part_err("mime", "Bytes needs an explicit mimeType".into()));
        }
        Ok(encode_part(
            raw.as_ref(),
            mime_type,
            part_type_for_mime(mime_type),
        ))
    }

    /// A part from any reader with an explicit mime type, drained now. The
    /// reader is not closed; it belongs to the caller.
    pub fn from_reader(mut r: impl std::io::Read, mime_type: &str) -> Result<Self, PartError> {
        if mime_type.is_empty() {
            return Err(part_err("mime", "Reader needs an explicit mimeType".into()));
        }
        let mut raw = Vec::new();
        r.read_to_end(&mut raw)
            .map_err(|e| part_err("read", format!("cannot read the reader: {e}")))?;
        Ok(encode_part(&raw, mime_type, part_type_for_mime(mime_type)))
    }

    /// A part from a URL. A `data:<mime>;base64,<b64>` URL is decoded into
    /// `{mime_type, data}` (two spellings of the same bytes never diverge);
    /// any other URL is kept as `url` and needs an explicit mime type.
    pub fn from_url(url: &str, mime_type: &str) -> Result<Self, PartError> {
        use base64::Engine as _;
        if let Some(rest) = url.strip_prefix("data:") {
            let (meta, payload) = rest
                .split_once(',')
                .ok_or_else(|| part_err("dataurl", "malformed data: URL — no comma".into()))?;
            if !meta.contains(";base64") {
                return Err(part_err(
                    "dataurl",
                    "only base64 data: URLs are supported".into(),
                ));
            }
            let mime = meta.trim_end_matches(";base64");
            if mime.is_empty() {
                return Err(part_err("dataurl", "data: URL carries no mime type".into()));
            }
            let raw = base64::engine::general_purpose::STANDARD
                .decode(payload)
                .map_err(|e| {
                    part_err(
                        "dataurl",
                        format!("data: URL payload is not standard base64: {e}"),
                    )
                })?;
            return Ok(encode_part(&raw, mime, part_type_for_mime(mime)));
        }
        if mime_type.is_empty() {
            return Err(part_err(
                "mime",
                "a url part needs an explicit mimeType".into(),
            ));
        }
        Ok(Self {
            kind: part_type_for_mime(mime_type).into(),
            mime_type: mime_type.into(),
            url: url.into(),
            ..Default::default()
        })
    }
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
    #[serde(
        rename = "expiresAt",
        default,
        skip_serializing_if = "String::is_empty"
    )]
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

pub(crate) fn to_base36(mut n: u64) -> String {
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
    /// Decides per call whether this tool may run alongside other safe calls of the
    /// same model response (SPEC §8 "Tool scheduling"). `None` ⇒ safe only when
    /// `hints.read_only`. See [`is_concurrency_safe`].
    pub concurrency_safe: Option<ConcurrencySafeFn>,
}

/// A per-call concurrency-safety predicate (SPEC §8 "Tool scheduling").
pub type ConcurrencySafeFn = Arc<dyn Fn(&Value) -> bool + Send + Sync>;

/// Whether a call to `tool` with `args` may run alongside other safe calls of the same
/// response: the tool's own predicate wins; otherwise only a `read_only` tool is safe.
/// A tool without hints is unsafe.
pub fn is_concurrency_safe(tool: &Tool, args: &Value) -> bool {
    match &tool.concurrency_safe {
        Some(f) => f(args),
        None => tool.hints.map(|h| h.read_only).unwrap_or(false),
    }
}

impl Tool {
    /// This tool with a per-call concurrency-safety predicate.
    pub fn with_concurrency_safe(
        mut self,
        f: impl Fn(&Value) -> bool + Send + Sync + 'static,
    ) -> Self {
        self.concurrency_safe = Some(Arc::new(f));
        self
    }
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
        concurrency_safe: None,
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

/// The cap when neither the option nor `TOOLNEXUS_MAX_TOOL_CONCURRENCY` sets one.
pub const DEFAULT_MAX_TOOL_CONCURRENCY: usize = 10;

/// option > env `TOOLNEXUS_MAX_TOOL_CONCURRENCY` > 10 (SPEC §8 "Tool scheduling").
/// Zero, negative or non-numeric values fall back.
pub fn resolve_max_tool_concurrency(option: usize) -> usize {
    resolve_max_tool_concurrency_with(option, |k| std::env::var(k).ok())
}

/// As [`resolve_max_tool_concurrency`] with an injectable env lookup.
pub fn resolve_max_tool_concurrency_with(
    option: usize,
    env: impl Fn(&str) -> Option<String>,
) -> usize {
    if option > 0 {
        return option;
    }
    env("TOOLNEXUS_MAX_TOOL_CONCURRENCY")
        .map(|raw| raw.trim().trim_start_matches('+').to_string())
        .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|s| s.parse::<usize>().ok())
        .filter(|n| *n > 0)
        .unwrap_or(DEFAULT_MAX_TOOL_CONCURRENCY)
}

/// Runs one response's call futures in emitted order: consecutive safe calls overlap
/// (at most `cap` at once); an unsafe call runs alone, after every earlier call and
/// before any later one. Outputs keep emitted order.
pub(crate) async fn schedule_calls<F, T>(futs: Vec<F>, safe: &[bool], cap: usize) -> Vec<T>
where
    F: Future<Output = T>,
{
    let sem = tokio::sync::Semaphore::new(cap.max(1));
    let mut out = Vec::with_capacity(safe.len());
    let mut run: Vec<F> = Vec::new();
    for (i, f) in futs.into_iter().enumerate() {
        if safe.get(i).copied().unwrap_or(false) {
            run.push(f);
            continue;
        }
        if !run.is_empty() {
            out.extend(bounded(std::mem::take(&mut run), &sem).await);
        }
        out.push(f.await);
    }
    if !run.is_empty() {
        out.extend(bounded(run, &sem).await);
    }
    out
}

/// All of `run` concurrently, at most `sem`'s permits at once, outputs in order.
async fn bounded<F: Future<Output = T>, T>(run: Vec<F>, sem: &tokio::sync::Semaphore) -> Vec<T> {
    futures_util::future::join_all(run.into_iter().map(|f| async move {
        let _permit = sem.acquire().await;
        f.await
    }))
    .await
}
