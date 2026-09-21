# Portability spike — is 7-port ACP (#96) + CLI-backed model source (#97) feasible?

**Overrules the framing in `spikes/acp/SPIKE.md` and `spikes/cli-model/SPIKE.md`.** Both
prior spikes are Go-only and both concluded "Go-local, no SPEC §0 change, ships in
`golang/` or an example." That conclusion is correct about `SPEC.md §0` (a `Generate`
seam genuinely adds no cross-language conformance surface) but incomplete about the
repo's own prime directive: *"A behavior change lands in all seven ports, or it is not
done… silent drift is the one bug this repo exists to prevent"* (`CLAUDE.md`). A
capability that only Go users get is exactly that drift, SPEC-invisible or not. This
spike does not re-litigate the Go findings (ADR 0025/0026 gates 1–5, already proven with
running code) — it asks the question those spikes explicitly did not: **can the other
six ports build the same thing, and what would it cost.**

## 1. Per-port seam audit

Every port already ships the equivalent of Go's `InProcessOptions.Generate`
(`golang/inprocess.go:58`) — a synchronous "hand me the assembled request, return one
assistant message" hook that a CLI- or ACP-backed model plugs into with zero changes to
the tool-calling loop, MCP, skills, sub-agents, hooks or metrics. All seven accept a
caller-supplied `generate` that can shell out or speak a subprocess protocol; none is
impossible or structurally awkward. One is *procedurally* awkward, not structurally —
Python's forced-synchronous contract, isolated and proven below in §3.

| Port | Seam type + location | Signature | Async? |
|---|---|---|---|
| **Go** | `InProcessOptions.Generate`, `golang/inprocess.go:58` | `func(InProcessRequest) (InProcessResponse, error)` | sync (host may spawn goroutines internally) |
| **JS/TS** | `InProcessOptions.generate`, `js/src/client.ts:1155` | `(request: InProcessRequest) => InProcessResponse \| Promise<InProcessResponse>` | **sync or async, both accepted** — the seam types a `Promise` explicitly |
| **Python** | `create_in_process_client(generate=...)`, `python/src/toolnexus/client.py:2252`; enforced sync at `_InProcessTransport.post`, `client.py:2185` (`inspect.isawaitable(answer)` → raises) | `Callable[[dict], dict]` | **sync only, by explicit design** — "it runs on the client's worker thread. Do async work before the call, or block inside it." |
| **Java** | `InProcess.Options.generate`, `java/src/main/java/io/github/muthuishere/toolnexus/InProcess.java:110,123`; consumed at `GenerateBackedHttpClient`, line ~182 | `Function<Request, Response>` | sync (host may hand off to an `Executor` internally) |
| **C#** | `InProcess` options `Func<Request, Response>? Generate`, `csharp/src/Toolnexus/InProcess.cs:65`; consumed by `GenerateBackedHandler`, line 124–125 | `Func<Request, Response>` | sync (host may `.Result`/`Task.Run` internally) |
| **Elixir** | `create_in_process/1`'s `:generate`, `elixir/lib/toolnexus/client.ex:701–705,731` (`in_process_transport/1`) | `(keyword) -> map`, arity 1 | sync — but the BEAM's actor model makes "spawn a Port and receive a message back" the *native* idiom, not a workaround |
| **Clojure** (JVM + cljgo, one `.cljc`) | `create-in-process-client`, `clojure/src/toolnexus/client.cljc:257–259` (`in-process-http-client`, line 200) | `(fn [req] -> {:content ...} \| {:tool-calls [...]})` | sync |

**No port is impossible or awkward for this.** The seam shape (call in, block, get one
message back) is uniform. The real risk, per the task's own framing, is one level down —
not "can the seam accept a function that shells out" but "does spawning a subprocess and
speaking its protocol require new infrastructure." That's §2.

## 2. Subprocess capability audit

