"""toolnexus.agents — sub-agents & the agent runtime (SPEC.md §7D).

An Agent is a Tool: (system prompt × a filtered toolkit view × the §8 client
loop), invocable by a delegating model through the ``task`` builtin or bridged
into the classic API via ``Agent.as_tool()``. The runtime substrate exposes the
six host verbs (spawn/post/wake/wait/interrupt/close) over a Handle state
machine, with hierarchical budgets, backpressure gates, §10 suspension
escalation, and durable resume with task-key reattachment.

Import the Level-1 surface from here (the package namespace disambiguates from
the outbound-A2A ``toolnexus.agent``):

    from toolnexus.agents import agent, Budget
"""
from .compaction import compactor, estimate_tokens
from .runtime import (
    AgentDef,
    AgentRuntime,
    AsyncioClock,
    Budget,
    Clock,
    Handle,
    HandleView,
    InboxItem,
    PostResult,
    LIMIT_COMPLETION,
    LIMIT_MAX_CHILDREN,
    LIMIT_MAX_CONCURRENT,
    LIMIT_MAX_DEPTH,
    LIMIT_MAX_TOKENS,
    LIMIT_MAX_TOOL_CALLS,
    LIMIT_MAX_TURNS,
    LIMIT_MAX_WALL_MS,
    LIMIT_TIMEOUT,
    LIMITS,
    SpawnError,
    TASK_STATUS_CLOSED,
    TASK_STATUS_DONE,
    TASK_STATUS_ERROR,
    TASK_STATUS_INCOMPLETE,
    TASK_STATUS_INTERRUPTED,
    TASK_STATUS_PENDING,
    TASK_STATUS_TIMEOUT,
    TASK_STATUSES,
    TaskResult,
)
from .loop import (
    Completion,
    Loop,
    Outcome,
    Verdict,
    all_todos_done,
    guarded_hooks,
    harness,
    loop_unsupported,
    run_gated,
)
from .surface import (
    BOOTSTRAP_ORDER,
    HEARTBEAT_OK,
    Agent,
    StartedAgent,
    agent,
    agent_from_dir,
    compose_soul,
    memory_tool,
    start_agent,
)

__all__ = [
    "Completion",
    "Loop",
    "Outcome",
    "Verdict",
    "all_todos_done",
    "guarded_hooks",
    "harness",
    "loop_unsupported",
    "run_gated",
    "Agent",
    "AgentDef",
    "AgentRuntime",
    "AsyncioClock",
    "BOOTSTRAP_ORDER",
    "Budget",
    "Clock",
    "HEARTBEAT_OK",
    "Handle",
    "HandleView",
    "InboxItem",
    "PostResult",
    "LIMITS",
    "LIMIT_COMPLETION",
    "LIMIT_MAX_CHILDREN",
    "LIMIT_MAX_CONCURRENT",
    "LIMIT_MAX_DEPTH",
    "LIMIT_MAX_TOKENS",
    "LIMIT_MAX_TOOL_CALLS",
    "LIMIT_MAX_TURNS",
    "LIMIT_MAX_WALL_MS",
    "LIMIT_TIMEOUT",
    "SpawnError",
    "TASK_STATUSES",
    "TASK_STATUS_CLOSED",
    "TASK_STATUS_DONE",
    "TASK_STATUS_ERROR",
    "TASK_STATUS_INCOMPLETE",
    "TASK_STATUS_INTERRUPTED",
    "TASK_STATUS_PENDING",
    "TASK_STATUS_TIMEOUT",
    "StartedAgent",
    "TaskResult",
    "agent",
    "agent_from_dir",
    "compactor",
    "compose_soul",
    "estimate_tokens",
    "memory_tool",
    "start_agent",
]
