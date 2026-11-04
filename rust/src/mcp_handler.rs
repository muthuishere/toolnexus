//! MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server with generic
//! per-request hooks (SPEC §7C.1, ADR 0039). Port of `golang/mcphandler.go`.
//! Behind the `serve` feature.
//!
//! [`Toolkit::serve`] owns a listener and exposes one static tool list.
//! [`Toolkit::mcp_handler`] returns an [`McpHandler`] a host mounts on its own
//! router (hyper directly — it implements `hyper::service::Service` — or any
//! framework through [`McpHandler::handle`]), behind its own TLS, CORS and
//! OAuth. Every policy is a hook; toolnexus has no notion of tenant, user, role
//! or storage.
//!
//! The transport is JSON-RPC over HTTP written by hand (no rmcp server types in
//! the public API): tools resolve per request, and a confirm elicitation rides
//! the SAME POST's SSE response (protocol 2025-06-18).

use std::any::Any;
use std::collections::HashMap;
use std::convert::Infallible;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use http_body_util::{BodyExt, Full, Limited, StreamBody, combinators::BoxBody};
use hyper::body::{Body, Bytes, Frame};
use hyper::header::{HeaderMap, HeaderValue};
use hyper::{Method, Request, Response, StatusCode, Uri};
use serde_json::{Map, Value, json};
use tokio::sync::{mpsc, oneshot};

use crate::tool::{ContentPart, Tool, ToolContext, ToolResult, ToolSource};
use crate::toolkit::Toolkit;

/// The MCP protocol versions whose wire [`McpHandler`] implements (ADR 0039).
/// Configured versions outside this list are dropped.
pub const MCP_HANDLER_IMPLEMENTED_VERSIONS: &[&str] = &["2025-11-25", "2025-06-18"];
const DEFAULT_VERSIONS: &[&str] = &["2025-06-18"];
const DEFAULT_NAME: &str = "toolnexus";
const DEFAULT_VERSION: &str = "0.1.0";

/// A boxed, sendable future returned by the async hooks.
pub type BoxFuture<T> = Pin<Box<dyn Future<Output = T> + Send>>;
/// What `authenticate` produces; reachable everywhere via
/// [`McpRequestContext::identity`].
pub type McpIdentity = Arc<dyn Any + Send + Sync>;

/// The outcome of a `before_call` gate.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum McpDecisionKind {
    #[default]
    Allow,
    Deny,
    Confirm,
}

impl McpDecisionKind {
    /// The wire / event name: `allow` | `deny` | `confirm`.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Allow => "allow",
            Self::Deny => "deny",
            Self::Confirm => "confirm",
        }
    }
}

/// What `before_call` returns. The default allows.
///
/// - Allow: the tool runs.
/// - Deny: the tool does not run; the caller gets `result` when set, else an
///   isError result carrying `message` (`"denied"` when empty).
/// - Confirm: the client is asked (`elicitation/create`, `message` as prompt)
///   when it declared elicitation; accept ⇒ the tool runs, else isError
///   `"declined"`. When it cannot elicit: `result` when set, else isError
///   `"confirmation required: <message>"`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpDecision {
    pub kind: McpDecisionKind,
    pub message: String,
    pub result: Option<ToolResult>,
}

impl McpDecision {
    pub fn allow() -> Self {
        Self::default()
    }
    pub fn deny(message: impl Into<String>) -> Self {
        Self {
            kind: McpDecisionKind::Deny,
            message: message.into(),
            result: None,
        }
    }
    pub fn confirm(message: impl Into<String>) -> Self {
        Self {
            kind: McpDecisionKind::Confirm,
            message: message.into(),
            result: None,
        }
    }
    /// This decision with a host-shaped result (deny / confirm fallback).
    pub fn with_result(mut self, result: ToolResult) -> Self {
        self.result = Some(result);
        self
    }
}

/// Advertised at `initialize`. Empty `title` / `instructions` are omitted.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpServerInfo {
    pub name: String,
    pub title: String,
    pub version: String,
    pub instructions: String,
}

/// One `resources/list` entry.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpResource {
    pub uri: String,
    pub name: String,
    pub title: String,
    pub description: String,
    pub mime_type: String,
}

/// One `resources/read` entry: set `text`, or `blob` (base64).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpResourceContent {
    pub uri: String,
    pub mime_type: String,
    pub text: String,
    pub blob: String,
}

/// One prompt argument.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpPromptArgument {
    pub name: String,
    pub description: String,
    pub required: bool,
}

/// One `prompts/list` entry.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpPrompt {
    pub name: String,
    pub title: String,
    pub description: String,
    pub arguments: Vec<McpPromptArgument>,
}

/// One text message of a `prompts/get` result. `role`: `user` | `assistant`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpPromptMessage {
    pub role: String,
    pub text: String,
}

/// The `prompts/get` result.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpPromptResult {
    pub description: String,
    pub messages: Vec<McpPromptMessage>,
}

/// The request being served: HTTP parts, the authenticated identity, the
/// session id and the JSON-RPC `params._meta`. Every hook receives it; a tool
/// reads it with [`mcp_request_context`].
#[derive(Clone)]
pub struct McpRequestContext {
    pub method: Method,
    pub uri: Uri,
    pub headers: HeaderMap,
    identity: Option<McpIdentity>,
    session_id: String,
    meta: Option<Map<String, Value>>,
}

impl std::fmt::Debug for McpRequestContext {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpRequestContext")
            .field("method", &self.method)
            .field("uri", &self.uri)
            .field("session_id", &self.session_id)
            .field("meta", &self.meta)
            .finish_non_exhaustive()
    }
}

