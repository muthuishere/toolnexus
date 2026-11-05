/**
 * CLI client styles (SPEC §8C, ADR 0037): an agent CLI the user already has installed —
 * and pays for — becomes the model behind an ordinary in-process client, selected by a
 * closed enum, never by a string.
 *
 * Two transports, one constructor:
 *  - ACP (primary): when the style declares an ACP launch and the binary speaks it, the
 *    user's whole toolkit is served as a local MCP server and handed to the agent in
 *    `session/new` `mcpServers`. The agent calls the tools natively.
 *  - one-shot CLI (fallback): the CLI runs once per model turn with the conversation
 *    rendered under a fixed preamble; its reply is parsed as an OpenAI-shaped envelope and
 *    tool calls go back to the toolnexus loop.
 *
 * A style is data (`clistyles.ts`, byte-identical to `conformance/cli-styles/`) and holds
 * no binary: `command` is always the caller's.
 */
import { spawn } from "node:child_process"
import { randomBytes, timingSafeEqual } from "node:crypto"
import fs from "node:fs"
import http from "node:http"
import os from "node:os"
import path from "node:path"
import { createInterface } from "node:readline"
import type { Readable, Writable } from "node:stream"
import type { AddressInfo } from "node:net"
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js"
import { CLI_STYLE_FILES } from "./clistyles.js"
import { loadACP, killGroup, type ACPClient, type ACPMCPServer } from "./acp.js"
import { buildMcpServer } from "./mcpserve.js"
import {
  Client,
  createInProcessFetch,
  type ClientOptions,
  type Hooks,
  type InProcessOptions,
  type InProcessRequest,
  type InProcessResponse,
  type InProcessToolCall,
  type StreamEvent,
} from "./client.js"
import type { Toolkit } from "./toolkit.js"
import type { Tool, ToolResult } from "./types.js"

/** The fixed one-shot preamble (`conformance/cli-styles/preamble.txt`). */
export const CLI_PREAMBLE: string = CLI_STYLE_FILES["preamble.txt"]

/** One bounded CLI invocation / one ACP prompt, unless `callTimeoutMs` says otherwise. */
export const CLI_DEFAULT_CALL_TIMEOUT_MS = 300000

/** The name the toolkit's `skill` tool is served under to an ACP agent (copilot and devin
 *  ship their own `skill`). */
export const CLI_SERVED_SKILL_TOOL_NAME = "load_skill"

/** Carries the bearer token to a stdio bridge. */
export const CLI_MCP_TOKEN_ENV = "TOOLNEXUS_MCP_TOKEN"

const ACP_SERVER_NAME = "toolnexus"

/** The closed set of CLI families. Each is a shape (argv, prompt mode, answer source, ACP
 *  launch), never a binary. The value is the shared file name; no API accepts a string. */
export enum CliStyles {
  Claude = "claude",
  Codex = "codex",
  Copilot = "copilot",
  Opencode = "opencode",
  Devin = "devin",
  Gemini = "gemini",
  Goose = "goose",
  Custom = "custom",
}

/** How a CLI client reaches the agent. Auto (default): ACP when the style has an ACP launch
 *  and the binary answers `initialize`, else the one-shot CLI. ACP / CLI force one. */
export enum CliTransports {
  Auto = "auto",
  ACP = "acp",
  CLI = "cli",
}

/** A style's ACP launch. */
export interface CliACPLaunch {
  args: string[]
  /** For agents that ignore `session/new` mcpServers: written into a fresh temp session cwd. */
  projectMcpFile?: string
  /** Appended to the system prompt of every ACP turn (a new system message when none). */
  systemHint?: string
  verified?: string
  notes?: string
}

/** One CLI's invocation recipe — data shared by every port. `args` elements are strings or
 *  optional groups (arrays of strings, kept only when a model is set). */
export interface CliStyleDef {
  name: string
  args?: Array<string | string[]>
  prompt?: string
  answer?: string
  timeoutMs?: number
  env?: Record<string, string>
  verified?: string
  notes?: string
  acp?: CliACPLaunch
}

const STYLE_VALUES = new Set<string>(Object.values(CliStyles))

/** The built-in definition of a style (undefined for Custom). */
export function cliStyleDef(style: CliStyles): CliStyleDef | undefined {
  if (style === CliStyles.Custom || !STYLE_VALUES.has(style)) return undefined
  const raw = CLI_STYLE_FILES[`${style}.json`]
  return raw ? (JSON.parse(raw) as CliStyleDef) : undefined
}

/** Reads a user style from a file path, or from JSON text that starts with "{". Pass the
 *  result as `styleDef`. */
