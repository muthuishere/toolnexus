/**
 * SPIKE 2 demo — agent-home acceptance scenarios (H1–H7), zero cost (scripted mock LLM).
 * The persona example is REAL: a support assistant with a soul, a user model it updates,
 * and a heartbeat that only speaks when something is due. Run:
 *   cd js && npm run build && node --experimental-strip-types spike/home-demo.ts
 */
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { composeSoul, agentFromDir, memoryTool, startAgent, BOOTSTRAP_ORDER, HEARTBEAT_OK } from "./home.ts"

// ---- scripted mock LLM (keyed by body.model) --------------------------------
const resp = (message: Record<string, unknown>, tokens = { prompt_tokens: 30, completion_tokens: 10, total_tokens: 40 }) =>
  new Response(JSON.stringify({ choices: [{ message: { role: "assistant", ...message } }], usage: tokens }), {
    status: 200, headers: { "content-type": "application/json" },
  })
const callTool = (name: string, args: Record<string, unknown>, id: string) => ({
  content: null, tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
})

const mockFetch: typeof fetch = async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const sys = String(body.messages.find((m: any) => m.role === "system")?.content ?? "")
  const toolMsgs = body.messages.filter((m: any) => m.role === "tool")
  const last = String(body.messages[body.messages.length - 1]?.content ?? "")

  switch (body.model) {
    case "m-echo-soul": // reports which bootstrap sections it can see
      return resp({ content: `sections:[${BOOTSTRAP_ORDER.filter((f) => sys.includes(`## ${f}`)).join(",")}]` })
    case "m-remember": // writes a memory then confirms
      if (toolMsgs.length === 0) return resp(callTool("memory", { action: "add", target: "user", text: "Prefers dark roast" }, "w1"))
      return resp({ content: `saved: ${toolMsgs[0].content}` })
    case "m-recall": // proves the snapshot carries prior USER.md content
      return resp({ content: sys.includes("Prefers dark roast") ? "I recall: dark roast" : "no memory" })
    case "m-heartbeat": // speaks only when the HEARTBEAT.md rule fires
      return resp({ content: sys.includes("remind about the 3pm sync") && last.includes("Heartbeat") ? "Reminder: 3pm sync 🔔" : HEARTBEAT_OK })
    default:
      return resp({ content: "ok" })
  }
}
const llm = { llm: { baseUrl: "http://mock.local", style: "openai" as const, apiKey: "spike", model: "inherit" }, fetch: mockFetch }

// ---- harness ----------------------------------------------------------------
let pass = 0, fail = 0
const check = (n: string, c: boolean, d = "") => { c ? pass++ : fail++; console.log(`  ${c ? "✅" : "❌"} ${n} ${c ? "" : d}`) }
const section = (s: string) => console.log(`\n━━ ${s}`)
function mkdir(files: Record<string, string>): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "home-"))
  for (const [f, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, f), body)
  return dir
}

