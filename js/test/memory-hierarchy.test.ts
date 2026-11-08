/**
 * Memory hierarchy + dreaming (SPEC.md §7E "Memory hierarchy" / "Dreaming"), driven by the
 * shared fixtures `examples/memory-hierarchy/fixture.json` and `examples/dreaming/fixture.json`.
 * Hermetic: scripted in-process LLM, virtual clock, temp dirs.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import crypto from "node:crypto"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { agents, defineTool, transcriptSearchTool, type Tool } from "../dist/index.js"

const { composeSoul, memoryTool, fromDir, dreamer, startAgent, DREAM_PROMPT, HEARTBEAT_PROMPT } = agents

const load = (name: string) =>
  JSON.parse(fs.readFileSync(path.resolve(fileURLToPath(import.meta.url), `../../../examples/${name}/fixture.json`), "utf8"))
const MH = load("memory-hierarchy")
const DR = load("dreaming")

const sha256 = (s: string) => crypto.createHash("sha256").update(s, "utf8").digest("hex")

function mkHome(files: Record<string, string>): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "memh-"))
  for (const [f, body] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(dir, f)), { recursive: true })
    fs.writeFileSync(path.join(dir, f), body)
  }
  return dir
}

/** Every file under `dir`, keyed by '/'-separated relative path. */
function snapshot(dir: string): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (p: string, rel: string) => {
    for (const n of fs.readdirSync(p).sort()) {
      const f = path.join(p, n)
      if (fs.statSync(f).isDirectory()) walk(f, `${rel}${n}/`)
      else out[rel + n] = fs.readFileSync(f, "utf8")
    }
  }
  walk(dir, "")
  return out
}

function stubStore(conversations: Array<{ id: string; messages: any[] }>) {
  let reads = 0
  return {
    get reads() {
      return reads
    },
    async get(id: string) {
      reads++
      return conversations.find((c) => c.id === id)?.messages
    },
    async save() {
      throw new Error("transcript search must never write")
    },
    async list() {
      reads++
      return conversations.map((c) => ({ id: c.id, updatedAt: new Date(0) }))
    },
  }
}

// ===========================================================================
// G1 — index cap
// ===========================================================================

function capMemory(c: any): string {
  if (c.memory !== undefined) return c.memory
  let s = ""
  for (let i = 1; i <= c.generate.count; i++) s += c.generate.line.replaceAll("<i>", String(i)) + "\n"
  return s
}

