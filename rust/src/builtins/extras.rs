//! question / todowrite (default) and websearch / notebook_edit (opt-in).
//! Ports of `golang/builtin.go` and `golang/builtin_extras.go`.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use super::diff::{FileChange, add_diff_meta};
use super::{
    Args, BuiltinEnv, arg_num, arg_str, as_array, b_err, b_ok, builtin, go_escape, go_io_err,
    go_json, meta,
};
use crate::tool::{Request, Tool, pending};

// ---------------------------------------------------------------------------
// question
// ---------------------------------------------------------------------------

fn value_text(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Null => "<nil>".into(),
        other => other.to_string(),
    }
}

/// Each question in order, `" (options: a, b)"` when it has options, `\n`-joined.
pub fn render_question_prompt(questions: &[Value]) -> String {
    questions
        .iter()
        .map(|q| {
            let mut line = q
                .get("question")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            if let Some(Value::Array(opts)) = q.get("options")
                && !opts.is_empty()
            {
                let opts: Vec<String> = opts.iter().map(value_text).collect();
                line.push_str(&format!(" (options: {})", opts.join(", ")));
            }
            line
        })
        .collect::<Vec<_>>()
        .join("\n")
}

pub(crate) fn question_tool() -> Tool {
    builtin(
        "question",
        "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
        json!({
            "type": "object",
            "properties": {
                "questions": {
                    "type": "array",
                    "description": "Questions to ask",
                    "items": {
                        "type": "object",
                        "properties": {
                            "question": {"type": "string"},
                            "header": {"type": "string"},
                            "options": {"type": "array", "items": {"type": "string"}},
                            "multiple": {"type": "boolean"}
                        },
                        "required": ["question"]
                    }
                }
            },
            "required": ["questions"],
            "additionalProperties": false
        }),
        |args, ctx| async move {
            let questions = as_array(args.get("questions"));
            if let Some(answer) = &ctx.answer {
                let data = Value::Object(answer.data.clone().unwrap_or_default());
                return b_ok(go_json(&data), None);
            }
            let mut data = Map::new();
            let prompt = render_question_prompt(&questions);
            data.insert("questions".into(), Value::Array(questions));
            pending(Request {
                kind: "question".into(),
                prompt,
                data: Some(data),
                ..Default::default()
            })
        },
    )
}

// ---------------------------------------------------------------------------
// todowrite
// ---------------------------------------------------------------------------

pub(crate) fn todowrite_tool() -> Tool {
    builtin(
        "todowrite",
        "Replace the session todo list. Returns the rendered list.",
        json!({
            "type": "object",
            "properties": {
                "todos": {
                    "type": "array",
                    "description": "The full todo list to store",
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "text": {"type": "string"},
                            "completed": {"type": "boolean"}
                        },
                        "required": ["id", "text", "completed"]
                    }
                }
            },
            "required": ["todos"],
            "additionalProperties": false
        }),
        |args, _ctx| async move {
            let todos = as_array(args.get("todos"));
            let lines: Vec<String> = todos
                .iter()
                .map(|item| {
                    let text = item.get("text").and_then(Value::as_str).unwrap_or("");
                    let done = item
                        .get("completed")
                        .and_then(Value::as_bool)
                        .unwrap_or(false);
                    format!("[{}] {text}", if done { "x" } else { " " })
                })
                .collect();
            let mut rendered = lines.join("\n");
            if rendered.is_empty() {
                rendered = "(no todos)".into();
            }
            b_ok(rendered, meta(vec![("todos", Value::Array(todos))]))
        },
    )
}

// ---------------------------------------------------------------------------
// websearch (opt-in)
// ---------------------------------------------------------------------------

/// One search hit.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct WebSearchResult {
    pub title: String,
    pub url: String,
    pub snippet: String,
}

/// The future a [`WebSearchFn`] returns.
pub type WebSearchFuture =
    Pin<Box<dyn Future<Output = Result<Vec<WebSearchResult>, String>> + Send>>;

