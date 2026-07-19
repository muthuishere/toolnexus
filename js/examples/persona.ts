/**
 * Persona agent (SPEC §7E): the directory IS the agent. `agents.fromDir` composes a
 * folder of bootstrap files (SOUL/USER/HEARTBEAT/MEMORY.md) into a frozen soul and wires
 * a file-backed `memory` tool, so Ava can edit her own durable memory. This proves the two
 * things that make a persona different from a one-shot worker:
 *
 *   1. a memory write lands on DISK (not just in the transcript), and
 *   2. it loads at the START of the NEXT session (frozen-snapshot — the cache-stable rule).
 *
 * The committed bootstrap dir (examples/persona-agent/ava) is copied to a writable temp dir
 * first, so a run never dirties the repo — the real pattern for a sandboxed host is exactly
 * this: point the home dir somewhere writable.
 *
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/persona.ts
 */
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { agents } from "../dist/index.js"

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) {
  console.log("no OPENROUTER_API_KEY — skipping live persona loop")
  process.exit(0)
}

const llm = {
  baseUrl: "https://openrouter.ai/api/v1",
  style: "openai" as const,
  model: process.env.OPENROUTER_MODEL ?? "openai/gpt-4o-mini",
  apiKey: KEY,
}

// Copy the committed bootstrap dir to a writable temp home so the run never dirties the repo.
const source = path.resolve(fileURLToPath(import.meta.url), "../../../examples/persona-agent/ava")
const home = fs.mkdtempSync(path.join(os.tmpdir(), "ava-"))
fs.cpSync(source, home, { recursive: true })

// Session 1 — Ava learns a stable preference and records it with her memory tool.
const ava = agents.fromDir(home, { name: "ava" })
const s1 = await ava.run(
  "I take my coffee as a dark roast, no sugar. This is a stable preference — " +
    "call the memory tool now (action=add, target=user) to save it to my profile, " +
    "then reply 'noted'.",
  { llm },
)
console.log("session 1:", s1.status, "·", s1.text.replace(/\n/g, " ").slice(0, 80))

const userModel = fs.readFileSync(path.join(home, "USER.md"), "utf8")
const persisted = /dark roast/i.test(userModel)
console.log("USER.md on disk now contains 'dark roast':", persisted)

// Session 2 — a FRESH fromDir re-reads the folder: the frozen snapshot now carries the write.
const ava2 = agents.fromDir(home, { name: "ava" })
const s2 = await ava2.run("How do I take my coffee? Answer from what you already know.", { llm })
console.log("session 2:", s2.status, "·", s2.text.replace(/\n/g, " ").slice(0, 80))

fs.rmSync(home, { recursive: true, force: true })

const recalled = /dark roast/i.test(s2.text)
if (!persisted || !recalled) {
  console.error("❌ persona did not persist + recall memory across sessions")
  process.exit(1)
}
console.log("\n✅ persona memory verified — written to disk in session 1, recalled in session 2")
