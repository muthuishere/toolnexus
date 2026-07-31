# S21 — `toolkit.serve`: the inbound side, both profiles, error paths included

**Question:** does `toolkit.serve` — SPEC §7B (A2A agent) and §7C (MCP server),
co-mounted on one server — hold up on Clojure (JVM) **and** cljgo, *including
the paths where things go wrong*?

**Answer: yes.** Verified 2026-07-31. All three modes byte-identical, first run.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 2939 bytes)
  jvm == cljgo-run  (byte-identical, 2939 bytes)
```

Reproduce: `./run-both.sh` (needs `clojure` and `cljgo`; **no** `npx`, no network
beyond `127.0.0.1`, no LLM). One file, `src/toolnexus/serve.cljc`, 557 lines,
**zero reader conditionals, zero `java.*`, zero Go interop.**

## Why four servers

S17 already co-mounted both profiles and drove the happy path. This spike is
about everything S17 skipped, and absence is a behaviour, so it starts four:

| | `a2a` | `mcp` | proves |
|---|---|---|---|
| **S1** | configured | configured | every card field, `provider`, `a2a.skills` filter, `mcp.tools` filter, configured `serverInfo` |
| **S2** | `{}` | `{}` | every §7B/§7C **default**, plus the whole task lifecycle and all error paths |
| **S3** | `{}` | *absent* | `POST /mcp` ⇒ 404, card still 200 |
| **S4** | *absent* | `{}` | card ⇒ 404, `POST /` ⇒ 404, `/mcp` still works |

## What it measured

### §7B — Agent Card

- **Configured** (S1): key set is exactly
  `capabilities, defaultInputModes, defaultOutputModes, description, name,
  protocolVersion, provider, skills, url, version`; `provider` present with
  `{organization, url}`; `url == base + "/"`; `skills` filtered to
  `a2a.skills` ⇒ **1 of 2**; every skill carries the `SKILL.md` description;
  **no toolkit tool name appears in `skills[]`**.
- **Defaults** (S2, `a2a {}`): `name:"toolnexus-agent"`, `description:""`,
  `version:"0.1.0"`, `protocolVersion:"0.3.0"`,
  `capabilities:{streaming:false, pushNotifications:false}`,
  `defaultInput/OutputModes:["text"]`, **no `provider` key at all** (absent, not
  `null`), all 2 skills.
- The skill source is the shared `examples/skills/` fixture (`hello-world`) plus
  one **data-provided** skill (`inline note`) — the shared fixture ships exactly
  one skill and a filter is meaningless over one. It is a data SkillSource, not
  a forked copy of the fixture.
- That second skill also pins the §7B/§7C **asymmetry** in one line:
  card skill `id` is sanitized (`"inline note"` → `"inline_note"`) while the
  skill `name` is not — and §7C tool names are not either (below).

### §7B — task lifecycle

| step | measured |
|---|---|
| `SendMessage` returns | `status.state == "submitted"`, with an id, **immediately** |
| async fulfilment | `working` observed by a peer via `GetTask` *while the run is still in flight* |
| success | `completed`, `artifacts` length 1, `artifactId` present, `parts:[{kind:"text"}]`, text = the run's text |
| failure | `failed`, `status.message.role == "agent"`, `parts:[{kind:"text"}]`, text = the thrown message, **no `artifacts` key** |
| **server survives** | a *later* `SendMessage` after the throwing task ⇒ `completed` |

`working` is observed deterministically rather than raced: the fulfilment of the
task named `slow` parks on an atom gate until the peer has seen `working` and
releases it. Without that, a fast fulfilment jumps `submitted → completed` and
the transition is unobservable (see finding 6).

### §7B — error paths

| case | code |
|---|---|
| `GetTask` unknown id | `-32001`, no `result` key |
| unknown method | `-32601` |
| malformed JSON body (`{not json at all`) | `-32700`, `id: null`, HTTP 200 |
| **another request after the parse error** | `-32601` — server still answering |

### §7C — MCP over `/mcp`

- `initialize`: configured ⇒ `serverInfo {name:"s21-mcp", version:"2.0.0"}`;
  defaults ⇒ `{name:"toolnexus", version:"0.1.0"}`, `protocolVersion
  "2024-11-05"`, `capabilities.tools` present.
- `tools/list` **verbatim**: the toolkit holds a tool literally named
  `calc.sum`. Served name is `calc.sum`; `sanitize("calc.sum")` is `calc_sum`
  and that string appears **nowhere** in the list. This is the §7A/§7B-vs-§7C
  asymmetry, measured rather than asserted. 4 tools, all with an `inputSchema`.
- `mcp.tools: ["calc.sum","echo_ok","no-such-tool"]` ⇒ served
  `["calc.sum","echo_ok"]`, count 2, **no error for the unknown name**;
  `["kaboom","skill"]` filtered out.
- `tools/call`: `content:[{type:"text"}]`, `isError:false` on success;
  `isError:true` propagated from a `ToolResult` that reports an error;
  an `execute` **throw** ⇒ `isError:true` with the error text and **no JSON-RPC
  `error` member**; the very next `tools/call` succeeds (`"a|b"`).
- unknown tool name ⇒ `-32602`, no `result`. A tool that exists in the toolkit
  but is filtered out of this profile ⇒ also `-32602`.
- `onCall` fired 4×, in order, with the right `isError` on each.

### Absence

- `mcp` absent (S3): `POST /mcp` ⇒ **404**, card ⇒ 200.
- `a2a` absent (S4): card ⇒ **404**, `POST /` ⇒ **404**, `/mcp` still answers
  `serverInfo.name == "toolnexus"`.

## Findings

1. **`koine.server` on cljgo prints to STDOUT.** Its Go backend emits
   `bri: listening on http://localhost:NNNNN` per server — despite `:ops false`
   and `:middleware []` — so a program whose stdout is data gets four garbage
   lines *carrying a non-deterministic port*. `run-both.sh` filters to the last
   `^{` line. This should go to stderr in koine; a library that prints on the
   caller's stdout cannot be embedded.
2. **AMBIGUITY §7B: the HTTP status carrying a JSON-RPC error is unpinned.**
   The spec pins `-32700/-32601/-32001` but never says whether a parse error is
   HTTP 200 + JSON-RPC error or HTTP 400. We chose 200 (JSON-RPC 2.0's usual
   reading). Six ports can differ here and §0 conformance would not notice.
3. **AMBIGUITY §7C: is a filtered-out tool still *callable*?** §7C only says the
   *list* is filtered. A literal implementation leaves `mcp.tools`-excluded
   tools reachable via `tools/call` — which turns what reads like an allowlist
   into a cosmetic filter. We made the filter authoritative for calls too
   (⇒ `-32602`). This needs pinning; it is security-relevant, not stylistic.
4. **AMBIGUITY §7B: unknown names in `a2a.skills`.** §7C explicitly says unknown
   `mcp.tools` names are ignored; §7B says nothing for `a2a.skills`. We ignored
   them, by symmetry. Untested asymmetry is exactly how ports drift.
5. **AMBIGUITY §7C: no parse-error code is named** for `/mcp` (only §7B names
   one). We returned `-32700` there too.
6. **The `working` state is only *reachably* observable for a slow fulfilment.**
   §7B says "save `working` → run", which is a *storage* obligation, not an
   observability one. A conformance test that asserts a peer sees `working`
   would be flaky in every port. The spec should say the store MUST record
   `working` before the run begins — the wording it has, said explicitly.
7. **PROFILE CONFLICT when co-mounted: two identities, one server.** `a2a` and
   `mcp` carry independent `name`/`version`, with *different* defaults —
   `"toolnexus-agent"` vs `"toolnexus"`. One base URL answers as two differently
   named agents. Worse, the Agent Card has **no field advertising the
   co-mounted `/mcp` surface**, and its `url` is the A2A JSON-RPC endpoint, so
   an A2A peer that fetches the card can never discover the MCP server sitting
   one path away. If co-mounting is a supported topology, the card should say so.
8. **AMBIGUITY: "every request 404s".** §7B says that of an absent `a2a`
   profile, which read literally would 404 `/mcp` as well; §7C says an MCP-only
   `serve` "404s all other paths". They only reconcile under per-profile
   mounting, which is what we implemented (S4 proves it). Worth one sentence in
   §7B.
9. **`future` + `atom` is enough for §7B async fulfilment on both hosts.** No
   host-specific concurrency, no reader conditional, no koine addition. The
   peer-visible `submitted → working → completed|failed` sequence is identical
   on the JVM and on both cljgo modes.

## What this spike did NOT cover

- **TaskStore pluggability.** §7B's `resolveStore` — `"file:<dir>"` and a
  caller-supplied object — is untested; only the default in-memory store runs.
- **`onTask` telemetry.** The hook fires on a terminal state, but with no
  `RunResult`: fulfilment here is a local fn, not the §8 client loop (S16/S17
  cover the loop). Tokens / tool count / turns are unmeasured.
- **Real streamable-HTTP.** `/mcp` is plain JSON-RPC over `POST`. No SSE, no
  `Accept: text/event-stream`, no `Mcp-Session-Id`, no `notifications/
  initialized`, no batching, no `GET`/`DELETE` on `/mcp`. A real MCP SDK client
  (Claude Desktop) may well require content negotiation this does not do — the
  peer here is our own JSON-RPC client, not an SDK.
- **HTTP method dispatch.** Routing is by path only; a `GET /` is handed to the
  A2A JSON-RPC decoder rather than rejected. Untested, and not specified.
- **Cancellation.** §7C's "`ctx` carries the request's cancellation signal" is
  not exercised.
- **Concurrency under load.** One task at a time; no simultaneous
  `SendMessage`, no task-store contention.
- **Auth, streaming, push notifications** — out of core scope per SPEC.
- **A2A `Task` fields beyond the ones §7B names** (contextId, history,
  timestamps) and the real A2A method spellings (SPEC pins the literal strings
  `SendMessage`/`GetTask`).
