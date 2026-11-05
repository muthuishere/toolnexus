//! Test support for MCP 2026-07-28: an in-test fixture server (the shared
//! `examples/mcp-2026/fixture.json` tools + skills) and a dev authorization
//! server. Hermetic: 127.0.0.1 only. Not library code.
#![allow(dead_code)]

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine as _;
use serde_json::{Value, json};

use crate::common::{Req, Resp, serve};

pub const V: &str = "2026-07-28";

fn rpc(id: &Value, result: Value) -> Resp {
    Resp::json(json!({"jsonrpc": "2.0", "id": id, "result": result}))
}
fn rpc_err(status: u16, id: &Value, code: i64, message: &str, data: Option<Value>) -> Resp {
    let mut e = json!({"code": code, "message": message});
    if let Some(d) = data {
        e["data"] = d;
    }
    let mut r = Resp::json(json!({"jsonrpc": "2.0", "id": id, "error": e}));
    r.status = status;
    r
}

fn sha256_hex(b: &[u8]) -> String {
    use sha2::Digest as _;
    sha2::Sha256::digest(b)
        .iter()
        .map(|x| format!("{x:02x}"))
        .collect()
}

fn decode_header(v: &str) -> String {
    if let Some(b) = v
        .strip_prefix("=?base64?")
        .and_then(|r| r.strip_suffix("?="))
    {
        return base64::engine::general_purpose::STANDARD
            .decode(b)
            .ok()
            .and_then(|x| String::from_utf8(x).ok())
            .unwrap_or_default();
    }
    v.to_owned()
}

#[derive(Clone, Copy, PartialEq)]
pub enum Protect {
    None,
    All,
    CallsOnly,
}

#[derive(Default)]
pub struct FxState {
    pub execs: HashMap<String, usize>,
    /// (method, lowercased headers, body)
    pub seen: Vec<(String, HashMap<String, String>, Value)>,
    /// Reply 502 after running the tool.
    pub fail_502_after_exec: bool,
    /// Reply an unreadable body after running the tool.
    pub garbage_after_exec: bool,
    /// Revoke every token (and refresh token) when greet asks for input.
    pub revoke_on_input_required: bool,
}

pub struct Fixture {
    pub url: String,
    pub st: Arc<Mutex<FxState>>,
}

impl Fixture {
    pub fn execs(&self, tool: &str) -> usize {
        self.st
            .lock()
            .unwrap()
            .execs
            .get(tool)
            .copied()
            .unwrap_or(0)
    }
    pub fn calls(&self, method: &str) -> Vec<Value> {
        self.st
            .lock()
            .unwrap()
            .seen
            .iter()
            .filter(|(m, _, _)| m == method)
            .map(|(_, _, b)| b.clone())
            .collect()
    }
    pub fn headers(&self, method: &str) -> Vec<HashMap<String, String>> {
        self.st
            .lock()
            .unwrap()
            .seen
            .iter()
            .filter(|(m, _, _)| m == method)
            .map(|(_, h, _)| h.clone())
            .collect()
    }
}

fn skill_files() -> Vec<(String, String)> {
    let root =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/skills/hello-world");
    let mut out = vec![
        (
            "SKILL.md".to_owned(),
            std::fs::read_to_string(root.join("SKILL.md")).unwrap(),
        ),
        (
            "scripts/greet.sh".to_owned(),
            std::fs::read_to_string(root.join("scripts/greet.sh")).unwrap(),
        ),
    ];
    out.sort();
    out
}

fn fixture_tools(extra: &[Value]) -> Vec<Value> {
    let fx: Value = serde_json::from_str(
        &std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../examples/mcp-2026/fixture.json"),
        )
        .unwrap(),
    )
    .unwrap();
    let mut t = fx["tools"].as_array().unwrap().clone();
    t.extend(extra.iter().cloned());
    t
}