for (const c of MH.indexCap.cases) {
  test(`G1 index cap: ${c.name}`, () => {
    const mem = capMemory(c)
    const dir = mkHome({ ...c.files, "MEMORY.md": mem })
    const { soul } = composeSoul(dir, c.options)
    assert.equal(sha256(soul), c.expect.soulSha256)
    assert.equal(Buffer.byteLength(soul), c.expect.soulBytes)
    assert.ok(soul.endsWith(c.expect.memorySectionTail))
    assert.equal(soul === composeSoul(dir).soul, c.expect.equalsUncappedSoul)
    assert.equal(fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8"), mem) // disk untouched
  })
}

test("G1: fromDir threads memoryIndexCap; 500 lines ⇒ first 200 + notice", () => {
  const c = MH.indexCap.cases.find((x: any) => x.name === "lines-cap-preset-notes-off")
  const dir = mkHome({ ...c.files, "MEMORY.md": capMemory(c) })
  const soul = fromDir(dir, { memoryIndexCap: true }).spec.soul!
  assert.equal(sha256(soul), c.expect.soulSha256)
  assert.ok(soul.includes("- fact 200\n\n[MEMORY.md index truncated") && !soul.includes("- fact 201"))
  assert.equal(fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").split("\n").length - 1, 500)
})

test("G1: invalid cap values throw", () => {
  const dir = mkHome({ "MEMORY.md": "- a\n" })
  assert.throws(() => composeSoul(dir, { memoryIndexCap: { maxLines: 0 } }))
})

// ===========================================================================
// G2 — notes tier
// ===========================================================================

test("G2: notes-off tool is byte-identical; notes-on schema/description pinned", () => {
  const off = memoryTool("/x")
  assert.equal(off.description, MH.memoryTool.notesOff.description)
  assert.deepEqual(off.inputSchema, MH.memoryTool.notesOff.inputSchema)
  assert.equal(JSON.stringify(memoryTool("/x", { notes: false }).inputSchema), JSON.stringify(off.inputSchema))
  const on = memoryTool("/x", { notes: true })
  assert.equal(on.description, MH.memoryTool.notesOn.description)
  assert.deepEqual(on.inputSchema, MH.memoryTool.notesOn.inputSchema)
})

for (const c of MH.memoryTool.cases) {
  test(`G2 memory tool: ${c.name}`, async () => {
    const dir = mkHome(c.seed)
    const tool = memoryTool(dir, { notes: c.notes })
    for (const op of c.ops) {
      const r = await tool.execute(op.args)
      assert.deepEqual({ output: r.output, isError: r.isError }, op.expect, JSON.stringify(op.args))
    }
    assert.deepEqual(snapshot(dir), c.expectFiles)
    if (c.soulMustNotContain) assert.ok(!composeSoul(dir, { notes: true }).soul.includes(c.soulMustNotContain))
  })
}

test("G2: list skips symlinks (dangling or not) and directories — regular files only", async (t) => {
  const f = MH.memoryTool.listSkipsNonRegular
  const dir = mkHome(f.regular)
  try {
    for (const [link, target] of Object.entries(f.symlinks)) fs.symlinkSync(target as string, path.join(dir, link))
  } catch {
    t.skip("symlinks unavailable")
    return
  }
  for (const d of f.dirs) fs.mkdirSync(path.join(dir, d))
  const r = await memoryTool(dir, { notes: true }).execute({ action: "list" })
  assert.deepEqual({ output: r.output, isError: r.isError }, { output: f.expectList, isError: false })
})

test("G2: fromDir notes:true wires the notes-on tool", () => {
  const dir = mkHome({ "SOUL.md": "S" })
  const mem = fromDir(dir, { notes: true }).spec.uses!.tools!.find((t: Tool) => t.name === "memory")!
  assert.equal(mem.description, MH.memoryTool.notesOn.description)
})

// ===========================================================================
// G3 — transcript search
// ===========================================================================

test("G3: transcript search schema/description pinned", () => {
  const t = transcriptSearchTool(stubStore([]))
  assert.equal(t.name, MH.transcriptSearch.defaults.name)
  assert.equal(t.description, MH.transcriptSearch.description)
  assert.deepEqual(t.inputSchema, MH.transcriptSearch.inputSchema)
})

for (const c of MH.transcriptSearch.cases) {
  test(`G3 transcript search: ${c.name}`, async () => {
    const convos = c.emptyStore ? [] : MH.transcriptSearch.store.conversations
    const t = transcriptSearchTool(stubStore(convos), c.options ?? {})
    const r = await t.execute(c.args)
    assert.deepEqual({ output: r.output, isError: r.isError }, c.expect)
  })
}

test("G3: empty query does not read the store; store without list() is rejected", async () => {
  const store = stubStore(MH.transcriptSearch.store.conversations)
  const r = await transcriptSearchTool(store).execute({ query: "" })
  assert.ok(r.isError)
  assert.equal(store.reads, 0)
  const noList = { async get() { return undefined }, async save() {} }
  assert.throws(() => transcriptSearchTool(noList as any), { message: MH.transcriptSearch.storeWithoutList.error })
})

test("G3: works over the shipped InMemoryConversationStore", async () => {
  const { InMemoryConversationStore } = await import("../dist/index.js")
  const store = new InMemoryConversationStore()
  await store.save("only", [{ role: "user", content: "The vault code is 4417" }])
  const r = await transcriptSearchTool(store).execute({ query: "VAULT" })
  assert.equal(r.output, "only#0 user: The vault code is 4417")
})

// ===========================================================================
// G4 — write safety
// ===========================================================================

test("G4: concurrent adds from two tools over one home keep every entry", async () => {
  const { writers, addsEach, expectEntries } = MH.writeSafety.concurrentAdds
  const dir = mkHome({})
  const tools = Array.from({ length: writers }, () => memoryTool(dir))
  await Promise.all(
    tools.flatMap((t, w) => Array.from({ length: addsEach }, (_, n) => t.execute({ action: "add", text: `w${w}-${n}` }))),
  )
  const lines = fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").trimEnd().split("\n")
  assert.equal(lines.length, expectEntries)
  assert.equal(new Set(lines).size, expectEntries)
})

test("G4: concurrent adds via a symlinked home and the real path keep every entry", async (t) => {
  const { addsEach, expectEntries } = MH.writeSafety.concurrentAddsViaSymlink
  const dir = mkHome({})
  const link = dir + "-link"
  try {
    fs.symlinkSync(dir, link)
  } catch {
    t.skip("symlinks unavailable")
    return
  }
  const tools = [memoryTool(link), memoryTool(dir)]
  await Promise.all(
    tools.flatMap((tool, w) => Array.from({ length: addsEach }, (_, n) => tool.execute({ action: "add", text: `w${w}-${n}` }))),
  )
  const lines = fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").trimEnd().split("\n")
  assert.equal(new Set(lines).size, expectEntries)
  assert.equal(lines.length, expectEntries)
})

test("G4: a 3 MB MEMORY.md + add grows on disk, nothing truncated, no temp file", async () => {
  const { bytes, fill, addText } = MH.writeSafety.largeFileAdd
  const original = fill.repeat(bytes)
  const dir = mkHome({ "MEMORY.md": original })
  const r = await memoryTool(dir, { notes: true }).execute({ action: "add", text: addText })
  assert.ok(!r.isError, r.output)
  assert.equal(fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8"), original + `\n- ${addText}\n`)
  assert.deepEqual(fs.readdirSync(dir), ["MEMORY.md"])
})

// ===========================================================================
// Dreaming
// ===========================================================================

class VirtualClock implements agents.Clock {
  private t = 0
  private timers: Array<{ at: number; fn: () => void; done: boolean }> = []
  now(): number {
    return this.t
  }
  setTimeout(fn: () => void, ms: number): () => void {
    const timer = { at: this.t + ms, fn, done: false }
    this.timers.push(timer)
    return () => {
      timer.done = true
    }
  }
  advance(ms: number): void {
    const target = this.t + ms
    for (;;) {
      const due = this.timers.filter((x) => !x.done && x.at <= target).sort((a, b) => a.at - b.at)[0]
      if (!due) break
      this.t = due.at
      due.done = true
      due.fn()
    }
    this.t = target
  }
}
async function flush(rounds = 40): Promise<void> {
  for (let i = 0; i < rounds; i++) await new Promise<void>((r) => setImmediate(r))
}

const SCRIPTS = DR.mockLLM.scripts
let dreamScript: "dreamer-fix" | "dreamer-idle" = "dreamer-fix"
const reply = (message: Record<string, unknown>) =>
  new Response(JSON.stringify({ choices: [{ message: { role: "assistant", ...message } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
const callTool = (name: string, args: unknown, id: string) =>
  reply({ content: null, tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }] })

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const msgs: any[] = body.messages
  const sys = String(msgs.find((m) => m.role === "system")?.content ?? "")
  const tool = msgs.filter((m) => m.role === "tool").map((m) => String(m.content))
  if (sys.includes("You are dreaming:")) {
    const steps = SCRIPTS[dreamScript]
    if (dreamScript === "dreamer-idle") return reply({ content: steps[0].text })
    const n = tool.length
    if (n === 0 || n === 1) return callTool(steps[n].toolCalls[0].name, steps[n].toolCalls[0].args, `d${n}`)
    if (n === 2) {
      if (tool[0].includes("Mt. Moon exit is south") && tool[1].includes("exit is north")) {
        return callTool(steps[2].toolCalls[0].name, steps[2].toolCalls[0].args, "d2")
      }
      return reply({ content: "HEARTBEAT_OK" })
    }
    if (n === 3) return callTool(steps[3].toolCalls[0].name, steps[3].toolCalls[0].args, "d3")
    return reply({ content: steps[4].text })
  }
  switch (body.model) {
    case "m-navigator":
      return reply({ content: sys.includes("Mt. Moon exit is north") ? "Head north out of Mt. Moon." : "Head south out of Mt. Moon." })
    default:
      return reply({ content: "HEARTBEAT_OK" })
  }
}

const store = () => stubStore(DR.store.conversations)

test("D0: DREAM_PROMPT is the fixture's exact text", () => {
  assert.equal(DREAM_PROMPT, DR.dreamPrompt)
})

test("D1 BUILD GATE (Mt. Moon): one dream pass fixes the wrong note, logs it, next session answers differently", async () => {
  const g = DR.expect.D1_mtMoonBuildGate
  const home = mkHome(DR.home)
  const before = await fromDir(home, { model: g.before.model }).run(g.before.prompt, { fetch: mockFetch })
  assert.equal(before.text, g.before.text)

  dreamScript = "dreamer-fix"
  const pass = await dreamer(home, { store: store() }).run(HEARTBEAT_PROMPT, { fetch: mockFetch })
  assert.equal(pass.text, g.pass.replyText)
  const snap = snapshot(home)
  for (const [f, body] of Object.entries(g.after.files)) assert.equal(snap[f], body, f)
  assert.equal(snap["memory/log.md"].split("\n").filter((l) => l.startsWith("- ")).length, 1)

  const after = await fromDir(home, { model: g.after.nextSession.model }).run(g.after.nextSession.prompt, { fetch: mockFetch })
  assert.equal(after.text, g.after.nextSession.text)

  // D2 (second case): a second pass finds nothing to do — silent, nothing written.
  const frozen = snapshot(home)
  const again = await dreamer(home, { store: store() }).run(HEARTBEAT_PROMPT, { fetch: mockFetch })
  assert.ok(again.text.includes("HEARTBEAT_OK"))
  assert.deepEqual(snapshot(home), frozen)
})

test("D2: an idle dreamer (HEARTBEAT_OK) writes nothing", async () => {
  const home = mkHome(DR.home)
  const frozen = snapshot(home)
  dreamScript = "dreamer-idle"
  try {
    const r = await dreamer(home, { store: store() }).run(HEARTBEAT_PROMPT, { fetch: mockFetch })
    assert.ok(r.text.includes("HEARTBEAT_OK"))
  } finally {
    dreamScript = "dreamer-fix"
  }
  assert.deepEqual(snapshot(home), frozen)
})

test("D3: the dreamer's tools are exactly memory (notes on) + transcript_search", () => {
  const e = DR.expect.D3_dreamerToolsExactly
  const home = mkHome(DR.home)
  const fake = (name: string) => defineTool({ name, description: name, run: () => "x" })
  const persona = fromDir(home, { tools: e.personaTools.map(fake) })
  assert.deepEqual(persona.spec.uses!.tools!.map((t: Tool) => t.name), [...e.personaTools, "memory"])
  const d = dreamer(home, { store: store() })
  const tools = d.spec.uses!.tools!
  assert.deepEqual(tools.map((t: Tool) => t.name), e.dreamerTools)
  assert.equal(tools[0].description, MH.memoryTool.notesOn.description)
})

test("D3b: dreamer soul = DREAM_PROMPT + DREAM.md + instructions; name; store checks", () => {
  const e = DR.expect.D3b_dreamerSoul
  const plain = mkHome(DR.home)
  assert.equal(dreamer(plain, { store: store() }).spec.soul, e.soulWithoutExtras)
  assert.equal(dreamer(plain, { store: store() }).name, `${path.basename(plain)}-dreamer`)
  const withExtras = mkHome({ ...DR.home, "DREAM.md": e["DREAM.md"] })
  assert.equal(dreamer(withExtras, { store: store(), instructions: e.instructions }).spec.soul, e.soul)
  assert.throws(() => dreamer(plain, { store: { async get() { return undefined }, async save() {} } as any }), /list\(\)/)
  assert.throws(() => dreamer(plain, {} as any), /store is required/)
})

test("D4: fromDir(…, {dream}) runs the dreamer on the persona's runtime; its replies never reach onBeat", async () => {
  const e = DR.expect.D4_fromDirDream
  const home = mkHome(DR.home)
  const clock = new VirtualClock()
  const onBeat: string[] = []
  dreamScript = "dreamer-fix"
  const started = startAgent(
    fromDir(home, { model: "m-quiet", dream: { everyMs: 50, store: store() } }),
    { fetch: mockFetch, clock },
    { everyMs: 1000, onBeat: (t) => onBeat.push(t) },
  )
  clock.advance(50)
  await flush()
  const snap = snapshot(home)
  for (const [f, body] of Object.entries(e.after.files)) assert.equal(snap[f], body, f)
  await started.stop()
  assert.deepEqual(onBeat, [])
  assert.deepEqual(started.beats, [])
})

test("D6: a dreamer name already in the persona's tree makes startAgent throw", () => {
  const e = DR.expect.D6_dreamerNameCollision
  const home = mkHome(DR.home)
  const persona = fromDir(home, { name: e.personaName, model: "m-quiet", dream: { everyMs: 50, store: store() } })
  ;(persona as any).spec.team = [agents.agent(`${e.personaName}-dreamer`, { does: "squatter", soul: "x" })]
  const clock = new VirtualClock()
  assert.throws(() => startAgent(persona, { fetch: mockFetch, clock }, { everyMs: 50 }), { message: e.error })
})

test("D5: absent dream option — no dreamer, nothing written", async () => {
  const home = mkHome(DR.home)
  const frozen = snapshot(home)
  const clock = new VirtualClock()
  const persona = fromDir(home, { model: "m-quiet" })
  assert.equal(Object.keys(persona.registry()).length, 1)
  const started = startAgent(persona, { fetch: mockFetch, clock }, { everyMs: 50 })
  for (let i = 0; i < 4; i++) {
    clock.advance(50)
    await flush()
  }
  await started.stop()
  assert.deepEqual(snapshot(home), frozen)
  assert.ok(!started.runtime.trace.some((l) => l.includes("dreamer")))
})
