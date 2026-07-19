/**
 * Agent home tests (SPEC.md §7E) — the 15 spike acceptance checks (H1–H7) rewritten
 * as shipped tests, driven by the shared `examples/persona-agent/fixture.json` where
 * it maps: the directory-is-the-agent bootstrap, the file-backed `memory` builtin with
 * frozen-snapshot semantics, and the virtual-clock heartbeat.
 *
 * Hermetic: zero network, the LLM is a scripted in-process `fetch` implementing the
 * fixture's mockLLM scripts. All heartbeat timing runs on a virtual clock.
 *
 * Run: npm run build && node --experimental-strip-types --test test/home.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { agents, type Tool } from "../dist/index.js"

const { fromDir, memoryTool, composeSoul, startAgent, BOOTSTRAP_ORDER, HEARTBEAT_OK } = agents

const FIXTURE = JSON.parse(
  fs.readFileSync(
    path.resolve(fileURLToPath(import.meta.url), "../../../examples/persona-agent/fixture.json"),
    "utf8",
  ),
)

// ---------------------------------------------------------------------------
// Virtual clock (§7D: fixtures run on a virtual clock)
// ---------------------------------------------------------------------------

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

/** Flush promise chains queued behind the mock LLM's async hops. */
async function flush(rounds = 20): Promise<void> {
  for (let i = 0; i < rounds; i++) await new Promise<void>((r) => setImmediate(r))
}

// ---------------------------------------------------------------------------
// Scripted mock LLM — the fixture's mockLLM scripts (openai style, keyed by model)
// ---------------------------------------------------------------------------

function openaiResponse(message: Record<string, unknown>, tokens = FIXTURE.mockLLM.usagePerCall) {
  return new Response(JSON.stringify({ choices: [{ message: { role: "assistant", ...message } }], usage: tokens }), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
}
const call = (name: string, args: Record<string, unknown>, id: string) => ({
  content: null,
  tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
})

/** Gate for the coalesce probe: `m-heartbeat-gated` blocks its turn until released,
 * so several beats overlap one in-flight turn. */
let gate = deferred()
function deferred<T = void>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const msgs: any[] = body.messages
  const toolMsgs = msgs.filter((m) => m.role === "tool")
  const sys = String(msgs.find((m) => m.role === "system")?.content ?? "")
  const last = String(msgs[msgs.length - 1]?.content ?? "")

  switch (body.model) {
    // m-echo-soul — reports which bootstrap sections it can see in the system prompt.
    case "m-echo-soul":
      return openaiResponse({ content: `sections:[${BOOTSTRAP_ORDER.filter((f) => sys.includes(`## ${f}`)).join(",")}]` })
    // m-remember — writes a user-memory entry, then confirms with the tool result.
    case "m-remember":
      if (toolMsgs.length === 0) return openaiResponse(call("memory", { action: "add", target: "user", text: "Prefers dark roast" }, "w1"))
      return openaiResponse({ content: `saved: ${toolMsgs[0].content}` })
    // m-recall — proves a fresh session's snapshot carries the persisted USER.md note.
    case "m-recall":
      return openaiResponse({ content: sys.includes("Prefers dark roast") ? "I recall: dark roast" : "no memory" })
    // m-heartbeat — speaks only when HEARTBEAT.md is due AND a heartbeat drove the turn.
    case "m-heartbeat":
      return openaiResponse({
        content: sys.includes("remind about the 3pm sync") && last.includes("Heartbeat") ? "Reminder: 3pm sync 🔔" : HEARTBEAT_OK,
      })
    // m-heartbeat-gated — a due heartbeat whose turn blocks on a gate (coalesce probe).
    case "m-heartbeat-gated":
      await gate.promise
      return openaiResponse({ content: "Reminder: 3pm sync 🔔" })
    default:
      return openaiResponse({ content: "ok" })
  }
}

const runOpts = { fetch: mockFetch, clock: new VirtualClock() }

// ---------------------------------------------------------------------------
// Bootstrap-dir helpers
// ---------------------------------------------------------------------------

function mkdir(files: Record<string, string>): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "home-"))
  for (const [f, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, f), body)
  return dir
}
/** A real bootstrap dir straight from the shared fixture. */
function fixtureDir(): string {
  return mkdir(FIXTURE.bootstrapDir)
}

