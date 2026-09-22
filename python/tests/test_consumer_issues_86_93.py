"""Consumer issues #86–#93 — the decisions in
``openspec/changes/fix-consumer-issues-86-93/DECISIONS.md`` (D1–D6 + addendum A1–A7).

Hermetic throughout: a scripted in-process transport is the LLM (the same
``http_transport`` seam the resilience suite uses), the skill fixtures are the
ones the #93 spike measured. No network, no API key, no cost.
"""
from __future__ import annotations

import asyncio
import json
import os
import tempfile
from pathlib import Path
from typing import Any, Optional

import pytest

from toolnexus import (
    RunTimeout,
    Tool,
    ToolResult,
    answer_declined,
    answer_output,
    create_client,
    create_toolkit,
)
from toolnexus.agents import LIMITS, Budget, TASK_STATUSES, agent, loop_unsupported
from toolnexus.classifier import ClassifierError, create_classifier
from toolnexus.client import (
    LIMIT_MAX_TURNS,
    MAX_ERROR_BODY,
    REDACTED,
    RUN_STATUSES,
    ProviderError,
    capped_error_body,
    safe_error_body,
)
from toolnexus.skill import SkillSkip, list_skills, load_skills

SPIKE_93 = Path(__file__).resolve().parents[2] / "spikes" / "issues" / "93" / "fixtures"


# --------------------------------------------------------------------------- #
# A scripted transport: records every request body, replies with canned text.
# --------------------------------------------------------------------------- #
class RecordingTransport:
    def __init__(self, replies: Optional[list[dict[str, Any]]] = None) -> None:
        self.requests: list[dict[str, Any]] = []
        self.replies = replies or []

    def _body(self) -> dict[str, Any]:
        if self.replies:
            return self.replies.pop(0)
        return {
            "choices": [{"message": {"role": "assistant", "content": "a haiku"}}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8},
        }

    def post(self, url, headers, payload, timeout):  # noqa: ANN001
        self.requests.append(payload)
        return self._body()

    def open(self, url, headers, payload, timeout):  # noqa: A003, ANN001
        raise NotImplementedError


def _client(transport: RecordingTransport, **kw: Any):
    return create_client(
        base_url="http://mock.local/v1",
        style="openai",
        model="mock",
        api_key="unused",
        http_transport=transport,
        **kw,
    )


# --------------------------------------------------------------------------- #
# D1 — a completion needs no toolkit (#86, ADR 0023)
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
async def test_d1_run_without_a_toolkit():
    t = RecordingTransport()
    r = await _client(t).run("write me a haiku")
    assert r.text == "a haiku"


@pytest.mark.asyncio
async def test_d1_explicit_none_toolkit():
    t = RecordingTransport()
    r = await _client(t).run("write me a haiku", None)
    assert r.text == "a haiku"


@pytest.mark.asyncio
async def test_d1_toolkitless_body_has_no_tools_key():
    # The WIRE assertion: absent, never an empty array — an empty `tools: []` is a
    # 400 on several providers.
    t = RecordingTransport()
    await _client(t).run("write me a haiku")
    body = t.requests[0]
    assert "tools" not in body
    assert "tool_choice" not in body


@pytest.mark.asyncio
async def test_d1_ask_and_stream_without_a_toolkit():
    t = RecordingTransport()
    r = await _client(t).ask("write me a haiku")
    assert r.text == "a haiku"
    assert "tools" not in t.requests[0]


@pytest.mark.asyncio
async def test_d1_skills_prompt_still_reaches_the_system_message_with_a_toolkit():
    # The null-guard must not cost a toolkit-bearing run its §0.10 system message.
    t = RecordingTransport()
    tk = await create_toolkit(builtins=False, skills_dir=str(SPIKE_93))
    await _client(t).run("hi", tk)
    system = next(m for m in t.requests[0]["messages"] if m["role"] == "system")
    assert "Available Skills" in system["content"]


# --------------------------------------------------------------------------- #
# D2 — the Loop honours the spec (#87, ADR 0024)
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
async def test_d2_caller_system_prompt_wins_over_the_soul():
    a = agent("a", does="x", soul="I am the soul")
    t = RecordingTransport()
    loop = a.loop(
        {
            "base_url": "http://mock.local/v1",
            "style": "openai",
            "model": "mock",
            "api_key": "unused",
            "http_transport": t,
            "system_prompt": "the CALLER's prompt",
        },
        await create_toolkit(builtins=False),
    )
    await loop.run("go")
    system = next(m for m in t.requests[0]["messages"] if m["role"] == "system")
    assert system["content"].startswith("the CALLER's prompt")
    assert "I am the soul" not in system["content"]


@pytest.mark.asyncio
async def test_d2_soul_is_the_default_when_the_caller_sets_none():
    a = agent("a", does="x", soul="I am the soul")
    t = RecordingTransport()
    loop = a.loop(
        {
            "base_url": "http://mock.local/v1",
            "style": "openai",
            "model": "mock",
            "api_key": "unused",
            "http_transport": t,
        },
        await create_toolkit(builtins=False),
    )
    await loop.run("go")
    system = next(m for m in t.requests[0]["messages"] if m["role"] == "system")
    assert "I am the soul" in system["content"]


