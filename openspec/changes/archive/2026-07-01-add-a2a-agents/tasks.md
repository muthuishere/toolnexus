## 1. Contract (SPEC.md)

- [x] 1.1 SPEC §7A (outbound): `agent()`/`agents:[]`/config `agents`/`addAgent`, tool name
      `sanitize(agent)_sanitize(skill)`, `source:"a2a"`, `SendMessage`→poll `GetTask`, Task→ToolResult
      mapping, metadata `{agent,taskId,state,polls,ms}`.
- [x] 1.2 SPEC §7B (inbound): `serve(addr,{client,a2a})`, card at `/.well-known/agent-card.json` from
      skills, `capabilities.streaming:false`, JSON-RPC `SendMessage`+`GetTask`, `TaskStore` adapter
      (in-memory default + file/custom), `onTask`; task states + error codes pinned.
- [x] 1.3 SPEC §2: `agents` config block + reserved sibling keys (`builtins`/`agents`/`a2a`) note.

## 2. Outbound — reference port (JS) then parity

- [x] 2.1 js (reference): `agent()` + `agents:[]` + config `agents` + `addAgent()`; the `source:"a2a"` tool doing `SendMessage`→poll `GetTask`; 31 tests green
- [x] 2.2 python: ported (42 tests)
- [x] 2.3 golang: ported (go test -race)
- [x] 2.4 java: ported (suite green)
- [x] 2.5 csharp: ported (65 tests)
- [x] 2.6 Tests (all 5): skills→tools; success round-trip; failed/timeout → isError; ctx-cancel stops polling; enabled/disabled skip

## 3. Inbound — reference port (JS) then parity

- [x] 3.1 js (reference): `toolkit.serve(addr,{client,a2a})`; card from skills; `SendMessage`+`GetTask`; `TaskStore` + in-memory default; `onTask`; `stop()`; 39 tests
- [x] 3.2 python: ported (51 tests)
- [x] 3.3 golang: ported (go test -race)
- [x] 3.4 java: ported (50 tests)
- [x] 3.5 csharp: ported (72 tests)
- [x] 3.6 File `TaskStore` adapter (all 5) + host-supplied-store test
- [x] 3.7 Tests (all 5): card lists skills not raw tools; a2a absent → no routes; submit→poll to completed; fulfilment error → failed task, server survives

## 4. Round-trip, examples & parity

- [x] 4.1 Round-trip proven per port (each port's own outbound `agent()` → own `serve()` → `client.run` → artifact) in unit tests
- [x] 4.2 Interop: real-a2a-python-shaped methods (`SendMessage`/`GetTask`), task states, `/.well-known/agent-card.json` card path — pinned + round-tripped
- [x] 4.3 Ran all 5 suites independently — js 39, python 51, go (race), java 50, csharp 72 — green; source parity spot-checked. Deferred: streaming/push/auth, Go CLI `serve` subcommand
- [x] 4.4 Updated all 6 READMEs (+ golang/GUIDE.md): outbound `agent`/`agents`/`addAgent` (Go/C# use `Agent{...}` struct literals — idiomatic, no factory), inbound `serve`/`ServeAsync`+`a2a`, TaskStore adapters
- [ ] 4.5 Optional: add a shared `examples/` A2A "hello" agent fixture (per-port round-trip already covers behavior)

## 5. Wrap-up

- [ ] 5.1 `openspec validate add-a2a-agents` passes
- [x] 5.2 No auth/streaming/push in core; card `streaming:false`; secrets never logged
- [ ] 5.3 Open the PR (or fold into the release) with the change folder + code in one diff
