## 1. Contract and shared fixture (do first — everything else asserts against it)

- [ ] 1.1 Pin the observable contract in `SPEC.md §2`: `tools/call` carries a progress token; `timeout` bounds time since the LAST progress notification; a non-matching token never resets; a silent server is unchanged; cancellation still wins. State explicitly that the mechanism is per-port and unspecified.
- [ ] 1.2 Note in `SPEC.md §2` that an endlessly-reporting server is bounded by caller cancellation (§1 `Context`) and the run-level `timeoutMs` (§8), not by an MCP-specific cap.
- [ ] 1.3 Add the shared fixture `examples/mcp-progress-server.mjs`: a stdio MCP server exposing one slow tool with configurable total duration and progress interval, whose result reports `progressTokenReceived` and `notificationsSent`. Must run under plain `node` with the SDK already used by `examples/mcp.json`.
- [ ] 1.4 Document the fixture in `examples/README.md` alongside the existing `mcp.json` entry, including the exact assertions every port must make.

## 2. js (the proven no-op — fix first, it is the reference implementation)

- [ ] 2.1 Add the `onprogress` handler to the `callTool` options in `js/src/mcp.ts:203` so the SDK attaches a progress token and `resetTimeoutOnProgress: true` finally takes effect.
- [ ] 2.2 Add a test against the shared fixture covering all six spec scenarios.
- [ ] 2.3 Verify: `cd js && npm test`.

## 3. Ports with SDK progress support

- [ ] 3.1 python — pass `progress_callback=` to `session.call_tool` (`mcp_source.py:243`) and enforce the idle budget with our own watchdog, since `read_timeout_seconds` is a hard deadline (design D6). Verify: `cd python && python -m pytest -q`.
- [ ] 3.2 golang — set `req.Params.Meta.ProgressToken` (`mcp/types.go:167`), route `client.OnNotification` (`client/client.go:127`), and replace `context.WithTimeout` at `golang/mcp.go:302` with a cancellable context driven by a resettable timer (design D6). Verify: `cd golang && go build ./... && go vet ./... && go test -race ./...`.
- [ ] 3.3 java — attach the official SDK's progress handler to `client.callTool(req)` (`McpSource.java:646`) and reset the call budget. Verify: `cd java && ./gradlew test --no-daemon`.
- [ ] 3.4 csharp — pass `IProgress<ProgressNotificationValue>` to `CallToolAsync` in `McpSource.cs` and reset the call budget. Verify: `cd csharp && dotnet test`.

## 4. In-house MCP clients

- [ ] 4.1 elixir — generate a per-call progress token, handle `notifications/progress`, and reset the pending timer in `connection.ex:403` `put_pending`. Verify: `cd elixir && mix test` and `mix coveralls` (gate ≥ 95%).
- [ ] 4.2 clojure — generate a per-call progress token, handle `notifications/progress`, and extend the deadline in `mcp.cljc:326` `await-response!`. Verify: `cd clojure` and the port's usual suite plus the 5-mode exact-agree gate.

## 5. Cross-cutting correctness

- [ ] 5.1 Confirm token matching is enforced in every port: an unmatched `progressToken` must not reset the in-flight call's budget (spec scenario 5, design D4).
- [ ] 5.2 Confirm the existing `mcp-load-lifecycle` cancellation scenarios still pass unchanged in golang and python, where the deadline construct was reworked.
- [ ] 5.3 Confirm load-phase bounding (connect / `initialize` / `tools/list`) is untouched in all seven ports — this change is `tools/call` only.
- [ ] 5.4 Run every port against the shared `examples/` fixtures and confirm identical outcomes (the §0 conformance check).

## 6. Parity checklist (a port that cannot pass stays UNCHECKED — never silently dropped)

- [ ] 6.1 js
- [ ] 6.2 python
- [ ] 6.3 golang
- [ ] 6.4 java
- [ ] 6.5 csharp
- [ ] 6.6 elixir
- [ ] 6.7 clojure

## 7. Documentation

- [ ] 7.1 Add a `CHANGELOG.md` entry under `## Unreleased` written as what a user gets: a long-running MCP tool that reports progress no longer dies at 30s; note that `js`'s previous `resetTimeoutOnProgress` never functioned; name any port left on the hard deadline and where the gap is tracked.
- [ ] 7.2 Document the idle-timeout meaning of `timeout` in each per-port README that documents MCP config, and state the two escape hatches for an endlessly-reporting server.
- [ ] 7.3 Resolve or explicitly defer the three Open Questions in `design.md` (absolute cap; java/csharp mechanism verified by running, not just API surface; whether to rename `timeout`).
