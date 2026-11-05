//! MCP — INBOUND, the 2026-07-28 wire on [`McpHandler`] (SPEC §7C.2, ADR 0040).
//! Port of `golang/mcphandler2026.go`.
//!
//! Opt-in: list `"2026-07-28"` in `protocol_versions`. A POST is served on
//! this profile when the version is accepted and the request is a 2026
//! request (`route_2026`). The profile is stateless: no initialize, no
//! `Mcp-Session-Id`, no server→client request. Every hook keeps its meaning:
//! `authenticate` (after header validation), `list_tools`, `before_call`,
//! `on_call`, resources, prompts, plus `list_skills` for the SEP-2640 skills
//! extension. A confirm decision and a tool suspension (§10 pending) leave as
//! an MRTR `input_required` result; the client's retry carries
//! `inputResponses` and the echoed `requestState`.

use std::path::Path;
use std::sync::Arc;
use std::time::Instant;

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use hyper::body::Bytes;
use hyper::{Response, StatusCode};
use serde_json::{Map, Value, json};
use tokio::sync::mpsc;

use super::{
    Ctx, DEFAULT_NAME, DEFAULT_VERSION, McpBody, McpCallEvent, McpDecision, McpDecisionKind,
    McpHandler, McpRequestContext, McpServerInfo, RpcMessage, RpcWriter, json_response,
    mcp_wire_tool, omit_empty, tool_result_to_mcp,
};
use crate::tool::{Answer, Request as PendingRequest, ToolContext, ToolResult, pending_of};

/// The MCP 2026-07-28 protocol version (SPEC §2.1).
pub const MCP_PROTOCOL_2026: &str = "2026-07-28";
/// The SEP-2640 skills extension id advertised by `server/discover`.
pub const MCP_SKILLS_EXTENSION: &str = "io.modelcontextprotocol/skills";
pub(super) const META_VERSION: &str = "io.modelcontextprotocol/protocolVersion";
const META_CLIENT_CAPS: &str = "io.modelcontextprotocol/clientCapabilities";
const META_SERVER_INFO: &str = "io.modelcontextprotocol/serverInfo";
const CODE_HEADER_MISMATCH: i64 = -32020;
const CODE_UNSUPPORTED: i64 = -32022;

/// One skill published through the skills extension (SEP-2640). `files` are
/// paths relative to the skill directory (`/`-separated) and MUST include
/// `SKILL.md`. `frontmatter` `None` ⇒ `{name}`. [`load_mcp_skills`] builds these.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpSkill {
    pub name: String,
    pub frontmatter: Option<Map<String, Value>>,
    pub files: Vec<McpSkillFile>,
}

/// One text file of a skill.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct McpSkillFile {
    pub path: String,
    pub text: String,
}

/// A 2026 JSON-RPC error: HTTP status (0 ⇒ 200), code, message, data.
struct Err26 {
    status: u16,
    code: i64,
    msg: String,
    data: Option<Value>,
}

fn err26(code: i64, msg: impl Into<String>) -> Err26 {
    Err26 {
        status: 0,
        code,
        msg: msg.into(),
        data: None,
    }
}

fn write26(status: u16, id: &Value, out: Result<Map<String, Value>, Err26>) -> Response<McpBody> {
    let msg = match out {
        Ok(r) => json!({"jsonrpc": "2.0", "id": id, "result": r}),
        Err(e) => {
            let mut er = json!({"code": e.code, "message": e.msg});
            if let Some(d) = e.data {
                er["data"] = d;
            }
            json!({"jsonrpc": "2.0", "id": id, "error": er})
        }
    };
    json_response(StatusCode::from_u16(status).unwrap_or(StatusCode::OK), &msg)
}

/// Decode the `=?base64?…?=` header sentinel (SPEC §2.3). `None` ⇒ the header
/// is absent/empty or the sentinel does not decode.
pub(super) fn decode_header_value(v: &str) -> Option<String> {
    if v.len() >= 11 && v.starts_with("=?base64?") && v.ends_with("?=") {
        let b = STANDARD.decode(&v[9..v.len() - 2]).ok()?;
        return Some(String::from_utf8_lossy(&b).into_owned());
    }
    (!v.is_empty()).then(|| v.to_owned())
}

