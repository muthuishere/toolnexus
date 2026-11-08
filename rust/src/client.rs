//! Unified LLM client (SPEC §8): one tool-calling loop over the OpenAI
//! (Chat Completions) and Anthropic (Messages) wire styles, with hooks,
//! streaming, retries (incl. `Retry-After`), conversation memory, metrics,
//! prompt caching, tool tiers and §10 suspension (`wait_for` / `pending`).
//!
//! Mirrors `golang/client.go`. Gemini is a schema adapter only (§0 item 7), as in
//! every other port: there is no Gemini wire style in the loop.

mod acp_bridge;
mod cache;
mod emit;
mod inprocess;
mod metrics;
mod models;
mod tiers;
mod translate;

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

pub use acp_bridge::{acp_generate, acp_generate_stream, acp_messages};
pub use cache::PromptCacheOptions;
pub use emit::{UNSUPPORTED_PART_AS_ERROR, UNSUPPORTED_PART_AS_TEXT, validate_part};
pub use inprocess::{
    EmitFn, GenerateFn, GenerateStreamFn, InProcessOptions, InProcessRequest, InProcessResponse,
    InProcessToolCall, InProcessUsage, create_in_process_client,
};
pub use metrics::MetricEvent;
pub use models::{ModelInfo, list_models};
pub use tiers::{CountTokensFn, ToolTier, ToolTierOptions, UsageCountFn};
pub use translate::{TranslateRequest, TranslateResult, TranslatedToolCall};

use crate::store::{ConversationStore, InMemoryConversationStore};
use crate::tool::{Answer, ContentPart, Request, ToolContext, ToolHints, ToolResult, pending_of};
use crate::toolkit::Toolkit;
use crate::{to_anthropic, to_openai};

// ---------------------------------------------------------------------------
// options
// ---------------------------------------------------------------------------

/// The provider wire style.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Style {
    #[default]
    #[serde(rename = "openai")]
    OpenAI,
    Anthropic,
}

/// A boxed, sendable future (hooks and `wait_for` are async).
pub type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send>>;
pub type BeforeLlmHook =
    Arc<dyn Fn(BeforeLlmEvent) -> BoxFuture<Result<Option<LlmOverride>, String>> + Send + Sync>;
pub type AfterLlmHook = Arc<dyn Fn(AfterLlmEvent) -> BoxFuture<Result<(), String>> + Send + Sync>;
pub type BeforeToolHook =
    Arc<dyn Fn(BeforeToolEvent) -> BoxFuture<Result<Option<ToolOverride>, String>> + Send + Sync>;
pub type AfterToolHook =
    Arc<dyn Fn(AfterToolEvent) -> BoxFuture<Result<Option<ToolOverride>, String>> + Send + Sync>;
/// §10 host slot: resolve a suspension.
pub type WaitForFn = Arc<dyn Fn(Request) -> BoxFuture<Result<Answer, String>> + Send + Sync>;
/// §10 host slot with the turn's cancellation (a pending question ends with its turn).
pub type WaitForCtxFn =
    Arc<dyn Fn(CancellationToken, Request) -> BoxFuture<Result<Answer, String>> + Send + Sync>;
pub type OnMetricFn = Arc<dyn Fn(&MetricEvent) + Send + Sync>;
pub type OnErrorFn = Arc<dyn Fn(&ErrorInfo) -> ErrorTier + Send + Sync>;
pub type BodyTransformFn = Arc<dyn Fn(Value) -> Option<Value> + Send + Sync>;

/// Lifecycle middleware (§8 Hooks). Every hook may fail the run with `Err`.
#[derive(Clone, Default)]
pub struct Hooks {
    pub before_llm: Option<BeforeLlmHook>,
    pub after_llm: Option<AfterLlmHook>,
    pub before_tool: Option<BeforeToolHook>,
    pub after_tool: Option<AfterToolHook>,
}

#[derive(Debug, Clone)]
pub struct BeforeLlmEvent {
    pub messages: Vec<Value>,
    pub tools: Vec<Value>,
    pub model: String,
    pub turn: usize,
}

#[derive(Debug, Clone)]
pub struct AfterLlmEvent {
    pub response: Value,
    pub model: String,
    pub turn: usize,
}

#[derive(Debug, Clone)]
pub struct BeforeToolEvent {
    pub name: String,
    pub args: Value,
    pub id: String,
    pub turn: usize,
    /// The tool's advisory hints (for permission gates).
    pub hints: Option<ToolHints>,
}

#[derive(Debug, Clone)]
pub struct AfterToolEvent {
    pub name: String,
    pub args: Value,
    pub result: ToolResult,
    pub id: String,
    pub turn: usize,
}

/// `before_llm` override: replace messages / tools, or route this call to another model.
#[derive(Debug, Clone, Default)]
pub struct LlmOverride {
    pub messages: Option<Vec<Value>>,
    pub tools: Option<Vec<Value>>,
    pub model: Option<String>,
}

/// `before_tool` / `after_tool` override: rewrite args, or short-circuit with a result.
#[derive(Debug, Clone, Default)]
pub struct ToolOverride {
    pub args: Option<Value>,
    pub result: Option<ToolResult>,
}

/// What `on_error` decides for one failed attempt.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorTier {
    Retry,
    Fail,
}

/// One failed LLM attempt, for `on_error`.
#[derive(Debug, Clone)]
pub struct ErrorInfo {
    pub error: String,
    /// HTTP status, 0 for a transport error.
    pub status: u16,
    pub attempt: u32,
    /// The default classification.
    pub retryable: bool,
}

/// Client options (§8). Field names are the parity-checked option names.
#[derive(Clone, Default)]
pub struct ClientOptions {
    pub base_url: String,
    pub style: Style,
    pub model: String,
    /// Explicit key. Else `api_key_env`, else the env var that matches the host.
    pub api_key: String,
    /// Name of the env var to read the key from (any host).
    pub api_key_env: String,
    pub headers: HashMap<String, String>,
    pub system_prompt: String,
    /// 0 ⇒ 10.
    pub max_turns: usize,
    /// SPEC §8 "Stop-reason recovery" (default false): answer a malformed tool call with a
    /// corrective message (once), a truncated reply (length/max_tokens) with a continue
    /// message (up to 3 times, text joined) and an empty answer with a nudge (once).
    pub stop_recovery: bool,
    /// Cap on concurrency-safe tool calls of one response running at once (SPEC §8
    /// "Tool scheduling"). 0 ⇒ env `TOOLNEXUS_MAX_TOOL_CONCURRENCY` when a positive
    /// integer, else 10. Unsafe calls (anything not read-only) always run alone.
    pub max_tool_concurrency: usize,
    pub hooks: Hooks,
    /// 0 ⇒ 2 retries (3 attempts); -1 ⇒ no retries; n ⇒ n.
    pub retries: i32,
    /// 0 ⇒ 500 ms.
    pub retry_base_ms: u64,
    /// Extra statuses to retry, ADDED to 429/500/502/503/504/529.
    pub retryable_statuses: Vec<u16>,
    /// Whole-run deadline in ms (0 = none).
    pub timeout_ms: u64,
    /// `None` ⇒ in-memory.
    pub store: Option<Arc<dyn ConversationStore>>,
    pub on_metric: Option<OnMetricFn>,
    pub wait_for: Option<WaitForFn>,
    /// Used instead of `wait_for` when both are set.
    pub wait_for_ctx: Option<WaitForCtxFn>,
    /// Merged into every request body (not `messages`/`tools`/`stream`).
    pub request_params: Map<String, Value>,
    /// Last word on the request body (`None` return keeps it).
    pub body_transform: Option<BodyTransformFn>,
    pub prompt_cache: Option<PromptCacheOptions>,
    pub tool_tiers: Option<ToolTierOptions>,
    pub http_client: Option<reqwest::Client>,
    pub on_error: Option<OnErrorFn>,
    /// Per-part decoded size cap (0 = none).
    pub max_part_bytes: usize,
    /// `"error"` | `"text"` | empty (strict for attached parts, placeholder for tool parts).
    pub on_unsupported_part: String,
}

// ---------------------------------------------------------------------------
// results, events, errors
// ---------------------------------------------------------------------------

/// One executed tool call.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ToolCall {
    pub name: String,
    pub args: Value,
    pub output: String,
    #[serde(rename = "isError")]
    pub is_error: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, Value>>,
    /// The tool result's non-text parts (§1B); present iff it had parts.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub parts: Vec<ContentPart>,
}

/// A non-text part produced by a tool call during a run (§8 `RunResult.files`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct RunFile {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(rename = "mimeType", default, skip_serializing_if = "String::is_empty")]
    pub mime_type: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub data: String,
    /// The part's name, when it has one.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    /// The tool's name.
    pub from: String,
    /// The model's tool-call id.
    #[serde(rename = "callId")]
    pub call_id: String,
}

/// The `files` entries a tool call contributes: every non-text part, in order.
pub fn run_files_of(call: &ToolCall, call_id: &str) -> Vec<RunFile> {
    call.parts
        .iter()
        .filter(|p| p.kind != "text")
        .map(|p| RunFile {
            kind: p.kind.clone(),
            mime_type: p.mime_type.clone(),
            url: p.url.clone(),
            data: p.data.clone(),
            name: p.name.clone(),
            from: call.name.clone(),
            call_id: call_id.to_string(),
        })
        .collect()
}

/// Token usage, summed over a run.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Usage {
    #[serde(rename = "promptTokens")]
    pub prompt_tokens: i64,
    #[serde(rename = "completionTokens")]
    pub completion_tokens: i64,
    #[serde(rename = "totalTokens")]
    pub total_tokens: i64,
    #[serde(rename = "cacheReadTokens", skip_serializing_if = "is_zero", default)]
    pub cache_read_tokens: i64,
    #[serde(rename = "cacheWriteTokens", skip_serializing_if = "is_zero", default)]
    pub cache_write_tokens: i64,
}

fn is_zero(n: &i64) -> bool {
    *n == 0
}

/// The outcome of a run. `status`: `done` | `pending` | `incomplete` (with `limit`).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct RunResult {
    pub text: String,
    pub messages: Vec<Value>,
    #[serde(rename = "toolCalls")]
    pub tool_calls: Vec<ToolCall>,
    /// Every non-text part tool calls produced this run, in call order (§8).
    #[serde(default)]
    pub files: Vec<RunFile>,
    #[serde(rename = "toolCallCount")]
    pub tool_call_count: usize,
    pub turns: usize,
    pub usage: Usage,
    pub model: String,
    pub status: String,
    #[serde(skip_serializing_if = "String::is_empty", default)]
    pub limit: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub pending: Option<Request>,
}