export function loadCliStyle(pathOrJson: string): CliStyleDef {
  let data = pathOrJson
  if (!pathOrJson.trim().startsWith("{")) {
    try {
      data = fs.readFileSync(pathOrJson, "utf8")
    } catch (e) {
      throw new Error(`cli style: ${(e as Error).message}`)
    }
  }
  try {
    return JSON.parse(data) as CliStyleDef
  } catch (e) {
    throw new Error(`cli style: ${(e as Error).message}`)
  }
}

/** Options for {@link createCliClient}: every in-process client option (minus `generate`)
 *  plus the CLI's own. `command` is REQUIRED — the library never picks the binary. `model`
 *  is passed to the CLI unchanged (absent ⇒ the CLI's own default). */
export type CliOptions = Omit<InProcessOptions, "generate" | "model"> & {
  /** A built-in recipe. Required unless `styleDef` is set. */
  style?: CliStyles
  /** A user style ({@link loadCliStyle}); wins over `style`. */
  styleDef?: CliStyleDef
  /** The CLI binary, a path or a name on PATH. Required. */
  command: string
  model?: string
  /** For `CliStyles.Custom`. */
  args?: Array<string | string[]>
  prompt?: string
  answer?: string
  /** Added to the child's environment; values expand `${VAR}` at call time, never logged. */
  env?: Record<string, string>
  /** Bounds one CLI invocation or one ACP prompt. Default 300000. */
  callTimeoutMs?: number
  /** The child's working directory (default: this process's). */
  cwd?: string
  /** Default `CliTransports.Auto`. */
  transport?: CliTransports
  /** Served to an ACP agent as a local MCP server. Pass the same toolkit (or none) to run. */
  toolkit?: Toolkit
  /** The full argv that starts the agent in ACP mode (e.g. an adapter); replaces
   *  `[command] + style.acp.args` for the ACP launch only. */
  acpCommand?: string[]
  /** Argv of a stdio⇄HTTP MCP bridge, used only for an agent that accepts no http MCP
   *  servers. The URL is appended; the token goes in env TOOLNEXUS_MCP_TOKEN. Default:
   *  `toolnexus mcp-bridge` when the toolnexus CLI is on PATH. */
  bridgeCommand?: string[]
  /** Observes every raw ACP `session/update` params object. */
  onAcpUpdate?: (params: any) => void
}

/** One answered `session/request_permission`. */
export interface CliPermissionDecision {
  toolCallId: string
  title: string
  allowed: boolean
}

/** A read-only view of the live ACP session. The bearer token is never exposed. */
export interface ACPSessionInfo {
  url: string
  mcpServersSent: any[]
  permissions: CliPermissionDecision[]
  servedCalls: string[]
  sessionCwd: string
}

class CliLaunchError extends Error {}

const PLACEHOLDER_RE = /\{\{([^{}]*)\}\}/g

interface CliElem {
  group: boolean
  parts: string[]
}

interface CliState {
  style: CliStyleDef
  opts: CliOptions
  elems: CliElem[]
  hasCLI: boolean
  env: Record<string, string>
  timeout: number
  acpArgv?: string[]
}

/** An ordinary {@link Client} whose model is an agent CLI, plus `close()`, `transport()`
 *  and `fallbackReason()`. */
export class CliClient extends Client {
  private readonly cli: CliState
  private chosen: CliTransports
  private acp?: CliACPSession
  private opening?: Promise<CliACPSession | undefined>
  private closed = false
  private note = ""

  /** @internal use {@link createCliClient}. */
  constructor(o: CliOptions, style: CliStyleDef, elems: CliElem[], hasCLI: boolean, acpArgv: string[] | undefined) {
    const {
      style: _s, styleDef: _sd, command: _c, args: _a, prompt: _p, answer: _an, env: _e, callTimeoutMs: _t,
      cwd: _cwd, transport: _tr, toolkit: _tk, acpCommand: _ac, bridgeCommand: _b, onAcpUpdate: _u, ...rest
    } = o
    let self: CliClient | undefined
    const inner = createInProcessFetch((req) => self!.generate(req))
    const cliFetch: typeof fetch = async (url, init) => {
      const body = JSON.parse(String((init as any)?.body ?? "{}"))
      if (body.stream) {
        // SPEC §8C: over ACP, a stream is incremental (text, thought and the agent's own
        // tool events as they arrive).
        const s = await self!.session()
        if (s) return createInProcessFetch(undefined, (req, emit) => s.generate(req, emit))(url, init)
        // The one-shot CLI does not stream incrementally, so a stream degrades instead of
        // erroring — run the turn whole and replay it as one SSE chunk. The loop then emits
        // its usual tool_call / tool_result events, the answer as ONE text event, and done.
        delete body.stream
        delete body.stream_options
        const res = await inner(url, { ...(init as any), body: JSON.stringify(body) })
        return cliReplayAsSSE(await res.json())
      }
      return inner(url, init)
    }
    super({
      retries: 0,
      ...(rest as ClientOptions),
      model: o.model || style.name,
      baseUrl: "http://in-process.invalid/v1",
      style: "openai",
      apiKey: "in-process",
      fetch: cliFetch,
    })
    self = this
    const env: Record<string, string> = { ...(style.env ?? {}), ...(o.env ?? {}) }
    let timeout = o.callTimeoutMs ?? 0
    if (!(timeout > 0)) timeout = style.timeoutMs ?? 0
    if (!(timeout > 0)) timeout = CLI_DEFAULT_CALL_TIMEOUT_MS
    this.cli = { style, opts: o, elems, hasCLI, env, timeout, acpArgv }
    const t = o.transport ?? CliTransports.Auto
    this.chosen = t === CliTransports.CLI || !acpArgv ? CliTransports.CLI : t === CliTransports.ACP ? CliTransports.ACP : CliTransports.Auto
  }

