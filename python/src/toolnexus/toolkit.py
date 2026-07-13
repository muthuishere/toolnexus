"""Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
single uniform list, with provider adapters and a single ``execute()`` router.

The MCP SDK is async, so the toolkit is async: build it with the
:func:`create_toolkit` factory and manage its lifetime with ``async with`` (or
call :meth:`Toolkit.close`). Mirrors the JS reference (``js/src/toolkit.ts``).
"""
from __future__ import annotations

import asyncio
import inspect
import sys
from typing import TYPE_CHECKING, Any, Optional

from .a2a import Agent, agent_tools, parse_agents_config
from .adapters import to_anthropic, to_gemini, to_openai
from .builtin import BuiltinsConfig, select_builtins
from .mcp_serve import (
    MCPServeConfig,
    OnCall,
)
from .mcp_source import McpSource, load_mcp_with_context
from .serve import A2AConfig, OnTask, ServeHandle, start_a2a_server
from .skill import SkillDef, SkillProvider, SkillSource, load_skills
from .types import McpStatus, Tool, ToolContext, ToolResult

if TYPE_CHECKING:  # typing-only, avoid a runtime import cycle with client.py
    from .client import Client


class Toolkit:
    def __init__(
        self,
        mcp: Optional[McpSource],
        skill: Optional[SkillSource],
        builtins: list[Tool],
        agents: list[Tool],
        extra_tools: list[Tool],
        a2a_config: Optional[A2AConfig] = None,
        mcp_server_config: Optional[MCPServeConfig] = None,
        disable_tools: Optional[list[str]] = None,
    ) -> None:
        self._mcp = mcp
        self._skill = skill
        # A2A profile read from a top-level `a2a` config block (serve() falls back to this).
        self._a2a_config = a2a_config
        # MCP serve profile read from a top-level `mcpServer` (singular) config block
        # — distinct from the client-side `mcpServers`. serve() prefers its inline
        # `mcp` over this.
        self._mcp_server_config = mcp_server_config
        self._by_name: dict[str, Tool] = {}
        # Builtins are the lowest-precedence source: a host extra_tools entry with
        # the same name shadows a builtin (SPEC §4). Drop shadowed builtins up
        # front, then apply the normal first-wins dedupe for MCP/skill/agents/extras.
        extra_names = {t.name for t in extra_tools}
        active_builtins = [b for b in builtins if b.name not in extra_names]
        all_tools: list[Tool] = []
        if mcp is not None:
            all_tools.extend(mcp.tools)
        if skill is not None:
            all_tools.append(skill.tool)
        all_tools.extend(active_builtins)
        all_tools.extend(agents)
        all_tools.extend(extra_tools)
        # DisableTools drops tools by their FINAL exposed name across every source
        # (MCP `server_tool`, builtins, native, a2a), applied at aggregation. Composes
        # with the per-server/per-skill map filters. Mirrors Go's DisableTools.
        disabled = set(disable_tools or ())
        for t in all_tools:
            if t.name in disabled:
                continue
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
        skills: Optional[list[SkillDef]] = None,
        skill_provider: Optional[SkillProvider] = None,
        skills_filter: Optional[dict[str, bool]] = None,
        skill_sample_limit: int = 0,
        extra_tools: Optional[list[Tool]] = None,
        builtins: Optional[BuiltinsConfig] = None,
        agents: Optional[list[Agent]] = None,
        wait_for: Optional[Any] = None,
        disable_tools: Optional[list[str]] = None,
        disable_skills: Optional[list[str]] = None,
        cancel: Optional[Any] = None,
    ) -> "Toolkit":
        # ``wait_for`` (§10): when set, connected MCP servers may elicit input from the
        # human mid-``tools/call``, bridged onto this resolver (form→kind:"input",
        # URL→kind:"authorization"). Typically the same function passed to the client.
        # Omit ⇒ MCP elicitation is not advertised.
        # ``cancel`` (§2 Gap 3): an optional asyncio.Event; when set it aborts the whole
        # MCP load (the Python analogue of a cancelled parent context).
        mcp = (
            await load_mcp_with_context(mcp_config, wait_for=wait_for, cancel=cancel)
            if mcp_config is not None
            else None
        )
        # Skills come from directories, in-memory data, and/or a lazy provider
        # (§3, S1). The provider is resolved here (create() is async) and merged
        # with the data list; a provider failure is isolated so other sources
        # still load.
        skill = None
        if skills_dir is not None or skills is not None or skill_provider is not None:
            provider_defs: list[SkillDef] = []
            if skill_provider is not None:
                try:
                    result = skill_provider()
                    if inspect.isawaitable(result):
                        result = await result
                    provider_defs = list(result)
                except Exception as e:  # noqa: BLE001 — provider failure is isolated
                    print(f"[toolnexus] skill provider failed: {e}", file=sys.stderr)
                    provider_defs = []
            data_defs = list(skills or []) + provider_defs
            # DisableSkills is sugar over a drop-list: fold each name into the filter as
            # False (without overriding an explicit skills_filter entry). Mirrors Go.
            eff_filter = skills_filter
            if disable_skills:
                merged = dict(skills_filter or {})
                for n in disable_skills:
                    merged.setdefault(n, False)
                eff_filter = merged
            skill = load_skills(
                skills_dir,
                skills=data_defs or None,
                filter=eff_filter,
                sample_limit=skill_sample_limit,
            )
        # The toggle comes from the `builtins` option, or a top-level `builtins`
        # key on a parsed config dict — same precedence as MCP's is_enabled.
        builtins_cfg = builtins
        if (
            builtins_cfg is None
            and isinstance(mcp_config, dict)
            and "builtins" in mcp_config
        ):
            builtins_cfg = mcp_config["builtins"]
        builtin_tools = select_builtins(builtins_cfg)

        # Remote A2A agents come from the `agents=[...]` option plus a top-level
        # `agents` block on a parsed config dict (mirrors mcpServers). Each is
        # resolved to its skill tools; a failing agent never breaks the toolkit.
        agent_descriptors: list[Agent] = list(agents or [])
        if isinstance(mcp_config, dict) and "agents" in mcp_config:
            agent_descriptors.extend(parse_agents_config(mcp_config["agents"]))

        async def _resolve(ag: Agent) -> list[Tool]:
            try:
                return await agent_tools(ag)
            except Exception as e:  # noqa: BLE001 — one bad agent never breaks the toolkit
                print(
                    f'[toolnexus] A2A agent "{ag.card}" failed: {e}',
                    file=sys.stderr,
                )
                return []

        agent_tool_lists = await asyncio.gather(*(_resolve(a) for a in agent_descriptors))
        agent_tools_flat = [t for lst in agent_tool_lists for t in lst]

        # A2A inbound profile: a top-level `a2a` block on a parsed config dict
        # (mirrors builtins/agents). serve() prefers its inline `a2a` over this.
        a2a_config: Optional[A2AConfig] = None
        if isinstance(mcp_config, dict) and "a2a" in mcp_config:
            a2a_config = mcp_config["a2a"]

        # MCP inbound profile: a top-level `mcpServer` (singular) block — distinct
        # from the client-side `mcpServers` block. serve() prefers its inline `mcp`
        # over this.
        mcp_server_config: Optional[MCPServeConfig] = None
        if isinstance(mcp_config, dict) and "mcpServer" in mcp_config:
            mcp_server_config = mcp_config["mcpServer"]

        return cls(
            mcp,
            skill,
            builtin_tools,
            agent_tools_flat,
            extra_tools or [],
            a2a_config,
            mcp_server_config,
            disable_tools=disable_tools,
        )

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

    async def add_agent(
        self,
        agent_or_card_url: "Agent | str",
        *,
        headers: Optional[dict[str, str]] = None,
        timeout: Optional[int] = None,
        poll_every: Optional[int] = None,
    ) -> "Toolkit":
        """Fetch a remote A2A agent's card at runtime and register its skills as
        tools. Accepts an :class:`Agent` descriptor or a bare card URL.
        First-name-wins dedupe. Returns ``self`` so calls can be chained.
        """
        if isinstance(agent_or_card_url, str):
            ag = Agent(
                card=agent_or_card_url,
                headers=headers,
                timeout=timeout,
                poll_every=poll_every,
            )
        else:
            ag = agent_or_card_url
        tools = await agent_tools(ag)
        return self.register(*tools)

    async def serve(
        self,
        addr: str,
        *,
        client: Optional["Client"] = None,
        a2a: Optional[A2AConfig] = None,
        on_task: Optional[OnTask] = None,
        mcp: Optional[MCPServeConfig] = None,
        on_call: Optional[OnCall] = None,
    ) -> ServeHandle:
        """Serve this toolkit as an agent over HTTP. When the ``a2a`` profile is
        present (inline, or a top-level ``a2a`` config block the toolkit was built
        from), it mounts the A2A Agent Card (``/.well-known/agent-card.json``, built
        from skills) and a JSON-RPC endpoint (``SendMessage`` + ``GetTask``) fulfilled
        by the client. A message's A2A ``contextId`` keys the conversation via
        ``client.ask``, so a peer's turns are remembered through the client's
        ConversationStore. When the ``mcp`` profile is present (inline, or a top-level
        ``mcpServer`` config block), a streamable-HTTP MCP server exposing the
        toolkit's unified tools is co-mounted at ``POST /mcp``. When both are absent,
        no routes are mounted. Returns a stoppable handle.
        """
        cfg = a2a if a2a is not None else self._a2a_config
        mcp_cfg = mcp if mcp is not None else self._mcp_server_config
        skills = list(self._skill.skills.values()) if self._skill is not None else []

        async def run_task(text: str, context_id: Optional[str] = None) -> Any:
            # contextId keys the conversation via client.ask so a peer's turns are
            # remembered through the client's ConversationStore; absent ⇒ stateless run.
            if context_id:
                return await client.ask(text, self, id=context_id)  # type: ignore[union-attr]
            return await client.run(text, self)  # type: ignore[union-attr]

        return await start_a2a_server(
            addr=addr,
            a2a=cfg,
            skills=skills,
            run_task=run_task,
            on_task=on_task,
            loop=asyncio.get_running_loop(),
            mcp=mcp_cfg,
            mcp_tools=self.tools() if mcp_cfg else None,
            on_call=on_call,
        )

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
    skills: Optional[list[SkillDef]] = None,
    skill_provider: Optional[SkillProvider] = None,
    skills_filter: Optional[dict[str, bool]] = None,
    skill_sample_limit: int = 0,
    extra_tools: Optional[list[Tool]] = None,
    builtins: Optional[BuiltinsConfig] = None,
    agents: Optional[list[Agent]] = None,
    wait_for: Optional[Any] = None,
    disable_tools: Optional[list[str]] = None,
    disable_skills: Optional[list[str]] = None,
    cancel: Optional[Any] = None,
) -> Toolkit:
    """Async factory. The returned Toolkit is also an async context manager.

    Built-in tools (§4A) are on by default; pass ``builtins=False`` /
    ``{"disabled": True}`` / ``{"enabled": False}`` to turn the whole source off.
    Remote A2A agents (§7A) are supplied via ``agents=[agent(...), ...]`` and/or a
    top-level ``agents`` block on a parsed config dict.

    ``disable_tools`` drops tools by their final exposed name across every source;
    ``disable_skills`` drops skills by name from the ``skill`` catalog (both compose
    with the per-server/per-skill map filters). ``cancel`` is an optional
    :class:`asyncio.Event` propagated into the MCP load (§2 Gap 3).
    """
    return await Toolkit.create(
        mcp_config=mcp_config,
        skills_dir=skills_dir,
        skills=skills,
        skill_provider=skill_provider,
        skills_filter=skills_filter,
        skill_sample_limit=skill_sample_limit,
        extra_tools=extra_tools,
        builtins=builtins,
        agents=agents,
        wait_for=wait_for,
        disable_tools=disable_tools,
        disable_skills=disable_skills,
        cancel=cancel,
    )
