// Stateless MCP 2026-07-28 server over POST /mcp (node:http request listener).
import { createHash } from "node:crypto"
import { readFileSync, readdirSync, statSync } from "node:fs"
import http from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  CODE_HEADER_MISMATCH, CODE_INVALID_PARAMS, CODE_METHOD_NOT_FOUND, CODE_UNSUPPORTED_PROTOCOL,
  META_SERVER_INFO, META_VERSION, PROTOCOL_VERSION, RpcError, SKILLS_EXT, type Obj,
} from "./protocol.ts"
import { parseSkillURI } from "./skills.ts"
import type { Principal, ServerAuth } from "./serverauth.ts"

/** What a tool handler gets besides its arguments. */
export interface ToolContext {
  principal?: Principal // the verified caller when the server has auth
}
/** Return a string (text result), an object with "content" (a full CallToolResult), or any JSON value (structured). */
export type ToolHandler = (args: Obj, ctx: ToolContext) => unknown | Promise<unknown>

interface SkillFile { uri: string; data: Buffer }
interface Skill { uri: string; frontmatter: Obj; files: SkillFile[] }

/** A request body larger than this gets 413. */
export const MAX_BODY_BYTES = 4 << 20

const byName = (a: Obj, b: Obj) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
const byUri = (a: { uri: string }, b: { uri: string }) => (a.uri < b.uri ? -1 : a.uri > b.uri ? 1 : 0)

export class Server {
  /** nil = unprotected (the default; cross-port checks run without auth). */
  auth?: ServerAuth
  private info: Obj = {}
  private skills: Skill[] = [] // sorted by uri
  private handlers = new Map<string, ToolHandler>()
  private toolDefs: Obj[] = [] // fixture + code tools, sorted by name

  /** Registers a code-defined tool (listed in tools/list); chainable. */
  tool(name: string, description: string, handler: ToolHandler): this {
    this.handlers.set(name, handler)
    this.toolDefs = this.toolDefs.filter((t) => t.name !== name)
    this.toolDefs.push({ name, description, inputSchema: { type: "object" } })
    this.toolDefs.sort(byName)
    return this
  }

  /** Loads a fixture: {server, tools, skillsDir} (skillsDir relative to the fixture file). */
  static load(fixturePath: string): Server {
    const fx = JSON.parse(readFileSync(fixturePath, "utf8"))
    const s = new Server()
    s.info = fx.server ?? {}
    for (const t of fx.tools ?? []) {
      if (typeof t?.name !== "string") throw new Error(`fixture tool without a name: ${JSON.stringify(t)}`)
      s.toolDefs.push(t)
    }
    s.toolDefs.sort(byName)
    const root = path.join(path.dirname(fixturePath), fx.skillsDir ?? ".")
    for (const p of walk(root)) if (path.basename(p) === "SKILL.md") s.skills.push(loadSkill(path.dirname(p)))
    s.skills.sort(byUri)
    return s
  }

  /** The node:http listener: `http.createServer(server.listener)`. */
  listener = (req: IncomingMessage, res: ServerResponse): void => {
    this.serve(req, res).catch((e) => {
      if (!res.headersSent) writeRPC(res, 500, null, undefined, new RpcError(-32603, "Internal error"))
      else res.destroy(e)
    })
  }

  /** Listens on host:port (port 0 = random); resolves with the MCP URL and a close function. */
  listen(port = 0, host = "127.0.0.1"): Promise<{ url: string; port: number; close: () => Promise<void> }> {
    const hs = http.createServer(this.listener)
    return new Promise((resolve, reject) => {
      hs.once("error", reject)
      hs.listen(port, host, () => {
        const p = (hs.address() as { port: number }).port
        resolve({ url: `http://${host}:${p}/mcp`, port: p, close: () => closeServer(hs) })
      })
    })
  }

  private serverInfo(): Obj {
    return { [META_SERVER_INFO]: this.info }
  }

  private async serve(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const pathname = new URL(req.url ?? "/", "http://x").pathname
    if (this.auth && req.method === "GET" && this.auth.serveMetadata(req, res, pathname)) return
    if (pathname !== "/mcp") return void res.writeHead(404).end("404 page not found\n")
    if (req.method !== "POST") return void res.writeHead(405).end()
    let body: string
    try {
      body = await readBody(req, MAX_BODY_BYTES)
    } catch {
      return writeRPC(res, 413, null, undefined, new RpcError(-32600, "Request body too large or unreadable"))
    }
    let msg: any
    try {
      msg = JSON.parse(body)
      if (!msg || typeof msg !== "object" || Array.isArray(msg)) throw new Error()
    } catch {
      return writeRPC(res, 400, null, undefined, new RpcError(-32700, "Parse error"))
    }
    if (msg.id === undefined || msg.id === null) return void res.writeHead(202).end() // notification: accept
    const method = typeof msg.method === "string" ? msg.method : ""
    const params: Obj = msg.params && typeof msg.params === "object" && !Array.isArray(msg.params) ? msg.params : {}
    const bad = this.validate(req, method, params)
    if (bad) return writeRPC(res, bad[0], msg.id, undefined, bad[1])
    let ctx: ToolContext = {}
    if (this.auth) {
      const p = await this.auth.check(req, res, method, params)
      if (!p) return
      ctx = { principal: p }
    }
    try {
      const result = await this.dispatch(method, params, ctx)
      writeRPC(res, 200, msg.id, result)
    } catch (e) {
      if (!(e instanceof RpcError)) throw e
      writeRPC(res, e.code === CODE_METHOD_NOT_FOUND ? 404 : 200, msg.id, undefined, e)
    }
  }

