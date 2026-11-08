//! `transcript_search_tool` — read-only search over a [`ConversationStore`]
//! (SPEC §7E "Memory hierarchy" → transcript search). The ground-truth tier of
//! agent memory: a dreaming persona checks its notes against what actually
//! happened, not against its own notes.

use std::sync::Arc;

use serde_json::{Value, json};

use crate::store::ConversationStore;
use crate::tool::{Tool, ToolResult, ToolSource};

/// The tool description, byte-identical in every port.
pub const TRANSCRIPT_SEARCH_DESCRIPTION: &str = "Search past conversation transcripts — the ground truth of what actually happened. Case-insensitive substring match over message text. Returns one line per matching message: <conversationId>#<index> <role>: <snippet>. Read-only.";

/// [`transcript_search_tool`] options; `None` takes the default.
#[derive(Clone, Debug, Default)]
pub struct TranscriptSearchOptions {
    /// Most hits per call and the ceiling for the `limit` input (default 20).
    pub max_results: Option<usize>,
    /// Snippet length in Unicode code points (default 200).
    pub snippet_chars: Option<usize>,
    /// Tool name (default `transcript_search`).
    pub name: Option<String>,
}

fn is_ascii_ws(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\r' | '\x0c' | '\x0b')
}

/// Collapse every run of ASCII whitespace to one space and trim.
fn normalize(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut in_ws = false;
    for c in s.chars() {
        if is_ascii_ws(c) {
            in_ws = true;
        } else {
            if in_ws && !out.is_empty() {
                out.push(' ');
            }
            in_ws = false;
            out.push(c);
        }
    }
    out
}

fn parts_text(content: &Value, out: &mut Vec<String>) {
    match content {
        Value::String(s) => out.push(s.clone()),
        Value::Array(parts) => {
            for p in parts {
                match p.get("type").and_then(Value::as_str) {
                    Some("text") => {
                        if let Some(t) = p.get("text").and_then(Value::as_str) {
                            out.push(t.to_string());
                        }
                    }
                    Some("tool_use") => {
                        // serde_json maps keep keys sorted, so "stored order" is key order.
                        let input = p.get("input").cloned().unwrap_or_else(|| json!({}));
                        let input = if input.is_null() { json!({}) } else { input };
                        out.push(input.to_string());
                    }
                    Some("tool_result") => {
                        if let Some(c) = p.get("content") {
                            parts_text(c, out);
                        }
                    }
                    _ => {}
                }
            }
        }
        _ => {}
    }
}

/// The searchable text of one stored message (§7E): string content, text
/// parts, `tool_use` input as compact JSON, `tool_result` content
/// (recursively), then each OpenAI tool call's `arguments` string — joined by
/// `"\n"`, then whitespace-normalized. Images contribute nothing.
pub fn transcript_message_text(msg: &Value) -> String {
    let mut pieces = Vec::new();
    if let Some(c) = msg.get("content") {
        parts_text(c, &mut pieces);
    }
    if let Some(calls) = msg.get("tool_calls").and_then(Value::as_array) {
        for tc in calls {
            if let Some(a) = tc
                .get("function")
                .and_then(|f| f.get("arguments"))
                .and_then(Value::as_str)
            {
                pieces.push(a.to_string());
            }
        }
    }
    normalize(&pieces.join("\n"))
}

fn index_of(hay: &[char], needle: &[char]) -> Option<usize> {
    if needle.len() > hay.len() {
        return None;
    }
    (0..=hay.len() - needle.len()).find(|&i| hay[i..i + needle.len()] == *needle)
}

fn snippet(cps: &[char], at: usize, qlen: usize, size: usize) -> String {
    if cps.len() <= size {
        return cps.iter().collect();
    }
    let start = (at + qlen / 2)
        .saturating_sub(size / 2)
        .min(cps.len() - size);
    let end = start + size;
    let mut s = String::new();
    if start > 0 {
        s.push('…');
    }
    s.extend(&cps[start..end]);
    if end < cps.len() {
        s.push('…');
    }
    s
}

