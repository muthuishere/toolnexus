# Changelog

All ports are versioned and released together; entries here apply to every port unless a port
is named. All seven ports are at tier `full` (js / python / golang / java / csharp / elixir /
clojure) — see `conformance/options_manifest.json`, which is where a
port's tier is declared, never by the port about itself. Releases are cut as
GitHub Releases `vX.Y.Z` via `release.yml` (see `PUBLISHING.md`).

## Unreleased

## 0.14.0 — 2026-08-15

### Fixed — cancelling an A2A call reports `canceled` reliably (js)

Aborting an in-flight A2A tool call reported one of three different states depending on when the
signal landed. Between polls it correctly returned `canceled`; if the signal fired while a
`SendMessage`/`GetTask` request was in flight, the aborted fetch threw and the result carried the
last known state (`submitted` or `working`) plus a raw transport error message instead. `SPEC.md`
§7A pins abort ⇒ `"A2A task <id> canceled"`, and the golang port already honored it on that path —
this was **js drifting from the shared contract**, so a host could not treat the metadata state as
meaningful for a cancelled call.

Fixed in `js` only. The other five ports are **not audited** for the same hole; golang is known
correct. If you rely on this, check your port.

### Fixed — compaction no longer orphans a tool result under the Anthropic style

A long-running agent on `style:"anthropic"` could, the first time compaction fired, produce a
transcript the Anthropic API rejects. Compaction keeps the most recent tail and requires that tail
to begin at a `user` turn, which guarantees tool-pair safety in the OpenAI dialect — a tool result
there is a `tool` message. Under Anthropic a tool result *is* a `user` message carrying
`tool_result` blocks, so the tail could begin on the tool result itself while the `assistant`
message holding the matching `tool_use` was summarized away. The provider rejects a `tool_result`
with no `tool_use`, so the agent broke at exactly the point its context filled up.

The tail boundary is now dialect-neutral: a `user` turn carrying tool results is not a boundary, and
the tail extends back to a genuine one (the same "safety over size" fallback the rule already used).
**OpenAI-style transcripts are unaffected** — every `user` message remains a boundary and output is
byte-identical. Fixed in all seven ports.

Not done: the underlying reason the rule could be dialect-bound at all — conversation history is
stored in whichever provider dialect produced it — is tracked in
`openspec/changes/add-canonical-transcript`, which makes a tool result a first-class message kind so
"a user turn" can no longer mean "a tool result". Regression tests currently ship in golang, js and
python; java, csharp, elixir and clojure carry the fix and pass their existing suites, with the
shared fixture tracked in `openspec/changes/fix-compaction-tool-pair-dialect` (task 1.3).

### Changed — performance benchmarks re-run in full, and Clojure joins the table

The whole benchmark suite was re-measured in **one sitting on 2 August 2026** — 39 framework
configurations across seven languages — so no cell on the results page is a splice of two
different days or two different toolchains. `benchmarks/results.json`,
`docs/performance-benchmarks.md` and the site's [Performance](https://muthuishere.github.io/toolnexus/performance/)
page all carry the same run. Nothing was skipped; `results.json` now records a `skipped` list
so that a framework that fails to stand up in a future run is named rather than quietly missing.

- **A Clojure runner exists** (`benchmarks/run_toolnexus_clojure/`) and measures **both hosts**
  from one `.cljc` file — `toolnexus-clojure-jvm` via `clojure -M`, `toolnexus-clojure-cljgo` via
  a `cljgo build` AOT binary. The performance page had deliberately stayed six-language because
  no seventh runner existed; it is seven-language now for exactly that reason and no other.
- **What the Clojure numbers say, plainly: it is the slowest port on the page.** ~5.5 ms p50 over
  MCP on both hosts, against 1.0 ms for Python and 0.49 ms for Go. The loop is not the problem —
  with native tools the same port runs the scenario in 1.7 ms; the shared stdio MCP server adds
  ~2 ms *per tool call*, where Go pays 0.07 ms for identical wire traffic. That points at the
  port's stdio JSON-RPC path (blocking line-read round trip plus the pure-Clojure JSON codec) and
  is the biggest single optimisation target the benchmark has surfaced. Published, not massaged.
