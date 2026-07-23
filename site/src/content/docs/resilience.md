---
title: Resilience benchmark
description: >-
  Does each port go through adverse conditions gracefully? A hermetic, mock-only failure-handling matrix — every cell MEASURED or clearly marked pending.
---

> **Every cell in the matrix below is [MEASURED]** with a reproducible, hermetic
> harness committed under [`../benchmarks/resilience/`](../benchmarks/resilience/) —
> one local mock LLM (no real provider, no token, no network egress) and stdlib-only
> stdio MCP stubs. Where a toolchain was not available to run a port honestly, the
> cell is **`pending`**, never a pass. A partial-but-real matrix beats a
> complete-but-fake one.
>
> This is a **different axis** from the [performance benchmark](./performance/):
> that page measures *how fast* a port is; this one measures *whether a port goes
> through failure gracefully* — isolate, surface, or bound the failure instead of
> crashing, hanging, or silently returning a wrong answer.
>
> Measured **July 2026** on toolnexus **0.9.4**. MCP Python SDK 1.28.1 ·
> mark3labs/mcp-go 0.48.0 · Node 24 · Elixir 1.20 / OTP 29. Re-run before quoting.

---

## TL;DR

Across **8 adverse-condition scenarios × 4 ports run locally (Python, JS, Go,
Elixir)** — that's **32 measured cells** — **every cell was graceful.** No crash,
no hang, no silently-wrong answer.

- A **broken MCP server is isolated**: a bad command, an unreachable remote, or a
  server that hangs each records status `"failed"` while the **healthy** server's
  tools stay present and the toolkit still builds (R1-R3).
- A **malformed `mcp.json`** surfaces a clear parse error, no crash (R4).
- **LLM failure is surfaced, not swallowed**: an out-of-credits `402` and a
  persistent `500` both surface as a bounded error via the default classifier;
  a transient `429` is retried and succeeds (R5-R7).
- A **native tool that throws mid-run** is fed back to the model as an error result,
  and the agent loop continues to a final answer (R8).

Java and C# are **`pending`** on this pass (harness stubbed; not yet run).

---

## The matrix

`PASS` = graceful (isolated / surfaced / bounded). Numbers in the per-scenario
tables below are wall time for that one scenario (median of the run shown).

| Scenario | Python | JS | Go | Elixir | Java | C# |
|----------|:------:|:--:|:--:|:------:|:----:|:--:|
| **R1** MCP server won't start (bad command) | PASS | PASS | PASS | PASS | pending | pending |
| **R2** MCP remote unreachable (closed port) | PASS | PASS | PASS | PASS | pending | pending |
| **R3** MCP server hangs (never responds) | PASS | PASS | PASS | PASS | pending | pending |
| **R4** Malformed `mcp.json` | PASS | PASS | PASS | PASS | pending | pending |
| **R5** LLM out-of-credits / auth (402) | PASS | PASS | PASS | PASS | pending | pending |
| **R6** LLM rate-limit then ok (429→200) | PASS | PASS | PASS | PASS | pending | pending |
| **R7** LLM persistent 500 (retries exhausted) | PASS | PASS | PASS | PASS | pending | pending |
| **R8** Native tool throws mid-run | PASS | PASS | PASS | PASS | pending | pending |

---

## What "graceful" means, scenario by scenario

Each runner asserts a concrete, observable property — not just "it didn't throw".

### MCP failure is isolated (R1-R3)

Each of these builds a toolkit with **two** MCP servers: one healthy shared stdio
server (`benchmarks/mcp_server.py`, three tools) and one broken server. Graceful
requires all three of: the toolkit **builds**, the broken server's status is
`"failed"`, the healthy server's status is `"connected"`, and its tools are present.

- **R1 — bad command.** The broken server's `command` points at a nonexistent
  binary. Every port marks it `failed` (`executable_not_found` / `ENOENT` /
  `fork/exec … no such file`) and keeps the good server's three tools.
- **R2 — remote unreachable.** A `remote` server whose `url` points at a
  just-closed local port. Every port surfaces `connection refused` and marks only
  that server `failed`, in tens of milliseconds.
- **R3 — server hangs.** A stdio server that accepts the connection and **never
  answers `initialize`**. The per-server `timeout` (set to 2000 ms here) fires and
  the server is marked `failed`; the build continues.

| Port | R1 | R2 | R3 |
|------|----|----|----|
| Python | ~18 ms | ~37 ms | ~4.0 s |
| JS     | ~17 ms | ~16 ms | ~4.0 s |
| Go     | ~23 ms | ~18 ms | ~4.0 s |
| Elixir | ~23 ms | ~24 ms | ~2.6 s |

