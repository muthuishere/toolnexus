"""The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
what a relative path means, and what a timeout kills.

Every assertion carries its control. The spike that produced these fixes twice
reported a clean kill from a broken probe, so "no orphan" is only evidence next
to a run proving the command can write the marker at all
(spikes/builtin-host-boundary/SPIKE.md §1).
"""
from __future__ import annotations

import asyncio
import os
import sys
import time

import pytest

from toolnexus import create_builtin_tools, select_builtins
from toolnexus.builtin import builtin_shell

posix_only = pytest.mark.skipif(sys.platform == "win32", reason="POSIX command shape")


def tool_named(cfg, name):
    for t in create_builtin_tools(cfg):
        if t.name == name:
            return t
    raise AssertionError(f"builtin {name} not found")


async def run(tool, args, ctx=None):
    return await tool.execute(args, ctx)


# The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
# exec-optimising the single command away — the difference between measuring an
# orphan and measuring nothing.
def orphan_command(marker: str) -> str:
    return f"sleep 0.2; sh -c 'sleep 1; touch {marker}'"


# --------------------------------------------------------------------------- #
# #102 — a timeout kills the job, not the shell
# --------------------------------------------------------------------------- #
@posix_only
@pytest.mark.asyncio
async def test_timeout_kills_the_whole_job(tmp_path):
    marker = str(tmp_path / "orphan.marker")
    res = await run(tool_named(None, "bash"), {"command": orphan_command(marker), "timeout": 300})

    assert res.is_error is True
    assert "timed out" in res.output
    assert res.metadata["timedOut"] is True
    assert res.metadata["killedTree"] is True

    time.sleep(2)
    assert not os.path.exists(marker), "the grandchild outlived the kill"


@posix_only
@pytest.mark.asyncio
async def test_control_the_probe_command_can_write_the_marker(tmp_path):
    marker = str(tmp_path / "control.marker")
    res = await run(tool_named(None, "bash"), {"command": orphan_command(marker), "timeout": 20_000})
    assert res.is_error is False, res.output
    assert os.path.exists(marker), "the command cannot write the marker — the orphan test proves nothing"


@posix_only
@pytest.mark.asyncio
async def test_cancelling_the_call_stops_the_work(tmp_path):
    """The handle has to be published OUT of the worker frame for this to work:
    `asyncio.to_thread` cannot be interrupted, so cancelling only helps if the
    cancel path can reach the Popen the thread created (measured: without that,
    a cancelled run still orphans)."""
    marker = str(tmp_path / "cancelled.marker")
    bash = tool_named(None, "bash")
    task = asyncio.ensure_future(run(bash, {"command": orphan_command(marker), "timeout": 60_000}))
    await asyncio.sleep(0.3)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    await asyncio.sleep(2.5)
    assert not os.path.exists(marker), "cancellation left the job running"


@posix_only
@pytest.mark.asyncio
async def test_timeout_returns_as_soon_as_the_job_is_gone(tmp_path):
    """The 2000 ms window bounds how long the KILL may take, not how long the
    CALLER waits. Two ports used to sleep through it regardless, turning a 300 ms
    timeout into a 2.3 s call (spikes/builtin-host-boundary/stress/STRESS.md §3)."""
    marker = str(tmp_path / "grace.marker")
    started = time.monotonic()
    res = await run(tool_named(None, "bash"), {"command": orphan_command(marker), "timeout": 300})
    elapsed = (time.monotonic() - started) * 1000

    assert res.is_error is True
    assert elapsed < 1500, f"a 300 ms timeout took {elapsed:.0f} ms — the caller waited out the grace window"


# --------------------------------------------------------------------------- #
# #100 — the interpreter is chosen, and reported
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
async def test_resolved_interpreter_is_reported():
    res = await run(tool_named(None, "bash"), {"command": "echo hi"})
    assert res.metadata.get("shell"), "metadata['shell'] must name the interpreter that ran"
    if sys.platform != "win32":
        assert res.metadata["shell"] == "/bin/sh -c"


@posix_only
@pytest.mark.asyncio
async def test_host_supplied_shell_is_used_verbatim():
    res = await run(tool_named({"shell": ["/bin/sh", "-c"]}, "bash"), {"command": "echo verbatim"})
    assert res.is_error is False, res.output
    assert "verbatim" in res.output
    assert res.metadata["shell"] == "/bin/sh -c"


