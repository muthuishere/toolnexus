# The in-process seam stops at the top-level client

## Why

`createInProcessClient` shipped in 0.16.0 as the semantic seam for a model that lives in
this process: `generate(request) -> response`, no HTTP types to construct. It replaced
about 90 lines of hand-rolled `chat.completion` assembly in the reporting consumer's
adapter, which is exactly what it is for.

The sub-agent runtime did not get the memo. It accepts a wire-shaped transport and a
provider config and nothing else (`golang/agents/runtime.go:270-287`). So a host whose
model is a *function* has exactly one way to reach sub-agents: copy the library's private
round tripper — the request decode, `choices[0].message` assembly, `finish_reason`
derivation, argument encoding, the usage block, the streaming refusal — into its own tree,
tracking an unexported file it cannot import (issue #95).

That copy is guaranteed drift, in the one repo whose entire purpose is preventing drift.

## What a spike found (`spikes/inprocess-subagent/SPIKE.md`)

The audit asked whether this was a Go-only bug. **It is not.** No port's agent runtime
accepts a semantic generate; all seven accept only a wire/HTTP-shaped transport, and every
port already builds an equivalent generate-backed adapter **privately** for its own
in-process client — Java's `GenerateBackedHttpClient`, C#'s `GenerateBackedHandler`,
plain functions in Elixir and Clojure. Seven hosts, seven private adapters, and no way for
a caller to reach any of them.

The spike also proved the fix cannot be faked from outside: the client's HTTP fields are
unexported, so sharing one generate across a top-level client and a sub-agent was
impossible without reflection or duplication until a 10-line export was added.

## What changes

- Every port's agent runtime accepts the **semantic generate** as an alternative to the
  transport/provider config. Mutually exclusive, **validated at construction with an
  error** — never resolved by precedence, because a silent precedence rule is how a host
  ends up talking to the wrong model.
- Every port **exports** its existing private generate-backed adapter, and its top-level
  in-process client becomes a caller of it. Zero duplicated logic, and a host wiring the
  seam somewhere else gets the same adapter the library uses.
- The **global turn gate still applies** on the new path. A semantic generate that
  bypassed the concurrency gate would silently remove a control hosts depend on, so each
  port ships a gate test **with a negative control** — an assertion proven capable of
  failing, not a tautology that passes either way.

## What this does not do

It does not add a `client.transport()` accessor (the third shape proposed in #95). It needs
the same new-accessor work in every port for no benefit over exporting the adapter, and it
implies every client has a transport — which, for the four ports whose in-process path is
a plain function, is a fiction.