@pytest.mark.asyncio
async def test_d2_spec_model_and_budget_are_loop_defaults():
    a = agent("a", does="x", model="m-spec", budget=Budget(max_turns=3))
    t = RecordingTransport()
    base = {
        "base_url": "http://mock.local/v1",
        "style": "openai",
        "api_key": "unused",
        "http_transport": t,
    }
    loop = a.loop(dict(base), await create_toolkit(builtins=False))
    assert loop._client_options("")["model"] == "m-spec"
    assert loop._client_options("")["max_turns"] == 3
    # Addendum A8: the sentinel "inherit" counts as ABSENT, like a missing field.
    inherit = a.loop({**base, "model": "inherit"}, await create_toolkit(builtins=False))
    assert inherit._client_options("")["model"] == "m-spec"
    # A caller's real model still wins over the spec default.
    caller = a.loop({**base, "model": "m-caller"}, await create_toolkit(builtins=False))
    assert caller._client_options("")["model"] == "m-caller"
    # …and a per-call model wins over both.
    assert loop._client_options("m-call")["request_params"]["model"] == "m-call"


def test_d2_loop_unsupported_uses_the_canonical_vocabulary():
    # Addendum A6: the SAME four strings in all seven ports — never snake_case.
    child = agent("child", does="y")
    a = agent(
        "a",
        does="x",
        uses={"tools": []},
        team=[child],
        wait_for=lambda req: None,
        on_metric=lambda ev: None,
    )
    assert loop_unsupported(a) == ["team", "waitFor", "onMetric"]
    assert loop_unsupported(agent("b", does="x")) == []


@pytest.mark.asyncio
async def test_d2_a_denied_tool_is_never_entered():
    # The regression assertion is the EXECUTE COUNTER, never the model's text.
    entered = {"n": 0}

    async def execute(args=None, ctx=None):  # noqa: ANN001
        entered["n"] += 1
        return ToolResult(output="ran", is_error=False)

    danger = Tool(
        name="danger",
        description="irreversible",
        input_schema={"type": "object", "properties": {}},
        source="native",
        execute=execute,
    )
    a = agent("a", does="x", guardrails=[lambda ev: "policy: no danger"])
    t = RecordingTransport(
        [
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "type": "function",
                                    "function": {"name": "danger", "arguments": "{}"},
                                }
                            ],
                        }
                    }
                ],
                "usage": {"total_tokens": 10},
            },
            {
                "choices": [{"message": {"role": "assistant", "content": "stopped"}}],
                "usage": {"total_tokens": 4},
            },
        ]
    )
    loop = a.loop(
        {
            "base_url": "http://mock.local/v1",
            "style": "openai",
            "model": "mock",
            "api_key": "unused",
            "http_transport": t,
        },
        await create_toolkit(builtins=False, extra_tools=[danger]),
    )
    await loop.run("go")
    assert entered["n"] == 0


# --------------------------------------------------------------------------- #
# D3 — the runtime path is as legible as the loop (#88/#90, ADR 0025)
# --------------------------------------------------------------------------- #
def _rt(t: RecordingTransport) -> dict[str, Any]:
    """Runtime options: the LLM descriptor plus the injected transport."""
    return {
        "llm": {
            "base_url": "http://mock.local/v1",
            "style": "openai",
            "model": "mock",
            "api_key": "unused",
        },
        "transport": t,
    }


@pytest.mark.asyncio
async def test_d3_total_tokens_is_the_tree_total_and_own_tokens_is_the_agent():
    child = agent("explore", does="reads things")
    coordinator = agent("coordinator", does="delegates", team=[child])
    t = RecordingTransport(
        [
            # coordinator turn 1: delegate
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "type": "function",
                                    "function": {
                                        "name": "task",
                                        "arguments": json.dumps({"agent": "explore", "prompt": "find A"}),
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"total_tokens": 100},
            },
            # the child's own run
            {
                "choices": [{"message": {"role": "assistant", "content": "found A"}}],
                "usage": {"total_tokens": 400},
            },
            # coordinator turn 2: final
            {
                "choices": [{"message": {"role": "assistant", "content": "done"}}],
                "usage": {"total_tokens": 100},
            },
        ]
    )
    r = await coordinator.run(prompt="go", **_rt(t))
    assert r.status == "done"
    # The whole tree, on a `done` status — not the root's own 200 (#88).
    assert r.total_tokens == 600
    assert r.own_tokens == 200
    # Addendum A13a: `turns` is the handle's OWN cumulative round trips, reported the
    # same way on every status — here the coordinator's two (delegate, then answer).
    # It does NOT roll up (A13b): the child's turn is not in this number.
    assert r.turns == 2


@pytest.mark.asyncio
async def test_d3_limit_is_machine_readable_on_a_turn_cap():
    a = agent("a", does="x", budget=Budget(max_turns=2))
    looping = {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {"id": "c1", "type": "function", "function": {"name": "nope", "arguments": "{}"}}
                    ],
                }
            }
        ],
        "usage": {"total_tokens": 7},
    }
    t = RecordingTransport([dict(looping) for _ in range(6)])
    r = await a.run(prompt="go", **_rt(t))
    assert r.status == "incomplete"
    # The reason is a FIELD now, not only prose in `text` (#90.1).
    assert r.limit == LIMIT_MAX_TURNS
    assert r.limit in LIMITS