- **The two hosts agree to within 0.2 ms** from the same source, which is the port's whole claim
  measured under load. Where they diverge is startup and memory, both favouring cljgo: process
  cold start ~1.13 s (JVM) vs ~0.02 s (binary), peak RSS ~500 MB vs ~31 MB. The JVM figure is the
  default heap on a 48 GB machine, not memory the port needs — said so on the page rather than
  dropping the column.
- **Clojure p95 is not measured the same way as the other ports**, and the page says so: koine's
  portable monotonic clock is millisecond-resolution on both hosts, so a sample is a batch of 10
  runs divided by 10. Mean is unaffected; p50/p95 are percentiles over batch means and are
  therefore smoother than a per-run percentile.
- **Go publishes a real MCP row again.** The 0.9.0 stdio-MCP bug that forced July's table to show
  a native-tools-only Go number is fixed, so Go now discovers over a live stdio session: 0.49 ms
  p50, the fastest cell on the page, in a 10 MB binary at 17 MB RSS.
- Two competitor edges remain and stay published: **LangChain4j** is 0.04 ms ahead in Java (down
  from 0.78 ms), and **Semantic Kernel**'s native path is 0.03 ms ahead in C# while toolnexus is
  doing live MCP.
- `run_all.py` now registers every runner that exists (JS, Elixir, the Go competitors, Clojure)
  instead of a subset, accepts both runner output shapes, and can run a runner in its own working
  directory. `benchmarks/README.md` documents the full set, plus two install traps found on the
  way: LangGraph and Google ADK need `mcp<2` pinned (2.0.0 removed symbols their adapters
  import), and CrewAI needs the `crewai-tools[mcp]` extra or its MCP adapter aborts on a prompt.

### Fixed

- `js/package-lock.json` had been left at 0.10.0 while `package.json` moved to 0.13.0; running
  `npm install` re-syncs it. Nothing user-visible changed, but a lockfile that disagrees with its
  manifest is the kind of drift a release should not carry.


## 0.13.0 — 2026-08-02

### Added — Clojure, the 7th language (tier `full`, not yet published)

