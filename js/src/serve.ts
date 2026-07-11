/**
 * A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent.
 *
 * `toolkit.serve(addr, { client, a2a })` stands up a minimal HTTP server that,
 * when the `a2a` profile is present, exposes:
 *
 *   GET  /.well-known/agent-card.json  → an Agent Card built from the toolkit's
 *        **skills** (SKILL.md name+description), never raw tools.
 *   POST /                             → JSON-RPC 2.0: `SendMessage` (submit a
 *        Task, fulfil it asynchronously via `client.run`) + `GetTask` (poll).
 *
 * This is the inbound counterpart to a2a.ts (outbound) and speaks the same wire
 * subset (verified against a2a-python). Task persistence is a pluggable
 * `TaskStore` (in-memory default; file / custom selectable via `a2a.store`).
 * See ../../SPEC.md and openspec/changes/add-a2a-agents.
 */
import http from "node:http"
import fs from "node:fs"
import path from "node:path"
import { randomUUID } from "node:crypto"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { sanitize, type Tool } from "./types.js"
import type { SkillInfo } from "./skill.js"
import type { RunResult } from "./client.js"
import type { A2AArtifact, A2ATask, A2ATaskState, AgentCard } from "./a2a.js"
import { buildMcpServer, exposedMcpTools, type MCPServeConfig, type OnCall } from "./mcpserve.js"

// ---------------------------------------------------------------------------
// TaskStore adapter — pluggable persistence for served Tasks.
// ---------------------------------------------------------------------------

/** Pluggable Task persistence. The-factory can plug NATS/JetStream the same way. */
export interface TaskStore {
  get(id: string): Promise<A2ATask | undefined>
  save(task: A2ATask): Promise<void>
}

/** Default in-memory store — Tasks live only for the lifetime of the process. */
export class InMemoryTaskStore implements TaskStore {
  private readonly tasks = new Map<string, A2ATask>()
  async get(id: string): Promise<A2ATask | undefined> {
    return this.tasks.get(id)
  }
  async save(task: A2ATask): Promise<void> {
    this.tasks.set(task.id, task)
  }
}

/** File-backed store — one JSON file per Task id, under `dir`. */
export class FileTaskStore implements TaskStore {
  constructor(private readonly dir: string) {
    fs.mkdirSync(dir, { recursive: true })
  }
  private file(id: string): string {
    return path.join(this.dir, `${sanitize(id)}.json`)
  }
  async get(id: string): Promise<A2ATask | undefined> {
    try {
      return JSON.parse(fs.readFileSync(this.file(id), "utf8")) as A2ATask
    } catch {
      return undefined
    }
  }
  async save(task: A2ATask): Promise<void> {
    fs.writeFileSync(this.file(task.id), JSON.stringify(task))
  }
}

/** Map a `store` selector to a concrete TaskStore. `"file:<dir>"` → FileTaskStore. */
export function resolveStore(store?: TaskStore | "memory" | string): TaskStore {
  if (store === undefined || store === "memory") return new InMemoryTaskStore()
  if (typeof store === "string") {
    if (store.startsWith("file:")) return new FileTaskStore(store.slice("file:".length))
    throw new Error(`Unknown A2A store: "${store}" (expected "memory" or "file:<dir>")`)
  }
  return store
}

// ---------------------------------------------------------------------------
// A2A serve config + handle.
// ---------------------------------------------------------------------------

export interface A2AProvider {
  organization: string
  url: string
}

/** Opt-in A2A profile for `toolkit.serve` — configures the Agent Card + store. */
export interface A2AConfig {
  name?: string
  description?: string
  version?: string
  provider?: A2AProvider
  /** Subset of the toolkit's skill ids/names to advertise; omit ⇒ all. */
  skills?: string[]
  /** `"memory"` (default) | `"file:<dir>"` | a custom TaskStore. */
  store?: TaskStore | "memory" | string
}

/** Surfaced on each Task's terminal state, carrying the RunResult telemetry. */
export interface OnTaskEvent {
  id: string
  skill?: string
  task: A2ATask
  result?: RunResult
  state: A2ATaskState
}

export type OnTask = (ev: OnTaskEvent) => void | Promise<void>

/** Handle returned by `toolkit.serve` — the base URL plus a stop/close method. */
export interface ServeHandle {
  /** Base URL of the server, e.g. `http://127.0.0.1:PORT`. */
  url: string
  stop(): Promise<void>
  /** Alias for `stop()`. */
  close(): Promise<void>
}

// ---------------------------------------------------------------------------
// Card builder.
// ---------------------------------------------------------------------------

