"""Unified LLM client — the host loop.

Give it a plain base URL + a style ("openai" | "anthropic") and it runs the whole
tool-calling agent loop against a :class:`Toolkit`. See ../../SPEC.md §8. Mirrors
the JS reference (``js/src/client.ts``).

Uses only the standard library (``urllib`` + ``json``); the blocking POST runs in
a thread via ``asyncio.to_thread``. The API key is read from the ``api_key`` arg
or the environment and is NEVER printed.
"""
from __future__ import annotations

import asyncio
import inspect
import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Literal, Mapping, Optional

from .toolkit import Toolkit
from .types import ToolResult

ClientStyle = Literal["openai", "anthropic"]

# Lifecycle hooks (see ../../SPEC.md §8 "Hooks"). ``hooks`` is any object/mapping
# carrying optional callables under snake_case keys — each async-OR-sync:
#   before_llm({"messages", "tools", "model", "turn"})
#       -> optionally {"messages"?, "tools"?} to replace them.
#   after_llm({"response", "model", "turn"})  -> observe (response carries usage).
#   before_tool({"name", "args", "id", "turn"})
#       -> {"result": ToolResult} to SHORT-CIRCUIT, or {"args": {...}} to rewrite.
#   after_tool({"name", "args", "result", "id", "turn"})
#       -> {"result": ToolResult} to replace the result.
Hooks = Any


def _get_hook(hooks: Any, name: str) -> Optional[Any]:
    """Pull a hook callable off a dict/mapping OR an attribute-bearing object."""
    if hooks is None:
        return None
    if isinstance(hooks, Mapping):
        fn = hooks.get(name)
    else:
        fn = getattr(hooks, name, None)
    return fn if callable(fn) else None


async def _call_hook(fn: Any, ev: dict[str, Any]) -> Any:
    """Invoke a hook tolerant of sync OR async callables."""
    r = fn(ev)
    if inspect.isawaitable(r):
        r = await r
    return r


def _empty_usage() -> dict[str, int]:
    return {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}


@dataclass
class RunResult:
    text: str
    messages: list[dict[str, Any]]
    # Every tool call made, each carrying name, args, output, is_error, metadata.
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    # Total number of tool calls (= len(tool_calls)).
    tool_call_count: int = 0
    # Number of LLM round trips.
    turns: int = 0
    # Aggregated token usage across all turns.
    usage: dict[str, int] = field(default_factory=_empty_usage)
    # The model used.
    model: str = ""


def _add_usage(acc: dict[str, int], raw: Any, style: ClientStyle) -> None:
    """Accumulate one response's token usage into ``acc`` (mirrors JS ``addUsage``)."""
    if not raw:
        return
    if style == "anthropic":
        prompt = raw.get("input_tokens") or 0
        completion = raw.get("output_tokens") or 0
        acc["prompt_tokens"] += prompt
        acc["completion_tokens"] += completion
        acc["total_tokens"] += prompt + completion
    else:
        prompt = raw.get("prompt_tokens") or 0
        completion = raw.get("completion_tokens") or 0
        acc["prompt_tokens"] += prompt
        acc["completion_tokens"] += completion
        acc["total_tokens"] += raw.get("total_tokens") or (prompt + completion)


def _resolve_key(api_key: Optional[str], style: ClientStyle) -> str:
    if api_key:
        return api_key
    env = os.environ
    key = (
        env.get("OPENROUTER_API_KEY")
        or (env.get("ANTHROPIC_API_KEY") if style == "anthropic" else env.get("OPENAI_API_KEY"))
        or env.get("OPENAI_API_KEY")
        or env.get("ANTHROPIC_API_KEY")
    )
    if not key:
        raise RuntimeError(
            "No API key: set api_key or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY"
        )
    return key


def _post(url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"LLM {e.code}: {text}") from None


def _safe_json(s: Any) -> dict[str, Any]:
    if isinstance(s, dict):
        return s
    try:
        return json.loads(s or "{}")
    except Exception:
        return {}


