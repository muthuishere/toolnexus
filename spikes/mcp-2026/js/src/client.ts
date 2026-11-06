// Stateless MCP 2026-07-28 client: Streamable HTTP (this file) or stdio (stdio.ts), same API.
import { createHash } from "node:crypto"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import {
  defaultHttp, META_CLIENT_CAPS, META_CLIENT_INFO, META_VERSION, PROTOCOL_VERSION, RpcError,
  type HttpFn, type HttpResponse, type Obj,
} from "./protocol.ts"
import { PendingError, type OAuth } from "./auth.ts"
import { parseSkillURI } from "./skills.ts"
import { rank } from "./toolsearch.ts"

/** Answers one MRTR input request (stands in for toolnexus §10 waitFor). */
export type Responder = (key: string, req: Obj) => Obj | Promise<Obj>

/** The transport behind a stdio client (stdio.ts). */
export interface Transport {
  roundTrip(id: number, body: string): Promise<Obj>
  close(): Promise<void>
}

export interface ClientOptions {
  http?: HttpFn // every MCP request goes through it
  headers?: Record<string, string> // extra headers on every request (e.g. a static API key)
  auth?: OAuth // undefined = no authorization (HTTP only; stdio servers take credentials from their env)
  toolSearch?: boolean // tools() exposes search_tools + call_tool instead of every server tool
  description?: string // what this server is for; shown in search_tools results
}

export class Client {
  url: string
  version = PROTOCOL_VERSION
  info: Obj = { name: "toolnexus-spike-js", version: "0.0.1" }
  caps: Obj = { elicitation: { form: {} } }
  http: HttpFn
  headers: Record<string, string>
  auth?: OAuth
  toolSearch: boolean
  description: string
  /** @internal stdio transport (connectStdio) */
  transport?: Transport
  /** @internal lets the self-test forge headers (negative cases only) */
  headerOverride?: Record<string, string>
  private nextId = 0
  private toolCache?: Obj[]
  private toolExpiry = 0
  private toolLoad?: Promise<Obj[]>

  constructor(url: string, o: ClientOptions = {}) {
    this.url = url
    this.http = o.http ?? defaultHttp
    this.headers = o.headers ?? {}
    this.auth = o.auth
    this.toolSearch = o.toolSearch ?? false
    this.description = o.description ?? ""
  }

  /**
   * Sends one request; params gets the per-request _meta merged in.
   *
   * Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
   * carrying WWW-Authenticate — the server rejected it before dispatch, so the tool never ran. A lost
   * connection, timeout, 5xx or unreadable response is thrown and NEVER retried: the server may
   * already have executed the call.
   */
  async call(method: string, params: Obj = {}): Promise<Obj> {
    params._meta = { [META_VERSION]: this.version, [META_CLIENT_CAPS]: this.caps, [META_CLIENT_INFO]: this.info }
    const id = ++this.nextId
    const body = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    const msg = this.transport ? await this.transport.roundTrip(id, body) : await this.httpRoundTrip(method, params, body)
    if (msg.error) {
      const e = msg.error
      throw new RpcError(Number(e.code), String(e.message ?? ""), e.data)
    }
    const r = msg.result
    if (!r || typeof r !== "object" || Array.isArray(r)) throw new Error("response has neither result nor error") // hostile/broken server
    if (!("resultType" in r)) r.resultType = "complete" // earlier-protocol servers
    return r
  }

