//! Stateless MCP 2026-07-28 server over `POST /mcp`: header validation (-32020 / -32022),
//! server/discover, tools (fixture echo + greet MRTR, plus code-defined tools), the skills
//! extension (skills/list, skills/get, resources/read on skill:// URIs), optional OAuth.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use base64::Engine;
use serde_json::{Value, json};

use crate::http::{Handler, IntoHandler, Request, Response};
use crate::serverauth::{Principal, ServerAuth};
use crate::skills::parse_skill_uri;
use crate::{
    CODE_HEADER_MISMATCH, CODE_INVALID_PARAMS, CODE_METHOD_NOT_FOUND, CODE_UNSUPPORTED_PROTOCOL,
    META_SERVER_INFO, META_VERSION, Obj, PROTOCOL_VERSION, sha256_hex,
};

/// What a tool handler sees besides its arguments.
#[derive(Debug, Clone, Default)]
pub struct ToolContext {
    /// The verified caller (None on an unprotected server or over stdio).
    pub principal: Option<Principal>,
}

/// Handles one tool call. Return a string (text result), an object with "content" (a full
/// CallToolResult), or any JSON value (structured). Err(msg) becomes an isError result.
pub type ToolFn = Arc<dyn Fn(&ToolContext, Obj) -> Result<Value, String> + Send + Sync>;

#[derive(Clone)]
struct SkillFile {
    uri: String,
    data: Vec<u8>,
}

#[derive(Clone)]
struct Skill {
    uri: String,
    frontmatter: Obj,
    files: Vec<SkillFile>, // sorted by uri; includes SKILL.md
}

pub(crate) struct RpcErr {
    pub code: i64,
    pub message: String,
    pub data: Option<Value>,
}

fn rpc_err(code: i64, message: impl Into<String>) -> RpcErr {
    RpcErr {
        code,
        message: message.into(),
        data: None,
    }
}

/// The MCP server. Build it, set `auth`, then serve it (`HttpServer::start(srv)`).
#[derive(Default)]
pub struct Server {
    info: Value,
    skills: Vec<Skill>,
    /// None = unprotected (the default; cross-port checks run without auth).
    pub auth: Option<ServerAuth>,
    handlers: HashMap<String, ToolFn>,
    tools: Vec<Value>, // fixture + code tools, sorted by name
}

impl Server {
    /// An empty server: add tools with `tool`, protect it with `auth`.
    pub fn new() -> Server {
        Server::default()
    }

    /// Registers a code-defined tool (listed in tools/list; checked before the fixture tools).
    pub fn tool<F>(mut self, name: &str, description: &str, f: F) -> Server
    where
        F: Fn(&ToolContext, Obj) -> Result<Value, String> + Send + Sync + 'static,
    {
        self.add_tool(name, description, f);
        self
    }

    pub fn add_tool<F>(&mut self, name: &str, description: &str, f: F)
    where
        F: Fn(&ToolContext, Obj) -> Result<Value, String> + Send + Sync + 'static,
    {
        self.handlers.insert(name.to_string(), Arc::new(f));
        self.tools.retain(|t| t["name"] != name);
        self.tools.push(
            json!({"name": name, "description": description, "inputSchema": {"type": "object"}}),
        );
        self.sort_tools();
    }

    fn sort_tools(&mut self) {
        self.tools
            .sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
    }

    /// Loads a fixture JSON (`server`, `tools`, `skillsDir` relative to the fixture file).
    pub fn load(fixture_path: impl AsRef<Path>) -> Result<Server, String> {
        let p = fixture_path.as_ref();
        let raw = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
        let fx: Value = serde_json::from_slice(&raw).map_err(|e| e.to_string())?;
        let mut s = Server {
            info: fx.get("server").cloned().unwrap_or(Value::Null),
            ..Default::default()
        };
        for t in fx
            .get("tools")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default()
        {
            if !t.get("name").is_some_and(Value::is_string) {
                return Err(format!("fixture tool without a name: {t}"));
            }
            s.tools.push(t);
        }
        s.sort_tools();
        let dir = fx
            .get("skillsDir")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let root = p.parent().unwrap_or(Path::new(".")).join(dir);
        let mut found = vec![];
        walk(&root, &mut found)?;
        for f in found
            .iter()
            .filter(|f| f.file_name().is_some_and(|n| n == "SKILL.md"))
        {
            s.skills.push(load_skill(f.parent().unwrap())?);
        }
        s.skills.sort_by(|a, b| a.uri.cmp(&b.uri));
        Ok(s)
    }