/// A streaming event. `kind`: `text` | `thought` | `tool_call` | `tool_result` |
/// `usage` | `pending` | `done` | `error`. `thought` (an ACP agent's
/// `agent_thought_chunk`, `via: "acp"`) is never part of the answer text.
#[derive(Debug, Clone, Default)]
pub struct StreamEvent {
    pub kind: String,
    pub delta: String,
    pub name: String,
    /// `"call_tool"` when the model called the tool through `call_tool`; `"acp"` from an ACP agent.
    pub via: String,
    pub id: String,
    pub args: Value,
    pub output: String,
    pub is_error: bool,
    pub metadata: Option<Map<String, Value>>,
    pub usage: Option<Usage>,
    pub result: Option<Box<RunResult>>,
    pub request: Option<Request>,
    pub error: Option<ClientError>,
}

impl StreamEvent {
    fn of(kind: &str) -> Self {
        Self {
            kind: kind.into(),
            ..Default::default()
        }
    }
    /// A text delta (for in-process `generate_stream` emitters).
    pub fn text(delta: impl Into<String>) -> Self {
        Self {
            kind: "text".into(),
            delta: delta.into(),
            ..Default::default()
        }
    }
}

/// A non-2xx provider answer. The body is redacted (account ids) and dropped
/// entirely for 401/403.
#[derive(Debug, Clone, PartialEq)]
pub struct ProviderError {
    pub status: u16,
    pub body: String,
    pub retry_after: String,
}

/// Marker that replaces a redacted account identifier in a provider error body.
pub const REDACTED_MARKER: &str = "«redacted»";

/// Redact account identifiers from a provider error body (empty for 401/403).
pub fn redact_provider_body(status: u16, body: &str) -> String {
    if status == 401 || status == 403 {
        return String::new();
    }
    let s = body.trim();
    if s.is_empty() {
        return String::new();
    }
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(
            r#"("(?:user_id|account_id|org_id|organization)"\s*:\s*)("(?:[^"\\]|\\.)*"|[^,}\s]+)"#,
        )
        .expect("static regex")
    });
    re.replace_all(s, format!("${{1}}\"{REDACTED_MARKER}\"").as_str())
        .into_owned()
}

impl std::fmt::Display for ProviderError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.body.is_empty() {
            return write!(f, "LLM {}", self.status);
        }
        let b = &self.body;
        if b.len() > 200 {
            let mut n = 200;
            while !b.is_char_boundary(n) {
                n -= 1;
            }
            write!(f, "LLM {}: {}…", self.status, &b[..n])
        } else {
            write!(f, "LLM {}: {}", self.status, b)
        }
    }
}

/// Every way a run can fail.
#[derive(Debug, Clone, thiserror::Error)]
pub enum ClientError {
    #[error("{0}")]
    NoApiKey(String),
    #[error("{0}")]
    Provider(ProviderError),
    #[error("{0}")]
    Transport(String),
    #[error("decode LLM response: {0}")]
    Decode(String),
    #[error("{0}")]
    Hook(String),
    #[error("waitFor: {0}")]
    WaitFor(String),
    #[error("{0}")]
    Part(String),
    #[error("conversation store: {0}")]
    Store(String),
    #[error("context canceled")]
    Cancelled,
    /// The whole-run `timeout_ms` elapsed; `partial` is the incomplete result
    /// (`status:"incomplete"`, `limit:"timeout"`).
    #[error("run timeout after {timeout_ms}ms")]
    RunTimeout {
        timeout_ms: u64,
        partial: Box<RunResult>,
    },
    #[error("{0}")]
    Unsupported(String),
    /// A durable resume (`run_with_answer` / `ask_with_answer`) was refused.
    #[error("{0}")]
    Resume(String),
}

/// What a run starts from.
#[derive(Debug, Clone)]
pub enum Prompt {
    Text(String),
    /// Multimodal user turn (§1B).
    Parts(Vec<ContentPart>),
}

impl From<&str> for Prompt {
    fn from(s: &str) -> Self {
        Prompt::Text(s.into())
    }
}
impl From<String> for Prompt {
    fn from(s: String) -> Self {
        Prompt::Text(s)
    }
}
impl From<Vec<ContentPart>> for Prompt {
    fn from(p: Vec<ContentPart>) -> Self {
        Prompt::Parts(p)
    }
}

fn user_message(p: &Prompt) -> Value {
    match p {
        Prompt::Text(s) => json!({"role": "user", "content": s}),
        Prompt::Parts(parts) => json!({"role": "user", "content": parts}),
    }
}

// ---------------------------------------------------------------------------
// client
// ---------------------------------------------------------------------------

const DEFAULT_RETRYABLE: [u16; 6] = [429, 500, 502, 503, 504, 529];

fn is_retryable_status(status: u16, extra: &[u16]) -> bool {
    DEFAULT_RETRYABLE.contains(&status) || extra.contains(&status)
}

/// Parse a `Retry-After` header: plain non-negative integer seconds ≤ i32::MAX only.
pub fn retry_after_seconds(v: &str) -> Option<Duration> {
    let s = v.trim();
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let secs: i64 = s.parse().ok()?;
    if secs > i32::MAX as i64 {
        return None;
    }
    Some(Duration::from_secs(secs as u64))
}

pub(crate) enum Transport {
    Http(reqwest::Client),
    InProcess(Arc<inprocess::InProcess>),
}

pub(crate) enum RespBody {
    Http(reqwest::Response),
    Full(Vec<u8>),
    Chunks(mpsc::UnboundedReceiver<Result<Vec<u8>, String>>),
}

pub(crate) struct RawResponse {
    pub status: u16,
    pub retry_after: String,
    pub body: RespBody,
}

impl RawResponse {
    async fn read_all(self) -> Result<Vec<u8>, ClientError> {
        match self.body {
            RespBody::Full(b) => Ok(b),
            RespBody::Http(r) => r
                .bytes()
                .await
                .map(|b| b.to_vec())
                .map_err(|e| ClientError::Transport(e.to_string())),
            RespBody::Chunks(mut rx) => {
                let mut out = Vec::new();
                while let Some(c) = rx.recv().await {
                    out.extend(c.map_err(ClientError::Transport)?);
                }
                Ok(out)
            }
        }
    }
}

/// Run `fut` on a runtime worker thread when the runtime is multi-threaded.
///
/// A host usually awaits a run from `block_on` (`#[tokio::main]`), whose
/// thread is not a worker: every wake from the I/O driver (HTTP reply, MCP
/// reply) then has to unpark that thread. On a worker the run is woken in
/// place, one hand-off per run instead of one per await. Dropping the
/// returned future aborts the task (cancel-on-drop, as before); a panic
/// inside resumes on the caller.
pub(crate) async fn on_worker<F>(fut: F) -> F::Output
where
    F: std::future::Future + Send + 'static,
    F::Output: Send + 'static,
{
    use tokio::runtime::{Handle, RuntimeFlavor};
    let multi =
        Handle::try_current().is_ok_and(|h| h.runtime_flavor() == RuntimeFlavor::MultiThread);
    if !multi {
        return fut.await;
    }
    struct AbortOnDrop<T>(tokio::task::JoinHandle<T>);
    impl<T> Drop for AbortOnDrop<T> {
        fn drop(&mut self) {
            self.0.abort();
        }
    }
    let mut h = AbortOnDrop(tokio::spawn(fut));
    match (&mut h.0).await {
        Ok(v) => v,
        Err(e) if e.is_panic() => std::panic::resume_unwind(e.into_panic()),
        // we abort only when dropped, so this is a runtime shutting down
        Err(e) => panic!("toolnexus: run task cancelled by the runtime: {e}"),
    }
}

/// The §7D global turn gate: at most N LLM calls in flight across every
/// client that shares it. It wraps ONLY the provider call (never a whole run),
/// and the slot is released on any exit, including a cancelled caller.
pub(crate) struct TurnGate {
    sem: Arc<tokio::sync::Semaphore>,
    counts: Mutex<(usize, usize)>,
}

pub(crate) struct TurnSlot<'a> {
    gate: &'a TurnGate,
    _permit: tokio::sync::OwnedSemaphorePermit,
}

impl Drop for TurnSlot<'_> {
    fn drop(&mut self) {
        let mut c = self.gate.counts.lock().unwrap_or_else(|e| e.into_inner());
        c.0 -= 1;
    }
}

impl TurnGate {
    pub(crate) fn new(n: usize) -> Self {
        TurnGate {
            sem: Arc::new(tokio::sync::Semaphore::new(n.max(1))),
            counts: Mutex::new((0, 0)),
        }
    }
    async fn acquire(&self) -> TurnSlot<'_> {
        let permit = self
            .sem
            .clone()
            .acquire_owned()
            .await
            .expect("turn gate semaphore is never closed");
        let mut c = self.counts.lock().unwrap_or_else(|e| e.into_inner());
        c.0 += 1;
        if c.0 > c.1 {
            c.1 = c.0;
        }
        TurnSlot {
            gate: self,
            _permit: permit,
        }
    }
    /// The high-water mark of in-flight calls.
    pub(crate) fn max_observed(&self) -> usize {
        self.counts.lock().unwrap_or_else(|e| e.into_inner()).1
    }
}

struct Inner {
    opts: ClientOptions,
    transport: Transport,
    gate: Option<Arc<TurnGate>>,
    store: Arc<dyn ConversationStore>,
    registry: metrics::Registry,
    plans: Mutex<HashMap<usize, Arc<tiers::Plan>>>,
}

/// The unified client. Cheap to clone.
#[derive(Clone)]
pub struct Client {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Client {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Client")
            .field("base_url", &self.inner.opts.base_url)
            .field("style", &self.inner.opts.style)
            .field("model", &self.inner.opts.model)
            .finish_non_exhaustive()
    }
}

/// Build a client (§8).
pub fn create_client(opts: ClientOptions) -> Client {
    let transport = Transport::Http(opts.http_client.clone().unwrap_or_default());
    Client::with_transport(opts, transport)
}

#[derive(Clone)]
struct Call {
    id: String,
    name: String,
    args: Value,
}

#[derive(Default)]
struct RunState {
    messages: Vec<Value>,
    tool_calls: Vec<ToolCall>,
    files: Vec<RunFile>,
    usage: Usage,
    turns: usize,
    last_model: String,
    /// The transcript was built (an error after this point keeps a partial).
    started: bool,
    /// The last response stopped at the output token limit (length / max_tokens).
    truncated: bool,
    /// The last response had a tool call whose arguments are not a JSON object.
    malformed: bool,
    /// Stop-reason recovery budgets + carried text (per run).
    rec: StopRecovery,
}

