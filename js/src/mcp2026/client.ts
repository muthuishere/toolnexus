/**
 * Stateless MCP 2026-07-28 client (SPEC §2.2–§2.7): Streamable HTTP or stdio, one API.
 * Never sends `initialize`; no session; never re-sends a request except after a pre-dispatch
 * 401/403 auth rejection (§2.6).
 */
import { createHash } from "node:crypto"
import {
  defaultHttp, encodeHeader, HttpStatusError, META_CLIENT_CAPS, META_CLIENT_INFO, META_VERSION, NotJsonRpcError,
  PROTOCOL_VERSION, RpcError, DEFAULT_TIMEOUT_MS, type HttpFn, type HttpResponse, type Obj,
} from "./protocol.js"
import type { OAuth } from "./auth.js"
import { parseSkillURI } from "./skills.js"

/** The transport behind a stdio client (stdio.ts). */
export interface Transport {
  roundTrip(id: number, body: string, timeoutMs: number, signal?: AbortSignal): Promise<Obj>
  close(): Promise<void>
}

export interface CallOptions {
  timeoutMs?: number
  signal?: AbortSignal
}

export class Mcp2026Client {
  readonly url: string
  info: Obj = { name: "toolnexus", version: "0.1.0" }
  caps: Obj = { elicitation: { form: {} } }
  http: HttpFn = defaultHttp
  headers: Record<string, string>
  auth?: OAuth
  timeoutMs: number
  transport?: Transport
  private nextId = 0

  constructor(url: string, o: { headers?: Record<string, string>; auth?: OAuth; timeoutMs?: number; transport?: Transport } = {}) {
    this.url = url
    this.headers = o.headers ?? {}
    this.auth = o.auth
    this.timeoutMs = o.timeoutMs ?? DEFAULT_TIMEOUT_MS
    this.transport = o.transport
  }

  /** Sends one request; params gets the per-request _meta merged in. Throws RpcError on a JSON-RPC error. */
  async call(method: string, params: Obj = {}, o: CallOptions = {}): Promise<Obj> {
    params = { ...params, _meta: { ...(params._meta ?? {}), [META_VERSION]: PROTOCOL_VERSION, [META_CLIENT_CAPS]: this.caps, [META_CLIENT_INFO]: this.info } }
    const id = ++this.nextId
    const body = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    const timeoutMs = o.timeoutMs ?? this.timeoutMs
    const msg = this.transport ? await this.transport.roundTrip(id, body, timeoutMs, o.signal) : await this.httpRoundTrip(method, params, body, timeoutMs, o.signal)
    if (msg.error != null) {
      const e = msg.error
      const err = new RpcError(Number(e.code), String(e.message ?? ""), e.data)
      if (msg.__status) err.httpStatus = msg.__status
      throw err
    }
    const r = msg.result
    if (!r || typeof r !== "object" || Array.isArray(r)) throw new NotJsonRpcError("response has neither result nor error")
    if (!("resultType" in r)) r.resultType = "complete"
    if (r.resultType !== "complete" && r.resultType !== "input_required") throw new Error(`unsupported resultType "${String(r.resultType)}"`)
    return r
  }

