// Shared wire constants + tiny HTTP layer for MCP 2026-07-28 (stateless Streamable HTTP).
// Node stdlib only: node:http / node:https (no fetch, so a per-request trust store is possible).
import http from "node:http"
import https from "node:https"
import tls from "node:tls"

export const PROTOCOL_VERSION = "2026-07-28"
export const META_VERSION = "io.modelcontextprotocol/protocolVersion"
export const META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
export const META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
export const META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
export const SKILLS_EXT = "io.modelcontextprotocol/skills"

export const CODE_METHOD_NOT_FOUND = -32601
export const CODE_INVALID_PARAMS = -32602
export const CODE_HEADER_MISMATCH = -32020
export const CODE_UNSUPPORTED_PROTOCOL = -32022

/** A JSON object. */
export type Obj = Record<string, any>

/** A JSON-RPC error returned by the server. */
export class RpcError extends Error {
  code: number
  data?: unknown
  constructor(code: number, message: string, data?: unknown) {
    super(message)
    this.code = code
    if (data !== undefined) this.data = data
  }
  toJSON(): Obj {
    return this.data === undefined ? { code: this.code, message: this.message } : { code: this.code, message: this.message, data: this.data }
  }
}

/** Bounds every request made through defaultHttp (a hung server cannot hang the caller). */
export const DEFAULT_TIMEOUT_MS = 30_000
const MAX_RESPONSE_BYTES = 16 << 20

export interface HttpRequest {
  method: string
  url: string
  headers?: Record<string, string>
  body?: string
  timeoutMs?: number
}
export interface HttpResponse {
  status: number
  headers: Record<string, string> // lower-case names
  body: string
}
/** The seam for every outbound HTTP call (inject one to record, proxy or add headers). */
export type HttpFn = (r: HttpRequest) => Promise<HttpResponse>

// Dev only: CA certificates of DevAuthServers started in this process, trusted by defaultHttp,
// so `new ServerAuth({ issuer: as.issuer })` and a zero-config OAuth client need no HTTP wiring.
const devCAs: string[] = []
export function trustDevCert(pem: string): void {
  devCAs.push(pem)
}

/** node:http(s) request; reads the whole body (capped at 16 MiB). Never follows redirects. */
export const defaultHttp: HttpFn = (r) =>
  new Promise((resolve, reject) => {
    const u = new URL(r.url)
    const opts: https.RequestOptions = { method: r.method, headers: r.headers ?? {}, agent: false }
    if (u.protocol === "https:" && devCAs.length) opts.ca = [...tls.rootCertificates, ...devCAs]
    const req = (u.protocol === "https:" ? https : http).request(u, opts, (res) => {
      const chunks: Buffer[] = []
      let n = 0
      res.on("data", (c: Buffer) => {
        n += c.length
        if (n > MAX_RESPONSE_BYTES) {
          req.destroy(new Error("response too large"))
          return
        }
        chunks.push(c)
      })
      res.on("end", () => {
        const headers: Record<string, string> = {}
        for (const [k, v] of Object.entries(res.headers)) if (v !== undefined) headers[k] = Array.isArray(v) ? v.join(", ") : v
        resolve({ status: res.statusCode ?? 0, headers, body: Buffer.concat(chunks).toString("utf8") })
      })
      res.on("error", reject)
    })
    req.setTimeout(r.timeoutMs ?? DEFAULT_TIMEOUT_MS, () => req.destroy(new Error(`request to ${u.host} timed out`)))
    req.on("error", reject)
    req.end(r.body)
  })

/** GET + follow redirects (dev/test "browser"). Returns the final response and its URL. */
export async function browse(url: string, httpFn: HttpFn = defaultHttp, max = 10): Promise<HttpResponse & { url: string }> {
  for (let i = 0; ; i++) {
    const res = await httpFn({ method: "GET", url })
    const loc = res.headers["location"]
    if (res.status >= 300 && res.status < 400 && loc && i < max) {
      url = new URL(loc, url).toString()
      continue
    }
    return { ...res, url }
  }
}

export async function getJSON(httpFn: HttpFn, url: string): Promise<Obj> {
  const res = await httpFn({ method: "GET", url, headers: { Accept: "application/json" } })
  if (res.status !== 200) throw new Error(`GET ${url}: HTTP ${res.status}`)
  const v = JSON.parse(res.body)
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`GET ${url}: not a JSON object`)
  return v
}

/** Text returns the first text content of a tool result ("" if none). */
export function text(r: Obj | undefined | null): string {
  const c = r?.content
  if (!Array.isArray(c) || c.length === 0) return ""
  return typeof c[0]?.text === "string" ? c[0].text : ""
}

/** A small async mutex (one login / one refresh at a time). */
export class Mutex {
  private tail: Promise<void> = Promise.resolve()
  async lock<T>(f: () => Promise<T>): Promise<T> {
    let release!: () => void
    const prev = this.tail
    this.tail = new Promise((r) => (release = r))
    await prev
    try {
      return await f()
    } finally {
      release()
    }
  }
}
