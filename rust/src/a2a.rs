//! A2A (agent-to-agent) — OUTBOUND: call remote A2A agents (SPEC §7A). Port of
//! `golang/a2a.go`.
//!
//! A remote agent publishes an Agent Card at `/.well-known/agent-card.json`
//! describing its skills. Each advertised skill becomes a uniform [`Tool`]
//! (`source: "a2a"`) whose execute performs one JSON-RPC `SendMessage`
//! (non-blocking) to get a Task id, then polls `GetTask` until the Task reaches a
//! terminal state, mapping the Task's artifact/message text to a [`ToolResult`].
//!
//! `${ENV}` header values expand at call time and are never logged.

use std::collections::BTreeMap;
use std::sync::{Arc, LazyLock};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::mcp::expand_env_headers;
use crate::tool::{Tool, ToolContext, ToolResult, ToolSource, sanitize};

/// Default overall poll budget (300 s).
pub const A2A_DEFAULT_TIMEOUT_MS: u64 = 300_000;
/// Default interval between `GetTask` polls (1 s).
pub const A2A_DEFAULT_POLL_EVERY_MS: u64 = 1_000;

static HTTP: LazyLock<reqwest::Client> = LazyLock::new(reqwest::Client::new);

/// Whether a task state stops the poll loop.
fn terminal(state: &str) -> bool {
    matches!(state, "completed" | "failed" | "canceled")
}

// ---------------------------------------------------------------------------
// Wire types (the A2A subset every port speaks).
// ---------------------------------------------------------------------------

/// One content part. `text: None` (absent / null) is distinct from `""`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aPart {
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub text: Option<String>,
}

impl A2aPart {
    pub fn text(t: impl Into<String>) -> Self {
        Self {
            kind: "text".into(),
            text: Some(t.into()),
        }
    }
}

/// A role-tagged list of parts.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aMessage {
    #[serde(default)]
    pub role: String,
    #[serde(
        rename = "messageId",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub message_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub parts: Vec<A2aPart>,
}

/// A task output artifact.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aArtifact {
    #[serde(
        rename = "artifactId",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub artifact_id: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub parts: Vec<A2aPart>,
}

/// The task state plus an optional status message.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aTaskStatus {
    #[serde(default)]
    pub state: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<A2aMessage>,
}

/// The unit of work returned by `SendMessage` / `GetTask`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aTask {
    #[serde(default)]
    pub id: String,
    /// Groups a peer's turns into one conversation (A2A `contextId`).
    #[serde(
        rename = "contextId",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub context_id: String,
    #[serde(default)]
    pub status: A2aTaskStatus,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub artifacts: Vec<A2aArtifact>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub history: Vec<A2aMessage>,
}

/// One advertised skill from an Agent Card.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct A2aSkill {
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub name: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub description: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub examples: Vec<String>,
}

/// The descriptor a remote agent publishes at `/.well-known/agent-card.json`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct AgentCard {
    #[serde(default)]
    pub name: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub description: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub version: String,
    #[serde(
        rename = "protocolVersion",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub protocol_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Map<String, Value>>,
    #[serde(
        rename = "defaultInputModes",
        default,
        skip_serializing_if = "Vec::is_empty"
    )]
    pub default_input_modes: Vec<String>,
    #[serde(
        rename = "defaultOutputModes",
        default,
        skip_serializing_if = "Vec::is_empty"
    )]
    pub default_output_modes: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub skills: Vec<A2aSkill>,
    /// The JSON-RPC endpoint the agent listens on.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
}

// ---------------------------------------------------------------------------
// Agent descriptor + config block.
// ---------------------------------------------------------------------------

/// A remote A2A agent: its card URL, `${ENV}` headers, poll budget and interval.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Agent {
    /// URL of the Agent Card (`/.well-known/agent-card.json`).
    pub card: String,
    /// Static request headers; `${ENV_VAR}` values expand at call time.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<String, String>,
    /// Overall poll budget in ms (0 ⇒ 300000).
    #[serde(rename = "timeout", default)]
    pub timeout_ms: u64,
    /// Interval between `GetTask` polls in ms (0 ⇒ 1000).
    #[serde(rename = "pollEvery", default)]
    pub poll_every_ms: u64,
}

