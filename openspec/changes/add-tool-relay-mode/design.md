## Context

toolnexus is used by routsi as an OpenAI→Anthropic/Gemini **translator**. A translator
must never execute anything on the proxy host, but standard OpenAI function calling
requires the *caller* to execute the model's tool call. Today every `Tool` couples a
schema to a handler, so a proxy has to drop tools entirely and loses function calling.
Issue #37; ADR-0010 (accepted); spikes 0001 (source) and 0002 (executable).

**Current state, measured.** Spike 0002 prototyped relay from the existing public API
only — a `Tool` that returns `Pending` on first call and returns the host's output on
retry-with-answer — and ran the real loop against a mock LLM. 14/14 green. Findings that
shape this design:

- On the **in-process** path (`waitFor` set), relay **already works**, unmodified, on the
  OpenAI loop, the Anthropic-native loop, and the streaming loop. `RelayTool` is
  ergonomics over a mechanism that is already correct.
- On the **durable** path (no `waitFor` — routsi's shape) three relay calls in one turn
  measure `3 tool_calls vs 1 tool_result`, and the one result present is a placeholder
  **error**. Identical numbers on all three loops, which localizes the gap to the
  **shared** suspension path rather than any transcript builder.
- Declaring an uncalled relay tool is inert; a real tool sharing a turn with a relay call
  still executes; `go test -race ./...` stays green with the prototype present.

**Constraint that dominates every choice here:** §10 is hardened and verified
byte-identical across six ports, with dedicated concurrency tests. It is the riskiest
surface in the repo to disturb, so the design is biased hard toward *additive*.

## Goals / Non-Goals

**Goals:**
- Declaration-only tools: the model's call is surfaced as structured data, nothing runs
  host-side, and the caller's result is fed back as a proper `tool_result`.
- Work on the **durable** (stateless, cross-process) host shape, which is the one routsi
  needs and the only one currently broken.
- Preserve parallel tool calls end to end — a caller must see all N calls of a turn.
- Zero observable change when no relay tool is declared, in all six ports.
- Land on both the streaming and non-streaming loops, in all six ports.

**Non-Goals:**
- No second agent-loop mode and no second suspension mechanism (`SPEC.md:1439-1441`).
- No change to `Builtins:false` semantics; builtins remain off for proxies.
- No change to the format adapters — they already emit any `Tool`'s declaration natively.
- No change to `ConversationStore` — spike 0002 proved it already round-trips
  `tool_use`/`tool_result` pairs with ids intact.
- Not fixing the general two-concurrent-**auth**-suspensions durable defect (ADR-0010
  "Observation"). Relay's own path is covered; the general case wants its own change.
- No live-provider verification — this repo's suites are hermetic by design.

## Decisions

### D1 — Relay is a §10 tool, not a loop mode

`RelayTool(name, description, schema)` returns an ordinary `Tool` whose handler suspends
on first call and returns the host's output on retry-with-answer. The loop's
execute-or-not branch is **untouched**.

*Why:* spike 0002 proved the mechanism already works this way on all three loops. The
alternative — a `RelayTools` field plus a return-on-first-`tool_use` mode, as issue #37
proposed — would re-implement the halt path a second time in six languages and create the
second suspension mechanism the spec forbids. Rejected.

### D2 — All of a turn's relay calls ride the one surfaced `Request`, under `data.calls`

Keep §10's first-in-order halt rule exactly as shipped; put the whole turn's relay calls
in `data.calls` (array of `{id, name, input}`, in tool-call order).

*Why:* it preserves a hardened, six-port-verified concurrency rule *and* maps one-to-one
onto OpenAI's `tool_calls` array. The alternative (F2-b: relax the halt so N suspensions
surface as N requests) is conceptually tidier but re-opens exactly the contract that was
hardened on purpose. Rejected.

*Note:* `data.calls` is an array even for a single call. One shape, no special case.

### D3 — Add an answer-carrying resume entry point, rather than change the halt transcript

`RunWithAnswer` / `Ask(..., answer)` (idiomatic per port). The host that received
`status:"pending"` executes the calls and resumes with an `Answer`.

