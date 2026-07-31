# S20 — SPEC §7A: calling a remote A2A agent, on both hosts

**Question:** can toolnexus **call** a remote A2A agent and expose its advertised
skills as tools — card fetch, JSON-RPC `SendMessage`/`GetTask`, the submit→poll
state machine, and all five terminal outcomes — in portable Clojure over koine
alone, byte-identically on Clojure (JVM) and cljgo?

**Answer: yes.** Verified 2026-07-31. Zero reader conditionals, zero `java.*`,
zero Go interop, koine the only dependency.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 2506 bytes)
  jvm == cljgo-run  (byte-identical, 2506 bytes)
```

Reproduce: `./run-both.sh` (needs `clojure` and `cljgo`; no network beyond
127.0.0.1, no LLM, no key).

## What it covers

`src/toolnexus/a2aout.cljc`, 409 lines, is both the §7A client **and** the
remote agent it talks to — a `koine.server` on `127.0.0.1:0` speaking real
JSON-RPC 2.0, scripted so every branch of §7A is measured rather than described.

| §7A | what | measured |
|---|---|---|
| resolve | `GET /.well-known/agent-card.json` → `AgentCard` | name/version/protocolVersion/capabilities/modes/3 skills |
| naming | `sanitize(card.name)_sanitize(skill.id ?? skill.name)` | `Demo_Bot_echo`, `Demo_Bot_fail_now` (dotted id), `Demo_Bot_Slow_Work` (**no** `id` → falls back to `name`) |
| tool shape | `source:"a2a"`, `inputSchema {task:string, required:["task"]}` | emitted verbatim, in the report |
| endpoint | JSON-RPC endpoint = `card.url`, **fallback** = card-URL origin | both proven (`fromCardUrl`, `originFallback`) against two cards |
| isolation | "a failing agent is isolated, never fatal" | a 404 card ⇒ `{toolCount 0, error "HTTP 404: no card here"}`, no throw |
| wire | one `SendMessage` (`configuration.blocking:false`) then `GetTask` every `pollEvery` | the scripted agent answers `working` first, so the poll loop really loops |
| `completed` | all `kind:"text"` parts across `artifacts[].parts[]` joined by `\n` | `"line one\nline two"` — a `kind:"data"` part in between is skipped |
| completed (fallback) | last `role:"agent"` history message when there are no artifacts | `"final reply"` (not the earlier agent message) |
| `failed` | `A2A task <id> <state>: <status.message text>` | **`A2A task task-fail failed: boom`** |
| `canceled` | same, with the `[: …]` **absent** when there is no `status.message` | **`A2A task task-cancel canceled`** |
| timeout | `A2A task <id> timed out after <ms>ms (state=<state>)` | **`A2A task task-hang timed out after 300ms (state=working)`** |
| abort | `ctx` abort ⇒ stop before the next `GetTask` | **`A2A task task-hang canceled`** |
| `metadata` | `{agent, taskId, state, polls, ms}` on **every** result | all six results carry exactly those five keys |
| headers | `${ENV}` expansion, never logged | report carries the header **key** and a boolean "expansion changed the value" — never a value |

The three contract strings the brief asked to prove byte-for-byte
(`failed`, `timed out`, and the optional-detail `canceled`) are asserted by the
byte diff itself: they are literal substrings of the single JSON line that all
three runs agree on.

`polls` and `ms` are non-deterministic by construction, so the report prints
**`metadataKeys`** (the sorted key names) plus only the stable trio
`{agent, taskId, state}`. Task ids are fixed strings (`task-ok`, `task-fail`,
`task-hang`, …) rather than uuids for the same reason — the error strings are
part of the contract and must be diffable. The timeout budget is **300 ms** with
a 60 ms poll interval, so the whole spike runs in well under a second.

## Findings

1. **`agent` is `clojure.core/agent` — §7A's factory name cannot be used.**
   SPEC §7A names the factory `agent({card, …})`. In Clojure that shadows
   `clojure.core/agent` (STM agents), which the spike brief bans outright and
   which is exactly the class of shadowing cljgo is unhappy about. This spike
   uses `resolve-agent`; the port will need a deliberate public name
   (`a2a-agent` reads best and keeps the `a2a` prefix the source name already
   uses). **Not a blocker — a naming decision the port must make once.**

2. **koine gap / host divergence: `koine.server/serve` prints a banner to
   STDOUT on cljgo, nothing on the JVM.** Every cljgo run emits
   `bri: listening on http://localhost:<port>` on **stdout** before the report
   (confirmed by splitting the two streams to files: stderr is empty). That both
   pollutes a one-JSON-line contract and leaks a non-deterministic port number
   into stdout. Workaround here is `tail -1` in `run-both.sh`, which is fine for
   a spike but is a real defect for a library: a toolnexus `serve` must not
   write to the host program's stdout. Worth a koine issue (silence the bri
   banner, or route it to stderr behind a flag).

