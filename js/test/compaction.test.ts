/**
 * Context compaction tests (SPEC.md §7F) — the 11 spike acceptance checks (C1–C6)
 * rewritten as shipped tests, driven by the shared `examples/compaction/fixture.json`
 * where it maps: the no-op below budget (byte-identical), summarize-above-budget,
 * tool-pair safety, tail preservation, system-prompt preservation, and end-to-end
 * through the shipped client loop via the `beforeLLM` seam.
 *
 * Hermetic: zero network, the C6 LLM is a scripted in-process `fetch`.
 *
 * Run: npm run build && node --experimental-strip-types --test test/compaction.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  agents,
  createClient,
  createToolkit,
  defineTool,
  type Tool,
} from "../dist/index.js"

const { compactor, estimateTokens } = agents
type Message = agents.Message

const FIXTURE = JSON.parse(
  fs.readFileSync(
    path.resolve(fileURLToPath(import.meta.url), "../../../examples/compaction/fixture.json"),
    "utf8",
  ),
)

// ---------------------------------------------------------------------------
// Build the transcript deterministically from the fixture generator, so this
// port produces the same bytes as every other. Each of `userTurns` groups is the
// four-message pattern: user, assistant+tool_calls, tool, assistant.
// ---------------------------------------------------------------------------
function buildTranscript(userTurns: number): Message[] {
  const { systemPrompt } = FIXTURE.input
  const m: Message[] = [{ role: "system", content: systemPrompt }]
  for (let i = 0; i < userTurns; i++) {
    m.push({ role: "user", content: `question ${i} ${"pad ".repeat(40)}` })
    m.push({
      role: "assistant",
      content: null,
      tool_calls: [{ id: `c${i}`, type: "function", function: { name: "lookup", arguments: "{}" } }],
    })
    m.push({ role: "tool", tool_call_id: `c${i}`, content: `result ${i} ${"data ".repeat(40)}` })
    m.push({ role: "assistant", content: `answer ${i}` })
  }
  return m
}

const summarize = (older: Message[]) => `summarized ${older.length} messages`
const { maxTokens, keepTail } = FIXTURE.options as { maxTokens: number; keepTail: number }

// C1 — no-op below budget (byte-identical) ----------------------------------
test("C1: below budget returns nothing (byte-identical no-op)", async () => {
  const hook = compactor({ maxTokens: 100_000, summarize })
  const msgs = buildTranscript(3)
  const out = await hook({ messages: msgs, tools: [], model: "m", turn: 0 })
  assert.equal(out, undefined, "hook must return nothing under budget")
})

// C2 — compacts above budget ------------------------------------------------
test("C2: above budget summarizes the head and keeps a tail", async () => {
  const msgs = buildTranscript(FIXTURE.input.generator.userTurns)
  const before = estimateTokens(msgs)
  const hook = compactor({ maxTokens, keepTail, summarize })
  const out = await hook({ messages: msgs, tools: [], model: "m", turn: 0 })

  assert.ok(out, "compacts when over budget")
  assert.ok(estimateTokens(out.messages) < before, "result is smaller than the original")
  assert.ok(
    out.messages.some((m) => String(m.content).startsWith(FIXTURE.expect.summaryContentPrefix)),
    "a summary system message is inserted",
  )
})

// C3 — tool-pair safety -----------------------------------------------------
test("C3: no tool result is orphaned and the tail begins at a user turn", async () => {
  const msgs = buildTranscript(FIXTURE.input.generator.userTurns)
  const out = await compactor({ maxTokens, keepTail, summarize })({
    messages: msgs,
    tools: [],
    model: "m",
    turn: 0,
  })
  assert.ok(out)
  const t = out.messages

  // Every tool message must be preceded (earlier in the result) by an assistant
  // carrying its tool_call_id.
  for (let i = 0; i < t.length; i++) {
    if (t[i].role !== "tool") continue
    const id = t[i].tool_call_id
    const hasParent = t
      .slice(0, i)
      .some((m) => m.role === "assistant" && ((m.tool_calls ?? []) as any[]).some((c) => c.id === id))
    assert.ok(hasParent, `tool message at ${i} is orphaned from its tool_calls`)
  }

  const firstNonSystem = t.find((m) => m.role !== "system")
  assert.equal(
    firstNonSystem?.role,
    FIXTURE.expect.firstNonSystemMessageRole,
    "the tail begins at a clean user turn",
  )
})

// C4 — leading system prompt preserved verbatim -----------------------------
test("C4: the leading system prompt is preserved verbatim", async () => {
  const msgs = buildTranscript(FIXTURE.input.generator.userTurns)
  const out = await compactor({ maxTokens, summarize })({
    messages: msgs,
    tools: [],
    model: "m",
    turn: 0,
  })
  assert.ok(out)
  assert.equal(out.messages[0].role, "system")
  assert.ok(
    String(out.messages[0].content).includes(FIXTURE.expect.leadingSystemMessage.contentContains),
    "original SOUL system prompt is still first",
  )
})

// C5 — flush-to-memory nudge ------------------------------------------------
test("C5: flushToMemory injects a pre-compact reminder before the tail", async () => {
  const msgs = buildTranscript(FIXTURE.input.generator.userTurns)
  const out = await compactor({ maxTokens, summarize, flushToMemory: true })({
    messages: msgs,
    tools: [],
    model: "m",
    turn: 0,
  })
  assert.ok(out)
  assert.ok(
    out.messages.some((m) => String(m.content).includes("save it with the memory tool")),
    "a 'save with the memory tool' reminder is present",
  )
})

// C6 — end-to-end through the shipped client loop ---------------------------
test("C6: wired via beforeLLM, the run continues past a mid-run compaction", async () => {
  // Mock LLM: makes 6 tool calls (padding the transcript), then a final answer.
  let calls = 0
  const mockFetch: typeof fetch = async (_u, init) => {
    const body = JSON.parse(String(init?.body))
    const compacted = body.messages.some((m: any) => String(m.content ?? "").startsWith("[Summary"))
    // "done" is a call-count decision (like a real model deciding it has enough), NOT
    // a count of tool messages — compaction legitimately removes those.
    const done = calls >= 6
    const message = done
      ? { role: "assistant", content: `final (compacted=${compacted})` }
      : {
          role: "assistant",
          content: null,
          tool_calls: [{ id: `c${calls++}`, type: "function", function: { name: "pad", arguments: "{}" } }],
        }
    return new Response(
      JSON.stringify({
        choices: [{ message }],
        usage: { prompt_tokens: 50, completion_tokens: 10, total_tokens: 60 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }

  const pad: Tool = defineTool({
    name: "pad",
    description: "pads",
    inputSchema: { type: "object", properties: {} },
    run: async () => "x ".repeat(600),
  })
  const toolkit = await createToolkit({ builtins: false, extraTools: [pad] })
  const client = createClient({
    baseUrl: "http://mock.local",
    style: "openai",
    model: "m",
    apiKey: "test",
    maxTurns: 20,
    fetch: mockFetch,
    systemPrompt: "You are Ava. SOUL.",
    hooks: { beforeLLM: compactor({ maxTokens: 600, keepTail: 250, summarize }) },
  })

  const r = await client.run("start", { toolkit })
  assert.equal(r.status, "done", `run completes to a final answer (got ${r.status})`)
  assert.ok(r.text.startsWith("final"), `final answer expected, got: ${r.text}`)
  assert.ok(r.text.includes("compacted=true"), `compaction should have fired during the run: ${r.text}`)
  assert.ok(
    estimateTokens(r.messages as Message[]) < 4000,
    `final transcript should be bounded, got ${estimateTokens(r.messages as Message[])}`,
  )
})