*Why:* the durable path's placeholder-error write is the shipped halted-tool transcript
rule, verified identical in six ports on 2026-07-18. Changing it (F1-b) would produce a
transcript shape no port emits today and put a hardened rule at risk. Adding an entry
point is additive and is independently the missing half of the durable path — nothing
today can resume a persisted suspension at all. Rejected: F1-b.

### D4 — Resume fills **every** outstanding `tool_result` slot of the halted turn

Not just the halted call's. Spike 0002 measured 2 of 3 slots missing, and a provider
requires one `tool_result` per `tool_use`.

*Why:* without this, D2's array is useless — the caller would have N results and one slot.
This is the single most easily-missed requirement in the change, which is why it is called
out as its own decision and its own spec scenario.

### D5 — The collision guard is unconditional

Reject a relay tool whose name collides with a builtin's name **even when builtins are
off**.

*Why:* the guard exists so a *future* change to the builtin set cannot silently convert a
declaration-only tool into a host-executed one. A guard that only fires when builtins are
enabled would not protect the proxy case, which always has them off. Cheap, and it is the
security-relevant line in the whole change.

### D6 — `Answer.data` carries `output` plus an error flag

A relayed tool that failed at the caller must reach the model as an error `tool_result`,
not as an aborted run. `ok == false` stays reserved for "the caller declined/could not
relay", which §10 already maps to an error result.

*Why:* two genuinely different outcomes — "your tool failed" vs "I won't run it" — and
both must let the model recover. Spike 0002 covers both (S2, S9).

### D7 — Go is the reference port; the other five follow its spike

Implement Go first, port spike 0002's fourteen cases into each port's suite, and use S4's
measured numbers as the cross-port oracle.

*Why:* parity is the product. §10 shipped six times; a design verified only in Go is a
parity risk, and the numbers give every port an unambiguous target.

## Risks / Trade-offs

- **Disturbing §10's hardened concurrency rules** → Every decision above is additive: no
  edit to the halt rule (D2), no edit to the transcript rule (D3), no edit to the
  execute-or-not branch (D1). The existing `TestConcurrentSuspensionsSurfaceFirst` and its
  streaming twin must keep passing **unmodified** in every port — that is the gate.
- **Six-port drift** → Per-language parity checklist in `tasks.md`; the fourteen spike
  cases ported per language; `data.calls` keys pinned byte-identically in `SPEC.md` §10.
- **D4 quietly skipped in some port** → It has its own spec scenario and its own task per
  port; the ported S4 test fails loudly if a port fills only one slot.
- **Spike scaffolding rotting in the tree** → `golang/relay_spike_test.go` is explicitly
  temporary; a task folds it into the real suite (or deletes it) before the PR.
- **The baseline tests invert when the change lands** → S4/S11/S12 assert *today's*
  behavior on purpose and will fail once F2-a exists. They even self-detect: S4 fails if
  `data.calls` appears. Updating them is a task, not a surprise.
- **No live-provider proof** → "Anthropic rejects an unbalanced transcript" is a
  documented provider requirement, not something the hermetic suite observed. The
  *imbalance* is measured; the rejection is inferred. Stated in spike 0002 and accepted.
- **Two suspension kinds in one run** (a relay call and an auth-required MCP call in the
  same turn) → Untested by spike 0002. Add a case before implementation is called done;
  §10's single `waitFor` slot should already serialize them, but "should" is not "measured".

## Migration Plan

Additive and opt-in; nothing to migrate. No relay tool declared ⇒ byte-identical
behavior. Rollback is reverting the change; no persisted data shape changes (the new
`data.calls` only ever appears inside a relay `Request`, which does not exist today).
Ships in the next coordinated all-port version bump, per `PUBLISHING.md`.

## Open Questions

- Naming per port for the resume entry point (`RunWithAnswer` vs an options field vs an
  overload). Idiomatic shape wins per port; the *behavior* is pinned by the spec.
- Whether the resume entry point should also accept the transcript explicitly, or always
  read it from the `ConversationStore` by conversation id. Leaning: both — `Run*` takes
  history today, `Ask` takes an id; mirror that split rather than inventing a third shape.
- Should `data.calls` entries carry the raw argument JSON string alongside the parsed
  `input`? An OpenAI-shaped caller may want to echo arguments byte-for-byte. Cheap to add
  now, awkward later.
