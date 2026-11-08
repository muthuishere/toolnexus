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

export const nodeFetch: typeof fetch = (input, init) => {
  const fallback = () => globalThis.fetch(input, init)
  if (globalThis.fetch !== nativeFetch || proxyEnv()) return fallback()
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
      if (status >= 300 && status < 400 && res.headers.location) {
        // fetch follows redirects; hand the whole request to it rather than re-implement that.
        res.resume()
        signal?.removeEventListener("abort", onAbort)
        settled = true
        fallback().then(resolve, reject)
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
