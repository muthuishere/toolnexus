# Design — LLM upstream failover

## Shape

An upstream is the tuple the client already holds as five separate singletons:

```
Upstream { baseUrl, model, style?, apiKey?, fetch? }
```

The client's existing `baseUrl`/`model`/`style`/`apiKey`/`fetch` remain the **primary** upstream,
unchanged, so the option is purely additive. `fallbacks` is an ordered list; unspecified fields
inherit from the primary, so the common case — same provider, backup model — is one short entry.

The control flow is two nested loops:

```
for upstream in [primary, ...fallbacks]:        # outer: failover
    for attempt in 0..retries:                  # inner: today's loop, unchanged
        request = derive(upstream, transcript)  # <-- the structural change
        ...
```

The inner loop is today's code verbatim. The change that makes any of it possible is `derive`:
the request must be built **per attempt from the upstream**, not once before the loop. That is why
the injected transport cannot express this (proposal, spike 2b) and why the six pre-built call sites
in `js/src/client.ts` have to be reworked.

## Decisions

**D1 — Retry inside, failover outside.**
The `retries` budget is spent on the current upstream before advancing, matching LiteLLM's
`num_retries`-then-fallback ordering. The alternative — advance on first failure, retry only after
exhausting the list — reacts faster to a hard outage but converts every transient `429` on the
primary into traffic on a backup that may be pricier or weaker. Retry is for the blip; failover is
for the outage. The budget **resets** per upstream, so worst case is `(1 + retries) × upstreams`
attempts, which is bounded, predictable, and documented.

**D2 — Reuse the one retryable set.**
`SPEC.md:1075-1085` defines exactly one (`429`/`5xx`/network). Failover advances on the same set.
A network-errors-only trigger was considered and rejected: it would put two disagreeing definitions
of failure in one loop, and it excludes the `503` that is the single most common real outage signal.
Aborts continue to bypass everything and are never retried or failed over (`SPEC.md:1073`).

**D3 — `onError` stays the single authority.**
The classifier gains the current upstream's identity in its context, and its verdict governs both
tiers: `fail` short-circuits retry *and* failover. A host that wants "never leave the primary"
already has the lever. No third tier — the no-suspend-tier requirement in `resilience-policy` is
untouched and continues to hold.

**D4 — Cooldown is local and dumb, on purpose.**
An upstream that exhausts its budget is excluded for the rest of that request (a hard guarantee), and
deprioritised for a short window afterwards (best-effort). No ejection percentages, no panic
threshold, no shared store — those are Envoy's, and they require the fleet view a library does not
have. The window is a plain per-process timer, and the docs say so plainly rather than implying
distributed health.

**D5 — First token is the commit point.**
Spike 3: three deltas reached the caller, then the socket died. There is no way to un-emit them.
Before the first token, a stream failure fails over like any other; after it, the error surfaces.
This is what every serious implementation does, because no provider offers continue-from-offset
(SSE `Last-Event-ID` does not help — it resumes a *feed*, not a generation).

**D6 — Report the server, not the config.**
`RunResult` carries the upstream that actually served, and the metric events' `model` label follows.
`RunResult.model` hardcoding `opts.model` (`js/src/client.ts:167`) is a latent bug the moment more
than one upstream exists: cost attribution and per-model dashboards would be silently wrong. Adding
the serving upstream alongside — rather than only mutating `model` — keeps a host that reads `model`
today working.

**D7 — Cross-style fallbacks are gated on the canonical transcript.**
A mid-conversation hop to a different-`style` provider means re-rendering the accumulated transcript
into the other dialect. Until `add-canonical-transcript` lands, that render does not exist and the
hop would forward a foreign dialect — the exact silent bug that change fixes. So a `fallbacks` entry
whose `style` differs from the primary's SHALL be rejected **at construction**, with an error naming
the dependency. Fail at wiring time, not on the outage.

**D8 — Usage accumulates across upstreams.**
A run that spans two providers reports the sum, with per-upstream attribution in the metric events.
Discarding the failed upstream's usage would under-report real spend — a `500` after the prompt was
processed is still billed by most providers.

## Open questions

**Q1 — Should the cooldown be observable?** A host operating a fleet may want to export "primary is
in cooldown". Cheap to expose as a metric event; skipped in v1 unless review wants it.

**Q2 — Per-upstream `retries`?** A cheap primary might deserve 3 attempts and an expensive backup 1.
Currently the client-level `retries` applies to each. Deferred — inheritance already covers it if the
field is later added to `Upstream`, and no consumer has asked.
