# Mastra vs toolnexus — an honest comparison

## Framing: different categories, not competitors

**Mastra** and **toolnexus** are not the same kind of thing, and neither is a strict
superset of the other.

**Mastra** is a **batteries-included TypeScript agent platform** — the same *class* of thing as
**Spring AI** (JVM) or **LangGraph** (Python): a broad, ecosystem-anchored framework you build
your whole agent stack on, not a focused core you embed. It ships an agent
runtime, a full **workflows engine** (`packages/core/src/workflows/workflow.ts:1545`,
`createWorkflow`), **RAG** (`@mastra/rag`), **evals/scorers** (`@mastra/evals`),
**long-term memory with storage adapters** (`@mastra/memory`), a **dev playground UI**
(`@internal/playground`), **deployers** for Cloudflare/Netlify/Vercel/etc.
(`packages/deployer`, `deployers/`), MCP client+server (`@mastra/mcp`), voice/TTS,
telemetry/observability, and an agent-builder. It is built on the **Vercel AI SDK**
(`@mastra/core` depends on `@ai-sdk/*`), so it is **TypeScript/Node only**, and it targets
teams building their whole agent stack on that ecosystem.

**toolnexus** is a **focused, vendor-neutral tool-unification + agent-runtime library**,
shipped **byte-identical across six languages** (`js/`, `python/`, `golang/`, `java/`,
`csharp/`, `elixir/`). Its product is *parity*: it unifies MCP tools, agent skills
(`SKILL.md`), native functions, HTTP endpoints, and remote A2A agents behind **one `Tool`
interface** (`SPEC.md §0`), emits schemas in **OpenAI / Anthropic / Gemini** formats
(`SPEC.md §0.7`), and runs a small client loop with hooks/streaming/memory (`SPEC.md §8`),
sub-agents (`§7D`), a persona home (`§7E`), compaction (`§7F`), suspension/human-in-the-loop
(`§10`), and inbound `serve` as A2A or MCP (`§7B`, `§7C`). It is deliberately **not a
platform** — it has no workflows engine, no RAG, no evals, no playground, no deployers, and
no storage/memory backend beyond file-backed persona memory (`§7E`).

Put simply: Mastra is the **whole stack for one language**; toolnexus is a **portable core
for six languages**.

## Capability matrix

| Capability | Mastra | toolnexus | Notes |
|---|---|---|---|
| Languages | ⚠️ | ✅ | Mastra: TS/Node only (built on Vercel AI SDK, `@mastra/core`). toolnexus: js/python/golang/java/csharp/elixir, byte-parity. |
| Model providers | ✅ | ✅ | Mastra: many, via AI SDK (`@ai-sdk/*`). toolnexus: OpenAI + Anthropic wire styles in the built-in client (`§8`); schema adapters for OpenAI/Anthropic/Gemini (`§0.7`). |
| MCP client | ✅ | ✅ | Both connect stdio + HTTP servers. Mastra `@mastra/mcp` (`packages/mcp/src/client`). toolnexus `§2`, per-language MCP SDKs. |
| MCP / A2A server (inbound) | ✅ | ✅ | Mastra: MCP server (`packages/mcp/src/server`). toolnexus: `serve` as A2A agent (`§7B`) or MCP server (`§7C`). |
| Agent skills (`SKILL.md`) | ⚠️ | ✅ | Mastra has a `skills` module (`packages/core/src/skills`); toolnexus makes the Anthropic-style progressive-disclosure `SKILL.md` skill tool a first-class, byte-exact contract (`§3`, `§0.6`). |
| Native/function tools | ✅ | ✅ | Both. toolnexus `source:"native"` (`§6`). |
| HTTP/REST tools | ⚠️ | ✅ | toolnexus has a first-class `http` tool source with `{ph}` URL + `${ENV}` header expansion (`§7`). Mastra: reachable via custom tools, not a dedicated primitive. |
| Sub-agents / multi-agent | ✅ | ✅ | Mastra: agent networks (`packages/core/src/agent/agent-network`). toolnexus: agents-as-tools sub-agent runtime (`§7D`). |
| Built-in tools (shell/file/etc.) | ✅ | ✅ | toolnexus ships `bash,read,write,edit,grep,glob,webfetch,question,apply_patch,todowrite` (`§4A`). Mastra: coding-agent module + tools. |
| Workflows engine | ✅ | ❌ | Mastra: durable graph workflow engine (`workflow.ts:1545`, evented + scheduler). toolnexus has none by design. |
| RAG / vector | ✅ | ❌ | Mastra: `@mastra/rag` (document, graph-rag, rerank) + vector layer. toolnexus: none. |
| Evals / scorers | ✅ | ❌ | Mastra: `@mastra/evals` (`scorers`). toolnexus: none. |
| Long-term memory | ✅ | ⚠️ | Mastra: `@mastra/memory` with pluggable storage + semantic recall. toolnexus: only file-backed persona memory (`§7E`), no DB/vector adapters. |
| Conversation memory (in-loop) | ✅ | ✅ | Both keep conversation state in the loop (toolnexus `§8`). |
| Persona / soul files | ⚠️ | ✅ | toolnexus: explicit "agent home" persona surface (`§7E`). Mastra: instructions/config, no dedicated persona-home concept. |
| Compaction | ✅ | ✅ | Mastra: harness/processors. toolnexus: `beforeLLM` compactor across all ports (`§7F`). |
| Human-in-the-loop / suspension | ✅ | ✅ | Mastra: workflow suspend/resume. toolnexus: `Request`/`Answer`/`waitFor` suspension incl. MCP elicitation bridge (`§10`). |
| Dev playground UI | ✅ | ❌ | Mastra: `@internal/playground` + `playground-ui`. toolnexus: none. |
| Deployers / hosting | ✅ | ❌ | Mastra: Cloudflare/Netlify/Vercel/sandbox/cloud (`deployers/`, `packages/deployer`). toolnexus: it is a library you embed. |
| Observability / telemetry | ✅ | ⚠️ | Mastra: telemetry + observability modules. toolnexus: loop hooks + metrics (`§8`), no full tracing backend. |
| Voice / TTS | ✅ | ❌ | Mastra: `voice`, `tts` modules. toolnexus: none. |