impl McpRequestContext {
    /// What `authenticate` produced (`None` without `authenticate`).
    pub fn identity(&self) -> Option<&McpIdentity> {
        self.identity.as_ref()
    }
    /// The identity downcast to `T`.
    pub fn identity_as<T: 'static>(&self) -> Option<&T> {
        self.identity.as_ref()?.downcast_ref::<T>()
    }
    /// The `Mcp-Session-Id` ("" when stateless or none).
    pub fn session_id(&self) -> &str {
        &self.session_id
    }
    /// The JSON-RPC request's `params._meta` (`None` when absent).
    pub fn meta(&self) -> Option<&Map<String, Value>> {
        self.meta.as_ref()
    }
    /// A request header as a string ("" when absent or not UTF-8).
    pub fn header(&self, name: &str) -> &str {
        self.headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
    }
    /// The `Host` header (else the URI authority).
    pub fn host(&self) -> String {
        let h = self.header("host");
        if !h.is_empty() {
            return h.to_owned();
        }
        self.uri
            .authority()
            .map(|a| a.to_string())
            .unwrap_or_default()
    }
}

/// The [`McpRequestContext`] of the served call a tool is running in.
pub fn mcp_request_context(ctx: &ToolContext) -> Option<&McpRequestContext> {
    ctx.request.as_ref()?.downcast_ref::<McpRequestContext>()
}

/// Issues and checks `Mcp-Session-Id` values. The default is an in-memory set;
/// a host plugs its own (signed ids, a DB) so any replica validates any session.
pub trait McpSessionIds: Send + Sync {
    fn generate(&self, rc: Arc<McpRequestContext>) -> BoxFuture<Result<String, String>>;
    /// Whether `id` is live (false ⇒ HTTP 404).
    fn validate(&self, rc: Arc<McpRequestContext>, id: String) -> BoxFuture<Result<bool, String>>;
    fn terminate(&self, rc: Arc<McpRequestContext>, id: String) -> BoxFuture<Result<(), String>>;
}

/// Surfaced once per inbound `tools/call`.
#[derive(Clone)]
pub struct McpCallEvent {
    pub name: String,
    pub source: ToolSource,
    pub ms: u64,
    pub is_error: bool,
    /// The execution failure, when the tool failed (or panicked).
    pub err: Option<String>,
    /// Size of the JSON CallToolResult sent back.
    pub result_bytes: usize,
    pub decision: McpDecisionKind,
    pub identity: Option<McpIdentity>,
    pub session_id: String,
}

impl McpCallEvent {
    /// The identity downcast to `T`.
    pub fn identity_as<T: 'static>(&self) -> Option<&T> {
        self.identity.as_ref()?.downcast_ref::<T>()
    }
}

impl std::fmt::Debug for McpCallEvent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpCallEvent")
            .field("name", &self.name)
            .field("source", &self.source)
            .field("ms", &self.ms)
            .field("is_error", &self.is_error)
            .field("err", &self.err)
            .field("result_bytes", &self.result_bytes)
            .field("decision", &self.decision)
            .field("session_id", &self.session_id)
            .finish_non_exhaustive()
    }
}

type Ctx = Arc<McpRequestContext>;

/// `authenticate`: an error ⇒ HTTP 401.
pub type AuthenticateFn = Arc<dyn Fn(Ctx) -> BoxFuture<Result<McpIdentity, String>> + Send + Sync>;
/// `www_authenticate(ctx, err)`: the `WWW-Authenticate` value ("" ⇒ omitted).
pub type WwwAuthenticateFn = Arc<dyn Fn(&McpRequestContext, &str) -> String + Send + Sync>;
pub type ServerInfoFn = Arc<dyn Fn(&McpRequestContext) -> McpServerInfo + Send + Sync>;
pub type ListToolsFn = Arc<dyn Fn(Ctx) -> BoxFuture<Result<Vec<Tool>, String>> + Send + Sync>;
/// `before_call(ctx, tool, args)`; an error is a deny carrying its message.
pub type BeforeCallFn =
    Arc<dyn Fn(Ctx, Tool, Value) -> BoxFuture<Result<McpDecision, String>> + Send + Sync>;
pub type OnMcpCallFn = Arc<dyn Fn(McpCallEvent) + Send + Sync>;
pub type ListResourcesFn =
    Arc<dyn Fn(Ctx) -> BoxFuture<Result<Vec<McpResource>, String>> + Send + Sync>;
pub type ReadResourceFn =
    Arc<dyn Fn(Ctx, String) -> BoxFuture<Result<Vec<McpResourceContent>, String>> + Send + Sync>;
pub type ListPromptsFn =
    Arc<dyn Fn(Ctx) -> BoxFuture<Result<Vec<McpPrompt>, String>> + Send + Sync>;
pub type GetPromptFn = Arc<
    dyn Fn(Ctx, String, HashMap<String, String>) -> BoxFuture<Result<McpPromptResult, String>>
        + Send
        + Sync,
>;

