/**
 * The 2026-07-28 direct path behind loadMcp (SPEC §2.2–§2.8, §2A): discover first, then tools,
 * skills and tool search; MRTR and OAuth pauses surface through §10 (waitFor inline, or a
 * `{status:"pending"}` suspension resumed via `ctx.answer`). `probe` reports `fallback` when the
 * server is pre-2026 so loadMcp takes the frozen SDK path (§2.F).
 */
import { randomBytes } from "node:crypto"
import { Mcp2026Client } from "./client.js"
import { HttpStatusError, NotJsonRpcError, PROTOCOL_VERSION, RpcError, SKILLS_EXT, type Obj } from "./protocol.js"
import { LoopbackAuthorizer, MemoryTokenStore, OAuth, PauseAuthorizer, PendingError, type TokenStore } from "./auth.js"
import { spawnStdio, StdioClosedError } from "./stdio.js"
import { rank } from "./toolsearch.js"
import type { Answer, JSONSchema, Request, Tool, ToolContext, ToolResult } from "../types.js"
import { sanitize } from "../types.js"
import type { SkillDef } from "../skill.js"
import { answerToElicitResult, elicitationToRequest, mapCallToolResult, mcpHints } from "../mcp.js"

export type WaitFor = (request: Request) => Promise<Answer>

/** Per-server `auth` (§2A.1; keys camelCase as in mcp.json). */
export interface AuthConfig {
  clientId?: string
  clientSecret?: string
  tokenAuthMethod?: "client_secret_basic" | "client_secret_post"
  clientIds?: Record<string, string>
  clientMetadataUrl?: string
  redirectUri?: string
  mode?: "auto" | "pause"
  omitRefreshResource?: boolean
  allowHttp?: boolean
}

export interface Load2026Options {
  waitFor?: WaitFor
  tokenStore?: TokenStore
  openUrl?: (url: string) => unknown | Promise<unknown>
  signal?: AbortSignal
  /** Final exposed names to drop (Toolkit `disableTools`); applied before tool search too. */
  disableTools?: string[]
}

/** The OAuth engine for one remote server (§2A.1). */
export function newOAuth(auth: AuthConfig | undefined, o: Load2026Options, store: TokenStore): OAuth {
  const a = auth ?? {}
  const mode = a.mode ?? "auto"
  let authorizer
  if (mode === "pause") {
    if (!a.redirectUri) throw new Error("auth.redirectUri is required for pause mode")
    authorizer = new PauseAuthorizer({ redirect: a.redirectUri })
  } else {
    authorizer = new LoopbackAuthorizer(o.openUrl ? { open: o.openUrl } : {})
  }
  return new OAuth({
    authorizer, store,
    clientId: a.clientId, clientSecret: a.clientSecret, tokenAuthMethod: a.tokenAuthMethod,
    clientIds: a.clientIds, clientMetadataUrl: a.clientMetadataUrl,
    omitRefreshResource: a.omitRefreshResource, allowHttp: a.allowHttp,
  })
}

export function defaultTokenStore(o: Load2026Options): TokenStore {
  return o.tokenStore ?? new MemoryTokenStore()
}

function deniedError(a: Answer | undefined): Error {
  return new Error(`authorization denied: ${a?.reason ?? "cancelled"}`)
}

function callbackUrlOf(a: Answer): string {
  const u = a.data?.callbackUrl
  if (typeof u !== "string" || !u) throw new Error("authorization answer carries no data.callbackUrl")
  return u
}

/** Runs f; an OAuth pause is answered inline through waitFor, else it is a load failure. */
async function authed(oauth: OAuth | undefined, waitFor: WaitFor | undefined, f: () => Promise<Obj>): Promise<Obj> {
  try {
    return await f()
  } catch (e) {
    if (!(e instanceof PendingError) || !oauth) throw e
    if (!waitFor) throw new Error("authorization pending")
    const a = await waitFor({ ...e.pending })
    if (!a?.ok) throw deniedError(a)
    // resume() stores the token (and re-sends the paused read); f() then runs again, authorized.
    await oauth.resume(e.pending.id, callbackUrlOf(a))
    return f()
  }
}

/** What the probe decided. `fallback` ⇒ take the frozen SDK path; anything thrown ⇒ status failed. */
export type Probe = { kind: "modern"; server: ModernServer } | { kind: "fallback" }