/// SPEC §7C.2 header validation, in order.
fn validate(rc: &McpRequestContext, method: &str, p: &Map<String, Value>) -> Result<(), Err26> {
    let mismatch = |m: String| Err26 {
        status: 400,
        code: CODE_HEADER_MISMATCH,
        msg: format!("Header mismatch: {m}"),
        data: None,
    };
    let hv = rc.header("mcp-protocol-version");
    if hv.is_empty() {
        return Err(mismatch("MCP-Protocol-Version header missing".into()));
    }
    let hm = rc.header("mcp-method");
    if hm != method {
        return Err(mismatch(format!(
            "Mcp-Method header value '{hm}' does not match body value '{method}'"
        )));
    }
    if matches!(method, "tools/call" | "prompts/get" | "resources/read") {
        let key = if method == "resources/read" {
            "uri"
        } else {
            "name"
        };
        let want = p.get(key).and_then(Value::as_str).unwrap_or("");
        let got = decode_header_value(rc.header("mcp-name"));
        if got.as_deref() != Some(want) {
            return Err(mismatch(format!(
                "Mcp-Name header value '{}' does not match body value '{want}'",
                got.unwrap_or_default()
            )));
        }
    }
    let mv = rc
        .meta()
        .and_then(|m| m.get(META_VERSION))
        .and_then(Value::as_str)
        .unwrap_or("");
    if mv != hv {
        return Err(mismatch(format!(
            "MCP-Protocol-Version header value '{hv}' does not match _meta value '{mv}'"
        )));
    }
    if mv != MCP_PROTOCOL_2026 {
        return Err(Err26 {
            status: 400,
            code: CODE_UNSUPPORTED,
            msg: "Unsupported protocol version".into(),
            data: Some(json!({"supported": [MCP_PROTOCOL_2026], "requested": mv})),
        });
    }
    Ok(())
}

/// The opaque `requestState`: which tool the round belongs to and whether a
/// confirm was already accepted. Base64url (no padding) of compact JSON
/// `{"tool":..[,"confirmed":true]}`; NOT integrity-protected.
#[derive(Default, Clone)]
struct State26 {
    tool: String,
    confirmed: bool,
}

fn encode_state(s: &State26) -> String {
    let mut j = format!(
        "{{\"tool\":{}",
        serde_json::to_string(&s.tool).unwrap_or_default()
    );
    if s.confirmed {
        j.push_str(",\"confirmed\":true");
    }
    j.push('}');
    URL_SAFE_NO_PAD.encode(j)
}

fn decode_state(v: Option<&Value>) -> Option<State26> {
    #[derive(serde::Deserialize)]
    struct S {
        #[serde(default)]
        tool: String,
        #[serde(default)]
        confirmed: bool,
    }
    let b = URL_SAFE_NO_PAD.decode(v?.as_str()?).ok()?;
    let s: S = serde_json::from_slice(&b).ok()?;
    Some(State26 {
        tool: s.tool,
        confirmed: s.confirmed,
    })
}

/// One MRTR input response as a §10 Answer.
fn input_response(key: &str, v: Option<&Value>) -> Option<Answer> {
    let m = v?.as_object()?;
    let action = m.get("action").and_then(Value::as_str).unwrap_or("");
    let ok = action == "accept";
    Some(Answer {
        id: key.to_owned(),
        ok,
        data: m.get("content").and_then(Value::as_object).cloned(),
        reason: if ok {
            String::new()
        } else if action == "decline" {
            "declined".into()
        } else {
            "cancelled".into()
        },
    })
}

/// A §10 Request as an MRTR input request: kind "authorization" with a URL ⇒
/// URL-mode elicitation, anything else ⇒ form.
fn input_request(r: &PendingRequest) -> Value {
    if r.kind == "authorization" && !r.url.is_empty() {
        return json!({"method": "elicitation/create", "params": {
            "mode": "url", "message": r.prompt, "url": r.url, "elicitationId": r.id}});
    }
    let schema = r
        .data
        .as_ref()
        .and_then(|d| d.get("schema"))
        .filter(|s| s.is_object())
        .cloned()
        .unwrap_or_else(|| json!({"type": "object", "properties": {}}));
    json!({"method": "elicitation/create", "params": {
        "mode": "form", "message": r.prompt, "requestedSchema": schema}})
}

