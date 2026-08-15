## 1. Contract, gate, and shared fixture (do first — everything else asserts against these)

- [ ] 1.1 Pin the canonical message shape in `SPEC.md § Conversation memory`: the four kinds, their fields, the normative serialized field names a `ConversationStore` writes, the at-most-one-leading-`system` invariant, and the rule that opaque provider content survives a same-style render and is dropped on a cross-style one.
- [ ] 1.2 Pin the render/parse table for both styles in `SPEC.md` (system placement, tool-call encoding, tool-result carrier), since it is the cross-port porting obligation.
- [ ] 1.3 Pin the format marker and the legacy read rules — upgrade a matching unmarked transcript, fail loudly on a foreign-dialect one (design D1).
- [ ] 1.4 Add the shared fixture: a cross-style resume case under `examples/` whose transcript **contains a tool call and its result** — the case the spike proved broken — with the exact assertions every port must make (no `tool_calls`/`role:"tool"` reaching Anthropic; no `role:"system"` inside `messages` under Anthropic).
- [ ] 1.5 Establish the byte-identity gate (design D5) per port: capture today's request bodies for a same-style run with and without tool calls, and assert the post-change bodies match. This is the regression guard for the whole change.

## 2. js (reference implementation — land first)

- [ ] 2.1 Introduce the canonical message type and the two render/parse pairs in `js/src/client.ts`, replacing the wire-shaped accumulation in both style branches (`:815` Anthropic system hoist + `:869-872` `tool_result` push; `:889-891` OpenAI in-array `system`).
- [ ] 2.2 Make `RunResult.messages`, `run(..., {history})`, and the `ask` store read/write all canonical (design D2).
- [ ] 2.3 Implement the format marker plus legacy read/upgrade/fail (design D1).
- [ ] 2.4 Update `js/src/agents/` compaction to operate on canonical messages.
- [ ] 2.5 Tests: byte-identity gate (1.5), the shared cross-style fixture (1.4), both legacy paths, and the at-most-one-`system` invariant. Verify: `cd js && npm test`.

## 3. Remaining ports (same tasks as §2, per port)

- [ ] 3.1 python — `python/src/toolnexus/client.py` + `agents/compaction.py`. Verify: `cd python && python -m pytest -q`.
- [ ] 3.2 golang — `golang/client.go` + `golang/agents/compaction.go`. Verify: `cd golang && go build ./... && go vet ./... && go test -race ./...`.
- [ ] 3.3 java — `java/.../LlmClient.java` + `agents/`. Verify: `cd java && ./gradlew test --no-daemon`.
- [ ] 3.4 csharp — `csharp/src/Toolnexus/LlmClient.cs` + `Agents/Compaction.cs`. Verify: `cd csharp && dotnet test`.
- [ ] 3.5 elixir — `elixir/lib/toolnexus/client.ex` + `agents/compaction.ex`. Verify: `cd elixir && mix test` and `mix coveralls` (gate ≥ 95%).
- [ ] 3.6 clojure — `clojure/src/toolnexus/client.cljc`. Verify: the port's suite plus the 5-mode exact-agree gate.

## 4. Cross-cutting correctness

- [ ] 4.1 Confirm the persisted serialization is identical across all seven ports: write a transcript with one port's store, read it with another's, resume successfully.
- [ ] 4.2 Confirm A2A `serve` (§7B) resume-by-`contextId` still works and now survives a style change, in every port that ships `serve`.
- [ ] 4.3 Confirm the streaming event contract is untouched — this change is the message channel only.
- [ ] 4.4 Confirm suspension/resume (§10 `askWithAnswer`) round-trips through the canonical form, since it loads and saves through the same store.
- [ ] 4.5 Confirm the balance invariant on the durable-halt path. Scope note: the durable **halt** (`status:"pending"`) ships in all seven ports, so the imbalanced transcript is written in all seven; the answer-carrying **resume** entry point is `golang` only, a stated preview (`SPEC.md:1501`; `RunWithAnswer`/`AskWithAnswer` at `golang/relay.go:380,397`). Fix the write in seven, verify the replay in Go plus routsi's shape (durable host, replays history, OpenAI→Anthropic translation).
- [ ] 4.6 Fix the dialect-blind compaction boundary, which the canonical form is what makes statable. `golang/agents/compaction.go:100-101` claims "a tail starting at a user message can never orphan a `tool` result" — true in the OpenAI dialect, **false under Anthropic**, where tool results are appended as `user` messages (`golang/client.go:1256`). Restate the `SPEC.md:961-964` tool-pair invariant over canonical kinds instead of OpenAI roles, and re-verify each port's tail scan. ADR 0013 deferred this to a separate ADR; it belongs here because a `toolResult` kind is what removes the ambiguity.
- [ ] 4.6 Land ADR 0015 decision rule 3 alongside: `SPEC.md §8`'s `ConversationStore` gains the sentence that `save` SHALL be atomic per id — a reader observes either the previous transcript or the new one, never a prefix. Documentation only; no port code changes.
- [ ] 4.7 Notify the ADR 0015 / 0013 authors when this archives — `add-compaction-observability` and the tool-result pruning stage are both sequenced behind it.
- [ ] 4.8 Resolve design Q1 (a `renderFor(style)` compatibility accessor on `RunResult.messages`) — decide in review, then either implement in all seven ports or record the rejection in `design.md`.

## 5. Ship

- [ ] 5.1 `CHANGELOG.md` under `## Unreleased`: lead with what the user gets — persisted conversations are now portable across providers and ports, and a dialect mismatch that used to produce a provider `400` now fails with a named error. State the narrow break (hosts reading `RunResult.messages` as a wire shape) and the legacy-transcript behaviour.
- [ ] 5.2 Update the per-language READMEs where they show `ConversationStore` or `RunResult.messages`.
- [ ] 5.3 `openspec validate add-canonical-transcript`.