  /** "acp", "cli", or "auto" before the first model call has decided. */
  transport(): string {
    return this.chosen
  }

  /** Why Auto fell back from ACP to the one-shot CLI ("" when it did not). */
  fallbackReason(): string {
    return this.note
  }

  /** Ends the ACP agent's process group, stops the served toolkit and removes any temp
   *  session dir. Idempotent; a no-op for the one-shot CLI. */
  async close(): Promise<void> {
    this.closed = true
    const s = this.acp ?? (this.opening ? await this.opening.catch(() => undefined) : undefined)
    this.acp = undefined
    if (s) await s.close()
  }

  /** The live ACP session's info (undefined when one-shot or not yet opened). Header and
   *  env values in `mcpServersSent` are redacted. */
  acpSession(): ACPSessionInfo | undefined {
    const s = this.acp
    if (!s) return undefined
    return {
      url: s.url,
      mcpServersSent: redactServers(s.client?.mcpServersSent ?? []),
      permissions: [...s.log],
      servedCalls: [...s.ran],
      sessionCwd: s.tempDir,
    }
  }

  private async generate(req: InProcessRequest): Promise<InProcessResponse> {
    const s = await this.session()
    if (s) return s.generate(req)
    return parseCliReply(await this.invoke(renderCliPrompt(req.messages, req.tools ?? [])))
  }

  /** The live ACP session, opened on first use; undefined ⇒ the one-shot transport. */
  private async session(): Promise<CliACPSession | undefined> {
    if (this.closed) throw new Error("cli: client is closed")
    if (this.chosen === CliTransports.CLI) return undefined
    if (this.acp) return this.acp
    if (!this.opening) {
      this.opening = openCliACPSession(this.cli).then(
        (s) => {
          this.acp = s
          this.chosen = CliTransports.ACP
          return s
        },
        (e) => {
          this.opening = undefined
          if (this.chosen === CliTransports.Auto && e instanceof CliLaunchError && this.cli.hasCLI) {
            this.chosen = CliTransports.CLI
            this.note = e.message
            return undefined
          }
          throw e instanceof CliLaunchError ? new Error(e.message) : e
        },
      )
    }
    const s = await this.opening
    if (s && this.closed) {
      await s.close()
      throw new Error("cli: client is closed")
    }
    return s
  }

  /** @internal Renders the argv for one invocation, with the temp files it created. */
  cliArgv(prompt: string): { argv: string[]; outPath: string; tmp: string[] } {
    const model = this.cli.opts.model ?? ""
    const tmp: string[] = []
    let filePath = ""
    let outPath = ""
    const temp = (content?: string) => {
      const name = path.join(os.tmpdir(), `toolnexus-cli-${randomBytes(8).toString("hex")}`)
      tmp.push(name)
      if (content !== undefined) fs.writeFileSync(name, content, { mode: 0o600 })
      return name // for {{out}} the CLI creates it; absence ⇒ "no output file"
    }
    const sub = (p: string) =>
      p.replace(PLACEHOLDER_RE, (m, key: string) => {
        switch (key) {
          case "prompt":
            return prompt
          case "model":
            return model
          case "file":
            if (!filePath) filePath = temp(prompt)
            return filePath
          case "out":
            if (!outPath) outPath = temp()
            return outPath
        }
        return m
      })
    const argv = [this.cli.opts.command]
    for (const e of this.cli.elems) {
      if (e.group && !model) continue
      for (const p of e.parts) argv.push(sub(p))
    }
    return { argv, outPath, tmp }
  }

