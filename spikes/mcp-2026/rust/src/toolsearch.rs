//! Lazy tool search: with `Client.tool_search` the LLM sees two small tools instead of all N:
//! search_tools(query) -> ranked {name, description, inputSchema, server}, and
//! call_tool(name, arguments) to run one it found. Ranking is BM25 over name (x2) + description.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use serde_json::{Value, json};

use crate::{Client, Error, Obj, Responder};

impl Client {
    /// The tool definitions to give the model: every server tool, or (tool_search) the two
    /// meta-tools. Run them with `invoke`.
    pub async fn tools(&self) -> Result<Vec<Obj>, Error> {
        if !self.tool_search {
            return self.list_tools().await;
        }
        let about = if self.description.is_empty() {
            "this MCP server"
        } else {
            self.description.as_str()
        };
        let o = |v: Value| v.as_object().cloned().unwrap();
        Ok(vec![
            o(
                json!({"name": "search_tools", "description": format!("Search the tools of {about} by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."),
                "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]}}),
            ),
            o(
                json!({"name": "call_tool", "description": "Run a tool found with search_tools.",
                "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "arguments": {"type": "object"}}, "required": ["name"]}}),
            ),
        ])
    }

    /// Runs a tool the model asked for: a server tool, or search_tools / call_tool in tool_search mode.
    pub async fn invoke(
        &self,
        name: &str,
        args: Value,
        respond: Option<Responder>,
    ) -> Result<Obj, Error> {
        if !self.tool_search {
            return self.call_tool(name, args, respond).await;
        }
        match name {
            "search_tools" => {
                let q = args
                    .get("query")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let limit = args
                    .get("limit")
                    .and_then(Value::as_f64)
                    .filter(|l| *l > 0.0)
                    .map_or(5, |l| l as usize);
                let hits = self.search_tools(q, limit).await?;
                let text = serde_json::to_string(&hits).unwrap();
                Ok(
                    crate::obj!({"resultType": "complete", "content": [{"type": "text", "text": text}],
                    "structuredContent": {"tools": hits}, "isError": false}),
                )
            }
            "call_tool" => {
                let n = args.get("name").and_then(Value::as_str).unwrap_or_default();
                let a = args
                    .get("arguments")
                    .cloned()
                    .filter(Value::is_object)
                    .unwrap_or(Value::Null);
                self.call_tool(n, a, respond).await
            }
            _ => Err(Error::msg(format!(
                "unknown tool {name:?} (ToolSearch mode exposes search_tools and call_tool)"
            ))),
        }
    }

    /// Ranks the server's tools against query (BM25; name tokens count twice).
    pub async fn search_tools(&self, query: &str, limit: usize) -> Result<Vec<Obj>, Error> {
        let tools = self.list_tools().await?;
        let s = |t: &Obj, k: &str| {
            t.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string()
        };
        let mut df: HashMap<String, usize> = HashMap::new();
        let mut total = 0;
        let docs: Vec<Vec<String>> = tools
            .iter()
            .map(|t| {
                let nt = tokens(&s(t, "name"));
                let mut d = nt.clone();
                d.extend(nt);
                d.extend(tokens(&s(t, "description")));
                total += d.len();
                for w in d.iter().collect::<HashSet<_>>() {
                    *df.entry(w.clone()).or_default() += 1;
                }
                d
            })
            .collect();
        let n = tools.len() as f64;
        let avg = total as f64 / n.max(1.0);
        let mut hits: Vec<(usize, f64)> = vec![];
        for (i, d) in docs.iter().enumerate() {
            let mut tf: HashMap<&str, usize> = HashMap::new();
            for w in d {
                *tf.entry(w).or_default() += 1;
            }
            let mut score = 0.0;
            for q in tokens(query) {
                let f = *tf.get(q.as_str()).unwrap_or(&0) as f64;
                if f == 0.0 {
                    continue;
                }
                let dfq = df[&q] as f64;
                let idf = (1.0 + (n - dfq + 0.5) / (dfq + 0.5)).ln();
                score += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * d.len() as f64 / avg));
            }
            if score > 0.0 {
                hits.push((i, score));
            }
        }
        hits.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap()); // stable
        Ok(hits
            .iter()
            .take(limit)
            .map(|(i, _)| {
                let t = &tools[*i];
                crate::obj!({"name": t.get("name"), "description": t.get("description"),
                    "inputSchema": t.get("inputSchema"), "server": self.description})
            })
            .collect())
    }

    /// tools/list, cached for the server's ttlMs.
    pub(crate) async fn list_tools(&self) -> Result<Vec<Obj>, Error> {
        let mut cache = self.tool_cache.lock().await;
        if let Some((tools, exp)) = cache.as_ref()
            && Instant::now() < *exp
        {
            return Ok(tools.clone());
        }
        let r = self.call("tools/list", Value::Null).await?;
        let tools: Vec<Obj> = r
            .get("tools")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(|t| t.as_object().cloned()).collect())
            .unwrap_or_default();
        let ttl = r
            .get("ttlMs")
            .and_then(Value::as_f64)
            .unwrap_or(0.0)
            .max(0.0);
        *cache = Some((
            tools.clone(),
            Instant::now() + Duration::from_millis(ttl as u64),
        ));
        Ok(tools)
    }
}

/// Lowercases and splits on non-alphanumerics and camelCase boundaries.
pub fn tokens(s: &str) -> Vec<String> {
    let mut out = vec![];
    let mut cur = String::new();
    let mut prev_lower = false;
    for r in s.chars() {
        if r.is_uppercase() && prev_lower {
            if !cur.is_empty() {
                out.push(cur.to_lowercase());
                cur.clear();
            }
            cur.push(r);
        } else if r.is_alphanumeric() {
            cur.push(r);
        } else if !cur.is_empty() {
            out.push(cur.to_lowercase());
            cur.clear();
        }
        prev_lower = r.is_lowercase();
    }
    if !cur.is_empty() {
        out.push(cur.to_lowercase());
    }
    out
}
