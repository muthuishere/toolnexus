/**
 * Replays ../conformance/mcp_handler/cases.json (SPEC §7C.1, ADR 0039) exactly per its README
 * against toolkit.mcpHandler over real loopback HTTP. The Go reference is
 * golang/mcphandler_conformance_test.go. The fixture is read in place, never copied.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import http from "node:http"
import path from "node:path"
import type { AddressInfo } from "node:net"
import { fileURLToPath } from "node:url"
import {
  createToolkit,
  identityFrom,
  mcpRequestMetaFrom,
  loadMcpSkills,
  McpServerAuth,
  pending,
  type Tool,
  type ToolResult,
  type McpHandlerConfig,
} from "../dist/index.js"

const casesPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../conformance/mcp_handler/cases.json")
const cases = JSON.parse(fs.readFileSync(casesPath, "utf8"))
const skillsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../examples/skills")
const META_VERSION = "io.modelcontextprotocol/protocolVersion"

/** README identity-name rule: a string verbatim, a Principal's subject, else "". */
const identityName = (v: unknown): string =>
  typeof v === "string" ? v : v && typeof v === "object" && typeof (v as any).subject === "string" ? (v as any).subject : ""

const toResult = (r: any): ToolResult => {
  const out: ToolResult = { output: r?.output ?? "", isError: !!r?.isError }
  if (r?.structured !== undefined) out.structured = r.structured
  if (r?.meta !== undefined) out.meta = r.meta
  return out
}

