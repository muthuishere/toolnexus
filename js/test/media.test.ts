/**
 * Media generation (SPEC §7G) + RunResult.files (§8).
 * Hermetic: every conformance file under ../conformance/media is replayed — API cases against an
 * in-process node:http server, CLI cases against fake-cli/fake-cli.sh run with /bin/sh. The
 * fixtures are read in place (never copied).
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import http from "node:http"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  createMedia, createToolkit, createClient, loadMediaStyle, styleMediaClient, modelParts, toOpenAIWire, toAnthropicWire,
  MEDIA_VERBS, A2E_STYLE, runFiles, MediaError, MediaUnavailableError,
  A2EMediaClient, ElevenLabsMediaClient, OpenAIMediaClient, OpenRouterMediaClient, FalMediaClient, GrokMediaClient, CodexMediaClient,
  type MediaClient, type Tool, type ToolResult,
} from "../dist/index.js"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CONF = path.resolve(HERE, "../../conformance/media")
const read = (f: string) => JSON.parse(fs.readFileSync(path.join(CONF, f), "utf8"))
const CASES = read("cases.json").cases as any[]
const CLIENTS = read("clients.json").cases as any[]
const CLI = read("cli.json").cases as any[]
const ROUTING = read("routing.json")
const SCHEMAS = read("schemas.json")
const VERBS = read("verbs.json")
const FAKE_CLI = path.join(CONF, "fake-cli", "fake-cli.sh")

test("media conformance: exact case counts", () => {
  assert.equal(CASES.length, 49)
  assert.equal(CLIENTS.length, 76)
  assert.equal(CLI.length, 24)
  assert.equal(ROUTING.cases.length, 19)
  assert.equal(ROUTING.schemaCases.length, 1)
  assert.equal(VERBS.verbs.length, 7)
})

const TMP = fs.realpathSync(os.tmpdir())
const tmpDir = (p: string) => fs.mkdtempSync(path.join(TMP, p))

/** Replace every placeholder in every string of a JSON value. */
function sub(v: any, map: Record<string, string>): any {
  if (typeof v === "string") {
    let s = v
    for (const [k, r] of Object.entries(map)) s = s.split(k).join(r)
    return s
  }
  if (Array.isArray(v)) return v.map((x) => sub(x, map))
  if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x, map)]))
  return v
}

const reEscape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")

// ---- fake HTTP provider ----------------------------------------------------------------------

type Recorded = { method: string; path: string; headers: http.IncomingHttpHeaders; body: Buffer }

/**
 * Fake provider: serves script[] in order, last entry repeats; records every request.
 * `reset()` starts a new generation: every response closes its connection (no pooling), so any request
 * still arriving on a connection opened before it (e.g. a poll the client aborted at its deadline) is dropped, so it can
 * never be recorded into — and shift the script position of — the next face's requests.
 */
async function fakeServer(script: any[]) {
  const requests: Recorded[] = []
  let url = ""
  let gen = 0
  const server = http.createServer((req, res) => {
    const reqGen = (req.socket as any).__gen
    const chunks: Buffer[] = []
    req.on("data", (c) => chunks.push(c))
    req.on("end", () => {
      if (reqGen !== gen) { req.socket.destroy(); return }
      requests.push({ method: req.method!, path: req.url!, headers: req.headers, body: Buffer.concat(chunks) })
      const entry = sub(script[Math.min(requests.length - 1, script.length - 1)], { "{server}": url })
      const headers: Record<string, string> = { ...(entry.body !== undefined ? { "content-type": "application/json" } : {}), connection: "close" }
      for (const [k, v] of Object.entries(entry.headers ?? {})) headers[k.toLowerCase()] = String(v)
      res.writeHead(entry.status, headers)
      if (entry.bodyBase64 !== undefined) res.end(Buffer.from(entry.bodyBase64, "base64"))
      else if (entry.rawBody !== undefined) res.end(entry.rawBody)
      else res.end(JSON.stringify(entry.body))
    })
  })
  server.on("connection", (s) => {
    ;(s as any).__gen = gen
  })
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
  url = `http://127.0.0.1:${(server.address() as any).port}`
  return {
    url,
    requests,
    reset: () => { gen++; requests.length = 0 },
    close: () => { server.closeAllConnections?.(); return new Promise((r) => server.close(r)) },
  }
}

function parseMultipart(r: Recorded): any[] {
  const m = /boundary=(.+)$/.exec(String(r.headers["content-type"] ?? ""))
  assert.ok(m, "multipart content-type")
  const parts = r.body.toString("latin1").split(`--${m![1]}`).slice(1, -1)
  return parts.map((p) => {
    const sep = p.indexOf("\r\n\r\n")
    const head = p.slice(2, sep)
    const body = p.slice(sep + 4, p.length - 2)
    const name = /name="([^"]*)"/.exec(head)![1]
    const filename = /filename="([^"]*)"/.exec(head)?.[1]
    if (filename === undefined) return { name, value: Buffer.from(body, "latin1").toString("utf8") }
    const contentType = /Content-Type: (.*)/i.exec(head)![1].trim()
    return { name, filename, contentType, bytesBase64: Buffer.from(body, "latin1").toString("base64") }
  })
}

