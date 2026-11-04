/**
 * ACP (Agent Client Protocol) model source — issue #96, ADR 0031
 * ("ACP: the warm session is the feature, and it breaks the stateless request"),
 * openspec/changes/add-acp-model-source.
 *
 * ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per line,
 * over a child process's stdin/stdout (`devin acp`, Gemini CLI, Zed's agents all
 * speak it). This file ships ACP as nothing more than a `generate` function —
 * the exact shape {@link InProcessOptions.generate} already defines — so the
 * tool-calling loop, skills, MCP tools, adapters and sub-agents are entirely
 * untouched. `loadACP` opens the connection once (`initialize` → `session/new` →
 * optional `session/set_mode`) and hands back an {@link ACPClient} whose
 * `.generate` can be passed straight to {@link createInProcessClient}.
 *
 * The hard part is not the JSON-RPC plumbing — it is that an ACP session is
 * *stateful* while every other toolnexus model source is stateless-by-default.
 * toolnexus assembles a complete request every turn; sending the whole thing
 * into a session that already remembers the transcript makes some agents answer
 * a near-duplicate prompt from stale history (reproduced in `spikes/acp/`).
 * The mitigation (kept as the default, not left to the caller) is an explicit
 * "this supersedes everything earlier" marker appended to every rendered
 * prompt — see {@link renderPrompt}.
 *
 * Three more traps this client exists to close, all proven in the spike:
 *  - `session/update` notifications interleave with JSON-RPC replies on the
 *    same stdout stream, so everything arriving is demultiplexed by id.
 *  - Only `agent_message_chunk` text may be accumulated into the reply —
 *    `agent_thought_chunk` and tool-call narration wrap prose around
 *    structured output and corrupt it if mixed in.
 *  - An unanswered `session/request_permission` hangs the turn FOREVER, even
 *    in an agent's bypass mode. The client answers with the first `allow`-kind
 *    option the instant the request arrives; `permissionTimeoutMs` is only a
 *    safety net for a broken agent that never asks and never replies at all.
 */
import { spawn } from "node:child_process"
import { createInterface } from "node:readline"
import path from "node:path"
import type { InProcessRequest, InProcessResponse } from "./client.js"

/** Options for {@link loadACP}. `command`/`args` spawn the ACP agent as a child
 *  process; everything else tunes the session handshake. */
export interface ACPOptions {
  /** The ACP agent binary, e.g. `"devin"`. */
  command: string
  /** Arguments to the agent, e.g. `["acp"]`. */
  args?: string[]
  /** Working directory for `session/new`. Real ACP agents (e.g. `devin acp`)
   *  reject a relative `cwd` with `-32602 Invalid params` — this is always
   *  resolved to an absolute path before it is sent. Default: `process.cwd()`. */
  cwd?: string
  /** Extra environment variables for the child, merged over the current
   *  process environment. */
  env?: Record<string, string>
  /** ACP `initialize` protocolVersion. Default `1`. */
  protocolVersion?: number
  /** Safety-net timeout (ms) for one `session/prompt` turn — NOT specifically
   *  for the permission handshake, which this client always answers instantly.
   *  It exists only so a broken/unresponsive agent cannot hang a caller
   *  forever. Default 30000. */
  permissionTimeoutMs?: number
  /** Optional `session/set_mode` sent once, right after `session/new`. */
  mode?: string
  /** Sent in `session/new` `mcpServers` (absent ⇒ `[]`). An entry with both `url` and
   *  `command` is resolved against the agent's `initialize` result: the http shape when it
   *  advertises `agentCapabilities.mcpCapabilities.http`, else the stdio shape (SPEC §8C). */
  mcpServers?: ACPMCPServer[]
  /** Sent as `initialize` `clientCapabilities._meta` (vendor flags). Opaque to toolnexus. */
  clientCapsMeta?: Record<string, unknown>
  /** Passed through unchanged after `session/new`: `session/set_config_option`
   *  `{configId:"model"}`, then `session/set_model` if the agent rejects that. Both failing
   *  fails `loadACP`. */
  model?: string
  /** Receives the agent's stderr (default: discarded). */
  stderr?: (chunk: string) => void
  /** Decides each `session/request_permission`: return an `optionId` to select it, or
   *  `""`/undefined to answer with the first `reject*` option. Absent ⇒ the first `allow*`
   *  option, as before. */
  onPermission?: (params: any) => string | undefined | Promise<string | undefined>
  /** Observes every raw `session/update` params object (must not throw). */
  onUpdate?: (params: any) => void
  /** Bounds each handshake request (`initialize`, `session/new`, …). Absent ⇒ unbounded;
   *  a request still fails at once when the agent exits. */
  requestTimeoutMs?: number
}

