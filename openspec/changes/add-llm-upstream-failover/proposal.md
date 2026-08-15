# LLM upstream failover — ordered backup targets beneath the retry budget

## Why

When the configured LLM endpoint is gone — a region outage, a provider incident, a model
deprecated out from under a running service — the client retries the same dead address until the
budget is spent and then fails. A spike against the built JS client, pointing at an upstream that
refuses connections:

```
2a baseline: FAILED after 3 attempts — fetch failed
   every attempt went to the SAME dead url: true (1 distinct url)
```

Three attempts (`1 + retries`), one address. `retries` handles a transient blip on a healthy
endpoint, which is the failure it was designed for; it has nothing to say about an endpoint that is
not coming back.

**A host cannot build this today.** The natural attempt is the §8 Gap 2 injectable transport — catch
the error and re-issue against a backup. The spike shows it half-works and is silently wrong:

```
2b fetch-hook reroute: run completed = true, text="hello from BACKUP"
   backup upstream received model = "gpt-4o"   <-- wanted the BACKUP's model
   RunResult.model reports        = "gpt-4o"
```

The URL can be rewritten because it is a transport argument. `model` cannot: it is serialized into
the JSON body (`js/src/client.ts:365`, `:379`, `:445`) before the transport ever runs. So a
host-built failover asks the backup for the primary's model — and if the backup is a different
provider it is also the wrong dialect, wrong auth header, and wrong usage accounting, all of which
branch on the client-level `style` (`js/src/client.ts:212`, `:409`, `:427`, `:603`). `RunResult.model`
compounds it by reporting `opts.model` regardless of who actually served (`js/src/client.ts:167`).

Failover is therefore a client-level concern by construction: an upstream is the tuple
(`baseUrl`, `model`, `style`, credentials, transport), and only the client holds all five.

## What this is not

**Not load balancing.** The prior-art survey settled this. LiteLLM's Router needs Redis to share
deployment health across instances; Envoy's outlier detection assumes a fleet view
(`max_ejection_percent`, a panic threshold); Vercel moved model fallbacks out of the AI SDK and into
a hosted Gateway. Distributing traffic requires global state a library in one process does not have.
Failover requires only local knowledge — *this* call failed, try the next one — which is exactly what
a library can be trusted with. Round-robin, weighting, and health-based traffic shaping are
deliberately out of scope; that is infrastructure's job.

**Not a second failure taxonomy.** `SPEC.md:1075-1085` defines one retryable set (`429`, `5xx`,
network errors) shared by `retries` and `onError`. Failover reuses it rather than introducing a
network-errors-only trigger, which would mean two disagreeing notions of "failed" inside one loop.

## The §11 collision — model faithfulness and the route-gate

`SPEC.md:1169-1173` pins **model faithfulness**: `Client.run` "transmits the caller's configured
`model` id to the endpoint **verbatim** on every turn — no rewrite, alias, or silent default when the
caller supplied one. A route the operator chose as cheap … is the model actually billed." It is
conformance-pinned by `golang/routing_conformance_test.go:47`. Read literally, failover breaks it:
the whole point is to send a different model than the one configured.

The reconciliation is that the rule forbids *silent* substitution — an alias, a default, a rewrite
the operator did not ask for. A fallback model is explicitly configured by that same operator, in
order, and `RunResult` reports which one served (design D6). This change SHALL state that carve-out
in §11 rather than leave a conformance-pinned sentence quietly contradicted.

**The route-gate is the safety half, and it is not a documentation issue.** `SPEC.md:1174-1178`
makes `beforeLLM` the seam where "an expensive-tier route is gated" — the reference pattern aborts
the call unless a cost shield approves the model. If failover advances to a fallback without
re-running that gate, an operator who gated an expensive tier gets billed for it anyway the moment
the cheap primary has an outage — precisely when nobody is watching. The gate SHALL therefore run
again for each upstream, with that upstream's model. ADR 0014 is the reason this is visible at all:
it documents `beforeLLM` as having two independent spec-canonical tenants (compaction at
`SPEC.md:943`, the route-gate at `:1174`) competing for one slot.

## What Changes

