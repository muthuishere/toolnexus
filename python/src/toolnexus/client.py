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
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, AsyncGenerator, Callable, Literal, Mapping, Optional, Protocol, TypedDict

from .toolkit import Toolkit
from .types import Answer, Request, ToolContext, ToolResult, pending_of

# §10 Suspension resolver: (request) -> answer, idiomatic async per port. May be a
# plain sync callable or an async coroutine; its interior is unconstrained.
WaitFor = Callable[[Request], Any]

ClientStyle = Literal["openai", "anthropic"]

# §8 Resilience. What to do with a failed LLM call.
ErrorTier = Literal["retry", "fail"]


class ErrorInfo(TypedDict, total=False):
    """§8 Resilience. Context passed to ``on_error`` for each failed LLM attempt.

    ``status`` is present on a non-ok HTTP response; ``error`` on a transport/network
    throw. ``attempt`` is the zero-based try index. ``retryable`` is whether the
    status/error is in the default retryable set (429/5xx/network).
    """

    error: Any
    status: int
    attempt: int
    retryable: bool


# §8 Resilience. Classify each failed LLM attempt into "retry" or "fail". Omit ⇒ the
# default classifier (retryable-within-budget ⇒ retry, else fail) — byte-identical to
# today. A "retry" is always bounded by ``retries``; the classifier cannot loop unbounded.
ErrorClassifier = Callable[[ErrorInfo], ErrorTier]

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
    # "done" normally; "pending" iff a tool suspended and no wait_for was set (§10);
    # "incomplete" iff a §7D limit stopped the run — here, ``max_turns`` exhausted
    # while the model was still emitting tool calls with no final text (loud, never
    # a silent "done").
    status: Literal["done", "pending", "incomplete"] = "done"
    # §7D: which limit stopped the run (e.g. "maxTurns") — set ONLY when
    # status == "incomplete"; None otherwise.
    limit: Optional[str] = None
    # The unresolved suspension — present iff status == "pending" (§10).
    pending: Optional[Request] = None


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


# --------------------------------------------------------------------------- #
# Gap 2 — injectable HTTP transport for the LLM path.
# --------------------------------------------------------------------------- #
#
# This port drives LLM requests with the standard library (``urllib``) in worker
# threads, not ``httpx``, so the idiomatic injection point is a small transport
# object exposing the two workers the loop uses: ``post`` (non-streaming, returns a
# parsed dict) and ``open`` (streaming, returns a live response with ``read1``/``read``/
# ``close``). Supply an ``http_transport`` to route the LLM path through a custom
# implementation (observability, proxying, a recording double in tests); None ⇒ the
# default urllib transport. Scope is the LLM path only — MCP transports use the MCP
# SDK's own clients (§8 Gap 2, A5). Mirrors Go's injectable ``*http.Client``.
class HttpTransport(Protocol):
    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        """Blocking JSON POST → parsed dict (raise ``_HttpError`` on HTTP error)."""
        ...

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        """Open a streaming POST, returning a live response object the caller reads/closes."""
        ...


class UrllibTransport:
    """Default HTTP transport for the LLM path — stdlib ``urllib`` (today's behavior)."""

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        return _post(url, headers, payload, timeout)

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):  # noqa: A003
        return _open(url, headers, payload, timeout)


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


# --------------------------------------------------------------------------- #
# Observability — semantic metric events (§8) + zero-dependency Prometheus text
# --------------------------------------------------------------------------- #
#
# ``on_metric`` receives readable, snake_case dict events (idiomatic Python — the
# field CASING is NOT byte-identical to the JS reference, exactly like RunResult's
# ``tool_calls``; only ``metrics()`` text below is byte-identical across ports):
#   {"event": "llm", "model", "status": "ok"|"error", "ms",
#    "prompt_tokens", "completion_tokens"}                    — one per LLM call
#   {"event": "tool", "tool", "source", "is_error", "ms",
#    "pending"?}                                              — one per tool call
#    (``pending`` is True when the result is a §10 suspension: never a tool error)
#   {"event": "run", "model", "turns", "tool_calls",
#    "total_tokens", "ms", "error"?}                          — one per run/ask
# The same events also feed the built-in Prometheus registry (``client.metrics()``).
MetricEvent = dict[str, Any]
OnMetric = Callable[[MetricEvent], None]


def _now() -> float:
    return time.monotonic()


def _ms_since(start: float) -> float:
    """Elapsed milliseconds since ``start`` (a ``time.monotonic()`` reading)."""
    return (time.monotonic() - start) * 1000.0


def _per_call(raw: Any, style: ClientStyle) -> tuple[int, int]:
    """Per-call (prompt, completion) tokens from one raw usage payload (not summed)."""
    if not raw:
        return (0, 0)
    if style == "anthropic":
        return (raw.get("input_tokens") or 0, raw.get("output_tokens") or 0)
    return (raw.get("prompt_tokens") or 0, raw.get("completion_tokens") or 0)


