// add-host-engine-needs: `command` + `args`, FileConversationStore + list/delete, MCP idle timeout.
import { test } from "node:test"
import assert from "node:assert/strict"
import os from "node:os"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { parseMcpConfig, loadMcp, FileConversationStore, InMemoryConversationStore } from "../dist/index.js"

const PROGRESS_SERVER = path.resolve(fileURLToPath(import.meta.url), "../../../examples/mcp-progress-server.mjs")
const tmpRoot = () => fs.mkdtempSync(path.join(os.tmpdir(), "tn-store-"))

// ---- command + args ----
test("command+args: string command with args under mcpServers", () => {
  assert.deepEqual(parseMcpConfig({ mcpServers: { a: { command: "node", args: ["s.mjs"] } } }).a, { command: ["node", "s.mjs"] })
})
test("command+args: array command with args under servers", () => {
  assert.deepEqual(parseMcpConfig({ servers: { a: { command: ["node", "--x"], args: ["s.mjs"] } } }).a, { command: ["node", "--x", "s.mjs"] })
})
test("command+args: flat form equals argv form", () => {
  assert.deepEqual(parseMcpConfig({ a: { command: "node", args: ["s.mjs"] } }), parseMcpConfig({ a: { command: ["node", "s.mjs"] } }))
})
test("command+args: string command without args", () => {
  assert.deepEqual(parseMcpConfig({ a: { command: "srv" } }).a, { command: ["srv"] })
})
test("command+args: args with spaces stay single argv elements", () => {
  assert.deepEqual(parseMcpConfig({ a: { command: "node", args: ["my file.mjs", "--a b"] } }).a, { command: ["node", "my file.mjs", "--a b"] })
})
test("command+args: remote server ignores args", () => {
  const r = parseMcpConfig({ r: { url: "http://x", args: ["y"] } as any }).r as any
  assert.equal(r.url, "http://x")
})
for (const [label, cfg, field] of [
  ["empty string", { command: "" }, "command"],
  ["empty array", { command: [] }, "command"],
  ["non-string element", { command: ["node", 3] }, "command"],
  ["missing command", { cwd: "/" }, "command"],
  ["args not array", { command: "node", args: "s.mjs" }, "args"],
  ["args non-string element", { command: "node", args: ["a", 1] }, "args"],
] as const) {
  test(`command+args: invalid ${label} names the server`, () => {
    assert.throws(() => parseMcpConfig({ mcpServers: { bad: cfg as any } }), (e: Error) => e.message.includes('"bad"') && e.message.includes(field))
  })
}

