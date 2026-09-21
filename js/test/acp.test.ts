/**
 * Tests for the ACP (Agent Client Protocol) model source — issue #96, ADR 0025,
 * openspec/changes/add-acp-model-source. Hermetic: no network, no real ACP
 * agent. Drives `test/fixtures/acp-fake-server.mjs`, a small built-ins-only
 * Node script that speaks the same JSON-RPC-over-stdio protocol a real agent
 * (devin acp, Gemini CLI, Zed) does, scripted per ADR 0025's gate items.
 *
 * Run: npm run build && node --experimental-strip-types --test test/acp.test.ts
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { loadACP, type InProcessRequest } from "../dist/index.js"

const FIXTURE = new URL("./fixtures/acp-fake-server.mjs", import.meta.url).pathname

function recordFile(): string {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "acp-test-")), "record.ndjson")
}

function req(text: string, history: string[] = []): InProcessRequest {
  const messages = [...history.map((h) => ({ role: "user", content: h })), { role: "user", content: text }]
  return { messages, model: "acp", body: {} }
}

test("acp: warm session reuse — one process, one session/new, many generate() calls", async () => {
  const record = recordFile()
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "default", ACP_RECORD_FILE: record } })
  try {
    const r1 = await client.generate(req("hello"))
    const r2 = await client.generate(req("again"))
    const r3 = await client.generate(req("third"))
    assert.equal(r1.content, "echo:1:user: hello\nSUPERSEDES-ALL-PRIOR: hello")
    assert.equal(r2.content, "echo:2:user: again\nSUPERSEDES-ALL-PRIOR: again")
    assert.equal(r3.content, "echo:3:user: third\nSUPERSEDES-ALL-PRIOR: third")

    const lines = fs.readFileSync(record, "utf8").trim().split("\n")
    assert.equal(lines.length, 1, "session/new must be sent exactly once across three generate() calls")
  } finally {
    await client.close()
  }
})

test("acp: session/new carries an absolute cwd and an mcpServers array", async () => {
  const record = recordFile()
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "default", ACP_RECORD_FILE: record } })
  try {
    const params = JSON.parse(fs.readFileSync(record, "utf8").trim().split("\n")[0])
    assert.ok(path.isAbsolute(params.cwd), `cwd must be absolute, got ${JSON.stringify(params.cwd)}`)
    assert.ok(Array.isArray(params.mcpServers), "mcpServers must be present as an array")
  } finally {
    await client.close()
  }
})

test("acp: only agent_message_chunk text is accumulated — thought/tool narration is dropped", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "noisy" } })
  try {
    const r = await client.generate(req("42"))
    const parsed = JSON.parse(r.content ?? "")
    assert.equal(parsed.answer, "user: 42\nSUPERSEDES-ALL-PRIOR: 42")
  } finally {
    await client.close()
  }
})

test("acp: a permission request is answered, not awaited — the turn completes quickly", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "permission" } })
  try {
    const start = Date.now()
    const r = await client.generate(req("delete the database"))
    const elapsed = Date.now() - start
    assert.ok(elapsed < 500, `expected the turn to complete quickly, took ${elapsed}ms`)
    // The fixture offers reject_once first, allow_once second — the client
    // must pick the first option whose kind STARTS WITH "allow".
    assert.equal(r.content, "PERMITTED:allow-once")
  } finally {
    await client.close()
  }
})

test("acp: an unresponsive agent is bounded by the safety-net timeout, not left to hang forever", async () => {
  const client = await loadACP({
    command: "node",
    args: [FIXTURE],
    env: { ACP_SCENARIO: "hang" },
    permissionTimeoutMs: 300,
  })
  try {
    await assert.rejects(client.generate(req("anything")), /timed out/)
  } finally {
    await client.close()
  }
})

test("acp: the supersedes marker prevents a stale answer from a stateful session", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "stale" } })
  try {
    const r1 = await client.generate(req("What is the capital of France?"))
    assert.equal(r1.content, "FRESH-ANSWER-TO:What is the capital of France?")

    // Turn 2's full assembled request contains turn 1's text verbatim (the
    // library always sends the WHOLE transcript) — without the supersedes
    // marker a naive stateful agent would match turn 1 first and answer
    // stale. The marker the library appends must make it answer turn 2.
    const r2 = await client.generate(req("What is the capital of Japan?", ["What is the capital of France?"]))
    assert.equal(r2.content, "FRESH-ANSWER-TO:What is the capital of Japan?")
    assert.notEqual(r2.content, "STALE-ANSWER-TO:What is the capital of France?")
  } finally {
    await client.close()
  }
})

test("acp: concurrent generate() calls on one session are serialised, never interleaved", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "serialize" } })
  try {
    const p1 = client.generate(req("first"))
    const p2 = client.generate(req("second"))
    const [r1, r2] = await Promise.all([p1, p2])
    assert.equal(r1.content, "done:user: first\nSUPERSEDES-ALL-PRIOR: first")
    assert.equal(r2.content, "done:user: second\nSUPERSEDES-ALL-PRIOR: second")
  } finally {
    await client.close()
  }
})

test("acp: close() is idempotent and actually terminates the child process", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "default" } })
  const pid = (client as unknown as { _pid?: number })._pid
  assert.ok(typeof pid === "number" && pid > 0, "expected an internal pid for the test to verify against")

  await client.close()
  await client.close() // must not throw / reject a second time

  assert.throws(() => process.kill(pid as number, 0), /ESRCH/, "the child process should no longer exist")
})
