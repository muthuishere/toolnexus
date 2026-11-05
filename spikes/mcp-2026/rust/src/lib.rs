//! mcp2026 — in-house MCP 2026-07-28 client + server spike (Rust port of `spikes/mcp-2026/golang`).
//!
//! No MCP SDK: tokio + hyper (server) + reqwest (client) + serde_json, RS256 via `rsa`.
//! Stateless Streamable HTTP (`POST /mcp`) and stdio, MRTR `input_required`, the skills
//! extension, lazy tool search, and OAuth on both sides (client: PRM -> AS metadata ->
//! PKCE S256 + resource -> token -> refresh / step-up; server: protected resource + JWT/JWKS).

pub mod auth;
mod checks;
mod client;
pub mod devauth;
pub mod http;
pub mod server;
pub mod serverauth;
pub mod skills;
pub mod stdio;
pub mod toolsearch;

pub use auth::{Authorizer, MemoryTokenStore, OAuth, Opener, Pending, Token, TokenStore, opener};
pub use checks::{fixture_server, run_checks, run_checks_with};
pub use client::{Client, Responder, responder};
pub use devauth::DevAuthServer;
pub use http::{HttpServer, IntoHandler, Request, Response, handler};
pub use server::{Server, ToolContext};
pub use serverauth::{Principal, ServerAuth};
pub use stdio::serve_stdio;

use serde_json::{Map, Value};

/// A JSON object.
pub type Obj = Map<String, Value>;

pub const PROTOCOL_VERSION: &str = "2026-07-28";
pub const META_VERSION: &str = "io.modelcontextprotocol/protocolVersion";
pub const META_CLIENT_CAPS: &str = "io.modelcontextprotocol/clientCapabilities";
pub const META_CLIENT_INFO: &str = "io.modelcontextprotocol/clientInfo";
pub const META_SERVER_INFO: &str = "io.modelcontextprotocol/serverInfo";
pub const SKILLS_EXT: &str = "io.modelcontextprotocol/skills";

pub const CODE_METHOD_NOT_FOUND: i64 = -32601;
pub const CODE_INVALID_PARAMS: i64 = -32602;
pub const CODE_HEADER_MISMATCH: i64 = -32020;
pub const CODE_UNSUPPORTED_PROTOCOL: i64 = -32022;

/// Every failure the client can return.
#[derive(Debug, Clone)]
pub enum Error {
    /// A JSON-RPC error from the server.
    Rpc {
        code: i64,
        message: String,
        data: Option<Value>,
    },
    /// Authorization paused (pause mode): finish with `OAuth::resume(id, callback_url)`.
    Pending(Pending),
    /// Transport, protocol or auth failure.
    Other(String),
}

impl Error {
    pub fn msg(m: impl Into<String>) -> Self {
        Error::Other(m.into())
    }
    /// The JSON-RPC error code, if this is a server error.
    pub fn code(&self) -> Option<i64> {
        match self {
            Error::Rpc { code, .. } => Some(*code),
            _ => None,
        }
    }
    /// The pending authorization, if the call paused.
    pub fn pending(&self) -> Option<&Pending> {
        match self {
            Error::Pending(p) => Some(p),
            _ => None,
        }
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Rpc { message, .. } => f.write_str(message),
            Error::Pending(p) => write!(f, "authorization pending: {}", p.prompt),
            Error::Other(m) => f.write_str(m),
        }
    }
}

impl std::error::Error for Error {}

/// Returns the pending authorization inside a result's error, or None.
pub fn pending_of<T>(r: &Result<T, Error>) -> Option<&Pending> {
    r.as_ref().err().and_then(Error::pending)
}

/// Returns the first text content of a tool result ("" if none).
pub fn text(r: &Obj) -> String {
    r.get("content")
        .and_then(Value::as_array)
        .and_then(|c| c.first())
        .and_then(|c| c.get("text"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// `json!({...})` as an [`Obj`] (panics if the value is not an object).
#[macro_export]
macro_rules! obj {
    ($($t:tt)*) => {
        match ::serde_json::json!($($t)*) {
            ::serde_json::Value::Object(m) => m,
            other => panic!("obj!: not an object: {other}"),
        }
    };
}

pub(crate) fn rand_str(n: usize) -> String {
    use base64::Engine;
    use rand::RngCore;
    let mut b = vec![0u8; n];
    rand::thread_rng().fill_bytes(&mut b);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

pub(crate) fn b64url(b: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

pub(crate) fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest;
    sha2::Sha256::digest(b)
        .iter()
        .map(|x| format!("{x:02x}"))
        .collect()
}
