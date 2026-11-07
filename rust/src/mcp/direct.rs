//! The MCP 2026-07-28 path behind the existing MCP source (SPEC §2.2–§2.8).
//!
//! [`probe`] sends `server/discover` first. A server that speaks `2026-07-28`
//! is used directly: its tools become the same uniform [`Tool`]s (naming,
//! schema normalization, result mapping, `tools` filter) as on the frozen
//! path, MRTR `input_required` rounds go through §10 `wait_for` (or return a
//! resumable `{status:"pending"}`), skills from the skills extension join the
//! `skill` tool, and `toolSearch` exposes `search_tools` + `call_tool`.
//! Anything else is handed to the frozen SDK path (§2.2a).

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Value, json};

use super::elicit::{answer_to_elicit_result, elicitation_to_request};
use super::oauth::{OAuth, OpenUrlFn, TokenStore};
use super::wire::{Obj, PROTOCOL_VERSION, SKILLS_EXT, Session, WireError, lock, sha256_hex};
pub(crate) use super::wire::{StdioHandoff, StdoutReader};
use super::{
    ServerConfig, apply_tools_filter, build_input_schema, expand_env_headers, hints_of,
    map_wire_result,
};
use crate::client::WaitForFn;
use crate::skills::{SkillDef, skill_def_from_markdown};
use crate::tool::{Answer, Request, Tool, ToolContext, ToolResult, ToolSource, pending, sanitize};

/// What a load needs beyond the server config.
#[derive(Clone)]
pub(crate) struct LoadCtx {
    pub(crate) wait_for: Option<WaitForFn>,
    pub(crate) store: Arc<dyn TokenStore>,
    pub(crate) open_url: Option<OpenUrlFn>,
    pub(crate) http: reqwest::Client,
    /// Toolkit `disableTools` (final names): also hidden from tool search.
    pub(crate) disabled: HashSet<String>,
}

pub(crate) enum Probe {
    /// The server speaks 2026-07-28.
    Direct(Connected),
    /// Use the frozen SDK path; `bearer` is a token OAuth already obtained.
    Fallback {
        bearer: String,
        /// stdio only: the probe child after a fast non-2026 discover reply,
        /// to continue into `initialize` (no respawn). `None` ⇒ spawn anew.
        stdio: Option<Box<StdioHandoff>>,
    },
}

pub(crate) struct Connected {
    pub(crate) tools: Vec<Tool>,
    pub(crate) skills: Vec<SkillDef>,
    pub(crate) server: Arc<DirectServer>,
}

/// A connected 2026 server.
pub(crate) struct DirectServer {
    name: String,
    session: Session,
    wait_for: Option<WaitForFn>,
    filter: BTreeMap<String, bool>,
    disabled: HashSet<String>,
    /// Paused tool calls by §10 request id.
    paused: Mutex<HashMap<String, Round>>,
}

impl DirectServer {
    pub(crate) async fn close(&self) {
        self.session.close().await;
    }
}

/// Discover first; connect directly, fall back, or fail (§2.2a).
pub(crate) async fn probe(name: &str, cfg: &ServerConfig, ctx: &LoadCtx) -> Result<Probe, String> {
    let remote = cfg.is_remote();
    let session = if remote {
        let url = cfg.url.clone().unwrap_or_default();
        let auth = Arc::new(OAuth::new(
            cfg.auth.clone().unwrap_or_default(),
            ctx.store.clone(),
            ctx.open_url.clone(),
            ctx.http.clone(),
        ));
        Session::http(
            url,
            expand_env_headers(&cfg.headers),
            ctx.http.clone(),
            auth,
            cfg.timeout(),
        )
    } else {
        if cfg.command.is_empty() {
            return Err(format!("local server {name:?} has no command"));
        }
        Session::stdio(&cfg.command, &cfg.env, cfg.cwd.as_deref(), cfg.timeout())?
    };
    // older stdio servers may ignore a pre-`initialize` request
    let dt = if remote {
        cfg.timeout()
    } else {
        cfg.timeout().min(Duration::from_secs(5))
    };
    let r = resolve_auth(&session, &ctx.wait_for, || {
        session.call_within("server/discover", json!({}), dt)
    })
    .await;
    let discovered = match r {
        Ok(d) if supports_2026(&d) => d,
        Ok(_)
        | Err(WireError::Rpc(_))
        | Err(WireError::NotRpc(_))
        | Err(WireError::Http(400 | 404 | 405 | 500..=599)) => {
            // SPEC §2.2a (amended): a 5xx on discover also tries the frozen path
            // (some SDK servers answer unknown methods with 500)
            return Ok(fallback(session, cfg, true).await);
        }
        // silence: the probe child is killed and the frozen path respawns
        Err(WireError::Timeout(_)) if !remote => return Ok(fallback(session, cfg, false).await),
        Err(e) => {
            session.close().await;
            return Err(e.to_string());
        }
    };
    session.release_probe();
    let defs = match resolve_auth(&session, &ctx.wait_for, || session.list_tools(true)).await {
        Ok(d) => d,
        Err(e) => {
            session.close().await;
            return Err(format!("list tools failed: {e}"));
        }
    };
    let server = Arc::new(DirectServer {
        name: name.to_owned(),
        session,
        wait_for: ctx.wait_for.clone(),
        filter: cfg.tools.clone(),
        disabled: ctx.disabled.clone(),
        paused: Mutex::new(HashMap::new()),
    });
    let has_skills = discovered
        .get("capabilities")
        .and_then(|c| c.get("extensions"))
        .and_then(|e| e.get(SKILLS_EXT))
        .is_some();
    let skills = if has_skills {
        remote_skills(&server).await
    } else {
        Vec::new()
    };
    let tools = if cfg.tool_search {
        search_tools(&server, cfg.description.as_deref())
    } else {
        apply_tools_filter(name, defs, &cfg.tools, |d| {
            d.get("name").and_then(Value::as_str).unwrap_or("")
        })
        .into_iter()
        .map(|d| direct_tool(&server, &d))
        .collect()
    };
    Ok(Probe::Direct(Connected {
        tools,
        skills,
        server,
    }))
}

