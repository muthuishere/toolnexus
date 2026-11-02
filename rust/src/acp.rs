//! ACP (Agent Client Protocol) client — parity with `golang/acp.go`, plus the
//! streamed updates and host permission callback a desktop host needs.
//!
//! ACP is JSON-RPC 2.0, one object per line, over a child agent's stdin/stdout.
//! One [`AcpClient`] is one warm session: the agent process and its
//! `session/new` are created once by [`AcpClient::load`] and serve every turn.
//!
//! What this client does, in protocol order:
//! - `initialize` (protocol version 1 by default; fs capabilities declared off)
//!   with a generous timeout, because adapters spawned via `npx` cold-start slowly;
//! - `session/new` with an absolute cwd (agents reject a relative one), then an
//!   optional `session/set_mode`;
//! - `session/prompt`, one at a time per session (turns are serialized), with every
//!   `session/update` delivered to the caller as it arrives — `agent_message_chunk`,
//!   `agent_thought_chunk`, `tool_call`, `tool_call_update`, `plan`, anything else;
//! - `session/request_permission` answered by the host callback, or by default
//!   with the first `allow*` option (an unanswered request hangs the turn forever);
//! - `session/cancel` to stop a running turn;
//! - an agent that exits mid-turn ends the turn with [`AcpError::AgentExited`].
//!
//! Only `agent_message_chunk` text forms [`PromptResult::text`]; thoughts and tool
//! narration are kept in the update stream but never mixed into the reply.

use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use process_wrap::tokio::{ChildWrapper, CommandWrap};
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{ChildStdin, Command};
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio_util::sync::CancellationToken;

/// The marker [`AcpClient::generate`] appends, naming the latest user turn as
/// superseding everything earlier in the (stateful) session — same text as Go.
pub const SUPERSEDES_MARKER: &str = "SUPERSEDES-ALL-PRIOR:";