fn can_elicit(meta: Option<&Map<String, Value>>) -> bool {
    meta.and_then(|m| m.get(META_CLIENT_CAPS))
        .and_then(Value::as_object)
        .is_some_and(|c| c.contains_key("elicitation"))
}

fn frontmatter(s: &McpSkill) -> Value {
    match &s.frontmatter {
        Some(f) => Value::Object(f.clone()),
        None => json!({"name": s.name}),
    }
}

fn sha256_hex(b: &[u8]) -> String {
    let d = ring::digest::digest(&ring::digest::SHA256, b);
    d.as_ref().iter().map(|x| format!("{x:02x}")).collect()
}

fn skill_entry(s: &McpSkill) -> Value {
    let mut files = s.files.clone();
    files.sort_by(|a, b| a.path.cmp(&b.path));
    let res: Vec<Value> = files
        .iter()
        .map(|f| {
            json!({"uri": format!("skill://{}/{}", s.name, f.path),
                   "digest": format!("sha256:{}", sha256_hex(f.text.as_bytes())),
                   "size": f.text.len()})
        })
        .collect();
    json!({"uri": format!("skill://{}/SKILL.md", s.name), "frontmatter": frontmatter(s), "resources": res})
}

/// Percent-decode once (Go `url.PathUnescape`: `+` stays, a bad escape fails).
pub(super) fn percent_decode(s: &str) -> Option<Vec<u8>> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hex = |c: u8| (c as char).to_digit(16);
            let (Some(h), Some(l)) = (
                b.get(i + 1).copied().and_then(hex),
                b.get(i + 2).copied().and_then(hex),
            ) else {
                return None;
            };
            out.push((h * 16 + l) as u8);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    Some(out)
}

/// Validate `skill://<name>/<path>` (SPEC §2.7 containment) ⇒ (name, path).
fn parse_skill_uri(uri: &str) -> Result<(String, String), &'static str> {
    let rest = uri.strip_prefix("skill://").ok_or("not a skill:// URI")?;
    let dec = percent_decode(rest).ok_or("bad percent-encoding")?;
    let dec = String::from_utf8_lossy(&dec).into_owned();
    if dec.contains(['%', '\\', '\0']) {
        return Err("skill URI has encoded, backslash or NUL characters");
    }
    let segs: Vec<&str> = dec.split('/').collect();
    if segs.len() < 2 {
        return Err("skill URI needs skill://<name>/<path>");
    }
    if segs
        .iter()
        .any(|s| s.is_empty() || *s == "." || *s == ".." || s.contains(':'))
    {
        return Err("skill URI path escapes the skill (empty, '.', '..' or ':' segment)");
    }
    Ok((segs[0].to_owned(), segs[1..].join("/")))
}

impl McpHandler {
    pub(super) fn has_2026(&self) -> bool {
        self.inner.versions.iter().any(|v| v == MCP_PROTOCOL_2026)
    }

    pub(super) fn only_2026(&self) -> bool {
        self.inner.versions.len() == 1 && self.inner.versions[0] == MCP_PROTOCOL_2026
    }

    /// Whether this POST is served on the 2026-07-28 profile.
    fn route_2026(
        &self,
        rc: &McpRequestContext,
        method: &str,
        meta: Option<&Map<String, Value>>,
    ) -> bool {
        if !self.has_2026() {
            return false;
        }
        if self.inner.legacy.is_empty() {
            return true;
        }
        if method == "initialize" {
            return false;
        }
        let hv = rc.header("mcp-protocol-version");
        if hv == MCP_PROTOCOL_2026 {
            return true;
        }
        if !hv.is_empty() && self.inner.versions.iter().any(|v| v == hv) {
            return false;
        }
        method == "server/discover"
            || !rc.header("mcp-method").is_empty()
            || meta.is_some_and(|m| m.contains_key(META_VERSION))
    }