async fn fallback(session: Session, cfg: &ServerConfig, replied: bool) -> Probe {
    let bearer = session
        .auth()
        .map(|a| a.stored_bearer(cfg.url.as_deref().unwrap_or_default()))
        .unwrap_or_default();
    // a stdio server that answered discover keeps its process (SPEC §2.2a)
    let stdio = if replied {
        session.take_stdio().await.map(Box::new)
    } else {
        None
    };
    session.close().await;
    Probe::Fallback { bearer, stdio }
}

fn supports_2026(d: &Obj) -> bool {
    d.get("supportedVersions")
        .and_then(Value::as_array)
        .is_some_and(|a| a.iter().any(|v| v.as_str() == Some(PROTOCOL_VERSION)))
}

/// Run `f`; an OAuth pause during load is resolved inline through `wait_for`
/// (then `f` is sent for the first time), else the load fails with
/// `authorization pending`.
async fn resolve_auth<T, F, Fut>(
    session: &Session,
    wait_for: &Option<WaitForFn>,
    f: F,
) -> Result<T, WireError>
where
    F: Fn() -> Fut,
    Fut: std::future::Future<Output = Result<T, WireError>>,
{
    loop {
        match f().await {
            Err(WireError::Pending(req)) => {
                let Some(w) = wait_for else {
                    return Err(WireError::Other("authorization pending".into()));
                };
                let answer = w(*req.clone()).await.map_err(WireError::Other)?;
                finish_auth(session, &req.id, &answer)
                    .await
                    .map_err(WireError::Other)?;
            }
            other => return other,
        }
    }
}

/// Complete a paused OAuth flow from its §10 answer (`data.callbackUrl`).
async fn finish_auth(session: &Session, id: &str, answer: &Answer) -> Result<(), String> {
    if !answer.ok {
        let reason = if answer.reason.is_empty() {
            "cancelled"
        } else {
            answer.reason.as_str()
        };
        return Err(format!("authorization denied: {reason}"));
    }
    let cb = answer
        .data
        .as_ref()
        .and_then(|d| d.get("callbackUrl"))
        .and_then(Value::as_str)
        .unwrap_or_default();
    let Some(auth) = session.auth() else {
        return Err(format!("unknown pending authorization {id:?}"));
    };
    auth.resume(id, cb).await.map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// tools/call + MRTR (§2.5)
// ---------------------------------------------------------------------------

enum Awaiting {
    Nothing,
    Input(String),
    Auth,
}

/// One tool call's progress: the round being built and what it waits on.
struct Round {
    name: String,
    args: Value,
    responses: Option<Obj>,
    state: Option<Value>,
    asks: VecDeque<(String, Obj)>,
    awaiting: Awaiting,
    rounds: usize,
}

const MAX_ROUNDS: usize = 8;

fn direct_tool(server: &Arc<DirectServer>, def: &Obj) -> Tool {
    let original = def
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let empty = serde_json::Map::new();
    let input = def
        .get("inputSchema")
        .and_then(Value::as_object)
        .unwrap_or(&empty);
    let srv = server.clone();
    Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: format!("{}_{}", sanitize(&server.name), sanitize(&original)),
        description: def
            .get("description")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned(),
        input_schema: build_input_schema(input),
        source: ToolSource::Mcp,
        hints: hints_of(&Value::Object(def.clone())),
        execute: Arc::new(move |args, ctx| {
            let (srv, original) = (srv.clone(), original.clone());
            Box::pin(async move { call_direct(srv, original, args, ctx).await })
        }),
    }
}