    fn server_info(&self) -> Value {
        json!({META_SERVER_INFO: self.info})
    }

    /// Serves one HTTP request (what the HTTP layer calls).
    pub async fn handle(&self, r: Request) -> Response {
        if let Some(a) = &self.auth
            && r.method == "GET"
            && let Some(resp) = a.serve_metadata(&r)
        {
            return resp;
        }
        if r.path != "/mcp" {
            return Response::text(404, "404 page not found");
        }
        if r.method != "POST" {
            return Response::status(405);
        }
        if r.body_error {
            return write_rpc(
                413,
                Value::Null,
                None,
                Some(rpc_err(-32600, "Request body too large or unreadable")),
            );
        }
        let parse_err = || write_rpc(400, Value::Null, None, Some(rpc_err(-32700, "Parse error")));
        let Ok(Value::Object(req)) = serde_json::from_slice::<Value>(&r.body) else {
            return parse_err();
        };
        let id = req.get("id").cloned().unwrap_or(Value::Null);
        let method = match req.get("method") {
            None | Some(Value::Null) => "",
            Some(Value::String(s)) => s.as_str(),
            Some(_) => return parse_err(),
        };
        let params = match req.get("params") {
            None | Some(Value::Null) => Obj::new(),
            Some(Value::Object(m)) => m.clone(),
            Some(_) => return parse_err(),
        };
        if id.is_null() {
            return Response::status(202); // notification: none defined in core; accept
        }
        if let Err((status, e)) = validate(&r, method, &params) {
            return write_rpc(status, id, None, Some(e));
        }
        let mut ctx = ToolContext::default();
        if let Some(a) = &self.auth {
            match a.check(&r, method, &params).await {
                Ok(p) => ctx.principal = Some(p),
                Err(resp) => return resp,
            }
        }
        let res = self.dispatch(&ctx, method, &params);
        let status = match &res {
            Err(e) if e.code == CODE_METHOD_NOT_FOUND => 404,
            _ => 200,
        };
        match res {
            Ok(v) => write_rpc(status, id, Some(v), None),
            Err(e) => write_rpc(status, id, None, Some(e)),
        }
    }

    pub(crate) fn dispatch(
        &self,
        ctx: &ToolContext,
        method: &str,
        p: &Obj,
    ) -> Result<Value, RpcErr> {
        let uri = p.get("uri").and_then(Value::as_str).unwrap_or_default();
        match method {
            "server/discover" => Ok(
                json!({"resultType": "complete", "supportedVersions": [PROTOCOL_VERSION],
                "capabilities": {"tools": {}, "resources": {}, "extensions": {crate::SKILLS_EXT: {}}},
                "_meta": self.server_info(), "ttlMs": 3600000, "cacheScope": "public"}),
            ),
            "tools/list" => Ok(
                json!({"resultType": "complete", "tools": self.tools, "ttlMs": 60000,
                "cacheScope": "public", "_meta": self.server_info()}),
            ),
            "tools/call" => self.call_tool(ctx, p),
            "skills/list" => {
                let list: Vec<Value> = self.skills.iter().map(Skill::entry).collect();
                Ok(
                    json!({"resultType": "complete", "skills": list, "ttlMs": 60000, "cacheScope": "public",
                    "_meta": self.server_info()}),
                )
            }
            "skills/get" => {
                if let Err(e) = parse_skill_uri(uri) {
                    return Err(rpc_err(
                        CODE_INVALID_PARAMS,
                        format!("Invalid skill URI: {e}"),
                    ));
                }
                match self.skills.iter().find(|s| s.uri == uri) {
                    Some(sk) => Ok(
                        json!({"resultType": "complete", "skill": sk.entry(), "_meta": self.server_info()}),
                    ),
                    None => Err(rpc_err(
                        CODE_INVALID_PARAMS,
                        format!("Unknown skill: {uri}"),
                    )),
                }
            }
            "resources/read" => {
                if let Err(e) = parse_skill_uri(uri) {
                    return Err(rpc_err(
                        CODE_INVALID_PARAMS,
                        format!("Invalid resource URI: {e}"),
                    ));
                }
                for f in self.skills.iter().flat_map(|s| &s.files) {
                    if f.uri == uri {
                        let mime = if uri.ends_with(".md") {
                            "text/markdown"
                        } else {
                            "text/plain"
                        };
                        return Ok(json!({"resultType": "complete",
                            "contents": [{"uri": uri, "mimeType": mime, "text": String::from_utf8_lossy(&f.data)}],
                            "ttlMs": 60000, "cacheScope": "public", "_meta": self.server_info()}));
                    }
                }
                Err(rpc_err(
                    CODE_INVALID_PARAMS,
                    format!("Resource not found: {uri}"),
                ))
            }
            _ => Err(rpc_err(
                CODE_METHOD_NOT_FOUND,
                format!("Method not found: {method}"),
            )),
        }
    }

