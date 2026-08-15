## 1. Contract and shared fixture

- [x] 1.1 Restate `SPEC.md:961-964` in dialect-neutral terms: the tail begins at a user turn that does not carry a tool result; an Anthropic `user` message carrying `tool_result` blocks is not a boundary; OpenAI-dialect boundaries are unchanged.
- [x] 1.2 Correct the misleading in-code comment (`golang/agents/compaction.go:100-102` and its equivalents), which currently asserts the false guarantee.
- [ ] 1.3 Add the Anthropic-carrier case to the shared compaction fixture so all seven ports assert it identically. Regression tests currently exist in golang, js and python only; java/csharp/elixir/clojure carry the fix and pass their existing suites but have no dedicated test yet.

## 2. Ports (one predicate each)

- [x] 2.1 golang — `golang/agents/compaction.go:99-122`. Verify: `go build ./... && go vet ./... && go test -race ./...`.
- [x] 2.2 js — `js/src/agents/compaction.ts`. Verify: `cd js && npm test`.
- [x] 2.3 python — `python/src/toolnexus/agents/compaction.py`. Verify: `cd python && python -m pytest -q`.
- [x] 2.4 java — `java/.../Compaction.java`. Verify: `cd java && ./gradlew test --no-daemon`.
- [x] 2.5 csharp — `csharp/src/Toolnexus/Agents/Compaction.cs`. Verify: `cd csharp && dotnet test`.
- [x] 2.6 elixir — `elixir/lib/toolnexus/agents/compaction.ex`. Verify: `cd elixir && mix test` + `mix coveralls` (≥95%).
- [x] 2.7 clojure — `clojure/src/toolnexus/agents/compaction.cljc`. Predicate must use `clojure.core` only, no host interop (ADR 0009). Verify: the port's suite + 5-mode exact-agree gate.

## 3. Correctness

- [x] 3.1 Assert the OpenAI-dialect no-change property per port (byte-identical output) — this is the regression guard.
- [x] 3.2 Assert the no-safe-boundary case. CORRECTED during implementation: it is NOT a no-op — the body is summarized with an empty tail, which cannot orphan anything and is how a long agentic run stays bounded. Turning it into a no-op broke the shipped C6 acceptance case (`golang/agents/compaction_test.go`); the spec delta was corrected to match shipped behavior.
- [x] 3.3 Confirm the existing compaction tests still pass unchanged in all seven ports.

## 4. Ship

- [x] 4.1 `CHANGELOG.md` under `## Unreleased`: an Anthropic-style agent whose transcript compacted could produce a request the API rejects; it no longer can. Note the OpenAI path is unchanged.
- [x] 4.2 `openspec validate fix-compaction-tool-pair-dialect`.
