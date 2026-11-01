/**
 * Opt-in builtins for coding-agent hosts (SPEC §4C): background shells (`bash`
 * run_in_background + `bash_output` + `kill_shell`), `websearch` over a host-supplied provider,
 * and `notebook_edit`. None is on by default: a host that sets nothing sees the same ten builtins.
 */
import { spawn, spawnSync, type ChildProcess } from "node:child_process"
import type { ToolResult } from "./types.js"

const err = (output: string, metadata?: Record<string, unknown>): ToolResult => ({ output, isError: true, metadata })
const ok = (output: string, metadata?: Record<string, unknown>): ToolResult => ({ output, isError: false, metadata })

// ---------------------------------------------------------------------------
// background shells
// ---------------------------------------------------------------------------

/** One background command, for a host UI. */
export interface BackgroundShellInfo {
  id: string
  command: string
  started: Date
  running: boolean
  /** Valid when not running; -1 when killed by a signal. */
  exitCode: number
  killed: boolean
}

interface BgJob {
  id: string
  command: string
  started: Date
  child: ChildProcess
  pgid?: number
  grace: number
  out: string
  read: number
  running: boolean
  exitCode: number
  killed: boolean
  done: Promise<void>
}

/**
 * Owns the commands `bash` started with `run_in_background`. Pass one as
 * `builtins.background`, and call `closeAll()` when the host shuts down — a background command
 * is NOT tied to the turn that started it, so nothing else stops it.
 */
export class BackgroundShells {
  private next = 0
  private readonly jobs = new Map<string, BgJob>()

  /** @internal Start `command` under `argv` in its own process group. */
  start(argv: string[], command: string, workdir: string, grace: number): BgJob {
    const child = spawn(argv[0], [...argv.slice(1), command], { cwd: workdir, detached: process.platform !== "win32" })
    let resolveDone!: () => void
    const done = new Promise<void>((r) => (resolveDone = r))
    const j: BgJob = {
      id: `bg${++this.next}`, command, started: new Date(), child, pgid: child.pid, grace,
      out: "", read: 0, running: true, exitCode: 0, killed: false, done,
    }
    child.stdout?.on("data", (d) => (j.out += d.toString()))
    child.stderr?.on("data", (d) => (j.out += d.toString()))
    const finish = (code: number | null) => {
      if (!j.running) return
      j.running = false
      j.exitCode = code ?? -1
      resolveDone()
    }
    child.on("error", (e) => {
      j.out += `bash: ${e.message}\n`
      finish(-1)
    })
    child.on("close", (code) => finish(code))
    // A background job must never keep the host's event loop alive on its own.
    child.unref()
    ;(child.stdout as any)?.unref?.()
    ;(child.stderr as any)?.unref?.()
    this.jobs.set(j.id, j)
    return j
  }

  /** @internal */
  get(id: string): BgJob | undefined {
    return this.jobs.get(id)
  }

  private signal(j: BgJob, graceful: boolean): void {
    const pgid = j.pgid
    if (typeof pgid !== "number" || pgid <= 1 || !j.running) return
    try {
      if (process.platform === "win32") spawnSync("taskkill", ["/T", "/F", "/PID", String(pgid)])
      else process.kill(-pgid, graceful ? "SIGTERM" : "SIGKILL")
    } catch {
      /* already gone */
    }
  }

  /** @internal Stop the job's whole process group: SIGTERM, the grace window, SIGKILL.
   * Resolves whether it was still running. */
  async kill(j: BgJob): Promise<boolean> {
    if (!j.running) return false
    j.killed = true
    // The child and its pipes are unref'd so an idle job never pins the host. While we are
    // WAITING on it, though, they must be ref'd: otherwise, once the grace timer is cleared,
    // nothing holds the event loop open until 'close' fires and Node exits with this promise
    // still pending (seen on Linux, where 'close' lands an iteration later than on macOS).
    j.child.ref()
    ;(j.child.stdout as any)?.ref?.()
    ;(j.child.stderr as any)?.ref?.()
    this.signal(j, true)
    let t: NodeJS.Timeout | undefined
    const timedOut = await Promise.race([j.done.then(() => false), new Promise<boolean>((r) => (t = setTimeout(() => r(true), j.grace)))])
    if (t) clearTimeout(t)
    if (timedOut) {
      this.signal(j, false)
      await j.done
    }
    return true
  }

  static info(j: BgJob): BackgroundShellInfo {
    return { id: j.id, command: j.command, started: j.started, running: j.running, exitCode: j.exitCode, killed: j.killed }
  }

  /** Every background command, oldest first. */
  list(): BackgroundShellInfo[] {
    return [...this.jobs.values()].sort((a, b) => a.started.getTime() - b.started.getTime()).map(BackgroundShells.info)
  }

  /** Stop one background command's process group. Unknown id ⇒ throws. */
  async killShell(id: string): Promise<void> {
    const j = this.jobs.get(id)
    if (!j) throw new Error(`no background shell "${id}"`)
    await this.kill(j)
  }

  /** Stop every background command still running, concurrently. */
  async closeAll(): Promise<void> {
    await Promise.all([...this.jobs.values()].map((j) => this.kill(j)))
  }
}

export function bgStatus(i: BackgroundShellInfo): string {
  if (i.running) return "running"
  if (i.killed) return "killed"
  return `exited with code ${i.exitCode}`
}

