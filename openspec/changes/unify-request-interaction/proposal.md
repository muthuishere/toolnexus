## Why

toolnexus already has **one** primitive for every out-of-band human/host interaction: §10
Suspension — a tool returns `Pending(Request{kind, prompt, …})`, the host's `waitFor` resolves
it, the loop resumes. Login is `kind:"authorization"`, approval is `kind:"approval"`, free-text
is `kind:"input"`. There is no separate auth subsystem and no separate approval subsystem —
there is one suspend/resume seam and `kind` is just data on top of it.

Asking the user a **question** is the same idea — "pick one of these options" is out-of-band
human input, indistinguishable in shape from "approve this" or "log in here." Yet the built-in
`question` tool (§4A) is the **lone violator**: it returns *immediately*
(`ToolResult{isError:false, output:<JSON>, metadata:{questions}}`) instead of suspending. That
forces every host into a second, parallel convention for "the model wants to ask something" —
exactly the fragmentation §10 exists to prevent. A host that already wired `waitFor` for logins
and approvals still has to special-case `question` out-of-band by hand.

We do **not** want a separate `AskUserQuestion` path. Everything that needs the human is a
`Request`, distinguished only by its `kind`. This change removes the one exception so that
statement is true with no asterisk.

The unification was validated by a hermetic spike (JS, 12/12 green): a tool raising
`pending({kind:"question", …})`, a `beforeTool` guard raising a `Request` with no tool code, and
a durable halt with no `waitFor` — all three resolve through the same one `waitFor`. Path B
(guard-raised pending) was then confirmed by code-trace across all five ports; it already works
everywhere. The only remaining inconsistency is the `question` builtin itself.

## What Changes

- **The `question` built-in tool suspends** instead of returning immediately. It returns
  `pending({ kind: "question", prompt: <rendered>, data: { questions } })` — a §10 `Request`,
  identical in shape to `authorization` / `approval` / `input`. No new type, no new option, no
  new host slot; it rides the `waitFor` a host may already have.
- **On resolution it returns the human's answer.** When the loop re-executes the tool with
  `ctx.answer` set (§10 loop rule, `answer.ok == true`), `question` returns the answer payload
  as its output — like `kind:"input"`, where the resolution *is* the value. With no `waitFor`
  configured, the run halts durably (`status:"pending"`) carrying the `question` Request, for a
  durable host to deliver and resume later.
- **Cross-language contract (`SPEC.md`) updated**: §4A's `question` row is rewritten from
  "returns … for the host to answer" to "suspends with a `kind:"question"` Request"; §10 names
  `question` as the canonical `kind:"question"` producer alongside `authorization`.
- **Parity across all five ports** (js / python / golang / java / csharp): the same ~5-line edit
  in each `question` implementation, plus one parity test locking (a) `question` suspends and
  resumes and (b) a guard-raised `Request` is honored by the loop (path B — already working;
  the test prevents regression).

## Impact

- **Affected spec:** `builtin-tools` (new requirement — the `question` tool suspends via a
  Request). `SPEC.md` §4A + §10 (cross-language contract wording).
- **Affected code:** `question` tool in all five ports (`js/src/builtin.ts`,
  `python/src/toolnexus/builtin.py`, `golang/builtin.go`,
  `java/.../BuiltinTools.java`, `csharp/src/Toolnexus/BuiltinTools.cs`) + one parity test each.
- **Behavior change, but low blast radius:** the `question` tool's name and input schema are
  unchanged; only its *return* changes from an immediate result to a suspension. Hosts that
  already handle §10 `Pending` get `question` for free. Hosts that read
  `metadata.questions` off an immediate result must move to the `waitFor` seam — called out as a
  breaking note in the change.
- **Net subtraction of concepts:** this deletes a special case rather than adding a subsystem.
  After it, "everything that asks the human is a `Request`" is literally true.
