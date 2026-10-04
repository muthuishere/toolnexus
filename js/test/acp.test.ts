/**
 * Tests for the ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
 * openspec/changes/add-acp-model-source + add-acp-tool-calling. Hermetic: no network, no real ACP
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
import { createInProcessClient, createToolkit, defineTool, loadACP, type InProcessRequest } from "../dist/index.js"
// Internal helpers: exported from acp.ts for these tests, not from the package index.
import { ACP_PREAMBLE, parseACPReply, renderACPPrompt } from "../dist/acp.js"

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
    // A plain-text (non-envelope) reply passes through the parser untouched.
    assert.equal(r1.content, `echo:1:${renderACPPrompt(req("hello"))}`)
    assert.equal(r2.content, `echo:2:${renderACPPrompt(req("again"))}`)
    assert.equal(r3.content, `echo:3:${renderACPPrompt(req("third"))}`)

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
    // {"answer": ...} is not an envelope (no tool_calls/content/choices/message),
    // so it reaches the host as the original text — structured output intact.
    const parsed = JSON.parse(r.content ?? "")
    assert.equal(parsed.answer, renderACPPrompt(req("42")))
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
    // The client executes tools, the agent must not: by default the first
    // option whose kind STARTS WITH "reject" is selected.
    assert.equal(r.content, "PERMITTED:reject")
  } finally {
    await client.close()
  }
})

test("acp: allowAgentTools opts in — the first allow-kind option is selected, still immediately", async () => {
  const client = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "permission" }, allowAgentTools: true })
  try {
    const start = Date.now()
    const r = await client.generate(req("go"))
    assert.ok(Date.now() - start < 500, "permission was awaited, not answered")
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
    assert.equal(r1.content, `done:${renderACPPrompt(req("first"))}`)
    assert.equal(r2.content, `done:${renderACPPrompt(req("second"))}`)
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

// ---------------------------------------------------------------------------
// ACP as a real tool-calling model (SPEC §8 "ACP model source",
// openspec/changes/add-acp-tool-calling).
// ---------------------------------------------------------------------------

/** Pulls the REQUEST JSON back out of a rendered prompt. */
function splitPrompt(prompt: string): { preamble: string; request: any } | null {
  const i = prompt.indexOf("\nREQUEST:\n")
  const j = prompt.lastIndexOf("\n\nSUPERSEDES-ALL-PRIOR: ")
  if (i < 0 || j < i) return null
  return { preamble: prompt.slice(0, i), request: JSON.parse(prompt.slice(i + "\nREQUEST:\n".length, j)) }
}

test("acp: tool-calling loop end to end — the agent asks for a tool, the toolkit runs it, the agent answers", async () => {
  const requestFile = recordFile()
  const acp = await loadACP({ command: "node", args: [FIXTURE], env: { ACP_SCENARIO: "toolloop", ACP_REQUEST_FILE: requestFile } })
  const tk = await createToolkit({ builtins: false })
  const ran: unknown[] = []
  tk.register(
    defineTool({
      name: "add",
      description: "Add two numbers",
      inputSchema: { type: "object", properties: { a: { type: "number" }, b: { type: "number" } }, required: ["a", "b"] },
      run: async (a: Record<string, unknown>) => {
        ran.push(a)
        return String(Number(a.a) + Number(a.b))
      },
    }),
  )
  try {
    const client = createInProcessClient({ model: "acp", generate: acp.generate })
    const r = await client.run("What is 2 + 3?", { toolkit: tk })
    assert.equal(r.text, "The answer is 5.")
    assert.equal(r.toolCalls.length, 1)
    assert.equal(r.toolCalls[0].name, "add")
    assert.equal(r.toolCalls[0].output, "5")
    assert.deepEqual(ran, [{ a: 2, b: 3 }])

    const requests = fs.readFileSync(requestFile, "utf8").trim().split("\n").map((l) => JSON.parse(l))
    assert.equal(requests.length, 2, "expected 2 prompts (ask, then answer)")
    // Turn 1: add's OpenAI-shaped schema reached the agent.
    const add = requests[0].tools.find((t: any) => t?.type === "function" && t.function?.name === "add")
    assert.ok(add, `turn 1 tools did not carry add: ${JSON.stringify(requests[0].tools)}`)
    assert.deepEqual(add.function.parameters.required, ["a", "b"])
    // Turn 2: the assistant tool_calls message and the tool result are both there.
    const msgs = requests[1].messages
    assert.ok(
      msgs.some((m: any) => m.role === "assistant" && Array.isArray(m.tool_calls) && m.tool_calls[0]?.id === "c1"),
      `turn 2 is missing the assistant tool_calls message: ${JSON.stringify(msgs)}`,
    )
    assert.ok(
      msgs.some((m: any) => m.role === "tool" && m.tool_call_id === "c1" && m.content === "5"),
      `turn 2 is missing the tool result: ${JSON.stringify(msgs)}`,
    )
  } finally {
    await tk.close()
    await acp.close()
  }
})

