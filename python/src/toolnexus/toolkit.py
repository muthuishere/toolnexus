"""Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
single uniform list, with provider adapters and a single ``execute()`` router.

The MCP SDK is async, so the toolkit is async: build it with the
:func:`create_toolkit` factory and manage its lifetime with ``async with`` (or
call :meth:`Toolkit.close`). Mirrors the JS reference (``js/src/toolkit.ts``).
"""
from __future__ import annotations

import sys
from typing import Any, Optional

from .adapters import to_anthropic, to_gemini, to_openai
from .mcp_source import McpSource, load_mcp
from .skill import SkillSource, load_skills
from .types import McpStatus, Tool, ToolContext, ToolResult


class Toolkit:
    def __init__(
        self,
        mcp: Optional[McpSource],
        skill: Optional[SkillSource],
        extra_tools: list[Tool],
    ) -> None:
        self._mcp = mcp
        self._skill = skill
        self._by_name: dict[str, Tool] = {}
        all_tools: list[Tool] = []
        if mcp is not None:
            all_tools.extend(mcp.tools)
        if skill is not None:
            all_tools.append(skill.tool)
        all_tools.extend(extra_tools)
        for t in all_tools:
            if t.name in self._by_name:
                print(
                    f'[toolnexus] duplicate tool name "{t.name}" — keeping first',
                    file=sys.stderr,
                )
                continue
            self._by_name[t.name] = t

    @classmethod
    async def create(
        cls,
        mcp_config: Optional[str | dict[str, Any]] = None,
        skills_dir: Optional[str | list[str]] = None,
        extra_tools: Optional[list[Tool]] = None,
    ) -> "Toolkit":
        mcp = await load_mcp(mcp_config) if mcp_config is not None else None
        skill = load_skills(skills_dir) if skills_dir is not None else None
        return cls(mcp, skill, extra_tools or [])

    def register(self, *tools: Tool) -> "Toolkit":
        """Add native/http/custom tools at runtime. First-name-wins; warn on dup.

        Returns ``self`` so calls can be chained.
        """
        for t in tools:
            if t.name in self._by_name:
                print(
                    f'[toolnexus] duplicate tool name "{t.name}" — keeping first',
                    file=sys.stderr,
                )
                continue
            self._by_name[t.name] = t
        return self

    def tools(self) -> list[Tool]:
        return list(self._by_name.values())

    def get(self, name: str) -> Optional[Tool]:
        return self._by_name.get(name)

    async def execute(
        self,
        name: str,
        args: Optional[dict[str, Any]] = None,
        ctx: Optional[ToolContext] = None,
    ) -> ToolResult:
        tool = self._by_name.get(name)
        if tool is None:
            return ToolResult(output=f"Unknown tool: {name}", is_error=True)
        return await tool.execute(args or {}, ctx)

    def skills_prompt(self) -> str:
        """Markdown skill catalog for the system prompt."""
        return self._skill.prompt() if self._skill is not None else ""

    def mcp_status(self) -> dict[str, McpStatus]:
        return dict(self._mcp.status) if self._mcp is not None else {}

    def to_openai(self) -> list[dict[str, Any]]:
        return to_openai(self.tools())

    def to_anthropic(self) -> list[dict[str, Any]]:
        return to_anthropic(self.tools())

    def to_gemini(self) -> list[dict[str, Any]]:
        return to_gemini(self.tools())

    async def close(self) -> None:
        if self._mcp is not None:
            await self._mcp.close()

    async def __aenter__(self) -> "Toolkit":
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.close()


async def create_toolkit(
    mcp_config: Optional[str | dict[str, Any]] = None,
    skills_dir: Optional[str | list[str]] = None,
    extra_tools: Optional[list[Tool]] = None,
) -> Toolkit:
    """Async factory. The returned Toolkit is also an async context manager."""
    return await Toolkit.create(
        mcp_config=mcp_config, skills_dir=skills_dir, extra_tools=extra_tools
    )