function assertRequests(c: any, got: Recorded[], strictBody: boolean) {
  if (c.expectRequests) {
    assert.equal(got.length, c.expectRequests.length, `${c.name}: request count`)
    c.expectRequests.forEach((want: any, i: number) => {
      const r = got[i]
      assert.equal(r.method, want.method, `${c.name}: method #${i}`)
      assert.equal(r.path, want.path, `${c.name}: path #${i}`)
      if (want.body !== undefined) {
        assert.deepEqual(JSON.parse(r.body.toString("utf8")), want.body, `${c.name}: body #${i}`)
        assert.equal(r.headers["content-type"], "application/json", `${c.name}: content-type #${i}`)
      } else if (want.multipart !== undefined) {
        assert.deepEqual(parseMultipart(r), want.multipart, `${c.name}: multipart #${i}`)
      } else if (strictBody) {
        assert.equal(r.body.length, 0, `${c.name}: no body #${i}`)
      }
      for (const [k, v] of Object.entries(want.headers ?? {})) assert.equal(r.headers[k.toLowerCase()], v, `${c.name}: header ${k} #${i}`)
      for (const k of want.absentHeaders ?? []) assert.equal(r.headers[k.toLowerCase()], undefined, `${c.name}: absent ${k} #${i}`)
    })
  }
  for (const [name, value] of Object.entries(c.expectHeaders ?? {})) {
    for (const r of got) assert.equal(r.headers[name.toLowerCase()], value, `${c.name}: header ${name}`)
  }
  // D23: a header pattern holds on EVERY request (start, poll, download).
  for (const [name, re] of Object.entries<string>(c.expectHeaderPatterns ?? {})) {
    assert.ok(got.length > 0, `${c.name}: requests for ${name}`)
    for (const r of got) assert.match(String(r.headers[name.toLowerCase()] ?? ""), new RegExp(re), `${c.name}: header pattern ${name}`)
  }
}

/** D20 via the port's run-files function; D21 noUsage on the tool face. */
function assertRunFiles(c: any, tr: ToolResult, toolName: string) {
  for (const p of tr.parts ?? []) assert.equal("path" in p, false, `${c.name}: part holds no path`)
  if (c.noUsage) assert.equal(tr.metadata && "usage" in tr.metadata, false, `${c.name}: no usage in metadata`)
  if (c.runFilePath === undefined) return
  const files = runFiles([{ name: toolName, args: {}, output: tr.output, isError: tr.isError, metadata: tr.metadata, ...(tr.parts ? { parts: tr.parts } : {}) }])
  assert.ok(files.length > 0, `${c.name}: run files`)
  const lines = tr.output.replace(/^[a-z]+ ready: /, "").split("\n")
  files.forEach((f: any, i: number) => {
    if (c.runFilePath) {
      assert.ok(typeof f.path === "string" && path.isAbsolute(f.path), `${c.name}: run file path #${i}`)
      assert.equal(f.path, lines[i], `${c.name}: run file path = MediaFile.path #${i}`)
    } else assert.equal("path" in f, false, `${c.name}: run file has no path #${i}`)
  })
}

/** Compare a MediaResult, checking `saved` files on disk (§7G.11). */
function assertResult(name: string, got: any, want: any, client: string, verb: string, dir?: string) {
  assert.ok(got, `${name}: no result`)
  const { files: gotFiles, ...gotRest } = got
  const { files: wantFiles, ...wantRest } = want
  assert.deepEqual(gotRest, wantRest, `${name}: result fields`)
  assert.equal(gotFiles.length, wantFiles.length, `${name}: file count`)
  wantFiles.forEach((wf: any, i: number) => {
    const gf = gotFiles[i]
    if (!wf.saved) return assert.deepEqual(gf, wf, `${name}: file #${i}`)
    assert.deepEqual(Object.keys(gf).sort(), ["mimeType", "path", "type", "url"], `${name}: saved file keys #${i}`)
    assert.equal(gf.type, wf.type)
    assert.equal(gf.mimeType, wf.mimeType)
    assert.ok(path.isAbsolute(gf.path), `${name}: absolute path`)
    if (dir) assert.equal(path.dirname(gf.path), dir, `${name}: saved under {dir}`)
    const re = new RegExp(`^${client}-${verb}-[0-9]+-[0-9]+${reEscape(wf.saved.ext)}$`)
    assert.match(path.basename(gf.path), re, `${name}: saved name`)
    assert.equal(gf.url, "file://" + gf.path)
    assert.equal(fs.readFileSync(gf.path).toString("base64"), wf.saved.bytesBase64, `${name}: saved bytes`)
  })
}

function assertError(c: any, err: unknown, map: Record<string, string>) {
  assert.ok(err, `${c.name}: expected an error`)
  const msg = (err as Error).message
  if (c.error !== undefined) assert.equal(msg, sub(c.error, map), `${c.name}: error`)
  if (c.errorPrefix !== undefined) assert.ok(msg.startsWith(sub(c.errorPrefix, map)), `${c.name}: errorPrefix: ${msg}`)
  if (c.status !== undefined) {
    assert.ok(err instanceof MediaError, `${c.name}: typed media error`)
    assert.equal((err as MediaError).status, c.status, `${c.name}: status`)
    assert.equal(err instanceof MediaUnavailableError, c.status === "unavailable", `${c.name}: unavailable subclass`)
  }
}

