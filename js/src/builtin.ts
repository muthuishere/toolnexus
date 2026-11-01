/**
 * Built-in tool source (source: "builtin"). The default toolset toolnexus ships
 * so an agent can act with zero custom wiring — opencode's built-ins, ported
 * with identical tool names + input schemas. See ../../SPEC.md §4A.
 *
 * Every tool obeys the uniform Tool/ToolResult contract: a failure is a
 * ToolResult{isError:true}, never a thrown exception across the boundary. Paths
 * resolve relative to the process working directory unless absolute.
 */
import { spawn, spawnSync } from "node:child_process"
import fs from "node:fs/promises"
import { existsSync, readdirSync, realpathSync } from "node:fs"
import { compareCodePoints, sortEntriesByName, toPosixPath } from "./order.js"
import path from "node:path"
import type { JSONSchema, Tool, ToolContext, ToolHints, ToolResult } from "./types.js"
import { pending } from "./types.js"
import { mediaTypeFor } from "./content.js"
import {
  BackgroundShells, bashOutput, killShell, runWebSearch, editNotebook, goJsonIndent, type WebSearchFunc,
} from "./builtin_extras.js"

/**
 * Config for the single global builtin toggle (mirrors MCP isEnabled precedence),
 * plus the host boundary: which interpreter `bash` runs, what a relative path
 * resolves against, and whether paths leaving it are refused (SPEC §4A, ADR 0034).
 */
export type BuiltinsConfig =
  | boolean
  | {
      enabled?: boolean
      disabled?: boolean
      tools?: Record<string, boolean>
      /**
       * The argv prefix `bash` runs a command with — `["sh","-c"]`,
       * `["cmd","/d","/s","/c"]`, `["powershell","-NoProfile","-Command"]`. Set,
       * it is used verbatim. Absent, an interpreter is DETECTED at toolkit
       * construction (POSIX `sh -c`; Windows %COMSPEC% → pwsh → powershell → bash).
       */
      shell?: string[]
      /**
       * What a RELATIVE path means, for every builtin that touches the
       * filesystem — including the paths inside apply_patch's patch text, and as
       * bash's default workdir. Empty ⇒ the process cwd, i.e. today's behaviour.
       */
      baseDir?: string
      /**
       * Refuse any path whose CANONICAL form leaves baseDir (symlinks, Windows
       * junctions and short names resolved on both sides), and Windows reserved
       * device names. Default off. A guarantee about path resolution in the file
       * builtins, NOT a sandbox — `bash` still reaches the whole filesystem.
       */
      confineToBaseDir?: boolean
      /**
       * POSIX wait in ms between SIGTERM and SIGKILL of a timed-out or cancelled
       * `bash` job's process group. Absent or 0 ⇒ 2000. No effect on Windows,
       * which has no graceful stop for a console process (SPEC §4A).
       */
      killGraceMs?: number
      /**
       * Opt-in (SPEC §4C): `bash` takes `run_in_background` and the `bash_output` / `kill_shell`
       * tools exist. The host owns the registry: call `closeAll()` on shutdown.
       */
      background?: BackgroundShells
      /** Opt-in (SPEC §4C): adds a `websearch` tool backed by this provider. The library ships none. */
      webSearch?: WebSearchFunc
      /** Opt-in (SPEC §4C): adds the `notebook_edit` tool for .ipynb cells. */
      notebookEdit?: boolean
    }

/**
 * How long a job gets between "please stop" and "stop" when `killGraceMs` is
 * not set. Identical in every port, so a timeout means the same thing everywhere.
 */
const DEFAULT_KILL_GRACE_MS = 2000

const WIN_RESERVED = new Set([
  "CON", "PRN", "AUX", "NUL",
  "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
  "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
])

/** Does `name` resolve as an executable on PATH? (PATHEXT-aware on Windows.) */
function resolvesOnPath(name: string): boolean {
  if (name.includes(path.sep) || name.includes("/")) return existsSync(name)
  const dirs = (process.env.PATH ?? "").split(path.delimiter).filter(Boolean)
  const exts =
    process.platform === "win32"
      ? (process.env.PATHEXT ?? ".COM;.EXE;.BAT;.CMD").split(";").filter(Boolean)
      : [""]
  for (const dir of dirs) {
    for (const ext of exts) {
      if (existsSync(path.join(dir, name + ext))) return true
    }
  }
  return false
}

/**
 * The interpreters tried when the host names none. %COMSPEC% leads on Windows
 * because PowerShell is routinely blocked by execution or application-control
 * policy, while %COMSPEC% is always present.
 */
function shellCandidates(): string[][] {
  // `/bin/sh` first, then a PATH lookup: `shell: true` means literally
  // `/bin/sh`, while spawning the bare name `sh` is a PATH lookup — two
  // different binaries on a machine that has both. Naming the absolute path
  // first is what makes dropping `shell: true` byte-identical here.
  if (process.platform !== "win32") return [["/bin/sh", "-c"], ["sh", "-c"]]
  const out: string[][] = []
  if (process.env.COMSPEC) out.push([process.env.COMSPEC, "/d", "/s", "/c"])
  out.push(
    ["cmd.exe", "/d", "/s", "/c"],
    ["pwsh", "-NoProfile", "-Command"],
    ["powershell", "-NoProfile", "-Command"],
    ["bash", "-lc"],
  )
  return out
}

/**
 * The host boundary the builtins are allowed to know about. Constructed once,
 * at toolkit construction: a missing interpreter is a configuration fact, and
 * turn fourteen of a paid run is the expensive place to learn it.
 */
class BuiltinEnv {
  readonly shell: string[]
  readonly shellError?: Error
  readonly baseDir: string
  readonly confine: boolean
  readonly killGraceMs: number
  readonly background?: BackgroundShells
  readonly webSearch?: WebSearchFunc
  readonly notebookEdit: boolean

