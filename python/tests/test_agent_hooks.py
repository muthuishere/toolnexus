"""The §8 seams on a §7D agent run — ``hooks`` / ``on_metric`` (SPEC.md §7D).

Shared fixture: ``examples/agent-hooks/fixture.json`` (scenarios H1-H6).

The runtime builds each handle's client itself, so the §8 seams reach an agent run
only by being handed to it. Both the runtime options and :class:`AgentDef` carry the
two optional values; they are resolved **def-over-runtime, replace never merge, each
field independently**, and forwarded **verbatim** into ``create_client`` — the runtime
never composes, wraps, reorders, defaults or reads them.

The payoff: a real §7F :func:`compactor` is just a ``before_llm`` hook, so it attaches
per agent (two agents in one runtime can carry different budgets). And because
compaction rewrites the working transcript — the very list that gets persisted — it
interacts with the durable-pending rewind: a turn that compacts and then suspends is
rewound to its FULL pre-turn transcript, the compaction discarded.

The LLM is the scripted in-process transport from ``_agent_mocks`` (zero network).
"""
from __future__ import annotations

import copy
import threading
from typing import Any, Optional

from toolnexus import Answer
from toolnexus.agents import AgentRuntime, Handle, compactor

from _agent_mocks import MockTransport, registry


class RecordingTransport(MockTransport):
    """Scripted mock that also snapshots the exact transcript each call sent.

    The client mutates its ``messages`` list in place after the POST returns, so the
    payload is deep-copied at record time — the recording is the WIRE, not a view of
    the loop's later state.
    """

    def __init__(self, sleep: float = 0) -> None:
        super().__init__(sleep=sleep)
        self._lock = threading.Lock()
        self.sent: dict[str, list[list[dict[str, Any]]]] = {}

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        with self._lock:
            self.sent.setdefault(payload["model"], []).append(copy.deepcopy(payload["messages"]))
        return super().post(url, headers, payload, timeout)

    def last(self, model: str) -> list[dict[str, Any]]:
        with self._lock:
            runs = self.sent.get(model) or []
            return runs[-1] if runs else []


def _defs(**overrides: dict[str, Any]) -> dict:
    """The shared fixture registry with per-agent field overrides."""
    return registry(overrides or None)


def _spawn(rt: AgentRuntime, name: str) -> Handle:
    h = rt.spawn(rt.root, name)
    assert isinstance(h, Handle), getattr(h, "error", h)
    return h


async def _seed(rt: AgentRuntime, h: Handle, pairs: int = 40) -> list[dict[str, Any]]:
    """Stuff a handle's stored transcript with a long history so the next turn is
    over budget: a verbatim leading system prompt + ``pairs`` user/assistant pairs."""
    msgs: list[dict[str, Any]] = [{"role": "system", "content": "SOUL-VERBATIM"}]
    for _ in range(pairs):
        msgs.append({"role": "user", "content": "q" * 200})
        msgs.append({"role": "assistant", "content": "a" * 200})
    await rt.conversation_store.save(h.conv_id, msgs)
    return msgs