@pytest.mark.asyncio
async def test_d3_limit_is_set_on_a_budget_stop():
    a = agent("a", does="x", budget=Budget(max_tokens=1))
    t = RecordingTransport(
        [
            {"choices": [{"message": {"role": "assistant", "content": "one"}}], "usage": {"total_tokens": 50}},
            {"choices": [{"message": {"role": "assistant", "content": "two"}}], "usage": {"total_tokens": 50}},
        ]
    )
    rt = a._runtime(**_rt(t))
    h = rt.spawn(rt.root, "a")
    await rt.run_turn(h, "go")
    second = await rt.run_turn(h, "again")
    assert second.status == "incomplete"
    # Addendum A14: the CANONICAL spelling of the Budget field, identical in every
    # port — never python's internal dimension name ("tokens").
    assert second.limit == "maxTokens"
    assert second.limit in LIMITS


@pytest.mark.asyncio
async def test_d3_resume_returns_the_resumed_result():
    from toolnexus import pending

    async def approve(args=None, ctx=None):  # noqa: ANN001
        if ctx is not None and getattr(ctx, "answer", None) is not None:
            return ToolResult(output="approved", is_error=False)
        return pending(kind="approval", prompt="may I commit?")

    tool = Tool(
        name="approve",
        description="asks",
        input_schema={"type": "object", "properties": {}},
        source="native",
        execute=approve,
    )
    a = agent("worker", does="x", uses={"tools": [tool]})
    call = {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {"id": "c1", "type": "function", "function": {"name": "approve", "arguments": "{}"}}
                    ],
                }
            }
        ],
        "usage": {"total_tokens": 50},
    }
    final = {
        "choices": [{"message": {"role": "assistant", "content": "committed"}}],
        "usage": {"total_tokens": 50},
    }
    t = RecordingTransport([dict(call), dict(call), dict(final)])
    r = await a.run(prompt="go", **_rt(t))
    assert r.status == "pending"
    from toolnexus import Answer

    resumed = await r.runtime.resume(Answer(id=r.pending.id, ok=True))
    # It used to return None — the final answer was unreachable through the API.
    assert resumed is not None
    assert resumed.status == "done"
    assert resumed.text == "committed"


def test_d3_task_status_vocabulary_is_named():
    assert "timeout" in TASK_STATUSES
    assert len(TASK_STATUSES) == 7


# --------------------------------------------------------------------------- #
# D4 — the Answer payload contract (#89, ADR 0026)
# --------------------------------------------------------------------------- #
def test_d4_answer_output_constructor():
    a = answer_output("pnd-1", "staging")
    assert a.ok is True
    assert a.id == "pnd-1"
    # `output` is the recognised key (addendum A3) — hosts stop guessing.
    assert a.data == {"output": "staging"}


def test_d4_a_non_string_output_errors_loudly():
    with pytest.raises(TypeError):
        answer_output("pnd-1", {"value": "staging"})  # type: ignore[arg-type]


@pytest.mark.asyncio
async def test_d4_the_answer_reaches_the_tool_itself():
    # Python has no durable RunWithAnswer path (golang-only): the inline wait_for
    # path is the whole §10 surface here, and it re-executes the tool with
    # ctx.answer, which is the behaviour #89 wanted.
    from toolnexus import pending

    seen: dict[str, Any] = {}

    async def ask_human(args=None, ctx=None):  # noqa: ANN001
        if ctx is not None and getattr(ctx, "answer", None) is not None:
            seen["answer"] = ctx.answer
            return ToolResult(output=ctx.answer.data["output"], is_error=False)
        return pending(kind="input", prompt="which environment?")

    tool = Tool(
        name="ask_human",
        description="asks",
        input_schema={"type": "object", "properties": {}},
        source="native",
        execute=ask_human,
    )
    t = RecordingTransport(
        [
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": None,
                            "tool_calls": [
                                {
                                    "id": "c1",
                                    "type": "function",
                                    "function": {"name": "ask_human", "arguments": "{}"},
                                }
                            ],
                        }
                    }
                ],
                "usage": {"total_tokens": 5},
            },
            {"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {"total_tokens": 5}},
        ]
    )
    client = _client(t, wait_for=lambda req: answer_output(req.id, "staging"))
    r = await client.run("go", await create_toolkit(builtins=False, extra_tools=[tool]))
    assert r.status == "done"
    assert seen["answer"].data["output"] == "staging"


# --------------------------------------------------------------------------- #
# D5 — what we hand back when we fail (#91/#92, ADR 0027)
# --------------------------------------------------------------------------- #
def test_d5_backend_preset_sets_three_fields_as_a_unit():
    c = create_classifier(backend="openrouter")
    assert c.base_url == "https://openrouter.ai/api/v1"
    assert c.model == "typesafe/jev-1.13"
    assert c.api_key_env == "OPENROUTER_API_KEY"
    d = create_classifier(backend="typesafe")
    assert d.base_url == "https://api.typesafe.ai/v1"
    assert d.model == "jev-latest"


def test_d5_the_known_mismatch_fails_at_construction():
    with pytest.raises(ClassifierError) as e:
        create_classifier(base_url="https://openrouter.ai/api/v1", model="jev-latest")
    assert "typesafe/jev-1.13" in str(e.value)


def test_d5_two_status_vocabularies_are_both_named():
    assert set(RUN_STATUSES) == {"done", "pending", "incomplete"}
    # The collision that produced #92.1: the names differ, the field does not.
    assert "timeout" in TASK_STATUSES and "timeout" not in RUN_STATUSES


def test_d5_provider_error_is_typed():
    e = ProviderError(429, "slow down", "2")
    assert e.status == 429
    assert e.body == "slow down"
    # The RAW header verbatim, not a parsed number — identical in all seven ports.
    assert e.retry_after == "2"


