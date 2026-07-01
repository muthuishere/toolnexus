## 1. Spec & contract

- [x] 1.1 Add a "Built-in tools" section to `SPEC.md` (§4A): 11 tool names + exact input
      schemas, on-by-default rule, single global `builtins` toggle (MCP `isEnabled` precedence),
      and behavioral contracts (bash workdir/timeout, edit exact-replace, apply_patch grammar, websearch stub).
- [x] 1.2 Add toolkit-assembly note to `SPEC.md` (§4): order MCP → skill → builtin → extraTools, first-wins dedupe.
- [x] 1.3 Resolved the three open questions in SPEC.md: websearch = no-provider stub (isError);
      question = returns JSON of questions (non-interactive); apply_patch = full opencode Begin/End Patch grammar.
- [x] 1.4 SPEC.md §0.11 + §4A: builtin/MCP tools schema-surfaced only (no builtins prompt section);
      §3/§0.6 add the `skillsPrompt()` instruction preamble contract (byte-identical text).

## 2. Tier A — source scaffold, toggle, assembly, read/write/edit (all four ports)

- [x] 2.1 js: builtin source module + `builtins` config parse (enabled/disabled) + toolkit wiring; implement `read`, `write`, `edit` (done — full toolset, 22 tests green)
- [x] 2.2 python: same as 2.1 (done — 33 tests green)
- [x] 2.3 golang: same as 2.1 (done — go test -race green)
- [x] 2.4 java: same as 2.1 (done — gradle build/test green)
- [x] 2.5 Tests (all four): default assembly includes builtins; toggle-off removes them; name-collision dedupe; read/write/edit round-trip + `replaceAll` + not-found → isError
- [x] 2.6 Skills-prompt preamble (all four): prepended to `skillsPrompt()`; byte-identical text verified across ports; no preamble when no skills; builtins add no prompt text (schema-only)

## 3. Tier B — bash, grep, glob (all four ports)

- [x] 3.1 js: implement `bash` (workdir/timeout), `grep` (regex + include/limit), `glob` (pattern/path/limit) (done)
- [x] 3.2 python: same as 3.1 (done)
- [x] 3.3 golang: same as 3.1 (done)
- [x] 3.4 java: same as 3.1 (done)
- [x] 3.5 Tests (all four): bash exit/timeout → ToolResult; grep/glob match counts on temp fixtures; no secret/output logging

## 4. Tier C — webfetch, websearch, apply_patch, todowrite, question (all four ports)

- [x] 4.1 js: implement `webfetch` (format/timeout), `websearch` (per decision 1.3), `apply_patch`, `todowrite`, `question` (done)
- [x] 4.2 python: same as 4.1 (done)
- [x] 4.3 golang: same as 4.1 (done)
- [x] 4.4 java: same as 4.1 (done)
- [x] 4.5 Tests (all four): webfetch (localhost); websearch unconfigured → isError; apply_patch add/update/delete + atomic abort; todowrite/question round-trip

## 5. Cross-port parity & examples

- [x] 5.1 Verified live against the real 21-file `video-transcribe` skill: skill loads + builtin read/bash/grep operate on its files (per-port unit tests cover builtins hermetically with temp dirs)
- [x] 5.2 Ran each port's suite independently — js 24, python 35, go (race) ok, java 13, csharp 58 — all green; parity spot-checked (preamble byte-identical, 11 names, select helper) across 5 ports
- [~] 5.3 READMEs updated for builtins + toggle (per-tool map + C# section docs pass in progress)

## 7. Per-tool enable/disable (`builtins.tools` map)

- [x] 7.1 js: `selectBuiltins()` applies a `tools` name→bool map on the all-on baseline (whole-source-off wins); 24 tests green
- [x] 7.2 python: `select_builtins()` + tests (35 passed)
- [x] 7.3 golang: `SelectBuiltins()` + tests (go test -race green)
- [x] 7.4 java: `BuiltinTools.select()` + tests (13 passed)
- [x] 7.5 Tests (all): `{tools:{bash:false,write:false}}` drops those two, keeps the other nine; unknown name ignored; disabled:true short-circuits the map

## 8. C# port parity (repo grew a fifth port)

- [x] 8.1 csharp: builtin source (11 tools, source "builtin"), toggle + per-tool map, MCP→skill→builtin→extra assembly + first-wins dedupe
- [x] 8.2 csharp: skills-prompt instruction preamble (byte-identical string verified across 5 ports)
- [x] 8.3 csharp: tests mirroring the JS suite; `dotnet test` green (58 passed)
- [~] 8.4 csharp: README "Built-in tools" section (docs pass in progress)

## 6. Wrap-up

- [x] 6.1 `openspec validate add-builtin-tools` passes
- [x] 6.2 Parity confirmed across all FIVE ports (js/python/golang/java/csharp); deferred: `websearch` provider (ships as no-provider stub), `apply_patch` fuzzy-context matching (exact-substring only); no per-port drift
- [ ] 6.3 Open the PR with the change folder + code in one diff (awaiting user go-ahead)