fn server_meta(name: &str) -> Obj {
    let mut m = Obj::new();
    m.insert("server".into(), json!(name));
    m
}

async fn call_direct(
    srv: Arc<DirectServer>,
    name: String,
    args: Value,
    ctx: ToolContext,
) -> ToolResult {
    let meta = server_meta(&srv.name);
    let err = |m: String| ToolResult::error(m).with_metadata(server_meta(&srv.name));
    let resumed = ctx
        .answer
        .as_ref()
        .and_then(|a| lock(&srv.paused).remove(&a.id).map(|r| (a, r)));
    let mut round = match resumed {
        Some((answer, mut r)) => {
            match std::mem::replace(&mut r.awaiting, Awaiting::Nothing) {
                Awaiting::Input(key) => {
                    r.responses
                        .get_or_insert_with(Obj::new)
                        .insert(key, answer_to_elicit_result(answer));
                }
                Awaiting::Auth => {
                    if let Err(e) = finish_auth(&srv.session, &answer.id, answer).await {
                        return err(e);
                    }
                }
                Awaiting::Nothing => {}
            }
            r
        }
        None => Round {
            name,
            args: if args.is_object() { args } else { json!({}) },
            responses: None,
            state: None,
            asks: VecDeque::new(),
            awaiting: Awaiting::Nothing,
            rounds: 0,
        },
    };
    loop {
        while let Some((key, ir)) = round.asks.pop_front() {
            let method = ir.get("method").and_then(Value::as_str).unwrap_or("");
            if method != "elicitation/create" {
                return err(format!("unsupported input request method {method:?}"));
            }
            let mut req = elicitation_to_request(ir.get("params").unwrap_or(&Value::Null));
            req.id = format!("mrtr-{}", super::wire::rand_str(12));
            match &srv.wait_for {
                Some(w) => match w(req).await {
                    Ok(a) => {
                        round
                            .responses
                            .get_or_insert_with(Obj::new)
                            .insert(key, answer_to_elicit_result(&a));
                    }
                    Err(e) => return err(e),
                },
                None => {
                    round.awaiting = Awaiting::Input(key);
                    return park(&srv, round, req, meta);
                }
            }
        }
        let mut p = Obj::new();
        p.insert("name".into(), json!(round.name));
        p.insert("arguments".into(), round.args.clone());
        if let Some(r) = &round.responses {
            p.insert("inputResponses".into(), Value::Object(r.clone()));
        }
        if let Some(s) = &round.state {
            p.insert("requestState".into(), s.clone());
        }
        let res = tokio::select! {
            r = srv.session.call("tools/call", Value::Object(p)) => r,
            _ = ctx.cancel.cancelled() => return err("cancelled".into()),
        };
        match res {
            // rejected before dispatch: the tool has not run; resuming sends this round
            Err(WireError::Pending(req)) => match &srv.wait_for {
                Some(w) => {
                    let answer = match w(*req.clone()).await {
                        Ok(a) => a,
                        Err(e) => return err(e),
                    };
                    if let Err(e) = finish_auth(&srv.session, &req.id, &answer).await {
                        return err(e);
                    }
                }
                None => {
                    round.awaiting = Awaiting::Auth;
                    return park(&srv, round, *req, meta);
                }
            },
            Err(e) => return err(e.to_string()),
            Ok(r) if r.get("resultType").and_then(Value::as_str) == Some("input_required") => {
                round.rounds += 1;
                if round.rounds > MAX_ROUNDS {
                    return err(format!(
                        "tools/call {}: too many input_required rounds",
                        round.name
                    ));
                }
                round.state = r.get("requestState").cloned().filter(|v| !v.is_null());
                round.responses = Some(Obj::new());
                round.asks = r
                    .get("inputRequests")
                    .and_then(Value::as_object)
                    .map(|m| {
                        m.iter()
                            .map(|(k, v)| (k.clone(), v.as_object().cloned().unwrap_or_default()))
                            .collect()
                    })
                    .unwrap_or_default();
            }
            Ok(r) => {
                let content: Vec<Value> = r
                    .get("content")
                    .and_then(Value::as_array)
                    .cloned()
                    .unwrap_or_default();
                return map_wire_result(
                    r.get("isError").and_then(Value::as_bool).unwrap_or(false),
                    r.get("structuredContent").filter(|v| !v.is_null()).cloned(),
                    &content,
                )
                .with_metadata(meta);
            }
        }
    }
}

