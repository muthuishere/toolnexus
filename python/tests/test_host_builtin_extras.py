"""add-go-host-asks ask #7 (SPEC §4C): background shells, websearch, notebook_edit,
MCP resource tools, grep context, read/grep truncation metadata.
Mirrors golang/host_builtin_extras_test.go."""
from __future__ import annotations

import asyncio
import json
import shutil
import sys
import time
from pathlib import Path

import pytest

from toolnexus import BackgroundShells, WebSearchResult, load_mcp
from toolnexus.builtin import create_builtin_tools

FIXTURE = Path(__file__).resolve().parents[2] / "examples" / "mcp-progress-server.mjs"
NODE = shutil.which("node")
OPT_IN = {"bash_output", "kill_shell", "websearch", "notebook_edit"}


def _tool(cfg, name):
    for t in create_builtin_tools(cfg):
        if t.name == name:
            return t
    raise AssertionError(f"builtin {name} not present")


def test_opt_in_builtins_absent_by_default():
    for cfg in (None, {}, {"base_dir": ""}):
        tools = create_builtin_tools(cfg)
        assert not OPT_IN & {t.name for t in tools}
        bash = next(t for t in tools if t.name == "bash")
        assert "run_in_background" not in bash.input_schema["properties"]


@pytest.mark.skipif(sys.platform == "win32", reason="posix")
async def test_background_shell_lifecycle():
    bg = BackgroundShells()
    try:
        cfg = {"background": bg, "kill_grace_ms": 100}
        bash, out, kill = _tool(cfg, "bash"), _tool(cfg, "bash_output"), _tool(cfg, "kill_shell")
        assert "run_in_background" in bash.input_schema["properties"]

        r = await bash.execute({"command": "echo one; sleep 0.2; echo two", "run_in_background": True})
        bid = (r.metadata or {}).get("backgroundId")
        assert not r.is_error and bid
        deadline = time.monotonic() + 5
        acc = ""
        while True:
            o = await out.execute({"id": bid})
            acc += o.output.split("\n[status:", 1)[0]
            if o.metadata["status"] == "exited":
                assert o.metadata["exitCode"] == 0, o.metadata
                break
            assert time.monotonic() < deadline, "never exited"
            await asyncio.sleep(0.05)
        assert acc.count("one") == 1 and acc.count("two") == 1, acc

        # a stubborn command with a child, killed as a group
        r = await bash.execute({"command": "trap '' TERM; sleep 30 & wait", "run_in_background": True})
        bid2 = r.metadata["backgroundId"]
        await asyncio.sleep(0.1)
        t0 = time.monotonic()
        k = await kill.execute({"id": bid2})
        assert not k.is_error and k.metadata["killed"] is True, k
        assert time.monotonic() - t0 < 2
        o = await out.execute({"id": bid2})
        assert o.metadata["status"] == "killed", o.metadata
        k2 = await kill.execute({"id": bid2})
        assert k2.metadata["killed"] is False
        assert (await out.execute({"id": "nope"})).is_error
        assert len(bg.list()) == 2
    finally:
        bg.close_all()


async def test_websearch_provider():
    got = {}

    def search(q, n):
        got["q"], got["n"] = q, n
        return [WebSearchResult(title="A", url="https://a.example", snippet="sa"),
                {"title": "B", "url": "https://b.example"}]

    ws = _tool({"web_search": search}, "websearch")
    assert ws.hints.read_only and ws.hints.open_world
    r = await ws.execute({"query": "go", "limit": 1})
    assert got == {"q": "go", "n": 1}
    assert r.output == "1. A\n   https://a.example\n   sa"


async def test_notebook_edit(tmp_path):
    nb = {"cells": [
        {"cell_type": "code", "id": "a", "metadata": {}, "source": ["x = 1\n"],
         "outputs": [{"output_type": "stream", "text": ["1"]}], "execution_count": 3},
        {"cell_type": "markdown", "id": "b", "metadata": {}, "source": ["# T"]},
    ], "metadata": {}, "nbformat": 4, "nbformat_minor": 5}
    p = tmp_path / "n.ipynb"
    p.write_text(json.dumps(nb))
    ne = _tool({"notebook_edit": True, "base_dir": str(tmp_path)}, "notebook_edit")
    load = lambda: json.loads(p.read_text())["cells"]  # noqa: E731

    r = await ne.execute({"path": "n.ipynb", "cell_id": "a", "new_source": "x = 2\ny = 3"})
    assert not r.is_error, r.output
    c0 = load()[0]
    assert c0["outputs"] == [] and c0["execution_count"] is None and c0["source"] == ["x = 2\n", "y = 3"]
    r = await ne.execute({"path": "n.ipynb", "cell_index": 0, "edit_mode": "insert",
                          "cell_type": "markdown", "new_source": "hi"})
    assert not r.is_error, r.output
    cells = load()
    assert len(cells) == 3 and cells[1]["cell_type"] == "markdown"
    r = await ne.execute({"path": "n.ipynb", "cell_id": "b", "edit_mode": "delete"})
    assert not r.is_error and len(load()) == 2 and r.metadata["files"]
    assert (await ne.execute({"path": "n.ipynb", "cell_id": "zz"})).is_error


async def test_grep_context_and_truncation(tmp_path):
    (tmp_path / "f.txt").write_text("a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n")
    cfg = {"base_dir": str(tmp_path)}
    grep = _tool(cfg, "grep")
    r = await grep.execute({"pattern": "HIT", "context": 1})
    assert r.output == "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
    r = await grep.execute({"pattern": "HIT", "before": 0, "after": 4})
    assert r.output == "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
    r = await grep.execute({"pattern": "HIT", "limit": 1})
    assert r.output == "f.txt:3:HIT1" and r.metadata == {"count": 1, "truncated": True, "total": 2}
    r = await grep.execute({"pattern": "HIT"})
    assert r.output == "f.txt:3:HIT1\nf.txt:8:HIT2" and r.metadata == {"count": 2}
    read = _tool(cfg, "read")
    r = await read.execute({"path": "f.txt", "limit": 2})
    assert r.output == "a\nb"
    assert r.metadata == {"truncated": True, "totalLines": 9, "nextOffset": 3}
    r = await read.execute({"path": "f.txt", "offset": 8})
    assert r.output == "HIT2\ng\n" and r.metadata is None


@pytest.mark.skipif(NODE is None, reason="node not installed")
async def test_mcp_resource_tools():
    src = await load_mcp({"mcp": {"progress": {"command": [NODE, str(FIXTURE)], "timeout": 5000}}})
    try:
        lst, read = src.resource_tools()
        assert lst.hints.read_only and read.hints.read_only
        r = await lst.execute({})
        assert not r.is_error
        assert r.output == "progress fixture://hello — hello (text/plain): A fixed text resource."
        r = await read.execute({"server": "progress", "uri": "fixture://hello"})
        assert not r.is_error and r.output == "hello resource"
        assert (await read.execute({"server": "progress", "uri": "fixture://nope"})).is_error
        assert (await read.execute({"server": "nope", "uri": "x"})).is_error
    finally:
        try:
            await src.close()
        except Exception:  # noqa: BLE001 — pre-existing SDK teardown race
            pass
