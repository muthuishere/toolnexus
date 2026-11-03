//! Loop — a live execution of an Agent over a caller-built client and toolkit,
//! and the completion gate that stops it claiming `done` too early. Port of
//! `golang/agents/loop.go`.
//!
//! Spec answers "MAY it?", Run "with WHAT?", Loop "DID it?"; none of them
//! answers "is it RIGHT?" — that is a tool, skill or agent.

use std::future::Future;
use std::sync::{Arc, Mutex};

use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use super::agent::{Agent, Completion, Spec};
use crate::client::{
    BeforeToolEvent, Client, ClientError, ClientOptions, Hooks, RunResult, ToolOverride,
    create_client,
};
use crate::tool::ToolResult;
use crate::toolkit::Toolkit;

/// Compile a Spec's guardrails into one `before_tool` with FIRST-DENY-WINS,
/// composed ahead of any `before_tool` the Spec already set.
pub(crate) fn guarded_hooks(sp: &Spec) -> Option<Hooks> {
    if sp.guardrails.is_empty() {
        return sp.hooks.clone();
    }
    let mut merged = sp.hooks.clone().unwrap_or_default();
    let prior = merged.before_tool.clone();
    let rails = sp.guardrails.clone();
    merged.before_tool = Some(Arc::new(move |ev: BeforeToolEvent| {
        for g in &rails {
            let v = g(&ev);
            if !v.is_empty() && v != "allow" {
                let out = ToolOverride {
                    result: Some(ToolResult::error(format!("denied: {v}"))),
                    ..Default::default()
                };
                return Box::pin(async move { Ok(Some(out)) });
            }
        }
        match &prior {
            Some(p) => p(ev),
            None => Box::pin(async { Ok(None) }),
        }
    }));
    Some(merged)
}

/// What varies PER CALL.
#[derive(Debug, Clone, Default)]
pub struct RunOpts {
    /// Overrides the agent's model for this call only. `""` ⇒ the agent's.
    pub model: String,
}

/// What a `Loop::run` reports. `status` reuses the shipped vocabulary.
#[derive(Debug, Clone, Default)]
pub struct Outcome {
    pub text: String,
    /// `done` | `incomplete` | `pending` | `error`.
    pub status: String,
    /// Named whenever `status != "done"` — never a silent stop.
    pub stopped_by: String,
    pub attempts: usize,
    pub turns: usize,
    pub result: RunResult,
}

/// A live execution of an Agent. Its only verb is `run`.
pub struct Loop {
    agent: Agent,
    opts: ClientOptions,
    toolkit: Toolkit,
    history: Arc<Mutex<Vec<Value>>>,
    turns: usize,
    status: String,
}

impl Agent {
    /// Open a live execution. Takes CLIENT OPTIONS (not a client) so
    /// `RunOpts.model` can override the model per call.
    pub fn loop_(&self, opts: ClientOptions, tk: Toolkit) -> Loop {
        Loop {
            agent: self.clone(),
            opts,
            toolkit: tk,
            history: Arc::new(Mutex::new(Vec::new())),
            turns: 0,
            status: "idle".into(),
        }
    }
}

/// The Spec fields this agent declares that the Loop door cannot honour, in a
/// stable order and the CANONICAL vocabulary (`tools`, `team`, `waitFor`,
/// `onMetric`). Empty ⇒ the Loop carries the whole Spec. Drive such an agent
/// through [`super::Runtime`] instead.
pub fn loop_unsupported(sp: &Spec) -> Vec<String> {
    let mut out = Vec::new();
    if !sp.tools.is_empty() {
        out.push("tools".to_string());
    }
    if !sp.team.is_empty() {
        out.push("team".into());
    }
    if sp.wait_for.is_some() {
        out.push("waitFor".into());
    }
    if sp.on_metric.is_some() {
        out.push("onMetric".into());
    }
    out
}

impl Loop {
    /// [`loop_unsupported`] for this Loop's agent.
    pub fn unsupported(&self) -> Vec<String> {
        loop_unsupported(&self.agent.spec)
    }

    /// Observed, never set by the caller.
    pub fn status(&self) -> &str {
        &self.status
    }

    /// Model round trips this loop has spent.
    pub fn turns(&self) -> usize {
        self.turns
    }

    /// The client for this call, HONOURING THE SPEC: the soul is the default
    /// system prompt (a caller-supplied one wins), guardrails + hooks become
    /// the same first-deny-wins `before_tool`, the Spec model and
    /// `budget.max_turns` are defaults, and `RunOpts.model` rides
    /// `request_params`.
    fn client_for(&self, o: &RunOpts) -> Client {
        let mut opts = self.opts.clone();
        let sp = &self.agent.spec;
        if opts.system_prompt.is_empty() && !sp.soul.is_empty() {
            opts.system_prompt = sp.soul.clone();
        }
        if let Some(h) = guarded_hooks(sp) {
            opts.hooks = h;
        }
        if (opts.model.is_empty() || opts.model == "inherit")
            && !sp.model.is_empty()
            && sp.model != "inherit"
        {
            opts.model = sp.model.clone();
        }
        if opts.max_turns == 0
            && let Some(b) = &sp.budget
            && b.max_turns > 0
        {
            opts.max_turns = b.max_turns;
        }
        if !o.model.is_empty() {
            let mut rp: Map<String, Value> = opts.request_params.clone();
            rp.insert("model".into(), json!(o.model));
            opts.request_params = rp;
        }
        create_client(opts)
    }