/// Errors from the ACP client.
#[derive(Debug, thiserror::Error)]
pub enum AcpError {
    #[error("toolnexus: acp: {0}")]
    Config(String),
    #[error("toolnexus: acp: start {command:?}: {source}")]
    Spawn {
        command: String,
        source: std::io::Error,
    },
    #[error("toolnexus: acp: {method} timed out after {waited:?}")]
    Timeout { method: String, waited: Duration },
    #[error("toolnexus: acp: turn hung waiting on session/request_permission for {0:?}")]
    PermissionTimeout(Duration),
    #[error("acp error {code}: {message}")]
    Rpc { code: i64, message: String },
    #[error("toolnexus: acp: agent exited{}", .code.map(|c| format!(" with code {c}")).unwrap_or_default())]
    AgentExited { code: Option<i32> },
    #[error("toolnexus: acp: {0}")]
    Protocol(String),
    #[error("toolnexus: acp: io: {0}")]
    Io(#[from] std::io::Error),
    /// The turn was cancelled by the host (`session/cancel` was sent).
    #[error("toolnexus: acp: turn cancelled")]
    Cancelled,
}

/// One permission option the agent offers (`allow_once`, `allow_always`,
/// `reject_once`, `reject_always`, ...).
#[derive(Debug, Clone, PartialEq)]
pub struct PermissionOption {
    pub option_id: String,
    pub name: String,
    pub kind: String,
}

/// A `session/request_permission` from the agent.
#[derive(Debug, Clone)]
pub struct PermissionRequest {
    pub session_id: String,
    /// The tool call the agent wants to make (raw ACP `toolCall` object).
    pub tool_call: Value,
    pub options: Vec<PermissionOption>,
}

impl PermissionRequest {
    /// The first option whose kind starts with `allow` — the default answer.
    pub fn first_allow(&self) -> Option<&PermissionOption> {
        self.options.iter().find(|o| o.kind.starts_with("allow"))
    }
}

/// The host's answer to a permission request.
#[derive(Debug, Clone, PartialEq)]
pub enum PermissionOutcome {
    /// Answer with this option id. An empty id behaves like [`Self::Reject`].
    Selected(String),
    Cancelled,
    /// No decision (the Go hook's error / empty answer): the first `reject*`
    /// option, else `cancelled`.
    Reject,
}

/// Host observer of EVERY `session/update`: receives the raw `params` object
/// (`{sessionId, update}`), during a turn or not. Called on the read loop, so
/// it must not block.
pub type UpdateHandler = Arc<dyn Fn(&Value) + Send + Sync>;

/// One event of a streamed turn ([`AcpClient::generate_stream`]). Tool events
/// carry `via: "acp"`: the AGENT ran these tools, a host loop must not.
#[derive(Debug, Clone, PartialEq)]
pub enum AcpStreamEvent {
    /// A reply text delta (`agent_message_chunk` only).
    Text { delta: String },
    ToolCall {
        id: String,
        name: String,
        args: Value,
        via: &'static str,
    },
    ToolResult {
        id: String,
        name: String,
        output: String,
        is_error: bool,
        via: &'static str,
    },
}

/// Async host permission handler.
pub type PermissionHandler = Arc<
    dyn Fn(PermissionRequest) -> Pin<Box<dyn Future<Output = PermissionOutcome> + Send>>
        + Send
        + Sync,
>;

/// Wrap an async closure as a [`PermissionHandler`].
pub fn permission_handler<F, Fut>(f: F) -> PermissionHandler
where
    F: Fn(PermissionRequest) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = PermissionOutcome> + Send + 'static,
{
    Arc::new(move |r| Box::pin(f(r)))
}

/// One `session/update`. `kind` is the ACP `sessionUpdate` discriminator
/// (`agent_message_chunk`, `agent_thought_chunk`, `tool_call`,
/// `tool_call_update`, `plan`, ...); `update` is the raw object.
#[derive(Debug, Clone)]
pub struct SessionUpdate {
    pub kind: String,
    pub update: Value,
}

impl SessionUpdate {
    /// The text of a message or thought chunk (`content.text`), if any.
    pub fn text(&self) -> Option<&str> {
        self.update.get("content")?.get("text")?.as_str()
    }
}

/// The outcome of one prompt turn.
#[derive(Debug, Clone)]
pub struct PromptResult {
    /// Concatenated `agent_message_chunk` text only.
    pub text: String,
    /// The agent's `stopReason` (`end_turn`, `cancelled`, `max_tokens`, ...).
    pub stop_reason: String,
    /// Every update of the turn, in arrival order.
    pub updates: Vec<SessionUpdate>,
}

/// Configures the agent process and its session. Only `command` is required.
#[derive(Clone)]
pub struct AcpOptions {
    pub command: String,
    pub args: Vec<String>,
    /// Extra environment for the child (it inherits this process's environment).
    pub env: Vec<(String, String)>,
    /// Absolute working directory for `session/new`; default: the current dir.
    pub cwd: Option<PathBuf>,
    /// Negotiated in `initialize`; default 1.
    pub protocol_version: u32,
    /// Optional `session/set_mode` after `session/new`.
    pub mode: Option<String>,
    /// Bounds `initialize` — generous by default (120s) for `npx` cold starts.
    pub initialize_timeout: Duration,
    /// Bounds `session/new` and `session/set_mode`; default 30s.
    pub request_timeout: Duration,
    /// Bounds the host permission handler; on expiry the request is answered
    /// `cancelled` and the turn fails with [`AcpError::PermissionTimeout`]. Default 30s.
    pub permission_timeout: Duration,
    /// Host permission handler; `None` ⇒ the first `allow*` option.
    pub on_permission: Option<PermissionHandler>,
    /// Observer of every `session/update`; `None` ⇒ nothing is called.
    pub on_update: Option<UpdateHandler>,
    /// Wait between SIGTERM and SIGKILL of the agent's process group on close; default 2s.
    pub kill_grace: Duration,
}

impl AcpOptions {
    pub fn new(command: impl Into<String>) -> Self {
        Self {
            command: command.into(),
            args: Vec::new(),
            env: Vec::new(),
            cwd: None,
            protocol_version: 1,
            mode: None,
            initialize_timeout: Duration::from_secs(120),
            request_timeout: Duration::from_secs(30),
            permission_timeout: Duration::from_secs(30),
            on_permission: None,
            on_update: None,
            kill_grace: Duration::from_secs(2),
        }
    }
    pub fn args<I, S>(mut self, args: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.args = args.into_iter().map(Into::into).collect();
        self
    }
    pub fn env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env.push((key.into(), value.into()));
        self
    }
    pub fn cwd(mut self, cwd: impl Into<PathBuf>) -> Self {
        self.cwd = Some(cwd.into());
        self
    }
    pub fn mode(mut self, mode: impl Into<String>) -> Self {
        self.mode = Some(mode.into());
        self
    }
    pub fn on_update<F>(mut self, f: F) -> Self
    where
        F: Fn(&Value) + Send + Sync + 'static,
    {
        self.on_update = Some(Arc::new(f));
        self
    }