test("acp: prompt shape — pinned preamble, REQUEST JSON leading with messages, no HTML escaping, marker line", () => {
  const p = renderACPPrompt({
    model: "acp",
    body: {},
    messages: [
      { role: "system", content: "be terse" },
      {
        role: "user",
        content: [{ type: "text", text: "a <b> & c" }, { type: "image_url" }, { type: "text", text: "d" }],
      },
    ],
  })
  const split = splitPrompt(p)
  assert.ok(split, `prompt does not split: ${JSON.stringify(p)}`)
  assert.equal(split.preamble, ACP_PREAMBLE)
  assert.ok(p.endsWith("\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d"), `marker line wrong: ${JSON.stringify(p.slice(-60))}`)
  assert.ok(!p.includes("\\u003c"), "REQUEST JSON is HTML-escaped")
  assert.deepEqual(split.request.tools, [], "absent tools must render as []")
  assert.ok(p.includes('\nREQUEST:\n{"messages":'), "REQUEST JSON must lead with messages")

  // Latest-user fallbacks: no user message ⇒ the last message; no messages ⇒ empty.
  assert.ok(renderACPPrompt({ model: "acp", body: {}, messages: [{ role: "system", content: "sys" }] }).endsWith("SUPERSEDES-ALL-PRIOR: sys"))
  assert.ok(renderACPPrompt({ model: "acp", body: {}, messages: [] }).endsWith('{"messages":[],"tools":[]}\n\nSUPERSEDES-ALL-PRIOR: '))
})

// The preamble is byte-pinned by SPEC.md §8; read it back from the spec so the
// constant cannot drift from the contract every port is held to.
test("acp: the preamble matches SPEC.md byte for byte", (t) => {
  let spec: string
  try {
    spec = fs.readFileSync(new URL("../../SPEC.md", import.meta.url), "utf8")
  } catch (e) {
    t.skip(`SPEC.md not reachable: ${e}`)
    return
  }
  const i = spec.indexOf("`PREAMBLE` is these seven lines")
  assert.ok(i >= 0, "SPEC.md has no ACP preamble block")
  const s = spec.slice(i)
  const start = s.indexOf("```\n") + "```\n".length
  const end = s.indexOf("```", start)
  assert.equal(ACP_PREAMBLE, s.slice(start, end))
})

test("acp: reply parser — the SPEC §8 table", () => {
  const add = [{ id: "c1", name: "add", arguments: { a: 2, b: 3 } }]
  const addStr = [{ id: "c1", name: "add", arguments: '{"a":2,"b":3}' }]
  const cases: Array<[string, string, unknown[] | undefined, string | undefined]> = [
    ["plain prose passes through", "just text {not json", undefined, "just text {not json"],
    ["content envelope", '{"content":"The answer is 5."}', undefined, "The answer is 5."],
    ["null content", '{"content":null}', undefined, ""],
    ["non-string content encodes", '{"content":{"x":1}}', undefined, '{"x":1}'],
    ["string arguments pre-encoded", '{"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}', addStr, undefined],
    ["object arguments", '{"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}', add, undefined],
    ["fenced", '```json\n{"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}\n```', add, undefined],
    ["prose around", 'Calling now: {"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]} done', add, undefined],
    ["choices envelope", '{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}}]}', add, undefined],
    ["message envelope", '{"message":{"content":"hi"}}', undefined, "hi"],
    ["flat call, no id, no arguments", '{"tool_calls":[{"name":"ping"}]}', [{ name: "ping", arguments: {} }], undefined],
    ["nameless call skipped, falls to content", '{"tool_calls":[{"function":{"arguments":"{}"}}],"content":"fallback"}', undefined, "fallback"],
    ["structured output passes through", ' {"answer":true} ', undefined, ' {"answer":true} '],
  ]
  for (const [name, input, calls, content] of cases) {
    const got = parseACPReply(input)
    assert.deepEqual(got.toolCalls, calls, `${name}: toolCalls`)
    assert.equal(got.content, content, `${name}: content`)
  }
})
