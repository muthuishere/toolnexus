//! A2A — INBOUND: serve a toolkit as a remote A2A agent (SPEC §7B), plus the
//! mount point for the MCP server profile (§7C, [`crate::mcp_serve`]). Port of
//! `golang/serve.go`. Behind the `serve` feature (hyper).
//!
//! With the A2A profile present, [`Toolkit::serve`] exposes:
//!
//! - `GET /.well-known/agent-card.json` — an Agent Card built from the toolkit's
//!   SKILLS (SKILL.md name + description), never raw tools.
//! - `POST /` (any path but `/mcp`) — JSON-RPC 2.0: `SendMessage` (submit a Task,
//!   fulfil it in the background through the client loop) and `GetTask` (poll).
//!
//! With the MCP profile present, `POST /mcp` is a streamable-HTTP MCP server.
//! Neither ⇒ every request is `404 not found`.

use std::convert::Infallible;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use http_body_util::{BodyExt, Full, combinators::BoxBody};
use hyper::body::{Bytes, Incoming};
use hyper::{Method, Request, Response, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::a2a::{A2aArtifact, A2aMessage, A2aPart, A2aTask, A2aTaskStatus};
use crate::client::{Client, RunResult};
use crate::mcp_serve::{McpServeConfig, OnCall, mcp_http_service};
use crate::skills::SkillInfo;
use crate::tool::sanitize;
use crate::toolkit::Toolkit;

/// Where the Agent Card is served.
pub const CARD_PATH: &str = "/.well-known/agent-card.json";
const PROTOCOL_VERSION: &str = "0.3.0";
const DEFAULT_VERSION: &str = "0.1.0";

// ---------------------------------------------------------------------------
// TaskStore — pluggable persistence for served Tasks.
// ---------------------------------------------------------------------------

/// Pluggable Task persistence. `Ok(None)` from `get` is an unknown id.
pub trait TaskStore: Send + Sync {
    fn get(&self, id: &str) -> Result<Option<A2aTask>, String>;
    fn save(&self, task: A2aTask) -> Result<(), String>;
}

/// The default store: Tasks live for the process lifetime.
#[derive(Debug, Default)]
pub struct InMemoryTaskStore {
    tasks: Mutex<std::collections::HashMap<String, A2aTask>>,
}

impl InMemoryTaskStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl TaskStore for InMemoryTaskStore {
    fn get(&self, id: &str) -> Result<Option<A2aTask>, String> {
        Ok(self
            .tasks
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(id)
            .cloned())
    }
    fn save(&self, task: A2aTask) -> Result<(), String> {
        self.tasks
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(task.id.clone(), task);
        Ok(())
    }
}

/// One JSON file per Task id under `dir` (atomic temp-file + rename writes).
#[derive(Debug, Clone)]
pub struct FileTaskStore {
    dir: PathBuf,
}

impl FileTaskStore {
    /// A store rooted at `dir` (created if missing).
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        let dir = dir.into();
        let _ = std::fs::create_dir_all(&dir);
        Self { dir }
    }
    fn file(&self, id: &str) -> PathBuf {
        self.dir.join(format!("{}.json", sanitize(id)))
    }
}

impl TaskStore for FileTaskStore {
    /// A missing or unreadable file is an unknown id, as in Go.
    fn get(&self, id: &str) -> Result<Option<A2aTask>, String> {
        Ok(std::fs::read(self.file(id))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok()))
    }
    fn save(&self, task: A2aTask) -> Result<(), String> {
        let data = serde_json::to_vec(&task).map_err(|e| e.to_string())?;
        let target = self.file(&task.id);
        let tmp = target.with_extension("json.tmp");
        std::fs::write(&tmp, data).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, &target).map_err(|e| e.to_string())
    }
}

/// The `store` selector of [`A2aConfig`]: `"memory"` (default, also `""`),
/// `"file:<dir>"`, or a host store.
#[derive(Clone)]
pub enum TaskStoreSpec {
    Selector(String),
    Custom(Arc<dyn TaskStore>),
}

impl Default for TaskStoreSpec {
    fn default() -> Self {
        TaskStoreSpec::Selector(String::new())
    }
}

impl std::fmt::Debug for TaskStoreSpec {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TaskStoreSpec::Selector(s) => f.debug_tuple("Selector").field(s).finish(),
            TaskStoreSpec::Custom(_) => f.write_str("Custom(..)"),
        }
    }
}

