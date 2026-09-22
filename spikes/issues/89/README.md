# Spike — issue #89: which keys of `Answer.Data` actually reach the tool?

Mock LLM (an injected `http.RoundTripper` / `fetch`). **No network, no API key, no cost.**

```
go run .          # golang port
node js-spike.mjs # js port
```

One tool (`ask_human`, SPEC §10 `kind:"input"`), one scripted model that calls it
once and then quotes back verbatim whatever `tool_result` it was shown — so the
final text *is* the evidence of what the tool was believed to have returned.

## What is being asked

| # | path | `Answer.Data` | why |
|---|------|---------------|-----|
| A | durable `RunWithAnswer` | `{"value": "staging"}` | the issue's exact report |
| B | durable `RunWithAnswer` | `{"output": "staging"}` | control — the one key the code reads |
| C | durable `RunWithAnswer` | `{"answers": ["staging"]}` | the key the **docs** show |
| E | `ask(id)` + `waitFor` | `{"answers": ["staging"]}` | the **documented two-phase flow**, verbatim |
| D | inline `waitFor` | `{"value": "staging"}` | the other §10 path, as a contrast |

## Captured output — golang

```
issue #89 — does Answer.Data reach the tool? (mock LLM, no network)

== A. durable RunWithAnswer — Data{"value": "staging"}   (issue #89) ==
  Answer.Data sent   : {"value":"staging"}
  err                : <nil>
  RunResult.Status   : "done"
  tool Execute ran   : 1 time(s)
  tool saw ctx.Answer: <never re-executed>
  model's final text : model saw tool_result: no result supplied on resume for call_1
  VERDICT            : ANSWER LOST — host told status "done", the model got a FABRICATED tool error

== B. durable RunWithAnswer — Data{"output": "staging"}  (control) ==
  Answer.Data sent   : {"output":"staging"}
  err                : <nil>
  RunResult.Status   : "done"
  tool Execute ran   : 1 time(s)
  tool saw ctx.Answer: <never re-executed>
  model's final text : model saw tool_result: staging
  VERDICT            : value reached the MODEL, but the tool was never re-executed (§10 rule 1 not applied)

== C. durable RunWithAnswer — Data{"answers": [...]}     (the DOCS' example) ==
  Answer.Data sent   : {"answers":["staging"]}
  err                : <nil>
  RunResult.Status   : "done"
  tool Execute ran   : 1 time(s)
  tool saw ctx.Answer: <never re-executed>
  model's final text : model saw tool_result: no result supplied on resume for call_1
  VERDICT            : ANSWER LOST — host told status "done", the model got a FABRICATED tool error

== E. the DOCS' two-phase flow — ask(id) + waitFor Data{"answers": [...]} ==
  phase 1 status     : "pending" (pending=true)
  Answer.Data sent   : {"answers":["staging"]}
  err                : <nil>
  RunResult.Status   : "done"
  tool Execute ran   : 1 time(s)
  tool saw ctx.Answer: <never re-executed>
  model's final text : model saw tool_result: which environment?
  VERDICT            : value reached the MODEL, but the tool was never re-executed (§10 rule 1 not applied)

== D. inline waitFor        — Data{"value": "staging"}   (the other §10 path) ==
  Answer.Data sent   : {"value":"staging"}
  err                : <nil>
  RunResult.Status   : "done"
  tool Execute ran   : 2 time(s)
  tool saw ctx.Answer: {"value":"staging"}
  model's final text : model saw tool_result: human replied: {"value":"staging"}
  VERDICT            : answer delivered to the tool itself (ctx.Answer)
```

## Captured output — js