**Finding that changes the risk picture entirely: every port already has proven,
working, newline-delimited-JSON-over-pipes subprocess machinery, in its shipped code,
today — because MCP's local `command`⇒stdio transport (SPEC §2) already requires
exactly that shape.** ACP is JSON-RPC 2.0 over stdin/stdout, one object per line, framed
identically to MCP stdio. The CLI-backed source (ADR 0026) is a strictly simpler
one-shot exec + file I/O. Neither needs a new third-party dependency in any of the seven
ports — the dependency already ships, because MCP local servers made it mandatory.

| Port | Existing subprocess machinery (cited) | New dep needed for ACP (bidi pipes)? | New dep needed for CLI source (one-shot + files)? |
|---|---|---|---|
| Go | `os/exec` (stdlib) — `golang/inprocess.go`'s MCP local transport | No | No |
| JS/TS | `node:child_process.spawn`, `js/src/builtin.ts:10,160`; also used by the MCP stdio client, `js/src/mcp.ts:278` | No — **but confirms Node-only.** `js/package.json` ships `"main"`/`"exports".import` only, no browser target; this is not a risk, just a scoping fact worth stating explicitly since "browser-targeted JS" was flagged as a candidate risk and isn't one — this port never claimed browser support | No |
| Python | `subprocess` (stdlib) — `subprocess.run(...)`, `python/src/toolnexus/builtin.py:174` (bash tool), run via `asyncio.to_thread` elsewhere in the client for the *async*-facing API surface | No (blocking `Popen`/`subprocess.run` inside a sync `generate` needs no new library) | No |
| Java | `java.lang.ProcessBuilder` — `java/src/main/java/io/github/muthuishere/toolnexus/BuiltinTools.java:237`, and the MCP client's own process spawn at `McpSource.java:529-533` | No | No |
| C# | `System.Diagnostics.Process`/`ProcessStartInfo` — `csharp/src/Toolnexus/BuiltinTools.cs:258` | No | No |
| Elixir | Erlang `Port` — `elixir/lib/toolnexus/mcp/transport/stdio.ex` (`Port.open({:spawn_executable, ...})`, line ~50), already framed with newline-delimited JSON + partial-line buffering (`split_lines/1`) for MCP | No — this is the **best-fit host for ACP of all seven**: BEAM `Port` + a receiving process *is* the idiom for "long-lived child, async notifications interleaved with request/response," no adapter layer needed | No |
| Clojure — **JVM host** | `koine.process/spawn` (long-lived, piped stdin/stdout — doc string literally says *"This is what a line-delimited JSON-RPC transport (MCP stdio) requires"*) and `koine.process/sh` (one-shot); both already used by the port's real MCP client, `clojure/src/toolnexus/mcp.cljc:373` (`proc/spawn`), and the `bash` builtin, `clojure/src/toolnexus/builtin.cljc:241` (`proc/sh`) | No | No |
| Clojure — **cljgo host** | **Same `koine.process` API, same `.cljc` source, zero reader conditionals** — `koine` is dual-host by construction (ADR 0009 measurement 3/4: `clojure.core` + koine surface verified identical on both hosts) and this spike independently reran that proof for the process-spawn primitive specifically (§3 below) | No | No |

**On the two flagged high-risk candidates, checked directly, evidence not priors:**

- **"Browser-targeted JS?"** — not a real risk. `js/package.json` never declares a
  browser entry point; `node:child_process` is a hard Node dependency already present in
  the shipped `builtin.ts` bash tool and the MCP stdio client. There is no browser build
  to break.
