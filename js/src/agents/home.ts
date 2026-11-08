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
import { randomBytes } from "node:crypto"
import { defineTool } from "../native.js"
import type { Tool } from "../types.js"
import { agent, Agent, type AgentRunOptions } from "./agent.js"
import { AgentRuntime, isVerbError, type Handle } from "./runtime.js"
import type { ConversationStore } from "../client.js"
import { transcriptSearchTool } from "../transcript_search.js"

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

/** The preset index cap (§7E "Memory hierarchy"): Claude Code's 200 lines / 25 KB. */
export const MEMORY_INDEX_CAP_PRESET = { maxLines: 200, maxBytes: 25600 } as const

/** `memoryIndexCap` option: `true` ⇒ the preset; an object's missing field ⇒ the preset's. */
export type MemoryIndexCap = boolean | { maxLines?: number; maxBytes?: number }

/** Options for {@link composeSoul}. Absent ⇒ byte-identical to the pre-hierarchy soul. */
export interface ComposeSoulOptions {
  /** Second, smaller injection cap for `MEMORY.md` only (whole lines). Off by default. */
  memoryIndexCap?: MemoryIndexCap
  /** Notes tier on — selects the notice wording that points at `note:<slug>`. */
  notes?: boolean
}

function resolveIndexCap(c: MemoryIndexCap | undefined): { maxLines: number; maxBytes: number } | undefined {
  if (c === undefined || c === false) return undefined
  const v = c === true ? {} : c
  const maxLines = v.maxLines ?? MEMORY_INDEX_CAP_PRESET.maxLines
  const maxBytes = v.maxBytes ?? MEMORY_INDEX_CAP_PRESET.maxBytes
  if (!Number.isInteger(maxLines) || maxLines < 1 || !Number.isInteger(maxBytes) || maxBytes < 1) {
    throw new Error("memoryIndexCap: maxLines and maxBytes must be integers >= 1")
  }
  return { maxLines, maxBytes }
}

/** The exact notice appended to a capped `MEMORY.md` (§7E "Memory hierarchy"). */
export function memoryIndexNotice(maxLines: number, maxBytes: number, notes: boolean): string {
  const tail = notes ? `; move detail into notes (memory target "note:<slug>").` : ` (keep entries short).`
  return `\n\n[MEMORY.md index truncated at ${maxLines} lines / ${maxBytes} bytes. Keep MEMORY.md a short index${tail}]`
}

/** Cut a UTF-8 string at the last character boundary whose byte length is <= `max`. */
function cutUtf8(s: string, max: number): string {
  let out = ""
  let bytes = 0
  for (const ch of s) {
    const n = Buffer.byteLength(ch)
    if (bytes + n > max) break
    out += ch
    bytes += n
  }
  return out
}

/**
 * Apply the index cap to `MEMORY.md`'s (already 2 MB-capped) content. Lines are split on
 * "\n"; one trailing "\n" does not start a line. Within both bounds ⇒ returned unchanged.
 * Otherwise keep the first `maxLines` lines, drop trailing whole lines until the "\n"-joined
 * UTF-8 length is <= `maxBytes` (a lone over-long first line is cut at a character
 * boundary), and append {@link memoryIndexNotice}.
 */
export function applyIndexCap(content: string, cap: { maxLines: number; maxBytes: number }, notes = false): string {
  const lines = content.split("\n")
  if (content.endsWith("\n")) lines.pop()
  if (lines.length <= cap.maxLines && Buffer.byteLength(lines.join("\n")) <= cap.maxBytes) return content
  const kept = lines.slice(0, cap.maxLines)
  while (kept.length > 1 && Buffer.byteLength(kept.join("\n")) > cap.maxBytes) kept.pop()
  let text = kept.join("\n")
  if (Buffer.byteLength(text) > cap.maxBytes) text = cutUtf8(text, cap.maxBytes)
  return text + memoryIndexNotice(cap.maxLines, cap.maxBytes, notes)
}

