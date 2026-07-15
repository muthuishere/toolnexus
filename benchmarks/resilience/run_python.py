#!/usr/bin/env python3
"""toolnexus (Python port) RESILIENCE runner.

Runs the eight adverse-condition scenarios (R1-R8) against the shared resilience
mock LLM + local MCP stubs, and prints ONE JSON line per scenario:

    {"lang","scenario","outcome","detail","elapsed_ms"}

outcome ∈ graceful | crash | hang | wrong. "graceful" = the port isolated /
surfaced / bounded the failure instead of crashing, hanging, or silently
returning a wrong answer. Nothing here calls a real LLM or leaves the box.

Env inputs (set by run_resilience.py):
  BENCH_REPO   repo root
  MCP_PYTHON   python that launches the stdio MCP stubs
  MOCK_BASE    base url of the resilience mock (e.g. http://127.0.0.1:PORT)
  MCP_HANG     path to mcp_hang.py
"""
from __future__ import annotations

import asyncio
import json
import os
import socket
import tempfile
import time
import uuid

from toolnexus import create_toolkit, create_client, define_tool

REPO = os.environ["BENCH_REPO"]
MCP_PY = os.environ["MCP_PYTHON"]
MOCK_BASE = os.environ.get("MOCK_BASE", "http://127.0.0.1:8901")
MCP_HANG = os.environ["MCP_HANG"]
GOOD_SERVER = f"{REPO}/benchmarks/mcp_server.py"

HARD_CAP_S = 20.0  # a scenario that runs past this is a "hang"


def good_cfg():
    return {"type": "local", "command": [MCP_PY, GOOD_SERVER], "enabled": True, "timeout": 30000}


def closed_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def boom_tool():
    def boom(**_kwargs):
        raise RuntimeError("boom: native tool failed on purpose")
    return define_tool(boom, name="boom", description="A tool that always raises.",
                       input_schema={"type": "object", "properties": {}})


# --------------------------------------------------------------------------- #
# Scenario bodies. Each returns (outcome, detail).
# --------------------------------------------------------------------------- #
async def r1_bad_command():
    cfg = {"mcpServers": {
        "good": good_cfg(),
        "bad": {"type": "local", "command": ["/nonexistent/tn-badcmd-xyz"], "enabled": True, "timeout": 2000},
    }}
    tk = await create_toolkit(mcp_config=cfg, builtins=False)
    try:
        st = tk.mcp_status()
        names = {t.name for t in tk.tools()}
        ok = st.get("bad") == "failed" and st.get("good") == "connected" and len(names) > 0
        return ("graceful" if ok else "wrong",
                f"status={st} tools={sorted(names)}")
    finally:
        await tk.close()


async def r2_remote_unreachable():
    port = closed_port()
    cfg = {"mcpServers": {
        "good": good_cfg(),
        "remote": {"type": "remote", "url": f"http://127.0.0.1:{port}/mcp", "enabled": True, "timeout": 2000},
    }}
    tk = await create_toolkit(mcp_config=cfg, builtins=False)
    try:
        st = tk.mcp_status()
        names = {t.name for t in tk.tools()}
        ok = st.get("remote") == "failed" and st.get("good") == "connected" and len(names) > 0
        return ("graceful" if ok else "wrong", f"status={st} tools={sorted(names)}")
    finally:
        await tk.close()


async def r3_hang():
    cfg = {"mcpServers": {
        "good": good_cfg(),
        "hang": {"type": "local", "command": [MCP_PY, MCP_HANG], "enabled": True, "timeout": 2000},
    }}
    tk = await create_toolkit(mcp_config=cfg, builtins=False)
    try:
        st = tk.mcp_status()
        names = {t.name for t in tk.tools()}
        ok = st.get("hang") == "failed" and st.get("good") == "connected" and len(names) > 0
        return ("graceful" if ok else "wrong", f"status={st} tools={sorted(names)}")
    finally:
        await tk.close()