/// The three pinned user messages of SPEC §8 "Stop-reason recovery" — byte-exact in every port.
pub const STOP_RECOVERY_NUDGE: &str =
    "Your previous response contained no answer. Continue and give your answer.";
pub const STOP_RECOVERY_CONTINUE: &str = "Your previous response was cut off at the output token limit. Continue exactly where it stopped, without repeating anything.";
pub const STOP_RECOVERY_CORRECTIVE: &str = "Your previous tool call could not be used: its arguments were not a valid JSON object. Call the tool again with arguments that are a single valid JSON object matching its input schema.";

#[derive(Default)]
struct StopRecovery {
    correct: u8,
    cont: u8,
    nudges: u8,
    carry: String,
}

impl StopRecovery {
    /// Classifies one response (SPEC §8 order): the pinned text to append, and whether the
    /// assistant message is dropped. `None` ⇒ no recovery.
    fn decide(
        &mut self,
        has_calls: bool,
        malformed: bool,
        truncated: bool,
        text: &str,
    ) -> Option<(&'static str, bool)> {
        if has_calls && malformed && self.correct < 1 {
            self.correct += 1;
            Some((STOP_RECOVERY_CORRECTIVE, true))
        } else if !has_calls && truncated && self.cont < 3 {
            self.cont += 1;
            self.carry.push_str(text);
            Some((STOP_RECOVERY_CONTINUE, false))
        } else if !has_calls && !truncated && text.trim().is_empty() && self.nudges < 1 {
            self.nudges += 1;
            Some((STOP_RECOVERY_NUDGE, true))
        } else {
            None
        }
    }
}

/// A raw tool-arguments string that is not a JSON object (empty/whitespace is `{}`).
fn malformed_args(raw: &str) -> bool {
    let t = raw.trim();
    !t.is_empty() && !matches!(serde_json::from_str::<Value>(t), Ok(Value::Object(_)))
}

enum TurnEnd {
    /// No choices: end with empty text, no assistant message.
    Empty,
    Final(String),
    Calls(Vec<Call>),
}

type Tx = mpsc::UnboundedSender<StreamEvent>;

fn send(tx: Option<&Tx>, ev: StreamEvent) {
    if let Some(tx) = tx {
        let _ = tx.send(ev);
    }
}

fn ms_since(t: Instant) -> i64 {
    t.elapsed().as_millis() as i64
}

fn num(raw: &Value, k: &str) -> i64 {
    raw.get(k).and_then(Value::as_f64).unwrap_or(0.0) as i64
}

fn add_usage(acc: &mut Usage, raw: &Value, style: Style) {
    if !raw.is_object() {
        return;
    }
    if style == Style::Anthropic {
        let (i, o) = (num(raw, "input_tokens"), num(raw, "output_tokens"));
        acc.prompt_tokens += i;
        acc.completion_tokens += o;
        acc.total_tokens += i + o;
        acc.cache_read_tokens += num(raw, "cache_read_input_tokens");
        acc.cache_write_tokens += num(raw, "cache_creation_input_tokens");
        return;
    }
    if let Some(d) = raw.get("prompt_tokens_details") {
        acc.cache_read_tokens += num(d, "cached_tokens");
    }
    let (p, c) = (num(raw, "prompt_tokens"), num(raw, "completion_tokens"));
    acc.prompt_tokens += p;
    acc.completion_tokens += c;
    let total = num(raw, "total_tokens");
    acc.total_tokens += if total != 0 { total } else { p + c };
}

fn per_call(raw: &Value, style: Style) -> (i64, i64) {
    if style == Style::Anthropic {
        (num(raw, "input_tokens"), num(raw, "output_tokens"))
    } else {
        (num(raw, "prompt_tokens"), num(raw, "completion_tokens"))
    }
}

fn safe_json_args(s: &str) -> Value {
    match serde_json::from_str::<Value>(s) {
        Ok(Value::Object(m)) => Value::Object(m),
        _ => Value::Object(Map::new()),
    }
}

fn obj_or_empty(v: Option<&Value>) -> Value {
    match v {
        Some(Value::Object(m)) => Value::Object(m.clone()),
        _ => Value::Object(Map::new()),
    }
}

fn last_text(messages: &[Value]) -> String {
    for m in messages.iter().rev() {
        if m.get("role").and_then(Value::as_str) != Some("assistant") {
            continue;
        }
        if let Some(s) = m.get("content").and_then(Value::as_str) {
            return s.into();
        }
    }
    String::new()
}

const INTERRUPTED_OUTPUT: &str = "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first.";

/// Pair every unanswered tool call of the last assistant turn with an
/// `interrupted` result, so a saved transcript stays valid for the provider.
fn seal_interrupted(mut messages: Vec<Value>) -> Vec<Value> {
    let Some(last) = messages
        .iter()
        .rposition(|m| m.get("role").and_then(Value::as_str) == Some("assistant"))
    else {
        return messages;
    };
    let asst = messages[last].clone();
    if let Some(calls) = asst
        .get("tool_calls")
        .and_then(Value::as_array)
        .filter(|c| !c.is_empty())
    {
        let have: std::collections::HashSet<String> = messages[last + 1..]
            .iter()
            .filter(|m| m.get("role").and_then(Value::as_str) == Some("tool"))
            .filter_map(|m| {
                m.get("tool_call_id")
                    .and_then(Value::as_str)
                    .map(String::from)
            })
            .collect();
        for tc in calls {
            let id = tc.get("id").and_then(Value::as_str).unwrap_or("");
            if id.is_empty() || have.contains(id) {
                continue;
            }
            messages
                .push(json!({"role": "tool", "tool_call_id": id, "content": INTERRUPTED_OUTPUT}));
        }
        return messages;
    }
    let ids: Vec<String> = asst
        .get("content")
        .and_then(Value::as_array)
        .map(|bs| {
            bs.iter()
                .filter(|b| b.get("type").and_then(Value::as_str) == Some("tool_use"))
                .filter_map(|b| b.get("id").and_then(Value::as_str))
                .filter(|s| !s.is_empty())
                .map(String::from)
                .collect()
        })
        .unwrap_or_default();
    if ids.is_empty() {
        return messages;
    }
    let mut blocks: Vec<Value> = Vec::new();
    let mut have = std::collections::HashSet::new();
    let mut has_user = false;
    if let Some(um) = messages.get(last + 1)
        && um.get("role").and_then(Value::as_str) == Some("user")
        && let Some(bs) = um.get("content").and_then(Value::as_array)
    {
        has_user = true;
        blocks.extend(bs.iter().cloned());
        for b in bs {
            if b.get("type").and_then(Value::as_str) == Some("tool_result")
                && let Some(id) = b.get("tool_use_id").and_then(Value::as_str)
            {
                have.insert(id.to_owned());
            }
        }
    }
    let mut added = false;
    for id in ids {
        if !have.contains(&id) {
            blocks.push(json!({"type": "tool_result", "tool_use_id": id, "content": INTERRUPTED_OUTPUT, "is_error": true}));
            added = true;
        }
    }
    if !added {
        return messages;
    }
    let user = json!({"role": "user", "content": blocks});
    if has_user {
        messages[last + 1] = user;
    } else {
        messages.push(user);
    }
    messages
}

/// Split SSE bytes into lines (`\r` trimmed).
struct LineReader {
    buf: Vec<u8>,
}

impl LineReader {
    fn push(&mut self, chunk: &[u8]) -> Vec<String> {
        self.buf.extend_from_slice(chunk);
        let mut out = Vec::new();
        while let Some(i) = self.buf.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = self.buf.drain(..=i).collect();
            let s = String::from_utf8_lossy(&line[..line.len() - 1]);
            out.push(s.trim_end_matches('\r').to_owned());
        }
        out
    }
    fn finish(&mut self) -> Option<String> {
        if self.buf.is_empty() {
            return None;
        }
        let s = String::from_utf8_lossy(&self.buf)
            .trim_end_matches('\r')
            .to_owned();
        self.buf.clear();
        Some(s)
    }
}

impl Client {
    pub(crate) fn with_transport(opts: ClientOptions, transport: Transport) -> Client {
        Self::with_transport_gate(opts, transport, None)
    }

    /// A client whose provider calls pass through a shared [`TurnGate`]; with
    /// `in_process` the model is a host function (as `create_in_process_client`).
    pub(crate) fn gated(
        opts: ClientOptions,
        in_process: Option<GenerateFn>,
        gate: Arc<TurnGate>,
    ) -> Client {
        let transport = match in_process {
            Some(g) => inprocess::transport_of(g),
            None => Transport::Http(opts.http_client.clone().unwrap_or_default()),
        };
        Self::with_transport_gate(opts, transport, Some(gate))
    }

    fn with_transport_gate(
        opts: ClientOptions,
        transport: Transport,
        gate: Option<Arc<TurnGate>>,
    ) -> Client {
        let store = opts
            .store
            .clone()
            .unwrap_or_else(|| Arc::new(InMemoryConversationStore::new()));
        Client {
            inner: Arc::new(Inner {
                opts,
                transport,
                gate,
                store,
                registry: metrics::Registry::default(),
                plans: Mutex::new(HashMap::new()),
            }),
        }
    }

    fn opts(&self) -> &ClientOptions {
        &self.inner.opts
    }

    /// Prometheus text for everything this client did (byte-identical across ports).
    pub fn metrics(&self) -> String {
        self.inner.registry.render()
    }

    /// Feed one event to the registry and `on_metric` (used by tests and hosts that
    /// aggregate runs from elsewhere).
    pub fn record_metric(&self, ev: MetricEvent) {
        self.inner.registry.record(&ev);
        if let Some(f) = &self.opts().on_metric {
            f(&ev);
        }
    }

    /// The conversation store `ask` uses.
    pub fn conversation_store(&self) -> Arc<dyn ConversationStore> {
        self.inner.store.clone()
    }

    fn emit_llm(&self, model: &str, status: &str, start: Instant, p: i64, c: i64) {
        self.record_metric(MetricEvent {
            event: "llm".into(),
            model: model.into(),
            status: status.into(),
            ms: ms_since(start),
            prompt_tokens: p,
            completion_tokens: c,
            ..Default::default()
        });
    }

