"""ADR 0024 — the in-process seam stops at the top-level client, ported to Python.

``InProcessTransport`` (public, ``toolnexus.InProcessTransport``) is the ONE
adapter that turns a semantic ``generate(request) -> {...}`` function into the
shipped ``HttpTransport`` seam. ``create_in_process_client`` has always built one
internally; this proves ``AgentRuntime(in_process=...)`` calls the SAME exported
class rather than duplicating the request/response assembly — and that the
runtime's existing global turn gate (``max_concurrent_turns``) still applies on
this path, with a negative control so the gate assertion isn't a tautology.

Mirrors ``golang/agents/inprocess_test.go``.
"""
from __future__ import annotations

import threading
import time

from toolnexus import InProcessTransport, create_in_process_client, create_toolkit
from toolnexus.agents import AgentDef, AgentRuntime, Handle


def _spawn(rt: AgentRuntime, name: str) -> Handle:
    h = rt.spawn(rt.root, name)
    assert isinstance(h, Handle), getattr(h, "error", h)
    return h


# --------------------------------------------------------------------------- #
# 1. ONE generate function serves both the top-level client and the runtime —
#    no copied adapter code.
# --------------------------------------------------------------------------- #
async def test_one_generate_serves_client_and_runtime_via_shared_adapter():
    calls: list[dict] = []

    def generate(request: dict) -> dict:
        calls.append(request)
        return {"content": "hello from in-process"}

    # Top-level client.
    tk = await create_toolkit(builtins=False)
    client = create_in_process_client(model="m-shared", generate=generate)
    r1 = await client.run("hi", toolkit=tk)
    assert r1.text == "hello from in-process"
    assert r1.status == "done"

    # Sub-agent runtime — the SAME generate function, via `in_process=`.
    rt = AgentRuntime(
        registry={"echo": AgentDef(name="echo", description="echoes", system_prompt="", model="m-shared")},
        in_process=generate,
    )
    h = _spawn(rt, "echo")
    rt.wake(h, "hi")
    r2 = await rt.wait(h)
    assert r2.text == "hello from in-process"
    assert r2.status == "done"

    # Both went through the one generate function.
    assert len(calls) == 2

    # The runtime built its transport via the SAME exported class
    # `create_in_process_client` uses internally — zero duplicated logic.
    assert isinstance(rt._inner_transport, InProcessTransport)


# --------------------------------------------------------------------------- #
# 2. Construction-time mutual exclusion, loud, never resolved by precedence.
# --------------------------------------------------------------------------- #
def test_transport_and_in_process_are_mutually_exclusive():
    class _NoopTransport:
        def post(self, url, headers, payload, timeout):  # noqa: A002
            return {"choices": [{"message": {"role": "assistant", "content": "x"}}], "usage": {}}

        def open(self, url, headers, payload, timeout):  # noqa: A003
            raise NotImplementedError

    try:
        AgentRuntime(
            registry={"echo": AgentDef(name="echo", description="d", system_prompt="", model="m")},
            transport=_NoopTransport(),
            in_process=lambda req: {"content": "x"},
        )
        assert False, "expected ValueError"
    except ValueError as e:
        assert "mutually exclusive" in str(e)


def test_llm_and_in_process_are_mutually_exclusive():
    try:
        AgentRuntime(
            registry={"echo": AgentDef(name="echo", description="d", system_prompt="", model="m")},
            llm={"base_url": "http://example.invalid", "style": "openai", "model": "m", "api_key": "k"},
            in_process=lambda req: {"content": "x"},
        )
        assert False, "expected ValueError"
    except ValueError as e:
        assert "mutually exclusive" in str(e)


def test_in_process_alone_constructs_fine():
    rt = AgentRuntime(
        registry={"echo": AgentDef(name="echo", description="d", system_prompt="", model="m")},
        in_process=lambda req: {"content": "x"},
    )
    assert isinstance(rt._inner_transport, InProcessTransport)


# --------------------------------------------------------------------------- #
# 3. The global turn gate (max_concurrent_turns) still applies on the
#    in-process path — WITH a negative control proving the detector isn't a
#    tautology.
# --------------------------------------------------------------------------- #
class _InFlightCounter:
    """A thread-safe in-flight counter, independent of the runtime's own
    ``max_observed_concurrent_turns`` bookkeeping — this is the external witness."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._current = 0
        self.max_observed = 0

    def enter(self) -> None:
        with self._lock:
            self._current += 1
            self.max_observed = max(self.max_observed, self._current)

    def leave(self) -> None:
        with self._lock:
            self._current -= 1


def _gate_registry() -> dict[str, AgentDef]:
    n_workers = 5
    return {
        "coordinator": AgentDef(
            name="coordinator",
            description="fans out N workers in one turn",
            system_prompt="",
            model="m-gate-coordinator",
            task_targets=["worker"],
        ),
        "worker": AgentDef(name="worker", description="does one LLM call", system_prompt="", model="m-gate-worker"),
    }, n_workers


def _gate_generate(counter: _InFlightCounter):
    n_workers = 5

    def generate(request: dict) -> dict:
        model = request.get("model")
        msgs = request.get("messages") or []
        tool_msgs = [m for m in msgs if m.get("role") == "tool"]
        if model == "m-gate-coordinator":
            if not tool_msgs:
                return {
                    "tool_calls": [
                        {"id": f"c{i}", "name": "task", "arguments": {"agent": "worker", "prompt": f"job {i}"}}
                        for i in range(n_workers)
                    ]
                }
            return {"content": "coordinator done"}
        if model == "m-gate-worker":
            # The scripted model itself is the witness: increment while "in
            # flight", sleep to widen the overlap window, decrement.
            counter.enter()
            try:
                time.sleep(0.03)
            finally:
                counter.leave()
            return {"content": "worker done"}
        return {"content": "ok"}

    return generate


async def test_gate_serializes_in_process_calls_when_max_concurrent_turns_is_1():
    counter = _InFlightCounter()
    registry, _n_workers = _gate_registry()
    rt = AgentRuntime(registry=registry, in_process=_gate_generate(counter), max_concurrent_turns=1)
    coord = _spawn(rt, "coordinator")
    rt.wake(coord, "go")
    r = await rt.wait(coord)
    assert r.status == "done", r
    assert counter.max_observed == 1, "max_concurrent_turns=1 must serialize every LLM call, including in-process"
    assert rt.max_observed_concurrent_turns == 1


async def test_gate_allows_overlap_negative_control_when_max_concurrent_turns_is_high():
    """Same scripted model, same fan-out — only the gate width changes. If this
    negative control did NOT show overlap, the detector above would be a
    tautology rather than real evidence of serialization."""
    counter = _InFlightCounter()
    registry, n_workers = _gate_registry()
    rt = AgentRuntime(registry=registry, in_process=_gate_generate(counter), max_concurrent_turns=n_workers)
    coord = _spawn(rt, "coordinator")
    rt.wake(coord, "go")
    r = await rt.wait(coord)
    assert r.status == "done", r
    assert counter.max_observed >= 2, "with headroom in the gate, concurrent in-process calls must overlap"
    assert rt.max_observed_concurrent_turns >= 2
