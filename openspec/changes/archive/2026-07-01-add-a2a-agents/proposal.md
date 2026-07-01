## Why

toolnexus unifies MCP · skills · native · HTTP · builtin behind one `Tool` and drives any model with
`client.run()`. **A2A (agent-to-agent)** is the missing edge: letting a toolnexus agent **call other
agents** and **be called by them**. It is not a new paradigm — it reuses the httpTool plumbing (call
out), the client loop (fulfil), and the existing hooks/telemetry (observe). Extend, don't fork.

## What Changes

Minimal, HTTP-based, and a **genuine subset of real A2A** (JSON-RPC 2.0, verified against the
`a2a-python` SDK) so a toolnexus agent interoperates with real A2A peers. Vocabulary is the spec's:
remote **agents** publish an **Agent Card** at `/.well-known/agent-card.json`; work is a **Task** with
a `status.state` (`submitted`/`working`/`completed`/`failed`/`canceled`). Wire = JSON-RPC over one
HTTP POST endpoint; methods **`SendMessage`** (submit) + **`GetTask`** (poll). **Submit→poll** model
(not blocking): send returns a Task id, then poll `GetTask` until a terminal state. No streaming, no
push notifications, no gRPC, no auth in core.

- **Outbound — call remote agents.** A remote agent's advertised skills become tools in your toolkit.
  Remote agents are a first-class tool source (the 6th, alongside mcp·skill·native·http·builtin).
  - **`agent({ card, headers?, timeout?, pollEvery? })`** — a factory returning an **Agent** object,
    the same compositional shape as `httpTool()` / `defineTool()`. Build each, then compose:
    `createToolkit({ agents: [codex, claude] })` — an array of Agent objects (mirrors `extraTools`).
    Defaults to empty (external endpoints, not auto-loaded). `${ENV}` `headers`, `timeout` ms, `pollEvery` ms.
  - Config: an equivalent **`agents`** block in the config file (mirrors `mcpServers` — `card` URL,
    `${ENV}` `headers`, `enabled`/`disabled` precedence, `timeout`). Merges with the option. Zero code.
  - Dynamic: **`toolkit.addAgent(agentOrCardUrl, opts?)`** at runtime — fetches the card and registers
    its skills as tools (reuses `toolkit.register(...)`).
  - Each remote skill → a tool named `sanitize(agent)_sanitize(skill)` (same rule as MCP, §0.2). Its
    `execute` does one **`SendMessage`** (JSON-RPC, non-blocking) to get a Task id, then **polls
    `GetTask`** every `pollEvery` until a terminal state, mapping the Task's artifacts/message text to
    a normal `ToolResult`. Reuses httpTool's `${ENV}` headers + timeout + non-2xx mapping; honors
    `ctx` cancel/timeout so a run-level abort stops the poll.
- **Inbound — be a remote agent.** `serve` is a **generic** agent-over-HTTP server, protocol-neutral;
  **A2A is an opt-in profile** via an `a2a` option (typed `A2AConfig`). It serves `GET
  /.well-known/agent-card.json` (card built from the toolkit's **skills**, not raw tools) and one
  JSON-RPC POST endpoint handling **`SendMessage`** (enqueue a Task, kick `client.run(task, toolkit)`
  async, return the `submitted` Task id) and **`GetTask`** (return the current Task by id). Task
  persistence is a **pluggable `TaskStore` adapter** (`get(id)` / `save(task)`) — the user chooses:
  a built-in **in-memory** store is the default, with **file** and **db/custom** adapters selectable
  via `a2a.store` (the-factory can plug a NATS/JetStream store the same way). Fulfilment uses
  **skills + MCP + builtins + native/http together**.
- **Observability/telemetry reuse (no new system):** outbound agent calls flow through
  `beforeTool`/`afterTool` hooks and appear in `RunResult.toolCalls` with `metadata
  {agent, taskId, state, polls, ms}`; inbound each Task produces a `RunResult` surfaced via a `serve`
  `onTask` callback (skill, caller, tokens, tools, turns, ms, outcome).
- **No auth/identity/crypto in core** — a pluggable `auth?` hook, no-op default; the-factory layers
  ed25519 / credit / roster on top. Card declares `capabilities.streaming:false`; streaming/push are
  a follow-up (forward-compatible — addable without breaking clients).
- Applies across **all five ports** (js/python/golang/java/csharp). `SPEC.md` gains an A2A section.

Already-present, reused (not re-implemented): `skillsDir: string | string[]` (multiple skills
folders), `toolkit.register(...)` (dynamic add), httpTool's request/header/timeout machinery (§7),
the client `hooks` + `RunResult` telemetry (§8).

## Capabilities

### New Capabilities
- `a2a-outbound`: remote agents as a tool source — the `agent()` factory, `agents:[]` option, config
  `agents` block, and `toolkit.addAgent()`; each advertised skill becomes a Tool that does JSON-RPC
  `SendMessage` → poll `GetTask`, reusing httpTool machinery + cancel/hook reuse.
- `a2a-inbound`: `serve` (generic agent-over-HTTP) plus the opt-in `a2a` profile — an Agent Card at
  `/.well-known/agent-card.json` built from skills, and a JSON-RPC endpoint handling `SendMessage` +
  `GetTask` backed by a **pluggable `TaskStore` adapter** (in-memory default; file/db/custom
  selectable), fulfilling tasks via `client.run`.

### Modified Capabilities
<!-- SPEC.md §2 (config parsing) gains the `agents` block; §3/§7/§8 are referenced. No archived
     capability specs to modify (openspec/specs/ is empty). -->

## Impact

- **SPEC.md** — new A2A section: outbound (`agent`/`agents`/`addAgent`, the SendMessage→GetTask tool
  contract, tool naming), inbound (`serve` + `a2a` profile, Agent Card = skills, JSON-RPC
  SendMessage/GetTask, in-memory task store), and the `agents` config block under §2. Pins the exact
  wire subset (methods, task states, well-known card path) so the five ports stay interoperable.
- **js/python/golang/java/csharp** — one A2A module per port (outbound tool + `agent`/`addAgent`;
  `serve` + `a2a` profile + task store), config parsing for `agents`, per-port tests using an
  in-process A2A server fixture (no sockets) as the fake peer.
- **Go CLI** — optional `toolnexus serve` subcommand (follow-up; not required for v1).
- **Dependencies** — none new; each port's stdlib HTTP client/server + JSON. Secrets via `${ENV}`
  headers, never logged.
- **Security** — the Agent Card exposes skills, never raw tools; `bash`/fs builtins stay private
  implementation. Auth is out of core (pluggable hook, no-op default).
