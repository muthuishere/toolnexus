"""§7E memory hierarchy + dreaming, driven by the shared fixtures
``examples/memory-hierarchy/fixture.json`` and ``examples/dreaming/fixture.json``.

Hermetic: scripted in-process LLM, virtual clock, temp dirs. The behavior reference is
``js/test/memory-hierarchy.test.ts``.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import threading
from pathlib import Path
from typing import Any

import pytest

from toolnexus import InMemoryConversationStore, Tool, ToolResult, transcript_search_tool
from toolnexus.agents import (
    DREAM_PROMPT,
    HEARTBEAT_PROMPT,
    agent,
    agent_from_dir,
    compose_soul,
    dreamer,
    memory_tool,
    start_agent,
)

from _agent_mocks import EXAMPLES, VirtualClock, openai_body, tool_call

MH = json.loads((EXAMPLES / "memory-hierarchy" / "fixture.json").read_text(encoding="utf-8"))
DR = json.loads((EXAMPLES / "dreaming" / "fixture.json").read_text(encoding="utf-8"))


def _sha(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def mk_home(base: Path, files: dict[str, str]) -> str:
    base.mkdir(parents=True, exist_ok=True)
    for name, body in files.items():
        p = base / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(body.encode("utf-8"))
    return str(base)


def snapshot(d: str) -> dict[str, str]:
    out: dict[str, str] = {}

    def walk(p: str, rel: str) -> None:
        for n in sorted(os.listdir(p)):
            f = os.path.join(p, n)
            if os.path.isdir(f):
                walk(f, f"{rel}{n}/")
            else:
                out[rel + n] = Path(f).read_bytes().decode("utf-8")

    walk(d, "")
    return out


class StubStore:
    def __init__(self, conversations: list[dict[str, Any]]) -> None:
        self.conversations = conversations
        self.reads = 0

    async def get(self, id: str):  # noqa: A002
        self.reads += 1
        return next((c["messages"] for c in self.conversations if c["id"] == id), None)

    async def save(self, id: str, messages):  # noqa: A002
        raise AssertionError("transcript search must never write")

    async def list(self):
        self.reads += 1
        return [{"id": c["id"]} for c in self.conversations]


class NoListStore:
    async def get(self, id: str):  # noqa: A002
        return None

    async def save(self, id: str, messages):  # noqa: A002
        return None


def _res(r: ToolResult) -> dict[str, Any]:
    return {"output": r.output, "isError": r.is_error}


# =========================================================================== #
# G1 — index cap
# =========================================================================== #
def _cap_memory(c: dict[str, Any]) -> str:
    if "memory" in c:
        return c["memory"]
    g = c["generate"]
    return "".join(g["line"].replace("<i>", str(i)) + "\n" for i in range(1, g["count"] + 1))


def _cap_kwargs(opts: dict[str, Any]) -> dict[str, Any]:
    kw: dict[str, Any] = {}
    if "memoryIndexCap" in opts:
        kw["memory_index_cap"] = opts["memoryIndexCap"]
    if "notes" in opts:
        kw["notes"] = opts["notes"]
    return kw


@pytest.mark.parametrize("c", MH["indexCap"]["cases"], ids=lambda c: c["name"])
def test_g1_index_cap(tmp_path, c):
    mem = _cap_memory(c)
    d = mk_home(tmp_path, {**c["files"], "MEMORY.md": mem})
    soul, _ = compose_soul(d, **_cap_kwargs(c["options"]))
    e = c["expect"]
    assert _sha(soul) == e["soulSha256"]
    assert len(soul.encode("utf-8")) == e["soulBytes"]
    assert soul.endswith(e["memorySectionTail"])
    assert (soul == compose_soul(d)[0]) == e["equalsUncappedSoul"]
    assert (tmp_path / "MEMORY.md").read_bytes().decode("utf-8") == mem  # disk untouched


def test_g1_from_dir_threads_cap(tmp_path):
    c = next(x for x in MH["indexCap"]["cases"] if x["name"] == "lines-cap-preset-notes-off")
    d = mk_home(tmp_path, {**c["files"], "MEMORY.md": _cap_memory(c)})
    soul = agent_from_dir(d, memory_index_cap=True).soul
    assert _sha(soul) == c["expect"]["soulSha256"]
    assert "- fact 200\n\n[MEMORY.md index truncated" in soul and "- fact 201" not in soul
    assert (tmp_path / "MEMORY.md").read_text(encoding="utf-8").count("\n") == 500


@pytest.mark.parametrize("bad", [{"maxLines": 0}, {"max_bytes": 0}, {"max_lines": 1.5}, {"max_lines": True}])
def test_g1_invalid_cap_raises(tmp_path, bad):
    d = mk_home(tmp_path, {"MEMORY.md": "- a\n"})
    with pytest.raises(ValueError):
        compose_soul(d, memory_index_cap=bad)


# =========================================================================== #
# G2 — notes tier
# =========================================================================== #
def test_g2_schemas_and_descriptions_pinned():
    off = memory_tool("/x")
    assert off.description == MH["memoryTool"]["notesOff"]["description"]
    assert json.dumps(off.input_schema) == json.dumps(MH["memoryTool"]["notesOff"]["inputSchema"])
    assert json.dumps(memory_tool("/x", notes=False).input_schema) == json.dumps(off.input_schema)
    on = memory_tool("/x", notes=True)
    assert on.description == MH["memoryTool"]["notesOn"]["description"]
    assert json.dumps(on.input_schema) == json.dumps(MH["memoryTool"]["notesOn"]["inputSchema"])


@pytest.mark.parametrize("c", MH["memoryTool"]["cases"], ids=lambda c: c["name"])
async def test_g2_memory_tool(tmp_path, c):
    d = mk_home(tmp_path, c["seed"])
    tool = memory_tool(d, notes=c["notes"])
    for op in c["ops"]:
        r = await tool.execute(op["args"])
        assert _res(r) == op["expect"], op["args"]
    assert snapshot(d) == c["expectFiles"]
    if c.get("soulMustNotContain"):
        assert c["soulMustNotContain"] not in compose_soul(d, notes=True)[0]


async def test_g2_list_skips_non_regular(tmp_path):
    cfg = MH["memoryTool"]["listSkipsNonRegular"]
    d = mk_home(tmp_path, cfg["regular"])
    for rel in cfg["dirs"]:
        os.makedirs(os.path.join(d, rel))
    try:
        for rel, target in cfg["symlinks"].items():
            os.symlink(target, os.path.join(d, rel))
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    r = await memory_tool(d, notes=True).execute({"action": "list"})
    assert _res(r) == {"output": cfg["expectList"], "isError": False}


def test_g2_from_dir_notes_wires_notes_tool(tmp_path):
    d = mk_home(tmp_path, {"SOUL.md": "S"})
    mem = next(t for t in agent_from_dir(d, notes=True).uses["tools"] if t.name == "memory")
    assert mem.description == MH["memoryTool"]["notesOn"]["description"]


# =========================================================================== #
# G3 — transcript search
# =========================================================================== #
def test_g3_schema_pinned():
    t = transcript_search_tool(StubStore([]))
    assert t.name == MH["transcriptSearch"]["defaults"]["name"]
    assert t.description == MH["transcriptSearch"]["description"]
    assert json.dumps(t.input_schema) == json.dumps(MH["transcriptSearch"]["inputSchema"])


@pytest.mark.parametrize("c", MH["transcriptSearch"]["cases"], ids=lambda c: c["name"])
async def test_g3_transcript_search(c):
    o = c.get("options") or {}
    kw = {}
    if "maxResults" in o:
        kw["max_results"] = o["maxResults"]
    if "snippetChars" in o:
        kw["snippet_chars"] = o["snippetChars"]
    convs = [] if c.get("emptyStore") else MH["transcriptSearch"]["store"]["conversations"]
    t = transcript_search_tool(StubStore(convs), **kw)
    assert _res(await t.execute(c["args"])) == c["expect"]


async def test_g3_empty_query_no_read_and_list_required():
    store = StubStore(MH["transcriptSearch"]["store"]["conversations"])
    r = await transcript_search_tool(store).execute({"query": ""})
    assert r.is_error and store.reads == 0
    with pytest.raises(ValueError) as ei:
        transcript_search_tool(NoListStore())
    assert str(ei.value) == MH["transcriptSearch"]["storeWithoutList"]["error"]
    with pytest.raises(ValueError):
        transcript_search_tool(store, max_results=0)


async def test_g3_shipped_in_memory_store():
    store = InMemoryConversationStore()
    await store.save("only", [{"role": "user", "content": "The vault code is 4417"}])
    r = await transcript_search_tool(store).execute({"query": "VAULT"})
    assert r.output == "only#0 user: The vault code is 4417"


# =========================================================================== #
# G4 — write safety
# =========================================================================== #
async def test_g4_concurrent_adds_two_tools_one_home(tmp_path):
    cfg = MH["writeSafety"]["concurrentAdds"]
    d = mk_home(tmp_path, {})
    tools = [memory_tool(d) for _ in range(cfg["writers"])]
    await asyncio.gather(
        *(t.execute({"action": "add", "text": f"w{w}-{n}"}) for w, t in enumerate(tools) for n in range(cfg["addsEach"]))
    )
    lines = (tmp_path / "MEMORY.md").read_text(encoding="utf-8").rstrip().split("\n")
    assert len(lines) == cfg["expectEntries"] and len(set(lines)) == cfg["expectEntries"]


def test_g4_concurrent_adds_from_threads(tmp_path):
    """The real per-path lock: the same 40 adds from OS threads (one event loop each)."""
    cfg = MH["writeSafety"]["concurrentAdds"]
    d = mk_home(tmp_path, {})
    # two tools over the SAME home spelled two ways (resolved path is the lock key)
    tools = [memory_tool(d), memory_tool(os.path.join(d, ".", ""))]
    barrier = threading.Barrier(cfg["writers"] * cfg["addsEach"])

    def worker(t: Tool, text: str) -> None:
        barrier.wait()
        asyncio.run(t.execute({"action": "add", "text": text}))

    threads = [
        threading.Thread(target=worker, args=(t, f"w{w}-{n}"))
        for w, t in enumerate(tools)
        for n in range(cfg["addsEach"])
    ]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    lines = (tmp_path / "MEMORY.md").read_text(encoding="utf-8").rstrip().split("\n")
    assert len(lines) == cfg["expectEntries"] and len(set(lines)) == cfg["expectEntries"]


def test_g4_concurrent_adds_via_symlink(tmp_path):
    cfg = MH["writeSafety"]["concurrentAddsViaSymlink"]
    real = tmp_path / "home"
    real.mkdir()
    link = tmp_path / "link"
    try:
        os.symlink(real, link)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    tools = [memory_tool(str(link)), memory_tool(str(real))]
    barrier = threading.Barrier(cfg["writers"] * cfg["addsEach"])

    def worker(t: Tool, text: str) -> None:
        barrier.wait()
        asyncio.run(t.execute({"action": "add", "text": text}))

    threads = [
        threading.Thread(target=worker, args=(t, f"w{w}-{n}"))
        for w, t in enumerate(tools)
        for n in range(cfg["addsEach"])
    ]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    lines = (real / cfg["file"]).read_text(encoding="utf-8").rstrip().split("\n")
    assert len(lines) == cfg["expectEntries"] and len(set(lines)) == cfg["expectEntries"]


async def test_g4_large_file_add_no_truncation_no_temp(tmp_path):
    cfg = MH["writeSafety"]["largeFileAdd"]
    original = cfg["fill"] * cfg["bytes"]
    d = mk_home(tmp_path, {"MEMORY.md": original})
    r = await memory_tool(d, notes=True).execute({"action": "add", "text": cfg["addText"]})
    assert not r.is_error, r.output
    assert (tmp_path / "MEMORY.md").read_text(encoding="utf-8") == original + f"\n- {cfg['addText']}\n"
    assert os.listdir(d) == ["MEMORY.md"]


def test_g4_existing_permission_bits_kept(tmp_path):
    d = mk_home(tmp_path, {"MEMORY.md": "- a\n"})
    os.chmod(tmp_path / "MEMORY.md", 0o640)
    asyncio.run(memory_tool(d).execute({"action": "add", "text": "b"}))
    assert os.stat(tmp_path / "MEMORY.md").st_mode & 0o777 == 0o640


# =========================================================================== #
# Dreaming
# =========================================================================== #
SCRIPTS = DR["mockLLM"]["scripts"]


class DreamTransport:
    """The fixture's scripted LLM: the dreamer is recognised by its system prompt."""

    def __init__(self, script: str = "dreamer-fix") -> None:
        self.script = script

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        msgs = payload["messages"]
        sys = next((str(m.get("content") or "") for m in msgs if m.get("role") == "system"), "")
        tool = [str(m.get("content")) for m in msgs if m.get("role") == "tool"]
        if "You are dreaming:" in sys:
            steps = SCRIPTS[self.script]
            if self.script == "dreamer-idle":
                return openai_body({"content": steps[0]["text"]})
            n = len(tool)
            call = lambda i: openai_body(  # noqa: E731
                tool_call(steps[i]["toolCalls"][0]["name"], steps[i]["toolCalls"][0]["args"], f"d{i}")
            )
            if n in (0, 1):
                return call(n)
            if n == 2:
                if "Mt. Moon exit is south" in tool[0] and "exit is north" in tool[1]:
                    return call(2)
                return openai_body({"content": "HEARTBEAT_OK"})
            if n == 3:
                return call(3)
            return openai_body({"content": steps[4]["text"]})
        if payload["model"] == "m-navigator":
            north = "Mt. Moon exit is north" in sys
            return openai_body({"content": "Head north out of Mt. Moon." if north else "Head south out of Mt. Moon."})
        return openai_body({"content": "HEARTBEAT_OK"})

    def open(self, url, headers, payload, timeout):  # noqa: A003
        raise NotImplementedError("mock is non-streaming")


