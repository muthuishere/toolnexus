//! The agent runtime (SPEC §7D–§7F): sub-agents as tools over the shipped
//! client loop. One axiom — an Agent is a Tool — plus six host verbs (`spawn`,
//! `post`, `wake`, `wait`, `interrupt`, `close`) over a handle tree, personas
//! from a directory (§7E) and context compaction (§7F). Port of
//! `golang/agents/`.
//!
//! - [`Runtime`]: the handle table, the three backpressure gates (bounded inbox,
//!   per-parent concurrency with FIFO slot transfer, a global turn gate around
//!   ONLY the provider call), hierarchical budgets, §10 suspension with
//!   nearest-interpreter escalation, durable pending and [`Runtime::resume`].
//! - [`Agent`] / [`Spec`]: the Level-1 surface; `team` wiring is the sub-agent
//!   graph, [`Agent::as_tool`] the bridge back into a classic toolkit.
//! - [`Loop`]: a live execution over a caller-built client and toolkit, with the
//!   completion gate and guardrails.
//! - [`compactor`]: a `before_llm` hook that keeps a transcript under budget.
//! - [`from_dir`] / [`memory_tool`] / [`start_agent`]: the persona home.

mod agent;
mod agent_loop;
mod clock;
mod compaction;
mod home;
mod runtime;

pub use agent::{Agent, AsToolOptionsFn, Completion, Guardrail, Spec, VerifyFn, harness, new};
pub use agent_loop::{Loop, Outcome, RunOpts, all_todos_done, loop_unsupported};
pub use clock::{Clock, SystemClock};
pub use compaction::{
    CompactInfo, CompactorOptions, CountTokensFn, OnCompactFn, SummarizeFn, compactor,
    estimate_tokens,
};
pub use home::{
    BOOTSTRAP_ORDER, ComposeSoulOptions, DREAM_PROMPT, DreamOptions, DreamerOptions,
    FromDirOptions, HEARTBEAT_OK, HEARTBEAT_PROMPT, MEMORY_INDEX_CAP_PRESET,
    MEMORY_NOTES_DESCRIPTION, MemoryIndexCap, MemoryToolOptions, OnBeatFn, StartOptions,
    StartedAgent, apply_index_cap, compose_soul, compose_soul_with, dreamer, from_dir,
    memory_index_notice, memory_tool, memory_tool_with, start_agent,
};
pub use runtime::{
    Budget, CloseOptions, Def, Handle, HandleView, InboxItem, LlmOptions, OnCloseFn, OnSpawnFn,
    Options, PostResult, REASON_BUDGET, REASON_CLOSED, REASON_ERROR, REASON_INTERRUPTED, Runtime,
    State, TaskResult, UNLIMITED,
};

// ---- the §7D status vocabulary ---------------------------------------------
//
// SEVEN values, and a DIFFERENT SET from the §8 client's three. "timeout" is
// exclusively this set's, and exclusively `wait(handle, timeout)` — a WAIT
// deadline, where the child keeps running.

/// The turn produced a final answer.
pub const TASK_STATUS_DONE: &str = "done";
/// Parked durably on an unresolved §10 Request.
pub const TASK_STATUS_PENDING: &str = "pending";
/// A limit stopped it; `TaskResult.limit` names which.
pub const TASK_STATUS_INCOMPLETE: &str = "incomplete";
/// The in-flight turn was aborted; the inbox is intact.
pub const TASK_STATUS_INTERRUPTED: &str = "interrupted";
/// The handle was closed.
pub const TASK_STATUS_CLOSED: &str = "closed";
/// A WAIT deadline expired while the child kept running.
pub const TASK_STATUS_TIMEOUT: &str = "timeout";
/// The turn failed; only the root may throw to the host.
pub const TASK_STATUS_ERROR: &str = "error";

// The CLOSED `TaskResult.limit` vocabulary: the Budget field spelled as SPEC
// spells it, plus the two stops that are not Budget fields.
pub const LIMIT_MAX_TURNS: &str = "maxTurns";
pub const LIMIT_MAX_TOKENS: &str = "maxTokens";
pub const LIMIT_MAX_TOOL_CALLS: &str = "maxToolCalls";
pub const LIMIT_MAX_WALL_MS: &str = "maxWallMs";
pub const LIMIT_MAX_CHILDREN: &str = "maxChildren";
pub const LIMIT_MAX_CONCURRENT: &str = "maxConcurrent";
pub const LIMIT_MAX_DEPTH: &str = "maxDepth";
/// The completion gate refused the work.
pub const LIMIT_COMPLETION: &str = "completion";
/// The §8 whole-run deadline, or a wait deadline.
pub const LIMIT_TIMEOUT: &str = "timeout";