```
issue #89 — JS port (mock LLM, no network)

== 0. Go's durable-resume API in JS ==
  Client methods absent : runWithAnswer, askWithAnswer
  relayTool exported    : false
  VERDICT               : ABSENT — a durable JS host has NO supported way to hand results back

== inline waitFor — data {"value":"staging"} ==
  answer.data sent   : {"value":"staging"}
  RunResult.status   : "done"
  tool execute ran   : 2 time(s)
  tool saw ctx.answer: {"value":"staging"}
  model's final text : model saw tool_result: {"output":"human replied: {\"value\":\"staging\"}"}
  VERDICT            : answer delivered to the tool itself (ctx.answer)

== inline waitFor — data {"output":"staging"} ==
  answer.data sent   : {"output":"staging"}
  RunResult.status   : "done"
  tool execute ran   : 2 time(s)
  tool saw ctx.answer: {"output":"staging"}
  model's final text : model saw tool_result: {"output":"human replied: {\"output\":\"staging\"}"}
  VERDICT            : answer delivered to the tool itself (ctx.answer)

== inline waitFor — data {"answers":["staging"]} ==
  answer.data sent   : {"answers":["staging"]}
  RunResult.status   : "done"
  tool execute ran   : 2 time(s)
  tool saw ctx.answer: {"answers":["staging"]}
  model's final text : model saw tool_result: {"output":"human replied: {\"answers\":[\"staging\"]}"}
  VERDICT            : answer delivered to the tool itself (ctx.answer)

== E. the DOCS' two-phase flow — ask(id) + waitFor data{"answers":[...]} ==
  phase 1 status     : "pending" (pending=true)

== E (continued) — the docs claim done.status === "done" ==
  answer.data sent   : {"answers":["staging"]}
  RunResult.status   : "done"
  tool execute ran   : 1 time(s)
  tool saw ctx.answer: <never re-executed>
  model's final text : model saw tool_result: which environment?
  VERDICT            : value never reached the tool (status done)
```

## What this proves

1. **The issue is correct, and understates it.** (A) and (C): `Data` with any key
   but `output`/`results` is discarded, the model is fed the fabricated error
   `no result supplied on resume for call_1`, and the host is handed
   `status: "done"` with `err == nil`. Nothing anywhere reports the loss.
2. **Even the control (B) does not satisfy §10.** `RunWithAnswer` never
   re-executes the tool — `tool Execute ran: 1 time(s)`, `ctx.Answer` never seen.
   It splices `Data["output"]` straight into the transcript as the tool's result.
   That is relay semantics (ADR-0010) applied to *every* `Request.Kind`, while
   §10's loop rule says a resolved suspension MUST "re-execute the same tool with
   the same args once, passing the `answer` in `Context.answer`". Compare (D),
   the inline path, where the tool runs twice and sees `{"value":"staging"}`
   verbatim. So the durable and inline paths deliver the answer to *different
   recipients* — a second, deeper divergence than the key name.
3. **The documented two-phase example is broken, in both ports.** (E) reproduces
   `site/src/content/docs/suspension.mdx:300-341` exactly. Phase 1 halts
   (`status "pending"`); phase 2 replays the stored transcript under the same
   conversation id with a `waitFor` returning `{"answers": [reply]}`. The docs
   assert `done.status === "done"` — and it is "done", which is precisely the
   problem: the tool is **never re-executed** (`1 time(s)`, `ctx.Answer` never
   seen), `waitFor` is **never called**, and the model answers off the halt's own
   placeholder (`which environment?`). The human's reply is dropped in full. Go
   and JS agree, byte for byte, on being wrong.
4. **`RunWithAnswer` is Go-only.** Scenario 0 in the JS spike: `runWithAnswer`
   and `askWithAnswer` are absent from `Client`, `relayTool` is not exported.
   Audits of `python/`, `java/`, `csharp/` find the same absence
   (`openspec/changes/add-tool-relay-mode/tasks.md:82-83` still has those boxes
   unchecked). So the required-key contract is not merely undocumented — it does
   not exist in six of seven ports, and the durable §10 path has no
   implementation there at all.

The inline `waitFor` path (D) is correct and key-agnostic in every port audited:
the whole `Answer` reaches `ctx.answer`, so `value`, `answers`, `text` all work.
Only the durable path has a required key, and only Go has a durable path.

See `docs/adr/0026-the-answer-payload-contract.md`.