  private async invoke(prompt: string): Promise<string> {
    let r: { argv: string[]; outPath: string; tmp: string[] } = { argv: [], outPath: "", tmp: [] }
    try {
      r = this.cliArgv(prompt)
      return await this.run1(r.argv, r.outPath, prompt)
    } finally {
      for (const f of r.tmp) fs.rmSync(f, { force: true })
    }
  }

  private run1(argv: string[], outPath: string, prompt: string): Promise<string> {
    const { style, opts, env, timeout } = this.cli
    const bin = lookPath(argv[0])
    if (!bin) return Promise.reject(new Error(`cli: command not found: ${argv[0]}`))
    return new Promise((resolve, reject) => {
      const child = spawn(bin, argv.slice(1), {
        cwd: opts.cwd || undefined,
        env: Object.keys(env).length ? { ...process.env, ...expandCliEnv(env) } : process.env,
        stdio: ["pipe", "pipe", "pipe"],
        detached: process.platform !== "win32",
      })
      let stdout = ""
      let stderr = ""
      let timedOut = false
      child.stdout!.setEncoding("utf8").on("data", (d: string) => (stdout += d))
      child.stderr!.setEncoding("utf8").on("data", (d: string) => (stderr += d))
      child.stdin!.on("error", () => {})
      child.stdin!.end(style.prompt === "stdin" ? prompt : "")
      const timer = setTimeout(() => {
        timedOut = true
        if (child.pid !== undefined) killGroup(child.pid, "SIGKILL")
      }, timeout)
      child.on("error", () => {
        clearTimeout(timer)
        reject(new Error(`cli: command not found: ${argv[0]}`))
      })
      child.on("close", (code, signal) => {
        clearTimeout(timer)
        if (timedOut) return reject(new Error(`cli: timed out after ${timeout}ms`))
        if (code !== 0) {
          let msg = stderr.trim()
          const exit = code ?? (signal ? 128 + (os.constants.signals[signal] ?? 0) : -1)
          if (!msg) return reject(new Error(`cli: exit ${exit}`))
          const cps = Array.from(msg)
          if (cps.length > 1000) msg = cps.slice(0, 1000).join("") + "…"
          return reject(new Error(`cli: exit ${exit}: ${msg}`))
        }
        let text = stdout
        if (style.answer === "out") {
          try {
            text = fs.readFileSync(outPath, "utf8")
          } catch {
            return reject(new Error("cli: no output file"))
          }
        }
        if (!text.trim()) return reject(new Error("cli: empty reply"))
        resolve(text)
      })
    })
  }
}

/**
 * Builds a client whose model is an installed agent CLI. Validates everything it can at
 * construction; the transport (ACP or one-shot) is decided on the first model call.
 */
export function createCliClient(o: CliOptions): CliClient {
  if (!o || !o.command) throw new Error("cli: Command is required (path or name of the CLI binary)")
  let d: CliStyleDef | undefined
  if (o.styleDef) d = o.styleDef
  else if (o.style === CliStyles.Custom) d = { name: "custom", args: o.args, prompt: o.prompt, answer: o.answer }
  else if (o.style !== undefined && STYLE_VALUES.has(o.style)) d = cliStyleDef(o.style)
  if (!d) throw new Error("cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)")

  let acpArgv: string[] | undefined
  if (o.acpCommand && o.acpCommand.length > 0) acpArgv = [...o.acpCommand]
  else if (d.acp) acpArgv = [o.command, ...(d.acp.args ?? [])]
  const t = o.transport ?? CliTransports.Auto
  if (t === CliTransports.ACP && !acpArgv) {
    throw new Error(`cli: style ${JSON.stringify(d.name)} has no ACP launch; set ACPCommand or use CliTransports.CLI`)
  }
  let elems: CliElem[] = []
  let hasCLI = false
  if (t !== CliTransports.ACP) {
    const acpOnly = d.args === undefined && !d.prompt && !d.answer && acpArgv && t === CliTransports.Auto
    if (!acpOnly) {
      elems = normalizeCliArgs(d.args ?? [])
      validateCliStyle(d, elems, o.model ?? "")
      hasCLI = true
    }
  }
  return new CliClient(o, d, elems, hasCLI, acpArgv)
}

// --------------------------------------------------------------------------- //
// Argv templating and validation.
// --------------------------------------------------------------------------- //

function normalizeCliArgs(args: unknown[]): CliElem[] {
  return args.map((e) => {
    if (typeof e === "string") return { group: false, parts: [e] }
    if (Array.isArray(e)) {
      if (!e.every((x) => typeof x === "string")) throw new Error("cli: a group may only hold strings")
      return { group: true, parts: e as string[] }
    }
    throw new Error("cli: an arg must be a string or a group of strings")
  })
}