/** One `session/new` `mcpServers` entry. `url` ⇒ the http shape
 *  `{type:"http",name,url,headers[]}`; `command` ⇒ the stdio shape `{name,command,args[],env[]}`
 *  (no `type`); both ⇒ the agent's advertised http capability picks. `headers`/`env` go on the
 *  wire as `[{name,value}]` arrays sorted by name; their values are never logged. */
export interface ACPMCPServer {
  name: string
  url?: string
  headers?: Record<string, string>
  command?: string
  args?: string[]
  env?: Record<string, string>
}

function acpNameValues(m: Record<string, string> | undefined): Array<{ name: string; value: string }> {
  return Object.keys(m ?? {})
    .sort()
    .map((name) => ({ name, value: (m as Record<string, string>)[name] }))
}

/** The ACP wire shape of one entry for an agent that does (`httpOK`) or does not accept http. */
export function acpServerWire(s: ACPMCPServer, httpOK: boolean): Record<string, unknown> {
  if (s.url && (httpOK || !s.command)) {
    return { type: "http", name: s.name, url: s.url, headers: acpNameValues(s.headers) }
  }
  return { name: s.name, command: s.command ?? "", args: s.args ?? [], env: acpNameValues(s.env) }
}

/** One warm ACP connection: one child process, one session, alive across many
 *  turns. `.generate` has exactly the shape {@link InProcessOptions.generate}
 *  wants, so it plugs straight into {@link createInProcessClient}. */
export interface ACPClient {
  generate: (request: InProcessRequest) => Promise<InProcessResponse>
  /** Terminates the agent's process group. Idempotent — a second call is a no-op. */
  close: () => Promise<void>
  /** The exact `mcpServers` list sent in `session/new`. */
  readonly mcpServersSent: any[]
  /** The agent's raw `initialize` result. */
  readonly initializeResult: any
}

/** The literal marker every rendered prompt ends with, naming the current
 *  turn as the one to answer. Kept as a named constant (not just inlined)
 *  because the exact wording is shared, by convention, with the other
 *  language ports and the spike that proved it fixes the stale-answer bug. */
const SUPERSEDES_MARKER = "SUPERSEDES-ALL-PRIOR:"

interface RpcMessage {
  jsonrpc?: string
  id?: string | number
  method?: string
  params?: any
  result?: any
  error?: { code: number; message: string }
}

function renderContentPart(part: any): string {
  if (typeof part === "string") return part
  if (part == null) return ""
  if (typeof part === "object" && "text" in part && typeof part.text === "string") return part.text
  return JSON.stringify(part)
}

/** Flattens `content` (a string, or an array of content parts) to plain text. */
function renderContent(content: any): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) return content.map(renderContentPart).join(" ")
  return renderContentPart(content)
}

/** Renders the FULL assembled request as one prompt string — role + content
 *  per message — then appends the supersedes marker naming the latest user
 *  turn, per ADR 0031's proposed (and spike-confirmed) default: full request
 *  every turn, not a delta, so this stays stateless like every other
 *  toolnexus model source and never risks a shadow transcript drifting from
 *  the caller's own conversation state. */