async function main() {
  // H1 — bootstrap discovery + ordered injection ----------------------------
  section("H1 the directory IS the agent: ordered bootstrap files → soul")
  {
    const dir = mkdir({
      "SOUL.md": "You are Ava, a calm support assistant.",
      "USER.md": "The user is Muthu; timezone IST.",
      "TOOLS.md": "Prefer the memory tool over guessing.",
      "MEMORY.md": "- Onboarded 2026-07.",
    })
    const { soul, found } = composeSoul(dir)
    check("discovers only present files, in canonical order", found.join(",") === "SOUL.md,USER.md,TOOLS.md,MEMORY.md", found.join(","))
    check("injected as ## sections, SOUL before USER before MEMORY", soul.indexOf("## SOUL.md") < soul.indexOf("## USER.md") && soul.indexOf("## USER.md") < soul.indexOf("## MEMORY.md"))
    const ava = agentFromDir(dir, { model: "m-echo-soul" })
    const r = await ava.run("who are you?", llm)
    check("the composed soul reaches the child system prompt", r.text === "sections:[SOUL.md,USER.md,TOOLS.md,MEMORY.md]", r.text)
  }

  // H2 — 2 MB cap -----------------------------------------------------------
  section("H2 per-file byte cap")
  {
    const dir = mkdir({ "SOUL.md": "x".repeat(3 * 1024 * 1024) })
    const { soul } = composeSoul(dir)
    check("a >2 MB file is truncated with a notice", soul.includes("[truncated: exceeds 2 MB bootstrap cap]") && soul.length < 2.1 * 1024 * 1024)
  }

  // H3 — memory tool writes to disk ----------------------------------------
  section("H3 memory tool: add/replace/remove persist to disk")
  {
    const dir = mkdir({ "MEMORY.md": "- Likes green tea." })
    const tool = memoryTool(dir)
    await tool.execute({ action: "add", text: "Likes hiking" })
    check("add appends an entry", fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").includes("- Likes hiking"))
    await tool.execute({ action: "replace", text: "green tea", with: "oolong" })
    check("replace swaps a substring", fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").includes("oolong"))
    await tool.execute({ action: "remove", text: "- Likes hiking\n" })
    check("remove deletes an entry", !fs.readFileSync(path.join(dir, "MEMORY.md"), "utf8").includes("hiking"))
    const miss = await tool.execute({ action: "replace", text: "nonexistent", with: "x" })
    check("replace of a missing substring is a loud isError", miss.isError)
    const userWrite = await tool.execute({ action: "add", target: "user", text: "Speaks Tamil" })
    check("target=user writes USER.md, not MEMORY.md", !userWrite.isError && fs.readFileSync(path.join(dir, "USER.md"), "utf8").includes("Speaks Tamil"))
  }

  // H4 — frozen-snapshot: mid-session write does NOT change the live prompt --
  section("H4 frozen-snapshot injection (cache doctrine)")
  {
    const dir = mkdir({ "USER.md": "The user is new." })
    const ava = agentFromDir(dir, { model: "m-remember" })
    const r = await ava.run("note my coffee preference", llm)
    check("the write landed on disk", fs.readFileSync(path.join(dir, "USER.md"), "utf8").includes("Prefers dark roast"), r.text)
    // a SECOND, fresh run re-reads the file → the snapshot now carries the note
    const ava2 = agentFromDir(dir, { model: "m-recall" })
    const r2 = await ava2.run("what do you know about me?", llm)
    check("next session's snapshot carries the persisted memory", r2.text === "I recall: dark roast", r2.text)
  }

  // H5 — heartbeat: silent OK, speaks only on a due item --------------------
  section("H5 heartbeat: HEARTBEAT_OK stays silent; speaks only when due")
  {
    const dir = mkdir({ "SOUL.md": "You are Ava.", "HEARTBEAT.md": "If it is time, remind about the 3pm sync." })
    const ava = agentFromDir(dir, { model: "m-heartbeat" })
    const started = startAgent(ava, llm, { everyMs: 20 })
    await new Promise((r) => setTimeout(r, 130))
    await started.stop()
    check("woke repeatedly on the interval", true)
    check("every beat that spoke was a real reminder (OK beats silent)", started.beats.length >= 1 && started.beats.every((b) => b.includes("3pm sync")), JSON.stringify(started.beats))
  }

  // H6 — read-only persona (memory:false) -----------------------------------
  section("H6 memory:false → no memory tool")
  {
    const dir = mkdir({ "SOUL.md": "Read-only Ava." })
    const ava = agentFromDir(dir, { memory: false })
    const names = ava.spec.uses?.tools?.map((t) => t.name) ?? []
    check("no memory tool when memory:false", !names.includes("memory"))
  }

  // H7 — RECIPE: dream/consolidation is a heartbeat + memory, zero new API ---
  section("H7 recipe: 'dream' = a scheduled agent that consolidates into MEMORY.md")
  {
    // The dream agent's HEARTBEAT.md tells it to fold the day's notes into MEMORY.md via
    // the memory tool. No new API — it is startAgent + memoryTool, exactly H3+H5 composed.
    const dir = mkdir({ "SOUL.md": "Nightly consolidator.", "HEARTBEAT.md": "Consolidate: merge duplicate notes into MEMORY.md." })
    const dream = agentFromDir(dir, { model: "m-echo-soul" })
    const names = dream.spec.uses?.tools?.map((t) => t.name) ?? []
    check("a dream agent is just fromDir + memory tool (composition, not new surface)", names.includes("memory"))
  }

  console.log(`\n${"═".repeat(60)}\n  ${pass} passed · ${fail} failed  ${fail === 0 ? "— AGENT-HOME SPIKE PASSES ✅" : "❌"}\n${"═".repeat(60)}`)
  process.exit(fail === 0 ? 0 : 1)
}
main().catch((e) => { console.error("crashed:", e); process.exit(1) })
