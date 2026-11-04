//! MCP source (SPEC §0 items 2–4, §2), on the official Rust SDK `rmcp`.
//!
//! Parse an `mcp.json` (`mcpServers` | `servers` | `mcp`, or a bare map), connect
//! to every enabled server — local stdio (its own process group / Job Object) or
//! remote streamable HTTP — list its tools and expose each as a uniform [`Tool`]
//! named `sanitize(server)_sanitize(tool)`. One failing server is isolated:
//! it is marked `failed` and the rest load. Port of `golang/mcp.go`.
//!
//! Each `tools/call` carries a progress token, and its timeout is an IDLE
//! timeout: a matching `notifications/progress` resets it (rmcp
//! `reset_timeout_on_progress`). There is no absolute cap.

use std::collections::{BTreeMap, HashMap};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use rmcp::model::{CallToolRequestParams, ClientRequest, ContentBlock, ServerResult};
use rmcp::service::{PeerRequestOptions, RunningService};
#[cfg(feature = "mcp-http")]
use rmcp::transport::StreamableHttpClientTransport;
use rmcp::transport::TokioChildProcess;
#[cfg(feature = "mcp-http")]
use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;
use rmcp::{RoleClient, ServiceExt};
use serde_json::{Map, Value, json};
use tokio::sync::Mutex;

use crate::tool::{ContentPart, Tool, ToolHints, ToolResult, ToolSource, sanitize};

mod direct;
mod elicit;
mod oauth;
mod resources;
#[cfg(feature = "mcp-http")]
pub mod sse;
mod wire;

pub use direct::parse_skill_uri;
pub use elicit::{answer_to_elicit_result, elicitation_to_request};
pub use oauth::{AuthConfig, MemoryTokenStore, OpenUrlFn, Token, TokenStore};
pub use wire::PROTOCOL_VERSION;

/// Default per-server timeout (connect, list, and the per-call idle timeout).
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

/// MCP config errors (a malformed config fails the parse, naming the server).
#[derive(Debug, thiserror::Error)]
pub enum McpError {
    #[error("read mcp config: {0}")]
    Read(#[from] std::io::Error),
    #[error("parse mcp config: {0}")]
    Parse(String),
    #[error("mcp server {server:?}: {message}")]
    Server { server: String, message: String },
}

/// One server entry. Local (stdio) and remote fields share the struct; the
/// transport is inferred (`type`, else `url` ⇒ remote).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ServerConfig {
    /// `"local"` | `"remote"` | `""` (inferred).
    pub kind: String,
    /// argv: `command` as a string or array, with `args` appended.
    pub command: Vec<String>,
    /// `environment` (or its alias `env`).
    pub env: BTreeMap<String, String>,
    pub cwd: Option<String>,
    pub url: Option<String>,
    /// Header values expand `${ENV_VAR}` at connect time; never logged.
    pub headers: BTreeMap<String, String>,
    pub enabled: Option<bool>,
    pub disabled: Option<bool>,
    /// Milliseconds; 0 ⇒ 30 s.
    pub timeout_ms: u64,
    /// Per-server allowlist on ORIGINAL tool names (empty ⇒ all; any true ⇒
    /// allowlist; only false ⇒ drop-list).
    pub tools: BTreeMap<String, bool>,
    /// OAuth (SPEC §2A.1); `None` ⇒ defaults (a 401 still triggers the flow).
    pub auth: Option<AuthConfig>,
    /// What this server is for (tool search, §2.8).
    pub description: Option<String>,
    /// Expose `search_tools` + `call_tool` instead of every tool (§2.8).
    pub tool_search: bool,
}

impl ServerConfig {
    pub fn is_remote(&self) -> bool {
        match self.kind.as_str() {
            "remote" => true,
            "local" => false,
            _ => self.url.as_deref().is_some_and(|u| !u.is_empty()),
        }
    }
    pub fn is_enabled(&self) -> bool {
        !(self.disabled == Some(true) || self.enabled == Some(false))
    }
    pub fn timeout(&self) -> Duration {
        if self.timeout_ms > 0 {
            Duration::from_millis(self.timeout_ms)
        } else {
            DEFAULT_TIMEOUT
        }
    }
}

/// Server name → config, ordered by name.
pub type McpConfig = BTreeMap<String, ServerConfig>;

/// Connection state of a server.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum McpStatus {
    Connected,
    Disabled,
    Failed,
}

