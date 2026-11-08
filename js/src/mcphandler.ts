/**
 * MCP — INBOUND, embeddable: a plain streamable-HTTP MCP server as a Node request
 * listener `(req, res) => void` with generic per-request hooks (SPEC §7C.1, ADR 0039).
 *
 * `serve(addr, {mcp})` (mcpserve.ts) owns a listener and exposes one static tool list.
 * `toolkit.mcpHandler(cfg)` is what a host mounts on its own server / router, behind its
 * own TLS, CORS and OAuth. Every policy is a hook; toolnexus has no notion of tenant,
 * user, role or storage — the host decides those inside the hooks.
 *
 * The transport is hand-written JSON-RPC (mirrors golang/mcphandler.go): tools resolve per
 * request, and a confirm elicitation rides the SAME POST's SSE response. No SDK server
 * types appear in this public API.
 */
import { createHash, randomBytes } from "node:crypto"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { Answer, Request, Tool, ToolAnnotations, ToolContext, ToolResult, ToolSource } from "./types.js"
import { pendingOf } from "./types.js"
import { toolResultToMcp } from "./mcpserve.js"
import { compareCodePoints } from "./order.js"
import { decodeHeaderValue } from "./mcpheader.js"
import type { McpSkill } from "./skill.js"

/** The MCP 2026-07-28 protocol version: an opt-in stateless wire (SPEC §7C.2), never a default. */
export const MCP_PROTOCOL_2026 = "2026-07-28"
/** The SEP-2640 skills extension id advertised by `server/discover` when `listSkills` is set. */
export const MCP_SKILLS_EXTENSION = "io.modelcontextprotocol/skills"
const META_VERSION = "io.modelcontextprotocol/protocolVersion"
const META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
const META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

/**
 * The MCP protocol versions whose wire this handler implements (ADR 0039 §versions).
 * 2026-07-28 is opt-in only (list it in `protocolVersions`): the stateless wire of SPEC §7C.2.
 */
export const MCP_HANDLER_IMPLEMENTED_VERSIONS: readonly string[] = [MCP_PROTOCOL_2026, "2025-11-25", "2025-06-18"]
const DEFAULT_VERSIONS = ["2025-06-18"]
const DEFAULT_NAME = "toolnexus"
const DEFAULT_VERSION = "0.1.0"

export type McpDecisionKind = "allow" | "deny" | "confirm"

/**
 * What `beforeCall` returns. `undefined` / `{}` allows. deny ⇒ `result` when set, else isError
 * `message` (default "denied"). confirm ⇒ elicitation when the client can, else `result` when
 * set, else isError "confirmation required: <message>".
 */
export interface McpDecision {
  kind?: McpDecisionKind
  message?: string
  result?: ToolResult
}

export interface McpServerInfo {
  name?: string
  /** serverInfo.title; empty ⇒ omitted. */
  title?: string
  version?: string
  instructions?: string
}

export interface McpResource {
  uri: string
  name: string
  title?: string
  description?: string
  mimeType?: string
}

/** One entry of resources/read: set `text`, or `blob` (base64). */
export interface McpResourceContent {
  uri: string
  mimeType?: string
  text?: string
  blob?: string
}

export interface McpPromptArgument {
  name: string
  description?: string
  required?: boolean
}

export interface McpPrompt {
  name: string
  title?: string
  description?: string
  arguments?: McpPromptArgument[]
}

export interface McpPromptMessage {
  role: "user" | "assistant"
  text: string
}

export interface McpPromptResult {
  description?: string
  messages: McpPromptMessage[]
}

/** The inbound request a hook or tool is serving. Read it with the `*From(ctx)` helpers. */
export interface McpContext {
  /** What `authenticate` returned (undefined without one). */
  identity?: unknown
  request: IncomingMessage
  /** Mcp-Session-Id ("" when stateless / none). */
  sessionId: string
  /** The JSON-RPC request's `params._meta`, when present. */
  meta?: Record<string, unknown>
  /** Aborted when the HTTP request goes away. */
  signal: AbortSignal
}

type Awaitable<T> = T | Promise<T>

/** Issues and checks Mcp-Session-Id values. Default: an in-memory set. */
export interface McpSessionIds {
  generate(ctx: McpContext): Awaitable<string>
  /** false ⇒ HTTP 404. */
  validate(ctx: McpContext, id: string): Awaitable<boolean>
  terminate(ctx: McpContext, id: string): Awaitable<void>
}

/**
 * An `authenticate` failure with an explicit HTTP status and challenge (`McpServerAuth` throws
 * it; any hook may). On any wire the handler answers `status` (default 401) with
 * `WWW-Authenticate` = `challenge` (a non-empty `wwwAuthenticate` result wins) and the body
 * `{"error":"unauthorized"}`. `reason` is never sent on the wire.
 */
export class McpAuthError extends Error {
  readonly status: number
  readonly challenge: string
  readonly reason: string
  constructor(status: number, challenge: string, reason = "") {
    super(reason || "unauthorized")
    this.name = "McpAuthError"
    this.status = status
    this.challenge = challenge
    this.reason = reason
  }
}

/** Surfaced once per inbound tools/call. */
export interface McpCallEvent {
  name: string
  source: ToolSource
  ms: number
  isError: boolean
  /** The execute() failure, when it threw. */
  err?: unknown
  /** Size in bytes of the JSON CallToolResult sent back. */
  resultBytes: number
  decision: McpDecisionKind
  identity: unknown
  sessionId: string
}

