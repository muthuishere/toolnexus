/**
 * Default LLM transport: a `fetch`-shaped function over `node:http`/`node:https` with a shared
 * keep-alive agent.
 *
 * Why: Node's built-in `fetch` (undici + web streams) costs ~1.5 ms per local round-trip, against
 * ~0.3 ms for `node:http` on the same keep-alive socket. With two LLM calls per tool-calling turn
 * that was most of the client's per-request overhead (benchmarks/run_toolnexus_js.mjs).
 *
 * Behavior is kept identical to `fetch` for what the client uses (status, headers incl.
 * Retry-After, json/text, a streamed `body` for SSE, abort via `signal` rejecting with its reason).
 * Anything this path does not cover is handed to `globalThis.fetch` unchanged: a proxy env
 * (`HTTP(S)_PROXY`, `NODE_USE_ENV_PROXY`), a replaced global `fetch`, a non-http(s) URL, a body
 * that is not a string/bytes, and redirects.
 */
import http from "node:http"
import https from "node:https"
import { Readable } from "node:stream"
import zlib from "node:zlib"

const nativeFetch = globalThis.fetch
const httpAgent = new http.Agent({ keepAlive: true })
const httpsAgent = new https.Agent({ keepAlive: true })

function proxyEnv(): boolean {
  const e = process.env
  return !!(e.HTTP_PROXY || e.HTTPS_PROXY || e.http_proxy || e.https_proxy || e.NODE_USE_ENV_PROXY)
}

function toHeaderRecord(h: RequestInit["headers"]): Record<string, string> {
  const out: Record<string, string> = {}
  if (!h) return out
  if (h instanceof Headers || Array.isArray(h)) {
    for (const [k, v] of h as Iterable<[string, string]>) out[k] = v
    return out
  }
  return { ...(h as Record<string, string>) }
}

// undici's global dispatcher slot. Node fills it lazily with a default Agent on the first fetch;
// anything else there (a ProxyAgent, a mock, a tuned Agent) means the host routed fetch on purpose.
const DISPATCHER = Symbol.for("undici.globalDispatcher.1")
let knownDefaultDispatcher: unknown

function customDispatcher(): boolean {
  const d = (globalThis as Record<symbol, unknown>)[DISPATCHER]
  return d !== undefined && d !== knownDefaultDispatcher
}

/** Calls the platform fetch; if that call is what created Node's default dispatcher, remember it
 * so later calls still take the fast path. */
function platformFetch(input: Parameters<typeof fetch>[0], init?: RequestInit): Promise<Response> {
  const before = (globalThis as Record<symbol, unknown>)[DISPATCHER]
  const p = globalThis.fetch(input, init)
  if (before === undefined) {
    const after = (globalThis as Record<symbol, unknown>)[DISPATCHER]
    if (after !== undefined) knownDefaultDispatcher = after
  }
  return p
}

const REDIRECTS = new Set([301, 302, 303, 307, 308])

export const nodeFetch: typeof fetch = (input, init) => {
  const fallback = () => platformFetch(input, init)
  if (globalThis.fetch !== nativeFetch || proxyEnv() || customDispatcher()) return fallback()
  if (init?.redirect && init.redirect !== "follow") return fallback()
  const url = typeof input === "string" ? input : input instanceof URL ? input.href : undefined
  const body = init?.body
  if (url === undefined || (body != null && typeof body !== "string" && !(body instanceof Uint8Array))) return fallback()
  const isHttps = url.startsWith("https:")
  if (!isHttps && !url.startsWith("http:")) return fallback()
  const signal = init?.signal ?? undefined
  if (signal?.aborted) return Promise.reject(signal.reason)

  return new Promise<Response>((resolve, reject) => {
    const headers = toHeaderRecord(init?.headers)
    if (body != null && !Object.keys(headers).some((k) => k.toLowerCase() === "content-length")) {
      headers["content-length"] = String(typeof body === "string" ? Buffer.byteLength(body) : body.byteLength)
    }
    const mod = isHttps ? https : http
    let settled = false
    const onAbort = () => {
      req.destroy(signal!.reason)
      if (!settled) { settled = true; reject(signal!.reason) }
    }
    const req = mod.request(url, { method: init?.method ?? "GET", headers, agent: isHttps ? httpsAgent : httpAgent }, (res) => {
      const status = res.statusCode ?? 0
      if (REDIRECTS.has(status) && res.headers.location) {
        // Follow it the way fetch does, from the redirect target: re-sending to the original URL
        // would reach the server twice. 303, and 301/302 after a POST, become a bodiless GET;
        // 307/308 repeat the method and body. Further hops are fetch's to follow.
        res.resume()
        signal?.removeEventListener("abort", onAbort)
        settled = true
        const target = new URL(res.headers.location, url).href
        const method = (init?.method ?? "GET").toUpperCase()
        const toGet = status === 303 ? method !== "HEAD" : (status === 301 || status === 302) && method === "POST"
        let next: RequestInit | undefined = init
        if (toGet) {
          const h = toHeaderRecord(init?.headers)
          for (const k of Object.keys(h)) {
            if (/^content-(type|length|encoding|language|location)$/i.test(k)) delete h[k]
          }
          next = { ...init, method: "GET", body: undefined, headers: h }
        }
        platformFetch(target, next).then(resolve, reject)
        return
      }
      const h = new Headers()
      for (let i = 0; i < res.rawHeaders.length; i += 2) h.append(res.rawHeaders[i], res.rawHeaders[i + 1])
      let stream: Readable = res
      const enc = (res.headers["content-encoding"] ?? "").toLowerCase()
      if (enc === "gzip" || enc === "x-gzip" || enc === "deflate") stream = res.pipe(zlib.createUnzip())
      else if (enc === "br") stream = res.pipe(zlib.createBrotliDecompress())
      const nullBody = status === 204 || status === 304 || init?.method === "HEAD"
      const ctype = (res.headers["content-type"] ?? "").toLowerCase()
      const done = (b: ConstructorParameters<typeof Response>[0]) => {
        if (settled) return
        settled = true
        resolve(new Response(nullBody ? null : b, { status, statusText: res.statusMessage, headers: h }))
      }
      if (ctype.includes("text/event-stream")) {
        // Streamed: the body is consumed incrementally (SSE). Abort still destroys the socket.
        stream.once("end", () => signal?.removeEventListener("abort", onAbort))
        done(Readable.toWeb(stream) as unknown as ReadableStream)
        return
      }
      const chunks: Buffer[] = []
      stream.on("data", (c: Buffer) => chunks.push(c))
      stream.once("error", (e) => { if (!settled) { settled = true; reject(e) } })
      stream.once("end", () => {
        signal?.removeEventListener("abort", onAbort)
        done(Buffer.concat(chunks))
      })
    })
    req.once("error", (e) => {
      signal?.removeEventListener("abort", onAbort)
      if (!settled) { settled = true; reject(signal?.aborted ? signal.reason : new TypeError("fetch failed", { cause: e })) }
    })
    signal?.addEventListener("abort", onAbort, { once: true })
    req.end(body ?? undefined)
  })
}