/// Configures [`Toolkit::mcp_handler`]. Every field is optional.
#[derive(Clone, Default)]
pub struct McpHandlerConfig {
    /// Static serverInfo; `server_info`, when set, wins. Defaults
    /// `toolnexus` / `0.1.0`; empty title / instructions are omitted.
    pub name: String,
    pub title: String,
    pub version: String,
    pub instructions: String,
    pub server_info: Option<ServerInfoFn>,
    /// Accepted protocol versions, newest first. Entries outside
    /// [`MCP_HANDLER_IMPLEMENTED_VERSIONS`] are dropped. Default `["2025-06-18"]`.
    pub protocol_versions: Vec<String>,
    /// Runs on every HTTP request before JSON-RPC parsing.
    pub authenticate: Option<AuthenticateFn>,
    pub www_authenticate: Option<WwwAuthenticateFn>,
    /// The tool list per request, for `tools/list` AND the `tools/call` lookup.
    /// `None` ⇒ the toolkit's tools.
    pub list_tools: Option<ListToolsFn>,
    pub before_call: Option<BeforeCallFn>,
    /// Bounds an elicitation round trip. `None` ⇒ 5 minutes.
    pub confirm_timeout: Option<Duration>,
    /// Fires once per `tools/call`; its panics are isolated.
    pub on_call: Option<OnMcpCallFn>,
    /// `None` list hook ⇒ capability not advertised, methods are -32601.
    pub list_resources: Option<ListResourcesFn>,
    pub read_resource: Option<ReadResourceFn>,
    pub list_prompts: Option<ListPromptsFn>,
    pub get_prompt: Option<GetPromptFn>,
    /// Replaces the default in-memory session set.
    pub session_ids: Option<Arc<dyn McpSessionIds>>,
    /// Issue and check no session id; confirm never elicits.
    pub stateless: bool,
    /// Stateless only: returned as `Mcp-Session-Id` on initialize, never validated.
    pub stateless_session_id: String,
    /// Caps a POST body. 0 ⇒ 4 MiB.
    pub max_body_bytes: usize,
}

/// The default in-memory session set.
#[derive(Default)]
struct MemorySessionIds {
    live: Mutex<std::collections::HashSet<String>>,
}

impl McpSessionIds for MemorySessionIds {
    fn generate(&self, _: Ctx) -> BoxFuture<Result<String, String>> {
        let id = uuid::Uuid::new_v4().simple().to_string();
        self.live.lock().unwrap().insert(id.clone());
        Box::pin(async move { Ok(id) })
    }
    fn validate(&self, _: Ctx, id: String) -> BoxFuture<Result<bool, String>> {
        let ok = self.live.lock().unwrap().contains(&id);
        Box::pin(async move { Ok(ok) })
    }
    fn terminate(&self, _: Ctx, id: String) -> BoxFuture<Result<(), String>> {
        self.live.lock().unwrap().remove(&id);
        Box::pin(async { Ok(()) })
    }
}

/// The response body type.
pub type McpBody = BoxBody<Bytes, Infallible>;

struct Inner {
    tk: Toolkit,
    cfg: McpHandlerConfig,
    ids: Arc<dyn McpSessionIds>,
    versions: Vec<String>,
    confirm_timeout: Duration,
    max_body: usize,
    caps: Mutex<HashMap<String, bool>>,
    streams: Mutex<Vec<mpsc::Sender<Bytes>>>,
    pending: Mutex<HashMap<(String, String), oneshot::Sender<Value>>>,
    seq: AtomicU64,
}

/// The embeddable MCP server returned by [`Toolkit::mcp_handler`]. Cheap to
/// clone. Mount it with hyper (`hyper::service::Service`) or call
/// [`McpHandler::handle`] from any framework.
#[derive(Clone)]
pub struct McpHandler {
    inner: Arc<Inner>,
}

impl Toolkit {
    /// A streamable-HTTP MCP server for this toolkit, to mount on the host's
    /// own router (SPEC §7C.1). [`Toolkit::serve`] is unchanged.
    pub fn mcp_handler(&self, cfg: McpHandlerConfig) -> McpHandler {
        let mut versions: Vec<String> = Vec::new();
        for v in &cfg.protocol_versions {
            if MCP_HANDLER_IMPLEMENTED_VERSIONS.contains(&v.as_str()) && !versions.contains(v) {
                versions.push(v.clone());
            }
        }
        if versions.is_empty() {
            versions = DEFAULT_VERSIONS.iter().map(|s| s.to_string()).collect();
        }
        let ids = cfg
            .session_ids
            .clone()
            .unwrap_or_else(|| Arc::new(MemorySessionIds::default()));
        McpHandler {
            inner: Arc::new(Inner {
                tk: self.clone(),
                ids,
                versions,
                confirm_timeout: cfg
                    .confirm_timeout
                    .filter(|d| !d.is_zero())
                    .unwrap_or(Duration::from_secs(300)),
                max_body: if cfg.max_body_bytes == 0 {
                    4 << 20
                } else {
                    cfg.max_body_bytes
                },
                cfg,
                caps: Mutex::default(),
                streams: Mutex::default(),
                pending: Mutex::default(),
                seq: AtomicU64::new(0),
            }),
        }
    }
}

// ── responses ────────────────────────────────────────────────────────────