impl McpStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Connected => "connected",
            Self::Disabled => "disabled",
            Self::Failed => "failed",
        }
    }
}

// ---------------------------------------------------------------------------
// parsing
// ---------------------------------------------------------------------------

/// Read and parse an MCP config file.
pub fn parse_mcp_config_file(path: impl AsRef<Path>) -> Result<McpConfig, McpError> {
    parse_mcp_config_str(&std::fs::read_to_string(path)?)
}

/// Parse MCP config JSON text.
pub fn parse_mcp_config_str(text: &str) -> Result<McpConfig, McpError> {
    let v: Value = serde_json::from_str(text).map_err(|e| McpError::Parse(e.to_string()))?;
    parse_mcp_config(&v)
}

/// Parse a parsed config object: `mcpServers` | `servers` | `mcp`, else the bare
/// map with the reserved sibling keys (builtins, agents, a2a, mcpServer) removed.
pub fn parse_mcp_config(v: &Value) -> Result<McpConfig, McpError> {
    let obj = v
        .as_object()
        .ok_or_else(|| McpError::Parse("config must be a JSON object".into()))?;
    for key in ["mcpServers", "servers", "mcp"] {
        if let Some(servers) = obj.get(key) {
            return decode_servers(servers).map_err(|e| match e {
                McpError::Parse(m) => McpError::Parse(format!("{key:?}: {m}")),
                other => other,
            });
        }
    }
    let mut bare = obj.clone();
    for reserved in ["builtins", "agents", "a2a", "mcpServer"] {
        bare.remove(reserved);
    }
    decode_servers(&Value::Object(bare))
}

fn decode_servers(v: &Value) -> Result<McpConfig, McpError> {
    let mut cfg = McpConfig::new();
    let Some(map) = v.as_object() else {
        if v.is_null() {
            return Ok(cfg);
        }
        return Err(McpError::Parse("servers must be an object".into()));
    };
    for (name, body) in map {
        let err = |m: &str| McpError::Server {
            server: name.clone(),
            message: m.to_owned(),
        };
        let e = body
            .as_object()
            .ok_or_else(|| err("entry must be an object"))?;
        let s = |k: &str| e.get(k).and_then(Value::as_str).map(str::to_owned);
        let b = |k: &str| e.get(k).and_then(Value::as_bool);
        let str_map = |k: &str| -> BTreeMap<String, String> {
            e.get(k)
                .and_then(Value::as_object)
                .map(|m| {
                    m.iter()
                        .filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_owned())))
                        .collect()
                })
                .unwrap_or_default()
        };
        let mut sc = ServerConfig {
            kind: s("type").unwrap_or_default(),
            cwd: s("cwd"),
            url: s("url"),
            headers: str_map("headers"),
            enabled: b("enabled"),
            disabled: b("disabled"),
            timeout_ms: e.get("timeout").and_then(Value::as_u64).unwrap_or(0),
            tools: e
                .get("tools")
                .and_then(Value::as_object)
                .map(|m| {
                    m.iter()
                        .filter_map(|(k, v)| v.as_bool().map(|v| (k.clone(), v)))
                        .collect()
                })
                .unwrap_or_default(),
            auth: e
                .get("auth")
                .filter(|v| v.is_object())
                .map(AuthConfig::from_value),
            description: s("description"),
            tool_search: b("toolSearch").unwrap_or(false),
            ..Default::default()
        };
        let env = str_map("environment");
        sc.env = if env.is_empty() { str_map("env") } else { env };
        sc.command = normalize_command(e.get("command"), e.get("args"), sc.is_remote())
            .map_err(|m| err(&m))?;
        cfg.insert(name.clone(), sc);
    }
    Ok(cfg)
}

fn normalize_command(
    command: Option<&Value>,
    args: Option<&Value>,
    remote: bool,
) -> Result<Vec<String>, String> {
    let mut argv = match command {
        None | Some(Value::Null) => return Ok(Vec::new()),
        Some(Value::String(s)) if s.is_empty() => {
            return Err("invalid command: empty string".into());
        }
        Some(Value::String(s)) => vec![s.clone()],
        Some(Value::Array(a)) if a.is_empty() => return Err("invalid command: empty array".into()),
        Some(Value::Array(a)) => a
            .iter()
            .map(|v| v.as_str().map(str::to_owned))
            .collect::<Option<Vec<_>>>()
            .ok_or("invalid command: array elements must be strings")?,
        Some(_) => return Err("invalid command: must be a string or an array of strings".into()),
    };
    if remote {
        return Ok(argv);
    }
    match args {
        None | Some(Value::Null) => {}
        Some(Value::Array(a)) => {
            for v in a {
                argv.push(
                    v.as_str()
                        .ok_or("invalid args: elements must be strings")?
                        .to_owned(),
                );
            }
        }
        Some(_) => return Err("invalid args: must be an array of strings".into()),
    }
    Ok(argv)
}