    pub fn on_permission(mut self, h: PermissionHandler) -> Self {
        self.on_permission = Some(h);
        self
    }
}

type Reply = Result<Value, AcpError>;

struct Shared {
    stdin: Mutex<Option<ChildStdin>>,
    pending: std::sync::Mutex<HashMap<String, oneshot::Sender<Reply>>>,
    /// Where the running turn's updates go (None between turns).
    sink: std::sync::Mutex<Option<mpsc::UnboundedSender<TurnEvent>>>,
    exited: std::sync::Mutex<Option<Option<i32>>>,
    /// The agent process (POSIX process-group leader / Windows Job Object).
    child: Mutex<Option<Box<dyn ChildWrapper>>>,
}

enum TurnEvent {
    Update(SessionUpdate),
    PermissionTimedOut(Duration),
}

/// One warm ACP session over one child agent process.
pub struct AcpClient {
    shared: Arc<Shared>,
    next_id: AtomicU64,
    session_id: String,
    turn: Mutex<()>,
    kill_grace: Duration,
    request_timeout: Duration,
    agent_info: Value,
    pid: Option<u32>,
}

impl AcpClient {
    /// Spawn the agent and complete `initialize` + `session/new` (+ `session/set_mode`).
    pub async fn load(opts: AcpOptions) -> Result<Self, AcpError> {
        if opts.command.is_empty() {
            return Err(AcpError::Config("AcpOptions.command is required".into()));
        }
        let cwd = match &opts.cwd {
            Some(c) => c.clone(),
            None => std::env::current_dir()?,
        };
        if !cwd.is_absolute() {
            return Err(AcpError::Config(format!(
                "cwd must be absolute, got {cwd:?}"
            )));
        }
        let mut cmd = Command::new(&opts.command);
        cmd.args(&opts.args)
            .envs(opts.env.iter().cloned())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        let mut wrap = CommandWrap::from(cmd);
        // Its own process group (POSIX) / Job Object (Windows), so close stops
        // everything it started — an npx adapter AND the agent it spawned.
        #[cfg(unix)]
        wrap.wrap(process_wrap::tokio::ProcessGroup::leader());
        #[cfg(windows)]
        wrap.wrap(process_wrap::tokio::JobObject);
        wrap.wrap(process_wrap::tokio::KillOnDrop);
        let mut child = wrap.spawn().map_err(|source| AcpError::Spawn {
            command: opts.command.clone(),
            source,
        })?;
        let pid = child.id();
        let stdin = child.stdin().take().expect("piped stdin");
        let stdout = child.stdout().take().expect("piped stdout");

        let shared = Arc::new(Shared {
            stdin: Mutex::new(Some(stdin)),
            pending: std::sync::Mutex::new(HashMap::new()),
            sink: std::sync::Mutex::new(None),
            exited: std::sync::Mutex::new(None),
            child: Mutex::new(Some(child)),
        });
        tokio::spawn(read_loop(
            shared.clone(),
            BufReader::new(stdout),
            opts.on_permission.clone(),
            opts.on_update.clone(),
            opts.permission_timeout,
        ));

        let mut client = AcpClient {
            shared,
            next_id: AtomicU64::new(0),
            session_id: String::new(),
            turn: Mutex::new(()),
            kill_grace: opts.kill_grace,
            request_timeout: opts.request_timeout,
            agent_info: Value::Null,
            pid,
        };
        let init = client
            .call(
                "initialize",
                json!({
                    "protocolVersion": opts.protocol_version,
                    "clientCapabilities": {"fs": {"readTextFile": false, "writeTextFile": false}},
                }),
                opts.initialize_timeout,
            )
            .await;
        let init = match init {
            Ok(v) => v,
            Err(e) => {
                client.close().await;
                return Err(e);
            }
        };
        client.agent_info = init;
        let sn = client
            .call(
                "session/new",
                json!({"cwd": cwd.to_string_lossy(), "mcpServers": []}),
                opts.request_timeout,
            )
            .await;
        let sid = match sn.map(|v| {
            v.get("sessionId")
                .and_then(Value::as_str)
                .map(str::to_owned)
        }) {
            Ok(Some(s)) if !s.is_empty() => s,
            Ok(_) => {
                client.close().await;
                return Err(AcpError::Protocol(
                    "session/new: no sessionId in response".into(),
                ));
            }
            Err(e) => {
                client.close().await;
                return Err(e);
            }
        };
        client.session_id = sid;
        if let Some(mode) = &opts.mode {
            let r = client
                .call(
                    "session/set_mode",
                    json!({"sessionId": client.session_id, "modeId": mode}),
                    opts.request_timeout,
                )
                .await;
            if let Err(e) = r {
                client.close().await;
                return Err(e);
            }
        }
        Ok(client)
    }