/// A host search provider: `(query, limit, cancel) -> results`.
pub type WebSearchFn =
    Arc<dyn Fn(String, usize, CancellationToken) -> WebSearchFuture + Send + Sync>;

pub(crate) fn websearch_tool(search: WebSearchFn) -> Tool {
    builtin(
        "websearch",
        "Search the web. Returns numbered results with title, URL and snippet.",
        json!({
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "The search query"},
                "limit": {"type": "number", "description": "Maximum number of results (default 5)"}
            },
            "required": ["query"],
            "additionalProperties": false
        }),
        move |args, ctx| {
            let search = search.clone();
            async move {
                let q = arg_str(&args, "query").unwrap_or("").to_owned();
                if q.trim().is_empty() {
                    return b_err("websearch: query is required", None);
                }
                let mut limit = 5usize;
                if let Some(l) = arg_num(&args, "limit")
                    && l >= 1.0
                {
                    limit = l as usize;
                }
                let mut results = match search(q, limit, ctx.cancel.clone()).await {
                    Ok(r) => r,
                    Err(e) => return b_err(format!("websearch: {e}"), None),
                };
                results.truncate(limit);
                if results.is_empty() {
                    return b_ok("No results.", meta(vec![("count", json!(0))]));
                }
                let mut sb = String::new();
                for (i, r) in results.iter().enumerate() {
                    if i > 0 {
                        sb.push_str("\n\n");
                    }
                    sb.push_str(&format!("{}. {}\n   {}", i + 1, r.title, r.url));
                    if !r.snippet.is_empty() {
                        sb.push_str(&format!("\n   {}", r.snippet));
                    }
                }
                let count = results.len();
                b_ok(
                    sb,
                    meta(vec![
                        ("count", json!(count)),
                        (
                            "results",
                            serde_json::to_value(&results).unwrap_or_default(),
                        ),
                    ]),
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// notebook_edit (opt-in)
// ---------------------------------------------------------------------------

fn notebook_source(s: &str) -> Value {
    if s.is_empty() {
        return json!([]);
    }
    Value::Array(
        s.split_inclusive('\n')
            .filter(|p| !p.is_empty())
            .map(|p| Value::String(p.to_owned()))
            .collect(),
    )
}

/// Go `json.MarshalIndent(v, "", " ")` + trailing newline.
fn go_indent_json(v: &Value) -> String {
    use serde::Serialize as _;
    let mut buf = Vec::new();
    let fmt = serde_json::ser::PrettyFormatter::with_indent(b" ");
    let mut ser = serde_json::Serializer::with_formatter(&mut buf, fmt);
    let _ = v.serialize(&mut ser);
    let mut s = go_escape(&String::from_utf8_lossy(&buf));
    s.push('\n');
    s
}

pub(crate) fn notebook_edit_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "notebook_edit",
        "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, or delete it. Replacing a code cell clears its outputs.",
        json!({
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the .ipynb file"},
                "cell_id": {"type": "string", "description": "The cell's id (or use cell_index)"},
                "cell_index": {"type": "number", "description": "0-based cell position (used when cell_id is absent)"},
                "new_source": {"type": "string", "description": "The new cell source (replace/insert)"},
                "cell_type": {"type": "string", "enum": ["code", "markdown"], "description": "Cell type for insert (default code) or to change on replace"},
                "edit_mode": {"type": "string", "enum": ["replace", "insert", "delete"], "description": "Default replace. insert adds the new cell AFTER the given cell, or first when none is given"}
            },
            "required": ["path"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move { notebook_impl(&env, &args) }
        },
    )
}

fn notebook_impl(env: &BuiltinEnv, args: &Args) -> crate::tool::ToolResult {
    let p = arg_str(args, "path").unwrap_or("");
    if p.is_empty() {
        return b_err("notebook_edit: path is required", None);
    }
    let full = match env.resolve_path(p) {
        Ok(f) => f,
        Err(e) => return b_err(format!("notebook_edit: {e}"), None),
    };
    let raw = match std::fs::read(&full) {
        Ok(r) => String::from_utf8_lossy(&r).into_owned(),
        Err(e) => {
            return b_err(
                format!("notebook_edit: {}", go_io_err("open", &full, &e)),
                None,
            );
        }
    };
    let mut nb: Map<String, Value> = match serde_json::from_str(&raw) {
        Ok(Value::Object(m)) => m,
        Ok(_) => {
            return b_err(
                format!("notebook_edit: {p} is not a notebook: not a JSON object"),
                None,
            );
        }
        Err(e) => return b_err(format!("notebook_edit: {p} is not a notebook: {e}"), None),
    };
    let mut cells = match nb.get("cells") {
        Some(Value::Array(a)) => a.clone(),
        _ => Vec::new(),
    };
    let mode = match arg_str(args, "edit_mode") {
        Some(m) if !m.is_empty() => m.to_owned(),
        _ => "replace".to_owned(),
    };
    let mut idx: i64 = -1;
    if let Some(id) = arg_str(args, "cell_id").filter(|s| !s.is_empty()) {
        match cells
            .iter()
            .position(|c| c.get("id").and_then(Value::as_str) == Some(id))
        {
            Some(i) => idx = i as i64,
            None => return b_err(format!("notebook_edit: no cell with id {id:?}"), None),
        }
    } else if let Some(n) = arg_num(args, "cell_index") {
        idx = n as i64;
        if idx < 0 || idx >= cells.len() as i64 {
            return b_err(
                format!(
                    "notebook_edit: cell_index {idx} out of range (0-{})",
                    cells.len() as i64 - 1
                ),
                None,
            );
        }
    } else if mode != "insert" {
        return b_err("notebook_edit: cell_id or cell_index is required", None);
    }
    let src = arg_str(args, "new_source").unwrap_or("");
    let cell_type = arg_str(args, "cell_type").unwrap_or("");
    let summary = match mode.as_str() {
        "replace" => {
            let Some(Value::Object(cm)) = cells.get_mut(idx as usize) else {
                return b_err("notebook_edit: cell is not an object", None);
            };
            cm.insert("source".into(), notebook_source(src));
            if !cell_type.is_empty() {
                cm.insert("cell_type".into(), json!(cell_type));
            }
            if cm.get("cell_type").and_then(Value::as_str) == Some("code") {
                cm.insert("outputs".into(), json!([]));
                cm.insert("execution_count".into(), Value::Null);
            } else {
                cm.remove("outputs");
                cm.remove("execution_count");
            }
            format!("Replaced cell {idx} in {p}")
        }
        "insert" => {
            let ct = if cell_type.is_empty() {
                "code"
            } else {
                cell_type
            };
            let mut cell = Map::new();
            cell.insert("cell_type".into(), json!(ct));
            cell.insert("metadata".into(), json!({}));
            cell.insert("source".into(), notebook_source(src));
            if ct == "code" {
                cell.insert("outputs".into(), json!([]));
                cell.insert("execution_count".into(), Value::Null);
            }
            let at = (idx + 1) as usize;
            cells.insert(at, Value::Object(cell));
            format!("Inserted {ct} cell at {at} in {p}")
        }
        "delete" => {
            cells.remove(idx as usize);
            format!("Deleted cell {idx} from {p}")
        }
        other => return b_err(format!("notebook_edit: unknown edit_mode {other:?}"), None),
    };
    let n_cells = cells.len();
    nb.insert("cells".into(), Value::Array(cells));
    let out = go_indent_json(&Value::Object(nb));
    if let Err(e) = std::fs::write(&full, &out) {
        return b_err(
            format!("notebook_edit: {}", go_io_err("open", &full, &e)),
            None,
        );
    }
    let mut m = meta(vec![("cells", json!(n_cells))]).unwrap_or_default();
    add_diff_meta(
        &mut m,
        &[FileChange {
            path: env.diff_path(p, &full),
            status: "modified",
            before: raw,
            after: out,
        }],
    );
    b_ok(summary, Some(m))
}