  constructor(cfg?: BuiltinsConfig) {
    const obj = typeof cfg === "object" && cfg !== null ? cfg : {}
    this.background = obj.background
    this.webSearch = obj.webSearch
    this.notebookEdit = obj.notebookEdit === true
    this.killGraceMs = typeof obj.killGraceMs === "number" && obj.killGraceMs > 0 ? obj.killGraceMs : DEFAULT_KILL_GRACE_MS
    this.baseDir = obj.baseDir ?? ""
    this.confine = obj.confineToBaseDir === true
    if (obj.shell && obj.shell.length > 0) {
      this.shell = obj.shell
      return
    }
    const tried: string[] = []
    for (const argv of shellCandidates()) {
      tried.push(argv[0])
      if (resolvesOnPath(argv[0])) {
        this.shell = argv
        return
      }
    }
    this.shell = []
    this.shellError = new Error(
      `no shell interpreter found (tried: ${tried.join(", ")}); set builtins.shell, or disable the bash builtin with builtins.tools.bash = false`,
    )
  }

  /** The interpreter argv, or the detection error when there is none. */
  shellArgv(): string[] {
    if (this.shellError) throw this.shellError
    return this.shell
  }

  get shellLabel(): string {
    return this.shell.join(" ")
  }

  /** The directory a command or a walk starts from. */
  dir(): string {
    return this.baseDir || process.cwd()
  }

  /**
   * Map a tool-supplied path onto the filesystem: relative to baseDir (or, with
   * none, exactly as before), and refused when confinement is on and the
   * canonical target is outside the base.
   */
  resolvePath(p: string): string {
    if (this.confine && !this.baseDir) {
      throw new Error("confineToBaseDir is set but baseDir is empty")
    }
    const full = this.baseDir && !path.isAbsolute(p) ? path.join(this.baseDir, p) : p
    if (!this.confine) return full
    if (process.platform === "win32") {
      const base = path.basename(full).split(".")[0].trim().toUpperCase()
      if (WIN_RESERVED.has(base)) {
        throw new Error(`${p} names a reserved device, which is not a file inside ${this.baseDir}`)
      }
    }
    const canonBase = canonicalPath(this.baseDir)
    const canonTarget = canonicalPath(full)
    const rel = path.relative(canonBase, canonTarget)
    if (rel === ".." || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) {
      throw new Error(`${p} resolves outside baseDir ${this.baseDir}`)
    }
    return full
  }
}

/**
 * Resolve a path for comparison. Symlinks — and, on Windows, directory
 * junctions and 8.3 short names — are resolved on the DEEPEST EXISTING
 * ancestor and the remaining segments re-attached, because a file `write` is
 * about to create has no real path, and a check that only works on existing
 * files is not a check for `write`.
 */
function canonicalPath(p: string): string {
  const abs = path.resolve(p)
  let cur = abs
  let tail = ""
  for (;;) {
    try {
      const resolved = realpathSync.native(cur)
      return tail ? path.join(resolved, tail) : resolved
    } catch {
      const parent = path.dirname(cur)
      if (parent === cur) return abs
      tail = tail ? path.join(path.basename(cur), tail) : path.basename(cur)
      cur = parent
    }
  }
}

/**
 * Whether the builtin source is on. Default ON. Same precedence as MCP:
 * `disabled:true` wins, else `enabled:false` disables, otherwise enabled.
 */
export function builtinsEnabled(cfg: BuiltinsConfig | undefined): boolean {
  if (cfg === undefined) return true
  if (typeof cfg === "boolean") return cfg
  if (cfg.disabled === true) return false
  if (cfg.enabled === false) return false
  return true
}

/**
 * Resolve the active builtin tools for a config. Whole-source-off wins and
 * returns `[]`. Otherwise all ten are on; a `tools` name→bool map drops any
 * tool mapped to `false` (all-on baseline; `true`/absent stay on; unknown names
 * are ignored). SPEC §4A.
 */
export function selectBuiltins(cfg: BuiltinsConfig | undefined): Tool[] {
  if (!builtinsEnabled(cfg)) return []
  const map = typeof cfg === "object" ? cfg.tools : undefined
  const all = createBuiltinTools(cfg)
  const selected = map ? all.filter((t) => map[t.name] !== false) : all
  // A missing interpreter is a construction-time failure, and only when `bash`
  // survived the toggles: a host that disabled it should run fine on a box with
  // no shell at all (ADR 0034 D1).
  if (selected.some((t) => t.name === "bash")) builtinShell(cfg)
  return selected
}

const err = (output: string, metadata?: Record<string, unknown>): ToolResult => ({ output, isError: true, metadata })
const ok = (output: string, metadata?: Record<string, unknown>): ToolResult => ({ output, isError: false, metadata })

const IGNORE_DIRS = new Set(["node_modules", ".git"])

function builtin(
  name: string,
  description: string,
  inputSchema: JSONSchema,
  run: (args: Record<string, unknown>, ctx?: ToolContext) => Promise<ToolResult>,
): Tool {
  return {
    name,
    description,
    inputSchema,
    source: "builtin",
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      try {
        return await run(args ?? {}, ctx)
      } catch (e) {
        return err(`${name}: ${e instanceof Error ? e.message : String(e)}`)
      }
    },
  }
}

// ---------------------------------------------------------------------------
// glob helpers (shared by grep + glob)
// ---------------------------------------------------------------------------

/** Convert a glob (`*`, `**`, `?`) to an anchored RegExp. */
function globToRegExp(glob: string): RegExp {
  let re = ""
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i]
    if (c === "*") {
      if (glob[i + 1] === "*") {
        re += ".*"
        i++
        if (glob[i + 1] === "/") i++
      } else {
        re += "[^/]*"
      }
    } else if (c === "?") {
      re += "[^/]"
    } else if ("\\^$.|+()[]{}".includes(c)) {
      re += "\\" + c
    } else {
      re += c
    }
  }
  return new RegExp("^" + re + "$")
}

/** Match a relative path against a glob; slash-less globs test the basename. */
function matchGlob(rel: string, glob: string): boolean {
  const re = globToRegExp(glob)
  if (!glob.includes("/")) return re.test(path.basename(rel))
  return re.test(rel)
}

/** Recursively list files under `root` (skips node_modules/.git). Returns absolute paths. */
function walkFiles(root: string): string[] {
  const out: string[] = []
  const stack = [root]
  while (stack.length) {
    const dir = stack.pop()!
    let entries
    try {
      // EXPLICIT (A24). `glob` breaks at its cap, so an unsorted read changes WHICH files the
      // model is shown, not merely their order — the same content bug as <skill_files> had.
      entries = sortEntriesByName(readdirSync(dir, { withFileTypes: true }))
    } catch {
      continue
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) {
        if (IGNORE_DIRS.has(entry.name)) continue
        stack.push(full)
      } else if (entry.isFile()) {
        out.push(full)
      }
    }
  }
  return out
}