/// Start the fixture server at `/mcp`. `extra_tools` are listed (and echo
/// their `text` argument when called).
pub async fn start(protect: Protect, auth: Option<Arc<DevAs>>, extra_tools: Vec<Value>) -> Fixture {
    let st = Arc::new(Mutex::new(FxState::default()));
    let st2 = st.clone();
    let url_cell: Arc<Mutex<String>> = Arc::default();
    let uc = url_cell.clone();
    let srv = serve(move |r: Req| {
        let (st, auth, uc, extra) = (st2.clone(), auth.clone(), uc.clone(), extra_tools.clone());
        async move {
            handle(
                r,
                &st,
                auth.as_deref(),
                &uc.lock().unwrap().clone(),
                protect,
                &extra,
            )
        }
    })
    .await;
    let url = format!("{}/mcp", srv.url);
    *url_cell.lock().unwrap() = url.clone();
    Fixture { url, st }
}

fn challenge(url: &str, status: u16, error: Option<&str>, scope: &str) -> Resp {
    let origin = url.trim_end_matches("/mcp");
    let mut h = format!(
        "Bearer resource_metadata=\"{origin}/.well-known/oauth-protected-resource/mcp\", scope=\"{scope}\""
    );
    if let Some(e) = error {
        h.push_str(&format!(", error=\"{e}\""));
    }
    let mut r = Resp::json(json!({"error": if status == 401 {"unauthorized"} else {"forbidden"}}));
    r.status = status;
    r.header("WWW-Authenticate", &h)
}