function validateCliStyle(d: CliStyleDef, elems: CliElem[], model: string): void {
  if (!["stdin", "arg", "file"].includes(d.prompt ?? "")) {
    throw new Error(`cli: prompt must be stdin, arg or file, not ${JSON.stringify(d.prompt ?? "")}`)
  }
  if (!["stdout", "out"].includes(d.answer ?? "")) {
    throw new Error(`cli: answer must be stdout or out, not ${JSON.stringify(d.answer ?? "")}`)
  }
  const seen = new Set<string>()
  for (const e of elems) {
    for (const p of e.parts) {
      for (const m of p.matchAll(PLACEHOLDER_RE)) {
        const k = m[1]
        if (k === "model") {
          if (!e.group && !model) throw new Error("cli: the style needs a model: {{model}} is outside an optional group")
        } else if (k !== "prompt" && k !== "file" && k !== "out") {
          throw new Error(`cli: unknown placeholder {{${k}}}`)
        }
        seen.add(k)
      }
    }
  }
  if (model && !seen.has("model")) throw new Error(`cli: style ${JSON.stringify(d.name)} takes no model`)
  if (d.prompt === "arg" && !seen.has("prompt")) throw new Error('cli: prompt "arg" needs {{prompt}} in args')
  if (d.prompt === "file" && !seen.has("file")) throw new Error('cli: prompt "file" needs {{file}} in args')
  if (d.answer === "out" && !seen.has("out")) throw new Error('cli: answer "out" needs {{out}} in args')
}

// --------------------------------------------------------------------------- //
// The envelope: render and tolerant parse.
// --------------------------------------------------------------------------- //

/** PREAMBLE + "\nREQUEST:\n" + {"messages","tools"} as compact JSON (no HTML escaping —
 *  JSON.stringify never escapes `<`, `>` or `&`). */
export function renderCliPrompt(messages: any[] | undefined, tools: any[] | undefined): string {
  return CLI_PREAMBLE + "\nREQUEST:\n" + JSON.stringify({ messages: messages ?? [], tools: tools ?? [] })
}

/** Parses one JSON object spanning the whole text; trailing text is a failure. */
function parseObject(s: string): Record<string, any> | undefined {
  if (!s.trim()) return undefined
  try {
    const v = JSON.parse(s)
    return v && typeof v === "object" && !Array.isArray(v) ? v : undefined
  } catch {
    return undefined
  }
}

/**
 * The tolerant six-step envelope parser: (1) trim; (2) strip one markdown fence; (3) parse
 * the whole text as one object, else (4) the span first "{" … last "}"; (5) unwrap
 * `choices[0].message` or `message`; (6) `tool_calls` (flat or `{function:{}}`, string or
 * object arguments) win, else `content`. Anything else is the raw text as content.
 */
export function parseCliReply(text: string): InProcessResponse {
  let s = text.trim()
  if (s.startsWith("```")) {
    const i = s.indexOf("\n")
    s = i >= 0 ? s.slice(i + 1) : ""
    s = s.trim()
    if (s.endsWith("```")) s = s.slice(0, -3)
    s = s.trim()
  }
  let obj = parseObject(s)
  if (!obj) {
    const i = s.indexOf("{")
    const j = s.lastIndexOf("}")
    if (i >= 0 && j > i) obj = parseObject(s.slice(i, j + 1))
  }
  if (!obj) return { content: text }
  if (Array.isArray(obj.choices)) {
    const m = obj.choices[0]?.message
    if (obj.choices.length > 0 && m && typeof m === "object" && !Array.isArray(m)) obj = m
  } else if (obj.message && typeof obj.message === "object" && !Array.isArray(obj.message)) {
    obj = obj.message
  }
  const o = obj as Record<string, any>
  if (Array.isArray(o.tool_calls)) {
    const calls: InProcessToolCall[] = []
    for (const e of o.tool_calls) {
      if (!e || typeof e !== "object" || Array.isArray(e)) continue
      const fn = e.function && typeof e.function === "object" && !Array.isArray(e.function) ? e.function : e
      if (typeof fn.name !== "string" || !fn.name) continue
      const call: InProcessToolCall = { name: fn.name, arguments: fn.arguments ?? {} }
      if (typeof e.id === "string" && e.id) call.id = e.id
      calls.push(call)
    }
    if (calls.length > 0) return { toolCalls: calls }
  }
  if ("content" in o) {
    const c = o.content
    if (typeof c === "string") return { content: c }
    if (c === null) return { content: "" }
    return { content: JSON.stringify(c) }
  }
  return { content: text }
}

