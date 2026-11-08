//! toolnexus — Rust port (see `openspec/changes/add-rust-port`).
//!
//! The ACP client ([`acp`]), skills ([`skills`]), the MCP client ([`mcp`], on
//! rmcp), core [`Tool`] types with native and [`http`] tools, the [`builtins`],
//! the [`toolkit`] aggregator, schema [`adapters`] and the unified [`client`]
//! loop with §10 suspension, single-turn `translate` (§11), the §8B
//! [`classifier`] and its [`judge`] adapters and batteries.

pub mod a2a;
pub mod acp;
pub mod adapters;
pub mod agents;
#[cfg(feature = "serve")]
pub mod as_mcp_server;
pub mod builtins;
pub mod classifier;
pub mod client;
pub mod http;
pub mod judge;
pub mod mcp;
#[cfg(feature = "serve")]
pub mod mcp_handler;
#[cfg(feature = "serve")]
pub mod mcp_serve;
pub mod media;
pub mod relay;
#[cfg(feature = "serve")]
pub mod serve;
pub mod skills;
pub mod store;
pub mod tool;
pub mod toolkit;
pub mod transcript_search;

pub use adapters::{to_anthropic, to_gemini, to_openai};
#[cfg(feature = "serve")]
pub use as_mcp_server::McpServerHandle;
pub use builtins::{BuiltinsConfig, select_builtins};
pub use classifier::{
    Classifier, ClassifierError, ClassifierOptions, ClassifierStyle, Decision, Question, Questions,
    create_classifier,
};
pub use media::{
    CloneVoiceInput, ImageInput, Media, MediaError, MediaFile, MediaOptions, MediaProgress,
    MediaResult, MediaStyle, SpeechInput, TalkingInput, TranscribeInput, VideoInput, create_media,
    load_media_style,
};
pub use tool::{
    Answer, ConcurrencySafeFn, ContentPart, DEFAULT_MAX_TOOL_CONCURRENCY, PartError, Request, Tool,
    ToolAnnotations, ToolContext, ToolHints, ToolResult, ToolSource, auth_required, define_tool,
    define_typed_tool, empty_schema, is_concurrency_safe, pending, pending_of,
    resolve_max_tool_concurrency, resolve_max_tool_concurrency_with, sanitize,
};
pub use toolkit::{McpConfigInput, Toolkit, ToolkitError, ToolkitOptions, create_toolkit};
pub use transcript_search::{
    TRANSCRIPT_SEARCH_DESCRIPTION, TranscriptSearchOptions, transcript_message_text,
    transcript_search_tool,
};