/// `${ENV_VAR}` in header values ← the environment (unset ⇒ ""). Values never logged.
pub fn expand_env_headers(headers: &BTreeMap<String, String>) -> BTreeMap<String, String> {
    headers
        .iter()
        .map(|(k, v)| {
            let mut out = String::new();
            let mut rest = v.as_str();
            while let Some(i) = rest.find("${") {
                out.push_str(&rest[..i]);
                let after = &rest[i + 2..];
                match after.find('}') {
                    Some(j) if is_env_name(&after[..j]) => {
                        out.push_str(&std::env::var(&after[..j]).unwrap_or_default());
                        rest = &after[j + 1..];
                    }
                    _ => {
                        out.push_str("${");
                        rest = after;
                    }
                }
            }
            out.push_str(rest);
            (k.clone(), out)
        })
        .collect()
}

fn is_env_name(s: &str) -> bool {
    let mut c = s.chars();
    c.next()
        .is_some_and(|f| f.is_ascii_alphabetic() || f == '_')
        && c.all(|x| x.is_ascii_alphanumeric() || x == '_')
}

// ---------------------------------------------------------------------------
// loading
// ---------------------------------------------------------------------------

type Client = RunningService<RoleClient, elicit::Bridge>;

/// One connected server's peer, for [`McpSource::resource_tools`].
#[derive(Clone)]
pub(crate) struct ServerConn {
    pub(crate) peer: Arc<rmcp::Peer<RoleClient>>,
    pub(crate) timeout: Duration,
}

/// The connected servers and their tools.
pub struct McpSource {
    /// All servers' tools, servers in name order, each server's tools in list order.
    pub tools: Vec<Tool>,
    pub status: BTreeMap<String, McpStatus>,
    /// Why each failed server failed (diagnostic).
    pub errors: BTreeMap<String, String>,
    /// Skills served by 2026 servers' skills extension (§2.7); the toolkit
    /// adds them to the `skill` tool.
    pub skills: Vec<crate::skills::SkillDef>,
    clients: Mutex<Vec<Client>>,
    direct: Vec<Arc<direct::DirectServer>>,
    servers: BTreeMap<String, ServerConn>,
}

impl std::fmt::Debug for McpSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpSource")
            .field("tools", &self.tools)
            .field("status", &self.status)
            .finish_non_exhaustive()
    }
}

impl McpSource {
    /// Disconnect every server (stdio children are stopped as a group). Idempotent.
    pub async fn close(&self) {
        for c in self.clients.lock().await.drain(..) {
            let _ = c.cancel().await;
        }
        for d in &self.direct {
            d.close().await;
        }
    }

    /// The connected server names, sorted.
    pub fn server_names(&self) -> Vec<String> {
        self.servers.keys().cloned().collect()
    }

    /// `list_mcp_resources` + `read_mcp_resource` over the connected servers
    /// (opt-in; port of Go `McpSource.ResourceTools`). Both are read-only.
    pub fn resource_tools(&self) -> Vec<Tool> {
        resources::resource_tools(self.servers.clone())
    }
}

/// One server's outcome, reported by [`McpLoadOptions::on_server`] the moment
/// that server finishes — it does not wait for slower servers.
#[derive(Debug, Clone)]
pub struct McpServerResult {
    pub name: String,
    pub status: McpStatus,
    /// This server's converted tools (empty unless connected). They stay usable
    /// until the returned [`McpSource`] is closed.
    pub tools: Vec<Tool>,
    /// Why the server failed (`None` when connected or disabled).
    pub error: Option<String>,
}

/// Per-server readiness callback (calls are serialized; keep it short).
pub type OnServer = Arc<dyn Fn(McpServerResult) + Send + Sync>;

