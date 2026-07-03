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
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { ListToolsRequestSchema, CallToolRequestSchema, McpError, ErrorCode } from "@modelcontextprotocol/sdk/types.js"
import type { Tool, ToolSource } from "./types.js"

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
    let result
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
    return { content: [{ type: "text", text: result.output }], isError: result.isError }
  })

  return server
}