def test_d5_provider_error_carries_a_non_delay_seconds_retry_after_verbatim():
    """The point of the raw field: a value the WAITING rule ignores still reaches the host.

    An HTTP-date is a legitimate ``Retry-After``; the library falls back to backoff for it
    (``_parse_retry_after`` answers None), but the response really supplied it, so the typed
    error carries it byte for byte rather than nulling it out.
    """
    from toolnexus.client import _parse_retry_after

    raw = "Wed, 21 Oct 2026 07:28:00 GMT"
    e = ProviderError(503, "later", raw)
    assert e.retry_after == raw
    assert _parse_retry_after(raw) is None  # waiting rule unchanged: falls back to backoff


def test_d5_account_identifiers_are_redacted_not_merely_capped():
    body = '{"error":{"message":"no credit","user_id":"user_2abc","org_id":"org_9"}}'
    out = safe_error_body(402, body)
    assert "user_2abc" not in out and "org_9" not in out
    assert out.count(REDACTED) == 2
    # The leak in #91 was 96 bytes — well under any cap.
    assert len(body) < MAX_ERROR_BODY


def test_d5_auth_bodies_never_surface():
    assert safe_error_body(401, "Authorization: Bearer sk-live-xyz rejected") == ""
    assert safe_error_body(403, "forbidden for token sk-live-xyz") == ""
    assert "sk-live" not in str(ProviderError(401, "Bearer sk-live-xyz", None))


def test_d5_the_cap_is_message_only():
    # Addendum A5: the typed field carries the whole redacted body.
    long = "x" * (MAX_ERROR_BODY * 3)
    e = ProviderError(500, long, None)
    assert len(e.body) == len(long)
    assert len(capped_error_body(500, long)) == MAX_ERROR_BODY + 1  # + the ellipsis


@pytest.mark.asyncio
async def test_d5_timeout_message_names_the_budget():
    class Slow:
        """Honours the per-request timeout the way urllib does: it expires."""

        def post(self, url, headers, payload, timeout):  # noqa: ANN001
            import time as _t

            _t.sleep(max(timeout, 0))
            raise TimeoutError("timed out")

        def open(self, url, headers, payload, timeout):  # noqa: A003, ANN001
            raise NotImplementedError

    client = create_client(
        base_url="http://mock.local/v1",
        style="openai",
        model="mock",
        api_key="unused",
        http_transport=Slow(),
        timeout_ms=10,
    )
    with pytest.raises(RunTimeout) as e:
        await client.run("go")
    assert "timeout_ms" in str(e.value)


@pytest.mark.asyncio
async def test_d5_must_not_regress_fail_fast_on_a_non_retryable_4xx():
    attempts = {"n": 0}

    class Failing:
        def post(self, url, headers, payload, timeout):  # noqa: ANN001
            attempts["n"] += 1
            raise ProviderError(400, "bad request", None)

        def open(self, url, headers, payload, timeout):  # noqa: A003, ANN001
            raise NotImplementedError

    client = create_client(
        base_url="http://mock.local/v1",
        style="openai",
        model="mock",
        api_key="unused",
        http_transport=Failing(),
        retries=3,
    )
    with pytest.raises(ProviderError):
        await client.run("go")
    assert attempts["n"] == 1  # 400 is NOT in {429,500,502,503,504,529}


@pytest.mark.asyncio
async def test_d5_must_not_regress_429_is_retried():
    from toolnexus.client import _RETRYABLE

    assert _RETRYABLE == frozenset({429, 500, 502, 503, 504, 529})
    attempts = {"n": 0}

    class Flaky:
        def post(self, url, headers, payload, timeout):  # noqa: ANN001
            attempts["n"] += 1
            if attempts["n"] == 1:
                raise ProviderError(429, "slow down", 0.0)
            return {
                "choices": [{"message": {"role": "assistant", "content": "ok"}}],
                "usage": {"total_tokens": 1},
            }

        def open(self, url, headers, payload, timeout):  # noqa: A003, ANN001
            raise NotImplementedError

    client = create_client(
        base_url="http://mock.local/v1",
        style="openai",
        model="mock",
        api_key="unused",
        http_transport=Flaky(),
        retries=2,
    )
    r = await client.run("go")
    assert r.text == "ok" and attempts["n"] == 2


def test_d5_must_not_regress_classifier_cost_absent_is_not_zero():
    from toolnexus.classifier import ClassifierUsage

    assert ClassifierUsage().cost is None


# --------------------------------------------------------------------------- #
# D6 — a skill the writing tool accepts (#93, ADR 0028)
# --------------------------------------------------------------------------- #
def test_d6_yaml_runs_first_block_scalars_are_byte_identical():
    src = load_skills(str(SPIKE_93))
    assert src.skills["block-literal"].description == (
        "First line of the description.\n"
        "Second line, with a colon: still fine inside a block scalar."
    )
    assert src.skills["block-folded"].description == (
        "A folded description that runs across two source lines."
    )


def test_d6_the_fallback_rescues_frontmatter_yaml_refused():
    src = load_skills(str(SPIKE_93))
    # Both files are YAML errors; both are files Claude Code reads happily.
    assert "broken-flow" in src.skills
    assert "broken-tab" in src.skills
    # …and the fallback INVENTS nothing: a value opening `[` is refused outright.
    assert src.skills["broken-flow"].description is None
    assert src.skills["broken-tab"].description == "tab-indented continuation"


