"""A2A (agent-to-agent) — OUTBOUND: call remote A2A agents.

Mirrors the JS reference (``js/src/a2a.ts``). A remote agent publishes an Agent
Card at ``/.well-known/agent-card.json`` describing its skills. Each advertised
skill becomes a uniform :class:`Tool` (``source="a2a"``) whose ``execute``
performs one JSON-RPC ``SendMessage`` (non-blocking) to get a Task id, then polls
``GetTask`` until the Task reaches a terminal state — mapping the Task's
artifact/message text to a :class:`ToolResult`.

This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST endpoint),
so a toolnexus agent interoperates with real A2A peers. See ../../SPEC.md §7A and
openspec/changes/add-a2a-agents. Reuses the ``${ENV}`` header expansion from
mcp_source; secrets live in the environment and are never logged.

Units note: to stay byte-identical with the JS reference (whose error strings and
``metadata.ms`` are in milliseconds), :class:`Agent` ``timeout`` / ``poll_every``
are milliseconds. A ``ctx.timeout`` (seconds, per the Python convention) still
overrides the poll budget when provided.
"""
from __future__ import annotations

import asyncio
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any, Optional

from .mcp_source import expand_env_headers
from .types import JSONSchema, Tool, ToolContext, ToolResult, sanitize

DEFAULT_TIMEOUT = 300_000  # ms (JS: DEFAULT_TIMEOUT)
DEFAULT_POLL_EVERY = 1_000  # ms (JS: DEFAULT_POLL_EVERY)

# Terminal A2A task states — polling stops once one of these is reached.
TERMINAL = frozenset({"completed", "failed", "canceled"})


# ---------------------------------------------------------------------------
# Wire types (the A2A subset we speak — verified against a2a-python). Parsed
# JSON arrives as plain dicts; these aliases document the shapes we read.
# ---------------------------------------------------------------------------

A2ATaskState = str  # "submitted" | "working" | "completed" | "failed" | "canceled"
A2APart = dict[str, Any]  # {"kind": str, "text"?: str, ...}
A2AMessage = dict[str, Any]  # {"role": str, "messageId"?: str, "parts"?: [A2APart], ...}
A2AArtifact = dict[str, Any]  # {"parts"?: [A2APart], ...}
A2ATaskStatus = dict[str, Any]  # {"state": A2ATaskState, "message"?: A2AMessage, ...}
A2ATask = dict[str, Any]  # {"id": str, "status": A2ATaskStatus, "artifacts"?, "history"?}
A2ASkill = dict[str, Any]  # {"id"?, "name"?, "description"?, "tags"?, "examples"?}
AgentCard = dict[str, Any]  # {"name", "url"?, "skills"?: [A2ASkill], ...}


# ---------------------------------------------------------------------------
# Agent descriptor + factory.
# ---------------------------------------------------------------------------


@dataclass
class Agent:
    """A remote A2A agent, pointing at its Agent Card URL.

    ``timeout`` / ``poll_every`` are milliseconds (JS parity). ``headers`` values
    support ``${ENV_VAR}`` expansion at call time and are never logged.
    """

    card: str
    headers: Optional[dict[str, str]] = None
    timeout: Optional[int] = None
    poll_every: Optional[int] = None


def agent(
    card: str,
    *,
    headers: Optional[dict[str, str]] = None,
    timeout: Optional[int] = None,
    poll_every: Optional[int] = None,
) -> Agent:
    """Build an :class:`Agent` descriptor pointing at a remote Agent Card URL."""
    return Agent(card=card, headers=headers, timeout=timeout, poll_every=poll_every)


# ---------------------------------------------------------------------------
# Config parsing — an `agents` block mirroring `mcpServers` (§2).
# ---------------------------------------------------------------------------

AgentConfig = dict[str, Any]  # {"card", "headers"?, "timeout"?, "pollEvery"?, "enabled"?/"disabled"?}
AgentsConfig = dict[str, AgentConfig]


def is_enabled(cfg: AgentConfig) -> bool:
    """MCP ``isEnabled`` precedence: ``disabled:true`` wins, then ``enabled:false``."""
    if cfg.get("disabled") is True:
        return False
    if cfg.get("enabled") is False:
        return False
    return True


