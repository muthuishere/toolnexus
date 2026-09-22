## Why

Eight issues (#86–#93) arrived inside two weeks from two consumers building on `golang/` at
0.18.1 — a workflow platform and a human-gated durable host. Each was filed as "this one port is
missing a thing". Six spikes (`spikes/issues/86`, `87`, `88-90`, `89`, `91-92`, `93`), all
hermetic mock-LLM runs with no network and no key, measured every claim in every port. The
measurements did two things: they corrected five of the reports, and they turned up **eleven
parity breaks nobody had reported**, several worse than the issue that led to them.

What was reported:

| issue | reported | measured |
|---|---|---|
| #86 | `ask(prompt)` with no toolkit crashes in five ports | **six** ports; one bug — the §0.10 system message dereferences the toolkit before anything touches tools. The claimed JS fix does not exist on any branch |
| #87 | Go's `Agent.Loop` silently drops `Spec.Guardrails` | correct, and understated — the **soul** goes too. A denied `bash` **executed** on the Loop path |
| #88/#90 | `TaskResult` has no tree token total, no structured stop reason, and `Resume` returns nothing | correct in all seven ports; `TotalTokens` under-reported 600 as 200 with one child |
| #89 | a durable `Answer` is discarded unless the key is `output`/`results`, host sees `done` | correct, and the "working" key never reaches the tool either |
| #91 | `jev-latest` is a dead default | **does not reproduce** — it serves. The defect is that `baseUrl`/`model`/`apiKeyEnv` are only jointly valid and nothing says so |
| #92 | `RunResult.status` never returns the documented `"timeout"` | correct, for a reason the report could not see: **two closed status vocabularies share the field name `status`** |
| #93 | 6 of 87 skills silently skipped over an unquoted `": "` in a description | exact, reproduced as 6 of 87 |

What no issue reported, and what makes this a parity change rather than seven bug fixes:

1. **JS lets `Spec.Soul` override a caller's `systemPrompt`** where Python and C# let the caller
   win — a behavioural fork on a path all six "correct" ports claim to honour (ADR 0024).
2. **`TaskResult.TotalTokens` already means two different things depending on status** — the
   rolled-up tree total on error/closed/timeout/settled, this run's figure on
   done/pending/incomplete (ADR 0025).
3. **Java replays the literal string `"continue"`** on every resume, losing the actual prompt and
   any drained inbox text. A straight §0 break (ADR 0025).
4. **C# and Clojure hardcode the maxTurns wording** and never read `r.Limit`, so a
   completion-gate stop is reported to the host as a turn-cap stop — the wrong reason, not a
   missing one (ADR 0025).
5. **Elixir already ships `limit` on `TaskResult`; six ports do not.** Drift regardless of which
   way it is decided (ADR 0025).
6. **Six of seven ports have no durable resume at all**, while `SPEC.md:2020` says "Every port
   provides:" two lines under a banner saying "`golang` only" (ADR 0026).
7. **Elixir's `Answer` is atom-keyed and crashes on a JSON round-trip; Clojure's is keyword-keyed
   and reads a string-keyed answer as *declined*** — on the one path whose entire purpose is to
   survive a JSON column (ADR 0026).
8. **Clojure has no run-level deadline and retries timeouts**, where every other port refuses to
   (ADR 0027).
9. **No port truncates or redacts an LLM error body.** A fake `user_2FAKE…` account id surfaced
   verbatim in Go and JS. The classifier path has had a cap-and-blank policy for seven ports and
   the §8 path has none of it — and the cap would not have caught this body anyway, at 96 bytes
   (ADR 0027).
10. **Clojure's `frontmatter.cljc` is not YAML.** It loads 33 of 87 where the others load 69, and
    64% of a second catalog the others load perfectly is invisible. The byte-identity claim has
    been false since the Clojure port landed (ADR 0028).
11. **`docx`/`pdf`/`pptx` resolve to *different files* per port.** §3 pins first-wins and says
    nothing about discovery order, so the winner falls out of each ecosystem's directory walk —
    the names agree, the loaded content does not (ADR 0028).

Every decision below is owner-approved in this change's `DECISIONS.md` (2026-09-21) and evidenced
in `docs/adr/0023`–`0028`.

## What Changes

### D1 — a toolkit-less completion is a first-class call (#86)

The absent toolkit becomes a legal, spec'd call in all seven ports: the system message is the
system prompt alone, and the request carries **no** `tools` and **no** `tool_choice` key — not an
empty array. One null-guard at the system-message site per port, plus the idiomatic call shape
(Python default argument, JS optional `ctx`, C# `Toolkit?`, Java three overloads, Elixir default
args; Go and Clojure already work and gain tests).

### D2 — `Loop` honours the Spec it is handed (#87)

Go's `clientFor` applies `Spec.Soul` and the compiled guardrail hooks. `Spec.Model` and
`Spec.Budget.MaxTurns` become Loop defaults everywhere. A caller-supplied system prompt **wins**
over the soul in all seven ports — JS is brought to that rule. The genuinely unhonourable
residue (`Tools`, `Team`, `WaitFor`, `OnMetric`) is named by an additive
`loopUnsupported(spec)`, not by a construction-time error.

### D3 — the runtime path becomes as legible as the loop (#88, #90)

`TaskResult.TotalTokens` means the cumulative subtree total on **every** status, with a new
`OwnTokens` for the per-agent figure. `Limit` joins `TaskResult` in the six ports lacking it,
populated for budget stops too. `Resume` returns the resumed result. Java stops replaying
`"continue"`; C# and Clojure stop reporting the wrong stop reason.

### D4 — the `Answer` payload stops being a guess (#89)

On `ok == true` with no determinable result for an outstanding call, the engine **errors to the
host** instead of handing the model a fabricated tool error and reporting `done`.
`AnswerOutput(id, output)` constructors land in all seven ports so the map stops being
hand-built. Elixir and Clojure accept string keys. A non-string `output` is an error, not `""`.

### D5 — a failure is a return value, with the same care as a success (#91, #92)

A `backend` preset (`typesafe` | `openrouter`) sets `baseUrl`, `model` and `apiKeyEnv` **as a
unit**, and the known cross-base mismatch fails at construction with the correct spelling in the
message. Both status vocabularies get named constants in every port and are documented as two
distinct sets. Go stops returning a zero-value result beside a non-nil error. Clojure gains a
run-level deadline and stops retrying timeouts. Error bodies become a typed value carrying
`status`/`body`/`retryAfter`, with account-identifier keys redacted, the 401/403 blanking and the
200-character cap lifted from the classifier path onto the §8 path.

### D6 — a skill is what the writing tool accepts (#93)

Plus the ordering sweep this batch forced: the §0.10 skills prompt and the `skill` tool's
not-found list are ordered by code point in all seven ports (js was locale-dependent and so
unstable across machines; csharp compared UTF-16 code units); every directory read feeding shipped
output is explicitly sorted; and every capped listing — the `<skill_files>` sample, `glob`, `grep`
— collects and sorts before it truncates, emitting the slash-separated relative path it sorted on.

Frontmatter parsing becomes **YAML first**, with a line-wise `key: rest-of-line` rescue for
`name`/`description` that runs **only** on frontmatter a real YAML parser has already refused, and
refuses any value opening `| > & * [ { !`. Skip records gain a `detail` carrying the native parser
error. `LoadSkills` surfaces its skips. Clojure's hand-rolled frontmatter is replaced with real
YAML across both `.cljc` hosts. §3 gains a specified **discovery order**.

## Breaking

- **`TaskResult.TotalTokens` returns a different number** on done/pending/incomplete: the tree
  total instead of this run's. A host that compensated by adding `UsageTokens(Root)` itself will
  double-count until it stops. This is a behaviour change, not a fix, and is named as one.
- **`TaskResult.Turns` likewise returns a different number** on done/pending/incomplete, in the
  ports that were per-run on those three statuses and cumulative on the other three — the same
  status-dependent meaning as the token field, one field over. It becomes that handle's own
  cumulative round trips on every status. Turns are **not** rolled up into a parent, and a
  parent may legitimately report fewer turns than a child it delegated to.
- **`Resume` gains a return value.** Go callers of `err := rt.Resume(...)` stop compiling; the
  other six are source-compatible.
- **A Go Loop driven with a Spec carrying `Guardrails`/`Soul` changes behaviour** — the guardrail
  now fires. A fixture that relied on it *not* firing will now deny.
- **A durable resume with an unrecognised payload now errors** where it returned `done`. Hosts
  relying on the old behaviour are relying on their humans' answers being dropped.
- **A typed LLM error replaces a message-only error**, which breaks anyone matching on text — the
  current interface precisely because there was no other one.
- **Clojure loads more skills, and different ones.** The hand-rolled subset is gone.
- **`grep`'s output moves from absolute to relative paths** in the ports that emitted absolute
  ones (golang, csharp, python — all three of which sorted on the relative path while emitting the
  absolute one). A host parsing those results as absolute paths must join them to the walk root.
- **Sorting before the cap changes WHICH results appear**, not merely their order, in every capped
  listing: the `<skill_files>` sample, `glob` and `grep`. Previously the filesystem's enumeration
  order decided the contents, so the same query could return different entries on a different
  machine. This closes ADR-0004 K1 and is a behaviour change for anyone who had come to depend on
  what their filesystem happened to return.
- **The skills prompt's order changes** where a port sorted by locale (js) or by UTF-16 code unit
  (csharp). The prompt is pinned byte-identical, and js's previous ordering was not even stable
  across machines.
- **The duplicate-name winner flips in js, golang and elixir.** Pinning the discovery order —
  depth ascending, then code point — makes a top-level `docx/SKILL.md` **or** `xlsx/SKILL.md`
  beat `synced/<uuid>/…`, uniformly, so the python/java/csharp winner becomes the contract.
  Under the ports' current ordering the winner depended on the skill's own first letter relative
  to a sibling directory's name, so `xlsx` resolved to the nested copy where `docx` did not. The skill's *name* is unchanged and no catalog entry appears or disappears — but the
  file that supplies its `content` is a different file in those three ports. A host whose prompt
  depended on the `synced/` copy's body will see different instructions. This is a user-visible
  behaviour change in three ports, not a no-op refactor.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `client-request-shaping` — the absent toolkit becomes a pinned call shape with a pinned wire.
- `agent-runtime` — the Loop honours the Spec it is handed; `TaskResult`'s token fields get one
  meaning each; the limit-stop requirement gains a structured carrier and the two status
  vocabularies become named constants.
- `suspension` — `Resume` returns a result; the `Answer` payload contract becomes explicit; the
  resume idempotency contract is stated at the point of use.
- `resilience-policy` — the run-level deadline becomes a cross-port requirement, and a provider
  failure becomes a typed, redacted value.
- `typed-decisions` — the endpoint pairing becomes the configurable unit, and the known mismatch
  fails at construction.
- `skill-discovery` — YAML-first parsing with a guarded rescue, surfaced skips with a native
  detail, a specified discovery order, a code-point-ordered skills prompt, explicitly sorted
  directory reads, and a sample that is collected and sorted before it is capped.
- `builtin-tools` — every capped listing sorts before it truncates, and emits the relative,
  slash-separated path it sorted on.

## Deferred — named, not dropped

Five items are deliberately **not** in this change and each gets its own OpenSpec change:

1. **Whether an admission refusal is a verb error or a settled result.** `maxChildren`,
   `maxConcurrent` and `maxDepth` name spawn/admission refusals that several ports return from
   the verb and never settle. This change pins their spelling wherever a port reports such a stop
   and deliberately does not move where they surface — that asymmetry lives in the verb's return
   type, so closing it changes a signature.
2. **Transcript replay on resume.** Rewind-to-checkpoint is pinned by name in `SPEC.md:912`, so
   changing it is a §0 decision, not a bugfix. It needs two transcript policies in one runtime,
   makes the pending placeholder's shape a byte-identical obligation, and **does not remove the
   idempotency requirement anyway** — §10 resolves by re-executing the suspended tool with
   `ctx.answer`, so at least one tool is always called twice across a resume. What ships here
   instead is the contract stated loudly, in docs and in every `WaitFor` doc comment.
3. **`<skill_files>` still emits absolute paths in every port.** The sample sorts on the relative
   path and emits the absolute one. There is no ordering defect — every entry shares the skill
   directory prefix, so the two orders coincide — but the emitted shape is machine-specific. It
   needs a **seven-port decision** (all ports emit relative, or the slash-separated-relative rule
   carves the skill sample out explicitly); csharp declined to fix it unilaterally, correctly,
   because js emits absolute there too and one port changing alone would break §3 byte-identity.
4. **The classifier's `canonicalJson` / `canonicalRequest` key ordering.** js orders by UTF-16 code
   unit, golang byte-wise over UTF-8 — identical for every ASCII and BMP key, and canonical-request
   keys are schema names, so nothing diverges observably today. It is nonetheless a real cross-port
   byte-identity path, so it needs a coordinated seven-port ruling rather than a unilateral edit,
   which would *create* the drift it exists to prevent.
5. **Non-relay resumes re-executing the tool with `ctx.answer`.** This is the correct fix that
   kind-aware defaulting is groping for, and it is the higher-value of the two — it is also what
   would let the six ports without a durable path implement it correctly the first time rather
   than porting Go's transcript splice. It must be specified before it is written.
