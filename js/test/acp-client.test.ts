/**
 * SPEC §8C (ADR 0041): replays conformance/acp-client/cases.json — stream.acpDrip, passthrough,
 * agentExit, unknownRequest, asMcpServer — against test/fixtures/acp-client-fake.mjs.
 * stream.inProcess is replayed in test/in-process.test.ts. Hermetic: loopback only.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { createInProcessClient, createToolkit, defineTool, loadACP, type ACPOptions } from "../dist/index.js"

const CASES = JSON.parse(fs.readFileSync(new URL("../../conformance/acp-client/cases.json", import.meta.url), "utf8"))
const FAKE = new URL("./fixtures/acp-client-fake.mjs", import.meta.url).pathname
const SKILLS = new URL("../../examples/skills", import.meta.url).pathname

function recFile(): string {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "acp-client-")), "rec.ndjson")
}
function readRec(f: string): Array<{ method: string; params: any }> {
  return fs.existsSync(f) ? fs.readFileSync(f, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []
}
const fake = (env: Record<string, string>, extra: Partial<ACPOptions> = {}) =>
  loadACP({ command: "node", args: [FAKE], env, ...extra })

test("acp-client stream.acpDrip: incremental text + thought through the in-process seam", async () => {
  const fx = CASES.stream.acpDrip
  const acp = await fake(fx.fakeEnv)
  try {
    const client = createInProcessClient({ model: "m", generate: acp.generate, generateStream: acp.generateStream })
    const deltas: string[] = []
    const thoughts: any[] = []
    let firstAt = 0
    let doneAt = 0
    let done: any
    for await (const ev of client.stream("go")) {
      const e = ev as any
      if (e.type === "text") {
        if (!firstAt) firstAt = Date.now()
        deltas.push(e.delta)
      } else if (e.type === "thought") thoughts.push(e)
      else if (e.type === "done") {
        doneAt = Date.now()
        done = e.result
      }
    }
    assert.deepEqual(deltas, fx.chunks)
    assert.equal(thoughts.length, 1)
    assert.deepEqual(thoughts[0], fx.expectThought)
    assert.ok(doneAt - firstAt >= fx.minLeadMs, `lead ${doneAt - firstAt}ms`)
    assert.equal(done.text, fx.expectDoneText)
    assert.equal((await client.run("again")).text, fx.expectDoneText)
  } finally {
    await acp.close()
  }
})

test("acp-client stream.acpToolUpdate: rawInput -> tool_call_args, title remembered (#188)", async () => {
  const fx = CASES.stream.acpToolUpdate
  const acp = await fake(fx.fakeEnv)
  try {
    const client = createInProcessClient({ model: "m", generate: acp.generate, generateStream: acp.generateStream })
    const events: any[] = []
    let done: any
    for await (const ev of client.stream("go")) {
      const e = ev as any
      if (e.type === "done") done = e.result
      else if (e.type !== "text" && e.type !== "usage") events.push(e)
    }
    const got = events.map((e, i) => {
      const want = fx.expectEvents[i]
      if (!want) return e
      if (e.type === "tool_call") return { type: e.type, id: e.id, name: e.name, via: e.via }
      return Object.fromEntries(Object.keys(want).map((k) => [k, e[k]]))
    })
    assert.deepEqual(got, fx.expectEvents)
    assert.equal(done.text, fx.expectDoneText)
  } finally {
    await acp.close()
  }
})

test("acp-client passthrough: mcpServers verbatim, _meta keys, model via set_config_option", async () => {
  const fx = CASES.passthrough
  const rec = recFile()
  const acp = await fake({ TN_FAKE_REC: rec }, fx.options)
  await acp.close()
  const r = readRec(rec)
  const init = r.find((x) => x.method === "initialize")!
  assert.deepEqual(init.params.clientCapabilities, fx.expectInitializeClientCapabilities)
  const sn = r.find((x) => x.method === "session/new")!
  assert.deepEqual(sn.params.mcpServers, fx.options.mcpServers)
  assert.deepEqual(sn.params._meta, fx.options.sessionMeta)
  const sco = r.find((x) => x.method === "session/set_config_option")!
  assert.equal(sco.params.configId, fx.expectSetConfigOption.configId)
  assert.equal(sco.params.value, fx.expectSetConfigOption.value)
  assert.equal(sco.params.sessionId, "s1")
  assert.ok(!r.some((x) => x.method === "session/set_model"))
})

test("acp-client passthrough.fallbackSetModel: rejected set_config_option ⇒ set_model", async () => {
  const fx = CASES.passthrough
  const rec = recFile()
  const acp = await fake({ ...fx.fallbackSetModel.fakeEnv, TN_FAKE_REC: rec }, { model: fx.options.model })
  await acp.close()
  const sm = readRec(rec).find((x) => x.method === "session/set_model")!
  assert.equal(sm.params.modelId, fx.fallbackSetModel.expectSetModel.modelId)
  assert.equal(sm.params.sessionId, "s1")
})

test("acp-client passthrough.bareExpect: no options ⇒ [] and no _meta keys", async () => {
  const fx = CASES.passthrough.bareExpect
  const rec = recFile()
  const acp = await fake({ TN_FAKE_REC: rec })
  await acp.close()
  const r = readRec(rec)
  const sn = r.find((x) => x.method === "session/new")!
  assert.deepEqual(sn.params.mcpServers, fx.mcpServers)
  assert.equal("_meta" in sn.params, fx.sessionNewHasMeta)
  assert.equal("_meta" in r.find((x) => x.method === "initialize")!.params.clientCapabilities, fx.clientCapabilitiesHasMeta)
  assert.ok(!r.some((x) => x.method.includes("set_")))
})

test("acp-client agentExit: a pending prompt fails promptly", async () => {
  const fx = CASES.agentExit
  const acp = await fake(fx.fakeEnv, { permissionTimeoutMs: 30000 })
  const t0 = Date.now()
  await assert.rejects(acp.generate({ messages: [{ role: "user", content: "x" }], body: {} } as any), (e: Error) =>
    e.message.includes(fx.expectError),
  )
  assert.ok(Date.now() - t0 < 5000)
  await acp.close()
})

test("acp-client unknownRequest: answered with -32601, turn completes", async () => {
  const fx = CASES.unknownRequest
  const acp = await fake(fx.fakeEnv)
  try {
    const res = await acp.generate({ messages: [{ role: "user", content: "x" }], body: {} } as any)
    assert.equal(res.content, fx.expectText)
  } finally {
    await acp.close()
  }
})

test("acp-client asMcpServer: loopback, bearer, 401/404/405, tools via own MCP client", async () => {
  const fx = CASES.asMcpServer
  const echo = defineTool({
    name: "echo",
    description: "echo text",
    inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] },
    run: async (a: any) => String(a.text),
  })
  const tk = await createToolkit({ skillsDir: SKILLS })
  tk.register(echo)
  const srv = await tk.asMcpServer()
  const srv2 = await tk.asMcpServer()
  try {
    assert.match(srv.url, new RegExp(fx.urlPattern))
    assert.match(srv.token, new RegExp(fx.tokenPattern))
    if (fx.twoServersDistinctTokens) assert.notEqual(srv.token, srv2.token)

    const noTok = await fetch(srv.url, { method: "POST", body: "{}" })
    assert.equal(noTok.status, fx.noTokenStatus)
    for (const [k, v] of Object.entries(fx.noTokenHeader)) assert.equal(noTok.headers.get(k), v)
    const wrong = await fetch(srv.url, { method: "POST", headers: { Authorization: "Bearer " + "0".repeat(64) }, body: "{}" })
    assert.equal(wrong.status, fx.wrongTokenStatus)
    const auth = { Authorization: "Bearer " + srv.token }
    assert.equal((await fetch(srv.url, { headers: auth })).status, fx.getWithTokenStatus)
    assert.equal((await fetch(srv.url.replace(/\/mcp$/, "/other"), { headers: auth })).status, fx.otherPathStatus)

    const mcpTk = await createToolkit({
      mcpConfig: { mcpServers: { tn: { type: "remote", url: srv.url, headers: auth } } },
    })
    try {
      const names = mcpTk.tools().map((t) => t.name)
      for (const n of fx.toolsListContains) assert.ok(names.includes(`tn_${n}`), `${n} in ${names}`)
      const res = await mcpTk.execute(`tn_${fx.callEcho.name}`, fx.callEcho.arguments)
      assert.equal(res.output, fx.callEcho.expectText)
    } finally {
      await mcpTk.close()
    }
  } finally {
    await srv.close()
    await srv.close()
    await srv2.close()
  }
  if (fx.afterCloseRefused) await assert.rejects(fetch(srv.url, { method: "POST", body: "{}" }))
})
