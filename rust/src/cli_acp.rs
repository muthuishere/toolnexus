//! CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary") — port of
//! `golang/cli_acp.go`. Behind the `serve` feature.
//!
//! On the first model call the toolkit is served as a streamable-HTTP MCP
//! server on `127.0.0.1:<ephemeral>/mcp`, guarded by a per-session bearer
//! token, and handed to the agent in `session/new` `mcpServers` (http when the
//! agent advertises `mcpCapabilities.http`, else a stdio bridge; a project MCP
//! file in a temp cwd for agents that ignore session servers). The agent calls
//! the tools natively; toolnexus hooks run at the server boundary. Every
//! permission request is decided per `toolCallId`: tools served by our server
//! are allowed, everything else (the agent's own shell, edit, fetch …) rejected.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::convert::Infallible;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::{Method, Request, Response, StatusCode};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::acp::{
    AcpClient, AcpError, AcpMcpServer, AcpOptions, PermissionOutcome, permission_handler,
};
use crate::cli::{
    CLI_MCP_TOKEN_ENV, CLI_SERVED_SKILL_TOOL_NAME, CliClient, CliShared, cli_is_served_call,
    cli_with_system_hint, expand_cli_env, look_path, write_private,
};
use crate::client::{
    AfterToolEvent, BeforeToolEvent, EmitFn, Hooks, InProcessRequest, InProcessResponse,
    acp_messages,
};
use crate::mcp_serve::{McpServeConfig, mcp_http_service};
use crate::tool::{Tool, ToolResult, ToolSource};
use crate::toolkit::Toolkit;

const SERVER_NAME: &str = "toolnexus";

/// One answered `session/request_permission`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CliPermissionDecision {
    pub tool_call_id: String,
    pub title: String,
    pub allowed: bool,
}

/// A read-only view of the live ACP session. The bearer token is never
/// exposed: header and env values in `mcp_servers_sent` are redacted.
#[derive(Debug, Clone, Default)]
pub struct AcpSessionInfo {
    pub url: String,
    pub mcp_servers_sent: Vec<Value>,
    pub permissions: Vec<CliPermissionDecision>,
    pub served_calls: Vec<String>,
    /// The temp session cwd (project-MCP-file styles), else `None`.
    pub session_cwd: Option<PathBuf>,
}

#[derive(Default)]
struct PermState {
    calls: HashMap<String, String>,
    decisions: HashMap<String, bool>,
    log: Vec<CliPermissionDecision>,
}

pub(crate) struct CliAcpSession {
    client: Arc<AcpClient>,
    url: String,
    stop: CancellationToken,
    temp_dir: Option<PathBuf>,
    hint: String,
    timeout_ms: u64,
    perms: Arc<Mutex<PermState>>,
    ran: Arc<Mutex<Vec<String>>>,
}

impl CliClient {
    /// The live ACP session's info (`None` when the one-shot transport is in
    /// use or no call has opened a session yet).
    pub async fn acp_session(&self) -> Option<AcpSessionInfo> {
        let s = self.shared.acp.lock().await.clone()?;
        Some(AcpSessionInfo {
            url: s.url.clone(),
            mcp_servers_sent: redact(s.client.mcp_servers_sent()),
            permissions: s.perms.lock().unwrap().log.clone(),
            served_calls: s.ran.lock().unwrap().clone(),
            session_cwd: s.temp_dir.clone(),
        })
    }
}

fn redact(sent: &[Value]) -> Vec<Value> {
    let mut out = sent.to_vec();
    for e in &mut out {
        for k in ["headers", "env"] {
            if let Some(Value::Array(a)) = e.get_mut(k) {
                for nv in a {
                    if nv.is_object() {
                        nv["value"] = json!("[redacted]");
                    }
                }
            }
        }
    }
    out
}