// ---------------------------------------------------------------------------
// individual tools
// ---------------------------------------------------------------------------

function bashTool(env: BuiltinEnv): Tool {
  const properties: Record<string, unknown> = {
    command: { type: "string", description: "The shell command to run" },
    workdir: { type: "string", description: "Working directory (default: process cwd)" },
    timeout: { type: "number", description: "Timeout in milliseconds (default 60000)" },
    description: { type: "string", description: "Human-readable description of the command" },
  }
  if (env.background) {
    properties.run_in_background = {
      type: "boolean",
      description: "Start the command and return at once with a background shell id; read it with bash_output, stop it with kill_shell. No timeout applies.",
    }
  }
  return builtin(
    "bash",
    "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
    {
      type: "object",
      properties,
      required: ["command"],
      additionalProperties: false,
    },
    (args, ctx) =>
      new Promise<ToolResult>((resolve) => {
        const command = String(args.command ?? "")
        if (!command) return resolve(err("bash: command is required"))
        let argv: string[]
        let workdir: string
        try {
          argv = env.shellArgv()
          workdir = args.workdir ? env.resolvePath(String(args.workdir)) : env.dir()
        } catch (e) {
          return resolve(err(`bash: ${(e as Error).message}`))
        }
        const timeout = typeof args.timeout === "number" ? args.timeout : 60_000
        const meta: Record<string, unknown> = { shell: env.shellLabel }
        if (env.background && args.run_in_background === true) {
          const j = env.background.start(argv, command, workdir, env.killGraceMs)
          return resolve(ok(`Started background shell ${j.id}. Read its output with bash_output, stop it with kill_shell.`, { ...meta, backgroundId: j.id }))
        }

        // NOT `shell: true`: on Windows that silently means cmd.exe while every
        // other port fails loudly, and it hides WHICH interpreter ran. `detached`
        // puts the child in its own process group so a kill reaches the command
        // AND everything it started — without it the kill reaches the
        // interpreter only and the real work runs on, reparented (ADR 0034 D4).
        const child = spawn(argv[0], [...argv.slice(1), command], {
          cwd: workdir,
          detached: process.platform !== "win32",
        })
        let out = ""
        let stopped: "" | "timeout" | "cancelled" = ""
        let killedTree = false
        let settled = false

        // Captured once, at spawn: `detached` makes the child a process-group
        // LEADER, so its pid is the pgid. Reading it later from `child` would
        // risk signalling by a pid the runtime has already let go of.
        const pgid = child.pid

        const killJob = (graceful: boolean): boolean => {
          // Nothing may be signalled once this call has settled: a stale timer
          // firing after the fact would aim at a pid the OS may have reissued,
          // and a NEGATED pid is a whole process group.
          if (settled) return false
          if (typeof pgid !== "number" || !Number.isInteger(pgid) || pgid <= 1) return false
          try {
            if (process.platform === "win32") {
              // Windows has NO graceful termination for a console process, and
              // asking anyway is worse than not asking: measured on a native
              // Windows box, `taskkill /T` without `/F` refuses every console
              // process in the tree ("can only be terminated forcefully") while
              // still being able to take the parent down — which reparents the
              // grandchild and leaves it running. So the job is terminated at
              // once there, and the grace window is a POSIX effect (ADR 0034 D4).
              const r = spawnSync("taskkill", ["/T", "/F", "/PID", String(pgid)])
              return r.status === 0
            }
            // Never signal a REAPED child by negated pid: that pid may belong to
            // something else by now, and the blast radius is a whole process
            // GROUP. (On Windows above, taskkill targets one pid and a dead pid
            // is merely an error, so the same guard is not needed there — and
            // must not be applied, because the tree can outlive the child.)
            if (child.exitCode !== null || child.signalCode !== null) return false
            process.kill(-pgid, graceful ? "SIGTERM" : "SIGKILL")
            return true
          } catch {
            return false
          }
        }

        // Ask, wait out the grace window, then insist. A runner that gets
        // SIGTERM removes its temp directories; one that gets SIGKILL does not.
        const stop = (why: "timeout" | "cancelled") => {
          if (stopped) return
          stopped = why
          killedTree = killJob(true)
          graceTimer = setTimeout(() => {
            if (killJob(false)) killedTree = true
          }, env.killGraceMs)
        }

        let graceTimer: NodeJS.Timeout | undefined
        const timer = setTimeout(() => stop("timeout"), timeout)
        const signal = ctx?.signal
        const onAbort = () => stop("cancelled")
        signal?.addEventListener?.("abort", onAbort, { once: true })

        const finish = (result: ToolResult) => {
          if (settled) return
          settled = true
          clearTimeout(timer)
          if (graceTimer) clearTimeout(graceTimer)
          signal?.removeEventListener?.("abort", onAbort)
          resolve(result)
        }

        child.stdout?.on("data", (d) => (out += d.toString()))
        child.stderr?.on("data", (d) => (out += d.toString()))
        child.on("error", (e) => finish(err(`bash: ${e.message}`, meta)))
        child.on("close", (code) => {
          if (stopped) {
            // WINDOWS ONLY, and the platform guard is the point. There, the
            // direct child closing does not mean the job is gone — a detached
            // grandchild outlives it — so the tree is terminated here by pid.
            //
            // On POSIX this must NOT run. `close` only fires once the stdio the
            // grandchild inherited has closed, so the job is already gone; and
            // signalling `-pid` after the child has been reaped aims at whatever
            // process now owns that pid. PIDs recycle fast on a busy CI box, and
            // the blast radius of a negated pid is a whole process GROUP — which
            // is how this killed sibling test workers on the GitHub runner while
            // passing on macOS, in a container, and on its own.
            if (!killedTree && process.platform === "win32") killedTree = killJob(false)
            meta.timedOut = stopped === "timeout"
            meta.killedTree = killedTree
            return finish(
              err(
                stopped === "timeout"
                  ? `bash: command timed out after ${timeout}ms\n${out}`
                  : `bash: command cancelled\n${out}`,
                meta,
              ),
            )
          }
          meta.exitCode = code
          if (code !== 0) return finish(err(`${out}\nbash: command exited with code ${code}`, meta))
          finish(ok(out, meta))
        })
      }),
  )
}