    fn call_tool(&self, ctx: &ToolContext, p: &Obj) -> Result<Value, RpcErr> {
        let name = p.get("name").and_then(Value::as_str).unwrap_or_default();
        let args = p
            .get("arguments")
            .and_then(Value::as_object)
            .cloned()
            .unwrap_or_default();
        if let Some(h) = self.handlers.get(name) {
            return Ok(match h(ctx, args) {
                Err(e) => {
                    json!({"resultType": "complete", "content": [{"type": "text", "text": e}], "isError": true})
                }
                Ok(Value::String(s)) => {
                    json!({"resultType": "complete", "content": [{"type": "text", "text": s}], "isError": false})
                }
                Ok(Value::Object(mut m)) if m.contains_key("content") => {
                    m.entry("resultType").or_insert(json!("complete"));
                    Value::Object(m)
                }
                Ok(v) => {
                    json!({"resultType": "complete", "content": [{"type": "text", "text": v.to_string()}],
                    "structuredContent": v, "isError": false})
                }
            });
        }
        let text = |t: &str, structured: Value| {
            json!({"resultType": "complete", "content": [{"type": "text", "text": t}], "isError": false,
                "_meta": self.server_info(), "structuredContent": structured})
        };
        match name {
            "echo" => {
                let t = args.get("text").and_then(Value::as_str).unwrap_or_default();
                Ok(text(t, json!({"text": t})))
            }
            "greet" => {
                let n = p
                    .get("inputResponses")
                    .and_then(|r| r.get("name"))
                    .filter(|nr| nr.get("action").and_then(Value::as_str) == Some("accept"))
                    .and_then(|nr| nr.get("content"))
                    .and_then(|c| c.get("name"))
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                if !n.is_empty() {
                    let g = format!("Hello, {n}!");
                    return Ok(text(&g, json!({"greeting": g})));
                }
                // Missing/declined answer: ask (again), per MRTR error-handling guidance.
                Ok(
                    json!({"resultType": "input_required", "_meta": self.server_info(), "inputRequests": {
                        "name": {"method": "elicitation/create", "params": {
                            "mode": "form", "message": "What is your name?",
                            "requestedSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
                        }},
                    }}),
                )
            }
            _ => Err(rpc_err(
                CODE_INVALID_PARAMS,
                format!("Unknown tool: {name}"),
            )),
        }
    }
}

impl IntoHandler for Arc<Server> {
    fn into_handler(self) -> Handler {
        Arc::new(move |r| {
            let s = self.clone();
            Box::pin(async move { s.handle(r).await })
        })
    }
}

impl IntoHandler for Server {
    fn into_handler(self) -> Handler {
        Arc::new(self).into_handler()
    }
}

fn validate(r: &Request, method: &str, p: &Obj) -> Result<(), (u16, RpcErr)> {
    let mismatch = |m: String| {
        Err((
            400,
            rpc_err(CODE_HEADER_MISMATCH, format!("Header mismatch: {m}")),
        ))
    };
    let hv = r.header("MCP-Protocol-Version");
    if hv.is_empty() {
        return mismatch("MCP-Protocol-Version header missing".into());
    }
    let hm = r.header("Mcp-Method");
    if hm != method {
        return mismatch(format!(
            "Mcp-Method header value '{hm}' does not match body value '{method}'"
        ));
    }
    if matches!(method, "tools/call" | "resources/read" | "prompts/get") {
        let key = if method == "resources/read" {
            "uri"
        } else {
            "name"
        };
        let want = p.get(key).and_then(Value::as_str).unwrap_or_default();
        let (got, ok) = decode_header(&r.header("Mcp-Name"));
        if !ok || got != want {
            return mismatch(format!(
                "Mcp-Name header value '{got}' does not match body value '{want}'"
            ));
        }
    }
    let mv = p
        .get("_meta")
        .and_then(|m| m.get(META_VERSION))
        .and_then(Value::as_str)
        .unwrap_or_default();
    if mv != hv {
        return mismatch(format!(
            "MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'"
        ));
    }
    if mv != PROTOCOL_VERSION {
        return Err((400, unsupported(mv)));
    }
    Ok(())
}