/** `bash_output(id)`. */
export function bashOutput(bg: BackgroundShells, id: string): ToolResult {
  const j = bg.get(id)
  if (!j) return err(`bash_output: no background shell "${id}"`)
  const fresh = j.out.slice(j.read)
  j.read = j.out.length
  const info = BackgroundShells.info(j)
  const status = bgStatus(info)
  const meta: Record<string, unknown> = { id, status: status.split(" ")[0] }
  if (!info.running && !info.killed) meta.exitCode = info.exitCode
  return ok(`${fresh}\n[status: ${status}]`, meta)
}

/** `kill_shell(id)`. */
export async function killShell(bg: BackgroundShells, id: string): Promise<ToolResult> {
  const j = bg.get(id)
  if (!j) return err(`kill_shell: no background shell "${id}"`)
  if (!(await bg.kill(j))) {
    return ok(`Background shell ${id} had already ${bgStatus(BackgroundShells.info(j))}`, { id, killed: false })
  }
  return ok(`Killed background shell ${id}`, { id, killed: true })
}

// ---------------------------------------------------------------------------
// websearch
// ---------------------------------------------------------------------------

/** One hit from a host search provider. */
export interface WebSearchResult {
  title: string
  url: string
  snippet?: string
}

/** The host's search provider. The library ships none. */
export type WebSearchFunc = (query: string, limit: number, ctx: { signal?: AbortSignal }) => Promise<WebSearchResult[]>

export async function runWebSearch(search: WebSearchFunc, args: Record<string, unknown>, signal?: AbortSignal): Promise<ToolResult> {
  const q = typeof args.query === "string" ? args.query : ""
  if (!q.trim()) return err("websearch: query is required")
  const limit = typeof args.limit === "number" && args.limit >= 1 ? Math.trunc(args.limit) : 5
  let results: WebSearchResult[]
  try {
    results = await search(q, limit, { signal })
  } catch (e) {
    return err(`websearch: ${e instanceof Error ? e.message : String(e)}`)
  }
  results = (results ?? []).slice(0, limit)
  if (results.length === 0) return ok("No results.", { count: 0 })
  const text = results
    .map((r, i) => `${i + 1}. ${r.title}\n   ${r.url}` + (r.snippet ? `\n   ${r.snippet}` : ""))
    .join("\n\n")
  return ok(text, { count: results.length, results: results.map((r) => ({ title: r.title, url: r.url, snippet: r.snippet ?? "" })) })
}

// ---------------------------------------------------------------------------
// notebook_edit
// ---------------------------------------------------------------------------

/** Split text into nbformat's list of lines, each keeping its "\n". */
export function notebookSource(s: string): string[] {
  if (!s) return []
  return s.split(/(?<=\n)/).filter((p) => p !== "")
}

/** Go's json.MarshalIndent(v, "", " ") with sorted keys, plus Go's HTML escaping. */
export function goJsonIndent(v: unknown): string {
  const sort = (x: unknown): unknown => {
    if (Array.isArray(x)) return x.map(sort)
    if (x && typeof x === "object") {
      const out: Record<string, unknown> = {}
      for (const k of Object.keys(x as object).sort()) out[k] = sort((x as Record<string, unknown>)[k])
      return out
    }
    return x
  }
  return JSON.stringify(sort(v), null, " ")
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(new RegExp(String.fromCharCode(0x2028), "g"), "\\u2028")
    .replace(new RegExp(String.fromCharCode(0x2029), "g"), "\\u2029")
}

/** Apply one notebook_edit to the parsed notebook, in place. Returns the summary, or an error result. */
export function editNotebook(nb: Record<string, any>, args: Record<string, unknown>, p: string): { summary: string } | ToolResult {
  let cells: any[] = Array.isArray(nb.cells) ? nb.cells : []
  const mode = typeof args.edit_mode === "string" && args.edit_mode ? args.edit_mode : "replace"
  let idx = -1
  if (typeof args.cell_id === "string" && args.cell_id) {
    idx = cells.findIndex((c) => c && typeof c === "object" && c.id === args.cell_id)
    if (idx < 0) return err(`notebook_edit: no cell with id "${args.cell_id}"`)
  } else if (typeof args.cell_index === "number") {
    idx = Math.trunc(args.cell_index)
    if (idx < 0 || idx >= cells.length) return err(`notebook_edit: cell_index ${idx} out of range (0-${cells.length - 1})`)
  } else if (mode !== "insert") {
    return err("notebook_edit: cell_id or cell_index is required")
  }
  const src = typeof args.new_source === "string" ? args.new_source : ""
  let cellType = typeof args.cell_type === "string" ? args.cell_type : ""
  let summary: string
  switch (mode) {
    case "replace": {
      const cm = cells[idx]
      cm.source = notebookSource(src)
      if (cellType) cm.cell_type = cellType
      if (cm.cell_type === "code") {
        cm.outputs = []
        cm.execution_count = null
      } else {
        delete cm.outputs
        delete cm.execution_count
      }
      summary = `Replaced cell ${idx} in ${p}`
      break
    }
    case "insert": {
      if (!cellType) cellType = "code"
      const cell: Record<string, unknown> = { cell_type: cellType, metadata: {}, source: notebookSource(src) }
      if (cellType === "code") {
        cell.outputs = []
        cell.execution_count = null
      }
      const at = idx + 1
      cells = [...cells.slice(0, at), cell, ...cells.slice(at)]
      summary = `Inserted ${cellType} cell at ${at} in ${p}`
      break
    }
    case "delete":
      cells = [...cells.slice(0, idx), ...cells.slice(idx + 1)]
      summary = `Deleted cell ${idx} from ${p}`
      break
    default:
      return err(`notebook_edit: unknown edit_mode "${mode}"`)
  }
  nb.cells = cells
  return { summary }
}