def _store() -> StubStore:
    return StubStore(DR["store"]["conversations"])


def test_d0_dream_prompt_exact():
    assert DREAM_PROMPT == DR["dreamPrompt"]


async def test_d1_build_gate_mt_moon(tmp_path):
    g = DR["expect"]["D1_mtMoonBuildGate"]
    home = mk_home(tmp_path, DR["home"])
    t = DreamTransport("dreamer-fix")
    before = await agent_from_dir(home, model=g["before"]["model"]).run(g["before"]["prompt"], transport=t)
    assert before.text == g["before"]["text"]

    p = await dreamer(home, store=_store()).run(HEARTBEAT_PROMPT, transport=t)
    assert p.text == g["pass"]["replyText"]
    snap = snapshot(home)
    for f, body in g["after"]["files"].items():
        assert snap[f] == body, f
    assert len([l for l in snap["memory/log.md"].split("\n") if l.startswith("- ")]) == 1

    nxt = g["after"]["nextSession"]
    after = await agent_from_dir(home, model=nxt["model"]).run(nxt["prompt"], transport=t)
    assert after.text == nxt["text"]

    # D2 (second case): a second pass finds nothing to do — silent, nothing written.
    frozen = snapshot(home)
    again = await dreamer(home, store=_store()).run(HEARTBEAT_PROMPT, transport=t)
    assert "HEARTBEAT_OK" in again.text
    assert snapshot(home) == frozen


