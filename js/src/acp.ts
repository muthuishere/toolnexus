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
}

/** One warm ACP connection: one child process, one session, alive across many
 *  turns. `.generate` has exactly the shape {@link InProcessOptions.generate}
 *  wants, so it plugs straight into {@link createInProcessClient}. */
export interface ACPClient {
  generate: (request: InProcessRequest) => Promise<InProcessResponse>
  /** Terminates the agent process. Idempotent — a second call is a no-op. */
  close: () => Promise<void>
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

  const child = spawn(opts.command, opts.args ?? [], {
    cwd,
    env: opts.env ? { ...process.env, ...opts.env } : process.env,
    stdio: ["pipe", "pipe", "ignore"],
  })

  let closed = false
  let nextId = 0
  const pending = new Map<string, (msg: RpcMessage) => void>()
  let updateSink: ((params: any) => void) | null = null

  const rl = createInterface({ input: child.stdout! })
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
    child.stdin!.write(JSON.stringify(msg) + "\n")
  }

  function reply(id: unknown, result: unknown): void {
    write({ jsonrpc: "2.0", id, result })
  }

  function call(method: string, params: unknown): Promise<any> {
    const id = `c-${++nextId}`
    return new Promise((resolve, reject) => {
      pending.set(id, (msg) => {
        if (msg.error) reject(new Error(`toolnexus: ACP error ${msg.error.code}: ${msg.error.message}`))
        else resolve(msg.result)
      })
      write({ jsonrpc: "2.0", id, method, params })
    })
  }

  /** Demultiplexes EVERYTHING arriving on stdout: replies to our requests
   *  (matched by JSON-RPC id), `session/update` notifications (interleaved
   *  with replies, routed to whichever turn is in flight), and
   *  server-initiated requests like `session/request_permission`. */
  function handleLine(msg: RpcMessage): void {
    if (msg.method === undefined && msg.id !== undefined) {
      const cb = pending.get(String(msg.id))
      if (cb) {
        pending.delete(String(msg.id))
        cb(msg)
      }
      return
    }
    if (msg.method === "session/update") {
      updateSink?.(msg.params)
      return
    }
    if (msg.method === "session/request_permission" && msg.id !== undefined) {
      answerPermission(msg)
      return
    }
    // An unhandled server->client request still needs a reply or a
    // strict agent may itself hang waiting on one.
    if (msg.id !== undefined) reply(msg.id, {})
  }

  /** Answers with the FIRST option whose `kind` starts with `allow` — an
   *  unanswered permission request hangs the turn forever (proven in
   *  spikes/acp). This runs the instant the request arrives, never waiting
   *  for anything else. */
  function answerPermission(req: RpcMessage): void {
    const options: Array<{ optionId: string; kind?: string }> = req.params?.options ?? []
    const chosen = options.find((o) => typeof o.kind === "string" && o.kind.startsWith("allow"))
    const result = chosen
      ? { outcome: { outcome: "selected", optionId: chosen.optionId } }
      : { outcome: { outcome: "cancelled" } }
    reply(req.id, result)
  }

  await call("initialize", {
    protocolVersion: opts.protocolVersion ?? 1,
    clientCapabilities: { fs: { readTextFile: false, writeTextFile: false } },
  })

  const sessionResult = await call("session/new", { cwd, mcpServers: [] })
  const sessionId = sessionResult?.sessionId

  if (opts.mode) {
    await call("session/set_mode", { sessionId, modeId: opts.mode })
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

    const replyPromise = new Promise<RpcMessage>((resolve) => pending.set(id, resolve))
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

  const close = (): Promise<void> => {
    if (closed) return Promise.resolve()
    closed = true
    rl.close()
    return new Promise((resolve) => {
      if (child.exitCode !== null || child.signalCode !== null) {
        resolve()
        return
      }
      const done = () => resolve()
      child.once("exit", done)
      child.kill()
      // Belt-and-braces: resolve even if the child never emits `exit` (e.g.
      // already reaped), so close() itself never hangs.
      setTimeout(done, 1000)
    })
  }

  const client: ACPClient = { generate, close }
  // Non-enumerable, undocumented — internal test hook only, so the public
  // ACPClient surface stays exactly {generate, close}.
  Object.defineProperty(client, "_pid", { value: child.pid, enumerable: false })
  return client
}
