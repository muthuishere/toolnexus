# Tasks — unify human/loop interaction on §10 Request

Status (2026-07-11): #1 (question→pending) + G1 (serve input-required) SHIPPED across all five
ports, all suites independently verified green (JS 59, Python 80, Go ok, Java BUILD SUCCESSFUL,
C# 100). G2/G3 + refinements deferred per owner (see §8).

## 0. Contract (spec-first)

- [x] 0.1 `SPEC.md` §4A `question` row rewritten to the suspension contract.
- [x] 0.2 `SPEC.md` §10 names `question` as the canonical `kind:"question"` producer + documents the
      non-normative recommended `{answers:[...]}` answer-data shape.
- [x] 0.3 `renderPrompt` — LOCKED & implemented byte-identically in all five: per question in order,
      `question` text (missing→`""`); if `options` non-empty, append `" (options: " + join(options, ", ") + ")"`;
      join with `"\n"`; no trailing newline; `header` NOT rendered. Canonical:
      `[{question:"Pick a color?",options:["red","green"]},{question:"Confirm?"}]` →
      `Pick a color? (options: red, green)\nConfirm?` (asserted byte-exact in every port).
- [x] 0.4 `SPEC.md` §10 security pin (R3): `kind:"question"`/`"input"` MUST NOT collect credentials;
      credential capture uses `kind:"authorization"` with `url`.

## 1. JS (`js/`)  — reference port (commit c7ed2c3)

- [x] 1.1 `src/builtin.ts` `questionTool` two-branch (`ctx.answer` → `ok(...)`; else `pending(...)`) +
      `renderQuestionPrompt`; description updated. Also `src/serve.ts` G1 (§8.1).
- [x] 1.2 Parity test: first call → `metadata.pending` kind `"question"` + `data.questions` + byte-exact
      prompt; re-exec with an answer → forwarded verbatim; no-`waitFor` client loop → `status:"pending"`.
- [~] 1.3 Path-B (beforeTool-raised pending) — verified working earlier by spike + code-trace; a
      dedicated lock test is deferred to the follow-up (the loop-honors-suspension path is exercised by
      the client-halt + serve-G1 tests). See §8.
- [x] 1.4 `npm test` green (59 passed).

## 2. Python (`python/`)

- [x] 2.1 `src/toolnexus/builtin.py` `_question_tool.run` two-branch + `_render_question_prompt`;
      `src/toolnexus/serve.py` `_fulfil` G1.
- [x] 2.2 Parity tests (`test_unit.py` suspend+resolve, `test_pending.py` client halt).
- [~] 2.3 Path-B dedicated lock test — deferred (as 1.3).
- [x] 2.4 `pytest -q` green (80 passed, verified in a clean venv).

## 3. Go (`golang/`)

- [x] 3.1 `builtin.go` question tool (`_`→`ctx` rename) two-branch + `renderQuestionPrompt`;
      `serve.go` `fulfil` G1.
- [x] 3.2 Parity tests (`builtin_test.go`, `pending_test.go` `TestQuestionNoWaitForHalts`).
- [~] 3.3 Path-B dedicated lock test — deferred (as 1.3).
- [x] 3.4 `go build ./... && go vet ./... && go test -race ./...` green.

## 4. Java (`java/`)

- [x] 4.1 `BuiltinTools.java` question tool two-branch + `renderQuestionPrompt`; `A2AServer.java`
      `fulfil` G1.
- [x] 4.2 Parity tests (`BuiltinToolsTest`, `PendingSuspensionTest`).
- [~] 4.3 Path-B dedicated lock test — deferred (as 1.3).
- [x] 4.4 `./gradlew test --no-daemon` green.

## 5. C# (`csharp/`)

- [x] 5.1 `BuiltinTools.cs` question tool (`_`→`ctx` rename) two-branch + `RenderQuestionPrompt`;
      `A2AServer.cs` `FulfilAsync` G1.
- [x] 5.2 Parity tests (`BuiltinToolsTests`, `PendingTests`).
- [~] 5.3 Path-B dedicated lock test — deferred (as 1.3).
- [x] 5.4 `dotnet test` green (100 passed).

## 6. Cross-language parity

- [x] 6.1 `renderPrompt` output byte-identical across all five (each asserts the canonical bytes).
- [x] 6.2 Suspended `Request` shape (`kind`, `data.questions`) identical across ports.
- [x] 6.3 `openspec validate unify-request-interaction --strict`.

## 7. Docs / notes

- [x] 7.1 Breaking-change note recorded (proposal.md — hosts reading `metadata.questions` off an
      immediate result move to the `waitFor` seam). READMEs only list `question`; no edit needed; the
      unrelated "Answers research questions" custom-tool example lines untouched.
- [x] 7.2 Break list — the five OLD-contract unit tests rewritten to the suspension contract in every
      port (todowrite half preserved).

## 8. Suspension-layer hardening — SHIPPED G1; G2/G3/refinements DEFERRED (owner: "Implement #1 + G1")

- [x] 8.1 (G1) `serve()` maps a suspended run → A2A `input-required` (carrying `pending.prompt`) instead
      of a false `completed`, in ALL FIVE ports (JS `serve.ts`, Py `serve.py`, Go `serve.go`, Java
      `A2AServer.java`, C# `A2AServer.cs`) + a served-agent regression test each. Pinned in SPEC §10.
- [ ] 8.2 (G2, DEFERRED) pending result counted as a tool error in metrics + fires `afterTool`
      (`client.ts:416` + ports). → follow-up `harden-suspension-layer`.
- [ ] 8.3 (G3, DEFERRED, narrow) concurrent suspensions with no `waitFor` drop all but one
      (`client.ts:472,480` + ports). → follow-up `harden-suspension-layer`.
- [ ] 8.4 (Refinements, DEFERRED) R1 three-state `Answer.reason`, R2 optional `Request.schema`,
      + the dedicated path-B lock test (1.3/2.3/3.3/4.3/5.3). → follow-up. (R3 security pin already
      shipped in 0.4.)

### KNOWN LIMITATIONS until the follow-up lands
- A `question`/other suspension still increments the `is_error` tool metric and runs `afterTool` as
  a failure (G2) — an error-rate circuit-breaker may count questions. Cosmetic to metrics only; the
  model does not see the error (the loop's `pendingOf` check shields it).
- Two suspensions in one turn *with no `waitFor`* surface only one on `RunResult.pending` (G3). With a
  `waitFor` configured, each resolves inline — no loss.
