//! Tool tiers (SPEC §4C): send only the core schemas, list the rest in a
//! `tool_search` description, and let the model load them (`call_tool` unwraps).
//! The plan is built once per toolkit and frozen until `reset_tool_tiers`.

use std::collections::BTreeMap;
use std::sync::Arc;

use serde_json::{Map, Value, json};

use crate::tool::{Tool, ToolHints, ToolResult, ToolSource};
use crate::toolkit::Toolkit;

/// A tool's tier in a plan.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolTier {
    /// Full schema in the tools array.
    Core,
    /// Name + one line in the `tool_search` description.
    Listed,
    /// Name only.
    Deferred,
}

/// How often a tool has been used (budget demotion order).
pub type UsageCountFn = Arc<dyn Fn(&str) -> usize + Send + Sync>;
/// Token estimate of a tool definition (default: JSON bytes / 4, rounded up).
pub type CountTokensFn = Arc<dyn Fn(&Value) -> usize + Send + Sync>;

/// `ClientOptions.tool_tiers`.
#[derive(Clone, Default)]
pub struct ToolTierOptions {
    pub core: Vec<String>,
    pub deferred: Vec<String>,
    /// Token budget for the core schemas; 0 ⇒ `context_window / 20` when set.
    pub budget_tokens: usize,
    pub context_window: usize,
    pub usage_count: Option<UsageCountFn>,
    pub count_tokens: Option<CountTokensFn>,
}

impl std::fmt::Debug for ToolTierOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ToolTierOptions")
            .field("core", &self.core)
            .field("deferred", &self.deferred)
            .field("budget_tokens", &self.budget_tokens)
            .field("context_window", &self.context_window)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Plan {
    pub tier: BTreeMap<String, ToolTier>,
    pub core: Vec<String>,
    pub listed: Vec<String>,
    pub deferred: Vec<String>,
    pub search: Tool,
    pub call: Tool,
}

/// Go `json.Marshal`'s HTML escaping (`<`, `>`, `&`, U+2028, U+2029).
pub(crate) fn go_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '<' => out.push_str("\\u003c"),
            '>' => out.push_str("\\u003e"),
            '&' => out.push_str("\\u0026"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            c => out.push(c),
        }
    }
    out
}

pub(crate) fn default_count_tokens(v: &Value) -> usize {
    let s = go_escape(&serde_json::to_string(v).unwrap_or_default());
    s.len().div_ceil(4)
}

pub(crate) fn tool_def(t: &Tool) -> Value {
    json!({"name": t.name, "description": t.description, "inputSchema": t.input_schema})
}

/// First line, cut to 120 bytes on a char boundary with `…`.
pub(crate) fn first_line(s: &str) -> String {
    let s = s.split('\n').next().unwrap_or("");
    const MAX: usize = 120;
    if s.len() > MAX {
        let mut n = MAX;
        while n > 0 && !s.is_char_boundary(n) {
            n -= 1;
        }
        return format!("{}…", &s[..n]);
    }
    s.to_owned()
}

fn noop_tool(name: &str, description: String, schema: Value, hints: Option<ToolHints>) -> Tool {
    Tool {
        concurrency_safe: None,
        name: name.into(),
        description,
        input_schema: schema,
        source: ToolSource::Builtin,
        execute: Arc::new(|_, _| {
            Box::pin(async { ToolResult::error("handled by the client loop") })
        }),
        hints,
    }
}

impl Plan {
    fn rebuild(&mut self, by_name: &BTreeMap<String, Tool>) {
        self.core.clear();
        self.listed.clear();
        self.deferred.clear();
        for (n, t) in &self.tier {
            match t {
                ToolTier::Core => self.core.push(n.clone()),
                ToolTier::Listed => self.listed.push(n.clone()),
                ToolTier::Deferred => self.deferred.push(n.clone()),
            }
        }
        let mut sb = String::from(
            "Load the full schema of tools that are not shown in full, then call them by name (or with call_tool). Query by keywords, or \"select:a,b\" for exact names.",
        );
        if !self.listed.is_empty() {
            sb.push_str("\n\nAvailable tools:");
            for n in &self.listed {
                sb.push_str("\n- ");
                sb.push_str(n);
                let d = first_line(by_name.get(n).map(|t| t.description.as_str()).unwrap_or(""));
                if !d.is_empty() {
                    sb.push_str(" — ");
                    sb.push_str(&d);
                }
            }
        }
        if !self.deferred.is_empty() {
            sb.push_str("\n\nMore tools (name only): ");
            sb.push_str(&self.deferred.join(", "));
        }
        self.search = noop_tool(
            "tool_search",
            sb,
            json!({"type": "object", "properties": {
                "query": {"type": "string", "description": "Keywords, or select:name1,name2"},
                "limit": {"type": "number", "description": "Maximum results for a keyword query (default 5)"}
            }, "required": ["query"], "additionalProperties": false}),
            Some(ToolHints {
                read_only: true,
                ..Default::default()
            }),
        );
        self.call = noop_tool(
            "call_tool",
            "Call a tool loaded with tool_search, by name, with its arguments.".into(),
            json!({"type": "object", "properties": {
                "name": {"type": "string"},
                "arguments": {"type": "object"}
            }, "required": ["name"], "additionalProperties": false}),
            None,
        );
    }
}

