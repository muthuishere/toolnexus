//! Toolkit — the aggregator (SPEC §4): MCP → skill → builtin → A2A agents → extraTools,
//! first name wins, an `extraTools` entry shadows a builtin of the same name.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, RwLock};

use serde_json::Value;

use crate::a2a::{Agent, agent_tools, parse_agents_value};
use crate::adapters::{to_anthropic, to_gemini, to_openai};
use crate::builtins::{BuiltinsConfig, select_builtins};
use crate::mcp::{self, McpConfig, McpSource, McpStatus};
use crate::skills::{LoadSkillsOptions, SkillDef, SkillSource, load_skills_with};
use crate::tool::{Answer, Tool, ToolContext, ToolResult};

/// Where the MCP config comes from: a file path, a parsed JSON value, or typed config.
#[derive(Debug, Clone)]
pub enum McpConfigInput {
    Path(PathBuf),
    Json(Value),
    Parsed(McpConfig),
}

/// The future a [`SkillProvider`] returns.
pub type SkillProviderFuture = Pin<Box<dyn Future<Output = Result<Vec<SkillDef>, String>> + Send>>;

/// A host skill provider (S1): called once at construction; an error is logged and ignored.
pub type SkillProvider = Arc<dyn Fn() -> SkillProviderFuture + Send + Sync>;

/// `createToolkit` options (§4).
#[derive(Clone, Default)]
pub struct ToolkitOptions {
    pub mcp_config: Option<McpConfigInput>,
    pub skills_dir: Vec<PathBuf>,
    /// Skills as data (S1).
    pub skills: Vec<SkillDef>,
    pub skill_provider: Option<SkillProvider>,
    /// Skill allowlist (S2).
    pub skills_filter: HashMap<String, bool>,
    /// `<skill_files>` sample cap (S5); 0 ⇒ 10.
    pub skill_sample_limit: i64,
    pub disable_tools: Vec<String>,
    pub disable_skills: Vec<String>,
    pub extra_tools: Vec<Tool>,
    /// `None` ⇒ the config's top-level `builtins` key, else all on.
    pub builtins: Option<BuiltinsConfig>,
    /// Remote A2A agents (§7A): each advertised skill becomes a `source: "a2a"`
    /// tool. A top-level `agents` block in the MCP config adds more. A failing
    /// agent is logged and skipped.
    pub agents: Vec<Agent>,
    /// Host resolver for out-of-band input (§10). When set, connected MCP
    /// servers may elicit input mid-`tools/call` and it is bridged onto this
    /// `wait_for` (form → `kind:"input"`, URL → `kind:"authorization"`) —
    /// typically the same function given to the client. `None` ⇒ MCP
    /// elicitation is not advertised.
    pub wait_for: Option<crate::client::WaitForFn>,
    /// Where MCP OAuth tokens live (§2A.1a); `None` ⇒ in memory, per load.
    pub token_store: Option<Arc<dyn mcp::TokenStore>>,
    /// How `auto`-mode MCP OAuth opens the authorization URL; `None` ⇒ the OS opener.
    pub open_url: Option<mcp::OpenUrlFn>,
    /// Media generation (§7G): its tools register after A2A agent tools and
    /// before `extra_tools` (first name wins).
    pub media: Option<crate::media::Media>,
}

/// Construction failure (bad MCP config file, or no shell for an enabled `bash`).
#[derive(Debug, thiserror::Error)]
pub enum ToolkitError {
    #[error("{0}")]
    Mcp(#[from] mcp::McpError),
    #[error("{0}")]
    Builtins(String),
    /// A relay tool shares a name with a builtin (ADR-0010 D5).
    #[error("{0}")]
    Relay(String),
}

#[derive(Default)]
struct Registry {
    order: Vec<String>,
    by_name: HashMap<String, Tool>,
}

impl Registry {
    fn add(&mut self, t: Tool) {
        if self.by_name.contains_key(&t.name) {
            eprintln!(
                "[toolnexus] duplicate tool name {:?} — keeping first",
                t.name
            );
            return;
        }
        self.order.push(t.name.clone());
        self.by_name.insert(t.name.clone(), t);
    }
}

/// The aggregated toolkit. Cheap to clone (shared state).
#[derive(Clone)]
pub struct Toolkit {
    reg: Arc<RwLock<Registry>>,
    mcp: Option<Arc<McpSource>>,
    skill: Option<Arc<SkillSource>>,
    /// The parsed MCP config, for the `a2a` / `mcpServer` serve blocks.
    #[cfg_attr(not(feature = "serve"), allow(dead_code))]
    raw_config: Option<Arc<Value>>,
}

impl std::fmt::Debug for Toolkit {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Toolkit")
            .field("tools", &self.names())
            .finish_non_exhaustive()
    }
}