Legend: ✅ first-class · ⚠️ partial/indirect · ❌ absent.

## Where Mastra wins (honestly)

- **Product depth on TS.** It is a complete platform — agents, workflows, RAG, evals,
  memory, voice — not just a tool layer.
- **Workflows engine.** A real durable, evented graph workflow engine
  (`packages/core/src/workflows/`) with scheduling. toolnexus has nothing comparable.
- **RAG out of the box.** `@mastra/rag` with document processing, graph-RAG, and rerank.
- **Evals / scorers.** `@mastra/evals` gives you measurable agent quality; toolnexus
  leaves evaluation to you.
- **Playground DX.** A local UI to inspect agents, tools, workflows, and memory
  (`@internal/playground`). Big onboarding and debugging advantage.
- **Deploy story.** First-party deployers for Cloudflare/Netlify/Vercel/sandbox.
- **Memory with real storage.** Pluggable storage adapters + semantic recall vs
  toolnexus's file-only persona memory.
- **Ecosystem & community.** Larger project, more contributors, more integrations, more
  docs, riding the Vercel AI SDK ecosystem.

## Where toolnexus wins (honestly)

- **Six-language byte-parity.** The *same* agent behavior in JS, Python, Go, Java, C#, and
  Elixir, verified against shared `examples/` fixtures (`SPEC.md §0`). Mastra is TS-only.
- **Vendor-neutral schema output.** Emits OpenAI / Anthropic / Gemini tool schemas
  directly (`§0.7`) and speaks OpenAI + Anthropic client wire styles (`§8`) — not tied to
  one SDK's abstraction.
- **Tiny footprint, low overhead.** A focused core with no platform baggage. A separate
  mock-LLM benchmark measures per-language overhead vs competitors — **see the benchmark**
  (no numbers invented here).
- **One unified `Tool` interface.** MCP, `SKILL.md` skills, native functions, HTTP
  endpoints, and remote A2A agents are all the same `Tool` to the LLM (`§0.1`, `§4`) —
  one mental model, one execution path.
- **Embeddable, not a platform.** You drop it into an existing app in any of six
  languages; you are not adopting a framework, a dev server, or a deploy pipeline.
- **Inbound + outbound symmetry.** The same toolkit you consume can be `serve`d as an A2A
  agent or an MCP server (`§7B`, `§7C`).

## Which should you pick?

- **All-in on TypeScript/Node and you want the whole stack** — workflows, RAG, evals, a
  playground, and one-command deploy → **Mastra**. It is the more complete product for that
  world.
- **You need the same agent in Python *and* Go *and* Java (or Elixir/C#)** → **toolnexus**.
  Byte-parity across six languages is its whole reason to exist; Mastra cannot do this.
- **You want a minimal, embeddable tool/agent core** — no framework lock-in, no dev
  server, low overhead, drop into an existing service → **toolnexus**.
- **You want vendor-neutral tool schemas** across OpenAI/Anthropic/Gemini without adopting
  a specific SDK's agent abstraction → **toolnexus**. If you're happy standardizing on the
  Vercel AI SDK, Mastra's integration is deeper.
- **You need durable workflows, retrieval, or automated evals** → **Mastra** today.
  toolnexus does not provide these and does not intend to.

---

### Claims I could not fully verify

- **Mastra model-provider breadth**: inferred from the many `@ai-sdk/*` provider deps in
  `packages/core/package.json`; I did not enumerate every provider.
- **Mastra memory "semantic recall / storage adapters"**: `@mastra/memory` exists with
  `processors` and `tools`, and Mastra's architecture is storage-adapter based, but I read
  the package layout, not each adapter, so treat the specific feature wording as
  directional.
- **Mastra skills parity**: `packages/core/src/skills` exists; I did not confirm it
  implements the same Anthropic `SKILL.md` progressive-disclosure format toolnexus pins in
  `§3`, so I marked it ⚠️ rather than ✅.
- Package `description` fields in Mastra are empty, so package purposes are inferred from
  directory contents and names.