pub(crate) fn unsupported(mv: &str) -> RpcErr {
    RpcErr {
        code: CODE_UNSUPPORTED_PROTOCOL,
        message: "Unsupported protocol version".into(),
        data: Some(json!({"supported": [PROTOCOL_VERSION], "requested": mv})),
    }
}

fn decode_header(v: &str) -> (String, bool) {
    if v.starts_with("=?base64?") && v.ends_with("?=") && v.len() >= 11 {
        return match base64::engine::general_purpose::STANDARD.decode(&v[9..v.len() - 2]) {
            Ok(b) => (String::from_utf8_lossy(&b).into_owned(), true),
            Err(_) => (String::new(), false),
        };
    }
    (v.to_string(), !v.is_empty())
}

pub(crate) fn rpc_message(id: Value, result: Option<Value>, e: Option<RpcErr>) -> Value {
    let mut msg = json!({"jsonrpc": "2.0", "id": id});
    match e {
        Some(e) => {
            let mut err = json!({"code": e.code, "message": e.message});
            if let Some(d) = e.data {
                err["data"] = d;
            }
            msg["error"] = err;
        }
        None => msg["result"] = result.unwrap_or(Value::Null),
    }
    msg
}

fn write_rpc(status: u16, id: Value, result: Option<Value>, e: Option<RpcErr>) -> Response {
    Response::json(status, &rpc_message(id, result, e))
}

impl Skill {
    fn entry(&self) -> Value {
        let res: Vec<Value> = self
            .files
            .iter()
            .map(|f| json!({"uri": f.uri, "digest": format!("sha256:{}", sha256_hex(&f.data)), "size": f.data.len()}))
            .collect();
        json!({"uri": self.uri, "frontmatter": self.frontmatter, "resources": res})
    }
}

fn walk(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
    let mut entries: Vec<_> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(Result::ok)
        .map(|e| e.path())
        .collect();
    entries.sort();
    for p in entries {
        if p.is_dir() {
            walk(&p, out)?;
        } else {
            out.push(p);
        }
    }
    Ok(())
}

fn load_skill(dir: &Path) -> Result<Skill, String> {
    let md = std::fs::read_to_string(dir.join("SKILL.md")).map_err(|e| e.to_string())?;
    let fm = parse_frontmatter(&md);
    let name = fm
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    if name.is_empty() || dir.file_name().and_then(|n| n.to_str()) != Some(name.as_str()) {
        return Err(format!("skill {}: name must match dir", dir.display()));
    }
    let mut files = vec![];
    walk(dir, &mut files)?;
    let mut sk = Skill {
        uri: format!("skill://{name}/SKILL.md"),
        frontmatter: fm,
        files: vec![],
    };
    for f in files {
        let rel = f
            .strip_prefix(dir)
            .unwrap()
            .components()
            .map(|c| c.as_os_str().to_string_lossy())
            .collect::<Vec<_>>();
        let data = std::fs::read(&f).map_err(|e| e.to_string())?;
        sk.files.push(SkillFile {
            uri: format!("skill://{name}/{}", rel.join("/")),
            data,
        });
    }
    sk.files.sort_by(|a, b| a.uri.cmp(&b.uri));
    Ok(sk)
}

/// The flat `key: value` YAML subset the fixture skills use.
fn parse_frontmatter(md: &str) -> Obj {
    let mut fm = Obj::new();
    let md = md.replace("\r\n", "\n");
    let Some(rest) = md.strip_prefix("---\n") else {
        return fm;
    };
    let Some(end) = rest.find("\n---") else {
        return fm;
    };
    for line in rest[..end].split('\n') {
        if let Some((k, v)) = line.split_once(':')
            && !line.starts_with(' ')
        {
            fm.insert(
                k.trim().into(),
                json!(v.trim().trim_matches(|c| c == '"' || c == '\'')),
            );
        }
    }
    fm
}
