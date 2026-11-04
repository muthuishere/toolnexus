//! HTTP / REST tools (SPEC §0 item 9, §7; `source: "http"`). Port of `golang/http.go`.
//!
//! `{ph}` URL placeholders are filled (path-escaped) from the args and removed;
//! for GET every remaining arg goes to the query string, otherwise only the
//! names in `query`, and the rest is the body (`json` default | `form` | `raw`).
//! `${ENV}` header values expand at call time and are never logged. Non-2xx ⇒
//! `HTTP <status>: <body>` isError; `metadata.status` is always set.

use std::collections::BTreeMap;
use std::sync::{Arc, LazyLock};
use std::time::Duration;

use regex::Regex;
use serde_json::{Map, Value, json};

use crate::builtins::go_json;
use crate::mcp::expand_env_headers;
use crate::tool::{Tool, ToolResult, ToolSource, empty_schema};

/// Default request timeout (30 s), as in Go.
pub const HTTP_DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

/// Options for [`http_tool`] (Go `HTTPToolOptions`).
#[derive(Debug, Clone, Default)]
pub struct HttpToolOptions {
    pub name: String,
    pub description: String,
    /// `GET` (default) | `POST` | ...
    pub method: String,
    /// May contain `{placeholder}` segments.
    pub url: String,
    /// Values may contain `${ENV_VAR}`.
    pub headers: BTreeMap<String, String>,
    /// Arg names sent as query params on a non-GET request.
    pub query: Vec<String>,
    /// Body mode for a non-GET request: `json` (default) | `form` | `raw` (arg `body`).
    pub body: String,
    /// `None` ⇒ an empty object schema.
    pub input_schema: Option<Value>,
    /// Per-tool timeout in ms; 0 ⇒ 30 s. A `ToolContext.timeout_ms` overrides it.
    pub timeout_ms: u64,
    /// `""` = body text | `status+text` | `json` (re-encoded).
    pub result_mode: String,
}

static PLACEHOLDER: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\{(\w+)\}").expect("static regex"));

/// Go `fmt.Sprint`-style rendering of an arg value.
fn to_str(v: Option<&Value>) -> String {
    match v {
        None | Some(Value::Null) => String::new(),
        Some(Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

fn escape(s: &str, keep: &[u8], space_plus: bool) -> String {
    let mut out = String::new();
    for &b in s.as_bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) || keep.contains(&b) {
            out.push(b as char);
        } else if b == b' ' && space_plus {
            out.push('+');
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Go `url.PathEscape` (path-segment mode).
fn path_escape(s: &str) -> String {
    escape(s, b"$&+,:;=@", false)
}

/// Go `url.QueryEscape`.
fn query_escape(s: &str) -> String {
    escape(s, b"", true)
}

/// Go `url.Values.Encode`: sorted by key.
fn encode_values(pairs: &BTreeMap<String, String>) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{}={}", query_escape(k), query_escape(v)))
        .collect::<Vec<_>>()
        .join("&")
}

fn has_header(h: &BTreeMap<String, String>, key: &str) -> bool {
    h.keys().any(|k| k.eq_ignore_ascii_case(key))
}

fn reencode_json(text: &str) -> String {
    match serde_json::from_str::<Value>(text) {
        Ok(v) => go_json(&v),
        Err(_) => go_json(&Value::String(text.to_owned())),
    }
}

/// Build an HTTP tool (Go `HTTPTool`).
pub fn http_tool(opts: HttpToolOptions) -> Tool {
    let opts = Arc::new(opts);
    Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: opts.name.clone(),
        description: opts.description.clone(),
        input_schema: opts.input_schema.clone().unwrap_or_else(empty_schema),
        source: ToolSource::Http,
        hints: None,
        execute: Arc::new(move |args, ctx| {
            let opts = opts.clone();
            Box::pin(async move {
                let mut a: Map<String, Value> = match args {
                    Value::Object(m) => m,
                    _ => Map::new(),
                };
                let method = if opts.method.is_empty() {
                    "GET".to_owned()
                } else {
                    opts.method.to_uppercase()
                };
                let mut endpoint = PLACEHOLDER
                    .replace_all(&opts.url, |c: &regex::Captures| {
                        let v = a.remove(&c[1]);
                        path_escape(&to_str(v.as_ref()))
                    })
                    .into_owned();
                let is_get = method == "GET";
                let mut params = BTreeMap::new();
                let keys: Vec<String> = a.keys().cloned().collect();
                for k in keys {
                    if is_get || opts.query.contains(&k) {
                        let v = a.remove(&k);
                        params.insert(k, to_str(v.as_ref()));
                    }
                }
                if !params.is_empty() {
                    endpoint.push(if endpoint.contains('?') { '&' } else { '?' });
                    endpoint.push_str(&encode_values(&params));
                }
                let mut headers = expand_env_headers(&opts.headers);
                let mut body: Option<String> = None;
                if method != "GET" && method != "HEAD" && !a.is_empty() {
                    match if opts.body.is_empty() {
                        "json"
                    } else {
                        opts.body.as_str()
                    } {
                        "json" => {
                            if !has_header(&headers, "Content-Type") {
                                headers.insert("Content-Type".into(), "application/json".into());
                            }
                            body = Some(go_json(&Value::Object(a.clone())));
                        }
                        "form" => {
                            if !has_header(&headers, "Content-Type") {
                                headers.insert(
                                    "Content-Type".into(),
                                    "application/x-www-form-urlencoded".into(),
                                );
                            }
                            let form: BTreeMap<String, String> = a
                                .iter()
                                .map(|(k, v)| (k.clone(), to_str(Some(v))))
                                .collect();
                            body = Some(encode_values(&form));
                        }
                        "raw" => body = Some(to_str(a.get("body"))),
                        _ => {}
                    }
                }
                let mut timeout = HTTP_DEFAULT_TIMEOUT;
                if opts.timeout_ms > 0 {
                    timeout = Duration::from_millis(opts.timeout_ms);
                }
                if ctx.timeout_ms > 0 {
                    timeout = Duration::from_millis(ctx.timeout_ms);
                }
                let m = match reqwest::Method::from_bytes(method.as_bytes()) {
                    Ok(m) => m,
                    Err(e) => return ToolResult::error(e.to_string()),
                };
                let mut req = reqwest::Client::new().request(m, &endpoint);
                for (k, v) in &headers {
                    req = req.header(k, v);
                }
                if let Some(b) = body {
                    req = req.body(b);
                }
                let send = async {
                    let resp = req.send().await.map_err(|e| e.to_string())?;
                    let status = resp.status().as_u16();
                    let text = resp.text().await.unwrap_or_default();
                    Ok::<_, String>((status, text))
                };
                let res = tokio::select! {
                    r = tokio::time::timeout(timeout, send) => match r {
                        Ok(r) => r,
                        Err(_) => Err("context deadline exceeded".to_owned()),
                    },
                    _ = ctx.cancel.cancelled() => Err("context canceled".to_owned()),
                };
                let (status, text) = match res {
                    Ok(v) => v,
                    Err(e) => return ToolResult::error(e),
                };
                let mut meta = Map::new();
                meta.insert("status".into(), json!(status));
                if !(200..300).contains(&status) {
                    return ToolResult::error(format!("HTTP {status}: {text}")).with_metadata(meta);
                }
                let output = match opts.result_mode.as_str() {
                    "status+text" => format!("{status}\n{text}"),
                    "json" => reencode_json(&text),
                    _ => text,
                };
                ToolResult::ok(output).with_metadata(meta)
            })
        }),
    }
}
