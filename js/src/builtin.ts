/**
 * Built-in tool source (source: "builtin"). The default toolset toolnexus ships
 * so an agent can act with zero custom wiring — opencode's built-ins, ported
 * with identical tool names + input schemas. See ../../SPEC.md §4A.
 *
 * Every tool obeys the uniform Tool/ToolResult contract: a failure is a
 * ToolResult{isError:true}, never a thrown exception across the boundary. Paths
 * resolve relative to the process working directory unless absolute.
 */
import { spawn } from "node:child_process"
import fs from "node:fs/promises"
import { existsSync, readdirSync } from "node:fs"
import path from "node:path"
import type { JSONSchema, Tool, ToolContext, ToolResult } from "./types.js"

/** Config for the single global builtin toggle (mirrors MCP isEnabled precedence). */
export type BuiltinsConfig =
  | boolean
  | { enabled?: boolean; disabled?: boolean; tools?: Record<string, boolean> }

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
  const all = createBuiltinTools()
  if (!map) return all
  return all.filter((t) => map[t.name] !== false)
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
      entries = readdirSync(dir, { withFileTypes: true })
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

function bashTool(): Tool {
  return builtin(
    "bash",
    "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
    {
      type: "object",
      properties: {
        command: { type: "string", description: "The shell command to run" },
        workdir: { type: "string", description: "Working directory (default: process cwd)" },
        timeout: { type: "number", description: "Timeout in milliseconds (default 60000)" },
        description: { type: "string", description: "Human-readable description of the command" },
      },
      required: ["command"],
      additionalProperties: false,
    },
    (args) =>
      new Promise<ToolResult>((resolve) => {
        const command = String(args.command ?? "")
        if (!command) return resolve(err("bash: command is required"))
        const workdir = args.workdir ? String(args.workdir) : process.cwd()
        const timeout = typeof args.timeout === "number" ? args.timeout : 60_000
        const child = spawn(command, { shell: true, cwd: workdir })
        let out = ""
        let timedOut = false
        const timer = setTimeout(() => {
          timedOut = true
          child.kill("SIGKILL")
        }, timeout)
        child.stdout?.on("data", (d) => (out += d.toString()))
        child.stderr?.on("data", (d) => (out += d.toString()))
        child.on("error", (e) => {
          clearTimeout(timer)
          resolve(err(`bash: ${e.message}`))
        })
        child.on("close", (code) => {
          clearTimeout(timer)
          if (timedOut) return resolve(err(`bash: command timed out after ${timeout}ms\n${out}`))
          if (code !== 0) {
            return resolve(err(`${out}\nbash: command exited with code ${code}`, { exitCode: code }))
          }
          resolve(ok(out, { exitCode: code }))
        })
      }),
  )
}

function readTool(): Tool {
  return builtin(
    "read",
    "Read a UTF-8 text file. With offset/limit, return only that line window.",
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
      let content: string
      try {
        content = await fs.readFile(p, "utf8")
      } catch (e) {
        return err(`read: ${e instanceof Error ? e.message : String(e)}`)
      }
      if (args.offset === undefined && args.limit === undefined) return ok(content)
      const lines = content.split("\n")
      const offset = typeof args.offset === "number" ? Math.max(1, Math.trunc(args.offset)) : 1
      const start = offset - 1
      const limit = typeof args.limit === "number" ? Math.max(0, Math.trunc(args.limit)) : lines.length - start
      return ok(lines.slice(start, start + limit).join("\n"))
    },
  )
}

function writeTool(): Tool {
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
      await fs.mkdir(path.dirname(path.resolve(p)), { recursive: true })
      await fs.writeFile(p, content, "utf8")
      const bytes = Buffer.byteLength(content, "utf8")
      return ok(`Wrote ${bytes} bytes to ${p}`, { bytes })
    },
  )
}

function editTool(): Tool {
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
      try {
        content = await fs.readFile(p, "utf8")
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
      await fs.writeFile(p, next, "utf8")
      return ok(`Edited ${p} (${args.replaceAll === true ? count : 1} replacement${(args.replaceAll === true ? count : 1) === 1 ? "" : "s"})`, {
        replacements: args.replaceAll === true ? count : 1,
      })
    },
  )
}

function grepTool(): Tool {
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
      const root = args.path ? String(args.path) : process.cwd()
      const include = args.include ? String(args.include) : undefined
      const limit = typeof args.limit === "number" ? args.limit : 100
      const matches: string[] = []
      for (const file of walkFiles(root)) {
        if (matches.length >= limit) break
        const rel = path.relative(root, file)
        if (include && !matchGlob(rel, include)) continue
        let text: string
        try {
          text = await fs.readFile(file, "utf8")
        } catch {
          continue
        }
        const lines = text.split("\n")
        for (let i = 0; i < lines.length; i++) {
          if (matches.length >= limit) break
          if (re.test(lines[i])) matches.push(`${file}:${i + 1}:${lines[i]}`)
        }
      }
      return ok(matches.join("\n"), { count: matches.length })
    },
  )
}

function globTool(): Tool {
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
      const root = args.path ? String(args.path) : process.cwd()
      const limit = typeof args.limit === "number" ? args.limit : 100
      const found: string[] = []
      for (const file of walkFiles(root)) {
        if (found.length >= limit) break
        const rel = path.relative(root, file)
        if (matchGlob(rel, pattern)) found.push(rel)
      }
      found.sort()
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

function questionTool(): Tool {
  return builtin(
    "question",
    "Ask the host one or more questions. Returns the questions as structured output for the host to answer.",
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
    async (args) => {
      const questions = Array.isArray(args.questions) ? args.questions : []
      return ok(JSON.stringify(questions), { questions })
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

function applyPatchTool(): Tool {
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
      // Stage every write/delete first; only touch the filesystem once all hunks apply.
      const writes: Array<{ path: string; content: string }> = []
      const deletes: string[] = []
      try {
        for (const op of ops) {
          if (op.type === "add") {
            if (existsSync(op.path)) throw new Error(`file already exists: ${op.path}`)
            writes.push({ path: op.path, content: op.content })
          } else if (op.type === "delete") {
            if (!existsSync(op.path)) throw new Error(`file not found: ${op.path}`)
            deletes.push(op.path)
          } else {
            const content = await fs.readFile(op.path, "utf8")
            writes.push({ path: op.path, content: applyUpdate(content, op.body) })
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
      })
    },
  )
}

/**
 * Build the ten built-in tools (each source:"builtin"). The order is fixed
 * for parity: bash, read, write, edit, grep, glob, webfetch, question,
 * apply_patch, todowrite.
 */
export function createBuiltinTools(): Tool[] {
  return [
    bashTool(),
    readTool(),
    writeTool(),
    editTool(),
    grepTool(),
    globTool(),
    webfetchTool(),
    questionTool(),
    applyPatchTool(),
    todowriteTool(),
  ]
}