**A measured observation, not a gap:** on R3 the hung-stdio connect is *bounded and
isolated* in every port (that's the graceful property), but Python / JS / Go take
**~2× the configured 2000 ms per-server timeout** to give up (~4 s), while Elixir
comes in nearer ~1× (~2.6 s). The failure is always bounded — but the stdio
hang-detection budget is not a tight single multiple of `timeout` across ports.
Worth a spec note if tighter parity on the hang deadline is wanted.

> **Note on the Go stdio MCP client.** The older overhead benchmark documented a
> real bug where the Go port's stdio MCP child was killed immediately after connect.
> On this run (toolnexus 0.9.4) that is **no longer observed**: in R1 and R3 the Go
> port holds a live stdio session — the healthy server reports `connected` with all
> three tools listed while the broken sibling is isolated.

### Malformed config surfaces cleanly (R4)

A truncated / invalid `mcp.json` is handed to `createToolkit`. Graceful = a clear
error with **no crash**: Python raises `JSONDecodeError`, JS `SyntaxError`, Go
returns `parse mcp config: unexpected end of JSON input`, Elixir returns
`{:error, …}`. All in ≤ ~2 ms.

### LLM failure is surfaced or retried (R5-R7)

These exercise the resilience classifier (default: retryable → retry, else
fail) against the mock LLM.

- **R5 — out of credits (402).** A `402` is **not** in the retryable set, so the
  default classifier fails fast: the run surfaces `LLM 402: …` as an error with
  **one** request — no hang, no retry storm (~2-10 ms).
- **R6 — rate-limit then ok (429 → 200).** The first attempt gets `429`
  (retryable); the client backs off and retries, the second attempt returns `200`,
  and the run **succeeds** with the expected final text.
- **R7 — persistent 500.** Every attempt returns `500`. The client retries to its
  budget and then surfaces `LLM 500: …` as a bounded error (~30-165 ms with a 1 ms
  base backoff and 2 retries) — it does not loop forever.

| Port | R5 (402) | R6 (429→200) | R7 (500) |
|------|----------|--------------|----------|
| Python | ~3 ms  | ~35 ms | ~164 ms |
| JS     | ~10 ms | ~52 ms | ~127 ms |
| Go     | ~2 ms  | ~1 ms  | ~99 ms  |
| Elixir | ~9 ms  | ~20 ms | ~27 ms  |

### A throwing tool doesn't kill the run (R8)

A native tool `boom` raises on every call. The mock scripts turn 1 to call `boom`,
then turn 2 to finalize. Graceful = the thrown error becomes a tool result with
`isError: true`, is fed back to the model, and the loop reaches a **final answer**
(`RESILIENCE_OK`) — verified in every port, in ≤ ~3 ms.

---

## Methodology

### Why mock-only

The property under test is **failure handling**, which must be deterministic and
free. A real provider would make `402` / `429` / `500` non-reproducible and cost
money. So every scenario points the client at one local
[`resilience_mock_llm.py`](../benchmarks/resilience/resilience_mock_llm.py) — an
OpenAI-compatible server that routes behaviour by URL prefix (`/e402`, `/e401`,
`/e500`, `/retry/<id>` = 429-then-200, `/boom`) so the **same wire** drives every
port. MCP failures use stdlib-only stdio stubs: the shared healthy server, a
nonexistent binary, a closed TCP port, and
[`mcp_hang.py`](../benchmarks/resilience/mcp_hang.py) (accepts, never responds).

### Outcome classification

Each runner emits one JSON line per scenario —
`{lang, scenario, outcome, detail, elapsed_ms}` — with
`outcome ∈ graceful | crash | hang | wrong`:

- **graceful** — the asserted isolate/surface/bound property held.
- **crash** — an unexpected throw escaped (e.g. an LLM error that should have been
  fed back as a tool result).
- **hang** — the scenario exceeded a hard cap (20 s) — a bounded-time failure.
- **wrong** — it returned silently, with a missing or incorrect result.

### Reproduce

```sh
export VENVS=/tmp/bench-venvs
uv venv "$VENVS/toolnexus" -p 3.11 && uv pip install --python "$VENVS/toolnexus/bin/python" -e ./python
RES_PY="$VENVS/toolnexus/bin/python" "$VENVS/toolnexus/bin/python" benchmarks/resilience/run_resilience.py
```

The orchestrator runs each port whose toolchain is present (Go needs `go`; JS
needs `node` + a built `js/dist`; Elixir needs `mix`), writes
`benchmarks/resilience/results.json`, and prints the matrix plus a GAPS list. A
missing toolchain is `pending`, never a pass — see the
[benchmark README](../benchmarks/resilience/) for per-port invocation.

---

## Honesty

This benchmark exists to **expose** gaps, not to paper over them. On this pass
there were **no gaps** in the four ports run — every measured cell was graceful —
and the one imperfection found (R3's ~2× hang-timeout overshoot in Python/JS/Go) is
reported above as a measured observation rather than hidden. Java and C# are
`pending` and will be filled in when their runners are stood up; they are not
claimed as passing until measured.