  private validate(req: IncomingMessage, method: string, p: Obj): [number, RpcError] | undefined {
    const mismatch = (m: string): [number, RpcError] => [400, new RpcError(CODE_HEADER_MISMATCH, "Header mismatch: " + m)]
    const hv = header(req, "mcp-protocol-version")
    if (hv === "") return mismatch("MCP-Protocol-Version header missing")
    const hm = header(req, "mcp-method")
    if (hm !== method) return mismatch(`Mcp-Method header value '${hm}' does not match body value '${method}'`)
    if (method === "tools/call" || method === "resources/read" || method === "prompts/get") {
      const want = String((method === "resources/read" ? p.uri : p.name) ?? "")
      const got = decodeHeader(header(req, "mcp-name"))
      if (got === undefined || got !== want) return mismatch(`Mcp-Name header value '${got ?? ""}' does not match body value '${want}'`)
    }
    const meta = p._meta && typeof p._meta === "object" ? p._meta : {}
    const mv = typeof meta[META_VERSION] === "string" ? meta[META_VERSION] : ""
    if (mv !== hv) return mismatch(`MCP-Protocol-Version header value '${hv}' does not match _meta value '${mv}'`)
    if (mv !== PROTOCOL_VERSION)
      return [400, new RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version", { supported: [PROTOCOL_VERSION], requested: mv })]
    return undefined
  }

  /** Runs one method (shared by HTTP and stdio). Throws RpcError. */
  async dispatch(method: string, p: Obj, ctx: ToolContext = {}): Promise<Obj> {
    switch (method) {
      case "server/discover":
        return {
          resultType: "complete", supportedVersions: [PROTOCOL_VERSION],
          capabilities: { tools: {}, resources: {}, extensions: { [SKILLS_EXT]: {} } },
          _meta: this.serverInfo(), ttlMs: 3600000, cacheScope: "public",
        }
      case "tools/list":
        return { resultType: "complete", tools: this.toolDefs, ttlMs: 60000, cacheScope: "public", _meta: this.serverInfo() }
      case "tools/call":
        return this.callTool(p, ctx)
      case "skills/list":
        return { resultType: "complete", skills: this.skills.map(entry), ttlMs: 60000, cacheScope: "public", _meta: this.serverInfo() }
      case "skills/get": {
        const uri = String(p.uri ?? "")
        safeURI(uri, "Invalid skill URI: ")
        const sk = this.skills.find((s) => s.uri === uri)
        if (!sk) throw new RpcError(CODE_INVALID_PARAMS, "Unknown skill: " + uri)
        return { resultType: "complete", skill: entry(sk), _meta: this.serverInfo() }
      }
      case "resources/read": {
        const uri = String(p.uri ?? "")
        safeURI(uri, "Invalid resource URI: ")
        for (const sk of this.skills)
          for (const f of sk.files)
            if (f.uri === uri)
              return {
                resultType: "complete",
                contents: [{ uri, mimeType: uri.endsWith(".md") ? "text/markdown" : "text/plain", text: f.data.toString("utf8") }],
                ttlMs: 60000, cacheScope: "public", _meta: this.serverInfo(),
              }
        throw new RpcError(CODE_INVALID_PARAMS, "Resource not found: " + uri)
      }
    }
    throw new RpcError(CODE_METHOD_NOT_FOUND, "Method not found: " + method)
  }

  private async callTool(p: Obj, ctx: ToolContext): Promise<Obj> {
    const name = String(p.name ?? "")
    const args: Obj = p.arguments && typeof p.arguments === "object" ? p.arguments : {}
    const h = this.handlers.get(name)
    if (h) {
      let v: unknown
      try {
        v = await h(args, ctx)
      } catch (e) {
        return { resultType: "complete", content: [{ type: "text", text: (e as Error)?.message ?? String(e) }], isError: true }
      }
      if (typeof v === "string") return { resultType: "complete", content: [{ type: "text", text: v }], isError: false }
      if (v && typeof v === "object" && !Array.isArray(v) && "content" in v) return { resultType: "complete", ...(v as Obj) }
      return { resultType: "complete", content: [{ type: "text", text: JSON.stringify(v ?? null) }], structuredContent: v ?? null, isError: false }
    }
    const textResult = (t: string, structured: Obj): Obj => ({
      resultType: "complete", content: [{ type: "text", text: t }], isError: false, _meta: this.serverInfo(), structuredContent: structured,
    })
    if (name === "echo") {
      const t = typeof args.text === "string" ? args.text : ""
      return textResult(t, { text: t })
    }
    if (name === "greet") {
      const nr = p.inputResponses?.name
      const n = nr?.action === "accept" ? nr?.content?.name : undefined
      if (typeof n === "string" && n !== "") return textResult(`Hello, ${n}!`, { greeting: `Hello, ${n}!` })
      // Missing/declined answer: ask (again), per MRTR error-handling guidance.
      return {
        resultType: "input_required", _meta: this.serverInfo(),
        inputRequests: {
          name: {
            method: "elicitation/create",
            params: {
              mode: "form", message: "What is your name?",
              requestedSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] },
            },
          },
        },
      }
    }
    throw new RpcError(CODE_INVALID_PARAMS, "Unknown tool: " + name)
  }
}