def test_d6_genuinely_malformed_files_are_still_refused():
    src = load_skills(str(SPIKE_93))
    skipped = {os.path.basename(os.path.dirname(s.location)): s for s in src.skipped}
    assert set(skipped) == {"broken-unclosed", "no-frontmatter", "no-name"}
    assert all(s.reason == "missing-name" for s in skipped.values())


def test_d6_load_skills_returns_the_skips():
    # A2: returned data on the result — a host must not need list_skills to learn
    # that files vanished.
    src = load_skills(str(SPIKE_93))
    assert src.skipped
    assert all(isinstance(s, SkillSkip) for s in src.skipped)
    assert {s.location for s in src.skipped} == {
        s.location for s in list_skills(str(SPIKE_93)).skipped
    }


def test_d6_skips_carry_the_native_parser_detail():
    with tempfile.TemporaryDirectory() as root:
        d = os.path.join(root, "s")
        os.makedirs(d)
        # YAML refuses it AND the fallback cannot find a name ⇒ malformed, with detail.
        with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write("---\n: [unclosed\n---\nbody\n")
        src = load_skills(root)
        assert len(src.skipped) == 1
        assert src.skipped[0].reason == "malformed-frontmatter"
        assert src.skipped[0].detail  # PyYAML's own message, port-specific by design


def test_d6_first_wins_in_a_deterministic_discovery_order():
    # A1: lexicographic by path relative to the root, never the filesystem's order.
    with tempfile.TemporaryDirectory() as root:
        for sub in ("b-second", "a-first"):
            d = os.path.join(root, sub)
            os.makedirs(d)
            with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write(f"---\nname: dup\ndescription: from {sub}\n---\nbody\n")
        src = load_skills(root)
        assert src.skills["dup"].description == "from a-first"
        assert [s.reason for s in src.skipped] == ["duplicate-name"]


def test_d6_a1a_code_point_order_decides_the_winner():
    # Addendum A1a: Unicode CODE-POINT comparison of the path relative to the root —
    # no locale collation, no case folding, no segment-aware comparison. The shape
    # that motivated it: a top-level `docx/` beats a deep `synced/<hash>/docx/`.
    with tempfile.TemporaryDirectory() as root:
        # (No case-variant pair here: macOS's filesystem is case-insensitive, so the
        # case-folding half of A1a cannot be expressed as two real directories.)
        for rel in ("docx", os.path.join("synced", "9f3a", "docx"), "Zsync"):
            d = os.path.join(root, rel)
            os.makedirs(d)
            with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write(f"---\nname: docx\ndescription: from {rel}\n---\nbody\n")
        src = load_skills(root)
        # "Zsync/SKILL.md" < "docx/SKILL.md" < "synced/9f3a/docx/SKILL.md" by code
        # point: upper-case sorts FIRST, never folded away, and a top-level `docx/`
        # still beats the deep `synced/<hash>/docx/` that motivated the rule.
        assert src.skills["docx"].description == "from Zsync"
        assert len(src.skipped) == 2


def test_d4_a9_answer_declined_constructor():
    a = answer_declined("pnd-1", "cancelled")
    assert a.ok is False and a.reason == "cancelled"
    assert answer_declined("pnd-1").reason == "declined"
    with pytest.raises(ValueError):
        answer_declined("pnd-1", "nope")


def test_d6_the_fixture_table_is_the_cross_port_arbiter():
    # The accept/skip table over spikes/issues/93/fixtures must be IDENTICAL in every
    # port (detail strings are the native parser's and are deliberately excluded).
    src = load_skills(str(SPIKE_93))
    # Addendum A11: assert the description STRING, never only the ok/skip verdict —
    # `hash-inline` is decided by YAML's COMMENT handling (` #` opens a comment), not
    # by the rescue, and a verdict-only assertion would hide a mismatch.
    assert {name: info.description for name, info in sorted(src.skills.items())} == {
        "anchors": "A skill using a YAML anchor and alias.",
        "block-folded": "A folded description that runs across two source lines.",
        "block-literal": (
            "First line of the description.\n"
            "Second line, with a colon: still fine inside a block scalar."
        ),
        # A12: ok with the NAME KEPT and NO description — the proof that A10's
        # non-string/refused-value guard is really implemented.
        "broken-flow": None,
        "broken-tab": "tab-indented continuation",
        "colon-space": (
            "Work out billable hours from git commits and session logs. "
            "Trigger on: update the timesheet, do my timesheet, how many hours did I work."
        ),
        "colon-space-quoted": (
            "Work out billable hours. Trigger on: update the timesheet, do my timesheet."
        ),
        "colon-space-single": "Work out billable hours. Trigger on: update the timesheet.",
        "hash-inline": "Tag things with",
        "list-block": "A skill with a block-sequence key.",
        "list-value": "A skill that also declares a list-valued key.",
        "nested-map": "A skill with a nested mapping key.",
        "plain": "An ordinary skill with an ordinary one-line description.",
        "url-colon": "Fetch pages from https://example.com/docs and summarise them.",
    }
    assert sorted(
        (os.path.basename(os.path.dirname(s.location)), s.reason) for s in src.skipped
    ) == [
        ("broken-unclosed", "missing-name"),
        ("no-frontmatter", "missing-name"),
        ("no-name", "missing-name"),
    ]