impl Agent {
    /// An agent at `card` with defaults.
    pub fn new(card: impl Into<String>) -> Self {
        Self {
            card: card.into(),
            ..Default::default()
        }
    }
    fn timeout(&self) -> Duration {
        Duration::from_millis(if self.timeout_ms > 0 {
            self.timeout_ms
        } else {
            A2A_DEFAULT_TIMEOUT_MS
        })
    }
    fn poll_every(&self) -> Duration {
        Duration::from_millis(if self.poll_every_ms > 0 {
            self.poll_every_ms
        } else {
            A2A_DEFAULT_POLL_EVERY_MS
        })
    }
}

impl From<&str> for Agent {
    fn from(card: &str) -> Self {
        Agent::new(card)
    }
}
impl From<String> for Agent {
    fn from(card: String) -> Self {
        Agent::new(card)
    }
}

/// One entry of a config-file `agents` block.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct AgentConfig {
    #[serde(default)]
    pub card: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    #[serde(default)]
    pub timeout: u64,
    #[serde(rename = "pollEvery", default)]
    pub poll_every: u64,
    #[serde(default)]
    pub enabled: Option<bool>,
    #[serde(default)]
    pub disabled: Option<bool>,
}

/// An `agents` block: identifier → entry.
pub type AgentsConfig = BTreeMap<String, AgentConfig>;

/// Turn an `agents` block into descriptors: `disabled: true` wins, then
/// `enabled: false`; an entry with no card is skipped. The key is only an
/// identifier — a tool's prefix comes from the fetched card's name.
pub fn parse_agents_config(block: &AgentsConfig) -> Vec<Agent> {
    block
        .values()
        .filter(|c| !c.card.is_empty())
        .filter(|c| c.disabled != Some(true) && c.enabled != Some(false))
        .map(|c| Agent {
            card: c.card.clone(),
            headers: c.headers.clone(),
            timeout_ms: c.timeout,
            poll_every_ms: c.poll_every,
        })
        .collect()
}

/// [`parse_agents_config`] over a raw JSON `agents` value (malformed ⇒ none).
pub fn parse_agents_value(v: &Value) -> Vec<Agent> {
    serde_json::from_value::<AgentsConfig>(v.clone())
        .map(|b| parse_agents_config(&b))
        .unwrap_or_default()
}

// ---------------------------------------------------------------------------
// JSON-RPC transport + card fetch.
// ---------------------------------------------------------------------------

fn apply_headers(
    mut req: reqwest::RequestBuilder,
    headers: &BTreeMap<String, String>,
) -> reqwest::RequestBuilder {
    for (k, v) in headers {
        req = req.header(k, v);
    }
    req
}

/// POST one JSON-RPC 2.0 request; the raw `result` (or the error text).
async fn json_rpc(
    endpoint: &str,
    method: &str,
    params: Value,
    headers: &BTreeMap<String, String>,
    budget: Duration,
) -> Result<Value, String> {
    let envelope = json!({
        "jsonrpc": "2.0",
        "id": uuid::Uuid::new_v4().to_string(),
        "method": method,
        "params": params,
    });
    let req = apply_headers(
        HTTP.post(endpoint)
            .timeout(budget)
            .header("Content-Type", "application/json")
            .body(envelope.to_string()),
        headers,
    );
    let resp = req.send().await.map_err(|e| e.to_string())?;
    let status = resp.status().as_u16();
    let raw = resp.text().await.unwrap_or_default();
    if !(200..300).contains(&status) {
        return Err(format!("HTTP {status}: {raw}"));
    }
    // A body that is not JSON yields a null result (no error).
    let payload: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
    if let Some(err) = payload.get("error").filter(|e| e.is_object()) {
        let msg = err.get("message").and_then(Value::as_str).unwrap_or("");
        if !msg.is_empty() {
            return Err(msg.to_owned());
        }
        let code = err
            .get("code")
            .and_then(Value::as_i64)
            .map(|c| c.to_string())
            .unwrap_or_default();
        return Err(format!("JSON-RPC error {code}").trim().to_owned());
    }
    Ok(payload.get("result").cloned().unwrap_or(Value::Null))
}

