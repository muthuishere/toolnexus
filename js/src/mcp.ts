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
import type { Tool as McpToolDef } from "@modelcontextprotocol/sdk/types.js"
import { type JSONSchema, type Tool, type ToolContext, type ToolResult, type McpStatus, sanitize } from "./types.js"

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
}

export interface RemoteServer {
  type?: "remote"
  url: string
  headers?: Record<string, string>
  enabled?: boolean
  disabled?: boolean
  timeout?: number
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
  // agents/a2a) so they are not mistaken for MCP servers when no wrapper key
  // is present.
  const { builtins: _builtins, agents: _agents, a2a: _a2a, ...servers } = raw
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
export async function loadMcp(input: string | object): Promise<McpSource> {
  const config = parseMcpConfig(input)
  const tools: Tool[] = []
  const status: Record<string, McpStatus> = {}
  const clients: Client[] = []

  await Promise.all(
    Object.entries(config).map(async ([name, cfg]) => {
      if (!isEnabled(cfg)) {
        status[name] = "disabled"
        return
      }
      const timeout = cfg.timeout ?? DEFAULT_TIMEOUT
      const client = new Client({ name: "toolnexus", version: "0.1.0" }, { capabilities: {} })
      try {
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
        const defs = await paginateTools(client, timeout)
        for (const def of defs) tools.push(convertTool(name, def, client, timeout))
        clients.push(client)
        status[name] = "connected"
      } catch (e) {
        status[name] = "failed"
        await client.close().catch(() => {})
        // one bad server never breaks the toolkit
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