    /// Route and serve a JSON POST body on the 2026 profile; `None` ⇒ the
    /// request belongs to the legacy wire.
    pub(super) async fn try_2026(
        &self,
        rc: &McpRequestContext,
        body: &Bytes,
    ) -> Option<Response<McpBody>> {
        let text = String::from_utf8_lossy(body);
        let batch = text.trim_start().starts_with('[');
        let msg = if batch {
            None
        } else {
            serde_json::from_slice::<RpcMessage>(body)
                .ok()
                .filter(|m| m.jsonrpc == "2.0")
        };
        let Some(msg) = msg else {
            if !self.only_2026() {
                return None;
            }
            return Some(if batch {
                write26(
                    400,
                    &Value::Null,
                    Err(err26(-32600, "batch requests are not supported")),
                )
            } else {
                write26(400, &Value::Null, Err(err26(-32700, "Parse error")))
            });
        };
        let meta = msg
            .params
            .as_ref()
            .and_then(|p| p.get("_meta"))
            .and_then(Value::as_object)
            .cloned();
        if !self.route_2026(rc, &msg.method, meta.as_ref()) {
            return None;
        }
        Some(self.serve_2026(rc.clone(), msg, meta).await)
    }

    async fn serve_2026(
        &self,
        mut rc: McpRequestContext,
        msg: RpcMessage,
        meta: Option<Map<String, Value>>,
    ) -> Response<McpBody> {
        let id = match &msg.id {
            Some(id) if !msg.method.is_empty() => id.clone(),
            // A notification (or a stray response): acknowledged.
            _ => return super::empty(StatusCode::ACCEPTED),
        };
        rc.meta = meta;
        let params = match &msg.params {
            Some(Value::Object(p)) => p.clone(),
            _ => Map::new(),
        };
        if let Err(e) = validate(&rc, &msg.method, &params) {
            return write26(e.status, &id, Err(e));
        }
        if let Some(auth) = &self.inner.cfg.authenticate {
            match auth(Arc::new(rc.clone())).await {
                Ok(identity) => rc.identity = Some(identity),
                Err(e) => return self.auth_failure(&rc, e),
            }
        }
        let rc = Arc::new(rc);
        let out = self.dispatch_2026(&rc, &msg, &params).await;
        let status = match &out {
            Err(e) if e.status != 0 => e.status,
            _ => 200,
        };
        write26(status, &id, out)
    }

    fn info_2026(&self, rc: &McpRequestContext) -> McpServerInfo {
        let cfg = &self.inner.cfg;
        let mut info = match &cfg.server_info {
            Some(f) => f(rc),
            None => McpServerInfo {
                name: cfg.name.clone(),
                title: cfg.title.clone(),
                version: cfg.version.clone(),
                instructions: cfg.instructions.clone(),
            },
        };
        if info.name.is_empty() {
            info.name = DEFAULT_NAME.into();
        }
        if info.version.is_empty() {
            info.version = DEFAULT_VERSION.into();
        }
        info
    }

    /// Stamp `resultType` and `_meta.serverInfo` (set last) onto a result.
    fn complete26(
        &self,
        rc: &McpRequestContext,
        mut m: Map<String, Value>,
        result_type: &str,
    ) -> Map<String, Value> {
        let info = self.info_2026(rc);
        let mut meta = match m.remove("_meta") {
            Some(Value::Object(o)) => o,
            _ => Map::new(),
        };
        meta.insert(
            META_SERVER_INFO.into(),
            Value::Object(omit_empty(&[
                ("name", &info.name),
                ("title", &info.title),
                ("version", &info.version),
            ])),
        );
        m.insert("_meta".into(), Value::Object(meta));
        m.insert("resultType".into(), json!(result_type));
        m
    }

