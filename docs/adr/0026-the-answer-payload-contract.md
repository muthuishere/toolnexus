# ADR 0026 — The `Answer` payload contract: the one map a caller hand-builds is the one map with an unwritten required key

- **Status:** **Proposed — measured 2026-09-21.** Every claim below is a captured run or a
  `file:line`, not a prior. Two of my own starting assumptions did not survive the spike and are
  recorded in *Corrections* rather than quietly dropped.
- **Date:** 2026-09-21
- **Driver:** issue #89 — "Resume silently discards `Answer.Data` unless the key is
  `output`/`results` — host sees status done." Measured on 0.18.1 by a consumer building a
  human-in-the-loop durable host.
- **Evidence:** `spikes/issues/89/` — a mock-LLM spike (no network, no API key, no cost) in **two
  ports**, Go (`main.go` + `mock.go`) and JS (`js-spike.mjs`), with the captured output pasted into
  `spikes/issues/89/README.md`. Re-runnable: `go run .` and `node js-spike.mjs`. Plus a
  seven-port source audit, cited inline.
- **Related:** SPEC §10 (suspension), SPEC.md:1966–2051 (the relay + durable-resume addenda),
  ADR-0010 (relay mode), `openspec/changes/add-tool-relay-mode/tasks.md`,
  `openspec/changes/add-canonical-transcript/tasks.md:32`.

## Context

§10 is the repo's one primitive for "a tool cannot finish in one shot." It has two exits, and the
whole design rests on them being the *same* mechanism seen from two postures:

- **inline** — a `waitFor` is configured; the loop resolves the suspension and, per the §10 loop
  rule, "**re-execute[s] the same tool with the same args once, passing the `answer` in
  `Context.answer`**."
- **durable** — no `waitFor`; the run halts with `status:"pending"` carrying the `Request`. The host
  persists it, asks a human out of band, and comes back later through the answer-carrying entry
  point (`RunWithAnswer`).

`Request` and `Answer` are documented as "plain serializable data," and `Answer.data` as the
"kind-specific payload (e.g. the value entered)." For `kind:"input"`, §10 says "the resolution
**is** the payload."

Everywhere else in toolnexus, a caller constructs a *typed* thing: a `Tool`, a `Request` via
`Pending`, a `RelayAnswer` via its constructor. The durable resume is the **one place in the whole
surface where a host hand-builds a free-form map and hands it to the engine**. It is also the one
place with a required key that is written down nowhere — not in `SPEC.md`, not in the API docs, not
in the example the docs site ships.

## The measurements

All five scenarios use one tool (`ask_human`, `kind:"input"`), one scripted model that calls it once
and then quotes back verbatim whatever `tool_result` it was shown. The model's final text is
therefore direct evidence of what the tool was *believed* to have returned.

### 1. The issue is correct — and understates it

`spikes/issues/89/README.md`, scenario A, Go:

```
Answer.Data sent   : {"value":"staging"}
err                : <nil>
RunResult.Status   : "done"
tool Execute ran   : 1 time(s)
tool saw ctx.Answer: <never re-executed>
model's final text : model saw tool_result: no result supplied on resume for call_1
```

The human's reply is discarded; the model is fed a **fabricated tool error**; the host is handed
`status:"done"` with a nil error. The only surviving trace is a string inside the transcript that
no API surfaces. `golang/relay.go:251-273` reads exactly two shapes —
`Data["results"].([]any)`, else `Data["output"].(string)` — and `golang/relay.go:333-347`
fabricates `"no result supplied on resume for " + name` for everything else. Both type assertions
are failure-silent (`, _`): a non-string `output` becomes `""`, not an error.

The issue calls this "not *completely* silent" because the model sees `isError: true`. That is
generous. The model is not the host's error channel; a model shown a fabricated tool error will
often apologise, guess, or re-ask, and the run still reports `done`. A silent failure that
launders itself through the model's own text is worse than one that throws, because the host's
logs look healthy and the transcript looks plausible.

### 2. Even the *control* violates §10 — the deeper divergence

Scenario B, the key that "works":

```
Answer.Data sent   : {"output":"staging"}
RunResult.Status   : "done"
tool Execute ran   : 1 time(s)
tool saw ctx.Answer: <never re-executed>
model's final text : model saw tool_result: staging
```

