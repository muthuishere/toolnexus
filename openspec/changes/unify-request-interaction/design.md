# Design — unify human/loop interaction on §10 Request

## The one-line thesis

Everything that needs the human out-of-band is a §10 `Request{kind, prompt, …}` resolved by the
one `waitFor`. `question` is the last builtin that doesn't obey this. Make it obey. No new
machinery — this is subtraction.

## Why this needs no new types

§10 already ships the entire mechanism in every port:

- `pending(request)` producer helper — returns `ToolResult{isError:true, metadata:{pending:Request}}`
  and auto-generates `Request.id` (`js/src/types.ts:50`, mirrored per port).
- `Request{id, kind, prompt, url?, data?, expiresAt?}` — open `kind` vocabulary, byte-identical wire data.
- `waitFor(request) → answer` — the one host slot (`ClientOptions.waitFor` / `WaitFor`).
- Loop rule (§10): on `metadata.pending`, call `waitFor`; on `answer.ok` re-execute the same tool
  once with `ctx.answer` set; with no `waitFor`, halt durably (`RunResult.status:"pending"`).
- `ToolContext.answer` is already plumbed to the builtin `run(args, ctx?)` signature
  (`js/src/builtin.ts:56`) — so the tool can read the resolution on re-execute with zero wiring.

`question` becomes a two-line state machine over primitives that already exist.

## The state machine for `question`

```
run(args, ctx):
  questions = args.questions as array (default [])
  if ctx?.answer is present:                      # re-executed after waitFor resolved (answer.ok==true)
      return ok(JSON.stringify(ctx.answer.data ?? {}))   # the resolution IS the payload — like kind:"input"
  return pending({ kind: "question",
                   prompt: renderPrompt(questions),
                   data: { questions } })          # first call → suspend
```

- **First call** → suspension. `data.questions` carries the full structured questions (the same
  array the host used to read off `metadata.questions`), now under the uniform §10 envelope.
- **`prompt`** is a human-readable rendering of the questions (§10 requires `prompt:string` — "what
  is being asked, in human words"). `renderPrompt` joins each question's text (and its options, if
  any) into one string. It is a fallback for channels that show only text; the machine-readable
  form stays in `data.questions`.
- **Resolved re-execute** → return `ctx.answer.data`. For `kind:"question"`, like `kind:"input"`,
  the answer *is* the payload; the host's `waitFor` returns `Answer{ok:true, data:{…the picks…}}`
  and the tool hands that straight back to the model. The exact shape of the picks is the host's
  (it owns `waitFor`); the tool does not interpret it, only forwards it — keeping the kernel
  agnostic, consistent with `authorization` where the token lives in `answer.data`.
- **No `waitFor`** → the loop rule halts durably; the `question` Request surfaces on
  `RunResult.status:"pending"`. A durable host delivers it (channel, file, another agent) and
  resumes by calling `run` again. Falls out of §10 with no extra code.

## Two origins, one seam (path A and path B)

The unification has two faces, both already resolved by the same `waitFor`:

- **Path A — a tool asks.** `question` (or any native/MCP tool) returns `Pending`. This change
  makes `question` do it.