function assertTool(c: any, tr: ToolResult, map: Record<string, string>, dirForPattern?: string) {
  const want = c.tool
  assert.equal(tr.isError, want.isError, `${c.name}: tool isError (${tr.output})`)
  if (want.output !== undefined) assert.equal(tr.output, sub(want.output, map), `${c.name}: tool output`)
  if (want.outputPrefix !== undefined) assert.ok(tr.output.startsWith(sub(want.outputPrefix, map)), `${c.name}: tool outputPrefix`)
  if (want.outputPattern !== undefined) {
    const re = new RegExp(want.outputPattern.split("{dir}").join(reEscape(dirForPattern ?? "")))
    assert.match(tr.output, re, `${c.name}: tool outputPattern`)
  }
  if (want.parts !== undefined) assert.deepEqual(tr.parts, sub(want.parts, map), `${c.name}: tool parts`)
  if (want.noParts || (!want.isError && want.parts === undefined && want.metadata?.verb && ["transcribe", "cloneVoice"].includes(want.metadata.verb))) {
    assert.equal("parts" in tr, false, `${c.name}: no parts key`)
  }
  if (want.metadata !== undefined) assert.deepEqual(tr.metadata, sub(want.metadata, map), `${c.name}: tool metadata`)
  if (want.isError) assert.equal(tr.metadata, undefined, `${c.name}: error has no metadata`)
  if (want.modelParts !== undefined) {
    const mp = sub(want.modelParts, map)
    assert.deepEqual(modelParts(tr), mp, `${c.name}: modelParts`)
    // The assembly functions (§8A) emit exactly those parts on a transcript holding this result.
    const oa = toOpenAIWire([{ role: "tool", tool_call_id: "c1", name: c.tool.name, content: tr.output, parts: modelParts(tr) }])
    assert.equal(oa.length, mp.length ? 2 : 1, `${c.name}: openai relocation`)
    const an = toAnthropicWire([{ role: "user", content: [{ type: "tool_result", tool_use_id: "c1", content: tr.output, parts: modelParts(tr) }] }])
    const block = an[0].content[0]
    assert.equal(Array.isArray(block.content) ? block.content.length - 1 : 0, mp.length, `${c.name}: anthropic blocks`)
    if (mp.length === 0) {
      // D9: no image part => the plain output string in every style.
      assert.equal(oa[0].content, tr.output, `${c.name}: D9 openai content`)
      assert.equal(block.content, tr.output, `${c.name}: D9 anthropic content`)
    }
  }
}

function withEnv(env: Record<string, string> | undefined, unset: string[] | undefined): () => void {
  const saved: Record<string, string | undefined> = {}
  for (const k of [...Object.keys(env ?? {}), ...(unset ?? [])]) saved[k] = process.env[k]
  for (const k of unset ?? []) delete process.env[k]
  for (const [k, v] of Object.entries(env ?? {})) process.env[k] = v
  return () => { for (const [k, v] of Object.entries(saved)) { if (v === undefined) delete process.env[k]; else process.env[k] = v } }
}

// ---- cases.json: the style engine ------------------------------------------------------------

for (const c of CASES) {
  test(`media cases: ${c.name}`, async () => {
    const srv = await fakeServer(c.script)
    const restore = withEnv(c.env, c.unsetEnv)
    try {
      const style = sub(c.style, { "{server}": srv.url })
      if (c.styleError) {
        assert.throws(() => styleMediaClient(style), { message: c.error }, `${c.name}: style error`)
        assert.equal(srv.requests.length, 0)
        return
      }
      const config = sub(c.config ?? {}, { "{server}": srv.url })
      const media = createMedia(styleMediaClient(style, config))
      const progress: string[] = []
      let result: any, err: unknown
      try { result = await media.generate(c.verb, c.args, { progress: (p) => progress.push(p.status) }) } catch (e) { err = e }
      if (c.error !== undefined) assertError(c, err, {})
      else {
        assert.equal(err, undefined, `${c.name}: unexpected error ${(err as Error)?.message}`)
        assert.deepEqual(result, c.result, `${c.name}: result`)
      }
      assertRequests(c, srv.requests, true)
      if (c.expectProgress) assert.deepEqual(progress, c.expectProgress, `${c.name}: progress`)

      await srv.close()

      if (c.tool) {
        // Fresh server: a request the timed-out function face left in flight cannot shift the script.
        const srv2 = await fakeServer(c.script)
        try {
          const media2 = createMedia(styleMediaClient(sub(c.style, { "{server}": srv2.url }), sub(c.config ?? {}, { "{server}": srv2.url })))
          const tool = media2.tools().find((t: Tool) => t.name === c.tool.name)
          assert.ok(tool, `${c.name}: tool ${c.tool.name}`)
          const tr = await tool!.execute(c.args)
          const { name: _n, modelParts: _m, ...want } = c.tool
          assert.deepEqual(tr, want, `${c.name}: tool result`)
          assertTool(c, tr, {})
          assertRequests(c, srv2.requests, true)
        } finally {
          await srv2.close()
        }
      }
    } finally {
      restore()
      await srv.close()
    }
  })
}

// ---- clients.json: built-in API clients ------------------------------------------------------

const BUILTIN: Record<string, (cfg: any) => MediaClient> = {
  a2e: (cfg) => new A2EMediaClient(cfg),
  elevenlabs: (cfg) => new ElevenLabsMediaClient(cfg),
  openai: (cfg) => new OpenAIMediaClient(cfg),
  openrouter: (cfg) => new OpenRouterMediaClient(cfg),
  fal: (cfg) => new FalMediaClient(cfg),
}

