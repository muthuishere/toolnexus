# ADR 0003 — MCP host: lifecycle, liveness, and least-privilege launch

- **Status:** Deferred, except M4 (2026-07-14) — M1–M3 are parked until a resident-agent
  consumer files a concrete need (per this ADR's own honesty note: latent, not observed).
  **M4 (least-privilege stdio env) is accepted for promotion on its own** — it is a real
  security gap today, contradicts the workspace's standing secrets rule, and is cheap;
  promote it as a standalone OpenSpec change without waiting on M1–M3.
- **Date:** 2026-07-14
- **Driver:** a structural comparison of the toolnexus MCP source (`js/src/mcp.ts`, mirrored in
  `golang/mcp.go` + python/java/csharp; `SPEC.md §2`) against a mature, long-lived MCP **host** —
  VS Code's `src/vs/workbench/contrib/mcp/` contribution. toolnexus's MCP plumbing is clean where
  it exists (bounded connect/list, cursor-safe pagination, failure isolation, env-safe headers),
  but it treats a server connection as a **one-shot load**: connect every server up front, list
  its tools once, and never touch the transport's health or tool set again. That is correct for a
  short CLI run and fragile for a long-lived agent. This ADR records the four gaps that separate
  "fine for a script" from "safe for a resident agent," each with the additive API to close it.
- **Honesty note (how this differs from ADR 0001/0002):** ADR 0001's gaps were verified against
  rag_go's production workarounds. This ADR is **reference-architecture-verified, not
  consumer-verified**: every toolnexus-side fact is line-cited against the real source, and every
  "better" is line-cited against VS Code's real source — but no toolnexus consumer has yet filed a
  crash report for these. They are latent, not observed. Treat the priority order as
  impact-ranked engineering judgement, not incident-driven. M1 and M2 are correctness/robustness;
  M3 and M4 are efficiency/security hardening.
- **Process note:** as with ADR 0001/0002, each confirmed gap flows through an OpenSpec change
  (`/opsx:propose`) with `SPEC.md §2` deltas where the cross-language contract moves (M1, M2, M4
  move it; M3 is a per-port idiom over the same observable contract), a per-language parity
  checklist, and a shared `examples/` fixture where behavior must be byte-identical. All proposals
  are **additive and backward-compatible**: a consumer calling `loadMcp(config)` and nothing else
  gets today's behavior unchanged.

## Context

Today the entire MCP source is reachable as one call:

```ts
// js/src/mcp.ts
export async function loadMcp(input, opts?): Promise<McpSource>   // :262
//   → { tools: Tool[]; status: Record<string,McpStatus>; close() }
```

`loadMcp` fans out over every enabled server with `Promise.all` (`mcp.ts:273`, Go
`mcp.go:511`/`wg` `:536`), and for each: `connectServer` opens a transport once (`mcp.ts:228`),
`paginateTools` lists the tools once (`mcp.ts:171`), `convertTool` wraps each as a uniform `Tool`
(`mcp.ts:194`), and the client is stashed in a slice held until `close()`. After that first pass
there is **no code path that observes the transport again** — no reconnect on a dropped stdio
pipe, no handler for `notifications/tools/list_changed`, no lazy start, and every stdio server is
launched with the **entire** parent environment (`mcp.ts:251` `{ ...process.env }`, Go `mcp.go:404`
`os.Environ()`). VS Code, by contrast, models a connection as a small state machine it can start,
stop, restart, and gate. Priority order (highest impact first): **M1 connection lifecycle → M2
live tool list → M3 lazy start → M4 least-privilege env.**

---

## Gap M1 — A connection is one-shot; there is no lifecycle (priority 1)

### Motivation

`connectServer` runs exactly once inside `loadMcp`. If a stdio server crashes, or an HTTP server
drops the stream, mid-session, the client object is dead: every subsequent `tool.execute` returns
`{ isError: true }` **forever**, with no detection and no recovery. A resident agent that ran fine
at boot silently loses a whole tool source an hour in. VS Code treats a connection as a state
machine — `McpConnectionState.Kind = Stopped | Starting | Running | Error` — with explicit
`start()` / `stop()` and a `_waitForState` gate, and `canBeStarted()` guarding re-entry
(`src/vs/workbench/contrib/mcp/common/mcpServerConnection.ts:43-65`, `stop` `:123-127`). That is
exactly the machinery that makes restart-on-crash possible.

### Proposed API (additive)

```ts
// McpSource additions (js/src/mcp.ts) — the source becomes observable + recoverable
interface McpSource {
  tools: Tool[]
  status: Record<string, McpStatus>          // existing: "connected" | "failed" | "disabled"
  close(): Promise<void>
  // NEW — per-server connection state, and a manual restart.
  state(server: string): "stopped" | "starting" | "running" | "error"
  restart(server: string): Promise<void>     // stop (if any) → reconnect → re-list → re-wrap tools
  onStateChange?(cb: (server: string, state: string) => void): () => void  // optional observer
}

// loadMcp opts additions
interface LoadMcpOptions {
  waitFor?: (r: Request) => Promise<Answer>
  signal?: AbortSignal
  // NEW — auto-restart a server whose transport closes unexpectedly.
  autoRestart?: boolean | { maxRetries?: number; backoffMs?: number }  // default false ⇒ today
}
```

When `autoRestart` is set, a transport `close`/`error` after a successful start transitions the
server to `error`, then attempts reconnect with bounded exponential backoff, re-lists, and swaps
the server's tools in place (same prefixed names). On give-up it stays `error` and
`tool.execute` returns the same isolated error as today.

### Acceptance tests
- Kill a stdio server's process after load; with `autoRestart:true` a subsequent `execute`
  succeeds after ≤ `maxRetries`, and `state(server)` walks `running→error→starting→running`.
- With `autoRestart` **unset**, behavior is byte-identical to today (dead client, isolated error).
- `restart(server)` on a healthy server is idempotent (stop→start, tools unchanged).
- A parent `signal` abort during a restart cancels it promptly (reuse the `raceTimeout` path).

### Cross-language parity
Contract moves in `SPEC.md §2`: define the four state names and the `restart` observable. Go
already has the concurrency primitives (`sync.WaitGroup` `mcp.go:536`); each port keeps its own
idiom for the backoff timer but must agree on state names + retry semantics. Shared fixture:
`examples/mcp-restart/` with a crash-once stub server.

---

## Gap M2 — The tool list is captured once and never refreshed (priority 2)

### Motivation

MCP servers may send `notifications/tools/list_changed` when their tool set changes at runtime
(feature flags, auth state, a plugin loading). toolnexus lists tools exactly once in
`paginateTools` (`mcp.ts:171`) and **subscribes to nothing** — grep the source: there is no
`list_changed` handler in any port. A server that adds a tool after connect is invisible; one that
removes a tool leaves a stale wrapper whose `execute` fails. This is a spec-conformance gap, not
just a nicety.

### Proposed API (additive)

```ts
interface McpSource {
  // ...M1 additions...
  refresh(server?: string): Promise<void>    // re-list one server (or all) and reconcile tools
  onToolsChange?(cb: (server: string) => void): () => void
}
// loadMcp opts
interface LoadMcpOptions {
  liveToolList?: boolean   // default false ⇒ today. true ⇒ subscribe to list_changed → refresh()
}
```

`refresh` diffs the new listing against the current wrappers for that server and adds/removes so
the flattened `tools` array stays current; `liveToolList` wires the server's `list_changed`
notification straight into `refresh(server)`.

### Acceptance tests
- A stub server that emits `list_changed` after adding a tool: with `liveToolList:true` the new
  prefixed tool appears in `source.tools` without a reload; with it false, it does not.
- `refresh()` is safe to call concurrently with an in-flight `execute` on the same server.
- Removing a tool server-side then `refresh()` drops exactly that wrapper, others untouched.

### Cross-language parity
Moves `SPEC.md §2`: the `refresh`/`liveToolList` contract and the reconcile-by-name rule. Shared
fixture `examples/mcp-list-changed/`.

---

## Gap M3 — Every server is connected eagerly at load (priority 3)

### Motivation

`loadMcp` pays N transport spawns / N HTTP handshakes up front via `Promise.all` (`mcp.ts:273`),
and a slow server delays the whole load up to its timeout even if the agent never calls its tools
this turn. VS Code starts a server only when its tools are first needed (lazy start, e.g.
`McpStartPromptingServerCommand`, `src/vs/workbench/contrib/mcp/browser/mcpCommands.ts:1553`). For
an agent with a dozen configured servers and a short conversation, most of that boot cost is
wasted.

### Proposed API (additive)

```ts
// per-server config (LocalServer / RemoteServer)
interface ServerConfigCommon {
  lazy?: boolean   // default false ⇒ connect at load (today). true ⇒ connect on first execute.
}
```

A `lazy` server still contributes its tools to the registry — via a cached listing done once, or
(stricter) a **placeholder** whose first `execute` triggers `connectServer` + `paginateTools`,
then proceeds. Recommend: connect-and-list on first execute, cache thereafter; a `lazy` server
reports `state:"stopped"` until first use.

### Acceptance tests
- A `lazy:true` server is not connected after `loadMcp` returns (assert no child process / no
  socket); it connects on the first `execute` of one of its tools and succeeds.
- Load time with all servers `lazy` is bounded by the fastest server, not the slowest.
- `close()` tears down only the servers actually started.

### Cross-language parity
Observable contract identical across ports; the lazy mechanism is a per-port idiom (closure vs
struct). No `SPEC.md` wire change beyond documenting the `lazy` config key. Fixture optional.

---

## Gap M4 — stdio servers inherit the entire parent environment (priority 4, security)

### Motivation

`connectServer` launches every stdio server with `{ ...process.env, ...serverEnv }` (`mcp.ts:251`,
Go `os.Environ()` `mcp.go:404`). That hands **all** ambient secrets — API keys, tokens,
cloud creds sitting in the parent env — to every third-party MCP binary the config names. A
`mcp.json` a user pasted from the internet gets your whole keyring. This violates least-privilege
and, for this workspace specifically, the standing rule that a secret's value must not leak into
processes that don't need it. The header path already does the right thing (`expandEnvHeaders`
pulls only named `${VAR}`s); the stdio path does not.