// ---- FileConversationStore ----
test("file store: roundtrip across instances", async () => {
  const root = path.join(tmpRoot(), "nested")
  await new FileConversationStore(root).save("c1", [{ role: "user", content: "hi" }])
  assert.deepEqual(await new FileConversationStore(root).get("c1"), [{ role: "user", content: "hi" }])
  assert.equal(await new FileConversationStore(root).get("nope"), undefined)
  const body = JSON.parse(fs.readFileSync(path.join(root, "c1.json"), "utf8"))
  assert.equal(body.version, 1)
})
test("file store: sync modes round-trip, leave no temp file; unknown rejected", async () => {
  assert.throws(() => new FileConversationStore(tmpRoot(), { sync: "bogus" as any }), /sync mode/)
  for (const sync of [undefined, "full", "fsync", "none"] as const) {
    const root = tmpRoot()
    await new FileConversationStore(root, { sync }).save("c1", [{ role: "user", content: "hi" }])
    assert.deepEqual(await new FileConversationStore(root).get("c1"), [{ role: "user", content: "hi" }])
    assert.deepEqual(fs.readdirSync(root), ["c1.json"], `mode ${sync}`)
  }
})
test("file store: root is required", () => {
  assert.throws(() => new FileConversationStore(""))
})
test("file store: traversal / invalid ids rejected by every op", async () => {
  const s = new FileConversationStore(tmpRoot())
  for (const id of ["..", ".", "../x", "a/b", "", "x".repeat(129), "a b"]) {
    await assert.rejects(s.get(id))
    await assert.rejects(s.save(id, []))
    await assert.rejects(s.delete(id))
  }
})
test("file store: corrupt file is an error", async () => {
  const root = tmpRoot()
  fs.writeFileSync(path.join(root, "c.json"), "{not json")
  await assert.rejects(new FileConversationStore(root).get("c"))
})
test("file store: unknown version is an error", async () => {
  const root = tmpRoot()
  fs.writeFileSync(path.join(root, "c.json"), JSON.stringify({ version: 2, messages: [] }))
  await assert.rejects(new FileConversationStore(root).get("c"), /version/)
})
test("file store: list newest first, ties by id, skips junk", async () => {
  const root = tmpRoot()
  const s = new FileConversationStore(root)
  for (const id of ["b", "a", "old", "new"]) await s.save(id, [])
  const t = (sec: number) => new Date(1_700_000_000_000 + sec * 1000)
  fs.utimesSync(path.join(root, "a.json"), t(10), t(10))
  fs.utimesSync(path.join(root, "b.json"), t(10), t(10))
  fs.utimesSync(path.join(root, "old.json"), t(1), t(1))
  fs.utimesSync(path.join(root, "new.json"), t(20), t(20))
  fs.writeFileSync(path.join(root, ".x.abc.tmp"), "")
  fs.writeFileSync(path.join(root, "bad name.json"), "")
  const rows = await s.list()
  assert.deepEqual(rows.map((r) => r.id), ["new", "a", "b", "old"])
  assert.ok(rows[0].updatedAt instanceof Date)
  assert.deepEqual(await new FileConversationStore(path.join(root, "missing")).list(), [])
})
test("file store: delete is idempotent", async () => {
  const s = new FileConversationStore(tmpRoot())
  await s.save("d", [1])
  await s.delete("d")
  await s.delete("d")
  assert.equal(await s.get("d"), undefined)
  assert.deepEqual(await s.list(), [])
})
test("file store: concurrent same-id saves leave a valid file and no temp files", async () => {
  const root = tmpRoot()
  const s = new FileConversationStore(root)
  await Promise.all(Array.from({ length: 25 }, (_, i) => s.save("same", [i, "x".repeat(i * 100)])))
  const got = await s.get("same")
  assert.ok(Array.isArray(got) && got.length === 2)
  assert.deepEqual(got, [24, "x".repeat(2400)]) // serialized in call order
  assert.deepEqual(fs.readdirSync(root), ["same.json"])
})
test("in-memory store: list/delete", async () => {
  const s = new InMemoryConversationStore()
  await s.save("b", [])
  await s.save("a", [])
  const ids = (await s.list()).map((r) => r.id)
  assert.deepEqual([...ids].sort(), ["a", "b"])
  await s.delete("a")
  await s.delete("a")
  assert.deepEqual((await s.list()).map((r) => r.id), ["b"])
  assert.equal(await s.get("a"), undefined)
})

// ---- MCP idle timeout ----
async function callSlow(args: Record<string, unknown>) {
  const src = await loadMcp({ p: { command: "node", args: [PROGRESS_SERVER], timeout: 300 } })
  try {
    const tool = src.tools.find((t) => t.name === "p_slow")!
    assert.ok(tool, JSON.stringify(src.status))
    return await tool.execute(args)
  } finally {
    await src.close()
  }
}
test("idle timeout: progress resets the timer past the timeout", async () => {
  const r = await callSlow({ totalMs: 1200, intervalMs: 100 })
  assert.equal(r.isError, false, r.output)
  const body = JSON.parse(r.output)
  assert.equal(body.progressTokenReceived, true)
  assert.ok(body.notificationsSent > 0)
})
test("idle timeout: no progress times out", async () => {
  const r = await callSlow({ totalMs: 1200, intervalMs: 0 })
  assert.equal(r.isError, true)
  assert.match(r.output, /timed out|timeout/i)
})
test("idle timeout: non-matching token never resets", async () => {
  const r = await callSlow({ totalMs: 1200, intervalMs: 100, wrongToken: true })
  assert.equal(r.isError, true)
  assert.match(r.output, /timed out|timeout/i)
})
test("idle timeout: cancellation wins over progress", async () => {
  const src = await loadMcp({ p: { command: "node", args: [PROGRESS_SERVER], timeout: 300 } })
  try {
    const ac = new AbortController()
    setTimeout(() => ac.abort(new Error("cancelled by caller")), 250)
    const t0 = Date.now()
    const r = await src.tools.find((t) => t.name === "p_slow")!.execute({ totalMs: 1200, intervalMs: 50 }, { signal: ac.signal } as any)
    assert.equal(r.isError, true)
    assert.ok(Date.now() - t0 < 1000)
  } finally {
    await src.close()
  }
})
