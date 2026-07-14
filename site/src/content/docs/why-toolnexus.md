---
title: Why toolnexus
description: >-
  What you can build with toolnexus's host-control primitives — five concrete multi-tenant agent-platform scenarios, each tied to the workaround it deletes.
---

*A value/positioning narrative for the new host-control primitives (v0.7.0 client shaping + v0.8.0
skills extensions). Grounded in `SPEC.md` §2/§3/§8 and the two consumer ADRs
(`docs/adr/0001-rag-go-consumer-needs.md`, `docs/adr/0002-skills-consumer-needs.md`). Nothing here
is aspirational — every symbol named below shipped.*

---

## 1. The thesis

toolnexus already collapsed six tool sources — MCP servers, agent skills, native functions,
HTTP/REST endpoints, the ten built-in shell/file tools, and remote A2A agents — into **one uniform
`Tool` registry**, rendered into any model's OpenAI/Anthropic/Gemini schema and driven by a client
loop with skills injection, streaming, retries, hooks, conversation memory, and human-in-the-loop
suspension. That made *building one agent* a three-line job. The new host-control primitives make
**building a platform that runs a different agent per tenant, per user tier, per request** a
config job instead of a subsystem job. The through-line: everything an LLM can call is the same
`Tool`, and now everything a *host* needs to do to that tool set — gate it per agent, feed it from
a database instead of the filesystem, validate an admin's config without booting an agent, shape
the request per provider, inject its own HTTP transport, cancel a hung load, and share the
conversation transcript — is a first-class, additive option rather than a wrapper you bolt on
outside the library. toolnexus is no longer just "your LLM with tools built in"; it is the
**substrate a multi-tenant agent product sits on**.

---

## 2. The unlock: from "one agent" to "an agent platform"

The library was always enough to stand up *an* agent. What it lacked was every seam a platform
builder reaches for the moment there is more than one tenant, more than one tier, and an admin UI.
Those seams are exactly the seven MCP/client gaps in ADR 0001 and the skills gaps in ADR 0002 —
and the proof they were real is that a production consumer, **rag_go** (the Go rewrite of the
Volentis RAG API, a multi-tenant RAG platform), had already built each one *outside* the library
as a workaround. The new primitives let it delete them:

| Platform need | The workaround rag_go built | The primitive that replaces it |
|---|---|---|
| Per-provider/per-model request shaping (temperature, `reasoning_effort`, GLM `chat_template_kwargs`, Scaleway tool-role message rewriting) | An **in-process reverse proxy** intercepting every `POST /chat/completions` | `ClientOptions.RequestParams` (declarative merge, caller wins) + `BodyTransform` (full-body escape hatch) — §8 |
| Bounded, cancellable toolkit construction against flaky MCP endpoints | A **build watchdog** goroutine racing a timer, leaking the abandoned connection | Ctx-aware load (`LoadMcpWithContext` / signal passthrough) + bounded SSE start — §2 |
| Per-agent tool gating from `mcp_servers.agenttools[].enabledTools` | A **wrapper-based tool filter** that reverse-engineered sanitized/prefixed names | `ServerConfig.tools` per-server allowlist (keyed on original names) — §2 |
| Reading/rewinding the transcript for empty-answer retries | A **shadow conversation store** mirroring every `Ask`, with drift risk | `Client.ConversationStore()` accessor — §8 |
| Validating an admin-entered MCP config live | Building a **full toolkit** then diffing names apart | `ListMcpTools` list-only inventory — §2 |
| Providers that 400 on an empty tools array | A **zero-tools degrade path** — a parallel raw-HTTP client | Empty-tools omission (drop `tools`/`tool_choice` when the set is empty) — §8 |
| Observability / proxy / per-request timeouts on the LLM path | (part of why the proxy existed) | Injectable `HTTPClient` — §8 |