// ===========================================================================
// H1 — the directory IS the agent: ordered bootstrap discovery + injection
// ===========================================================================

test("H1: ordered bootstrap discovery, ## injection, and the soul reaches the child prompt", async () => {
  const dir = fixtureDir()
  const { soul, found } = composeSoul(dir)

  // 1. only present files, in canonical order (matches the fixture's expectation)
  assert.deepEqual(found, FIXTURE.expect.H1_composedSoulSectionsInOrder)
  // 2. injected as ## sections, in order (SOUL before USER before HEARTBEAT before MEMORY)
  const order = FIXTURE.expect.H1_composedSoulSectionsInOrder.map((f: string) => soul.indexOf(`## ${f}`))
  assert.ok(order.every((v: number, i: number) => i === 0 || order[i - 1] < v), soul)
  // 3. the composed soul reaches the child's system prompt
  const ava = fromDir(dir, { model: "m-echo-soul" })
  const r = await ava.run("who are you?", runOpts)
  assert.equal(r.text, `sections:[${FIXTURE.expect.H1_composedSoulSectionsInOrder.join(",")}]`)
})

// ===========================================================================
// H2 — per-file 2 MB cap
// ===========================================================================

test("H2: a >2 MB bootstrap file is injected truncated with a notice, disk untouched", () => {
  const big = "x".repeat(3 * 1024 * 1024)
  const dir = mkdir({ "SOUL.md": big })
  const { soul } = composeSoul(dir)
  assert.ok(soul.includes("[truncated: exceeds 2 MB bootstrap cap]"))
  assert.ok(soul.length < 2.1 * 1024 * 1024, `soul length ${soul.length}`)
  // the on-disk file is untouched
  assert.equal(fs.readFileSync(path.join(dir, "SOUL.md"), "utf8").length, big.length)
})

// ===========================================================================
// H3 — the memory builtin: add / replace / remove persist to disk; loud miss
// ===========================================================================

test("H3: memory add/replace/remove persist; missing substring is a loud error; target routing", async () => {
  const dir = mkdir({ "MEMORY.md": "- Likes green tea." })
  const tool = memoryTool(dir)
  const read = (f: string) => fs.readFileSync(path.join(dir, f), "utf8")

  await tool.execute({ action: "add", text: "Likes hiking" })
  assert.ok(read("MEMORY.md").includes("- Likes hiking"))

  await tool.execute({ action: "replace", text: "green tea", with: "oolong" })
  assert.ok(read("MEMORY.md").includes("oolong") && !read("MEMORY.md").includes("green tea"))

  await tool.execute({ action: "remove", text: "- Likes hiking\n" })
  assert.ok(!read("MEMORY.md").includes("hiking"))

  const miss = await tool.execute({ action: "replace", text: "nonexistent", with: "x" })
  assert.ok(miss.isError && miss.output.includes("nonexistent"))

  const userWrite = await tool.execute({ action: "add", target: "user", text: "Speaks Tamil" })
  assert.ok(!userWrite.isError && read("USER.md").includes("Speaks Tamil"))
})

// ===========================================================================
// H4 — frozen-snapshot: a mid-session write hits disk, not the live prompt
// ===========================================================================

test("H4: memory write lands on disk and only the NEXT session's snapshot carries it", async () => {
  const dir = fixtureDir()
  const ava = fromDir(dir, { model: "m-remember" })
  await ava.run("note my coffee preference", runOpts)

  // the write landed on disk (target=user → USER.md)
  const { file, contains } = FIXTURE.expect.H4_writeLandsOnDisk
  assert.ok(fs.readFileSync(path.join(dir, file), "utf8").includes(contains))

  // a fresh fromDir re-reads the file → the snapshot now carries the note
  const next = FIXTURE.expect.H4_nextSessionSnapshotCarriesMemory
  const ava2 = fromDir(dir, { model: next.model })
  const r2 = await ava2.run("what do you know about me?", runOpts)
  assert.equal(r2.text, next.text)
})

