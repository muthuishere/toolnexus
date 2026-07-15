#!/usr/bin/env python3
"""Orchestrator for the toolnexus RESILIENCE benchmark.

Starts ONE shared, zero-cost resilience mock LLM (same wire for every port —
fairness), then invokes each language runner once. Each runner executes the
eight adverse-condition scenarios (R1-R8) and prints one JSON line per scenario;
this script aggregates them to ``results.json`` and prints a language-wise matrix.

Everything is hermetic: no real LLM, no registry token, no code in any port's
``src/`` is modified — the runners only import/compile the local ports.

Scenarios
  R1 MCP server won't start (bad command)     -> isolate: that server "failed", others present
  R2 MCP remote unreachable (closed port)     -> isolate + bounded
  R3 MCP server hangs (never responds)         -> per-server timeout -> "failed", bounded
  R4 malformed mcp.json                        -> clear error, no crash
  R5 LLM out-of-credits (402)                  -> surfaced error, no hang (§8 default: non-retryable -> fail)
  R6 LLM rate-limit then ok (429 -> 200)       -> retried, succeeds
  R7 LLM persistent 500                        -> retries exhausted -> surfaced error, bounded
  R8 native tool throws mid-run                -> isError fed back, loop reaches a final answer

Toolchain autodetection: a port runs only if its toolchain (and, for Python, a
toolnexus-installed interpreter via ``RES_PY``) is present; otherwise it is
recorded as ``pending``. Java / C# are recorded as ``pending`` (harness stubbed).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import resilience_mock_llm

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MCP_HANG = str(HERE / "mcp_hang.py")

SCENARIOS = ["R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8"]
SCENARIO_DESC = {
    "R1": "MCP server won't start (bad command)",
    "R2": "MCP remote unreachable (closed port)",
    "R3": "MCP server hangs (never responds)",
    "R4": "Malformed mcp.json",
    "R5": "LLM out-of-credits / auth (402)",
    "R6": "LLM rate-limit then ok (429->200)",
    "R7": "LLM persistent 500 (retries exhausted)",
    "R8": "Native tool throws mid-run",
}

RUNNER_TIMEOUT = 180  # seconds per language runner (all 8 scenarios)


def find_python() -> str | None:
    """A python interpreter with the toolnexus package installed."""
    cand = os.environ.get("RES_PY") or os.environ.get("TN_PY")
    if cand and Path(cand).exists():
        return cand
    guess = "/tmp/bench-venvs/toolnexus/bin/python"
    if Path(guess).exists():
        return guess
    # last resort: the current interpreter if it can import toolnexus
    try:
        subprocess.run([sys.executable, "-c", "import toolnexus"], check=True,
                       capture_output=True)
        return sys.executable
    except Exception:  # noqa: BLE001
        return None


def mcp_python(py: str | None) -> str:
    """Any python launches the stdlib MCP stubs; prefer the toolnexus one, else system."""
    return py or shutil.which("python3") or sys.executable


def base_env(mock_base: str, mcp_py: str) -> dict:
    env = dict(os.environ)
    env.update({
        "BENCH_REPO": str(REPO),
        "MCP_PYTHON": mcp_py,
        "MOCK_BASE": mock_base,
        "MCP_HANG": MCP_HANG,
    })
    return env


def parse_lines(stdout: str, lang: str) -> dict:
    got = {}
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            rec = json.loads(line)
        except Exception:  # noqa: BLE001
            continue
        if rec.get("lang") and rec.get("scenario"):
            got[rec["scenario"]] = rec
    return got


def run_python(env, mcp_py) -> dict:
    py = env["_RES_PY"]
    p = subprocess.run([py, str(HERE / "run_python.py")], env=env,
                       capture_output=True, text=True, timeout=RUNNER_TIMEOUT)
    return parse_lines(p.stdout, "python") or _all_error("python", p.stderr)


def run_js(env) -> dict:
    node = shutil.which("node")
    if not node or not (REPO / "js" / "dist" / "index.js").exists():
        return _all_pending("js", "node or js/dist not available")
    p = subprocess.run([node, str(HERE / "run_js.mjs")], env=env,
                       capture_output=True, text=True, timeout=RUNNER_TIMEOUT)
    return parse_lines(p.stdout, "js") or _all_error("js", p.stderr)


def run_go(env) -> dict:
    go = shutil.which("go")
    if not go:
        return _all_pending("go", "go toolchain not available")
    binp = HERE / "run_go" / ".bin_res_go"
    build = subprocess.run([go, "build", "-o", str(binp), "."],
                           cwd=str(HERE / "run_go"), capture_output=True, text=True)
    if build.returncode != 0:
        return _all_error("go", "build failed: " + build.stderr)
    p = subprocess.run([str(binp)], env=env, capture_output=True, text=True,
                       timeout=RUNNER_TIMEOUT)
    return parse_lines(p.stdout, "go") or _all_error("go", p.stderr)


def run_elixir(env) -> dict:
    mix = shutil.which("mix")
    if not mix:
        return _all_pending("elixir", "mix / elixir not available")
    p = subprocess.run([mix, "run", str(HERE / "run_elixir.exs")],
                       cwd=str(REPO / "elixir"), env=env,
                       capture_output=True, text=True, timeout=RUNNER_TIMEOUT)
    return parse_lines(p.stdout, "elixir") or _all_error("elixir", p.stderr)


def _all_pending(lang, why) -> dict:
    return {s: {"lang": lang, "scenario": s, "outcome": "pending", "detail": why,
                "elapsed_ms": None} for s in SCENARIOS}


def _all_error(lang, stderr) -> dict:
    detail = "runner produced no output: " + (stderr or "")[-200:]
    return {s: {"lang": lang, "scenario": s, "outcome": "crash", "detail": detail,
                "elapsed_ms": None} for s in SCENARIOS}


def main():
    py = find_python()
    mcp_py = mcp_python(py)

    srv, port = resilience_mock_llm.start_in_process()
    mock_base = f"http://127.0.0.1:{port}"
    print(f"[resilience] mock LLM on {mock_base}", file=sys.stderr)

    env = base_env(mock_base, mcp_py)

    results: dict[str, dict] = {}

    # Python
    if py:
        penv = dict(env)
        penv["_RES_PY"] = py
        print("[resilience] running python…", file=sys.stderr)
        results["python"] = run_python(penv, mcp_py)
    else:
        results["python"] = _all_pending("python", "no interpreter with toolnexus installed (set RES_PY)")

    # JS
    print("[resilience] running js…", file=sys.stderr)
    results["js"] = run_js(dict(env))

    # Go
    print("[resilience] running go…", file=sys.stderr)
    results["go"] = run_go(dict(env))

    # Elixir
    print("[resilience] running elixir…", file=sys.stderr)
    results["elixir"] = run_elixir(dict(env))

    # Java / C# — harness stubbed, run pending (do not fake results)
    results["java"] = _all_pending("java", "harness stubbed, run pending")
    results["csharp"] = _all_pending("csharp", "harness stubbed, run pending")

    srv.shutdown()

    langs = ["python", "js", "go", "elixir", "java", "csharp"]
    out = {
        "benchmark": "resilience",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "scenarios": SCENARIO_DESC,
        "langs": langs,
        "results": results,
    }
    out_path = HERE / "results.json"
    out_path.write_text(json.dumps(out, indent=2) + "\n")

    # ---- matrix ----
    print("\nRESILIENCE MATRIX  (scenario x language)\n")
    header = f"{'':<4} " + " ".join(f"{l[:7]:>8}" for l in langs)
    print(header)
    for s in SCENARIOS:
        cells = []
        for l in langs:
            oc = results[l].get(s, {}).get("outcome", "?")
            mark = {"graceful": "PASS", "crash": "CRASH", "hang": "HANG",
                    "wrong": "WRONG", "pending": "pend"}.get(oc, oc)
            cells.append(f"{mark:>8}")
        print(f"{s:<4} " + " ".join(cells))
    print()

    gaps = []
    for l in langs:
        for s in SCENARIOS:
            oc = results[l].get(s, {}).get("outcome")
            if oc in ("crash", "hang", "wrong"):
                gaps.append((l, s, oc, results[l][s].get("detail", "")))
    if gaps:
        print("GAPS (not graceful):")
        for l, s, oc, d in gaps:
            print(f"  {l} {s} = {oc}: {d}")
    else:
        print("No gaps: every measured cell was graceful.")
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()
