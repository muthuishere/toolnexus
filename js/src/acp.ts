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
 * prompt — see {@link renderACPPrompt}.
 *
 * The agent is a real tool-calling model (openspec/changes/add-acp-tool-calling,
 * SPEC §8 "ACP model source"): each prompt carries the OpenAI-shaped request —
 * messages, including earlier tool calls and their results, plus the tool
 * schemas — and the agent's JSON reply is parsed back into content or tool calls
 * ({@link parseACPReply}), which the loop executes through the toolkit.
 *
 * Three more traps this client exists to close, all proven in the spike:
 *  - `session/update` notifications interleave with JSON-RPC replies on the
 *    same stdout stream, so everything arriving is demultiplexed by id.
 *  - Only `agent_message_chunk` text may be accumulated into the reply —
 *    `agent_thought_chunk` and tool-call narration wrap prose around
 *    structured output and corrupt it if mixed in.
 *  - An unanswered `session/request_permission` hangs the turn FOREVER, even
 *    in an agent's bypass mode. The client answers the instant the request
 *    arrives — the first `reject`-kind option by default (toolnexus executes
 *    the tools), the first `allow`-kind option with `allowAgentTools`;
 *    `permissionTimeoutMs` is only a safety net for a broken agent that never
 *    asks and never replies at all.
 */
import { spawn } from "node:child_process"
import { createInterface } from "node:readline"
import path from "node:path"
import type { InProcessRequest, InProcessResponse, InProcessToolCall } from "./client.js"

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
  /** Lets the agent run tools of its OWN: a `session/request_permission` is then
   *  answered with the first `allow`-kind option. Default `false` — the first
   *  `reject`-kind option — because toolnexus is the tool executor (SPEC §8,
   *  add-acp-tool-calling): an agent that runs `bash` itself has escaped every
   *  hook and any builtin execution seam. */
  allowAgentTools?: boolean
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

/** Byte-pinned by SPEC.md §8 "ACP model source" — identical in all seven ports
 *  (seven lines, each ending in a newline). Exported for tests only; not part of
 *  the package's public surface (see index.ts). */
export const ACP_PREAMBLE =
  "You are the language model behind a tool-calling client. The client executes tools; you never do.\n" +
  "Do not run commands, read or edit files, or use any tool of your own.\n" +
  'The REQUEST below is the complete conversation in OpenAI chat-completions format: "messages" holds every message so far, including earlier tool calls and their results; "tools" lists the only tools you may call.\n' +
  "Reply with exactly one JSON object and nothing else: no prose, no markdown fences.\n" +
  'To give the final answer: {"content": "<answer>"}\n' +
  'To call tools: {"tool_calls": [{"id": "<unique id>", "type": "function", "function": {"name": "<tool name>", "arguments": "<JSON-encoded arguments>"}}]}\n' +
  'Never both. Use tool results already in "messages" instead of calling the same tool again.\n'

/** A message's content for the supersedes line: a string as is; an array of parts ⇒
 *  the `text` of its `type:"text"` parts joined by one space; anything else ⇒ "". */
function contentText(content: any): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content
      .filter((p) => p && typeof p === "object" && p.type === "text" && typeof p.text === "string")
      .map((p) => p.text)
      .join(" ")
  }
  return ""
}

/** Renders the FULL assembled request as one prompt (SPEC §8):
 *  PREAMBLE + "\nREQUEST:\n" + {"messages","tools"} JSON + "\n\n" + marker + " " +
 *  latest user text. The whole request goes every turn, not a delta — an ACP session
 *  is stateful, and a delta would make this client a shadow copy of conversation
 *  state — and the supersedes marker keeps a stateful agent off an earlier
 *  near-duplicate in its own history (ADR 0031). `JSON.stringify` never HTML-escapes
 *  and keeps insertion order, so `messages` leads. Exported for tests only. */