- **"Clojure on cljgo vs JVM?"** — this was the right thing to flag, and it is the one
  place this spike found real, fixable friction (not in subprocess *capability*, which
  `koine.process` already proves dual-host, but in **project configuration**: `cljgo`
  resolves dependencies from a separate `build.cljgo` file, not `deps.edn`, and refuses
  to load ANY namespace outside its own project's `:paths` even transitively — see §3).
- **"Elixir ports?"** (the BEAM primitive, not "how many of the 7 ports") — not a risk;
  it is the *best-suited* concurrency model of the seven for ACP's interleaved
  notification/response demultiplexing, because message-passing between the port-owning
  process and a receiver is the native way BEAM already handles exactly this shape (see
  `elixir/lib/toolnexus/mcp/transport/stdio.ex`'s own doc comments on port lifetime and
  framing).

**The one genuine, non-hypothetical friction point this audit found: Python's
forced-synchronous `generate` contract** (`client.py:2185`, explicitly rejects a
coroutine). A one-shot CLI source is unaffected — `subprocess.run` blocks, which is
exactly what the seam wants (proven in §3). An **ACP** adapter would need a background
reader thread demultiplexing JSON-RPC notifications from responses while `generate`
blocks synchronously waiting for its turn's answer — doable with `threading` + a
`queue.Queue`, no new dependency, but real engineering the other six ports get closer to
free (Go: goroutines; JS: the seam is already `Promise`-typed; Elixir: BEAM messaging is
the native idiom; Java/C#: threads are unremarkable; Clojure: `koine.process/spawn` +
`future`/`promise`, already core-verified dual-host per ADR 0009 measurement 3). This
spike did not build the ACP case (out of scope — see §3), but flags it precisely so it
isn't discovered mid-implementation.

## 3. Two working proofs

Per the task, the two highest-risk candidates from §2 were **Python** (the only port
with a real, non-hypothetical seam constraint — forced synchronicity) and **Clojure**
(the only port with a second host to prove, per the task's explicit instruction, and the
one place this audit found real friction — project configuration, not subprocess
capability). Both spikes build the **CLI-backed model source** (ADR 0026 shape:
one-shot subprocess, prompt/response via files) — the shared fake CLI proves the
envelope contract works identically driven from two different host languages.

### Shared fixture: `spikes/portability/fakecli/fakecli.py`

A hermetic, scripted fake CLI (no network, no real agent), used by both spikes. It:

- reads `<openai_request>{body}</openai_request>` from a `--prompt-file`,
- proves it actually parsed the body — not just echoed bytes — by lifting an
  **"unknown to every adapter in this repo" key**
  (`x_portability_marker_never_seen_by_adapter`) out of the body and mirroring its
  value back inside the tool call it returns,
- on the FIRST call (no `role: "tool"` message in the transcript yet) returns a tool
  call in real OpenAI wire shape (`arguments` as a JSON-encoded **string**, matching the
  wire contract, not a bare object),
- on the SECOND call (a tool result is now present in the transcript) answers with
  final content quoting the marker — so both spikes exercise a real two-turn
  tool-call loop, not a single canned response,
- writes `<openai_response>{...}</openai_response>` to `--out`.

Manual verification of the fixture alone:

```
$ python3 fakecli/fakecli.py --prompt-file in --out out --model m1
$ cat out
<openai_response>{"choices": [{"index": 0, "finish_reason": "tool_calls", "message":
{"role": "assistant", "content": null, "tool_calls": [{"id": "call_1", "type":
"function", "function": {"name": "echo_marker", "arguments":
"{\"received_marker\": 42424242, \"model\": \"m1\"}"}}]}}], "usage": {"prompt_tokens":
1, "completion_tokens": 1, "total_tokens": 2}}</openai_response>
```

### Proof A — Python, `spikes/portability/python/`

`climodel.py` builds a synchronous `generate(req) -> dict` via plain `subprocess.run`
(no `asyncio`, confirming the sync-seam friction from §2 is real but not blocking for a
one-shot CLI), envelope-wraps `req["body"]` byte-for-byte, strictly parses the response
(dispatches on whether `message.tool_calls` is populated, never on `finish_reason`), and
is wired into the **real, unmodified** `python/` package via
`create_in_process_client` (`toolnexus/__init__.py` → `toolnexus/client.py`). Installed
with `pip install -e ../../../python` into a local venv (`spikes/portability/python/.venv`)
— the real source tree, not a copy.

```
$ cd spikes/portability/python && .venv/bin/python -m pytest -v test_climodel.py
============================= test session starts ==============================
platform darwin -- Python 3.14.7, pytest-9.1.1, pluggy-1.6.0
plugins: asyncio-1.4.0, anyio-4.15.1
asyncio: mode=Mode.STRICT, debug=False, ...
collecting ... collected 1 item

test_climodel.py::test_cli_backed_generate_returns_a_tool_call_end_to_end PASSED [100%]

============================== 1 passed in 0.41s ===============================
```

The test asserts `r.status == "done"` and `"777777" in r.text` — the marker traveled
`request_params` → assembled body → envelope → subprocess → fake CLI → parsed
response → tool call → tool execution → second `generate` call → final content,
through the real Python client's actual tool-calling loop.

### Proof B — Clojure, dual-host, `spikes/portability/clojure/`

`climodel.cljc` (one `.cljc` file, **zero reader conditionals**) uses
`koine.process/sh` — the one-shot form, already proven dual-host by the port's own
`bash` builtin tool — wired into the **real** `create-in-process-client`
(`toolnexus.client`). To keep this self-contained and hermetic without editing the real
`clojure/` tree, the port's own `src/toolnexus/*.cljc` files were copied **read-only**
(`cp -n`, byte-identical, never edited) into the spike's own `src/toolnexus/`; this was
forced by a real finding, not convenience — see the friction point below.

**Bug caught by the spike, not by inspection:** the first version used
`(get parsed "choices")` (string key) on the client's JSON response. It silently
returned `nil` and fell through to an empty-content answer — no exception, just a wrong
answer, exactly the "confidently wrong" failure class ADR 0026 warns about for a
different reason. Cause: `koine.json/read-str` **keywordizes** keys (matching every
other call site in the real port, e.g. `client.cljc`'s own
`in-process-http-client`), so the fix was switching to keyword access
(`(get parsed :choices)`) — see the comment left in `climodel.cljc` at the fix site.

**Real friction found, and it is project-configuration, not language capability:**
`cljgo run`/`cljgo build` resolve dependencies from a separate **`build.cljgo`** file
(a `(defn build [b] (dep b "...") (install b (exe b {...})))` form), not from
`deps.edn` — and refuse to load a namespace transitively required from outside the
project's own `:paths`, even with a correct `deps.edn` and a copied `build.lock.edn`.
Confirmed by isolating it to a two-line minimal reproduction
(`(require 'koine.env)` alone, in a fresh `/tmp` project) before touching the real
spike — it is a `cljgo` tooling behavior, not a `koine` or `toolnexus` problem, and the
real `clojure/` port's own `build.cljgo` is the reference solution (this spike's
`build.cljgo` mirrors it). Once the port sources were copied in-project and
`build.cljgo` added, `cljgo build` resolved cleanly:

