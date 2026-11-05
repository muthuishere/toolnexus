//! `list_mcp_resources` / `read_mcp_resource` (port of `golang/mcp_resources.go`).

use std::collections::BTreeMap;
use std::sync::Arc;

use rmcp::model::ReadResourceRequestParams;
use serde_json::{Map, Value, json};

use super::ServerConn;
use crate::tool::{Tool, ToolHints, ToolResult, ToolSource};

fn ro() -> Option<ToolHints> {
    Some(ToolHints {
        read_only: true,
        ..Default::default()
    })
}

fn conn<'a>(
    servers: &'a BTreeMap<String, ServerConn>,
    name: &str,
) -> Result<&'a ServerConn, String> {
    servers.get(name).ok_or_else(|| {
        format!(
            "no connected MCP server {name:?} (connected: {})",
            servers.keys().cloned().collect::<Vec<_>>().join(", ")
        )
    })
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_owned()
}

pub(crate) fn resource_tools(servers: BTreeMap<String, ServerConn>) -> Vec<Tool> {
    let servers = Arc::new(servers);
    let ls = servers.clone();
    let list = Tool { output_schema: None, annotations: None,
        concurrency_safe: None,
        name: "list_mcp_resources".into(),
        description: "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.".into(),
        input_schema: json!({"type": "object", "properties": {
            "server": {"type": "string", "description": "Only this server (default: all)"}
        }, "additionalProperties": false}),
        source: ToolSource::Mcp,
        hints: ro(),
        execute: Arc::new(move |args, ctx| {
            let servers = ls.clone();
            Box::pin(async move {
                let mut names: Vec<String> = servers.keys().cloned().collect();
                if let Some(one) = args.get("server").and_then(Value::as_str).filter(|x| !x.is_empty()) {
                    if let Err(e) = conn(&servers, one) {
                        return ToolResult::error(format!("list_mcp_resources: {e}"));
                    }
                    names = vec![one.to_owned()];
                }
                let mut lines = Vec::new();
                let mut items = Vec::new();
                for name in names {
                    let Ok(c) = conn(&servers, &name) else { continue };
                    let res = tokio::select! {
                        r = tokio::time::timeout(c.timeout, c.peer.list_all_resources()) => match r {
                            Ok(Ok(v)) => Ok(v),
                            Ok(Err(e)) => Err(e.to_string()),
                            Err(_) => Err("context deadline exceeded".to_owned()),
                        },
                        _ = ctx.cancel.cancelled() => Err("context canceled".to_owned()),
                    };
                    let resources = match res {
                        Ok(r) => r,
                        Err(e) => {
                            lines.push(format!("{name}: error: {e}"));
                            continue;
                        }
                    };
                    for r in resources {
                        let v = serde_json::to_value(&r).unwrap_or_default();
                        let (uri, rname, mime, desc) =
                            (s(&v, "uri"), s(&v, "name"), s(&v, "mimeType"), s(&v, "description"));
                        let mut line = format!("{name} {uri}");
                        if !rname.is_empty() {
                            line.push_str(&format!(" — {rname}"));
                        }
                        if !mime.is_empty() {
                            line.push_str(&format!(" ({mime})"));
                        }
                        if !desc.is_empty() {
                            line.push_str(&format!(": {desc}"));
                        }
                        lines.push(line);
                        items.push(json!({"server": name, "uri": uri, "name": rname, "mimeType": mime, "description": desc}));
                    }
                }
                let mut meta = Map::new();
                if lines.is_empty() {
                    meta.insert("count".into(), json!(0));
                    return ToolResult::ok("No resources.").with_metadata(meta);
                }
                meta.insert("count".into(), json!(items.len()));
                meta.insert("resources".into(), Value::Array(items));
                ToolResult::ok(lines.join("\n")).with_metadata(meta)
            })
        }),
    };
    let rs = servers;
    let read = Tool { output_schema: None, annotations: None,
        concurrency_safe: None,
        name: "read_mcp_resource".into(),
        description: "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.".into(),
        input_schema: json!({"type": "object", "properties": {
            "server": {"type": "string", "description": "The MCP server name"},
            "uri": {"type": "string", "description": "The resource uri"}
        }, "required": ["server", "uri"], "additionalProperties": false}),
        source: ToolSource::Mcp,
        hints: ro(),
        execute: Arc::new(move |args, ctx| {
            let servers = rs.clone();
            Box::pin(async move {
                let server = args.get("server").and_then(Value::as_str).unwrap_or("").to_owned();
                let uri = args.get("uri").and_then(Value::as_str).unwrap_or("").to_owned();
                let c = match conn(&servers, &server) {
                    Ok(c) => c,
                    Err(e) => return ToolResult::error(format!("read_mcp_resource: {e}")),
                };
                if uri.is_empty() {
                    return ToolResult::error("read_mcp_resource: uri is required");
                }
                let res = tokio::select! {
                    r = tokio::time::timeout(c.timeout, c.peer.read_resource(ReadResourceRequestParams::new(uri.clone()))) => match r {
                        Ok(Ok(v)) => Ok(v),
                        Ok(Err(e)) => Err(e.to_string()),
                        Err(_) => Err("context deadline exceeded".to_owned()),
                    },
                    _ = ctx.cancel.cancelled() => Err("context canceled".to_owned()),
                };
                let res = match res {
                    Ok(r) => r,
                    Err(e) => return ToolResult::error(format!("read_mcp_resource: {e}")),
                };
                let v = serde_json::to_value(&res).unwrap_or_default();
                let mut parts = Vec::new();
                for rc in v.get("contents").and_then(Value::as_array).cloned().unwrap_or_default() {
                    if let Some(t) = rc.get("text").and_then(Value::as_str) {
                        parts.push(t.to_owned());
                    } else if let Some(b) = rc.get("blob").and_then(Value::as_str) {
                        parts.push(format!("[binary {}, {} base64 chars]", s(&rc, "mimeType"), b.len()));
                    }
                }
                let mut meta = Map::new();
                meta.insert("server".into(), json!(server));
                meta.insert("uri".into(), json!(uri));
                ToolResult::ok(parts.join("\n")).with_metadata(meta)
            })
        }),
    };
    vec![list, read]
}