/** Configures `toolkit.mcpHandler`. Every field is optional. */
export interface McpHandlerConfig {
  name?: string
  title?: string
  version?: string
  instructions?: string
  /** Resolved at initialize; wins over the static fields. */
  serverInfo?: (ctx: McpContext) => Awaitable<McpServerInfo>
  /** Accepted versions, newest first; unimplemented entries dropped. Default ["2025-06-18"]. */
  protocolVersions?: string[]
  /** Every HTTP request, before JSON-RPC parsing. Throw / reject ⇒ HTTP 401. */
  authenticate?: (req: IncomingMessage) => Awaitable<unknown>
  /** WWW-Authenticate header for the 401 (when non-empty). */
  wwwAuthenticate?: (req: IncomingMessage, err: unknown) => string | undefined
  /** Per request, for tools/list AND the tools/call lookup. Omit ⇒ the toolkit's tools. */
  listTools?: (ctx: McpContext) => Awaitable<Tool[]>
  /** Gate per tools/call. A throw is a deny with its message. */
  beforeCall?: (ctx: McpContext, tool: Tool, args: Record<string, unknown>) => Awaitable<McpDecision | void>
  /** Elicitation round-trip bound in ms. Default 5 minutes. */
  confirmTimeout?: number
  /** Fires once per tools/call; its throws are isolated. */
  onCall?: (ev: McpCallEvent) => void
  listResources?: (ctx: McpContext) => Awaitable<McpResource[]>
  readResource?: (ctx: McpContext, uri: string) => Awaitable<McpResourceContent[]>
  listPrompts?: (ctx: McpContext) => Awaitable<McpPrompt[]>
  getPrompt?: (ctx: McpContext, name: string, args: Record<string, string>) => Awaitable<McpPromptResult>
  /**
   * §7C.2: skills published through the SEP-2640 skills extension on the 2026-07-28 wire
   * (skills/list, skills/get, resources/read of skill:// URIs). Omit ⇒ not advertised.
   * `loadMcpSkills(dir)` builds the list from a skills directory.
   */
  listSkills?: (ctx: McpContext) => Awaitable<McpSkill[]>
  sessionIds?: McpSessionIds
  /** No Mcp-Session-Id issued or checked; elicitation never used. */
  stateless?: boolean
  /** Stateless only: echoed as Mcp-Session-Id on initialize, never validated. */
  statelessSessionId?: string
  /** POST body cap in bytes. Default 4 MiB. */
  maxBodyBytes?: number
}

/** The embeddable handler: a Node request listener plus `notifyToolsChanged()`. */
export interface McpHandler {
  (req: IncomingMessage, res: ServerResponse): void
  /** Sends notifications/tools/list_changed on every open GET stream. */
  notifyToolsChanged(): void
}

type AnyCtx = McpContext | ToolContext | undefined | null
function mcpOf(ctx: AnyCtx): McpContext | undefined {
  if (!ctx) return undefined
  if ((ctx as ToolContext).mcp) return (ctx as ToolContext).mcp
  if ((ctx as McpContext).request && "sessionId" in ctx) return ctx as McpContext
  return undefined
}
/** The value `authenticate` produced for this request (hook ctx or tool ctx). */
export const identityFrom = (ctx: AnyCtx): unknown => mcpOf(ctx)?.identity
/** The inbound IncomingMessage being served. */
export const requestFrom = (ctx: AnyCtx): IncomingMessage | undefined => mcpOf(ctx)?.request
/** The request's Mcp-Session-Id ("" if none). */
export const mcpSessionIdFrom = (ctx: AnyCtx): string => mcpOf(ctx)?.sessionId ?? ""
/** The JSON-RPC request's `params._meta` (undefined when absent). */
export const mcpRequestMetaFrom = (ctx: AnyCtx): Record<string, unknown> | undefined => mcpOf(ctx)?.meta

interface RpcError {
  code: number
  message: string
}
const rpcErr = (code: number, message: string): { error: RpcError } => ({ error: { code, message } })
const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e))
const isObj = (v: unknown): v is Record<string, any> => typeof v === "object" && v !== null && !Array.isArray(v)

function omitEmpty(m: Record<string, unknown>): Record<string, unknown> {
  for (const k of Object.keys(m)) if (m[k] === "" || m[k] === undefined) delete m[k]
  return m
}

function httpError(res: ServerResponse, status: number, text: string, headers: Record<string, string> = {}) {
  res.writeHead(status, { "Content-Type": "text/plain; charset=utf-8", "X-Content-Type-Options": "nosniff", ...headers })
  res.end(text + "\n")
}

function writeAuthFailure(cfg: McpHandlerConfig, req: IncomingMessage, res: ServerResponse, err: unknown) {
  let challenge = cfg.wwwAuthenticate?.(req, err) ?? ""
  if (err instanceof McpAuthError) {
    if (!challenge) challenge = err.challenge
    const h: Record<string, string> = { "Content-Type": "application/json" }
    if (challenge) h["WWW-Authenticate"] = challenge
    res.writeHead(err.status || 401, h)
    res.end('{"error":"unauthorized"}')
    return
  }
  httpError(res, 401, "Unauthorized", challenge ? { "WWW-Authenticate": challenge } : {})
}

function writeRpcError(res: ServerResponse, id: unknown, code: number, message: string) {
  res.writeHead(400, { "Content-Type": "application/json" })
  res.end(JSON.stringify({ jsonrpc: "2.0", id: id ?? null, error: { code, message } }) + "\n")
}

/** The tools/list entry: name, description, inputSchema, outputSchema?, annotations?. */
export function mcpWireTool(t: Tool): Record<string, unknown> {
  const m: Record<string, unknown> = {
    name: t.name,
    description: t.description,
    inputSchema: t.inputSchema ?? { type: "object" },
  }
  if (t.outputSchema) m.outputSchema = t.outputSchema
  const ann: Record<string, unknown> = {}
  const a: ToolAnnotations | undefined = t.annotations
  if (a) {
    if (a.title) ann.title = a.title
    for (const k of ["readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"] as const) {
      if (typeof a[k] === "boolean") ann[k] = a[k]
    }
  } else if (t.hints) {
    ann.readOnlyHint = t.hints.readOnly
    ann.destructiveHint = t.hints.destructive
    ann.openWorldHint = t.hints.openWorld
  }
  if (Object.keys(ann).length > 0) m.annotations = ann
  return m
}

function memorySessionIds(): McpSessionIds {
  const live = new Set<string>()
  return {
    generate() {
      const id = randomBytes(16).toString("hex")
      live.add(id)
      return id
    },
    validate: (_ctx, id) => live.has(id),
    terminate(_ctx, id) {
      live.delete(id)
    },
  }
}