fn random_token() -> String {
    // 32 random bytes from the OS CSPRNG, hex.
    let mut bytes = [0u8; 32];
    #[cfg(unix)]
    let filled = {
        use std::io::Read;
        std::fs::File::open("/dev/urandom")
            .and_then(|mut f| f.read_exact(&mut bytes))
            .is_ok()
    };
    #[cfg(not(unix))]
    let filled = false;
    if !filled {
        // uuid v4 is drawn from the OS CSPRNG too (getrandom)
        for c in bytes.chunks_mut(16) {
            let u = uuid::Uuid::new_v4();
            c.copy_from_slice(&u.as_bytes()[..c.len()]);
        }
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The stderr tail an agent wrote, for error messages.
#[derive(Clone, Default)]
struct Tail(Arc<Mutex<Vec<u8>>>);

impl Tail {
    fn push(&self, b: &[u8]) {
        let mut v = self.0.lock().unwrap();
        v.extend_from_slice(b);
        if v.len() > 4096 {
            let cut = v.len() - 4096;
            v.drain(..cut);
        }
    }
    fn text(&self) -> String {
        String::from_utf8_lossy(&self.0.lock().unwrap())
            .trim()
            .to_owned()
    }
}

impl CliAcpSession {
    /// Open the session. `Err((launch, message))`: `launch` is true when the
    /// agent failed to start or to answer `initialize` (Auto may fall back).
    pub(crate) async fn open(c: &Arc<CliShared>) -> Result<Self, (bool, String)> {
        let argv = &c.acp_argv;
        let Some(bin) = look_path(&argv[0]) else {
            return Err((true, format!("cli: command not found: {}", argv[0])));
        };
        let token = random_token();
        let perms = Arc::new(Mutex::new(PermState::default()));
        let ran = Arc::new(Mutex::new(Vec::new()));
        let tools = served_tools(
            c.opts.toolkit.as_ref(),
            &c.opts.in_process.hooks,
            ran.clone(),
        );
        let served: Arc<HashSet<String>> = Arc::new(tools.iter().map(|t| t.name.clone()).collect());
        let stop = CancellationToken::new();
        let url = serve(tools, &token, stop.clone())
            .await
            .map_err(|e| (false, e))?;

        let mut temp_dir: Option<PathBuf> = None;
        let cleanup = |stop: &CancellationToken, temp_dir: &Option<PathBuf>| {
            stop.cancel();
            if let Some(d) = temp_dir {
                let _ = std::fs::remove_dir_all(d);
            }
        };

        let mut cwd = c
            .opts
            .cwd
            .clone()
            .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
        if let Ok(abs) = std::path::absolute(&cwd) {
            cwd = abs;
        }
        let mut servers = Vec::new();
        let project = c
            .style
            .acp
            .as_ref()
            .map(|a| a.project_mcp_file.clone())
            .filter(|f| !f.is_empty() && c.opts.acp_command.is_empty());
        if let Some(file) = project {
            let dir = std::env::temp_dir()
                .join(format!("toolnexus-acp-{}", uuid::Uuid::new_v4().simple()));
            let made = std::fs::create_dir_all(&dir);
            temp_dir = Some(dir.clone());
            let f = dir.join(&file);
            let body = json!({"mcpServers": {SERVER_NAME: {
                "type": "http", "url": url, "headers": {"Authorization": format!("Bearer {token}")}
            }}});
            let wrote = made.and_then(|_| {
                if let Some(p) = f.parent() {
                    std::fs::create_dir_all(p)?;
                }
                write_private(&f, body.to_string().as_bytes())
            });
            if let Err(e) = wrote {
                cleanup(&stop, &temp_dir);
                return Err((false, format!("cli: acp: {e}")));
            }
            cwd = dir;
        } else {
            let mut entry = AcpMcpServer {
                name: SERVER_NAME.into(),
                url: url.clone(),
                headers: BTreeMap::from([("Authorization".into(), format!("Bearer {token}"))]),
                ..Default::default()
            };
            let mut bridge = c.opts.bridge_command.clone();
            if bridge.is_empty() {
                if let Some(p) = look_path("toolnexus") {
                    bridge = vec![p.to_string_lossy().into_owned(), "mcp-bridge".into()];
                }
            }
            if !bridge.is_empty() {
                entry.command = bridge[0].clone();
                entry.args = bridge[1..].to_vec();
                entry.args.push(url.clone());
                entry.env = BTreeMap::from([(CLI_MCP_TOKEN_ENV.into(), token.clone())]);
            }
            servers.push(entry);
        }

        let timeout = Duration::from_millis(c.timeout_ms);
        let tail = Tail::default();
        let tail2 = tail.clone();
        let perms_u = perms.clone();
        let user_update = c.opts.on_acp_update.clone();
        let mut o = AcpOptions::new(bin.to_string_lossy());
        o.args = argv[1..].to_vec();
        o.env = expand_cli_env(&c.env);
        o.cwd = Some(cwd);
        o.initialize_timeout = timeout;
        o.request_timeout = timeout;
        o.permission_timeout = timeout;
        o.mcp_servers = servers;
        o.model = Some(c.opts.in_process.model.clone()).filter(|m| !m.is_empty());
        o.stderr = Some(Arc::new(move |b: &[u8]| tail2.push(b)));
        o.on_update = Some(Arc::new(move |params: &Value| {
            remember_title(&perms_u, params);
            if let Some(h) = &user_update {
                h(params);
            }
        }));
        let perms_p = perms.clone();
        o.on_permission = Some(permission_handler(move |req| {
            let perms = perms_p.clone();
            let served = served.clone();
            async move { decide(&perms, &served, &req) }
        }));
        match AcpClient::load_phased(o).await {
            Ok(client) => Ok(CliAcpSession {
                client: Arc::new(client),
                url,
                stop,
                temp_dir,
                hint: c
                    .style
                    .acp
                    .as_ref()
                    .map(|a| a.system_hint.clone())
                    .unwrap_or_default(),
                timeout_ms: c.timeout_ms,
                perms,
                ran,
            }),
            Err((launch, e)) => {
                cleanup(&stop, &temp_dir);
                let mut msg = e.to_string().replacen("toolnexus: acp:", "cli: acp:", 1);
                if !msg.starts_with("cli: acp:") {
                    msg = format!("cli: acp: {msg}");
                }
                let t = tail.text();
                if !t.is_empty() {
                    msg = format!("{msg}: {t}");
                }
                Err((launch, msg))
            }
        }
    }

    pub(crate) async fn close(&self) {
        self.client.close().await;
        self.stop.cancel();
        if let Some(d) = &self.temp_dir {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    pub(crate) async fn generate(
        &self,
        cancel: CancellationToken,
        req: InProcessRequest,
        emit: EmitFn,
    ) -> Result<InProcessResponse, String> {
        let mut req = req;
        req.messages = cli_with_system_hint(&req.messages, &self.hint);
        let msgs = acp_messages(&req);
        let turn = self.client.generate_stream(&msgs, &cancel, |ev| {
            emit(crate::client::acp_bridge::to_stream_event(ev))
        });
        let text = match tokio::time::timeout(Duration::from_millis(self.timeout_ms), turn).await {
            Ok(Ok(t)) => t,
            Ok(Err(AcpError::PermissionTimeout(_))) | Err(_) => {
                let _ = self.client.cancel().await;
                return Err(format!("cli: timed out after {}ms", self.timeout_ms));
            }
            Ok(Err(e)) => return Err(e.to_string()),
        };
        Ok(InProcessResponse {
            content: text,
            ..Default::default()
        })
    }
}

fn remember_title(perms: &Mutex<PermState>, params: &Value) {
    let u = params.get("update").unwrap_or(&Value::Null);
    let kind = u.get("sessionUpdate").and_then(Value::as_str).unwrap_or("");
    let id = u.get("toolCallId").and_then(Value::as_str).unwrap_or("");
    let title = u.get("title").and_then(Value::as_str).unwrap_or("");
    if id.is_empty() || title.is_empty() || !(kind == "tool_call" || kind == "tool_call_update") {
        return;
    }
    perms
        .lock()
        .unwrap()
        .calls
        .entry(id.to_owned())
        .or_insert_with(|| title.to_owned());
}

/// Decide one permission request by its `toolCallId`, once per id.
fn decide(
    perms: &Mutex<PermState>,
    served: &HashSet<String>,
    req: &crate::acp::PermissionRequest,
) -> PermissionOutcome {
    let id = req
        .tool_call
        .get("toolCallId")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    let mut title = req
        .tool_call
        .get("title")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    let mut p = perms.lock().unwrap();
    if title.is_empty() {
        title = p.calls.get(&id).cloned().unwrap_or_default();
    }
    let allowed = match p.decisions.get(&id) {
        Some(a) => *a,
        None => {
            let a = !id.is_empty() && cli_is_served_call(&title, served.iter().map(String::as_str));
            if !id.is_empty() {
                p.decisions.insert(id.clone(), a);
            }
            a
        }
    };
    p.log.push(CliPermissionDecision {
        tool_call_id: id,
        title,
        allowed,
    });
    drop(p);
    if !allowed {
        return PermissionOutcome::Reject;
    }
    let pick = req
        .options
        .iter()
        .find(|o| o.kind == "allow_once")
        .or_else(|| req.first_allow());
    match pick {
        Some(o) => PermissionOutcome::Selected(o.option_id.clone()),
        None => PermissionOutcome::Reject,
    }
}

/// The toolkit as served to the agent: `skill` renamed to `load_skill` with the
/// skills prompt in its description; every call runs the before/after tool hooks.
fn served_tools(tk: Option<&Toolkit>, hooks: &Hooks, ran: Arc<Mutex<Vec<String>>>) -> Vec<Tool> {
    let Some(tk) = tk else {
        return Vec::new();
    };
    let skills_prompt = tk.skills_prompt();
    tk.tools()
        .into_iter()
        .map(|mut t| {
            let original = t.name.clone();
            if t.source == ToolSource::Skill && t.name == "skill" {
                t.name = CLI_SERVED_SKILL_TOOL_NAME.into();
                if !skills_prompt.is_empty() {
                    t.description = format!("{}\n\n{}", t.description, skills_prompt);
                }
            }
            let inner = t.execute.clone();
            let hints = t.hints;
            let served_name = t.name.clone();
            let hooks = hooks.clone();
            let ran = ran.clone();
            t.execute = Arc::new(move |args, ctx| {
                let inner = inner.clone();
                let hooks = hooks.clone();
                let ran = ran.clone();
                let name = original.clone();
                let served_name = served_name.clone();
                Box::pin(async move {
                    let mut args = args;
                    if let Some(h) = &hooks.before_tool {
                        let ev = BeforeToolEvent {
                            name: name.clone(),
                            args: args.clone(),
                            id: ctx.call_id.clone().unwrap_or_default(),
                            turn: 0,
                            hints,
                        };
                        match h(ev).await {
                            Err(e) => return ToolResult::error(e),
                            Ok(Some(ov)) => {
                                if let Some(r) = ov.result {
                                    return r;
                                }
                                if let Some(a) = ov.args {
                                    args = a;
                                }
                            }
                            Ok(None) => {}
                        }
                    }
                    ran.lock().unwrap().push(served_name);
                    let mut res = inner(args.clone(), ctx.clone()).await;
                    if let Some(h) = &hooks.after_tool {
                        let ev = AfterToolEvent {
                            name,
                            args,
                            result: res.clone(),
                            id: ctx.call_id.clone().unwrap_or_default(),
                            turn: 0,
                        };
                        match h(ev).await {
                            Err(e) => return ToolResult::error(e),
                            Ok(Some(ov)) => {
                                if let Some(r) = ov.result {
                                    res = r;
                                }
                            }
                            Ok(None) => {}
                        }
                    }
                    res
                })
            });
            t
        })
        .collect()
}

type Body = http_body_util::combinators::BoxBody<Bytes, Infallible>;

fn plain(status: StatusCode, text: &'static str) -> Response<Body> {
    let mut r = Response::new(Full::new(Bytes::from_static(text.as_bytes())).boxed());
    *r.status_mut() = status;
    r
}

fn same(a: &[u8], b: &[u8]) -> bool {
    // constant time in the length of the expected value
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Serve `tools` on `127.0.0.1:<ephemeral>/mcp` behind the bearer token.
async fn serve(tools: Vec<Tool>, token: &str, stop: CancellationToken) -> Result<String, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|e| format!("cli: acp: serve toolkit: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("cli: acp: serve toolkit: {e}"))?
        .port();
    let cfg = McpServeConfig {
        name: SERVER_NAME.into(),
        ..Default::default()
    };
    let mcp = Arc::new(mcp_http_service(tools, &cfg, None, stop.child_token()));
    let want: Arc<Vec<u8>> = Arc::new(format!("Bearer {token}").into_bytes());
    tokio::spawn(async move {
        loop {
            let conn = tokio::select! {
                _ = stop.cancelled() => return,
                c = listener.accept() => c,
            };
            let Ok((stream, _)) = conn else { continue };
            let (mcp, want, stop_conn) = (mcp.clone(), want.clone(), stop.clone());
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |req: Request<Incoming>| {
                    let (mcp, want) = (mcp.clone(), want.clone());
                    async move {
                        if req.uri().path() != "/mcp" {
                            return Ok::<_, Infallible>(plain(StatusCode::NOT_FOUND, "not found"));
                        }
                        let auth = req
                            .headers()
                            .get("authorization")
                            .map(|v| v.as_bytes())
                            .unwrap_or_default();
                        if !same(auth, &want) {
                            let mut r = plain(StatusCode::UNAUTHORIZED, "unauthorized");
                            r.headers_mut()
                                .insert("WWW-Authenticate", "Bearer".parse().unwrap());
                            return Ok(r);
                        }
                        // Stateless: no standalone SSE stream (GET) and no session
                        // to DELETE; an opened GET stream would stall the client.
                        if req.method() != Method::POST {
                            return Ok(plain(StatusCode::METHOD_NOT_ALLOWED, "method not allowed"));
                        }
                        Ok(mcp.handle(req).await)
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
    Ok(format!("http://127.0.0.1:{port}/mcp"))
}
