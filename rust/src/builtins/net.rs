//! `webfetch` (port of `golang/builtin.go` webfetchTool).

use std::sync::LazyLock;
use std::time::Duration;

use regex::Regex;
use serde_json::json;

use super::{arg_num, arg_str, b_err, b_ok, builtin, meta};
use crate::tool::Tool;

static SCRIPT_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<script.*?</script>").expect("static regex"));
static STYLE_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?is)<style.*?</style>").expect("static regex"));
static TAG_RE: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"<[^>]+>").expect("static regex"));
static TRAILING_WS_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"[ \t]+\n").expect("static regex"));
static MULTI_NL_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\n{3,}").expect("static regex"));

/// Strip scripts, styles and tags, collapse whitespace (Go `stripHTML`).
pub fn strip_html(html: &str) -> String {
    let s = SCRIPT_RE.replace_all(html, "");
    let s = STYLE_RE.replace_all(&s, "");
    let s = TAG_RE.replace_all(&s, "");
    let s = TRAILING_WS_RE.replace_all(&s, "\n");
    let s = MULTI_NL_RE.replace_all(&s, "\n\n");
    s.trim().to_owned()
}

pub(crate) fn webfetch_tool() -> Tool {
    builtin(
        "webfetch",
        "HTTP GET a URL and return its body as text, markdown, or html.",
        json!({
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to fetch"},
                "format": {"type": "string", "enum": ["text", "markdown", "html"], "description": "Response format (default markdown)"},
                "timeout": {"type": "number", "description": "Timeout in seconds (default 30)"}
            },
            "required": ["url"],
            "additionalProperties": false
        }),
        |args, ctx| async move {
            let url = arg_str(&args, "url").unwrap_or("").to_owned();
            if url.is_empty() {
                return b_err("webfetch: url is required", None);
            }
            let format = match arg_str(&args, "format") {
                Some(f @ ("text" | "html")) => f.to_owned(),
                _ => "markdown".to_owned(),
            };
            let timeout_s = arg_num(&args, "timeout").unwrap_or(30.0).max(0.0);
            let fetch = async {
                let resp = reqwest::Client::new()
                    .get(&url)
                    .send()
                    .await
                    .map_err(|e| format!("webfetch: {e}"))?;
                let status = resp.status().as_u16();
                let body = resp.text().await.map_err(|e| format!("webfetch: {e}"))?;
                Ok::<_, String>((status, body))
            };
            let res = tokio::select! {
                r = tokio::time::timeout(Duration::from_millis((timeout_s * 1000.0) as u64), fetch) => match r {
                    Ok(r) => r,
                    Err(_) => Err("webfetch: context deadline exceeded".to_owned()),
                },
                _ = ctx.cancel.cancelled() => Err("webfetch: context canceled".to_owned()),
            };
            let (status, body) = match res {
                Ok(v) => v,
                Err(e) => return b_err(e, None),
            };
            if !(200..300).contains(&status) {
                return b_err(
                    format!("HTTP {status}"),
                    meta(vec![("status", json!(status))]),
                );
            }
            let output = if format == "html" {
                body
            } else {
                strip_html(&body)
            };
            b_ok(
                output,
                meta(vec![("status", json!(status)), ("format", json!(format))]),
            )
        },
    )
}