def parse_agents_config(block: Optional[AgentsConfig]) -> list[Agent]:
    """Parse an ``agents`` block into :class:`Agent` descriptors, skipping disabled
    entries. The config key is just an identifier — a tool's name prefix comes from
    the fetched card's ``name``, not the key. Config keys are the shared cross-language
    wire names (``pollEvery``, camelCase).
    """
    if not block:
        return []
    out: list[Agent] = []
    for cfg in block.values():
        if not cfg or not isinstance(cfg.get("card"), str):
            continue
        if not is_enabled(cfg):
            continue
        out.append(
            agent(
                cfg["card"],
                headers=cfg.get("headers"),
                timeout=cfg.get("timeout"),
                poll_every=cfg.get("pollEvery"),
            )
        )
    return out


# ---------------------------------------------------------------------------
# JSON-RPC transport (single POST) + card fetch. Blocking urllib calls run in a
# thread via asyncio.to_thread so they cooperate with the async toolkit. Reuses
# the ${ENV} header expansion, timeout handling, and non-2xx → error mapping.
# ---------------------------------------------------------------------------


def _aborted(signal: Any) -> bool:
    """True if the ctx cancellation token is set. Accepts an ``asyncio.Event``
    (``.is_set()``), an AbortSignal-like object (``.aborted``), or a bare bool."""
    if signal is None:
        return False
    is_set = getattr(signal, "is_set", None)
    if callable(is_set):
        return bool(is_set())
    aborted = getattr(signal, "aborted", None)
    if aborted is not None:
        return bool(aborted)
    return bool(signal)


