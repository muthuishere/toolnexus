/**
 * Tests for the ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
 * openspec/changes/add-acp-model-source. Hermetic: no network, no real ACP
 * agent. Drives `test/fixtures/acp-fake-server.mjs`, a small built-ins-only
 * Node script that speaks the same JSON-RPC-over-stdio protocol a real agent
 * (devin acp, Gemini CLI, Zed) does, scripted per ADR 0031's gate items.
 *
 * Run: npm run build && node --experimental-strip-types --test test/acp.test.ts
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { createInProcessClient, loadACP, type InProcessRequest } from "../dist/index.js"

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

test("acp: an aborted signal sends session/cancel, rejects AbortError, and the session stays warm", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "cancel" }, requestTimeoutMs: 5000 })
  try {
    const ac = new AbortController()
    const seen: string[] = []
    const turn = client.generateStream(
      req("block"),
      (ev) => {
        if (ev.type === "text") {
          seen.push(ev.delta)
          ac.abort()
        }
      },
      { signal: ac.signal },
    )
    await assert.rejects(turn, (e: any) => e.name === "AbortError")
    assert.deepEqual(seen, ["working"])
    // The fake ends the held turn only on the notification; the next turn proves it saw one.
    const next = await client.generate(req("again"))
    assert.equal(next.content, "after-cancel-seen:true")

    const pre = new AbortController()
    pre.abort()
    await assert.rejects(client.generate(req("again"), { signal: pre.signal }), (e: any) => e.name === "AbortError")
  } finally {
    await client.close()
  }
})

// ---------------------------------------------------------------------------
// SPEC §8C "ACP content parts" (openspec/changes/fix-acp-image-parts).
// ---------------------------------------------------------------------------

// A 1x1 transparent PNG (67 bytes).
const PNG = Buffer.from(
  "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c63000100000500010d0a2db40000000049454e44ae426082",
  "hex",
)
const PNG_B64 = PNG.toString("base64")
const PNG_DESC = `image (image/png, ${PNG.length} bytes)`

/** One turn whose user message carries a text part and an image part (the OpenAI chat wire
 *  shape the in-process seam receives); returns the session/prompt blocks the fake recorded. */
async function imagePromptBlocks(scenario: string): Promise<any[]> {
  const promptFile = recordFile()
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: scenario, ACP_PROMPT_FILE: promptFile } })
  try {
    await client.generate({
      messages: [{ role: "user", content: [
        { type: "text", text: "what is in this picture?" },
        { type: "image_url", image_url: { url: `data:image/png;base64,${PNG_B64}` } },
      ] }],
      model: "acp",
      body: {},
    })
  } finally {
    await client.close()
  }
  return JSON.parse(fs.readFileSync(promptFile, "utf8").trim().split("\n")[0])
}

function splitBlocks(blocks: any[]): { text: string; images: any[] } {
  return {
    text: blocks.filter((b) => b.type === "text").map((b) => b.text).join(""),
    images: blocks.filter((b) => b.type === "image"),
  }
}

test("acp: an image part is sent as an ACP image block when the agent advertises image", async () => {
  const { text, images } = splitBlocks(await imagePromptBlocks("image"))
  assert.equal(images.length, 1, `expected 1 ACP image block (text=${JSON.stringify(text)})`)
  assert.equal(images[0].mimeType, "image/png")
  assert.equal(images[0].data, PNG_B64)
  assert.ok(!text.includes(PNG_B64), "base64 leaked into a text block")
  assert.ok(text.includes("what is in this picture?"), `text part lost: ${text}`)
})

test("acp: without the image capability an image part is only its description in the text", async () => {
  const { text, images } = splitBlocks(await imagePromptBlocks("default"))
  assert.equal(images.length, 0)
  assert.ok(!text.includes(PNG_B64), "base64 leaked into a text block")
  assert.ok(text.includes(PNG_DESC), `expected ${PNG_DESC} in ${text}`)
})

test("acp: a URL-only image part has an empty mimeType; a file block is typed by its mime", async () => {
  const promptFile = recordFile()
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "image", ACP_PROMPT_FILE: promptFile } })
  try {
    await client.generate({
      messages: [{ role: "user", content: [
        { type: "image_url", image_url: { url: "https://example.com/cat.png" } },
        { type: "file", file: { filename: "x.png", file_data: `data:image/png;base64,${PNG_B64}` } },
      ] }],
      model: "acp",
      body: {},
    })
  } finally {
    await client.close()
  }
  const { text, images } = splitBlocks(JSON.parse(fs.readFileSync(promptFile, "utf8").trim().split("\n")[0]))
  assert.ok(text.includes("image (, 0 bytes)"), text)
  assert.ok(text.includes(PNG_DESC), text)
  assert.equal(images.length, 1, "the URL-only part gets no block; the image-typed file does")
  assert.equal(images[0].data, PNG_B64)
})

test("acp: inbound image blocks become tool_result parts and response files", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "image_out" } })
  try {
    const results: any[] = []
    const resp = await client.generateStream(req("shoot"), (ev) => {
      if (ev.type === "tool_result") results.push(ev)
    })
    assert.equal(results.length, 1)
    assert.equal(results[0].output, PNG_DESC)
    assert.deepEqual(results[0].parts, [{ type: "image", mimeType: "image/png", data: PNG_B64 }])
    assert.equal(resp.content, "here")
    const files = resp.files ?? []
    assert.equal(files.length, 2, JSON.stringify(files))
    assert.equal(files[0].from, "screenshot")
    assert.equal(files[0].callId, "t1")
    assert.equal(files[0].type, "image")
    assert.equal(files[0].data, PNG_B64)
    assert.equal(files[1].from, "acp")
    assert.equal(files[1].callId, "")
    assert.equal(files[1].mimeType, "image/png")
    assert.equal(files[1].data, PNG_B64)
  } finally {
    await client.close()
  }
})

test("acp: inbound images reach RunResult.files through run() and stream()", async () => {
  const acp = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "image_out" } })
  try {
    const client = createInProcessClient({ model: "acp", generateStream: acp.generateStream })
    const res = await client.run("shoot")
    assert.equal(res.text, "here")
    assert.equal(res.files.length, 2, JSON.stringify(res.files))
    assert.equal(res.files[0].from, "screenshot")
    assert.equal(res.files[0].data, PNG_B64)
    assert.equal(res.files[1].from, "acp")

    let done: any
    for await (const ev of client.stream("shoot")) if (ev.type === "done") done = ev.result
    assert.ok(done, "stream ended without done")
    assert.equal(done.files.length, 2, JSON.stringify(done.files))
    assert.equal(done.files[0].callId, "t1")
  } finally {
    await acp.close()
  }
})