# FIXED across all ports for byte-parity of *_duration_seconds histograms. Seconds.
_DURATION_BUCKETS = [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60]
# ``le`` label values rendered exactly like JS ``String(bucket)`` (1 not 1.0).
_DURATION_BUCKET_LES = ["0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "60"]


def _fmt_num(x: float) -> str:
    """Render a number like JS ``String()``: integral values without a decimal point."""
    f = float(x)
    if f.is_integer():
        return str(int(f))
    return repr(f)


def _escape_label(v: str) -> str:
    """Escape a Prometheus label value: backslash, double-quote, newline."""
    return v.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _label_str(pairs: list[tuple[str, str]]) -> str:
    """Render ordered label pairs to ``k="v",k="v"`` (order is load-bearing for parity)."""
    return ",".join(f'{k}="{_escape_label(v)}"' for k, v in pairs)


def _with_le(base: str, le: str) -> str:
    return f'{base},le="{le}"' if base else f'le="{le}"'


class _Hist:
    __slots__ = ("counts", "sum", "count")

    def __init__(self) -> None:
        self.counts = [0] * len(_DURATION_BUCKETS)
        self.sum = 0.0
        self.count = 0


class MetricsRegistry:
    """In-memory cumulative registry that turns the same semantic events ``on_metric``
    sees into the Prometheus counters/histograms of §8, and renders the text exposition
    format by hand (no dependency). The rendered text is byte-identical across all ports.
    """

    def __init__(self) -> None:
        self._llm_requests: dict[str, float] = {}   # labels: model,status
        self._llm_tokens: dict[str, float] = {}     # labels: type
        self._llm_duration: dict[str, _Hist] = {}   # labels: model
        self._tool_calls: dict[str, float] = {}     # labels: tool,source,is_error,pending
        self._tool_duration: dict[str, _Hist] = {}  # labels: tool
        self._run_errors: dict[str, float] = {}     # labels: model

    def record(self, ev: MetricEvent) -> None:
        et = ev.get("event")
        if et == "llm":
            self._inc(self._llm_requests, _label_str([("model", ev["model"]), ("status", ev["status"])]))
            if ev["status"] == "ok":
                self._inc(self._llm_tokens, _label_str([("type", "prompt")]), ev["prompt_tokens"])
                self._inc(self._llm_tokens, _label_str([("type", "completion")]), ev["completion_tokens"])
            self._observe(self._llm_duration, _label_str([("model", ev["model"])]), ev["ms"] / 1000)
        elif et == "tool":
            self._inc(
                self._tool_calls,
                _label_str([("tool", ev["tool"]), ("source", ev["source"]), ("is_error", "true" if ev["is_error"] else "false"), ("pending", "true" if ev.get("pending") else "false")]),
            )
            self._observe(self._tool_duration, _label_str([("tool", ev["tool"])]), ev["ms"] / 1000)
        elif et == "run":
            if ev.get("error"):
                self._inc(self._run_errors, _label_str([("model", ev["model"])]))

    @staticmethod
    def _inc(m: dict[str, float], key: str, by: float = 1) -> None:
        m[key] = m.get(key, 0) + by

    @staticmethod
    def _observe(m: dict[str, _Hist], key: str, seconds: float) -> None:
        h = m.get(key)
        if h is None:
            h = _Hist()
            m[key] = h
        h.sum += seconds
        h.count += 1
        for i, bucket in enumerate(_DURATION_BUCKETS):
            if seconds <= bucket:
                h.counts[i] += 1

    def render(self) -> str:
        out: list[str] = []
        self._render_counter(out, "toolnexus_llm_requests_total", "Total LLM requests.", self._llm_requests)
        self._render_counter(out, "toolnexus_llm_tokens_total", "Total tokens, by type.", self._llm_tokens)
        self._render_histogram(out, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", self._llm_duration)
        self._render_counter(out, "toolnexus_tool_calls_total", "Total tool calls.", self._tool_calls)
        self._render_histogram(out, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", self._tool_duration)
        self._render_counter(out, "toolnexus_run_errors_total", "Total run errors.", self._run_errors)
        return "\n".join(out) + "\n"

    @staticmethod
    def _render_counter(out: list[str], name: str, help_text: str, m: dict[str, float]) -> None:
        out.append(f"# HELP {name} {help_text}")
        out.append(f"# TYPE {name} counter")
        for key in sorted(m.keys()):
            v = _fmt_num(m[key])
            out.append(f"{name}{{{key}}} {v}" if key else f"{name} {v}")

    @staticmethod
    def _render_histogram(out: list[str], name: str, help_text: str, m: dict[str, _Hist]) -> None:
        out.append(f"# HELP {name} {help_text}")
        out.append(f"# TYPE {name} histogram")
        for key in sorted(m.keys()):
            h = m[key]
            for i, le in enumerate(_DURATION_BUCKET_LES):
                out.append(f"{name}_bucket{{{_with_le(key, le)}}} {_fmt_num(h.counts[i])}")
            out.append(f"{name}_bucket{{{_with_le(key, '+Inf')}}} {_fmt_num(h.count)}")
            out.append(f"{name}_sum{{{key}}} {_fmt_num(h.sum)}" if key else f"{name}_sum {_fmt_num(h.sum)}")
            out.append(f"{name}_count{{{key}}} {_fmt_num(h.count)}" if key else f"{name}_count {_fmt_num(h.count)}")


class ConversationStore(Protocol):
    """Where ``ask()`` conversations are remembered — two async methods. Ship the
    in-memory default; implement this for a file/db/redis provider to persist
    across processes. Mirrors the JS ``ConversationStore`` interface.
    """

    async def get(self, id: str) -> Optional[list[dict[str, Any]]]:  # noqa: A002
        """Return the stored transcript for ``id``, or None if none."""
        ...

    async def save(self, id: str, messages: list[dict[str, Any]]) -> None:  # noqa: A002
        """Persist the (updated) transcript for ``id``."""
        ...


class InMemoryConversationStore:
    """Default conversation provider — keeps transcripts in memory for the client's
    lifetime. Copies on get/save so callers can't mutate the stored transcript.
    Mirrors the JS ``InMemoryConversationStore``.
    """

    def __init__(self) -> None:
        self._map: dict[str, list[dict[str, Any]]] = {}

    async def get(self, id: str) -> Optional[list[dict[str, Any]]]:  # noqa: A002
        m = self._map.get(id)
        return list(m) if m is not None else None

    async def save(self, id: str, messages: list[dict[str, Any]]) -> None:  # noqa: A002
        self._map[id] = list(messages)


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
        store: Optional[ConversationStore] = None,
        on_metric: Optional[OnMetric] = None,
        wait_for: Optional[WaitFor] = None,
        request_params: Optional[dict[str, Any]] = None,
        body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
        http_transport: Optional[HttpTransport] = None,
        on_error: Optional[ErrorClassifier] = None,
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
        # Gap 1: extra top-level body keys shallow-merged into EVERY LLM body (caller
        # wins); messages/tools/stream forbidden here. body_transform runs LAST.
        self.request_params = request_params
        self.body_transform = body_transform
        # Gap 2: injectable HTTP transport for the LLM path; None ⇒ default urllib.
        self._transport: HttpTransport = http_transport if http_transport is not None else UrllibTransport()
        # §8 Resilience: host-classified retry-vs-fail for each failed LLM attempt.
        # None ⇒ the default classifier (retryable ⇒ "retry", else "fail"), byte-identical
        # to prior behavior. A "retry" is always capped by ``retries`` in _llm_fetch.
        self.on_error: Optional[ErrorClassifier] = on_error
        # Conversation provider for ask() — from the `store` arg, else in-memory.
        self.store: ConversationStore = store if store is not None else InMemoryConversationStore()
        # §10 Suspension resolver — when a tool returns Pending, the client calls
        # this then retries the tool with ctx.answer set. None ⇒ durable halt.
        self.wait_for = wait_for
        # Observability sink — receives semantic metric events as the loop runs (§8).
        self.on_metric = on_metric
        # Cumulative Prometheus registry fed by the same events on_metric sees.
        self._registry = MetricsRegistry()

    def metrics(self) -> str:
        """Prometheus text exposition of cumulative metrics (§8). Always valid, and
        empty-but-valid (HELP/TYPE only) before any activity."""
        return self._registry.render()

    @property
    def conversation_store(self) -> ConversationStore:
        """The client's conversation provider (§8 Gap 4) — the exact instance passed as
        ``store``, else the default in-memory store the client created. Read (``get``)
        and write (``save``) it directly to share state with :meth:`ask` /
        :meth:`stream`, so no consumer needs a shadow copy. Mirrors Go
        ``Client.ConversationStore()``."""
        return self.store

    def _finalize_body(self, body: dict[str, Any]) -> dict[str, Any]:
        """Apply the Gap 1 request-shaping contract to an assembled body: strip
        forbidden keys from ``request_params``, shallow-merge ``request_params`` (caller
        wins), then run ``body_transform`` last (its return replaces the body; None ⇒
        unchanged). Called at every LLM body-build site. Mirrors Go ``finalizeBody``."""
        if self.request_params:
            for k, v in self.request_params.items():
                if k in ("messages", "tools", "stream"):
                    print(
                        f'[toolnexus] request_params key "{k}" is not allowed — ignored (use body_transform)',
                        file=sys.stderr,
                    )
                    continue
                body[k] = v
        if self.body_transform is not None:
            out = self.body_transform(body)
            if out is not None:
                return out
        return body

    def _emit(self, ev: MetricEvent) -> None:
        """Feed a semantic metric event to the built-in registry + the optional sink."""
        self._registry.record(ev)
        if self.on_metric is not None:
            self.on_metric(ev)

    def _end_run(
        self,
        run_start: float,
        text: str,
        messages: list[dict[str, Any]],
        tool_calls: list[dict[str, Any]],
        turns: int,
        usage: dict[str, int],
        at_limit: bool = False,
    ) -> RunResult:
        """Emit the terminal ``run`` metric event and build the RunResult.

        ``at_limit`` marks a run that ended by exhausting ``max_turns`` while the
        model was still emitting tool calls; with no final assistant text that is a
        loud ``status="incomplete"`` (§7D limit stop), never a silent ``"done"``.
        """
        self._emit(
            {
                "event": "run",
                "model": self.model,
                "turns": turns,
                "tool_calls": len(tool_calls),
                "total_tokens": usage["total_tokens"],
                "ms": _ms_since(run_start),
            }
        )
        return self._result(text, messages, tool_calls, turns, usage, at_limit=at_limit)

    def _emit_run_error(
        self,
        run_start: float,
        tool_calls: list[dict[str, Any]],
        turns: int,
        usage: dict[str, int],
        e: BaseException,
    ) -> None:
        """Emit a ``run`` error metric event (once, on a thrown run)."""
        self._emit(
            {
                "event": "run",
                "model": self.model,
                "turns": turns,
                "tool_calls": len(tool_calls),
                "total_tokens": usage["total_tokens"],
                "ms": _ms_since(run_start),
                "error": str(e),
            }
        )

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
        at_limit: bool = False,
    ) -> RunResult:
        return RunResult(
            text=text,
            messages=messages,
            tool_calls=tool_calls,
            tool_call_count=len(tool_calls),
            turns=turns,
            usage=usage,
            model=self.model,
            status="incomplete" if at_limit and not text else "done",
            limit="maxTurns" if at_limit and not text else None,
        )

    # ----------------------------------------------------------------------- #
    # §10 Suspension — Pending / wait_for
    # ----------------------------------------------------------------------- #
    def _pending_run(
        self,
        run_start: float,
        request: Request,
        messages: list[dict[str, Any]],
        tool_calls: list[dict[str, Any]],
        turns: int,
        usage: dict[str, int],
    ) -> RunResult:
        """A run halted because a tool suspended and no ``wait_for`` was configured.
        Emits the terminal ``run`` event and returns status="pending". Mirrors JS
        ``pendingRun``."""
        self._emit(
            {
                "event": "run",
                "model": self.model,
                "turns": turns,
                "tool_calls": len(tool_calls),
                "total_tokens": usage["total_tokens"],
                "ms": _ms_since(run_start),
            }
        )
        return RunResult(
            text=request.prompt,
            messages=messages,
            tool_calls=tool_calls,
            tool_call_count=len(tool_calls),
            turns=turns,
            usage=usage,
            model=self.model,
            status="pending",
            pending=request,
        )

    async def _resolve_pending(
        self,
        toolkit: Toolkit,
        name: str,
        args: dict[str, Any],
        request: Request,
        call_id: Optional[str],
        turn: int,
    ) -> tuple[ToolResult, Optional[Request]]:
        """Resolve a suspended tool result (§10). Calls ``wait_for(request)``, and on
        ``ok`` re-executes the SAME tool once with ``ctx.answer`` set. Returns
        ``(result, halted)`` — ``halted`` is the request when no ``wait_for`` is
        configured (the run should stop and surface it). Mirrors JS ``resolvePending``.
        """
        if self.wait_for is None:
            return ToolResult(output=request.prompt, is_error=True), request
        r = self.wait_for(request)
        if inspect.isawaitable(r):
            r = await r
        answer: Optional[Answer] = r
        if answer is None or not answer.ok:
            return ToolResult(output=f"declined/expired: {request.prompt}", is_error=True), None
        t0 = _now()
        result = await toolkit.execute(name, args, ToolContext(answer=answer))
        after = _get_hook(self.hooks, "after_tool")
        if after is not None:
            ov = await _call_hook(
                after, {"name": name, "args": args, "result": result, "id": call_id, "turn": turn}
            )
            if ov and ov.get("result") is not None:
                result = ov["result"]
        source = toolkit.get(name).source if toolkit.get(name) is not None else "custom"
        self._emit_tool(name, source, result, t0)
        if pending_of(result) is not None:
            # never loop forever on the same request
            result = ToolResult(output=f"unresolved: {request.prompt}", is_error=True)
        return result, None

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

    @staticmethod
    async def _fetch_once(
        worker,
        url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        per_req: float,
        cancel: Optional[asyncio.Event],
    ):
        """One transport call in a worker thread, raced against the cancel token.

        Cooperative-cancel contract (SPEC §7D, python row): urllib offers no way to
        abort a blocked request, so when ``cancel`` fires mid-request the *run*
        aborts immediately (:class:`RunCancelled`) while the worker thread finishes
        in the background and its result is discarded. Observable outcome matches
        the mid-request-abort ports; only the background thread's lifetime differs.
        """
        if cancel is None:
            return await asyncio.to_thread(worker, url, headers, payload, per_req)
        fetch = asyncio.ensure_future(asyncio.to_thread(worker, url, headers, payload, per_req))
        watcher = asyncio.ensure_future(cancel.wait())
        try:
            done, _ = await asyncio.wait({fetch, watcher}, return_when=asyncio.FIRST_COMPLETED)
            if fetch in done:
                return fetch.result()
            # Cancel fired first: abandon the in-flight request (see docstring).
            # Retrieve any eventual exception so it is never logged as unhandled.
            fetch.add_done_callback(lambda t: None if t.cancelled() else t.exception())
            raise RunCancelled("run cancelled")
        finally:
            watcher.cancel()

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
        # Default classifier = today's behavior, expressed as an on_error. A "retry" is
        # always capped by the budget below (attempt == retries stops regardless).
        classify: ErrorClassifier = self.on_error or (
            lambda info: "retry" if info.get("retryable") else "fail"
        )
        last_err: Optional[Exception] = None
        for attempt in range(self.retries + 1):
            per_req = self._remaining(deadline, cancel)
            try:
                return await self._fetch_once(worker, url, headers, payload, per_req, cancel)
            except _HttpError as e:
                last_err = e
                retryable = e.status in _RETRYABLE
                tier = classify({"status": e.status, "attempt": attempt, "retryable": retryable})
                if tier == "fail" or attempt == self.retries:
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
                tier = classify({"error": e, "attempt": attempt, "retryable": True})
                if tier == "fail" or attempt == self.retries:
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
        found = toolkit.get(name)
        source = found.source if found is not None else "custom"
        t0 = _now()
        before = _get_hook(self.hooks, "before_tool")
        if before is not None:
            ov = await _call_hook(before, {"name": name, "args": a, "id": call_id, "turn": turn})
            if ov:
                if ov.get("result") is not None:
                    # short-circuit (deny / cache hit / dry-run, or a guard-raised suspension —
                    # path B) — real tool never runs, but it still counts as a tool call for
                    # metrics; a suspension is classified pending, never an error.
                    self._emit_tool(name, source, ov["result"], t0)
                    return a, ov["result"]
                if ov.get("args") is not None:
                    a = ov["args"]
        result = await toolkit.execute(name, a)
        # A suspension (§10) is not a real result: skip after_tool's failure path on it — the
        # resolved result (post-wait_for) still flows through after_tool in _resolve_pending — and
        # never count it as a tool error.
        after = _get_hook(self.hooks, "after_tool")
        if after is not None and pending_of(result) is None:
            ov = await _call_hook(
                after, {"name": name, "args": a, "result": result, "id": call_id, "turn": turn}
            )
            if ov and ov.get("result") is not None:
                result = ov["result"]
        self._emit_tool(name, source, result, t0)
        return a, result

    def _emit_tool(self, name: str, source: str, result: ToolResult, t0: float) -> None:
        """Emit the ``tool`` metric, classifying a §10 suspension as ``pending``
        (``is_error`` False), never a failure. Mirrors JS ``emitTool``."""
        req = pending_of(result)
        ev: dict[str, Any] = {
            "event": "tool",
            "tool": name,
            "source": source,
            "is_error": False if req is not None else result.is_error,
            "ms": _ms_since(t0),
        }
        if req is not None:
            ev["pending"] = True
        self._emit(ev)

    async def _llm_call_json(
        self,
        url: str,
        headers: dict[str, str],
        payload: dict[str, Any],
        deadline: Optional[float],
        cancel: Optional[asyncio.Event],
        style: ClientStyle,
    ) -> dict[str, Any]:
        """One non-streaming LLM call, with an ``llm`` metric event (ok/error + per-call
        tokens + ms). Mirrors JS ``llmCallJson``."""
        t0 = _now()
        try:
            data = await self._llm_fetch(self._transport.post, url, headers, payload, deadline, cancel)
            prompt, completion = _per_call(data.get("usage"), style)
            self._emit({"event": "llm", "model": self.model, "status": "ok", "ms": _ms_since(t0), "prompt_tokens": prompt, "completion_tokens": completion})
            return data
        except Exception:
            self._emit({"event": "llm", "model": self.model, "status": "error", "ms": _ms_since(t0), "prompt_tokens": 0, "completion_tokens": 0})
            raise

    async def run(
        self,
        prompt: str,
        toolkit: Toolkit,
        history: Optional[list[dict[str, Any]]] = None,
        cancel: Optional[asyncio.Event] = None,
    ) -> RunResult:
        """Run the agent loop. ``history`` continues a prior transcript (the system
        prompt is NOT re-added); ``cancel`` is an optional external abort token.

        Cancellation contract (SPEC §7D, python row — cooperative cancel):
        ``cancel`` is an :class:`asyncio.Event`. Guaranteed observation points are
        *between attempts*: before each LLM attempt, during retry backoff, and
        between streamed SSE events. Additionally, transport-level abort where the
        stack allows: the in-flight worker-thread request is raced against the
        token (a fired cancel aborts the run immediately; the blocked ``urllib``
        call itself cannot be interrupted, so its thread finishes in the background
        and the result is discarded), and a live streaming response is closed. A
        fired cancel raises :class:`RunCancelled` and is never retried. Only abort
        *latency* differs from the mid-request-abort ports (JS/Go/C#/Elixir); the
        observable outcome is identical.
        """
        if self.style == "anthropic":
            return await self._run_anthropic(prompt, toolkit, history, cancel)
        return await self._run_openai(prompt, toolkit, history, cancel)

    async def ask(
        self,
        prompt: str,
        toolkit: Toolkit,
        *,
        id: Optional[str] = None,  # noqa: A002 — mirrors JS `ask(prompt, { id })`
        on_text: Optional[Callable[[str], Any]] = None,
        cancel: Optional[asyncio.Event] = None,
    ) -> RunResult:
        """Stateful ask. With an ``id``, the client's ``store`` remembers the
        conversation: it loads that id's transcript, runs, saves the updated
        transcript, and returns the answer — so the next ``ask`` with the same
        ``id`` continues it. Without an ``id`` it is a stateless one-shot
        (identical to :meth:`run`). Mirrors the JS ``ask``.

        With ``on_text`` (a sync- or async-callable ``(delta: str) -> None``), the
        streaming loop runs and each assistant text delta is forwarded to it; ``ask``
        still returns the final :class:`RunResult`. Memory (id load/save) is handled by
        :meth:`stream`, so there is no duplication. Omit it ⇒ the non-streaming path.

        ``cancel`` follows the cooperative-cancel contract documented on :meth:`run`
        (SPEC §7D python row): between-attempts minimum, plus transport-level abort
        where urllib allows; a fired cancel raises :class:`RunCancelled`.
        """
        if on_text is not None:
            result: Optional[RunResult] = None
            async for ev in self.stream(prompt, toolkit, id=id, cancel=cancel):
                if ev["type"] == "text":
                    r = on_text(ev["delta"])
                    if inspect.isawaitable(r):
                        await r
                elif ev["type"] == "done":
                    result = ev["result"]
            assert result is not None
            return result
        if not id:
            return await self.run(prompt, toolkit, cancel=cancel)
        history = await self.store.get(id) or []
        result = await self.run(prompt, toolkit, history=history, cancel=cancel)
        await self.store.save(id, result.messages)
        return result

    def conversation(self, toolkit: Toolkit, cancel: Optional[asyncio.Event] = None) -> "Conversation":
        """A stateful multi-turn conversation that retains history across sends."""
        return Conversation(self, toolkit, cancel)

    async def stream(
        self,
        prompt: str,
        toolkit: Toolkit,
        *,
        id: Optional[str] = None,  # noqa: A002 — mirrors JS `stream(prompt, { id })`
        cancel: Optional[asyncio.Event] = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
        """Streaming variant: async-iterate live event dicts (text deltas,
        tool_call/tool_result, usage, done). With an ``id`` it is stateful (like
        :meth:`ask`): the thread's transcript is loaded as history before streaming,
        and saved back to the ConversationStore on the terminal ``done`` event.
        No ``id`` ⇒ stateless. Mirrors the JS ``stream``.
        """
        history = (await self.store.get(id) or []) if id else None
        if self.style == "anthropic":
            gen = self._stream_anthropic(prompt, toolkit, cancel, history)
        else:
            gen = self._stream_openai(prompt, toolkit, cancel, history)
        async for ev in gen:
            if ev["type"] == "done" and id:
                await self.store.save(id, ev["result"].messages)
            yield ev

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
        run_start = _now()

        try:
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
                payload: dict[str, Any] = {
                    "model": self.model,
                    "messages": messages,
                }
                # Gap 5: omit tools/tool_choice entirely when the effective list is empty
                # (including after a before_llm override) — many providers 400 on [].
                if tools:
                    payload["tools"] = tools
                    payload["tool_choice"] = "auto"
                payload = self._finalize_body(payload)
                data = await self._llm_call_json(url, req_headers, payload, deadline, cancel, "openai")
                _add_usage(usage, data.get("usage"), "openai")
                after = _get_hook(self.hooks, "after_llm")
                if after is not None:
                    await _call_hook(after, {"response": data, "model": self.model, "turn": turn})
                msg = data["choices"][0]["message"]
                messages.append(msg)
                calls = msg.get("tool_calls") or []
                if not calls:
                    return self._end_run(
                        run_start, msg.get("content") or "", messages, tool_calls, turns, usage
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
                # Record in tool-call order; on the FIRST durable halt, surface it and stop —
                # deterministic, and the later suspensions' placeholder results never enter the
                # transcript (they re-suspend on resume). Mirrors the streaming path.
                for (call_id, name, _orig_args), (args, result) in zip(parsed, results):
                    halted: Optional[Request] = None
                    req = pending_of(result)
                    if req is not None:
                        result, halted = await self._resolve_pending(toolkit, name, args, req, call_id, turn)
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
                    if halted is not None:
                        return self._pending_run(run_start, halted, messages, tool_calls, turns, usage)

            return self._end_run(
                run_start, _last_text(messages), messages, tool_calls, turns, usage, at_limit=True
            )
        except Exception as e:
            self._emit_run_error(run_start, tool_calls, turns, usage, e)
            raise

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
        run_start = _now()

        try:
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
                payload: dict[str, Any] = {
                    "model": self.model,
                    "max_tokens": 4096,
                    "system": system,
                    "messages": messages,
                }
                # Gap 5: omit tools when the effective list is empty.
                if tools:
                    payload["tools"] = tools
                payload = self._finalize_body(payload)
                data = await self._llm_call_json(endpoint, req_headers, payload, deadline, cancel, "anthropic")
                _add_usage(usage, data.get("usage"), "anthropic")
                after = _get_hook(self.hooks, "after_llm")
                if after is not None:
                    await _call_hook(after, {"response": data, "model": self.model, "turn": turn})
                content = data.get("content") or []
                messages.append({"role": "assistant", "content": content})
                uses = [b for b in content if b.get("type") == "tool_use"]
                if not uses:
                    text = "".join(b.get("text", "") for b in content if b.get("type") == "text")
                    return self._end_run(run_start, text, messages, tool_calls, turns, usage)

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
                # Record in tool-call order; on the FIRST durable halt, include that tool_result
                # block, stop building the content, and surface it — deterministic (G3), later
                # suspensions re-suspend on resume. Mirrors the OpenAI path and the streaming loops.
                results: list[dict[str, Any]] = []
                halted: Optional[Request] = None
                for use, (args, result) in zip(uses, outputs):
                    req = pending_of(result)
                    if req is not None:
                        result, halted = await self._resolve_pending(toolkit, use["name"], args, req, use.get("id"), turn)
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
                    if halted is not None:
                        break
                messages.append({"role": "user", "content": results})
                if halted is not None:
                    return self._pending_run(run_start, halted, messages, tool_calls, turns, usage)

            return self._end_run(run_start, "", messages, tool_calls, turns, usage, at_limit=True)
        except Exception as e:
            self._emit_run_error(run_start, tool_calls, turns, usage, e)
            raise

    # ----------------------------------------------------------------------- #
    # Streaming: OpenAI-style
    # ----------------------------------------------------------------------- #
    async def _stream_openai(
        self,
        prompt: str,
        toolkit: Toolkit,
        cancel: Optional[asyncio.Event],
        history: Optional[list[dict[str, Any]]] = None,
    ) -> AsyncGenerator[dict[str, Any], None]:
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
        run_start = _now()

        try:
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
                payload: dict[str, Any] = {
                    "model": self.model,
                    "messages": messages,
                    "stream": True,
                    "stream_options": {"include_usage": True},
                }
                # Gap 5: omit tools/tool_choice when the effective list is empty.
                if tools:
                    payload["tools"] = tools
                    payload["tool_choice"] = "auto"
                payload = self._finalize_body(payload)

                t0 = _now()
                before_p, before_c = usage["prompt_tokens"], usage["completion_tokens"]
                content = ""
                acc: dict[int, dict[str, str]] = {}
                try:
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
                except Exception:
                    self._emit({"event": "llm", "model": self.model, "status": "error", "ms": _ms_since(t0), "prompt_tokens": 0, "completion_tokens": 0})
                    raise
                self._emit({"event": "llm", "model": self.model, "status": "ok", "ms": _ms_since(t0), "prompt_tokens": usage["prompt_tokens"] - before_p, "completion_tokens": usage["completion_tokens"] - before_c})

                after = _get_hook(self.hooks, "after_llm")
                if after is not None:
                    await _call_hook(
                        after, {"response": {"streamed": True, "usage": usage}, "model": self.model, "turn": turn}
                    )

                calls = [acc[i] for i in sorted(acc)]
                if not calls:
                    messages.append({"role": "assistant", "content": content})
                    yield {"type": "usage", "usage": usage}
                    yield {"type": "done", "result": self._end_run(run_start, content, messages, tool_calls, turns, usage)}
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
                    req = pending_of(result)
                    if req is not None:
                        yield {"type": "pending", "request": req}  # surface BEFORE wait_for so a channel can push the link
                        result, halted = await self._resolve_pending(toolkit, c["name"], args, req, c["id"], turn)
                        if halted is not None:
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
                            yield {"type": "done", "result": self._pending_run(run_start, halted, messages, tool_calls, turns, usage)}
                            return
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

            yield {"type": "done", "result": self._end_run(run_start, _last_text(messages), messages, tool_calls, turns, usage, at_limit=True)}
        except Exception as e:
            self._emit_run_error(run_start, tool_calls, turns, usage, e)
            raise

    # ----------------------------------------------------------------------- #
    # Streaming: Anthropic-style
    # ----------------------------------------------------------------------- #
    async def _stream_anthropic(
        self,
        prompt: str,
        toolkit: Toolkit,
        cancel: Optional[asyncio.Event],
        history: Optional[list[dict[str, Any]]] = None,
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
        if history:
            messages: list[dict[str, Any]] = list(history) + [{"role": "user", "content": prompt}]
        else:
            messages = [{"role": "user", "content": prompt}]
        tools = toolkit.to_anthropic()
        tool_calls: list[dict[str, Any]] = []
        usage = _empty_usage()
        turns = 0
        run_start = _now()

        try:
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
                payload: dict[str, Any] = {
                    "model": self.model,
                    "max_tokens": 4096,
                    "system": system,
                    "messages": messages,
                    "stream": True,
                }
                # Gap 5: omit tools when the effective list is empty.
                if tools:
                    payload["tools"] = tools
                payload = self._finalize_body(payload)

                t0 = _now()
                before_p, before_c = usage["prompt_tokens"], usage["completion_tokens"]
                blocks: dict[int, dict[str, Any]] = {}
                stop_reason = ""
                try:
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
                except Exception:
                    self._emit({"event": "llm", "model": self.model, "status": "error", "ms": _ms_since(t0), "prompt_tokens": 0, "completion_tokens": 0})
                    raise
                self._emit({"event": "llm", "model": self.model, "status": "ok", "ms": _ms_since(t0), "prompt_tokens": usage["prompt_tokens"] - before_p, "completion_tokens": usage["completion_tokens"] - before_c})

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
                    yield {"type": "done", "result": self._end_run(run_start, text, messages, tool_calls, turns, usage)}
                    return

                for u in uses:
                    yield {"type": "tool_call", "id": u["id"], "name": u["name"], "args": u["input"]}
                settled = await asyncio.gather(
                    *(self._run_tool(toolkit, u["name"], u.get("input") or {}, u["id"], turn) for u in uses)
                )
                results: list[dict[str, Any]] = []
                halted: Optional[Request] = None
                for u, (args, result) in zip(uses, settled):
                    req = pending_of(result)
                    if req is not None:
                        yield {"type": "pending", "request": req}  # surface BEFORE wait_for
                        result, halted = await self._resolve_pending(toolkit, u["name"], args, req, u["id"], turn)
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
                    if halted is not None:
                        messages.append({"role": "user", "content": results})
                        yield {"type": "done", "result": self._pending_run(run_start, halted, messages, tool_calls, turns, usage)}
                        return
                messages.append({"role": "user", "content": results})

            yield {"type": "done", "result": self._end_run(run_start, "", messages, tool_calls, turns, usage, at_limit=True)}
        except Exception as e:
            self._emit_run_error(run_start, tool_calls, turns, usage, e)
            raise

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
        resp = await self._llm_fetch(self._transport.open, url, headers, payload, deadline, cancel)

        queue: asyncio.Queue = asyncio.Queue(maxsize=256)
        _SENTINEL = object()
        stop = threading.Event()

        # Transport-level abort (SPEC §7D python row): once the response is live we DO
        # hold something closeable, so an external cancel closes the streaming response
        # — the reader thread unblocks and the loop below raises RunCancelled.
        canceller: Optional[asyncio.Task] = None
        if cancel is not None:

            async def _abort_on_cancel() -> None:
                await cancel.wait()
                try:
                    resp.close()
                except Exception:
                    pass

            canceller = asyncio.ensure_future(_abort_on_cancel())

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
            if canceller is not None:
                canceller.cancel()
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
    store: Optional[ConversationStore] = None,
    on_metric: Optional[OnMetric] = None,
    wait_for: Optional[WaitFor] = None,
    request_params: Optional[dict[str, Any]] = None,
    body_transform: Optional[Callable[[dict[str, Any]], Optional[dict[str, Any]]]] = None,
    http_transport: Optional[HttpTransport] = None,
    on_error: Optional[ErrorClassifier] = None,
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
        store=store,
        on_metric=on_metric,
        wait_for=wait_for,
        request_params=request_params,
        body_transform=body_transform,
        http_transport=http_transport,
        on_error=on_error,
    )
