# Changelog

All ports are versioned and released together; entries here apply to every port unless a port
is named. All seven ports are at tier `full` (js / python / golang / java / csharp / elixir /
clojure) — see `conformance/options_manifest.json`, which is where a
port's tier is declared, never by the port about itself. Releases are cut as
GitHub Releases `vX.Y.Z` via `release.yml` (see `PUBLISHING.md`).

## Unreleased

## 0.20.0 — 2026-09-22

**`retryAfter` on the typed provider error now means the same thing in all seven ports — the raw
`Retry-After` header, verbatim.** *Breaking for python, elixir, csharp and clojure hosts that read
this field.* 0.19.0 shipped the field without pinning its representation, and the seven ports
promptly picked three: js, golang and java carried the raw header string; python and elixir carried
parsed seconds; csharp and clojure carried milliseconds — csharp under a different name again
(`RetryAfterMs`). A host porting retry-handling between ports got a number that was off by 1000, or
a string where it expected a float. Now every port carries the header exactly as the provider sent
it, on a field spelled `retryAfter` / `RetryAfter` / `retry_after` / `:retry-after`, and the port's
natural absent (`undefined` / `null` / `nil` / `None`) when the response sent no such header.

What changed per port: **python** `retry_after: float | None` → `str | None`; **elixir**
`retry_after: non_neg_integer() | nil` → `String.t() | nil`; **csharp** `RetryAfterMs: long?` →
`RetryAfter: string?` (the member is renamed, deliberately); **clojure** `:retry-after` was
milliseconds, now the raw header. js, golang and java are unchanged — they were already right.

The raw header wins because it is **lossless**. `Retry-After` may legitimately be an HTTP-date, or
fractional, signed, out-of-range or unparseable; a numeric field has to null all of those out,
throwing away something the response really did supply and that a host may well want to log or act
on itself. The field is for the host, not for the library. **The library's own retry/backoff
behaviour is untouched**: internally every port still honours only the `delay-seconds` form (whole
seconds, `0` means "retry now") and falls back to exponential backoff for anything else — so
`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT` still produces a backoff wait *and* now still reaches
you verbatim on the field. Each changed port has a test pinning exactly that case. `SPEC.md` §8 now
states the representation outright so it cannot drift again, and `docs/adr/0027` carries a note on
what D3 left unsaid.

**Java can now parse an `mcp.json` without connecting to anything.**
`McpSource.parseConfig(Object)` is public, closing the last availability gap in the seven-port MCP
config parser — Java was the only port where validating config meant either calling `load` (which
spawns processes and opens connections) or reimplementing the wrapper-key rules yourself. It takes a
file path, a raw JSON string, or an already-parsed `Map`, unwraps `mcpServers` / `servers` / `mcp`,
and returns server name → server config. Use it to fail fast on a typo at startup, to filter or
rewrite config before handing it to `load`, or to accept config from somewhere that is not a file.
The change is purely additive: the method's behaviour is unchanged, and it is the same one `load`
and `listMcpTools` have always called. As in the JS and Go references the returned map is not a
defensive copy — copy it before mutating. One difference survives and is documented: C# and Java
spell it `McpSource.ParseConfig` / `McpSource.parseConfig`, where the other five ports expose a free
function (`parseMcpConfig`, `parse_mcp_config`, `ParseMcpConfig`, `parse_config`, `parse-config`).

**Four API-reference pages documented signatures that do not exist, and one documented the wrong
rule.** No library behaviour changed here — but if you copied these, you got code that would not
compile, or a wrong mental model, so they are worth naming. `resume` was written up as returning
nothing in Go (`suspension/resume`, `runtime/handle`) and Python (`suspension/resume`); it returns
the result of the topmost handle the cascade re-ran (`SPEC.md` §7D) — `(TaskResult, error)` in Go, a
`TaskResult` in Python, a result map in Elixir. Go's page also claimed `Resume` errors on an
`answer.ID` mismatch; it never inspects `answer.ID`, and errors only when no handle in the tree is
suspended. Python's `suspension/resume` claimed a resumed `TaskResult`'s counters are per-call; they
are the handle's **own cumulative** totals and grow across a resume, never reset (`SPEC.md`:1078).
Elixir's `client/resilience` still rescued `RuntimeError`, which 0.19.0's typed provider errors
replaced with `Toolnexus.ProviderError`. And the Go and C# `skills/list` pages said a skill is
`malformed-frontmatter` when YAML fails to parse — it is malformed only when YAML refuses it **and**
the lenient line-wise rescue also recovers no `name` (`SPEC.md`:409-416, ADR 0028); both pages
illustrated the rule with a fixture the rescue happily recovers, so they documented the opposite of
what the ports do.

**The API reference now has a coverage gate, and it blocks CI.** `site/scripts/coverage-gate.mjs`
(new `docs-coverage` job) fails the build when a manifest entry has no page in some port, when a
page is still an unfilled generator scaffold, when a page is an orphan nothing points at, or when a
declared parity gap does not say it is one. It also runs `verify-symbols.mjs --strict`, which
existed but ran nowhere, so a renamed export used to surface as a broken page rather than a failed
build. This is the check that was missing when fifteen pages — `client/create` and `toolkit/create`
among them, in five ports — sat as empty `TODO` scaffolds without anything noticing. Page *depth*
(when-to-use / why / three examples) is reported but not yet blocking; the gate prints the
outstanding count on every run.

## 0.19.0 — 2026-09-22

### Eight things that went wrong for people building on 0.18.x, fixed in all seven ports

