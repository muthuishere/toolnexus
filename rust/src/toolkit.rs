//! Toolkit — the aggregator (SPEC §4): MCP → skill → builtin → extraTools,
//! first name wins, an `extraTools` entry shadows a builtin of the same name.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, RwLock};

use serde_json::Value;

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
}

/// Construction failure (bad MCP config file, or no shell for an enabled `bash`).
#[derive(Debug, thiserror::Error)]
pub enum ToolkitError {
    #[error("{0}")]
    Mcp(#[from] mcp::McpError),
    #[error("{0}")]
    Builtins(String),
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
        Some(c) => Some(Arc::new(mcp::load_mcp(&c).await)),
        None => None,
    };

    let skill = if !opts.skills_dir.is_empty()
        || !opts.skills.is_empty()
        || opts.skill_provider.is_some()
    {
        let mut defs = opts.skills.clone();
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
    all.extend(opts.extra_tools.iter().cloned());

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

    /// Disconnect MCP servers. Idempotent.
    pub async fn close(&self) {
        if let Some(m) = &self.mcp {
            m.close().await;
        }
    }
}