def test_builtin_shell_reports_detection_and_bash_can_be_disabled():
    assert builtin_shell()
    names = [t.name for t in select_builtins({"tools": {"bash": False}})]
    assert "bash" not in names


# --------------------------------------------------------------------------- #
# #101 — one base directory, and optional confinement
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
async def test_base_dir_scopes_relative_paths(tmp_path):
    base = str(tmp_path)
    res = await run(tool_named({"base_dir": base}, "write"), {"path": "sub/nested.txt", "content": "landed"})
    assert res.is_error is False, res.output
    assert os.path.exists(os.path.join(base, "sub", "nested.txt"))
    assert not os.path.exists(os.path.join(os.getcwd(), "sub", "nested.txt")), "leaked into the process cwd"

    read = await run(tool_named({"base_dir": base}, "read"), {"path": "sub/nested.txt"})
    assert read.output == "landed"


@pytest.mark.asyncio
async def test_empty_base_dir_is_todays_behaviour(tmp_path):
    target = str(tmp_path / "absolute.txt")
    res = await run(tool_named(None, "write"), {"path": target, "content": "x"})
    assert res.is_error is False, res.output
    assert os.path.exists(target)


@pytest.mark.asyncio
async def test_apply_patch_resolves_paths_inside_the_patch_text(tmp_path):
    base = str(tmp_path)
    patch = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch"
    res = await run(tool_named({"base_dir": base}, "apply_patch"), {"patchText": patch})
    assert res.is_error is False, res.output
    assert os.path.exists(os.path.join(base, "pkg", "new.txt")), "the path inside the patch text was not resolved"


@posix_only
@pytest.mark.asyncio
async def test_bash_defaults_its_workdir_to_base_dir(tmp_path):
    (tmp_path / "marker.txt").write_text("x")
    res = await run(tool_named({"base_dir": str(tmp_path)}, "bash"), {"command": "ls marker.txt"})
    assert res.is_error is False, res.output
    assert "marker.txt" in res.output


@pytest.mark.asyncio
async def test_confinement_refuses_escapes_and_still_serves_what_is_inside(tmp_path):
    base = tmp_path / "base"
    outside = tmp_path / "outside"
    base.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("secret")
    read = tool_named({"base_dir": str(base), "confine_to_base_dir": True}, "read")

    for p in ["../outside/secret.txt", str(outside / "secret.txt")]:
        res = await run(read, {"path": p})
        assert res.is_error is True, f"{p} should be refused"
        assert "outside baseDir" in res.output

    (base / "ok.txt").write_text("fine")
    inside = await run(read, {"path": "ok.txt"})
    assert inside.is_error is False, "a path inside baseDir must still be read"


@posix_only
@pytest.mark.asyncio
async def test_confinement_follows_symlinks_before_deciding(tmp_path):
    base = tmp_path / "base"
    outside = tmp_path / "outside"
    base.mkdir()
    outside.mkdir()
    (outside / "secret.txt").write_text("secret")
    os.symlink(outside, base / "link")

    res = await run(tool_named({"base_dir": str(base), "confine_to_base_dir": True}, "read"), {"path": "link/secret.txt"})
    assert res.is_error is True, "a symlink out of baseDir must be refused"


@pytest.mark.asyncio
async def test_confinement_covers_paths_that_do_not_exist_yet(tmp_path):
    write = tool_named({"base_dir": str(tmp_path), "confine_to_base_dir": True}, "write")
    refused = await run(write, {"path": "../escape.txt", "content": "x"})
    assert refused.is_error is True
    allowed = await run(write, {"path": "deep/new/file.txt", "content": "x"})
    assert allowed.is_error is False, allowed.output


# --------------------------------------------------------------------------- #
# §4A — grep emits the string it sorted by
# --------------------------------------------------------------------------- #
@pytest.mark.asyncio
async def test_grep_emits_walk_root_relative_forward_slashed_paths(tmp_path):
    (tmp_path / "tree" / "sub").mkdir(parents=True)
    (tmp_path / "tree" / "sub" / "a.txt").write_text("needle\n")

    res = await run(tool_named({"base_dir": str(tmp_path)}, "grep"), {"pattern": "needle", "path": "tree"})
    assert res.is_error is False, res.output
    assert res.output == "sub/a.txt:1:needle"
    assert "\\" not in res.output, "§4A requires `/` on every platform"