- The client SHALL accept an optional ordered list of **fallback upstreams**. Each entry may override
  `baseUrl`, `model`, `style`, credentials, and the injected transport — so a backup may be a
  different model, a different region, or a different provider entirely.
- Failover sits **beneath** retry, as an outer tier: the `retries` budget is spent on the current
  upstream first; only when it is exhausted does the client advance to the next upstream, where the
  budget resets. Retry handles the blip, failover handles the outage.
- Only failures the existing classification calls retryable trigger advancement. A `400` is a bad
  request and will be a bad request on the backup too; it fails as it does today. `onError` returning
  `fail` short-circuits the whole thing, including failover.
- An upstream that has failed is excluded for the remainder of that request, so a chain never
  revisits a dead target. A short, **process-local** cooldown additionally deprioritises it for
  subsequent requests — local because a library cannot honestly claim shared health state.
- **Streaming commits at the first token.** Before the first token is emitted, a failure fails over
  normally. After it, the response is committed: the error surfaces and no fallback is attempted.
  The spike confirms why — the caller had already received `"Hello from primary"` when the socket
  died, and any fallback would append a second, contradictory answer to text already on screen.
  This matches the industry consensus; no provider offers continue-from-offset.
- `RunResult` and the metric events SHALL report the upstream that actually served, not the
  configured one — the correctness bug the spike surfaced at `client.ts:167`, `:88`, `:90`.
- **Absent the option, behaviour is byte-identical to today.** No signature moves.

## Dependencies

Depends on `add-canonical-transcript`. A fallback to a different-`style` provider requires rendering
the in-flight transcript into the target dialect; without a canonical form that hop silently forwards
the wrong dialect. Cross-style fallbacks SHALL be rejected at construction until that change lands,
rather than shipping a half-working hop.

## Capabilities

### Modified Capabilities

- `resilience-policy`: gains upstream failover as an outer tier around the existing retry budget, and
  extends the `onError` contract so a host can see which upstream a failure came from. The existing
  requirements — the two-tier `retry`/`fail` classification, the no-suspend-tier rule, and the
  conformance matrix — keep their meaning; failover is layered outside them, and the no-suspend rule
  continues to hold.
- `client-observability`: `on_metric` events and `RunResult` report the serving upstream. Tracked as
  a requirement in this change's `resilience-policy` delta to keep the failover contract in one
  place; the observability spec's existing requirements are unchanged in wording.

## Impact

- **Code**: the retry loop and every call site that builds a request. In `js/` that is the loop at
  `js/src/client.ts:630-655` and the six sites handing `llmFetch` a pre-built url+init (`:453`,
  `:485`, `:733`, `:813`, `:912`, `:984`) — these must be reworked to **re-derive** the request per
  attempt rather than build it once, which is the structural core of the change. Equivalents in
  `python/src/toolnexus/client.py`, `golang/client.go`, `java/.../LlmClient.java`,
  `csharp/src/Toolnexus/LlmClient.cs`, `elixir/lib/toolnexus/client.ex`,
  `clojure/src/toolnexus/client.cljc`.
- **Contract**: `SPEC.md §8` (resilience and the `onError` classification at `:1075-1085`), and the
  `RunResult.model` definition.
- **Fixtures**: shared fake upstreams under `examples/` that can refuse connections, return `429`/
  `5xx`, and die mid-stream — the spike harness, promoted.
- **Secrets**: a fallback's credentials are read from the environment at call time, exactly as
  today's key resolution and remote MCP `headers` do, and are never logged. Fixtures use obvious
  fakes.
- **Risk**: the per-attempt re-derivation touches the hot path in seven languages. Mitigated by the
  absent-option byte-identity gate, which is the first task.

## Deliberate non-goals for v1

- **Load balancing / traffic shaping** — infrastructure, per the survey above.
- **Shared cross-instance health** — would require Redis or equivalent; the cooldown stays local and
  is documented as such.
- **`context_window_fallbacks`** (LiteLLM's hop to a larger-context model on overflow) — a real
  capability with genuine affinity to §7F compaction, and a natural follow-on once the upstream list
  exists. Not v1.