function readTool(env: BuiltinEnv): Tool {
  return builtin(
    "read",
    "Read a file. Recognised media (png/jpg/jpeg/gif/webp/pdf/mp3/wav) comes back as a content part; anything else is read as UTF-8 text, with offset/limit returning that line window.",
    {
      type: "object",
      properties: {
        path: { type: "string", description: "Path to the file to read" },
        offset: { type: "number", description: "1-based line to start from" },
        limit: { type: "number", description: "Maximum number of lines to read" },
      },
      required: ["path"],
      additionalProperties: false,
    },
    async (args) => {
      const p = String(args.path ?? "")
      if (!p) return err("read: path is required")
      let bytes: Buffer
      let full: string
      try {
        full = env.resolvePath(p)
        bytes = await fs.readFile(full)
      } catch (e) {
        return err(`read: ${e instanceof Error ? e.message : String(e)}`)
      }
      // §6: a recognised media extension comes back as a part. The table is fixed — no magic-byte
      // sniffing and no platform mime database, whose contents vary per machine.
      const media = mediaTypeFor(full)
      if (media) {
        return {
          output: `${p} (${media.mimeType}, ${bytes.length} bytes)`,
          isError: false,
          parts: [{ type: media.type, mimeType: media.mimeType, data: bytes.toString("base64"), ...(media.type === "file" ? { name: path.basename(p) } : {}) } as any],
        }
      }
      let content: string
      try {
        // `fatal` so undecodable bytes are an isError RESULT, not U+FFFD soup — and never a
        // raised exception escaping execute() into the loop. `ignoreBOM` keeps a leading BOM,
        // as readFile(…, "utf8") does, so text reads stay byte-identical.
        content = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes)
      } catch {
        return err(`read: ${p} is not valid UTF-8 text and has no recognised media extension`)
      }
      if (args.offset === undefined && args.limit === undefined) return ok(content)
      const lines = content.split("\n")
      const offset = typeof args.offset === "number" ? Math.max(1, Math.trunc(args.offset)) : 1
      const start = offset - 1
      const limit = typeof args.limit === "number" ? Math.max(0, Math.trunc(args.limit)) : lines.length - start
      const end = Math.min(Math.max(start, start + limit), lines.length)
      const out = lines.slice(start, start + limit).join("\n")
      // A window that stops before the end of the file says so in metadata, with the offset to
      // continue from (SPEC §4C). The text stays byte-identical. A trailing newline is not a line.
      let total = lines.length
      if (total > 0 && lines[total - 1] === "") total--
      if (end < total) return ok(out, { truncated: true, totalLines: total, nextOffset: end + 1 })
      return ok(out)
    },
  )
}

function writeTool(env: BuiltinEnv): Tool {
  return builtin(
    "write",
    "Write content to a file (create/overwrite), creating parent directories.",
    {
      type: "object",
      properties: {
        path: { type: "string", description: "Path to write to" },
        content: { type: "string", description: "Content to write" },
      },
      required: ["path", "content"],
      additionalProperties: false,
    },
    async (args) => {
      const p = String(args.path ?? "")
      if (!p) return err("write: path is required")
      const content = typeof args.content === "string" ? args.content : String(args.content ?? "")
      let full: string
      try {
        full = env.resolvePath(p)
      } catch (e) {
        return err(`write: ${e instanceof Error ? e.message : String(e)}`)
      }
      const before = await readIfExists(full)
      await fs.mkdir(path.dirname(path.resolve(full)), { recursive: true })
      await fs.writeFile(full, content, "utf8")
      const bytes = Buffer.byteLength(content, "utf8")
      return ok(`Wrote ${bytes} bytes to ${p}`, { bytes, ...editMetadata(env, [{ path: full, before, after: content }]) })
    },
  )
}

function editTool(env: BuiltinEnv): Tool {
  return builtin(
    "edit",
    "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
    {
      type: "object",
      properties: {
        path: { type: "string", description: "Path to the file to edit" },
        oldString: { type: "string", description: "Exact string to replace" },
        newString: { type: "string", description: "Replacement string" },
        replaceAll: { type: "boolean", description: "Replace all occurrences" },
      },
      required: ["path", "oldString", "newString"],
      additionalProperties: false,
    },
    async (args) => {
      const p = String(args.path ?? "")
      if (!p) return err("edit: path is required")
      if (typeof args.oldString !== "string" || args.oldString.length === 0) {
        return err("edit: oldString is required")
      }
      const oldString = args.oldString
      const newString = typeof args.newString === "string" ? args.newString : String(args.newString ?? "")
      let content: string
      let full: string
      try {
        full = env.resolvePath(p)
        content = await fs.readFile(full, "utf8")
      } catch (e) {
        return err(`edit: ${e instanceof Error ? e.message : String(e)}`)
      }
      const count = content.split(oldString).length - 1
      if (count === 0) return err(`edit: oldString not found in ${p}`)
      let next: string
      if (args.replaceAll === true) {
        next = content.split(oldString).join(newString)
      } else {
        if (count > 1) return err(`edit: oldString is not unique in ${p} (${count} occurrences); use replaceAll`)
        next = content.replace(oldString, newString)
      }
      await fs.writeFile(full, next, "utf8")
      return ok(`Edited ${p} (${args.replaceAll === true ? count : 1} replacement${(args.replaceAll === true ? count : 1) === 1 ? "" : "s"})`, {
        replacements: args.replaceAll === true ? count : 1,
        ...editMetadata(env, [{ path: full, before: content, after: next }]),
      })
    },
  )
}