    /// The agent's process id (on unix also its process-group id).
    pub fn pid(&self) -> Option<u32> {
        self.pid
    }

    /// The session id from `session/new`.
    pub fn session_id(&self) -> &str {
        &self.session_id
    }

    /// The agent's `initialize` result (protocol version, capabilities, agentInfo).
    pub fn agent_info(&self) -> &Value {
        &self.agent_info
    }

    fn new_id(&self) -> String {
        format!("c-{}", self.next_id.fetch_add(1, Ordering::Relaxed) + 1)
    }

    async fn call(&self, method: &str, params: Value, timeout: Duration) -> Reply {
        let id = self.new_id();
        let rx = self.register(&id)?;
        write_line(
            &self.shared,
            &json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}),
        )
        .await?;
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(r)) => r,
            Ok(Err(_)) => Err(self.exit_error()),
            Err(_) => {
                self.shared.pending.lock().unwrap().remove(&id);
                Err(AcpError::Timeout {
                    method: method.into(),
                    waited: timeout,
                })
            }
        }
    }

    fn register(&self, id: &str) -> Result<oneshot::Receiver<Reply>, AcpError> {
        if self.shared.exited.lock().unwrap().is_some() {
            return Err(self.exit_error());
        }
        let (tx, rx) = oneshot::channel();
        self.shared
            .pending
            .lock()
            .unwrap()
            .insert(id.to_owned(), tx);
        Ok(rx)
    }

    fn exit_error(&self) -> AcpError {
        AcpError::AgentExited {
            code: self.shared.exited.lock().unwrap().flatten(),
        }
    }

    /// Start one turn. Updates arrive on the returned [`PromptStream`] as the
    /// agent sends them; [`PromptStream::finish`] gives the result. Turns on one
    /// session are serialized: a second `prompt` waits for the first to finish.
    pub async fn prompt(&self, text: &str) -> Result<PromptStream<'_>, AcpError> {
        self.prompt_blocks(vec![json!({"type": "text", "text": text})])
            .await
    }

    /// [`prompt`](Self::prompt) with raw ACP content blocks.
    pub async fn prompt_blocks(&self, blocks: Vec<Value>) -> Result<PromptStream<'_>, AcpError> {
        let guard = self.turn.lock().await;
        let (tx, rx) = mpsc::unbounded_channel();
        *self.shared.sink.lock().unwrap() = Some(tx);
        let id = self.new_id();
        let reply = match self.register(&id) {
            Ok(r) => r,
            Err(e) => {
                *self.shared.sink.lock().unwrap() = None;
                return Err(e);
            }
        };
        let msg = json!({"jsonrpc": "2.0", "id": id, "method": "session/prompt",
            "params": {"sessionId": self.session_id, "prompt": blocks}});
        if let Err(e) = write_line(&self.shared, &msg).await {
            *self.shared.sink.lock().unwrap() = None;
            return Err(e);
        }
        Ok(PromptStream {
            client: self,
            _guard: guard,
            updates: rx,
            reply: Some(reply),
            finished: None,
            backlog: VecDeque::new(),
            seen: Vec::new(),
            text: String::new(),
            failure: None,
        })
    }

    /// Send one prompt and wait for the whole turn.
    pub async fn prompt_text(&self, text: &str) -> Result<PromptResult, AcpError> {
        self.prompt(text).await?.finish().await
    }

    /// The Go `Generate` seam: render `(role, content)` messages as
    /// `"role: content"` lines, append the supersedes marker naming the latest
    /// user turn, send one prompt, return the reply text.
    pub async fn generate(&self, messages: &[(String, String)]) -> Result<String, AcpError> {
        Ok(self.prompt_text(&render_prompt(messages)).await?.text)
    }

    /// [`generate`](Self::generate) bound to `cancel`: when the token fires
    /// mid-turn, `session/cancel` is sent and the turn's end awaited (bounded
    /// by `request_timeout`) so the next turn never lands on a busy session;
    /// the call then returns [`AcpError::Cancelled`].
    pub async fn generate_cancellable(
        &self,
        messages: &[(String, String)],
        cancel: &CancellationToken,
    ) -> Result<String, AcpError> {
        self.generate_stream(messages, cancel, |_| {}).await
    }

    /// [`generate_cancellable`](Self::generate_cancellable) that also reports
    /// the turn as it happens: message chunks as [`AcpStreamEvent::Text`], the
    /// agent's own tool calls as `ToolCall` / `ToolResult` (on a
    /// `tool_call_update` with status `completed` or `failed`), all `via: "acp"`.
    /// Thoughts and plans are not emitted (observe them with `on_update`).
    pub async fn generate_stream<F>(
        &self,
        messages: &[(String, String)],
        cancel: &CancellationToken,
        mut emit: F,
    ) -> Result<String, AcpError>
    where
        F: FnMut(AcpStreamEvent),
    {
        let mut turn = self.prompt(&render_prompt(messages)).await?;
        let mut names: HashMap<String, String> = HashMap::new();
        let mut cancelled = false;
        loop {
            let u = tokio::select! {
                u = turn.next() => u,
                _ = cancel.cancelled(), if !cancelled => {
                    cancelled = true;
                    let _ = self.cancel().await;
                    continue;
                }
            };
            let Some(u) = u else { break };
            if cancelled {
                continue;
            }
            let field = |k: &str| {
                u.update
                    .get(k)
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_owned()
            };
            let (id, title) = (field("toolCallId"), field("title"));
            match u.kind.as_str() {
                "agent_message_chunk" => {
                    if let Some(t) = u.text().filter(|t| !t.is_empty()) {
                        emit(AcpStreamEvent::Text {
                            delta: t.to_owned(),
                        });
                    }
                }
                "tool_call" => {
                    let name = if title.is_empty() {
                        field("kind")
                    } else {
                        title
                    };
                    names.insert(id.clone(), name.clone());
                    emit(AcpStreamEvent::ToolCall {
                        id,
                        name,
                        args: u.update.get("rawInput").cloned().unwrap_or(Value::Null),
                        via: "acp",
                    });
                }
                "tool_call_update" => {
                    let status = field("status");
                    if status != "completed" && status != "failed" {
                        continue;
                    }
                    let name = if title.is_empty() {
                        names.get(&id).cloned().unwrap_or_default()
                    } else {
                        title
                    };
                    emit(AcpStreamEvent::ToolResult {
                        id,
                        name,
                        output: tool_content_text(u.update.get("content").unwrap_or(&Value::Null)),
                        is_error: status == "failed",
                        via: "acp",
                    });
                }
                _ => {}
            }
        }
        if cancelled {
            let _ = tokio::time::timeout(self.request_timeout, turn.finish()).await;
            return Err(AcpError::Cancelled);
        }
        Ok(turn.finish().await?.text)
    }

    /// Ask the agent to stop the running turn (`session/cancel`). The turn then
    /// finishes with stop reason `cancelled`.
    pub async fn cancel(&self) -> Result<(), AcpError> {
        write_line(&self.shared, &json!({"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": self.session_id}})).await
    }

    /// Stop the agent: close stdin, SIGTERM its process group, wait `kill_grace`,
    /// then SIGKILL. Idempotent.
    pub async fn close(&self) {
        self.shared.stdin.lock().await.take();
        let Some(mut child) = self.shared.child.lock().await.take() else {
            return;
        };
        #[cfg(unix)]
        if let Some(pid) = child.id() {
            // ask the whole group first; insist after the grace window
            unsafe { libc::killpg(pid as i32, libc::SIGTERM) };
            if tokio::time::timeout(self.kill_grace, child.wait())
                .await
                .is_ok()
            {
                return;
            }
        }
        // POSIX: SIGKILL to the group. Windows: terminate the Job Object (no
        // graceful signal exists there).
        let _ = child.start_kill();
        let _ = child.wait().await;
    }
}