fn handle(
    r: Req,
    st: &Mutex<FxState>,
    auth: Option<&DevAs>,
    url: &str,
    protect: Protect,
    extra: &[Value],
) -> Resp {
    if r.method == "GET" && r.path == "/.well-known/oauth-protected-resource/mcp" {
        let Some(a) = auth else {
            return Resp::status(404, "");
        };
        return Resp::json(
            json!({"resource": url, "authorization_servers": [a.issuer()],
            "scopes_supported": ["mcp:read"], "bearer_methods_supported": ["header"]}),
        );
    }
    if r.method != "POST" || r.path != "/mcp" {
        return Resp::status(404, "not found");
    }
    let body = r.body.clone();
    let id = body.get("id").cloned().unwrap_or(Value::Null);
    let method = body
        .get("method")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    let params = body.get("params").cloned().unwrap_or(json!({}));
    st.lock()
        .unwrap()
        .seen
        .push((method.clone(), r.headers.clone(), body.clone()));
    let mv = params["_meta"]["io.modelcontextprotocol/protocolVersion"]
        .as_str()
        .unwrap_or("");
    let hv = r
        .headers
        .get("mcp-protocol-version")
        .cloned()
        .unwrap_or_default();
    if hv.is_empty()
        || hv != mv
        || r.headers.get("mcp-method").map(String::as_str) != Some(method.as_str())
    {
        return rpc_err(400, &id, -32020, "Header mismatch", None);
    }
    if mv != V {
        return rpc_err(
            400,
            &id,
            -32022,
            "Unsupported protocol version",
            Some(json!({"supported": [V], "requested": mv})),
        );
    }
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    if method == "tools/call"
        && r.headers.get("mcp-name").map(|v| decode_header(v)) != Some(name.clone())
    {
        return rpc_err(400, &id, -32020, "Header mismatch", None);
    }
    // authorization
    if let Some(a) = auth {
        let needs =
            protect == Protect::All || (protect == Protect::CallsOnly && method == "tools/call");
        if needs {
            let bearer = r
                .headers
                .get("authorization")
                .and_then(|h| h.strip_prefix("Bearer "))
                .unwrap_or("");
            match a.check(bearer, url) {
                Check::Missing => return challenge(url, 401, None, "mcp:read"),
                Check::Invalid => return challenge(url, 401, Some("invalid_token"), "mcp:read"),
                Check::Ok(scopes) => {
                    if method == "tools/call" && name == "greet" && !scopes.contains("mcp:greet") {
                        return challenge(
                            url,
                            403,
                            Some("insufficient_scope"),
                            "mcp:read mcp:greet",
                        );
                    }
                }
            }
        }
    }
    match method.as_str() {
        "server/discover" => rpc(
            &id,
            json!({"supportedVersions": [V],
            "capabilities": {"tools": {}, "extensions": {"io.modelcontextprotocol/skills": {}}},
            "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "toolnexus-spike", "version": "0.0.1"}}}),
        ),
        "tools/list" => rpc(
            &id,
            json!({"tools": fixture_tools(extra), "ttlMs": 60000, "cacheScope": "public"}),
        ),
        "tools/call" => {
            let args = params.get("arguments").cloned().unwrap_or(json!({}));
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            if name == "greet" {
                let resp = &params["inputResponses"]["name"];
                if resp["action"] == "accept" && params["requestState"] == "greet-1" {
                    *st.lock().unwrap().execs.entry(name).or_default() += 1;
                    let who = resp["content"]["name"].as_str().unwrap_or("");
                    return rpc(
                        &id,
                        json!({"content": [{"type": "text", "text": format!("Hello, {who}!")}], "isError": false}),
                    );
                }
                if st.lock().unwrap().revoke_on_input_required
                    && let Some(a) = auth
                {
                    a.revoke_all();
                }
                return rpc(
                    &id,
                    json!({"resultType": "input_required", "requestState": "greet-1",
                    "inputRequests": {"name": {"method": "elicitation/create", "params": {"mode": "form",
                        "message": "What is your name?",
                        "requestedSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}}}}),
                );
            }
            let known = name == "echo" || extra.iter().any(|t| t["name"] == name.as_str());
            if !known {
                return rpc_err(200, &id, -32602, &format!("Unknown tool: {name}"), None);
            }
            let mut s = st.lock().unwrap();
            *s.execs.entry(name).or_default() += 1;
            if s.fail_502_after_exec {
                return Resp::status(502, "bad gateway");
            }
            if s.garbage_after_exec {
                return Resp::json(json!("not json-rpc")).header("X", "y");
            }
            rpc(
                &id,
                json!({"content": [{"type": "text", "text": text}], "structuredContent": {"text": text}, "isError": false}),
            )
        }
        "skills/list" => rpc(
            &id,
            json!({"skills": [{"uri": "skill://hello-world/SKILL.md",
            "frontmatter": {"name": "hello-world"}}], "ttlMs": 60000, "cacheScope": "public"}),
        ),
        "skills/get" => {
            if params["uri"] != "skill://hello-world/SKILL.md" {
                return rpc_err(200, &id, -32602, "unknown skill", None);
            }
            let res: Vec<Value> = skill_files()
                .iter()
                .map(|(p, t)| {
                    json!({"uri": format!("skill://hello-world/{p}"),
                "digest": format!("sha256:{}", sha256_hex(t.as_bytes())), "size": t.len()})
                })
                .collect();
            rpc(
                &id,
                json!({"skill": {"uri": "skill://hello-world/SKILL.md", "frontmatter": {"name": "hello-world"}, "resources": res}}),
            )
        }
        "resources/read" => {
            let uri = params["uri"].as_str().unwrap_or("");
            match skill_files()
                .into_iter()
                .find(|(p, _)| format!("skill://hello-world/{p}") == uri)
            {
                Some((p, t)) => rpc(
                    &id,
                    json!({"contents": [{"uri": uri,
                    "mimeType": if p.ends_with(".md") {"text/markdown"} else {"text/plain"}, "text": t}]}),
                ),
                None => rpc_err(200, &id, -32602, "unknown resource", None),
            }
        }
        _ => rpc_err(404, &id, -32601, "Method not found", None),
    }
}

// ---------------------------------------------------------------------------
// dev authorization server
// ---------------------------------------------------------------------------

pub enum Check {
    Missing,
    Invalid,
    Ok(HashSet<String>),
}

pub struct Knobs {
    pub s256: bool,
    pub iss_param: bool,
    pub wrong_iss: bool,
    pub wrong_state: bool,
    pub cimd: bool,
    pub form_encoded: bool,
    pub reject_refresh_resource: bool,
    pub expires_in: u64,
    pub clients: HashSet<String>,
}

impl Default for Knobs {
    fn default() -> Self {
        Knobs {
            s256: true,
            iss_param: true,
            wrong_iss: false,
            wrong_state: false,
            cimd: false,
            form_encoded: false,
            reject_refresh_resource: false,
            expires_in: 3600,
            clients: ["dev-client".to_owned()].into(),
        }
    }
}

struct Code {
    client_id: String,
    redirect: String,
    challenge: String,
    resource: String,
    scope: String,
}

struct Issued {
    resource: String,
    scopes: HashSet<String>,
    expires: Instant,
}

#[derive(Default)]
pub struct AsState {
    pub knobs: Knobs,
    codes: HashMap<String, Code>,
    tokens: HashMap<String, Issued>,
    refresh: HashMap<String, (String, String)>, // refresh -> (resource, scope)
    pub token_requests: usize,
    pub refresh_requests: usize,
    pub authorize_scopes: Vec<String>,
    pub issued_values: Vec<String>,
    seq: u64,
}

pub struct DevAs {
    pub url: String,
    pub st: Arc<Mutex<AsState>>,
}

impl DevAs {
    pub fn issuer(&self) -> String {
        self.url.clone()
    }
    pub fn knobs(&self, f: impl FnOnce(&mut Knobs)) {
        f(&mut self.st.lock().unwrap().knobs);
    }
    pub fn stats(&self) -> (usize, usize) {
        let s = self.st.lock().unwrap();
        (s.token_requests, s.refresh_requests)
    }
    fn check(&self, bearer: &str, resource: &str) -> Check {
        if bearer.is_empty() {
            return Check::Missing;
        }
        let s = self.st.lock().unwrap();
        match s.tokens.get(bearer) {
            Some(t) if t.resource == resource && Instant::now() < t.expires => {
                Check::Ok(t.scopes.clone())
            }
            _ => Check::Invalid,
        }
    }
    fn revoke_all(&self) {
        let mut s = self.st.lock().unwrap();
        s.tokens.clear();
        s.refresh.clear();
    }
}

fn form(raw: &str) -> HashMap<String, String> {
    url::form_urlencoded::parse(raw.as_bytes())
        .into_owned()
        .collect()
}

pub async fn start_as() -> Arc<DevAs> {
    let st: Arc<Mutex<AsState>> = Arc::default();
    let st2 = st.clone();
    let url_cell: Arc<Mutex<String>> = Arc::default();
    let uc = url_cell.clone();
    let srv = serve(move |r: Req| {
        let (st, uc) = (st2.clone(), uc.clone());
        async move { as_handle(r, &st, &uc.lock().unwrap().clone()) }
    })
    .await;
    *url_cell.lock().unwrap() = srv.url.clone();
    Arc::new(DevAs { url: srv.url, st })
}

fn as_handle(r: Req, st: &Mutex<AsState>, issuer: &str) -> Resp {
    let mut s = st.lock().unwrap();
    let (path, query) = r.path.split_once('?').unwrap_or((r.path.as_str(), ""));
    match (r.method.as_str(), path) {
        ("GET", "/.well-known/oauth-authorization-server") => {
            let mut md = json!({"issuer": issuer, "authorization_endpoint": format!("{issuer}/authorize"),
                "token_endpoint": format!("{issuer}/token"),
                "code_challenge_methods_supported": if s.knobs.s256 {json!(["S256"])} else {json!(["plain"])},
                "authorization_response_iss_parameter_supported": s.knobs.iss_param});
            if s.knobs.cimd {
                md["client_id_metadata_document_supported"] = json!(true);
            }
            Resp::json(md)
        }
        ("GET", "/authorize") => {
            let q = form(query);
            let g = |k: &str| q.get(k).cloned().unwrap_or_default();
            let cid = g("client_id");
            let known =
                s.knobs.clients.contains(&cid) || (s.knobs.cimd && cid.starts_with("https://"));
            if !known || g("code_challenge_method") != "S256" || g("resource").is_empty() {
                return Resp::status(400, "bad authorize request");
            }
            s.seq += 1;
            let code = format!("code-{}", s.seq);
            s.authorize_scopes.push(g("scope"));
            s.codes.insert(
                code.clone(),
                Code {
                    client_id: cid,
                    redirect: g("redirect_uri"),
                    challenge: g("code_challenge"),
                    resource: g("resource"),
                    scope: g("scope"),
                },
            );
            let state = if s.knobs.wrong_state {
                "not-the-state".to_owned()
            } else {
                g("state")
            };
            let mut loc = url::Url::parse(&g("redirect_uri")).unwrap();
            loc.query_pairs_mut()
                .append_pair("code", &code)
                .append_pair("state", &state);
            if s.knobs.iss_param {
                let iss = if s.knobs.wrong_iss {
                    "https://evil.example"
                } else {
                    issuer
                };
                loc.query_pairs_mut().append_pair("iss", iss);
            }
            Resp::status(302, "").header("Location", loc.as_str())
        }
        ("POST", "/token") => {
            s.token_requests += 1;
            let f = form(&r.raw);
            let g = |k: &str| f.get(k).cloned().unwrap_or_default();
            let bad = |e: &str| {
                let mut x = Resp::json(json!({"error": e}));
                x.status = 400;
                x
            };
            let (resource, scope) = match g("grant_type").as_str() {
                "authorization_code" => {
                    let Some(c) = s.codes.remove(&g("code")) else {
                        return bad("invalid_grant");
                    };
                    use sha2::Digest as _;
                    let ch = base64::engine::general_purpose::URL_SAFE_NO_PAD
                        .encode(sha2::Sha256::digest(g("code_verifier").as_bytes()));
                    if ch != c.challenge
                        || c.redirect != g("redirect_uri")
                        || c.resource != g("resource")
                        || c.client_id != g("client_id")
                    {
                        return bad("invalid_grant");
                    }
                    (c.resource, c.scope)
                }
                "refresh_token" => {
                    s.refresh_requests += 1;
                    if s.knobs.reject_refresh_resource && f.contains_key("resource") {
                        return bad("invalid_target");
                    }
                    let Some(v) = s.refresh.remove(&g("refresh_token")) else {
                        return bad("invalid_grant");
                    };
                    v
                }
                _ => return bad("unsupported_grant_type"),
            };
            s.seq += 1;
            let (at, rt) = (
                format!("at-secret-{}", s.seq),
                format!("rt-secret-{}", s.seq),
            );
            s.issued_values.push(at.clone());
            s.issued_values.push(rt.clone());
            let exp = s.knobs.expires_in;
            s.tokens.insert(
                at.clone(),
                Issued {
                    resource: resource.clone(),
                    scopes: scope.split_whitespace().map(String::from).collect(),
                    expires: Instant::now() + Duration::from_secs(exp),
                },
            );
            s.refresh.insert(rt.clone(), (resource, scope.clone()));
            if s.knobs.form_encoded {
                let body = url::form_urlencoded::Serializer::new(String::new())
                    .append_pair("access_token", &at)
                    .append_pair("token_type", "bearer")
                    .append_pair("refresh_token", &rt)
                    .append_pair("scope", &scope)
                    .append_pair("expires_in", &exp.to_string())
                    .finish();
                return Resp {
                    status: 200,
                    headers: vec![(
                        "Content-Type".into(),
                        "application/x-www-form-urlencoded".into(),
                    )],
                    body,
                };
            }
            Resp::json(
                json!({"access_token": at, "token_type": "Bearer", "refresh_token": rt,
                "expires_in": exp, "scope": scope}),
            )
        }
        _ => Resp::status(404, "not found"),
    }
}
