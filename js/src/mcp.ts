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
import { type JSONSchema, type Tool, type ToolContext, type ToolHints, type ToolResult, type McpStatus, type Request, type Answer, sanitize } from "./types.js"
import { type ContentPart, summarizePart } from "./content.js"
import type { SkillDef } from "./skill.js"
import { probe, newOAuth, defaultTokenStore, type AuthConfig, type ModernServer, type Load2026Options } from "./mcp2026/source.js"
import { PendingError, type OAuth, type TokenStore } from "./mcp2026/auth.js"
export { Token, MemoryTokenStore, type TokenStore } from "./mcp2026/auth.js"
export type { AuthConfig } from "./mcp2026/source.js"

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
  /** §2.8 lazy tool search: expose `<server>_search_tools` + `<server>_call_tool` instead of every tool. */
  toolSearch?: boolean
  /** §2.8 what this server is for (shown by search_tools). */
  description?: string
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
  /** §2.8 lazy tool search (see LocalServer.toolSearch). */
  toolSearch?: boolean
  description?: string
  /** §2A OAuth 2.1 client settings. Absent ⇒ a 401 still triggers the flow (CIMD if the AS supports it). */
  auth?: AuthConfig
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
  if (wrapped !== undefined) return normalizeServers(wrapped)
  // Bare map of servers — but strip sibling top-level config keys (builtins/
  // agents/a2a/mcpServer) so they are not mistaken for MCP servers when no
  // wrapper key is present.
  const { builtins: _builtins, agents: _agents, a2a: _a2a, mcpServer: _mcpServer, ...servers } = raw
  return normalizeServers(servers)
}

/**
 * §2 `command` + `args`. A local server's `command` may be a non-empty string or a non-empty
 * string array; an optional `args` string array is appended. The result is always the argv form
 * (`command: string[]`, no `args`). Spawned with no shell. A remote server ignores `args`.
 */