/// A running turn: read updates with [`next`](Self::next), then [`finish`](Self::finish).
pub struct PromptStream<'a> {
    client: &'a AcpClient,
    _guard: tokio::sync::MutexGuard<'a, ()>,
    updates: mpsc::UnboundedReceiver<TurnEvent>,
    reply: Option<oneshot::Receiver<Reply>>,
    finished: Option<Reply>,
    backlog: VecDeque<SessionUpdate>,
    seen: Vec<SessionUpdate>,
    text: String,
    failure: Option<AcpError>,
}

impl PromptStream<'_> {
    fn record(&mut self, u: &SessionUpdate) {
        if u.kind == "agent_message_chunk" {
            if let Some(t) = u.text() {
                self.text.push_str(t);
            }
        }
        self.seen.push(u.clone());
    }

    fn absorb(&mut self, ev: TurnEvent) -> Option<SessionUpdate> {
        match ev {
            TurnEvent::Update(u) => {
                self.record(&u);
                Some(u)
            }
            TurnEvent::PermissionTimedOut(d) => {
                self.failure.get_or_insert(AcpError::PermissionTimeout(d));
                None
            }
        }
    }

    /// The next update, or `None` once the turn has ended.
    pub async fn next(&mut self) -> Option<SessionUpdate> {
        loop {
            if let Some(u) = self.backlog.pop_front() {
                return Some(u);
            }
            let reply = self.reply.as_mut()?;
            tokio::select! {
                biased;
                ev = self.updates.recv() => {
                    if let Some(ev) = ev {
                        if let Some(u) = self.absorb(ev) {
                            return Some(u);
                        }
                    }
                }
                r = reply => {
                    self.reply = None;
                    self.finished = Some(r.unwrap_or_else(|_| Err(self.client.exit_error())));
                    // updates written before the reply line are already queued
                    while let Ok(ev) = self.updates.try_recv() {
                        if let Some(u) = self.absorb(ev) {
                            self.backlog.push_back(u);
                        }
                    }
                }
            }
        }
    }

    /// Wait for the turn to end and return its result.
    pub async fn finish(mut self) -> Result<PromptResult, AcpError> {
        while self.next().await.is_some() {}
        if let Some(e) = self.failure.take() {
            return Err(e);
        }
        let v = self.finished.take().expect("turn finished")?;
        let stop_reason = v
            .get("stopReason")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        Ok(PromptResult {
            text: std::mem::take(&mut self.text),
            stop_reason,
            updates: std::mem::take(&mut self.seen),
        })
    }
}