    fn emit_run(&self, st: &RunState, start: Instant, error: String) {
        self.record_metric(MetricEvent {
            event: "run".into(),
            model: st.last_model.clone(),
            turns: st.turns as i64,
            tool_calls: st.tool_calls.len() as i64,
            total_tokens: st.usage.total_tokens,
            ms: ms_since(start),
            error,
            ..Default::default()
        });
    }

    fn emit_tool(&self, name: &str, source: &str, r: &ToolResult, start: Instant) {
        let pending = pending_of(r).is_some();
        self.record_metric(MetricEvent {
            event: "tool".into(),
            tool: name.into(),
            source: source.into(),
            is_error: !pending && r.is_error,
            pending,
            ms: ms_since(start),
            ..Default::default()
        });
    }

    fn max_turns(&self) -> usize {
        if self.opts().max_turns > 0 {
            self.opts().max_turns
        } else {
            10
        }
    }

    fn retries(&self) -> u32 {
        match self.opts().retries {
            -1 => 0,
            n if n > 0 => n as u32,
            _ => 2,
        }
    }

    fn retry_base_ms(&self) -> u64 {
        if self.opts().retry_base_ms > 0 {
            self.opts().retry_base_ms
        } else {
            500
        }
    }

    /// The wait before retry `attempt`: an honoured `Retry-After`, else
    /// exponential backoff with up to 100 ms of jitter.
    pub fn backoff(&self, attempt: u32, retry_after: &str) -> Duration {
        if let Some(d) = retry_after_seconds(retry_after) {
            return d;
        }
        let jitter = (std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(0)
            % 100) as u64;
        Duration::from_millis(self.retry_base_ms() * (1u64 << attempt.min(30)) + jitter)
    }

    /// The API key for this client's host (SPEC §8, 0.22.0): explicit `api_key`,
    /// else `api_key_env`, else the provider env var matching an https host. A key
    /// is never sent to a host it does not belong to.
    pub fn resolve_key(&self) -> Result<String, ClientError> {
        let o = self.opts();
        if !o.api_key.is_empty() {
            return Ok(o.api_key.clone());
        }
        let env_name = if o.api_key_env.is_empty() {
            key_env_for_base_url(&o.base_url).to_owned()
        } else {
            o.api_key_env.clone()
        };
        if env_name.is_empty() {
            return Err(ClientError::NoApiKey(format!(
                "no API key: set APIKey or APIKeyEnv (provider env vars are only used for https://api.openai.com, https://api.anthropic.com and https://openrouter.ai, never for {:?})",
                o.base_url
            )));
        }
        match std::env::var(&env_name) {
            Ok(k) if !k.is_empty() => Ok(k),
            _ => Err(ClientError::NoApiKey(format!(
                "no API key: set APIKey or {env_name}"
            ))),
        }
    }

    fn system(&self, tk: &Toolkit) -> String {
        let mut parts = Vec::new();
        if !self.opts().system_prompt.is_empty() {
            parts.push(self.opts().system_prompt.clone());
        }
        let sp = tk.skills_prompt();
        if !sp.is_empty() {
            parts.push(sp);
        }
        parts.join("\n\n")
    }

    // -- tool tiers ---------------------------------------------------------

    fn plan(&self, tk: &Toolkit) -> Option<Arc<tiers::Plan>> {
        let o = self.opts().tool_tiers.as_ref()?;
        let mut plans = self.inner.plans.lock().unwrap_or_else(|e| e.into_inner());
        Some(
            plans
                .entry(tk.identity())
                .or_insert_with(|| Arc::new(tiers::build_plan(o, &tk.tools())))
                .clone(),
        )
    }

    /// The frozen tier of every tool in `tk` (`None` without `tool_tiers`).
    pub fn tool_tier_plan(&self, tk: &Toolkit) -> Option<HashMap<String, ToolTier>> {
        self.plan(tk)
            .map(|p| p.tier.iter().map(|(k, v)| (k.clone(), *v)).collect())
    }

