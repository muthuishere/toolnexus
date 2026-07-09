"""toolnexus — provider-agnostic toolkit: dynamic MCP servers + agent skills.

Mirrors the JS reference implementation (``js/src/``); shared contract in
``SPEC.md``.
"""
from __future__ import annotations

from .a2a import (
    Agent,
    AgentConfig,
    AgentsConfig,
    agent,
    agent_tools,
    parse_agents_config,
)
from .adapters import to_anthropic, to_gemini, to_openai
from .client import (
    Client,
    ClientStyle,
    Conversation,
    WaitFor,
    ConversationStore,
    Hooks,
    InMemoryConversationStore,
    MetricEvent,
    OnMetric,
    RunCancelled,
    RunResult,
    RunTimeout,
    create_client,
)
from .builtin import (
    BuiltinsConfig,
    builtins_enabled,
    create_builtin_tools,
    select_builtins,
)
from .http import DEFAULT_TIMEOUT as HTTP_DEFAULT_TIMEOUT
from .http import http_tool
from .native import define_tool, tool
from .mcp_source import (
    DEFAULT_TIMEOUT,
    McpConfig,
    McpSource,
    ServerConfig,
    expand_env_headers,
    is_enabled,
    is_remote,
    load_mcp,
    parse_mcp_config,
)
from .mcp_serve import (
    MCPServeConfig,
    OnCall,
    build_mcp_server,
    exposed_mcp_tools,
)
from .serve import (
    A2AConfig,
    FileTaskStore,
    InMemoryTaskStore,
    OnTask,
    ServeHandle,
    TaskStore,
    build_agent_card,
    resolve_store,
    start_a2a_server,
)
from .skill import (
    SKILL_TOOL_DESCRIPTION,
    SKILLS_PROMPT_PREAMBLE,
    SkillInfo,
    SkillSource,
    load_skills,
)
from .toolkit import Toolkit, create_toolkit
from .types import (
    Answer,
    JSONSchema,
    McpStatus,
    Request,
    Tool,
    ToolContext,
    ToolResult,
    ToolSource,
    auth_required,
    pending,
    pending_of,
    sanitize,
)

__all__ = [
    # types
    "JSONSchema",
    "McpStatus",
    "Tool",
    "ToolContext",
    "ToolResult",
    "ToolSource",
    "sanitize",
    # suspension (§10)
    "Request",
    "Answer",
    "pending",
    "auth_required",
    "pending_of",
    # mcp
    "DEFAULT_TIMEOUT",
    "McpConfig",
    "McpSource",
    "ServerConfig",
    "expand_env_headers",
    "is_enabled",
    "is_remote",
    "load_mcp",
    "parse_mcp_config",
    # skill
    "SKILL_TOOL_DESCRIPTION",
    "SKILLS_PROMPT_PREAMBLE",
    "SkillInfo",
    "SkillSource",
    "load_skills",
    # builtin tools
    "BuiltinsConfig",
    "builtins_enabled",
    "create_builtin_tools",
    "select_builtins",
    # adapters
    "to_anthropic",
    "to_gemini",
    "to_openai",
    # toolkit
    "Toolkit",
    "create_toolkit",
    # native tools
    "tool",
    "define_tool",
    # http tools
    "http_tool",
    "HTTP_DEFAULT_TIMEOUT",
    # a2a agents (outbound)
    "Agent",
    "AgentConfig",
    "AgentsConfig",
    "agent",
    "agent_tools",
    "parse_agents_config",
    # a2a serve (inbound)
    "A2AConfig",
    "TaskStore",
    "InMemoryTaskStore",
    "FileTaskStore",
    "resolve_store",
    "build_agent_card",
    "start_a2a_server",
    "ServeHandle",
    "OnTask",
    # mcp serve (inbound)
    "MCPServeConfig",
    "OnCall",
    "build_mcp_server",
    "exposed_mcp_tools",
    # unified client
    "Client",
    "ClientStyle",
    "Conversation",
    "ConversationStore",
    "InMemoryConversationStore",
    "Hooks",
    "MetricEvent",
    "OnMetric",
    "RunCancelled",
    "RunResult",
    "RunTimeout",
    "WaitFor",
    "create_client",
]
