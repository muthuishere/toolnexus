/**
 * MCP 2026-07-28 wire constants + a tiny node:http(s) layer (SPEC §2.1, §2.3). No MCP SDK.
 * node:http instead of fetch so a per-request trust store is possible (dev authorization servers).
 */
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

/** A JSON-RPC error returned by the server. `httpStatus` is set when it came over HTTP. */
export class RpcError extends Error {
  code: number
  data?: unknown
  httpStatus?: number
  constructor(code: number, message: string, data?: unknown) {
    super(message)
    this.code = code
    if (data !== undefined) this.data = data
  }
}

/** A non-2xx HTTP reply that carried no JSON-RPC message. */
export class HttpStatusError extends Error {
  status: number
  constructor(status: number, message?: string) {
    super(message ?? `HTTP ${status}`)
    this.status = status
  }
}

/** A 2xx reply that is not a JSON-RPC response for the request (§2.2a: falls back on discover). */
export class NotJsonRpcError extends Error {}

export const DEFAULT_TIMEOUT_MS = 30_000
const MAX_RESPONSE_BYTES = 16 << 20

export interface HttpRequest {
  method: string
  url: string
  headers?: Record<string, string>
  body?: string
  timeoutMs?: number
  signal?: AbortSignal
}
export interface HttpResponse {
  status: number
  headers: Record<string, string> // lower-case names
  body: string
}
/** The seam for every outbound HTTP call. */
export type HttpFn = (r: HttpRequest) => Promise<HttpResponse>

// Test/dev only: extra CA certificates trusted by defaultHttp (a dev authorization server's
// self-signed certificate). Not exported from the package index.
const extraCAs: string[] = []
export function trustDevCert(pem: string): void {
  extraCAs.push(pem)
}

/** node:http(s) request; reads the whole body (capped at 16 MiB). Never follows redirects. */
export const defaultHttp: HttpFn = (r) =>
  new Promise((resolve, reject) => {
    const u = new URL(r.url)
    const opts: https.RequestOptions = { method: r.method, headers: r.headers ?? {}, agent: false }
    if (u.protocol === "https:" && extraCAs.length) opts.ca = [...tls.rootCertificates, ...extraCAs]
    if (r.signal?.aborted) return reject(r.signal.reason ?? new Error("aborted"))
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
    const onAbort = () => req.destroy(r.signal?.reason instanceof Error ? r.signal.reason : new Error("aborted"))
    r.signal?.addEventListener("abort", onAbort, { once: true })
    req.on("close", () => r.signal?.removeEventListener("abort", onAbort))
    req.setTimeout(r.timeoutMs ?? DEFAULT_TIMEOUT_MS, () => req.destroy(new Error(`request to ${u.host} timed out`)))
    req.on("error", reject)
    req.end(r.body)
  })

export async function getJSON(httpFn: HttpFn, url: string): Promise<Obj> {
  const res = await httpFn({ method: "GET", url, headers: { Accept: "application/json" } })
  if (res.status !== 200) throw new Error(`GET ${url}: HTTP ${res.status}`)
  const v = JSON.parse(res.body)
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`GET ${url}: not a JSON object`)
  return v
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

/** Plain value if printable ASCII without edge whitespace, else the =?base64?…?= sentinel (§2.3). */
export function encodeHeader(v: string): string {
  const safe = v === v.trim() && !(v.startsWith("=?base64?") && v.endsWith("?=")) && /^[\x20-\x7e]*$/.test(v)
  return safe ? v : "=?base64?" + Buffer.from(v, "utf8").toString("base64") + "?="
}