async def r4_malformed_json():
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write('{ "mcpServers": { "x": { "type": "local", ')  # truncated / invalid
        path = f.name
    try:
        try:
            tk = await create_toolkit(mcp_config=path, builtins=False)
        except Exception as e:  # noqa: BLE001 — the graceful path
            return ("graceful", f"raised {type(e).__name__}: {str(e)[:80]}")
        # No exception ⇒ it must not have silently succeeded with a broken config.
        await tk.close()
        return ("wrong", "malformed config did not raise")
    finally:
        os.unlink(path)


async def _run_llm(base_suffix, *, retries=2, tools=None, expect="raise"):
    tk = await create_toolkit(extra_tools=tools or [], builtins=False)
    try:
        client = create_client(base_url=f"{MOCK_BASE}/{base_suffix}", style="openai",
                               model="mock-model", api_key="sk-mock-not-real",
                               retries=retries, retry_base_ms=1)
        res = await client.run("hello", tk)
        return res
    finally:
        await tk.close()


async def r5_out_of_credits():
    try:
        res = await _run_llm("e402")
        return ("wrong", f"402 did not surface; text={res.text!r}")
    except Exception as e:  # noqa: BLE001
        return ("graceful", f"surfaced {type(e).__name__}: {str(e)[:80]}")


async def r6_rate_limit_then_ok():
    suffix = f"retry/{uuid.uuid4().hex}"
    try:
        res = await _run_llm(suffix, retries=3)
    except Exception as e:  # noqa: BLE001
        return ("crash", f"did not recover from 429: {type(e).__name__}: {str(e)[:80]}")
    if res.text and "RESILIENCE_OK" in res.text:
        return ("graceful", f"retried then succeeded: text={res.text!r}")
    return ("wrong", f"unexpected final text={res.text!r}")


async def r7_persistent_500():
    try:
        res = await _run_llm("e500", retries=2)
        return ("wrong", f"500 did not surface; text={res.text!r}")
    except Exception as e:  # noqa: BLE001
        return ("graceful", f"surfaced after retries {type(e).__name__}: {str(e)[:80]}")


async def r8_tool_throws():
    try:
        res = await _run_llm("boom", tools=[boom_tool()])
    except Exception as e:  # noqa: BLE001
        return ("crash", f"tool error was not fed back: {type(e).__name__}: {str(e)[:80]}")
    boom_errs = [c for c in res.tool_calls if c.get("name") == "boom" and c.get("is_error")]
    if res.text and "RESILIENCE_OK" in res.text and boom_errs:
        return ("graceful", f"tool error fed back, loop reached final: text={res.text!r}")
    return ("wrong", f"text={res.text!r} boom_errors={len(boom_errs)}")


SCENARIOS = {
    "R1": r1_bad_command, "R2": r2_remote_unreachable, "R3": r3_hang,
    "R4": r4_malformed_json, "R5": r5_out_of_credits, "R6": r6_rate_limit_then_ok,
    "R7": r7_persistent_500, "R8": r8_tool_throws,
}


async def run_one(key, fn):
    t0 = time.perf_counter()
    try:
        outcome, detail = await asyncio.wait_for(fn(), timeout=HARD_CAP_S)
    except asyncio.TimeoutError:
        outcome, detail = "hang", f"exceeded {HARD_CAP_S}s hard cap"
    except Exception as e:  # noqa: BLE001 — an unexpected throw from the harness itself
        outcome, detail = "crash", f"harness caught {type(e).__name__}: {str(e)[:100]}"
    elapsed = (time.perf_counter() - t0) * 1000.0
    return {"lang": "python", "scenario": key, "outcome": outcome,
            "detail": detail, "elapsed_ms": round(elapsed, 1)}


async def main():
    only = os.environ.get("ONLY_SCENARIO")
    for key, fn in SCENARIOS.items():
        if only and key != only:
            continue
        line = await run_one(key, fn)
        print(json.dumps(line), flush=True)


if __name__ == "__main__":
    asyncio.run(main())