for (const c of CLIENTS) {
  test(`media clients: ${c.name}`, async () => {
    const restore = withEnv(c.env, c.unsetEnv)
    try {
      for (const face of ["function", "tool"] as const) {
        if (face === "tool" && !c.tool && c.runFilePath === undefined) continue
        const srv = await fakeServer(c.script)
        const dir = tmpDir("tn-media-dir-")
        const inputs = tmpDir("tn-media-in-")
        for (const [n, b] of Object.entries(c.inputFiles ?? {})) fs.writeFileSync(path.join(inputs, n), Buffer.from(b as string, "base64"))
        const map = { "{server}": srv.url, "{dir}": dir, "{inputs}": inputs }
        try {
          const media = createMedia(BUILTIN[c.client](sub(c.config, map)))
          const args = sub(c.args, map)
          if (face === "function") {
            const progress: string[] = []
            let result: any, err: unknown
            const t0 = Date.now()
            const deadline = c.ctxTimeoutMs ? Date.now() + c.ctxTimeoutMs : undefined
            try { result = await media.generate(c.verb, args, { progress: (p) => progress.push(p.status), deadline }) } catch (e) { err = e }
            if (c.maxElapsedMs) assert.ok(Date.now() - t0 < c.maxElapsedMs, `${c.name}: elapsed ${Date.now() - t0}ms`)
            if (c.error !== undefined || c.errorPrefix !== undefined) assertError(c, err, map)
            else {
              assert.equal(err, undefined, `${c.name}: unexpected error ${(err as Error)?.message}`)
              assertResult(c.name, result, sub(c.result, map), c.client, c.verb, dir)
              if (c.noUsage) assert.equal("usage" in result, false, `${c.name}: no usage`)
            }
            if (c.expectProgress) assert.deepEqual(progress, c.expectProgress, `${c.name}: progress`)
          } else {
            const tool = media.tools().find((t: Tool) => t.name === (c.tool?.name ?? MEDIA_VERBS.find((v) => v.verb === c.verb)!.tool))
            assert.ok(tool, `${c.name}: tool`)
            const tr = await tool!.execute(args)
            if (c.tool) assertTool(c, tr, map, dir)
            assertRunFiles(c, tr, tool!.name)
          }
          assertRequests(sub(c, map), srv.requests, false)
        } finally {
          await srv.close()
        }
      }
    } finally {
      restore()
    }
  })
}

// ---- cli.json: CLI clients over the fake CLI -------------------------------------------------

const CLI_CLIENT: Record<string, (cfg: any) => MediaClient> = {
  grok: (cfg) => new GrokMediaClient(cfg),
  codex: (cfg) => new CodexMediaClient(cfg),
}

for (const c of CLI) {
  test(`media cli: ${c.name}`, async () => {
    for (const face of ["function", "tool"] as const) {
      if (face === "tool" && !c.tool && c.runFilePath === undefined) continue
      const out = tmpDir("tn-cli-out-")
      const dir = tmpDir("tn-cli-dir-")
      const fixture = path.join(tmpDir("tn-cli-fx-"), "fixture.jsonl")
      fs.writeFileSync(fixture, c.fixture.map((l: string) => l + "\n").join(""))
      const map = { "{out}": out, "{dir}": dir }
      const restore = withEnv(sub(c.env, map), undefined)
      try {
        const config = { ...sub(c.config, map), command: c.command ?? ["/bin/sh", FAKE_CLI, fixture, out] }
        const media = createMedia(CLI_CLIENT[c.client](config))
        const t0 = Date.now()
        if (face === "function") {
          const progress: string[] = []
          let result: any, err: unknown
          const deadline = c.ctxTimeoutMs ? Date.now() + c.ctxTimeoutMs : undefined
          try { result = await media.generate(c.verb, c.args, { progress: (p) => progress.push(p.status), deadline }) } catch (e) { err = e }
          if (c.error !== undefined || c.errorPrefix !== undefined) assertError(c, err, map)
          else {
            assert.equal(err, undefined, `${c.name}: unexpected error ${(err as Error)?.message}`)
            assertResult(c.name, result, sub(c.result, map), c.client, c.verb, dir)
            if (c.noUsage) assert.equal("usage" in result, false, `${c.name}: no usage`)
          }
          if (c.expectProgress) assert.deepEqual(progress, c.expectProgress, `${c.name}: progress`)
          for (const p of sub(c.expectExists ?? [], map)) assert.ok(fs.existsSync(p), `${c.name}: exists ${p}`)
          for (const p of sub(c.expectMissing ?? [], map)) assert.ok(!fs.existsSync(p), `${c.name}: missing ${p}`)
        } else {
          const tool = media.tools().find((t: Tool) => t.name === (c.tool?.name ?? MEDIA_VERBS.find((v) => v.verb === c.verb)!.tool))
          assert.ok(tool, `${c.name}: tool`)
          const tr = await tool!.execute(c.args)
          if (c.tool) assertTool(c, tr, map, dir)
          assertRunFiles(c, tr, tool!.name)
        }
        if (c.maxElapsedMs) assert.ok(Date.now() - t0 < c.maxElapsedMs, `${c.name}: elapsed ${Date.now() - t0}ms`)
        const argvFile = path.join(out, "argv.txt")
        if (c.expectNoSpawn) assert.equal(fs.existsSync(argvFile), false, `${c.name}: no spawn`)
        if (c.expectArgv) {
          const argv = fs.readFileSync(argvFile, "utf8").split("\n").slice(0, -1)
          const cwd = fs.readFileSync(path.join(out, "cwd.txt"), "utf8").replace(/\n$/, "")
          assert.equal(argv.length, c.expectArgv.length, `${c.name}: argv length`)
          c.expectArgv.forEach((w: string, i: number) => {
            if (w === "{cwd}") assert.equal(argv[i], cwd, `${c.name}: --cwd (D17: byte for byte)`)
            else assert.equal(argv[i], sub(w, map), `${c.name}: argv #${i}`)
          })
        }
        if (c.expectEnv?.GROK_CONFIG !== undefined) {
          assert.equal(fs.readFileSync(path.join(out, "env-GROK_CONFIG.txt"), "utf8"), c.expectEnv.GROK_CONFIG)
        }
      } finally {
        restore()
      }
    }
  })
}