// --------------------------------------------------------------------------- //
// Process helpers.
// --------------------------------------------------------------------------- //

const ENV_REF_RE = /\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g

function expandCliEnv(env: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(env)) out[k] = v.replace(ENV_REF_RE, (_m, n: string) => process.env[n] ?? "")
  return out
}

/** Resolves a command like exec.LookPath: a path is checked as-is, a name is searched on PATH. */
function lookPath(cmd: string): string | undefined {
  const exe = (p: string) => {
    try {
      fs.accessSync(p, fs.constants.X_OK)
      return fs.statSync(p).isFile()
    } catch {
      return false
    }
  }
  if (cmd.includes("/") || cmd.includes(path.sep)) return exe(cmd) ? cmd : undefined
  const exts = process.platform === "win32" ? (process.env.PATHEXT ?? ".EXE;.CMD;.BAT").split(";") : [""]
  for (const dir of (process.env.PATH ?? "").split(path.delimiter)) {
    if (!dir) continue
    for (const ext of exts) {
      const p = path.join(dir, cmd + ext)
      if (exe(p)) return p
    }
  }
  return undefined
}

function redactServers(list: any[]): any[] {
  const out = JSON.parse(JSON.stringify(list))
  for (const e of out) {
    for (const k of ["headers", "env"]) {
      if (Array.isArray(e?.[k])) for (const nv of e[k]) if (nv && typeof nv === "object") nv.value = "[redacted]"
    }
  }
  return out
}

// --------------------------------------------------------------------------- //
// ACP transport — the toolkit goes in as MCP.
// --------------------------------------------------------------------------- //

interface CliACPSession {
  client?: ACPClient
  url: string
  tempDir: string
  log: CliPermissionDecision[]
  ran: string[]
  generate(req: InProcessRequest, emit?: (event: StreamEvent) => void): Promise<InProcessResponse>
  close(): Promise<void>
}

async function openCliACPSession(cli: CliState): Promise<CliACPSession> {
  const argv = cli.acpArgv!
  const bin = lookPath(argv[0])
  if (!bin) throw new CliLaunchError(`cli: command not found: ${argv[0]}`)
  const o = cli.opts
  const token = randomBytes(32).toString("hex")
  const served = new Set<string>()
  const calls = new Map<string, string>()
  const decisions = new Map<string, boolean>()
  const log: CliPermissionDecision[] = []
  const ran: string[] = []
  const hint = cli.style.acp?.systemHint ?? ""

  const tools = cliServedTools(o.toolkit, o.hooks, (n) => ran.push(n))
  for (const t of tools) served.add(t.name)
  const srv = await serveToolkit(tools, token)
  let tempDir = ""
  let client: ACPClient | undefined

  const close = async () => {
    if (client) await client.close()
    await srv.stop()
    if (tempDir) fs.rmSync(tempDir, { recursive: true, force: true })
  }

  try {
    let cwd = path.resolve(o.cwd || process.cwd())
    let servers: ACPMCPServer[] = []
    const projectFile = cli.style.acp?.projectMcpFile
    if (projectFile && !(o.acpCommand && o.acpCommand.length)) {
      tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "toolnexus-acp-"))
      cwd = tempDir
      const f = path.join(tempDir, projectFile)
      fs.mkdirSync(path.dirname(f), { recursive: true, mode: 0o700 })
      const body = { mcpServers: { [ACP_SERVER_NAME]: { type: "http", url: srv.url, headers: { Authorization: "Bearer " + token } } } }
      fs.writeFileSync(f, JSON.stringify(body), { mode: 0o600 })
    } else {
      const entry: ACPMCPServer = { name: ACP_SERVER_NAME, url: srv.url, headers: { Authorization: "Bearer " + token } }
      let bridge = o.bridgeCommand
      if (!bridge || bridge.length === 0) {
        const p = lookPath("toolnexus")
        if (p) bridge = [p, "mcp-bridge"]
      }
      if (bridge && bridge.length > 0) {
        entry.command = bridge[0]
        entry.args = [...bridge.slice(1), srv.url]
        entry.env = { [CLI_MCP_TOKEN_ENV]: token }
      }
      servers = [entry]
    }

    let stderr = ""
    const permission = (params: any): string | undefined => {
      const id: string = params?.toolCall?.toolCallId ?? ""
      let title: string = params?.toolCall?.title ?? ""
      if (!title) title = calls.get(id) ?? ""
      let allowed = decisions.get(id)
      if (allowed === undefined) {
        allowed = id !== "" && cliIsServedCall(title, served)
        if (id) decisions.set(id, allowed)
      }
      log.push({ toolCallId: id, title, allowed })
      if (!allowed) return undefined
      const options: Array<{ optionId: string; kind?: string }> = params?.options ?? []
      return (options.find((x) => x.kind === "allow_once") ?? options.find((x) => x.kind?.startsWith("allow")))?.optionId
    }
    const update = (params: any) => {
      const u = params?.update
      if (u && (u.sessionUpdate === "tool_call" || u.sessionUpdate === "tool_call_update") && u.toolCallId && u.title) {
        if (!calls.has(u.toolCallId)) calls.set(u.toolCallId, u.title)
      }
      o.onAcpUpdate?.(params)
    }
    try {
      client = await loadACP({
        command: bin,
        args: argv.slice(1),
        env: Object.keys(cli.env).length ? expandCliEnv(cli.env) : undefined,
        cwd,
        permissionTimeoutMs: cli.timeout,
        requestTimeoutMs: cli.timeout,
        onPermission: permission,
        onUpdate: update,
        mcpServers: servers,
        model: o.model || undefined,
        stderr: (d) => {
          stderr = (stderr + d).slice(-4096)
        },
      })
    } catch (e) {
      let msg = (e as Error).message
      // let the stderr drain a moment: the agent usually says why it died
      await new Promise((r) => setTimeout(r, 20))
      if (stderr.trim()) msg += ": " + stderr.trim()
      msg = msg.replace("toolnexus: acp:", "cli: acp:")
      if (/^cli: acp: (start|initialize)/.test(msg)) throw new CliLaunchError(msg)
      throw new Error(msg)
    }
  } catch (e) {
    await close()
    throw e
  }

  const acp = client!
  return {
    client: acp,
    url: srv.url,
    tempDir,
    log,
    ran,
    close,
    async generate(req, emit) {
      try {
        return await acp.generateStream({ ...req, messages: cliWithSystemHint(req.messages, hint) }, emit ?? (() => {}))
      } catch (e) {
        const m = (e as Error).message
        if (/^toolnexus: ACP turn timed out/.test(m)) throw new Error(`cli: timed out after ${cli.timeout}ms`)
        throw e
      }
    },
  }
}