/// GET and parse the Agent Card at `card_url`.
pub async fn fetch_card(
    card_url: &str,
    headers: &BTreeMap<String, String>,
    budget: Duration,
) -> Result<AgentCard, String> {
    let resp = apply_headers(HTTP.get(card_url).timeout(budget), headers)
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let status = resp.status().as_u16();
    let raw = resp.text().await.unwrap_or_default();
    if !(200..300).contains(&status) {
        return Err(format!("HTTP {status}: {raw}"));
    }
    serde_json::from_str(&raw).map_err(|e| e.to_string())
}

fn texts(parts: &[A2aPart]) -> Vec<&str> {
    parts
        .iter()
        .filter(|p| p.kind == "text")
        .filter_map(|p| p.text.as_deref())
        .collect()
}

/// A task's text output: artifact text parts, else the last agent message.
fn extract_output(task: &A2aTask) -> String {
    let parts: Vec<&str> = task
        .artifacts
        .iter()
        .flat_map(|a| texts(&a.parts))
        .collect();
    if !parts.is_empty() {
        return parts.join("\n");
    }
    for msg in task.history.iter().rev() {
        if msg.role != "agent" {
            continue;
        }
        let text = texts(&msg.parts).join("\n");
        if !text.is_empty() {
            return text;
        }
    }
    String::new()
}

fn status_message_text(task: &A2aTask) -> String {
    task.status
        .message
        .as_ref()
        .map(|m| texts(&m.parts).join("\n"))
        .unwrap_or_default()
}

/// `scheme://host[:port]` of a URL (the endpoint fallback); unparseable ⇒ as-is.
fn origin_of(raw: &str) -> String {
    match reqwest::Url::parse(raw) {
        Ok(u) if u.has_host() => {
            let origin = u.origin().ascii_serialization();
            if origin == "null" {
                raw.to_owned()
            } else {
                origin
            }
        }
        _ => raw.to_owned(),
    }
}

// ---------------------------------------------------------------------------
// Skill → Tool.
// ---------------------------------------------------------------------------

/// The fixed input schema every A2A tool advertises.
pub fn task_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "task": {
                "type": "string",
                "description": "The task to send to the agent, in natural language.",
            }
        },
        "required": ["task"],
        "additionalProperties": false,
    })
}

struct Run {
    agent: String,
    start: Instant,
    task_id: String,
    state: String,
    polls: u64,
}

impl Run {
    fn meta(&self) -> Map<String, Value> {
        let mut m = Map::new();
        m.insert("agent".into(), json!(self.agent));
        m.insert("taskId".into(), json!(self.task_id));
        m.insert("state".into(), json!(self.state));
        m.insert("polls".into(), json!(self.polls));
        m.insert("ms".into(), json!(self.start.elapsed().as_millis() as u64));
        m
    }
    fn error(&self, out: String) -> ToolResult {
        ToolResult::error(out).with_metadata(self.meta())
    }
    fn canceled(&mut self) -> ToolResult {
        self.state = "canceled".into();
        self.error(format!("A2A task {} canceled", self.task_id))
    }
}

/// Race `fut` against cancellation; `None` ⇒ cancelled.
async fn or_cancel<T>(cancel: &CancellationToken, fut: impl Future<Output = T>) -> Option<T> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => None,
        v = fut => Some(v),
    }
}

struct SkillCall {
    agent_name: String,
    endpoint: String,
    agent: Agent,
}