The value reaches the **model** but never the **tool**. `RunWithAnswer` does not re-execute
anything; `repairHaltedTurn` (`golang/relay.go:317-364`) splices `Data["output"]` directly into the
transcript as that call's result. That is *relay* semantics (ADR-0010: the host executed the tool,
so its output is the result) applied unconditionally to **every** `Request.Kind`, including
`kind:"input"`, `"approval"` and `"authorization"`, where the tool is supposed to run.

Contrast scenario D, the inline path, same tool, same data:

```
tool Execute ran   : 2 time(s)
tool saw ctx.Answer: {"value":"staging"}
model's final text : model saw tool_result: human replied: {"value":"staging"}
```

So the two §10 exits deliver the answer to **different recipients**: inline → the tool, durable →
the transcript. A host that develops against `waitFor` and then goes durable finds that neither its
tool's post-resume branch nor its chosen data key does anything. The key name is a symptom; the
recipient mismatch is the disease. (SPEC.md:2048-2051 already concedes a related defect — "the
general case of two concurrent **non-relay** suspensions on the durable path still leaves an
unbalanced turn … a known open defect" — but frames it as a transcript-balance issue, not as
"non-relay kinds are resumed with relay semantics.")

### 3. The documented example is broken, and identically broken in two ports

`site/src/content/docs/suspension.mdx:300-341` is the durable-resume tutorial, in all seven
languages. Phase 1 halts under a conversation id; phase 2 builds a **new** client with
`waitFor: req => ({ id: req.id, ok: true, data: { answers: [reply] } })` and calls `ask` on the same
id. The Go tab is `Data: map[string]any{"answers": []string{reply}}` (line 334), Python line 322,
Java 345, C# 358, Elixir 370, Clojure 383 (keyword-keyed). Every tab asserts `status == "done"`.

Scenario E reproduces it verbatim. **Go:**

```
phase 1 status     : "pending" (pending=true)
RunResult.Status   : "done"
tool Execute ran   : 1 time(s)
tool saw ctx.Answer: <never re-executed>
model's final text : model saw tool_result: which environment?
```

**JS:** byte-for-byte the same three lines. The assertion the docs make (`done.status === "done"`)
holds — and that is exactly the failure. `waitFor` is **never called**. The tool is never
re-executed. The model answers off the halt's own placeholder (`which environment?`, the request
prompt written as an error result). The human's reply never enters the conversation at all.

So the issue's point (3) is right but for a second reason: the docs' `{"answers": […]}` is
discarded on `RunWithAnswer` (scenario C, fabricated error), **and** the flow the docs actually
show does not reach `RunWithAnswer` or `waitFor` either. Replaying a stored transcript that already
contains the halt placeholder is not a resume; it is a continuation of a conversation in which the
tool already "failed."

### 4. Six of seven ports have no durable resume at all

This is the finding that outranks the issue. A source audit of every port:

| port | `runWithAnswer` / `askWithAnswer` | relay tools | required key |
|---|---|---|---|
| **golang** | `golang/relay.go:380`, `:401` | yes | `"results"` \| `"output"` |
| js | **absent** (`js/src/client.ts` exposes `run`/`ask`/`stream` only) | **absent** (`relayTool` not exported) | n/a |
| python | **absent** | **absent** | n/a |
| java | **absent** (`java/…/LlmClient.java:890-1243`) | **absent** | n/a |
| csharp | **absent** | **absent** | n/a |
| elixir | **absent** (`elixir/lib/toolnexus/client.ex:386`, `:583` only) | **absent** | n/a |
| clojure | **absent** (`clojure/src/toolnexus/client.cljc:886`, `:1024` only) | **absent** | n/a |

The only `run_with_answer` outside Go is a *private* agent-layer helper
(`elixir/lib/toolnexus/agents.ex:188`) that replays the turn with a one-shot `waitFor` — a
different mechanism, agent-runs only, and it does the §10-correct thing (re-executes the tool with
`ctx.answer`). JS and Python have the same agent-layer path (`js/src/agents/runtime.ts:558-578`,
`python/…/agents/runtime.py:600-630`).