async def test_d2_idle_dreamer_writes_nothing(tmp_path):
    home = mk_home(tmp_path, DR["home"])
    frozen = snapshot(home)
    r = await dreamer(home, store=_store()).run(HEARTBEAT_PROMPT, transport=DreamTransport("dreamer-idle"))
    assert "HEARTBEAT_OK" in r.text
    assert snapshot(home) == frozen


def test_d3_dreamer_tools_exactly(tmp_path):
    e = DR["expect"]["D3_dreamerToolsExactly"]
    home = mk_home(tmp_path, DR["home"])

    async def _x(args=None, ctx=None):
        return ToolResult(output="x")

    fake = [Tool(name=n, description=n, input_schema={"type": "object"}, source="native", execute=_x) for n in e["personaTools"]]
    persona = agent_from_dir(home, tools=fake)
    assert [t.name for t in persona.uses["tools"]] == [*e["personaTools"], "memory"]
    tools = dreamer(home, store=_store()).uses["tools"]
    assert [t.name for t in tools] == e["dreamerTools"]
    assert tools[0].description == MH["memoryTool"]["notesOn"]["description"]


def test_d3b_dreamer_soul_name_store_checks(tmp_path):
    e = DR["expect"]["D3b_dreamerSoul"]
    plain = mk_home(tmp_path / "plain", DR["home"])
    assert dreamer(plain, store=_store()).soul == e["soulWithoutExtras"]
    assert dreamer(plain, store=_store()).name == "plain-dreamer"
    assert dreamer(plain, store=_store()).model == "inherit"
    extras = mk_home(tmp_path / "extras", {**DR["home"], "DREAM.md": e["DREAM.md"]})
    assert dreamer(extras, store=_store(), instructions=e["instructions"]).soul == e["soul"]
    with pytest.raises(ValueError, match=r"list\(\)"):
        dreamer(plain, store=NoListStore())
    with pytest.raises(ValueError, match="store is required"):
        dreamer(plain)
    with pytest.raises(ValueError, match="everyMs"):
        agent_from_dir(plain, dream={"every_ms": 0, "store": _store()})


