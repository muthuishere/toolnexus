## 1. Contract

- [x] 1.1 SPEC.md §3: state that discovery + the sibling-file sampler follow symlinked dirs/files, with a real-path cycle guard

## 2. Implement across all five ports

- [x] 2.1 js (reference): follow symlinked dirs/`SKILL.md` in the discovery walker + sibling-file sampler; realpath cycle guard; regression test (25 tests green)
- [ ] 2.2 python: same
- [ ] 2.3 golang: same
- [ ] 2.4 java: same
- [ ] 2.5 csharp: same

## 3. Verify

- [ ] 3.1 Each port's suite green; a temp-fixture test proves a symlinked skill dir is discovered
- [ ] 3.2 `openspec validate follow-symlinked-skills` passes
- [ ] 3.3 Confirm parity: same discovery behavior in all five ports (no drift)