/** Answers one POST: plain JSON, or SSE once a server→client request was sent first. */
class RpcWriter {
  sse = false
  constructor(private readonly res: ServerResponse) {}
  event(v: unknown) {
    if (!this.sse) {
      this.res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" })
      this.sse = true
    }
    this.res.write(`event: message\ndata: ${JSON.stringify(v)}\n\n`)
  }
  respond(id: unknown, out: { result?: unknown; error?: RpcError }) {
    const msg: Record<string, unknown> = { jsonrpc: "2.0", id }
    if (out.error) msg.error = out.error
    else msg.result = out.result
    if (this.sse) {
      this.event(msg)
      this.res.end()
      return
    }
    this.res.writeHead(200, { "Content-Type": "application/json" })
    this.res.end(JSON.stringify(msg) + "\n")
  }
}

/** Builds the handler (`toolkit.mcpHandler(cfg)` calls this with the toolkit's tools). */
export function createMcpHandler(toolkitTools: () => Tool[], cfg: McpHandlerConfig = {}): McpHandler {
  const versions: string[] = []
  for (const v of cfg.protocolVersions ?? []) {
    if (MCP_HANDLER_IMPLEMENTED_VERSIONS.includes(v) && !versions.includes(v)) versions.push(v)
  }
  if (versions.length === 0) versions.push(...DEFAULT_VERSIONS)
  /** The initialize/session wire: every accepted version but 2026-07-28. */
  const legacy = versions.filter((v) => v !== MCP_PROTOCOL_2026)
  const has2026 = versions.includes(MCP_PROTOCOL_2026)
  const only2026 = has2026 && legacy.length === 0
  const ids = cfg.sessionIds ?? memorySessionIds()
  const confirmTimeout = cfg.confirmTimeout && cfg.confirmTimeout > 0 ? cfg.confirmTimeout : 5 * 60_000
  const maxBody = cfg.maxBodyBytes && cfg.maxBodyBytes > 0 ? cfg.maxBodyBytes : 4 << 20

  const caps = new Map<string, boolean>()
  const streams = new Set<ServerResponse>()
  const pending = new Map<string, (msg: Record<string, any>) => void>()
  let seq = 0

  const header = (req: IncomingMessage, name: string): string => {
    const v = req.headers[name.toLowerCase()]
    return Array.isArray(v) ? (v[0] ?? "") : (v ?? "")
  }

  const tools = async (ctx: McpContext) => (cfg.listTools ? await cfg.listTools(ctx) : toolkitTools())

  /** Validates protocol header + session (stateful). Writes the HTTP error itself on failure. */
  async function checkSession(ctx: McpContext, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
    const v = header(req, "MCP-Protocol-Version")
    if (v !== "" && !versions.includes(v)) {
      httpError(res, 400, "Bad Request: unsupported MCP-Protocol-Version")
      return false
    }
    if (cfg.stateless) return true
    const sid = header(req, "Mcp-Session-Id")
    if (sid === "") {
      httpError(res, 400, "Bad Request: missing Mcp-Session-Id")
      return false
    }
    let ok = false
    try {
      ok = await ids.validate(ctx, sid)
    } catch {
      ok = false
    }
    if (!ok) {
      httpError(res, 404, "Session not found")
      return false
    }
    ctx.sessionId = sid
    return true
  }

  function readBody(req: IncomingMessage): Promise<{ body?: string; tooLarge?: boolean; error?: boolean }> {
    return new Promise((resolve) => {
      const chunks: Buffer[] = []
      let size = 0
      let tooLarge = false
      req.on("data", (c: Buffer) => {
        size += c.length
        if (size > maxBody) tooLarge = true
        else chunks.push(c)
      })
      req.on("end", () => resolve(tooLarge ? { tooLarge } : { body: Buffer.concat(chunks).toString("utf8") }))
      req.on("error", () => resolve({ error: true }))
    })
  }

  type ReadResult = Awaited<ReturnType<typeof readBody>>
  const isJsonPost = (req: IncomingMessage) => header(req, "Content-Type").split(";")[0]!.trim().toLowerCase() === "application/json"

  async function handlePost(ctx: McpContext, req: IncomingMessage, res: ServerResponse, pre?: ReadResult) {
    if (!isJsonPost(req)) {
      httpError(res, 415, "Unsupported Media Type: must be application/json")
      return
    }
    const rb = pre ?? (await readBody(req))
    if (rb.error) return writeRpcError(res, null, -32700, "read error")
    if (rb.tooLarge) return httpError(res, 413, "Request Entity Too Large")
    const body = rb.body!
    if (body.trim().startsWith("[")) {
      // JSON-RPC batching was removed in protocol 2025-06-18.
      return writeRpcError(res, null, -32600, "batch requests are not supported")
    }
    let msg: Record<string, any>
    try {
      msg = JSON.parse(body)
    } catch {
      return writeRpcError(res, null, -32700, "parse error")
    }
    if (!isObj(msg) || msg.jsonrpc !== "2.0" || (msg.method !== undefined && typeof msg.method !== "string")) {
      return writeRpcError(res, null, -32700, "parse error")
    }
    const method: string = msg.method ?? ""
    const hasId = "id" in msg

    if (method === "initialize") return handleInitialize(ctx, res, msg)

    if (!(await checkSession(ctx, req, res))) return

    if (method === "") {
      // A client response (the answer to our elicitation).
      if (hasId && typeof msg.id === "string") pending.get(ctx.sessionId + "\0" + msg.id)?.(msg)
      res.writeHead(202).end()
      return
    }
    if (!hasId || msg.id === null) {
      res.writeHead(202).end() // a notification
      return
    }
    if (isObj(msg.params) && isObj(msg.params._meta)) ctx.meta = msg.params._meta
    const rw = new RpcWriter(res)
    let out: { result?: unknown; error?: RpcError }
    try {
      out = await dispatch(ctx, rw, method, msg.params)
    } catch (e) {
      out = rpcErr(-32603, errMsg(e))
    }
    rw.respond(msg.id, out)
  }

  async function handleInitialize(ctx: McpContext, res: ServerResponse, msg: Record<string, any>) {
    const p = isObj(msg.params) ? msg.params : {}
    const version = typeof p.protocolVersion === "string" && legacy.includes(p.protocolVersion) ? p.protocolVersion : legacy[0]!
    let sid = ""
    if (cfg.stateless) {
      sid = cfg.statelessSessionId ?? ""
    } else {
      try {
        sid = await ids.generate(ctx)
      } catch {
        return writeRpcError(res, msg.id, -32603, "cannot create session")
      }
      ctx.sessionId = sid
      const el = isObj(p.capabilities) ? p.capabilities.elicitation : undefined
      caps.set(sid, el !== undefined && el !== null)
    }
    const info: McpServerInfo = cfg.serverInfo
      ? await cfg.serverInfo(ctx)
      : { name: cfg.name, title: cfg.title, version: cfg.version, instructions: cfg.instructions }
    const capabilities: Record<string, unknown> = { tools: { listChanged: true } }
    if (cfg.listResources) capabilities.resources = {}
    if (cfg.listPrompts) capabilities.prompts = {}
    const result: Record<string, unknown> = {
      protocolVersion: version,
      capabilities,
      serverInfo: omitEmpty({ name: info.name || DEFAULT_NAME, title: info.title, version: info.version || DEFAULT_VERSION }),
    }
    if (info.instructions) result.instructions = info.instructions
    if (sid !== "") res.setHeader("Mcp-Session-Id", sid)
    new RpcWriter(res).respond(msg.id, { result })
  }

  async function handleGet(ctx: McpContext, req: IncomingMessage, res: ServerResponse) {
    if (!header(req, "Accept").includes("text/event-stream")) return httpError(res, 405, "Method Not Allowed")
    if (!(await checkSession(ctx, req, res))) return
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" })
    res.flushHeaders()
    streams.add(res)
    res.on("close", () => streams.delete(res))
  }

  async function handleDelete(ctx: McpContext, req: IncomingMessage, res: ServerResponse) {
    if (cfg.stateless) return httpError(res, 405, "Method Not Allowed")
    if (!(await checkSession(ctx, req, res))) return
    try {
      await ids.terminate(ctx, ctx.sessionId)
    } catch {
      return httpError(res, 500, "cannot terminate session")
    }
    caps.delete(ctx.sessionId)
    res.writeHead(200).end()
  }

  async function dispatch(
    ctx: McpContext,
    rw: RpcWriter,
    method: string,
    params: any,
  ): Promise<{ result?: unknown; error?: RpcError }> {
    switch (method) {
      case "ping":
        return { result: {} }
      case "tools/list": {
        let ts: Tool[]
        try {
          ts = await tools(ctx)
        } catch (e) {
          return rpcErr(-32603, errMsg(e))
        }
        return { result: { tools: (ts ?? []).map(mcpWireTool) } }
      }
      case "tools/call":
        return callTool(ctx, rw, params)
      case "resources/list": {
        if (!cfg.listResources) break
        let rs: McpResource[]
        try {
          rs = await cfg.listResources(ctx)
        } catch (e) {
          return rpcErr(-32603, errMsg(e))
        }
        return {
          result: {
            resources: (rs ?? []).map((r) =>
              omitEmpty({ uri: r.uri, name: r.name, title: r.title, description: r.description, mimeType: r.mimeType }),
            ),
          },
        }
      }
      case "resources/read": {
        if (!cfg.listResources || !cfg.readResource) break
        const uri = isObj(params) && typeof params.uri === "string" ? params.uri : ""
        let cs: McpResourceContent[]
        try {
          cs = await cfg.readResource(ctx, uri)
        } catch (e) {
          return rpcErr(-32002, errMsg(e))
        }
        return {
          result: {
            contents: (cs ?? []).map((c) => {
              const m = omitEmpty({ uri: c.uri, mimeType: c.mimeType, blob: c.blob })
              if (!c.blob) m.text = c.text ?? ""
              return m
            }),
          },
        }
      }
      case "prompts/list": {
        if (!cfg.listPrompts) break
        let ps: McpPrompt[]
        try {
          ps = await cfg.listPrompts(ctx)
        } catch (e) {
          return rpcErr(-32603, errMsg(e))
        }
        return {
          result: {
            prompts: (ps ?? []).map((p) => {
              const m = omitEmpty({ name: p.name, title: p.title, description: p.description })
              if (p.arguments?.length) {
                m.arguments = p.arguments.map((a) => {
                  const am = omitEmpty({ name: a.name, description: a.description })
                  if (a.required) am.required = true
                  return am
                })
              }
              return m
            }),
          },
        }
      }
      case "prompts/get": {
        if (!cfg.listPrompts || !cfg.getPrompt) break
        if (!isObj(params)) return rpcErr(-32602, "invalid params")
        const { name, arguments: args } = params
        const badArgs =
          args !== undefined && args !== null && (!isObj(args) || Object.values(args).some((v) => typeof v !== "string"))
        if ((name !== undefined && name !== null && typeof name !== "string") || badArgs) {
          return rpcErr(-32602, "invalid params")
        }
        let pr: McpPromptResult
        try {
          pr = await cfg.getPrompt(ctx, name ?? "", (args as Record<string, string>) ?? {})
        } catch (e) {
          return rpcErr(-32602, errMsg(e))
        }
        return {
          result: omitEmpty({
            description: pr.description,
            messages: (pr.messages ?? []).map((m) => ({ role: m.role, content: { type: "text", text: m.text } })),
          }),
        }
      }
    }
    return rpcErr(-32601, "method not found: " + method)
  }

  async function callTool(ctx: McpContext, rw: RpcWriter, params: any): Promise<{ result?: unknown; error?: RpcError }> {
    if (!isObj(params) || (params.name !== undefined && params.name !== null && typeof params.name !== "string")) {
      return rpcErr(-32602, "invalid params")
    }
    const name: string = params.name ?? ""
    let ts: Tool[]
    try {
      ts = await tools(ctx)
    } catch (e) {
      return rpcErr(-32603, errMsg(e))
    }
    const tool = (ts ?? []).find((t) => t.name === name)
    if (!tool || typeof tool.execute !== "function") return rpcErr(-32602, "tool not found: " + name)

    let args: Record<string, unknown> = {}
    const raw = params.arguments
    if (raw !== undefined && raw !== null) {
      if (!isObj(raw)) {
        if (header(ctx.request, "MCP-Protocol-Version") === "2025-11-25") {
          // 2025-11-25: input validation errors are tool execution errors.
          return { result: toolResultToMcp({ output: "invalid arguments: must be an object", isError: true }, true) }
        }
        return rpcErr(-32602, "arguments must be an object")
      }
      args = raw
    }

    const started = Date.now()
    const ev: McpCallEvent = {
      name: tool.name,
      source: tool.source,
      ms: 0,
      isError: false,
      resultBytes: 0,
      decision: "allow",
      identity: ctx.identity,
      sessionId: ctx.sessionId,
    }
    const finish = (res: ToolResult, execErr?: unknown) => {
      const wire = toolResultToMcp(res, true)
      ev.ms = Date.now() - started
      ev.isError = !!res.isError
      if (execErr !== undefined) ev.err = execErr
      ev.resultBytes = Buffer.byteLength(JSON.stringify(wire))
      if (cfg.onCall) {
        try {
          cfg.onCall(ev)
        } catch {
          // host callback throws are isolated
        }
      }
      return { result: wire }
    }

    if (cfg.beforeCall) {
      let d: McpDecision
      try {
        d = (await cfg.beforeCall(ctx, tool, args)) ?? {}
      } catch (e) {
        d = { kind: "deny", message: errMsg(e) }
      }
      const kind: McpDecisionKind = d.kind ?? "allow"
      ev.decision = kind
      if (kind === "deny") {
        if (d.result) return finish(d.result)
        return finish({ output: d.message || "denied", isError: true })
      }
      if (kind === "confirm") {
        if (!(ctx.sessionId !== "" && caps.get(ctx.sessionId))) {
          if (d.result) return finish(d.result)
          return finish({ output: "confirmation required: " + (d.message ?? ""), isError: true })
        }
        let accepted: boolean
        try {
          accepted = await elicitConfirm(ctx, rw, d.message ?? "")
        } catch (e) {
          return finish({ output: "confirmation failed: " + errMsg(e), isError: true })
        }
        if (!accepted) return finish({ output: "declined", isError: true })
      }
    }

    let res: ToolResult
    let execErr: unknown
    try {
      res = await tool.execute(args, { signal: ctx.signal, mcp: ctx })
    } catch (e) {
      execErr = e
      res = { output: errMsg(e), isError: true }
    }
    return finish(res, execErr)
  }

  /** Sends elicitation/create on this POST's SSE stream; the answer arrives via a later POST. */
  function elicitConfirm(ctx: McpContext, rw: RpcWriter, message: string): Promise<boolean> {
    const id = `toolnexus-confirm-${++seq}`
    const key = ctx.sessionId + "\0" + id
    if (!message) message = "Confirm this action?"
    return new Promise<boolean>((resolve, reject) => {
      const cleanup = () => {
        pending.delete(key)
        clearTimeout(timer)
        ctx.signal.removeEventListener("abort", onAbort)
      }
      const onAbort = () => {
        cleanup()
        reject(new Error("context canceled"))
      }
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error("context deadline exceeded"))
      }, confirmTimeout)
      ctx.signal.addEventListener("abort", onAbort)
      pending.set(key, (m) => {
        cleanup()
        if (m.error !== undefined && m.error !== null) return reject(new Error("client returned an error"))
        if (m.result === undefined) return reject(new Error("unexpected end of JSON input"))
        const r = isObj(m.result) ? m.result : {}
        if (r.action !== "accept") return resolve(false)
        if (isObj(r.content) && r.content.confirm === false) return resolve(false)
        resolve(true)
      })
      try {
        rw.event({
          jsonrpc: "2.0",
          id,
          method: "elicitation/create",
          params: {
            message,
            requestedSchema: {
              type: "object",
              properties: { confirm: { type: "boolean", title: "Confirm", description: message } },
            },
          },
        })
      } catch (e) {
        cleanup()
        reject(e)
      }
    })
  }

  // ---- MCP 2026-07-28 (SPEC §7C.2, ADR 0040): the stateless wire, opt-in ----

  /** Routes and answers a POST on the 2026 profile; false ⇒ the legacy wire serves it (body pre-read). */
  async function tryPost2026(ctx: McpContext, req: IncomingMessage, res: ServerResponse, rb: ReadResult): Promise<boolean> {
    if (rb.error || rb.tooLarge) return false // the legacy path answers identically
    const body = rb.body!
    const trimmed = body.trim()
    let msg: any
    let bad = trimmed.startsWith("[")
    if (!bad) {
      try {
        msg = JSON.parse(body)
      } catch {
        bad = true
      }
    }
    if (!bad && (!isObj(msg) || msg.jsonrpc !== "2.0" || (msg.method !== undefined && msg.method !== null && typeof msg.method !== "string"))) bad = true
    if (bad) {
      if (!only2026) return false
      if (trimmed.startsWith("[")) write26(res, 400, null, undefined, { code: -32600, message: "batch requests are not supported" })
      else write26(res, 400, null, undefined, { code: -32700, message: "Parse error" })
      return true
    }
    const method: string = msg.method ?? ""
    const params = isObj(msg.params) ? msg.params : undefined
    const meta = params && isObj(params._meta) ? (params._meta as Record<string, unknown>) : undefined
    if (!route2026(req, method, meta)) return false
    await serve2026(ctx, req, res, msg, method, params ?? {}, meta)
    return true
  }

  function route2026(req: IncomingMessage, method: string, meta: Record<string, unknown> | undefined): boolean {
    if (only2026) return true
    if (method === "initialize") return false
    const hv = header(req, "MCP-Protocol-Version")
    if (hv !== "" && legacy.includes(hv)) return false
    return method === "server/discover" || header(req, "Mcp-Method") !== "" || (meta !== undefined && META_VERSION in meta)
  }

  async function serve2026(
    ctx: McpContext,
    req: IncomingMessage,
    res: ServerResponse,
    msg: Record<string, any>,
    method: string,
    params: Record<string, any>,
    meta: Record<string, unknown> | undefined,
  ) {
    if (method === "" || !("id" in msg) || msg.id === null) {
      res.writeHead(202).end() // a notification (or a stray response): acknowledged
      return
    }
    const v = validate2026(req, method, params, meta)
    if (v) return write26(res, 400, msg.id, undefined, v)
    if (cfg.authenticate) {
      try {
        ctx.identity = await cfg.authenticate(req)
      } catch (err) {
        return writeAuthFailure(cfg, req, res, err)
      }
    }
    if (meta) ctx.meta = meta
    let out: Out26
    try {
      out = await dispatch2026(ctx, method, params, meta)
    } catch (e) {
      out = { error: { code: -32603, message: errMsg(e) } }
    }
    write26(res, out.status ?? 200, msg.id, out.result, out.error)
  }

  function validate2026(
    req: IncomingMessage,
    method: string,
    p: Record<string, any>,
    meta: Record<string, unknown> | undefined,
  ): Err26 | undefined {
    const mismatch = (m: string): Err26 => ({ code: -32020, message: "Header mismatch: " + m })
    const hv = header(req, "MCP-Protocol-Version")
    if (hv === "") return mismatch("MCP-Protocol-Version header missing")
    const hm = header(req, "Mcp-Method")
    if (hm !== method) return mismatch(`Mcp-Method header value '${hm}' does not match body value '${method}'`)
    if (method === "tools/call" || method === "prompts/get" || method === "resources/read") {
      const want = typeof p[method === "resources/read" ? "uri" : "name"] === "string" ? p[method === "resources/read" ? "uri" : "name"] : ""
      const [got, ok] = decodeHeaderValue(header(req, "Mcp-Name"))
      if (!ok || got !== want) return mismatch(`Mcp-Name header value '${got}' does not match body value '${want}'`)
    }
    const mv = meta && typeof meta[META_VERSION] === "string" ? (meta[META_VERSION] as string) : ""
    if (mv !== hv) return mismatch(`MCP-Protocol-Version header value '${hv}' does not match _meta value '${mv}'`)
    if (mv !== MCP_PROTOCOL_2026) {
      return { code: -32022, message: "Unsupported protocol version", data: { supported: [MCP_PROTOCOL_2026], requested: mv } }
    }
    return undefined
  }

  async function info2026(ctx: McpContext): Promise<McpServerInfo> {
    const info: McpServerInfo = cfg.serverInfo
      ? { ...(await cfg.serverInfo(ctx)) }
      : { name: cfg.name, title: cfg.title, version: cfg.version, instructions: cfg.instructions }
    if (!info.name) info.name = DEFAULT_NAME
    if (!info.version) info.version = DEFAULT_VERSION
    return info
  }

  /** Stamps resultType and _meta.serverInfo (set last, after any ToolResult.meta keys). */
  async function complete26(ctx: McpContext, m: Record<string, unknown>, resultType = "complete"): Promise<Out26> {
    const info = await info2026(ctx)
    const meta: Record<string, unknown> = isObj(m._meta) ? { ...m._meta } : {}
    delete meta[META_SERVER_INFO]
    meta[META_SERVER_INFO] = omitEmpty({ name: info.name, title: info.title, version: info.version })
    m._meta = meta
    m.resultType = resultType
    return { result: m }
  }

  const cacheScope26 = () => (cfg.authenticate ? "private" : "public")

  async function skills26(ctx: McpContext): Promise<McpSkill[]> {
    const ss = (await cfg.listSkills!(ctx)) ?? []
    return [...ss].sort((a, b) => compareCodePoints(a.name, b.name))
  }

  async function dispatch2026(
    ctx: McpContext,
    method: string,
    p: Record<string, any>,
    meta: Record<string, unknown> | undefined,
  ): Promise<Out26> {
    const internal = (e: unknown): Out26 => ({ error: { code: -32603, message: errMsg(e) } })
    const invalid = (m: string): Out26 => ({ error: { code: -32602, message: m } })
    switch (method) {
      case "server/discover": {
        const info = await info2026(ctx)
        const caps: Record<string, unknown> = { tools: {} }
        if (cfg.listResources || cfg.listSkills) caps.resources = {}
        if (cfg.listPrompts) caps.prompts = {}
        if (cfg.listSkills) caps.extensions = { [MCP_SKILLS_EXTENSION]: {} }
        const r: Record<string, unknown> = { supportedVersions: [MCP_PROTOCOL_2026], capabilities: caps, ttlMs: 3600000, cacheScope: "public" }
        if (info.instructions) r.instructions = info.instructions
        return complete26(ctx, r)
      }
      case "ping":
        return complete26(ctx, {})
      case "tools/list": {
        let ts: Tool[]
        try {
          ts = (await tools(ctx)) ?? []
        } catch (e) {
          return internal(e)
        }
        const sorted = [...ts].sort((a, b) => compareCodePoints(a.name, b.name))
        return complete26(ctx, { tools: sorted.map(mcpWireTool), ttlMs: 60000, cacheScope: cacheScope26() })
      }
      case "tools/call":
        return callTool2026(ctx, p, meta)
      case "skills/list": {
        if (!cfg.listSkills) break
        let ss: McpSkill[]
        try {
          ss = await skills26(ctx)
        } catch (e) {
          return internal(e)
        }
        const list = ss.map((s) => ({ uri: `skill://${s.name}/SKILL.md`, frontmatter: skillFrontmatter(s) }))
        return complete26(ctx, { skills: list, ttlMs: 60000, cacheScope: cacheScope26() })
      }
      case "skills/get": {
        if (!cfg.listSkills) break
        const uri = typeof p.uri === "string" ? p.uri : ""
        const parsed = parseSkillUri(uri)
        if ("error" in parsed) return invalid("Invalid skill URI: " + parsed.error)
        let ss: McpSkill[]
        try {
          ss = await skills26(ctx)
        } catch (e) {
          return internal(e)
        }
        const s = ss.find((x) => `skill://${x.name}/SKILL.md` === uri)
        if (s) return complete26(ctx, { skill: skillEntry(s) })
        return invalid("Unknown skill: " + uri)
      }
      case "resources/list": {
        if (!cfg.listResources) break
        const r = await dispatch(ctx, null as unknown as RpcWriter, method, p)
        if (r.error) return { error: r.error }
        return complete26(ctx, { ...(r.result as Record<string, unknown>) })
      }
      case "resources/read": {
        const uri = typeof p.uri === "string" ? p.uri : ""
        if (uri.startsWith("skill://") && cfg.listSkills) {
          const parsed = parseSkillUri(uri)
          if ("error" in parsed) return invalid("Invalid resource URI: " + parsed.error)
          let ss: McpSkill[]
          try {
            ss = await skills26(ctx)
          } catch (e) {
            return internal(e)
          }
          for (const s of ss) {
            if (s.name !== parsed.name) continue
            const f = (s.files ?? []).find((x) => x.path === parsed.rel)
            if (f) {
              const mimeType = parsed.rel.endsWith(".md") ? "text/markdown" : "text/plain"
              return complete26(ctx, { contents: [{ uri, mimeType, text: f.text }], ttlMs: 60000, cacheScope: cacheScope26() })
            }
          }
          return invalid("Resource not found: " + uri)
        }
        if (!cfg.listResources || !cfg.readResource) break
        const r = await dispatch(ctx, null as unknown as RpcWriter, method, p)
        if (r.error) return { error: r.error }
        return complete26(ctx, { ...(r.result as Record<string, unknown>) })
      }
      case "prompts/list":
      case "prompts/get": {
        if (!cfg.listPrompts) break
        // Same payload as the legacy wire; only the envelope differs.
        const r = await dispatch(ctx, null as unknown as RpcWriter, method, p)
        if (r.error) return { error: r.error }
        return complete26(ctx, isObj(r.result) ? { ...r.result } : {})
      }
    }
    return { status: 404, error: { code: -32601, message: "Method not found: " + method } }
  }

  async function callTool2026(ctx: McpContext, p: Record<string, any>, meta: Record<string, unknown> | undefined): Promise<Out26> {
    const name = typeof p.name === "string" ? p.name : ""
    let ts: Tool[]
    try {
      ts = (await tools(ctx)) ?? []
    } catch (e) {
      return { error: { code: -32603, message: errMsg(e) } }
    }
    const tool = ts.find((t) => t.name === name)
    if (!tool || typeof tool.execute !== "function") return { error: { code: -32602, message: "Unknown tool: " + name } }

    const started = Date.now()
    const ev: McpCallEvent = {
      name: tool.name,
      source: tool.source,
      ms: 0,
      isError: false,
      resultBytes: 0,
      decision: "allow",
      identity: ctx.identity,
      sessionId: "",
    }
    const finish = (out: Out26, isError: boolean, execErr?: unknown): Out26 => {
      ev.ms = Date.now() - started
      ev.isError = isError
      if (execErr !== undefined) ev.err = execErr
      ev.resultBytes = Buffer.byteLength(JSON.stringify(out.result))
      if (cfg.onCall) {
        try {
          cfg.onCall(ev)
        } catch {
          // host callback throws are isolated
        }
      }
      return out
    }
    const result = async (r: ToolResult, execErr?: unknown) =>
      finish(await complete26(ctx, toolResultToMcp(r, true)), !!r.isError, execErr)
    const inputRequired = async (m: Record<string, unknown>) => finish(await complete26(ctx, m, "input_required"), false)

    let args: Record<string, unknown> = {}
    const raw = p.arguments
    if (raw !== undefined && raw !== null) {
      if (!isObj(raw)) return result({ output: "invalid arguments: must be an object", isError: true })
      args = raw
    }

    const state = decodeState26(p.requestState)
    const hasState = state !== undefined && state.tool === name
    let confirmed = hasState && state!.confirmed === true
    let responses: Record<string, unknown> | undefined = isObj(p.inputResponses) ? p.inputResponses : undefined

    if (cfg.beforeCall) {
      let d: McpDecision
      try {
        d = (await cfg.beforeCall(ctx, tool, args)) ?? {}
      } catch (e) {
        d = { kind: "deny", message: errMsg(e) }
      }
      const kind: McpDecisionKind = d.kind || "allow"
      ev.decision = kind
      if (kind === "deny") {
        if (d.result) return result(d.result)
        return result({ output: d.message || "denied", isError: true })
      }
      if (kind === "confirm" && !confirmed) {
        const ans = inputResponse26("confirm", responses?.confirm)
        if (hasState && ans) {
          if (!ans.ok) return result({ output: "declined", isError: true })
          if (ans.data?.confirm === false) return result({ output: "declined", isError: true })
          confirmed = true
          responses = undefined // the confirm round is consumed
        } else if (!canElicit26(meta)) {
          if (d.result) return result(d.result)
          return result({ output: "confirmation required: " + (d.message ?? ""), isError: true })
        } else {
          const msg = d.message || "Confirm this action?"
          return inputRequired({
            inputRequests: {
              confirm: {
                method: "elicitation/create",
                params: {
                  mode: "form",
                  message: msg,
                  requestedSchema: {
                    type: "object",
                    properties: { confirm: { type: "boolean", title: "Confirm", description: msg } },
                  },
                },
              },
            },
            requestState: encodeState26(name, false),
          })
        }
      }
    }

    const tc: ToolContext = { signal: ctx.signal, mcp: ctx }
    if (hasState && responses && Object.keys(responses).length === 1) {
      const [k, v] = Object.entries(responses)[0]!
      const a = inputResponse26(k, v)
      if (a) tc.answer = a
    }
    let res: ToolResult
    let execErr: unknown
    try {
      res = await tool.execute(args, tc)
    } catch (e) {
      execErr = e
      res = { output: errMsg(e), isError: true }
    }
    const req = execErr === undefined ? pendingOf(res) : undefined
    if (req) {
      return inputRequired({ inputRequests: { [req.id]: inputRequest26(req) }, requestState: encodeState26(name, confirmed) })
    }
    return result(res, execErr)
  }

  const handler = ((req: IncomingMessage, res: ServerResponse) => {
    const ac = new AbortController()
    res.on("close", () => ac.abort())
    const ctx: McpContext = { request: req, sessionId: "", signal: ac.signal }
    void (async () => {
      let pre: ReadResult | undefined
      if (req.method === "POST" && has2026 && isJsonPost(req)) {
        pre = await readBody(req)
        if (await tryPost2026(ctx, req, res, pre)) return
      }
      if (only2026 && req.method !== "POST") {
        req.resume()
        return httpError(res, 405, "Method Not Allowed", { Allow: "POST" })
      }
      if (cfg.authenticate) {
        try {
          ctx.identity = await cfg.authenticate(req)
        } catch (err) {
          if (!pre) req.resume()
          return writeAuthFailure(cfg, req, res, err)
        }
      }
      switch (req.method) {
        case "POST":
          return handlePost(ctx, req, res, pre)
        case "GET":
          return handleGet(ctx, req, res)
        case "DELETE":
          return handleDelete(ctx, req, res)
        default:
          return httpError(res, 405, "Method Not Allowed", { Allow: "GET, POST, DELETE" })
      }
    })().catch((e) => {
      if (!res.headersSent) httpError(res, 500, "Internal Server Error: " + errMsg(e))
      else res.end()
    })
  }) as McpHandler

  handler.notifyToolsChanged = () => {
    const line = `event: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", method: "notifications/tools/list_changed" })}\n\n`
    for (const s of streams) {
      try {
        s.write(line)
      } catch {
        // a dead stream is dropped by its close handler
      }
    }
  }
  return handler
}

