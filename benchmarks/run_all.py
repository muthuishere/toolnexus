#!/usr/bin/env python3
"""Benchmark orchestrator.

Starts the mock LLM (if not already up), then runs each framework runner as a
SEPARATE process wrapped in `/usr/bin/time -l` so we capture that process's peak
RSS. Parses each runner's JSON result (init_ms + per-request latencies),
aggregates mean / p50 / p95, and prints a results table + writes results.json.

Interpreter / binary paths come from environment variables so the harness is
reproducible on any machine (see benchmarks/README.md):

  BENCH_REPO   repo root
  MCP_PYTHON   python (any) used to launch the shared MCP server
  MOCK_URL     mock LLM base url (default http://127.0.0.1:8900)
  TN_PY        toolnexus venv python
  LG_PY        langgraph venv python
  ADK_PY       adk venv python
  GO_BIN       built Go runner binary
  JAVA_APP     toolnexus-java dist launch script
  SPRING_JAR   spring-ai boot jar
"""
from __future__ import annotations

import json
import os
import re
import statistics
import subprocess
import sys
import time
import urllib.request

REPO = os.environ["BENCH_REPO"]
MOCK_URL = os.environ.get("MOCK_URL", "http://127.0.0.1:8900")
RUNS = os.environ.get("BENCH_RUNS", "25")
WARMUP = os.environ.get("BENCH_WARMUP", "5")
TIME = "/usr/bin/time"


def mock_up():
    try:
        urllib.request.urlopen(MOCK_URL + "/health", timeout=1)
        return True
    except Exception:
        return False


def parse_rss_bytes(stderr: str) -> float | None:
    m = re.search(r"(\d+)\s+maximum resident set size", stderr)
    return float(m.group(1)) if m else None


def run_target(name, cmd, env_extra=None):
    env = dict(os.environ)
    env.setdefault("OPENAI_API_KEY", "sk-mock-not-a-real-key")
    if env_extra:
        env.update(env_extra)
    full = [TIME, "-l", *cmd]
    p = subprocess.run(full, env=env, capture_output=True, text=True, timeout=300)
    out = p.stdout
    # extract the JSON result line
    line = None
    for ln in out.splitlines():
        s = ln.strip()
        if s.startswith("BENCHJSON "):
            line = s[len("BENCHJSON "):]
        elif s.startswith('{"framework"') or ('"framework"' in s and s.startswith("{")):
            line = s
    if line is None:
        print(f"  !! {name}: no JSON result. stderr tail:\n" +
              "\n".join(p.stderr.splitlines()[-8:]))
        return None
    data = json.loads(line)
    rss = parse_rss_bytes(p.stderr)  # macOS: bytes
    lat = data["latencies_ms"]
    lat_sorted = sorted(lat)
    def pct(p):
        k = max(0, min(len(lat_sorted) - 1, int(round((p / 100) * (len(lat_sorted) - 1)))))
        return lat_sorted[k]
    return {
        "name": name,
        "framework": data["framework"],
        "language": data["language"],
        "init_ms": round(data["init_ms"], 1),
        "tool_count": data["tool_count"],
        "mean_ms": round(statistics.mean(lat), 3),
        "p50_ms": round(pct(50), 3),
        "p95_ms": round(pct(95), 3),
        "n": len(lat),
        "peak_rss_mb": round(rss / 1024 / 1024, 1) if rss else None,
        "final_ok": data["final_text"].startswith("The weather in Paris"),
    }