fn plain_error(status: StatusCode, msg: &str) -> Response<McpBody> {
    let mut r = Response::new(Full::new(Bytes::from(format!("{msg}\n"))).boxed());
    *r.status_mut() = status;
    let h = r.headers_mut();
    h.insert(
        "content-type",
        HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    h.insert(
        "x-content-type-options",
        HeaderValue::from_static("nosniff"),
    );
    r
}

fn json_response(status: StatusCode, v: &Value) -> Response<McpBody> {
    let mut body = serde_json::to_vec(v).unwrap_or_default();
    body.push(b'\n');
    let mut r = Response::new(Full::new(Bytes::from(body)).boxed());
    *r.status_mut() = status;
    r.headers_mut()
        .insert("content-type", HeaderValue::from_static("application/json"));
    r
}

fn empty(status: StatusCode) -> Response<McpBody> {
    let mut r = Response::new(Full::new(Bytes::new()).boxed());
    *r.status_mut() = status;
    r
}

fn rpc_error_response(id: Option<Value>, code: i64, message: &str) -> Response<McpBody> {
    json_response(
        StatusCode::BAD_REQUEST,
        &json!({"jsonrpc": "2.0", "id": id.unwrap_or(Value::Null),
                "error": {"code": code, "message": message}}),
    )
}

fn envelope(id: &Value, out: Result<Value, RpcErr>) -> Value {
    match out {
        Ok(r) => json!({"jsonrpc": "2.0", "id": id, "result": r}),
        Err(e) => json!({"jsonrpc": "2.0", "id": id, "error": {"code": e.0, "message": e.1}}),
    }
}

fn sse_frame(v: &Value) -> Bytes {
    Bytes::from(format!(
        "event: message\ndata: {}\n\n",
        serde_json::to_string(v).unwrap_or_default()
    ))
}

fn sse_response(rx: mpsc::Receiver<Bytes>) -> Response<McpBody> {
    let stream = tokio_stream_from(rx).map(|b| Ok::<_, Infallible>(Frame::data(b)));
    let mut r = Response::new(BodyExt::boxed(StreamBody::new(stream)));
    r.headers_mut().insert(
        "content-type",
        HeaderValue::from_static("text/event-stream"),
    );
    r.headers_mut()
        .insert("cache-control", HeaderValue::from_static("no-cache"));
    r
}

fn tokio_stream_from(mut rx: mpsc::Receiver<Bytes>) -> impl futures_util::Stream<Item = Bytes> {
    futures_util::stream::poll_fn(move |cx| rx.poll_recv(cx))
}

#[derive(Debug)]
struct RpcErr(i64, String);

fn rpc(code: i64, msg: impl Into<String>) -> RpcErr {
    RpcErr(code, msg.into())
}

/// Messages from a POST's dispatch task: server→client events, then the reply.
enum Out {
    Event(Value),
    Final(Value),
}

/// Answers one POST: plain JSON, unless a server→client request was sent first.
#[derive(Clone)]
struct RpcWriter(mpsc::UnboundedSender<Out>);

impl RpcWriter {
    fn event(&self, v: Value) -> bool {
        self.0.send(Out::Event(v)).is_ok()
    }
}

#[derive(serde::Deserialize)]
struct RpcMessage {
    #[serde(default)]
    jsonrpc: String,
    #[serde(default)]
    id: Option<Value>,
    #[serde(default)]
    method: String,
    #[serde(default)]
    params: Option<Value>,
    #[serde(default)]
    result: Option<Value>,
    #[serde(default)]
    error: Option<Value>,
}

fn omit_empty(pairs: &[(&str, &str)]) -> Map<String, Value> {
    pairs
        .iter()
        .filter(|(_, v)| !v.is_empty())
        .map(|(k, v)| (k.to_string(), Value::String(v.to_string())))
        .collect()
}

// ── HTTP ─────────────────────────────────────────────────────────────────

impl McpHandler {
    /// Send `notifications/tools/list_changed` on every open GET stream; a
    /// slow stream drops the message rather than block.
    pub fn notify_tools_changed(&self) {
        let msg =
            sse_frame(&json!({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}));
        let mut streams = self.inner.streams.lock().unwrap();
        streams.retain(|tx| {
            !matches!(
                tx.try_send(msg.clone()),
                Err(mpsc::error::TrySendError::Closed(_))
            )
        });
    }

    /// Serve one HTTP request (POST / GET / DELETE; anything else is 405).
    pub async fn handle<B>(&self, req: Request<B>) -> Response<McpBody>
    where
        B: Body + Send + 'static,
        B::Data: Send,
        B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
    {
        let (parts, body) = req.into_parts();
        let mut rc = McpRequestContext {
            method: parts.method.clone(),
            uri: parts.uri.clone(),
            headers: parts.headers.clone(),
            identity: None,
            session_id: String::new(),
            meta: None,
        };
        let h = &self.inner;
        if let Some(auth) = &h.cfg.authenticate {
            match auth(Arc::new(rc.clone())).await {
                Ok(id) => rc.identity = Some(id),
                Err(e) => {
                    let mut r = plain_error(StatusCode::UNAUTHORIZED, "Unauthorized");
                    if let Some(w) = &h.cfg.www_authenticate {
                        let v = w(&rc, &e);
                        if let (false, Ok(hv)) = (v.is_empty(), HeaderValue::from_str(&v)) {
                            r.headers_mut().insert("www-authenticate", hv);
                        }
                    }
                    return r;
                }
            }
        }
        match parts.method {
            Method::POST => self.handle_post(rc, body).await,
            Method::GET => self.handle_get(rc).await,
            Method::DELETE => self.handle_delete(rc).await,
            _ => {
                let mut r = plain_error(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
                r.headers_mut()
                    .insert("allow", HeaderValue::from_static("GET, POST, DELETE"));
                r
            }
        }
    }

    /// Validate the `MCP-Protocol-Version` header and (stateful) the session.
    async fn check_session(
        &self,
        mut rc: McpRequestContext,
    ) -> Result<McpRequestContext, Box<Response<McpBody>>> {
        let h = &self.inner;
        let v = rc.header("mcp-protocol-version");
        if !v.is_empty() && !h.versions.iter().any(|x| x == v) {
            return Err(Box::new(plain_error(
                StatusCode::BAD_REQUEST,
                "Bad Request: unsupported MCP-Protocol-Version",
            )));
        }
        if h.cfg.stateless {
            return Ok(rc);
        }
        let sid = rc.header("mcp-session-id").to_owned();
        if sid.is_empty() {
            return Err(Box::new(plain_error(
                StatusCode::BAD_REQUEST,
                "Bad Request: missing Mcp-Session-Id",
            )));
        }
        match h.ids.validate(Arc::new(rc.clone()), sid.clone()).await {
            Ok(true) => {
                rc.session_id = sid;
                Ok(rc)
            }
            _ => Err(Box::new(plain_error(
                StatusCode::NOT_FOUND,
                "Session not found",
            ))),
        }
    }

    async fn handle_post<B>(&self, rc: McpRequestContext, body: B) -> Response<McpBody>
    where
        B: Body + Send + 'static,
        B::Data: Send,
        B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
    {
        let h = &self.inner;
        let ct = rc.header("content-type");
        let mt = ct
            .split(';')
            .next()
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        if mt != "application/json" {
            return plain_error(
                StatusCode::UNSUPPORTED_MEDIA_TYPE,
                "Unsupported Media Type: must be application/json",
            );
        }
        let body = match Limited::new(body, h.max_body).collect().await {
            Ok(b) => b.to_bytes(),
            Err(e) => {
                if e.downcast_ref::<http_body_util::LengthLimitError>()
                    .is_some()
                {
                    return plain_error(StatusCode::PAYLOAD_TOO_LARGE, "Request Entity Too Large");
                }
                return rpc_error_response(None, -32700, "read error");
            }
        };
        let text = String::from_utf8_lossy(&body);
        if text.trim_start().starts_with('[') {
            // JSON-RPC batching was removed in protocol 2025-06-18.
            return rpc_error_response(None, -32600, "batch requests are not supported");
        }
        let msg: RpcMessage = match serde_json::from_slice::<RpcMessage>(&body) {
            Ok(m) if m.jsonrpc == "2.0" => m,
            _ => return rpc_error_response(None, -32700, "parse error"),
        };
        if msg.method == "initialize" {
            return self.handle_initialize(rc, msg).await;
        }
        let mut rc = match self.check_session(rc).await {
            Ok(rc) => rc,
            Err(r) => return *r,
        };
        // A response from the client (the answer to our elicitation).
        if msg.method.is_empty() {
            if let Some(Value::String(id)) = &msg.id {
                let tx = h
                    .pending
                    .lock()
                    .unwrap()
                    .remove(&(rc.session_id.clone(), id.clone()));
                if let Some(tx) = tx {
                    let raw = json!({"result": msg.result, "error": msg.error});
                    let _ = tx.send(raw);
                }
            }
            return empty(StatusCode::ACCEPTED);
        }
        // A notification (no id): acknowledge.
        let Some(id) = msg.id.clone() else {
            return empty(StatusCode::ACCEPTED);
        };
        if let Some(Value::Object(p)) = &msg.params {
            if let Some(Value::Object(m)) = p.get("_meta") {
                rc.meta = Some(m.clone());
            }
        }

        let (tx, mut rx) = mpsc::unbounded_channel();
        let me = self.clone();
        let rc = Arc::new(rc);
        let writer = RpcWriter(tx.clone());
        tokio::spawn(async move {
            let out = me.dispatch(rc, writer, msg).await;
            let _ = tx.send(Out::Final(envelope(&id, out)));
        });
        match rx.recv().await {
            Some(Out::Event(first)) => {
                // Upgrade to SSE: forward events, then the reply, then end.
                let (stx, srx) = mpsc::channel::<Bytes>(16);
                tokio::spawn(async move {
                    if stx.send(sse_frame(&first)).await.is_err() {
                        return;
                    }
                    while let Some(m) = rx.recv().await {
                        let (v, last) = match m {
                            Out::Event(v) => (v, false),
                            Out::Final(v) => (v, true),
                        };
                        if stx.send(sse_frame(&v)).await.is_err() || last {
                            return;
                        }
                    }
                });
                sse_response(srx)
            }
            Some(Out::Final(v)) => json_response(StatusCode::OK, &v),
            None => plain_error(StatusCode::INTERNAL_SERVER_ERROR, "Internal Server Error"),
        }
    }

    async fn handle_initialize(
        &self,
        mut rc: McpRequestContext,
        msg: RpcMessage,
    ) -> Response<McpBody> {
        let h = &self.inner;
        let p = msg.params.clone().unwrap_or(Value::Null);
        let asked = p
            .get("protocolVersion")
            .and_then(Value::as_str)
            .unwrap_or("");
        let version = if h.versions.iter().any(|v| v == asked) {
            asked.to_owned()
        } else {
            h.versions[0].clone()
        };
        let id = msg.id.clone().unwrap_or(Value::Null);
        let sid;
        if h.cfg.stateless {
            sid = h.cfg.stateless_session_id.clone();
        } else {
            match h.ids.generate(Arc::new(rc.clone())).await {
                Ok(s) => sid = s,
                Err(_) => return rpc_error_response(Some(id), -32603, "cannot create session"),
            }
            rc.session_id = sid.clone();
            let el = p
                .get("capabilities")
                .and_then(|c| c.get("elicitation"))
                .is_some_and(|e| !e.is_null());
            h.caps.lock().unwrap().insert(sid.clone(), el);
        }
        let mut info = McpServerInfo {
            name: h.cfg.name.clone(),
            title: h.cfg.title.clone(),
            version: h.cfg.version.clone(),
            instructions: h.cfg.instructions.clone(),
        };
        if let Some(f) = &h.cfg.server_info {
            info = f(&rc);
        }
        if info.name.is_empty() {
            info.name = DEFAULT_NAME.into();
        }
        if info.version.is_empty() {
            info.version = DEFAULT_VERSION.into();
        }
        let mut caps = json!({"tools": {"listChanged": true}});
        if h.cfg.list_resources.is_some() {
            caps["resources"] = json!({});
        }
        if h.cfg.list_prompts.is_some() {
            caps["prompts"] = json!({});
        }
        let mut res = json!({
            "protocolVersion": version,
            "capabilities": caps,
            "serverInfo": omit_empty(&[("name", &info.name), ("title", &info.title), ("version", &info.version)]),
        });
        if !info.instructions.is_empty() {
            res["instructions"] = Value::String(info.instructions);
        }
        let mut r = json_response(StatusCode::OK, &envelope(&id, Ok(res)));
        if let (false, Ok(hv)) = (sid.is_empty(), HeaderValue::from_str(&sid)) {
            r.headers_mut().insert("mcp-session-id", hv);
        }
        r
    }

    async fn handle_get(&self, rc: McpRequestContext) -> Response<McpBody> {
        if !rc.header("accept").contains("text/event-stream") {
            return plain_error(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
        }
        if let Err(r) = self.check_session(rc).await {
            return *r;
        }
        let (tx, rx) = mpsc::channel::<Bytes>(16);
        self.inner.streams.lock().unwrap().push(tx);
        sse_response(rx)
    }

    async fn handle_delete(&self, rc: McpRequestContext) -> Response<McpBody> {
        let h = &self.inner;
        if h.cfg.stateless {
            return plain_error(StatusCode::METHOD_NOT_ALLOWED, "Method Not Allowed");
        }
        let rc = match self.check_session(rc).await {
            Ok(rc) => rc,
            Err(r) => return *r,
        };
        let sid = rc.session_id.clone();
        if h.ids.terminate(Arc::new(rc), sid.clone()).await.is_err() {
            return plain_error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "cannot terminate session",
            );
        }
        h.caps.lock().unwrap().remove(&sid);
        empty(StatusCode::OK)
    }

    // ── JSON-RPC dispatch ────────────────────────────────────────────────

    async fn tools(&self, rc: &Ctx) -> Result<Vec<Tool>, String> {
        match &self.inner.cfg.list_tools {
            Some(f) => f(rc.clone()).await,
            None => Ok(self.inner.tk.tools()),
        }
    }

    async fn dispatch(&self, rc: Ctx, w: RpcWriter, msg: RpcMessage) -> Result<Value, RpcErr> {
        let cfg = &self.inner.cfg;
        let params = msg.params.clone().unwrap_or(Value::Null);
        let not_found = || rpc(-32601, format!("method not found: {}", msg.method));
        match msg.method.as_str() {
            "ping" => Ok(json!({})),
            "tools/list" => {
                let tools = self.tools(&rc).await.map_err(|e| rpc(-32603, e))?;
                Ok(json!({"tools": tools.iter().map(mcp_wire_tool).collect::<Vec<_>>()}))
            }
            "tools/call" => self.call_tool(rc, w, params).await,
            "resources/list" => {
                let f = cfg.list_resources.as_ref().ok_or_else(not_found)?;
                let rs = f(rc).await.map_err(|e| rpc(-32603, e))?;
                let out: Vec<Value> = rs
                    .iter()
                    .map(|r| {
                        Value::Object(omit_empty(&[
                            ("uri", &r.uri),
                            ("name", &r.name),
                            ("title", &r.title),
                            ("description", &r.description),
                            ("mimeType", &r.mime_type),
                        ]))
                    })
                    .collect();
                Ok(json!({"resources": out}))
            }
            "resources/read" => {
                let (Some(_), Some(f)) = (&cfg.list_resources, &cfg.read_resource) else {
                    return Err(not_found());
                };
                let uri = params
                    .get("uri")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_owned();
                let cs = f(rc, uri).await.map_err(|e| rpc(-32002, e))?;
                let out: Vec<Value> = cs
                    .iter()
                    .map(|c| {
                        let mut m = omit_empty(&[
                            ("uri", &c.uri),
                            ("mimeType", &c.mime_type),
                            ("blob", &c.blob),
                        ]);
                        if c.blob.is_empty() {
                            m.insert("text".into(), Value::String(c.text.clone()));
                        }
                        Value::Object(m)
                    })
                    .collect();
                Ok(json!({"contents": out}))
            }
            "prompts/list" => {
                let f = cfg.list_prompts.as_ref().ok_or_else(not_found)?;
                let ps = f(rc).await.map_err(|e| rpc(-32603, e))?;
                let out: Vec<Value> = ps
                    .iter()
                    .map(|p| {
                        let mut m = omit_empty(&[
                            ("name", &p.name),
                            ("title", &p.title),
                            ("description", &p.description),
                        ]);
                        if !p.arguments.is_empty() {
                            let args: Vec<Value> = p
                                .arguments
                                .iter()
                                .map(|a| {
                                    let mut am = omit_empty(&[
                                        ("name", &a.name),
                                        ("description", &a.description),
                                    ]);
                                    if a.required {
                                        am.insert("required".into(), Value::Bool(true));
                                    }
                                    Value::Object(am)
                                })
                                .collect();
                            m.insert("arguments".into(), Value::Array(args));
                        }
                        Value::Object(m)
                    })
                    .collect();
                Ok(json!({"prompts": out}))
            }
            "prompts/get" => {
                let (Some(_), Some(f)) = (&cfg.list_prompts, &cfg.get_prompt) else {
                    return Err(not_found());
                };
                #[derive(serde::Deserialize)]
                struct P {
                    #[serde(default)]
                    name: String,
                    #[serde(default)]
                    arguments: Option<HashMap<String, String>>,
                }
                let p: P =
                    serde_json::from_value(params).map_err(|_| rpc(-32602, "invalid params"))?;
                let pr = f(rc, p.name, p.arguments.unwrap_or_default())
                    .await
                    .map_err(|e| rpc(-32602, e))?;
                let msgs: Vec<Value> = pr
                    .messages
                    .iter()
                    .map(|m| json!({"role": m.role, "content": {"type": "text", "text": m.text}}))
                    .collect();
                let mut out = omit_empty(&[("description", &pr.description)]);
                out.insert("messages".into(), Value::Array(msgs));
                Ok(Value::Object(out))
            }
            _ => Err(not_found()),
        }
    }

    async fn call_tool(&self, rc: Ctx, w: RpcWriter, params: Value) -> Result<Value, RpcErr> {
        let h = &self.inner;
        let Value::Object(p) = params else {
            return Err(rpc(-32602, "invalid params"));
        };
        let name = match p.get("name") {
            None | Some(Value::Null) => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(_) => return Err(rpc(-32602, "invalid params")),
        };
        let tools = self.tools(&rc).await.map_err(|e| rpc(-32603, e))?;
        let Some(tool) = tools.into_iter().find(|t| t.name == name) else {
            return Err(rpc(-32602, format!("tool not found: {name}")));
        };
        let args = match p.get("arguments") {
            None | Some(Value::Null) => Value::Object(Map::new()),
            Some(Value::Object(m)) => Value::Object(m.clone()),
            Some(_) => {
                if rc.header("mcp-protocol-version") == "2025-11-25" {
                    // 2025-11-25: input validation errors are tool execution errors.
                    return Ok(tool_result_to_mcp(&ToolResult::error(
                        "invalid arguments: must be an object",
                    )));
                }
                return Err(rpc(-32602, "arguments must be an object"));
            }
        };

        let started = Instant::now();
        let mut ev = McpCallEvent {
            name: tool.name.clone(),
            source: tool.source,
            ms: 0,
            is_error: false,
            err: None,
            result_bytes: 0,
            decision: McpDecisionKind::Allow,
            identity: rc.identity.clone(),
            session_id: rc.session_id.clone(),
        };
        let on_call = h.cfg.on_call.clone();
        let finish = move |mut ev: McpCallEvent, res: &ToolResult, err: Option<String>| {
            let wire = tool_result_to_mcp(res);
            ev.ms = started.elapsed().as_millis() as u64;
            ev.is_error = res.is_error;
            ev.err = err;
            ev.result_bytes = serde_json::to_vec(&wire).map(|b| b.len()).unwrap_or(0);
            if let Some(cb) = &on_call {
                let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(ev)));
            }
            wire
        };

        if let Some(bc) = &h.cfg.before_call {
            let d = match bc(rc.clone(), tool.clone(), args.clone()).await {
                Ok(d) => d,
                Err(e) => McpDecision::deny(e),
            };
            ev.decision = d.kind;
            match d.kind {
                McpDecisionKind::Allow => {}
                McpDecisionKind::Deny => {
                    let res = d.result.unwrap_or_else(|| {
                        ToolResult::error(if d.message.is_empty() {
                            "denied".to_owned()
                        } else {
                            d.message.clone()
                        })
                    });
                    return Ok(finish(ev, &res, None));
                }
                McpDecisionKind::Confirm => {
                    if !self.can_elicit(&rc.session_id) {
                        let res = d.result.unwrap_or_else(|| {
                            ToolResult::error(format!("confirmation required: {}", d.message))
                        });
                        return Ok(finish(ev, &res, None));
                    }
                    match self.elicit_confirm(&rc.session_id, &w, &d.message).await {
                        Err(e) => {
                            let res = ToolResult::error(format!("confirmation failed: {e}"));
                            return Ok(finish(ev, &res, None));
                        }
                        Ok(false) => return Ok(finish(ev, &ToolResult::error("declined"), None)),
                        Ok(true) => {}
                    }
                }
            }
        }

        let ctx = ToolContext {
            request: Some(rc.clone() as Arc<dyn Any + Send + Sync>),
            ..Default::default()
        };
        let t = tool.clone();
        // A panicking tool is an isError result, never a protocol crash.
        let res = match tokio::spawn(async move { t.call(args, ctx).await }).await {
            Ok(r) => r,
            Err(e) => ToolResult::error(format!("tool panicked: {e}")),
        };
        let err = res.is_error.then(|| res.output.clone());
        Ok(finish(ev, &res, err))
    }

    fn can_elicit(&self, sid: &str) -> bool {
        !sid.is_empty() && *self.inner.caps.lock().unwrap().get(sid).unwrap_or(&false)
    }

    /// `elicitation/create` on this POST's SSE stream; the answer arrives on a
    /// separate POST.
    async fn elicit_confirm(
        &self,
        sid: &str,
        w: &RpcWriter,
        message: &str,
    ) -> Result<bool, String> {
        let h = &self.inner;
        let id = format!(
            "toolnexus-confirm-{}",
            h.seq.fetch_add(1, Ordering::SeqCst) + 1
        );
        let key = (sid.to_owned(), id.clone());
        let (tx, rx) = oneshot::channel();
        h.pending.lock().unwrap().insert(key.clone(), tx);
        struct Cleanup<'a>(&'a Inner, (String, String));
        impl Drop for Cleanup<'_> {
            fn drop(&mut self) {
                self.0.pending.lock().unwrap().remove(&self.1);
            }
        }
        let _g = Cleanup(h, key);
        let message = if message.is_empty() {
            "Confirm this action?"
        } else {
            message
        };
        let req = json!({
            "jsonrpc": "2.0", "id": id, "method": "elicitation/create",
            "params": {
                "message": message,
                "requestedSchema": {
                    "type": "object",
                    "properties": {
                        "confirm": {"type": "boolean", "title": "Confirm", "description": message}
                    }
                }
            }
        });
        if !w.event(req) {
            return Err("stream closed".into());
        }
        let raw = match tokio::time::timeout(h.confirm_timeout, rx).await {
            Ok(Ok(v)) => v,
            Ok(Err(_)) => return Err("stream closed".into()),
            Err(_) => return Err("context deadline exceeded".into()),
        };
        if raw.get("error").is_some_and(|e| !e.is_null()) {
            return Err("client returned an error".into());
        }
        let Some(Value::Object(r)) = raw.get("result") else {
            return Err("unexpected end of JSON input".into());
        };
        if r.get("action").and_then(Value::as_str) != Some("accept") {
            return Ok(false);
        }
        if let Some(Value::Bool(false)) = r.get("content").and_then(|c| c.get("confirm")) {
            return Ok(false);
        }
        Ok(true)
    }
}