// ---- §7C.2 helpers ----

interface Err26 {
  code: number
  message: string
  data?: unknown
}
interface Out26 {
  status?: number
  result?: Record<string, unknown>
  error?: Err26
}

function write26(res: ServerResponse, status: number, id: unknown, result: Record<string, unknown> | undefined, error: Err26 | undefined) {
  const msg: Record<string, unknown> = { jsonrpc: "2.0", id: id ?? null }
  if (error) msg.error = error
  else msg.result = result
  res.writeHead(status, { "Content-Type": "application/json" })
  res.end(JSON.stringify(msg) + "\n")
}

const skillFrontmatter = (s: McpSkill): Record<string, unknown> => s.frontmatter ?? { name: s.name }

function skillEntry(s: McpSkill): Record<string, unknown> {
  const files = [...(s.files ?? [])].sort((a, b) => compareCodePoints(a.path, b.path))
  return {
    uri: `skill://${s.name}/SKILL.md`,
    frontmatter: skillFrontmatter(s),
    resources: files.map((f) => ({
      uri: `skill://${s.name}/${f.path}`,
      digest: "sha256:" + createHash("sha256").update(f.text, "utf8").digest("hex"),
      size: Buffer.byteLength(f.text, "utf8"),
    })),
  }
}

/** Percent-decodes once, like Go's url.PathUnescape; undefined on a malformed escape. */
function pathUnescape(s: string): string | undefined {
  if (!s.includes("%")) return s
  const bytes: number[] = []
  for (let i = 0; i < s.length; ) {
    if (s[i] === "%") {
      const hex = s.slice(i + 1, i + 3)
      if (!/^[0-9a-fA-F]{2}$/.test(hex)) return undefined
      bytes.push(parseInt(hex, 16))
      i += 3
    } else {
      const cp = s.codePointAt(i)!
      const ch = String.fromCodePoint(cp)
      bytes.push(...Buffer.from(ch, "utf8"))
      i += ch.length
    }
  }
  return Buffer.from(bytes).toString("utf8")
}