def test_d6_a15_depth_beats_code_point():
    # Addendum A15: DEPTH ascending FIRST, code point only as the tie-break within a
    # depth. `xlsx` is the shape that distinguishes the two rules — it begins with
    # `x`, which sorts AFTER the nested copy's first segment `synced`, so a pure
    # code-point sort would hand the win to `synced/<uuid>/xlsx`. Depth-first keeps
    # the top-level skill winning whatever its name.
    with tempfile.TemporaryDirectory() as root:
        for rel in ("xlsx", os.path.join("synced", "b71e", "xlsx")):
            d = os.path.join(root, rel)
            os.makedirs(d)
            with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write(f"---\nname: xlsx\ndescription: from {rel}\n---\nbody\n")
        src = load_skills(root)
        assert src.skills["xlsx"].description == "from xlsx"
        # Sanity: plain code-point order really would have chosen the other one.
        assert "synced/b71e/xlsx/SKILL.md" < "xlsx/SKILL.md"


@pytest.mark.asyncio
async def test_d5_a17_wait_timeout_sets_status_and_limit_together():
    # A17: the wait deadline used to report status="timeout" with NO limit, so a host
    # branching on `limit` saw empty while `status` said it timed out. Both fields are
    # asserted together, each against its own closed vocabulary, so they cannot drift
    # apart again.
    #
    # Driven on a VIRTUAL clock (csharp's warning): a short real-time deadline racing
    # the scheduler starves this assertion into flakiness instead of a clean failure.
    from _agent_mocks import VirtualClock

    clock = VirtualClock()
    a = agent("a", does="x")
    rt = a._runtime(clock=clock, **_rt(RecordingTransport()))
    h = rt.spawn(rt.root, "a")
    waiter = asyncio.ensure_future(rt.wait(h, timeout_ms=5_000))
    await asyncio.sleep(0)
    await clock.advance(6.0)
    r = await waiter
    assert r.status == "timeout"
    assert r.status in TASK_STATUSES
    assert r.limit == "timeout"
    assert r.limit in LIMITS


@pytest.mark.asyncio
async def test_d5_a18_status_and_limit_invariant_across_the_branches():
    """A18 — the INVARIANT, not an instance: a LIMIT STOP must name its limit, and a
    NON-limit stop must leave it empty. Driven over a real `done`, a real budget
    `incomplete` and a real `closed`, each value checked against its own closed
    vocabulary (TASK_STATUSES / LIMITS).
    """
    limit_bearing = {"incomplete", "timeout"}

    def check(r) -> None:  # noqa: ANN001
        assert r.status in TASK_STATUSES, r.status
        if r.status in limit_bearing:
            assert r.limit, f"{r.status} reported no limit"
            assert r.limit in LIMITS, r.limit
        else:
            assert not r.limit, f"{r.status} reported limit {r.limit!r}"

    # done
    a = agent("a", does="x")
    t = RecordingTransport(
        [{"choices": [{"message": {"role": "assistant", "content": "ok"}}], "usage": {"total_tokens": 5}}]
    )
    check(await a.run(prompt="go", **_rt(t)))

    # incomplete, from the budget walk
    b = agent("b", does="x", budget=Budget(max_tokens=1))
    t2 = RecordingTransport(
        [
            {"choices": [{"message": {"role": "assistant", "content": "one"}}], "usage": {"total_tokens": 50}},
            {"choices": [{"message": {"role": "assistant", "content": "two"}}], "usage": {"total_tokens": 50}},
        ]
    )
    rt = b._runtime(**_rt(t2))
    h = rt.spawn(rt.root, "b")
    await rt.run_turn(h, "go")
    stopped = await rt.run_turn(h, "again")
    check(stopped)
    assert stopped.limit == "maxTokens"

    # closed — driven EXPLICITLY. A zero-timeout wait on a closed handle returns its
    # SETTLED last result (here the budget stop), not a fresh `closed`, so asserting
    # via wait() would test something other than what it claims.
    c = agent("c", does="x")
    rt3 = c._runtime(**_rt(RecordingTransport()))
    h3 = rt3.spawn(rt3.root, "c")
    await rt3.close(h3)
    assert h3.last_result is not None
    check(h3.last_result)
    assert h3.last_result.status == "closed"

    # timeout — on the INJECTABLE clock, never a short real deadline. csharp saw the
    # real-time form starve this exact assertion: the deadline fired late, the
    # response won the race, and the timeout LOOKED LIKE A SUCCESS.
    from _agent_mocks import VirtualClock

    clock = VirtualClock()
    d = agent("d", does="x")
    rt4 = d._runtime(clock=clock, **_rt(RecordingTransport()))
    h4 = rt4.spawn(rt4.root, "d")
    waiter = asyncio.ensure_future(rt4.wait(h4, timeout_ms=5_000))
    await asyncio.sleep(0)
    await clock.advance(6.0)
    timed_out = await waiter
    check(timed_out)
    assert timed_out.status == "timeout" and timed_out.limit == "timeout"


