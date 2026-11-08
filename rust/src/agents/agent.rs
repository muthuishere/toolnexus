//! Level-1 surface (SPEC §7D): one new noun — the Agent — compiled down to the
//! six runtime verbs. Declaring agents in each other's `team` IS the sub-agent
//! wiring; [`Agent::as_tool`] is the bridge back into the classic toolkit (an
//! Agent is a Tool). Port of `golang/agents/agent.go`.

use std::collections::HashMap;
use std::sync::Arc;

use serde_json::{Map, Value, json};

use super::agent_loop::guarded_hooks;
use super::runtime::{
    Budget, CloseOptions, Def, OnCloseFn, OnSpawnFn, Options, Runtime, TaskResult,
};
use crate::client::{BeforeToolEvent, Hooks, OnMetricFn, RunResult, WaitForFn};
use crate::tool::{Tool, ToolContext, ToolResult, ToolSource};

/// A POLICY check on a tool call: `""` or `"allow"` permits, anything else is
/// the denial reason. Never a correctness check.
pub type Guardrail = Arc<dyn Fn(&BeforeToolEvent) -> String + Send + Sync>;

/// `(ok, reason)`; the reason is fed back to the agent on failure.
pub type VerifyFn = Arc<dyn Fn(&RunResult) -> (bool, String) + Send + Sync>;

/// The gate an agent must pass before it may report `done`. The loop never
/// interprets `verify` — it counts attempts and reports honestly.
#[derive(Clone, Default)]
pub struct Completion {
    /// Required.
    pub verify: Option<VerifyFn>,
    /// REQUIRED (>= 1): an unbounded gate is a runaway loop.
    pub max_attempts: usize,
}

impl Completion {
    pub fn new(
        verify: impl Fn(&RunResult) -> (bool, String) + Send + Sync + 'static,
        max_attempts: usize,
    ) -> Self {
        Completion {
            verify: Some(Arc::new(verify)),
            max_attempts,
        }
    }
}

/// Declares an agent at the Level-1 surface.
#[derive(Clone, Default)]
pub struct Spec {
    /// What the delegating model sees — the routing description (required).
    pub does: String,
    /// The toolkit VIEW for this agent — scoping is the security model.
    pub tools: Vec<Tool>,
    /// Identity / system prompt, inline.
    pub soul: String,
    /// The path form of `soul`, read at registry-build time; wins when readable.
    pub soul_file: String,
    /// Agents this one may delegate to via `task`. Empty ⇒ no task tool.
    pub team: Vec<Agent>,
    pub budget: Option<Budget>,
    /// `""` ⇒ `"inherit"` (the runtime's LLM default).
    pub model: String,
    /// §10 interpreter authority for the subtree.
    pub wait_for: Option<WaitForFn>,
    pub on_spawn: Option<OnSpawnFn>,
    pub on_close: Option<OnCloseFn>,
    /// §8 hooks for THIS agent's turns; set ⇒ replaces `Options.hooks`.
    pub hooks: Option<Hooks>,
    /// §8 sink for THIS agent's turns; set ⇒ replaces `Options.on_metric`.
    pub on_metric: Option<OnMetricFn>,
    /// Compiled into one `before_tool` with FIRST-DENY-WINS, ahead of
    /// `hooks.before_tool`.
    pub guardrails: Vec<Guardrail>,
    /// The gate that stops this agent claiming `done` before its work
    /// verifies; travels with the agent into delegation.
    pub completion: Option<Completion>,
}

/// The one new noun.
#[derive(Clone)]
pub struct Agent {
    pub name: String,
    pub spec: Spec,
    /// Set by `from_dir(…, dream)`, read by `start_agent` (SPEC §7E "Dreaming").
    pub(crate) dream: Option<Arc<super::home::DreamConfig>>,
}

impl std::fmt::Debug for Agent {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Agent")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

/// Build an Agent.
pub fn new(name: impl Into<String>, spec: Spec) -> Agent {
    Agent {
        name: name.into(),
        spec,
        dream: None,
    }
}

/// The capability set an agent needs to COMPLETE its problem plus its
/// boundaries. A FACTORY over [`Spec`], not a second type: Model + Harness = Agent.
pub fn harness(s: Spec) -> Spec {
    s
}

/// Builds the runtime options per call from the calling tool's context.
pub type AsToolOptionsFn = Arc<dyn Fn(&ToolContext) -> Options + Send + Sync>;

impl Agent {
    /// This agent plus the TRANSITIVE CLOSURE of its team graph — unreachable
    /// agents are not present.
    pub fn registry(&self) -> HashMap<String, Def> {
        let mut acc = HashMap::new();
        self.registry_into(&mut acc);
        acc
    }

    fn registry_into(&self, acc: &mut HashMap<String, Def>) {
        if acc.contains_key(&self.name) {
            return;
        }
        let sp = &self.spec;
        let mut soul = sp.soul.clone();
        if !sp.soul_file.is_empty()
            && let Ok(s) = std::fs::read_to_string(&sp.soul_file)
        {
            soul = s;
        }
        let model = if sp.model.is_empty() {
            "inherit".to_string()
        } else {
            sp.model.clone()
        };
        acc.insert(
            self.name.clone(),
            Def {
                name: self.name.clone(),
                does: sp.does.clone(),
                soul,
                model,
                tools: sp.tools.clone(),
                team: sp.team.iter().map(|t| t.name.clone()).collect(),
                budget: sp.budget,
                wait_for: sp.wait_for.clone(),
                on_spawn: sp.on_spawn.clone(),
                on_close: sp.on_close.clone(),
                hooks: guarded_hooks(sp),
                completion: sp.completion.clone(),
                on_metric: sp.on_metric.clone(),
            },
        );
        for t in &sp.team {
            t.registry_into(acc);
        }
    }

