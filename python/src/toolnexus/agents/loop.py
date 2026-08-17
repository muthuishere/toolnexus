"""Loop — a live execution of an Agent, and the completion gate that stops it
claiming ``done`` too early. A layer over the shipped §8 client: nothing here
changes existing behaviour.

The placement law this encodes::

    Agent (the harness) answers "MAY it?"       — capability, ceilings.  Per problem.
    RunOptions          answers "with WHAT?"    — model for this call.   Per call.
    Loop                answers "DID it?"       — status, turns.         Observed.
    none of them        answers "is it RIGHT?"  — a tool, skill or agent.

So ``Loop`` takes no options: it is read, not configured.
"""
from __future__ import annotations

import inspect
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Union

__all__ = [
    "Completion",
    "Guardrail",
    "Loop",
    "Outcome",
    "Verdict",
    "all_todos_done",
    "guarded_hooks",
    "harness",
    "run_gated",
]

# A POLICY check on a tool call — "may it?", never "is it right?". Return
# ``"allow"`` (or None) to permit; any other string DENIES with that reason.
Guardrail = Callable[[Any], Optional[str]]


@dataclass
class Verdict:
    """What a completion verifier returns."""

    ok: bool
    reason: str = ""


@dataclass
class Completion:
    """The gate that stops an agent claiming ``done`` before its work verifies."""

    # Judges the run. Receives the tool calls ACCUMULATED across attempts.
    verify: Callable[[Any], Union[Verdict, Awaitable[Verdict]]]
    # REQUIRED. An unbounded verify loop is a denial-of-service on the caller's bill.
    max_attempts: int = 0


@dataclass
class Outcome:
    """What a run reports. ``status`` reuses the SHIPPED vocabulary — no new status
    strings are minted (SPEC.md pins TaskStatus identical across ports)."""

    text: str = ""
    status: str = "done"  # done | incomplete | pending | error
    # Named whenever ``status`` is not ``done`` — never a silent stop.
    stopped_by: str = ""
    attempts: int = 0
    turns: int = 0
    result: Any = None


@dataclass
class RunOptions:
    """What varies PER CALL. ``model`` is here — not on the agent's default and not
    on the Loop — so the same conversation may change model between turns."""

    model: str = ""


def harness(**spec: Any) -> dict[str, Any]:
    """``harness`` is a NAME, not a type.

    An agent spec already IS the harness — tools, soul, team, budget, model,
    policy, ceilings — so this is the word landing in the API without a second
    concept to learn. ``agent("x", **harness(does="..."))`` and
    ``agent("x", does="...")`` are indistinguishable.
    """
    return spec


def guarded_hooks(guardrails: Optional[list[Guardrail]], hooks: Any) -> Any:
    """Compile guardrails into one ``before_tool`` with FIRST-DENY-WINS, composed
    ahead of any hook already set. No guardrails ⇒ ``hooks`` is returned untouched,
    so absent is byte-identical."""
    if not guardrails:
        return hooks

    prior = None
    base: dict[str, Any] = {}
    if hooks:
        base = dict(hooks) if isinstance(hooks, dict) else dict(getattr(hooks, "__dict__", {}))
        prior = base.get("before_tool")

    async def before_tool(ev: Any) -> Any:
        for rail in guardrails:
            verdict = rail(ev)
            if verdict and verdict != "allow":
                return {"result": {"output": f"denied: {verdict}", "is_error": True}}
        if prior is None:
            return None
        out = prior(ev)
        return await out if inspect.isawaitable(out) else out

    base["before_tool"] = before_tool
    return base


def _tool_calls(result: Any) -> list[Any]:
    calls = getattr(result, "tool_calls", None)
    if calls is None and isinstance(result, dict):
        calls = result.get("tool_calls")
    return list(calls or [])


def _field(obj: Any, name: str, default: Any = None) -> Any:
    if isinstance(obj, dict):
        return obj.get(name, default)
    return getattr(obj, name, default)


def all_todos_done(result: Any) -> Verdict:
    """The built-in completion verifier. Reads the SHIPPED ``todowrite`` builtin's
    result metadata and requires every item to be checked.

    Structural, not domain: it counts unchecked boxes and never learns what a todo
    means, so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒
    pass, so the gate never punishes an agent that does not use the builtin.
    """
    for call in reversed(_tool_calls(result)):
        if _field(call, "name") != "todowrite":
            continue
        metadata = _field(call, "metadata") or {}
        todos = metadata.get("todos") if isinstance(metadata, dict) else None
        if not isinstance(todos, list):
            return Verdict(ok=True)
        open_items = [str(_field(t, "text", "")) for t in todos if not _field(t, "completed")]
        if open_items:
            return Verdict(ok=False, reason=f"{len(open_items)} item(s) still open: {'; '.join(open_items)}")
        return Verdict(ok=True)
    return Verdict(ok=True)