// ---- routing.json ----------------------------------------------------------------------------

const CANON_ARGS = Object.fromEntries(MEDIA_VERBS.map((v) => [v.verb, v.args.map((a) => ({ name: a.name, required: a.required === true }))]))

function routingClients(defs: any[], calls: any[]): MediaClient[] {
  return defs.map((d) => {
    const client: any = { name: d.name, supports: (v: string) => v in d.verbs }
    if (Object.values(d.verbs).some((v: any) => v.args)) client.args = (v: string) => d.verbs[v]?.args ?? CANON_ARGS[v] ?? []
    for (const [verb, spec] of Object.entries<any>(d.verbs)) {
      client[verb] = async (args: Record<string, unknown>) => {
        calls.push({ client: d.name, verb, args })
        const o = spec.outcome
        if (o?.error !== undefined) throw o.status === "unavailable" ? new MediaUnavailableError(o.error) : new MediaError(o.error, o.status)
        return structuredClone(o.result)
      }
    }
    return client as MediaClient
  })
}

for (const c of ROUTING.cases) {
  test(`media routing: ${c.name}`, async () => {
    const calls: any[] = []
    const media = createMedia(routingClients(c.clients, calls))
    let result: any, err: unknown
    try { result = await media.generate(c.verb, c.args) } catch (e) { err = e }
    if (c.expectCalls) assert.deepEqual(calls, c.expectCalls, `${c.name}: calls`)
    if (c.error !== undefined) assertError(c, err, {})
    else assert.deepEqual(result, c.result, `${c.name}: result`)
    if (c.tool) {
      const fresh = createMedia(routingClients(c.clients, []))
      const tool = fresh.tools().find((t: Tool) => t.name === (c.tool.name ?? MEDIA_VERBS.find((v) => v.verb === c.verb)!.tool))
      assert.ok(tool, `${c.name}: tool`)
      assertTool(c, await tool!.execute(c.args), {})
    }
  })
}

for (const c of ROUTING.schemaCases) {
  test(`media routing schema: ${c.name}`, () => {
    const tools = createMedia(routingClients(c.clients, [])).tools()
    assert.deepEqual(tools.map((t: Tool) => ({ name: t.name, description: t.description, source: t.source, inputSchema: t.inputSchema })), c.tools)
    for (const t of tools) assert.deepEqual(Object.keys(t.inputSchema.properties as object), Object.keys(c.tools.find((w: any) => w.name === t.name).inputSchema.properties))
  })
}

test("media routing: createMedia([]) fails", () => {
  assert.throws(() => createMedia([]), { message: ROUTING.emptyClients.error })
})

// ---- embedded copies, schemas, toolkit -------------------------------------------------------

test("media: embedded verb table is verbs.json verbatim", () => {
  assert.deepEqual(MEDIA_VERBS, VERBS.verbs)
  assert.equal(JSON.stringify(MEDIA_VERBS), JSON.stringify(VERBS.verbs))
})

test("media: embedded a2e preset is examples/media-styles/a2e.json verbatim", () => {
  const file = JSON.parse(fs.readFileSync(path.resolve(HERE, "../../examples/media-styles/a2e.json"), "utf8"))
  assert.equal(JSON.stringify(A2E_STYLE), JSON.stringify(file))
})

test("media: tool schemas match conformance/media/schemas.json (order + bytes)", () => {
  const tools = createMedia(styleMediaClient(SCHEMAS.style)).tools()
  assert.deepEqual(
    tools.map((t: Tool) => ({ name: t.name, description: t.description, source: t.source, inputSchema: t.inputSchema })),
    SCHEMAS.tools,
  )
})

test("media: createToolkit({ media }) registers the tools; loadMediaStyle reads a file", async () => {
  const tmp = tmpDir("tn-media-")
  const f = path.join(tmp, "style.json")
  fs.writeFileSync(f, JSON.stringify(SCHEMAS.style))
  const media = createMedia(styleMediaClient(loadMediaStyle(f)))
  const tk = await createToolkit({ builtins: false, media })
  assert.deepEqual(tk.tools().filter((t: Tool) => t.source === "media").map((t: Tool) => t.name), SCHEMAS.tools.map((t: any) => t.name))
  fs.writeFileSync(f, JSON.stringify({ baseUrl: "http://x", verbs: { talking: { mode: "sync", kind: "video", start: { path: "/t" }, args: {}, resultPath: "u" } } }))
  assert.throws(() => loadMediaStyle(f), { message: 'style: unknown verb "talking"' })
})

test("media: built-in clients declare their verbs and names", () => {
  const want: Record<string, [MediaClient, string[]]> = {
    a2e: [new A2EMediaClient(), ["image", "video", "speech", "talk"]],
    elevenlabs: [new ElevenLabsMediaClient(), ["speech", "music", "transcribe", "cloneVoice"]],
    openai: [new OpenAIMediaClient(), ["image", "video", "speech", "transcribe"]],
    openrouter: [new OpenRouterMediaClient(), ["image"]],
    fal: [new FalMediaClient(), ["image", "video"]],
    grok: [new GrokMediaClient(), ["image", "video"]],
    codex: [new CodexMediaClient(), ["image"]],
  }
  for (const [name, [client, verbs]] of Object.entries(want)) {
    assert.equal(client.name, name)
    assert.deepEqual(MEDIA_VERBS.map((v) => v.verb).filter((v) => client.supports(v)), verbs, name)
  }
})

