//! Core tool types (SPEC §0 item 1, §1): one `Tool` shape for every source.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use tokio_util::sync::CancellationToken;

/// Where a tool came from. Serialized as the lowercase wire names.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ToolSource {
    Mcp,
    Skill,
    Custom,
    Native,
    Http,
    Builtin,
    Agent,
}

/// What a tool returns. Wire keys match every other port.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ToolResult {
    pub output: String,
    #[serde(rename = "isError")]
    pub is_error: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, Value>>,
}

impl ToolResult {
    pub fn ok(output: impl Into<String>) -> Self {
        Self {
            output: output.into(),
            ..Default::default()
        }
    }
    pub fn error(output: impl Into<String>) -> Self {
        Self {
            output: output.into(),
            is_error: true,
            metadata: None,
        }
    }
    pub fn with_metadata(mut self, metadata: Map<String, Value>) -> Self {
        self.metadata = Some(metadata);
        self
    }
}

/// Per-call context: cancellation, and the model's call id when run by a loop.
#[derive(Debug, Clone, Default)]
pub struct ToolContext {
    pub cancel: CancellationToken,
    pub call_id: Option<String>,
}

/// The boxed future a tool's execute returns.
pub type ToolFuture = Pin<Box<dyn Future<Output = ToolResult> + Send>>;

/// A tool's execute function.
pub type ExecuteFn = Arc<dyn Fn(Value, ToolContext) -> ToolFuture + Send + Sync>;

/// A named, described, schema'd callable (SPEC §1).
#[derive(Clone)]
pub struct Tool {
    pub name: String,
    pub description: String,
    /// JSON-Schema object for the arguments.
    pub input_schema: Value,
    pub source: ToolSource,
    pub execute: ExecuteFn,
}

impl std::fmt::Debug for Tool {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Tool")
            .field("name", &self.name)
            .field("source", &self.source)
            .finish_non_exhaustive()
    }
}

impl Tool {
    /// Run the tool. A missing/`null` args value is passed as `{}`.
    pub async fn call(&self, args: Value, ctx: ToolContext) -> ToolResult {
        let args = if args.is_null() {
            Value::Object(Map::new())
        } else {
            args
        };
        (self.execute)(args, ctx).await
    }
}

/// `sanitize(x)` (SPEC §0 item 2): replace anything outside `[a-zA-Z0-9_-]` with `_`.
pub fn sanitize(value: &str) -> String {
    value
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// The schema used when a native tool declares none: an object with no properties.
pub fn empty_schema() -> Value {
    serde_json::json!({"type": "object", "properties": {}, "additionalProperties": false})
}

/// A native tool (SPEC §0 item 8, `source: "native"`): `Ok(text)` ⇒ output,
/// `Err(e)` ⇒ `isError` with `e.to_string()`. `schema: None` ⇒ [`empty_schema`].
pub fn define_tool<F, Fut, E>(
    name: impl Into<String>,
    description: impl Into<String>,
    schema: Option<Value>,
    f: F,
) -> Tool
where
    F: Fn(Value, ToolContext) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<String, E>> + Send + 'static,
    E: std::fmt::Display,
{
    let f = Arc::new(f);
    Tool {
        name: name.into(),
        description: description.into(),
        input_schema: schema.unwrap_or_else(empty_schema),
        source: ToolSource::Native,
        execute: Arc::new(move |args, ctx| {
            let f = f.clone();
            Box::pin(async move {
                match f(args, ctx).await {
                    Ok(out) => ToolResult::ok(out),
                    Err(e) => ToolResult::error(e.to_string()),
                }
            })
        }),
    }
}

/// [`define_tool`] with the arguments decoded into `T` first. A decode failure
/// is an `isError` result naming the problem; `schema` is advertised verbatim.
pub fn define_typed_tool<T, F, Fut, E>(
    name: impl Into<String>,
    description: impl Into<String>,
    schema: Value,
    f: F,
) -> Tool
where
    T: serde::de::DeserializeOwned + Send + 'static,
    F: Fn(T, ToolContext) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<String, E>> + Send + 'static,
    E: std::fmt::Display,
{
    let f = Arc::new(f);
    define_tool(name, description, Some(schema), move |args, ctx| {
        let f = f.clone();
        async move {
            let input: T =
                serde_json::from_value(args).map_err(|e| format!("invalid arguments: {e}"))?;
            f(input, ctx).await.map_err(|e| e.to_string())
        }
    })
}