function normalizeServers(servers: Record<string, any>): McpConfig {
  const out: McpConfig = {}
  for (const [name, cfg] of Object.entries(servers ?? {})) {
    if (cfg === null || typeof cfg !== "object" || isRemote(cfg as ServerConfig)) {
      out[name] = cfg
      continue
    }
    const { args, command, ...rest } = cfg as any
    const head: unknown[] | undefined =
      typeof command === "string" ? [command] : Array.isArray(command) ? command : undefined
    if (!head || head.length === 0 || head[0] === "" || head.some((c) => typeof c !== "string")) {
      throw new Error(`mcp server "${name}": invalid command`)
    }
    if (args !== undefined && (!Array.isArray(args) || args.some((a: unknown) => typeof a !== "string"))) {
      throw new Error(`mcp server "${name}": invalid args`)
    }
    out[name] = { ...rest, command: [...(head as string[]), ...((args as string[] | undefined) ?? [])] }
  }
  return out
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

/**
 * §0.4 / §2. Map every non-text `content[]` entry to a `ContentPart`, so nothing an MCP
 * server returns is dropped silently: image ⇒ image, audio ⇒ audio, `resource_link` ⇒
 * `file{url}`, an embedded `resource` carrying a blob ⇒ `file{data}`. An embedded resource
 * carrying *text* is not a part — it belongs in `output`, and {@link joinTextContent}
 * takes it there.
 */
export function mcpContentToParts(content: unknown): ContentPart[] | undefined {
  if (!Array.isArray(content)) return undefined
  const parts: ContentPart[] = []
  for (const item of content) {
    const it = item as any
    if (!it || typeof it !== "object") continue
    // `data` / `blob` are base64 STRINGS in the SDK, never bytes — passed through verbatim
    // so no encoder of ours can drift them away from what the server sent.
    if (it.type === "image" && typeof it.data === "string") {
      parts.push({ type: "image", mimeType: String(it.mimeType ?? "application/octet-stream"), data: it.data })
    } else if (it.type === "audio" && typeof it.data === "string") {
      parts.push({ type: "audio", mimeType: String(it.mimeType ?? "application/octet-stream"), data: it.data })
    } else if (it.type === "resource_link" && it.uri) {
      const part: ContentPart = { type: "file", mimeType: String(it.mimeType ?? "application/octet-stream"), url: String(it.uri) }
      if (it.name) (part as any).name = String(it.name)
      parts.push(part)
    } else if (it.type === "resource" && typeof it.resource?.blob === "string") {
      const part: ContentPart = { type: "file", mimeType: String(it.resource.mimeType ?? "application/octet-stream"), data: it.resource.blob }
      if (it.resource.uri) (part as any).name = String(it.resource.uri)
      parts.push(part)
    }
  }
  return parts.length ? parts : undefined
}

/** An image-only result must not answer the model with "". Name what came back instead. */
function describeMcpParts(parts: ContentPart[] | undefined): string {
  return parts ? parts.map(summarizePart).join("\n") : ""
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

/** An embedded resource carrying text is text: it joins `output` in place, keeping the
 *  server's ordering, rather than being discarded with the other non-text entries. */
function isResourceText(value: unknown): value is { resource: { text: string } } {
  const v = value as any
  return v?.type === "resource" && typeof v?.resource?.text === "string"
}

function joinTextContent(content: unknown): string {
  if (!Array.isArray(content)) return ""
  return content
    .flatMap((item) => (isTextContent(item) ? [item.text] : isResourceText(item) ? [item.resource.text] : []))
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

/** §2 `tools/call` result → ToolResult (shared by the 2026 path and the frozen fallback). */
export function mapCallToolResult(result: any, server: string): ToolResult {
  // Parts are collected BEFORE any branch returns: the isError and structuredContent
  // short-circuits both read `content[]` on the way past, so neither reintroduces the
  // silent drop this mapping exists to remove (§2).
  const parts = mcpContentToParts(result.content)
  const withParts = (r: ToolResult): ToolResult => (parts ? { ...r, parts } : r)
  if (result.isError) {
    return withParts({ output: formatToolErrorContent(result.content), isError: true, metadata: { server } })
  }
  if (result.structuredContent !== undefined && result.structuredContent !== null) {
    return withParts({ output: JSON.stringify(result.structuredContent), isError: false, metadata: { server } })
  }
  const text = joinTextContent(result.content)
  return withParts({ output: text || describeMcpParts(parts), isError: false, metadata: { server } })
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
    ...(mcpHints((def as any).annotations) ? { hints: mcpHints((def as any).annotations) } : {}),
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      try {
        const result: any = await client.callTool(
          { name: def.name, arguments: args ?? {} },
          undefined,
          // The SDK only attaches `_meta.progressToken` when `onprogress` is supplied; without it
          // `resetTimeoutOnProgress` never fires (add-mcp-progress-timeout). Notifications for a
          // token that matches no in-flight request are dropped by the SDK, so they never reset.
          { onprogress: () => {}, resetTimeoutOnProgress: true, signal: ctx?.signal, timeout: ctx?.timeout ?? timeout },
        )
        return mapCallToolResult(result, server)
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
  /**
   * `list_mcp_resources` and `read_mcp_resource` bound to this source's connected servers
   * (SPEC §4C). Both read-only. Opt-in: register them on a toolkit. They stop working once the
   * source is closed.
   */
  resourceTools(): Tool[]
  /** §2.7 skills served by 2026 servers (skills extension); the toolkit adds them to the `skill` tool. */
  skills: SkillDef[]
}

/** One server's outcome, reported by `loadMcp`'s `onServer` the moment that server finishes. */
export interface McpServerResult {
  name: string
  status: McpStatus
  /** This server's converted tools (empty unless connected). Usable until the source closes. */
  tools: Tool[]
  /** Why the server failed (absent when connected or disabled). */
  error?: Error
}

/**
 * MCP tool annotations → ToolHints (SPEC §4C). No hint fields at all ⇒ undefined (unknown).
 * Defaults follow the MCP spec: readOnlyHint false, destructiveHint and openWorldHint true.
 */
export function mcpHints(a: { readOnlyHint?: boolean; destructiveHint?: boolean; openWorldHint?: boolean } | undefined): ToolHints | undefined {
  if (!a || (a.readOnlyHint === undefined && a.destructiveHint === undefined && a.openWorldHint === undefined)) return undefined
  const h: ToolHints = { readOnly: a.readOnlyHint ?? false, destructive: a.destructiveHint ?? true, openWorld: a.openWorldHint ?? true }
  if (h.readOnly) h.destructive = false
  return h
}

interface ServerConn {
  listResources(signal?: AbortSignal): Promise<any[]>
  readResource(uri: string, signal?: AbortSignal): Promise<any[]>
}

function sdkConn(client: Client, timeout: number): ServerConn {
  return {
    listResources: async (signal) => ((await client.listResources({}, { timeout, signal })) as any).resources ?? [],
    readResource: async (uri, signal) => ((await client.readResource({ uri }, { timeout, signal })) as any).contents ?? [],
  }
}

function modernConn(s: ModernServer): ServerConn {
  return { listResources: (signal) => s.listResources(signal), readResource: (uri, signal) => s.readResource(uri, signal) }
}

function resourceTools(servers: Map<string, ServerConn>): Tool[] {
  const ro: ToolHints = { readOnly: true, destructive: false, openWorld: false }
  const names = () => [...servers.keys()].sort()
  const conn = (server: string): ServerConn => {
    const c = servers.get(server)
    if (!c) throw new Error(`no connected MCP server "${server}" (connected: ${names().join(", ")})`)
    return c
  }
  const list: Tool = {
    name: "list_mcp_resources",
    description: "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.",
    inputSchema: {
      type: "object",
      properties: { server: { type: "string", description: "Only this server (default: all)" } },
      additionalProperties: false,
    },
    source: "mcp",
    hints: ro,
    async execute(args, ctx) {
      let targets = names()
      const s = typeof args?.server === "string" ? args.server : ""
      if (s) {
        try {
          conn(s)
        } catch (e) {
          return { output: `list_mcp_resources: ${(e as Error).message}`, isError: true }
        }
        targets = [s]
      }
      const lines: string[] = []
      const items: unknown[] = []
      for (const name of targets) {
        const c = conn(name)
        try {
          for (const r of await c.listResources(ctx?.signal)) {
            let line = `${name} ${r.uri}`
            if (r.name) line += ` — ${r.name}`
            if (r.mimeType) line += ` (${r.mimeType})`
            if (r.description) line += `: ${r.description}`
            lines.push(line)
            items.push({ server: name, uri: r.uri, name: r.name ?? "", mimeType: r.mimeType ?? "", description: r.description ?? "" })
          }
        } catch (e) {
          lines.push(`${name}: error: ${e instanceof Error ? e.message : String(e)}`)
        }
      }
      if (!lines.length) return { output: "No resources.", isError: false, metadata: { count: 0 } }
      return { output: lines.join("\n"), isError: false, metadata: { count: items.length, resources: items } }
    },
  }
  const read: Tool = {
    name: "read_mcp_resource",
    description: "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.",
    inputSchema: {
      type: "object",
      properties: {
        server: { type: "string", description: "The MCP server name" },
        uri: { type: "string", description: "The resource uri" },
      },
      required: ["server", "uri"],
      additionalProperties: false,
    },
    source: "mcp",
    hints: ro,
    async execute(args, ctx) {
      const server = typeof args?.server === "string" ? args.server : ""
      const uri = typeof args?.uri === "string" ? args.uri : ""
      let c: ServerConn
      try {
        c = conn(server)
      } catch (e) {
        return { output: `read_mcp_resource: ${(e as Error).message}`, isError: true }
      }
      if (!uri) return { output: "read_mcp_resource: uri is required", isError: true }
      try {
        const parts: string[] = []
        for (const rc of await c.readResource(uri, ctx?.signal)) {
          if (typeof rc.text === "string") parts.push(rc.text)
          else if (typeof rc.blob === "string") parts.push(`[binary ${rc.mimeType ?? ""}, ${rc.blob.length} base64 chars]`)
        }
        return { output: parts.join("\n"), isError: false, metadata: { server, uri } }
      } catch (e) {
        return { output: `read_mcp_resource: ${e instanceof Error ? e.message : String(e)}`, isError: true }
      }
    },
  }
  return [list, read]
}

/** Create a fresh (not-yet-connected) client, with the elicitation bridge attached if a waitFor
 *  exists. Separated from connect so the caller ALWAYS holds a closeable reference — even if the
 *  connect is later abandoned by a timeout/abort, `client.close()` still terminates any spawned
 *  stdio child (no orphaned processes on cancellation). */
function newMcpClient(waitFor?: (request: Request) => Promise<Answer>): Client {
  const client = new Client({ name: "toolnexus", version: "0.1.0" }, { capabilities: waitFor ? { elicitation: {} } : {} })
  if (waitFor) {
    client.setRequestHandler(ElicitRequestSchema, async (request) =>
      answerToElicitResult(await waitFor(elicitationToRequest(request.params))),
    )
  }
  return client
}

/** Connect an existing client's transport (remote streamable-HTTP with SSE fallback, or local stdio). */
async function connectTransport(client: Client, cfg: ServerConfig, auth?: FallbackAuth): Promise<void> {
  if (isRemote(cfg)) {
    const url = new URL(cfg.url)
    const expanded = expandEnvHeaders(cfg.headers)
    const requestInit = expanded ? { headers: expanded } : undefined
    const fetch = auth ? bearerFetch(cfg.url, auth) : undefined
    try {
      await client.connect(new StreamableHTTPClientTransport(url, { requestInit, ...(fetch ? { fetch } : {}) }))
    } catch {
      await client.connect(new SSEClientTransport(url, { requestInit, ...(fetch ? { fetch } : {}) }))
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
}

interface FallbackAuth {
  oauth: OAuth
  waitFor?: (request: Request) => Promise<Answer>
}

/**
 * §2A.1a frozen fallback + OAuth: the SDK transport's fetch attaches the current bearer token
 * (refreshing as needed); a 401/403 challenge runs the same OAuth flow once, then the request is
 * re-sent. A pause is answered through waitFor, else the request fails ("authorization pending").
 */
function bearerFetch(resource: string, a: FallbackAuth): typeof globalThis.fetch {
  return async (input: any, init?: any) => {
    for (let attempt = 0; ; attempt++) {
      const headers = new Headers(init?.headers)
      const tok = await a.oauth.bearer(resource)
      if (tok) headers.set("Authorization", "Bearer " + tok)
      const res = await globalThis.fetch(input, { ...init, headers })
      const www = res.headers.get("www-authenticate") ?? ""
      if (attempt > 0 || (res.status !== 401 && res.status !== 403) || !www) return res
      try {
        await a.oauth.challenge(resource, res.status, www, tok, async () => ({}))
      } catch (e) {
        if (!(e instanceof PendingError)) throw e
        if (!a.waitFor) throw new Error("authorization pending")
        const ans = await a.waitFor({ ...e.pending })
        if (!ans?.ok) throw new Error(`authorization denied: ${ans?.reason ?? "cancelled"}`)
        const cb = ans.data?.callbackUrl
        if (typeof cb !== "string") throw new Error("authorization answer carries no data.callbackUrl")
        await a.oauth.resume(e.pending.id, cb)
      }
    }
  }
}

/** Options shared by loadMcp / listMcpTools for the 2026 path and OAuth (§2A.1a). */
export interface McpLoadOptions {
  waitFor?: (request: Request) => Promise<Answer>
  signal?: AbortSignal
  /** Where OAuth tokens live (default: in memory, per load). */
  tokenStore?: TokenStore
  /** How `auto` OAuth mode opens the authorization URL (default: the OS opener). */
  openUrl?: (url: string) => unknown | Promise<unknown>
}

type Connected =
  | { kind: "modern"; server: ModernServer }
  | { kind: "sdk"; client: Client; defs: McpToolDef[] }

/**
 * Discover first (§2.2): a 2026 server is used directly; an old one (§2.2a) gets the frozen SDK
 * path, with the OAuth bearer attached for remote servers. Throws ⇒ the server failed.
 */
async function connectServer(name: string, cfg: ServerConfig, timeout: number, opts: McpLoadOptions, store: TokenStore, sdkClient: () => Client, onSdk: (c: Client) => void): Promise<Connected> {
  const o: Load2026Options = { waitFor: opts.waitFor, tokenStore: store, openUrl: opts.openUrl, signal: opts.signal }
  const remote = isRemote(cfg)
  const oauth = remote ? newOAuth(cfg.auth, o, store) : undefined
  const p = await probe(
    {
      name, timeout, tools: cfg.tools, toolSearch: cfg.toolSearch === true, description: cfg.description,
      remote: remote ? { url: cfg.url, headers: expandEnvHeaders(cfg.headers), oauth: oauth! } : undefined,
      local: remote ? undefined : {
        argv: (cfg as LocalServer).command,
        env: { ...process.env, ...((cfg as LocalServer).environment ?? (cfg as LocalServer).env ?? {}) },
        cwd: (cfg as LocalServer).cwd,
      },
    },
    o,
  )
  if (p.kind === "modern") return p
  const client = sdkClient()
  onSdk(client)
  await raceTimeout(connectTransport(client, cfg, oauth ? { oauth, waitFor: opts.waitFor } : undefined), timeout, opts.signal)
  const defs = await raceTimeout(paginateTools(client, timeout), timeout, opts.signal)
  return { kind: "sdk", client, defs }
}

/** Connect to every enabled server, list + convert tools. Failures are isolated.
 *  §2 Gap 3: an optional `signal` bounds each connect/list by the server timeout and aborts the
 *  whole load promptly on parent cancellation. */
export async function loadMcp(
  input: string | object,
  opts?: McpLoadOptions & {
    /**
     * Called once per server the moment it is connected, failed or found disabled (SPEC §4C),
     * so a host can register a fast server's tools while a slow one is still connecting.
     * Calls are serialized (an async callback is awaited before the next one runs); the load
     * still resolves only after every server and every callback has finished.
     */
    onServer?: (result: McpServerResult) => void | Promise<void>
  },
): Promise<McpSource> {
  const config = parseMcpConfig(input)
  const tools: Tool[] = []
  const status: Record<string, McpStatus> = {}
  const clients: { close(): Promise<void> }[] = []
  const servers = new Map<string, ServerConn>()
  const skills: SkillDef[] = []
  const store = defaultTokenStore({ tokenStore: opts?.tokenStore })
  const waitFor = opts?.waitFor
  const signal = opts?.signal
  const onServer = opts?.onServer
  let reports: Promise<void> = Promise.resolve()
  let reportError: unknown
  const report = (r: McpServerResult) => {
    if (!onServer) return
    reports = reports.then(async () => {
      try {
        await onServer(r)
      } catch (e) {
        reportError ??= e
      }
    })
  }

  const loading = Promise.all(
    Object.entries(config).map(async ([name, cfg]) => {
      if (!isEnabled(cfg)) {
        status[name] = "disabled"
        report({ name, status: "disabled", tools: [] })
        return
      }
      const timeout = cfg.timeout ?? DEFAULT_TIMEOUT
      // The fallback SDK client is created before its connect so a timed-out/aborted connect
      // still has a closeable handle (its stdio child is killed by close() — never orphaned).
      let client: Client | undefined
      try {
        const c = await connectServer(name, cfg, timeout, opts ?? {}, store, () => newMcpClient(waitFor), (cl) => (client = cl))
        let converted: Tool[]
        if (c.kind === "modern") {
          converted = c.server.tools()
          skills.push(...c.server.skills)
          clients.push(c.server)
          servers.set(name, modernConn(c.server))
        } else {
          const kept = applyToolsFilter(name, c.defs, (cfg as { tools?: Record<string, boolean> }).tools)
          converted = kept.map((def) => convertTool(name, def, c.client, timeout))
          clients.push(c.client)
          servers.set(name, sdkConn(c.client, timeout))
        }
        tools.push(...converted)
        status[name] = "connected"
        report({ name, status: "connected", tools: converted })
      } catch (e) {
        status[name] = "failed"
        await (client as Client | undefined)?.close().catch(() => {})
        // Parent cancellation/deadline aborts the whole load (§2 Gap 3, A1); a per-server timeout
        // within budget isolates just this server.
        if (signal?.aborted) throw e
        report({ name, status: "failed", tools: [], error: e instanceof Error ? e : new Error(String(e)) })
        console.warn(`[toolnexus] MCP server "${name}" failed: ${e instanceof Error ? e.message : String(e)}`)
      }
    }),
  )
  try {
    await loading
  } catch (e) {
    // Parent cancellation: close every client, including ones whose tools onServer handed out.
    await reports
    await Promise.all(clients.map((c) => c.close().catch(() => {})))
    throw e
  }

  await reports
  if (reportError) {
    await Promise.all(clients.map((c) => c.close().catch(() => {})))
    throw reportError
  }

  return {
    tools,
    status,
    async close() {
      await Promise.all(clients.map((c) => c.close().catch(() => {})))
    },
    resourceTools: () => resourceTools(servers),
    skills,
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
export async function listMcpTools(input: string | object, opts?: McpLoadOptions): Promise<McpInventory> {
  const config = parseMcpConfig(input)
  const toolsByServer: Record<string, ToolInfo[]> = {}
  const status: Record<string, McpStatus> = {}
  const signal = opts?.signal
  const store = defaultTokenStore({ tokenStore: opts?.tokenStore })

  await Promise.all(
    Object.entries(config).map(async ([name, cfg]) => {
      if (!isEnabled(cfg)) {
        status[name] = "disabled"
        return
      }
      const timeout = cfg.timeout ?? DEFAULT_TIMEOUT
      // The fallback client is created before its connect so an aborted/timed-out connect is still closed.
      let client: Client | undefined
      try {
        const c = await connectServer(name, cfg, timeout, { ...opts, waitFor: opts?.waitFor }, store, () => newMcpClient(), (cl) => (client = cl))
        const defs: any[] = c.kind === "modern" ? c.server.rawDefs() : c.defs
        if (c.kind === "modern") await c.server.close().catch(() => {})
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
        if (signal?.aborted) { await (client as Client | undefined)?.close().catch(() => {}); throw e }
        console.warn(`[toolnexus] MCP server "${name}" failed: ${e instanceof Error ? e.message : String(e)}`)
      } finally {
        await (client as Client | undefined)?.close().catch(() => {})
      }
    }),
  )

  return { tools: toolsByServer, status }
}
