//! MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C). Port of
//! `golang/mcpserve.go`, on rmcp's server + streamable-HTTP transport. Behind
//! the `serve` feature.
//!
//! Where A2A advertises the toolkit's SKILLS and runs the whole client loop, MCP
//! advertises the toolkit's UNIFIED tools (every source) and dispatches each
//! `tools/call` straight to the tool. The calling MCP client is the LLM host, so
//! there is no client, Task or store. Mounted at `POST /mcp` by
//! [`Toolkit::serve`](crate::toolkit::Toolkit::serve), stateless.

use std::collections::HashSet;
use std::sync::Arc;
use std::time::Instant;

use rmcp::model::{
    CallToolRequestParams, CallToolResponse, CallToolResult, ContentBlock, Implementation,
    ListToolsResult, PaginatedRequestParams, Resource, ResourceContents, ServerCapabilities,
    ServerConfig,
};
use rmcp::service::RequestContext;
use rmcp::transport::streamable_http_server::session::never::NeverSessionManager;
use rmcp::transport::streamable_http_server::{StreamableHttpServerConfig, StreamableHttpService};
use rmcp::{ErrorData, RoleServer, ServerHandler};
use serde::Deserialize;
use serde_json::{Map, Value};
use tokio_util::sync::CancellationToken;

use crate::tool::{ContentPart, Tool, ToolContext, ToolSource};

const DEFAULT_NAME: &str = "toolnexus";
const DEFAULT_VERSION: &str = "0.1.0";

/// The opt-in MCP serve profile. Deserializes from a top-level `mcpServer` block.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct McpServeConfig {
    /// `initialize` `serverInfo.name`; empty ⇒ `toolnexus`.
    #[serde(default)]
    pub name: String,
    /// `serverInfo.version`; empty ⇒ `0.1.0`.
    #[serde(default)]
    pub version: String,
    /// Tool names to expose; `None` ⇒ all. Unknown names are ignored.
    #[serde(default)]
    pub tools: Option<Vec<String>>,
}

/// Surfaced on each inbound `tools/call`.
#[derive(Debug, Clone, PartialEq)]
pub struct OnCallEvent {
    pub name: String,
    pub source: ToolSource,
    pub ms: u64,
    pub is_error: bool,
}

/// The per-call callback.
pub type OnCall = Arc<dyn Fn(OnCallEvent) + Send + Sync>;

/// The tools this profile exposes: every toolkit tool, filtered to `cfg.tools`
/// when set (unknown names in the filter are ignored, never an error).
pub fn exposed_mcp_tools(tools: Vec<Tool>, cfg: Option<&McpServeConfig>) -> Vec<Tool> {
    match cfg.and_then(|c| c.tools.as_ref()) {
        None => tools,
        Some(names) => {
            let wanted: HashSet<&str> = names.iter().map(String::as_str).collect();
            tools
                .into_iter()
                .filter(|t| wanted.contains(t.name.as_str()))
                .collect()
        }
    }
}

/// An rmcp server handler over a fixed set of toolnexus tools. Usable with any
/// rmcp transport; [`Toolkit::serve`](crate::toolkit::Toolkit::serve) mounts it
/// over streamable HTTP.
#[derive(Clone)]
pub struct McpToolServer {
    tools: Arc<Vec<Tool>>,
    name: String,
    version: String,
    on_call: Option<OnCall>,
}

impl McpToolServer {
    /// `tools` are exposed verbatim (filter first with [`exposed_mcp_tools`]).
    pub fn new(tools: Vec<Tool>, cfg: Option<&McpServeConfig>, on_call: Option<OnCall>) -> Self {
        let pick = |v: Option<&String>, d: &str| match v {
            Some(s) if !s.is_empty() => s.clone(),
            _ => d.to_owned(),
        };
        Self {
            tools: Arc::new(tools),
            name: pick(cfg.map(|c| &c.name), DEFAULT_NAME),
            version: pick(cfg.map(|c| &c.version), DEFAULT_VERSION),
            on_call,
        }
    }
}

/// The wire definition: name verbatim, the schema passed through as-is.
fn wire_tool(t: &Tool) -> rmcp::model::Tool {
    let schema: Map<String, Value> = match &t.input_schema {
        Value::Object(m) => m.clone(),
        _ => Map::new(),
    };
    rmcp::model::Tool::new(t.name.clone(), t.description.clone(), Arc::new(schema))
}

