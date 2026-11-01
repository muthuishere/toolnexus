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

use rmcp::model::{
    CallToolRequestParams, ClientRequest, ContentBlock, ResourceContents, ServerResult,
};
use rmcp::service::{PeerRequestOptions, RunningService};
#[cfg(feature = "mcp-http")]
use rmcp::transport::StreamableHttpClientTransport;
use rmcp::transport::TokioChildProcess;
#[cfg(feature = "mcp-http")]
use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;
use rmcp::{RoleClient, ServiceExt};
use serde_json::{Map, Value, json};
use tokio::sync::Mutex;

use crate::tool::{Tool, ToolResult, ToolSource, sanitize};

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

type Client = RunningService<RoleClient, ()>;

/// The connected servers and their tools.
pub struct McpSource {
    /// All servers' tools, servers in name order, each server's tools in list order.
    pub tools: Vec<Tool>,
    pub status: BTreeMap<String, McpStatus>,
    /// Why each failed server failed (diagnostic).
    pub errors: BTreeMap<String, String>,
    clients: Mutex<Vec<Client>>,
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
    }
}

/// Connect to every enabled server concurrently. Never fails as a whole: a
/// server that cannot connect or list is `failed` and the rest load.
pub async fn load_mcp(config: &McpConfig) -> McpSource {
    let mut tasks = Vec::new();
    let mut status = BTreeMap::new();
    for (name, cfg) in config {
        if !cfg.is_enabled() {
            status.insert(name.clone(), McpStatus::Disabled);
            continue;
        }
        let (name, cfg) = (name.clone(), cfg.clone());
        tasks.push(tokio::spawn(async move {
            let r = tokio::time::timeout(cfg.timeout(), connect_and_list(&name, &cfg)).await;
            let r = r.unwrap_or_else(|_| {
                Err(format!("timed out after {}ms", cfg.timeout().as_millis()))
            });
            (name, cfg, r)
        }));
    }
    let mut tools = Vec::new();
    let mut errors = BTreeMap::new();
    let mut clients = Vec::new();
    let mut done = Vec::new();
    for t in tasks {
        if let Ok(x) = t.await {
            done.push(x);
        }
    }
    done.sort_by(|a, b| a.0.cmp(&b.0));
    for (name, cfg, r) in done {
        match r {
            Ok((client, defs)) => {
                let peer = Arc::new(client.peer().clone());
                for def in apply_tools_filter(&name, defs, &cfg.tools) {
                    tools.push(convert_tool(&name, def, peer.clone(), cfg.timeout()));
                }
                clients.push(client);
                status.insert(name, McpStatus::Connected);
            }
            Err(e) => {
                eprintln!("[toolnexus] MCP server {name:?} failed: {e}");
                status.insert(name.clone(), McpStatus::Failed);
                errors.insert(name, e);
            }
        }
    }
    McpSource {
        tools,
        status,
        errors,
        clients: Mutex::new(clients),
    }
}

async fn connect_and_list(
    name: &str,
    cfg: &ServerConfig,
) -> Result<(Client, Vec<rmcp::model::Tool>), String> {
    let client = if cfg.is_remote() {
        #[cfg(not(feature = "mcp-http"))]
        return Err(format!(
            "remote server {name:?}: built without the `mcp-http` feature"
        ));
        #[cfg(feature = "mcp-http")]
        {
            let url = cfg.url.clone().unwrap_or_default();
            let mut tc = StreamableHttpClientTransportConfig::with_uri(url);
            for (k, v) in expand_env_headers(&cfg.headers) {
                let (Ok(k), Ok(v)) = (
                    http::HeaderName::try_from(k.as_str()),
                    http::HeaderValue::try_from(v.as_str()),
                ) else {
                    return Err(format!("invalid header {k:?}"));
                };
                tc.custom_headers.insert(k, v);
            }
            let transport = StreamableHttpClientTransport::with_client(reqwest::Client::new(), tc);
            ().serve(transport).await.map_err(|e| e.to_string())?
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
        ().serve(transport).await.map_err(|e| e.to_string())?
    };
    let defs = client
        .list_all_tools()
        .await
        .map_err(|e| format!("list tools failed: {e}"))?;
    Ok((client, defs))
}

fn apply_tools_filter(
    server: &str,
    defs: Vec<rmcp::model::Tool>,
    filter: &BTreeMap<String, bool>,
) -> Vec<rmcp::model::Tool> {
    if filter.is_empty() {
        return defs;
    }
    let has_true = filter.values().any(|v| *v);
    for k in filter.keys() {
        if !defs.iter().any(|d| d.name == k.as_str()) {
            eprintln!("[toolnexus] MCP server {server:?} tools filter name {k:?} matched no tool");
        }
    }
    defs.into_iter()
        .filter(|d| match filter.get(d.name.as_ref()) {
            Some(v) if has_true => *v,
            None if has_true => false,
            Some(v) => *v,
            None => true,
        })
        .collect()
}

/// The tool's input schema, normalized like every port: `type: object`,
/// `properties` (default `{}`), `required` and `$defs` when present,
/// `additionalProperties: false`.
fn build_input_schema(def: &rmcp::model::Tool) -> Value {
    let input = &def.input_schema;
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
        name: format!("{}_{}", sanitize(&server), sanitize(&original)),
        description: def.description.as_deref().unwrap_or("").to_owned(),
        input_schema: build_input_schema(&def),
        source: ToolSource::Mcp,
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
/// (an embedded text resource counts as text, in place).
fn map_result(is_error: bool, structured: Option<Value>, content: &[ContentBlock]) -> ToolResult {
    let text_of = |c: &ContentBlock| -> Option<String> {
        match c {
            ContentBlock::Text(t) => Some(t.text.clone()),
            _ => None,
        }
    };
    if is_error {
        let parts: Vec<String> = content
            .iter()
            .filter_map(text_of)
            .filter(|t| !t.trim().is_empty())
            .collect();
        return ToolResult::error(if parts.is_empty() {
            "MCP tool returned an error".to_owned()
        } else {
            parts.join("\n\n")
        });
    }
    if let Some(s) = structured {
        return ToolResult::ok(serde_json::to_string(&s).unwrap_or_default());
    }
    let parts: Vec<String> = content
        .iter()
        .filter_map(|c| match c {
            ContentBlock::Text(t) => Some(t.text.clone()),
            ContentBlock::Resource(r) => match &r.resource {
                ResourceContents::TextResourceContents { text, .. } => Some(text.clone()),
                _ => None,
            },
            _ => None,
        })
        .collect();
    ToolResult::ok(parts.join("\n"))
}

#[doc(hidden)]
pub fn __status_map(src: &McpSource) -> HashMap<String, &'static str> {
    src.status
        .iter()
        .map(|(k, v)| (k.clone(), v.as_str()))
        .collect()
}