// ---- function face extras --------------------------------------------------------------------

test("media: typed verbs pass providerOptions last; first client supporting a verb wins", async () => {
  const srv = await fakeServer([{ status: 200, body: { u: "https://x/a.png" } }])
  try {
    const a = { baseUrl: srv.url, verbs: { image: { mode: "sync", kind: "image", start: { path: "/a" }, args: { prompt: "p" }, defaults: { q: 1 }, resultPath: "u" } } }
    const b = { baseUrl: srv.url, verbs: { image: { mode: "sync", kind: "image", start: { path: "/b" }, args: { prompt: "p" }, resultPath: "u" } } }
    const media = createMedia([styleMediaClient(a as any), styleMediaClient(b as any)])
    const r = await media.image({ prompt: "cat", providerOptions: { q: 2, p: "over" } })
    assert.deepEqual(r, { files: [{ type: "image", mimeType: "image/png", url: "https://x/a.png" }], status: "completed" })
    assert.equal(srv.requests[0].path, "/a")
    assert.deepEqual(JSON.parse(srv.requests[0].body.toString()), { q: 2, p: "over" })
  } finally { await srv.close() }
})

test("media: AbortSignal stops the poll loop", async () => {
  const srv = await fakeServer([{ status: 200, body: { id: "j" } }, { status: 200, body: { st: "processing" } }])
  try {
    const media = createMedia(styleMediaClient({ baseUrl: srv.url, verbs: { video: { mode: "async", kind: "video", start: { path: "/v" }, args: { prompt: "p" }, idPath: "id",
      poll: { path: "/v/{id}", statusPath: "st", done: ["ok"], failed: ["x"], intervalMs: 5, timeoutMs: 60000 }, resultPath: "u" } } } as any))
    const ac = new AbortController()
    setTimeout(() => ac.abort(), 40)
    const t0 = Date.now()
    await assert.rejects(media.video({ prompt: "x", signal: ac.signal }), (e: any) => e?.name === "AbortError")
    assert.ok(Date.now() - t0 < 5000)
    const ac2 = new AbortController()
    ac2.abort()
    const tr = await media.tools()[0].execute({ prompt: "x" }, { signal: ac2.signal })
    assert.equal(tr.isError, true)
  } finally { await srv.close() }
})

test("media: AbortSignal kills a CLI run", async () => {
  const fixture = path.join(tmpDir("tn-cli-fx-"), "f.jsonl")
  fs.writeFileSync(fixture, "#hang\n")
  const media = createMedia(new GrokMediaClient({ command: ["/bin/sh", FAKE_CLI, fixture, tmpDir("tn-cli-out-")] }))
  const ac = new AbortController()
  setTimeout(() => ac.abort(), 100)
  const t0 = Date.now()
  await assert.rejects(media.image({ prompt: "x", signal: ac.signal }), (e: any) => e?.name === "AbortError")
  assert.ok(Date.now() - t0 < 5000)
})

test("media: loop.onProgress is used when ctx.progress is absent (never both)", async () => {
  const srv = await fakeServer([{ status: 200, body: { id: "j", st: "ok", u: "https://x/a.png" } }])
  try {
    const seen: any[] = []
    const style = { baseUrl: srv.url, verbs: { image: { mode: "async", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, idPath: "id",
      poll: { path: "/p/{id}", statusPath: "st", done: ["ok"] }, resultPath: "u" } } }
    const media = createMedia(styleMediaClient(style as any, { loop: { intervalMs: 1, onProgress: (p) => seen.push(p) } }))
    await media.image({ prompt: "x" })
    assert.equal(seen.length, 1)
    assert.deepEqual({ ...seen[0], elapsedMs: 0 }, { verb: "image", client: "style", jobId: "j", status: "ok", elapsedMs: 0 })
    const other: string[] = []
    await media.image({ prompt: "x", progress: (p) => other.push(p.status) })
    assert.equal(seen.length, 1)
    assert.deepEqual(other, ["ok"])
  } finally { await srv.close() }
})

test("media: a string error-envelope code renders as-is", async () => {
  const srv = await fakeServer([{ status: 200, body: { code: "E_QUOTA", message: "out of credits" } }])
  try {
    const media = createMedia(styleMediaClient({ baseUrl: srv.url, errorEnvelope: { codePath: "code", okValues: ["OK"], messagePath: "message" },
      verbs: { image: { mode: "sync", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, resultPath: "u" } } } as any))
    await assert.rejects(media.image({ prompt: "x" }), { message: "API error E_QUOTA: out of credits" })
  } finally { await srv.close() }
})

test("media: several unsupported args ⇒ error names the first in sorted order; null args are absent", async () => {
  const media = createMedia(styleMediaClient({ baseUrl: "http://unused", verbs: { image: { mode: "sync", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, resultPath: "u" } } } as any))
  await assert.rejects(media.generate("image", { seed: 1, prompt: "x", aspectRatio: "1:1", Width: 2 }), { message: 'image: argument "Width" not supported' })
  await assert.rejects(media.generate("image", { zeta: 1, alpha: 2 }), { message: 'image: argument "alpha" not supported' })
  await assert.rejects(media.generate("image", { prompt: "x", seed: null, zeta: 1 }), { message: 'image: argument "zeta" not supported' })
})

