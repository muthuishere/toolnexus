"""Judge batteries (SPEC §8B "Batteries", change add-judge-batteries) against the shared
fixtures in examples/judge/batteries/, plus the hook tests mirroring golang's, plus the
beforeLLM per-turn ``model`` override. No network, no live LLM."""
from __future__ import annotations

import dataclasses
import json
from pathlib import Path
from typing import Any

import pytest

import toolnexus.client as client_mod
from toolnexus import (
    AgentRouterClassifier,
    Answer,
    Bands,
    ClassifierError,
    ContentGuardBlocked,
    ContentGuardClassifier,
    IsCompleteClassifier,
    ModelRouterClassifier,
    SkillRelevanceClassifier,
    ToolGuardClassifier,
    ToolRelevanceClassifier,
    ToolResult,
    ToolResultFilterClassifier,
    create_classifier,
    create_client,
    create_toolkit,
    define_tool,
    latest_user_text,
    question_from_wire,
    static_classifier,
)
from toolnexus.classifier import _decision_from_wire

FIX = Path(__file__).resolve().parents[2] / "examples" / "judge" / "batteries"


def _load(name: str) -> dict[str, Any]:
    return json.loads((FIX / name).read_text())


def _cases(name: str):
    return [pytest.param(name, c, id=f"{name}:{c['name']}") for c in _load(name)["cases"]]


ALL_CASES = [p for f in sorted(FIX.glob("*.json")) if f.name != "user-text-cases.json" for p in _cases(f.name)]


def _classifier_for(case: dict[str, Any]):
    if case.get("error"):
        async def boom(st, qs):
            raise ClassifierError("boom")
        return create_classifier(style="custom", evaluate=boom)
    calls = case.get("calls") or []
    if not calls:
        async def never(st, qs):
            pytest.fail("the battery must not call the classifier")
        return create_classifier(style="custom", evaluate=never)
    return static_classifier(*[
        (c["state"], {k: question_from_wire(v) for k, v in c["questions"].items()}, c["response"])
        for c in calls
    ])