/// Park the round under the request id and return the §10 pending result.
fn park(srv: &DirectServer, round: Round, req: Request, meta: Obj) -> ToolResult {
    lock(&srv.paused).insert(req.id.clone(), round);
    let mut res = pending(req);
    if let Some(m) = res.metadata.as_mut() {
        m.extend(meta);
    }
    res
}

// ---------------------------------------------------------------------------
// tool search (§2.8)
// ---------------------------------------------------------------------------

fn search_tools(server: &Arc<DirectServer>, description: Option<&str>) -> Vec<Tool> {
    let description = description.unwrap_or("").to_owned();
    let about = if description.is_empty() {
        server.name.clone()
    } else {
        description.clone()
    };
    let prefix = sanitize(&server.name);
    let (s1, s2) = (server.clone(), server.clone());
    let search = Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: format!("{prefix}_search_tools"),
        description: format!(
            "Search the tools of {about} by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."
        ),
        input_schema: json!({"type": "object", "properties": {"query": {"type": "string"}, "limit": {"type": "integer"}}, "required": ["query"]}),
        source: ToolSource::Mcp,
        hints: None,
        execute: Arc::new(move |args, _ctx| {
            let (srv, description) = (s1.clone(), description.clone());
            Box::pin(async move {
                let meta = server_meta(&srv.name);
                let tools = match srv.searchable().await {
                    Ok(t) => t,
                    Err(e) => return ToolResult::error(e.to_string()).with_metadata(meta),
                };
                let q = args
                    .get("query")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                let limit = args
                    .get("limit")
                    .and_then(Value::as_f64)
                    .filter(|l| *l > 0.0)
                    .map_or(5, |l| l as usize);
                // SPEC §2.8: compact JSON array of hits, best first ("[]" on no
                // match), keys in the order name, description, inputSchema, server
                let enc = |v: Option<&Value>| {
                    serde_json::to_string(v.unwrap_or(&Value::Null)).unwrap_or_default()
                };
                let hits: Vec<String> = bm25(&tools, q)
                    .into_iter()
                    .take(limit)
                    .map(|i| {
                        let t = &tools[i];
                        format!(
                            "{{\"name\":{},\"description\":{},\"inputSchema\":{},\"server\":{}}}",
                            enc(t.get("name")),
                            enc(t.get("description")),
                            t.get("name")
                                .and_then(Value::as_str)
                                .and_then(|n| srv.session.raw_schema(n))
                                .unwrap_or_else(|| enc(t.get("inputSchema"))),
                            enc(Some(&json!(description)))
                        )
                    })
                    .collect();
                ToolResult::ok(format!("[{}]", hits.join(","))).with_metadata(meta)
            })
        }),
    };
    let call = Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: format!("{prefix}_call_tool"),
        description: "Run a tool found with search_tools.".into(),
        input_schema: json!({"type": "object", "properties": {"name": {"type": "string"}, "arguments": {"type": "object"}}, "required": ["name"]}),
        source: ToolSource::Mcp,
        hints: None,
        execute: Arc::new(move |args, ctx| {
            let srv = s2.clone();
            Box::pin(async move {
                let n = args
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned();
                let a = args
                    .get("arguments")
                    .filter(|v| v.is_object())
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                let resuming = ctx
                    .answer
                    .as_ref()
                    .is_some_and(|ans| lock(&srv.paused).contains_key(&ans.id));
                if !resuming {
                    match srv.searchable().await {
                        Ok(ts)
                            if ts
                                .iter()
                                .any(|t| t.get("name").and_then(Value::as_str) == Some(&n)) => {}
                        Ok(_) => {
                            return ToolResult::error(format!(
                                "unknown tool {n:?} on {}",
                                srv.name
                            ))
                            .with_metadata(server_meta(&srv.name));
                        }
                        Err(e) => {
                            return ToolResult::error(e.to_string())
                                .with_metadata(server_meta(&srv.name));
                        }
                    }
                }
                call_direct(srv, n, a, ctx).await
            })
        }),
    };
    vec![search, call]
}

impl DirectServer {
    /// The server's tools (cached for `ttlMs`) after the `tools` filter.
    async fn searchable(&self) -> Result<Vec<Obj>, WireError> {
        let defs = self.session.list_tools(false).await?;
        let prefix = sanitize(&self.name);
        Ok(apply_tools_filter(&self.name, defs, &self.filter, |d| {
            d.get("name").and_then(Value::as_str).unwrap_or("")
        })
        .into_iter()
        .filter(|d| {
            let n = d.get("name").and_then(Value::as_str).unwrap_or("");
            !self.disabled.contains(&format!("{prefix}_{}", sanitize(n)))
        })
        .collect())
    }
}

