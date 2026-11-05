/**
 * MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).
 *
 * The inbound mirror of the A2A `serve` (§7B). Where A2A advertises the toolkit's
 * **skills** and fulfils a Task through the whole client loop, MCP advertises the
 * toolkit's **unified tools** (every source — mcp · skill · native · http ·
 * builtin · a2a) and dispatches each `tools/call` straight to `Tool.execute`. The
 * calling MCP client *is* the LLM host, so there is **no client, no Task, and no
 * TaskStore** — just `tools/list` + `tools/call` over the toolkit registry.
 *
 * Transport: **streamable-HTTP**, mounted at `POST /mcp` on the `serve(addr, …)`
 * server alongside the A2A routes (see serve.ts), built on the same
 * `@modelcontextprotocol/sdk` the client side already uses.
 */
import http from "node:http"
import type { AddressInfo } from "node:net"
import { randomBytes, timingSafeEqual } from "node:crypto"
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { ListToolsRequestSchema, CallToolRequestSchema, McpError, ErrorCode } from "@modelcontextprotocol/sdk/types.js"
import type { Tool, ToolSource, ToolResult } from "./types.js"
import type { ContentPart } from "./content.js"

const DEFAULT_NAME = "toolnexus"
const DEFAULT_VERSION = "0.1.0"

/** Opt-in MCP serve profile — the toolkit is exposed as an MCP server. */
export interface MCPServeConfig {
  /** Advertised server name (initialize `serverInfo.name`). Default "toolnexus". */
  name?: string
  /** Advertised server version. Default "0.1.0". */
  version?: string
  /** Subset of tool names to expose; omit ⇒ all. Unknown names are ignored. */
  tools?: string[]
}

/** Surfaced on each inbound `tools/call`. */
export interface OnCallEvent {
  name: string
  source: ToolSource
  ms: number
  isError: boolean
}

export type OnCall = (ev: OnCallEvent) => void | Promise<void>

/**
 * The tools this profile exposes: every toolkit tool, filtered to `cfg.tools`
 * when set (unknown names in the filter are ignored, never an error).
 */
export function exposedMcpTools(tools: Tool[], cfg?: MCPServeConfig): Tool[] {
  if (!cfg?.tools) return tools
  const wanted = new Set(cfg.tools)
  return tools.filter((t) => wanted.has(t.name))
}

/**
 * Build a low-level MCP `Server` exposing `tools` as MCP tools. `tools/list`
 * maps each Tool's name (verbatim — already sanitized at registration),
 * description, and inputSchema; `tools/call` dispatches to `Tool.execute` and
 * maps the `ToolResult` to a `CallToolResult` (`output` → text, `isError`
 * propagates). A fresh server is cheap to build, so the HTTP path makes one per
 * request (stateless).
 */
export function buildMcpServer(tools: Tool[], cfg?: MCPServeConfig, onCall?: OnCall): Server {
  const byName = new Map(tools.map((t) => [t.name, t]))
  const server = new Server(
    { name: cfg?.name ?? DEFAULT_NAME, version: cfg?.version ?? DEFAULT_VERSION },
    { capabilities: { tools: {} } },
  )

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
  }))

  server.setRequestHandler(CallToolRequestSchema, async (req, extra) => {
    const tool = byName.get(req.params.name)
    if (!tool) throw new McpError(ErrorCode.InvalidParams, `Unknown tool: ${req.params.name}`)
    const started = Date.now()
    // The call is a single synchronous tool invocation honoring the MCP
    // request's cancellation. An execute() throw becomes an isError result —
    // never a crash.
    let result: ToolResult
    try {
      result = await tool.execute(req.params.arguments ?? {}, { signal: extra.signal })
    } catch (e) {
      result = { output: e instanceof Error ? e.message : String(e), isError: true }
    }
    if (onCall) {
      try {
        await onCall({ name: tool.name, source: tool.source, ms: Date.now() - started, isError: result.isError })
      } catch {
        // Host callback errors are isolated.
      }
    }
    return { content: [{ type: "text", text: result.output }, ...partBlocks(result.parts)], isError: result.isError }
  })

  return server
}