function grepTool(env: BuiltinEnv): Tool {
  return builtin(
    "grep",
    "Search file contents by regex under a directory. Output is file:line:text matches.",
    {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Regular expression to search for" },
        path: { type: "string", description: "Directory to search (default: process cwd)" },
        include: { type: "string", description: "Glob filter for file names" },
        limit: { type: "number", description: "Maximum number of matches (default 100)" },
        context: { type: "number", description: "Lines of context before and after each match" },
        before: { type: "number", description: "Lines of context before each match (overrides context)" },
        after: { type: "number", description: "Lines of context after each match (overrides context)" },
      },
      required: ["pattern"],
      additionalProperties: false,
    },
    async (args) => {
      const pattern = String(args.pattern ?? "")
      if (!pattern) return err("grep: pattern is required")
      let re: RegExp
      try {
        re = new RegExp(pattern)
      } catch (e) {
        return err(`grep: invalid regex: ${e instanceof Error ? e.message : String(e)}`)
      }
      let root: string
      try {
        root = args.path ? env.resolvePath(String(args.path)) : env.dir()
      } catch (e) {
        return err(`grep: ${e instanceof Error ? e.message : String(e)}`)
      }
      const include = args.include ? String(args.include) : undefined
      const limit = typeof args.limit === "number" ? args.limit : 100
      // Was the worse of the two builtins: capped MID-WALK and never sorted at all, so both the
      // CONTENT and the order of the results were whatever the walk happened to reach first.
      // Now: collect every match, order by relative path (code point) then line number ascending,
      // and truncate last. The emitted path and the sort key are the same string (A28).
      let before = 0, after = 0
      if (typeof args.context === "number" && args.context > 0) before = after = Math.trunc(args.context)
      if (typeof args.before === "number" && args.before >= 0) before = Math.trunc(args.before)
      if (typeof args.after === "number" && args.after >= 0) after = Math.trunc(args.after)
      const fileLines = new Map<string, string[]>() // only filled when context is asked for
      const hits: Array<{ rel: string; line: number; text: string }> = []
      for (const file of walkFiles(root)) {
        const rel = toPosixPath(path.relative(root, file), path.sep)
        if (include && !matchGlob(rel, include)) continue
        let text: string
        try {
          text = await fs.readFile(file, "utf8")
        } catch {
          continue
        }
        const lines = text.split("\n")
        if (before > 0 || after > 0) fileLines.set(rel, lines)
        for (let i = 0; i < lines.length; i++) {
          if (re.test(lines[i])) hits.push({ rel, line: i + 1, text: lines[i] })
        }
      }
      hits.sort((a, b) => compareCodePoints(a.rel, b.rel) || a.line - b.line)
      const total = hits.length
      const kept = hits.slice(0, limit)
      let outLines: string[]
      if (before === 0 && after === 0) {
        outLines = kept.map((h) => `${h.rel}:${h.line}:${h.text}`)
      } else {
        // ripgrep-style: match lines path:N:text, context lines path-N-text, "--" between
        // groups that do not touch.
        outLines = []
        const matchAt = new Map<string, Set<number>>()
        for (const h of kept) {
          if (!matchAt.has(h.rel)) matchAt.set(h.rel, new Set())
          matchAt.get(h.rel)!.add(h.line)
        }
        let lastRel = "", lastLine = 0
        for (const h of kept) {
          const lines = fileLines.get(h.rel) ?? []
          let n = lines.length
          if (n > 0 && lines[n - 1] === "") n-- // a trailing newline is not a line
          let from = Math.max(1, h.line - before)
          const to = Math.min(n, h.line + after)
          if (h.rel === lastRel && from <= lastLine + 1) from = lastLine + 1
          else if (lastRel !== "") outLines.push("--")
          for (let k = from; k <= to; k++) {
            const sep = matchAt.get(h.rel)!.has(k) ? ":" : "-"
            outLines.push(`${h.rel}${sep}${k}${sep}${lines[k - 1]}`)
          }
          if (to > lastLine || h.rel !== lastRel) lastLine = to
          lastRel = h.rel
        }
      }
      const meta: Record<string, unknown> = { count: kept.length }
      // Say the list was cut, and by how much, in metadata (SPEC §4C). The text is unchanged.
      if (total > kept.length) {
        meta.truncated = true
        meta.total = total
      }
      return ok(outLines.join("\n"), meta)
    },
  )
}

function globTool(env: BuiltinEnv): Tool {
  return builtin(
    "glob",
    "List files matching a glob under a directory. Output is newline-joined relative paths.",
    {
      type: "object",
      properties: {
        pattern: { type: "string", description: "Glob pattern to match" },
        path: { type: "string", description: "Directory to search (default: process cwd)" },
        limit: { type: "number", description: "Maximum number of results (default 100)" },
      },
      required: ["pattern"],
      additionalProperties: false,
    },
    async (args) => {
      const pattern = String(args.pattern ?? "")
      if (!pattern) return err("glob: pattern is required")
      let root: string
      try {
        root = args.path ? env.resolvePath(String(args.path)) : env.dir()
      } catch (e) {
        return err(`glob: ${e instanceof Error ? e.message : String(e)}`)
      }
      const limit = typeof args.limit === "number" ? args.limit : 100
      // Collect ALL matches, order them, THEN cap (A24/A25): breaking the walk at the cap let
      // the filesystem decide WHICH files the model sees, not merely their order. The emitted
      // string and the sort key are the SAME `/`-separated relative path (A28).
      const found: string[] = []
      for (const file of walkFiles(root)) {
        const rel = toPosixPath(path.relative(root, file), path.sep)
        if (matchGlob(rel, pattern)) found.push(rel)
      }
      found.sort(compareCodePoints)
      return ok(found.slice(0, limit).join("\n"), { count: Math.min(found.length, limit) })
    },
  )
}

/** Very light HTML → text: drop scripts/styles + tags, collapse whitespace. */
function stripHtml(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<[^>]+>/g, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim()
}

function webfetchTool(): Tool {
  return builtin(
    "webfetch",
    "HTTP GET a URL and return its body as text, markdown, or html.",
    {
      type: "object",
      properties: {
        url: { type: "string", description: "URL to fetch" },
        format: { type: "string", enum: ["text", "markdown", "html"], description: "Response format (default markdown)" },
        timeout: { type: "number", description: "Timeout in seconds (default 30)" },
      },
      required: ["url"],
      additionalProperties: false,
    },
    async (args, ctx) => {
      const url = String(args.url ?? "")
      if (!url) return err("webfetch: url is required")
      const format = args.format === "text" || args.format === "html" ? args.format : "markdown"
      const timeoutMs = (typeof args.timeout === "number" ? args.timeout : 30) * 1000
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), timeoutMs)
      if (ctx?.signal) ctx.signal.addEventListener("abort", () => controller.abort(), { once: true })
      try {
        const res = await fetch(url, { method: "GET", signal: controller.signal })
        const body = await res.text()
        if (!res.ok) return err(`HTTP ${res.status}`, { status: res.status })
        const output = format === "html" ? body : stripHtml(body)
        return ok(output, { status: res.status, format })
      } catch (e) {
        return err(`webfetch: ${e instanceof Error ? e.message : String(e)}`)
      } finally {
        clearTimeout(timer)
      }
    },
  )
}