export function renderACPPrompt(request: InProcessRequest): string {
  const messages = request.messages ?? []
  const payload = JSON.stringify({ messages, tools: request.tools ?? [] })

  let latest = ""
  let found = false
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") {
      latest = contentText(messages[i].content)
      found = true
      break
    }
  }
  if (!found && messages.length > 0) latest = contentText(messages[messages.length - 1]?.content)

  return `${ACP_PREAMBLE}\nREQUEST:\n${payload}\n\n${SUPERSEDES_MARKER} ${latest}`
}

function isObject(v: unknown): v is Record<string, any> {
  return typeof v === "object" && v !== null && !Array.isArray(v)
}

function parseObject(s: string): Record<string, any> | undefined {
  try {
    const v = JSON.parse(s)
    return isObject(v) ? v : undefined
  } catch {
    return undefined
  }
}

/** Turns the agent's reply text into one assistant message by the algorithm SPEC §8
 *  pins: strip fences, parse (or the first-`{`..last-`}` slice), unwrap
 *  `choices[0].message` / `message`, then `tool_calls` ⇒ toolCalls, `content` ⇒
 *  content, anything else ⇒ the original text untouched (e.g. structured output the
 *  host asked for). Exported for tests only. */
export function parseACPReply(text: string): InProcessResponse {
  let s = text.trim()
  if (s.startsWith("```")) {
    const nl = s.indexOf("\n")
    s = nl >= 0 ? s.slice(nl + 1).trim() : ""
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

  if (Array.isArray(obj.choices) && obj.choices.length > 0) {
    if (isObject(obj.choices[0]) && isObject(obj.choices[0].message)) obj = obj.choices[0].message
  } else if (isObject(obj.message)) {
    obj = obj.message
  }
  const msg = obj as Record<string, any>

  if (Array.isArray(msg.tool_calls)) {
    const calls: InProcessToolCall[] = []
    for (const el of msg.tool_calls) {
      if (!isObject(el)) continue
      const fn = isObject(el.function) ? el.function : el
      if (typeof fn.name !== "string" || fn.name === "") continue
      // A string passes through as pre-encoded; anything else is structured.
      const call: InProcessToolCall = { name: fn.name, arguments: fn.arguments ?? {} }
      if (typeof el.id === "string" && el.id !== "") call.id = el.id
      calls.push(call)
    }
    if (calls.length > 0) return { toolCalls: calls }
  }

  if ("content" in msg) {
    const c = msg.content
    if (typeof c === "string") return { content: c }
    if (c === null) return { content: "" }
    return { content: JSON.stringify(c) }
  }
  return { content: text }
}

/**
 * Opens one ACP connection: spawns `command`, performs `initialize` →
 * `session/new` (with an absolute `cwd` and an `mcpServers` array — the exact
 * trap real `devin acp` enforces with `-32602` otherwise) → an optional
 * `session/set_mode`, then returns a client whose `.generate` sends exactly
 * one `session/prompt` per call, accumulating only `agent_message_chunk`
 * text and parsing it into content or tool calls.
 */
export async function loadACP(opts: ACPOptions): Promise<ACPClient> {
  const cwd = opts.cwd ? path.resolve(opts.cwd) : process.cwd()
  const permissionTimeoutMs = opts.permissionTimeoutMs ?? 30000
  const wantKind = opts.allowAgentTools ? "allow" : "reject"

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

  /** Answers with the FIRST option whose `kind` starts with `reject` — the client
   *  executes tools, the agent must not — or with the first `allow`-kind option
   *  when `allowAgentTools` is set; no matching option ⇒ `cancelled`. An
   *  unanswered permission request hangs the turn forever (proven in
   *  spikes/acp), so this runs the instant the request arrives, never waiting
   *  for anything else. */
  function answerPermission(req: RpcMessage): void {
    const options: Array<{ optionId: string; kind?: string }> = req.params?.options ?? []
    const chosen = options.find((o) => typeof o.kind === "string" && o.kind.startsWith(wantKind))
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
    const text = renderACPPrompt(request)
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
      return parseACPReply(content)
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