test("media: all seven media tools are concurrency-safe", () => {
  const v = (kind: string, arg: string) => ({ mode: "sync", kind, start: { path: "/x" }, args: { [arg]: arg }, resultPath: "u" })
  const tools = createMedia(styleMediaClient({ baseUrl: "http://unused", verbs: {
    image: v("image", "prompt"), video: v("video", "prompt"), speech: v("audio", "text"), music: v("audio", "prompt"),
    transcribe: v("text", "audio"), cloneVoice: v("text", "name"), talk: v("video", "image"),
  } } as any)).tools()
  assert.equal(tools.length, 7)
  for (const t of tools) assert.equal(t.concurrencySafe?.({}), true, t.name)
})

test("media: a cross-origin redirect never carries the style headers (§7G Redirects)", async () => {
  const seen: http.IncomingHttpHeaders[] = []
  const b = http.createServer((req, res) => { seen.push(req.headers); res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify({ u: "https://x/r.png" })) })
  await new Promise<void>((r) => b.listen(0, "127.0.0.1", r))
  const bUrl = `http://127.0.0.1:${(b.address() as any).port}`
  const a = http.createServer((req, res) => { req.resume(); res.writeHead(307, { location: bUrl + "/landed" }); res.end() })
  await new Promise<void>((r) => a.listen(0, "127.0.0.1", r))
  const aUrl = `http://127.0.0.1:${(a.address() as any).port}`
  try {
    const media = createMedia(styleMediaClient({ baseUrl: aUrl, headers: { Authorization: "Bearer FAKE", "X-Style": "s" },
      verbs: { image: { mode: "sync", kind: "image", start: { path: "/i" }, args: { prompt: "p" }, resultPath: "u" } } } as any))
    const r = await media.image({ prompt: "x" })
    assert.equal(r.files[0].url, "https://x/r.png")
    assert.equal(seen.length, 1)
    assert.equal(seen[0].authorization, undefined)
    assert.equal(seen[0]["x-style"], undefined)
  } finally {
    await new Promise((r) => a.close(r)); await new Promise((r) => b.close(r))
  }
})

test("media: a hung server is bounded by the job deadline (§7G Per-request timeout)", async () => {
  const pending: http.ServerResponse[] = []
  const srv = http.createServer((req, res) => {
    req.resume()
    if (req.method === "POST" && req.url === "/a") { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify({ id: "h1" })); return }
    pending.push(res)
  })
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  const url = `http://127.0.0.1:${(srv.address() as any).port}`
  try {
    const sync = createMedia(styleMediaClient({ baseUrl: url, verbs: { image: { mode: "sync", kind: "image", start: { path: "/s" }, args: { prompt: "p" }, poll: { timeoutMs: 200 }, resultPath: "u" } } } as any))
    const t0 = Date.now()
    await assert.rejects(sync.image({ prompt: "x" }), (e: any) => e.message === "image: timed out after 200ms" && e.status === "timeout")
    assert.ok(Date.now() - t0 < 3000)
    const asyncM = createMedia(styleMediaClient({ baseUrl: url, verbs: { image: { mode: "async", kind: "image", start: { path: "/a" }, args: { prompt: "p" }, idPath: "id",
      poll: { path: "/p/{id}", statusPath: "st", done: ["ok"], intervalMs: 1, timeoutMs: 1000 }, resultPath: "u" } } } as any))
    await assert.rejects(asyncM.image({ prompt: "x" }), (e: any) => e.message === "job h1 timed out after 1000ms" && e.jobId === "h1")
  } finally {
    for (const r of pending) r.destroy()
    srv.closeAllConnections?.()
    await new Promise((r) => srv.close(r))
  }
})

// ---- RunResult.files / toolCalls[].parts / model visibility (§8, §7G.6) ----------------------

const PARTS = [
  { type: "text", text: "ignored" },
  { type: "image", mimeType: "image/png", url: "https://x/a.png" },
  { type: "audio", mimeType: "audio/wav", url: "https://x/s.wav" },
]
const partsTool: Tool = {
  name: "make", description: "makes", source: "custom", inputSchema: { type: "object", properties: {} },
  async execute() { return { output: "made", isError: false, parts: PARTS as any } },
}
const plainTool: Tool = {
  name: "plain", description: "plain", source: "custom", inputSchema: { type: "object", properties: {} },
  async execute() { return { output: "ok", isError: false } },
}
const json = (o: unknown) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } })
const sseRes = (s: string) => new Response(s, { status: 200, headers: { "content-type": "text/event-stream" } })
const ev = (o: unknown) => `data: ${JSON.stringify(o)}\n\n`