/** Appends a style's ACP system hint to the first system message (a copy; the caller's
 *  messages are not mutated), or prepends a system message holding only the hint. */
export function cliWithSystemHint(msgs: any[], hint: string): any[] {
  if (!hint) return msgs
  const first = msgs[0]
  if (first && typeof first === "object" && first.role === "system" && typeof first.content === "string") {
    return [{ ...first, content: first.content === "" ? hint : first.content + "\n\n" + hint }, ...msgs.slice(1)]
  }
  return [{ role: "system", content: hint }, ...msgs]
}

/** Matches a permission title against the exact spellings agents use for a tool of OUR
 *  server: `<tool>`, `mcp__toolnexus__<tool>`, `toolnexus/<tool>`, `<tool> (toolnexus MCP
 *  Server)`, `Calling <tool> from toolnexus`, or `<tool>(…)`. No substring matching. */
export function cliIsServedCall(title: string, served: Set<string> | Iterable<string>): boolean {
  if (!title) return false
  for (const n of served) {
    if (
      title === n ||
      title === `mcp__${ACP_SERVER_NAME}__${n}` ||
      title === `${ACP_SERVER_NAME}/${n}` ||
      title === `${n} (${ACP_SERVER_NAME} MCP Server)` ||
      title === `Calling ${n} from ${ACP_SERVER_NAME}`
    ) {
      return true
    }
    if (title.startsWith(n + "(") && title.endsWith(")")) return true
  }
  return false
}

/** The toolkit as served to the agent: `skill` renamed to `load_skill` with the skills list
 *  in its description; every execute runs through hooks.beforeTool / afterTool. */
function cliServedTools(tk: Toolkit | undefined, hooks: Hooks | undefined, ran: (n: string) => void): Tool[] {
  if (!tk) return []
  return tk.tools().map((t) => {
    const name = t.name
    let servedName = t.name
    let description = t.description
    if (t.source === "skill" && t.name === "skill") {
      servedName = CLI_SERVED_SKILL_TOOL_NAME
      const sp = tk.skillsPrompt()
      if (sp) description = description + "\n\n" + sp
    }
    const execute = async (args: Record<string, unknown>, ctx?: any): Promise<ToolResult> => {
      if (hooks?.beforeTool) {
        try {
          const ov = await hooks.beforeTool({ name, args, turn: 0, hints: t.hints })
          if (ov && ov.result) return ov.result
          if (ov && ov.args) args = ov.args
        } catch (e) {
          return { output: (e as Error).message ?? String(e), isError: true }
        }
      }
      ran(servedName)
      let res: ToolResult
      try {
        res = await t.execute(args, ctx)
      } catch (e) {
        res = { output: (e as Error).message ?? String(e), isError: true }
      }
      if (hooks?.afterTool) {
        try {
          const ov = await hooks.afterTool({ name, args, result: res, turn: 0 })
          if (ov && ov.result) res = ov.result
        } catch (e) {
          return { output: (e as Error).message ?? String(e), isError: true }
        }
      }
      return res
    }
    return { ...t, name: servedName, description, execute }
  })
}