/// An integer `>= 1` (a JSON float with no fraction counts, as in JS).
fn positive_int(v: &Value) -> Option<usize> {
    if let Some(n) = v.as_u64() {
        return (n >= 1).then_some(n as usize);
    }
    let f = v.as_f64()?;
    (f.fract() == 0.0 && f >= 1.0 && f.is_finite()).then_some(f as usize)
}

/// A read-only [`Tool`] over `store` (§7E). Errors unless the store
/// [supports `list`](ConversationStore::supports_list). Input
/// `{query, conversationId?, limit?}`; output one line per hit,
/// `<conversationId>#<index> <role>: <snippet>`, `no matches` when none, and a
/// final `(+<k> more)` when hits exceed the limit. Never writes.
pub fn transcript_search_tool(
    store: Arc<dyn ConversationStore>,
    opts: TranscriptSearchOptions,
) -> Result<Tool, String> {
    if !store.supports_list() {
        return Err("transcriptSearchTool: the store must implement list()".into());
    }
    let max_results = opts.max_results.unwrap_or(20);
    let snippet_chars = opts.snippet_chars.unwrap_or(200);
    if max_results < 1 {
        return Err("transcriptSearchTool: maxResults must be an integer >= 1".into());
    }
    if snippet_chars < 1 {
        return Err("transcriptSearchTool: snippetChars must be an integer >= 1".into());
    }
    Ok(Tool {
        name: opts.name.unwrap_or_else(|| "transcript_search".into()),
        description: TRANSCRIPT_SEARCH_DESCRIPTION.into(),
        input_schema: json!({
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Text to find (case-insensitive substring)."},
                "conversationId": {"type": "string", "description": "Search only this conversation."},
                "limit": {"type": "integer", "description": format!("Most hits to return (default and max {max_results}).")},
            },
            "required": ["query"],
        }),
        source: ToolSource::Custom,
        execute: Arc::new(move |args, _ctx| {
            let store = store.clone();
            Box::pin(async move {
                let query = normalize(args.get("query").and_then(Value::as_str).unwrap_or(""));
                if query.is_empty() {
                    return ToolResult::error("query is required");
                }
                let mut limit = max_results;
                if let Some(l) = args.get("limit") {
                    match positive_int(l) {
                        Some(n) => limit = n.min(max_results),
                        None => return ToolResult::error("limit must be an integer >= 1"),
                    }
                }
                let ids: Vec<String> = match args.get("conversationId").and_then(Value::as_str) {
                    Some(id) => vec![id.to_string()],
                    None => match store.list() {
                        Ok(infos) => infos.into_iter().map(|e| e.id).collect(),
                        Err(e) => return ToolResult::error(e),
                    },
                };
                let needle: Vec<char> = query.chars().map(|c| c.to_ascii_lowercase()).collect();
                let mut hits: Vec<String> = Vec::new();
                let mut total = 0usize;
                for id in ids {
                    let msgs = match store.get(&id) {
                        Ok(Some(m)) => m,
                        Ok(None) => continue,
                        Err(e) => return ToolResult::error(e),
                    };
                    for (index, msg) in msgs.iter().enumerate() {
                        let cps: Vec<char> = transcript_message_text(msg).chars().collect();
                        let hay: Vec<char> = cps.iter().map(|c| c.to_ascii_lowercase()).collect();
                        let Some(at) = index_of(&hay, &needle) else {
                            continue;
                        };
                        total += 1;
                        if hits.len() < limit {
                            let role = msg.get("role").and_then(Value::as_str).unwrap_or("unknown");
                            hits.push(format!(
                                "{id}#{index} {role}: {}",
                                snippet(&cps, at, needle.len(), snippet_chars)
                            ));
                        }
                    }
                }
                if total == 0 {
                    return ToolResult::ok("no matches");
                }
                if total > hits.len() {
                    hits.push(format!("(+{} more)", total - hits.len()));
                }
                ToolResult::ok(hits.join("\n"))
            })
        }),
        hints: None,
        concurrency_safe: None,
        output_schema: None,
        annotations: None,
    })
}