/// Options for [`load_mcp_with`].
#[derive(Clone, Default)]
pub struct McpLoadOptions {
    /// Called once per server as soon as it is connected, failed or found
    /// disabled, so a host can register a fast server's tools
    /// (`Toolkit::register`) while a slow one is still connecting.
    pub on_server: Option<OnServer>,
    /// §10 resolver for MCP elicitation: set ⇒ every client advertises the
    /// `elicitation` capability and bridges `elicitation/create` onto it
    /// (form → `kind:"input"`, URL → `kind:"authorization"`). `None` ⇒ not
    /// advertised, so a compliant server will not elicit.
    pub wait_for: Option<crate::client::WaitForFn>,
    /// Where OAuth tokens live (§2A.1a); `None` ⇒ in memory, per load.
    pub token_store: Option<Arc<dyn TokenStore>>,
    /// How `auto`-mode OAuth opens the authorization URL; `None` ⇒ the OS opener.
    pub open_url: Option<OpenUrlFn>,
    /// Toolkit `disableTools` names; on a `toolSearch` server they are also
    /// hidden from `search_tools` / `call_tool`.
    pub disable_tools: Vec<String>,
}

/// Connect to every enabled server concurrently. Never fails as a whole: a
/// server that cannot connect or list is `failed` and the rest load.
pub async fn load_mcp(config: &McpConfig) -> McpSource {
    load_mcp_with(config, McpLoadOptions::default()).await
}

/// [`load_mcp`] plus per-server readiness ([`McpLoadOptions::on_server`]). It
/// still returns only after every server has finished, with the same source.
pub async fn load_mcp_with(config: &McpConfig, opts: McpLoadOptions) -> McpSource {
    let report = |r: McpServerResult| {
        if let Some(cb) = &opts.on_server {
            cb(r);
        }
    };
    let ctx = direct::LoadCtx {
        wait_for: opts.wait_for.clone(),
        store: opts
            .token_store
            .clone()
            .unwrap_or_else(|| MemoryTokenStore::new() as Arc<dyn TokenStore>),
        open_url: opts.open_url.clone(),
        http: reqwest::Client::new(),
        disabled: opts.disable_tools.iter().cloned().collect(),
    };
    let mut set = tokio::task::JoinSet::new();
    let mut status = BTreeMap::new();
    for (name, cfg) in config {
        if !cfg.is_enabled() {
            status.insert(name.clone(), McpStatus::Disabled);
            report(McpServerResult {
                name: name.clone(),
                status: McpStatus::Disabled,
                tools: Vec::new(),
                error: None,
            });
            continue;
        }
        let (name, cfg) = (name.clone(), cfg.clone());
        let bridge = elicit::Bridge {
            wait_for: opts.wait_for.clone(),
        };
        let ctx = ctx.clone();
        set.spawn(async move {
            let r = match direct::probe(&name, &cfg, &ctx).await {
                Ok(direct::Probe::Direct(c)) => Ok(Loaded::Direct(c)),
                Ok(direct::Probe::Fallback { bearer }) => tokio::time::timeout(
                    cfg.timeout(),
                    connect_and_list(&name, &cfg, bridge, bearer),
                )
                .await
                .unwrap_or_else(|_| Err(format!("timed out after {}ms", cfg.timeout().as_millis())))
                .map(|(c, d)| Loaded::Sdk(c, d)),
                Err(e) => Err(e),
            };
            (name, cfg, r)
        });
    }
    let mut by_server: BTreeMap<String, Vec<Tool>> = BTreeMap::new();
    let mut errors = BTreeMap::new();
    let mut clients = Vec::new();
    let mut servers = BTreeMap::new();
    let mut direct_servers = Vec::new();
    let mut skills = Vec::new();
    while let Some(joined) = set.join_next().await {
        let Ok((name, cfg, r)) = joined else { continue };
        match r {
            Ok(Loaded::Direct(c)) => {
                direct_servers.push(c.server);
                skills.extend(c.skills);
                status.insert(name.clone(), McpStatus::Connected);
                report(McpServerResult {
                    name: name.clone(),
                    status: McpStatus::Connected,
                    tools: c.tools.clone(),
                    error: None,
                });
                by_server.insert(name, c.tools);
            }
            Ok(Loaded::Sdk(client, defs)) => {
                let peer = Arc::new(client.peer().clone());
                let tools: Vec<Tool> =
                    apply_tools_filter(&name, defs, &cfg.tools, |d| d.name.as_ref())
                        .into_iter()
                        .map(|def| convert_tool(&name, def, peer.clone(), cfg.timeout()))
                        .collect();
                clients.push(client);
                servers.insert(
                    name.clone(),
                    ServerConn {
                        peer,
                        timeout: cfg.timeout(),
                    },
                );
                status.insert(name.clone(), McpStatus::Connected);
                report(McpServerResult {
                    name: name.clone(),
                    status: McpStatus::Connected,
                    tools: tools.clone(),
                    error: None,
                });
                by_server.insert(name, tools);
            }
            Err(e) => {
                eprintln!("[toolnexus] MCP server {name:?} failed: {e}");
                status.insert(name.clone(), McpStatus::Failed);
                report(McpServerResult {
                    name: name.clone(),
                    status: McpStatus::Failed,
                    tools: Vec::new(),
                    error: Some(e.clone()),
                });
                errors.insert(name, e);
            }
        }
    }
    McpSource {
        tools: by_server.into_values().flatten().collect(),
        status,
        errors,
        skills,
        clients: Mutex::new(clients),
        direct: direct_servers,
        servers,
    }
}

