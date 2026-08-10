## 1. Contract first (SPEC.md)

- [ ] 1.1 Add `"builtin"` to the `source` enum in `SPEC.md §0.1` and `§1` (Tool.source)
- [ ] 1.2 Add a `SPEC.md §0` conformance line: the five built-in tools, default-on, `defaultTools`
      switch, root confinement
- [ ] 1.3 Add new `SPEC.md §11 Built-in tools` — per-tool args, byte-exact output/error, schemas,
      sorting/relative-path rules (mirror specs/builtin-tools/spec.md)
- [ ] 1.4 `openspec validate add-default-builtin-tools --strict` passes

## 2. js/ (reference implementation)

- [ ] 2.1 `js/src/builtin.ts` — `read`/`write`/`execute`/`grep`/`glob` as `defineTool` with `source:"builtin"`, `rootDir` confinement (realpath + startsWith)
- [ ] 2.2 `ToolkitOptions.defaultTools` (`boolean | {rootDir?, exclude?}`); register built-ins last in the `Toolkit` constructor
- [ ] 2.3 `js/test/builtin.test.ts` — temp-dir tree; byte-exact per tool; traversal refused; exit/timeout suffix; disable + exclude
- [ ] 2.4 `npm run build && npm test` green

## 3. python/

- [ ] 3.1 `python/src/toolnexus/builtin.py` mirroring js behavior (os / subprocess / re / glob)
- [ ] 3.2 `defaultTools` on the toolkit factory; register last
- [ ] 3.3 `python/tests/test_builtin.py`
- [ ] 3.4 `python -m pytest -q` green

## 4. golang/

- [ ] 4.1 `golang/builtin.go` (os / os/exec / regexp / path/filepath) with `source:"builtin"`
- [ ] 4.2 `DefaultTools` option on the toolkit; register last
- [ ] 4.3 `golang/builtin_test.go`
- [ ] 4.4 `go build ./... && go vet ./... && go test -race ./...` green

## 5. java/

- [ ] 5.1 `Builtin*.java` (java.nio / ProcessBuilder / regex / PathMatcher)
- [ ] 5.2 `defaultTools` on `Toolkit.create`; register last
- [ ] 5.3 `SkillSourceTest`-style `BuiltinToolTest`
- [ ] 5.4 `./gradlew build --no-daemon` green

## 6. csharp/

- [ ] 6.1 `Builtin*.cs` (System.IO / System.Diagnostics.Process / Regex / Matcher)
- [ ] 6.2 `defaultTools` on the toolkit; register last
- [ ] 6.3 `BuiltinToolsTests.cs`
- [ ] 6.4 `dotnet test` green

## 7. Parity + docs

- [ ] 7.1 Cross-port parity check: same temp tree fixture ⇒ identical `read`/`write`/`grep`/`glob` bytes in all five ports
- [ ] 7.2 Update each README's tool-sources section to mention the default built-ins + how to disable
- [ ] 7.3 Confirm CI (`.github/workflows/ci.yml`) exercises the new suites hermetically (no network, no real-cwd writes)

## 8. Parity checklist (per behavior — do not let drift land silently)

- [ ] Default-on registration: js · python · golang · java · csharp
- [ ] `read` byte-exact: js · python · golang · java · csharp
- [ ] `write` byte-exact: js · python · golang · java · csharp
- [ ] `execute` exit/timeout suffix: js · python · golang · java · csharp
- [ ] `grep` sorted `path:line:text`: js · python · golang · java · csharp
- [ ] `glob` sorted relative paths: js · python · golang · java · csharp
- [ ] Root confinement + traversal refusal: js · python · golang · java · csharp
- [ ] `defaultTools:false` / `exclude`: js · python · golang · java · csharp
