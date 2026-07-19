/**
 * SPIKE 2 — agent-home (the persona half), on the SHIPPED §7D runtime.
 * Adds three things, all riding shipped seams (onSpawn injection point, the six
 * verbs, the runtime-wide store):
 *
 *   1. fromDir(dir)  — the directory IS the agent: ordered bootstrap files
 *      (AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md) composed into the soul
 *      as "## <file>" sections, 2 MB/file cap. Convention over configuration.
 *   2. memory tool   — one add|replace|remove tool over MEMORY.md/USER.md, with
 *      FROZEN-SNAPSHOT injection: the files are read into the system prompt at
 *      session start (via the soul), mid-session writes hit DISK not the live
 *      prompt, and refresh on the next run. (hermes/openclaw pattern.)
 *   3. startAgent    — heartbeat: an interval posts a tick to the agent's own inbox
 *      and wakes it (unsolicited rail, ticks coalesce); a HEARTBEAT_OK reply stays
 *      silent so no-op beats never message anyone.
 *
 * Throwaway-priced; extract to js/src/agents/home.ts after the spec lands.
 */
import fs from "node:fs"
import path from "node:path"
import { agents, defineTool, type Tool } from "../dist/index.js"

const { agent, AgentRuntime } = agents
type Agent = InstanceType<typeof agents.Agent>

/** openclaw bootstrap order — identity first, memory last. */
export const BOOTSTRAP_ORDER = [
  "AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md",
]
const MAX_FILE_BYTES = 2 * 1024 * 1024
export const HEARTBEAT_OK = "HEARTBEAT_OK"

/** Read a bootstrap file with the byte cap; undefined if absent. */
function readCapped(p: string): string | undefined {
  if (!fs.existsSync(p)) return undefined
  const body = fs.readFileSync(p, "utf8")
  return body.length > MAX_FILE_BYTES
    ? body.slice(0, MAX_FILE_BYTES) + "\n[truncated: exceeds 2 MB bootstrap cap]"
    : body
}

/** Compose the discovered bootstrap files into one soul string (frozen snapshot). */
export function composeSoul(dir: string): { soul: string; found: string[] } {
  const sections: string[] = []
  const found: string[] = []
  for (const f of BOOTSTRAP_ORDER) {
    const body = readCapped(path.join(dir, f))
    if (body !== undefined) {
      sections.push(`## ${f}\n\n${body.trim()}`)
      found.push(f)
    }
  }
  return { soul: sections.join("\n\n"), found }
}

/**
 * The `memory` builtin. One tool, three actions over MEMORY.md (the agent's own
 * notes) and USER.md (its model of the user). Writes go to DISK — the frozen
 * snapshot in the live prompt is intentionally NOT updated (cache-stable; the next
 * session re-reads). Returns the post-write file so the model sees what it wrote.
 */
export function memoryTool(dir: string): Tool {
  const fileFor = (target: string) => path.join(dir, target === "user" ? "USER.md" : "MEMORY.md")
  return defineTool({
    name: "memory",
    description:
      "Persist durable memory. action=add appends an entry; replace swaps an existing " +
      "substring; remove deletes one. target=self (MEMORY.md, default) or user (USER.md). " +
      "Writes persist to disk and load at the START of your next session — they do NOT change " +
      "your current context.",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["add", "replace", "remove"] },
        target: { type: "string", enum: ["self", "user"] },
        text: { type: "string", description: "For add: the entry. For replace/remove: the existing text." },
        with: { type: "string", description: "For replace: the replacement text." },
      },
      required: ["action", "text"],
    },
    run: (args: Record<string, unknown>) => {
      const file = fileFor(String(args.target ?? "self"))
      const cur = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : ""
      const text = String(args.text)
      let next = cur
      switch (args.action) {
        case "add":
          next = (cur.trimEnd() + `\n- ${text}\n`).trimStart()
          break
        case "replace":
          if (!cur.includes(text)) return { output: `not found: ${text}`, isError: true }
          next = cur.replace(text, String(args.with ?? ""))
          break
        case "remove":
          if (!cur.includes(text)) return { output: `not found: ${text}`, isError: true }
          next = cur.replace(text, "")
          break
      }
      fs.mkdirSync(path.dirname(file), { recursive: true })
      fs.writeFileSync(file, next)
      return { output: `ok (${args.action} → ${path.basename(file)}); loads next session` }
    },
  })
}

export interface FromDirOptions {
  does?: string
  name?: string
  model?: string
  /** Extra tools beyond the memory builtin. */
  tools?: Tool[]
  /** Set false to omit the memory tool (read-only persona). */
  memory?: boolean
}

/**
 * The directory IS the agent. Discovers bootstrap files → soul (frozen snapshot),
 * wires the memory tool over the same dir. `run(prompt, {llm})` like any agent.
 */
export function agentFromDir(dir: string, opts: FromDirOptions = {}): Agent {
  const { soul } = composeSoul(dir)
  const name = opts.name ?? path.basename(dir)
  const tools = [...(opts.tools ?? [])]
  if (opts.memory !== false) tools.push(memoryTool(dir))
  return agent(name, {
    does: opts.does ?? `persona agent from ${dir}`,
    soul,
    model: opts.model ?? "inherit",
    uses: { tools },
  })
}

export interface StartedAgent {
  stop: () => Promise<void>
  /** Fires only for NON-silent beats (HEARTBEAT_OK stripped). */
  beats: string[]
}

/**
 * Start a long-lived persona: a heartbeat interval posts a tick and wakes it with a
 * "read your HEARTBEAT.md and act, else HEARTBEAT_OK" prompt. HEARTBEAT_OK replies
 * are silent. Channels (Telegram/etc.) stay the HOST's job — wire them to post().
 */
export function startAgent(
  a: Agent,
  llm: FromDirOptions & { llm: unknown },
  opts: { everyMs: number; onBeat?: (text: string) => void } = { everyMs: 30 * 60_000 },
): StartedAgent {
  const runtime = new AgentRuntime({ ...(llm as any), registry: a.registry() })
  const spawned = runtime.spawn(runtime.root, a.name)
  if (agents.isVerbError(spawned)) throw new Error(spawned.error)
  const handle = spawned
  const beats: string[] = []
  const prompt =
    "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply " +
    HEARTBEAT_OK + "."
  const timer = setInterval(() => {
    // unsolicited rail: the tick lands in the inbox (coalesces); wake drains it as one turn
    runtime.post(handle, { from: "clock", channel: "timer", text: "tick" })
    if (handle.state === "idle") {
      const woke = runtime.wake(handle, prompt)
      if (woke.ok) {
        void runtime.wait(handle).then((r) => {
          if (!r.isError && !r.text.includes(HEARTBEAT_OK)) {
            beats.push(r.text)
            opts.onBeat?.(r.text)
          }
        })
      }
    }
  }, opts.everyMs)
  return {
    beats,
    stop: async () => {
      clearInterval(timer)
      await runtime.close(runtime.root)
    },
  }
}
