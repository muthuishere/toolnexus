## Context

toolnexus unifies MCP · skills · native · http · builtin behind one `Tool` and runs `client.run()`.
A2A adds two edges: call remote agents (outbound) and be a remote agent (inbound). Verified against
the `a2a-python` SDK (Downloads/a2a-python-main): the real wire is **JSON-RPC 2.0 over one HTTP POST
endpoint**, the Agent Card lives at **`/.well-known/agent-card.json`**, and a **blocking or
submit→poll** task lifecycle is supported. We build the **submit→poll** subset (better for
long-running agent work), reusing httpTool's HTTP machinery (§7) and the client's hooks/telemetry (§8).

## Goals / Non-Goals

**Goals:**
- Outbound + inbound A2A as a genuine, interoperable **subset** of real A2A (JSON-RPC
  `SendMessage` + `GetTask`, real Task/Message/Part/Artifact shapes, well-known card path).
- Submit→poll (send returns a Task id; poll `GetTask` to a terminal state). Pluggable `TaskStore`.
- 5-port byte-parity; hermetic tests via an in-process A2A server fixture (no sockets).

**Non-Goals (deliberately deferred):**
- Streaming (`SendStreamingMessage`/SSE), push notifications, gRPC/HTTP+JSON transports.
- Auth/identity/crypto in core (a no-op `auth?` hook; the-factory layers ed25519/credit on top).
- The Go CLI `serve` subcommand (follow-up).

## The wire subset (pinned from a2a-python)

- **Card:** `GET /.well-known/agent-card.json` → `{ name, description, version, protocolVersion,
  provider?:{organization,url}, capabilities:{streaming:false,pushNotifications:false},
  defaultInputModes, defaultOutputModes, skills:[{ id, name, description, tags?, examples? }], url }`.
  `skills` are built from the toolkit's agent-skills (SKILL.md `name`+`description`); `url` is the
  JSON-RPC endpoint.
- **Card config (declarative + inline).** The card's identity is configured either inline via the
  `serve(addr, { client, a2a })` option or a top-level **`a2a` block in the config file** (mirrors the
  `agents` block; merged, inline wins). `A2AConfig` fields: `name`, `description`, `version?`,
  `provider?`, `skills?` (subset of the toolkit's skill ids — omit to advertise all), `store?`
  (`"memory"` default | `"file:<dir>"` | a custom `TaskStore`). `capabilities.streaming` is fixed
  `false` in v1. `a2a` is thus a reserved sibling config key alongside `agents`/`builtins` (§2).
- **Transport:** JSON-RPC 2.0, `POST <url>`, `{jsonrpc:"2.0", method, params, id}` →
  `{jsonrpc:"2.0", result|error, id}`.
- **Methods:** `SendMessage` (params carry a `message:{role:"user", parts:[{kind:"text",text}]}` and a
  non-blocking configuration) → `result` a **Task** `{id, status:{state:"submitted"}, ...}`; `GetTask`
  (params `{id}`) → the current **Task** `{id, status:{state}, artifacts:[{parts:[{kind:"text",text}]}],
  history?}`.
- **TaskState (JSON strings):** `submitted`, `working`, `completed`, `failed`, `canceled`. Terminal =
  completed/failed/canceled. (input-required/auth-required exist in the spec; v1 treats any non-terminal
  as "keep polling", any terminal-non-completed as `isError`.)

## Decisions

**1. Submit→poll over blocking.** `SendMessage` returns immediately with a Task id; the outbound tool
polls `GetTask` every `pollEvery` (default 1000ms) until terminal or `timeout` (default 300000ms).
Rationale: agent tasks are long-running; blocking a tool call for minutes is worse UX and can't stream
progress. Blocking is a strict subset we can add later. The `ctx` signal aborts the poll.

**2. Map Task → ToolResult.** On `completed`: `output` = concatenated text of the Task's
`artifacts[].parts[]` (fallback to the final agent `history` message); `isError:false`. On
`failed`/`canceled` or poll timeout: `isError:true` with the state/message. `metadata` =
`{agent, taskId, state, polls, ms}` — same pattern as MCP `server` / HTTP `status`.

**3. Pluggable `TaskStore` adapter.** Interface: `get(id) -> Task|undefined`, `save(task)`. Ships an
**in-memory** default; **file** and **db/custom** are user-selectable via `a2a.store`. Rationale: the
user asked to choose (memory/file/db); the-factory plugs a NATS/JetStream store through the same
interface. Inbound `SendMessage` creates+saves a `submitted` Task, kicks `client.run` async (updates
the Task to `working`→`completed`/`failed` via `save`), and returns the id; `GetTask` reads via `get`.

**4. Reuse, don't rebuild.** Outbound uses httpTool's `${ENV}` header expansion + timeout + non-2xx
mapping and the port's HTTP client. Inbound uses each port's minimal stdlib HTTP server. Agent tools
are ordinary `Tool`s (`source:"a2a"`), so hooks + `RunResult` telemetry apply for free. A served Task
runs the normal loop, so skills + MCP + builtins all fulfil it.

**5. Card from skills, never raw tools.** The card advertises the toolkit's skills (public API);
`bash`/fs builtins stay private implementation. Security + correct A2A granularity (peers send tasks,
not tool calls).

## Risks / Trade-offs

- [Interop drift from real A2A] → pin method names/task states/card path in SPEC.md from the SDK; add a
  parity test that a real-A2A-shaped request/response round-trips. Keep `capabilities.streaming:false`
  so peers don't expect SSE.
- [Poll storm / never-terminal task] → cap by `timeout`; honor `ctx` cancel; default `pollEvery` 1000ms.
- [Async fulfilment errors on the serve side] → the background `client.run` wraps failures into a
  `failed` Task (never crashes the server); `onTask` surfaces outcome.
- [Port HTTP-server variance] → keep the inbound surface tiny (2 routes) and stdlib-only; parity test
  drives an in-process server (ASGI-transport-style / httptest / MockWebServer) with no open sockets.

## Testing (hermetic, per port)

Mirror a2a-python's pattern: stand up the A2A server **in-process** (no sockets) — Node
http server on an ephemeral port / Python `httpx.ASGITransport`-style / Go `httptest.Server` / Java
MockWebServer / C# `WebApplicationFactory` or in-proc `HttpListener` — with an in-memory store and a
canned toolkit, then drive the outbound `agent` tool against it. Assert: card fetch → skills; a skill
tool call → `SendMessage` → poll `GetTask` → `completed` Task → ToolResult text; `failed` → isError;
`ctx` cancel stops polling. A shared fixture (a "hello" A2A agent) is added under `examples/` so every
port tests the same round-trip.

## Open Questions

- **File store format** — one JSON file per task vs a single JSON map? Lean per-task JSON file (simple,
  concurrent-safe-ish); confirm during apply.
- **`serve` base (non-A2A) surface** — do we ship the plain `POST /messages` + streaming now, or only
  the `a2a` profile in this change and add the generic surface later? Lean: ship `a2a` profile only in
  v1 (that's the ask), keep `serve` minimal.