async fn execute_skill(sc: Arc<SkillCall>, args: Value, ctx: ToolContext) -> ToolResult {
    let budget = if ctx.timeout_ms > 0 {
        Duration::from_millis(ctx.timeout_ms)
    } else {
        sc.agent.timeout()
    };
    let poll_every = sc.agent.poll_every();
    let headers = expand_env_headers(&sc.agent.headers);
    let task_text = match args.get("task") {
        None | Some(Value::Null) => String::new(),
        Some(Value::String(s)) => s.clone(),
        Some(v) => v.to_string(),
    };
    let cancel = ctx.cancel.clone();
    let mut run = Run {
        agent: sc.agent_name.clone(),
        start: Instant::now(),
        task_id: String::new(),
        state: "submitted".into(),
        polls: 0,
    };

    // 1. SendMessage (non-blocking) → a submitted Task.
    let send = json!({
        "message": {
            "role": "user",
            "messageId": uuid::Uuid::new_v4().to_string(),
            "parts": [{"kind": "text", "text": task_text}],
        },
        "configuration": {"blocking": false},
    });
    let raw = match or_cancel(
        &cancel,
        json_rpc(&sc.endpoint, "SendMessage", send, &headers, budget),
    )
    .await
    {
        None => return run.canceled(),
        // A cancel that lands mid-request is still a cancel (§7A).
        Some(Err(_)) if cancel.is_cancelled() => return run.canceled(),
        Some(Err(e)) => return run.error(e),
        Some(Ok(v)) => v,
    };
    let mut task: A2aTask = serde_json::from_value(raw).unwrap_or_default();
    run.task_id = task.id.clone();
    if !task.status.state.is_empty() {
        run.state = task.status.state.clone();
    }

    // 2. Poll GetTask until terminal / timeout / cancel.
    while !terminal(&run.state) {
        if cancel.is_cancelled() {
            return run.canceled();
        }
        if run.start.elapsed() >= budget {
            let out = format!(
                "A2A task {} timed out after {}ms (state={})",
                run.task_id,
                budget.as_millis(),
                run.state
            );
            return run.error(out);
        }
        if or_cancel(&cancel, tokio::time::sleep(poll_every))
            .await
            .is_none()
        {
            return run.canceled();
        }
        let got = or_cancel(
            &cancel,
            json_rpc(
                &sc.endpoint,
                "GetTask",
                json!({"id": run.task_id}),
                &headers,
                budget,
            ),
        )
        .await;
        let raw = match got {
            None => return run.canceled(),
            Some(Err(_)) if cancel.is_cancelled() => return run.canceled(),
            Some(Err(e)) => return run.error(e),
            Some(Ok(v)) => v,
        };
        task = serde_json::from_value(raw).unwrap_or_default();
        run.polls += 1;
        if !task.status.state.is_empty() {
            run.state = task.status.state.clone();
        }
    }

    // 3. Terminal Task → ToolResult.
    if run.state == "completed" {
        return ToolResult::ok(extract_output(&task)).with_metadata(run.meta());
    }
    let detail = status_message_text(&task);
    let mut out = format!("A2A task {} {}", run.task_id, run.state);
    if !detail.is_empty() {
        out.push_str(": ");
        out.push_str(&detail);
    }
    run.error(out)
}

fn skill_tool(agent_name: &str, endpoint: &str, skill: &A2aSkill, agent: &Agent) -> Tool {
    let skill_id = if skill.id.is_empty() {
        &skill.name
    } else {
        &skill.id
    };
    let description = [&skill.description, &skill.name, skill_id]
        .into_iter()
        .find(|s| !s.is_empty())
        .cloned()
        .unwrap_or_default();
    let sc = Arc::new(SkillCall {
        agent_name: agent_name.to_owned(),
        endpoint: endpoint.to_owned(),
        agent: agent.clone(),
    });
    Tool {
        concurrency_safe: None,
        name: format!("{}_{}", sanitize(agent_name), sanitize(skill_id)),
        description,
        input_schema: task_schema(),
        source: ToolSource::A2a,
        hints: None,
        execute: Arc::new(move |args, ctx| Box::pin(execute_skill(sc.clone(), args, ctx))),
    }
}

/// Resolve an agent to its tools: fetch the card and make one tool per skill,
/// named `sanitize(card.name)_sanitize(skill.id)`. The card's `url` is the
/// JSON-RPC endpoint; absent, the card's origin.
pub async fn agent_tools(agent: &Agent) -> Result<Vec<Tool>, String> {
    let headers = expand_env_headers(&agent.headers);
    let card = fetch_card(&agent.card, &headers, agent.timeout()).await?;
    let name = if card.name.is_empty() {
        "agent".to_owned()
    } else {
        card.name.clone()
    };
    let endpoint = if card.url.is_empty() {
        origin_of(&agent.card)
    } else {
        card.url.clone()
    };
    Ok(card
        .skills
        .iter()
        .map(|s| skill_tool(&name, &endpoint, s, agent))
        .collect())
}
