/**
 * Dynamic MCP source. Mirrors opencode's mcp/index.ts + mcp/catalog.ts, but
 * standalone (no Effect): connect to every configured server, list tools, and
 * convert each into a uniform Tool.
 */
import { readFileSync } from "node:fs"
import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js"
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js"
import { SSEClientTransport } from "@modelcontextprotocol/sdk/client/sse.js"
import type { Tool as McpToolDef, ElicitRequest, ElicitResult } from "@modelcontextprotocol/sdk/types.js"
import { ElicitRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { type JSONSchema, type Tool, type ToolContext, type ToolResult, type McpStatus, type Request, type Answer, sanitize } from "./types.js"

// ---------------------------------------------------------------------------
// MCP elicitation bridge (§10). A server can ask US for input mid-`tools/call`
// (a reverse-request). We map it onto the one `waitFor`: form mode → kind:"input",
// URL mode → kind:"authorization". The two mappings are pure + byte-parity-tested.
// ---------------------------------------------------------------------------

let _elcSeq = 0
/** Map an MCP `elicitation/create` request onto a §10 `Request`. */
export function elicitationToRequest(params: ElicitRequest["params"]): Request {
  const p = params as { message?: string; requestedSchema?: unknown; mode?: string; url?: string }
  const isUrl = p.mode === "url"
  const req: Request = {
    id: `elc-${Date.now().toString(36)}-${++_elcSeq}`,
    kind: isUrl ? "authorization" : "input",
    prompt: String(p.message ?? ""),
  }
  if (isUrl && p.url) req.url = p.url
  if (!isUrl && p.requestedSchema) req.data = { schema: p.requestedSchema }
  return req
}

/** Map a resolved §10 `Answer` back onto an MCP `ElicitResult`. ok→accept; declined→decline; else→cancel. */
export function answerToElicitResult(answer: Answer): ElicitResult {
  if (answer.ok) return { action: "accept", content: (answer.data ?? {}) as ElicitResult["content"] }
  return { action: answer.reason === "declined" ? "decline" : "cancel" }
}

const DEFAULT_TIMEOUT = 30_000
const MAX_LIST_PAGES = 1_000

export interface LocalServer {
  type?: "local"
  command: string[]
  environment?: Record<string, string>
  env?: Record<string, string>
  cwd?: string
  enabled?: boolean
  disabled?: boolean
  timeout?: number
  /** §2 Gap 7. Per-server tool allowlist keyed on ORIGINAL tool name; same semantics as the
   * builtins/skills filter (nil/empty ⇒ all; ≥1 true ⇒ allowlist; only-false ⇒ drop-list; unknown
   * ⇒ ignore+warn). Applied to the listed defs before sanitize/prefix. */
  tools?: Record<string, boolean>
}

export interface RemoteServer {
  type?: "remote"
  url: string
  headers?: Record<string, string>
  enabled?: boolean
  disabled?: boolean
  timeout?: number
  /** §2 Gap 7. Per-server tool allowlist (see LocalServer.tools). */
  tools?: Record<string, boolean>
}

export type ServerConfig = LocalServer | RemoteServer
export type McpConfig = Record<string, ServerConfig>

function isRemote(cfg: ServerConfig): cfg is RemoteServer {
  if (cfg.type === "remote") return true
  if (cfg.type === "local") return false
  return "url" in cfg && typeof (cfg as RemoteServer).url === "string"
}

function isEnabled(cfg: ServerConfig): boolean {
  if (cfg.disabled === true) return false
  if (cfg.enabled === false) return false
  return true
}

/** Accept a path, a raw object, or an object wrapped under mcpServers/servers/mcp. */
export function parseMcpConfig(input: string | object): McpConfig {
  const raw: any = typeof input === "string" ? JSON.parse(readFileSync(input, "utf8")) : input
  const wrapped = raw.mcpServers ?? raw.servers ?? raw.mcp
  if (wrapped !== undefined) return wrapped as McpConfig
  // Bare map of servers — but strip sibling top-level config keys (builtins/
  // agents/a2a/mcpServer) so they are not mistaken for MCP servers when no
  // wrapper key is present.
  const { builtins: _builtins, agents: _agents, a2a: _a2a, mcpServer: _mcpServer, ...servers } = raw
  return servers as McpConfig
}

/**
 * Expand ${ENV_VAR} in header values from process.env so tokens live in the
 * environment, not the committed config. Values are never logged.
 */
export function expandEnvHeaders(headers?: Record<string, string>): Record<string, string> | undefined {
  if (!headers) return undefined
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(headers)) {
    out[k] = v.replace(/\$\{([A-Za-z0-9_]+)\}/g, (_, name) => process.env[name] ?? "")
  }
  return out
}

function formatToolErrorContent(content: unknown): string {
  if (!Array.isArray(content)) return "MCP tool returned an error"
  return (
    content
      .flatMap((item) => (isTextContent(item) ? [item.text] : []))
      .filter((text) => text.trim())
      .join("\n\n") || "MCP tool returned an error"
  )
}