And the companion skills gaps (ADR 0002, v0.8.0) do the same on the *skill* source: supply skills
as **data** (`SkillDef`) or a lazy `SkillSource` provider instead of an on-disk `skills/` tree;
gate them per agent with `SkillsFilter`; validate them with `ListSkills` (typed skip reasons:
`missing-name` | `malformed-frontmatter` | `duplicate-name` | `unreadable`); and emit a **logical
`skill://` base** instead of leaking absolute host paths when a toolkit is served over A2A.

The unifying design decision worth calling out: **one filter vocabulary everywhere.** The
per-server `tools` map, the per-agent `SkillsFilter`, and the built-ins `tools` map all share
identical semantics — `nil`/empty ⇒ all, ≥1 `true` ⇒ allowlist, only-`false` ⇒ drop-list, unknown
names ignored + warned. A platform configures MCP tools, skills, and built-ins the *same way*, and
the toolkit-level `DisableTools` / `DisableSkills` lists give a blunt final override by exposed
name across every source. That consistency is what turns "wire up gating" from a design problem
into a data-entry problem.

---

## 3. What you can now build

### Scenario A — a tiered SaaS: USER / PRO / ADMIN agents from one codebase

**Need:** a free-tier agent sees `get_my_leave_balance` and `get_my_hr_profile`; a PRO agent adds
the analytics MCP server and two more skills; an ADMIN agent gets the built-in `bash`/`edit`
toolset. One binary, three tool surfaces, chosen per request from a DB row.

**Before:** a wrapper layer that rebuilt the toolkit's tool list post-construction, matching on
sanitized/prefixed names it had to reverse-engineer — the exact filter hack rag_go documented.

**Now:** per server, `ServerConfig.tools = {"get_my_leave_balance": true, "get_my_hr_profile":
true}` (keyed on the **original** tool names the platform already stores); per agent,
`SkillsFilter = {"leave-policy": true}`; `builtins: false` for USER/PRO, on for ADMIN. Three rows
of config, no wrapper. Same vocabulary for all three sources.

### Scenario B — an admin console that validates a tenant's config live

**Need:** libreadmin's MCP-server and skill-authoring screens must answer, before anything runs,
"does this server connect, what tools does it expose, is this allowlist valid?" and "why didn't my
`SKILL.md` load?"

**Before:** spin a full toolkit (spawning built-ins, globbing skills, resolving A2A agents), then
parse prefixed names back apart to guess — and skill parse failures were a silent `log.Printf` +
`continue`, invisible to the author.

**Now:** `ListMcpTools(ctx, config)` connects to every enabled server, returns per server the
**unfiltered** tool defs under their original names plus a `connected|disabled|failed` status, and
**disconnects** — nothing left running. `ListSkills(ctx, dirs…)` returns `{ skills, skipped }`
with a **typed reason** per skip, so the UI can say *"this SKILL.md was skipped: missing-name."*
Both are list-only: no client, no tasks, no store, guaranteed teardown. This is the difference
between an admin screen that validates and one that has to boot an agent to find out.

### Scenario C — a provider-portability layer without a proxy

**Need:** the same agent must run against GLM on Scaleway (`chat_template_kwargs: {enable_thinking:
false}`, a `/nothink` suffix on the last user message), OpenRouter, and Anthropic — each with its
own sampling params and body quirks — and Scaleway needs tool-role messages rewritten.

**Before:** an in-process reverse proxy that JSON-decoded every request body, injected params,
rewrote messages, and forwarded upstream — for both streaming and non-streaming. The single
biggest workaround in the ADR.

**Now:** `RequestParams` carries the declarative params (`{"temperature": 0.2, "reasoning_effort":
"none", "chat_template_kwargs": {...}}`) — shallow-merged into every body on all four paths
(run/stream × openai/anthropic), caller wins on collision. `BodyTransform` receives the
fully-assembled body last and returns what hits the wire — the escape hatch for the Scaleway
tool-role rewrite and the `/nothink` append, message-rewriting included. Ordering is pinned: base
body → `BeforeLLM` hook → `RequestParams` merge → `BodyTransform` → wire. The proxy is deleted; an
injectable `HTTPClient` covers the residual observability/timeout/routing need on the LLM path.

