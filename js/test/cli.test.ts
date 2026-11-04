/**
 * Hermetic tests for the CLI client (SPEC §8C), mirroring golang/cli_test.go: no network,
 * no real agent CLI. `test/fixtures/cli-fake.mjs` plays the fake ACP agent, the fake
 * one-shot CLI and the stdio MCP bridge.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import {
  CLI_PREAMBLE,
  CLI_STYLE_FILES,
  CliStyles,
  CliTransports,
  cliIsServedCall,
  cliStyleDef,
  cliWithSystemHint,
  createCliClient,
  createToolkit,
  defineTool,
  loadCliStyle,
  parseCliReply,
  renderCliPrompt,
  type CliOptions,
  type CliStyleDef,
  type Toolkit,
} from "../dist/index.js"

const ROOT = new URL("../..", import.meta.url).pathname
const FAKE = new URL("./fixtures/cli-fake.mjs", import.meta.url).pathname
const SKILLS_DIR = path.join(ROOT, "examples/skills")

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "tn-cli-test-"))

async function badgeToolkit(): Promise<Toolkit> {
  const badge = defineTool({ name: "get_badge_number", description: "Returns the badge number.", run: () => "BADGE-77Q" })
  return createToolkit({ extraTools: [badge], builtins: false, skillsDir: SKILLS_DIR })
}

function fakeCLIOpts(mode: string, ...extra: string[]): CliOptions {
  return {
    style: CliStyles.Custom,
    command: FAKE,
    args: extra,
    prompt: "stdin",
    answer: "stdout",
    env: { TN_FAKE_CLI: mode },
    transport: CliTransports.CLI,
  }
}

async function runCli(o: CliOptions, tk?: Toolkit) {
  const c = createCliClient(o)
  try {
    return await c.run("What is my badge number?", { toolkit: tk })
  } finally {
    await c.close()
  }
}

async function fakeACPOpts(env: Record<string, string>): Promise<{ o: CliOptions & { styleDef: CliStyleDef }; rec: string }> {
  const rec = path.join(tmp(), "rec")
  const styleDef: CliStyleDef = { name: "fake", args: [], prompt: "stdin", answer: "stdout", acp: { args: ["acp"] } }
  return {
    o: {
      styleDef,
      command: FAKE,
      env: { TN_FAKE_ACP: "1", TN_FAKE_REC: rec, ...env },
      toolkit: await badgeToolkit(),
      bridgeCommand: [FAKE, "mcp-bridge"],
    },
    rec,
  }
}

function assertFakeACPRun(c: ReturnType<typeof createCliClient>, text: string, entry: string) {
  assert.equal(c.transport(), "acp", c.fallbackReason())
  for (const want of ["perm ours=yes own=no fake=no again=yes unknown=-32601", "entry=" + entry, "BADGE-77Q", "Hello"]) {
    assert.ok(text.includes(want), `missing ${want} in ${text}`)
  }
  assert.ok(text.includes('"name":"load_skill"') && !text.includes('"name":"skill"'), "skill must be served as load_skill")
  assert.equal(c.acpSession()?.servedCalls.length, 2, "server must record the executed tools")
}

// --------------------------------------------------------------------------- //
// Shared data.
// --------------------------------------------------------------------------- //

test("cli: styles + preamble are byte-identical to conformance/cli-styles", () => {
  const dir = path.join(ROOT, "conformance/cli-styles")
  const files = fs.readdirSync(dir).filter((n) => n !== "README.md")
  assert.equal(files.length, 8, "7 styles + preamble")
  for (const n of files) {
    assert.equal(CLI_STYLE_FILES[n], fs.readFileSync(path.join(dir, n), "utf8"), `js/src/clistyles.ts ${n} drifted (regenerate it)`)
  }
  assert.equal(Object.keys(CLI_STYLE_FILES).length, 8)
  assert.equal(CLI_PREAMBLE, fs.readFileSync(path.join(dir, "preamble.txt"), "utf8"))
  for (const s of [CliStyles.Claude, CliStyles.Codex, CliStyles.Copilot, CliStyles.Opencode, CliStyles.Devin, CliStyles.Gemini, CliStyles.Goose]) {
    const d = cliStyleDef(s)!
    assert.equal(d.name, s)
    assert.ok(d.verified, `${s} has no verified note`)
    createCliClient({ style: s, command: "x", transport: CliTransports.CLI }) // validates
  }
  assert.equal(cliStyleDef(CliStyles.Codex)!.acp, undefined, "codex has no ACP launch")
  assert.equal(cliStyleDef(CliStyles.Devin)!.acp?.projectMcpFile, ".mcp.json")
  assert.ok(cliStyleDef(CliStyles.Goose)!.verified!.startsWith("UNVERIFIED"))
  assert.equal(cliStyleDef(CliStyles.Custom), undefined)
})

test("cli: loadCliStyle reads JSON text or a file", () => {
  assert.equal(loadCliStyle('{"name":"mine","args":[],"prompt":"stdin","answer":"stdout"}').name, "mine")
  const f = path.join(tmp(), "s.json")
  fs.writeFileSync(f, CLI_STYLE_FILES["gemini.json"])
  assert.equal(loadCliStyle(f).name, "gemini")
  assert.throws(() => loadCliStyle(path.join(tmp(), "missing.json")), /^Error: cli style: /)
})

// --------------------------------------------------------------------------- //
// Construction errors (more in the conformance replay below).
// --------------------------------------------------------------------------- //

test("cli: construction errors are pinned", () => {
  const cases: Array<[any, string]> = [
    [{ style: CliStyles.Claude }, "cli: Command is required (path or name of the CLI binary)"],
    [{ command: "x" }, "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"],
    [{ style: CliStyles.Goose, command: "goose", model: "m", transport: CliTransports.CLI }, 'cli: style "goose" takes no model'],
    [{ style: CliStyles.Codex, command: "codex", transport: CliTransports.ACP }, 'cli: style "codex" has no ACP launch; set ACPCommand or use CliTransports.CLI'],
    [{ style: CliStyles.Custom, command: "x", args: ["{{model}}"], prompt: "stdin", answer: "stdout" }, "cli: the style needs a model: {{model}} is outside an optional group"],
    [{ style: CliStyles.Custom, command: "x", args: [["a", 1]], prompt: "stdin", answer: "stdout" }, "cli: a group may only hold strings"],
    [{ style: CliStyles.Custom, command: "x", args: [{}], prompt: "stdin", answer: "stdout" }, "cli: an arg must be a string or a group of strings"],
  ]
  for (const [o, want] of cases) {
    assert.throws(() => createCliClient(o), (e: Error) => e.message === want, want)
  }
  // An unknown value is not a style.
  assert.throws(() => createCliClient({ style: "nope" as any, command: "x" }), /Style is required/)
})

// --------------------------------------------------------------------------- //
// The envelope parser.
// --------------------------------------------------------------------------- //

test("cli: tolerant reply parser + render shape", () => {
  const r = parseCliReply('{"tool_calls":[{"id":"a","function":{"name":"t","arguments":"{\\"x\\":1}"}}]}')
  assert.equal(r.toolCalls?.[0].name, "t")
  assert.equal(r.toolCalls?.[0].id, "a")
  assert.equal(parseCliReply('{"content":{"a":1}}').content, '{"a":1}')
  assert.equal(parseCliReply("```\n```").content, "```\n```")
  assert.ok(renderCliPrompt(undefined, undefined).startsWith(CLI_PREAMBLE + '\nREQUEST:\n{"messages":[],"tools":[]}'))
  assert.ok(renderCliPrompt([{ role: "user", content: "<a&b>" }], []).includes('"<a&b>"'), "no HTML escaping")
})

// --------------------------------------------------------------------------- //
// One-shot transport.
// --------------------------------------------------------------------------- //

test("cli: one-shot tool round trip through the toolnexus loop", async () => {
  const rec = path.join(tmp(), "rec")
  const o = fakeCLIOpts("tool")
  o.env!.TN_FAKE_REC = rec
  o.systemPrompt = "You are a badge clerk."
  const res = await runCli(o, await badgeToolkit())
  assert.ok(res.text.includes("BADGE-77Q"), res.text)
  assert.equal(res.toolCalls.length, 1)
  assert.equal(res.toolCalls[0].name, "get_badge_number")
  assert.equal(res.usage.totalTokens, 0)
  const prompts = fs.readFileSync(rec, "utf8").split("\n=====\n")
  assert.ok(prompts[0].startsWith(CLI_PREAMBLE + "\nREQUEST:\n"))
  const req = JSON.parse(prompts[0].split("\nREQUEST:\n")[1])
  assert.equal(req.messages[0].role, "system")
  assert.ok(String(req.messages[0].content).includes("badge clerk"))
  assert.ok(req.tools.length > 0)
})

test("cli: prompt modes (arg, file) and the out file", async () => {
  const tk = await badgeToolkit()
  for (const [args, prompt, answer] of [
    [["--p", "{{prompt}}"], "arg", "stdout"],
    [["--f", "{{file}}"], "file", "stdout"],
    [["--o", "{{out}}"], "stdin", "out"],
  ] as const) {
    const before = fs.readdirSync(os.tmpdir()).filter((n) => n.startsWith("toolnexus-cli-")).length
    const o = { ...fakeCLIOpts("tool"), args: [...args], prompt, answer }
    const res = await runCli(o, tk)
    assert.ok(res.text.includes("BADGE-77Q"), `${prompt}/${answer}: ${res.text}`)
    const after = fs.readdirSync(os.tmpdir()).filter((n) => n.startsWith("toolnexus-cli-")).length
    assert.ok(after <= before, "temp files are removed")
  }
})

test("cli: optional model group", async () => {
  const o = fakeCLIOpts("x", "--argv", "-q")
  o.args!.push(["--model", "{{model}}"])
  let res = await runCli(o)
  assert.ok(!res.text.includes("--model"), res.text)
  res = await runCli({ ...o, model: "prov/m-1" })
  assert.ok(res.text.includes("--model prov/m-1"), res.text)
})

test("cli: pinned one-shot errors", async () => {
  const cases: Array<[CliOptions, string]> = [
    [fakeCLIOpts("exit"), "cli: exit 3: boom: bad login"],
    [fakeCLIOpts("empty"), "cli: empty reply"],
    [{ ...fakeCLIOpts("nooutfile", "--x", "{{out}}"), answer: "out" }, "cli: no output file"],
    [{ ...fakeCLIOpts("sleep"), callTimeoutMs: 300 }, "cli: timed out after 300ms"],
    [{ ...fakeCLIOpts("x"), command: "definitely-not-a-cli-xyz" }, "cli: command not found: definitely-not-a-cli-xyz"],
  ]
  for (const [o, want] of cases) {
    await assert.rejects(runCli(o), (e: Error) => e.message.includes(want), want)
  }
})

async function collectStream(c: ReturnType<typeof createCliClient>, tk?: Toolkit) {
  const types: string[] = []
  let text = ""
  for await (const ev of c.stream("What is my badge number?", { toolkit: tk })) {
    const e = ev as any
    if (e.type === "error") assert.fail(`stream must not error: ${e.error?.message ?? e.error}`)
    if (e.type === "text") text += e.delta
    types.push(e.type)
  }
  return { types, text }
}

test("cli: one-shot streams non-incrementally (tool_call, tool_result, one text, done)", async () => {
  const tk = await badgeToolkit()
  const c = createCliClient(fakeCLIOpts("tool"))
  try {
    const { types, text } = await collectStream(c, tk)
    const core = types.filter((t) => t === "tool_call" || t === "tool_result" || t === "text" || t === "done")
    assert.deepEqual(core, ["tool_call", "tool_result", "text", "done"], types.join(","))
    assert.equal(types[types.length - 1], "done")
    assert.ok(text.includes("BADGE-77Q"), text)
  } finally {
    await c.close()
  }
})

// --------------------------------------------------------------------------- //
// ACP transport.
// --------------------------------------------------------------------------- //

test("cli: ACP http entry with a bearer token", async () => {
  const { o, rec } = await fakeACPOpts({ FAKE_HTTP: "1" })
  const c = createCliClient(o)
  try {
    const res = await c.run("badge?")
    assertFakeACPRun(c, res.text, "http")
    assert.ok(res.text.includes("noauth=401"), res.text)
    const r = fs.readFileSync(rec, "utf8")
    assert.ok(r.includes('"headers":[{"name":"Authorization","value":"Bearer '), r)
    assert.ok(r.includes('"terminal":false'))
    assert.ok(!JSON.stringify(c.acpSession()).includes("Bearer "), "acpSession must redact the token")
  } finally {
    await c.close()
  }
})

test("cli: ACP stdio bridge entry when the agent lacks http", async () => {
  const { o, rec } = await fakeACPOpts({ FAKE_HTTP: "0" })
  const c = createCliClient(o)
  try {
    const res = await c.run("badge?")
    assertFakeACPRun(c, res.text, "<nil>")
    const r = fs.readFileSync(rec, "utf8")
    assert.ok(r.includes('"env":[{"name":"TOOLNEXUS_MCP_TOKEN"') && !r.includes('"type":"stdio"'), r)
  } finally {
    await c.close()
  }
})

test("cli: ACP project MCP file, temp dir removed and server stopped on close", async () => {
  const { o } = await fakeACPOpts({ FAKE_HTTP: "0", FAKE_PROJECT: "1" })
  o.styleDef.acp!.projectMcpFile = ".mcp.json"
  const c = createCliClient(o)
  const res = await c.run("badge?")
  assertFakeACPRun(c, res.text, "http")
  const info = c.acpSession()!
  assert.equal(info.mcpServersSent.length, 0)
  assert.ok(info.sessionCwd)
  const f = path.join(info.sessionCwd, ".mcp.json")
  assert.equal(fs.statSync(f).mode & 0o777, 0o600)
  await c.close()
  assert.ok(!fs.existsSync(info.sessionCwd), "temp session dir must be removed on close")
  await assert.rejects(fetch(info.url, { method: "POST", body: "{}" }), "served endpoint must stop with the session")
})

test("cli: ACP model pass-through (set_config_option, then set_model)", async () => {
  const { o, rec } = await fakeACPOpts({ FAKE_HTTP: "1", FAKE_NO_CONFIG: "1" })
  const c = createCliClient({ ...o, model: "anthropic/claude-x", transport: CliTransports.ACP })
  try {
    await c.run("badge?")
    const r = fs.readFileSync(rec, "utf8")
    assert.ok(r.includes('session/set_config_option {"sessionId":"s1","configId":"model","value":"anthropic/claude-x"}'), r)
    assert.ok(r.includes('session/set_model {"sessionId":"s1","modelId":"anthropic/claude-x"}'), r)
  } finally {
    await c.close()
  }
})

test("cli: Auto falls back to one-shot only on launch failure; forced ACP fails", async () => {
  const { o } = await fakeACPOpts({ FAKE_FAIL_INIT: "1", TN_FAKE_CLI: "tool" })
  const c = createCliClient(o)
  try {
    assert.equal(c.transport(), "auto")
    const res = await c.run("badge?", { toolkit: o.toolkit })
    assert.equal(c.transport(), "cli")
    assert.ok(c.fallbackReason(), "fallback reason recorded")
    assert.ok(res.text.includes("BADGE-77Q"), res.text)
  } finally {
    await c.close()
  }
  const { o: o2 } = await fakeACPOpts({ FAKE_FAIL_INIT: "1" })
  const c2 = createCliClient({ ...o2, transport: CliTransports.ACP })
  try {
    await assert.rejects(c2.run("badge?"), (e: Error) => e.message.startsWith("cli: acp: "), "forced ACP must fail")
  } finally {
    await c2.close()
  }
})

test("cli: exact served-call title forms", () => {
  const served = new Set(["get_badge_number", "load_skill"])
  const cases: Record<string, boolean> = {
    get_badge_number: true,
    mcp__toolnexus__get_badge_number: true,
    "toolnexus/get_badge_number": true,
    "get_badge_number (toolnexus MCP Server)": true,
    "Calling get_badge_number from toolnexus": true,
    "load_skill(name: secret-recipe)": true,
    skill: false,
    Bash: false,
    "": false,
    mcp__other__get_badge_number: false,
    "run get_badge_number and then rm -rf": false,
    "Calling get_badge_number from toolnexus2": false,
    "get_badge_number (evil MCP Server)": false,
    mcp__toolnexus__get_badge_number_and_more: false,
  }
  for (const [title, want] of Object.entries(cases)) assert.equal(cliIsServedCall(title, served), want, title)
})

test("cli: ACP systemHint from the style", async () => {
  assert.ok(cliStyleDef(CliStyles.Devin)!.acp!.systemHint!.includes('"toolnexus"'))
  const { o, rec } = await fakeACPOpts({ FAKE_HTTP: "1" })
  o.styleDef.acp!.systemHint = "HINT-USE-TOOLNEXUS"
  const c = createCliClient({ ...o, systemPrompt: "You are terse." })
  try {
    await c.run("badge?")
    assert.ok(fs.readFileSync(rec, "utf8").includes("You are terse.\\n\\nHINT-USE-TOOLNEXUS"))
  } finally {
    await c.close()
  }
  const got = cliWithSystemHint([{ role: "user", content: "x" }], "H")
  assert.equal(got.length, 2)
  assert.equal(got[0].content, "H")
  const msgs = [{ role: "system", content: "S" }]
  cliWithSystemHint(msgs, "H")
  assert.equal(msgs[0].content, "S", "caller's messages must not be mutated")
})

test("cli: hooks run at the served-tool boundary", async () => {
  const { o } = await fakeACPOpts({ FAKE_HTTP: "1" })
  const seen: string[] = []
  const c = createCliClient({
    ...o,
    hooks: {
      beforeTool: (ev) => {
        seen.push("before:" + ev.name)
      },
      afterTool: (ev) => {
        seen.push("after:" + ev.name)
      },
    },
  })
  try {
    await c.run("badge?")
    assert.deepEqual(seen, ["before:get_badge_number", "after:get_badge_number", "before:skill", "after:skill"])
  } finally {
    await c.close()
  }
})

// --------------------------------------------------------------------------- //
// conformance/cli-client/cases.json — the cross-port fixture.
// --------------------------------------------------------------------------- //

test("cli: conformance/cli-client/cases.json replay", () => {
  const fx = JSON.parse(fs.readFileSync(path.join(ROOT, "conformance/cli-client/cases.json"), "utf8"))
  const byName = new Map(Object.values(CliStyles).map((s) => [s as string, s]))
  for (const c of fx.argv) {
    const cl = createCliClient({ style: byName.get(c.style)!, command: c.command, transport: CliTransports.CLI, model: c.model || undefined })
    const { argv, outPath, tmp: files } = cl.cliArgv(c.prompt)
    for (const f of files) fs.rmSync(f, { force: true })
    const shown = argv.map((a) => (outPath && a === outPath ? "<out>" : files.length && a === files[0] ? "<file>" : a))
    assert.deepEqual(shown, c.argv, `${c.style} model=${c.model}`)
    assert.equal(cliStyleDef(byName.get(c.style)!)!.prompt === "stdin", c.stdin)
  }
  const norm = (v: unknown) => JSON.stringify(typeof v === "string" ? JSON.parse(v) : v)
  for (const c of fx.parse) {
    const r = parseCliReply(c.in)
    if (c.toolCall) {
      assert.equal(r.toolCalls?.length, 1, c.in)
      assert.equal(r.toolCalls![0].name, c.toolCall.name)
      assert.equal(r.toolCalls![0].id ?? "", c.toolCall.id ?? "")
      assert.equal(norm(r.toolCalls![0].arguments), norm(c.toolCall.arguments))
      continue
    }
    assert.equal(r.content, c.content, c.in)
    assert.ok(!r.toolCalls?.length)
  }
  for (const c of fx.constructionErrors) {
    const op = c.options
    const o: any = { command: op.command, model: op.model, prompt: op.prompt, answer: op.answer, args: op.args }
    if (op.style) o.style = byName.get(op.style)
    if (op.transport === "cli") o.transport = CliTransports.CLI
    if (op.transport === "acp") o.transport = CliTransports.ACP
    assert.throws(() => createCliClient(o), (e: Error) => e.message === c.error, c.error)
  }
  assert.equal(renderCliPrompt([], []), fx.renderEmpty.replace("<PREAMBLE>", CLI_PREAMBLE))
})

test("cli: ACP streams non-incrementally (text with the answer, then done)", async () => {
  const { o } = await fakeACPOpts({ FAKE_HTTP: "1" })
  const c = createCliClient(o)
  try {
    const { types, text } = await collectStream(c, o.toolkit)
    assert.equal(c.transport(), "acp", c.fallbackReason())
    assert.equal(types[types.length - 1], "done")
    assert.ok(types.includes("text"))
    assert.ok(text.includes("BADGE-77Q"), text)
  } finally {
    await c.close()
  }
})

test("cli: stream every style over both transports without error", async () => {
  const styles = [CliStyles.Claude, CliStyles.Codex, CliStyles.Copilot, CliStyles.Opencode,
    CliStyles.Devin, CliStyles.Gemini, CliStyles.Goose, CliStyles.Custom]
  for (const st of styles) {
    for (const tr of [CliTransports.CLI, CliTransports.ACP]) {
      const name = `${st}/${tr}`
      const tk = await badgeToolkit()
      let o: CliOptions = { style: st, command: FAKE, transport: tr, toolkit: tk, env: { TN_FAKE_CLI: "tool" } }
      if (st === CliStyles.Custom) o = { ...o, args: [], prompt: "stdin", answer: "stdout" }
      if (tr === CliTransports.ACP) {
        const { o: fo } = await fakeACPOpts({ FAKE_HTTP: "1" })
        o = { ...o, env: fo.env, bridgeCommand: fo.bridgeCommand, acpCommand: [FAKE, "acp"] }
      }
      const c = createCliClient(o)
      try {
        const { types, text } = await collectStream(c, tk)
        assert.equal(types[types.length - 1], "done", name)
        assert.ok(text.includes("BADGE-77Q"), `${name}: ${text}`)
        if (tr === CliTransports.CLI) assert.ok(types.includes("tool_call"), `${name}: one-shot stream carries tool_call`)
        assert.equal(c.transport(), tr, name)
      } finally {
        await c.close()
      }
    }
  }
})