async def test_d4_from_dir_dream_runs_on_same_runtime(tmp_path):
    e = DR["expect"]["D4_fromDirDream"]
    home = mk_home(tmp_path, DR["home"])
    clock = VirtualClock()
    reports: list[str] = []
    started = start_agent(
        agent_from_dir(home, model="m-quiet", dream={"every_ms": 50, "store": _store()}),
        every_ms=1000, on_report=reports.append, transport=DreamTransport("dreamer-fix"), clock=clock,
    )
    await asyncio.sleep(0)  # let the beat tasks arm their first sleep
    await clock.advance(0.050, settle=0.3)
    for _ in range(20):
        if snapshot(home).get("memory/log.md"):
            break
        await clock.advance(0.0, settle=0.1)
    snap = snapshot(home)
    for f, body in e["after"]["files"].items():
        assert snap.get(f) == body, f
    await started.stop()
    assert reports == []
    assert started.handle.state == "closed"


async def test_d6_dreamer_name_collision(tmp_path):
    e = DR["expect"]["D6_dreamerNameCollision"]
    home = mk_home(tmp_path, DR["home"])
    squatter = agent(f"{e['personaName']}-dreamer", does="squats the dreamer name", model="m-quiet")
    persona = agent_from_dir(
        home, name=e["personaName"], model="m-quiet", team=[squatter],
        dream={"every_ms": 50, "store": _store()},
    )
    before = len(asyncio.all_tasks())
    with pytest.raises(RuntimeError) as ei:
        start_agent(persona, every_ms=50, transport=DreamTransport(), clock=VirtualClock())
    assert str(ei.value) == e["error"]
    assert len(asyncio.all_tasks()) == before  # nothing scheduled


async def test_d5_absent_dream_unchanged(tmp_path):
    home = mk_home(tmp_path, DR["home"])
    frozen = snapshot(home)
    clock = VirtualClock()
    persona = agent_from_dir(home, model="m-quiet")
    assert len(persona.registry()) == 1
    started = start_agent(persona, every_ms=50, transport=DreamTransport(), clock=clock)
    for _ in range(4):
        await clock.advance(0.050)
    await started.stop()
    assert snapshot(home) == frozen
    assert not any("dreamer" in l for l in started.rt.trace)
