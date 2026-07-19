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
    SpawnError,
    TaskResult,
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
    "SpawnError",
    "StartedAgent",
    "TaskResult",
    "agent",
    "agent_from_dir",
    "compose_soul",
    "memory_tool",
    "start_agent",
]