# --------------------------------------------------------------------------- #
# 1 — a runtime-level before_llm hook runs inside an agent turn AND its rewrite
#     reaches the wire.
# --------------------------------------------------------------------------- #
async def test_runtime_hooks_run_inside_an_agent_turn():
    seen = 0

    async def before_llm(ev: dict[str, Any]) -> dict[str, Any]:
        nonlocal seen
        seen += 1
        return {"messages": [*ev["messages"], {"role": "system", "content": "INJECTED-BY-HOOK"}]}

    mock = RecordingTransport()
    rt = AgentRuntime(
        transport=mock,
        registry=_defs(peer={"model": "m-a"}),
        hooks={"before_llm": before_llm},
    )
    h = _spawn(rt, "peer")
    r = await rt.run_turn(h, "hello")
    assert not r.is_error, r.text
    assert seen > 0, "before_llm never ran inside the agent turn"
    sent = mock.last("m-a")
    assert any(m.get("content") == "INJECTED-BY-HOOK" for m in sent), sent
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 2 — a runtime-level on_metric receives the §8 semantic events of an agent turn.
# --------------------------------------------------------------------------- #
async def test_runtime_on_metric_receives_events():
    events: list[str] = []

    rt = AgentRuntime(
        transport=RecordingTransport(),
        registry=_defs(peer={"model": "m-a"}),
        on_metric=lambda ev: events.append(ev["event"]),
    )
    h = _spawn(rt, "peer")
    r = await rt.run_turn(h, "hi")
    assert not r.is_error, r.text
    assert "llm" in events and "run" in events, events
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 3 — def-level REPLACES runtime-level, for that agent only (never merged).
# --------------------------------------------------------------------------- #
async def test_def_hooks_replace_runtime_hooks_per_agent():
    fired: dict[str, int] = {"runtime": 0, "defA": 0}

    def mark(tag: str) -> dict[str, Any]:
        async def before_llm(ev: dict[str, Any]) -> None:
            fired[tag] += 1
            return None

        return {"before_llm": before_llm}

    rt = AgentRuntime(
        transport=RecordingTransport(),
        registry=_defs(peer={"model": "m-a", "hooks": mark("defA")}, slow={"model": "m-b"}),
        hooks=mark("runtime"),
    )
    a = _spawn(rt, "peer")
    b = _spawn(rt, "slow")
    assert not (await rt.run_turn(a, "hi")).is_error
    assert not (await rt.run_turn(b, "hi")).is_error

    assert fired["defA"] == 1, fired
    assert fired["runtime"] == 1, ("runtime hook must run ONLY for B — never merged into A", fired)
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 4 — the two fields resolve INDEPENDENTLY: overriding hooks does not drop the
#     inherited on_metric.
# --------------------------------------------------------------------------- #
async def test_hooks_and_on_metric_resolve_independently():
    models: list[str] = []

    async def def_hook(ev: dict[str, Any]) -> None:
        return None

    rt = AgentRuntime(
        transport=RecordingTransport(),
        registry=_defs(peer={"model": "m-a", "hooks": {"before_llm": def_hook}}),
        hooks={"before_llm": def_hook},
        on_metric=lambda ev: models.append(str(ev.get("model"))),
    )
    h = _spawn(rt, "peer")
    assert not (await rt.run_turn(h, "hi")).is_error
    assert "m-a" in models, ("a def that overrode hooks still inherits the runtime on_metric", models)
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 5 — THE POINT: a real §7F compactor attached through a def's hooks compacts an
#     agent run.
# --------------------------------------------------------------------------- #
async def test_compactor_attached_to_an_agent_run():
    mock = RecordingTransport()
    compact = compactor(
        max_tokens=200,
        keep_tail=80,
        summarize=lambda older: "SUMMARY-OF-HEAD",
    )
    rt = AgentRuntime(
        transport=mock,
        registry=_defs(
            peer={
                "model": "m-a",
                "system_prompt": "SOUL-VERBATIM",
                "hooks": {"before_llm": compact},
            }
        ),
    )
    h = _spawn(rt, "peer")
    pre = await _seed(rt, h)
    assert len(await rt.conversation_store.get(h.conv_id)) == 81

    r = await rt.run_turn(h, "next question")
    assert not r.is_error, r.text

    sent = mock.last("m-a")
    assert len(sent) < len(pre), (len(pre), len(sent))
    assert sent[0].get("content") == "SOUL-VERBATIM", sent[0]
    assert any("SUMMARY-OF-HEAD" in str(m.get("content") or "") for m in sent), sent
    await rt.close(rt.root)


# --------------------------------------------------------------------------- #
# 6 — §10 interaction: a turn that compacts and THEN suspends is rewound to its
#     FULL pre-turn transcript; the compaction is discarded.
# --------------------------------------------------------------------------- #
async def test_compaction_then_durable_pending_rewinds_full_transcript():
    compact = compactor(max_tokens=200, keep_tail=80, summarize=lambda older: "SUMMARY-OF-HEAD")
    rt = AgentRuntime(
        transport=MockTransport(sleep=0),
        registry=_defs(asker={"hooks": {"before_llm": compact}}),
    )
    h = _spawn(rt, "asker")  # no interpreter anywhere ⇒ durable pending
    pre = await _seed(rt, h)

    r = await rt.run_turn(h, "get the secret")
    assert r.status == "pending", r
    post: Optional[list] = await rt.conversation_store.get(h.conv_id)
    assert post is not None and len(post) == len(pre) == 81, (len(pre), len(post or []))
    assert post[0].get("content") == "SOUL-VERBATIM"
    assert not any("SUMMARY-OF-HEAD" in str(m.get("content") or "") for m in post)

    assert r.pending is not None
    await rt.resume(Answer(id=r.pending.id, ok=True))
    await rt.close(rt.root)
