//! toolnexus — Rust port (see `openspec/changes/add-rust-port`).
//!
//! The ACP client ([`acp`]), skills ([`skills`]), the MCP client ([`mcp`], on
//! rmcp), core [`Tool`] types with native and [`http`] tools, the [`builtins`],
//! the [`toolkit`] aggregator, schema [`adapters`] and the unified [`client`]
//! loop with §10 suspension.

pub mod acp;
pub mod adapters;
pub mod builtins;
pub mod client;
pub mod http;
pub mod mcp;
pub mod skills;
pub mod store;
pub mod tool;
pub mod toolkit;

pub use adapters::{to_anthropic, to_gemini, to_openai};
pub use builtins::{BuiltinsConfig, select_builtins};
pub use tool::{
    Answer, ContentPart, Request, Tool, ToolContext, ToolHints, ToolResult, ToolSource,
    auth_required, define_tool, define_typed_tool, empty_schema, pending, pending_of, sanitize,
};
pub use toolkit::{McpConfigInput, Toolkit, ToolkitError, ToolkitOptions, create_toolkit};
