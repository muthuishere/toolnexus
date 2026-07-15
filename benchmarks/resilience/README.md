# toolnexus resilience benchmark

A **separate axis** from the overhead benchmark next door (`../README.md`). That
one measures *how fast* a port is; this one measures *whether a port goes through
adverse conditions gracefully* — isolate, surface, or bound the failure instead
of crashing, hanging, or silently returning a wrong answer.

Everything is **hermetic and zero-cost**: one local mock LLM (no real provider,
no token, no network egress) and stdlib-only stdio MCP stubs. No port's `src/` is
modified — the runners only import/compile the local ports.

## The eight scenarios

| id | Adverse condition | Graceful = |
|----|-------------------|------------|
| R1 | MCP server won't start (bad command) | toolkit still builds; that server `"failed"`, **other** tools present |
| R2 | MCP remote unreachable (url to a closed port) | that server `"failed"`, isolated, bounded time |
| R3 | MCP server hangs (accepts, never responds) | per-server `timeout` fires → `"failed"`, bounded |
| R4 | Malformed `mcp.json` | clear error, **no crash** |
| R5 | LLM out-of-credits / auth (mock 402/401) | surfaced as an error, no hang (§8 default: non-retryable → fail) |
| R6 | LLM rate-limit then ok (mock 429 → 200) | retried, succeeds |
| R7 | LLM persistent 500 (mock) | retries exhausted → surfaced error, bounded |
| R8 | Native tool throws mid-run | `isError` fed back, loop continues to a final answer |

Each runner prints one JSON line per scenario:

```json
{"lang":"go","scenario":"R5","outcome":"graceful","detail":"…","elapsed_ms":2.2}
```

`outcome ∈ graceful | crash | hang | wrong`.

## Files

| File | Role |
|------|------|
| `resilience_mock_llm.py` | Shared mock LLM. Routes by URL prefix: `/e402`, `/e401`, `/e500`, `/retry/<id>` (429 then 200), `/boom` (turn 1 calls a tool, turn 2 finalizes). Same wire for every port. |
| `mcp_hang.py` | Stdio MCP server that accepts a connection and **never responds** (R3). |
| `../mcp_server.py` | The healthy shared stdio MCP server (reused as the "good" server in R1-R3). |
| `run_python.py` / `run_js.mjs` / `run_go/` / `run_elixir.exs` | Per-port runners; each runs all 8 scenarios. |
| `run_resilience.py` | Orchestrator: starts the mock, runs every available port, writes `results.json`, prints the matrix + a GAPS list. |

## Reproduce

Prereqs mirror the overhead benchmark. Point `RES_PY` at a python that has the
local toolnexus installed:

```sh
export VENVS=/tmp/bench-venvs
uv venv "$VENVS/toolnexus" -p 3.11 && uv pip install --python "$VENVS/toolnexus/bin/python" -e ./python
RES_PY="$VENVS/toolnexus/bin/python" "$VENVS/toolnexus/bin/python" benchmarks/resilience/run_resilience.py
```

A port runs only if its toolchain is present (Go: `go`; JS: `node` + a built
`js/dist`; Elixir: `mix` in `elixir/`); otherwise it is recorded `pending`.
**Java and C# are `pending` (harness stubbed).** Nothing is faked — a missing
toolchain is `pending`, never `graceful`.

Run a single port directly (mock must be up):

```sh
python benchmarks/resilience/resilience_mock_llm.py --port 8901 &
BENCH_REPO=$PWD MCP_PYTHON="$VENVS/toolnexus/bin/python" \
  MOCK_BASE=http://127.0.0.1:8901 MCP_HANG=$PWD/benchmarks/resilience/mcp_hang.py \
  "$VENVS/toolnexus/bin/python" benchmarks/resilience/run_python.py
```

## Honesty

This benchmark exists to **expose** gaps, not to paper over them. A scenario that
crashes / hangs / returns silently-wrong is recorded as such and listed under
GAPS — a partial-but-real matrix beats a complete-but-fake one.