const PROTOCOL_VERSION = "0.3.0"
const DEFAULT_VERSION = "0.1.0"

/**
 * Build an Agent Card from the toolkit's skills. `skills` are the SkillSource's
 * `SkillInfo`s (never raw tools); filtered to `cfg.skills` when given. `url` is
 * the JSON-RPC endpoint peers POST to.
 */
export function buildAgentCard(cfg: A2AConfig, skills: SkillInfo[], url: string): AgentCard {
  const wanted = cfg.skills
  const selected = wanted ? skills.filter((s) => wanted.includes(s.name)) : skills
  const card: AgentCard = {
    name: cfg.name ?? "toolnexus-agent",
    description: cfg.description ?? "",
    version: cfg.version ?? DEFAULT_VERSION,
    protocolVersion: PROTOCOL_VERSION,
    capabilities: { streaming: false, pushNotifications: false },
    defaultInputModes: ["text"],
    defaultOutputModes: ["text"],
    skills: selected.map((s) => ({ id: s.name, name: s.name, description: s.description ?? "" })),
    url,
  }
  if (cfg.provider) card.provider = cfg.provider
  return card
}

// ---------------------------------------------------------------------------
// Server.
// ---------------------------------------------------------------------------

const CARD_PATH = "/.well-known/agent-card.json"

export interface StartServerOptions {
  /** `host:port` (port 0 ⇒ ephemeral). */
  addr: string
  /** When present, mounts the A2A card + JSON-RPC routes; absent ⇒ no A2A routes. */
  a2a?: A2AConfig
  /** The toolkit's skills, for the card. */
  skills: SkillInfo[]
  /** Runs one served Task through the client loop. `contextId` (from the A2A
   * message) keys the conversation so served turns are remembered via the store. */
  runTask: (text: string, contextId?: string) => Promise<RunResult>
  onTask?: OnTask
  /** When present, mounts a streamable-HTTP MCP server at `/mcp`; absent ⇒ no MCP route. */
  mcp?: MCPServeConfig
  /** The toolkit's unified tools, exposed by the MCP profile. */
  mcpTools?: Tool[]
  /** Surfaced on each inbound MCP `tools/call`. */
  onCall?: OnCall
}

/** Extract the concatenated text of a JSON-RPC `SendMessage` message's parts. */
function messageText(params: any): string {
  const parts = params?.message?.parts
  if (!Array.isArray(parts)) return ""
  return parts
    .filter((p: any) => p && p.kind === "text" && typeof p.text === "string")
    .map((p: any) => p.text)
    .join("")
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => resolve(body))
  })
}

function safeJson(body: string): unknown {
  try {
    return JSON.parse(body)
  } catch {
    return undefined
  }
}

/**
 * Start the HTTP server. Delegated to by `Toolkit.serve`. When `a2a` is absent,
 * the server answers 404 to everything (a minimal base for now).
 */