/** Validates skill://<name>/<path> (SPEC §2.7 containment). */
function parseSkillUri(uri: string): { name: string; rel: string } | { error: string } {
  if (!uri.startsWith("skill://")) return { error: "not a skill:// URI" }
  const dec = pathUnescape(uri.slice("skill://".length))
  if (dec === undefined) return { error: "bad percent-encoding" }
  if (/[%\\\0]/.test(dec)) return { error: "skill URI has encoded, backslash or NUL characters" }
  const segs = dec.split("/")
  if (segs.length < 2) return { error: "skill URI needs skill://<name>/<path>" }
  for (const s of segs) {
    if (s === "" || s === "." || s === ".." || s.includes(":")) {
      return { error: "skill URI path escapes the skill (empty, '.', '..' or ':' segment)" }
    }
  }
  return { name: segs[0]!, rel: segs.slice(1).join("/") }
}

/** requestState = base64url-nopad(compact JSON {"tool":…[,"confirmed":true]}); not integrity-protected. */
function encodeState26(tool: string, confirmed: boolean): string {
  const o: Record<string, unknown> = { tool }
  if (confirmed) o.confirmed = true
  return Buffer.from(JSON.stringify(o), "utf8").toString("base64url")
}

function decodeState26(v: unknown): { tool: string; confirmed?: boolean } | undefined {
  if (typeof v !== "string" || !/^[A-Za-z0-9_-]+$/.test(v) || v.length % 4 === 1) return undefined
  try {
    const o = JSON.parse(Buffer.from(v, "base64url").toString("utf8"))
    if (!isObj(o)) return undefined
    if (o.tool !== undefined && o.tool !== null && typeof o.tool !== "string") return undefined
    if (o.confirmed !== undefined && o.confirmed !== null && typeof o.confirmed !== "boolean") return undefined
    return { tool: o.tool ?? "", confirmed: o.confirmed === true }
  } catch {
    return undefined
  }
}

/** Maps one MRTR input response onto a §10 Answer. */
function inputResponse26(key: string, v: unknown): Answer | undefined {
  if (!isObj(v)) return undefined
  const ok = v.action === "accept"
  const a: Answer = { id: key, ok }
  if (isObj(v.content)) a.data = v.content
  if (!ok) a.reason = v.action === "decline" ? "declined" : "cancelled"
  return a
}

const canElicit26 = (meta: Record<string, unknown> | undefined): boolean => {
  const caps = meta?.[META_CLIENT_CAPS]
  return isObj(caps) && "elicitation" in caps
}

/** A §10 Request as an MRTR input request: authorization+url ⇒ URL mode, else a form. */
function inputRequest26(r: Request): Record<string, unknown> {
  if (r.kind === "authorization" && r.url) {
    return { method: "elicitation/create", params: { mode: "url", message: r.prompt, url: r.url, elicitationId: r.id } }
  }
  const schema = isObj(r.data?.schema) ? r.data!.schema : { type: "object", properties: {} }
  return { method: "elicitation/create", params: { mode: "form", message: r.prompt, requestedSchema: schema } }
}
