/**
 * §10 Suspension — Pending / waitFor.
 *
 * A tool can't finish in one shot: it needs an out-of-band, async resolution (a human logs
 * in, approves, types a value). It returns `Pending(request)`; the client calls the host's
 * `waitFor(request) -> answer`, then RETRIES the tool with `ctx.answer`. Login is just the
 * `kind:"authorization"` case — no auth subsystem, just data + one function.
 *
 * This runs the REAL client loop against a tiny stubbed "LLM" (no network, no key) so you can
 * see the mechanism actually fire. It shows both host postures:
 *   A) waitFor provided → the engine resolves + retries transparently; the model gets the answer.
 *   B) no waitFor       → run() halts with { status:"pending", pending:request } to resume later.
 *
 *   node --experimental-strip-types examples/pending.ts
 */
import assert from "node:assert"
import { createToolkit, defineTool, createClient, authRequired, type Answer } from "../dist/index.js"

// ── A tool that needs authorization the first time, then succeeds ────────────────
let authed = false
function balanceToolkit() {
  authed = false
  const tk = createToolkit({})
  return tk.then((t) => {
    t.register(
      defineTool({
        name: "get_balance",
        description: "Return the account balance. Requires login first.",
        inputSchema: { type: "object", properties: {}, additionalProperties: false },
        run: (_args, ctx) => {
          if (!authed) return authRequired("https://example.com/login?token=abc", "Log in to view your balance")
          return `balance: ₹67,417 (resolved via answer ${ctx?.answer?.id})`
        },
      }),
    )
    return t
  })
}

// ── A tiny fake OpenAI endpoint: turn 1 calls get_balance; turn 2 (after it sees a tool
//    result) returns a final answer. Lets us exercise the real client loop with no network. ──
function stubLLM() {
  const real = globalThis.fetch
  globalThis.fetch = (async (_url: any, init: any) => {
    const body = JSON.parse(init.body)
    const sawToolResult = body.messages.some((m: any) => m.role === "tool")
    const message = sawToolResult
      ? { role: "assistant", content: "Done — your balance is ₹67,417." }
      : { role: "assistant", content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "get_balance", arguments: "{}" } }] }
    return {
      ok: true,
      json: async () => ({ choices: [{ message }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
    } as any
  }) as any
  return () => { globalThis.fetch = real }
}

const restore = stubLLM()
try {
  // ── A) waitFor provided → resolve + retry, transparently ───────────────────────
  const seen: string[] = []
  const clientA = createClient({
    baseUrl: "http://stub", style: "openai", model: "stub",
    // waitFor is the ONLY behavior the host supplies. Here we simulate the human completing
    // login. In real life: open a browser, OR text the link to #stockloop, OR forward over A2A.
    waitFor: async (request): Promise<Answer> => {
      seen.push(request.url!)
      authed = true // the world changed out-of-band
      return { id: request.id, ok: true }
    },
  })
  const a = await clientA.run("what is my balance?", { toolkit: await balanceToolkit() })
  console.log("A) waitFor path")
  console.log("   link delivered to host :", seen[0])
  console.log("   tool call output       :", a.toolCalls[0].output)
  console.log("   final answer           :", a.text)
  console.log("   status                 :", a.status)
  assert.equal(a.status, "done")
  assert.match(a.toolCalls[0].output, /67,417/)
  assert.ok(seen[0].includes("example.com/login"))

  // ── B) NO waitFor → durable: run() halts with the request to resume later ───────
  const clientB = createClient({ baseUrl: "http://stub", style: "openai", model: "stub" })
  const b = await clientB.run("what is my balance?", { toolkit: await balanceToolkit() })
  console.log("\nB) durable path (no waitFor)")
  console.log("   status                 :", b.status)
  console.log("   pending.kind           :", b.pending?.kind)
  console.log("   pending.url            :", b.pending?.url)
  assert.equal(b.status, "pending")
  assert.equal(b.pending?.kind, "authorization")
  assert.ok(b.pending?.url?.includes("example.com/login"))

  console.log("\n✅ Pending/waitFor works end-to-end through the real client loop.")
  console.log("   login is just data (kind:'authorization'); resolution is one function (waitFor).")
} finally {
  restore()
}
