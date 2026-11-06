"""add-host-engine-needs (command+args, FileConversationStore, list/delete) and
add-mcp-progress-timeout (idle timeout) — hermetic python tests."""
from __future__ import annotations

import asyncio
import json
import os
import shutil
from pathlib import Path

import pytest

from toolnexus.mcp_source import parse_mcp_config
from toolnexus import (
    ConversationEntry,
    FileConversationStore,
    InMemoryConversationStore,
    load_mcp,
    normalize_command,
)

FIXTURE = Path(__file__).resolve().parents[2] / "examples" / "mcp-progress-server.mjs"
NODE = shutil.which("node")


# ---- command + args --------------------------------------------------------

@pytest.mark.parametrize(
    "cfg,argv",
    [
        ({"command": "npx", "args": ["-y", "pkg"]}, ["npx", "-y", "pkg"]),
        ({"command": ["uvx"], "args": ["tool", "--flag"]}, ["uvx", "tool", "--flag"]),
        ({"command": ["node", "server.js", "--x"]}, ["node", "server.js", "--x"]),
        ({"command": "server"}, ["server"]),
        ({"command": "cmd", "args": ["a b", "c  d"]}, ["cmd", "a b", "c  d"]),
        ({"command": ["a", "b"], "args": ["c"]}, ["a", "b", "c"]),
    ],
)
def test_command_normalization(cfg, argv):
    assert normalize_command("srv", cfg) == argv


@pytest.mark.parametrize(
    "cfg,field",
    [
        ({"command": ""}, "command"),
        ({"command": []}, "command"),
        ({"command": ["a", 1]}, "command"),
        ({"command": 5}, "command"),
        ({}, "command"),
        ({"command": "a", "args": "b"}, "args"),
        ({"command": "a", "args": ["b", 2]}, "args"),
    ],
)
def test_command_invalid_names_server_and_field(cfg, field):
    with pytest.raises(ValueError) as ei:
        normalize_command("my-srv", cfg)
    assert '"my-srv"' in str(ei.value) and f"invalid {field}" in str(ei.value)


def test_invalid_command_fails_the_parse_naming_the_server():
    with pytest.raises(ValueError, match='mcp server "bad"'):
        parse_mcp_config({"mcp": {"bad": {"command": ""}, "ok": {"command": "node"}}})


# ---- FileConversationStore -------------------------------------------------

MSGS = [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "yo"}]


async def test_file_store_roundtrip_across_instances(tmp_path):
    root = tmp_path / "convs"
    await FileConversationStore(root).save("c1", MSGS)
    assert await FileConversationStore(str(root)).get("c1") == MSGS
    assert await FileConversationStore(root).get("missing") is None
    body = json.loads((root / "c1.json").read_text())
    assert body == {"version": 1, "messages": MSGS}
    if os.name == "posix":
        assert (root / "c1.json").stat().st_mode & 0o777 == 0o600
        assert root.stat().st_mode & 0o777 == 0o700


def test_file_store_requires_root():
    with pytest.raises(ValueError):
        FileConversationStore("")
    with pytest.raises((ValueError, TypeError)):
        FileConversationStore(None)  # type: ignore[arg-type]


@pytest.mark.parametrize("bad", ["../x", "..", ".", "a/b", "a\\b", "", "x" * 129, "sp ace", "/abs"])
async def test_file_store_rejects_traversal_ids(tmp_path, bad):
    s = FileConversationStore(tmp_path)
    for op in (s.get(bad), s.save(bad, MSGS), s.delete(bad)):
        with pytest.raises(ValueError):
            await op
    assert list(tmp_path.iterdir()) == []


async def test_file_store_corrupt_is_error(tmp_path):
    (tmp_path / "c.json").write_text("{not json")
    with pytest.raises(ValueError, match="corrupt"):
        await FileConversationStore(tmp_path).get("c")


async def test_file_store_unknown_version_is_error(tmp_path):
    (tmp_path / "c.json").write_text(json.dumps({"version": 2, "messages": []}))
    with pytest.raises(ValueError, match="version"):
        await FileConversationStore(tmp_path).get("c")