    /// Drop the frozen plan; the next request rebuilds it.
    pub fn reset_tool_tiers(&self, tk: &Toolkit) {
        self.inner
            .plans
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&tk.identity());
    }

    /// The tools sent on a request: everything, or (with tiers) the core schemas
    /// plus `tool_search` and `call_tool`.
    pub fn request_tools(&self, tk: &Toolkit) -> Vec<crate::tool::Tool> {
        let Some(p) = self.plan(tk) else {
            return tk.tools();
        };
        let mut out: Vec<_> = p.core.iter().filter_map(|n| tk.get(n)).collect();
        out.push(p.search.clone());
        out.push(p.call.clone());
        out
    }

    /// Run `tool_search` against a toolkit (what the loop does for the model).
    pub fn tool_search(&self, tk: &Toolkit, args: &Value) -> ToolResult {
        tiers::tool_search(tk, args)
    }

    /// One call's safety; looks through `call_tool` to the tool it names. Unknown ⇒ unsafe.
    fn call_safe(&self, tk: &Toolkit, call: &Call) -> bool {
        if self.opts().tool_tiers.is_some() && call.name == "tool_search" {
            return true;
        }
        let (name, args, _) = self.unwrap_call(&call.name, &call.args);
        tk.get(&name)
            .map(|t| crate::tool::is_concurrency_safe(&t, &args))
            .unwrap_or(false)
    }

    fn unwrap_call(&self, name: &str, args: &Value) -> (String, Value, bool) {
        if self.opts().tool_tiers.is_none() || name != "call_tool" {
            return (name.into(), args.clone(), false);
        }
        let real = args
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        (real, obj_or_empty(args.get("arguments")), true)
    }

    // -- HTTP -----------------------------------------------------------------

    fn finalize_body(&self, mut body: Map<String, Value>) -> Value {
        let o = self.opts();
        if let Some(pc) = &o.prompt_cache {
            cache::apply(&mut body, o.style, pc);
        }
        for (k, v) in &o.request_params {
            if k == "messages" || k == "tools" || k == "stream" {
                eprintln!(
                    "[toolnexus] requestParams key {k:?} is not allowed — ignored (use BodyTransform)"
                );
                continue;
            }
            body.insert(k.clone(), v.clone());
        }
        let body = Value::Object(body);
        if let Some(t) = &o.body_transform
            && let Some(out) = t(body.clone())
        {
            return out;
        }
        body
    }

    async fn send_once(
        &self,
        endpoint: &str,
        headers: &[(String, String)],
        raw: &[u8],
        cancel: &CancellationToken,
        side: Option<&Tx>,
    ) -> Result<RawResponse, String> {
        let _slot = match &self.inner.gate {
            Some(g) => Some(g.acquire().await),
            None => None,
        };
        match &self.inner.transport {
            Transport::InProcess(ip) => ip.respond(raw, cancel, side).await,
            Transport::Http(http) => {
                let mut req = http
                    .post(endpoint)
                    .header("Content-Type", "application/json");
                for (k, v) in headers {
                    req = req.header(k, v);
                }
                for (k, v) in &self.opts().headers {
                    req = req.header(k, v);
                }
                let resp = req
                    .body(raw.to_vec())
                    .send()
                    .await
                    .map_err(|e| e.to_string())?;
                let retry_after = resp
                    .headers()
                    .get("retry-after")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_owned();
                Ok(RawResponse {
                    status: resp.status().as_u16(),
                    retry_after,
                    body: RespBody::Http(resp),
                })
            }
        }
    }

    async fn sleep(&self, d: Duration, cancel: &CancellationToken) -> Result<(), ClientError> {
        tokio::select! {
            _ = cancel.cancelled() => Err(ClientError::Cancelled),
            _ = tokio::time::sleep(d) => Ok(()),
        }
    }

    /// POST with retries (§8 Resilience). A final non-2xx is returned for the
    /// caller to surface as a [`ProviderError`].
    async fn llm_fetch(
        &self,
        endpoint: &str,
        headers: &[(String, String)],
        raw: &[u8],
        cancel: &CancellationToken,
        side: Option<&Tx>,
    ) -> Result<RawResponse, ClientError> {
        let retries = self.retries();
        let classify = |info: &ErrorInfo| match &self.opts().on_error {
            Some(f) => f(info),
            None => {
                if info.retryable {
                    ErrorTier::Retry
                } else {
                    ErrorTier::Fail
                }
            }
        };
        let mut attempt = 0;
        loop {
            let r = tokio::select! {
                _ = cancel.cancelled() => return Err(ClientError::Cancelled),
                r = self.send_once(endpoint, headers, raw, cancel, side) => r,
            };
            match r {
                Err(e) => {
                    if cancel.is_cancelled() {
                        return Err(ClientError::Cancelled);
                    }
                    let tier = classify(&ErrorInfo {
                        error: e.clone(),
                        status: 0,
                        attempt,
                        retryable: true,
                    });
                    if tier == ErrorTier::Fail || attempt == retries {
                        return Err(ClientError::Transport(e));
                    }
                    self.sleep(self.backoff(attempt, ""), cancel).await?;
                }
                Ok(resp) => {
                    if resp.status < 300 {
                        return Ok(resp);
                    }
                    let retryable =
                        is_retryable_status(resp.status, &self.opts().retryable_statuses);
                    let tier = classify(&ErrorInfo {
                        error: format!("LLM {}", resp.status),
                        status: resp.status,
                        attempt,
                        retryable,
                    });
                    if tier == ErrorTier::Fail || attempt == retries {
                        return Ok(resp);
                    }
                    let ra = resp.retry_after.clone();
                    drop(resp);
                    self.sleep(self.backoff(attempt, &ra), cancel).await?;
                }
            }
            attempt += 1;
        }
    }

    async fn provider_error(resp: RawResponse) -> ClientError {
        let (status, ra) = (resp.status, resp.retry_after.clone());
        let body = resp.read_all().await.unwrap_or_default();
        ClientError::Provider(ProviderError {
            status,
            body: redact_provider_body(status, &String::from_utf8_lossy(&body)),
            retry_after: ra,
        })
    }

    fn endpoint(&self) -> String {
        let base = self.opts().base_url.trim_end_matches('/');
        match self.opts().style {
            Style::OpenAI => format!("{base}/chat/completions"),
            Style::Anthropic => {
                if base.ends_with("/v1") {
                    format!("{base}/messages")
                } else {
                    format!("{base}/v1/messages")
                }
            }
        }
    }

    fn auth_headers(&self, key: &str) -> Vec<(String, String)> {
        match self.opts().style {
            Style::OpenAI => vec![("Authorization".into(), format!("Bearer {key}"))],
            Style::Anthropic => vec![
                ("x-api-key".into(), key.into()),
                ("anthropic-version".into(), "2023-06-01".into()),
            ],
        }
    }

    fn wire(&self, messages: &[Value]) -> Result<Vec<Value>, ClientError> {
        if !emit::any_parts(messages) {
            return Ok(messages.to_vec());
        }
        let mut e = emit::Emitter {
            style: self.opts().style,
            max_bytes: self.opts().max_part_bytes,
            mode: self.opts().on_unsupported_part.clone(),
            warned: false,
        };
        e.emit(messages).map_err(ClientError::Part)
    }

    // -- one model call ---------------------------------------------------------

    /// Call the model once; returns the assistant message to append (if any) and
    /// how the turn ends.
    #[allow(clippy::too_many_arguments)]
    async fn call_model(
        &self,
        st: &mut RunState,
        key: &str,
        model: &str,
        system: &str,
        tools: &[Value],
        turn: usize,
        cancel: &CancellationToken,
        tx: Option<&Tx>,
    ) -> Result<(Option<Value>, TurnEnd), ClientError> {
        let style = self.opts().style;
        let streaming = tx.is_some();
        let wire = self.wire(&st.messages)?;
        let mut body = Map::new();
        body.insert("model".into(), json!(model));
        if style == Style::Anthropic {
            body.insert("max_tokens".into(), json!(4096));
        }
        body.insert("messages".into(), Value::Array(wire));
        if streaming {
            body.insert("stream".into(), json!(true));
            if style == Style::OpenAI {
                body.insert("stream_options".into(), json!({"include_usage": true}));
            }
        }
        if style == Style::Anthropic && !system.is_empty() {
            body.insert("system".into(), json!(system));
        }
        if !tools.is_empty() {
            body.insert("tools".into(), Value::Array(tools.to_vec()));
            if style == Style::OpenAI {
                body.insert("tool_choice".into(), json!("auto"));
            }
        }
        let body = self.finalize_body(body);
        let raw = serde_json::to_vec(&body).map_err(|e| ClientError::Decode(e.to_string()))?;
        let t0 = Instant::now();
        st.last_model = model.into();
        let before = st.usage;
        let resp = match self
            .llm_fetch(&self.endpoint(), &self.auth_headers(key), &raw, cancel, tx)
            .await
        {
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
        let parsed = if streaming {
            self.read_stream(st, resp, cancel, tx).await
        } else {
            self.read_full(st, resp).await
        };
        let (assistant, end, response) = match parsed {
            Ok(x) => x,
            Err(e) => {
                self.emit_llm(model, "error", t0, 0, 0);
                return Err(e);
            }
        };
        if streaming {
            self.emit_llm(
                model,
                "ok",
                t0,
                st.usage.prompt_tokens - before.prompt_tokens,
                st.usage.completion_tokens - before.completion_tokens,
            );
        }
        if let Some(h) = &self.opts().hooks.after_llm {
            h(AfterLlmEvent {
                response,
                model: model.into(),
                turn,
            })
            .await
            .map_err(ClientError::Hook)?;
        }
        Ok((assistant, end))
    }

    async fn read_full(
        &self,
        st: &mut RunState,
        resp: RawResponse,
    ) -> Result<(Option<Value>, TurnEnd, Value), ClientError> {
        let style = self.opts().style;
        let model = st.last_model.clone();
        let t0 = Instant::now();
        let raw = resp.read_all().await?;
        let data: Value =
            serde_json::from_slice(&raw).map_err(|e| ClientError::Decode(e.to_string()))?;
        if !data.is_object() {
            return Err(ClientError::Decode("response is not an object".into()));
        }
        let usage = data.get("usage").cloned().unwrap_or(Value::Null);
        let (p, c) = per_call(&usage, style);
        self.emit_llm(&model, "ok", t0, p, c);
        add_usage(&mut st.usage, &usage, style);
        if style == Style::OpenAI {
            let Some(msg) = data
                .get("choices")
                .and_then(Value::as_array)
                .and_then(|c| c.first())
                .map(|c| c.get("message").cloned().unwrap_or(Value::Null))
            else {
                return Ok((None, TurnEnd::Empty, data));
            };
            let content = msg
                .get("content")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            st.truncated = data
                .pointer("/choices/0/finish_reason")
                .and_then(Value::as_str)
                == Some("length");
            st.malformed = msg
                .get("tool_calls")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .any(|tc| {
                    malformed_args(
                        tc.pointer("/function/arguments")
                            .and_then(Value::as_str)
                            .unwrap_or(""),
                    )
                });
            let mut asst = json!({"role": "assistant", "content": content});
            let calls: Vec<Call> = msg
                .get("tool_calls")
                .and_then(Value::as_array)
                .map(|tcs| {
                    tcs.iter()
                        .map(|tc| Call {
                            id: tc.get("id").and_then(Value::as_str).unwrap_or("").into(),
                            name: tc
                                .pointer("/function/name")
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .into(),
                            args: safe_json_args(
                                tc.pointer("/function/arguments")
                                    .and_then(Value::as_str)
                                    .unwrap_or(""),
                            ),
                        })
                        .collect()
                })
                .unwrap_or_default();
            if !calls.is_empty() {
                let raw_calls: Vec<Value> = msg["tool_calls"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(|tc| {
                        json!({
                            "id": tc.get("id").and_then(Value::as_str).unwrap_or(""),
                            "type": "function",
                            "function": {
                                "name": tc.pointer("/function/name").and_then(Value::as_str).unwrap_or(""),
                                "arguments": tc.pointer("/function/arguments").and_then(Value::as_str).unwrap_or(""),
                            }
                        })
                    })
                    .collect();
                asst["tool_calls"] = Value::Array(raw_calls);
                return Ok((Some(asst), TurnEnd::Calls(calls), data));
            }
            return Ok((Some(asst), TurnEnd::Final(content), data));
        }
        st.truncated = data.get("stop_reason").and_then(Value::as_str) == Some("max_tokens");
        let mut content = Vec::new();
        let mut text = String::new();
        let mut calls = Vec::new();
        for b in data
            .get("content")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            match b.get("type").and_then(Value::as_str) {
                Some("text") => {
                    let t = b.get("text").and_then(Value::as_str).unwrap_or("");
                    content.push(json!({"type": "text", "text": t}));
                    text.push_str(t);
                }
                Some("tool_use") => {
                    let id = b.get("id").and_then(Value::as_str).unwrap_or("");
                    let name = b.get("name").and_then(Value::as_str).unwrap_or("");
                    let input = b.get("input").cloned().unwrap_or(Value::Null);
                    st.malformed |= !matches!(input, Value::Null | Value::Object(_));
                    content
                        .push(json!({"type": "tool_use", "id": id, "name": name, "input": input}));
                    calls.push(Call {
                        id: id.into(),
                        name: name.into(),
                        args: obj_or_empty(Some(&input)),
                    });
                }
                _ => {}
            }
        }
        let asst = json!({"role": "assistant", "content": content});
        if calls.is_empty() {
            return Ok((Some(asst), TurnEnd::Final(text), data));
        }
        Ok((Some(asst), TurnEnd::Calls(calls), data))
    }

    async fn read_stream(
        &self,
        st: &mut RunState,
        resp: RawResponse,
        cancel: &CancellationToken,
        tx: Option<&Tx>,
    ) -> Result<(Option<Value>, TurnEnd, Value), ClientError> {
        let style = self.opts().style;
        let mut reader = LineReader { buf: Vec::new() };
        // openai
        let mut content = String::new();
        let mut acc: Vec<(i64, Call, String)> = Vec::new(); // (index, call, raw args)
        // anthropic
        #[derive(Default)]
        struct Block {
            typ: String,
            text: String,
            id: String,
            name: String,
            json: String,
        }
        let mut blocks: Vec<(i64, Block)> = Vec::new();
        let mut stop_reason = String::new();
        let mut finish = String::new();
        let mut done = false;

        let mut handle = |line: &str, st: &mut RunState| -> bool {
            let Some(payload) = line.strip_prefix("data:") else {
                return true;
            };
            let payload = payload.trim();
            if style == Style::OpenAI && payload == "[DONE]" {
                return false;
            }
            let Ok(j) = serde_json::from_str::<Value>(payload) else {
                return true;
            };
            if !j.is_object() {
                return true;
            }
            if style == Style::OpenAI {
                if let Some(u) = j.get("usage").filter(|u| u.is_object()) {
                    add_usage(&mut st.usage, u, style);
                }
                if let Some(fr) = j
                    .pointer("/choices/0/finish_reason")
                    .and_then(Value::as_str)
                    && !fr.is_empty()
                {
                    finish = fr.to_owned();
                }
                let Some(d) = j
                    .get("choices")
                    .and_then(Value::as_array)
                    .and_then(|c| c.first())
                    .and_then(|c| c.get("delta"))
                    .filter(|d| d.is_object())
                else {
                    return true;
                };
                if let Some(t) = d.get("content").and_then(Value::as_str)
                    && !t.is_empty()
                {
                    content.push_str(t);
                    send(tx, StreamEvent::text(t));
                }
                for tc in d
                    .get("tool_calls")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                {
                    if !tc.is_object() {
                        continue;
                    }
                    let idx = tc.get("index").and_then(Value::as_f64).unwrap_or(0.0) as i64;
                    let pos = match acc.iter().position(|(i, _, _)| *i == idx) {
                        Some(p) => p,
                        None => {
                            acc.push((
                                idx,
                                Call {
                                    id: String::new(),
                                    name: String::new(),
                                    args: Value::Null,
                                },
                                String::new(),
                            ));
                            acc.len() - 1
                        }
                    };
                    let slot = &mut acc[pos];
                    if let Some(id) = tc.get("id").and_then(Value::as_str)
                        && !id.is_empty()
                    {
                        slot.1.id = id.into();
                    }
                    if let Some(n) = tc.pointer("/function/name").and_then(Value::as_str) {
                        slot.1.name.push_str(n);
                    }
                    if let Some(a) = tc.pointer("/function/arguments").and_then(Value::as_str) {
                        slot.2.push_str(a);
                    }
                }
                return true;
            }
            match j.get("type").and_then(Value::as_str) {
                Some("message_start") => {
                    if let Some(u) = j.pointer("/message/usage").filter(|u| u.is_object()) {
                        add_usage(&mut st.usage, u, style);
                    }
                }
                Some("content_block_start") => {
                    let idx = j.get("index").and_then(Value::as_f64).unwrap_or(0.0) as i64;
                    let cb = j.get("content_block");
                    let s = |k: &str| {
                        cb.and_then(|c| c.get(k))
                            .and_then(Value::as_str)
                            .unwrap_or("")
                            .to_owned()
                    };
                    let b = Block {
                        typ: s("type"),
                        id: s("id"),
                        name: s("name"),
                        ..Default::default()
                    };
                    blocks.retain(|(i, _)| *i != idx);
                    blocks.push((idx, b));
                }
                Some("content_block_delta") => {
                    let idx = j.get("index").and_then(Value::as_f64).unwrap_or(0.0) as i64;
                    let Some((_, b)) = blocks.iter_mut().rev().find(|(i, _)| *i == idx) else {
                        return true;
                    };
                    match j.pointer("/delta/type").and_then(Value::as_str) {
                        Some("text_delta") => {
                            if let Some(t) = j.pointer("/delta/text").and_then(Value::as_str) {
                                b.text.push_str(t);
                                send(tx, StreamEvent::text(t));
                            }
                        }
                        Some("input_json_delta") => {
                            if let Some(p) =
                                j.pointer("/delta/partial_json").and_then(Value::as_str)
                            {
                                b.json.push_str(p);
                            }
                        }
                        _ => {}
                    }
                }
                Some("message_delta") => {
                    if let Some(sr) = j.pointer("/delta/stop_reason").and_then(Value::as_str)
                        && !sr.is_empty()
                    {
                        stop_reason = sr.into();
                    }
                    if let Some(u) = j.get("usage").filter(|u| u.is_object()) {
                        add_usage(&mut st.usage, u, style);
                    }
                }
                _ => {}
            }
            true
        };

        match resp.body {
            RespBody::Full(b) => {
                for line in reader.push(&b).into_iter().chain(reader.finish()) {
                    if !handle(&line, st) {
                        break;
                    }
                }
            }
            RespBody::Http(mut r) => loop {
                let chunk = tokio::select! {
                    _ = cancel.cancelled() => return Err(ClientError::Cancelled),
                    c = r.chunk() => c.map_err(|e| ClientError::Transport(e.to_string()))?,
                };
                let lines = match chunk {
                    Some(c) => reader.push(&c),
                    None => {
                        done = true;
                        reader.finish().into_iter().collect()
                    }
                };
                let mut stop = false;
                for line in lines {
                    if !handle(&line, st) {
                        stop = true;
                        break;
                    }
                }
                if stop || done {
                    break;
                }
            },
            RespBody::Chunks(mut rx) => loop {
                let chunk = tokio::select! {
                    _ = cancel.cancelled() => return Err(ClientError::Cancelled),
                    c = rx.recv() => c,
                };
                let lines = match chunk {
                    Some(Ok(c)) => reader.push(&c),
                    Some(Err(e)) => return Err(ClientError::Transport(e)),
                    None => {
                        done = true;
                        reader.finish().into_iter().collect()
                    }
                };
                let mut stop = false;
                for line in lines {
                    if !handle(&line, st) {
                        stop = true;
                        break;
                    }
                }
                if stop || done {
                    break;
                }
            },
        }
        if cancel.is_cancelled() {
            return Err(ClientError::Cancelled);
        }
        let streamed = json!({"streamed": true});
        if style == Style::OpenAI {
            st.truncated = finish == "length";
            st.malformed = acc.iter().any(|(_, _, a)| malformed_args(a));
            if acc.is_empty() {
                let asst = json!({"role": "assistant", "content": content});
                return Ok((Some(asst), TurnEnd::Final(content), streamed));
            }
            let raw_calls: Vec<Value> = acc
                .iter()
                .map(|(_, c, a)| json!({"id": c.id, "type": "function", "function": {"name": c.name, "arguments": a}}))
                .collect();
            let asst = json!({
                "role": "assistant",
                "tool_calls": raw_calls,
                "content": if content.is_empty() { Value::Null } else { json!(content) },
            });
            let calls = acc
                .into_iter()
                .map(|(_, mut c, a)| {
                    c.args = safe_json_args(&a);
                    c
                })
                .collect();
            return Ok((Some(asst), TurnEnd::Calls(calls), streamed));
        }
        st.truncated = stop_reason == "max_tokens";
        let mut content_blocks = Vec::new();
        let mut text = String::new();
        let mut calls = Vec::new();
        for (_, b) in blocks {
            if b.typ == "tool_use" {
                st.malformed |= malformed_args(&b.json);
                let input = safe_json_args(&b.json);
                content_blocks
                    .push(json!({"type": "tool_use", "id": b.id, "name": b.name, "input": input}));
                calls.push(Call {
                    id: b.id,
                    name: b.name,
                    args: input,
                });
            } else {
                content_blocks.push(json!({"type": "text", "text": b.text}));
                text.push_str(&b.text);
            }
        }
        let asst = json!({"role": "assistant", "content": content_blocks});
        if stop_reason != "tool_use" || calls.is_empty() {
            return Ok((Some(asst), TurnEnd::Final(text), streamed));
        }
        Ok((Some(asst), TurnEnd::Calls(calls), streamed))
    }

    // -- tools ------------------------------------------------------------------

    async fn run_tool(
        &self,
        tk: &Toolkit,
        call: &Call,
        turn: usize,
        cancel: &CancellationToken,
    ) -> Result<(Value, ToolResult), ClientError> {
        if self.opts().tool_tiers.is_some() && call.name == "tool_search" {
            let start = Instant::now();
            let r = tiers::tool_search(tk, &call.args);
            self.emit_tool(&call.name, "builtin", &r, start);
            return Ok((call.args.clone(), r));
        }
        let (real, inner, wrapped) = self.unwrap_call(&call.name, &call.args);
        let (a, r) = self
            .run_tool_direct(tk, &real, inner, &call.id, turn, cancel)
            .await?;
        if wrapped {
            return Ok((json!({"name": real, "arguments": a}), r));
        }
        Ok((a, r))
    }

    fn source_of(tk: &Toolkit, name: &str) -> (String, Option<ToolHints>) {
        match tk.get(name) {
            Some(t) => (
                serde_json::to_value(t.source)
                    .ok()
                    .and_then(|v| v.as_str().map(String::from))
                    .unwrap_or_else(|| "custom".into()),
                t.hints,
            ),
            None => ("custom".into(), None),
        }
    }

    async fn run_tool_direct(
        &self,
        tk: &Toolkit,
        name: &str,
        args: Value,
        id: &str,
        turn: usize,
        cancel: &CancellationToken,
    ) -> Result<(Value, ToolResult), ClientError> {
        let hooks = &self.opts().hooks;
        let (source, hints) = Self::source_of(tk, name);
        let start = Instant::now();
        let mut a = args;
        if let Some(h) = &hooks.before_tool {
            let ov = h(BeforeToolEvent {
                name: name.into(),
                args: a.clone(),
                id: id.into(),
                turn,
                hints,
            })
            .await
            .map_err(ClientError::Hook)?;
            if let Some(ov) = ov {
                if let Some(r) = ov.result {
                    self.emit_tool(name, &source, &r, start);
                    return Ok((a, r));
                }
                if let Some(na) = ov.args {
                    a = na;
                }
            }
        }
        let ctx = ToolContext {
            cancel: cancel.clone(),
            call_id: Some(id.into()),
            ..Default::default()
        };
        let mut result = tk.execute(name, a.clone(), ctx).await;
        if let Some(h) = &hooks.after_tool
            && pending_of(&result).is_none()
        {
            let ov = h(AfterToolEvent {
                name: name.into(),
                args: a.clone(),
                result: result.clone(),
                id: id.into(),
                turn,
            })
            .await
            .map_err(ClientError::Hook)?;
            if let Some(r) = ov.and_then(|o| o.result) {
                result = r;
            }
        }
        self.emit_tool(name, &source, &result, start);
        Ok((a, result))
    }

    /// §10: resolve a suspension through `wait_for`, re-execute once with the
    /// answer, or (no `wait_for`) halt the run.
    #[allow(clippy::too_many_arguments)]
    async fn resolve_pending(
        &self,
        tk: &Toolkit,
        name: &str,
        args: &Value,
        request: Request,
        id: &str,
        turn: usize,
        cancel: &CancellationToken,
    ) -> Result<(ToolResult, Option<Request>), ClientError> {
        let (name, args, _) = self.unwrap_call(name, args);
        let o = self.opts();
        let answer = if let Some(w) = &o.wait_for_ctx {
            w(cancel.clone(), request.clone()).await
        } else if let Some(w) = &o.wait_for {
            w(request.clone()).await
        } else {
            return Ok((ToolResult::error(request.prompt.clone()), Some(request)));
        };
        let answer = answer.map_err(ClientError::WaitFor)?;
        if !answer.ok {
            return Ok((
                ToolResult::error(format!("declined/expired: {}", request.prompt)),
                None,
            ));
        }
        let start = Instant::now();
        let ctx = ToolContext {
            cancel: cancel.clone(),
            call_id: Some(id.into()),
            answer: Some(answer),
            ..Default::default()
        };
        let mut result = tk.execute(&name, args.clone(), ctx).await;
        if let Some(h) = &o.hooks.after_tool {
            let ov = h(AfterToolEvent {
                name: name.clone(),
                args: args.clone(),
                result: result.clone(),
                id: id.into(),
                turn,
            })
            .await
            .map_err(ClientError::Hook)?;
            if let Some(r) = ov.and_then(|o| o.result) {
                result = r;
            }
        }
        let (source, _) = Self::source_of(tk, &name);
        self.emit_tool(&name, &source, &result, start);
        if pending_of(&result).is_some() {
            result = ToolResult::error(format!("unresolved: {}", request.prompt));
        }
        Ok((result, None))
    }

    fn call_event(&self, c: &Call) -> StreamEvent {
        let (real, inner, wrapped) = self.unwrap_call(&c.name, &c.args);
        StreamEvent {
            kind: "tool_call".into(),
            id: c.id.clone(),
            name: real,
            args: inner,
            via: if wrapped {
                "call_tool".into()
            } else {
                String::new()
            },
            ..Default::default()
        }
    }

    fn result_event(&self, c: &Call, r: &ToolResult) -> StreamEvent {
        let (real, _, wrapped) = self.unwrap_call(&c.name, &c.args);
        StreamEvent {
            kind: "tool_result".into(),
            id: c.id.clone(),
            name: real,
            output: r.output.clone(),
            is_error: r.is_error,
            metadata: r.metadata.clone(),
            via: if wrapped {
                "call_tool".into()
            } else {
                String::new()
            },
            ..Default::default()
        }
    }

    // -- the loop ----------------------------------------------------------------

    fn result(&self, st: &RunState, text: String) -> RunResult {
        RunResult {
            text,
            messages: st.messages.clone(),
            tool_calls: st.tool_calls.clone(),
            files: st.files.clone(),
            tool_call_count: st.tool_calls.len(),
            turns: st.turns,
            usage: st.usage,
            model: st.last_model.clone(),
            status: "done".into(),
            ..Default::default()
        }
    }

    async fn run_loop(
        &self,
        st: &mut RunState,
        prompt: &Prompt,
        tk: &Toolkit,
        history: Vec<Value>,
        cancel: &CancellationToken,
        tx: Option<&Tx>,
    ) -> Result<RunResult, ClientError> {
        let key = self.resolve_key()?;
        let style = self.opts().style;
        let system = self.system(tk);
        if !history.is_empty() {
            st.messages = history;
        } else if style == Style::OpenAI && !system.is_empty() {
            st.messages
                .push(json!({"role": "system", "content": system}));
        }
        // A resume continues from the transcript, with no new user turn.
        if !matches!(prompt, Prompt::Text(t) if t == crate::relay::RESUME_NO_PROMPT) {
            st.messages.push(user_message(prompt));
        }
        st.started = true;
        let req_tools = self.request_tools(tk);
        let mut tools = match style {
            Style::OpenAI => to_openai(&req_tools),
            Style::Anthropic => to_anthropic(&req_tools),
        };
        for turn in 0..self.max_turns() {
            st.turns += 1;
            let mut turn_model = self.opts().model.clone();
            if let Some(h) = &self.opts().hooks.before_llm {
                let ov = h(BeforeLlmEvent {
                    messages: st.messages.clone(),
                    tools: tools.clone(),
                    model: self.opts().model.clone(),
                    turn,
                })
                .await
                .map_err(ClientError::Hook)?;
                if let Some(ov) = ov {
                    if let Some(m) = ov.messages {
                        st.messages = m;
                    }
                    if let Some(t) = ov.tools {
                        tools = t;
                    }
                    if let Some(m) = ov.model.filter(|m| !m.is_empty()) {
                        turn_model = m;
                    }
                }
            }
            st.truncated = false;
            st.malformed = false;
            let (asst, end) = self
                .call_model(st, &key, &turn_model, &system, &tools, turn, cancel, tx)
                .await?;
            let had_asst = asst.is_some();
            if let Some(a) = asst {
                st.messages.push(a);
            }
            if self.opts().stop_recovery && turn + 1 < self.max_turns() {
                let (has_calls, text) = match &end {
                    TurnEnd::Calls(_) => (true, ""),
                    TurnEnd::Final(t) => (false, t.as_str()),
                    TurnEnd::Empty => (false, ""),
                };
                let (malformed, truncated) = (st.malformed, st.truncated);
                if let Some((msg, drop)) = st.rec.decide(has_calls, malformed, truncated, text) {
                    if drop && had_asst {
                        st.messages.pop();
                    }
                    st.messages.push(json!({"role": "user", "content": msg}));
                    continue;
                }
            }
            let calls = match end {
                TurnEnd::Empty => {
                    let text = std::mem::take(&mut st.rec.carry);
                    return Ok(self.end_run(st, text, tx));
                }
                TurnEnd::Final(text) => {
                    let text = std::mem::take(&mut st.rec.carry) + &text;
                    return Ok(self.end_run(st, text, tx));
                }
                TurnEnd::Calls(c) => c,
            };
            st.rec.carry.clear();
            for c in &calls {
                send(tx, self.call_event(c));
            }
            let futs = calls.iter().map(|c| async move {
                let (args, mut result) = self.run_tool(tk, c, turn, cancel).await?;
                let mut halted = None;
                if let Some(mut req) = pending_of(&result) {
                    // the tool cannot know its own call id; the loop does
                    crate::relay::stamp_relay_call_id(&mut req, &c.id);
                    send(
                        tx,
                        StreamEvent {
                            request: Some(req.clone()),
                            ..StreamEvent::of("pending")
                        },
                    );
                    let (r, h) = self
                        .resolve_pending(tk, &c.name, &args, req, &c.id, turn, cancel)
                        .await?;
                    result = r;
                    halted = h;
                }
                if cancel.is_cancelled() {
                    return Err(ClientError::Cancelled);
                }
                Ok::<_, ClientError>((args, result, halted))
            });
            let safe: Vec<bool> = calls.iter().map(|c| self.call_safe(tk, c)).collect();
            let cap = crate::tool::resolve_max_tool_concurrency(self.opts().max_tool_concurrency);
            let futs: Vec<_> = futs.collect();
            let outcomes = crate::tool::schedule_calls(futs, &safe, cap).await;
            let entry = |c: &Call, r: &ToolResult| match style {
                Style::OpenAI => emit::with_tool_parts(
                    json!({"role": "tool", "tool_call_id": c.id, "content": r.output}),
                    &c.name,
                    &r.parts,
                ),
                Style::Anthropic => emit::with_tool_parts(
                    json!({"type": "tool_result", "tool_use_id": c.id, "content": r.output, "is_error": r.is_error}),
                    &c.name,
                    &r.parts,
                ),
            };
            // A tool-tiers `call_tool` call is recorded as the REAL tool with
            // its inner args, as hooks and stream events see it (§4C); the
            // transcript keeps the wrapper.
            let record = |c: &Call, args: &Value, r: &ToolResult| {
                let (name, args, _) = self.unwrap_call(&c.name, args);
                ToolCall {
                    name,
                    args,
                    output: r.output.clone(),
                    is_error: r.is_error,
                    metadata: r.metadata.clone(),
                    parts: r.parts.clone(),
                }
            };
            let push = |st: &mut RunState, tc: ToolCall, id: &str| {
                st.files.extend(run_files_of(&tc, id));
                st.tool_calls.push(tc);
            };
            if let Some(err) = outcomes.iter().find_map(|o| o.as_ref().err()).cloned() {
                // keep the finished pairs; the rest are sealed as interrupted
                let mut blocks = Vec::new();
                for (c, o) in calls.iter().zip(&outcomes) {
                    if let Ok((args, r, _)) = o {
                        match style {
                            Style::OpenAI => {
                                push(st, record(c, args, r), &c.id);
                                st.messages.push(entry(c, r));
                            }
                            Style::Anthropic => blocks.push(entry(c, r)),
                        }
                    }
                }
                if !blocks.is_empty() {
                    st.messages.push(json!({"role": "user", "content": blocks}));
                }
                return Err(err);
            }
            let mut blocks = Vec::new();
            let mut halt: Option<Request> = None;
            let mut done = Vec::with_capacity(outcomes.len());
            let mut halts = Vec::with_capacity(outcomes.len());
            for o in outcomes {
                let (args, r, h) = o?;
                done.push((args, r));
                halts.push(h);
            }
            // Fold every relay suspension of this turn into the first (§10,
            // ADR-0010 F2-a); the first-in-order halt rule below is untouched.
            crate::relay::merge_relay_calls(&mut halts);
            for ((c, (args, r)), h) in calls.iter().zip(done).zip(halts) {
                push(st, record(c, &args, &r), &c.id);
                match style {
                    Style::OpenAI => st.messages.push(entry(c, &r)),
                    Style::Anthropic => blocks.push(entry(c, &r)),
                }
                if h.is_some() {
                    halt = h;
                    break;
                }
                send(tx, self.result_event(c, &r));
            }
            if style == Style::Anthropic {
                st.messages.push(json!({"role": "user", "content": blocks}));
            }
            if let Some(req) = halt {
                return Ok(self.pending_run(st, req, tx));
            }
        }
        let text = if style == Style::OpenAI {
            last_text(&st.messages)
        } else {
            String::new()
        };
        let mut res = self.end_run(st, text.clone(), None);
        if text.is_empty() {
            res.status = "incomplete".into();
            res.limit = "maxTurns".into();
        }
        if tx.is_some() {
            send(
                tx,
                StreamEvent {
                    usage: Some(res.usage),
                    result: Some(Box::new(res.clone())),
                    ..StreamEvent::of("done")
                },
            );
        }
        Ok(res)
    }

    fn end_run(&self, st: &RunState, text: String, tx: Option<&Tx>) -> RunResult {
        if let Some(tx) = tx {
            let _ = tx.send(StreamEvent {
                usage: Some(st.usage),
                ..StreamEvent::of("usage")
            });
        }
        self.record_metric(MetricEvent {
            event: "run".into(),
            model: st.last_model.clone(),
            turns: st.turns as i64,
            tool_calls: st.tool_calls.len() as i64,
            total_tokens: st.usage.total_tokens,
            ..Default::default()
        });
        let res = self.result(st, text);
        send(
            tx,
            StreamEvent {
                usage: Some(res.usage),
                result: Some(Box::new(res.clone())),
                ..StreamEvent::of("done")
            },
        );
        res
    }

    fn pending_run(&self, st: &RunState, req: Request, tx: Option<&Tx>) -> RunResult {
        self.record_metric(MetricEvent {
            event: "run".into(),
            model: st.last_model.clone(),
            turns: st.turns as i64,
            tool_calls: st.tool_calls.len() as i64,
            total_tokens: st.usage.total_tokens,
            ..Default::default()
        });
        let mut res = self.result(st, req.prompt.clone());
        res.status = "pending".into();
        res.pending = Some(req);
        send(
            tx,
            StreamEvent {
                usage: Some(res.usage),
                result: Some(Box::new(res.clone())),
                ..StreamEvent::of("done")
            },
        );
        res
    }

    /// Drive one run: deadline, cancellation, run metric on error, partial
    /// transcript sealing. Returns the outcome and the (possibly partial) transcript.
    async fn drive(
        &self,
        prompt: &Prompt,
        tk: &Toolkit,
        history: Vec<Value>,
        cancel: CancellationToken,
        tx: Option<&Tx>,
    ) -> (Result<RunResult, ClientError>, Option<Vec<Value>>) {
        let run_cancel = cancel.child_token();
        let start = Instant::now();
        let mut st = RunState {
            last_model: self.opts().model.clone(),
            ..Default::default()
        };
        let timeout_ms = self.opts().timeout_ms;
        let r = {
            let fut = self.run_loop(&mut st, prompt, tk, history, &run_cancel, tx);
            if timeout_ms > 0 {
                match tokio::time::timeout(Duration::from_millis(timeout_ms), fut).await {
                    Ok(r) => r,
                    Err(_) => {
                        run_cancel.cancel();
                        Err(ClientError::RunTimeout {
                            timeout_ms,
                            partial: Box::default(),
                        })
                    }
                }
            } else {
                fut.await
            }
        };
        match r {
            Ok(res) => (Ok(res), None),
            Err(e) => {
                let e = if timeout_ms > 0
                    && matches!(e, ClientError::Cancelled)
                    && start.elapsed() >= Duration::from_millis(timeout_ms)
                {
                    ClientError::RunTimeout {
                        timeout_ms,
                        partial: Box::default(),
                    }
                } else {
                    e
                };
                self.emit_run(&st, start, e.to_string());
                let partial = if st.started {
                    st.messages = seal_interrupted(std::mem::take(&mut st.messages));
                    Some(st.messages.clone())
                } else {
                    None
                };
                let e = match e {
                    ClientError::RunTimeout { timeout_ms, .. } => {
                        let mut res = self.result(&st, String::new());
                        res.status = "incomplete".into();
                        res.limit = "timeout".into();
                        ClientError::RunTimeout {
                            timeout_ms,
                            partial: Box::new(res),
                        }
                    }
                    e => e,
                };
                (Err(e), partial)
            }
        }
    }

    // -- public surface -------------------------------------------------------------

    /// One run from a fresh transcript.
    pub async fn run(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
    ) -> Result<RunResult, ClientError> {
        self.run_ctx(CancellationToken::new(), prompt, tk, Vec::new())
            .await
    }

    /// Continue from a prior transcript (`history` replaces the system message).
    pub async fn run_with_history(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        history: Vec<Value>,
    ) -> Result<RunResult, ClientError> {
        self.run_ctx(CancellationToken::new(), prompt, tk, history)
            .await
    }

    /// The general form: cancellable, with optional history. Multimodal prompts
    /// are validated against `max_part_bytes` first.
    pub async fn run_ctx(
        &self,
        cancel: CancellationToken,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        history: Vec<Value>,
    ) -> Result<RunResult, ClientError> {
        let prompt = prompt.into();
        if let Prompt::Parts(parts) = &prompt {
            for p in parts {
                validate_part(p, self.opts().max_part_bytes).map_err(ClientError::Part)?;
            }
        }
        let (this, tk) = (self.clone(), tk.clone());
        on_worker(async move { this.drive(&prompt, &tk, history, cancel, None).await.0 }).await
    }

    /// Resume a run that halted with status `pending` (§10 durable path,
    /// ADR-0010): `history` is the transcript the host persisted, `pending` the
    /// Request it received, `answer` the results it executed itself. Every tool
    /// call of the halted turn gets a tool result (the placeholder is
    /// replaced), and the loop continues with no new user turn. The answer must
    /// echo the request id.
    pub async fn run_with_answer(
        &self,
        cancel: CancellationToken,
        tk: &Toolkit,
        history: Vec<Value>,
        pending: &Request,
        answer: &Answer,
    ) -> Result<RunResult, ClientError> {
        if answer.id != pending.id {
            return Err(ClientError::Resume(format!(
                "toolnexus: cannot resume — answer id {:?} does not echo the pending request id {:?}",
                answer.id, pending.id
            )));
        }
        if history.is_empty() {
            return Err(ClientError::Resume(
                "toolnexus: cannot resume — no transcript supplied".into(),
            ));
        }
        let repaired = crate::relay::repair_halted_turn(&history, pending, answer)
            .map_err(ClientError::Resume)?;
        self.run_ctx(cancel, crate::relay::RESUME_NO_PROMPT, tk, repaired)
            .await
    }

    /// [`Client::run_with_answer`] against a stored conversation: load `id`,
    /// resume it, save the updated transcript back.
    pub async fn ask_with_answer(
        &self,
        cancel: CancellationToken,
        tk: &Toolkit,
        id: &str,
        pending: &Request,
        answer: &Answer,
    ) -> Result<RunResult, ClientError> {
        if id.is_empty() {
            return Err(ClientError::Resume(
                "toolnexus: AskWithAnswer needs a conversation id".into(),
            ));
        }
        let history = self
            .inner
            .store
            .get(id)
            .map_err(ClientError::Store)?
            .unwrap_or_default();
        let res = self
            .run_with_answer(cancel, tk, history, pending, answer)
            .await?;
        self.inner
            .store
            .save(id, &res.messages)
            .map_err(ClientError::Store)?;
        Ok(res)
    }

    /// A run remembered under `id` in the conversation store (empty id ⇒ `run`).
    /// An interrupted turn is still saved (sealed); the error wins.
    pub async fn ask(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        id: &str,
    ) -> Result<RunResult, ClientError> {
        self.ask_ctx(CancellationToken::new(), prompt, tk, id).await
    }

    pub async fn ask_ctx(
        &self,
        cancel: CancellationToken,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        id: &str,
    ) -> Result<RunResult, ClientError> {
        let prompt = prompt.into();
        if id.is_empty() {
            return self.run_ctx(cancel, prompt, tk, Vec::new()).await;
        }
        let history = self
            .inner
            .store
            .get(id)
            .map_err(ClientError::Store)?
            .unwrap_or_default();
        let (r, partial) = self.drive(&prompt, tk, history.clone(), cancel, None).await;
        match r {
            Ok(res) => {
                self.inner
                    .store
                    .save(id, &res.messages)
                    .map_err(ClientError::Store)?;
                Ok(res)
            }
            Err(e) => {
                self.save_partial(id, history, &prompt, tk, partial);
                Err(e)
            }
        }
    }

    /// `ask` with streamed text: `on_text` gets every delta.
    pub async fn ask_stream(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        id: &str,
        mut on_text: impl FnMut(&str) + Send,
    ) -> Result<RunResult, ClientError> {
        let mut rx = self.stream_with_id(prompt, tk, id)?;
        let mut result = None;
        let mut err = None;
        while let Some(ev) = rx.recv().await {
            match ev.kind.as_str() {
                "text" => on_text(&ev.delta),
                "done" => result = ev.result.map(|r| *r),
                "error" => err = ev.error,
                _ => {}
            }
        }
        if let Some(e) = err {
            return Err(e);
        }
        Ok(result.unwrap_or_default())
    }

    fn save_partial(
        &self,
        id: &str,
        history: Vec<Value>,
        prompt: &Prompt,
        tk: &Toolkit,
        partial: Option<Vec<Value>>,
    ) {
        let msgs = match partial {
            Some(m) => m,
            None => {
                let empty = history.is_empty();
                let mut m = history;
                if empty && self.opts().style != Style::Anthropic {
                    let sys = self.system(tk);
                    if !sys.is_empty() {
                        m.push(json!({"role": "system", "content": sys}));
                    }
                }
                m.push(user_message(prompt));
                m
            }
        };
        let _ = self.inner.store.save(id, &msgs);
    }

    /// Stream a fresh run: `text`, `tool_call`, `tool_result`, `pending`, `usage`,
    /// then `done` (or `error`). The channel closes after the last event.
    pub fn stream(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
    ) -> Result<mpsc::UnboundedReceiver<StreamEvent>, ClientError> {
        self.stream_ctx(CancellationToken::new(), prompt, tk, "")
    }

    /// Stream a run remembered under `id` (empty ⇒ not remembered).
    pub fn stream_with_id(
        &self,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        id: &str,
    ) -> Result<mpsc::UnboundedReceiver<StreamEvent>, ClientError> {
        self.stream_ctx(CancellationToken::new(), prompt, tk, id)
    }

    pub fn stream_ctx(
        &self,
        cancel: CancellationToken,
        prompt: impl Into<Prompt>,
        tk: &Toolkit,
        id: &str,
    ) -> Result<mpsc::UnboundedReceiver<StreamEvent>, ClientError> {
        let prompt = prompt.into();
        let history = if id.is_empty() {
            Vec::new()
        } else {
            self.inner
                .store
                .get(id)
                .map_err(ClientError::Store)?
                .unwrap_or_default()
        };
        let (tx, rx) = mpsc::unbounded_channel();
        let (this, tk, id) = (self.clone(), tk.clone(), id.to_owned());
        tokio::spawn(async move {
            let (r, partial) = this
                .drive(&prompt, &tk, history.clone(), cancel, Some(&tx))
                .await;
            match r {
                Ok(res) => {
                    if !id.is_empty()
                        && let Err(e) = this.inner.store.save(&id, &res.messages)
                    {
                        let _ = tx.send(StreamEvent {
                            error: Some(ClientError::Store(e)),
                            ..StreamEvent::of("error")
                        });
                    }
                }
                Err(e) => {
                    if !id.is_empty() {
                        this.save_partial(&id, history, &prompt, &tk, partial);
                    }
                    let _ = tx.send(StreamEvent {
                        error: Some(e),
                        ..StreamEvent::of("error")
                    });
                }
            }
        });
        Ok(rx)
    }

    /// A stateful conversation over this client (history kept in memory).
    pub fn conversation(&self, tk: &Toolkit) -> Conversation {
        Conversation {
            client: self.clone(),
            tk: tk.clone(),
            messages: Vec::new(),
        }
    }
}