class Client:
    def __init__(
        self,
        *,
        base_url: str,
        style: ClientStyle,
        model: str,
        api_key: Optional[str] = None,
        headers: Optional[dict[str, str]] = None,
        system_prompt: Optional[str] = None,
        max_turns: int = 10,
        hooks: Hooks = None,
    ) -> None:
        self.base_url = base_url
        self.style = style
        self.model = model
        self.api_key = api_key
        self.headers = headers or {}
        self.system_prompt = system_prompt
        self.max_turns = max_turns
        self.hooks = hooks

    def _system(self, toolkit: Toolkit) -> str:
        parts = [self.system_prompt or "", toolkit.skills_prompt()]
        return "\n\n".join(p for p in parts if p)

    def _result(
        self,
        text: str,
        messages: list[dict[str, Any]],
        tool_calls: list[dict[str, Any]],
        turns: int,
        usage: dict[str, int],
    ) -> RunResult:
        return RunResult(
            text=text,
            messages=messages,
            tool_calls=tool_calls,
            tool_call_count=len(tool_calls),
            turns=turns,
            usage=usage,
            model=self.model,
        )

    async def _run_tool(
        self,
        toolkit: Toolkit,
        name: str,
        args: dict[str, Any],
        call_id: Optional[str],
        turn: int,
    ) -> tuple[dict[str, Any], ToolResult]:
        """Run one tool through before_tool/after_tool hooks: rewrite args,
        short-circuit, or transform the result. Mirrors JS ``runTool``."""
        a = args
        before = _get_hook(self.hooks, "before_tool")
        if before is not None:
            ov = await _call_hook(before, {"name": name, "args": a, "id": call_id, "turn": turn})
            if ov:
                if ov.get("result") is not None:
                    # short-circuit (deny / cache hit / dry-run) — real tool never runs
                    return a, ov["result"]
                if ov.get("args") is not None:
                    a = ov["args"]
        result = await toolkit.execute(name, a)
        after = _get_hook(self.hooks, "after_tool")
        if after is not None:
            ov = await _call_hook(
                after, {"name": name, "args": a, "result": result, "id": call_id, "turn": turn}
            )
            if ov and ov.get("result") is not None:
                result = ov["result"]
        return a, result

    async def run(self, prompt: str, toolkit: Toolkit) -> RunResult:
        if self.style == "anthropic":
            return await self._run_anthropic(prompt, toolkit)
        return await self._run_openai(prompt, toolkit)

    # ---- OpenAI-style: POST {base_url}/chat/completions ----
    async def _run_openai(self, prompt: str, toolkit: Toolkit) -> RunResult:
        key = _resolve_key(self.api_key, self.style)
        url = self.base_url.rstrip("/") + "/chat/completions"
        req_headers = {
            "Authorization": f"Bearer {key}",  # value never logged
            "Content-Type": "application/json",
            **self.headers,
        }
        messages: list[dict[str, Any]] = []
        system = self._system(toolkit)
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": prompt})
        tools = toolkit.to_openai()
        tool_calls: list[dict[str, Any]] = []
        usage = _empty_usage()
        turns = 0

        for turn in range(self.max_turns):
            turns += 1
            before = _get_hook(self.hooks, "before_llm")
            if before is not None:
                ov = await _call_hook(
                    before,
                    {"messages": messages, "tools": tools, "model": self.model, "turn": turn},
                )
                if ov:
                    if ov.get("messages") is not None:
                        messages = ov["messages"]
                    if ov.get("tools") is not None:
                        tools = ov["tools"]
            payload = {
                "model": self.model,
                "messages": messages,
                "tools": tools,
                "tool_choice": "auto",
            }
            data = await asyncio.to_thread(_post, url, req_headers, payload, 120.0)
            _add_usage(usage, data.get("usage"), "openai")
            after = _get_hook(self.hooks, "after_llm")
            if after is not None:
                await _call_hook(after, {"response": data, "model": self.model, "turn": turn})
            msg = data["choices"][0]["message"]
            messages.append(msg)
            calls = msg.get("tool_calls") or []
            if not calls:
                return self._result(
                    msg.get("content") or "", messages, tool_calls, turns, usage
                )

            # Parse the tool calls in order, then execute them concurrently (true
            # parallel tool calling). Each result is mapped back to its originating
            # call by index so tool_call_id ↔ output stays correct. We record the
            # tool_calls entry AFTER execute so it carries output/is_error/metadata.
            parsed = []
            for call in calls:
                fn = call["function"]
                args = _safe_json(fn.get("arguments"))
                parsed.append((call["id"], fn["name"], args))

            results = await asyncio.gather(
                *(
                    self._run_tool(toolkit, name, args, call_id, turn)
                    for (call_id, name, args) in parsed
                )
            )
            for (call_id, name, _orig_args), (args, result) in zip(parsed, results):
                tool_calls.append(
                    {
                        "name": name,
                        "args": args,
                        "output": result.output,
                        "is_error": result.is_error,
                        "metadata": result.metadata,
                    }
                )
                messages.append(
                    {"role": "tool", "tool_call_id": call_id, "content": result.output}
                )

        return self._result(_last_text(messages), messages, tool_calls, turns, usage)

    # ---- Anthropic-style: POST {base_url}/messages ----
    async def _run_anthropic(self, prompt: str, toolkit: Toolkit) -> RunResult:
        key = _resolve_key(self.api_key, self.style)
        base = self.base_url.rstrip("/")
        endpoint = f"{base}/messages" if base.endswith("/v1") else f"{base}/v1/messages"
        req_headers = {
            "x-api-key": key,  # value never logged
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            **self.headers,
        }
        system = self._system(toolkit)
        messages: list[dict[str, Any]] = [{"role": "user", "content": prompt}]
        tools = toolkit.to_anthropic()
        tool_calls: list[dict[str, Any]] = []
        usage = _empty_usage()
        turns = 0

        for turn in range(self.max_turns):
            turns += 1
            before = _get_hook(self.hooks, "before_llm")
            if before is not None:
                ov = await _call_hook(
                    before,
                    {"messages": messages, "tools": tools, "model": self.model, "turn": turn},
                )
                if ov:
                    if ov.get("messages") is not None:
                        messages = ov["messages"]
                    if ov.get("tools") is not None:
                        tools = ov["tools"]
            payload = {
                "model": self.model,
                "max_tokens": 4096,
                "system": system,
                "messages": messages,
                "tools": tools,
            }
            data = await asyncio.to_thread(_post, endpoint, req_headers, payload, 120.0)
            _add_usage(usage, data.get("usage"), "anthropic")
            after = _get_hook(self.hooks, "after_llm")
            if after is not None:
                await _call_hook(after, {"response": data, "model": self.model, "turn": turn})
            content = data.get("content") or []
            messages.append({"role": "assistant", "content": content})
            uses = [b for b in content if b.get("type") == "tool_use"]
            if not uses:
                text = "".join(b.get("text", "") for b in content if b.get("type") == "text")
                return self._result(text, messages, tool_calls, turns, usage)

            # Execute the tool_use blocks concurrently (true parallel tool calling).
            # Each result is mapped back to its originating block by index so
            # tool_use_id ↔ output stays correct. We record the tool_calls entry
            # AFTER execute so it carries output/is_error/metadata.
            outputs = await asyncio.gather(
                *(
                    self._run_tool(
                        toolkit, use["name"], use.get("input") or {}, use.get("id"), turn
                    )
                    for use in uses
                )
            )
            results: list[dict[str, Any]] = []
            for use, (args, result) in zip(uses, outputs):
                tool_calls.append(
                    {
                        "name": use["name"],
                        "args": args,
                        "output": result.output,
                        "is_error": result.is_error,
                        "metadata": result.metadata,
                    }
                )
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": use["id"],
                        "content": result.output,
                        "is_error": result.is_error,
                    }
                )
            messages.append({"role": "user", "content": results})

        return self._result("", messages, tool_calls, turns, usage)


def _last_text(messages: list[dict[str, Any]]) -> str:
    for m in reversed(messages):
        if m.get("role") == "assistant" and isinstance(m.get("content"), str):
            return m["content"]
    return ""


def create_client(
    *,
    base_url: str,
    style: ClientStyle,
    model: str,
    api_key: Optional[str] = None,
    headers: Optional[dict[str, str]] = None,
    system_prompt: Optional[str] = None,
    max_turns: int = 10,
    hooks: Hooks = None,
) -> Client:
    return Client(
        base_url=base_url,
        style=style,
        model=model,
        api_key=api_key,
        headers=headers,
        system_prompt=system_prompt,
        max_turns=max_turns,
        hooks=hooks,
    )