impl<'de> Deserialize<'de> for TaskStoreSpec {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(TaskStoreSpec::Selector(
            Option::<String>::deserialize(d)?.unwrap_or_default(),
        ))
    }
}

/// Map a selector to a store: `""` / `"memory"` ⇒ in-memory, `"file:<dir>"` ⇒
/// file store, a custom store as-is; anything else is an error.
pub fn resolve_store(spec: &TaskStoreSpec) -> Result<Arc<dyn TaskStore>, String> {
    match spec {
        TaskStoreSpec::Custom(s) => Ok(s.clone()),
        TaskStoreSpec::Selector(s) if s.is_empty() || s == "memory" => {
            Ok(Arc::new(InMemoryTaskStore::new()))
        }
        TaskStoreSpec::Selector(s) => match s.strip_prefix("file:") {
            Some(dir) => Ok(Arc::new(FileTaskStore::new(dir))),
            None => Err(format!(
                "unknown A2A store: {s:?} (expected \"memory\" or \"file:<dir>\")"
            )),
        },
    }
}

// ---------------------------------------------------------------------------
// Config, events, options, handle.
// ---------------------------------------------------------------------------

/// The organization publishing the agent.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aProvider {
    #[serde(default)]
    pub organization: String,
    #[serde(default)]
    pub url: String,
}

/// The opt-in A2A profile: the Agent Card and the Task store. Deserializes from
/// a top-level `a2a` config block.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct A2aConfig {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub provider: Option<A2aProvider>,
    /// Skill names to advertise; `None` ⇒ all, `Some` (even empty) ⇒ exactly these.
    #[serde(default)]
    pub skills: Option<Vec<String>>,
    #[serde(default)]
    pub store: TaskStoreSpec,
}

/// Surfaced on each Task's terminal state, with the run's telemetry.
#[derive(Debug, Clone)]
pub struct OnTaskEvent {
    pub id: String,
    pub skill: String,
    pub task: A2aTask,
    pub result: Option<RunResult>,
    pub state: String,
}

/// The terminal-state callback.
pub type OnTask = Arc<dyn Fn(OnTaskEvent) + Send + Sync>;

/// [`Toolkit::serve`] options. `client` fulfils Tasks; `a2a` (or the toolkit's
/// top-level `a2a` block) mounts the A2A routes; `mcp` (or a top-level
/// `mcpServer` block) mounts `POST /mcp`.
#[derive(Clone, Default)]
pub struct ServeOptions {
    pub client: Option<Client>,
    pub a2a: Option<A2aConfig>,
    pub on_task: Option<OnTask>,
    pub mcp: Option<McpServeConfig>,
    pub on_call: Option<OnCall>,
}

/// A running server: its base URL and an idempotent stop.
#[derive(Debug)]
pub struct ServeHandle {
    /// e.g. `http://127.0.0.1:PORT`.
    pub url: String,
    stop: CancellationToken,
}

impl ServeHandle {
    /// Stop accepting and drop open connections (idempotent).
    pub fn stop(&self) {
        self.stop.cancel();
    }
    /// Alias for [`stop`](Self::stop).
    pub fn close(&self) {
        self.stop();
    }
}

impl Drop for ServeHandle {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

// ---------------------------------------------------------------------------
// Card.
// ---------------------------------------------------------------------------

/// The fixed capability block of a served card.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AgentCardCapabilities {
    pub streaming: bool,
    #[serde(rename = "pushNotifications")]
    pub push_notifications: bool,
}

/// One advertised skill: always `{id, name, description}`.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct AgentCardSkill {
    pub id: String,
    pub name: String,
    pub description: String,
}

/// The exact wire shape published at [`CARD_PATH`] (field order = Go/JS).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct ServedAgentCard {
    pub name: String,
    pub description: String,
    pub version: String,
    #[serde(rename = "protocolVersion")]
    pub protocol_version: String,
    pub capabilities: AgentCardCapabilities,
    #[serde(rename = "defaultInputModes")]
    pub default_input_modes: Vec<String>,
    #[serde(rename = "defaultOutputModes")]
    pub default_output_modes: Vec<String>,
    pub skills: Vec<AgentCardSkill>,
    pub url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub provider: Option<A2aProvider>,
}