impl Drop for PromptStream<'_> {
    fn drop(&mut self) {
        *self.client.shared.sink.lock().unwrap() = None;
    }
}

async fn write_line(shared: &Shared, v: &Value) -> Result<(), AcpError> {
    let mut line = serde_json::to_vec(v).map_err(|e| AcpError::Protocol(e.to_string()))?;
    line.push(b'\n');
    let mut stdin = shared.stdin.lock().await;
    let w = stdin.as_mut().ok_or(AcpError::AgentExited { code: None })?;
    w.write_all(&line).await?;
    w.flush().await?;
    Ok(())
}

async fn read_loop(
    shared: Arc<Shared>,
    stdout: BufReader<tokio::process::ChildStdout>,
    on_permission: Option<PermissionHandler>,
    on_update: Option<UpdateHandler>,
    permission_timeout: Duration,
) {
    let mut lines = stdout.lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let Ok(msg) = serde_json::from_str::<Value>(line.trim()) else {
            continue;
        };
        let method = msg.get("method").and_then(Value::as_str);
        let id = msg.get("id").cloned().filter(|v| !v.is_null());
        match (method, id) {
            (None, Some(id)) => {
                let key = match &id {
                    Value::String(s) => s.clone(),
                    other => other.to_string(),
                };
                if let Some(tx) = shared.pending.lock().unwrap().remove(&key) {
                    let r = match msg.get("error") {
                        Some(e) => Err(AcpError::Rpc {
                            code: e.get("code").and_then(Value::as_i64).unwrap_or(0),
                            message: e
                                .get("message")
                                .and_then(Value::as_str)
                                .unwrap_or("")
                                .to_owned(),
                        }),
                        None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
                    };
                    let _ = tx.send(r);
                }
            }
            (Some("session/update"), _) => {
                if let Some(h) = &on_update {
                    h(msg.get("params").unwrap_or(&Value::Null));
                }
                let update = msg
                    .pointer("/params/update")
                    .cloned()
                    .unwrap_or(Value::Null);
                let kind = update
                    .get("sessionUpdate")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_owned();
                if let Some(tx) = shared.sink.lock().unwrap().as_ref() {
                    let _ = tx.send(TurnEvent::Update(SessionUpdate { kind, update }));
                }
            }
            (Some("session/request_permission"), Some(id)) => {
                // Answered off the read loop, so a slow host callback never
                // stalls the updates and replies behind it.
                let req = parse_permission(&msg);
                let options = req.options.clone();
                let shared = shared.clone();
                let handler = on_permission.clone();
                tokio::spawn(async move {
                    let outcome = match handler {
                        None => req
                            .first_allow()
                            .map(|o| PermissionOutcome::Selected(o.option_id.clone()))
                            .unwrap_or(PermissionOutcome::Cancelled),
                        Some(h) => match tokio::time::timeout(permission_timeout, h(req)).await {
                            Ok(o) => o,
                            Err(_) => {
                                if let Some(tx) = shared.sink.lock().unwrap().as_ref() {
                                    let _ =
                                        tx.send(TurnEvent::PermissionTimedOut(permission_timeout));
                                }
                                PermissionOutcome::Cancelled
                            }
                        },
                    };
                    let outcome = match outcome {
                        PermissionOutcome::Selected(o) if o.is_empty() => first_reject(&options),
                        PermissionOutcome::Reject => first_reject(&options),
                        o => o,
                    };
                    let outcome = match outcome {
                        PermissionOutcome::Selected(o) => {
                            json!({"outcome": "selected", "optionId": o})
                        }
                        _ => json!({"outcome": "cancelled"}),
                    };
                    let _ = write_line(
                        &shared,
                        &json!({"jsonrpc": "2.0", "id": id, "result": {"outcome": outcome}}),
                    )
                    .await;
                });
            }
            (Some(_), Some(id)) => {
                // A request we do not serve (e.g. fs/* — declared unsupported):
                // answer method-not-found so the agent is not left waiting.
                let _ = write_line(&shared, &json!({"jsonrpc": "2.0", "id": id, "error": {"code": -32601, "message": "method not supported by client"}})).await;
            }
            _ => {}
        }
    }
    // EOF: the agent exited (or closed stdout). Collect its exit code when it
    // has one within a moment, then fail everything still waiting.
    let mut code = None;
    if let Some(child) = shared.child.lock().await.as_mut() {
        if let Ok(Ok(status)) = tokio::time::timeout(Duration::from_millis(500), child.wait()).await
        {
            code = status.code();
        }
    }
    *shared.exited.lock().unwrap() = Some(code);
    let waiting: Vec<_> = shared.pending.lock().unwrap().drain().collect();
    for (_, tx) in waiting {
        let _ = tx.send(Err(AcpError::AgentExited { code }));
    }
}