  private async httpRoundTrip(method: string, params: Obj, body: string): Promise<Obj> {
    let resp: HttpResponse
    for (let attempt = 0; ; attempt++) {
      const sent = this.auth ? await this.auth.bearer(this.url) : ""
      resp = await this.post(method, params, body, sent) // a network error is never retried
      const www = resp.headers["www-authenticate"] ?? ""
      if (!this.auth || (resp.status !== 401 && resp.status !== 403) || !www) break
      if (attempt >= 3) throw new Error(`authorization failed: HTTP ${resp.status} after ${attempt + 1} attempts`) // spec: Step-Up retry limit
      await this.auth.challenge(this.url, resp.status, www, sent, () => this.call(method, params))
    }
    let msg: Obj | undefined
    if ((resp.headers["content-type"] ?? "").startsWith("text/event-stream")) {
      // Take the last data: payload that carries a result or error (the final response).
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
      } catch (e) {
        throw new Error(`HTTP ${resp.status}: ${(e as Error).message}`)
      }
    }
    if (!msg || typeof msg !== "object" || (msg.result == null && msg.error == null))
      throw new Error(`HTTP ${resp.status}: response has neither result nor error`)
    if (msg.error != null && (typeof msg.error !== "object" || typeof msg.error.code !== "number"))
      throw new Error(`HTTP ${resp.status}: malformed JSON-RPC error`)
    return msg
  }

  /** @internal */
  post(method: string, params: Obj, body: string, bearer: string): Promise<HttpResponse> {
    const h: Record<string, string> = {
      ...this.headers,
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      "MCP-Protocol-Version": this.version,
      "Mcp-Method": method,
    }
    if (method === "tools/call" || method === "prompts/get") h["Mcp-Name"] = encodeHeader(String(params.name ?? ""))
    if (method === "resources/read") h["Mcp-Name"] = encodeHeader(String(params.uri ?? ""))
    Object.assign(h, this.headerOverride)
    if (bearer) h.Authorization = "Bearer " + bearer
    return this.http({ method: "POST", url: this.url, headers: h, body })
  }

  /** tools/call, looping over input_required rounds via respond. */
  callTool(name: string, args: Obj = {}, respond?: Responder): Promise<Obj> {
    return this.runTool(name, args ?? {}, respond, undefined, undefined, 0)
  }

  private async runTool(name: string, args: Obj, respond: Responder | undefined, responses: Obj | undefined, state: unknown, round: number): Promise<Obj> {
    for (; round < 8; round++) {
      const p: Obj = { name, arguments: args }
      if (responses) p.inputResponses = responses
      if (state !== undefined && state !== null) p.requestState = state
      let res: Obj
      try {
        res = await this.call("tools/call", p)
      } catch (e) {
        if (e instanceof PendingError && this.auth) {
          // The paused request got 401/403 before dispatch: the server has NOT run this round.
          // Resume sends exactly this round (same inputResponses/requestState) for the first time —
          // earlier rounds are not replayed and the user is not re-asked.
          const [rs, st, r] = [responses, state, round]
          this.auth.setRetry(e.pending.id, () => this.runTool(name, args, respond, rs, st, r))
        }
        throw e
      }
      if (res.resultType !== "input_required") return res
      state = res.requestState
      responses = {}
      if (!respond) throw new Error(`tools/call ${name}: server asked for input but no Responder was given`)
      const reqs: Obj = res.inputRequests && typeof res.inputRequests === "object" ? res.inputRequests : {}
      for (const [k, v] of Object.entries(reqs)) {
        if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`tools/call ${name}: malformed input request "${k}"`)
        responses[k] = await respond(k, v)
      }
    }
    throw new Error(`tools/call ${name}: too many input_required rounds`)
  }

  /** Tool definitions to give the model: every server tool, or (toolSearch) the two meta-tools. Run them with invoke. */
  async tools(): Promise<Obj[]> {
    if (!this.toolSearch) return this.listTools()
    const about = this.description || "this MCP server"
    return [
      {
        name: "search_tools",
        description: `Search the tools of ${about} by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.`,
        inputSchema: { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" } }, required: ["query"] },
      },
      {
        name: "call_tool", description: "Run a tool found with search_tools.",
        inputSchema: { type: "object", properties: { name: { type: "string" }, arguments: { type: "object" } }, required: ["name"] },
      },
    ]
  }

  /** Runs a tool the model asked for: a server tool, or search_tools / call_tool in toolSearch mode. */
  async invoke(name: string, args: Obj = {}, respond?: Responder): Promise<Obj> {
    if (!this.toolSearch) return this.callTool(name, args, respond)
    if (name === "search_tools") {
      const limit = typeof args.limit === "number" && args.limit > 0 ? Math.trunc(args.limit) : 5
      const hits = await this.searchTools(String(args.query ?? ""), limit)
      return { resultType: "complete", content: [{ type: "text", text: JSON.stringify(hits) }], structuredContent: { tools: hits }, isError: false }
    }
    if (name === "call_tool") {
      const a = args.arguments && typeof args.arguments === "object" ? args.arguments : {}
      return this.callTool(String(args.name ?? ""), a, respond)
    }
    throw new Error(`unknown tool "${name}" (toolSearch mode exposes search_tools and call_tool)`)
  }

  /** Ranks the server's tools against query (BM25; name tokens count twice). */
  async searchTools(query: string, limit = 5): Promise<Obj[]> {
    const tools = await this.listTools()
    return rank(tools, query, limit).map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema, server: this.description }))
  }

  /** tools/list, cached for the server's ttlMs (concurrent callers share one request). */
  private listTools(): Promise<Obj[]> {
    if (this.toolCache && Date.now() < this.toolExpiry) return Promise.resolve(this.toolCache)
    this.toolLoad ??= (async () => {
      try {
        const r = await this.call("tools/list")
        const tools = (Array.isArray(r.tools) ? r.tools : []).filter((t: unknown) => t && typeof t === "object")
        this.toolCache = tools
        this.toolExpiry = Date.now() + (typeof r.ttlMs === "number" ? r.ttlMs : 0)
        return tools
      } finally {
        this.toolLoad = undefined
      }
    })()
    return this.toolLoad
  }

  /**
   * Fetches a skill (skills/get + resources/read for every file) into dir/<name>/, verifying each
   * file's sha256 digest. A file whose URI is not inside the skill is refused; nothing is written
   * until every file has been validated.
   */
  async downloadSkill(uri: string, dir: string): Promise<void> {
    const { name } = parseSkillURI(uri)
    const r = await this.call("skills/get", { uri })
    const res: Obj[] = Array.isArray(r.skill?.resources) ? r.skill.resources : []
    const files: [string, string][] = []
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
      const rr = await this.call("resources/read", { uri: fu })
      const cs = Array.isArray(rr.contents) ? rr.contents : []
      if (cs.length === 0) throw new Error(`${fu}: empty resources/read`)
      const t = typeof cs[0]?.text === "string" ? cs[0].text : ""
      const d = typeof m.digest === "string" ? m.digest : ""
      if (d && d !== "sha256:" + createHash("sha256").update(t).digest("hex")) throw new Error(`${fu}: digest mismatch`)
      const target = path.join(dir, name, ...rel.split("/"))
      if (!target.startsWith(path.join(dir, name) + path.sep)) throw new Error(`refusing skill resource "${fu}": outside skill ${name}`)
      files.push([target, t])
    }
    for (const [p, t] of files) {
      await mkdir(path.dirname(p), { recursive: true })
      await writeFile(p, t)
    }
  }

  /** Stops a stdio server (close stdin, then kill after 2s); no-op for HTTP clients. */
  async close(): Promise<void> {
    await this.transport?.close()
  }
}

/** Plain value if printable ASCII without edge whitespace, else the =?base64?…?= sentinel. */
export function encodeHeader(v: string): string {
  const safe = v === v.trim() && !(v.startsWith("=?base64?") && v.endsWith("?=")) && /^[\x20-\x7e]*$/.test(v)
  return safe ? v : "=?base64?" + Buffer.from(v, "utf8").toString("base64") + "?="
}