/// Build the card from the toolkit's skills (never raw tools), filtered to
/// `cfg.skills` when set. `url` is the JSON-RPC endpoint peers POST to.
pub fn build_agent_card(
    cfg: Option<&A2aConfig>,
    skills: &[SkillInfo],
    url: &str,
) -> ServedAgentCard {
    let wanted = cfg.and_then(|c| c.skills.as_ref());
    let skills = skills
        .iter()
        .filter(|s| wanted.is_none_or(|w| w.contains(&s.name)))
        .map(|s| AgentCardSkill {
            id: s.name.clone(),
            name: s.name.clone(),
            description: s.description.clone(),
        })
        .collect();
    let pick = |v: Option<&String>, d: &str| match v {
        Some(s) if !s.is_empty() => s.clone(),
        _ => d.to_owned(),
    };
    ServedAgentCard {
        name: pick(cfg.map(|c| &c.name), "toolnexus-agent"),
        description: pick(cfg.map(|c| &c.description), ""),
        version: pick(cfg.map(|c| &c.version), DEFAULT_VERSION),
        protocol_version: PROTOCOL_VERSION.into(),
        capabilities: AgentCardCapabilities {
            streaming: false,
            push_notifications: false,
        },
        default_input_modes: vec!["text".into()],
        default_output_modes: vec!["text".into()],
        skills,
        url: url.to_owned(),
        provider: cfg.and_then(|c| c.provider.clone()),
    }
}

// ---------------------------------------------------------------------------
// Server.
// ---------------------------------------------------------------------------

type Body = BoxBody<Bytes, Infallible>;

/// The future a [`RunTask`] returns.
type RunFuture = std::pin::Pin<Box<dyn Future<Output = Result<RunResult, String>> + Send>>;
/// Runs one served Task: `(text, contextId)`.
type RunTask = Arc<dyn Fn(String, String) -> RunFuture + Send + Sync>;

/// A JSON-RPC 2.0 response envelope (field order: jsonrpc, id, result|error).
#[derive(Serialize)]
struct RpcResponse<'a, T: Serialize> {
    jsonrpc: &'static str,
    id: &'a Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<RpcError>,
}

#[derive(Serialize)]
struct RpcError {
    code: i64,
    message: String,
}

fn json_response<T: Serialize>(v: &T) -> Response<Body> {
    let mut body = serde_json::to_vec(v).unwrap_or_default();
    body.push(b'\n');
    Response::builder()
        .header("content-type", "application/json")
        .body(Full::new(Bytes::from(body)).boxed())
        .unwrap_or_else(|_| not_found())
}

fn not_found() -> Response<Body> {
    let mut r = Response::new(Full::new(Bytes::from_static(b"not found")).boxed());
    *r.status_mut() = StatusCode::NOT_FOUND;
    r
}

fn rpc_ok<T: Serialize>(id: &Value, result: T) -> Response<Body> {
    json_response(&RpcResponse {
        jsonrpc: "2.0",
        id,
        result: Some(result),
        error: None,
    })
}

fn rpc_err(id: &Value, code: i64, message: String) -> Response<Body> {
    json_response(&RpcResponse::<()> {
        jsonrpc: "2.0",
        id,
        result: None,
        error: Some(RpcError { code, message }),
    })
}

/// The concatenated text of a `SendMessage` message's text parts.
fn message_text(params: &Value) -> String {
    params
        .pointer("/message/parts")
        .and_then(Value::as_array)
        .map(|parts| {
            parts
                .iter()
                .filter(|p| p.get("kind").and_then(Value::as_str) == Some("text"))
                .filter_map(|p| p.get("text").and_then(Value::as_str))
                .collect::<String>()
        })
        .unwrap_or_default()
}

