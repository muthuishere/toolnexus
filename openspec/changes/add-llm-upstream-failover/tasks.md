## 0. Prerequisite

- [ ] 0.1 `add-canonical-transcript` merged and archived. Cross-style fallbacks are gated on it (design D7); same-style failover does not depend on it, so this change MAY land first with the construction-time rejection in place — but not without it.

## 1. Contract, gate, and shared fixtures (do first)

- [ ] 1.1 Pin the upstream tuple and inheritance rules in `SPEC.md §8`: what a fallback may override, that unspecified fields inherit from the primary, and that the client's existing configuration is the primary.
- [ ] 1.2 Pin the two-tier ordering: retry exhausts on the current upstream, then advance; budget resets per upstream; total attempts bounded by `(1 + retries) × upstreams`; advancement uses the one retryable set already defined at `SPEC.md:1075-1085`; aborts (`:1073`) bypass both tiers.
- [ ] 1.3 Pin the first-token commit rule for streaming, and the per-request exclusion set plus the explicitly process-local cooldown.
- [ ] 1.4 Pin the reporting change: `RunResult` reports the serving upstream, metric labels follow, usage accumulates across upstreams, and `onError` sees the upstream identity and can short-circuit both tiers.
- [ ] 1.5 Establish the absent-option byte-identity gate: with no fallbacks configured, request bodies and the retry sequence must be identical to today, in every port. This is the regression guard for the per-attempt re-derivation.
- [ ] 1.6 Reconcile `SPEC.md §11`: state the model-faithfulness carve-out (`SPEC.md:1169-1173`) — failover selects among operator-configured upstreams and sends each model verbatim; it never aliases, rewrites, or silently defaults. Confirm `golang/routing_conformance_test.go:47` (`TestRoutingModelFaithfulness`) still passes unchanged.
- [ ] 1.7 Pin that the `beforeLLM` route-gate (`SPEC.md:1174-1178`) re-runs per upstream with that upstream's model, and that a gate abort prevents the request. Add a test in the shape of `golang/routing_conformance_test.go:82` (`TestRouteGateBlocksExpensiveTier`): cheap primary fails, expensive fallback is gated, run fails without billing the expensive tier.
- [ ] 1.8 Promote the spike harness into shared fixtures under `examples/`: fake upstreams that can refuse connections, return `429`/`5xx`, and die mid-stream after N tokens. Credentials in fixtures are obvious fakes; nothing reads a real key.

## 2. js (reference implementation — land first)

- [ ] 2.1 Rework the six pre-built call sites (`js/src/client.ts:453`, `:485`, `:733`, `:813`, `:912`, `:984`) so the request is **derived per attempt from the upstream** rather than built once. This is the structural core; everything else depends on it.
- [ ] 2.2 Wrap the retry loop (`js/src/client.ts:630-655`) in the outer failover loop, with the per-request exclusion set and the process-local cooldown.
- [ ] 2.3 Add the `fallbacks` option and construction-time validation, including the cross-style rejection (design D7).
- [ ] 2.4 Fix the reporting: `RunResult.model` currently hardcodes `opts.model` (`js/src/client.ts:167`); report the serving upstream, and label the metric events at `:88`/`:90` accordingly. Accumulate usage across upstreams (design D8).
- [ ] 2.5 Apply the first-token commit rule in the streaming path.
- [ ] 2.6 Extend the `onError` context with the upstream identity and confirm `fail` short-circuits failover.
- [ ] 2.7 Tests: the absent-option gate (1.5) and every spec scenario against the shared fixtures. Verify: `cd js && npm test`.

## 3. Remaining ports (same tasks as §2, per port)

- [ ] 3.1 python — `python/src/toolnexus/client.py`. Verify: `cd python && python -m pytest -q`.
- [ ] 3.2 golang — `golang/client.go` (note `HTTPClient` at `golang/client.go:88-91` becomes per-upstream). Verify: `cd golang && go build ./... && go vet ./... && go test -race ./...`.
- [ ] 3.3 java — `java/.../LlmClient.java` (`httpClient` builder at `:120`, dispatch at `:559-567`). Verify: `cd java && ./gradlew test --no-daemon`.
- [ ] 3.4 csharp — `csharp/src/Toolnexus/LlmClient.cs` (`WithHttpClient` at `:223`). Verify: `cd csharp && dotnet test`.
- [ ] 3.5 elixir — `elixir/lib/toolnexus/client.ex`. Verify: `cd elixir && mix test` and `mix coveralls` (gate ≥ 95%).
- [ ] 3.6 clojure — `clojure/src/toolnexus/client.cljc` (`:http-client` at `:305-313`). Verify: the port's suite plus the 5-mode exact-agree gate.

## 4. Cross-cutting correctness

- [ ] 4.1 Confirm in every port that a fallback's credentials are resolved from the environment at call time and never logged — including in the all-upstreams-failed error message, which must name upstreams without leaking secrets.
- [ ] 4.2 Confirm the `resilience-policy` no-suspend-tier requirement still holds: an all-upstreams-failed outcome surfaces as an error, never as a §10 `Request`.
- [ ] 4.3 Confirm suspension/resume (§10) and A2A `serve` (§7B) are unaffected by the per-attempt re-derivation.
- [ ] 4.4 Confirm the bounded-attempt guarantee empirically: with `retries: 2` and three upstreams, exactly nine attempts occur and no upstream is revisited.
- [ ] 4.5 Resolve design Q1 (expose cooldown state as a metric) and Q2 (per-upstream `retries`) in review — implement in all seven ports or record the rejection in `design.md`.

## 5. Ship

- [ ] 5.1 `CHANGELOG.md` under `## Unreleased`: lead with what the user gets — a dead endpoint or deprecated model no longer takes the agent down. Name the non-goals explicitly (no load balancing, no shared health state, no `context_window_fallbacks`) and where they are tracked, per the changelog rule on stating what is not done.
- [ ] 5.2 Document in the per-language READMEs that the cooldown is process-local, and that failover is not load balancing.
- [ ] 5.3 `openspec validate add-llm-upstream-failover`.