    /// Execute one request. With a Completion, the gate runs exactly where the
    /// loop would otherwise have returned `done`.
    pub async fn run(
        &mut self,
        cancel: CancellationToken,
        prompt: &str,
        opts: RunOpts,
    ) -> Result<Outcome, ClientError> {
        let client = self.client_for(&opts);
        self.status = "running".into();
        let attempts = Arc::new(Mutex::new(0usize));
        let turns = Arc::new(Mutex::new(self.turns));
        let ask = {
            let (attempts, turns, history) =
                (attempts.clone(), turns.clone(), self.history.clone());
            let tk = self.toolkit.clone();
            move |p: String| {
                let (client, tk, cancel) = (client.clone(), tk.clone(), cancel.clone());
                let (attempts, turns, history) = (attempts.clone(), turns.clone(), history.clone());
                Box::pin(async move {
                    *attempts.lock().unwrap_or_else(|e| e.into_inner()) += 1;
                    let h = history.lock().unwrap_or_else(|e| e.into_inner()).clone();
                    let out = client.run_ctx(cancel, p, &tk, h).await?;
                    *turns.lock().unwrap_or_else(|e| e.into_inner()) += out.turns;
                    *history.lock().unwrap_or_else(|e| e.into_inner()) = out.messages.clone();
                    Ok(out)
                }) as crate::client::BoxFuture<Result<RunResult, ClientError>>
            }
        };
        let r = run_gated(ask, prompt, self.agent.spec.completion.as_ref()).await;
        let attempts = *attempts.lock().unwrap_or_else(|e| e.into_inner());
        self.turns = *turns.lock().unwrap_or_else(|e| e.into_inner());
        let r = match r {
            Ok(r) => r,
            Err(e) => {
                self.status = "error".into();
                return Err(e);
            }
        };
        if !r.status.is_empty() && r.status != "done" {
            self.status = r.status.clone();
            let stopped_by = if r.limit == "completion" {
                r.text.clone()
            } else {
                format!("run reported {}", r.status)
            };
            return Ok(Outcome {
                text: r.text.clone(),
                status: r.status.clone(),
                stopped_by,
                attempts,
                turns: self.turns,
                result: r,
            });
        }
        self.status = "idle".into();
        Ok(Outcome {
            text: r.text.clone(),
            status: "done".into(),
            stopped_by: String::new(),
            attempts,
            turns: self.turns,
            result: r,
        })
    }
}

/// The built-in completion verifier: reads the shipped `todowrite` builtin's
/// result metadata and requires every item checked. No plan ⇒ pass.
pub fn all_todos_done(r: &RunResult) -> (bool, String) {
    for c in r.tool_calls.iter().rev() {
        if c.name != "todowrite" {
            continue;
        }
        let Some(raw) = c
            .metadata
            .as_ref()
            .and_then(|m| m.get("todos"))
            .and_then(Value::as_array)
        else {
            return (true, String::new());
        };
        let open: Vec<String> = raw
            .iter()
            .filter(|it| {
                !it.get("completed")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
            })
            .map(|it| {
                it.get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string()
            })
            .collect();
        if !open.is_empty() {
            return (
                false,
                format!("{} item(s) still open: {}", open.len(), open.join("; ")),
            );
        }
        return (true, String::new());
    }
    (true, String::new())
}

/// The completion gate around one or more attempts — SHARED by the Loop and the
/// runtime turn, so a delegated child gets the same guarantee. A run that is
/// `pending` or otherwise non-done carries its own reason and is never
/// re-judged.
pub(crate) async fn run_gated<F, Fut>(
    ask: F,
    prompt: &str,
    comp: Option<&Completion>,
) -> Result<RunResult, ClientError>
where
    F: Fn(String) -> Fut,
    Fut: Future<Output = Result<RunResult, ClientError>>,
{
    let Some(comp) = comp else {
        return ask(prompt.to_string()).await;
    };
    if comp.max_attempts < 1 {
        return Err(ClientError::Unsupported(
            "toolnexus: Completion.MaxAttempts must be >= 1".into(),
        ));
    }
    let Some(verify) = comp.verify.clone() else {
        return Err(ClientError::Unsupported(
            "toolnexus: Completion.Verify is required".into(),
        ));
    };
    let mut acc = Vec::new();
    let mut acc_files = Vec::new();
    let mut last = RunResult::default();
    let mut reason = String::new();
    for n in 1..=comp.max_attempts {
        let p = if n > 1 {
            format!("Your work did not verify: {reason}. Fix it and finish.")
        } else {
            prompt.to_string()
        };
        let mut r = ask(p).await?;
        acc.extend(r.tool_calls.iter().cloned());
        r.tool_calls = acc.clone();
        acc_files.extend(r.files.iter().cloned());
        r.files = acc_files.clone();
        last = r.clone();
        if !r.status.is_empty() && r.status != "done" {
            if !reason.is_empty() && r.status != "pending" {
                r.text = format!(
                    "{} [while verifying: attempt {n} last failed: {reason}]",
                    r.text
                );
            }
            return Ok(r);
        }
        let (ok, why) = verify(&r);
        if ok {
            return Ok(r);
        }
        reason = why;
    }
    last.status = "incomplete".into();
    last.limit = "completion".into();
    last.text = format!("completion.verify failed {}×: {reason}", comp.max_attempts);
    Ok(last)
}