    fn cache_scope(&self) -> &'static str {
        if self.inner.cfg.authenticate.is_some() {
            "private"
        } else {
            "public"
        }
    }

    async fn skills_2026(&self, rc: &Ctx) -> Result<Vec<McpSkill>, Err26> {
        let f = self.inner.cfg.list_skills.as_ref().expect("list_skills");
        let mut out = f(rc.clone()).await.map_err(|e| err26(-32603, e))?;
        out.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(out)
    }

    async fn dispatch_2026(
        &self,
        rc: &Ctx,
        msg: &RpcMessage,
        p: &Map<String, Value>,
    ) -> Result<Map<String, Value>, Err26> {
        let cfg = &self.inner.cfg;
        let obj = |v: Value| match v {
            Value::Object(m) => m,
            _ => Map::new(),
        };
        let method = msg.method.as_str();
        let not_found = || Err26 {
            status: 404,
            code: -32601,
            msg: format!("Method not found: {method}"),
            data: None,
        };
        match method {
            "server/discover" => {
                let info = self.info_2026(rc);
                let mut caps = json!({"tools": {}});
                if cfg.list_resources.is_some() || cfg.list_skills.is_some() {
                    caps["resources"] = json!({});
                }
                if cfg.list_prompts.is_some() {
                    caps["prompts"] = json!({});
                }
                if cfg.list_skills.is_some() {
                    caps["extensions"] = json!({MCP_SKILLS_EXTENSION: {}});
                }
                let mut res = obj(json!({"supportedVersions": [MCP_PROTOCOL_2026],
                    "capabilities": caps, "ttlMs": 3_600_000, "cacheScope": "public"}));
                if !info.instructions.is_empty() {
                    res.insert("instructions".into(), json!(info.instructions));
                }
                Ok(self.complete26(rc, res, "complete"))
            }
            "ping" => Ok(self.complete26(rc, Map::new(), "complete")),
            "tools/list" => {
                let mut tools = self.tools(rc).await.map_err(|e| err26(-32603, e))?;
                tools.sort_by(|a, b| a.name.cmp(&b.name));
                let out: Vec<Value> = tools.iter().map(mcp_wire_tool).collect();
                Ok(self.complete26(
                    rc,
                    obj(json!({"tools": out, "ttlMs": 60_000, "cacheScope": self.cache_scope()})),
                    "complete",
                ))
            }
            "tools/call" => self.call_tool_2026(rc, p).await,
            "skills/list" if cfg.list_skills.is_some() => {
                let skills = self.skills_2026(rc).await?;
                let list: Vec<Value> = skills
                    .iter()
                    .map(|s| json!({"uri": format!("skill://{}/SKILL.md", s.name), "frontmatter": frontmatter(s)}))
                    .collect();
                Ok(self.complete26(
                    rc,
                    obj(json!({"skills": list, "ttlMs": 60_000, "cacheScope": self.cache_scope()})),
                    "complete",
                ))
            }
            "skills/get" if cfg.list_skills.is_some() => {
                let uri = p.get("uri").and_then(Value::as_str).unwrap_or("");
                if let Err(why) = parse_skill_uri(uri) {
                    return Err(err26(-32602, format!("Invalid skill URI: {why}")));
                }
                let skills = self.skills_2026(rc).await?;
                match skills
                    .iter()
                    .find(|s| format!("skill://{}/SKILL.md", s.name) == uri)
                {
                    Some(s) => {
                        Ok(self.complete26(rc, obj(json!({"skill": skill_entry(s)})), "complete"))
                    }
                    None => Err(err26(-32602, format!("Unknown skill: {uri}"))),
                }
            }
            "resources/list" if cfg.list_resources.is_some() => self.legacy_envelope(rc, msg).await,
            "resources/read" => {
                let uri = p.get("uri").and_then(Value::as_str).unwrap_or("");
                if uri.starts_with("skill://") && cfg.list_skills.is_some() {
                    let (name, rel) = parse_skill_uri(uri)
                        .map_err(|why| err26(-32602, format!("Invalid resource URI: {why}")))?;
                    let skills = self.skills_2026(rc).await?;
                    let file = skills
                        .iter()
                        .filter(|s| s.name == name)
                        .flat_map(|s| s.files.iter())
                        .find(|f| f.path == rel);
                    return match file {
                        Some(f) => {
                            let mt = if rel.ends_with(".md") {
                                "text/markdown"
                            } else {
                                "text/plain"
                            };
                            Ok(self.complete26(
                                rc,
                                obj(json!({"contents": [{"uri": uri, "mimeType": mt, "text": f.text}],
                                    "ttlMs": 60_000, "cacheScope": self.cache_scope()})),
                                "complete",
                            ))
                        }
                        None => Err(err26(-32602, format!("Resource not found: {uri}"))),
                    };
                }
                if cfg.list_resources.is_none() || cfg.read_resource.is_none() {
                    return Err(not_found());
                }
                self.legacy_envelope(rc, msg).await
            }
            "prompts/list" | "prompts/get" if cfg.list_prompts.is_some() => {
                self.legacy_envelope(rc, msg).await
            }
            _ => Err(not_found()),
        }
    }

    /// The §7C.1 payload of `msg`, in the 2026 envelope.
    async fn legacy_envelope(
        &self,
        rc: &Ctx,
        msg: &RpcMessage,
    ) -> Result<Map<String, Value>, Err26> {
        let (tx, _rx) = mpsc::unbounded_channel();
        let m = RpcMessage {
            jsonrpc: "2.0".into(),
            id: msg.id.clone(),
            method: msg.method.clone(),
            params: msg.params.clone(),
            result: None,
            error: None,
        };
        let res = self
            .dispatch(rc.clone(), RpcWriter(tx), m)
            .await
            .map_err(|e| err26(e.0, e.1))?;
        let m = match res {
            Value::Object(m) => m,
            _ => Map::new(),
        };
        Ok(self.complete26(rc, m, "complete"))
    }

    async fn call_tool_2026(
        &self,
        rc: &Ctx,
        p: &Map<String, Value>,
    ) -> Result<Map<String, Value>, Err26> {
        let h = &self.inner;
        let name = p
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        let tools = self.tools(rc).await.map_err(|e| err26(-32603, e))?;
        let Some(tool) = tools.into_iter().find(|t| t.name == name) else {
            return Err(err26(-32602, format!("Unknown tool: {name}")));
        };
        let started = Instant::now();
        let mut ev = McpCallEvent {
            name: tool.name.clone(),
            source: tool.source,
            ms: 0,
            is_error: false,
            err: None,
            result_bytes: 0,
            decision: McpDecisionKind::Allow,
            identity: rc.identity.clone(),
            session_id: String::new(),
        };
        let on_call = h.cfg.on_call.clone();
        let finish =
            |mut ev: McpCallEvent, m: Map<String, Value>, is_error: bool, err: Option<String>| {
                ev.ms = started.elapsed().as_millis() as u64;
                ev.is_error = is_error;
                ev.err = err;
                ev.result_bytes = serde_json::to_vec(&m).map(|b| b.len()).unwrap_or(0);
                if let Some(cb) = &on_call {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| cb(ev)));
                }
                Ok(m)
            };
        let result = |ev: McpCallEvent, res: &ToolResult, err: Option<String>| {
            let m = match tool_result_to_mcp(res) {
                Value::Object(m) => m,
                _ => Map::new(),
            };
            finish(ev, self.complete26(rc, m, "complete"), res.is_error, err)
        };

        let args = match p.get("arguments") {
            None | Some(Value::Null) => Value::Object(Map::new()),
            Some(Value::Object(m)) => Value::Object(m.clone()),
            Some(_) => {
                return result(
                    ev,
                    &ToolResult::error("invalid arguments: must be an object"),
                    None,
                );
            }
        };
        let mut state = decode_state(p.get("requestState")).filter(|s| s.tool == name);
        let mut responses: Option<Map<String, Value>> =
            p.get("inputResponses").and_then(Value::as_object).cloned();

        if let Some(bc) = &h.cfg.before_call {
            let d = match bc(rc.clone(), tool.clone(), args.clone()).await {
                Ok(d) => d,
                Err(e) => McpDecision::deny(e),
            };
            ev.decision = d.kind;
            match d.kind {
                McpDecisionKind::Allow => {}
                McpDecisionKind::Deny => {
                    let res = d.result.unwrap_or_else(|| {
                        ToolResult::error(if d.message.is_empty() {
                            "denied".to_owned()
                        } else {
                            d.message.clone()
                        })
                    });
                    return result(ev, &res, None);
                }
                McpDecisionKind::Confirm => {
                    let confirmed = state.as_ref().is_some_and(|s| s.confirmed);
                    let answer = state.as_ref().and_then(|_| {
                        input_response("confirm", responses.as_ref()?.get("confirm"))
                    });
                    if confirmed {
                        // accepted in an earlier round of this call
                    } else if let Some(ans) = answer {
                        let refused = !ans.ok
                            || ans
                                .data
                                .as_ref()
                                .and_then(|d| d.get("confirm"))
                                .is_some_and(|v| v == &Value::Bool(false));
                        if refused {
                            return result(ev, &ToolResult::error("declined"), None);
                        }
                        state = Some(State26 {
                            tool: name.clone(),
                            confirmed: true,
                        });
                        responses = None; // the confirm round is consumed
                    } else if !can_elicit(rc.meta()) {
                        let res = d.result.unwrap_or_else(|| {
                            ToolResult::error(format!("confirmation required: {}", d.message))
                        });
                        return result(ev, &res, None);
                    } else {
                        let msg = if d.message.is_empty() {
                            "Confirm this action?".to_owned()
                        } else {
                            d.message.clone()
                        };
                        let m = json!({
                            "inputRequests": {"confirm": {"method": "elicitation/create", "params": {
                                "mode": "form", "message": msg,
                                "requestedSchema": {"type": "object", "properties": {
                                    "confirm": {"type": "boolean", "title": "Confirm", "description": msg}}}}}},
                            "requestState": encode_state(&State26 { tool: name.clone(), confirmed: false }),
                        });
                        let Value::Object(m) = m else { unreachable!() };
                        return finish(ev, self.complete26(rc, m, "input_required"), false, None);
                    }
                }
            }
        }

        let mut answer = None;
        if state.is_some() {
            if let Some(r) = responses.as_ref().filter(|r| r.len() == 1) {
                let (k, v) = r.iter().next().expect("one entry");
                answer = input_response(k, Some(v));
            }
        }
        let ctx = ToolContext {
            request: Some(rc.clone() as Arc<dyn std::any::Any + Send + Sync>),
            answer,
            ..Default::default()
        };
        let t = tool.clone();
        let res = match tokio::spawn(async move { t.call(args, ctx).await }).await {
            Ok(r) => r,
            Err(e) => ToolResult::error(format!("tool panicked: {e}")),
        };
        if let Some(req) = pending_of(&res) {
            let confirmed = state.as_ref().is_some_and(|s| s.confirmed);
            let mut ir = Map::new();
            ir.insert(req.id.clone(), input_request(&req));
            let mut m = Map::new();
            m.insert("inputRequests".into(), Value::Object(ir));
            m.insert(
                "requestState".into(),
                json!(encode_state(&State26 {
                    tool: name.clone(),
                    confirmed
                })),
            );
            return finish(ev, self.complete26(rc, m, "input_required"), false, None);
        }
        let err = res.is_error.then(|| res.output.clone());
        result(ev, &res, err)
    }
}