impl<B> hyper::service::Service<Request<B>> for McpHandler
where
    B: Body + Send + 'static,
    B::Data: Send,
    B::Error: Into<Box<dyn std::error::Error + Send + Sync>>,
{
    type Response = Response<McpBody>;
    type Error = Infallible;
    type Future = BoxFuture<Result<Response<McpBody>, Infallible>>;

    fn call(&self, req: Request<B>) -> Self::Future {
        let me = self.clone();
        Box::pin(async move { Ok(me.handle(req).await) })
    }
}

// ── wire helpers (shared with §7C serve) ─────────────────────────────────

/// The `tools/list` entry for `t`: name, description, inputSchema,
/// outputSchema when set, and annotations (verbatim; else derived from hints;
/// else omitted).
pub(crate) fn mcp_wire_tool(t: &Tool) -> Value {
    let schema = if t.input_schema.is_null() {
        json!({"type": "object"})
    } else {
        t.input_schema.clone()
    };
    let mut m = json!({"name": t.name, "description": t.description, "inputSchema": schema});
    if let Some(os) = &t.output_schema {
        m["outputSchema"] = os.clone();
    }
    let ann = if let Some(a) = &t.annotations {
        serde_json::to_value(a).unwrap_or(Value::Null)
    } else if let Some(hh) = &t.hints {
        json!({"readOnlyHint": hh.read_only, "destructiveHint": hh.destructive, "openWorldHint": hh.open_world})
    } else {
        Value::Null
    };
    if ann.as_object().is_some_and(|o| !o.is_empty()) {
        m["annotations"] = ann;
    }
    m
}