/** §2.2a: which discover failures mean "old server". */
function isFallbackError(e: unknown): boolean {
  if (e instanceof RpcError) return true
  if (e instanceof HttpStatusError) return e.status === 400 || e.status === 404 || e.status === 405 || e.status >= 500
  if (e instanceof NotJsonRpcError) return true
  return false
}

function supports2026(r: Obj): boolean {
  return Array.isArray(r.supportedVersions) && r.supportedVersions.includes(PROTOCOL_VERSION)
}

export interface ProbeTarget {
  name: string
  timeout: number
  tools?: Record<string, boolean>
  toolSearch?: boolean
  description?: string
  remote?: { url: string; headers?: Record<string, string>; oauth: OAuth }
  local?: { argv: string[]; env: NodeJS.ProcessEnv; cwd?: string }
}

/** Discover first (§2.2). Resolves `modern` (tools listed) or `fallback`; throws on a load failure. */
export async function probe(t: ProbeTarget, o: Load2026Options): Promise<Probe> {
  let client: Mcp2026Client
  if (t.remote) {
    client = new Mcp2026Client(t.remote.url, { headers: t.remote.headers, auth: t.remote.oauth, timeoutMs: t.timeout })
  } else {
    const tr = await spawnStdio(t.local!.argv, t.local!.env, t.local!.cwd)
    client = new Mcp2026Client("", { transport: tr, timeoutMs: t.timeout })
  }
  const oauth = t.remote?.oauth
  let disc: Obj
  try {
    // stdio: an old server may ignore a pre-initialize request ⇒ min(timeout, 5 s) then fall back.
    const timeoutMs = t.local ? Math.min(t.timeout, 5000) : t.timeout
    disc = await authed(oauth, o.waitFor, () => client.call("server/discover", {}, { timeoutMs, signal: o.signal }))
  } catch (e) {
    const stdioTimeout = !!t.local && e instanceof Error && e.message === "stdio request timed out"
    await client.close().catch(() => {})
    if (o.signal?.aborted) throw e
    if (e instanceof StdioClosedError) throw e
    if (isFallbackError(e) || stdioTimeout) return { kind: "fallback" }
    throw e
  }
  if (!supports2026(disc)) {
    await client.close().catch(() => {})
    return { kind: "fallback" }
  }
  try {
    const server = new ModernServer(t, client, disc, o)
    await server.load()
    return { kind: "modern", server }
  } catch (e) {
    await client.close().catch(() => {})
    throw e
  }
}

/** §2 Gap 7 filter over raw defs (same semantics as the frozen path). */
function filterDefs(server: string, defs: Obj[], filter?: Record<string, boolean>): Obj[] {
  if (!filter) return defs
  const keys = Object.keys(filter)
  if (keys.length === 0) return defs
  const hasTrue = keys.some((k) => filter[k] === true)
  const present = new Set(defs.map((d) => d.name))
  for (const k of keys) if (!present.has(k)) console.warn(`[toolnexus] server "${server}" tools filter name "${k}" matched no tool`)
  return defs.filter((d) => (hasTrue ? filter[d.name] === true : filter[d.name] !== false))
}

function parseFrontmatter(md: string): { data: Record<string, string>; body: string } {
  const m = md.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/)
  if (!m) return { data: {}, body: md }
  const data: Record<string, string> = {}
  for (const line of m[1].split(/\r?\n/)) {
    if (/^\s/.test(line)) continue
    const i = line.indexOf(":")
    if (i <= 0) continue
    let v = line.slice(i + 1).trim()
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1)
    data[line.slice(0, i).trim()] = v
  }
  return { data, body: m[2] }
}

/** A suspended tools/call: where to continue once the Answer arrives. */
interface Cont {
  name: string
  args: Obj
  round: number
  state?: unknown
  responses?: Obj
  reqs: Obj
  keys: string[]
  idx: number
  asked?: Request
  authId?: string
}

const MAX_ROUNDS = 8

export class ModernServer {
  readonly client: Mcp2026Client
  readonly discover: Obj
  defs: Obj[] = []
  skills: SkillDef[] = []
  private all: Obj[] = []
  private cacheUntil = Infinity
  private pend = new Map<string, Cont>()

  constructor(private t: ProbeTarget, client: Mcp2026Client, disc: Obj, private o: Load2026Options) {
    this.client = client
    this.discover = disc
  }