function isTextContent(value: unknown): value is { type: "text"; text: string } {
  return (
    typeof value === "object" &&
    value !== null &&
    (value as any).type === "text" &&
    typeof (value as any).text === "string"
  )
}

function joinTextContent(content: unknown): string {
  if (!Array.isArray(content)) return ""
  return content
    .flatMap((item) => (isTextContent(item) ? [item.text] : []))
    .join("\n")
}

/**
 * §2 Gap 7. Apply a per-server tool allowlist to the listed defs. Same semantics as the builtins
 * and skills filters: nil/empty ⇒ all; ≥1 true ⇒ allowlist (only true names); only-false ⇒ drop-list
 * over all-on; unknown names ignored + warned once. Keyed on ORIGINAL tool name.
 */
function applyToolsFilter(server: string, defs: McpToolDef[], filter?: Record<string, boolean>): McpToolDef[] {
  if (!filter) return defs
  const keys = Object.keys(filter)
  if (keys.length === 0) return defs
  const hasTrue = keys.some((k) => filter[k] === true)
  const present = new Set(defs.map((d) => d.name))
  for (const k of keys) {
    if (!present.has(k)) console.warn(`[toolnexus] server "${server}" tools filter name "${k}" matched no tool`)
  }
  return defs.filter((d) => (hasTrue ? filter[d.name] === true : filter[d.name] !== false))
}

/**
 * §2 Gap 3. Race a promise against a per-server timeout and the caller's abort signal, so a hung
 * connect/list is bounded (the SSE fallback included) and a parent cancellation aborts promptly.
 */
function raceTimeout<T>(p: Promise<T>, ms: number, signal?: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => { cleanup(); reject((signal as any)?.reason ?? new Error("aborted")) }
    const t = setTimeout(() => { cleanup(); reject(new Error("mcp connect/list timeout")) }, ms)
    const cleanup = () => { clearTimeout(t); signal?.removeEventListener("abort", onAbort) }
    if (signal) {
      if (signal.aborted) return onAbort()
      signal.addEventListener("abort", onAbort, { once: true })
    }
    p.then((v) => { cleanup(); resolve(v) }, (e) => { cleanup(); reject(e) })
  })
}

async function paginateTools(client: Client, timeout: number): Promise<McpToolDef[]> {
  const result: McpToolDef[] = []
  const cursors = new Set<string>()
  let cursor: string | undefined
  for (let page = 0; page < MAX_LIST_PAGES; page++) {
    const res = await client.listTools(cursor === undefined ? undefined : { cursor }, { timeout })
    result.push(...res.tools)
    if (res.nextCursor === undefined) return result
    if (cursors.has(res.nextCursor)) throw new Error(`MCP list returned duplicate cursor: ${res.nextCursor}`)
    cursors.add(res.nextCursor)
    cursor = res.nextCursor
  }
  throw new Error(`MCP list exceeded ${MAX_LIST_PAGES} pages`)
}

function convertTool(server: string, def: McpToolDef, client: Client, timeout: number): Tool {
  const inputSchema: JSONSchema = {
    ...(def.inputSchema as object),
    type: "object",
    properties: (def.inputSchema?.properties ?? {}) as Record<string, unknown>,
    additionalProperties: false,
  }
  return {
    name: sanitize(server) + "_" + sanitize(def.name),
    description: def.description ?? "",
    inputSchema,
    source: "mcp",
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      try {
        const result: any = await client.callTool(
          { name: def.name, arguments: args ?? {} },
          undefined,
          { resetTimeoutOnProgress: true, signal: ctx?.signal, timeout: ctx?.timeout ?? timeout },
        )
        if (result.isError) {
          return { output: formatToolErrorContent(result.content), isError: true, metadata: { server } }
        }
        if (result.structuredContent !== undefined && result.structuredContent !== null) {
          return { output: JSON.stringify(result.structuredContent), isError: false, metadata: { server } }
        }
        return { output: joinTextContent(result.content), isError: false, metadata: { server } }
      } catch (e) {
        return { output: e instanceof Error ? e.message : String(e), isError: true, metadata: { server } }
      }
    },
  }
}

export interface McpSource {
  tools: Tool[]
  status: Record<string, McpStatus>
  close(): Promise<void>
}