fn message_context_id(params: &Value) -> String {
    params
        .pointer("/message/contextId")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

struct A2aState {
    cfg: A2aConfig,
    skills: Vec<SkillInfo>,
    store: Arc<dyn TaskStore>,
    run_task: RunTask,
    on_task: Option<OnTask>,
    base: String,
}

impl A2aState {
    async fn handle(&self, req: Request<Incoming>) -> Response<Body> {
        if req.method() == Method::GET && req.uri().path() == CARD_PATH {
            let url = format!("{}/", self.base);
            return json_response(&build_agent_card(Some(&self.cfg), &self.skills, &url));
        }
        if req.method() != Method::POST {
            return not_found();
        }
        let body = req
            .into_body()
            .collect()
            .await
            .map(|b| b.to_bytes())
            .unwrap_or_default();
        let rpc: Value = match serde_json::from_slice::<Value>(&body) {
            Ok(v) if v.is_object() => v,
            _ => return rpc_err(&Value::Null, -32700, "Parse error".into()),
        };
        let id = rpc.get("id").cloned().unwrap_or(Value::Null);
        let method = rpc.get("method").and_then(Value::as_str).unwrap_or("");
        let params = rpc.get("params").cloned().unwrap_or(Value::Null);
        match method {
            "SendMessage" => {
                let text = message_text(&params);
                let context_id = message_context_id(&params);
                let task_id = uuid::Uuid::new_v4().to_string();
                let submitted = A2aTask {
                    id: task_id.clone(),
                    context_id: context_id.clone(),
                    status: A2aTaskStatus {
                        state: "submitted".into(),
                        message: None,
                    },
                    ..Default::default()
                };
                let _ = self.store.save(submitted.clone());
                tokio::spawn(fulfil(
                    task_id,
                    text,
                    context_id,
                    self.store.clone(),
                    self.run_task.clone(),
                    self.on_task.clone(),
                ));
                rpc_ok(&id, submitted)
            }
            "GetTask" => {
                let tid = params.get("id").and_then(Value::as_str).unwrap_or_default();
                match self.store.get(tid).ok().flatten() {
                    Some(t) => rpc_ok(&id, t),
                    None => rpc_err(&id, -32001, format!("Task not found: {tid}")),
                }
            }
            other => rpc_err(&id, -32601, format!("Method not found: {other}")),
        }
    }
}

fn agent_text(text: String) -> Option<A2aMessage> {
    Some(A2aMessage {
        role: "agent".into(),
        message_id: String::new(),
        parts: vec![A2aPart::text(text)],
    })
}

/// Background fulfilment: submitted → working → completed | failed |
/// input-required. Never crashes the server: an error (or panic) in the run is a
/// failed Task, and a panicking `on_task` is isolated.
async fn fulfil(
    id: String,
    text: String,
    context_id: String,
    store: Arc<dyn TaskStore>,
    run_task: RunTask,
    on_task: Option<OnTask>,
) {
    let _ = store.save(A2aTask {
        id: id.clone(),
        status: A2aTaskStatus {
            state: "working".into(),
            message: None,
        },
        ..Default::default()
    });
    // A panic inside the client loop is caught by the spawned task's JoinHandle.
    let outcome = match tokio::spawn(run_task(text, context_id)).await {
        Ok(r) => r,
        Err(e) => Err(format!("task panicked: {e}")),
    };
    let (task, result, state) = match outcome {
        Err(detail) => (
            A2aTask {
                id: id.clone(),
                status: A2aTaskStatus {
                    state: "failed".into(),
                    message: agent_text(detail),
                },
                ..Default::default()
            },
            None,
            "failed",
        ),
        // §10 suspension over A2A: surface `input-required` with the prompt,
        // never a false `completed`.
        Ok(rr) if rr.status == "pending" && rr.pending.is_some() => {
            let prompt = rr
                .pending
                .as_ref()
                .map(|p| p.prompt.clone())
                .unwrap_or_default();
            (
                A2aTask {
                    id: id.clone(),
                    status: A2aTaskStatus {
                        state: "input-required".into(),
                        message: agent_text(prompt),
                    },
                    ..Default::default()
                },
                Some(rr),
                "input-required",
            )
        }
        Ok(rr) => (
            A2aTask {
                id: id.clone(),
                status: A2aTaskStatus {
                    state: "completed".into(),
                    message: None,
                },
                artifacts: vec![A2aArtifact {
                    artifact_id: uuid::Uuid::new_v4().to_string(),
                    parts: vec![A2aPart::text(rr.text.clone())],
                }],
                ..Default::default()
            },
            Some(rr),
            "completed",
        ),
    };
    let _ = store.save(task.clone());
    if let Some(cb) = on_task {
        let ev = OnTaskEvent {
            id,
            skill: String::new(),
            task,
            result,
            state: state.into(),
        };
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(ev)));
    }
}

/// Split `host:port` on the last colon; a missing host is `127.0.0.1`.
fn split_addr(addr: &str) -> (String, String) {
    match addr.rfind(':') {
        None => ("127.0.0.1".into(), addr.into()),
        Some(i) => {
            let host = &addr[..i];
            let host = host.trim_start_matches('[').trim_end_matches(']');
            let host = if host.is_empty() { "127.0.0.1" } else { host };
            (host.into(), addr[i + 1..].into())
        }
    }
}