/**
 * §7C. A served tool's `ToolResult.parts` become MCP content blocks appended after the
 * text part, in order — image ⇒ image content, audio ⇒ audio content, file ⇒ an embedded
 * resource (a url-backed file keeps its uri, a byte-backed one carries the blob).
 */
function partBlocks(parts: ContentPart[] | undefined): any[] {
  if (!parts?.length) return []
  const out: any[] = []
  for (const p of parts) {
    if (p.type === "text") out.push({ type: "text", text: p.text })
    else if (p.type === "image" && p.data) out.push({ type: "image", data: p.data, mimeType: p.mimeType })
    else if (p.type === "audio" && p.data) out.push({ type: "audio", data: p.data, mimeType: p.mimeType })
    else {
      const uri = p.url ?? `file:///${(p as any).name ?? "resource"}`
      const resource: any = { uri, mimeType: p.mimeType }
      if (p.data) resource.blob = p.data
      out.push({ type: "resource", resource })
    }
  }
  return out
}

/** A loopback MCP endpoint for a local agent (SPEC §8C, {@link Toolkit.asMcpServer}). */
export interface LocalMcpServer {
  /** `http://127.0.0.1:<port>/mcp`. */
  url: string
  /** 64 lowercase hex chars; required as `Authorization: Bearer <token>`. Never logged. */
  token: string
  /** Stops the listener. Idempotent. */
  close(): Promise<void>
}

/**
 * Serves `tools` (filtered by `cfg.tools`) as the stateless §7C streamable-HTTP MCP endpoint on
 * `127.0.0.1:<ephemeral>/mcp` behind a fresh bearer token. Other paths ⇒ 404; missing/wrong
 * token ⇒ 401 + `WWW-Authenticate: Bearer` (constant-time compare); a non-POST ⇒ 405.
 */
export async function serveLocalMcp(tools: Tool[], cfg?: MCPServeConfig): Promise<LocalMcpServer> {
  const exposed = exposedMcpTools(tools, cfg)
  const token = randomBytes(32).toString("hex")
  const want = Buffer.from("Bearer " + token)
  const server = http.createServer((req, res) => {
    const u = req.url ?? ""
    if (u !== "/mcp" && !u.startsWith("/mcp?")) {
      res.writeHead(404).end("not found")
      return
    }
    const got = Buffer.from(String(req.headers.authorization ?? ""))
    if (got.length !== want.length || !timingSafeEqual(got, want)) {
      res.writeHead(401, { "WWW-Authenticate": "Bearer" }).end("unauthorized")
      return
    }
    if (req.method !== "POST") {
      res.writeHead(405, { Allow: "POST" }).end("method not allowed")
      return
    }
    void (async () => {
      const body = await readBody(req)
      let parsed: unknown
      try {
        parsed = body ? JSON.parse(body) : undefined
      } catch {
        parsed = undefined
      }
      const mcp = buildMcpServer(exposed, cfg)
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
      res.on("close", () => {
        void transport.close()
        void mcp.close()
      })
      await mcp.connect(transport)
      await transport.handleRequest(req, res, parsed)
    })().catch(() => {
      if (!res.headersSent) res.writeHead(500).end("mcp error")
    })
  })
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve())
  })
  const port = (server.address() as AddressInfo).port
  let closing: Promise<void> | undefined
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    token,
    close: () =>
      (closing ??= new Promise<void>((resolve) => {
        server.close(() => resolve())
        server.closeAllConnections()
      })),
  }
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = ""
    req.setEncoding("utf8")
    req.on("data", (c: string) => (data += c))
    req.on("end", () => resolve(data))
    req.on("error", reject)
  })
}
