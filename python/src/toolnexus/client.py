"""Unified LLM client — the host loop.

Give it a plain base URL + a style ("openai" | "anthropic") and it runs the whole
tool-calling agent loop against a :class:`Toolkit`. See ../../SPEC.md §8. Mirrors
the JS reference (``js/src/client.ts``).

Uses only the standard library (``urllib`` + ``json``); the blocking POST runs in
a thread via ``asyncio.to_thread``. For streaming, the blocking SSE read runs in a
worker thread that feeds an :class:`asyncio.Queue`, and the async generator yields
from that queue (no third-party deps). The API key is read from the ``api_key`` arg
or the environment and is NEVER printed.

Resilience: the LLM request retries on 429/5xx + network errors with exponential
backoff + jitter (honoring ``Retry-After``); a whole-run ``timeout_ms`` deadline
(and an optional external :class:`asyncio.Event` cancel token) aborts the run.
Aborts/timeouts are not retried.

Memory: ``run(prompt, toolkit, history=...)`` continues a prior transcript;
:class:`Conversation` (``client.conversation(toolkit)``) is the stateful wrapper.
"""
from __future__ import annotations

import asyncio
import inspect
import json
import os
import random
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Literal, Mapping, Optional

from .toolkit import Toolkit
from .types import ToolResult

ClientStyle = Literal["openai", "anthropic"]

# HTTP statuses worth retrying (transient).
_RETRYABLE = frozenset({429, 500, 502, 503, 504})

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


class RunTimeout(TimeoutError):
    """Raised when a run exceeds ``timeout_ms`` (or the external cancel token fires)."""


class RunCancelled(Exception):
    """Raised when an external cancel token (``asyncio.Event``) is set mid-run."""


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


class _HttpError(Exception):
    """Carries an HTTP status + body so the retry loop can inspect ``status``."""

    def __init__(self, status: int, text: str, retry_after: Optional[float]) -> None:
        super().__init__(f"LLM {status}: {text}")
        self.status = status
        self.text = text
        self.retry_after = retry_after


def _open(url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):
    """Open a POST request, returning the live response object (caller closes/reads)."""
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        return urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")
        ra = e.headers.get("Retry-After") if e.headers else None
        retry_after = None
        if ra:
            try:
                retry_after = float(ra)
            except ValueError:
                retry_after = None
        raise _HttpError(e.code, text, retry_after) from None