/// Indices of `tools` matching `query`, best first (BM25; name tokens count twice).
fn bm25(tools: &[Obj], query: &str) -> Vec<usize> {
    let s = |t: &Obj, k: &str| {
        t.get(k)
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned()
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
    hits.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal)); // stable
    hits.into_iter().map(|(i, _)| i).collect()
}

/// Lowercase, split on non-alphanumerics and camelCase boundaries.
fn tokens(s: &str) -> Vec<String> {
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

// ---------------------------------------------------------------------------
// skills extension (§2.7)
// ---------------------------------------------------------------------------

/// Split `skill://<name>/<path>` into a validated name and relative path:
/// percent-decode once, then reject leftover `%`, `\`, NUL, `:` and any empty,
/// `.` or `..` segment.
pub fn parse_skill_uri(uri: &str) -> Result<(String, String), String> {
    let rest = uri.strip_prefix("skill://").ok_or("not a skill:// URI")?;
    let dec = path_unescape(rest).ok_or("bad percent-encoding")?;
    if dec.contains(['%', '\\', '\0']) {
        return Err("skill URI has encoded, backslash or NUL characters".into());
    }
    let segs: Vec<&str> = dec.split('/').collect();
    if segs.len() < 2 {
        return Err("skill URI needs skill://<name>/<path>".into());
    }
    if segs
        .iter()
        .any(|s| s.is_empty() || *s == "." || *s == ".." || s.contains(':'))
    {
        return Err("skill URI path escapes the skill (empty, '.', '..' or ':' segment)".into());
    }
    Ok((segs[0].to_owned(), segs[1..].join("/")))
}

fn path_unescape(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let h = std::str::from_utf8(b.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(h, 16).ok()?);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

/// Every skill the server lists, as data for the `skill` tool. A skill whose
/// URIs escape it, or whose SKILL.md fails its digest, is skipped (logged).
async fn remote_skills(srv: &DirectServer) -> Vec<SkillDef> {
    let list = match srv.session.call("skills/list", json!({})).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!(
                "[toolnexus] MCP server {:?} skills/list failed: {e}",
                srv.name
            );
            return Vec::new();
        }
    };
    let uris: Vec<String> = list
        .get("skills")
        .and_then(Value::as_array)
        .map(|a| {
            a.iter()
                .filter_map(|s| s.get("uri").and_then(Value::as_str).map(String::from))
                .collect()
        })
        .unwrap_or_default();
    let mut out = Vec::new();
    for uri in uris {
        match remote_skill(srv, &uri).await {
            Ok(Some(d)) => out.push(d),
            Ok(None) => {}
            Err(e) => eprintln!("[toolnexus] MCP server {:?} skill {uri:?}: {e}", srv.name),
        }
    }
    out
}

async fn remote_skill(srv: &DirectServer, uri: &str) -> Result<Option<SkillDef>, String> {
    let (name, _) = parse_skill_uri(uri)?;
    let got = srv
        .session
        .call("skills/get", json!({"uri": uri}))
        .await
        .map_err(|e| e.to_string())?;
    let res = got
        .get("skill")
        .and_then(|s| s.get("resources"))
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut resources = Vec::new();
    let mut digest = None;
    for m in &res {
        let fu = m.get("uri").and_then(Value::as_str).unwrap_or_default();
        let rel = match parse_skill_uri(fu) {
            Ok((n, rel)) if n == name => rel,
            _ => {
                return Err(format!(
                    "refusing skill resource {fu:?}: outside skill {name}"
                ));
            }
        };
        if fu == uri {
            digest = m.get("digest").and_then(Value::as_str).map(String::from);
        } else {
            resources.push(rel);
        }
    }
    let rr = srv
        .session
        .call("resources/read", json!({"uri": uri}))
        .await
        .map_err(|e| e.to_string())?;
    let text = rr
        .get("contents")
        .and_then(Value::as_array)
        .and_then(|c| c.first())
        .and_then(|c| c.get("text"))
        .and_then(Value::as_str)
        .ok_or("empty resources/read")?;
    if let Some(d) = digest.filter(|d| !d.is_empty())
        && d != format!("sha256:{}", sha256_hex(text.as_bytes()))
    {
        return Err("digest mismatch".into());
    }
    Ok(skill_def_from_markdown(
        text,
        format!("skill://{name}/"),
        resources,
    ))
}