/// An in-memory multi-turn conversation.
#[derive(Debug, Clone)]
pub struct Conversation {
    client: Client,
    tk: Toolkit,
    pub messages: Vec<Value>,
}

impl Conversation {
    pub async fn send(&mut self, prompt: impl Into<Prompt>) -> Result<RunResult, ClientError> {
        let res = self
            .client
            .run_with_history(prompt, &self.tk, self.messages.clone())
            .await?;
        self.messages = res.messages.clone();
        Ok(res)
    }
    pub fn reset(&mut self) {
        self.messages.clear();
    }
}

/// The provider env var for an https base URL's host (`""` for any other host).
pub fn key_env_for_base_url(base_url: &str) -> &'static str {
    let h = host_of(base_url);
    if h == "api.openai.com" {
        "OPENAI_API_KEY"
    } else if h == "api.anthropic.com" {
        "ANTHROPIC_API_KEY"
    } else if h == "openrouter.ai" || h.ends_with(".openrouter.ai") {
        "OPENROUTER_API_KEY"
    } else {
        ""
    }
}

fn host_of(base_url: &str) -> String {
    let Some(rest) = base_url.strip_prefix("https://").or_else(|| {
        // scheme is case-insensitive
        if base_url.len() > 8 && base_url[..8].eq_ignore_ascii_case("https://") {
            Some(&base_url[8..])
        } else {
            None
        }
    }) else {
        return String::new();
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let hostport = authority.rsplit('@').next().unwrap_or("");
    let host = if let Some(stripped) = hostport.strip_prefix('[') {
        stripped.split(']').next().unwrap_or("")
    } else {
        hostport.split(':').next().unwrap_or("")
    };
    host.to_ascii_lowercase()
}