### Scenario D — a human-approval workflow

**Need:** an agent that can spend money or mutate production must pause and get a human yes/no
before the tool fires, then resume the same conversation.

**Now:** the client loop's §10 suspension surfaces a `Request{kind, prompt}` (a `question`, an
`input` form, an `authorization` URL) and waits; the host answers via `waitFor`, and — because
`Client.ConversationStore()` now exposes the exact transcript the client saved — the approval step
can read the pending state, rewind an attempt, or hand the thread to another worker without a
shadow copy. Suspension made the pause possible; the store accessor makes the surrounding
orchestration honest (one source of truth for history, shared with `ask`).

### Scenario E — a served A2A agent whose skills come from object storage

**Need:** expose a tenant's toolkit as a remote A2A agent (`toolkit.serve()`), where that tenant's
skills live as rows in Postgres or objects in a bucket — not a checkout on the box — and the
consuming agent runs on another host.

**Before:** materialize a temp `skills/` tree per request (I/O per request, tmp races, resource
bytes forced to local disk even for instruction-only skills), and the `skill` tool output leaked
`file:///home/…/tenants/42/skills/x` — both a filesystem-layout leak and useless to a remote
caller.

**Now:** feed skills as data — `Skills: []SkillDef{...}` or a lazy `SkillSource(ctx)` provider that
reads the bucket — and the served skill emits a **logical `skill://<name>/` base** with logical
`resources`, never an absolute host path. The three sources (dir + data + provider) compose and
dedupe by name (first-wins), a failing provider is isolated like a bad MCP server, and the
local-`SkillsDir` path stays **byte-identical** so the shared conformance fixture never moves.

---

## 4. Why five-language byte-identical parity matters here

A real company does not ship one runtime. Volentis alone drives this from a **Go** service
(rag_go); the same product surface may want a **Python** data service, a **TypeScript** frontend
agent, a **JVM** backend, and a **.NET** app. The platform primitives above are only useful if an
agent's *observable behavior* — which tools it sees after an allowlist, the exact bytes of a
`skill` tool's progressive-disclosure block, the empty-tools omission rule, the filter semantics —
is the same in whichever language happens to host it. That is precisely what `SPEC.md` pins: the
allowlist semantics are defined once and honored identically in js/python/golang/java/csharp, the
skill-loader output is byte-for-byte, and the shared `examples/` fixtures are the conformance test.
So a tenant's per-agent gating, validated in a TypeScript admin console, produces the **same** tool
surface when the agent actually runs in the Go service. Parity isn't a nice-to-have for a polyglot
shop — it's the thing that lets "the agent" mean one behavior across five stacks. And because every
one of these primitives is **additive** — a caller passing none of the new options gets
byte-identical behavior to before — none of it is a breaking migration.

---

## 5. Honest boundaries — what toolnexus is not

- **Not an LLM.** It drives one. You bring a base URL + `openai`/`anthropic` style + a model; the
  intelligence is the provider's.
- **Not a vector DB or a RAG framework.** rag_go does the retrieval; toolnexus is the *agent
  engine* it plugs into. Skills-as-data means you can feed it your storage, not that it is storage.
- **Not an orchestration platform.** No runtime to deploy, no server to operate, no DAG engine, no
  workflow scheduler. It is a **library** — à la carte, in-process. The client loop runs a
  conversation; it does not schedule your fleet. (`serve()` exposes one toolkit as an A2A/MCP
  endpoint; that is an edge, not an orchestrator.)
- **Not a config/secrets manager.** It expands `${ENV}` in headers at call time and never logs the
  value; it does not store your keys.
- **Not a provider-abstraction miracle.** `RequestParams`/`BodyTransform` let *you* shape bodies
  per provider — they hand you the seam; they don't hide provider differences behind a magic
  common model.

The positioning stays credible because the claim is narrow and true: toolnexus is the **small,
vendor-neutral, five-language, byte-identical substrate** that unifies every tool source behind one
interface and now gives a host the exact controls a multi-tenant agent product needs — and nothing
it doesn't.
