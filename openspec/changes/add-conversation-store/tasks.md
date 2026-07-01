## 1. Contract

- [x] 1.1 SPEC.md §8: `ConversationStore` (get/save) + in-memory default, `create_client(store)`,
      `ask(id?)` semantics (id ⇒ stateful, none ⇒ one-shot), and the A2A serve→`contextId` integration.

## 2. Implement across all five ports (ask + store + serve wiring + tests)

- [x] 2.1 js (reference): `ConversationStore` + `InMemoryConversationStore` + `store` opt + `ask`; serve threads `contextId`; 43 tests green
- [x] 2.2 python: ported (60 tests)
- [x] 2.3 golang: ported (go test -race)
- [x] 2.4 java: ported (59 tests)
- [x] 2.5 csharp: ported (81 tests)
- [x] 2.6 Tests (all 5): ask no-id ⇒ stateless; same id remembers; custom store get/save; serve remembers by contextId

## 3. Verify & release

- [x] 3.1 All five suites green; parity spot-checked (ask + store + serve-contextId wiring in each)
- [ ] 3.2 `openspec validate add-conversation-store` passes
- [ ] 3.3 Bump 0.3.0, PR, CI green, merge, archive, release
