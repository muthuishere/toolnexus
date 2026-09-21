# ADR 0030 — the in-process seam stops at the top-level client

- **Status:** **Accepted — REVISED 2026-09-22 after a spike, and SHIPPED in all seven
  ports.** The spike falsified the ADR's hopeful case: it asked whether the other six ports
  already accepted a semantic generate (which would have made this a Go bug). **None does.**
  All seven accept only a wire-shaped transport, and every one already builds an equivalent
  generate-backed adapter *privately*. So this was a genuine cross-language contract gap,
  not a Go convenience — the more expensive answer, and the correct one.
- **Date:** 2026-09-21
- **Driver:** issue #95. A consumer adopted `CreateInProcessClient`, then had to
  **re-implement the unexported round tripper** to use sub-agents.
- **Related:** ADR 0019 (one transport seam) — this is the same seam seen from the
  other end, and is *not* a re-litigation of it.

## Context

`createInProcessClient` shipped in 0.16.0 as the semantic seam: `Generate(request) ->
response`, no HTTP types. It is the answer to "my model is in this process".

`agents.Options` did not get the memo. It takes `Transport http.RoundTripper` and
`LLM *LLMOptions` (`golang/agents/runtime.go:270-287`) and nothing else. So a host
whose model is a function has exactly one way to reach the sub-agent runtime:
copy `inProcessRoundTripper` — the request decode, `choices[0].message` assembly,
`finish_reason` derivation, argument encoding, the usage block, the streaming
refusal — into its own tree, tracking an unexported file it cannot import.

That copy is guaranteed drift. It is also a **parity trap**: the same gap almost
certainly exists in the other six ports' sub-agent wiring, and nothing in CI would
notice, because `options_manifest.json` governs client options, not agent options.

The reporter offers three shapes. They are not equivalent:

1. **Export the round tripper** — `InProcessTransport(generate) http.RoundTripper`.
   Smallest diff, and `CreateInProcessClient` becomes its caller. But it is
   Go-shaped: it hands back an `http.RoundTripper`, the very HTTP type the semantic
   seam exists to avoid, and four ports have no such type to hand back.
2. **`agents.Options.InProcess *InProcessOptions`** — semantic, ports cleanly, but
   adds a third mutually-exclusive way to configure a runtime's model (`Transport` |
   `LLM` | `InProcess`), and mutually-exclusive option triples are how config bugs
   are born.
3. **`client.Transport()`** — same HTTP-shaped objection as (1), plus it implies
   every client has a transport, which for four ports is a fiction.

## Decision (proposed)

Prefer **(2), expressed as one semantic field, with (1) as the Go-local convenience**
— i.e. the cross-port contract is "a sub-agent runtime accepts a `Generate`", and Go
additionally exports the round tripper because Go hosts genuinely wire transports.
The mutually-exclusive triple is made safe by validating it at construction and
erroring, never by precedence rules.

## Gate — how it resolved

1. **The gap is real in all seven.** No port's agent runtime accepted a semantic generate.
   Seven private adapters, no way for a caller to reach any of them.
2. **Held, and better than hoped.** Every port's concurrency gate turned out to wrap
   *whatever transport resolves*, so the in-process path rides it by construction — **zero
   new gating code in any port**. Each port ships a gate test with a **negative control**
   (concurrency 1 ⇒ no overlap; concurrency N ⇒ overlaps observed), so the assertion is
   provably capable of failing rather than passing vacuously.
3. **Held.** One generate function now serves a top-level client and a sub-agent runtime in
   every port, with no copied adapter code. The spike first proved the copy was
   unavoidable: the client's HTTP fields are unexported, so sharing was impossible without
   reflection until the export existed.

## Correction to the previous draft

The draft claimed four ports "have no HTTP type to hand back", making shape 1 a parity
hazard. **Overstated.** Every port already builds an analogous adapter privately — Java's
`GenerateBackedHttpClient`, C#'s `GenerateBackedHandler`, Python's `_InProcessTransport`,
plain functions in Elixir and Clojure. Exporting it is mechanically available everywhere,
merely cheaper in the dynamic ports.

Shape 3 (`client.transport()`) is dropped: the same new-accessor work in every port, for no
benefit over exporting the adapter.

## Consequences

- A latent bug surfaced and was fixed in scope: C#'s `GenerateBackedHandler.SendAsync`
  never yielded, so turns ran **fully synchronously inside the runtime lock** — shipped
  behaviour since 0.16.0, affecting anyone using the in-process client there.
- Java's `GatedHttpClient` had a hardcoded delegate and now takes one by constructor.
- Construction fails on a conflicting model configuration, in each port's idiom for a
  static misconfiguration. Never resolved by precedence.
- Change: `openspec/changes/add-in-process-subagents/`.