async def run_gated(ask: Callable[[str], Any], prompt: str, completion: Optional[Completion]) -> Any:
    """Wrap a client run with the completion gate. SHARED by the standalone Loop and
    the §7D runtime turn, so a delegated child gets exactly the same guarantee as a
    directly-driven one.

    Rule 2 in force: a run that is ``pending`` (suspended on a human) or otherwise
    non-done already carries its own reason, so the gate never re-judges it. That
    keeps ``pending`` and ``incomplete`` distinct — the caller can always tell
    whether it owes an Answer or a fix.
    """
    if completion is None:
        return await ask(prompt)

    max_attempts = completion.max_attempts
    if not isinstance(max_attempts, int) or max_attempts < 1:
        raise ValueError("toolnexus: Completion.max_attempts must be an integer >= 1")
    if completion.verify is None:
        raise ValueError("toolnexus: Completion.verify is required")

    accumulated: list[Any] = []
    last: Any = None
    reason = ""
    for attempt in range(1, max_attempts + 1):
        text = prompt if attempt == 1 else f"Your work did not verify: {reason}. Fix it and finish."
        result = await ask(text)
        # The gate judges the ACCUMULATED work, so an agent cannot escape it by
        # declining to re-declare its plan on a retry.
        accumulated.extend(_tool_calls(result))
        try:
            result.tool_calls = list(accumulated)
        except (AttributeError, TypeError):  # pragma: no cover - defensive
            pass
        last = result

        status = _field(result, "status") or ""
        if status and status != "done":
            # The run stopped for its own reason (suspension, budget). If the gate
            # was mid-retry the caller must learn BOTH — otherwise a budget stop
            # masks the verification failure and they never see why it was looping.
            if reason and status != "pending":
                try:
                    result.text = (
                        f"{_field(result, 'text', '')} "
                        f"[while verifying: attempt {attempt} last failed: {reason}]"
                    )
                except (AttributeError, TypeError):  # pragma: no cover - defensive
                    pass
            return result

        verdict = completion.verify(result)
        if inspect.isawaitable(verdict):
            verdict = await verdict
        if verdict.ok:
            return result
        reason = verdict.reason or "unspecified"

    # Structured, not prose: ``limit`` is how a caller (and the §7D runtime) tells
    # WHICH limit stopped the run. ``text`` carries the human reason.
    last.status = "incomplete"
    last.limit = "completion"
    last.text = f"completion.verify failed {max_attempts}x: {reason}"
    return last


class Loop:
    """A live execution of an Agent. Its only verbs are ``run`` and reading state."""

    def __init__(self, agent: Any, options: dict[str, Any], toolkit: Any) -> None:
        self._agent = agent
        # CLIENT OPTIONS rather than a built client, because a per-call ``model``
        # override must be able to change the model — fixed at construction time.
        self._options = dict(options)
        self._toolkit = toolkit
        self._status = "idle"
        self._turns = 0

    @property
    def status(self) -> str:
        """Observed, never set by the caller."""
        return self._status

    @property
    def turns(self) -> int:
        """Model round trips this loop has spent."""
        return self._turns

    async def run(self, prompt: str, model: str = "") -> Outcome:
        from ..client import create_client  # local import: avoids a cycle

        completion = getattr(self._agent, "completion", None)
        client = create_client(**self._client_options(model))
        self._status = "running"

        state = {"attempts": 0, "history": []}

        async def ask(text: str) -> Any:
            state["attempts"] += 1
            result = await client.run(text, toolkit=self._toolkit, history=state["history"])
            self._turns += getattr(result, "turns", 0) or 0
            state["history"] = getattr(result, "messages", []) or []
            return result

        try:
            result = await run_gated(ask, prompt, completion)
        except Exception:
            self._status = "error"
            raise

        status = _field(result, "status") or "done"
        if status != "done":
            self._status = status
            stopped_by = (
                _field(result, "text", "")
                if _field(result, "limit") == "completion"
                else f"run reported {status}"
            )
            return Outcome(text=_field(result, "text", ""), status=status, stopped_by=stopped_by,
                           attempts=state["attempts"], turns=self._turns, result=result)

        self._status = "idle"
        return Outcome(text=_field(result, "text", ""), status="done",
                       attempts=state["attempts"], turns=self._turns, result=result)

    def _client_options(self, model: str) -> dict[str, Any]:
        """Apply a per-call model override via ``request_params`` (``model`` is not in
        the forbidden set — the client forbids only messages/tools/stream)."""
        opts = dict(self._options)
        soul = getattr(self._agent, "soul", None)
        if soul and not opts.get("system_prompt"):
            opts["system_prompt"] = soul
        opts["hooks"] = guarded_hooks(
            getattr(self._agent, "guardrails", None),
            getattr(self._agent, "hooks", None) or opts.get("hooks"),
        )
        if model:
            params = dict(opts.get("request_params") or {})
            params["model"] = model
            opts["request_params"] = params
        return opts