enum Loaded {
    Direct(direct::Connected),
    Sdk(Client, Vec<rmcp::model::Tool>),
}

async fn connect_and_list(
    name: &str,
    cfg: &ServerConfig,
    bridge: elicit::Bridge,
    bearer: String,
) -> Result<(Client, Vec<rmcp::model::Tool>), String> {
    let client = if cfg.is_remote() {
        #[cfg(not(feature = "mcp-http"))]
        let _ = bearer;
        #[cfg(not(feature = "mcp-http"))]
        return Err(format!(
            "remote server {name:?}: built without the `mcp-http` feature"
        ));
        #[cfg(feature = "mcp-http")]
        {
            connect_remote(cfg, bridge, &bearer).await?
        }
    } else {
        let Some((prog, args)) = cfg.command.split_first() else {
            return Err(format!("local server {name:?} has no command"));
        };
        let mut cmd = tokio::process::Command::new(prog);
        cmd.args(args).envs(cfg.env.iter());
        if let Some(cwd) = &cfg.cwd {
            cmd.current_dir(cwd);
        }
        let mut wrap = process_wrap::tokio::CommandWrap::from(cmd);
        #[cfg(unix)]
        wrap.wrap(process_wrap::tokio::ProcessGroup::leader());
        #[cfg(windows)]
        wrap.wrap(process_wrap::tokio::JobObject);
        let transport = TokioChildProcess::builder(wrap)
            .stderr(std::process::Stdio::null())
            .spawn()
            .map(|(t, _)| t)
            .map_err(|e| format!("start {prog:?}: {e}"))?;
        bridge.serve(transport).await.map_err(|e| e.to_string())?
    };
    let defs = client
        .list_all_tools()
        .await
        .map_err(|e| format!("list tools failed: {e}"))?;
    Ok((client, defs))
}

fn apply_tools_filter<T>(
    server: &str,
    defs: Vec<T>,
    filter: &BTreeMap<String, bool>,
    name: impl Fn(&T) -> &str,
) -> Vec<T> {
    if filter.is_empty() {
        return defs;
    }
    let has_true = filter.values().any(|v| *v);
    for k in filter.keys() {
        if !defs.iter().any(|d| name(d) == k.as_str()) {
            eprintln!("[toolnexus] MCP server {server:?} tools filter name {k:?} matched no tool");
        }
    }
    defs.into_iter()
        .filter(|d| match filter.get(name(d)) {
            Some(v) if has_true => *v,
            None if has_true => false,
            Some(v) => *v,
            None => true,
        })
        .collect()
}