/// A ToolResult as the MCP CallToolResult: output → text block (the JSON of
/// `structured` when output is empty), §1B parts after it, isError when true,
/// structuredContent / _meta when set.
pub(crate) fn tool_result_to_mcp(res: &ToolResult) -> Value {
    let mut text = res.output.clone();
    if text.is_empty() {
        if let Some(st) = &res.structured {
            text = serde_json::to_string(st).unwrap_or_default();
        }
    }
    let mut content = vec![json!({"type": "text", "text": text})];
    content.extend(res.parts.iter().filter_map(part_to_mcp));
    let mut out = json!({"content": content});
    if res.is_error {
        out["isError"] = Value::Bool(true);
    }
    if let Some(st) = &res.structured {
        out["structuredContent"] = st.clone();
    }
    if let Some(m) = &res.meta {
        out["_meta"] = Value::Object(m.clone());
    }
    out
}

fn part_to_mcp(p: &ContentPart) -> Option<Value> {
    let link = || {
        let mut m = omit_empty(&[
            ("uri", &p.url),
            ("name", &p.name),
            ("mimeType", &p.mime_type),
        ]);
        m.insert("type".into(), json!("resource_link"));
        m.entry("uri").or_insert(json!(""));
        m.entry("name").or_insert(json!(""));
        Value::Object(m)
    };
    match p.kind.as_str() {
        "text" => Some(json!({"type": "text", "text": p.text})),
        "image" | "audio" if !p.data.is_empty() => {
            Some(json!({"type": p.kind, "data": p.data, "mimeType": p.mime_type}))
        }
        "file" if !p.data.is_empty() => {
            let mut r = omit_empty(&[("uri", &p.name), ("mimeType", &p.mime_type)]);
            r.entry("uri").or_insert(json!(""));
            r.insert("blob".into(), json!(p.data));
            Some(json!({"type": "resource", "resource": r}))
        }
        "image" | "audio" | "file" => Some(link()),
        _ => None,
    }
}