  private get oauth(): OAuth | undefined {
    return this.t.remote?.oauth
  }

  private req<T extends Obj>(f: () => Promise<T>): Promise<Obj> {
    return authed(this.oauth, this.o.waitFor, f)
  }

  async load(): Promise<void> {
    await this.refreshTools()
    if (this.discover.capabilities?.extensions?.[SKILLS_EXT] !== undefined) await this.loadSkills()
  }

  private async refreshTools(): Promise<void> {
    const r = await this.req(() => this.client.listTools({ signal: this.o.signal }))
    this.all = r.tools
    const off = new Set(this.o.disableTools ?? [])
    const prefix = sanitize(this.t.name) + "_"
    this.defs = filterDefs(this.t.name, r.tools, this.t.tools).filter((d) => !off.has(prefix + sanitize(d.name)))
    this.cacheUntil = typeof r.ttlMs === "number" ? Date.now() + r.ttlMs : Infinity
  }

  private async loadSkills(): Promise<void> {
    const r = await this.req(() => this.client.call("skills/list", {}, { signal: this.o.signal }))
    for (const s of Array.isArray(r.skills) ? r.skills : []) {
      const uri = String(s?.uri ?? "")
      try {
        const f = await this.req(() => this.client.fetchSkill(uri, { signal: this.o.signal }))
        const { data, body } = parseFrontmatter(f.markdown)
        const name = data.name || f.name
        this.skills.push({ name, description: data.description ?? "", content: body, resources: f.resources, base: `skill://${f.name}/` })
      } catch (e) {
        console.warn(`[toolnexus] MCP server "${this.t.name}" skill ${JSON.stringify(uri)} skipped: ${e instanceof Error ? e.message : String(e)}`)
      }
    }
  }

