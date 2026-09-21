# ADR 0029 — `Retries` must be able to mean zero

- **Status:** **Accepted — REVISED 2026-09-22 after a spike. Its central premise was
  falsified for six of seven ports.** The first draft proposed a `-1` sentinel everywhere.
  A spike proved that JS, Python, Java, C# and Elixir already distinguish an explicit `0`
  from an unset field through their own native mechanisms, so a sentinel there would have
  been noise imported from Go's type system. What survives is a narrower, truer change:
  **one real code fix in Go, one unrelated parity break found in Clojure, and — the part
  that matters most — a test in all seven ports**, because five of them were correct only
  by accident and asserted nowhere. Shipped across seven ports 2026-09-22.
- **Date:** 2026-09-21
- **Driver:** issue #94. A consumer building a CLI-backed model measured **9 backend
  invocations for 3 asked-for attempts**, at ~15s each.
- **Scope:** all seven ports (`Retries` is a manifest option, `conformance/options_manifest.json`).

## Context

`ClientOptions.Retries` is documented `0 ⇒ 2` (`golang/client.go:50`, and the same in every
port). Zero is therefore not expressible: the zero value of the field means "two". For an
HTTP client that default is right — a 429 or a dropped socket is worth riding out. For a
model that is not on a wire, a retry is pure waste: the same local process is launched
again and returns the same thing.

The repo already knows this. `CreateInProcessClient` installs
`OnError ⇒ TierFail` internally, and `golang/inprocess.go:193` says why in a comment:

> This goes through OnError rather than Retries because this port documents
> `Retries: 0 ⇒ 2`, so zero cannot mean zero here without changing shipped behaviour.

That is the admission. The in-process seam needed "no retries", could not say it, and
routed around its own option. **A host on `CreateClient` with a custom transport — the
documented path for a local model (`examples/onnx-in-process`) — has no such hatch
and no signpost to it.**

## Correction to the previous draft

**The draft assumed `0 ⇒ 2` was a seven-port contract. It is not — it is a Go artefact.**
Measured, not recalled (`spikes/retries-zero/SPIKE.md`, each proven with a real client
against a fake failing transport): `js/src/client.ts:36,674` (`??`),
`python/src/toolnexus/client.py:545,567` (literal keyword default),
`java/.../LlmClient.java:92` (boxed `Integer` null-check), `csharp/.../LlmClient.cs:184`
(`int?` with `??`), `elixir/.../client.ex:278,356` (struct default plus truthy zero) all
already let an explicit `0` mean zero. Only `golang/client.go:521-526` and
`classifier.go:779-785` test `> 0` / `<= 0` on a bare `int` and cannot.

Had the draft shipped as written, five ports would have gained a `-1` spelling that means
nothing in them, to solve a problem they do not have.

**The spike also found a defect the draft was not looking for.** `clojure/`'s shipped
default is **0 retries, not 2** (`client.cljc:137,520`): six ports ride out a 429, Clojure
does not. That is a live, user-visible parity break, and it was invisible precisely because
no port asserted its retry count.

## Decision

`Retries: -1` in **Go only**, where the bare `int` leaves no other additive spelling; `0`
keeps meaning `2` there for every existing caller. Every other port keeps its native
spelling of an explicit zero. The manifest records these as aliases for one logical option,
exactly as it already does for the HTTP-client option.

Clojure's default is corrected to 2.

**And all seven ports get the same pair of tests** — zero means exactly one invocation, and
the default means three. This is the real content of the change. Five ports needed no code
at all; they needed an assertion, because a behaviour that is correct but untested is one
refactor away from being wrong, and this repo exists to prevent exactly that drift.

Rejected alternative: documentation only. It leaves the field lying about itself, and
the reported failure is a host that *read* the field and still got 3× the calls.

Considered and deferred: re-defaulting `0 ⇒ 0` with a separate `Retries` default
constant. It is the honest shape and it is a breaking change to a shipped contract in
seven registries; not worth it for this.

## Gate — how it resolved

1. **FALSIFIED for six of seven ports.** Five ports can see "unset" and keep their native
   spelling; the sentinel is Go-only. The gate was written to catch exactly this, and did.
2. **Held.** `-1` does not reach the backoff arithmetic; no negative sleep, no skipped
   first attempt, no unbounded loop. `go test -race ./...` green.
3. **Held.** `CreateInProcessClient` now passes the real spelling and its private
   `OnError ⇒ TierFail` workaround is gone, so the smell this ADR cited as evidence is
   removed rather than documented.

## Consequences

- One behaviour, seven ports, now asserted in all of them.
- Clojure users who relied on its accidental no-retry default will see two retries on a
  transient failure. Named in `CHANGELOG.md` as a behaviour change in that port.
- Change: `openspec/changes/fix-retries-zero/`.
