/**
 * SPEC §7F.1 reactive compaction on context overflow (issue #194). Replays the shared fixture
 * `conformance/reactive_compaction/cases.json`: every detection case against `isContextOverflow`,
 * and every loop case through run AND stream, in both styles, against an in-test HTTP server.
 *
 * Run: npm run build && node --experimental-strip-types --test test/reactive-compaction.test.ts
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import http from "node:http"
import path from "node:path"
import { fileURLToPath } from "node:url"
import type { AddressInfo } from "node:net"
import {
  createClient,
  createToolkit,
  defineTool,
  isContextOverflow,
  ContextOverflowError,
  LlmHttpError,
  InMemoryConversationStore,
} from "../dist/index.js"

const FIX = JSON.parse(fs.readFileSync(
  path.resolve(fileURLToPath(import.meta.url), "../../../conformance/reactive_compaction/cases.json"), "utf8"))

for (const c of FIX.detection) {
  test(`detection: ${c.name}`, () => {
    assert.equal(isContextOverflow(c.status, c.body), c.overflow)
  })
}

type Style = "openai" | "anthropic"

/** One scripted response per HTTP call, rendered for `style` and `stream`. */
function respond(res: http.ServerResponse, step: string, style: Style, stream: boolean, n: number) {
  if (step === "overflow" || step === "bad400") {
    const e = step === "overflow" ? FIX.overflowBodies[style] : FIX.bad400
    res.writeHead(e.status, { "Content-Type": "application/json" }).end(e.body)
    return
  }
  const isTool = step === "tool"
  const id = `call_${n}`
  if (!stream) {
    const body = style === "openai"
      ? { choices: [{ index: 0, finish_reason: isTool ? "tool_calls" : "stop", message: isTool
          ? { role: "assistant", content: null, tool_calls: [{ id, type: "function", function: { name: "ping", arguments: "{}" } }] }
          : { role: "assistant", content: "done" } }] }
      : { role: "assistant", stop_reason: isTool ? "tool_use" : "end_turn", content: isTool
          ? [{ type: "tool_use", id, name: "ping", input: {} }] : [{ type: "text", text: "done" }] }
    res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(body))
    return
  }
  res.writeHead(200, { "Content-Type": "text/event-stream" })
  const ev = (o: unknown) => res.write(`data: ${JSON.stringify(o)}\n\n`)
  if (style === "openai") {
    if (isTool) ev({ choices: [{ index: 0, delta: { tool_calls: [{ index: 0, id, function: { name: "ping", arguments: "{}" } }] }, finish_reason: "tool_calls" }] })
    else ev({ choices: [{ index: 0, delta: { content: "done" }, finish_reason: "stop" }] })
    res.end("data: [DONE]\n\n")
  } else {
    ev({ type: "message_start", message: { usage: { input_tokens: 1, output_tokens: 0 } } })
    if (isTool) {
      ev({ type: "content_block_start", index: 0, content_block: { type: "tool_use", id, name: "ping" } })
      ev({ type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: "{}" } })
    } else {
      ev({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })
      ev({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "done" } })
    }
    ev({ type: "content_block_stop", index: 0 })
    ev({ type: "message_delta", delta: { stop_reason: isTool ? "tool_use" : "end_turn" } })
    ev({ type: "message_stop" })
    res.end()
  }
}

const history = Array.from({ length: 8 }, (_, i) => ({ role: i % 2 === 0 ? "user" : "assistant", content: `h${i}` }))

for (const c of FIX.loops) {
  for (const style of ["openai", "anthropic"] as Style[]) {
    for (const mode of ["run", "stream"] as const) {
      test(`loop [${style} ${mode}]: ${c.name}`, async () => {
        let calls = 0
        const server = http.createServer((req, res) => {
          req.resume()
          req.on("end", () => {
            const step = c.script[calls++] ?? "text"
            respond(res, step, style, mode === "stream", calls)
          })
        })
        await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
        const port = (server.address() as AddressInfo).port
        let hookCalls = 0
        try {
          const client = createClient({
            baseUrl: `http://127.0.0.1:${port}`, apiKey: "YOUR_KEY_HERE", model: "m", style,
            maxTurns: 50, retries: 0,
            store: new InMemoryConversationStore(),
            ...(c.option ? {
              compactOnOverflow: (ev: { messages: any[] }) => {
                hookCalls++
                return c.hookNoop ? undefined : { messages: ev.messages.slice(1) }
              },
            } : {}),
          })
          const toolkit = await createToolkit({ extraTools: [defineTool({
            name: "ping", description: "ping", run: async () => "ok" })] })
          const go = async (): Promise<string> => {
            if (mode === "run") return (await client.run("go", { toolkit, history })).text
            const store = (client as any).store
            await store.save("t", history)
            let text = ""
            for await (const ev of client.stream("go", { toolkit, id: "t" })) if (ev.type === "done") text = ev.result.text
            return text
          }
          if (c.expect.text !== undefined) {
            assert.equal(await go(), c.expect.text)
            assert.equal(hookCalls, c.expect.compactions)
          } else {
            await assert.rejects(go(), (e: any) => {
              if (c.expect.error === "LlmHttpError") {
                assert.ok(e instanceof LlmHttpError, String(e))
                assert.equal(e.status, c.expect.status)
              } else {
                assert.ok(e instanceof ContextOverflowError, String(e))
                assert.equal(e.compactions, c.expect.compactions)
                assert.equal(e.message, c.expect.message)
                assert.equal(e.status, 400)
              }
              return true
            })
          }
          assert.equal(calls, c.calls)
        } finally {
          server.close()
        }
      })
    }
  }
}
