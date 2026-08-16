# ADR 0018 — An in-process `invoke` seam: body in, body out, no socket

- **Status:** **Proposed** (2026-08-16)
- **Date:** 2026-08-16
- **Driver:** [modelnexus](https://github.com/muthuishere/modelnexus) runs a GGUF model **inside
  the caller's process**. Today toolnexus can only reach a model over HTTP, so composing the two
  forces a local server to exist purely to talk to a library already loaded in the same address
  space. That is a socket, a port, a lifecycle and a serialization round-trip in exchange for
  nothing.

## Context

`ClientOptions` exposes an HTTP-shaped escape hatch already: `HTTPClient` (`golang/client.go:91`,
§8 Gap 2), scoped to the LLM path only. It is enough to *route* a request, but it is the wrong
shape for removing the network:

- In Go it is a concrete `*http.Client`, not an interface. Answering in-process means writing an
  `http.RoundTripper` that fabricates an `http.Response` — synthesising a status line and a body
  reader for a call that never leaves the process.
- The equivalent seam differs per port (a `fetch`-shaped function in JS, a client object in
  Python), so the same recipe reads differently in each language — the drift this project exists
  to prevent.
- Every in-process caller pays JSON encode + decode twice for a function call.

The important observation is that **the wire format is already the right contract; the wire is
not.** toolnexus builds a provider-shaped request body and parses a provider-shaped response
body. modelnexus's bridge is built over llama.cpp's `common_chat` and already emits OpenAI-shaped
`tool_calls`. The two agree on the *data structure* — only the transport is surplus.

## Decision

Add one option to `ClientOptions`, in every port:

```
invoke(request_body) -> response_body
```

- Receives the **fully-built provider request body** — after the `RequestParams` merge and after
  `BodyTransform`, exactly the map that would have been marshalled and sent.
- Returns the **provider-shaped response body** — exactly what would have been parsed from the
  HTTP response.
- When set, toolnexus performs **no HTTP at all** for LLM calls. `BaseURL`, `APIKey` and
  `Headers` are unused and MUST be ignored rather than validated.
- `Style` still selects the adapter, so an in-process provider declares whether it speaks
  OpenAI, Anthropic or Gemini shape and reuses the existing mapping and parsing untouched.

Everything above and below the seam is unchanged: `BeforeLLM` / `AfterLLM` hooks still run,
`Retries` still bounds attempts, `OnError` still classifies a failure into retry or fail, metrics
still record. The callback replaces the transport, not the loop.

**Scope for v1: non-streaming only.** If `invoke` is set and a streaming call is made, the port
MUST fail with a clear error naming the limitation rather than silently buffering or silently
falling back to HTTP. Streaming through the seam is a separate change (below).

### Why not the alternatives

- **Generalise `HTTPClient` to an interface per port.** Keeps HTTP vocabulary — status codes,
  headers, response readers — in a path with no HTTP in it. Every in-process implementer writes
  the same fake-response boilerplate, and it stays shaped differently in each language.
- **A semantic callback** (`messages, tools -> assistant message`). Cleaner in the abstract, but
  it obliges toolnexus to define and maintain a neutral message/tool-call model and to map every
  adapter onto it — duplicating the adapter layer to avoid one JSON round-trip. The provider body
  is *already* the neutral-enough contract, and it is one the caller can debug by printing.
- **Leave it alone; run a local server.** Works, and is what everyone does today. It also means
  the answer to "can I run an agent with no network?" is no, when the pieces to make it yes are
  already in the same process.

## Consequences

- A tool-calling agent can run with **no network, no port, no daemon** — the loop, the tools, the
  skills and the model all in one process. Nothing else in this space can currently show that in
  three languages.
- The seam is testable without a socket: a fake `invoke` returning a canned body replaces the
  HTTP stubs several suites stand up today, which should make those tests faster and less flaky.
- Two ways to reach a model now exist, and the docs must say plainly which is which:
  `BaseURL` for remote, `invoke` for in-process. Setting both is a caller error and SHOULD be
  rejected at construction rather than resolved by precedence.
- Seven-port parity work: this is one option and one branch in the LLM call path per port. Small,
  but it is seven implementations plus conformance fixtures, and it is not done until all seven
  ship it (`CLAUDE.md`, prime directive 2).
- `SPEC.md §8` moves — the contract gains a transport-substitution point — so the OpenSpec change
  carries a `SPEC.md` edit in the same diff.

## Open

- **Streaming.** modelnexus streams tokens, and the natural shape is a second optional callback
  receiving an emit function. Deliberately out of v1 so the simple case ships; revisit once the
  non-streaming seam has a real consumer.
- **Naming.** `invoke` reads well in every target language and does not imply a transport.
  `LlmTransport` and `Complete` were considered; `Transport` re-imports the HTTP vocabulary this
  ADR is trying to drop.
- **Async.** In ports where the client is async, the callback must be awaitable; in Go it takes a
  `context.Context` like the rest of the surface.

## Verification

Not yet spiked. Before this is accepted it wants the same treatment as ADR 0017: a runnable spike
wiring a real in-process model through `invoke` in at least two ports — Go and one other — and
demonstrating a full tool-calling turn with the network unreachable. Proving it with the network
*actually* disabled, rather than merely unused, is the point of the exercise.