/** Serves `tools` as streamable-HTTP MCP on 127.0.0.1:<ephemeral>/mcp behind a bearer token. */
async function serveToolkit(tools: Tool[], token: string): Promise<{ url: string; stop(): Promise<void> }> {
  const want = Buffer.from("Bearer " + token)
  const server = http.createServer((req, res) => {
    const u = req.url ?? ""
    if (u !== "/mcp" && !u.startsWith("/mcp?")) {
      res.writeHead(404).end("not found")
      return
    }
    const got = Buffer.from(String(req.headers.authorization ?? ""))
    if (got.length !== want.length || !timingSafeEqual(got, want)) {
      res.writeHead(401, { "WWW-Authenticate": "Bearer" }).end("unauthorized")
      return
    }
    void (async () => {
      const body = req.method === "POST" ? await readBody(req) : ""
      let parsed: unknown
      try {
        parsed = body ? JSON.parse(body) : undefined
      } catch {
        parsed = undefined
      }
      const mcp = buildMcpServer(tools, { name: ACP_SERVER_NAME })
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined })
      res.on("close", () => {
        void transport.close()
        void mcp.close()
      })
      await mcp.connect(transport)
      await transport.handleRequest(req, res, parsed)
    })().catch(() => {
      if (!res.headersSent) res.writeHead(500).end("mcp error")
    })
  })
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve())
  })
  const port = (server.address() as AddressInfo).port
  return {
    url: `http://127.0.0.1:${port}/mcp`,
    stop: () =>
      new Promise<void>((resolve) => {
        server.close(() => resolve())
        server.closeAllConnections()
      }),
  }
}

function readBody(req: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let data = ""
    req.setEncoding("utf8")
    req.on("data", (c: string) => (data += c))
    req.on("end", () => resolve(data))
    req.on("error", reject)
  })
}

/**
 * The stdio side of a stdio McpServer entry: each JSON-RPC line read from `input` is POSTed
 * to the toolkit's streamable-HTTP endpoint (with the bearer token when set), and each reply
 * — plain JSON or SSE `data:` frames — is written to `output` as one line. Resolves when
 * `input` ends.
 */
export async function runMcpBridge(url: string, token: string, input: Readable, output: Writable): Promise<void> {
  const rl = createInterface({ input, crlfDelay: Infinity })
  let session = ""
  for await (const raw of rl) {
    const line = raw.trim()
    if (!line) continue
    const headers: Record<string, string> = {
      "content-type": "application/json",
      accept: "application/json, text/event-stream",
    }
    if (token) headers.authorization = "Bearer " + token
    if (session) headers["mcp-session-id"] = session
    const resp = await fetch(url, { method: "POST", headers, body: line })
    const sid = resp.headers.get("mcp-session-id")
    if (sid) session = sid
    const body = await resp.text()
    if (resp.status === 401) throw new Error(`mcp-bridge: unauthorized (missing or wrong ${CLI_MCP_TOKEN_ENV})`)
    if ((resp.headers.get("content-type") ?? "").startsWith("text/event-stream")) {
      for (const l of body.split("\n")) {
        const t = l.replace(/\r$/, "")
        if (t.startsWith("data:")) output.write(t.slice(5).trim() + "\n")
      }
      continue
    }
    const b = body.trim()
    if (b.startsWith("{")) output.write(b + "\n")
  }
}

/** Re-encodes one whole OpenAI chat completion as a single-chunk SSE stream (the
 *  non-incremental stream of SPEC §8C). */
function cliReplayAsSSE(j: any): Response {
  const msg = j?.choices?.[0]?.message ?? {}
  const delta: Record<string, any> = {}
  if (msg.tool_calls?.length) {
    delta.tool_calls = msg.tool_calls.map((c: any, index: number) => ({ index, ...c }))
  } else {
    delta.content = msg.content ?? ""
  }
  const chunks = [
    { choices: [{ index: 0, delta, finish_reason: msg.tool_calls?.length ? "tool_calls" : "stop" }] },
    { choices: [], usage: j?.usage ?? { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 } },
  ]
  const sse = chunks.map((c) => `data: ${JSON.stringify(c)}\n\n`).join("") + "data: [DONE]\n\n"
  return new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } })
}