def _opts(o: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for k, v in (o or {}).items():
        if k == "onError":
            out["on_error"] = v
        elif k == "bands":
            out["bands"] = Bands(low=v["low"], high=v["high"])
        elif k == "askAt":
            out["ask_at"] = v
        elif k == "denyAt":
            out["deny_at"] = v
        else:
            out[k] = v  # role, dimensions
    return out


async def _run_case(fname: str, case: dict[str, Any]):
    c, o, i = _classifier_for(case), _opts(case.get("options") or {}), case["input"]
    if fname == "tool-guard.json":
        return await ToolGuardClassifier(c, **o).check(i)
    if fname == "tool-relevance.json":
        return await ToolRelevanceClassifier(c, **o).select(i["prompt"], i["tools"])
    if fname == "skill-relevance.json":
        return await SkillRelevanceClassifier(c, **o).select(i["prompt"], i["skills"])
    if fname == "tool-result-filter.json":
        return await ToolResultFilterClassifier(c, **o).filter(i["query"], i["chunks"])
    if fname == "is-complete.json":
        return await IsCompleteClassifier(c, **o).check(i["task"], i["answer"])
    if fname == "agent-router.json":
        return await AgentRouterClassifier(c, **o).pick(i["task"], i["agents"], i["fallback"])
    if fname == "content-guard.json":
        return await ContentGuardClassifier(c, **o).check(i["text"])
    if fname == "model-router.json":
        return await ModelRouterClassifier(c, i["models"], **o).pick(i["prompt"], i["fallback"])
    raise AssertionError(f"no battery for {fname}")


@pytest.mark.asyncio
@pytest.mark.parametrize("fname,case", ALL_CASES)
async def test_battery_fixture(fname, case):
    got = dataclasses.asdict(await _run_case(fname, case))
    for k, want in case["want"].items():
        if k == "error":
            assert (got["error"] is not None) == want, f"error presence: {got['error']!r}"
        else:
            assert got[k] == want, f"{k}: got {got[k]!r} want {want!r}"


def test_every_fixture_file_is_covered():
    names = {f.name for f in FIX.glob("*.json")} - {"user-text-cases.json"}
    assert len(names) == 8 and len(ALL_CASES) >= 8


@pytest.mark.parametrize("case", _load("user-text-cases.json")["cases"], ids=lambda c: c["name"])
def test_latest_user_text(case):
    assert latest_user_text(case["messages"]) == case["want"]


def test_on_error_required():
    c = static_classifier()
    for build in (
        lambda: ToolGuardClassifier(c),
        lambda: ToolRelevanceClassifier(c),
        lambda: SkillRelevanceClassifier(c),
        lambda: ToolResultFilterClassifier(c, on_error="maybe"),
        lambda: IsCompleteClassifier(c),
        lambda: ContentGuardClassifier(c),
    ):
        with pytest.raises(ValueError, match="on_error"):
            build()
    # the routers take none: fallback is their error outcome
    AgentRouterClassifier(c)
    ModelRouterClassifier(c, [])


# --------------------------------------------------------------------------- #
# hooks
# --------------------------------------------------------------------------- #


def fixed(answers: dict[str, Any]):
    async def ev(st, qs):
        return _decision_from_wire({"model": "m", "answers": answers})
    return create_classifier(style="custom", evaluate=ev)


def fixed_err():
    async def ev(st, qs):
        raise ClassifierError("boom")
    return create_classifier(style="custom", evaluate=ev)


def risk(score: float):
    return fixed({"risk": {"type": "score", "score": score, "confidence": 0.9,
                           "probabilities": {"0": 0.25, "1": 0.25, "2": 0.25, "3": 0.25},
                           "legend": {"0": "a", "1": "b", "2": "c", "3": "d"}}})


async def _deploy_toolkit(runs: list[int], extra: bool = False):
    def deploy():
        """deploy"""
        runs.append(1)
        return "DEPLOYED"

    tools = [define_tool(deploy, name="deploy", description="deploy",
                         input_schema={"type": "object", "properties": {}})]
    if extra:
        def send_email():
            """email"""
            return "E"
        tools.append(define_tool(send_email, name="send_email", description="email",
                                 input_schema={"type": "object", "properties": {}}))
    return await create_toolkit(extra_tools=tools, builtins=False)


def _record(monkeypatch) -> list[dict[str, Any]]:
    """Scripted openai endpoint: turn 1 calls deploy (id c1), then "done". Records bodies."""
    bodies: list[dict[str, Any]] = []

    def fake_post(url, headers, payload, timeout):
        bodies.append(json.loads(json.dumps(payload)))
        if len(bodies) == 1:
            msg = {"role": "assistant", "content": None, "tool_calls": [
                {"id": "c1", "type": "function", "function": {"name": "deploy", "arguments": "{}"}}]}
        else:
            msg = {"role": "assistant", "content": "done"}
        return {"choices": [{"message": msg}]}

    monkeypatch.setattr(client_mod, "_post", fake_post)
    return bodies


def _client(hooks=None, wait_for=None):
    return create_client(base_url="http://x/v1", style="openai", model="configured", api_key="k",
                         hooks=hooks, wait_for=wait_for, max_turns=4)


@pytest.mark.asyncio
async def test_tool_guard_hook(monkeypatch):
    ran: list[int] = []

    async def nxt(ev):
        ran.append(1)
        return None

    # ask: halts pending with the guard's Request; the tool never runs, next not called.
    _record(monkeypatch)
    runs: list[int] = []
    g = ToolGuardClassifier(risk(1.8), on_error="closed")
    r = await _client({"before_tool": g.as_hook(nxt)}).run("ship it", await _deploy_toolkit(runs))
    assert r.status == "pending" and r.pending is not None
    assert r.pending.id == "toolguard:c1" and r.pending.kind == "approval"
    assert r.pending.prompt == "Approve the call to deploy? (medium risk)"
    assert r.pending.data == {"tool": "deploy", "arguments": {}, "reason": "medium risk", "risk": 1.8}
    assert not ran and not runs

    # deny: short-circuits.
    _record(monkeypatch)
    g = ToolGuardClassifier(risk(2.9), on_error="closed")
    r = await _client({"before_tool": g.as_hook(nxt)}).run("ship it", await _deploy_toolkit(runs))
    assert not ran and not runs
    assert len(r.tool_calls) == 1 and r.tool_calls[0]["output"] == "denied by tool guard: high risk"

    # allow: next runs and the tool runs.
    _record(monkeypatch)
    g = ToolGuardClassifier(risk(0.1), on_error="closed")
    r = await _client({"before_tool": g.as_hook(nxt)}).run("ship it", await _deploy_toolkit(runs))
    assert ran and r.tool_calls[0]["output"] == "DEPLOYED" and len(runs) == 1

    # approved through wait_for: the tool runs once, no re-ask.
    _record(monkeypatch)
    runs.clear()
    g = ToolGuardClassifier(risk(1.8), on_error="closed")

    async def approve(q):
        return Answer(id=q.id, ok=True)

    r = await _client({"before_tool": g.as_hook()}, wait_for=approve).run("ship it", await _deploy_toolkit(runs))
    assert r.status != "pending" and r.tool_calls[0]["output"] == "DEPLOYED" and len(runs) == 1


@pytest.mark.asyncio
async def test_before_llm_model_override_is_per_turn(monkeypatch):
    bodies = _record(monkeypatch)
    seen: list[str] = []

    def before(ev):
        return {"model": "small-fast"} if ev["turn"] == 0 else None

    def after(ev):
        seen.append(ev["model"])

    await _client({"before_llm": before, "after_llm": after}).run("go", await _deploy_toolkit([]))
    assert [b["model"] for b in bodies] == ["small-fast", "configured"]
    assert seen == ["small-fast", "configured"]


@pytest.mark.asyncio
async def test_before_llm_empty_model_is_verbatim(monkeypatch):
    bodies = _record(monkeypatch)
    await _client({"before_llm": lambda ev: {"model": ""}}).run("go", await _deploy_toolkit([]))
    assert [b["model"] for b in bodies] == ["configured", "configured"]


MODELS = [{"id": "small-fast", "description": "cheap"}, {"id": "large-reasoning", "description": "dear"}]
SURE = {"model": {"type": "choice", "choice": "small-fast", "confidence": 0.91,
                  "probabilities": {"small-fast": 0.91, "large-reasoning": 0.09}}}
UNSURE = {"model": {"type": "choice", "choice": "small-fast", "confidence": 0.6,
                    "probabilities": {"small-fast": 0.6, "large-reasoning": 0.4}}}


@pytest.mark.asyncio
async def test_model_router_hook(monkeypatch):
    for ans, want in ((SURE, "small-fast"), (UNSURE, "configured")):
        bodies = _record(monkeypatch)
        r = ModelRouterClassifier(fixed(ans), MODELS)
        await _client({"before_llm": r.as_hook()}).run("capital of France?", await _deploy_toolkit([]))
        assert bodies and all(b["model"] == want for b in bodies)

    # unsure, or sure of the configured model itself: no override at all.
    ev = {"model": "small-fast", "messages": [{"role": "user", "content": "x"}], "tools": [], "turn": 0}
    for ans in (UNSURE, SURE):
        assert await ModelRouterClassifier(fixed(ans), MODELS).as_hook()(ev) is None

    # no router: verbatim.
    bodies = _record(monkeypatch)
    await _client().run("x", await _deploy_toolkit([]))
    assert bodies[0]["model"] == "configured"

    # next's model wins; next sees the routed model.
    bodies = _record(monkeypatch)
    saw: list[str] = []

    def nxt(ev):
        saw.append(ev["model"])
        return {"model": "pinned"}

    r = ModelRouterClassifier(fixed(SURE), MODELS)
    await _client({"before_llm": r.as_hook(nxt)}).run("x", await _deploy_toolkit([]))
    assert saw[0] == "small-fast" and bodies[0]["model"] == "pinned"


@pytest.mark.asyncio
async def test_tool_relevance_hook(monkeypatch):
    bodies = _record(monkeypatch)
    rel = ToolRelevanceClassifier(fixed({"deploy": {"type": "noul", "noul": 0.9},
                                         "send_email": {"type": "noul", "noul": 0.05}}), on_error="open")
    await _client({"before_llm": rel.as_hook()}).run("ship it", await _deploy_toolkit([], extra=True))
    tools = bodies[0]["tools"]
    assert [t["function"]["name"] for t in tools] == ["deploy"]


@pytest.mark.asyncio
async def test_content_guard_hook(monkeypatch):
    bodies = _record(monkeypatch)
    g = ContentGuardClassifier(fixed({"harmful": {"type": "noul", "noul": 0.96},
                                      "prompt_injection": {"type": "noul", "noul": 0.9}}), on_error="closed")
    with pytest.raises(ContentGuardBlocked, match="content guard blocked: harmful, prompt_injection"):
        await _client({"before_llm": g.as_hook()}).run("idiot", await _deploy_toolkit([]))
    assert bodies == []

    called: list[int] = []
    g = ContentGuardClassifier(fixed({"harmful": {"type": "noul", "noul": 0.5},
                                      "prompt_injection": {"type": "noul", "noul": 0.1}}), on_error="closed")
    h = g.as_hook(lambda ev: called.append(1))
    await h({"messages": [{"role": "user", "content": "meh"}]})
    assert called

    ge = ContentGuardClassifier(fixed_err(), on_error="closed")
    with pytest.raises(ContentGuardBlocked) as ei:
        await ge.as_hook()({"messages": [{"role": "user", "content": "x"}]})
    assert str(ei.value) == "content guard blocked: classifier error"


@pytest.mark.asyncio
async def test_tool_result_filter_hook():
    f = ToolResultFilterClassifier(fixed({"0": {"type": "noul", "noul": 0.9},
                                          "1": {"type": "noul", "noul": 0.05},
                                          "2": {"type": "noul", "noul": 0.5}}), on_error="open")
    saw: list[str] = []

    def nxt(ev):
        saw.append(ev["result"].output)
        return None

    ov = await f.as_hook(nxt)({"name": "t", "args": {}, "result": ToolResult(output="a\n\nb\n\nc", is_error=False)})
    assert ov is not None and ov["result"].output == "a\n\nc" and saw == ["a\n\nc"]

    # single chunk, error result, non-text parts: untouched, no classifier call.
    fe = ToolResultFilterClassifier(fixed_err(), on_error="closed")
    for r in (ToolResult(output="one", is_error=False),
              ToolResult(output="a\n\nb", is_error=True),
              ToolResult(output="a\n\nb", is_error=False, parts=[{"type": "image"}])):
        assert await fe.as_hook()({"name": "t", "args": {}, "result": r}) is None