function buildTools(executed: string[]): Tool[] {
  return cases.fixture.tools.map((ft: any) => {
    const t: Tool = {
      name: ft.name,
      description: ft.description,
      inputSchema: ft.inputSchema,
      source: "native",
      async execute(args, ctx) {
        executed.push(ft.name)
        switch (ft.behavior) {
          case "echo":
            return { output: String((args as any).text ?? ""), isError: false }
          case "whoami": {
            const id = identityName(identityFrom(ctx))
            const trace = mcpRequestMetaFrom(ctx)?.trace
            return { output: `${id}|${trace ?? ""}`, isError: false }
          }
          case "ask": {
            const a = ctx?.answer
            if (a?.ok) return { output: `Hello, ${(a.data as any)?.name}!`, isError: false }
            return pending({
              id: "name",
              kind: "input",
              prompt: "What is your name?",
              data: { schema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
            })
          }
          case "static":
            return toResult(ft.result)
          case "error":
            throw new Error(ft.error)
        }
        throw new Error("unknown behavior " + ft.behavior)
      },
    }
    if (ft.annotations) t.annotations = ft.annotations
    if (ft.hints) t.hints = ft.hints
    if (ft.outputSchema) t.outputSchema = ft.outputSchema
    return t
  })
}

function getPath(v: any, p: string): any {
  for (const seg of p.split(".")) {
    if (v === undefined || v === null) return undefined
    v = Array.isArray(v) ? v[Number(seg)] : v[seg]
  }
  return v
}

for (const sc of cases.scenarios) {
  test(`mcp_handler conformance: ${sc.name}`, async () => {
    const executed: string[] = []
    const onCalls: any[] = []
    const tools = buildTools(executed)
    const fx = cases.fixture
    const c = sc.config ?? {}
    const cfg: McpHandlerConfig = {
      // listTools ⇒ every fixture tool minus those whose visibleTo excludes the identity
      listTools: (ctx) => {
        const id = identityName(identityFrom(ctx))
        return tools.filter((_t, i) => {
          const vis = fx.tools[i].visibleTo
          return !vis || vis.includes(id)
        })
      },
      beforeCall: (_ctx, tool) => {
        const d = fx.tools.find((t: any) => t.name === tool.name)?.decision
        if (!d) return { kind: "allow" }
        return { kind: d.kind, message: d.message, result: d.result ? toResult(d.result) : undefined }
      },
      onCall: (ev) => onCalls.push({ name: ev.name, isError: ev.isError, decision: ev.decision }),
    }
    if (c.auth) {
      cfg.authenticate = (req) => {
        const id = fx.auth.tokens[req.headers.authorization ?? ""]
        if (id === undefined) throw new Error("unauthorized")
        return id
      }
      cfg.wwwAuthenticate = () => fx.auth.wwwAuthenticate
    }
    for (const k of ["name", "title", "version", "instructions", "stateless", "statelessSessionId", "protocolVersions"]) {
      if (k in c) (cfg as any)[k] = c[k]
    }
    if (c.skills) cfg.listSkills = () => loadMcpSkills(skillsDir)
    let meta: http.RequestListener = (_req, res) => void res.writeHead(404).end()
    if (c.serverAuth) {
      const fa = fx.serverAuth
      const auth = new McpServerAuth({
        issuer: fa.issuer,
        audience: fa.audience,
        scopes: fa.scopes,
        verify: (_req, token) => {
          const p = fa.principals[token]
          if (!p) throw new Error("bad token")
          return { subject: p.subject, scopes: p.scopes }
        },
      })
      cfg.authenticate = auth.authenticate
      meta = auth.metadata()
    }
    const tk = await createToolkit({ builtins: false })
    const srv = http.createServer(tk.mcpHandler(cfg))
    await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
    const url = `http://127.0.0.1:${(srv.address() as AddressInfo).port}/`
    const metaSrv = http.createServer(meta)
    await new Promise<void>((r) => metaSrv.listen(0, "127.0.0.1", r))
    const metaUrl = `http://127.0.0.1:${(metaSrv.address() as AddressInfo).port}`
    let sid = ""
    try {
      for (const [i, step] of sc.steps.entries()) {
        const rq = step.request
        const headers: Record<string, string> = { Accept: rq.accept ?? "application/json, text/event-stream" }
        if (rq.method === "POST") headers["Content-Type"] = rq.contentType ?? "application/json"
        const tok = rq.token ?? sc.token
        if (tok) headers.Authorization = tok
        if (rq.sessionId !== undefined) headers["Mcp-Session-Id"] = rq.sessionId
        else if (rq.session) headers["Mcp-Session-Id"] = sid
        if (rq.version) headers["MCP-Protocol-Version"] = rq.version
        let bodyObj = rq.body
        if (rq.mcp2026 && bodyObj !== undefined) {
          bodyObj = structuredClone(bodyObj)
          bodyObj.params ??= {}
          bodyObj.params._meta ??= {}
          if (!(META_VERSION in bodyObj.params._meta)) bodyObj.params._meta[META_VERSION] = "2026-07-28"
          headers["MCP-Protocol-Version"] = "2026-07-28"
          headers["Mcp-Method"] = bodyObj.method
          if (bodyObj.method === "tools/call" || bodyObj.method === "prompts/get") headers["Mcp-Name"] = bodyObj.params.name ?? ""
          if (bodyObj.method === "resources/read") headers["Mcp-Name"] = bodyObj.params.uri ?? ""
        }
        for (const [k, v] of Object.entries((rq.headers ?? {}) as Record<string, string>)) {
          for (const hk of Object.keys(headers)) if (hk.toLowerCase() === k.toLowerCase()) delete headers[hk]
          if (v !== "") headers[k] = v
        }
        const body = rq.rawBody ?? (bodyObj !== undefined ? JSON.stringify(bodyObj) : undefined)
        const target = rq.target ? metaUrl + rq.target : url
        const resp = await fetch(target, { method: rq.method, headers, body })
        const text = await resp.text()
        const where = `${sc.name} step ${i}`
        if (step.capture === "sid") sid = resp.headers.get("mcp-session-id") ?? ""
        const ex = step.expect
        if (ex.status !== undefined) assert.equal(resp.status, ex.status, `${where} status; body=${text}`)
        for (const [k, v] of Object.entries(ex.headers ?? {})) assert.equal(resp.headers.get(k), v, `${where} header ${k}`)
        for (const k of ex.headersPresent ?? []) assert.ok(resp.headers.get(k), `${where} header ${k} present`)
        for (const k of ex.headersAbsent ?? []) assert.ok(!resp.headers.get(k), `${where} header ${k} absent`)
        if (ex.json !== undefined || ex.jsonPath !== undefined) {
          const got = JSON.parse(text)
          if (ex.json !== undefined) assert.deepEqual(got, ex.json, where)
          for (const [p, v] of Object.entries(ex.jsonPath ?? {})) assert.deepEqual(getPath(got, p), v, `${where} ${p}`)
        }
      }
      if (sc.expectExecuted) assert.deepEqual(executed, sc.expectExecuted, `${sc.name} executed`)
      if (sc.expectOnCall) assert.deepEqual(onCalls, sc.expectOnCall, `${sc.name} onCall`)
    } finally {
      metaSrv.closeAllConnections()
      await new Promise<void>((r) => metaSrv.close(() => r()))
      srv.closeAllConnections()
      await new Promise<void>((r) => srv.close(() => r()))
      await tk.close()
    }
  })
}
