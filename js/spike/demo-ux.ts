/**
 * SPIKE demo 2 — the Level-1/2/bridge UX (S10–S13) on the same mock LLM.
 * Run: cd js && npm run build && node --experimental-strip-types spike/demo-ux.ts
 */
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { createClient, createToolkit, defineTool, type Tool } from "../dist/index.js"
import { agent, agentFromDir, startAgent, HEARTBEAT_OK } from "./agents.ts"

const openaiResponse = (message: Record<string, unknown>, tokens = { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 }) =>
  new Response(JSON.stringify({ choices: [{ message: { role: "assistant", ...message } }], usage: tokens }), { status: 200, headers: { "content-type": "application/json" } })
const call = (name: string, args: Record<string, unknown>, id: string) => ({
  content: null,
  tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
})

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const msgs: any[] = body.messages
  const toolMsgs = msgs.filter((m) => m.role === "tool")
  const sys = String(msgs.find((m) => m.role === "system")?.content ?? "")
  switch (body.model) {
    case "m-coder": {
      if (toolMsgs.length === 0) return openaiResponse(call("task", { agent: "explore", prompt: "find the bug" }, "t1"))
      return openaiResponse({ content: `fixed using: ${toolMsgs[0].content} [soul:${sys.includes("You are the CODER") ? "loaded" : "missing"}]` })
    }
    case "m-explore": {
      if (toolMsgs.length === 0) return openaiResponse(call("lookup", { q: "bug" }, "e1"))
      return openaiResponse({ content: `bug at line 42 (${toolMsgs[0].content})` })
    }
    case "m-mia": {
      // persona: soul sections visible? heartbeat with nothing to do → HEARTBEAT_OK
      const last = String(msgs[msgs.length - 1]?.content ?? "")
      if (last.includes("Heartbeat")) {
        const hasTicks = last.includes("channel=timer")
        return openaiResponse({ content: sys.includes("water the plants") && hasTicks ? "Reminder: water the plants! 🌱" : HEARTBEAT_OK })
      }
      return openaiResponse({ content: `soul-sections:[${["SOUL.md", "USER.md", "MEMORY.md"].filter((f) => sys.includes(`## ${f}`)).join(",")}]` })
    }
    case "m-old-api": {
      if (toolMsgs.length === 0) return openaiResponse(call("explore", { prompt: "scan the repo" }, "b1"))
      return openaiResponse({ content: `old-api summary: ${toolMsgs[0].content}` })
    }
    default:
      return openaiResponse({ content: "ok" })
  }
}

const lookup: Tool = defineTool({
  name: "lookup",
  description: "look something up",
  inputSchema: { type: "object", properties: { q: { type: "string" } } },
  run: async (a: Record<string, unknown>) => `data(${a.q})`,
})

let passed = 0
let failed = 0
const check = (n: string, c: boolean, d = "") => {
  c ? passed++ : failed++
  console.log(`  ${c ? "✅" : "❌"} ${n} ${c ? "" : d}`)
}
const section = (s: string) => console.log(`\n━━ ${s}`)

async function main() {
  // S10 — Level 1: the 12-line coding agent -------------------------------
  section("S10 Level-1 UX: agent() + team wiring + soul file")
  {
    const soulPath = path.join(os.tmpdir(), "AGENTS-spike.md")
    fs.writeFileSync(soulPath, "You are the CODER. Fix things surgically.")
    const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore" })
    const coder = agent("coder", { does: "implements changes", soulFile: soulPath, team: [explore], model: "m-coder", budget: { maxTokens: 10_000 } })
    const r = await coder.run({ fetch: mockFetch }, "fix the failing test")
    check("coding agent completes via its team", r.status === "done" && r.text.includes("bug at line 42"), r.text)
    check("soul FILE reached the system prompt", r.text.includes("[soul:loaded]"), r.text)
  }

  // S11 — team scoping: task cannot reach outside the declared team ---------
  section("S11 team scoping: task targets = the team, nothing else")
  {
    const stranger = agent("stranger", { does: "should be unreachable", model: "m-explore", uses: { tools: [lookup] } })
    const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore" })
    const coder = agent("coder", { does: "implements", team: [explore], model: "m-coder" })
    void stranger
    const reg = coder.registry()
    check("registry contains only the reachable graph", Object.keys(reg).sort().join(",") === "coder,explore", Object.keys(reg).join(","))
    check("coder's task targets are exactly its team", JSON.stringify((reg.coder as any).taskTargets) === '["explore"]')
  }

  // S12 — Level 2: agentFromDir + heartbeat + HEARTBEAT_OK silence ----------
  section("S12 Level-2 UX: the directory IS the agent · heartbeat · silent OK")
  {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mia-"))
    fs.writeFileSync(path.join(dir, "SOUL.md"), "You are Mia. Warm, brief.")
    fs.writeFileSync(path.join(dir, "USER.md"), "The user is Muthu.")
    fs.writeFileSync(path.join(dir, "MEMORY.md"), "- Likes green tea.")
    fs.writeFileSync(path.join(dir, "HEARTBEAT.md"), "On heartbeat: if it is watering day, remind to water the plants.")
    const mia = agentFromDir(dir, { model: "m-mia" })
    const direct = await mia.run({ fetch: mockFetch }, "hello")
    check("bootstrap files discovered + injected as ## sections (openclaw order)", direct.text === "soul-sections:[SOUL.md,USER.md,MEMORY.md]", direct.text)

    const reports: string[] = []
    const started = startAgent(mia, { fetch: mockFetch }, { everyMs: 25, onReport: (t) => reports.push(t) })
    await new Promise((r) => setTimeout(r, 140))
    await started.stop()
    const hbTurns = started.rt.trace.filter((l) => l.includes("idle→running")).length
    check("heartbeat woke the agent repeatedly", hbTurns >= 2, `turns=${hbTurns}`)
    check("HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)", reports.every((t) => t.includes("water")), JSON.stringify(reports))
    check("agent closed cleanly on stop()", started.handle.state === "closed")
  }

  // S13 — the bridge: agent.asTool() inside the OLD API ---------------------
  section("S13 bridge: an Agent IS a Tool in the classic createToolkit/createClient API")
  {
    const explore = agent("explore", { does: "read-only research", uses: { tools: [lookup] }, model: "m-explore" })
    const toolkit = await createToolkit({ builtins: false, extraTools: [explore.asTool({ fetch: mockFetch })] })
    const client = createClient({ baseUrl: "http://mock.local", style: "openai", model: "m-old-api", apiKey: "spike", fetch: mockFetch })
    const r = await client.run("summarize", { toolkit })
    check("old API called the agent like any tool", r.text.includes("old-api summary:") && r.text.includes("bug at line 42"), r.text)
    check("agent metadata surfaced through the tool result", r.toolCalls[0]?.metadata?.agent === "explore")
  }

  console.log(`\n${"═".repeat(60)}`)
  console.log(`  ${passed} passed · ${failed} failed  ${failed === 0 ? "— UX LAYER PASSES ✅" : "❌"}`)
  console.log("═".repeat(60))
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error("demo crashed:", e)
  process.exit(1)
})