/**
 * Render the questions into a human-readable `Request.prompt` (§10). Byte-identical across ports:
 * each question's text in order, `" (options: a, b, c)"` appended when it has non-empty options,
 * joined by "\n" (no trailing newline). `header` is not rendered — it survives in `data.questions`.
 */
function renderQuestionPrompt(questions: unknown[]): string {
  return questions
    .map((q) => {
      const item = q as { question?: unknown; options?: unknown }
      let line = typeof item?.question === "string" ? item.question : ""
      const opts = Array.isArray(item?.options) ? item.options : []
      if (opts.length > 0) line += ` (options: ${opts.join(", ")})`
      return line
    })
    .join("\n")
}

function questionTool(): Tool {
  return builtin(
    "question",
    "Ask the host one or more questions. Suspends via a kind:\"question\" Request (§10); the host's waitFor resolves it and the answer is returned to the model.",
    {
      type: "object",
      properties: {
        questions: {
          type: "array",
          description: "Questions to ask",
          items: {
            type: "object",
            properties: {
              question: { type: "string" },
              header: { type: "string" },
              options: { type: "array", items: { type: "string" } },
              multiple: { type: "boolean" },
            },
            required: ["question"],
          },
        },
      },
      required: ["questions"],
      additionalProperties: false,
    },
    async (args, ctx) => {
      const questions = Array.isArray(args.questions) ? args.questions : []
      // Re-executed after the host's waitFor resolved (§10 loop rule): the resolution IS the
      // answer, as with kind:"input" — forward it verbatim to the model.
      if (ctx?.answer) return ok(JSON.stringify(ctx.answer.data ?? {}))
      // First call: suspend. A question is just a §10 Request with kind:"question".
      return pending({ kind: "question", prompt: renderQuestionPrompt(questions), data: { questions } })
    },
  )
}

function todowriteTool(): Tool {
  return builtin(
    "todowrite",
    "Replace the session todo list. Returns the rendered list.",
    {
      type: "object",
      properties: {
        todos: {
          type: "array",
          description: "The full todo list to store",
          items: {
            type: "object",
            properties: {
              id: { type: "string" },
              text: { type: "string" },
              completed: { type: "boolean" },
            },
            required: ["id", "text", "completed"],
          },
        },
      },
      required: ["todos"],
      additionalProperties: false,
    },
    async (args) => {
      const todos = Array.isArray(args.todos) ? (args.todos as Array<Record<string, unknown>>) : []
      const rendered = todos.map((t) => `[${t.completed ? "x" : " "}] ${String(t.text ?? "")}`).join("\n")
      return ok(rendered || "(no todos)", { todos })
    },
  )
}

// ---------------------------------------------------------------------------
// apply_patch (opencode Begin/End Patch grammar)
// ---------------------------------------------------------------------------

type PatchOp =
  | { type: "add"; path: string; content: string }
  | { type: "delete"; path: string }
  | { type: "update"; path: string; body: string[] }

const FILE_MARKER = /^\*\*\* (Add|Update|Delete) File: (.+)$/

function parsePatch(patchText: string): PatchOp[] {
  const lines = patchText.split("\n")
  let i = 0
  while (i < lines.length && lines[i].trim() === "") i++
  if (lines[i]?.trim() !== "*** Begin Patch") throw new Error("missing '*** Begin Patch'")
  i++
  const ops: PatchOp[] = []
  while (i < lines.length) {
    const line = lines[i]
    if (line.trim() === "*** End Patch") return ops
    if (line.trim() === "") {
      i++
      continue
    }
    const m = FILE_MARKER.exec(line)
    if (!m) throw new Error(`unexpected line: ${line}`)
    const kind = m[1]
    const p = m[2].trim()
    i++
    const body: string[] = []
    while (i < lines.length && lines[i].trim() !== "*** End Patch" && !FILE_MARKER.test(lines[i])) {
      body.push(lines[i])
      i++
    }
    if (kind === "Add") {
      const content = body.map((l) => (l.startsWith("+") ? l.slice(1) : l)).join("\n")
      ops.push({ type: "add", path: p, content })
    } else if (kind === "Delete") {
      ops.push({ type: "delete", path: p })
    } else {
      ops.push({ type: "update", path: p, body })
    }
  }
  throw new Error("missing '*** End Patch'")
}

/** Apply an Update hunk-body to file content, returning the new content or throwing on a non-match. */
function applyUpdate(content: string, body: string[]): string {
  // split into hunks by @@ markers; body with no @@ is a single hunk.
  const hunks: string[][] = []
  let cur: string[] = []
  for (const l of body) {
    if (l.startsWith("@@")) {
      if (cur.length) hunks.push(cur)
      cur = []
    } else {
      cur.push(l)
    }
  }
  if (cur.length) hunks.push(cur)

  let result = content
  for (const hunk of hunks) {
    const oldLines: string[] = []
    const newLines: string[] = []
    for (const l of hunk) {
      if (l.startsWith("-")) oldLines.push(l.slice(1))
      else if (l.startsWith("+")) newLines.push(l.slice(1))
      else if (l.startsWith(" ")) {
        oldLines.push(l.slice(1))
        newLines.push(l.slice(1))
      } else {
        oldLines.push(l)
        newLines.push(l)
      }
    }
    const oldBlock = oldLines.join("\n")
    const newBlock = newLines.join("\n")
    if (oldBlock.length > 0) {
      if (!result.includes(oldBlock)) throw new Error("hunk does not match file contents")
      result = result.replace(oldBlock, newBlock)
    } else {
      // pure insertion with no context — append.
      result = result + (result.endsWith("\n") || result === "" ? "" : "\n") + newBlock
    }
  }
  return result
}