function scripted(style: "openai" | "anthropic", names: [string, string] = ["make", "plain"], bodies: any[] = []) {
  let n = 0
  return (async (_u: any, init: any) => {
    const parsed = JSON.parse(init.body)
    bodies.push(parsed)
    const stream = parsed.stream === true
    const first = n++ === 0
    const [t1, t2] = names
    if (style === "openai") {
      if (stream) {
        return sseRes(first
          ? ev({ choices: [{ index: 0, delta: { tool_calls: [
              { index: 0, id: "c1", function: { name: t1, arguments: "{}" } },
              { index: 1, id: "c2", function: { name: t2, arguments: "{}" } }] } }] }) + "data: [DONE]\n\n"
          : ev({ choices: [{ index: 0, delta: { content: "done" }, finish_reason: "stop" }] }) + "data: [DONE]\n\n")
      }
      return json(first
        ? { choices: [{ message: { role: "assistant", content: null, tool_calls: [
            { id: "c1", type: "function", function: { name: t1, arguments: "{}" } },
            { id: "c2", type: "function", function: { name: t2, arguments: "{}" } }] } }] }
        : { choices: [{ message: { role: "assistant", content: "done" }, finish_reason: "stop" }] })
    }
    if (stream) {
      return sseRes(first
        ? [ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }),
           ev({ type: "content_block_start", index: 0, content_block: { type: "tool_use", id: "c1", name: t1 } }),
           ev({ type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: "{}" } }),
           ev({ type: "content_block_start", index: 1, content_block: { type: "tool_use", id: "c2", name: t2 } }),
           ev({ type: "content_block_delta", index: 1, delta: { type: "input_json_delta", partial_json: "{}" } }),
           ev({ type: "message_delta", delta: { stop_reason: "tool_use" }, usage: { output_tokens: 1 } })].join("")
        : [ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }),
           ev({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }),
           ev({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "done" } }),
           ev({ type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { output_tokens: 1 } })].join(""))
    }
    return json(first
      ? { content: [{ type: "tool_use", id: "c1", name: t1, input: {} }, { type: "tool_use", id: "c2", name: t2, input: {} }], stop_reason: "tool_use", usage: {} }
      : { content: [{ type: "text", text: "done" }], stop_reason: "end_turn", usage: {} })
  }) as unknown as typeof fetch
}

const WANT_FILES = [
  { type: "image", mimeType: "image/png", url: "https://x/a.png", from: "make", callId: "c1" },
  { type: "audio", mimeType: "audio/wav", url: "https://x/s.wav", from: "make", callId: "c1" },
]

for (const style of ["openai", "anthropic"] as const) {
  for (const mode of ["run", "stream"] as const) {
    test(`RunResult.files + toolCalls[].parts: ${style} ${mode}`, async () => {
      const tk = await createToolkit({ builtins: false, extraTools: [partsTool, plainTool] })
      const c = createClient({ baseUrl: "http://never.invalid", style, model: "m", apiKey: "k", fetch: scripted(style), retries: 0 })
      let r: any
      if (mode === "run") r = await c.run("go", { toolkit: tk })
      else for await (const e of c.stream("go", { toolkit: tk })) if (e.type === "done") r = e.result
      assert.equal(r.text, "done")
      assert.deepEqual(r.files, WANT_FILES)
      assert.deepEqual(r.toolCalls[0].parts, PARTS)
      assert.equal("parts" in r.toolCalls[1], false)
    })

    test(`media model visibility: only image parts reach the model (${style} ${mode})`, async () => {
      const client: MediaClient = {
        name: "m",
        supports: (v) => v === "image" || v === "video",
        args: () => [],
        image: async () => ({ files: [{ type: "image", mimeType: "image/png", url: "https://x/i.png" }], status: "completed" }),
        video: async () => ({ files: [{ type: "file", mimeType: "video/mp4", data: "TVA0" }], status: "completed" }),
      }
      const tk = await createToolkit({ builtins: false, media: createMedia(client) })
      const bodies: any[] = []
      const c = createClient({ baseUrl: "http://never.invalid", style, model: "m", apiKey: "k", fetch: scripted(style, ["generate_image", "generate_video"], bodies), retries: 0 })
      let r: any
      if (mode === "run") r = await c.run("go", { toolkit: tk })
      else for await (const e of c.stream("go", { toolkit: tk })) if (e.type === "done") r = e.result
      assert.deepEqual(r.files.map((f: any) => f.type), ["image", "file"])
      const second = JSON.stringify(bodies[1].messages)
      assert.ok(second.includes("https://x/i.png"), "image reaches the model")
      assert.ok(!second.includes("TVA0"), "video data never reaches the model")
      assert.ok(second.includes("video ready: inline video/mp4 3 bytes"), "the model sees the output line")
    })
  }
}

test("RunResult.files is [] when no tool produced parts", async () => {
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", retries: 0,
    fetch: (async () => json({ choices: [{ message: { role: "assistant", content: "hi" }, finish_reason: "stop" }] })) as unknown as typeof fetch })
  const r = await c.run("go")
  assert.deepEqual(r.files, [])
})

// ---- D9 (SPEC §7G.12): no image part => plain output string in every message style -----------

test("media D9: a media result with modelParts [] sends the plain output in openai and anthropic", () => {
  const output = "video ready: https://x.test/v.mp4"
  const tr: ToolResult = {
    output,
    isError: false,
    parts: [{ type: "file", url: "https://x.test/v.mp4", mimeType: "video/mp4" } as any, { type: "audio", url: "https://x.test/a.mp3", mimeType: "audio/mpeg" } as any],
    metadata: { source: "media", verb: "video" },
  }
  assert.deepEqual(modelParts(tr), [])
  const oa = toOpenAIWire([{ role: "tool", tool_call_id: "c1", name: "generate_video", content: tr.output, parts: modelParts(tr) }])
  assert.equal(oa.length, 1, "no relocated user message")
  assert.equal(oa[0].content, output)
  assert.equal("parts" in oa[0], false)
  const an = toAnthropicWire([{ role: "user", content: [{ type: "tool_result", tool_use_id: "c1", content: tr.output, parts: modelParts(tr) }] }])
  const block = an[0].content[0]
  assert.equal(block.content, output, "plain string, not an array")
  assert.equal("parts" in block, false)
  // Gemini: the JS port has no Gemini client style (SPEC §8 lists openai|anthropic for js).
})