- **Path B — the loop/guard asks.** A `beforeTool` hook (the pipeline `guard`) returns
  `{result: pending({kind:"approval", …})}` with no tool code running. Because a `beforeTool`
  short-circuit result is returned as an ordinary `ToolResult`, and pending-detection (`pendingOf`)
  sits downstream of that convergence, the loop honors it identically. **Verified working in all
  five ports by code-trace** (JS spike 12/12; Python `client.py`, Go `client.go:452`, Java
  `LlmClient` runToolResolved + streaming, C# `LlmClient.cs:190` + `PendingOf` in all 4 variants).
  No fix needed — this change adds a regression test so it stays true.

The point of naming both: after this change, "the model wants the human" is one code path
(`Pending → waitFor → resume`) regardless of whether a tool, a native function, or a guard raised
it. That is the unification.

## Validation against prior art

Checked against `enterprisewebagent` (a Java Claude-Code-style clone): their ask-user is an
`AskUserRequestedEvent{question, choices}` — an *event* plus a `"PENDING_APPROVAL"` text marker,
with no true suspend/resume. toolnexus §10 is strictly stronger: a real durable suspension that
survives restarts and can be resolved by a different process/agent. We are not copying their
weaker shape; we are removing the one place we hadn't yet applied our stronger one.

## What this deliberately does NOT do

- **No new `kind` registry / enum.** `kind` stays an open string vocabulary (§10). `"question"`
  is a convention, not a closed type — same as `"authorization"` / `"approval"` / `"input"`.
- **No suspension capability spec is created here.** §10 lives in `SPEC.md`; folding the whole
  suspension contract into `openspec/specs/` is a separate, larger change. This one adds only the
  `question`-suspends requirement under `builtin-tools`, where the tool lives.
- **No change to the `question` tool name or input schema.** Discovery/schema parity with
  opencode is preserved; only the return value changes.
- **No interpretation of the answer shape.** The tool forwards `ctx.answer.data` verbatim; the
  host defines what a "pick" looks like via `waitFor`.

## Breaking-change note

Hosts that call the toolkit directly and read `metadata.questions` off an *immediate*
`question` result will no longer get one — `question` now suspends. The migration is the §10 seam
they likely already have for `authorization`/`approval`: provide `waitFor` (in-process) or handle
`RunResult.status:"pending"` (durable). This is called out in `tasks.md` and the `SPEC.md` §4A
edit. Given `question` was documented as "non-interactive in the headless loop," real hosts that
wanted interactivity were already forced onto a bespoke path; this replaces that with the
standard one.

## Locked decisions (2026-07-11)

- **`renderPrompt` format — LOCKED.** Each question's `question` text, joined by `"\n"` in array
  order; when a question has a non-empty `options`, append `" (options: a, b, c)"` (the option
  strings joined by `", "`). No trailing newline. Empty `questions` array → empty string. This is
  part of the human-facing `Request.prompt` (a text fallback for channels that show only text); the
  machine-readable form always stays in `data.questions`. Must be **byte-identical** across all five
  ports — the risk/parity task pins the canonical bytes for a 2-question example.
- **Answered-shape convention — LOCKED.** The tool forwards `ctx.answer.data` untouched (kernel
  stays agnostic — the host owns `waitFor` and defines the pick shape). SPEC §10 additionally
  documents a **non-normative recommended** shape `{ answers: [ ... ] }` (one entry per question, in
  order) as guidance for host authors, without making the tool depend on it.

## Huddle findings (2026-07-11) — verified

A three-lens design huddle (proponent+prior-art / adversary / risk-parity) ran on this change.
Load-bearing claims were re-verified against the actual code before folding.

### Prior art validates the unification (proponent)
- **MCP elicitation is our peer and converged on the same shape.** MCP's `elicitation/create` uses a
  `mode` discriminator (`form` = structured question, `url` = out-of-band auth/payment) under ONE
  primitive — MCP's own spec: *"The same request could direct the user into an OAuth flow, or a
  payment flow. The only difference is the URL and the message."* That is our `kind` thesis verbatim.
  Its resume is ours too: return an input-required result, then re-issue the **same** `tools/call`
  with the response — identical to our "re-execute the same tool with `ctx.answer`."
- **Anthropic's own Claude Agent SDK** routes both tool-approval AND its `AskUserQuestion` clarifying
  tool through the **same** `canUseTool` callback — the vendor that ships AskUserQuestion does not
  give it a separate seam. **OpenAI Agents SDK** (`needsApproval` → serializable interruptions,
  resolved per-item), **LangGraph** (`interrupt()`+checkpointer+`Command(resume=)`), and **Temporal**
  (wait_condition + external signal) all mirror our durable-halt branch. We are not inventing; we are
  applying our own stronger primitive to the one builtin that hadn't adopted it.

### Refinements suggested by prior art (candidate, not yet locked)
- **R1 — three-state resolution.** MCP distinguishes `accept | decline | cancel` (refused vs
  never-seen/timed-out). Our `Answer.ok:boolean` flattens this. Additive fix: optional
  `Answer.reason: "declined"|"cancelled"|"expired"` populated when `ok==false`. Non-breaking (loop
  branches only on `ok`).
- **R2 — optional `Request.schema`** (restricted/flat JSON Schema, like MCP's `requestedSchema`) so a
  generic durable/channel host can auto-render `question`/`input` without bespoke glue.
- **R3 — SPEC security pin (cheap, high value).** MCP mandates secrets NEVER transit the
  data/LLM channel — they go out-of-band via URL mode. Pin the converse: `kind:"question"`/`"input"`
  MUST NOT collect credentials; those use `kind:"authorization"` with `url`. This is the one guarantee
  genuinely at risk if `kind` is treated as a mere label.

### Confirmed suspension-layer gaps this change EXPOSES (verified in code)
These are **pre-existing** behaviors of the §10 layer (they already affect `authorization`/`approval`),
but `question` is a common model-initiated builtin, so this change promotes them from rare-edge to
routine. Each was verified in the JS source; being loop/serve-layer, they replicate across all five
ports (the earlier "verified in all five" covered path-B convergence, NOT these).

- **G1 (blocker for the A2A story) — `serve()` swallows a suspension.** `serve.ts` `fulfil` (JS
  `serve.ts:333-339`) marks the task `state:"completed"` with `result.text` as the artifact,
  unconditionally — it never checks `result.status === "pending"`. A suspended run over A2A returns a
  "completed" task carrying the rendered question *prompt* as the answer; the remote peer never learns
  it must resolve anything. A2A has an `input-required` state for exactly this. Fix: branch on
  `status:"pending"` → emit `input-required` (+ stash `result.pending`) in every port's serve.
- **G2 — pending counted as a tool error.** `runTool` (`client.ts:416`) emits the `tool` metric with
  `isError: result.isError` (true for a suspension) and runs `afterTool` — both BEFORE the caller's
  `pendingOf` check. Every question suspension increments the error counter and can trip an
  error-rate circuit-breaker. Fix: detect `pendingOf(result)` in `runTool` and tag the event
  `pending` distinct from `is_error` (skip the failure-semantics `afterTool` treatment).
- **G3 (narrow) — concurrent suspensions drop all but one.** In the parallel tool loop
  (`client.ts:472,480`) `halted` is last-write-wins. Two suspensions in one turn *with no `waitFor`*
  surface only one on `RunResult.pending`; the other is lost on resume. (WITH `waitFor` each resolves
  inline — no loss.) Fix: `pending: Request[]` or halt deterministically on the first in call order.

## MCP elicitation spike (2026-07-11) — the deeper unification, and why it's a SEPARATE change

Spiked how MCP elicitation actually works and whether it maps onto §10. Ground truth (all cited in
the huddle transcript):

- **Elicitation is a server→client REVERSE-REQUEST raised *during* our `tools/call`.** When
  toolnexus (MCP-outbound, §2) calls a remote server's tool, that server may send
  `elicitation/create` back to us mid-call and block until we answer — structurally the mirror of
  `sampling`/`roots`. Wire: request `{message, requestedSchema}` (+ draft `mode:"form"|"url"`);
  response `{action: accept|decline|cancel, content?}`. `requestedSchema` is a restricted flat-
  primitive JSON-Schema subset. Form ≈ our `kind:"input"`; URL mode ≈ our `kind:"authorization"`
  (secrets stay out-of-band). Security rule maps 1:1 onto our R3 pin.
- **All five pinned SDKs expose a client-side elicitation handler** (JS `setRequestHandler(ElicitRequestSchema)`,
  Py `elicitation_callback`, Go `WithElicitationHandler`, Java `.elicitation()/.urlElicitation()`,
  C# `Handlers.ElicitationHandler`); all have URL mode except Python 1.28.0 (form only, one bump
  away). **Not blocked by SDK gaps in any port.**
- **toolnexus registers NO elicitation handler today** — every port connects with empty
  capabilities (`js/src/mcp.ts:171`, `python/.../mcp_source.py:236`, `golang/mcp.go:298`). A remote
  server that elicits is refused → the tool fails, the human is never asked. The capability is
  invisible to us.
- **The data maps almost perfectly onto §10** (`message→prompt`, `requestedSchema→data.schema`,
  `accept+content→ok=true+data`, `decline/cancel→ok=false`, url→`kind:"authorization"`+`url`) — it
  reuses `Request`/`Answer`/`waitFor` verbatim, which validates the §10 design.

**Why it is NOT a rider on this change:** elicitation is a **transport-level callback fired inside
the SDK before `execute()` has produced any `ToolResult`** — so the §10 "a tool returns
`metadata.pending` → loop retries the same tool" machinery *cannot see it*. It needs a **new seam**:
an `ElicitationBridge` registered on the MCP client, constructed with the host `waitFor` plumbed
down into `loadMcp` (today `loadMcp` takes only config and can't reach `waitFor`), advertising the
capability only when `waitFor` is present, satisfying the reverse-request inline (it does NOT
re-execute the tool — the in-flight `callTool` resumes). Plus contract pins: `ok=false ⇒ decline`
(with optional `answer.data.action` override to reach `cancel`), adapter-side `content` validation
against `requestedSchema`, and URL-mode re-entrancy (one `callTool` may elicit twice). Those are §0
conformance-surface changes in `SPEC.md §2` + `§10` across five ports.

**Conclusion:** the `question` builtin is the **tool-side** producer of `Pending`; MCP elicitation
is the **transport-side** consumer of the *same* `waitFor`. Both belong to the §10 family, wired at
opposite ends of `toolkit.execute`. Ship `question`→pending first (it establishes `input` semantics
and exercises `waitFor` for in-band values), then a dedicated **`add-mcp-elicitation-bridge`**
change. Do not conflate — the interception points differ; folding them would blur §10's clean
"a tool returns Pending" pin. (Recorded as its own project thread.)

## enterprisewebagent (2026-07-11) — weakest tier, nothing to borrow mechanically

Confirmed by code-trace: its `AskUserRequestedEvent{sessionId, question, choices}` is a
fire-and-forget event; the `ask_user` tool returns a `PENDING_USER_INPUT:` text marker as a
*successful* result and the loop `continue`s and re-calls the model immediately — **it never
pauses.** No correlation id, no awaited answer, no durable paused state, single-process/in-memory.
Approval reuses the same event (`choices:["approve","deny"]` + `PENDING_APPROVAL:` marker) and is
*skipped, not gated*. **No MCP elicitation support** (client registers no capability/handler). Its
one good instinct — one event type for both questions and approvals — is our "one Request, many
kinds," but done as a notify-only marker. Validates that our §10 is strictly stronger; nothing to
copy.

## Scope fork (for the owner)

The core change (question→pending) is a clean, verified 5-port edit. G1–G3 are orthogonal
suspension-layer gaps. **Decision needed:** (A) ship the unification tight + file a separate
"harden-suspension-layer" change for G1–G3; or (B) bundle at least G1 (the A2A footgun) into this
change so "question suspends" is correct end-to-end including `serve()`. G2/G3 are quality/narrow and
can go either way. R1–R3 are additive refinements that can land with B or as their own change.

## Locked implementation facts (from risk-parity, verified)

- **No rollout blockers for the builtin edit.** All five ports pass `ctx` into the builtin callback on
  every execute incl. re-execute. Two ports discard it in the `question` handler only — one-token
  rename: `golang/builtin.go:706` (`_`→`ctx`), `csharp/.../BuiltinTools.cs:574` (`_`→`ctx`). Python
  (`builtin.py:476`) and Java (`BuiltinTools.java:538`) already name `ctx`.
- **Never assert `Request.id`** in tests — it is a per-port non-deterministic `pnd-<base36>-<seq>`.
- **`renderPrompt` — canonical algorithm (byte-exact, pin in SPEC):** for each question in array
  order, take its `question` text (missing/non-string → `""`); if `options` is a **non-empty** list,
  append `" (options: " + options.join(", ") + ")"`; join questions with a single `"\n"`; no
  leading/trailing newline. `header` is NOT rendered (survives only in `data.questions`). Empty
  `questions` → `""`. Canonical example: `[{question:"Pick a color?",options:["red","green"]},
  {question:"Confirm?"}]` → `Pick a color? (options: red, green)\nConfirm?`.
- **Break list (must change in this diff):** the §4A row `SPEC.md:371`, and five unit tests asserting
  the old contract — `js/test/unit.test.ts:743`, `python/tests/test_unit.py:578`,
  `golang/builtin_test.go:415`, `java/.../BuiltinToolsTest.java:324`,
  `csharp/.../BuiltinToolsTests.cs:381`. READMEs only *list* `question` (no return-shape claim) — no
  edit required; do NOT touch the unrelated "Answers research questions" custom-tool example lines.