```
$ cljgo build
cljgo deps: net.clojars.muthuishere/koine 0.11.0 — 13 namespace(s) with no Java interop
  pruned org.clojure/clojure 1.12.5 (cljgo IS the Clojure implementation; its clojure.core is embedded)

Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
{:test 1, :pass 2, :fail 0, :error 0, :type :summary}
cljgo build: installed .../spikes/portability/clojure/climodel-test
```

All **three** legs — matching the real port's own `cljgo-gate.sh` two-leg discipline
plus the JVM leg — verified to agree, verbatim:

```
=== cljgo AOT binary ===
Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
{:test 1, :pass 2, :fail 0, :error 0, :type :summary}

=== cljgo run (interpreted) ===
cljgo deps: net.clojars.muthuishere/koine 0.11.0 — 13 namespace(s) with no Java interop
  pruned org.clojure/clojure 1.12.5 (cljgo IS the Clojure implementation; its clojure.core is embedded)

Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
{:test 1, :pass 2, :fail 0, :error 0, :type :summary}

=== JVM (clojure -M) ===
Testing toolnexus.climodel-test

Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
{:test 1, :pass 2, :fail 0, :error 0, :type :summary}
```

Same source, same fake CLI, identical pass/fail/assertion counts on JVM, cljgo
interpreted, and the cljgo AOT binary. This directly answers the task's explicit
dual-host instruction: **subprocess-backed model sources are provably parity-clean
across both Clojure hosts.**