fn first_reject(options: &[PermissionOption]) -> PermissionOutcome {
    options
        .iter()
        .find(|o| o.kind.starts_with("reject"))
        .map(|o| PermissionOutcome::Selected(o.option_id.clone()))
        .unwrap_or(PermissionOutcome::Cancelled)
}

/// The text of a tool call's `content` (ACP tool content
/// `[{type:"content", content:{type:"text", text}}]`, or bare text blocks), joined by newlines.
fn tool_content_text(v: &Value) -> String {
    match v {
        Value::Array(items) => items
            .iter()
            .map(tool_content_text)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>()
            .join("\n"),
        Value::Object(m) => {
            if let Some(t) = m.get("text").and_then(Value::as_str) {
                t.to_owned()
            } else if let Some(c) = m.get("content") {
                tool_content_text(c)
            } else {
                String::new()
            }
        }
        _ => String::new(),
    }
}

fn parse_permission(msg: &Value) -> PermissionRequest {
    let p = msg.get("params").cloned().unwrap_or(Value::Null);
    let options = p
        .get("options")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .map(|o| PermissionOption {
                    option_id: o
                        .get("optionId")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned(),
                    name: o
                        .get("name")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned(),
                    kind: o
                        .get("kind")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_owned(),
                })
                .collect()
        })
        .unwrap_or_default();
    PermissionRequest {
        session_id: p
            .get("sessionId")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned(),
        tool_call: p.get("toolCall").cloned().unwrap_or(Value::Null),
        options,
    }
}

/// Render messages as Go's `renderACPPrompt` does: `"role: content"` lines,
/// then a blank line and the supersedes marker naming the latest user turn.
pub fn render_prompt(messages: &[(String, String)]) -> String {
    let mut out = String::new();
    let mut last_user = String::new();
    for (role, content) in messages {
        if role.is_empty() && content.is_empty() {
            continue;
        }
        out.push_str(&format!("{role}: {content}\n"));
        if role == "user" && !content.is_empty() {
            last_user = content.clone();
        }
    }
    if last_user.is_empty() {
        if let Some((_, c)) = messages.last() {
            last_user = c.clone();
        }
    }
    out.push_str(&format!("\n{SUPERSEDES_MARKER} {last_user}"));
    out
}
