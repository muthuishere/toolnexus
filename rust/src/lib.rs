//! toolnexus — Rust port (in progress; see `openspec/changes/add-rust-port`).
//!
//! Shipped so far: the ACP client ([`acp`]), skills ([`skills`]), the MCP
//! client ([`mcp`], on rmcp), core [`Tool`] types with native tools, and the
//! schema [`adapters`]. The toolkit, builtins and the client loop are phase 5.

pub mod acp;
pub mod adapters;
pub mod mcp;
pub mod skills;
pub mod tool;

pub use adapters::{to_anthropic, to_gemini, to_openai};
pub use tool::{
    Tool, ToolContext, ToolResult, ToolSource, define_tool, define_typed_tool, empty_schema,
    sanitize,
};