function applyPatchTool(env: BuiltinEnv): Tool {
  return builtin(
    "apply_patch",
    "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
    {
      type: "object",
      properties: {
        patchText: { type: "string", description: "The patch text in Begin/End Patch format" },
      },
      required: ["patchText"],
      additionalProperties: false,
    },
    async (args) => {
      const patchText = String(args.patchText ?? "")
      if (!patchText) return err("apply_patch: patchText is required")
      let ops: PatchOp[]
      try {
        ops = parsePatch(patchText)
      } catch (e) {
        return err(`apply_patch: ${e instanceof Error ? e.message : String(e)}`)
      }
      // The paths live INSIDE the patch text, not in the arguments, so a host
      // cannot rewrite them from a hook — this is the one case that genuinely
      // needs the base directory to be library-side (ADR 0034 D2).
      try {
        for (const op of ops) op.path = env.resolvePath(op.path)
      } catch (e) {
        return err(`apply_patch: ${e instanceof Error ? e.message : String(e)}`)
      }
      // Stage every write/delete first; only touch the filesystem once all hunks apply.
      const writes: Array<{ path: string; content: string }> = []
      const deletes: string[] = []
      const changes: FileChange[] = []
      try {
        for (const op of ops) {
          if (op.type === "add") {
            if (existsSync(op.path)) throw new Error(`file already exists: ${op.path}`)
            writes.push({ path: op.path, content: op.content })
            changes.push({ path: op.path, before: undefined, after: op.content })
          } else if (op.type === "delete") {
            if (!existsSync(op.path)) throw new Error(`file not found: ${op.path}`)
            deletes.push(op.path)
            changes.push({ path: op.path, before: await fs.readFile(op.path, "utf8"), after: undefined })
          } else {
            const content = await fs.readFile(op.path, "utf8")
            const next = applyUpdate(content, op.body)
            writes.push({ path: op.path, content: next })
            changes.push({ path: op.path, before: content, after: next })
          }
        }
      } catch (e) {
        return err(`apply_patch: ${e instanceof Error ? e.message : String(e)}`)
      }
      for (const w of writes) {
        await fs.mkdir(path.dirname(path.resolve(w.path)), { recursive: true })
        await fs.writeFile(w.path, w.content, "utf8")
      }
      for (const d of deletes) {
        await fs.rm(d, { force: true })
      }
      return ok(`Applied patch: ${ops.length} file operation${ops.length === 1 ? "" : "s"}`, {
        added: ops.filter((o) => o.type === "add").length,
        updated: ops.filter((o) => o.type === "update").length,
        deleted: ops.filter((o) => o.type === "delete").length,
        ...editMetadata(env, changes),
      })
    },
  )
}

// ---------------------------------------------------------------------------
// edit diffs (SPEC §4A "Edit diffs"): metadata.files + metadata.diff
// ---------------------------------------------------------------------------

/** One file a builtin changed; `undefined` before = created, `undefined` after = deleted. */
type FileChange = { path: string; before: string | undefined; after: string | undefined }

async function readIfExists(p: string): Promise<string | undefined> {
  try {
    return await fs.readFile(p, "utf8")
  } catch {
    return undefined
  }
}

/** Lines of a file's text; a trailing newline does not make an extra empty line. */
function diffLines(text: string | undefined): string[] {
  if (!text) return []
  const lines = text.split("\n")
  if (lines[lines.length - 1] === "") lines.pop()
  return lines
}

type DiffOp = { kind: " " | "-" | "+"; line: string }

/** Line diff by longest common subsequence — minimal, so the counts agree across ports. */
function lineDiff(a0: string[], b0: string[]): DiffOp[] {
  // Common prefix/suffix first: an edit touches a few lines, so the LCS table stays small.
  let pre = 0
  while (pre < a0.length && pre < b0.length && a0[pre] === b0[pre]) pre++
  let suf = 0
  while (suf < a0.length - pre && suf < b0.length - pre && a0[a0.length - 1 - suf] === b0[b0.length - 1 - suf]) suf++
  const head: DiffOp[] = a0.slice(0, pre).map((line) => ({ kind: " ", line }))
  const tail: DiffOp[] = a0.slice(a0.length - suf).map((line) => ({ kind: " ", line }))
  const a = a0.slice(pre, a0.length - suf), b = b0.slice(pre, b0.length - suf)
  const n = a.length, m = b.length
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1])
    }
  }
  const ops: DiffOp[] = []
  let i = 0, j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) { ops.push({ kind: " ", line: a[i] }); i++; j++ }
    else if (lcs[i + 1][j] >= lcs[i][j + 1]) ops.push({ kind: "-", line: a[i++] })
    else ops.push({ kind: "+", line: b[j++] })
  }
  while (i < n) ops.push({ kind: "-", line: a[i++] })
  while (j < m) ops.push({ kind: "+", line: b[j++] })
  return [...head, ...ops, ...tail]
}

/** Unified hunks with 3 lines of context. */
function unifiedHunks(ops: DiffOp[]): string[] {
  const CTX = 3
  const out: string[] = []
  const changed = ops.map((o, k) => (o.kind === " " ? -1 : k)).filter((k) => k >= 0)
  if (!changed.length) return out
  // group change indices whose gap of context is <= 2*CTX
  const groups: Array<[number, number]> = []
  for (const k of changed) {
    const g = groups[groups.length - 1]
    if (g && k - g[1] <= 2 * CTX) g[1] = k
    else groups.push([k, k])
  }
  // old/new line number before each op
  const oldAt: number[] = [], newAt: number[] = []
  let o = 1, nn = 1
  for (const op of ops) {
    oldAt.push(o); newAt.push(nn)
    if (op.kind !== "+") o++
    if (op.kind !== "-") nn++
  }
  for (const [s0, e0] of groups) {
    const s = Math.max(0, s0 - CTX), e = Math.min(ops.length - 1, e0 + CTX)
    const slice = ops.slice(s, e + 1)
    const oldLen = slice.filter((x) => x.kind !== "+").length
    const newLen = slice.filter((x) => x.kind !== "-").length
    const oldStart = oldLen ? oldAt[s] : oldAt[s] - 1
    const newStart = newLen ? newAt[s] : newAt[s] - 1
    out.push(`@@ -${oldStart},${oldLen} +${newStart},${newLen} @@`)
    for (const x of slice) out.push(x.kind + x.line)
  }
  return out
}