## 4. Cost estimate for a real 7-port ACP + CLI-source change

Baseline: the seam exists everywhere (§1), the subprocess primitive exists everywhere
(§2), and both spikes here prove the CLI-source shape end-to-end in two of the harder
ports. ACP (bidirectional, stateful, long-lived) is the more expensive of the two
features per port; the CLI source (one-shot) is materially cheaper everywhere because
neither the async-demux question nor the process-lifetime question exists for it.

| Port | CLI source (ADR 0026) | ACP (ADR 0025) | Why |
|---|---|---|---|
| Go | done (reference) | done (reference, spiked) | Both already spiked with running code. |
| JS/TS | **Low** | **Low** | `Promise`-typed seam natively fits an async JSON-RPC demux loop; `node:child_process` + `readline` is idiomatic for line-framed protocols; this is arguably the *easiest* non-Go port for ACP. |
| Elixir | **Low** | **Low–Medium** | `Port` + `GenServer` is the textbook shape for "long-lived child, async notifications, request/response by correlation id" — less adaptation than translation. Existing MCP stdio transport (`stdio.ex`) is close to a template. Medium only because permission-request timeout/hang semantics (ADR 0025 gate 2) need an explicit `GenServer` timeout, not a language gap. |
| Java | **Low** | **Medium** | `ProcessBuilder` + a reader thread is unremarkable; ACP's demux-by-JSON-RPC-id needs a small dedicated dispatcher (a `Map<Object, CompletableFuture<...>>`), more boilerplate than risk. |
| C# | **Low** | **Medium** | Same shape as Java — `Process` + `async`/`Task`-based reader; C#'s `async`/`await` is a good fit for ACP's request/notification interleaving, more code than novelty. |
| Python | **Low** (proven here) | **Medium–High** | The forced-sync `generate` contract (§2) means ACP's background-reader-plus-blocking-wait needs `threading`+`queue` explicitly, where five other ports get it closer to free from their concurrency primitives. Not a blocker — proven tractable for the simpler CLI case here — but the port needing the most deliberate design work for ACP specifically. |
| Clojure (JVM + cljgo) | **Low** (proven here, both hosts) | **Medium** | `koine.process/spawn` is *already documented as built for line-delimited JSON-RPC*, and `future`/`promise` are core-verified dual-host (ADR 0009). The real cost is **process**, not code: every new namespace needs the `build.cljgo` treatment found in §3 (a `build.cljgo` update mirroring `deps.edn`, kept in sync) and — the point ADR 0009 raises about ANY third-party dependency — a real ACP client would want to reuse a JSON-RPC framing/demux helper, and *nothing beyond `koine` can be pulled in* without breaking the cljgo host (ADR 0009 measurement 2: "any third-party Clojure dependency makes the port JVM-only"). If `koine.process` doesn't already carry enough (id-based request/response demux, notification callback dispatch) for ACP specifically, that logic has to be hand-rolled in pure `.cljc`, which is exactly what this port already does for its own MCP JSON-RPC client (`mcp.cljc`) — a precedent, not a gap. |