function safeURI(uri: string, prefix: string): void {
  try {
    parseSkillURI(uri)
  } catch (e) {
    throw new RpcError(CODE_INVALID_PARAMS, prefix + (e as Error).message)
  }
}

function entry(sk: Skill): Obj {
  return {
    uri: sk.uri, frontmatter: sk.frontmatter,
    resources: sk.files.map((f) => ({ uri: f.uri, digest: "sha256:" + createHash("sha256").update(f.data).digest("hex"), size: f.data.length })),
  }
}

function walk(dir: string): string[] {
  const out: string[] = []
  for (const e of readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) out.push(...walk(p))
    else if (e.isFile() || statSync(p).isFile()) out.push(p)
  }
  return out
}

function loadSkill(dir: string): Skill {
  const fm = parseFrontmatter(readFileSync(path.join(dir, "SKILL.md"), "utf8"))
  const name = fm.name
  if (!name || name !== path.basename(dir)) throw new Error(`skill ${dir}: name must match dir`)
  const files = walk(dir).map((p) => ({
    uri: `skill://${name}/${path.relative(dir, p).split(path.sep).join("/")}`, data: readFileSync(p),
  }))
  return { uri: `skill://${name}/SKILL.md`, frontmatter: fm, files: files.sort(byUri) }
}

/** The flat `key: value` YAML subset the fixture skills use. */
export function parseFrontmatter(md: string): Obj {
  const fm: Obj = {}
  md = md.replaceAll("\r\n", "\n")
  if (!md.startsWith("---\n")) return fm
  const end = md.slice(4).indexOf("\n---")
  if (end < 0) return fm
  for (const line of md.slice(4, 4 + end).split("\n")) {
    const i = line.indexOf(":")
    if (i >= 0 && !line.startsWith(" ")) fm[line.slice(0, i).trim()] = line.slice(i + 1).trim().replace(/^["']+|["']+$/g, "")
  }
  return fm
}

function header(req: IncomingMessage, name: string): string {
  const v = req.headers[name]
  return Array.isArray(v) ? v[0] ?? "" : v ?? ""
}

/** Decodes the `=?base64?...?=` sentinel; undefined = empty or undecodable. */
export function decodeHeader(v: string): string | undefined {
  if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11) {
    const b = v.slice(9, -2)
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(b) || b.length % 4 !== 0) return undefined
    return Buffer.from(b, "base64").toString("utf8")
  }
  return v === "" ? undefined : v
}

function readBody(req: IncomingMessage, max: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let n = 0
    req.on("data", (c: Buffer) => {
      n += c.length
      if (n > max) {
        reject(new Error("too large"))
        req.pause()
        return
      }
      chunks.push(c)
    })
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")))
    req.on("error", reject)
  })
}

export function writeRPC(res: ServerResponse, status: number, id: unknown, result?: Obj, e?: RpcError): void {
  const msg: Obj = { jsonrpc: "2.0", id: id ?? null }
  if (e) msg.error = e.toJSON()
  else msg.result = result
  res.writeHead(status, { "Content-Type": "application/json" })
  res.end(JSON.stringify(msg) + "\n")
}

export function closeServer(hs: http.Server): Promise<void> {
  return new Promise((r) => {
    hs.close(() => r())
    hs.closeAllConnections()
  })
}

/** The shared spike server (tools echo + greet, skill hello-world) from ../fixture.json, or $FIXTURE. */
export function fixtureServer(): Server {
  return Server.load(process.env.FIXTURE || path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "fixture.json"))
}