3. **SPEC §7A ambiguity — `timed out after <ms>ms` does not say *which* ms.**
   The same paragraph uses `ms` twice with two different meanings: the message
   `timed out after <ms>ms` and `metadata.ms`. The JS reference
   (`js/src/a2a.ts`) resolves it as the **configured budget** for the message
   and **elapsed wall time** for the metadata — which is also the only reading
   that makes the string deterministic and testable. This spike follows JS.
   Recommend SPEC say "after `<timeout budget>`ms" explicitly, or the Go/Java/C#
   ports will each guess.

4. **SPEC §7A ambiguity — `polls` is undefined.** `metadata.polls` is never
   defined as "the number of `GetTask` calls" vs "poll-loop iterations", and it
   is unclear whether the initial `SendMessage` counts. JS increments only
   *after* a successful `GetTask`; this spike matches. Cheap to pin in one
   clause.

5. **SPEC §7A ambiguity — `metadata.state` on the abort path.** On `ctx` abort
   the remote task is still `working`, yet JS reports `state:"canceled"` in
   metadata (the *local* verdict, not the remote state). SPEC pins only the
   output string. Matched JS; worth one sentence in SPEC.

6. **SPEC §7A silence — RPC/transport errors mid-poll.** §7A says "reuses
   httpTool's `${ENV}` header expansion + timeout + non-2xx mapping", so a
   non-2xx is `HTTP <status>: <body>`, but it never says what happens to a
   JSON-RPC `error` object or a transport failure during polling. JS turns both
   into `isError:true` with the message and the metadata built so far; this
   spike does the same (`HTTP transport <kind>` for koine's data-shaped
   transport failures, which have no JS analogue since `fetch` throws).

7. **No reader conditional was needed, and nothing in §7A required one.**
   `random-uuid` works on cljgo in **both** modes (interpreted and AOT) — worth
   recording, since §7A mandates uuids for the JSON-RPC `id` and `messageId`.
   The card-URL **origin** fallback is string arithmetic (`(take 3 (split url
   #"/"))`), so no host URL parser is involved. `#{...}` + `contains?` for the
   terminal-state set, `koine.time/mono-ms`/`elapsed-ms` for the budget, and
   `koine.time/sleep!` for `pollEvery` all behave identically on both hosts.

## What it does NOT cover

- **`§7A` config surface**: the `agents` block in a config file (`{ "<id>": {
  card, headers, timeout, pollEvery, enabled/disabled } }`) and its MCP
  `isEnabled` precedence, and `toolkit.addAgent(...)` at runtime. Both are pure
  config parsing over what S15 already proved for `mcpServers`; not measured
  here.
- **Toolkit integration**: these tools are built and executed directly, not
  registered into a toolkit or driven by the client loop (S16 covers the loop).
- **`§7B` inbound `serve`** — a different spike. Nothing here proves toolnexus
  can *be* an A2A agent; the peer in this spike is a hand-written script.
- **Real A2A interop.** The peer is a faithful but hand-rolled subset
  (JSON-RPC 2.0, `SendMessage`/`GetTask`, the Task/artifact shapes from §7A). No
  `a2a-python` SDK was run against it — that is a conformance question for the
  port, not a portability question for Clojure.
- **Streaming / push notifications / gRPC / auth** — explicitly out of §7A core.
- **A cancelled task on the remote side** (A2A `CancelTask`): §7A's `canceled`
  arrives only as a Task state here, never requested by the client.
- **`contextId`**, multi-turn conversations, and non-text part kinds beyond
  "skipped by the text extractor".