Eight consumer-reported issues (#86–#93), every one of them found by someone shipping on the
library rather than reading it. The evidence for each — a runnable, hermetic spike and a decision
record — is in `docs/adr/0023`–`0028`; the batch is `openspec/changes/fix-consumer-issues-86-93`.

**You can run a completion with no toolkit at all.** `run`/`ask`/`stream` now accept no toolkit
(and a null one), instead of failing while assembling the system message. Point the client at a
model, send a prompt, get text — no empty `Toolkit` to construct, no `builtins: false` ceremony.
The request body carries no `tools` and no `tool_choice` key at all, not an empty array, so a
provider that treats the two differently sees a plain completion.

**A guardrail on a harness now actually runs when the agent is driven by a loop.** In Go, an
agent's `soul`, `guardrails` and `hooks` were silently dropped on the `Loop` path — you could
write a policy denying `bash`, read it back in code review, and ship a harness with no policy.
Go now applies them, as the other six ports already did. Two more fields join them everywhere:
the harness's `model` and `budget.maxTurns` become loop defaults. Where both the harness and your
client options set a system prompt, **the caller wins** in every port now (JS previously let the
soul override yours). What a loop still cannot honour — `tools`, `team`, `waitFor`, `onMetric` —
is no longer silent: `loopUnsupported(spec)` returns those exact names, the same strings in all
seven ports, and the harness docs table says so. Note in particular that a loop-driven agent has
**no `task` tool and cannot delegate at all**, whatever its `team` declares.

**A delegated run now tells you what it cost and why it stopped.** `TaskResult.totalTokens` **and
`TaskResult.turns`** now mean the same thing on *every* status; each previously meant one thing on
three statuses and something else on the other three, which is unreadable whichever half you were
holding. `totalTokens` is the **rolled-up subtree** total — a parent never reports fewer tokens
than a child it delegated to — and the new `OwnTokens` gives you the per-agent figure when you need
to attribute spend. `turns` is the handle's **own** cumulative round trips, reported identically on
every status; it is **not** rolled up, so a parent that delegates in one turn to a child that takes
five legitimately reports fewer turns than its child. There is deliberately **no `OwnTurns`** —
with no roll-up, `turns` already is the own-figure. **This changes a value you may already be
reading:** on the runtime path, `turns` on `done`/`pending`/`incomplete` becomes the handle's full
cumulative count in the one port that reported a per-run figure there — **Go**. JS and C# already
reported it consistently, so nothing moves for their callers.
`TaskResult` gains `limit`, populated in six more ports and set for
budget stops as well as run limits, so "it stopped" comes with which ceiling. **`limit`'s values are
now a closed vocabulary you can branch on** — `maxTurns`, `maxTokens`, `maxToolCalls`, `maxWallMs`,
`maxChildren`, `maxConcurrent`, `maxDepth`, `completion`, `timeout` — each naming the budget field
that stopped the run, identical in all seven ports. Four ports were emitting their own internal pool
names into that field (`maxWall`, `tokens`, `wallMs`), which defeats the point of a field whose only
job is to be branched on. **If you already branch on one of the old strings, that branch breaks** —
they map onto the canonical spelling above. And the field is now filled consistently: **a run
could previously stop with `status: "timeout"` and no `limit` at all** — the two fields
contradicting each other inside the very feature added so you could branch on a stop. That was
live in three of the finished ports, and port-by-port audits then turned up further construction
sites with the same shape that no bug report had reached. The rule is now a contract, asserted as
an invariant rather than case by case: **a limit stop names its limit; a non-limit stop leaves it
empty.** Both vocabularies are **public API in every port**, so you branch on
`StopLimit.MaxWallMs`, not on a string literal. `Runtime.Resume`
returns the result of the topmost handle it re-ran instead of nothing. Java no longer replays the
literal string `"continue"` as the resumed prompt; C# and Clojure no longer hard-code "maxTurns"
in the stop message when a different limit stopped the run.

**A human's answer can no longer be dropped on a durable resume.** Go's answer-carrying entry point
read exactly two keys out of `Answer.data` — `results`, then `output` — and quietly fabricated a
tool error for anything else while handing you `status: "done"`. Hand it `{"value": "staging"}` and
the reply vanished, the model was shown an invented failure, and your logs looked healthy. Now:
`ok: true` with no determinable result for an outstanding call is an **error to you**, the keys are
named in SPEC §10 for the first time, and two constructors — `answerOutput(id, output)` and its
natural pair `answerDeclined(id, reason)`, for the human who says no — exist in all seven ports so
the map is not hand-built. A non-string `output` errors rather than degrading to `""`. Elixir and
Clojure now accept **string-keyed** answers — §10 says the keys are fixed across ports, but a
JSON-round-tripped `Answer` previously crashed in Elixir and read as a silent *decline* in Clojure,
which is precisely the path the durable posture exists for.

**The documented durable-resume example was wrong, in all seven tabs, and is rewritten.** It showed
resuming by calling `ask` again on the same conversation id. That is not a resume: the stored
transcript already holds the halted call's placeholder, so the tool never re-runs, your `waitFor` is
never called, and the run returns `"done"` with the model answering off the placeholder. The
suspension page now shows two honest postures — a **deferred `waitFor`** that parks the run on a
promise/future/channel and works in all seven ports, and the **answer-carrying resume**, which
exists in `golang` only and is labelled as the preview it is. SPEC §10 no longer says "every port
provides" two lines under its own "golang only" banner, and that banner now names Clojure too.

**Provider error messages no longer carry your account id.** A non-2xx from the LLM endpoint rode
out verbatim in every port — including, in the reported case, a `user_id` — as a bare string a host
had to parse and scrub itself. Failures are now typed values carrying `status`, `body` and
`retryAfter` as fields; `user_id`, `account_id`, `org_id` and `organization` are replaced with
`«redacted»` in **both** the message and the typed `body`; `401`/`403` bodies are blanked; and the
message's excerpt is capped at 200 characters while the typed field keeps the whole redacted body.
A length cap was never the protection — the leaking body in the report was 96 bytes. The SPEC
credentials guarantee now covers error messages, not only headers and `apiKeyEnv`.

**Two different closed sets were both called `status`, and one of them contains `timeout`.** The
agent runtime's `TaskResult.status` has seven values including `timeout`; the client's
`RunResult.status` has three and does not. Branching on the documented set from the wrong section
is what one report was actually about. No public field is renamed — that would break every host to
fix a documentation failure. Instead both vocabularies ship as **named constants** in every port
and are documented as distinct in SPEC §8 and §7D. Relatedly, a Go run that hit its own deadline
returned a zero-value result beside an error; it now returns `status: "incomplete"` with
`limit: "timeout"` and keeps the turns and usage it accumulated. Clojure gains a run-level deadline
it never had, and no longer *retries* a timeout. Elixir's bare `RuntimeError` on timeout becomes a
typed error. The classifier keeps `jev-latest` and gains a `backend` preset (`typesafe` |
`openrouter`) that sets base URL, model and key-env name as a unit, failing at construction on the
known model/backend mismatch rather than at the first call.

**Six more of your skills load.** Frontmatter that real skill-writing tools emit — an unquoted
`description` containing a `:` — was refused outright. Parsing now tries **strict YAML first** and
falls back to a guarded line-wise read only on frontmatter YAML has already refused (column-0 keys,
first wins, any value opening with `|`, `>`, `&`, `*`, `[`, `{` or `!` refused rather than
mis-read), so block scalars stay byte-identical and genuinely broken files are still skipped with
no invented description. On a 87-skill corpus that is 81 → 87. Clojure's hand-rolled frontmatter
reader is replaced with real YAML on both hosts: it loaded 33 of those 87 where the other ports
loaded 69. **Skips now come back as data** on the load result (`location`, `reason`, and a new
`detail` carrying the native parser's error) — you no longer need the inventory surface to discover
that six skills vanished. And **discovery order is now specified**, not left to each ecosystem's
directory walk: roots in the order you passed them, then lexicographic by path within each root.
Duplicate names still resolve first-wins, but "first" now means the same file in every port, and
the rule is the one everyone assumed was already true: **a top-level skill beats a nested copy of
the same name — uniformly, for every name.** Sorting is by **depth first**, then Unicode code point
within a depth. Depth has to be its own key: a pure alphabetical sort silently made the winner
depend on the skill's first letter, so `docx`, `pdf` and `pptx` beat `synced/<uuid>/…` while
`xlsx` lost to it. **Every nested duplicate now loses**, so a duplicated skill's `content` and
`location` can change under you: in `js` that is every duplicated name, and `golang` and `elixir`
change wherever they kept a nested copy. If you rely on a duplicate resolving a particular way,
check which file wins now. (The code-point tie-break, rather than the UTF-16 code-unit default,
matters only for filenames above U+FFFF.)

**The model now sees the same files on your machine and on CI.** Two defects, both in the same
place — what a listing contains, not merely how it is ordered:

- **`glob`, `grep` and the `<skill_files>` sample now sort BEFORE they cap.** They previously broke
  the walk at the limit, so the **filesystem** chose which results the model was shown: a different
  set on a different machine, and on some filesystems a different set between two runs of the same
  code. The rule is now one rule everywhere — collect everything, sort by the path relative to the
  walk root in code-point order, then truncate. This is ADR-0004's `K1` "sort-before-sample parity
  bug", open since that triage, closed in every port. **It changes which files appear, not just
  their order**, so a prompt that depended on a truncated listing may now carry different content.
- **`grep` emits a relative, `/`-separated path**: `rel:line:text` where it used to print an
  absolute one. Five ports had the identical defect — they **sorted on the relative path and
  emitted the absolute one**, ordering by one thing while displaying another, and leaking machine
  paths into model-visible output. **If you parse `grep` output, the string has changed.** Windows
  listings now use `/` like everywhere else.

**The skills prompt is ordered the same way on every machine.** The `## Available Skills` catalog —
which `SPEC §0.10` pins byte-identical across ports — was sorted three different ways, and JS's was
locale-dependent: the same code, the same `skills/` directory, a different order on a machine with
different ICU data. All seven ports now sort by Unicode code point over the skill name, as does the
`skill` tool's "Available: …" list when a skill is not found.

#### What this batch does NOT do

- **Transcript replay on resume is deferred** (`openspec/changes/fix-consumer-issues-86-93`, D3).
  A durable resume still rewinds the suspended turn to its pre-turn checkpoint and replays it, so
  **every tool that ran in that turn runs again**; reattachment-by-task-key protects `task` calls
  and nothing else. A leaf's own `git push` is yours to guard. That is now stated loudly in SPEC
  §7D, on the sub-agents and orchestrator pages, and in the `waitFor` doc comments — because it is
  a conformance decision, not a bug, and changing it needs its own spec delta.
- **Non-relay durable resumes still splice, they do not re-execute** (D4). On the golang durable
  path the recognised payload is spliced into the transcript as the call's result; the tool is not
  re-run and never sees `ctx.answer`, unlike the inline `waitFor` path, which does. Making
  `kind: "input"`/`"approval"`/`"authorization"` resume by re-execution is deferred to its own
  change and is the higher-value of the two.
- **Durable resume is still `golang` only.** `js`, `python`, `java`, `csharp`, `elixir` and
  `clojure` can raise a durable halt but have no answer-carrying way back in
  (`openspec/changes/add-tool-relay-mode/tasks.md`). Use the deferred-`waitFor` posture, which is
  at parity.
- **Where an admission refusal surfaces is unchanged.** Three of the nine `limit` values —
  `maxChildren`, `maxConcurrent`, `maxDepth` — are spawn/admission refusals, and in `js` and
  `csharp` they come back as a **verb error**, never a settled `TaskResult` with a `limit` on it.
  This batch pins the *spelling* wherever a port reports such a stop; it does not move any of them
  onto a settled result, and no port invented a settle path to make the string appear. Whether an
  admission refusal should be an error or a result is a real cross-port asymmetry that lives in the
  verb's **return type**, not in the vocabulary — so it needs its own change, and is tracked as a
  follow-up.
- **The `<skill_files>` sample still emits ABSOLUTE paths**, on every port. It is the one listing
  exempted from the relative-path rule above, and deliberately so for now: `SPEC §3` pins the
  block's shape byte-identical, so a port that switched to relative paths alone would break that
  identity — and unlike `grep`, there is no sort-key/emitted-string mismatch here, because every
  entry shares the skill-directory prefix and both orderings agree. Making it relative needs a
  seven-port decision, not a port-local fix.
- **The classifier's canonical-JSON key ordering is not unified.** `js` orders keys by UTF-16 code
  unit and `golang` byte-wise over UTF-8 — identical for every realistic key, since these are
  schema names, so nothing observable differs today. It is still a genuine cross-port byte-identity
  path, and changing it in one port would *create* the drift it exists to prevent, so it needs a
  coordinated ruling of its own.
- **Nothing stops a *third* vocabulary landing on a field named `status`.** The named constants pin
  today's two sets; no conformance row pins the invariant. Tracked as a follow-up.
### `retries: 0` now reliably means zero retries, in every port, pinned by a test

A consumer building a CLI-backed model (issue #94) asked for 3 attempts and measured 9 backend
invocations — each of their 3 attempts retried twice, at ~15s per process launch, because
`retries: 0` had never meant "no retries" anywhere it wasn't already spelled that way. A spike
(`spikes/retries-zero/SPIKE.md`) found the contract already held in five ports (js/python/java/
csharp/elixir all distinguish an unset field from an explicit `0` through their own idiom) and
was broken in two, in opposite directions:

- **golang** — `Retries` is a bare `int`; its zero value was indistinguishable from "unset", so
  `Retries: 0` silently became the default of 2 retries. Fixed by adding `Retries: -1` as the
  explicit "no retries" spelling; `0` is unchanged and still means the default. `CreateInProcessClient`
  now passes `Retries: -1` directly instead of forcing a private `OnError: TierFail` workaround.
- **clojure** — the opposite bug: this port's shipped default was **0 retries**, not 2, so an
  unset `:retries` client made 1/3 the LLM calls on a transient failure that every other port
  made. Fixed to default to 2, matching the other six ports.

All seven ports now carry two tests each pinning the contract: an explicit "no retries" makes
exactly one backend invocation, and the default (unset) makes exactly three total attempts.

### One model function now serves a top-level client *and* its sub-agents

`createInProcessClient` (0.16.0) let you hand the library a `generate` function instead of an
HTTP endpoint. Sub-agents never got the same option: a runtime accepted only a wire-shaped
transport, so a host whose model was a function had exactly one way to use sub-agents — copy the
library's *private* request/response adapter into its own tree and track an unexported file it
could not import (issue #95). That copy was guaranteed to drift the first time the shape changed
upstream, which is the single failure this repo exists to prevent.

An audit found the gap in **all seven ports**, not just the one that reported it: every port
accepted only a transport, and every port already built an equivalent generate-backed adapter
privately. So two things change everywhere:

- the agent/sub-agent runtime accepts a semantic `generate` (`InProcess` / `in_process` /
  `:in-process`, per port idiom) as an alternative to a transport or provider config. Supplying
  both fails at construction, naming the conflict — never resolved by a silent precedence rule.
- each port's generate-backed adapter is now **public** (Go `InProcessTransport`, Python
  `InProcessTransport`, Java `InProcess.GenerateBackedHttpClient`, C# `InProcess.GenerateBackedHandler`,
  Elixir `in_process_transport/1`, Clojure `in-process-http-client`), and each port's in-process
  client is a *caller* of it. One adapter, no duplicates.

Concurrency is unaffected: every port's turn gate already wrapped whichever transport resolved, so
the in-process path is gated by construction. Each port ships a gate test with a negative control.

Fixed in passing, **csharp only**: `GenerateBackedHandler.SendAsync` never actually yielded, so
in-process turns ran fully synchronously inside the runtime lock — shipped behaviour since 0.16.0.

### ACP: talk to devin, Gemini CLI or Zed's agents as a model, over one warm session

MCP is how toolnexus consumes *tools*; ACP (Agent Client Protocol) is the equivalent for consuming
a whole *agent* — JSON-RPC 2.0, one object per line, over a child process's stdin/stdout. New in
every port: connect to an ACP agent and use it as the model behind a client (issue #96), so the
tool-calling loop, skills, MCP tools and sub-agents work through it unchanged.

The point is process startup. Driving an agent CLI one-shot pays its launch cost on *every* turn
of the loop; a warm ACP session pays it once. Against `devin` (SWE-1.6 Slow) a turn went from
~15s to ~1.6s by the third prompt, and prompt size turned out to be free — a 12 KB prompt cost no
more than a 17-byte one. **Stated plainly, because it is easy to over-read: that win is the CLI's
startup cost, not a protocol-level speedup.** Against a server with no startup cost the same path
gains almost nothing.

A stateful session collides with how toolnexus assembles a complete request every turn — the
session accumulates near-duplicate histories and the agent will sometimes answer the stale one.
The library sends the full request and marks it as superseding everything earlier; you do not have
to write that line yourself. Sending only the delta is faster still but makes the client own a
shadow transcript that can desynchronise from your `ConversationStore`, so it is not offered.

Also handled, because each one silently breaks a run otherwise: only `agent_message_chunk` forms
the reply (thoughts and tool narration are dropped, or they wrap prose around structured output);
`session/request_permission` is answered rather than awaited (unanswered, a turn hangs forever,
even in bypass mode); turns on one session are serialised; the process outlives any single turn's
cancellation; `close` is idempotent. `session/new` sends an absolute `cwd` and an `mcpServers`
array — real `devin acp` rejects anything else with `-32602`.

**python only**: its in-process seam is synchronous by contract, so the ACP client runs a
background reader thread and a queue rather than widening that seam — `generate` stays an ordinary
synchronous function and nothing async crosses the boundary.

### Not done

- The one-shot CLI-backed model source (issue #97, `docs/adr/0032`) is designed and spiked but
  **not implemented in any port**. ACP covers agents that speak the protocol; a CLI offering only
  `-p` still needs a host-written adapter.
- ACP **delta mode** is not shipped; every turn sends the full request.
- A `generate` that internally relaunches a CLI or repairs a malformed reply reports **one** LLM
  metric event for N underlying calls, so that cost is currently invisible to client metrics.

## 0.18.1 — 2026-09-21

Documentation only. No code changed in any port; the published packages differ from 0.18.0
solely in the README each registry shows you.

### A documentation release no longer pays for a live model call

Every registry leg `needs:` the `live-scenarios` job — a real, paid call to a provider that
proves the harness mechanisms work before anything publishes. That is the right gate for a
release that changes code. For a release that changes only a README or a docs page it re-proves
exactly what the previous release proved, costs money, and adds one more way for a publish to
fail for no reason.

`release.yml` now decides. A new `scope` job diffs the tag against the previous one and sets
`code_changed`; `live-scenarios` runs only when it is true, and every registry leg accepts a
**skipped** live proof while still refusing a **failed** one.

**All seven registries publish either way.** The gate decides whether to re-prove the library,
never whether to ship it — the ports move in lockstep or their version numbers drift apart, which
is the failure `preflight` exists to catch.

Two details that decide whether a gate like this is honest:

- **The version manifests are excluded from the code set, and then put back.** Every release
  bumps all six by definition, so counting them as code would make `code_changed` permanently
  true and the gate would never fire. So each manifest's own diff is re-read, and a manifest that
  changed for anything other than its version string — a new dependency, a changed target
  framework, an edited script — is promoted back to code and the live proof runs.
- **Anything the filter has not seen counts as code.** The doc paths are enumerated; everything
  else, including workflow files, defaults to running the proof. A gate that guesses "probably
  docs" about an unfamiliar path is a gate that eventually skips a real release.

Verified in both directions before shipping: this release classifies as documentation-only, and
`v0.17.0..v0.18.0` — 340 changed files — classifies as code.

### The READMEs are a front page again, not a second copy of the manual

Eight READMEs went from **3249 lines to 594**. Each one now answers what it is, how to install
it, one quick start, and the single thing that port does differently — then points at the
documentation site and that language's API reference for everything else.

Nothing was lost. Every section removed already had its own page: suspension, memory,
observability, native and HTTP tools, attachments, built-ins, A2A, serve-as-MCP,
bring-your-own-loop, sub-agents, streaming. What went was a duplicate that drifted — and the
duplicate nothing checked, since no gate reads a README while the site's 968 documented snippets
are compiled and executed on every change.

Two things that had already drifted, now correct:

- **Every port advertised parity across five languages.** "Also in JavaScript, Python, Go, Java,
  and C#" — Java said "five languages", Elixir said six. There are **seven**; Elixir and Clojure
  had been shipping for releases without appearing in the claim.
- **Clojure's README undersold its own test suite**, quoting "291 tests / 1100 assertions" across
  "all five execution modes" when it is **495 / 2051**, and listing three modes where
  `all-modes-check.sh` runs five — both REPL evaluators were missing.

### Jev, on the typed-decisions page

Jev is the model behind the default `systemone` backend and most of the public interest in it is
game demos, so the page now says plainly what toolnexus does with it: **Jev is one backend behind
a vendor-neutral seam, not an integration.** `llm` runs the same questions on any chat model,
`custom` is your own function, `static` replays a recorded corpus offline — swap the backend and
your questions do not change. Links to the wire documentation, the gateway, the live
measurements and the encoding rules, and names both public game repos, including the one that
sends `criteria` with no per-option description — the exact mistake ADR 0021 measured at 17
apples against 0.

### A real call you can watch

[Measured on a live backend](https://muthuishere.github.io/toolnexus/harness/judge-live/) was
170 lines of generated tables and nothing to watch. It now opens with a 12-second recording of
`js/examples/judge.ts`, unmodified, making one real POST to the systemone wire over OpenRouter —
three question types in a single round trip, for $0.000022.

It also demonstrates the page's own argument. The take that shipped answered `urgency` **0.53 —
"level 1: the customer is inconvenienced"**; the take before it, identical bytes and identical
model, answered **0.47 — "level 0: the customer is working normally"**. A score sits between
levels and it moves, which is why no test asserts a live number and why `static` is the backend
CI runs.

Reproduce it with `OPENROUTER_API_KEY=… vhs site/scripts/judge-run.tape`. The tape is in the repo
and holds no credential: the library reads the key at the point of use and it appears in no
output, which is the only reason a recording of a credentialed call can be published.

## 0.18.0 — 2026-09-21

### Fixed — a classifier retry backoff no longer lets Node exit out from under it (javascript)

`Classifier`'s backoff timer was created and then `unref`'d. An unref'd timer does not keep
Node's event loop alive, so when a retry backoff was the only pending work, the loop could
resolve before the retry ever happened — the process exiting mid-retry rather than completing it.
The timer is awaited, so it is real pending work and must hold the loop open; the §8 `Client`'s
own `delay` has never unref'd, for exactly this reason. The request timeout watchdog still
unrefs, which is correct: a watchdog should never be the reason a process stays alive.

JavaScript only — the other six ports use blocking sleeps with no equivalent notion.

Found by CI rather than by reading: Node 22 cancelled every test after the first one to exercise
a classifier retry ("Promise resolution is still pending but the event loop has already
resolved"), reporting `237 passed, 0 failed, 8 cancelled` and exiting 1. Node 24, which the work
was written on, hid it completely. The suite is now 245/245 on both.

### Four quiet disagreements between the ports, closed

Each of these produced a green build and a valid-looking request, which is why they went
unnoticed. Two are contract that was never written down; two are Clojure behaving unlike the
other six.

**`decisions` is now gated.** The `static` backend's recorded corpus is what CI runs on — no
network, no credential, and the only backend a test may assert a number against. Every port had
it; the options manifest had no row for it and `SPEC.md §8B` named the `"static"` style without
naming the field that feeds it. The single option the whole test strategy rests on was the one
option parity could not see. It is now a **core**-tier row (classifier options: 16 → 17), so a
port that drops it fails the check rather than losing the ability to run the shared fixtures
quietly. No port code changed.

**An absent `calibrated` means `true`, and now says so.** All seven ports already decoded a
missing or `null` `calibrated` as `true`, treating only the literal `false` as false — seven
ports agreeing by inspection rather than by contract. `SPEC.md §8B` states it and each port pins
all four cases in a test. Nothing changes today; what changes is that a port cannot later default
it to `false` and silently invert every threshold you tuned, on a field your backend never sent.

**Clojure: `choice-over` no longer ships the colon.** A keyword key reached the wire as
`":billing"` rather than `billing`, because the port stringified ids with `str` instead of their
name. That is schema-valid, returns HTTP 200 and a well-formed distribution — with option ids
that differ from every other port (Elixir's atom `:billing` has always sent `billing`) and from
the string you then compare `(:choice answer)` against. Keys may now be strings, keywords or
symbols, and each travels as its plain name; a qualifier is kept (`:desk/billing` ⇒
`desk/billing`) so two distinct options cannot collide into one id. **If you keyed a downstream
branch on the `":billing"` form, drop the colon.** If you passed strings — which the port's own
docs told you to — nothing changes.

**Clojure: the client's retry backoff matches the other six.** `:retry-base-ms` defaulted to
`250` where every other port defaults to `500`, and the backoff omitted the `+ jitter(0–99 ms)`
term the others add. The jitter is not cosmetic: without it, a fleet of hosts that failed against
the same upstream at the same moment retries against it at the same moment, so the retry turns one
spike into several. Clojure now waits `base * 2^attempt + jitter(0–99 ms)` from a default of
`500`, and a usable `Retry-After` still wins outright. **A Clojure host that never set
`:retry-base-ms` now waits ~500 ms before its first retry instead of ~250 ms.**

Tracked in `openspec/changes/close-typed-decision-parity-gaps`.

**Clojure's `Classifier` example now runs on both hosts.** `examples.judge` was in the JVM
runner and nowhere in the cljgo one — no `run_judge.cljc` entry, no `ex-judge` build target — so
the port's whole claim, one source tree behaving identically on two hosts, went unchecked for its
newest subsystem. It is now the seventh example in both runners, AOT and interpreted, and CI runs
both. All seven pass on both hosts.

### The classifier's retry backoff is yours to set, in every port

`Classifier` retried a transient failure on a backoff base nobody could change: a hardcoded
`500 ms`, so two retries cost `1.5 s` of real waiting. Only JavaScript exposed a
`retryBaseMs` for it — one port with an option the other six lacked, on a seam `SPEC.md §8B`
says mirrors `ClientOptions` field-for-field.

`ClassifierOptions` now carries it everywhere, spelled natively: `retryBaseMs` (JavaScript, Java),
`RetryBaseMs` (Go, C#), `retry_base_ms` (Python, Elixir), `:retry-base-ms` (Clojure). The default
is `500`, the delay is still `base * 2^attempt` with no jitter, and a `Retry-After` header still
wins over it — so if you do not set the option, nothing about your timing changes. Set it to `1`
and a test that exercises the retry path stops sleeping: our own classifier retry tests dropped
from 1.51 s to 0.01 s (Go) and 2.56 s to 0.05 s (Java), and the C#, Elixir and Clojure suites no
longer fake a `Retry-After: 0` header to stay off the clock.

The option is registered in `conformance/options_manifest.json` (classifier options: 15 → 16), so
a port that forgets it now fails the parity check instead of drifting quietly.

**Follow-up, now shipped:** this entry originally reported two unfixed differences in the
*client's* backoff on Clojure. Both are closed below, under "Four quiet disagreements".

### TypeSafe's own API is a documented way to run a `Classifier` — and two defects it exposed

`style: "systemone"` already reached TypeSafe's first-party API by default
(`https://api.typesafe.ai/v1`, model `jev-latest`, `TYPESAFE_API_KEY`), but everything written down
pointed at OpenRouter's gateway. Both are now documented side by side on
[Backends & configuration](https://muthuishere.github.io/toolnexus/judge/backends/), and every
port's `examples/judge.*` picks its backend from the environment: `TYPESAFE_API_KEY` first,
`OPENROUTER_API_KEY` second, and with no key at all the existing offline `static` replay, exactly
as before. No default changed, and no new API was needed — `baseUrl`, `model` and `apiKeyEnv` were
already enough.

They are **equivalent in latency** (339 ms / 449 ms p50 / p95 against 351 ms / 400 ms, warm and
interleaved — a tie). The reason to use the first-party key is one fewer party in the path, not
speed. The one functional difference: **TypeSafe returns no `usage.cost`**, so a cost-based budget
only works through the gateway.

Pointing at it turned up two real defects, fixed in all seven ports:

- **`529 Overloaded` was not retryable, anywhere.** The default retryable set was
  `429/500/502/503/504`, so TypeSafe's documented "retry with backoff" status failed hard on the
  first attempt. `529` is now in the set — on the client path and the classifier path alike — and
  that is the whole behaviour change: **no status other than `529` changed classification.** The
  set stays an exhaustive enumeration rather than "any 5xx", because sweeping in permanently-broken
  statuses like `501 Not Implemented` would change the retry behaviour of every existing host
  without asking.
- **`retryableStatuses`, a new option on both `ClientOptions` and `ClassifierOptions`** (named per
  port: `RetryableStatuses`, `retryable_statuses`, `:retryable-statuses`). It is how a backend with
  its own transient status opts in — the case it exists for is a Cloudflare-fronted origin
  answering `520`–`527`:

  ```js
  createClient({ retryableStatuses: [520, 521, 522, 523, 524, 525, 526, 527] })
  ```

  It is **additive and cannot subtract**: listing statuses never removes `429` from the set, so you
  cannot accidentally lose `Retry-After` handling by using it. It decides the *default*
  classification only — `onError` still runs on every failed attempt and has the final say, so
  `onError` returning `"fail"` overrides a status you listed yourself. Registered in
  `conformance/options_manifest.json`, so the parity check covers it in all seven ports.
- **The docs were wrong about the retry set, and had been all along.** `SPEC.md` §8, §8B's option
  table and every port's `retries` doc comment said "429/5xx/network" while the code enumerated
  five statuses. The prose now states the real set (`429`/`500`/`502`/`503`/`504`/`529`, plus `408`
  on the classifier path) and points at `retryableStatuses` for anything beyond it. **No behaviour
  changed to make that true** — the documentation was corrected to the code, not the reverse.
- **"cost absent" was not representable in Go or C#.** `ClassifierUsage.Cost` was a bare `float64`
  / `double`, so against a backend that reports no cost both said **$0.00, as though the call were
  free**, when the truth was "unknown". Go now exposes `*float64` and C# `double?`, matching
  js/python/java/elixir/clojure, and a real `0` is still a real `0`. **This is a source-breaking
  change for Go and C# hosts that read `Usage.Cost`** — dereference or null-check it.

### Typed decisions — a `Classifier`, in all seven ports

Half the decisions in an agent are not actions, they are **judgments**: is this command risky, does
this turn need the billing skill, how urgent is this ticket. Until now you asked a chat model and
parsed prose back, with a round self-reported confidence and no distribution. `Tool` is the
contract for an action; `Classifier` is the contract for a judgment.

You declare typed questions once and get calibrated numbers back — no free text, no tool calling,
no loop:

- **`noul`** — one probability in `0..1`. It reports no confidence; the number *is* the answer.
- **`choice`** — one of your named options (up to 255), with a probability for **every** offered
  option and a confidence.
- **`score`** — a rating against an ordered rubric of 2–10 levels, and `1.21` is a real answer.

The keys you address the questions by are never transmitted, so a key may be a tool, skill or agent
name verbatim. Limits are enforced client-side before the request, and the error names the question
key rather than making you read the backend's 400. Four backends: `systemone` (the wire),
`llm` (the same questions as one structured-output call on the §8 client you already have — the
exit for a host with no System One credential), `custom`, and `static`, which is what CI runs: no
network, no credential. `ClassifierOptions` mirrors `ClientOptions` field-for-field, reusing the §8
`onError`/`Retry-After` policy verbatim rather than growing a second one — with two deliberate
differences: `apiKeyEnv` takes the **name** of an environment variable rather than a value, and
`timeout` bounds one request rather than a run.

**Two things in it exist because they were measured, not designed.** Describing your options is not
style advice: options described by consequence scored 17, 17, 17 apples, and the identical state
with each option replaced by its own id scored 0, 1, 0 — the floor of a shuffle control. That shape
is schema-valid and returns HTTP 200, so nothing would have told you. Now something does: a `choice`
whose criteria are all empty, all equal to their own keys, or all identical emits **one** warning
per question key through your existing metric sink, naming the key, and sends the request
byte-unchanged — detection, never repair. That warning arrives as a `classifier.warning` event
carrying its text in a **`warning`** field, never in `error`: it is advisory, not a failure, so a
consumer already counting failures off the same sink does not start counting warnings as outages. And every choice answer carries a derived `nearUniform`
(`max|p − 1/n| ≤ 0.05`), the one encoding health check that needs no ground truth and can run on
live traffic.

Both are **advisory, and neither is a correctness signal**. `nearUniform` cannot separate a good
encoding from a subtly wrong one, and confidence is no help either — the worst *working* encoding
measured carried the highest median confidence. More bluntly: a classifier **interprets, it never
authorises**. "Cannot hallucinate" means only that the value is inside the declared schema. Numeric
limits, permission checks and allowlists stay in your code, and a threshold tuned against one
backend does not transfer to another — which is what `Decision.calibrated` is for, and why you read
it before you compare a number to a threshold.

Same behaviour in js, python, golang, java, csharp, elixir and clojure, pinned by seven shared
fixtures in `examples/judge/` that every port asserts byte-for-byte. A host that constructs no
`Classifier` behaves byte-identically to a build without any of this, proven by a test rather than
asserted. New docs: **Cookbook → Typed decisions (judge)** and **Harness → Judge, measured live**.
Contract: `SPEC.md` §8B; encoding decisions: `docs/adr/0021`.

**What is NOT done**, and where it is tracked:

- **No adapters and no batteries.** There is no `SkillRelevance`, `ToolGuard` or `Verified` built on
  this seam yet, and no model routing — you write the questions yourself. Tracked in
  `openspec/changes/add-judge` as the follow-up `add-judge-adapters`.
- **The live measurements on the judge page are recorded, not regenerated.** There is no
  `judge-live` harness runner wired into the suite the way `harness/live` has one, so re-running
  them is a manual step. The page says so, per table, with its source named.
- **The `llm` backend is a compatibility exit, not an equivalent.** It reports
  `calibrated: false`, returns no real distribution, and on one measured fixture disagreed outright
  with the calibrated backend.
- The parity gate's temporary `landing` flag — which let `conformance/check_options_parity.py`
  report a not-yet-written port file as something other than a failure while these seven ports were
  being written — **is deleted with this change**, along with the code path that honoured it. A
  missing options file is a failure again, for every group.

### Spec — a `beforeTool` hook can raise a suspension, and now the contract says so

`SPEC.md` §10 defined a suspension by the *result* — a `ToolResult` whose `metadata.pending` is a
`Request` — but only ever described a tool producing one. A `beforeTool` hook short-circuiting
with that same result suspends the run identically, and the guarded tool never executes. Five
ports already implemented and tested it; it was simply never written down, so nothing said it was
guaranteed rather than incidental.

§10 now names both paths (A: the tool returns it, B: a hook short-circuits with it) and requires
every port to resolve a hook-raised suspension through `waitFor` exactly as it resolves a
tool-raised one, and to halt with the hook's own `Request` when no `waitFor` is set. This is what
a three-state policy gate needs: a guardrail returns a string and has only allow and deny, so
"ask a human first" belongs on `beforeTool`. Two coverage gaps closed with it — Go lacked the
hook-raised-plus-no-`waitFor` corner (the one a durable approval queue actually ships on), and
Elixir had no path-B test at all.

### Fixed — a guardrail that is not a plain string is refused, loudly, in every port

A `Guardrail` returns a verdict string synchronously: `""` or `"allow"` to permit, any other
string to deny. Handing it something else — most naturally an `async` function — did not report
an error anywhere. It silently did the wrong thing, and it did **three different wrong things**
depending on the port:

- **JS and Python** took the deny branch on every call. A `Promise`/coroutine is truthy and is not
  `"allow"`, so an async guardrail **denied every tool call in the run**, with the reason rendered
  as `denied: [object Promise]` / `denied: <coroutine object …>` (Python additionally leaked an
  un-awaited coroutine). An agent wired this way could not call a single tool, and nothing said why.
- **Elixir and Clojure** did the opposite: a non-string verdict failed the `is_binary` / `string?`
  test and fell through as an **allow**, so a policy check silently widened — the one direction a
  guardrail must never fail.
- **Go, Java and C#** were never reachable; their `Guardrail` type returns a `string`.

All four dynamic ports now raise immediately, naming what came back and where asynchronous work
belongs (a `beforeTool` hook, which is awaited). The contract is unchanged and stays synchronous
in all seven ports, so every guardrail that was already correct keeps its exact behaviour; only
the previously-silent mistake is now loud. If you need a judgement that takes I/O — a policy
service, a classifier — put it in `beforeTool` rather than in a guardrail.

### Fixed — `Run`/`Ask` accept a nil toolkit as "no tools" (golang)

`client.Run(ctx, prompt, nil)` panicked with a nil-pointer dereference, although §8 already
defines what an empty tool list does (the `tools` key, and `tool_choice` on the openai style, are
omitted from the request entirely). Passing `nil` is the obvious way to say "this call needs no
tools", so it now means exactly that: `Tools`, `ToOpenAI`, `ToAnthropic`, `ToGemini`,
`SkillsPrompt`, `Get`, `Execute` and `McpStatus` are all nil-safe on the receiver. Building an
empty `Toolkit` still works and is unchanged.

### Docs — the coding-agent scenario no longer says the agent runtime hides the hooks

The page claimed the runtime "does not surface" `beforeTool`/`afterTool`, and listed that under
its honest limits. That stopped being true when the harness and loop shipped: an agent spec takes
`hooks`, which the runtime forwards verbatim, and `guardrails`, which compile into one
`beforeTool`. The page now explains when to wrap a tool and when to use a hook — wrap when the
rule belongs to one tool and should travel with it, hook when the policy spans many — and the
limits list carries the guardrail's synchronous contract instead.

## 0.17.0 — 2026-09-02

### Fixed — an aborted A2A call reports a cancel, not a transport error (all ports)

`SPEC.md` §7A pins the contract: a `ctx` abort means `"A2A task <id> canceled"` with
`metadata.state == "canceled"`. That held when the abort landed *between* polls. When it landed
while a `SendMessage`/`GetTask` request was in flight, four of seven ports reported whatever the
transport happened to raise instead — Java surfaced the literal string
`"java.lang.InterruptedException"`, Go returned `context canceled` with the state still
`submitted`, and Elixir had no abort semantics at all: `Context.signal` existed in the struct and
nothing in the port ever read it.

Every error and exit path of an agent tool's `execute` now re-checks the abort signal before
reporting a transport error, so an abort observed anywhere — between polls, mid-`SendMessage`,
mid-`GetTask` — produces the same result. Each port gained tests for both in-flight cases.

Worth naming because it explains a symptom you may have seen: in the JS port this defect class had
already shown up as a "flaky" test that failed roughly twice in twelve runs. It was not flaky. It
was intermittently catching a real defect, which is what prompted the cross-port audit.

Closes #64. Tracked in `openspec/changes/audit-a2a-cancel-mid-request`.

### Fixed — MCP tools no longer lose their images (all ports)

Every port filtered an MCP `CallToolResult`'s `content[]` down to text parts and **silently
discarded the rest**. Point toolnexus at a screenshot tool, a chart tool, or Playwright MCP and
you got an empty string — no error, no log line, no way to distinguish "the tool returned
nothing" from "your picture was thrown away". It was specified that way (`SPEC.md:218`), so it
was a contract bug, not a slip, and it was identical in js, python, golang, java, csharp, elixir
and clojure.

Non-text content now becomes `ToolResult.parts`: image, audio, embedded resource, and
**`resource_link`** — a fifth block type that every SDK exposes and that we were dropping too.
Parts are collected on **every** branch, including the `structuredContent` and `isError`
short-circuits that returned before the content list was ever read; a server sending structured
content *and* an image kept neither before, and keeps both now. A text-only tool result is
byte-identical to before, down to the absent `parts` key.

**This is a behaviour change.** If you relied on non-text MCP content vanishing, it no longer
does — see the unsupported-part rule below, which is designed so that this cannot break a run
that works today.

### Added — attachments: images, PDFs and audio, in and out (all ports)

You could not hand the loop an image, and a tool could not return one. Both now work.

```js
await client.run(["What is broken in this screenshot?", await attach("./shot.png")], { toolkit })
```

Parts go in the **first** argument, alongside your text, because the order of text and image is
semantic to a model and an `{attachments}` option throws it away. Passing a plain string is
unchanged and byte-identical — no existing call site moves.

A `ContentPart` is `text`, `image`, `file` or `audio`, carrying base64 `data` or a `url` plus a
`mimeType`. **It never holds a filesystem path**: a path does not survive a transcript being
persisted and replayed, or crossing into a subagent, a served toolkit, or an A2A peer. The
convenience lives at the edge instead — the constructors take a path, native bytes, or a `data:`
URL and normalise immediately, so the disk touch is one legible call and the part that reaches
your transcript is portable. Mime type comes from a fixed extension table shared with `read`;
it is never sniffed and never resolved through the platform mime database, whose contents differ
per machine and would quietly break cross-port parity.

**The edge takes what you already have.** A caller holding an `InputStream`, a `FileInfo`, a
`Blob`, an `io.Reader` or an open file handle should not have to convert it by hand — that tax is
paid in every calling program instead of once in the library, and it is the same tax as making
you base64 things yourself. So each port accepts its own native sources: `File`/`Blob`/
`ArrayBuffer` in js, `os.PathLike` and any binary file-like object in python, `io.Reader`/
`fs.File`/`fs.FS` (so an `embed.FS` works) in golang, `java.io.File` and `InputStream` in java,
`FileInfo` and `Stream` in csharp, iodata and `File.Stream` in elixir. Clojure takes a path or
bytes and **says so** — `java.io.File` is JVM-only and cannot appear in dual-host `.cljc`, so
rather than fake it that port gives you a named error telling you what to pass.

The rule is *accept broadly, store narrowly*: whatever goes in, what lands in your transcript is
bytes and a mime type. Streams are read **eagerly at construction** — a part holding a half-read
stream would survive a replay no better than a path does — and a handle you supplied is read, not
closed; disposing it stays yours.

Tools can return parts too — `ToolResult` gains an optional `parts` alongside its still-required
`output`, so the transcript, compaction and any text-only provider keep seeing text.

`read` now returns an image part for a recognised media file. **golang only**: `File(path)`
carries its read error until `RunParts` surfaces it, so attaching a file adds no second `err` to
handle. **python only**: reading a binary file used to raise an unwrapped `UnicodeDecodeError`
straight into the client loop rather than returning an error result; it now returns one.

### Added — a tool that returns an image reaches the model on every provider

The two provider styles disagree, and the disagreement is load-bearing. Anthropic accepts image
blocks inside `tool_result.content`. OpenAI rejects them outright — `Image URLs are only allowed
for messages with role 'user', but this message with role 'tool' contains an image URL`, a hard
400, verified live rather than assumed.

So parts ride natively where the style has a shape for them, and only for `openai` are they
relocated into a single synthetic `user` message after the last tool message, in tool-call order,
each labelled with the tool it came from. Relocating on *every* style would have been simpler to
describe and worse to use: it discards the `tool_use_id` association, breaks cache breakpoints,
and makes the model read tool output as user input. The synthetic message is an adapter artifact
and is never written to `RunResult.messages` or the `ConversationStore`, so switching provider
mid-conversation leaves no OpenAI-shaped residue.

### Changed — an unsupported part is never dropped, and never breaks a working run

Silence is what this whole change exists to remove, so nothing is discarded quietly. But
erroring on everything would have been its own regression: an MCP server that volunteers an audio
clip would start failing runs that succeed today. The rule therefore follows **intent**. A part
*you attached* that the provider cannot represent is a typed error before any HTTP call — you
asked for something specific and silently changing it is the betrayal. A part that merely *arrived
from a tool* degrades to a named placeholder with a warn-once. `onUnsupportedPart: "error" |
"text"` overrides both.

The guard is a positive allowlist over the encoded block, not a mapping that hopes for the best.
That is not caution for its own sake: sending an unrecognised block type upstream returns **HTTP
200 with the content silently discarded** — the same failure this release fixes, one layer up.

`anthropic` names `audio` as a refusal, because the provider defines no audio block.

**clojure only**: that "typed error" is a **value**, not a throw — the port's rule is that nothing
crosses a source boundary as an exception. An unsendable part (attached-and-unsupported, both
`data` and `url`, an unknown extension, over `:max-part-bytes`) comes back as a `RunResult` with
`:status "incomplete"`, `:limit "contentPart"` and an `:error {:code :message}`, and no HTTP
request is made. The edge constructors follow the same rule: they return an error part carrying
the reason rather than raising, the way the Go port's `File(path)` defers its read error.

### Changed — content parts survive translation (`SPEC.md` §11)

The spec said an array `content` is flattened to text. Six of seven ports actually passed a
text-empty array through raw and undocumented — one of them with a comment explaining it. Both
the spec and the code now carry one rule: text parts concatenate, non-text parts translate. The
seven ports agree by specification rather than by coincidence.

### Known upstream hazard — OpenRouter drops images to Anthropic models

Verified live, and worth knowing before it wastes your afternoon: an image sent to
`openrouter.ai` for an **Anthropic** model arrives as **HTTP 200 with the image silently
discarded**. Measured with the 82-byte `examples/media/fixture.png`, as a prompt-token delta:

| model, via OpenRouter | delta | |
|---|---|---|
| `anthropic/claude-haiku-4.5` | **+4** | dropped (5/5 trials) |
| `anthropic/claude-sonnet-4.5` | **+4** | dropped |
| `openai/gpt-4o-mini` | +8500 | delivered |
| `google/gemini-2.5-flash-lite` | +258 | delivered |

**Switching to `style: "anthropic"` does not help** — OpenRouter's Anthropic-compatible
`/v1/messages` drops it the same way (14 → 18 input tokens for a hand-written native
`source{}` block with no toolnexus in the path). The route is the problem, not the block
shape. To send images to an Anthropic model, point `baseUrl` at `api.anthropic.com`
directly.

Nothing in toolnexus can fix this; the block we emit is the documented one, and the loop
completes correctly. The danger is that the model then *answers anyway* — ours replied
"Blue, Red, Yellow, Green" about an image it never received. That is why
`scripts/live-multimodal-check.py` and every port's `examples/multimodal.*` prove arrival by
**prompt-token delta** and never by reading the reply.

### Not done, and where it is tracked

Tracked in `openspec/changes/add-multimodal-content`:

- **Gemini request emission.** No port has a Gemini request path — `ClientStyle` is
  `openai | anthropic`, and `toGemini` only emits tool declarations for a caller's own client.
  Attachments work on both implemented styles; Gemini needs a client first.
- **A2A message parts** and **skill resources as parts** — both still text-only.
- **Provider `fileId`** — the answer to sending the same 5 MB PDF on twenty turns. Deferred.
- **Model image *output*.** Input only.
- **python only**: `countTokens` does not exist in that port, so the compaction mitigation that
  charges for part bytes has nothing to hook into there yet.

## 0.16.0 — 2026-08-17

### Fixed — an in-process client no longer needs an API key

`createInProcessClient` advertised "no `apiKey`" and then failed without one: the client resolves a
key from the environment and errors when it finds none, so an in-process model — which has no
endpoint to authenticate to — still demanded one. Every local run passed because a developer shell
has `OPENROUTER_API_KEY` set; CI, which has none, failed the in-process tests in **all seven ports**
at once. The constructor now supplies its own sentinel so that resolution never runs, and js/python
pin it with a test that strips the variables from the environment first.

### Added — `createInProcessClient`: a model in your process, with no wire to configure (all ports)

Running a model inside your own process was possible, and it made you lie three times: a `baseUrl`
that is never dialled, an `apiKey` for an endpoint with no auth, and a `style` for a wire that does
not exist. Two of those were already optional and were only ever noise in our own docs. `baseUrl`
was genuinely required — and omitting it did not error, it crashed with
`Cannot read properties of undefined (reading 'replace')`.

The bigger tax was the envelope. The seam is HTTP-shaped, so a host that only wanted to answer a
question had to **build an HTTP response first** — a `Response` in JS, an `*http.Response` in Go, an
`HttpResponseMessage` in C#, and in Java a **94-line `HttpClient` subclass**. Every example in our
own cookbook opened by defining the same `LocalModel.generate` wrapper to hide it. When every
example needs the same adapter, the adapter belongs in the library.

```js
createInProcessClient({ model: "my-local", generate: (req) => ({ content: "…" }) })
```

`generate` returns **one assistant message** — `{content}` to finish, `{toolCalls}` to call tools —
plus optional `usage`. The library derives `finish_reason`, builds the `choices` envelope, and
encodes tool arguments unless they are already a string. Tool calls are **flat**
(`{id, name, arguments}`): the nested `function:{}` wrapper is a wire detail, not something a model
author should have to type.

It is an ordinary client. MCP servers, agent skills, sub-agents, hooks, metrics, conversation memory
and the completion gate all behave exactly as against a hosted model, because this is a second
**constructor** built on the shipped transport — not a second seam. That transport is unchanged and
remains the answer for proxy, mTLS, credential injection and record-replay.

Shaped against the ecosystem rather than invented: Vercel's `LanguageModelV4`
(`doGenerate → content/finishReason/usage`), Microsoft.Extensions.AI's `IChatClient` and Pydantic
AI's `Model` all take messages in and a response out, and **none requires a base URL or key** — that
is an SDK-level pattern, not a framework one. All three also ship a built-in fake model, treating
this case as first-class.

**Failures are final, not retried.** A network client rides out transient failures; an in-process
one has no wire, so there is nothing transient — whatever `generate` throws will throw again, and
retrying only buys backoff before you see your own bug. Measured: the streaming refusal took **3.7s**
to surface before this default and **1ms** after. A genuinely flaky model can opt back in with
`retries` — or, in golang, with `OnError`, because that port documents `Retries: 0 ⇒ 2` and zero
cannot mean zero there without changing shipped semantics for every network client.

**Streaming is refused, not faked.** A `generate` returns a whole answer, and a single-chunk stream
is indistinguishable from a real one by content or delta count, so the streaming path raises with a
message naming the limitation. The exception is clojure, which has no streaming entry point at all,
so there is nothing there to refuse — stated rather than papered over.

`docs/adr/0019` rejected a semantic callback as a **replacement** for the transport seam; layering
one on top is a different claim, and the ADR is amended rather than quietly contradicted. Tracked in
`openspec/changes/add-in-process-client`.

## 0.15.0 — 2026-08-17

### Added — a harness, a loop, and a completion gate that survives delegation (all ports)

An agent framework has two things you must be able to name: **the harness** (everything the agent
*may* do — tools, identity, team, ceilings, policy) and **the loop** (a live execution of it).
toolnexus already shipped both and named neither, so every user invented their own vocabulary.
`harness()` now puts the word in the API without adding a type — a spec built through it and one
written inline are indistinguishable, so there is nothing to migrate. `loop()` opens a live
execution that reports `status`, `turns` and an `Outcome` whose stop reason is **always named**.

The loop deliberately takes **no configuration**: capability belongs to the harness, per-call
choices to the run options, and the loop only reports what happened. That is why `model` sits on
the run options — one conversation may change model between turns.

**The part that is genuinely new is the gate.** An agent could report `done` while its own declared
plan was still unfinished. You could bolt a retry loop on the outside, but a host-side loop
**cannot follow a delegation** — when agent A hands work to B via `task`, B runs to completion
inside the runtime and A's caller never sees it. Putting `completion = {verify, maxAttempts}` on
the harness is what makes the check travel with the agent. A built-in verifier, `allTodosDone`,
reads the shipped `todowrite` builtin and requires every declared item to be checked; it is
structural, never learns what a todo *means*, and passes when no plan was declared.

Six rules, each found by prototyping rather than design, and each tested in all seven ports:

- it judges **accumulated** work, or an agent escapes by not re-declaring its plan on the retry
- a non-`done` run is never re-judged, so the gate cannot turn a `pending` into an `incomplete`
- `maxAttempts` is **required** — an unbounded verify loop is a DoS on your own bill
- a failed gate stops **loudly**: `incomplete` plus a structured `limit: "completion"`
- when another limit fires mid-verification, the caller learns **both** reasons
- it reaches delegated children, because it is compiled in at the registry boundary

**Guardrails** ship alongside: policy-only checks on tool calls composed into one `beforeTool` with
**first-deny-wins**, so a later guardrail can never widen an earlier denial.

**Absent ⇒ byte-identical.** No guardrails and no completion is the existing path, unchanged. No new
status strings were minted — `SPEC.md` pins `TaskStatus` across ports, so the gate reuses
`incomplete` and distinguishes itself via `limit`.

### Fixed — `todowrite` did not return its `{todos}` metadata (clojure)

Six ports attach the todo list as result metadata; clojure returned only the rendered text. Nothing
had ever read the plan back, so nothing caught it — until the completion gate, which uses exactly
that metadata to see which items are still open. In clojure the gate therefore passed an unfinished
plan silently. Fixed, with the parity note recorded in the builtin itself.

Tracked in `openspec/changes/add-harness-and-loop`.

### Fixed — `Retry-After` meant seven different things in seven ports (all ports)

`SPEC.md` says the LLM request retries "honoring `Retry-After`". Every port implemented that
sentence, and no two of them agreed on what a header value means. Handed the same `429`, the ports
waited for different lengths of time — and three of them could be made to misbehave outright:

- **Fractional values split three ways.** `Retry-After: 5.9` waited 5.9s in python and js, **5s in
  elixir** (`Integer.parse` stops at the first non-digit, so it silently truncated), and fell back
  to backoff in golang, java, csharp and clojure.
- **A negative value produced an immediate retry** in js and csharp — a client hot-looping against
  a server that had just asked it to slow down — and a **negative sleep** in python.
- **An absurd value crashed the run** in java (`NumberFormatException` thrown from inside the retry
  path) and in clojure (`parse-long` answers nil past a long, and `(* 1000 nil)` throws), and
  parked python, js and elixir on a sleep measured in millennia.
- **`Retry-After: 0`** — the server saying "retry now" — was discarded as "no opinion" by python,
  js and elixir, which then applied backoff anyway.

All seven ports now implement one rule: honour `Retry-After` **only** in its `delay-seconds` form,
a run of ASCII digits (RFC 9110 §10.2.3) in `0 … 2147483647`, waited as exactly that many whole
seconds, including `0`. Everything else — fractional, signed, the HTTP-date form, out of range,
empty, unparseable — falls back to exponential backoff, and can no longer raise, wait a negative
duration, or retry without delay. The HTTP-date form stays deliberately unsupported, uniformly:
it buys one avoided backoff in exchange for portable date parsing in seven languages, and a server
that sends it now gets our backoff rather than a wrong answer in some ports and a crash in others.

**What you may notice:** if a provider sends a fractional `Retry-After`, python and js now back off
instead of waiting the fraction, and elixir no longer truncates it. Every port gains a regression
test pinning the same table of inputs — there was previously **no test for this in any port**, which
is why the drift survived seven ports and a conformance suite. Tracked in
`openspec/changes/fix-retry-after-parity`.

**Not done:** an honoured delay is interruptible by the whole-run deadline in five ports (golang
`sleep(ctx, …)`, python `_sleep(…, deadline)`, js `delay(ms, signal)`, java and csharp
`sleep(ms, deadline)`) but **not in elixir (`Process.sleep`) or clojure (`ktime/sleep!`)**, which
sleep the full delay and only notice the deadline on the next attempt. With the range now capped at
~68 years that is a long-tail annoyance rather than a hang, but it is a real remaining divergence.
It is a bounding question rather than a parsing one, so it is deliberately left out of this change
and not yet tracked by its own proposal.

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