def test_d5_a20_the_two_vocabularies_are_public_api():
    """A20 — "the suite passes" is not evidence of public visibility.

    In python the surface is convention, so the check is an import-surface assertion:
    both enumerable sets AND every individual value reachable as a NAMED constant from
    the public package path, so a host branches on a name and never a string literal.
    """
    import toolnexus.agents as pkg

    named_limits = {
        pkg.LIMIT_MAX_TURNS,
        pkg.LIMIT_MAX_TOKENS,
        pkg.LIMIT_MAX_TOOL_CALLS,
        pkg.LIMIT_MAX_WALL_MS,
        pkg.LIMIT_MAX_CHILDREN,
        pkg.LIMIT_MAX_CONCURRENT,
        pkg.LIMIT_MAX_DEPTH,
        pkg.LIMIT_COMPLETION,
        pkg.LIMIT_TIMEOUT,
    }
    named_statuses = {
        pkg.TASK_STATUS_DONE,
        pkg.TASK_STATUS_PENDING,
        pkg.TASK_STATUS_INCOMPLETE,
        pkg.TASK_STATUS_INTERRUPTED,
        pkg.TASK_STATUS_CLOSED,
        pkg.TASK_STATUS_TIMEOUT,
        pkg.TASK_STATUS_ERROR,
    }
    assert set(pkg.LIMITS) == named_limits
    assert set(pkg.TASK_STATUSES) == named_statuses
    # …and the package DECLARES them, which is how it declares its surface.
    for name in (
        "LIMITS",
        "TASK_STATUSES",
        *(f"LIMIT_{n}" for n in (
            "MAX_TURNS", "MAX_TOKENS", "MAX_TOOL_CALLS", "MAX_WALL_MS",
            "MAX_CHILDREN", "MAX_CONCURRENT", "MAX_DEPTH", "COMPLETION", "TIMEOUT",
        )),
        *(f"TASK_STATUS_{n}" for n in (
            "DONE", "PENDING", "INCOMPLETE", "INTERRUPTED", "CLOSED", "TIMEOUT", "ERROR",
        )),
    ):
        assert name in pkg.__all__, f"{name} is not declared public"
    # The internal dimension mapper stays PRIVATE: exporting it would leak the very
    # names ("tokens", "wallMs") the canonical vocabulary exists to keep out.
    assert not hasattr(pkg, "canonical_limit")


def test_d6_a22_the_skills_prompt_is_code_point_ordered():
    """A22 — §0.10 pins the skills prompt byte-identical across ports, so its order is
    part of the contract, not an accident of `sorted`.

    The names below separate code point from every locale collation: `Zebra` (U+005A)
    must precede `apple` (U+0061) because upper-case sorts FIRST by code point, while
    a locale-aware comparison folds case and puts `apple` first; and `Ápple` (U+00C1)
    must come LAST, after the ASCII names, where a locale would file it next to
    `apple`. The error a naive fix makes is exactly this one.
    """
    with tempfile.TemporaryDirectory() as root:
        for name in ("apple", "Zebra", "Ápple", "banana"):
            d = os.path.join(root, name)
            os.makedirs(d)
            with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
                f.write(f"---\nname: {name}\ndescription: the {name} skill\n---\nbody\n")
        src = load_skills(root)
        listed = [
            line.split("**")[1] for line in src.prompt().splitlines() if line.startswith("- **")
        ]
        assert listed == ["Zebra", "apple", "banana", "Ápple"]
        assert listed == sorted(listed)  # …which is precisely code-point order


@pytest.mark.asyncio
async def test_d6_a22_the_skill_files_sample_is_sorted_before_it_is_capped():
    """A22/A25/A27 — the `<skill_files>` sample list is shipped output.

    The fixture is built to DISCRIMINATE the rules that could be wrong, because a
    flat set of siblings makes bare-name, relative-path, flat and per-level ordering
    all collapse onto the same answer and proves nothing:

    * `b-nested/zz-a.txt` — a directory whose name orders differently from its
      contents': it follows `alpha.txt` by relative path but sorts LAST by bare name.
    * `alpha/` beside `alpha-b.txt` — flat relative-path order puts `alpha-b.txt`
      first (`-` U+002D < `/` U+002F), per-level order puts `alpha/f.txt` first.
    * `Zeta.txt` / `Ápple.txt` — code point vs locale: upper-case sorts first and the
      non-ASCII name sorts last, which no collation does.
    * a depth rule would pull both nested files behind every top-level one.

    And the cap is asserted both ways: the prefix it keeps, and a late-sorting file it
    must NOT contain — the content half of sort-before-cap (ADR-0004 K1).
    """
    with tempfile.TemporaryDirectory() as root:
        d = os.path.join(root, "s")
        os.makedirs(os.path.join(d, "b-nested"))
        os.makedirs(os.path.join(d, "alpha"))
        with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write("---\nname: s\ndescription: a skill\n---\nbody\n")
        for rel in (
            "m.txt",
            "Zeta.txt",
            "a.txt",
            "Ápple.txt",
            "alpha.txt",
            "alpha-b.txt",
            os.path.join("b-nested", "zz-a.txt"),
            os.path.join("alpha", "f.txt"),
        ):
            with open(os.path.join(d, rel), "w", encoding="utf-8") as f:
                f.write("x")

        # ---- the whole list, uncapped ---------------------------------------- #
        full = load_skills(root, sample_limit=50)
        out_full = (await full.tool.execute({"name": "s"})).output
        rels = [
            os.path.relpath(line[len("<file>") : -len("</file>")], d).replace(os.sep, "/")
            for line in out_full.splitlines()
            if line.startswith("<file>")
        ]
        assert rels == [
            "Zeta.txt",
            "a.txt",
            "alpha-b.txt",
            "alpha.txt",
            "alpha/f.txt",
            "b-nested/zz-a.txt",
            "m.txt",
            "Ápple.txt",
        ]
        assert rels == sorted(rels)  # plain code point on the RELATIVE PATH

        # ---- sorted BEFORE the cap ------------------------------------------- #
        capped = load_skills(root, sample_limit=3)
        out_capped = (await capped.tool.execute({"name": "s"})).output
        capped_rels = [
            os.path.relpath(line[len("<file>") : -len("</file>")], d).replace(os.sep, "/")
            for line in out_capped.splitlines()
            if line.startswith("<file>")
        ]
        assert capped_rels == ["Zeta.txt", "a.txt", "alpha-b.txt"]
        # The content half: late-sorting files are ABSENT, not merely last.
        for late in ("m.txt", "Ápple.txt", "b-nested/zz-a.txt"):
            assert late not in capped_rels