pub(crate) fn build_plan(o: &ToolTierOptions, tools: &[Tool]) -> Plan {
    let count = |v: &Value| match &o.count_tokens {
        Some(f) => f(v),
        None => default_count_tokens(v),
    };
    let usage = |n: &str| o.usage_count.as_ref().map(|f| f(n)).unwrap_or(0);
    let by_name: BTreeMap<String, Tool> =
        tools.iter().map(|t| (t.name.clone(), t.clone())).collect();
    let mut tier: BTreeMap<String, ToolTier> = tools
        .iter()
        .map(|t| (t.name.clone(), ToolTier::Listed))
        .collect();
    for n in &o.deferred {
        if let Some(t) = tier.get_mut(n) {
            *t = ToolTier::Deferred;
        }
    }
    for n in &o.core {
        if let Some(t) = tier.get_mut(n) {
            *t = ToolTier::Core;
        }
    }
    let placeholder = noop_tool("", String::new(), Value::Null, None);
    let mut p = Plan {
        tier,
        core: vec![],
        listed: vec![],
        deferred: vec![],
        search: placeholder.clone(),
        call: placeholder,
    };
    p.rebuild(&by_name);
    let mut budget = o.budget_tokens;
    if budget == 0 && o.context_window > 0 {
        budget = o.context_window / 20;
    }
    if budget > 0 {
        let cost = |p: &Plan| {
            let mut n = count(&tool_def(&p.search)) + count(&tool_def(&p.call));
            for name in &p.core {
                n += count(&tool_def(&by_name[name]));
            }
            n
        };
        let order = |names: &[String]| {
            let mut out = names.to_vec();
            out.sort_by(|a, b| {
                usage(a)
                    .cmp(&usage(b))
                    .then_with(|| count(&tool_def(&by_name[b])).cmp(&count(&tool_def(&by_name[a]))))
                    .then_with(|| a.cmp(b))
            });
            out
        };
        for n in order(&p.core) {
            if cost(&p) <= budget {
                break;
            }
            p.tier.insert(n, ToolTier::Listed);
            p.rebuild(&by_name);
        }
        for n in order(&p.listed) {
            if cost(&p) <= budget {
                break;
            }
            p.tier.insert(n, ToolTier::Deferred);
            p.rebuild(&by_name);
        }
    }
    p
}

/// `tool_search`: `select:a,b` loads exact names; otherwise keyword-scored
/// (name hit 3, description hit 1), top `limit` (default 5).
pub(crate) fn tool_search(tk: &Toolkit, args: &Value) -> ToolResult {
    let q = args
        .get("query")
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim()
        .to_owned();
    if q.is_empty() {
        return ToolResult::error("tool_search: query is required");
    }
    let mut picked: Vec<Tool> = Vec::new();
    if let Some(rest) = q.strip_prefix("select:") {
        let mut missing = Vec::new();
        for n in rest.split(',') {
            let n = n.trim();
            if n.is_empty() {
                continue;
            }
            match tk.get(n) {
                Some(t) => picked.push(t),
                None => missing.push(n.to_owned()),
            }
        }
        if !missing.is_empty() && picked.is_empty() {
            return ToolResult::error(format!("tool_search: no such tool: {}", missing.join(", ")));
        }
    } else {
        let limit = args
            .get("limit")
            .and_then(Value::as_f64)
            .filter(|l| *l >= 1.0)
            .map(|l| l as usize)
            .unwrap_or(5);
        let lower = q.to_lowercase();
        let terms: Vec<&str> = lower.split_whitespace().collect();
        let mut hits: Vec<(Tool, usize)> = Vec::new();
        for t in tk.tools() {
            let (name, desc) = (t.name.to_lowercase(), t.description.to_lowercase());
            let mut s = 0;
            for term in &terms {
                if name.contains(term) {
                    s += 3;
                }
                if desc.contains(term) {
                    s += 1;
                }
            }
            if s > 0 {
                hits.push((t, s));
            }
        }
        hits.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.name.cmp(&b.0.name)));
        picked = hits.into_iter().take(limit).map(|(t, _)| t).collect();
    }
    if picked.is_empty() {
        let mut meta = Map::new();
        meta.insert("count".into(), json!(0));
        return ToolResult::ok("No matching tools.").with_metadata(meta);
    }
    let defs: Vec<Value> = picked.iter().map(tool_def).collect();
    let names: Vec<&str> = picked.iter().map(|t| t.name.as_str()).collect();
    let pretty = go_escape(&serde_json::to_string_pretty(&defs).unwrap_or_default());
    let mut meta = Map::new();
    meta.insert("count".into(), json!(picked.len()));
    meta.insert("tools".into(), json!(names));
    ToolResult::ok(format!(
        "Loaded tools (call by name, or with call_tool):\n{pretty}"
    ))
    .with_metadata(meta)
}