def _post(url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
    """Blocking JSON POST → parsed dict (raises ``_HttpError`` on HTTP error)."""
    with _open(url, headers, payload, timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _safe_json(s: Any) -> dict[str, Any]:
    if isinstance(s, dict):
        return s
    try:
        return json.loads(s or "{}")
    except Exception:
        return {}


def _last_text(messages: list[dict[str, Any]]) -> str:
    for m in reversed(messages):
        if m.get("role") == "assistant" and isinstance(m.get("content"), str):
            return m["content"]
    return ""


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
        retries: int = 2,
        retry_base_ms: int = 500,
        timeout_ms: Optional[int] = None,
    ) -> None:
        self.base_url = base_url
        self.style = style
        self.model = model
        self.api_key = api_key
        self.headers = headers or {}
        self.system_prompt = system_prompt
        self.max_turns = max_turns
        self.hooks = hooks
        self.retries = retries
        self.retry_base_ms = retry_base_ms
        self.timeout_ms = timeout_ms

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

    # ----------------------------------------------------------------------- #
    # Resilience helpers
    # ----------------------------------------------------------------------- #
    def _deadline(self) -> Optional[float]:
        """Monotonic deadline for the whole run, or None when no ``timeout_ms``."""
        if self.timeout_ms is None:
            return None
        return time.monotonic() + self.timeout_ms / 1000.0

    @staticmethod
    def _check_cancelled(cancel: Optional[asyncio.Event]) -> None:
        if cancel is not None and cancel.is_set():
            raise RunCancelled("run cancelled")

    def _remaining(self, deadline: Optional[float], cancel: Optional[asyncio.Event]) -> float:
        """Seconds left before the deadline (used as the per-request urllib timeout).

        Raises :class:`RunTimeout` if already past the deadline, or
        :class:`RunCancelled` if the external token fired.
        """
        self._check_cancelled(cancel)
        if deadline is None:
            return 120.0  # default per-request timeout when no run deadline
        left = deadline - time.monotonic()
        if left <= 0:
            raise RunTimeout(f"run timeout after {self.timeout_ms}ms")
        return left

    async def _sleep(self, seconds: float, deadline: Optional[float], cancel: Optional[asyncio.Event]) -> None:
        """Backoff sleep that respects the deadline and cancel token."""
        if deadline is not None:
            seconds = min(seconds, max(0.0, deadline - time.monotonic()))
        # Poll the cancel token in small slices so a cancel interrupts a long backoff.
        end = time.monotonic() + seconds
        while True:
            self._check_cancelled(cancel)
            if deadline is not None and time.monotonic() >= deadline:
                raise RunTimeout(f"run timeout after {self.timeout_ms}ms")
            remaining = end - time.monotonic()
            if remaining <= 0:
                return
            await asyncio.sleep(min(0.1, remaining))

    async def _llm_fetch(
        self,
        worker,
        url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        deadline: Optional[float],
        cancel: Optional[asyncio.Event],
    ):
        """Run ``worker(url, headers, payload, per_request_timeout)`` in a thread with
        retry + exponential backoff on 429/5xx + network errors, honoring
        ``Retry-After``. Aborts (timeout/cancel) are not retried.

        ``worker`` returns whatever it produces (a parsed dict for non-streaming, or
        a live response for streaming). Mirrors JS ``llmFetch``.
        """
        base = self.retry_base_ms / 1000.0
        last_err: Optional[Exception] = None
        for attempt in range(self.retries + 1):
            per_req = self._remaining(deadline, cancel)
            try:
                return await asyncio.to_thread(worker, url, headers, payload, per_req)
            except _HttpError as e:
                last_err = e
                if e.status not in _RETRYABLE or attempt == self.retries:
                    raise
                wait = e.retry_after if e.retry_after else base * (2 ** attempt) + random.random() * 0.1
                await self._sleep(wait, deadline, cancel)
            except (RunTimeout, RunCancelled):
                raise  # never retry an abort
            except (urllib.error.URLError, OSError, TimeoutError) as e:
                # urllib raises socket.timeout (subclass of OSError/TimeoutError) on
                # the per-request timeout; treat as the deadline if one is set.
                if deadline is not None and time.monotonic() >= deadline:
                    raise RunTimeout(f"run timeout after {self.timeout_ms}ms") from None
                self._check_cancelled(cancel)
                last_err = e
                if attempt == self.retries:
                    raise
                await self._sleep(base * (2 ** attempt) + random.random() * 0.1, deadline, cancel)
        assert last_err is not None
        raise last_err

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

    async def run(
        self,
        prompt: str,
        toolkit: Toolkit,
        history: Optional[list[dict[str, Any]]] = None,
        cancel: Optional[asyncio.Event] = None,
    ) -> RunResult:
        """Run the agent loop. ``history`` continues a prior transcript (the system
        prompt is NOT re-added); ``cancel`` is an optional external abort token."""
        if self.style == "anthropic":
            return await self._run_anthropic(prompt, toolkit, history, cancel)
        return await self._run_openai(prompt, toolkit, history, cancel)

    def conversation(self, toolkit: Toolkit, cancel: Optional[asyncio.Event] = None) -> "Conversation":
        """A stateful multi-turn conversation that retains history across sends."""
        return Conversation(self, toolkit, cancel)

    def stream(
        self,
        prompt: str,
        toolkit: Toolkit,
        cancel: Optional[asyncio.Event] = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        """Streaming variant: async-iterate live event dicts (text deltas,
        tool_call/tool_result, usage, done)."""
        if self.style == "anthropic":
            return self._stream_anthropic(prompt, toolkit, cancel)
        return self._stream_openai(prompt, toolkit, cancel)

    # ----------------------------------------------------------------------- #
    # OpenAI-style: POST {base_url}/chat/completions
    # ----------------------------------------------------------------------- #
    async def _run_openai(
        self,
        prompt: str,
        toolkit: Toolkit,
        history: Optional[list[dict[str, Any]]],
        cancel: Optional[asyncio.Event],
    ) -> RunResult:
        key = _resolve_key(self.api_key, self.style)
        url = self.base_url.rstrip("/") + "/chat/completions"
        req_headers = {
            "Authorization": f"Bearer {key}",  # value never logged
            "Content-Type": "application/json",
            **self.headers,
        }
        deadline = self._deadline()
        messages: list[dict[str, Any]] = list(history) if history else []
        if not messages:
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
            data = await self._llm_fetch(_post, url, req_headers, payload, deadline, cancel)
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

    # ----------------------------------------------------------------------- #
    # Anthropic-style: POST {base_url}/messages
    # ----------------------------------------------------------------------- #
    async def _run_anthropic(
        self,
        prompt: str,
        toolkit: Toolkit,
        history: Optional[list[dict[str, Any]]],
        cancel: Optional[asyncio.Event],
    ) -> RunResult:
        key = _resolve_key(self.api_key, self.style)
        base = self.base_url.rstrip("/")
        endpoint = f"{base}/messages" if base.endswith("/v1") else f"{base}/v1/messages"
        req_headers = {
            "x-api-key": key,  # value never logged
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            **self.headers,
        }
        deadline = self._deadline()
        system = self._system(toolkit)
        if history:
            messages: list[dict[str, Any]] = list(history) + [{"role": "user", "content": prompt}]
        else:
            messages = [{"role": "user", "content": prompt}]
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
            data = await self._llm_fetch(_post, endpoint, req_headers, payload, deadline, cancel)
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

    # ----------------------------------------------------------------------- #
    # Streaming: OpenAI-style
    # ----------------------------------------------------------------------- #
    async def _stream_openai(
        self,
        prompt: str,
        toolkit: Toolkit,
        cancel: Optional[asyncio.Event],
    ) -> AsyncGenerator[dict[str, Any], None]:
        key = _resolve_key(self.api_key, self.style)
        url = self.base_url.rstrip("/") + "/chat/completions"
        req_headers = {
            "Authorization": f"Bearer {key}",  # value never logged
            "Content-Type": "application/json",
            **self.headers,
        }
        deadline = self._deadline()
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
                "stream": True,
                "stream_options": {"include_usage": True},
            }

            content = ""
            acc: dict[int, dict[str, str]] = {}
            async for line in self._sse_lines(url, req_headers, payload, deadline, cancel):
                if not line.startswith("data:"):
                    continue
                data_str = line[5:].strip()
                if data_str == "[DONE]":
                    break
                j = _safe_parse(data_str)
                if not j:
                    continue
                if j.get("usage"):
                    _add_usage(usage, j["usage"], "openai")
                choices = j.get("choices") or []
                if not choices:
                    continue
                d = choices[0].get("delta") or {}
                if d.get("content"):
                    content += d["content"]
                    yield {"type": "text", "delta": d["content"]}
                for tc in d.get("tool_calls") or []:
                    idx = tc.get("index", 0)
                    slot = acc.setdefault(idx, {"id": "", "name": "", "args": ""})
                    if tc.get("id"):
                        slot["id"] = tc["id"]
                    fn = tc.get("function") or {}
                    if fn.get("name"):
                        slot["name"] += fn["name"]
                    if fn.get("arguments"):
                        slot["args"] += fn["arguments"]

            after = _get_hook(self.hooks, "after_llm")
            if after is not None:
                await _call_hook(
                    after, {"response": {"streamed": True, "usage": usage}, "model": self.model, "turn": turn}
                )

            calls = [acc[i] for i in sorted(acc)]
            if not calls:
                messages.append({"role": "assistant", "content": content})
                yield {"type": "usage", "usage": usage}
                yield {"type": "done", "result": self._result(content, messages, tool_calls, turns, usage)}
                return

            messages.append(
                {
                    "role": "assistant",
                    "content": content or None,
                    "tool_calls": [
                        {
                            "id": c["id"],
                            "type": "function",
                            "function": {"name": c["name"], "arguments": c["args"]},
                        }
                        for c in calls
                    ],
                }
            )
            for c in calls:
                yield {"type": "tool_call", "id": c["id"], "name": c["name"], "args": _safe_json(c["args"])}
            settled = await asyncio.gather(
                *(self._run_tool(toolkit, c["name"], _safe_json(c["args"]), c["id"], turn) for c in calls)
            )
            for c, (args, result) in zip(calls, settled):
                tool_calls.append(
                    {
                        "name": c["name"],
                        "args": args,
                        "output": result.output,
                        "is_error": result.is_error,
                        "metadata": result.metadata,
                    }
                )
                messages.append({"role": "tool", "tool_call_id": c["id"], "content": result.output})
                yield {
                    "type": "tool_result",
                    "id": c["id"],
                    "name": c["name"],
                    "output": result.output,
                    "is_error": result.is_error,
                }

        yield {"type": "done", "result": self._result(_last_text(messages), messages, tool_calls, turns, usage)}

    # ----------------------------------------------------------------------- #
    # Streaming: Anthropic-style
    # ----------------------------------------------------------------------- #
    async def _stream_anthropic(
        self,
        prompt: str,
        toolkit: Toolkit,
        cancel: Optional[asyncio.Event],
    ) -> AsyncGenerator[dict[str, Any], None]:
        key = _resolve_key(self.api_key, self.style)
        base = self.base_url.rstrip("/")
        endpoint = f"{base}/messages" if base.endswith("/v1") else f"{base}/v1/messages"
        req_headers = {
            "x-api-key": key,  # value never logged
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            **self.headers,
        }
        deadline = self._deadline()
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
                "stream": True,
            }

            blocks: dict[int, dict[str, Any]] = {}
            stop_reason = ""
            async for line in self._sse_lines(endpoint, req_headers, payload, deadline, cancel):
                if not line.startswith("data:"):
                    continue
                j = _safe_parse(line[5:].strip())
                if not j:
                    continue
                t = j.get("type")
                if t == "message_start":
                    _add_usage(usage, (j.get("message") or {}).get("usage"), "anthropic")
                elif t == "content_block_start":
                    cb = j.get("content_block") or {}
                    blocks[j["index"]] = {
                        "type": cb.get("type"),
                        "id": cb.get("id"),
                        "name": cb.get("name"),
                        "text": "",
                        "json": "",
                    }
                elif t == "content_block_delta":
                    b = blocks.get(j["index"])
                    if b is None:
                        continue
                    delta = j.get("delta") or {}
                    if delta.get("type") == "text_delta":
                        b["text"] += delta.get("text", "")
                        yield {"type": "text", "delta": delta.get("text", "")}
                    elif delta.get("type") == "input_json_delta":
                        b["json"] += delta.get("partial_json", "")
                elif t == "message_delta":
                    stop_reason = (j.get("delta") or {}).get("stop_reason") or stop_reason
                    _add_usage(usage, j.get("usage"), "anthropic")

            after = _get_hook(self.hooks, "after_llm")
            if after is not None:
                await _call_hook(
                    after, {"response": {"streamed": True, "usage": usage}, "model": self.model, "turn": turn}
                )

            content = [
                {"type": "tool_use", "id": b["id"], "name": b["name"], "input": _safe_json(b["json"] or "{}")}
                if b["type"] == "tool_use"
                else {"type": "text", "text": b["text"]}
                for b in (blocks[i] for i in sorted(blocks))
            ]
            messages.append({"role": "assistant", "content": content})
            uses = [b for b in content if b["type"] == "tool_use"]
            if stop_reason != "tool_use" or not uses:
                text = "".join(b["text"] for b in content if b["type"] == "text")
                yield {"type": "usage", "usage": usage}
                yield {"type": "done", "result": self._result(text, messages, tool_calls, turns, usage)}
                return

            for u in uses:
                yield {"type": "tool_call", "id": u["id"], "name": u["name"], "args": u["input"]}
            settled = await asyncio.gather(
                *(self._run_tool(toolkit, u["name"], u.get("input") or {}, u["id"], turn) for u in uses)
            )
            results: list[dict[str, Any]] = []
            for u, (args, result) in zip(uses, settled):
                tool_calls.append(
                    {
                        "name": u["name"],
                        "args": args,
                        "output": result.output,
                        "is_error": result.is_error,
                        "metadata": result.metadata,
                    }
                )
                results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": u["id"],
                        "content": result.output,
                        "is_error": result.is_error,
                    }
                )
                yield {
                    "type": "tool_result",
                    "id": u["id"],
                    "name": u["name"],
                    "output": result.output,
                    "is_error": result.is_error,
                }
            messages.append({"role": "user", "content": results})

        yield {"type": "done", "result": self._result("", messages, tool_calls, turns, usage)}

    # ----------------------------------------------------------------------- #
    # SSE plumbing: blocking urllib read in a worker thread -> asyncio.Queue
    # ----------------------------------------------------------------------- #
    async def _sse_lines(
        self,
        url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        deadline: Optional[float],
        cancel: Optional[asyncio.Event],
    ) -> AsyncGenerator[str, None]:
        """Open the streaming POST (with retry on the initial connect) and yield SSE
        lines. Since urllib is blocking, a worker thread reads the response and feeds
        an :class:`asyncio.Queue`; this async generator awaits items from the queue.
        """
        loop = asyncio.get_running_loop()
        # Initial connection uses the retrying fetch (retries transient connect errors).
        resp = await self._llm_fetch(_open, url, headers, payload, deadline, cancel)

        queue: asyncio.Queue = asyncio.Queue(maxsize=256)
        _SENTINEL = object()
        stop = threading.Event()

        def reader() -> None:
            buf = b""
            try:
                # read1() returns as soon as ANY data is available (true incremental
                # streaming) instead of blocking for a full buffer like read(n).
                read = getattr(resp, "read1", None) or resp.read
                while not stop.is_set():
                    chunk = read(8192)  # resp carries its own socket timeout
                    if not chunk:
                        break
                    buf += chunk
                    while b"\n" in buf:
                        idx = buf.index(b"\n")
                        line = buf[:idx].rstrip(b"\r")
                        buf = buf[idx + 1 :]
                        loop.call_soon_threadsafe(queue.put_nowait, line.decode("utf-8", "replace"))
                if buf:
                    loop.call_soon_threadsafe(queue.put_nowait, buf.rstrip(b"\r").decode("utf-8", "replace"))
            except Exception as e:  # noqa: BLE001 — surface to the consumer
                loop.call_soon_threadsafe(queue.put_nowait, e)
            finally:
                loop.call_soon_threadsafe(queue.put_nowait, _SENTINEL)

        worker = loop.run_in_executor(None, reader)
        try:
            while True:
                # Honor the run deadline / cancel even while blocked on the queue.
                timeout = None
                if deadline is not None:
                    timeout = deadline - time.monotonic()
                    if timeout <= 0:
                        raise RunTimeout(f"run timeout after {self.timeout_ms}ms")
                try:
                    item = await asyncio.wait_for(queue.get(), timeout=timeout)
                except asyncio.TimeoutError:
                    raise RunTimeout(f"run timeout after {self.timeout_ms}ms") from None
                self._check_cancelled(cancel)
                if item is _SENTINEL:
                    break
                if isinstance(item, Exception):
                    raise item
                yield item
        finally:
            stop.set()
            try:
                resp.close()
            except Exception:
                pass
            # Let the reader thread observe `stop` / the closed socket and exit.
            try:
                await asyncio.wait_for(asyncio.shield(worker), timeout=1.0)
            except Exception:
                pass


def _safe_parse(s: str) -> Optional[dict[str, Any]]:
    try:
        return json.loads(s)
    except Exception:
        return None


class Conversation:
    """Stateful multi-turn conversation: each :meth:`send` continues the same
    transcript (memory). Mirrors the JS ``Conversation``."""

    def __init__(self, client: Client, toolkit: Toolkit, cancel: Optional[asyncio.Event] = None) -> None:
        self._client = client
        self._toolkit = toolkit
        self._cancel = cancel
        # Full running transcript (system + user + assistant + tool messages).
        self.messages: list[dict[str, Any]] = []

    async def send(self, prompt: str) -> RunResult:
        """Send the next user turn; prior history is retained automatically."""
        result = await self._client.run(
            prompt, self._toolkit, history=self.messages, cancel=self._cancel
        )
        self.messages = result.messages
        return result

    def reset(self) -> None:
        """Reset the conversation memory."""
        self.messages = []


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
    retries: int = 2,
    retry_base_ms: int = 500,
    timeout_ms: Optional[int] = None,
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
        retries=retries,
        retry_base_ms=retry_base_ms,
        timeout_ms=timeout_ms,
    )