/// Read every skill under `dir` (any depth, `**/SKILL.md`, symlinks not
/// followed) for [`McpHandlerConfig::list_skills`](super::McpHandlerConfig).
/// The frontmatter is `{name, description}` parsed as in §3; a skill without
/// a name is skipped. Files are every regular UTF-8 file under the skill
/// directory (binary files are skipped — the wire carries text), paths
/// `/`-separated, sorted by code point; skills sorted by name.
pub fn load_mcp_skills(dir: impl AsRef<Path>) -> std::io::Result<Vec<McpSkill>> {
    fn walk(dir: &Path, out: &mut Vec<std::path::PathBuf>) -> std::io::Result<()> {
        let mut entries: Vec<_> = std::fs::read_dir(dir)?.collect::<Result<_, _>>()?;
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let ft = e.file_type()?;
            if ft.is_dir() {
                walk(&e.path(), out)?;
            } else if ft.is_file() {
                out.push(e.path());
            }
        }
        Ok(())
    }
    let mut all = Vec::new();
    walk(dir.as_ref(), &mut all)?;
    let mut out = Vec::new();
    for p in all
        .iter()
        .filter(|p| p.file_name().is_some_and(|n| n == "SKILL.md"))
    {
        let raw = std::fs::read_to_string(p)?;
        let (fm, _, malformed, _) = crate::skills::parse_frontmatter(&raw);
        if malformed || fm.name.is_empty() {
            continue;
        }
        let root = p.parent().unwrap_or(Path::new(""));
        let mut files = Vec::new();
        let mut under = Vec::new();
        walk(root, &mut under)?;
        for fp in under {
            let Ok(text) = String::from_utf8(std::fs::read(&fp)?) else {
                continue;
            };
            let rel = fp.strip_prefix(root).unwrap_or(&fp);
            let rel: Vec<String> = rel
                .components()
                .map(|c| c.as_os_str().to_string_lossy().into_owned())
                .collect();
            files.push(McpSkillFile {
                path: rel.join("/"),
                text,
            });
        }
        files.sort_by(|a, b| a.path.cmp(&b.path));
        let mut f = Map::new();
        f.insert("name".into(), json!(fm.name));
        f.insert("description".into(), json!(fm.description));
        out.push(McpSkill {
            name: fm.name,
            frontmatter: Some(f),
            files,
        });
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(out)
}