/// §1B parts → MCP content blocks (after the text block, in order): image ⇒
/// image, audio ⇒ audio, file ⇒ embedded blob resource; a URL-only part ⇒ a
/// resource link. Base64 passes through verbatim.
fn parts_to_content(parts: &[ContentPart]) -> Vec<ContentBlock> {
    let link = |p: &ContentPart| {
        let mut r = Resource::new(p.url.clone(), p.name.clone());
        if !p.mime_type.is_empty() {
            r.mime_type = Some(p.mime_type.clone());
        }
        ContentBlock::resource_link(r)
    };
    parts
        .iter()
        .filter_map(|p| match p.kind.as_str() {
            "text" => Some(ContentBlock::text(p.text.clone())),
            "image" if !p.data.is_empty() => {
                Some(ContentBlock::image(p.data.clone(), p.mime_type.clone()))
            }
            "audio" if !p.data.is_empty() => {
                Some(ContentBlock::audio(p.data.clone(), p.mime_type.clone()))
            }
            "file" if !p.data.is_empty() => {
                let mut res = ResourceContents::BlobResourceContents {
                    uri: p.name.clone(),
                    mime_type: None,
                    blob: p.data.clone(),
                    meta: None,
                };
                if !p.mime_type.is_empty() {
                    res = res.with_mime_type(p.mime_type.clone());
                }
                Some(ContentBlock::resource(res))
            }
            "image" | "audio" | "file" => Some(link(p)),
            _ => None,
        })
        .collect()
}

impl ServerHandler for McpToolServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
            .with_server_info(Implementation::new(self.name.clone(), self.version.clone()))
    }

    async fn list_tools(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> Result<ListToolsResult, ErrorData> {
        Ok(ListToolsResult::with_all_items(
            self.tools.iter().map(wire_tool).collect(),
        ))
    }

    fn get_tool(&self, name: &str) -> Option<rmcp::model::Tool> {
        self.tools.iter().find(|t| t.name == name).map(wire_tool)
    }

    async fn call_tool(
        &self,
        request: CallToolRequestParams,
        context: RequestContext<RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        let Some(tool) = self.tools.iter().find(|t| t.name == request.name).cloned() else {
            return Err(ErrorData::invalid_params(
                format!("tool '{}' not found", request.name),
                None,
            ));
        };
        let started = Instant::now();
        let args = Value::Object(request.arguments.unwrap_or_default());
        let ctx = ToolContext {
            cancel: context.ct.clone(),
            ..Default::default()
        };
        // A panicking tool is an isError result, never a protocol crash.
        let res = match tokio::spawn(async move { tool.call(args, ctx).await }).await {
            Ok(r) => r,
            Err(e) => crate::tool::ToolResult::error(format!("tool panicked: {e}")),
        };
        if let Some(cb) = &self.on_call {
            let ev = OnCallEvent {
                name: request.name.to_string(),
                source: self
                    .tools
                    .iter()
                    .find(|t| t.name == request.name)
                    .map(|t| t.source)
                    .unwrap_or(ToolSource::Native),
                ms: started.elapsed().as_millis() as u64,
                is_error: res.is_error,
            };
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(ev)));
        }
        let mut content = vec![ContentBlock::text(res.output.clone())];
        content.extend(parts_to_content(&res.parts));
        let mut out = if res.is_error {
            CallToolResult::error(content)
        } else {
            CallToolResult::success(content)
        };
        out.is_error = Some(res.is_error);
        Ok(out.into())
    }
}

/// The stateless streamable-HTTP MCP service `serve` mounts at `/mcp`.
pub(crate) fn mcp_http_service(
    tools: Vec<Tool>,
    cfg: &McpServeConfig,
    on_call: Option<OnCall>,
    cancel: CancellationToken,
) -> StreamableHttpService<McpToolServer, NeverSessionManager> {
    let server = McpToolServer::new(exposed_mcp_tools(tools, Some(cfg)), Some(cfg), on_call);
    let mut config = StreamableHttpServerConfig::default().disable_allowed_hosts();
    config.legacy_session_mode = false;
    config.json_response = true;
    config.cancellation_token = cancel;
    StreamableHttpService::new(
        move || Ok(server.clone()),
        Arc::new(NeverSessionManager::default()),
        config,
    )
}