  /** The toolkit tools for this server: every (filtered) server tool, or the two tool-search tools. */
  tools(): Tool[] {
    const server = this.t.name
    if (!this.t.toolSearch) return this.defs.map((d) => this.convert(d))
    const about = this.t.description || server
    const search: Tool = {
      name: sanitize(server) + "_search_tools",
      description: `Search the tools of ${about} by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.`,
      inputSchema: { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" } }, required: ["query"] },
      source: "mcp",
      execute: async (args, ctx) => {
        try {
          if (Date.now() >= this.cacheUntil) await this.refreshTools()
          const limit = typeof args?.limit === "number" && args.limit > 0 ? Math.trunc(args.limit) : 5
          const hits = rank(this.defs, String(args?.query ?? ""), limit).map((d) => ({
            name: d.name, description: d.description, inputSchema: d.inputSchema, server: this.t.description ?? "",
          }))
          return { output: JSON.stringify(hits), isError: false, metadata: { server } }
        } catch (e) {
          return { output: e instanceof Error ? e.message : String(e), isError: true, metadata: { server } }
        }
      },
    }
    const call: Tool = {
      name: sanitize(server) + "_call_tool",
      description: "Run a tool found with search_tools.",
      inputSchema: { type: "object", properties: { name: { type: "string" }, arguments: { type: "object" } }, required: ["name"] },
      source: "mcp",
      execute: async (args, ctx) => {
        const name = String(args?.name ?? "")
        const a = args?.arguments && typeof args.arguments === "object" && !Array.isArray(args.arguments) ? (args.arguments as Obj) : {}
        if (!(ctx?.answer && this.pend.has(ctx.answer.id)) && !this.defs.some((d) => d.name === name)) {
          return { output: `unknown tool "${name}" on ${server}`, isError: true, metadata: { server } }
        }
        return this.run(name, a, ctx)
      },
    }
    return [search, call]
  }

  private convert(def: Obj): Tool {
    const inputSchema: JSONSchema = {
      ...(def.inputSchema as object),
      type: "object",
      properties: (def.inputSchema?.properties ?? {}) as Record<string, unknown>,
      additionalProperties: false,
    }
    const hints = mcpHints(def.annotations)
    return {
      name: sanitize(this.t.name) + "_" + sanitize(def.name),
      description: def.description ?? "",
      inputSchema,
      source: "mcp",
      ...(hints ? { hints } : {}),
      execute: (args, ctx) => this.run(def.name, args ?? {}, ctx),
    }
  }

  /** tools/call with MRTR rounds (§2.5) and OAuth pauses (§2A.1 10); never re-sends after a failure (§2.6). */
  async run(name: string, args: Obj, ctx?: ToolContext): Promise<ToolResult> {
    const server = this.t.name
    try {
      const answer = ctx?.answer
      const cont = answer ? this.pend.get(answer.id) : undefined
      if (cont && answer) {
        this.pend.delete(answer.id)
        return await this.drive(cont, answer, ctx)
      }
      return await this.drive({ name, args, round: 0, reqs: {}, keys: [], idx: 0 }, undefined, ctx)
    } catch (e) {
      return { output: e instanceof Error ? e.message : String(e), isError: true, metadata: { server } }
    }
  }

  private suspend(cont: Cont, req: Request): ToolResult {
    this.pend.set(req.id, cont)
    const output = req.url ? `${req.prompt}: ${req.url}` : req.prompt
    return { output, isError: true, metadata: { pending: req, server: this.t.name } }
  }

  private async drive(cont: Cont, answer: Answer | undefined, ctx?: ToolContext): Promise<ToolResult> {
    const waitFor = this.o.waitFor
    let res: Obj | undefined
    if (answer) {
      if (cont.authId) {
        if (!answer.ok) throw deniedError(answer)
        const id = cont.authId
        cont.authId = undefined
        res = await this.oauth!.resume(id, callbackUrlOf(answer))
      } else {
        cont.responses![cont.keys[cont.idx]] = answerToElicitResult(answer)
        cont.idx++
      }
    }
    for (;;) {
      if (!res) {
        while (cont.idx < cont.keys.length) {
          const key = cont.keys[cont.idx]
          const req = this.toRequest(key, cont.reqs[key])
          if (!waitFor) return this.suspend(cont, req)
          cont.responses![key] = answerToElicitResult(await waitFor(req))
          cont.idx++
        }
        if (cont.round >= MAX_ROUNDS) throw new Error(`tools/call ${cont.name}: too many input_required rounds`)
        const p: Obj = { name: cont.name, arguments: cont.args }
        if (cont.responses) p.inputResponses = cont.responses
        if (cont.state !== undefined && cont.state !== null) p.requestState = cont.state
        try {
          res = await this.client.call("tools/call", p, { timeoutMs: ctx?.timeout, signal: ctx?.signal })
        } catch (e) {
          if (!(e instanceof PendingError) || !this.oauth) throw e
          // Rejected before dispatch (401/403): this round has NOT run. Resume sends it once.
          if (!waitFor) {
            cont.authId = e.pending.id
            return this.suspend(cont, { ...e.pending })
          }
          const a = await waitFor({ ...e.pending })
          if (!a?.ok) throw deniedError(a)
          res = await this.oauth.resume(e.pending.id, callbackUrlOf(a))
        }
      }
      if (res.resultType !== "input_required") return mapCallToolResult(res, this.t.name)
      const reqs: Obj = res.inputRequests && typeof res.inputRequests === "object" && !Array.isArray(res.inputRequests) ? res.inputRequests : {}
      for (const [k, v] of Object.entries(reqs)) {
        if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`tools/call ${cont.name}: malformed input request "${k}"`)
      }
      cont.round++
      cont.state = res.requestState
      cont.responses = {}
      cont.reqs = reqs
      cont.keys = Object.keys(reqs)
      cont.idx = 0
      res = undefined
    }
  }

  private toRequest(key: string, ir: Obj): Request {
    if (ir.method !== "elicitation/create") throw new Error(`unsupported input request method ${JSON.stringify(ir.method)} ("${key}")`)
    const req = elicitationToRequest(ir.params ?? {})
    req.id = "mrtr-" + randomBytes(9).toString("base64url")
    return req
  }

  /** resources/list for list_mcp_resources (§4C). */
  async listResources(signal?: AbortSignal): Promise<Obj[]> {
    const r = await this.req(() => this.client.call("resources/list", {}, { signal }))
    return Array.isArray(r.resources) ? r.resources : []
  }

  async readResource(uri: string, signal?: AbortSignal): Promise<Obj[]> {
    const r = await this.req(() => this.client.call("resources/read", { uri }, { signal }))
    return Array.isArray(r.contents) ? r.contents : []
  }

  /** Raw (unfiltered) tool defs, for listMcpTools. */
  rawDefs(): Obj[] {
    return this.all
  }

  close(): Promise<void> {
    return this.client.close()
  }
}
