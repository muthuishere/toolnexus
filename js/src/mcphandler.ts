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
import { randomBytes } from "node:crypto"
import type { IncomingMessage, ServerResponse } from "node:http"
import type { Tool, ToolAnnotations, ToolContext, ToolResult, ToolSource } from "./types.js"
import { toolResultToMcp } from "./mcpserve.js"

/** The MCP protocol versions whose wire this handler implements (ADR 0039 §versions). */
export const MCP_HANDLER_IMPLEMENTED_VERSIONS: readonly string[] = ["2025-11-25", "2025-06-18"]
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

  async function handlePost(ctx: McpContext, req: IncomingMessage, res: ServerResponse) {
    const mt = header(req, "Content-Type").split(";")[0]!.trim().toLowerCase()
    if (mt !== "application/json") {
      httpError(res, 415, "Unsupported Media Type: must be application/json")
      return
    }
    const rb = await readBody(req)
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
    const version = typeof p.protocolVersion === "string" && versions.includes(p.protocolVersion) ? p.protocolVersion : versions[0]!
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

  const handler = ((req: IncomingMessage, res: ServerResponse) => {
    const ac = new AbortController()
    res.on("close", () => ac.abort())
    const ctx: McpContext = { request: req, sessionId: "", signal: ac.signal }
    void (async () => {
      if (cfg.authenticate) {
        try {
          ctx.identity = await cfg.authenticate(req)
        } catch (err) {
          const h: Record<string, string> = {}
          const v = cfg.wwwAuthenticate?.(req, err)
          if (v) h["WWW-Authenticate"] = v
          req.resume()
          return httpError(res, 401, "Unauthorized", h)
        }
      }
      switch (req.method) {
        case "POST":
          return handlePost(ctx, req, res)
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