    /// The Level-1 one-shot: build a runtime over this agent's registry, run
    /// the prompt, tear down — unless the run parks durably (`pending`), in
    /// which case the runtime stays alive so the host can `resume` it.
    pub async fn run(&self, mut opts: Options, prompt: &str) -> (TaskResult, Runtime) {
        opts.registry = self.registry();
        let rt = Runtime::new(opts);
        let root = rt.root();
        let h = match rt.spawn(&root, &self.name, None) {
            Ok(h) => h,
            Err(e) => {
                return (
                    TaskResult {
                        text: e,
                        is_error: true,
                        status: "error".into(),
                        ..Default::default()
                    },
                    rt,
                );
            }
        };
        rt.wake(&h, prompt);
        let r = rt.wait(&h, None).await;
        if r.status != "pending" {
            rt.close(&root, None).await;
        }
        (r, rt)
    }

    /// An Agent IS a Tool: its execute runs the agent's own loop in isolation
    /// and returns ONLY its final text plus metadata `{agent, turns,
    /// totalTokens}`. The calling turn's cancellation cancels the child.
    pub fn as_tool(&self, opts: Options) -> Tool {
        self.as_tool_with(Arc::new(move |_| opts.clone()))
    }

    /// `as_tool` with Options built per call from the calling tool's context,
    /// so the child runs under the parent turn's gates: `hooks` (the same
    /// `before_tool` permission gate), `wait_for` (the same ask bridge) and
    /// `on_metric`. A `Spec.hooks` on the agent replaces `Options.hooks`, so
    /// leave it unset when the gate must hold. When the calling context is
    /// cancelled the child is interrupted and the tool returns a cancelled
    /// error result.
    pub fn as_tool_with(&self, per: AsToolOptionsFn) -> Tool {
        let agent = self.clone();
        Tool {
            output_schema: None,
            annotations: None,
            concurrency_safe: None,
            name: self.name.clone(),
            description: self.spec.does.clone(),
            input_schema: json!({
                "type": "object",
                "properties": {"prompt": {"type": "string"}},
                "required": ["prompt"],
            }),
            source: ToolSource::Custom,
            hints: None,
            execute: Arc::new(move |args, ctx| {
                let agent = agent.clone();
                let per = per.clone();
                Box::pin(async move {
                    let prompt = args.get("prompt").and_then(Value::as_str).unwrap_or("");
                    let r = agent.run_cancellable(&ctx, per(&ctx), prompt).await;
                    let mut meta = Map::new();
                    meta.insert("agent".into(), json!(agent.name));
                    meta.insert("turns".into(), json!(r.turns));
                    meta.insert("totalTokens".into(), json!(r.total_tokens));
                    if r.status == "cancelled" {
                        return ToolResult {
                            output: format!("cancelled: {}", r.text),
                            is_error: true,
                            metadata: Some(meta),
                            ..Default::default()
                        };
                    }
                    ToolResult {
                        output: r.text,
                        is_error: r.is_error,
                        metadata: Some(meta),
                        ..Default::default()
                    }
                })
            }),
        }
    }

    /// `run` that interrupts the child when the calling context is cancelled.
    async fn run_cancellable(
        &self,
        ctx: &ToolContext,
        mut opts: Options,
        prompt: &str,
    ) -> TaskResult {
        let cancelled = || TaskResult {
            text: "context canceled".into(),
            is_error: true,
            status: "cancelled".into(),
            ..Default::default()
        };
        if ctx.cancel.is_cancelled() {
            return cancelled();
        }
        opts.registry = self.registry();
        let rt = Runtime::new(opts);
        let root = rt.root();
        let h = match rt.spawn(&root, &self.name, None) {
            Ok(h) => h,
            Err(e) => {
                return TaskResult {
                    text: e,
                    is_error: true,
                    status: "error".into(),
                    ..Default::default()
                };
            }
        };
        let stop = tokio_util::sync::CancellationToken::new();
        let watcher = {
            let (rt, root, stop, cancel) =
                (rt.clone(), root.clone(), stop.clone(), ctx.cancel.clone());
            tokio::spawn(async move {
                tokio::select! {
                    _ = cancel.cancelled() => {
                        rt.close(&root, Some(CloseOptions { force: true, reason: "cancelled".into() })).await;
                    }
                    _ = stop.cancelled() => {}
                }
            })
        };
        rt.wake(&h, prompt);
        let r = rt.wait(&h, None).await;
        stop.cancel();
        let _ = watcher.await;
        if ctx.cancel.is_cancelled() {
            let mut c = cancelled();
            c.turns = r.turns;
            c.total_tokens = r.total_tokens;
            return c;
        }
        if r.status != "pending" {
            rt.close(&root, None).await;
        }
        r
    }
}