/// Build a toolkit (§4).
pub async fn create_toolkit(opts: ToolkitOptions) -> Result<Toolkit, ToolkitError> {
    let mut raw_cfg: Option<Value> = None;
    let mcp_cfg: Option<McpConfig> = match &opts.mcp_config {
        None => None,
        Some(McpConfigInput::Path(p)) => {
            let text = std::fs::read_to_string(p).map_err(mcp::McpError::Read)?;
            raw_cfg = serde_json::from_str(&text).ok();
            Some(mcp::parse_mcp_config_str(&text)?)
        }
        Some(McpConfigInput::Json(v)) => {
            raw_cfg = Some(v.clone());
            Some(mcp::parse_mcp_config(v)?)
        }
        Some(McpConfigInput::Parsed(c)) => Some(c.clone()),
    };
    let mcp = match mcp_cfg {
        Some(c) => Some(Arc::new(
            mcp::load_mcp_with(
                &c,
                mcp::McpLoadOptions {
                    wait_for: opts.wait_for.clone(),
                    token_store: opts.token_store.clone(),
                    open_url: opts.open_url.clone(),
                    disable_tools: opts.disable_tools.clone(),
                    ..Default::default()
                },
            )
            .await,
        )),
        None => None,
    };

    // skills served by MCP servers (§2.7) join the same `skill` tool
    let remote_skills: Vec<SkillDef> = mcp.as_ref().map(|m| m.skills.clone()).unwrap_or_default();
    let skill = if !opts.skills_dir.is_empty()
        || !opts.skills.is_empty()
        || !remote_skills.is_empty()
        || opts.skill_provider.is_some()
    {
        let mut defs = opts.skills.clone();
        defs.extend(remote_skills);
        if let Some(p) = &opts.skill_provider {
            match p().await {
                Ok(more) => defs.extend(more),
                Err(e) => eprintln!("[toolnexus] skill provider failed: {e}"),
            }
        }
        let mut filter = opts.skills_filter.clone();
        for n in &opts.disable_skills {
            filter.entry(n.clone()).or_insert(false);
        }
        Some(Arc::new(load_skills_with(LoadSkillsOptions {
            dirs: opts.skills_dir.clone(),
            skills: defs,
            filter,
            sample_limit: opts.skill_sample_limit,
        })))
    } else {
        None
    };

    let builtins_cfg = match (
        &opts.builtins,
        raw_cfg.as_ref().and_then(|v| v.get("builtins")),
    ) {
        (Some(b), _) => b.clone(),
        (None, Some(v)) => BuiltinsConfig::from_value(v),
        (None, None) => BuiltinsConfig::default(),
    };
    let builtins = select_builtins(&builtins_cfg).map_err(ToolkitError::Builtins)?;

    let extra: HashSet<&str> = opts.extra_tools.iter().map(|t| t.name.as_str()).collect();
    let disabled: HashSet<&str> = opts.disable_tools.iter().map(String::as_str).collect();
    let mut all: Vec<Tool> = Vec::new();
    if let Some(m) = &mcp {
        all.extend(m.tools.iter().cloned());
    }
    if let Some(s) = &skill {
        all.push(s.tool.clone());
    }
    all.extend(
        builtins
            .into_iter()
            .filter(|b| !extra.contains(b.name.as_str())),
    );
    let mut agents = opts.agents.clone();
    if let Some(block) = raw_cfg.as_ref().and_then(|v| v.get("agents")) {
        agents.extend(parse_agents_value(block));
    }
    for ag in &agents {
        match agent_tools(ag).await {
            Ok(ts) => all.extend(ts),
            Err(e) => eprintln!("[toolnexus] A2A agent {:?} failed: {e}", ag.card),
        }
    }
    if let Some(m) = &opts.media {
        all.extend(m.tools());
    }
    all.extend(opts.extra_tools.iter().cloned());
    // A relay tool must never share a name with a builtin — checked
    // unconditionally, even with builtins off (ADR-0010 D5).
    crate::relay::check_relay_collisions(&all).map_err(ToolkitError::Relay)?;

    let mut reg = Registry::default();
    for t in all {
        if !disabled.contains(t.name.as_str()) {
            reg.add(t);
        }
    }
    Ok(Toolkit {
        reg: Arc::new(RwLock::new(reg)),
        mcp,
        skill,
        raw_config: raw_cfg.map(Arc::new),
    })
}

