## Why

toolnexus already has a first-class human-in-the-loop primitive: a tool suspends with a
`Request`, and the host resolves it with a `waitFor(request) -> Answer` callback (§10). That
callback is the right seam — minimal, one function, no auth subsystem. But **every host
re-implements the same plumbing** to satisfy it: read a line from the terminal, stand up an HTTP
endpoint and poll it, push over a websocket, render a browser form. We hand-wrote the terminal
version five times just to document it.

The fix is not a new mechanism — the `waitFor` contract is already correct and stays **exactly as
it is**. The fix is to **ship pre-built adapters that satisfy that contract**, so "how does my
agent get a human answer" is a one-liner, and to give the shape a name (`Responder`) so "define
your own" is simply "write a `waitFor`-shaped thing." Batteries, not a framework.

## What Changes

- **`waitFor` is unchanged and back-compatible.** A plain `(request) -> Answer` function keeps
  working byte-for-byte as today. This is additive only; no existing code changes behavior.
- **`waitFor` also accepts a pre-built adapter** (a `Responder`) — a value that satisfies the same
  Request→Answer contract. Passing an adapter is interchangeable with passing a function.
- **A named `Responder` type/alias** is introduced per port so that a custom responder is a
  first-class, documented shape — not a new interface to learn, the *same* one `waitFor` already
  speaks.
- **Ship a `stdioResponder`** in core: prints the request prompt and reads the operator's answer
  off the terminal, mapping the typed line to an `Answer`. Byte-identical prompt/parse rules
  across all 5 ports (shared conformance fixture).
- **Name the transport-adapter family** — `httpPollResponder`, `websocketResponder`, and
  (optionally) a `browserResponder` — and pin their wire contracts. Which of these ship in this
  change vs. a phase-2 follow-up is an **open decision in `design.md`** (owner selects).
- **Durable posture stays orthogonal.** An adapter is a *blocking* `waitFor` (it keeps the run
  alive while it reads/polls/awaits). The "web request returns now, answer hours later" case
  remains the existing no-`waitFor` durable-pending path. One thing is not overloaded to do two
  jobs — but a single adapter *may* be backed by the same persistence, so a poll/socket adapter
  bridges a long wait in-process. (See design.md D5.)
- Off by default and zero-cost when unused: a host that passes nothing, or its own function, sees
  no change. Matches the existing opt-in Hooks/Observability pattern.
- Byte-identical shape across all 5 ports; new shared fixtures under `examples/` cover the stdio
  adapter's prompt/parse and any shipped transport adapter's request/answer round-trip.
- **Not in this change**: implementation code. This proposal + spec deltas + `tasks.md` scope the
  contract and the per-language task breakdown; building it is a follow-up change. The open forks
  in `design.md` (D1–D5) are resolved with the owner before that follow-up. No publishing/release
  config is touched.

## Capabilities

- **suspension** (MODIFIED): the `waitFor` host contract gains an equivalent adapter form and a
  named `Responder` shape; a shipped `stdioResponder` and a named transport-adapter family are
  added, all satisfying the unchanged Request→Answer contract.