The inline `waitFor` path, by contrast, is **at parity and correct in all seven**: the whole
`Answer` reaches `ctx.answer`, unfiltered, so `value`, `answers`, `text` all work
(`js/src/client.ts:841`, `python/…/client.py:929`, `java/…/LlmClient.java:465`,
`csharp/…/LlmClient.cs:407`, `elixir/…/client.ex:1374`, `clojure/…/client.cljc:817`). The failure
strings `declined/expired: <prompt>` and `unresolved: <prompt>` match byte-for-byte across all of
them.

This is tracked (`openspec/changes/add-tool-relay-mode/tasks.md:82-83` has java/csharp unchecked)
and SPEC.md:1966 and :2022 both carry a **"`golang` only — a preview, NOT part of the §0
conformance contract"** banner. But two lines under that banner, SPEC.md:2020-2021 says **"Every
port provides:"** and lists the two signatures. The contract contradicts itself on the same page.
The banner's port list ("`js`, `python`, `java`, `csharp` and `elixir`") also predates the Clojure
port and omits it.

### 5. A wire hazard the audit turned up on the way

`Answer.data` is string-keyed in Go/JS/Python/Java/C#, **atom**-keyed in Elixir
(`elixir/lib/toolnexus/types.ex:48`; `as_answer/1` at `client.ex:1411` reads `Map.get(m, :id)` and
will `FunctionClauseError` on a string-keyed map) and **keyword**-keyed in Clojure
(`clojure/src/toolnexus/client.cljc:64-72`; `(:ok answer)` at `:813` reads `nil` from a
string-keyed map and silently treats the answer as **declined**). §10 says "`Request`/`Answer` keys
are **fixed across all ports** (they serialize over the wire and cross agent boundaries)." A host
that round-trips an `Answer` through JSON — which is the entire point of the durable path — gets a
hard crash in Elixir and a silent decline in Clojure. Nothing in either port re-atomises. This is
a separate defect from #89 and is noted here only so it is not lost; it needs its own issue.

## Decision

**Ship three of the four candidates, in this order, and reject the fourth as a standalone.**

### 1. Error on `Ok == true` with no recognised payload — YES, and widen it

Not merely "neither `results` nor `output`." The rule should be: on the durable resume, if
`answer.ok == true` and the engine cannot determine a result for **every outstanding tool call**,
that is an error returned to the **host**, not a fabricated `isError` result handed to the model.
`RunWithAnswer` already refuses a mismatched id (`golang/relay.go:381-383`) — "a stale or
misrouted answer cannot corrupt a conversation" — and this is the same class of caller mistake
with a strictly worse outcome, treated with strictly less care. The asymmetry is the bug.

The fabricated `"no result supplied on resume for X"` result should survive **only** for the case
it was written for and that SPEC.md:2042-2043 justifies: a multi-call relay turn where the host
deliberately answered some calls and not others, and the transcript must stay balanced. Even there
it should be reachable only when the host has supplied *some* recognised payload — i.e. it is a
partial-answer filler, never the response to a wholly unrecognised map.

*Cost:* a behaviour change in one port, on a `golang`-only preview surface explicitly excluded
from the §0 contract, converting a silent wrong answer into a loud error. Hosts currently relying
on it are relying on their humans' answers being dropped. Ship it in a minor, name it in
`CHANGELOG.md` under the existing "say what is NOT done" rule.

### 2. Typed constructors per port — YES, as the thing hosts are pointed at

`AnswerOutput(id, output)` alongside the existing `RelayAnswer(id, results)`, and the port-idiomatic
equivalents. This is the fix that removes the failure mode rather than reporting it: the map stops
being hand-built, so there is no key to get wrong. It is also the only candidate that composes with
a future `Answer` shape change without breaking every host.

Constructors and the error are not alternatives — the error catches the hosts that will keep
hand-building maps (every host resuming from a JSON column, which is most of them), and the
constructor is what the docs and the error message both point at.

*Cost:* one small additive function per port. Additive, no compatibility risk.

### 3. Document the key — YES, unconditionally, and fix the broken example

Issue #89's point (3) stands on its own: `SPEC.md`'s durable-resume subsection (2019-2046) never
names `Answer.Data["output"]` or `["results"]` at all — the required key is absent from the
*contract*, not merely from the tutorial. Three edits, all required:

- name both keys and their precedence in SPEC §10's durable-resume subsection;
- resolve the "**Every port provides**" / "**`golang` only**" contradiction at SPEC.md:2020 and
  add Clojure to the not-implemented list at :1969;
- replace `site/src/content/docs/suspension.mdx:300-341` — all seven tabs — because, per
  measurement 3, the flow it shows is broken independently of the key. The corrected example must
  show the `RunWithAnswer` entry point for Go and must **not** show a durable phase-2 for the six
  ports that do not have one; for those it should show the inline `waitFor` posture, which is
  correct and at parity.

*Cost:* documentation only. The expensive part is admitting the seven-tab example was never run.

### 4. Kind-aware defaulting ("for `kind:"input"`, a single-valued `Data` IS the payload") — REJECTED as a standalone, deferred as a consequence

This reads as the most generous option and is the most dangerous. It guesses. `{"value":
"staging"}` and `{"token": "…"}` and `{"approved": false}` are all single-valued maps with entirely
different meanings, and a rule that coerces whichever one it is handed into a tool-result string
turns a loud caller error back into a quiet wrong answer — the exact class this ADR exists to
close. It also cements the measurement-2 defect by making transcript-splicing feel *more* correct
for `kind:"input"`.

The real fix its intuition is groping for is the one measurement 2 names: **for a non-relay kind,
the durable resume should re-execute the tool with `ctx.answer`, exactly as the inline path does**,
at which point no key is required because the tool reads its own payload. That is a larger change
that must be specified first (it is also what would let the six missing ports implement the durable
path correctly the first time, rather than porting Go's splice). **Deferred to its own OpenSpec
change**, and it is the higher-value of the two.

### Summary

| candidate | verdict |
|---|---|
| error on unrecognised `Ok==true` payload | **accept, widened** — error to the host, not a fabricated result to the model |
| typed constructors (`AnswerOutput`) | **accept** — additive, removes the hand-built map |
| document the key + fix the example + fix the SPEC contradiction | **accept, required regardless** |
| kind-aware defaulting | **reject** — it guesses; its valid intuition becomes the kind-aware *re-execution* change |

## Sizing

Only Go has the surface that carries the defect, so the sizing splits cleanly between "fix the bug"
and "stop the contract from being a rumour."

| work | golang | js / python | java / csharp | elixir / clojure |
|---|---|---|---|---|
| error on unrecognised payload | **S** | — (no surface) | — | — |
| `AnswerOutput` constructor | **S** | **S** each (ship with the port's durable path) | **S** each | **S** each |
| SPEC + docs + the seven-tab example | **M**, shared, once | — | — | — |
| *deferred:* kind-aware re-execution on resume | **M** | **L** each (path does not exist) | **L** each | **L** each |
| *separate issue:* atom/keyword `Answer` key hazard | — | — | — | **M** each |

The whole of the accepted work is **S+S+M in one port plus a doc pass**. The deferred re-execution
change is where the L's live, and it should not be started until its spec delta exists.

## Corrections

**1. I expected the docs' `{"answers": […]}` example to fail by hitting the fabricated-error
path.** It does not — it never reaches `RunWithAnswer` at all. The documented flow replays a
stored transcript through `ask(id)`, so `waitFor` is never invoked and the model answers off the
halt placeholder. The example is broken in a *different and worse* way than the issue reports:
scenario C (the key being ignored) and scenario E (the flow never resuming) are two separate
defects that happen to share a code sample. Measured in two ports.

**2. I expected `Data["output"]` to be the "correct" usage.** It is not correct, it is merely
*non-empty*: scenario B delivers the string to the model while the tool is never re-executed and
`ctx.Answer` is never seen, which contradicts §10's loop rule for every non-relay kind. Had the
spike only run A and B, the conclusion would have been "document the key" — which is issue #89's
own point (3) and would have shipped a documented bug.

**3. The issue is framed as a Go bug; it is mostly a parity hole.** Before the audit I assumed the
required-key contract differed across ports. It does not differ — it does not *exist* in six of
seven, and the contract page that describes it contradicts itself about which ports have it
(SPEC.md:2020 vs :1966). A divergence would have been the bigger finding; an absence documented as
a universal is bigger still.