def _json_rpc_sync(
    endpoint: str,
    method: str,
    params: Any,
    headers: dict[str, str],
    timeout_s: float,
) -> Any:
    """POST one JSON-RPC 2.0 request and return ``result`` (raises on error/non-2xx)."""
    payload = {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": method, "params": params}
    body = json.dumps(payload).encode("utf-8")
    req_headers = {"Content-Type": "application/json", **headers}
    req = urllib.request.Request(endpoint, data=body, method="POST", headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            text = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {text}") from None
    parsed = _safe_parse(text)
    if isinstance(parsed, dict) and parsed.get("error"):
        err = parsed["error"]
        msg = err.get("message") if isinstance(err, dict) else None
        if not msg:
            code = err.get("code", "") if isinstance(err, dict) else ""
            msg = f"JSON-RPC error {code}".strip()
        raise RuntimeError(msg)
    return parsed.get("result") if isinstance(parsed, dict) else None


async def _json_rpc(
    endpoint: str,
    method: str,
    params: Any,
    headers: dict[str, str],
    timeout_s: float,
) -> Any:
    return await asyncio.to_thread(_json_rpc_sync, endpoint, method, params, headers, timeout_s)


def _fetch_card_sync(card_url: str, headers: dict[str, str], timeout_s: float) -> AgentCard:
    """Fetch and parse the Agent Card at its URL (GET)."""
    req = urllib.request.Request(card_url, method="GET", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            text = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {text}") from None
    return json.loads(text)


async def _fetch_card(card_url: str, headers: dict[str, str], timeout_s: float) -> AgentCard:
    return await asyncio.to_thread(_fetch_card_sync, card_url, headers, timeout_s)


def _safe_parse(text: str) -> Any:
    try:
        return json.loads(text)
    except Exception:
        return None


async def _sleep(ms: float, signal: Any) -> None:
    """Abortable sleep — returns early (without error) if ``signal`` fires."""
    deadline = time.monotonic() + ms / 1000.0
    while True:
        if _aborted(signal):
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        await asyncio.sleep(min(remaining, 0.02))


def _extract_output(task: A2ATask) -> str:
    """Concatenate a Task's text output: artifact text parts, else last agent message."""
    parts: list[str] = []
    for artifact in task.get("artifacts") or []:
        for part in artifact.get("parts") or []:
            if part.get("kind") == "text" and isinstance(part.get("text"), str):
                parts.append(part["text"])
    if parts:
        return "\n".join(parts)
    # Fallback: the last agent message in history.
    history = task.get("history") or []
    for msg in reversed(history):
        if msg.get("role") != "agent":
            continue
        text = "\n".join(
            p["text"]
            for p in (msg.get("parts") or [])
            if p.get("kind") == "text" and isinstance(p.get("text"), str)
        )
        if text:
            return text
    return ""


def _status_message_text(task: A2ATask) -> str:
    """Text of a Task's ``status.message`` (used for failed/canceled error output)."""
    msg = (task.get("status") or {}).get("message")
    if not msg:
        return ""
    return "\n".join(
        p["text"]
        for p in (msg.get("parts") or [])
        if p.get("kind") == "text" and isinstance(p.get("text"), str)
    )


# ---------------------------------------------------------------------------
# Skill → Tool.
# ---------------------------------------------------------------------------

_TASK_SCHEMA: JSONSchema = {
    "type": "object",
    "properties": {
        "task": {"type": "string", "description": "The task to send to the agent, in natural language."}
    },
    "required": ["task"],
    "additionalProperties": False,
}


def _skill_tool(agent_name: str, endpoint: str, skill: A2ASkill, ag: Agent) -> Tool:
    """Build one ``source="a2a"`` Tool for a single advertised skill of an agent."""
    skill_id = skill.get("id") or skill.get("name") or ""
    name = sanitize(agent_name) + "_" + sanitize(skill_id)
    timeout_ms = ag.timeout if ag.timeout is not None else DEFAULT_TIMEOUT
    poll_every_ms = ag.poll_every if ag.poll_every is not None else DEFAULT_POLL_EVERY

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        start = time.monotonic()
        # ctx.timeout is seconds (Python convention); the agent budget is ms.
        budget_ms = timeout_ms
        if ctx is not None and ctx.timeout is not None:
            budget_ms = ctx.timeout * 1000.0
        signal = ctx.signal if ctx is not None else None
        headers = expand_env_headers(ag.headers) or {}
        task_text = str((args or {}).get("task") or "")
        polls = 0
        task_id = ""
        state: A2ATaskState = "submitted"

        def meta() -> dict[str, Any]:
            return {
                "agent": agent_name,
                "taskId": task_id,
                "state": state,
                "polls": polls,
                "ms": int((time.monotonic() - start) * 1000),
            }

        try:
            # 1. SendMessage (non-blocking) → a submitted Task.
            task = await _json_rpc(
                endpoint,
                "SendMessage",
                {
                    "message": {
                        "role": "user",
                        "messageId": str(uuid.uuid4()),
                        "parts": [{"kind": "text", "text": task_text}],
                    },
                    "configuration": {"blocking": False},
                },
                headers,
                budget_ms / 1000.0,
            )
            task = task or {}
            task_id = task.get("id") or ""
            state = (task.get("status") or {}).get("state") or "submitted"

            # 2. Poll GetTask until terminal / timeout / cancel.
            while state not in TERMINAL:
                if _aborted(signal):
                    state = "canceled"
                    return ToolResult(output=f"A2A task {task_id} canceled", is_error=True, metadata=meta())
                if (time.monotonic() - start) * 1000 >= budget_ms:
                    return ToolResult(
                        output=f"A2A task {task_id} timed out after {int(budget_ms)}ms (state={state})",
                        is_error=True,
                        metadata=meta(),
                    )
                await _sleep(poll_every_ms, signal)
                # Cancelled during the wait → stop before another GetTask.
                if _aborted(signal):
                    state = "canceled"
                    return ToolResult(output=f"A2A task {task_id} canceled", is_error=True, metadata=meta())
                task = await _json_rpc(endpoint, "GetTask", {"id": task_id}, headers, budget_ms / 1000.0)
                task = task or {}
                polls += 1
                state = (task.get("status") or {}).get("state") or state

            # 3. Map the terminal Task → ToolResult.
            if state == "completed":
                return ToolResult(output=_extract_output(task), is_error=False, metadata=meta())
            detail = _status_message_text(task)
            return ToolResult(
                output=f"A2A task {task_id} {state}" + (f": {detail}" if detail else ""),
                is_error=True,
                metadata=meta(),
            )
        except Exception as e:  # noqa: BLE001 — surfaced as a tool error
            return ToolResult(output=str(e), is_error=True, metadata=meta())

    return Tool(
        name=name,
        description=skill.get("description") or skill.get("name") or skill_id,
        input_schema=_TASK_SCHEMA,
        source="a2a",  # type: ignore[arg-type]
        execute=execute,
    )


async def agent_tools(ag: Agent) -> list[Tool]:
    """Resolve an :class:`Agent` to its tools: fetch the card, read ``skills[]``, and
    produce one Tool per skill. The agent name prefix comes from the card's ``name``.
    """
    timeout_ms = ag.timeout if ag.timeout is not None else DEFAULT_TIMEOUT
    headers = expand_env_headers(ag.headers) or {}
    card = await _fetch_card(ag.card, headers, timeout_ms / 1000.0)
    agent_name = card.get("name") or "agent"
    # The card's `url` is the JSON-RPC endpoint; fall back to the card origin.
    endpoint = card.get("url")
    if not endpoint:
        parsed = urllib.parse.urlparse(ag.card)
        endpoint = f"{parsed.scheme}://{parsed.netloc}"
    return [_skill_tool(agent_name, endpoint, skill, ag) for skill in (card.get("skills") or [])]
