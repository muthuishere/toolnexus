# Tasks — streaming G3 parity

## Reference
- [x] 0.1 JS/Java/Python streaming loops already halt-on-first (verified) — they are the reference; no change.

## Fix the two divergent ports
- [ ] 1.1 Go — `golang/client.go` streaming loops (both OpenAI + Anthropic streaming variants): replace
      the last-write-wins `halted` with a first-in-order halt (emit `pending` event + `done`
      `RunResult{status:"pending"}` on the first halt, stop; no later-suspension leak). + streaming G3 test.
- [ ] 1.2 C# — `csharp/src/Toolnexus/LlmClient.cs` streaming loops (both variants): same fix. + test.
- [ ] 1.3 `go test -race ./...` and `dotnet test` green.

## Verify parity (unchanged ports)
- [ ] 2.1 JS/Java/Python suites still green (no change expected).
- [ ] 2.2 `openspec validate harden-streaming-suspension --strict`.