impl Toolkit {
    /// Add tools; safe while a turn is running (offered from the next model call).
    pub fn register(&self, tools: impl IntoIterator<Item = Tool>) -> &Self {
        let mut reg = self.reg.write().unwrap_or_else(|e| e.into_inner());
        for t in tools {
            reg.add(t);
        }
        self
    }

    /// Fetch a remote A2A agent's card at runtime and register its skills as
    /// tools (first name wins).
    pub async fn add_agent(&self, agent: impl Into<Agent>) -> Result<&Self, String> {
        let tools = agent_tools(&agent.into()).await?;
        Ok(self.register(tools))
    }

    /// A top-level block of the config this toolkit was built from.
    #[cfg_attr(not(feature = "serve"), allow(dead_code))]
    pub(crate) fn config_block(&self, key: &str) -> Option<Value> {
        self.raw_config.as_ref()?.get(key).cloned()
    }

    /// Every tool, in assembly order.
    pub fn tools(&self) -> Vec<Tool> {
        let reg = self.reg.read().unwrap_or_else(|e| e.into_inner());
        reg.order.iter().map(|n| reg.by_name[n].clone()).collect()
    }

    fn names(&self) -> Vec<String> {
        self.reg
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .order
            .clone()
    }

    pub fn get(&self, name: &str) -> Option<Tool> {
        self.reg
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .by_name
            .get(name)
            .cloned()
    }

    /// Route a call; an unknown name is `isError` `Unknown tool: <name>`.
    pub async fn execute(&self, name: &str, args: Value, ctx: ToolContext) -> ToolResult {
        match self.get(name) {
            Some(t) => t.call(args, ctx).await,
            None => ToolResult::error(format!("Unknown tool: {name}")),
        }
    }

    /// Execute with a §10 answer attached (re-execution after a suspension).
    pub async fn execute_with_answer(
        &self,
        name: &str,
        args: Value,
        mut ctx: ToolContext,
        answer: Answer,
    ) -> ToolResult {
        ctx.answer = Some(answer);
        self.execute(name, args, ctx).await
    }

    pub fn skills_prompt(&self) -> String {
        self.skill.as_ref().map(|s| s.prompt()).unwrap_or_default()
    }

    pub fn skill_source(&self) -> Option<&SkillSource> {
        self.skill.as_deref()
    }

    pub fn mcp_source(&self) -> Option<&McpSource> {
        self.mcp.as_deref()
    }

    pub fn mcp_status(&self) -> BTreeMap<String, McpStatus> {
        self.mcp
            .as_ref()
            .map(|m| m.status.clone())
            .unwrap_or_default()
    }

    pub fn to_openai(&self) -> Vec<Value> {
        to_openai(&self.tools())
    }
    pub fn to_anthropic(&self) -> Vec<Value> {
        to_anthropic(&self.tools())
    }
    pub fn to_gemini(&self) -> Vec<Value> {
        to_gemini(&self.tools())
    }

    /// Identity of the shared registry: equal for clones of one toolkit (keys
    /// per-toolkit client state such as a frozen tool-tier plan).
    pub fn identity(&self) -> usize {
        Arc::as_ptr(&self.reg) as *const () as usize
    }

    /// Disconnect MCP servers. Idempotent.
    pub async fn close(&self) {
        if let Some(m) = &self.mcp {
            m.close().await;
        }
    }
}