  private async httpRoundTrip(method: string, params: Obj, body: string, timeoutMs: number, signal?: AbortSignal): Promise<Obj> {
    let resp: HttpResponse
    for (let attempt = 0; ; attempt++) {
      const sent = this.auth ? await this.auth.bearer(this.url) : ""
      resp = await this.post(method, params, body, sent, timeoutMs, signal) // a network error is never retried
      const www = resp.headers["www-authenticate"] ?? ""
      if (!this.auth || (resp.status !== 401 && resp.status !== 403) || !www) break
      if (attempt >= 3) throw new Error(`authorization failed: HTTP ${resp.status} after ${attempt + 1} attempts`)
      await this.auth.challenge(this.url, resp.status, www, sent, () => this.call(method, params, { timeoutMs, signal }))
    }
    let msg: Obj | undefined
    if ((resp.headers["content-type"] ?? "").startsWith("text/event-stream")) {
      // The last data: payload carrying a result or error is the final response.
      for (const line of resp.body.split(/\r?\n/)) {
        if (!line.startsWith("data:")) continue
        try {
          const m = JSON.parse(line.slice(5))
          if (m && (m.result !== undefined || m.error !== undefined)) msg = m
        } catch {
          /* not JSON: ignore */
        }
      }
    } else {
      try {
        msg = JSON.parse(resp.body)
      } catch {
        msg = undefined
      }
    }
    const ok = resp.status >= 200 && resp.status < 300
    const isMsg = !!msg && typeof msg === "object" && (msg.result != null || (msg.error != null && typeof msg.error === "object" && typeof msg.error.code === "number"))
    if (!isMsg) {
      if (!ok) throw new HttpStatusError(resp.status)
      throw new NotJsonRpcError(`HTTP ${resp.status}: response is not a JSON-RPC response`)
    }
    if (!ok && msg!.error == null) throw new HttpStatusError(resp.status)
    return { ...msg, __status: ok ? undefined : resp.status }
  }

  private post(method: string, params: Obj, body: string, bearer: string, timeoutMs: number, signal?: AbortSignal): Promise<HttpResponse> {
    const h: Record<string, string> = {
      ...this.headers,
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      "MCP-Protocol-Version": PROTOCOL_VERSION,
      "Mcp-Method": method,
    }
    if (method === "tools/call" || method === "prompts/get") h["Mcp-Name"] = encodeHeader(String(params.name ?? ""))
    if (method === "resources/read") h["Mcp-Name"] = encodeHeader(String(params.uri ?? ""))
    if (bearer) h.Authorization = "Bearer " + bearer
    return this.http({ method: "POST", url: this.url, headers: h, body, timeoutMs, signal })
  }

  /** tools/list (one page — no nextCursor on the 2026 path, §2.9). Returns the tools and the ttlMs hint. */
  async listTools(o: CallOptions = {}): Promise<{ tools: Obj[]; ttlMs?: number }> {
    const r = await this.call("tools/list", {}, o)
    const tools = (Array.isArray(r.tools) ? r.tools : []).filter((t: unknown) => t && typeof t === "object" && typeof (t as Obj).name === "string")
    return { tools, ttlMs: typeof r.ttlMs === "number" ? r.ttlMs : undefined }
  }

  /**
   * One remote skill (§2.7) as a data skill: skills/get, then resources/read of its SKILL.md (sha256
   * verified). Every resource URI must be inside the skill. Returns the raw SKILL.md text and the
   * other resources' relative paths.
   */
  async fetchSkill(uri: string, o: CallOptions = {}): Promise<{ name: string; markdown: string; resources: string[] }> {
    const { name } = parseSkillURI(uri)
    const r = await this.call("skills/get", { uri }, o)
    const res: Obj[] = Array.isArray(r.skill?.resources) ? r.skill.resources : []
    const others: string[] = []
    let md: Obj | undefined
    for (const m of res) {
      const fu = String(m?.uri ?? "")
      let rel: string
      try {
        const p = parseSkillURI(fu)
        if (p.name !== name) throw new Error()
        rel = p.rel
      } catch {
        throw new Error(`refusing skill resource "${fu}": outside skill ${name}`)
      }
      if (rel === "SKILL.md") md = m
      else others.push(rel)
    }
    const mdUri = md ? String(md.uri) : uri
    const rr = await this.call("resources/read", { uri: mdUri }, o)
    const cs = Array.isArray(rr.contents) ? rr.contents : []
    if (cs.length === 0) throw new Error(`${mdUri}: empty resources/read`)
    const text = typeof cs[0]?.text === "string" ? cs[0].text : ""
    const d = typeof md?.digest === "string" ? md.digest : ""
    if (d && d !== "sha256:" + createHash("sha256").update(text).digest("hex")) throw new Error(`${mdUri}: digest mismatch`)
    return { name, markdown: text, resources: others.sort() }
  }

  async close(): Promise<void> {
    await this.transport?.close()
  }
}