#[cfg(feature = "mcp-http")]
async fn connect_remote(
    cfg: &ServerConfig,
    bridge: elicit::Bridge,
    bearer: &str,
) -> Result<Client, String> {
    let url = cfg.url.clone().unwrap_or_default();
    let mut hm = http::HeaderMap::new();
    // OAuth on the frozen path (§2A.1a): rmcp takes headers only at connect,
    // so the token obtained during discovery is sent as a static header.
    if !bearer.is_empty() {
        let v = http::HeaderValue::try_from(format!("Bearer {bearer}"))
            .map_err(|_| "invalid bearer token".to_owned())?;
        hm.insert(http::header::AUTHORIZATION, v);
    }
    for (k, v) in expand_env_headers(&cfg.headers) {
        let (Ok(k), Ok(v)) = (
            http::HeaderName::try_from(k.as_str()),
            http::HeaderValue::try_from(v.as_str()),
        ) else {
            return Err(format!("invalid header {k:?}"));
        };
        hm.insert(k, v);
    }
    // Prefer streamable HTTP, fall back to legacy SSE (as Go does).
    let mut tc = StreamableHttpClientTransportConfig::with_uri(url.clone());
    for (k, v) in &hm {
        tc.custom_headers.insert(k.clone(), v.clone());
    }
    let transport = StreamableHttpClientTransport::with_client(reqwest::Client::new(), tc);
    let http_err = match bridge.clone().serve(transport).await {
        Ok(c) => return Ok(c),
        Err(e) => e.to_string(),
    };
    let sse = sse::SseClientTransport::connect(&url, hm)
        .await
        .map_err(|e| format!("streamable-http failed ({http_err}) and sse start failed: {e}"))?;
    bridge
        .serve(sse)
        .await
        .map_err(|e| format!("streamable-http failed ({http_err}) and sse start failed: {e}"))
}

/// MCP tool annotations → hints. No hint fields at all ⇒ `None` (unknown).
/// Absent fields follow the MCP spec defaults (readOnly false, destructive
/// true, openWorld true); read-only implies not destructive.
fn mcp_hints(def: &rmcp::model::Tool) -> Option<ToolHints> {
    hints_of(&serde_json::to_value(def).ok()?)
}

/// [`mcp_hints`] over a wire-shaped tool definition.
fn hints_of(v: &Value) -> Option<ToolHints> {
    let a = v.get("annotations")?;
    let get = |k: &str| a.get(k).and_then(Value::as_bool);
    let (ro, de, ow) = (
        get("readOnlyHint"),
        get("destructiveHint"),
        get("openWorldHint"),
    );
    if ro.is_none() && de.is_none() && ow.is_none() {
        return None;
    }
    let read_only = ro.unwrap_or(false);
    Some(ToolHints {
        read_only,
        destructive: de.unwrap_or(true) && !read_only,
        open_world: ow.unwrap_or(true),
    })
}

/// The tool's input schema, normalized like every port: `type: object`,
/// `properties` (default `{}`), `required` and `$defs` when present,
/// `additionalProperties: false`.
fn build_input_schema(input: &Map<String, Value>) -> Value {
    let mut s = Map::new();
    if let Some(d) = input
        .get("$defs")
        .filter(|d| d.as_object().is_some_and(|o| !o.is_empty()))
    {
        s.insert("$defs".into(), d.clone());
    }
    if let Some(r) = input
        .get("required")
        .filter(|r| r.as_array().is_some_and(|a| !a.is_empty()))
    {
        s.insert("required".into(), r.clone());
    }
    s.insert("type".into(), json!("object"));
    s.insert(
        "properties".into(),
        input
            .get("properties")
            .filter(|p| p.is_object())
            .cloned()
            .unwrap_or_else(|| json!({})),
    );
    s.insert("additionalProperties".into(), json!(false));
    Value::Object(s)
}

fn convert_tool(
    server: &str,
    def: rmcp::model::Tool,
    peer: Arc<rmcp::Peer<RoleClient>>,
    timeout: Duration,
) -> Tool {
    let original = def.name.to_string();
    let server = server.to_owned();
    Tool {
        concurrency_safe: None,
        name: format!("{}_{}", sanitize(&server), sanitize(&original)),
        description: def.description.as_deref().unwrap_or("").to_owned(),
        input_schema: build_input_schema(&def.input_schema),
        source: ToolSource::Mcp,
        hints: mcp_hints(&def),
        execute: Arc::new(move |args, ctx| {
            let (peer, original, server) = (peer.clone(), original.clone(), server.clone());
            Box::pin(async move {
                let mut meta = Map::new();
                meta.insert("server".into(), json!(server));
                let arguments = match args {
                    Value::Object(m) => Some(m),
                    _ => Some(Map::new()),
                };
                let params = CallToolRequestParams::new(original)
                    .with_arguments(arguments.unwrap_or_default());
                let req = ClientRequest::CallToolRequest(rmcp::model::Request::new(params));
                let opts = PeerRequestOptions::with_timeout(timeout).reset_timeout_on_progress();
                let handle = match peer.send_request_with_option(req, opts).await {
                    Ok(h) => h,
                    Err(e) => return ToolResult::error(e.to_string()).with_metadata(meta),
                };
                let resp = tokio::select! {
                    r = handle.await_response() => r,
                    _ = ctx.cancel.cancelled() => return ToolResult::error("cancelled").with_metadata(meta),
                };
                match resp {
                    Ok(ServerResult::CallToolResult(r)) => map_result(
                        r.is_error.unwrap_or(false),
                        r.structured_content,
                        &r.content,
                    )
                    .with_metadata(meta),
                    Ok(other) => ToolResult::error(format!("unexpected response: {other:?}"))
                        .with_metadata(meta),
                    Err(rmcp::ServiceError::Timeout { timeout }) => ToolResult::error(format!(
                        "mcp idle timeout after {}ms without progress",
                        timeout.as_millis()
                    ))
                    .with_metadata(meta),
                    Err(e) => ToolResult::error(e.to_string()).with_metadata(meta),
                }
            })
        }),
    }
}

