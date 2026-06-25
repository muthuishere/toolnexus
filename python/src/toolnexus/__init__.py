"""toolnexus — provider-agnostic toolkit: dynamic MCP servers + agent skills.

Mirrors the JS reference implementation (``js/src/``); shared contract in
``SPEC.md``.
"""
from __future__ import annotations

from .adapters import to_anthropic, to_gemini, to_openai
from .client import Client, ClientStyle, Hooks, RunResult, create_client
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
from .skill import (
    SKILL_TOOL_DESCRIPTION,
    SkillInfo,
    SkillSource,
    load_skills,
)
from .toolkit import Toolkit, create_toolkit
from .types import (
    JSONSchema,
    McpStatus,
    Tool,
    ToolContext,
    ToolResult,
    ToolSource,
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
    "SkillInfo",
    "SkillSource",
    "load_skills",
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
    # unified client
    "Client",
    "ClientStyle",
    "Hooks",
    "RunResult",
    "create_client",
]
