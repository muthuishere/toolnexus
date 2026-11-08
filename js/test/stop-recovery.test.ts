/**
 * SPEC §8 "Stop-reason recovery": replays conformance/stop_recovery/cases.json in both styles
 * (openai, anthropic) and both loops (run, stream) against a scripted fetch — hermetic.
 */
import { strict as assert } from "node:assert"
import fs from "node:fs"
import test from "node:test"
import { createClient, createToolkit, defineTool } from "../dist/index.js"

const FIX = JSON.parse(fs.readFileSync(new URL("../../conformance/stop_recovery/cases.json", import.meta.url), "utf8"))

const STOP: Record<string, Record<string, string>> = {
  openai: { end: "stop", truncated: "length", tool: "tool_calls" },
  anthropic: { end: "end_turn", truncated: "max_tokens", tool: "tool_use" },
}

function parsedObj(s: string): any {
  try {
    const v = JSON.parse(s)
    return typeof v === "object" && v !== null && !Array.isArray(v) ? v : undefined
  } catch {
    return undefined
  }
}

function openaiJson(r: any) {
  const message: any = { role: "assistant", content: r.text ?? "" }
  if (r.thinking) message.reasoning_content = r.thinking
  if (r.toolCalls) message.tool_calls = r.toolCalls.map((c: any, i: number) => ({ id: `c${i}`, type: "function", function: { name: c.name, arguments: c.arguments } }))
  return { choices: [{ index: 0, message, finish_reason: STOP.openai[r.stop] }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }
}

function anthropicJson(r: any) {
  const content: any[] = []
  if (r.thinking) content.push({ type: "thinking", thinking: r.thinking, signature: "sig" })
  if (r.text) content.push({ type: "text", text: r.text })
  for (const [i, c] of (r.toolCalls ?? []).entries()) content.push({ type: "tool_use", id: `c${i}`, name: c.name, input: parsedObj(c.arguments) ?? c.arguments })
  return { content, stop_reason: STOP.anthropic[r.stop], usage: { input_tokens: 1, output_tokens: 1 } }
}

const sse = (events: any[]) => events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("")

function openaiSse(r: any) {
  const ev: any[] = []
  if (r.thinking) ev.push({ choices: [{ index: 0, delta: { reasoning_content: r.thinking } }] })
  if (r.text) ev.push({ choices: [{ index: 0, delta: { content: r.text } }] })
  for (const [i, c] of (r.toolCalls ?? []).entries()) ev.push({ choices: [{ index: 0, delta: { tool_calls: [{ index: i, id: `c${i}`, function: { name: c.name, arguments: c.arguments } }] } }] })
  ev.push({ choices: [{ index: 0, delta: {}, finish_reason: STOP.openai[r.stop] }] })
  return sse(ev) + "data: [DONE]\n\n"
}

function anthropicSse(r: any) {
  const ev: any[] = [{ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } }]
  let idx = 0
  if (r.thinking) {
    ev.push({ type: "content_block_start", index: idx, content_block: { type: "thinking", thinking: "" } })
    ev.push({ type: "content_block_delta", index: idx, delta: { type: "thinking_delta", thinking: r.thinking } })
    ev.push({ type: "content_block_stop", index: idx++ })
  }
  if (r.text) {
    ev.push({ type: "content_block_start", index: idx, content_block: { type: "text", text: "" } })
    ev.push({ type: "content_block_delta", index: idx, delta: { type: "text_delta", text: r.text } })
    ev.push({ type: "content_block_stop", index: idx++ })
  }
  for (const [i, c] of (r.toolCalls ?? []).entries()) {
    ev.push({ type: "content_block_start", index: idx, content_block: { type: "tool_use", id: `c${i}`, name: c.name, input: {} } })
    ev.push({ type: "content_block_delta", index: idx, delta: { type: "input_json_delta", partial_json: c.arguments } })
    ev.push({ type: "content_block_stop", index: idx++ })
  }
  ev.push({ type: "message_delta", delta: { stop_reason: STOP.anthropic[r.stop] }, usage: { output_tokens: 1 } })
  ev.push({ type: "message_stop" })
  return sse(ev)
}

for (const style of ["openai", "anthropic"] as const) {
  for (const loop of ["run", "stream"] as const) {
    for (const c of FIX.cases) {
      test(`stop recovery [${style}/${loop}] ${c.name}`, async () => {
        const sent: any[] = []
        let i = 0
        const fetchImpl = async (_url: string, init: any) => {
          const body = JSON.parse(init.body)
          sent.push(body)
          const r = c.responses[Math.min(i++, c.responses.length - 1)]
          if (body.stream) {
            return new Response(style === "openai" ? openaiSse(r) : anthropicSse(r), { status: 200, headers: { "content-type": "text/event-stream" } })
          }
          return new Response(JSON.stringify(style === "openai" ? openaiJson(r) : anthropicJson(r)), { status: 200, headers: { "content-type": "application/json" } })
        }
        const toolRuns: any[] = []
        const echo = defineTool({
          name: "echo", description: "echo", inputSchema: { type: "object", properties: {} },
          run: (args: any) => { toolRuns.push(args); return "ok" },
        })
        const toolkit = await createToolkit({ builtins: false, extraTools: [echo] })
        const client = createClient({
          baseUrl: "http://scripted.invalid", style, model: "m", apiKey: "unused", fetch: fetchImpl as any,
          retries: 0, stopRecovery: c.stopRecovery, ...(c.maxTurns ? { maxTurns: c.maxTurns } : {}),
        })
        let result: any
        if (loop === "run") result = await client.run("go", { toolkit })
        else {
          let deltas = ""
          for await (const ev of client.stream("go", { toolkit }) as any) {
            if (ev.type === "text") deltas += ev.delta
            if (ev.type === "done") result = ev.result
          }
          assert.ok(deltas.endsWith(result.text), "stream deltas end with the final text")
        }
        assert.equal(sent.length, c.expect.turns, "provider calls")
        assert.equal(result.turns, c.expect.turns, "turns")
        assert.equal(result.text, c.expect.text, "text")
        const got = sent.slice(1).map((b) => b.messages[b.messages.length - 1])
          .filter((m) => m.role === "user" && typeof m.content === "string" && Object.values(FIX.messages).includes(m.content))
        assert.deepEqual(got, c.expect.recoveries.map((k: string) => ({ role: "user", content: FIX.messages[k] })))
        assert.deepEqual(toolRuns, c.expect.toolRuns, "toolRuns")
      })
    }
  }
}