/** Connect to every enabled server, list + convert tools. Failures are isolated. */
/** Connect a fresh client to one server (remote streamable-HTTP with SSE fallback, or local stdio). */
async function connectServer(name: string, cfg: ServerConfig, waitFor?: (request: Request) => Promise<Answer>): Promise<Client> {
  const client = new Client({ name: "toolnexus", version: "0.1.0" }, { capabilities: waitFor ? { elicitation: {} } : {} })
  if (waitFor) {
    client.setRequestHandler(ElicitRequestSchema, async (request) =>
      answerToElicitResult(await waitFor(elicitationToRequest(request.params))),
    )
  }
  if (isRemote(cfg)) {
    const url = new URL(cfg.url)
    const expanded = expandEnvHeaders(cfg.headers)
    const requestInit = expanded ? { headers: expanded } : undefined
    try {
      await client.connect(new StreamableHTTPClientTransport(url, { requestInit }))
    } catch {
      await client.connect(new SSEClientTransport(url, { requestInit }))
    }
  } else {
    const local = cfg as LocalServer
    const [cmd, ...args] = local.command
    await client.connect(
      new StdioClientTransport({
        command: cmd,
        args,
        cwd: local.cwd,
        env: { ...(process.env as Record<string, string>), ...(local.environment ?? local.env ?? {}) },
        stderr: "pipe",
      }),
    )
  }
  return client
}

/** Connect to every enabled server, list + convert tools. Failures are isolated.
 *  §2 Gap 3: an optional `signal` bounds each connect/list by the server timeout and aborts the
 *  whole load promptly on parent cancellation. */
export async function loadMcp(
  input: string | object,
  opts?: { waitFor?: (request: Request) => Promise<Answer>; signal?: AbortSignal },
): Promise<McpSource> {
  const config = parseMcpConfig(input)
  const tools: Tool[] = []
  const status: Record<string, McpStatus> = {}
  const clients: Client[] = []
  const waitFor = opts?.waitFor
  const signal = opts?.signal

  await Promise.all(
    Object.entries(config).map(async ([name, cfg]) => {
      if (!isEnabled(cfg)) {
        status[name] = "disabled"
        return
      }
      const timeout = cfg.timeout ?? DEFAULT_TIMEOUT
      let client: Client | undefined
      try {
        client = await raceTimeout(connectServer(name, cfg, waitFor), timeout, signal)
        const defs = await raceTimeout(paginateTools(client, timeout), timeout, signal)
        const kept = applyToolsFilter(name, defs, (cfg as { tools?: Record<string, boolean> }).tools)
        for (const def of kept) tools.push(convertTool(name, def, client, timeout))
        clients.push(client)
        status[name] = "connected"
      } catch (e) {
        status[name] = "failed"
        await client?.close().catch(() => {})
        // Parent cancellation/deadline aborts the whole load (§2 Gap 3, A1); a per-server timeout
        // within budget isolates just this server.
        if (signal?.aborted) throw e
        console.warn(`[toolnexus] MCP server "${name}" failed: ${e instanceof Error ? e.message : String(e)}`)
      }
    }),
  )

  return {
    tools,
    status,
    async close() {
      await Promise.all(clients.map((c) => c.close().catch(() => {})))
    },
  }
}

/** §2 Gap 6. One listed tool definition (original, unprefixed name). */
export interface ToolInfo {
  name: string
  description: string
  inputSchema: JSONSchema
}

/** §2 Gap 6. Result of a list-only pass over an MCP config. */
export interface McpInventory {
  /** server → its full listed tool defs, UNFILTERED by ServerConfig.tools (authors the allowlist). */
  tools: Record<string, ToolInfo[]>
  status: Record<string, McpStatus>
}

/**
 * §2 Gap 6. Connect to every enabled server, list tool definitions, and DISCONNECT before
 * returning — no toolkit, no execute wiring, nothing left running. Failure isolation as in loadMcp.
 */
export async function listMcpTools(input: string | object, opts?: { signal?: AbortSignal }): Promise<McpInventory> {
  const config = parseMcpConfig(input)
  const toolsByServer: Record<string, ToolInfo[]> = {}
  const status: Record<string, McpStatus> = {}
  const signal = opts?.signal

  await Promise.all(
    Object.entries(config).map(async ([name, cfg]) => {
      if (!isEnabled(cfg)) {
        status[name] = "disabled"
        return
      }
      const timeout = cfg.timeout ?? DEFAULT_TIMEOUT
      let client: Client | undefined
      try {
        client = await raceTimeout(connectServer(name, cfg), timeout, signal)
        const defs = await raceTimeout(paginateTools(client, timeout), timeout, signal)
        toolsByServer[name] = defs.map((d) => ({
          name: d.name,
          description: d.description ?? "",
          inputSchema: {
            ...(d.inputSchema as object),
            type: "object",
            properties: (d.inputSchema?.properties ?? {}) as Record<string, unknown>,
          } as JSONSchema,
        }))
        status[name] = "connected"
      } catch (e) {
        status[name] = "failed"
        if (signal?.aborted) { await client?.close().catch(() => {}); throw e }
        console.warn(`[toolnexus] MCP server "${name}" failed: ${e instanceof Error ? e.message : String(e)}`)
      } finally {
        await client?.close().catch(() => {})
      }
    }),
  )

  return { tools: toolsByServer, status }
}