def targets():
    t = []
    if os.environ.get("TN_PY"):
        for cfg in ("mcp", "full"):
            t.append((f"toolnexus-python-{cfg}",
                      [os.environ["TN_PY"], f"{REPO}/benchmarks/run_toolnexus.py",
                       "--config", cfg, "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("LG_PY"):
        for cfg in ("default", "session"):
            t.append((f"langgraph-{cfg}",
                      [os.environ["LG_PY"], f"{REPO}/benchmarks/run_langgraph.py",
                       "--config", cfg, "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("ADK_PY"):
        t.append(("google-adk",
                  [os.environ["ADK_PY"], f"{REPO}/benchmarks/run_adk.py",
                   "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("CREWAI_PY"):
        t.append(("crewai",
                  [os.environ["CREWAI_PY"], f"{REPO}/benchmarks/run_crewai.py",
                   "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("PYDANTIC_PY"):
        t.append(("pydantic-ai",
                  [os.environ["PYDANTIC_PY"], f"{REPO}/benchmarks/run_pydantic_ai.py",
                   "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("OPENAI_AGENTS_PY"):
        t.append(("openai-agents",
                  [os.environ["OPENAI_AGENTS_PY"], f"{REPO}/benchmarks/run_openai_agents.py",
                   "--runs", RUNS, "--warmup", WARMUP], None))
    if os.environ.get("GO_BIN"):
        t.append(("toolnexus-go-native",
                  [os.environ["GO_BIN"], "-config", "native", "-runs", RUNS, "-warmup", WARMUP], None))
    if os.environ.get("JAVA_APP"):
        for cfg in ("mcp", "full"):
            t.append((f"toolnexus-java-{cfg}",
                      [os.environ["JAVA_APP"], "-config", cfg, "-runs", RUNS, "-warmup", WARMUP], None))
    if os.environ.get("SPRING_JAR"):
        t.append(("spring-ai-java",
                  ["java", "-jar", os.environ["SPRING_JAR"]],
                  {"BENCH_RUNS": RUNS, "BENCH_WARMUP": WARMUP}))
    if os.environ.get("LC4J_APP"):
        t.append(("langchain4j-mcp",
                  [os.environ["LC4J_APP"], "-config", "mcp", "-runs", RUNS, "-warmup", WARMUP], None))
    if os.environ.get("TN_CSHARP_DLL"):
        for cfg in ("mcp", "full"):
            t.append((f"toolnexus-csharp-{cfg}",
                      ["dotnet", os.environ["TN_CSHARP_DLL"], "-config", cfg,
                       "-runs", RUNS, "-warmup", WARMUP], None))
    if os.environ.get("SK_DLL"):
        t.append(("semantic-kernel",
                  ["dotnet", os.environ["SK_DLL"], "-config", "native",
                   "-runs", RUNS, "-warmup", WARMUP], None))
    if os.environ.get("MEAI_DLL"):
        t.append(("ms-extensions-ai",
                  ["dotnet", os.environ["MEAI_DLL"], "-config", "native",
                   "-runs", RUNS, "-warmup", WARMUP], None))
    return t


def main():
    if not mock_up():
        print("Mock LLM not reachable at", MOCK_URL,
              "- start it first: python benchmarks/mock_llm.py")
        sys.exit(1)

    results = []
    for name, cmd, env_extra in targets():
        print(f"running {name} ...")
        r = run_target(name, cmd, env_extra)
        if r:
            results.append(r)

    results.sort(key=lambda r: (r["language"], r["p50_ms"]))
    hdr = f"{'framework':<26}{'lang':<8}{'init_ms':>9}{'p50_ms':>9}{'p95_ms':>9}{'mean_ms':>9}{'rss_mb':>9}{'tools':>7}{'ok':>4}"
    print("\n" + hdr)
    print("-" * len(hdr))
    for r in results:
        print(f"{r['framework']:<26}{r['language']:<8}{r['init_ms']:>9}{r['p50_ms']:>9}"
              f"{r['p95_ms']:>9}{r['mean_ms']:>9}{str(r['peak_rss_mb']):>9}"
              f"{r['tool_count']:>7}{('Y' if r['final_ok'] else 'N'):>4}")

    outp = os.environ.get("RESULTS_JSON", f"{REPO}/benchmarks/results.json")
    with open(outp, "w") as f:
        json.dump({"runs": int(RUNS), "warmup": int(WARMUP),
                   "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                   "results": results}, f, indent=2)
    print("\nwrote", outp)


if __name__ == "__main__":
    main()