// ===========================================================================
// H5 — heartbeat: silent HEARTBEAT_OK, substantive beat surfaces, ticks coalesce
// ===========================================================================

test("H5: HEARTBEAT_OK stays silent, a due heartbeat surfaces, and ticks coalesce", async () => {
  // (a) a persona with no due duty (no "3pm sync" in its soul) → HEARTBEAT_OK → nothing surfaces
  const quietDir = mkdir({ "SOUL.md": "You are Ava, calm." })
  const quietClock = new VirtualClock()
  const quiet = startAgent(fromDir(quietDir, { model: "m-heartbeat" }), { fetch: mockFetch, clock: quietClock }, { everyMs: 20 })
  for (let i = 0; i < 3; i++) {
    quietClock.advance(20)
    await flush()
  }
  await quiet.stop()
  assert.deepEqual(quiet.beats, [])

  // (b) a due HEARTBEAT.md → the reminder surfaces to onBeat (fixture bootstrap dir)
  const dir = fixtureDir()
  const clock = new VirtualClock()
  const onBeat: string[] = []
  const started = startAgent(fromDir(dir, { model: "m-heartbeat" }), { fetch: mockFetch, clock }, { everyMs: 20, onBeat: (t) => onBeat.push(t) })
  for (let i = 0; i < 3; i++) {
    clock.advance(20)
    await flush()
  }
  await started.stop()
  assert.ok(started.beats.length >= 1 && started.beats.every((b) => b.includes(FIXTURE.expect.H5_dueHeartbeatSurfaces.beatContains)), JSON.stringify(started.beats))
  assert.deepEqual(onBeat, started.beats)

  // (c) ticks coalesce: several intervals elapse while one gated turn is in flight →
  // the queued ticks drain as ONE turn, not one turn per tick.
  gate = deferred()
  const cDir = fixtureDir()
  const cClock = new VirtualClock()
  const coalesce = startAgent(fromDir(cDir, { model: "m-heartbeat-gated" }), { fetch: mockFetch, clock: cClock }, { everyMs: 20 })
  cClock.advance(20) // beat 1 — wakes, turn blocks on the gate
  await flush()
  cClock.advance(20) // beat 2 — handle running, tick coalesces in the inbox
  cClock.advance(20) // beat 3 — still running, tick coalesces
  await flush()
  const wakesWhileBlocked = coalesce.runtime.trace.filter((l) => l.includes("idle→running (wake)")).length
  assert.equal(wakesWhileBlocked, 1, `expected a single coalesced turn, got ${wakesWhileBlocked}`)
  gate.resolve()
  await flush()
  await coalesce.stop()
})

// ===========================================================================
// H6 — opt-out: memory:false → no memory tool in the toolkit view
// ===========================================================================

test("H6: memory:false omits the memory tool", () => {
  const dir = mkdir({ "SOUL.md": "Read-only Ava." })
  const ava = fromDir(dir, { memory: false })
  const names = (ava.spec.uses?.tools ?? []).map((t: Tool) => t.name)
  assert.ok(!names.includes("memory"))
})

// ===========================================================================
// H7 — recipe: dream/consolidation = fromDir + memory, no new surface
// ===========================================================================

test("H7: a dream/consolidation agent is just fromDir + the memory tool (composition)", () => {
  const dir = mkdir({
    "SOUL.md": "Nightly consolidator.",
    "HEARTBEAT.md": "Consolidate: merge duplicate notes into MEMORY.md via the memory tool.",
  })
  const dream = fromDir(dir, { model: "m-echo-soul" })
  const names = (dream.spec.uses?.tools ?? []).map((t: Tool) => t.name)
  assert.ok(names.includes("memory"))
})