/// SPEC §0 item 4: isError ⇒ error with the non-blank text parts joined by a
/// blank line; structuredContent ⇒ its JSON; else text parts joined by "\n"
/// (an embedded text resource counts as text, in place). Every non-text entry
/// becomes a §1B part on EVERY branch; an output with only parts describes them.
fn map_result(is_error: bool, structured: Option<Value>, content: &[ContentBlock]) -> ToolResult {
    let wire: Vec<Value> = content
        .iter()
        .filter_map(|c| serde_json::to_value(c).ok())
        .collect();
    map_wire_result(is_error, structured, &wire)
}

/// The §0.4 mapping over wire-shaped `content[]`.
#[doc(hidden)]
pub fn map_wire_result(is_error: bool, structured: Option<Value>, content: &[Value]) -> ToolResult {
    let parts = content_parts(content);
    let text_of = |c: &Value| -> Option<String> {
        (c.get("type").and_then(Value::as_str) == Some("text")).then(|| {
            c.get("text")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned()
        })
    };
    let mut res = if is_error {
        let texts: Vec<String> = content
            .iter()
            .filter_map(text_of)
            .filter(|t| !t.trim().is_empty())
            .collect();
        ToolResult::error(if texts.is_empty() {
            "MCP tool returned an error".to_owned()
        } else {
            texts.join("\n\n")
        })
    } else if let Some(s) = structured {
        ToolResult::ok(crate::builtins::go_json(&s))
    } else {
        let texts: Vec<String> = content
            .iter()
            .filter_map(|c| {
                text_of(c).or_else(|| {
                    if c.get("type").and_then(Value::as_str) == Some("resource") {
                        c["resource"]
                            .get("text")
                            .and_then(Value::as_str)
                            .map(String::from)
                    } else {
                        None
                    }
                })
            })
            .collect();
        let mut out = texts.join("\n");
        if out.is_empty() && !parts.is_empty() {
            out = parts
                .iter()
                .map(describe_part)
                .collect::<Vec<_>>()
                .join("\n");
        }
        ToolResult::ok(out)
    };
    res.parts = parts;
    res
}

fn content_parts(content: &[Value]) -> Vec<ContentPart> {
    let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_owned();
    let mut out = Vec::new();
    for c in content {
        match c.get("type").and_then(Value::as_str) {
            Some(t @ ("image" | "audio")) => out.push(ContentPart {
                kind: t.into(),
                mime_type: s(c, "mimeType"),
                data: s(c, "data"),
                ..Default::default()
            }),
            Some("resource_link") => out.push(ContentPart {
                kind: "file".into(),
                mime_type: s(c, "mimeType"),
                url: s(c, "uri"),
                name: s(c, "name"),
                ..Default::default()
            }),
            Some("resource") => {
                let r = &c["resource"];
                if r.get("blob").is_some() {
                    out.push(ContentPart {
                        kind: "file".into(),
                        mime_type: s(r, "mimeType"),
                        data: s(r, "blob"),
                        name: s(r, "uri"),
                        ..Default::default()
                    });
                }
            }
            _ => {}
        }
    }
    out
}

fn describe_part(p: &ContentPart) -> String {
    use base64::Engine as _;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&p.data)
        .map(|b| b.len())
        .unwrap_or(0);
    format!("{} ({}, {bytes} bytes)", p.kind, p.mime_type)
}

#[doc(hidden)]
pub fn __status_map(src: &McpSource) -> HashMap<String, &'static str> {
    src.status
        .iter()
        .map(|(k, v)| (k.clone(), v.as_str()))
        .collect()
}