**Rough sequencing recommendation**, informed by both the Go spikes and this audit:
ship the CLI source first (cheap everywhere, proven twice here), across all seven ports,
as one OpenSpec change with a per-language parity checklist per `CLAUDE.md`'s workflow.
Then ACP as a second change, landing JS/Elixir first (cheapest, and Elixir's MCP stdio
transport is a near-template), Java/C#/Clojure next (moderate, mechanical), Python last
or with explicit extra review (the one port where the seam's own sync contract adds
real — not hypothetical — design work).

## Verdict

**Feasible — every port has the seam and the subprocess primitive today, with no new
third-party dependency required anywhere, for both a one-shot CLI source and a
bidirectional ACP session.** This spike proves the CLI-source shape running, end to end,
through the *real* unmodified client, in the two ports independently judged
highest-risk (Python's sync-only seam; Clojure's second, non-JVM host) — including all
three Clojure execution legs agreeing byte-for-byte. Nothing found here contradicts the
Go spikes' SPEC-surface conclusion (`SPEC.md §0` still doesn't move); it contradicts
only the inference that "no SPEC change" meant "Go-only is fine." It does not.

**What would actually block it, named plainly:**

1. **Python's forced-synchronous `generate` seam is real friction for ACP specifically**
   (not for the CLI source, proven fine here). Not a blocker — `threading`+`queue`
   closes it — but it needs deliberate design, not a straight port of the Go client.
2. **Clojure's `build.cljgo`/`deps.edn` split is a real, non-obvious tooling trap** that
   this spike burned real time on before finding the fix (§3). Any future Clojure spike
   or change in this repo should point at `clojure/build.cljgo` as the reference from
   the start, not rediscover this.
3. **Clojure's "no third-party dependency beyond `koine`" constraint (ADR 0009) is a
   real ceiling on ACP specifically** if `koine.process`'s current primitives
   (`spawn`/`send-line!`/`read-line!`) don't already cover JSON-RPC id-based demux and
   notification dispatch — this spike did not build ACP in Clojure, only the simpler
   CLI source, so this is a flagged open question for whoever picks up the ACP change,
   not a finding either way.
4. Nothing in this audit found a port where the feature is impossible, awkward, or
   needs a new dependency. The "Go-only, no SPEC change" framing understated the work
   real-izing this for six more ports, but never should have been read as "the other
   six can't do it."

## Files

- `spikes/portability/fakecli/fakecli.py` — shared hermetic fake CLI (ADR 0026 envelope
  shape, two-turn tool-call loop), used by both proofs.
- `spikes/portability/python/climodel.py`, `test_climodel.py` — Proof A. Run:
  `cd spikes/portability/python && .venv/bin/python -m pytest -v test_climodel.py`
  (venv: `python3 -m venv .venv && .venv/bin/pip install -e ../../../python pytest pytest-asyncio`).
- `spikes/portability/clojure/src/toolnexus/climodel.cljc`,
  `src/toolnexus/climodel_test.cljc`, `src/run_climodel_test.cljc` — Proof B (this
  spike's own new files). `src/toolnexus/*.cljc` (all other files in that directory) is
  a **read-only, unmodified copy** of the real `clojure/src/toolnexus/` tree, copied in
  rather than path-referenced because of the `cljgo` project-scoping finding in §3.
  `build.cljgo`, `deps.edn`, `build.lock.edn` mirror the real port's own build
  configuration. Run JVM: `clojure -M -e "(require 'toolnexus.climodel-test) (clojure.test/run-tests 'toolnexus.climodel-test)"`.
  Run cljgo (both interpreted and AOT): `cljgo build` (produces `climodel-test`, deleted
  after verification to keep the spike dir clean — rerun to regenerate) then
  `cljgo run src/run_climodel_test.cljc` / `./climodel-test`.
- No file outside `spikes/portability/` was modified. `golang/`, `js/`, `python/`,
  `java/`, `csharp/`, `elixir/`, `clojure/` real source trees are untouched (Python's
  package was `pip install -e`'d into an isolated venv under the spike dir, which writes
  no files into `python/`).
