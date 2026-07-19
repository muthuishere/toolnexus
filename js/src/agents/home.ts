/**
 * Agent home — the persona surface over the §7D runtime (SPEC.md §7E).
 *
 * A persona is an identity that lives in **files**, keeps durable **memory** it can
 * edit, and runs on a **heartbeat** so it can act unprompted. All three ride shipped
 * seams — the composed soul is just a system prompt, the memory tool is a plain
 * `Tool`, and the heartbeat is `post`+`wake` on the runtime's injectable clock — so
 * this layer adds *no* new runtime behavior. It is convention (a directory layout)
 * and composition (recipes over the six verbs), not new machinery.
 *
 *   import { agents } from "toolnexus"
 *   const ava = agents.fromDir("./personas/ava")
 *   const started = agents.startAgent(ava, { fetch, clock }, { everyMs: 30 * 60_000 })
 */
import fs from "node:fs"
import path from "node:path"
import { defineTool } from "../native.js"
import type { Tool } from "../types.js"
import { agent, Agent, type AgentRunOptions } from "./agent.js"
import { AgentRuntime, isVerbError } from "./runtime.js"

/**
 * Bootstrap discovery order (§7E): identity first, durable memory last. Each present
 * file is injected into the soul as a `## <filename>` section, in exactly this order.
 */
export const BOOTSTRAP_ORDER = [
  "AGENTS.md",
  "SOUL.md",
  "IDENTITY.md",
  "USER.md",
  "TOOLS.md",
  "HEARTBEAT.md",
  "MEMORY.md",
] as const

/** Per-file bootstrap cap (§7E): a larger file is injected truncated, disk untouched. */
export const MAX_FILE_BYTES = 2 * 1024 * 1024

/** Notice appended to a bootstrap file truncated at {@link MAX_FILE_BYTES}. */
export const TRUNCATION_NOTICE = "\n[truncated: exceeds 2 MB bootstrap cap]"

/** The silent-no-op sentinel: a heartbeat reply containing this surfaces nothing. */
export const HEARTBEAT_OK = "HEARTBEAT_OK"

/** The prompt a heartbeat wakes the persona with. Contains `HEARTBEAT_OK` so the
 * model knows the silent-reply contract, and the word "Heartbeat" so a HEARTBEAT.md
 * that keys off it can recognise the trigger. */
export const HEARTBEAT_PROMPT =
  `Heartbeat. Read your HEARTBEAT.md section and follow it. ` +
  `If nothing needs attention, reply ${HEARTBEAT_OK}.`

/** Read a bootstrap file with the byte cap applied; `undefined` if it is absent. */
function readCapped(file: string): string | undefined {
  let buf: Buffer
  try {
    buf = fs.readFileSync(file)
  } catch {
    return undefined // absent files are skipped (§7E)
  }
  if (buf.byteLength > MAX_FILE_BYTES) {
    return buf.subarray(0, MAX_FILE_BYTES).toString("utf8") + TRUNCATION_NOTICE
  }
  return buf.toString("utf8")
}

/**
 * Compose the bootstrap files present in `dir` into one soul string — the frozen
 * snapshot injected as the system prompt for the whole run. Only present files
 * appear, always in {@link BOOTSTRAP_ORDER}, each as a `## <filename>` section.
 */
export function composeSoul(dir: string): { soul: string; found: string[] } {
  const sections: string[] = []
  const found: string[] = []
  for (const file of BOOTSTRAP_ORDER) {
    const body = readCapped(path.join(dir, file))
    if (body !== undefined) {
      sections.push(`## ${file}\n\n${body.trim()}`)
      found.push(file)
    }
  }
  return { soul: sections.join("\n\n"), found }
}

/** Which memory file an action targets. `self` → MEMORY.md, `user` → USER.md. */
type MemoryTarget = "self" | "user"

function memoryFile(dir: string, target: unknown): string {
  return path.join(dir, target === "user" ? "USER.md" : "MEMORY.md")
}

/**
 * The `memory` builtin (§7E, file-backed, opt-in). One tool, three actions over
 * `MEMORY.md` (the agent's own notes) and `USER.md` (its model of the user):
 *
 *   - `add`     — append an entry
 *   - `replace` — swap an existing substring for `with`
 *   - `remove`  — delete an existing substring
 *
 * Every action writes to **disk**. It does **not** touch the live session's system
 * prompt: the frozen-snapshot rule means the edit loads only at the START of the
 * next session (keeping a long-lived persona cache-stable). A `replace`/`remove`
 * whose substring is absent is a loud `isError` — never a silent no-op.
 */