function renderPrompt(request: InProcessRequest): string {
  const messages = request.messages ?? []
  const lines = messages.map((m) => `${m?.role ?? "user"}: ${renderContent(m?.content)}`)

  let latest = ""
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") {
      latest = renderContent(messages[i].content)
      break
    }
  }
  if (!latest && messages.length > 0) latest = renderContent(messages[messages.length - 1]?.content)

  lines.push(`${SUPERSEDES_MARKER} ${latest}`)
  return lines.join("\n")
}

/**
 * Opens one ACP connection: spawns `command`, performs `initialize` →
 * `session/new` (with an absolute `cwd` and an `mcpServers` array — the exact
 * trap real `devin acp` enforces with `-32602` otherwise) → an optional
 * `session/set_mode`, then returns a client whose `.generate` sends exactly
 * one `session/prompt` per call, accumulating only `agent_message_chunk`
 * text.
 */
export async function loadACP(opts: ACPOptions): Promise<ACPClient> {
  const cwd = opts.cwd ? path.resolve(opts.cwd) : process.cwd()
  const permissionTimeoutMs = opts.permissionTimeoutMs ?? 30000

  // Its own process group (detached), so close() reaches an adapter's children too — an
  // `npx …` launch is a tree, not one process.
  const child = spawn(opts.command, opts.args ?? [], {
    cwd,
    env: opts.env ? { ...process.env, ...opts.env } : process.env,
    stdio: ["pipe", "pipe", opts.stderr ? "pipe" : "ignore"],
    detached: process.platform !== "win32",
  })
  if (opts.stderr) {
    child.stderr!.setEncoding("utf8")
    child.stderr!.on("data", (d: string) => {
      try {
        opts.stderr!(d)
      } catch {
        // a host sink never breaks the session
      }
    })
  }

  let closed = false
  let nextId = 0
  const pending = new Map<string, { method: string; cb: (msg: RpcMessage) => void }>()
  let updateSink: ((params: any) => void) | null = null
  let exited = false
  let startError: Error | undefined
  const childGone = new Promise<void>((resolve) => {
    child.once("exit", () => resolve())
    child.once("error", () => resolve())
  })

  // Fail fast: a request still waiting when the agent's stdout closes fails at once
  // instead of waiting out a timeout. setImmediate lets a spawn `error` land first.
  const failPending = () => {
    exited = true
    for (const [id, p] of pending) {
      pending.delete(id)
      p.cb({ error: { code: 0, message: exitMessage(p.method) } })
    }
  }
  const exitMessage = (method: string) =>
    startError
      ? `toolnexus: acp: start ${JSON.stringify(opts.command)}: ${startError.message}`
      : `toolnexus: acp: agent exited before replying to ${method}`
  child.on("error", (e) => {
    startError = e
    setImmediate(failPending)
  })
  child.stdin!.on("error", () => {
    // EPIPE after the agent died: surfaced through failPending, never as a crash.
  })

  const rl = createInterface({ input: child.stdout! })
  rl.on("close", () => setImmediate(failPending))
  rl.on("line", (raw) => {
    const line = raw.trim()
    if (!line) return
    let msg: RpcMessage
    try {
      msg = JSON.parse(line)
    } catch {
      return
    }
    handleLine(msg)
  })

  function write(msg: Record<string, unknown>): void {
    if (exited || child.stdin!.destroyed) return
    child.stdin!.write(JSON.stringify(msg) + "\n")
  }

  function reply(id: unknown, result: unknown): void {
    write({ jsonrpc: "2.0", id, result })
  }

  function call(method: string, params: unknown): Promise<any> {
    const id = `c-${++nextId}`
    return new Promise((resolve, reject) => {
      if (exited) {
        reject(new Error(exitMessage(method)))
        return
      }
      let timer: NodeJS.Timeout | undefined
      pending.set(id, {
        method,
        cb: (msg) => {
          clearTimeout(timer)
          if (msg.error && msg.error.code === 0) reject(new Error(msg.error.message))
          else if (msg.error) reject(new Error(`toolnexus: ACP error ${msg.error.code}: ${msg.error.message}`))
          else resolve(msg.result)
        },
      })
      if (opts.requestTimeoutMs && opts.requestTimeoutMs > 0) {
        const ms = opts.requestTimeoutMs
        timer = setTimeout(() => {
          pending.delete(id)
          reject(new Error(`toolnexus: acp: ${method} timed out after ${ms}ms`))
        }, ms)
      }
      write({ jsonrpc: "2.0", id, method, params })
    })
  }

  /** Demultiplexes EVERYTHING arriving on stdout: replies to our requests
   *  (matched by JSON-RPC id), `session/update` notifications (interleaved
   *  with replies, routed to whichever turn is in flight), and
   *  server-initiated requests like `session/request_permission`. */
  function handleLine(msg: RpcMessage): void {
    if (msg.method === undefined && msg.id !== undefined) {
      const p = pending.get(String(msg.id))
      if (p) {
        pending.delete(String(msg.id))
        p.cb(msg)
      }
      return
    }
    if (msg.method === "session/update") {
      if (opts.onUpdate) {
        try {
          opts.onUpdate(msg.params)
        } catch {
          // an observer never breaks the session
        }
      }
      updateSink?.(msg.params)
      return
    }
    if (msg.method === "session/request_permission" && msg.id !== undefined) {
      void answerPermission(msg)
      return
    }
    // An agent->client REQUEST we do not implement (fs/*, terminal/* — declared
    // unsupported in clientCapabilities). Answer it, or the agent waits forever.
    if (msg.id !== undefined) {
      write({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: `method not found: ${msg.method}` } })
    }
  }

  /** Answers with the FIRST option whose `kind` starts with `allow` — an
   *  unanswered permission request hangs the turn forever (proven in
   *  spikes/acp). This runs the instant the request arrives, never waiting
   *  for anything else. */
  async function answerPermission(req: RpcMessage): Promise<void> {
    const options: Array<{ optionId: string; kind?: string }> = req.params?.options ?? []
    let chosen: { optionId: string } | undefined
    if (opts.onPermission) {
      let picked: string | undefined
      try {
        picked = await opts.onPermission(req.params)
      } catch {
        picked = undefined
      }
      chosen = picked
        ? { optionId: picked }
        : options.find((o) => typeof o.kind === "string" && o.kind.startsWith("reject"))
    } else {
      chosen = options.find((o) => typeof o.kind === "string" && o.kind.startsWith("allow"))
    }
    const result = chosen
      ? { outcome: { outcome: "selected", optionId: chosen.optionId } }
      : { outcome: { outcome: "cancelled" } }
    reply(req.id, result)
  }

  const clientCapabilities: Record<string, unknown> = {
    fs: { readTextFile: false, writeTextFile: false },
    terminal: false,
  }
  if (opts.clientCapsMeta) clientCapabilities._meta = opts.clientCapsMeta
  const step = async <T>(label: string, fn: () => Promise<T>): Promise<T> => {
    try {
      return await fn()
    } catch (e) {
      await close()
      const m = e instanceof Error ? e.message : String(e)
      throw new Error(startError ? m : `toolnexus: acp: ${label}: ${m}`)
    }
  }
  const initializeResult = await step("initialize", () =>
    call("initialize", { protocolVersion: opts.protocolVersion ?? 1, clientCapabilities }),
  )
  const httpOK = initializeResult?.agentCapabilities?.mcpCapabilities?.http === true
  const mcpServersSent = (opts.mcpServers ?? []).map((s) => acpServerWire(s, httpOK))

  const sessionResult = await step("session/new", () => call("session/new", { cwd, mcpServers: mcpServersSent }))
  const sessionId = sessionResult?.sessionId

  if (opts.mode) {
    await step("session/set_mode", () => call("session/set_mode", { sessionId, modeId: opts.mode }))
  }
  if (opts.model) {
    // Passed through unchanged. Stable ACP spelling first, then the older session/set_model.
    const model = opts.model
    await step(`set model ${JSON.stringify(model)}`, async () => {
      try {
        await call("session/set_config_option", { sessionId, configId: "model", value: model })
      } catch (e1) {
        try {
          await call("session/set_model", { sessionId, modelId: model })
        } catch (e2) {
          throw new Error(`${(e1 as Error).message}; ${(e2 as Error).message}`)
        }
      }
    })
  }

  // Serialises turns: one ACP session is one conversation, so concurrent
  // prompts must not interleave into it. Idiomatic promise-chaining queue —
  // each generate() call attaches after whatever is already queued, and the
  // queue itself never rejects (a failed turn does not poison later ones).
  let turnQueue: Promise<unknown> = Promise.resolve()

  async function promptOnce(request: InProcessRequest): Promise<InProcessResponse> {
    const text = renderPrompt(request)
    const id = `c-${++nextId}`

    let content = ""
    updateSink = (params: any) => {
      const update = params?.update
      if (update?.sessionUpdate === "agent_message_chunk") {
        content += update?.content?.text ?? ""
      }
      // agent_thought_chunk and tool_call/tool_call_update narration are
      // deliberately dropped — mixing them into the reply corrupts
      // structured output a caller expects to parse.
    }

    if (exited) throw new Error(exitMessage("session/prompt"))
    const replyPromise = new Promise<RpcMessage>((resolve) => pending.set(id, { method: "session/prompt", cb: resolve }))
    write({ jsonrpc: "2.0", id, method: "session/prompt", params: { sessionId, prompt: [{ type: "text", text }] } })

    let timer: NodeJS.Timeout | undefined
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () => reject(new Error(`toolnexus: ACP turn timed out after ${permissionTimeoutMs}ms`)),
        permissionTimeoutMs,
      )
    })

    try {
      const msg = await Promise.race([replyPromise, timeout])
      if (msg.error && msg.error.code === 0) throw new Error(msg.error.message)
      if (msg.error) throw new Error(`toolnexus: ACP error ${msg.error.code}: ${msg.error.message}`)
      return { content }
    } finally {
      clearTimeout(timer)
      pending.delete(id)
      updateSink = null
    }
  }

  const generate = (request: InProcessRequest): Promise<InProcessResponse> => {
    if (closed) return Promise.reject(new Error("toolnexus: ACP client is closed"))
    const turn = turnQueue.then(() => promptOnce(request))
    turnQueue = turn.then(
      () => undefined,
      () => undefined,
    )
    return turn
  }

  async function close(): Promise<void> {
    if (closed) return
    closed = true
    rl.close()
    child.stdin!.end()
    if (startError || child.pid === undefined) return
    if (child.exitCode !== null || child.signalCode !== null) {
      killGroup(child.pid, "SIGKILL") // reap any orphaned children of the group
      return
    }
    killGroup(child.pid, "SIGTERM")
    const t = await Promise.race([childGone.then(() => "gone"), new Promise((r) => setTimeout(() => r("late"), 3000).unref())])
    killGroup(child.pid, "SIGKILL")
    if (t === "late") await Promise.race([childGone, new Promise((r) => setTimeout(r, 1000).unref())])
  }

  const client: ACPClient = { generate, close, mcpServersSent, initializeResult }
  // Non-enumerable, undocumented — internal test hook only, so the public
  // ACPClient surface stays exactly {generate, close}.
  Object.defineProperty(client, "_pid", { value: child.pid, enumerable: false })
  return client
}

/** Signals the child's whole process group (POSIX), else the child itself. Never throws. */
export function killGroup(pid: number, signal: NodeJS.Signals): void {
  if (process.platform !== "win32") {
    try {
      process.kill(-pid, signal)
      return
    } catch {
      // group already gone; fall through to the pid itself
    }
  }
  try {
    process.kill(pid, signal)
  } catch {
    // already gone
  }
}