function editMetadata(env: BuiltinEnv, changes: FileChange[]): { files: unknown[]; diff: string } {
  const files: unknown[] = []
  const diff: string[] = []
  for (const c of changes) {
    const rel = toPosixPath(env.baseDir ? path.relative(path.resolve(env.baseDir), path.resolve(c.path)) : c.path, path.sep)
    const ops = lineDiff(diffLines(c.before), diffLines(c.after))
    const additions = ops.filter((x) => x.kind === "+").length
    const deletions = ops.filter((x) => x.kind === "-").length
    const status = c.before === undefined ? "added" : c.after === undefined ? "deleted" : "modified"
    files.push({ path: rel, status, additions, deletions })
    diff.push(`--- ${c.before === undefined ? "/dev/null" : `a/${rel}`}`, `+++ ${c.after === undefined ? "/dev/null" : `b/${rel}`}`, ...unifiedHunks(ops))
  }
  return { files, diff: diff.length ? diff.join("\n") + "\n" : "" }
}

/**
 * Build the ten built-in tools (each source:"builtin"). The order is fixed
 * for parity: bash, read, write, edit, grep, glob, webfetch, question,
 * apply_patch, todowrite.
 */
export function createBuiltinTools(cfg?: BuiltinsConfig): Tool[] {
  const env = new BuiltinEnv(cfg)
  const tools = [
    bashTool(env),
    readTool(env),
    writeTool(env),
    editTool(env),
    grepTool(env),
    globTool(env),
    webfetchTool(),
    questionTool(),
    applyPatchTool(env),
    todowriteTool(),
  ]
  if (env.background) tools.push(bashOutputTool(env.background), killShellTool(env.background))
  if (env.webSearch) tools.push(webSearchTool(env.webSearch))
  if (env.notebookEdit) tools.push(notebookEditTool(env))
  for (const t of tools) t.hints = { ...BUILTIN_HINTS[t.name] }
  return tools
}

const hint = (readOnly: boolean, destructive: boolean, openWorld: boolean): ToolHints => ({ readOnly, destructive, openWorld })

/**
 * Hints on every builtin, for host permission gates (SPEC §4C). bash can do anything, so it is
 * mutating, destructive and open-world. todowrite and question only touch the agent's own state
 * or ask the human, so they count as read-only for the host's files and systems.
 */
const BUILTIN_HINTS: Record<string, ToolHints> = {
  bash: hint(false, true, true),
  read: hint(true, false, false),
  write: hint(false, true, false),
  edit: hint(false, true, false),
  grep: hint(true, false, false),
  glob: hint(true, false, false),
  webfetch: hint(true, false, true),
  question: hint(true, false, false),
  apply_patch: hint(false, true, false),
  todowrite: hint(true, false, false),
  bash_output: hint(true, false, false),
  kill_shell: hint(false, true, false),
  websearch: hint(true, false, true),
  notebook_edit: hint(false, true, false),
}

const idSchema: JSONSchema = {
  type: "object",
  properties: { id: { type: "string", description: "The background shell id bash returned" } },
  required: ["id"],
  additionalProperties: false,
}

function bashOutputTool(bg: BackgroundShells): Tool {
  return builtin(
    "bash_output",
    "Read the output a background bash command (started with run_in_background) produced since the last read, and whether it is still running.",
    idSchema,
    async (args) => bashOutput(bg, typeof args.id === "string" ? args.id : ""),
  )
}

function killShellTool(bg: BackgroundShells): Tool {
  return builtin(
    "kill_shell",
    "Stop a background bash command and everything it started.",
    idSchema,
    async (args) => killShell(bg, typeof args.id === "string" ? args.id : ""),
  )
}

function webSearchTool(search: WebSearchFunc): Tool {
  return builtin(
    "websearch",
    "Search the web. Returns numbered results with title, URL and snippet.",
    {
      type: "object",
      properties: {
        query: { type: "string", description: "The search query" },
        limit: { type: "number", description: "Maximum number of results (default 5)" },
      },
      required: ["query"],
      additionalProperties: false,
    },
    async (args, ctx) => runWebSearch(search, args, ctx?.signal),
  )
}

function notebookEditTool(env: BuiltinEnv): Tool {
  return builtin(
    "notebook_edit",
    "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, or delete it. Replacing a code cell clears its outputs.",
    {
      type: "object",
      properties: {
        path: { type: "string", description: "Path to the .ipynb file" },
        cell_id: { type: "string", description: "The cell's id (or use cell_index)" },
        cell_index: { type: "number", description: "0-based cell position (used when cell_id is absent)" },
        new_source: { type: "string", description: "The new cell source (replace/insert)" },
        cell_type: { type: "string", enum: ["code", "markdown"], description: "Cell type for insert (default code) or to change on replace" },
        edit_mode: { type: "string", enum: ["replace", "insert", "delete"], description: "Default replace. insert adds the new cell AFTER the given cell, or first when none is given" },
      },
      required: ["path"],
      additionalProperties: false,
    },
    async (args) => {
      const p = typeof args.path === "string" ? args.path : ""
      if (!p) return err("notebook_edit: path is required")
      let full: string
      let raw: string
      try {
        full = env.resolvePath(p)
        raw = await fs.readFile(full, "utf8")
      } catch (e) {
        return err(`notebook_edit: ${e instanceof Error ? e.message : String(e)}`)
      }
      let nb: Record<string, any>
      try {
        nb = JSON.parse(raw)
        if (!nb || typeof nb !== "object" || Array.isArray(nb)) throw new Error("not a JSON object")
      } catch (e) {
        return err(`notebook_edit: ${p} is not a notebook: ${e instanceof Error ? e.message : String(e)}`)
      }
      const r = editNotebook(nb, args, p)
      if (!("summary" in r)) return r
      const out = goJsonIndent(nb) + "\n"
      await fs.writeFile(full, out, "utf8")
      return ok(r.summary, { cells: nb.cells.length, ...editMetadata(env, [{ path: full, before: raw, after: out }]) })
    },
  )
}

/**
 * The interpreter the builtins resolved for `bash` — what a host prints, and
 * what `metadata.shell` carries on every bash result. Throws the same error
 * `selectBuiltins` fails construction with when nothing resolves.
 */
export function builtinShell(cfg?: BuiltinsConfig): string[] {
  return new BuiltinEnv(cfg).shellArgv()
}
