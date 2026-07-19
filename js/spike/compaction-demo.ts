/**
 * SPIKE 3 demo — compaction acceptance scenarios (C1–C6). Zero cost.
 * Proves: no-op below budget (byte-identical), summarize-above-budget, tool-pair
 * safety, tail preservation, system-prompt preservation, and end-to-end through the
 * SHIPPED client loop via the beforeLLM seam.
 * Run: cd js && npm run build && node --experimental-strip-types spike/compaction-demo.ts
 */
import { createClient, createToolkit, defineTool, type Tool } from "../dist/index.js"
import { compactor, estimateTokens, type Message } from "./compaction.ts"

let pass = 0, fail = 0
const check = (n: string, c: boolean, d = "") => { c ? pass++ : fail++; console.log(`  ${c ? "✅" : "❌"} ${n} ${c ? "" : d}`) }
const section = (s: string) => console.log(`\n━━ ${s}`)

// a transcript with tool groups: system, user, assistant+tool_calls, tool, assistant, user, ...
function transcript(userTurns: number): Message[] {
  const m: Message[] = [{ role: "system", content: "You are Ava. SOUL." }]
  for (let i = 0; i < userTurns; i++) {
    m.push({ role: "user", content: `question ${i} ${"pad ".repeat(40)}` })
    m.push({ role: "assistant", content: null, tool_calls: [{ id: `c${i}`, type: "function", function: { name: "lookup", arguments: "{}" } }] })
    m.push({ role: "tool", tool_call_id: `c${i}`, content: `result ${i} ${"data ".repeat(40)}` })
    m.push({ role: "assistant", content: `answer ${i}` })
  }
  return m
}
const summarize = (older: Message[]) => `summarized ${older.length} messages`

async function main() {
  // C1 — no-op below budget -------------------------------------------------
  section("C1 below budget → no-op (byte-identical)")
  {
    const hook = compactor({ maxTokens: 100_000, summarize })
    const msgs = transcript(3)
    const out = await hook({ messages: msgs, tools: [], model: "m", turn: 0 })
    check("returns nothing when under budget", out === undefined)
  }

  // C2 — compacts above budget ---------------------------------------------
  section("C2 above budget → summarize head, keep tail")
  {
    const msgs = transcript(30)
    const before = estimateTokens(msgs)
    const hook = compactor({ maxTokens: 2000, keepTail: 800, summarize })
    const out = await hook({ messages: msgs, tools: [], model: "m", turn: 0 })
    check("compacts when over budget", out !== undefined)
    check("result is smaller than the original", out !== undefined && estimateTokens(out.messages) < before)
    check("a summary system message is inserted", !!out?.messages.some((m) => String(m.content).startsWith("[Summary of earlier conversation]")))
  }

  // C3 — tool-pair safety ---------------------------------------------------
  section("C3 tool-pair safety: no orphaned tool result")
  {
    const msgs = transcript(30)
    const out = await compactor({ maxTokens: 2000, keepTail: 800, summarize })({ messages: msgs, tools: [], model: "m", turn: 0 })
    const t = out!.messages
    // every tool message must be preceded (somewhere earlier) by an assistant carrying its id
    let safe = true
    for (let i = 0; i < t.length; i++) {
      if (t[i].role === "tool") {
        const id = t[i].tool_call_id
        const hasParent = t.slice(0, i).some((m) => m.role === "assistant" && (m.tool_calls ?? []).some((c: any) => c.id === id))
        if (!hasParent) safe = false
      }
    }
    check("no tool message is orphaned from its tool_calls", safe)
    check("the tail begins at a clean user turn", t.find((m) => m.role !== "system")?.role === "user")
  }

  // C4 — system prompt preserved -------------------------------------------
  section("C4 leading system prompt preserved verbatim")
  {
    const msgs = transcript(30)
    const out = await compactor({ maxTokens: 2000, summarize })({ messages: msgs, tools: [], model: "m", turn: 0 })
    check("original SOUL system prompt is still first", out!.messages[0].role === "system" && String(out!.messages[0].content).includes("SOUL"))
  }

  // C5 — flush-to-memory nudge ---------------------------------------------
  section("C5 flushToMemory injects a pre-compact reminder")
  {
    const msgs = transcript(30)
    const out = await compactor({ maxTokens: 2000, summarize, flushToMemory: true })({ messages: msgs, tools: [], model: "m", turn: 0 })
    check("a 'save with the memory tool' reminder is present", out!.messages.some((m) => String(m.content).includes("save it with the memory tool")))
  }

  // C6 — end-to-end through the SHIPPED client loop -------------------------
  section("C6 wired via beforeLLM in a real run: the loop keeps going past a compaction")
  {
    // mock LLM: makes 6 tool calls (padding the transcript), then a final answer.
    let calls = 0
    const mockFetch: typeof fetch = async (_u, init) => {
      const body = JSON.parse(String(init?.body))
      const compacted = body.messages.some((m: any) => String(m.content ?? "").startsWith("[Summary"))
      // "done" is a call-count decision (like a real model deciding it has enough), NOT a
      // count of tool messages in the transcript — compaction legitimately removes those.
      const done = calls >= 6
      const message = done
        ? { role: "assistant", content: `final (compacted=${compacted})` }
        : { role: "assistant", content: null, tool_calls: [{ id: `c${calls++}`, type: "function", function: { name: "pad", arguments: "{}" } }] }
      return new Response(JSON.stringify({ choices: [{ message }], usage: { prompt_tokens: 50, completion_tokens: 10, total_tokens: 60 } }), { status: 200, headers: { "content-type": "application/json" } })
    }
    const pad: Tool = defineTool({ name: "pad", description: "pads", inputSchema: { type: "object", properties: {} }, run: async () => "x ".repeat(600) })
    const toolkit = await createToolkit({ builtins: false, extraTools: [pad] })
    const client = createClient({
      baseUrl: "http://mock.local", style: "openai", model: "m", apiKey: "spike", maxTurns: 20, fetch: mockFetch,
      systemPrompt: "You are Ava. SOUL.",
      hooks: { beforeLLM: compactor({ maxTokens: 600, keepTail: 250, summarize }) },
    })
    const r = await client.run("start", { toolkit })
    check("run completes to a final answer despite mid-run compaction", r.status === "done" && r.text.startsWith("final"), r.text)
    check("compaction actually fired during the run", r.text.includes("compacted=true"), r.text)
    check("final transcript is bounded, not the full raw history", estimateTokens(r.messages as Message[]) < 4000, String(estimateTokens(r.messages as Message[])))
  }

  console.log(`\n${"═".repeat(60)}\n  ${pass} passed · ${fail} failed  ${fail === 0 ? "— COMPACTION SPIKE PASSES ✅" : "❌"}\n${"═".repeat(60)}`)
  process.exit(fail === 0 ? 0 : 1)
}
main().catch((e) => { console.error("crashed:", e); process.exit(1) })