/// `0.0.0.0` / `::` are reported as `127.0.0.1` for callers.
fn base_url(host: &str, port: u16) -> String {
    let h = if host == "0.0.0.0" || host == "::" {
        "127.0.0.1"
    } else {
        host
    };
    if h.contains(':') {
        format!("http://[{h}]:{port}")
    } else {
        format!("http://{h}:{port}")
    }
}

/// Serve error (bad store selector, bind failure).
#[derive(Debug, thiserror::Error)]
#[error("{0}")]
pub struct ServeError(pub String);

impl Toolkit {
    /// Expose this toolkit over HTTP (SPEC §7B / §7C). A message's A2A
    /// `contextId` keys the conversation via `Client::ask`; none ⇒ a stateless
    /// `Client::run`. Inline `a2a` / `mcp` options win over the toolkit's
    /// top-level `a2a` / `mcpServer` config blocks. Bind `127.0.0.1:0` for an
    /// ephemeral port.
    pub async fn serve(&self, addr: &str, opts: ServeOptions) -> Result<ServeHandle, ServeError> {
        let a2a_cfg = opts.a2a.clone().or_else(|| {
            self.config_block("a2a")
                .map(|v| serde_json::from_value(v).unwrap_or_default())
        });
        let mcp_cfg = opts.mcp.clone().or_else(|| {
            self.config_block("mcpServer")
                .map(|v| serde_json::from_value(v).unwrap_or_default())
        });

        let (host, port) = split_addr(addr);
        let listener = TcpListener::bind((host.as_str(), port.parse::<u16>().unwrap_or(0)))
            .await
            .map_err(|e| ServeError(e.to_string()))?;
        let actual = listener
            .local_addr()
            .map_err(|e| ServeError(e.to_string()))?
            .port();
        let base = base_url(&host, actual);
        let stop = CancellationToken::new();

        let a2a = match a2a_cfg {
            None => None,
            Some(cfg) => {
                let store = resolve_store(&cfg.store).map_err(ServeError)?;
                let skills: Vec<SkillInfo> = self
                    .skill_source()
                    .map(|s| s.skills.values().cloned().collect())
                    .unwrap_or_default();
                let tk = self.clone();
                let client = opts.client.clone();
                let run_task: RunTask = Arc::new(move |text, context_id| {
                    let tk = tk.clone();
                    let client = client.clone();
                    Box::pin(async move {
                        let Some(client) = client else {
                            return Err("serve: no client configured to fulfil the task".into());
                        };
                        let r = if context_id.is_empty() {
                            client.run(text, &tk).await
                        } else {
                            client.ask(text, &tk, &context_id).await
                        };
                        r.map_err(|e| e.to_string())
                    })
                });
                Some(Arc::new(A2aState {
                    cfg,
                    skills,
                    store,
                    run_task,
                    on_task: opts.on_task.clone(),
                    base: base.clone(),
                }))
            }
        };
        let mcp = mcp_cfg.map(|cfg| {
            Arc::new(mcp_http_service(
                self.tools(),
                &cfg,
                opts.on_call.clone(),
                stop.child_token(),
            ))
        });

        let stop_loop = stop.clone();
        tokio::spawn(async move {
            loop {
                let conn = tokio::select! {
                    _ = stop_loop.cancelled() => return,
                    c = listener.accept() => c,
                };
                let Ok((stream, _)) = conn else { continue };
                let a2a = a2a.clone();
                let mcp = mcp.clone();
                let stop_conn = stop_loop.clone();
                tokio::spawn(async move {
                    let svc = hyper::service::service_fn(move |req: Request<Incoming>| {
                        let a2a = a2a.clone();
                        let mcp = mcp.clone();
                        async move {
                            if let Some(m) = &mcp {
                                if req.uri().path() == "/mcp" {
                                    return Ok::<_, Infallible>(m.handle(req).await);
                                }
                            }
                            Ok(match &a2a {
                                Some(s) => s.handle(req).await,
                                None => not_found(),
                            })
                        }
                    });
                    let conn = hyper::server::conn::http1::Builder::new()
                        .serve_connection(hyper_util::rt::TokioIo::new(stream), svc);
                    tokio::select! {
                        _ = stop_conn.cancelled() => {}
                        _ = conn => {}
                    }
                });
            }
        });

        Ok(ServeHandle { url: base, stop })
    }
}