/**
 * Compose the bootstrap files present in `dir` into one soul string — the frozen
 * snapshot injected as the system prompt for the whole run. Only present files
 * appear, always in {@link BOOTSTRAP_ORDER}, each as a `## <filename>` section.
 */
export function composeSoul(dir: string, opts: ComposeSoulOptions = {}): { soul: string; found: string[] } {
  const sections: string[] = []
  const found: string[] = []
  const cap = resolveIndexCap(opts.memoryIndexCap)
  for (const file of BOOTSTRAP_ORDER) {
    let body = readCapped(path.join(dir, file))
    if (body !== undefined && cap && file === "MEMORY.md") body = applyIndexCap(body, cap, opts.notes === true)
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

/** A valid note slug (§7E "Memory hierarchy"): lowercase, no path separators, no `..`. */
export const NOTE_SLUG_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/

/** Options for {@link memoryTool}. */
export interface MemoryToolOptions {
  /** Turn on the notes tier: `note:<slug>` targets plus `read`/`list`. Off ⇒ the tool is
   * byte-identical to the pre-hierarchy tool (schema, description, behavior). */
  notes?: boolean
}

const MEMORY_DESCRIPTION =
  "Persist durable memory. action=add appends an entry; replace swaps an " +
  "existing substring (with); remove deletes one. target=self (MEMORY.md, " +
  "default) or user (USER.md). Writes persist to disk and load at the START of " +
  "your NEXT session — they do NOT change your current context."

/** The notes-on description (§7E): says what memory is FOR, not rules about what to record. */
export const MEMORY_NOTES_DESCRIPTION =
  "Persist durable memory. MEMORY.md is a short index loaded at the start of every " +
  "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never " +
  "loaded automatically) and point to them from the index. Record what will change a " +
  "future decision. action=add appends an entry; replace swaps the first occurrence of " +
  "an existing substring (with); remove deletes it; read returns a file; list lists the " +
  "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: " +
  "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the " +
  "START of your NEXT session — they do NOT change your current context."

/**
 * Atomic replace (§7E write safety): temp file in the same dir, flushed (fsync), renamed
 * over the target; an existing file's permission bits are kept.
 */
function atomicWrite(file: string, data: string): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`
  try {
    const fd = fs.openSync(tmp, "w") // new file: 0666 & ~umask
    try {
      fs.writeFileSync(fd, data)
      fs.fsyncSync(fd)
    } finally {
      fs.closeSync(fd)
    }
    // Keep an existing file's permission bits (the rename would otherwise give it the temp's).
    if (process.platform !== "win32" && fs.existsSync(file)) {
      fs.chmodSync(tmp, fs.statSync(file).mode & 0o777)
    }
    fs.renameSync(tmp, file)
  } catch (e) {
    fs.rmSync(tmp, { force: true })
    throw e
  }
}

/** Read-modify-write over the FULL file (never the capped view). In JS the whole RMW is
 * synchronous, so it cannot interleave with another call in this runtime — the per-path
 * serialization §7E requires holds by construction. */
function rmw(file: string, action: unknown, text: string, withText: unknown): { output: string; isError: true } | undefined {
  const current = fs.existsSync(file) ? fs.readFileSync(file, "utf8") : ""
  let next: string
  switch (action) {
    case "add":
      next = (current.trimEnd() + `\n- ${text}\n`).trimStart()
      break
    case "replace":
      if (!current.includes(text)) return { output: `not found: ${text}`, isError: true }
      // First occurrence only, inserted LITERALLY (§7E): a function replacement, because a
      // string one would expand `$&`, `$1`, `$$` … in the model's text.
      next = current.replace(text, () => String(withText ?? ""))
      break
    case "remove":
      if (!current.includes(text)) return { output: `not found: ${text}`, isError: true }
      next = current.replace(text, () => "") // first occurrence only (§7E)
      break
    default:
      return { output: `unknown action: ${String(action)}`, isError: true }
  }
  atomicWrite(file, next)
  return undefined
}

/**
 * The `memory` builtin (§7E, file-backed, opt-in). One tool, three actions over
 * `MEMORY.md` (the agent's own notes) and `USER.md` (its model of the user):
 *
 *   - `add`     — append an entry
 *   - `replace` — swap the first occurrence of an existing substring for `with`
 *   - `remove`  — delete the first occurrence of an existing substring
 *
 * With `{notes: true}` it also takes `target: "note:<slug>"` (`<dir>/memory/<slug>.md`,
 * never injected into the soul) and two read actions, `read` and `list`.
 *
 * Every action writes to **disk**. It does **not** touch the live session's system
 * prompt: the frozen-snapshot rule means the edit loads only at the START of the
 * next session (keeping a long-lived persona cache-stable). A `replace`/`remove`
 * whose substring is absent is a loud `isError` — never a silent no-op.
 */
export function memoryTool(dir: string, opts: MemoryToolOptions = {}): Tool {
  if (opts.notes === true) return notesMemoryTool(dir)
  return defineTool({
    name: "memory",
    description: MEMORY_DESCRIPTION,
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
      const err = rmw(file, args.action, String(args.text), args.with)
      if (err) return err
      return `ok (${String(args.action)} → ${path.basename(file)}); loads next session`
    },
  })
}

function notesMemoryTool(dir: string): Tool {
  const notesDir = path.join(dir, "memory")
  return defineTool({
    name: "memory",
    description: MEMORY_NOTES_DESCRIPTION,
    inputSchema: {
      type: "object",
      properties: {
        action: { type: "string", enum: ["add", "replace", "remove", "read", "list"] },
        target: {
          type: "string",
          description: "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md",
        },
        text: { type: "string", description: "For add: the entry. For replace/remove: the existing text." },
        with: { type: "string", description: "For replace: the replacement text." },
      },
      required: ["action"],
    },
    run: (args: Record<string, unknown>) => {
      const action = args.action
      if (action === "list") return listNotes(notesDir)
      const target = args.target === undefined ? "self" : String(args.target)
      let file: string
      let label: string
      if (target === "self" || target === "user") {
        file = memoryFile(dir, target)
        label = path.basename(file)
      } else if (target.startsWith("note:")) {
        const slug = target.slice(5)
        if (!NOTE_SLUG_RE.test(slug) || slug.includes("..")) {
          return { output: `invalid note slug: ${slug}`, isError: true }
        }
        file = path.join(notesDir, `${slug}.md`)
        label = `memory/${slug}.md`
      } else {
        return { output: `unknown target: ${target}`, isError: true }
      }
      if (action === "read") {
        const body = readCapped(file)
        if (body === undefined) return { output: `not found: ${target}`, isError: true }
        return body
      }
      if (action !== "add" && action !== "replace" && action !== "remove") {
        return { output: `unknown action: ${String(action)}`, isError: true }
      }
      if (typeof args.text !== "string") return { output: "text is required", isError: true }
      const err = rmw(file, action, args.text, args.with)
      if (err) return err
      return label.startsWith("memory/") ? `ok (${action} → ${label})` : `ok (${action} → ${label}); loads next session`
    },
  })
}

/** `list` output (§7E): `<slug>\t<bytes>` per note, slug-ascending (explicit sort). */
function listNotes(notesDir: string): string {
  let names: string[]
  try {
    names = fs.readdirSync(notesDir)
  } catch {
    names = []
  }
  const rows: Array<[string, number]> = []
  for (const name of names) {
    if (!name.endsWith(".md")) continue
    const slug = name.slice(0, -3)
    if (!NOTE_SLUG_RE.test(slug) || slug.includes("..")) continue
    // lstat: a symlink (dangling or not) is not a regular file and is skipped (§7E).
    let st: fs.Stats
    try {
      st = fs.lstatSync(path.join(notesDir, name))
    } catch {
      continue
    }
    if (st.isFile()) rows.push([slug, st.size])
  }
  rows.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
  return rows.length === 0 ? "(no notes)" : rows.map(([s, n]) => `${s}\t${n}`).join("\n")
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
  /** Cap the injected `MEMORY.md` (whole lines; `true` ⇒ 200 lines / 25600 bytes). Off by default. */
  memoryIndexCap?: MemoryIndexCap
  /** Turn on the notes tier of the memory tool (`note:<slug>`, `read`, `list`). Off by default. */
  notes?: boolean
  /** Dream on a schedule: when this persona is started with {@link startAgent}, a
   * {@link dreamer} over the same dir runs on the same runtime every `everyMs`. */
  dream?: { everyMs: number; store: ConversationStore; instructions?: string }
}

/** Dream config attached by `fromDir(…, {dream})`, read by {@link startAgent}. */
const DREAMS = new WeakMap<Agent, { dreamer: Agent; everyMs: number }>()

/**
 * The directory IS the agent (§7E). Discovers the bootstrap files in `dir`, composes
 * them into a frozen soul snapshot, and (unless `memory:false`) wires a `memory` tool
 * over the same directory. Returns a plain {@link Agent} — `run`/`asTool`/`startAgent`
 * all apply.
 */
export function fromDir(dir: string, opts: FromDirOptions = {}): Agent {
  const { soul } = composeSoul(dir, { memoryIndexCap: opts.memoryIndexCap, notes: opts.notes })
  const tools = [...(opts.tools ?? [])]
  if (opts.memory !== false) tools.push(memoryTool(dir, { notes: opts.notes }))
  const name = opts.name ?? path.basename(dir)
  const persona = agent(name, {
    does: opts.does ?? `persona agent from ${dir}`,
    soul,
    model: opts.model ?? "inherit",
    uses: { tools },
  })
  if (opts.dream !== undefined) {
    const { everyMs, store, instructions } = opts.dream
    if (!(Number.isFinite(everyMs) && everyMs > 0)) throw new Error("fromDir: dream.everyMs must be > 0")
    DREAMS.set(persona, { dreamer: dreamer(dir, { store, instructions, name: `${name}-dreamer` }), everyMs })
  }
  return persona
}

/**
 * What a dream pass is FOR and the order it works in (§7E "Dreaming"). Byte-identical in
 * every port. It becomes the dreamer's `## HEARTBEAT.md` section, so the ordinary
 * {@link HEARTBEAT_PROMPT} wake drives a pass and `HEARTBEAT_OK` keeps an idle pass silent.
 */
export const DREAM_PROMPT = [
  "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.",
  "Work in this order:",
  "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix a claim the transcripts contradict; remove one they no longer support.",
  "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.",
  "3. Merge: fold duplicate or conflicting notes into one.",
  "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.",
  "5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.",
  "If nothing needs changing, write nothing and reply HEARTBEAT_OK.",
].join("\n")

/** Options for {@link dreamer}. */
export interface DreamerOptions {
  /** The transcripts to verify memory against — must implement `list()`. Required. */
  store: ConversationStore
  /** Host prose appended after {@link DREAM_PROMPT} (and after `DREAM.md`, if present). */
  instructions?: string
  /** Agent name; default `<basename(dir)>-dreamer`. */
  name?: string
  /** Model id; default `"inherit"` — the persona's client (use a cheap model there). */
  model?: string
}

/**
 * The built-in dreamer preset (§7E "Dreaming"): a plain {@link Agent} whose tools are
 * EXACTLY `memory` (notes on, over `dir`) and `transcript_search` (over `store`) — never
 * the persona's shell/file tools. Its soul is one `## HEARTBEAT.md` section holding
 * {@link DREAM_PROMPT}, then `DREAM.md` from `dir` (trimmed, if non-empty), then
 * `instructions` (trimmed, if non-empty), joined by blank lines. Schedule it with
 * {@link startAgent} or run one pass with `run(HEARTBEAT_PROMPT, …)`.
 */
export function dreamer(dir: string, opts: DreamerOptions): Agent {
  if (!opts || !opts.store) throw new Error("dreamer: store is required")
  const search = transcriptSearchTool(opts.store)
  const parts = [DREAM_PROMPT]
  const dreamMd = readCapped(path.join(dir, "DREAM.md"))?.trim()
  if (dreamMd) parts.push(dreamMd)
  const extra = opts.instructions?.trim()
  if (extra) parts.push(extra)
  return agent(opts.name ?? `${path.basename(dir)}-dreamer`, {
    does: `dreams over the memory in ${dir}`,
    soul: `## HEARTBEAT.md\n\n${parts.join("\n\n")}`,
    model: opts.model ?? "inherit",
    uses: { tools: [memoryTool(dir, { notes: true }), search] },
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
  const dream = DREAMS.get(a)
  if (dream && Object.prototype.hasOwnProperty.call(a.registry(), dream.dreamer.name)) {
    throw new Error(`startAgent: dreamer name collision: ${dream.dreamer.name} is already registered`)
  }
  const runtime = dream
    ? new AgentRuntime({ ...run, registry: { ...dream.dreamer.registry(), ...a.registry() } })
    : a.createRuntime(run)
  const handle = runtime.spawn(runtime.root, a.name)
  if (isVerbError(handle)) throw new Error(handle.error)

  const beats: string[] = []
  const cancels: Array<() => void> = []
  let stopped = false
  cancels.push(
    heartbeat(runtime, handle, hb.everyMs, () => stopped, (text) => {
      beats.push(text)
      hb.onBeat?.(text)
    }),
  )
  if (dream) {
    // fromDir(…, {dream}): the dreamer runs on the SAME runtime on its own interval. Its
    // replies are housekeeping — they never reach onBeat; what it did is in memory/log.md.
    const dh = runtime.spawn(runtime.root, dream.dreamer.name)
    if (isVerbError(dh)) {
      // Never leak the persona's heartbeat when the dreamer cannot start.
      stopped = true
      for (const c of cancels) c()
      void runtime.close(runtime.root)
      throw new Error(dh.error)
    }
    cancels.push(heartbeat(runtime, dh, dream.everyMs, () => stopped, () => {}))
  }

  return {
    runtime,
    beats,
    stop: async () => {
      stopped = true
      for (const c of cancels) c()
      await runtime.close(runtime.root)
    },
  }
}

/** One self-rescheduling heartbeat on `handle`; returns its cancel. */
function heartbeat(
  runtime: AgentRuntime,
  handle: Handle,
  everyMs: number,
  isStopped: () => boolean,
  onText: (text: string) => void,
): () => void {
  let cancel: (() => void) | undefined
  const beat = (): void => {
    if (isStopped()) return
    // Unsolicited rail: the tick lands in the inbox (coalesces); a wake — only when
    // idle — drains the whole inbox as one turn.
    runtime.post(handle, { from: "clock", channel: "timer", text: "tick" })
    if (handle.state === "idle") {
      const woke = runtime.wake(handle, HEARTBEAT_PROMPT)
      if (woke.ok) {
        void runtime.wait(handle).then((r) => {
          if (!r.isError && !r.text.includes(HEARTBEAT_OK)) onText(r.text)
        })
      }
    }
    // keepAlive:false — a heartbeat is housekeeping and must never be the reason
    // a host process stays up.
    cancel = runtime.clock.setTimeout(beat, everyMs, { keepAlive: false }) // self-reschedule → one live timer
  }
  cancel = runtime.clock.setTimeout(beat, everyMs, { keepAlive: false })
  return () => cancel?.()
}