export async function startA2AServer(opts: StartServerOptions): Promise<ServeHandle> {
  const { a2a, skills, runTask, onTask, mcp, mcpTools, onCall } = opts
  const [host, portStr] = splitAddr(opts.addr)
  const store = a2a ? resolveStore(a2a.store) : undefined
  const exposedTools = mcp ? exposedMcpTools(mcpTools ?? [], mcp) : []

  const server = http.createServer((req, res) => {
    const rpcRes = (id: unknown, payload: object) => {
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify({ jsonrpc: "2.0", id: id ?? null, ...payload }))
    }
    const rpcOk = (id: unknown, result: unknown) => rpcRes(id, { result })
    const rpcErr = (id: unknown, code: number, message: string) => rpcRes(id, { error: { code, message } })

    // No profile at all ⇒ no routes.
    if (!store && !mcp) {
      res.writeHead(404)
      res.end("not found")
      return
    }

    // MCP streamable-HTTP profile (independent of A2A): a fresh server+transport
    // per request (stateless — sessionIdGenerator undefined). Checked first so
    // `/mcp` is never shadowed by the A2A POST handler.
    if (mcp && req.url && (req.url === "/mcp" || req.url.startsWith("/mcp?"))) {
      void (async () => {
        const body = req.method === "POST" ? await readBody(req) : ""
        const parsed = body ? safeJson(body) : undefined
        const mcpServer = buildMcpServer(exposedTools, mcp, onCall)
        const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
        res.on("close", () => {
          void transport.close()
          void mcpServer.close()
        })
        await mcpServer.connect(transport)
        await transport.handleRequest(req, res, parsed)
      })().catch(() => {
        if (!res.headersSent) {
          res.writeHead(500)
          res.end("mcp error")
        }
      })
      return
    }

    // No A2A profile ⇒ no A2A routes (an MCP-only server 404s everything else).
    if (!a2a || !store) {
      res.writeHead(404)
      res.end("not found")
      return
    }

    if (req.method === "GET" && req.url === CARD_PATH) {
      const url = baseUrl(host, server) + "/"
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify(buildAgentCard(a2a, skills, url)))
      return
    }

    if (req.method === "POST") {
      readBody(req).then((body) => {
        let rpc: any
        try {
          rpc = JSON.parse(body)
        } catch {
          return rpcErr(null, -32700, "Parse error")
        }
        if (rpc?.method === "SendMessage") {
          const text = messageText(rpc.params)
          const contextId = typeof rpc.params?.message?.contextId === "string" ? rpc.params.message.contextId : undefined
          const id = randomUUID()
          const submitted: A2ATask = { id, contextId, status: { state: "submitted" } }
          // Persist submitted, kick fulfilment async, return the id immediately.
          store.save(submitted).then(() => {
            void fulfil(id, text, store, runTask, onTask, contextId)
            rpcOk(rpc.id, submitted)
          })
          return
        }
        if (rpc?.method === "GetTask") {
          const id = String(rpc.params?.id ?? "")
          store.get(id).then((task) => {
            if (!task) return rpcErr(rpc.id, -32001, `Task not found: ${id}`)
            rpcOk(rpc.id, task)
          })
          return
        }
        return rpcErr(rpc?.id ?? null, -32601, `Method not found: ${rpc?.method}`)
      })
      return
    }

    res.writeHead(404)
    res.end("not found")
  })

  await new Promise<void>((resolve) => server.listen(Number(portStr), host, resolve))

  return {
    url: baseUrl(host, server),
    stop() {
      return new Promise<void>((resolve) => server.close(() => resolve()))
    },
    close() {
      return new Promise<void>((resolve) => server.close(() => resolve()))
    },
  }
}

/**
 * Background fulfilment: submitted → working → (completed | failed). Never
 * throws — a fulfilment error becomes a `failed` Task, keeping the server alive.
 */
async function fulfil(
  id: string,
  text: string,
  store: TaskStore,
  runTask: (text: string, contextId?: string) => Promise<RunResult>,
  onTask?: OnTask,
  contextId?: string,
): Promise<void> {
  let task: A2ATask
  let result: RunResult | undefined
  let state: A2ATaskState
  try {
    await store.save({ id, status: { state: "working" } })
    // contextId groups a peer's turns into one conversation — thread it so the
    // client's ConversationStore remembers across tasks in the same context.
    result = await runTask(text, contextId)
    if (result.status === "pending" && result.pending) {
      // §10 suspension over A2A: the run halted waiting on out-of-band input. Surface the
      // protocol's `input-required` state carrying the request prompt — never a false `completed`.
      task = {
        id,
        status: { state: "input-required", message: { role: "agent", parts: [{ kind: "text", text: result.pending.prompt }] } },
      }
      state = "input-required"
    } else {
      const artifact: A2AArtifact = {
        artifactId: randomUUID(),
        parts: [{ kind: "text", text: result.text }],
      }
      task = { id, status: { state: "completed" }, artifacts: [artifact] }
      state = "completed"
    }
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e)
    task = { id, status: { state: "failed", message: { role: "agent", parts: [{ kind: "text", text: detail }] } } }
    state = "failed"
  }
  try {
    await store.save(task)
  } catch {
    // A store write failure must not crash the server.
  }
  if (onTask) {
    try {
      await onTask({ id, task, result, state })
    } catch {
      // Host callback errors are isolated.
    }
  }
}

// ---------------------------------------------------------------------------
// Address helpers.
// ---------------------------------------------------------------------------

/** Split `host:port` on the last colon. Missing host ⇒ 127.0.0.1. */
function splitAddr(addr: string): [string, string] {
  const idx = addr.lastIndexOf(":")
  if (idx === -1) return ["127.0.0.1", addr]
  const host = addr.slice(0, idx) || "127.0.0.1"
  return [host, addr.slice(idx + 1)]
}

/** Base URL for a listening server; 0.0.0.0 is reported as 127.0.0.1 for callers. */
function baseUrl(host: string, server: http.Server): string {
  const addr = server.address()
  const port = typeof addr === "object" && addr ? addr.port : 0
  const h = host === "0.0.0.0" || host === "::" ? "127.0.0.1" : host
  return `http://${h}:${port}`
}
