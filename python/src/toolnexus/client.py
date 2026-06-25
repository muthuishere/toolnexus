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
import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Literal, Optional

from .toolkit import Toolkit

ClientStyle = Literal["openai", "anthropic"]


@dataclass
class RunResult:
    text: str
    messages: list[dict[str, Any]]
    tool_calls: list[dict[str, Any]] = field(default_factory=list)


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
    ) -> None:
        self.base_url = base_url
        self.style = style
        self.model = model
        self.api_key = api_key
        self.headers = headers or {}
        self.system_prompt = system_prompt
        self.max_turns = max_turns

    def _system(self, toolkit: Toolkit) -> str:
        parts = [self.system_prompt or "", toolkit.skills_prompt()]
        return "\n\n".join(p for p in parts if p)

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

        for _turn in range(self.max_turns):
            payload = {
                "model": self.model,
                "messages": messages,
                "tools": tools,
                "tool_choice": "auto",
            }
            data = await asyncio.to_thread(_post, url, req_headers, payload, 120.0)
            msg = data["choices"][0]["message"]
            messages.append(msg)
            calls = msg.get("tool_calls") or []
            if not calls:
                return RunResult(
                    text=msg.get("content") or "", messages=messages, tool_calls=tool_calls
                )

            # Record the tool calls in order, then execute them concurrently
            # (true parallel tool calling). Each result is mapped back to its
            # originating call by index so tool_call_id ↔ output stays correct.
            parsed = []
            for call in calls:
                fn = call["function"]
                args = _safe_json(fn.get("arguments"))
                tool_calls.append({"name": fn["name"], "args": args})
                parsed.append((call["id"], fn["name"], args))

            results = await asyncio.gather(
                *(toolkit.execute(name, args) for (_id, name, args) in parsed)
            )
            for (call_id, _name, _args), result in zip(parsed, results):
                messages.append(
                    {"role": "tool", "tool_call_id": call_id, "content": result.output}
                )

        return RunResult(text=_last_text(messages), messages=messages, tool_calls=tool_calls)

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

        for _turn in range(self.max_turns):
            payload = {
                "model": self.model,
                "max_tokens": 4096,
                "system": system,
                "messages": messages,
                "tools": tools,
            }
            data = await asyncio.to_thread(_post, endpoint, req_headers, payload, 120.0)
            content = data.get("content") or []
            messages.append({"role": "assistant", "content": content})
            uses = [b for b in content if b.get("type") == "tool_use"]
            if not uses:
                text = "".join(b.get("text", "") for b in content if b.get("type") == "text")
                return RunResult(text=text, messages=messages, tool_calls=tool_calls)

            # Record the tool_use blocks in order, then execute them concurrently
            # (true parallel tool calling). Each result is mapped back to its
            # originating block by index so tool_use_id ↔ output stays correct.
            for use in uses:
                tool_calls.append({"name": use["name"], "args": use.get("input") or {}})

            outputs = await asyncio.gather(
                *(toolkit.execute(use["name"], use.get("input") or {}) for use in uses)
            )
            results: list[dict[str, Any]] = [
                {
                    "type": "tool_result",
                    "tool_use_id": use["id"],
                    "content": result.output,
                    "is_error": result.is_error,
                }
                for use, result in zip(uses, outputs)
            ]
            messages.append({"role": "user", "content": results})

        return RunResult(text="", messages=messages, tool_calls=tool_calls)


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
) -> Client:
    return Client(
        base_url=base_url,
        style=style,
        model=model,
        api_key=api_key,
        headers=headers,
        system_prompt=system_prompt,
        max_turns=max_turns,
    )
