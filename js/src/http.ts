/**
 * HTTP / REST tools — declare a remote endpoint as a uniform Tool.
 * See ../../SPEC.md §7.
 */
import type { JSONSchema, Tool, ToolContext, ToolResult } from "./types.js"

export interface HttpToolOptions {
  name: string
  description: string
  method: string
  url: string
  headers?: Record<string, string>
  /** Arg names sent as querystring instead of in the body. */
  query?: string[]
  /** Body encoding for non-GET requests. */
  body?: "json" | "form" | "raw"
  inputSchema?: JSONSchema
  timeout?: number
  resultMode?: "text" | "json" | "status+text"
}

const DEFAULT_TIMEOUT = 30_000
const EMPTY_SCHEMA: JSONSchema = { type: "object", properties: {}, additionalProperties: false }

/** Expand ${ENV_VAR} in header values from process.env (never logged). */
function expandHeaders(headers: Record<string, string> = {}): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(headers)) {
    out[k] = v.replace(/\$\{([A-Z0-9_]+)\}/gi, (_, name) => process.env[name] ?? "")
  }
  return out
}

/** Wrap an HTTP endpoint as a Tool. */
export function httpTool(opts: HttpToolOptions): Tool {
  const method = opts.method.toUpperCase()
  const querySet = new Set(opts.query ?? [])
  return {
    name: opts.name,
    description: opts.description,
    inputSchema: opts.inputSchema ?? EMPTY_SCHEMA,
    source: "http",
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      const a = { ...(args ?? {}) }
      // 1. substitute {placeholders} in the URL from args (consumed afterwards)
      let url = opts.url.replace(/\{(\w+)\}/g, (_, key) => {
        const val = a[key]
        delete a[key]
        return encodeURIComponent(String(val ?? ""))
      })
      // 2. querystring args
      const params = new URLSearchParams()
      for (const key of Object.keys(a)) {
        if (querySet.has(key) || method === "GET") {
          params.append(key, String(a[key]))
          delete a[key]
        }
      }
      const qs = params.toString()
      if (qs) url += (url.includes("?") ? "&" : "?") + qs

      // 3. body
      const headers = expandHeaders(opts.headers)
      let bodyInit: string | undefined
      if (method !== "GET" && method !== "HEAD" && Object.keys(a).length > 0) {
        const mode = opts.body ?? "json"
        if (mode === "json") {
          headers["Content-Type"] ??= "application/json"
          bodyInit = JSON.stringify(a)
        } else if (mode === "form") {
          headers["Content-Type"] ??= "application/x-www-form-urlencoded"
          bodyInit = new URLSearchParams(a as Record<string, string>).toString()
        } else {
          bodyInit = String((a as any).body ?? "")
        }
      }

      const timeout = ctx?.timeout ?? opts.timeout ?? DEFAULT_TIMEOUT
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), timeout)
      if (ctx?.signal) ctx.signal.addEventListener("abort", () => controller.abort(), { once: true })
      try {
        const res = await fetch(url, { method, headers, body: bodyInit, signal: controller.signal })
        const text = await res.text()
        if (!res.ok) {
          return { output: `HTTP ${res.status}: ${text}`, isError: true, metadata: { status: res.status } }
        }
        const output =
          opts.resultMode === "status+text"
            ? `${res.status}\n${text}`
            : opts.resultMode === "json"
              ? JSON.stringify(safeParse(text))
              : text
        return { output, isError: false, metadata: { status: res.status } }
      } catch (e) {
        return { output: e instanceof Error ? e.message : String(e), isError: true }
      } finally {
        clearTimeout(timer)
      }
    },
  }
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