async def test_file_store_list_newest_first_ties_by_id(tmp_path):
    s = FileConversationStore(tmp_path)
    for i in ("b", "a", "c", "old"):
        await s.save(i, MSGS)
    os.utime(tmp_path / "a.json", (2000, 2000))
    os.utime(tmp_path / "b.json", (2000, 2000))
    os.utime(tmp_path / "c.json", (3000, 3000))
    os.utime(tmp_path / "old.json", (1000, 1000))
    (tmp_path / ".x.abc.tmp").write_text("")
    (tmp_path / "bad name.json").write_text("{}")
    (tmp_path / "notes.txt").write_text("")
    got = await s.list()
    assert [e.id for e in got] == ["c", "a", "b", "old"]
    assert isinstance(got[0], ConversationEntry) and got[0].updated_at.timestamp() == 3000


async def test_file_store_list_missing_root_is_empty(tmp_path):
    assert await FileConversationStore(tmp_path / "nope").list() == []


async def test_file_store_delete_idempotent(tmp_path):
    s = FileConversationStore(tmp_path)
    await s.save("c", MSGS)
    await s.delete("c")
    await s.delete("c")
    assert await s.get("c") is None
    assert await s.list() == []


async def test_file_store_concurrent_saves_leave_valid_file_no_temps(tmp_path):
    s = FileConversationStore(tmp_path)
    variants = [[{"role": "user", "content": str(i) * 2000}] for i in range(30)]
    await asyncio.gather(*(s.save("same", v) for v in variants))
    got = await s.get("same")
    assert got in variants
    assert sorted(p.name for p in tmp_path.iterdir()) == ["same.json"]


# ---- InMemoryConversationStore list/delete ---------------------------------

async def test_in_memory_list_delete():
    s = InMemoryConversationStore()
    await s.save("b", MSGS)
    await asyncio.sleep(0.002)
    await s.save("a", MSGS)
    assert [e.id for e in await s.list()] == ["a", "b"]
    await s.delete("a")
    await s.delete("a")
    assert [e.id for e in await s.list()] == ["b"]
    assert await s.get("a") is None


# ---- MCP idle timeout (shared fixture) -------------------------------------

needs_node = pytest.mark.skipif(NODE is None, reason="node not installed")


async def _slow(args, timeout=300):
    src = await load_mcp(
        {"mcp": {"p": {"command": [NODE, str(FIXTURE)], "timeout": timeout}}}
    )
    try:
        assert src.status["p"] == "connected"
        tool = next(t for t in src.tools if t.name == "p_slow")
        return await tool.execute(args)
    finally:
        # Pre-existing MCP SDK teardown race (not this change): a server that answers
        # after we gave up writes into the already-closed read stream during
        # stdio_client shutdown and close() raises BrokenResourceError. The old hard
        # deadline hit the same race; tolerate it here.
        try:
            await src.close()
        except Exception:  # noqa: BLE001
            pass


@needs_node
async def test_idle_timeout_progress_extends_call():
    # The call outlives the idle budget (2000 ms vs 800 ms), so it only succeeds if
    # progress extends it. Progress every 20 ms gives a 40x margin over the budget:
    # with 100 ms vs 300 ms, one scheduler stall under parallel CI load starved a gap
    # past the budget and the call timed out.
    r = await _slow({"totalMs": 2000, "intervalMs": 20}, timeout=800)
    assert not r.is_error, r.output
    body = json.loads(r.output)
    assert body["progressTokenReceived"] is True
    assert body["notificationsSent"] > 0


@needs_node
async def test_idle_timeout_silent_server_times_out():
    r = await _slow({"totalMs": 1200, "intervalMs": 0})
    assert r.is_error and "Timed out" in r.output


@needs_node
async def test_idle_timeout_wrong_token_does_not_extend():
    r = await _slow({"totalMs": 1200, "intervalMs": 100, "wrongToken": True})
    assert r.is_error and "Timed out" in r.output


@needs_node
async def test_idle_timeout_short_call_without_progress_succeeds():
    r = await _slow({"totalMs": 50, "intervalMs": 0})
    assert not r.is_error and json.loads(r.output)["progressTokenReceived"] is True


def test_file_store_sync_modes(tmp_path):
    with pytest.raises(ValueError, match="sync mode"):
        FileConversationStore(tmp_path, sync="bogus")
    for i, mode in enumerate([None, "full", "fsync", "none"]):
        root = tmp_path / f"m{i}"
        s = FileConversationStore(root) if mode is None else FileConversationStore(root, sync=mode)
        asyncio.run(s.save("c1", [{"role": "user", "content": "hi"}]))
        assert asyncio.run(FileConversationStore(root).get("c1")) == [{"role": "user", "content": "hi"}]
        assert sorted(os.listdir(root)) == ["c1.json"], mode
