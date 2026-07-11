# Tasks — unify human/loop interaction on §10 Request

## 0. Contract (spec-first)

- [ ] 0.1 Rewrite `SPEC.md` §4A `question` row: from "returns `ToolResult{isError:false, output:<JSON>,
      metadata:{questions}}` for the host to answer" to "**suspends** — returns
      `pending({kind:"question", prompt:<rendered>, data:{questions}})`; on `waitFor` resolution
      (`answer.ok`) re-executes and returns `ctx.answer.data`; with no `waitFor`, halts durably."
- [ ] 0.2 `SPEC.md` §10: name `question` as the canonical `kind:"question"` producer alongside
      `authorization`; add the (non-normative) recommended answer-data shape for `kind:"question"`.
- [ ] 0.3 `renderPrompt` — LOCKED (design.md): per question in order, `question` text (missing→`""`);
      if `options` non-empty, append `" (options: " + join(options, ", ") + ")"`; join with `"\n"`;
      no trailing newline; `header` NOT rendered. Canonical: `[{question:"Pick a color?",
      options:["red","green"]},{question:"Confirm?"}]` → `Pick a color? (options: red, green)\nConfirm?`.
- [ ] 0.4 `SPEC.md` §10 security pin (huddle R3): `kind:"question"`/`"input"` MUST NOT collect
      credentials; credential capture uses `kind:"authorization"` with `url` (secrets stay out-of-band).

## 1. JS (`js/`)

- [ ] 1.1 `src/builtin.ts` `questionTool`: read `ctx?.answer` → if present return `ok(JSON.stringify(ctx.answer.data ?? {}))`;
      else return `pending({ kind:"question", prompt: renderPrompt(questions), data:{questions} })`.
      Update the tool description to say it suspends.
- [ ] 1.2 Parity test: `question` first call yields `metadata.pending` with `kind:"question"` +
      `data.questions`; with a `waitFor` returning `{ok:true, data:{...}}` the run resolves and the
      tool output carries the answer; with no `waitFor` the run ends `status:"pending"`.
- [ ] 1.3 Path-B regression test: a `beforeTool` hook returning `{result: pending({kind:"approval"})}`
      is honored by the loop → `waitFor` called / durable halt (no tool code runs).
- [ ] 1.4 `cd js && npm test` green.

## 2. Python (`python/`)

- [ ] 2.1 `src/toolnexus/builtin.py` question run (`~477`): mirror the two-branch logic using the
      `pending(...)` helper and `ctx.answer`.
- [ ] 2.2 Parity test (same three assertions as 1.2).
- [ ] 2.3 Path-B regression test (same as 1.3).
- [ ] 2.4 `cd python && python -m pytest -q` green.

## 3. Go (`golang/`)

- [ ] 3.1 `builtin.go:706` question tool: rename discarded `_ *ToolContext` → `ctx`; mirror the
      two-branch logic (`Pending(Request{Kind:"question", Prompt:…, Data: map[string]any{"questions":…}})`
      / `ctx.Answer`); keep idiomatic Go shape.
- [ ] 3.2 Parity test (same three assertions).
- [ ] 3.3 Path-B regression test.
- [ ] 3.4 `cd golang && go test -race ./...` green.

## 4. Java (`java/`)

- [ ] 4.1 `src/main/java/io/github/muthuishere/toolnexus/BuiltinTools.java` question tool (`~536`):
      mirror the two-branch logic using the `pending(...)` helper + `ctx.answer()`.
- [ ] 4.2 Parity test (same three assertions).
- [ ] 4.3 Path-B regression test.
- [ ] 4.4 `cd java && ./gradlew test --no-daemon` green.

## 5. C# (`csharp/`)

- [ ] 5.1 `src/Toolnexus/BuiltinTools.cs:574` question tool: rename discarded `(args, _)` → `(args, ctx)`;
      mirror the two-branch logic using `ToolResult.Pending(new Request{Kind="question", …})` +
      `ctx.Answer` (still `Task.FromResult(...)`).
- [ ] 5.2 Parity test (same three assertions).
- [ ] 5.3 Path-B regression test.
- [ ] 5.4 `cd csharp && dotnet test` green.

## 6. Cross-language parity

- [ ] 6.1 Confirm `renderPrompt` output is byte-identical across all five (same join, same
      option-rendering) — it is part of `Request.prompt`.
- [ ] 6.2 Confirm the suspended `Request` for the shared `examples/` question is identical in shape
      (`kind`, `data.questions`) across ports.
- [ ] 6.3 `openspec validate unify-request-interaction --strict`.

## 7. Docs / notes

- [ ] 7.1 Add the breaking-change note (hosts reading `metadata.questions` off an immediate result
      must move to the `waitFor` seam) to the change. READMEs only *list* `question` (no return-shape
      claim) — no edit required; do NOT touch the unrelated "Answers research questions" custom-tool
      example lines (`js/README.md:292`, `python/README.md:262`, `csharp/README.md:295`,
      `golang/README.md:368`, `java/README.md:378`).
- [ ] 7.2 Break list — five existing unit tests assert the OLD contract (`isError:false`, output ==
      JSON of questions, `metadata.questions`); rewrite each to the suspension contract (they combine
      `question`+`todowrite` — keep the todowrite half): `js/test/unit.test.ts:743`,
      `python/tests/test_unit.py:578`, `golang/builtin_test.go:415`,
      `java/.../BuiltinToolsTest.java:324`, `csharp/.../BuiltinToolsTests.cs:381`.

## 8. Suspension-layer hardening (SCOPE DECISION — see design.md "Scope fork")

The huddle confirmed three pre-existing §10-layer gaps this change *exposes* (it makes a common
model-initiated builtin suspend). Owner decides bundle-vs-defer. If deferred, file a separate
`harden-suspension-layer` change and add a KNOWN-LIMITATION note here.

- [ ] 8.1 (G1, A2A footgun) `serve()` `fulfil` marks a suspended run `completed` instead of emitting
      A2A `input-required`. Branch on `result.status === "pending"` → `input-required` + stash
      `result.pending`, in ALL FIVE ports' serve equivalents (JS `serve.ts:333-339` confirmed). Add a
      served-agent pending regression test.
- [ ] 8.2 (G2) `runTool` emits `isError:true` metric + runs `afterTool` for a pending result before
      the `pendingOf` check (JS `client.ts:416`). Tag pending events distinct from `is_error` so
      questions don't trip error-rate breakers. Five ports.
- [ ] 8.3 (G3, narrow) concurrent suspensions in one turn with no `waitFor` drop all but one
      (JS `client.ts:472,480` last-write-wins). Surface `pending: Request[]` or halt on first-in-order.
      Five ports. (WITH `waitFor` each resolves inline — no loss.)
- [ ] 8.4 (R1/R2/R3 refinements, optional) three-state `Answer.reason`, optional `Request.schema`,
      SPEC security pin (0.4 already covers R3). Land with §8 or as their own change.