export function memoryTool(dir: string): Tool {
  return defineTool({
    name: "memory",
    description:
      "Persist durable memory. action=add appends an entry; replace swaps an " +
      "existing substring (with); remove deletes one. target=self (MEMORY.md, " +
      "default) or user (USER.md). Writes persist to disk and load at the START of " +
      "your NEXT session — they do NOT change your current context.",
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["add", "replace", "remove"] },
        target: { type: "string", enum: ["self", "user"], description: "self=MEMORY.md (default), user=USER.md" },
        text: { type: "string", description: "For add: the entry. For replace/remove: the existing text." },
        with: { type: "string", description: "For replace: the replacement text." },
      },
      required: ["action", "text"],
    },
    run: (args: Record<string, unknown>) => {
      const target = (args.target as MemoryTarget) ?? "self"
      const file = memoryFile(dir, target)
      const text = String(args.text)
      const current = readCapped(file) ?? ""
      let next: string
      switch (args.action) {
        case "add":
          next = (current.trimEnd() + `\n- ${text}\n`).trimStart()
          break
        case "replace":
          if (!current.includes(text)) return { output: `not found: ${text}`, isError: true }
          next = current.replace(text, String(args.with ?? ""))
          break
        case "remove":
          if (!current.includes(text)) return { output: `not found: ${text}`, isError: true }
          next = current.replace(text, "")
          break
        default:
          return { output: `unknown action: ${String(args.action)}`, isError: true }
      }
      fs.mkdirSync(path.dirname(file), { recursive: true })
      fs.writeFileSync(file, next)
      return `ok (${String(args.action)} → ${path.basename(file)}); loads next session`
    },
  })
}

/** Options for {@link fromDir}. */
export interface FromDirOptions {
  /** Routing description a delegating model sees; default derived from the dir. */
  does?: string
  /** Agent name; default the directory's basename. */
  name?: string
  /** Model id; default `"inherit"` (the runtime's `llm.model`). */
  model?: string
  /** Extra tools beyond the memory builtin. */
  tools?: Tool[]
  /** Set `false` to omit the memory tool (a read-only persona). */
  memory?: boolean
}

/**
 * The directory IS the agent (§7E). Discovers the bootstrap files in `dir`, composes
 * them into a frozen soul snapshot, and (unless `memory:false`) wires a `memory` tool
 * over the same directory. Returns a plain {@link Agent} — `run`/`asTool`/`startAgent`
 * all apply.
 */
export function fromDir(dir: string, opts: FromDirOptions = {}): Agent {
  const { soul } = composeSoul(dir)
  const tools = [...(opts.tools ?? [])]
  if (opts.memory !== false) tools.push(memoryTool(dir))
  return agent(opts.name ?? path.basename(dir), {
    does: opts.does ?? `persona agent from ${dir}`,
    soul,
    model: opts.model ?? "inherit",
    uses: { tools },
  })
}

/** Heartbeat configuration for {@link startAgent}. */
export interface HeartbeatOptions {
  /** Interval between beats, in ms (measured on the runtime's clock). */
  everyMs: number
  /** Called for each SUBSTANTIVE beat — a `HEARTBEAT_OK` reply is stripped and never
   * surfaces here. */
  onBeat?: (text: string) => void
}

/** A running persona: the live runtime, the collected substantive beats, and `stop`. */
export interface StartedAgent {
  /** The runtime the persona runs on — expose so a host can `post`/`wake` it (channels). */
  runtime: AgentRuntime
  /** Substantive beats collected so far (HEARTBEAT_OK replies excluded). */
  beats: string[]
  /** Cancel the heartbeat and close the tree gracefully. */
  stop: () => Promise<void>
}

/**
 * Give a persona its own clock (§7E). On each interval it `post`s a tick to its own
 * inbox (the unsolicited rail — ticks **coalesce**, so a slow beat can't pile up) and,
 * when idle, `wake`s it with {@link HEARTBEAT_PROMPT}. A reply containing
 * {@link HEARTBEAT_OK} is treated as silent (no `onBeat`); only a substantive reply
 * surfaces. All timing goes through the runtime's injectable clock, so a virtual-clock
 * test is deterministic.
 *
 * Inbound channels are the host's job: deliver external events by calling
 * `started.runtime.post`/`wake` on the persona's handle.
 */
export function startAgent(a: Agent, run: AgentRunOptions, hb: HeartbeatOptions): StartedAgent {
  const runtime = a.createRuntime(run)
  const handle = runtime.spawn(runtime.root, a.name)
  if (isVerbError(handle)) throw new Error(handle.error)

  const beats: string[] = []
  let stopped = false
  let cancel: (() => void) | undefined

  const beat = (): void => {
    if (stopped) return
    // Unsolicited rail: the tick lands in the inbox (coalesces); a wake — only when
    // idle — drains the whole inbox as one turn.
    runtime.post(handle, { from: "clock", channel: "timer", text: "tick" })
    if (handle.state === "idle") {
      const woke = runtime.wake(handle, HEARTBEAT_PROMPT)
      if (woke.ok) {
        void runtime.wait(handle).then((r) => {
          if (!r.isError && !r.text.includes(HEARTBEAT_OK)) {
            beats.push(r.text)
            hb.onBeat?.(r.text)
          }
        })
      }
    }
    cancel = runtime.clock.setTimeout(beat, hb.everyMs) // self-reschedule → one live timer
  }
  cancel = runtime.clock.setTimeout(beat, hb.everyMs)

  return {
    runtime,
    beats,
    stop: async () => {
      stopped = true
      cancel?.()
      await runtime.close(runtime.root)
    },
  }
}