One `.cljc` source tree on **two runtimes**: Clojure on the JVM and
[cljgo](https://github.com/muthuishere/cljgo) (Clojure hosted on Go). Not two implementations
that agree — *one* implementation, byte-identical on both, with **zero reader conditionals**;
every host difference lives behind [koine](https://github.com/muthuishere/koine), which is the
port's only third-party dependency.

Implemented: SPEC §0.1–0.2 (Tool/ToolResult, sanitize), §0.3+§2 (MCP over stdio **and**
streamable-HTTP, per-source isolation), §0.5/§0.6/§3 (agent skills, byte-exact `skill` output),
§0.7 (OpenAI/Anthropic/Gemini adapters), §0.8/§0.9 (native + HTTP tools), §0.10/§8 (the client
loop, both provider styles, parallel tool calls), §0.11/§4A (builtins and MCP precedence),
§0.12/§10 (suspension), §7A/§7B/§7C (A2A out, `serve` in, MCP server in).

Also landed from the shared capability specs: `:request-params`, `:body-transform`,
`:http-client`, `:retries`/`:retry-base-ms`, `:timeout-ms`, `:on-error` (retry|fail — no
failure-originated suspend tier), `:on-metric`, `:store` + conversation memory.

**The port is at tier `full`** — every logical client and toolkit option present, zero
permitted absences, the same bar as the six shipped ports.

Since landed: **client hooks** (`:before-llm` / `:after-llm` / `:before-tool` / `:after-tool`),
**§11 single-turn translation**, the **MCP elicitation bridge** (§2/§10, on **both**
transports — see below), and all twelve toolkit options (`:skill-provider`, `:skills-filter`,
`:skill-sample-limit`, data skills, `:agents`, toolkit `:wait-for`, `:disable-tools` /
`:disable-skills`).

**Still absent, and the option gate structurally cannot see any of it** (it compares option
NAMES in two files, so a missing subsystem has no names to compare): the agent runtime (§7D) and
sub-agents, plus the two §7E entry points that need them (`from-dir`, `start-agent`). Context
compaction (§7F) and the rest of agent home shipped — see the next entry. Those remaining gaps
are what hold this port back from Clojars: not a tier downgrade, a subsystem not yet written.
Note `:agents` is the **A2A** option (remote agents behind an Agent Card),
which is a different capability from `openspec/specs/subagents` — that one remains unshipped
here and is not satisfied by it. `clojure.core/agent` exists on both hosts, so a future
subagents entry point cannot be named `agent`.

**MCP elicitation now works on streamable-HTTP too, not just stdio** — the gap reported here
previously is closed. It was koine's, not §2's: `koine.http/request` buffers the whole body (so a
server→client reverse request arriving mid-`tools/call` can never be seen in time) while
`koine.stream/sse-post` streamed but exposed no response headers — and MCP carries session
identity in the `Mcp-Session-Id` RESPONSE header that the reply must echo, so a consumer had to
choose between streaming and the session id. koine 0.10.0 added `{:on-open f}` to `sse-post`,
applied once to `{:status :headers}` while the stream is still open. The HTTP transport now
switches to the streaming leg as soon as a server answers in `text/event-stream`, maps an
`elicitation/create` onto the same one §10 `waitFor` as stdio (form ⇒ `kind:"input"` with
`requestedSchema` in `data.schema`; URL ⇒ `kind:"authorization"`), and posts the Answer back on
its own request carrying the session id — **inline**, so the in-flight `tools/call` resumes and
the tool is not re-executed. A JSON-only peer keeps the buffered leg unchanged.

**A silent cross-host bug fixed with it: response header CASING.** The two runtimes' HTTP clients
disagreed about the case of the names they hand back — `java.net.http` lowercases, Go's
`http.Header` canonicalises — so `(get (:headers res) "Mcp-Session-Id")` found the value on cljgo
and nil on the JVM, and the lowercase spelling did the exact reverse. No portable spelling
existed, and it failed silently, because a missing header and a mis-cased one are both nil: the
client would simply stop echoing the session id and the server would start a new session per
request. koine 0.10.0 lowercases response header names on every host and adds
`koine.http/header` for a case-insensitive read; every response-header read in the port (MCP
session id, MCP content type, the client loop's `Retry-After`) now goes through it, and the
port's own private copy of that normalisation is gone. Regression-tested against a real
loopback peer that issues the SAME session id under two different spellings of the header name —
either spelling alone is a state where a correct and a broken client coincide on one of the two
hosts.

**§11 divergence, recorded not resolved.** SPEC §11 says any tool call ⇒ `finishReason`
`"tool_calls"`. js, go, python and elixir all prefer the provider's own `finish_reason` when
present, so an OpenAI-style provider returning `"stop"` alongside tool calls yields `"stop"` —
the prose is violated in four shipped ports. The Clojure port matches the five ports, not the
prose. Correcting it is a cross-port change.

**Verified in five execution modes**, not two: `jvm-main`, `jvm-repl`, `cljgo-aot`, `cljgo-run`,
`cljgo-repl` — a REPL is where a human meets a library, and cljgo's own ADR 0007 calls a
REPL-vs-binary divergence unforgivable.

### Added — §7F context compaction and §7E agent home, in Clojure

`toolnexus.agents.compaction/compactor` returns a `:before-llm` hook that summarises the older
transcript and keeps a recent tail, so a long-lived agent stays inside the model's context
window. Below `:max-tokens` it is a no-op and the run is byte-identical to one with no
compactor. `toolnexus.agents.home/compose-soul` composes a persona's bootstrap files into one
system prompt — the directory is the agent — and `home/memory-tool` gives it durable notes it
edits itself, with a write landing on disk immediately but loading only at the start of the next
session.

`from-dir` and `start-agent` (the heartbeat) are the two §7E entry points still missing here:
both compose an agent definition, so both need the §7D runtime this port does not ship yet.

Two upstream defects were found by writing these:

- **cljgo: a descending `range` was an inconsistent seq** — `(range 6 1 -1)` counted 5 and
  mapped to five elements while `seq`/`vec`/`doall`/`some`/`filter` traversed it as `(6)`, and
  the 2-arity `(reduce + coll)` returned `6`, the first element. Nothing threw, so any code
  walking a collection backwards was silently wrong on cljgo and right on the JVM. Root-caused to
  three ascending-only comparisons in `LongRange`, fixed in cljgo v0.9.0 (PR #194).

  One correction, since the first version of this entry got it wrong and koine caught it: the
  **seeded 3-arity `(reduce f init coll)` was CORRECT** at the Clojure level — `clojure.core`
  does not route it through the broken method for this type. The broken Go method
  (`LongRange.ReduceInit`) is real, and our Go-level test measured it returning `init`; the
  Clojure surface simply never reached it. Both measurements were right about different layers,
  and only the Clojure one is what a user could hit — so an audit grepping for a seed-returning
  `reduce` would clear code that is actually broken and miss `(reduce f coll)`, the form that
  failed.
- **The documented Clojure examples did not all run.** They do now: `site/tests/runners/clojure.sh`
  executes every one of them four ways — JVM main, JVM REPL, cljgo interpreted, cljgo AOT —
  and caught a call to a function that does not exist, an invented `build.cljgo` verb, and a
  broken success contract, all in freshly written documentation.

`clojure/examples/clj/` and `clojure/examples/cljgo/` are two projects over one symlinked source
tree with five runnable examples each (MCP + skills + native, native/HTTP tools, progressive
disclosure, persona memory, compaction), verified in CI on both hosts.

### Added — Clojure: the §7D runtime completed, and an adversarial audit paid for itself

**The agent layer is now whole.** `from-dir` (the directory is the agent) and `start-agent`
(the heartbeat, on the runtime's injectable clock — deterministic under a virtual clock) landed
on the §7D runtime, plus `:on-budget` — the §7D host budget callback
(`stop | extend | suspend`, "suspend" parking on a §10 approval). js and golang already ship
`onBudget`; **python, java, csharp and elixir do not** — a pre-existing gap now named here so it
cannot go quiet. The runtime's previously-untested edges (maxWallMs, the tool-call pool, forced
close, wake-on-closed, model inherit, def-level on-metric) are each covered by a test that was
watched to fail.

**An adversarial audit found three shipped defects**, each proven by mutation before fixing:

- **A throwing §8 tool hook hung the consumer forever, on both hosts** — the try/catch covered
  `tool/execute` only, and both hooks ran outside it, so `deliver` never fired and the `deref`
  never returned. Now every exit delivers, and a hook's throw is rethrown on the calling thread,
  matching the shipped ports.
- **The §4A builtins toggle failed OPEN for string-keyed config** — `{:tools {"bash" false}}`
  and any JSON-read config left all ten builtins armed. Keys are normalised now, the same rule
  the §3 skills filter always had.
- **`write` reported a different byte count on each host, and both were wrong** — `utf8-count`
  folded code units, so one file was "8 bytes" on the JVM, "5" on cljgo, and 6 in truth.

**A whole class fell with them: nine host-dependent sorts.** `sort` orders by UTF-16 unit on
the JVM and UTF-8 byte on cljgo, so every sorted output surface — the §0.6 `<skill_files>`
block, the §3 catalog and not-found list, glob, the §7B Agent Card `skills[]`, and `mcp.json`
server order, where **which server wins a name collision** could depend on the host — now goes
through a code-point comparator. Three sorts were deliberately left: their inputs are
ASCII-by-construction, and a change that cannot be made to fail is not a fix.

**The long-standing "cljgo-only flake" was ours.** A fixture pinned at a fixed relative path let
concurrent suite runs trample each other — one run's delete mid-rebuild while another read,
which also produced our historical short-count aborts. Process-unique temp dirs; proven at
5-concurrent red before, 6-concurrent green after, on the JVM. The load-sensitivity hypothesis
this had fed upstream was withdrawn the same day.

**And the gates that let all of this ship green got teeth**: the suite registry is counted and
cross-checked (a dropped suite now fails by name, not by a floor 3× too loose), the five
execution modes must agree with each other to the assertion, the §0.11 test that asserted an
unreachable collision now drives a real MCP peer, and a new `env-chain-check.sh` proves the
API-key fallback chain from outside with fake keys — the one §8 behaviour no in-process test
can reach. Verified live end-to-end against a real provider on both hosts (2 turns, 1 tool
call, identical output) — the port's first live-LLM run.

393 tests / 1608 assertions, five execution modes, both hosts in exact agreement.

### Changed — cross-port conformance gate

- `conformance/check_options_parity.py` now tokenizes **kebab-case**. It previously split on
  `-`, so a Lisp port could never match an option name and reported all 23 as missing when only
  20 were. Applied by file extension: widening it for C-family languages would glue unrelated
  tokens together and manufacture false PASSES, which is the worse direction for a gate.
- **Port tiers.** A port is held to the tier declared in `conformance/options_manifest.json` —
  `full` (every option) or `core` (the §0 conformance contract). A full-tier option missing from
  a core-tier port is **debt, printed by name on every run**, never a pass: a permitted absence
  that stops being reported is indistinguishable from one that was implemented. The tier lives
  in the shared manifest so lowering the bar is a visible diff the other ports review.

### Changed — docs

- The launch explainer video on the docs site now says **seven** languages, and names Elixir and
  Clojure in the parity scene and Hex and Clojars in the closing registry line. It had been
  recorded when there were five, so the one place a first-time visitor hears the parity claim
  out loud was under-counting the ports by two.

### Known gaps

- **The Clojure port's per-request MCP cost is ~5× the Python port's**, isolated to its stdio
  JSON-RPC path (see the benchmark entry above). No OpenSpec change tracks it yet.
- The option gate compares option **names in two files**, so a port can be missing an entire
  subsystem and still report parity OK. A capability-level check belongs beside it.
- **Sorted output is not seven-port identical above the BMP.** python, go, elixir and now
  clojure order strings by code point; js, java and c# by UTF-16 code unit — so any sorted
  byte-exact surface (the §0.6 `<skill_files>` block, §3 catalogs, adapter order) diverges
  between the two camps for a non-BMP tool or skill name. Harmless for ASCII names, which is
  every name in the shared fixtures. Fixing it means SPEC.md pinning one order and three ports
  moving — a cross-port change, tracked here until an OpenSpec change picks it up. (cljgo
  aligning its `compare` with the JVM, requested upstream, would not close this: it would only
  move clojure between camps.)

## 0.12.0 — 2026-07-30

Adds **single-turn translation** (`SPEC.md` §11, ADR-0011) — the inbound half of the format
adapters. Additive: nothing existing changes, and no port behaves differently unless you call
the new entry point.

`SPEC.md §0` item 7 pinned the adapters as *schema only*: `toOpenAI`/`toAnthropic`/`toGemini`
translate tool declarations **outbound**, and nothing read a provider's tool calls back
**inbound**. Every user of those public functions hit the same wall — they could tell a
provider about their tools but not receive the calls it made. So the library served one
posture well ("the library executes tools in a loop") and the majority posture — *"I want
provider-portable tool calling, but **I** execute the tools"*, the premise of the entire
OpenAI function-calling protocol — not at all.

**New: `translate`** (idiomatic naming per port). Exactly **one** provider call, returned in
OpenAI shape. No agent loop, no tool execution, no conversation state — every call is
self-contained, so it can be run statelessly and scaled horizontally.

- **Request** takes the OpenAI `messages`, `tools` and `tool_choice` **verbatim**, so a caller
  never builds provider-native payloads. It also accepts an ordinary **toolkit** — MCP tools,
  skills, native functions, A2A agents, builtins — which is **declared and never executed**.
  The two tool sources compose.
- **Inbound translation preserves tool structure** that a text flattening destroys: an
  assistant turn's `tool_calls` become native tool-use blocks with `arguments` re-parsed from
  its JSON string into an object; a `tool`-role result becomes a tool-result block keyed by
  `tool_call_id`, **merged into one user turn** when consecutive; `system`/`developer` messages
  are hoisted into the provider's separate field; content-parts arrays are flattened. Both
  `arguments` wire forms (JSON string *and* object) are accepted.
- **Outbound** returns `text`, `toolCalls` with `arguments` as a JSON **string** (the wire
  form, echoable byte-for-byte), a mapped `finishReason` — **any tool call wins, giving
  `"tool_calls"`** — plus `usage`, `model` and the raw response. No tool call is dropped or
  truncated.
- **Shares the loop's infrastructure**: retries/backoff, request-param merging and the `llm`
  observability event. `beforeLLM`/`afterLLM` fire **once**; tool hooks never fire, because no
  tool runs.

**Parity verified by byte-diff, not assumed** (spike 0003). One adversarial fixture hitting
every §11 rule at once was run through all six ports and diffed: `js`, `python`, `java`,
`csharp` and `elixir` are **byte-identical to `golang`**, first diff, no corrections needed.
The agreed output is committed at `docs/spikes/0003-translation-parity-fixture.json` so a
future port or refactor can be checked against it directly.

Test counts: js 13 · python 20 · golang 12 · java 13 · csharp 20 · elixir 27. Every port's
full suite green; Elixir coverage 96.9% (gate 95).

Also adds `golang/examples/translator` — a stateless OpenAI-compatible proxy in ~60 lines.

### Relay tools + durable resume — `golang` ONLY, a preview

`golang/` also gains **relay (declaration-only) tools** and an **answer-carrying durable resume**
(`RelayTool`, `RunWithAnswer`/`AskWithAnswer`) built on the §10 suspension primitive — ADR-0010,
issue #37. **The other five ports do not implement this yet**, so it is deliberately **not** part of
the `SPEC.md §0` conformance contract; the §10 subsections carry a status banner and the remaining
ports are tracked as unchecked tasks in `openspec/changes/add-tool-relay-mode/tasks.md`. Saying so
out loud rather than letting parity drift silently is the point.

If you want cross-port behaviour today, use §11 translation. ADR-0011 explains the split: translation
is the right mechanism when the **caller** owns the conversation (the pass-through posture, ~95% of
proxy traffic), and relay is for **proxy-managed memory**, where toolnexus owns the conversation and
the caller sends only the new message. Two postures, two mechanisms.

`golang` relay is green — 24 tests, `-race` clean, and the pre-existing hardened §10 concurrency
tests pass unmodified.

**Fixed:** `python` pinned `mcp>=1.0.0,<2.0.0`. `mcp` 2.x renamed `streamablehttp_client` to
`streamable_http_client`, which broke `mcp_source.py` at **import** time — the whole package failed
to load for anyone resolving a fresh 2.x. Pinned until the rename is adopted.

## 0.11.0 — 2026-07-26

Makes §7F compaction actually reachable from a §7E persona agent. `SPEC.md §7F` defined
compaction *as* a use of the §8 `beforeLLM` hook, but the §7D agent runtime built each
handle's client internally and forwarded no hooks — so the spec promised a capability its
own runtime could not deliver. All six ports; nothing changes unless you opt in.

### Added

- **The §8 seams on a §7D agent run** (`SPEC.md §7D` "The §8 seams on an agent run",
  OpenSpec change `expose-agent-runtime-hooks`, driven by `docs/adr/0008`). `hooks` and
  `onMetric` are now optional on **both** the agent runtime and an **individual agent
  definition** — spelled as each port already spells them (`hooks`/`onMetric` in js,
  `hooks`/`on_metric` in python and elixir, `Hooks`/`OnMetric` in golang and csharp,
  `hooks(...)`/`onMetric(...)` on the java builder). Four rules hold identically everywhere:
  resolved **def-over-runtime, replace never merge**, each field independently (so an agent
  may override `hooks` and still inherit the runtime's `onMetric`); **forwarded verbatim**,
  never composed, wrapped, reordered, defaulted or read; **not a route** to alter the
  handle's composed soul, its §10 escalating `waitFor`, its turn-gated HTTP seam or the
  runtime-wide store (which is why it is two typed fields and not a `configureClient` escape
  hatch); and **unset ⇒ byte-identical** to a runtime without the fields.

  Per-agent is the point: two agents in one runtime can now carry **different compaction
  budgets**, and a metric sink can attribute events to the agent that produced them. Ships a
  shared `examples/agent-hooks/fixture.json` conformance fixture (scenarios H1–H6 plus four
  invariants) cited by every port's test file.

- **golang: `Runtime.ConversationStore()`.** The other five ports already exposed the
  runtime-wide conversation store (`store` in js, `conversation_store` in python and elixir,
  `conversationStore()` in java, `ConversationStore` in csharp); Go had no accessor, so a
  caller had to inject its own `Options.Store` just to read a handle's transcript. Returns the
  injected store itself when one was supplied. Read handle only — the store is still chosen at
  construction. The obligation is now stated in `SPEC.md §7D` for all six.

### Specified (behavior was already correct, but unpinned)

- **Compaction × §10 suspension.** A turn that compacts and *then* suspends is rewound with
  the rest of the turn: the stored transcript returns to its **full pre-turn** state, the
  compaction is discarded, and the resumed replay compacts again. Every port already behaved
  this way by accident; it is now a requirement with a scenario and a per-port test, so a port
  cannot "optimize" by persisting the compacted head.

### Fixed

- **elixir: a wrong-arity `before_llm` hook was silently ignored.** The client guarded on
  `is_function(f, 1)` and otherwise fell through to the no-op branch — the hook simply never
  ran, with no error. It now raises `ArgumentError`. Much easier to hit now that hooks can
  arrive from two places.
- **java / csharp: hooks could be silently dropped on spawn.** Both ports rebuild defs and
  options field-by-field in two places each (`withBudget` + `copyWithRegistry`; `CloneWith` +
  `CloneWithRegistry`), all on the spawn / Level-1 path. Both now carry the new fields, pinned
  by a clone test in csharp. Not a risk in the other four (golang copies by value, js spreads,
  python uses `dataclasses.replace`, elixir uses `Map.put`).
- **elixir: de-flaked the parallel-tool-call test.** It proved concurrency by wall clock
  (`elapsed < 280` over two 150ms sleeps) and hit 524ms on a loaded CI runner. Now asserts the
  **peak** number of tools in flight simultaneously, which serialized execution can never
  reach; mutation-verified by forcing `max_concurrency: 1`.

### Not included

Both deferrals from `docs/adr/0008` stand: a `preCompact` hook able to **abort** a compaction
(new control flow across six ports, awaiting downstream evidence) and `cache_control`
breakpoints (a provider-payload change). Each wants its own ADR.

## 0.10.0 — 2026-07-19

The agent release: both agent archetypes — coding (sub-agents) and persona (agent home) —
ship on a shared actor-model runtime, with compaction to keep either alive over long
sessions. All six ports, byte-parity against the shared `examples/` fixtures.

### Added

- **Context compaction in all six ports** (`SPEC.md §7F`, OpenSpec change `add-compaction`).
  An opt-in `beforeLLM` helper that keeps a long-lived or high-tool-volume agent under its
  context window — **additive, no core loop change**. In the `agents` surface:
  `compactor({ maxTokens, keepTail, summarize, countTokens, flushToMemory })` returns a
  `beforeLLM` hook that, once the transcript estimate exceeds `maxTokens`, replaces the older
  body with one summary system message and keeps a recent tail; below budget it is a **no-op,
  byte-identical** to no compactor. Two invariants: the retained tail begins at a `user` turn
  (**tool-pair safety** — a `tool` result is never orphaned from its `tool_call_id`), and a
  leading `system` prompt is preserved verbatim. `summarize(older)` is pluggable and MAY call an
  LLM; `countTokens` defaults to `ceil(chars/4)` (`estimateTokens`, an estimator not a
  tokenizer); `flushToMemory` injects a pre-compact reminder to persist durable facts via the
  §7E `memory` tool before summarizing. Ships a shared `examples/compaction/` conformance
  fixture and a "keep a persona alive for weeks" recipe (compactor + `flushToMemory` + the
  memory builtin) on the persona-agents docs page.

- **Persona agents (agent home) in all six ports** (`SPEC.md §7E`, OpenSpec change
  `add-agent-home`). The persona archetype over the §7D runtime — additive and opt-in, no
  runtime change. In the `agents` namespace: `fromDir(dir)` (Python `agent_from_dir`, Java
  `agentFromDir`) composes the bootstrap files
  `AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md` (in that order, 2 MB/file cap) into a
  frozen soul snapshot at session start; a file-backed `memory` builtin (`memoryTool(dir)`,
  actions `add`/`replace`/`remove` over `MEMORY.md`/`USER.md`) that writes to **disk** and loads
  at the START of the next session — never mutating the live prompt, keeping a long-lived persona
  cache-stable (a missing substring is a loud `isError`; opt out with `memory: false`); and
  `startAgent(agent, …, { everyMs })` — a heartbeat that posts a coalescing tick to the agent's
  own inbox and wakes it to read `HEARTBEAT.md`, where a `HEARTBEAT_OK` reply stays silent.
  Channels stay the host's job (wire inbound to `post`/`wake`). Ships with a runnable
  `examples/persona-agent/` ("Ava") + JS/Python/Go entrypoints, a "when to use which surface"
  guide, and dream/consolidation + channel-assistant recipes (composition, no new API).

- **Agent runtime + sub-agents in all six ports** (`SPEC.md §7D`, OpenSpec change
  `add-subagents`). A new `agents` namespace per port (never colliding with the A2A
  `Agent`): `agent(name, { does, uses, soul/soulFile, team, budget, model, waitFor,
  onSpawn, onClose })` with `.run(prompt)` and `.asTool()` — an Agent IS a Tool. Delegation
  runs through a built-in `task { agent, prompt }` tool (team-scoped, opt-in per
  definition): isolated child transcript, one tool message back, usage roll-up, parallel
  task calls. Underneath: a Handle state machine with six host verbs
  (`spawn/post/wake/wait/interrupt/close`), two delivery rails, three loud backpressure
  gates, hierarchical live-enforced budgets, §10 suspension escalation with durable resume
  by task-key reattachment, and a per-port cancellation contract.

### Changed — action may be required

- **`RunResult.status` gains `"incomplete"`** (QG5). A `maxTurns` stop that still had tool
  calls in flight — on the plain client `run`/`ask`/`stream` loops as well as agent runs —
  now returns `status: "incomplete"` plus `limit: "maxTurns"` (idiomatic casing per port)
  instead of a silent `"done"`. Any limit stop (turns, tokens, tool calls, wall clock) is
  loud and names its limit; partial work and the transcript are preserved. **Code that
  matches `status === "done"` after hitting `maxTurns` must update** to handle
  `"incomplete"`. The full closed status vocabulary is now
  `"done" | "pending" | "incomplete" | "interrupted" | "closed" | "timeout" | "error"`,
  identical strings in all six ports.