### Proposed API (additive)

```ts
interface ServerConfigCommon {
  // Whitelist of parent env var NAMES to pass through, in addition to explicit `env`.
  // undefined ⇒ today's behavior (inherit all) for back-compat.
  // []        ⇒ inherit nothing but a documented safe base (PATH, HOME, TMPDIR, LANG…).
  // [names]   ⇒ safe base + only these names from process.env.
  inheritEnv?: string[]
}
// loadMcp opts — flip the global default without editing every server
interface LoadMcpOptions {
  defaultInheritEnv?: string[]   // applies to servers that omit inheritEnv
}
```

Values still come from the environment at launch (never from the config file, never logged) —
this only narrows *which names* cross the boundary.

### Acceptance tests
- With `inheritEnv: []`, a stub server prints its env; it contains the safe base + explicit `env`,
  and **not** an unrelated `SECRET_TOKEN` present in the parent.
- With `inheritEnv` unset, the child sees the full parent env (back-compat).
- `defaultInheritEnv` applies to servers that omit their own `inheritEnv` and is overridden by it.

### Cross-language parity
Moves `SPEC.md §2`: the three-state semantics of `inheritEnv` (undefined/empty/list) and the
documented "safe base" set — this must be byte-identical across ports or the same config leaks
differently per language. Shared fixture `examples/mcp-scoped-env/`.

---

## Consumer questions (answer before promoting any gap)

- **QM1 (M1):** should `autoRestart` be the default once implemented, or stay opt-in? A resident
  agent wants it on; a one-shot CLI does not care. Leaning opt-in to preserve byte-parity, with a
  doc recommending it for long-lived hosts.
- **QM2 (M3):** lazy = "list once eagerly, connect on use" or "connect on first use and list
  then"? The latter is stricter (no boot cost at all) but means a `lazy` server's tools are absent
  from the very first system prompt. Which does the platform need?
- **QM4 (M4):** is flipping the **default** to a safe base (breaking change, major version) on the
  table, or must inherit-all stay the default forever for back-compat? This is the one gap whose
  ideal end-state is a breaking change.