def test_a24_builtin_grep_and_glob_walk_in_a_deterministic_order():
    """A24 — the builtin file tools stop at their `limit`, so walk order decides WHICH
    results the model sees. `_walk_files` fed both from raw `os.scandir` order.

    Sorted by relative path in code point (the A25 shape), so a nested file sorts
    under its directory's name rather than by its own basename.
    """
    from toolnexus.builtin import _walk_files

    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "zdir"))
        os.makedirs(os.path.join(root, "Adir"))
        for rel in ("m.txt", "a.txt", os.path.join("zdir", "aaa.txt"), os.path.join("Adir", "zzz.txt")):
            with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                f.write("x")
        rels = [os.path.relpath(p, root) for p in _walk_files(root)]
        assert rels == [
            os.path.join("Adir", "zzz.txt"),
            "a.txt",
            "m.txt",
            os.path.join("zdir", "aaa.txt"),
        ]
        assert rels == sorted(rels)  # plain code point, no depth rule, no case folding


@pytest.mark.asyncio
async def test_a28_grep_orders_by_file_then_line_and_emits_what_it_sorts():
    """A28 — grep's whole `file:line:text` sequence is pinned, not just which files
    appear: files by relative path in code point, then LINE NUMBER ASCENDING within a
    file. And the emitted path is the SAME string the tool orders by — relative to
    `path`, `/`-separated — so it cannot sort by one thing and display another.
    """
    from toolnexus.builtin import create_builtin_tools

    grep = next(t for t in create_builtin_tools() if t.name == "grep")
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "Adir"))
        with open(os.path.join(root, "Adir", "z.txt"), "w", encoding="utf-8") as f:
            f.write("needle A\nquiet\nneedle B\n")   # lines 1 and 3, out of order if unsorted
        with open(os.path.join(root, "a.txt"), "w", encoding="utf-8") as f:
            f.write("quiet\nneedle C\n")             # line 2
        r = await grep.execute({"pattern": "needle", "path": root})
        assert r.is_error is False
        assert r.output.splitlines() == [
            "Adir/z.txt:1:needle A",
            "Adir/z.txt:3:needle B",
            "a.txt:2:needle C",
        ]
        # Emitted relative and `/`-separated — never an absolute machine path.
        assert root not in r.output


@pytest.mark.asyncio
async def test_a27c_the_builtin_cap_selects_a_different_SET_than_the_raw_walk():
    """A27c — prove the cap fixture is not passing by filesystem accident.

    The test first reproduces the RAW walk (the pre-fix LIFO `os.scandir` order) and
    asserts that its first N is a different SET from the sorted first N. Only then is
    the capped `glob` asserted — otherwise the assertion would hold whether or not the
    sort exists, which is how four fixtures in this batch passed vacuously.

    Shape: two root files fill a cap of 2 before the walk ever descends, while the
    nested file sorts FIRST by relative path.
    """
    from toolnexus.builtin import create_builtin_tools

    glob = next(t for t in create_builtin_tools() if t.name == "glob")
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "A-dir"))
        for rel in ("m.txt", "n.txt", os.path.join("A-dir", "zz.txt")):
            with open(os.path.join(root, rel), "w", encoding="utf-8") as f:
                f.write("x")

        # The raw walk the implementation used before the fix: LIFO stack, entries in
        # whatever order os.scandir hands them back.
        raw: list[str] = []
        stack = [root]
        while stack:
            d = stack.pop()
            for entry in os.scandir(d):
                if entry.is_dir(follow_symlinks=False):
                    stack.append(entry.path)
                else:
                    raw.append(os.path.relpath(entry.path, root).replace(os.sep, "/"))
        by_walk = set(raw[:2])
        by_sort = set(sorted(raw)[:2])
        # THE PRECONDITION: if these were equal the fixture would prove nothing.
        assert by_walk != by_sort, f"fixture is vacuous on this filesystem: {raw}"
        assert by_sort == {"A-dir/zz.txt", "m.txt"}

        r = await glob.execute({"pattern": "*.txt", "path": root, "limit": 2})
        assert set(r.output.splitlines()) == by_sort
        assert "A-dir/zz.txt" in r.output  # the nested file the raw walk reached last


@pytest.mark.asyncio
async def test_a28_grep_line_numbers_are_numeric_not_lexicographic():
    """A28 — line 2 must precede line 10. Sorting the rendered `path:line:text` as one
    string puts `:10:` before `:2:`, so within-file order has to come from the scan,
    not from a string sort.
    """
    from toolnexus.builtin import create_builtin_tools

    grep = next(t for t in create_builtin_tools() if t.name == "grep")
    with tempfile.TemporaryDirectory() as root:
        with open(os.path.join(root, "f.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(["needle" if i in (2, 10) else "quiet" for i in range(1, 12)]))
        r = await grep.execute({"pattern": "needle", "path": root})
        assert r.output.splitlines() == ["f.txt:2:needle", "f.txt:10:needle"]
        assert r.output.splitlines() != sorted(r.output.splitlines())  # not a string sort
