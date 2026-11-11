/**
 * ACP (Agent Client Protocol) model source — issue #96, ADR 0031
 * ("ACP: the warm session is the feature, and it breaks the stateless request"),
 * openspec/changes/add-acp-model-source.
 *
 * ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per line,
 * over a child process's stdin/stdout. This file ships ACP as nothing more than a `generate` function —
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
import type { InProcessRequest, InProcessResponse, RunFile, StreamEvent } from "./client.js"
import { isContentPart, summarizePart, type ContentPart } from "./content.js"

/** Options for {@link loadACP}. `command`/`args` spawn the ACP agent as a child
 *  process; everything else tunes the session handshake. */
export interface ACPOptions {
  /** The ACP agent binary. */
  command: string
  /** Arguments to the agent, passed untouched. */
  args?: string[]
  /** Working directory for `session/new`. Real ACP agents
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
  /** Sent as `session/new` `mcpServers` VERBATIM and in order (absent ⇒ `[]`): toolnexus does
   *  not validate, rename, reshape or pick between the http/stdio forms (SPEC §8C). */
  mcpServers?: Record<string, unknown>[]
  /** Sent as `session/new` `_meta`; the key is absent when unset. Opaque to toolnexus. */
  sessionMeta?: Record<string, unknown>
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

/** One warm ACP connection: one child process, one session, alive across many
 *  turns. `.generate` has exactly the shape {@link InProcessOptions.generate}
 *  wants, so it plugs straight into {@link createInProcessClient}. */
/** Per-call options for {@link ACPClient.generate} / {@link ACPClient.generateStream}. */
export interface ACPGenerateOptions {
  /** Cancels the turn: when it aborts mid-turn the `session/cancel` notification is sent,
   *  the turn's reply is awaited (bounded by `requestTimeoutMs`, default 30s) so the next
   *  turn never lands on a busy session, and the call rejects with an `AbortError`. The
   *  process and session stay warm. */
  signal?: AbortSignal
}

export interface ACPClient {
  generate: (request: InProcessRequest, opts?: ACPGenerateOptions) => Promise<InProcessResponse>
  /** {@link generate}, streaming: `emit` fires as each `session/update` arrives —
   *  agent_message_chunk → `text`, agent_thought_chunk → `thought` (via "acp"),
   *  tool_call → `tool_call` and a completed/failed tool_call_update → `tool_result`
   *  (both via "acp": the agent's own tools, reported, never executed by the loop).
   *  Shaped for {@link InProcessOptions.generateStream}. The returned content is the
   *  message text only. */
  generateStream: (
    request: InProcessRequest,
    emit: (event: StreamEvent) => void,
    opts?: ACPGenerateOptions,
  ) => Promise<InProcessResponse>
  /** Sends `session/set_mode {sessionId, modeId}` on the running session (no restart). The
   *  agent's error rejects this call only; the client stays usable. */
  setMode: (mode: string) => Promise<void>
  /** Changes the model on the running session exactly as the `model` option does:
   *  `session/set_config_option {configId:"model"}`, then `session/set_model`; both failing rejects. */
  setModel: (model: string) => Promise<void>
  /** Terminates the agent's process group. Idempotent — a second call is a no-op. */
  close: () => Promise<void>
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

function renderContentPart(part: any, media?: ContentPart[]): string {
  if (typeof part === "string") return part
  if (part == null) return ""
  if (typeof part === "object" && "text" in part && typeof part.text === "string") return part.text
  // A media part (OpenAI chat wire shape) is named by its §1B one-liner, never its bytes;
  // the part itself travels as a native ACP block (SPEC §8C "ACP content parts").
  const p = acpWirePart(part)
  if (p && p.type !== "text") {
    media?.push(p)
    return summarizePart(p)
  }
  return JSON.stringify(part)
}

/** `data:<mime>;base64,<payload>` (non-empty mime) ⇒ {mimeType, data}; anything else ⇒ undefined (SPEC §8C). */
function parseBase64DataUrl(u: string): { mimeType: string; data: string } | undefined {
  if (!u.startsWith("data:")) return undefined
  const comma = u.indexOf(",")
  if (comma < 0) return undefined
  const meta = u.slice(5, comma)
  if (!meta.endsWith(";base64")) return undefined
  const mimeType = meta.slice(0, -";base64".length)
  return mimeType ? { mimeType, data: u.slice(comma + 1) } : undefined
}

/** One OpenAI chat media block read back into a §1B part, as SPEC §8C pins it (Go acpWirePart). */
function acpWirePart(part: any): ContentPart | undefined {
  switch (part?.type) {
    case "image_url": {
      const url = typeof part.image_url?.url === "string" ? part.image_url.url : ""
      const d = parseBase64DataUrl(url)
      if (d) return { type: "image", ...d } as ContentPart
      // A URL-only image part: EMPTY mimeType, never inferred from the URL.
      return url ? ({ type: "image", mimeType: "", url } as ContentPart) : undefined
    }
    case "input_audio": {
      let mimeType = "audio/" + String(part.input_audio?.format ?? "")
      if (mimeType === "audio/mp3") mimeType = "audio/mpeg"
      return { type: "audio", mimeType, data: String(part.input_audio?.data ?? "") } as ContentPart
    }
    case "file": {
      const d = parseBase64DataUrl(String(part.file?.file_data ?? ""))
      if (!d) return undefined
      const type = d.mimeType.startsWith("image/") ? "image" : d.mimeType.startsWith("audio/") ? "audio" : "file"
      const p: any = { type, ...d }
      if (part.file?.filename) p.name = String(part.file.filename)
      return p as ContentPart
    }
  }
  return isContentPart(part) && part.type !== "text" ? part : undefined
}

/** Flattens `content` (a string, or an array of content parts) to plain text; media parts
 *  found along the way are appended to `media`. */
function renderContent(content: any, media?: ContentPart[]): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) return content.map((p) => renderContentPart(p, media)).join("\n")
  return renderContentPart(content, media)
}

/** Renders the FULL assembled request as one prompt string — role + content
 *  per message — then appends the supersedes marker naming the latest user
 *  turn, per ADR 0031's proposed (and spike-confirmed) default: full request
 *  every turn, not a delta, so this stays stateless like every other
 *  toolnexus model source and never risks a shadow transcript drifting from
 *  the caller's own conversation state. */
function renderPrompt(request: InProcessRequest): { text: string; parts: ContentPart[] } {
  const messages = request.messages ?? []
  const parts: ContentPart[] = []
  const lines = messages.map((m) => `${m?.role ?? "user"}: ${renderContent(m?.content, parts)}`)

  let latest = ""
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") {
      latest = renderContent(messages[i].content)
      break
    }
  }
  if (!latest && messages.length > 0) latest = renderContent(messages[messages.length - 1]?.content)

  lines.push(`${SUPERSEDES_MARKER} ${latest}`)
  return { text: lines.join("\n"), parts }
}

/** The agent's `initialize` `agentCapabilities.promptCapabilities` (absent ⇒ false). */
interface PromptCaps {
  image: boolean
  audio: boolean
  embeddedContext: boolean
}

/** The session/prompt ContentBlock array: the text block, then one native block per media
 *  part the agent advertised support for. A part it cannot take stays named only by its
 *  description in the text; base64 never enters the text block. */
function promptBlocks(text: string, parts: ContentPart[], caps: PromptCaps): Record<string, unknown>[] {
  const blocks: Record<string, unknown>[] = [{ type: "text", text }]
  for (const p of parts) {
    if (p.type === "text" || !p.data) continue // ACP media blocks require inline data
    if (p.type === "image" && caps.image) blocks.push({ type: "image", mimeType: p.mimeType, data: p.data })
    else if (p.type === "audio" && caps.audio) blocks.push({ type: "audio", mimeType: p.mimeType, data: p.data })
    else if (p.type === "file" && caps.embeddedContext) {
      blocks.push({ type: "resource", resource: { uri: `attachment:///${p.name || "attachment"}`, mimeType: p.mimeType, blob: p.data } })
    }
  }
  return blocks
}

/**
 * Opens one ACP connection: spawns `command`, performs `initialize` →
 * `session/new` (with an absolute `cwd` and an `mcpServers` array — the exact
 * trap real agents enforce with `-32602` otherwise) → an optional
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
  const pc = initializeResult?.agentCapabilities?.promptCapabilities ?? {}
  const promptCaps: PromptCaps = { image: pc.image === true, audio: pc.audio === true, embeddedContext: pc.embeddedContext === true }
  const sessionParams: Record<string, unknown> = { cwd, mcpServers: opts.mcpServers ?? [] }
  if (opts.sessionMeta) sessionParams._meta = opts.sessionMeta
  const sessionResult = await step("session/new", () => call("session/new", sessionParams))
  const sessionId = sessionResult?.sessionId

  if (opts.mode) {
    await step("session/set_mode", () => call("session/set_mode", { sessionId, modeId: opts.mode }))
  }
  // Passed through unchanged. Stable ACP spelling first, then the older session/set_model.
  const sendModel = async (model: string): Promise<void> => {
    try {
      await call("session/set_config_option", { sessionId, configId: "model", value: model })
    } catch (e1) {
      try {
        await call("session/set_model", { sessionId, modelId: model })
      } catch (e2) {
        throw new Error(`${(e1 as Error).message}; ${(e2 as Error).message}`)
      }
    }
  }
  if (opts.model) {
    const model = opts.model
    await step(`set model ${JSON.stringify(model)}`, () => sendModel(model))
  }

  // Serialises turns: one ACP session is one conversation, so concurrent
  // prompts must not interleave into it. Idiomatic promise-chaining queue —
  // each generate() call attaches after whatever is already queued, and the
  // queue itself never rejects (a failed turn does not poison later ones).
  let turnQueue: Promise<unknown> = Promise.resolve()

  async function promptOnce(
    request: InProcessRequest,
    emit?: (event: StreamEvent) => void,
    signal?: AbortSignal,
  ): Promise<InProcessResponse> {
    if (signal?.aborted) throw acpAbortError()
    const { text, parts } = renderPrompt(request)
    const id = `c-${++nextId}`

    let content = ""
    const files: RunFile[] = [] // inbound media, in arrival order
    const messageParts: ContentPart[] = [] // media the agent sent as message chunks
    const toolNames = new Map<string, string>()
    const send = (ev: StreamEvent) => {
      try {
        emit?.(ev)
      } catch {
        // a consumer never breaks the session
      }
    }
    // Runs from the stdout read loop, so every event is emitted the moment its
    // notification is processed — never buffered to the end of the turn.
    updateSink = (params: any) => {
      const u = params?.update
      switch (u?.sessionUpdate) {
        case "agent_message_chunk": {
          const mp = acpBlockPart(u?.content)
          if (mp) {
            // An image/audio chunk is a part, never text (SPEC §8C ACP content parts).
            messageParts.push(mp)
            files.push(acpRunFile(mp, "acp", ""))
            break
          }
          const t: string = u?.content?.text ?? ""
          content += t
          if (t) send({ type: "text", delta: t })
          break
        }
        // Thought and tool narration never reach the content — mixing them into
        // the reply corrupts structured output a caller expects to parse.
        case "agent_thought_chunk": {
          const t: string = u?.content?.text ?? ""
          if (t) send({ type: "thought", delta: t, via: "acp" })
          break
        }
        case "tool_call": {
          const name: string = u.title || u.kind || ""
          toolNames.set(u.toolCallId ?? "", name)
          send({ type: "tool_call", id: u.toolCallId ?? "", name, args: u.rawInput ?? {}, via: "acp" })
          break
        }
        case "tool_call_update": {
          const callId: string = u.toolCallId ?? ""
          // A non-empty title is remembered as the call's name for every later event (#188).
          if (u.title) toolNames.set(callId, u.title)
          const name: string = toolNames.get(callId) || ""
          const raw = u.rawInput
          if (raw && typeof raw === "object" && !Array.isArray(raw) && Object.keys(raw).length > 0) {
            send({ type: "tool_call_args", id: callId, name, args: raw, via: "acp" })
          }
          if (u.status !== "completed" && u.status !== "failed") break
          const { output, parts: toolParts } = acpToolContent(u.content)
          for (const p of toolParts) files.push(acpRunFile(p, name, callId))
          send({
            type: "tool_result",
            id: callId,
            name,
            output,
            ...(toolParts.length ? { parts: toolParts } : {}),
            isError: u.status === "failed",
            via: "acp",
          })
          break
        }
      }
    }

    if (exited) throw new Error(exitMessage("session/prompt"))
    const replyPromise = new Promise<RpcMessage>((resolve) => pending.set(id, { method: "session/prompt", cb: resolve }))
    write({ jsonrpc: "2.0", id, method: "session/prompt", params: { sessionId, prompt: promptBlocks(text, parts, promptCaps) } })

    let timer: NodeJS.Timeout | undefined
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () => reject(new Error(`toolnexus: ACP turn timed out after ${permissionTimeoutMs}ms`)),
        permissionTimeoutMs,
      )
    })

    let onAbort: (() => void) | undefined
    const cancelled = new Promise<"cancelled">((resolve) => {
      if (!signal) return
      onAbort = () => resolve("cancelled")
      signal.addEventListener("abort", onAbort, { once: true })
    })

    try {
      const msg = await Promise.race([replyPromise, timeout, cancelled])
      if (msg === "cancelled") {
        // ACP: session/cancel is a notification (no id); the agent then ends the turn with
        // stopReason "cancelled". Wait (bounded) for that reply so the session is idle again.
        write({ jsonrpc: "2.0", method: "session/cancel", params: { sessionId } })
        const waitMs = opts.requestTimeoutMs && opts.requestTimeoutMs > 0 ? opts.requestTimeoutMs : 30000
        let wait: NodeJS.Timeout | undefined
        await Promise.race([
          replyPromise,
          new Promise((r) => {
            wait = setTimeout(r, waitMs)
          }),
        ])
        clearTimeout(wait)
        throw acpAbortError()
      }
      if (msg.error && msg.error.code === 0) throw new Error(msg.error.message)
      if (msg.error) throw new Error(`toolnexus: ACP error ${msg.error.code}: ${msg.error.message}`)
      // With no message text but media chunks, the answer is their §1B description.
      const answer = content === "" && messageParts.length ? messageParts.map(summarizePart).join("\n") : content
      return files.length ? { content: answer, files } : { content: answer }
    } finally {
      clearTimeout(timer)
      if (onAbort) signal!.removeEventListener("abort", onAbort)
      pending.delete(id)
      updateSink = null
    }
  }

  const generateStream = (
    request: InProcessRequest,
    emit?: (event: StreamEvent) => void,
    callOpts?: ACPGenerateOptions,
  ): Promise<InProcessResponse> => {
    if (closed) return Promise.reject(new Error("toolnexus: ACP client is closed"))
    const turn = turnQueue.then(() => promptOnce(request, emit, callOpts?.signal))
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

  const generate = (request: InProcessRequest, callOpts?: ACPGenerateOptions): Promise<InProcessResponse> =>
    generateStream(request, undefined, callOpts)

  // SPEC §8C (#197): sent at once on the running session — no restart, not queued behind a turn.
  const liveCall = async (label: string, fn: () => Promise<unknown>): Promise<void> => {
    if (closed) throw new Error("toolnexus: ACP client is closed")
    try {
      await fn()
    } catch (e) {
      throw new Error(`toolnexus: acp: ${label}: ${e instanceof Error ? e.message : String(e)}`)
    }
  }
  const setMode = (mode: string) =>
    liveCall("session/set_mode", () => call("session/set_mode", { sessionId, modeId: mode }))
  const setModel = (model: string) => liveCall(`set model ${JSON.stringify(model)}`, () => sendModel(model))

  const client: ACPClient = { generate, generateStream, setMode, setModel, close, initializeResult }
  // Non-enumerable, undocumented — internal test hook only, so the public
  // ACPClient surface stays exactly {generate, generateStream, close}.
  Object.defineProperty(client, "_pid", { value: child.pid, enumerable: false })
  return client
}

function acpAbortError(): Error {
  const e = new Error("toolnexus: ACP turn cancelled")
  e.name = "AbortError"
  return e
}

/** Signals the child's whole process group (POSIX), else the child itself. Never throws. */
function killGroup(pid: number, signal: NodeJS.Signals): void {
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

/** Reads an ACP tool_call_update's `content` array ([{type:"content", content:ContentBlock}]):
 *  text blocks join into the output; image/audio blocks become parts. With no text, the
 *  output is the parts' §1B description (as for an image-only MCP result, §0.4). */
function acpToolContent(content: any): { output: string; parts: ContentPart[] } {
  if (!Array.isArray(content)) return { output: "", parts: [] }
  const parts: ContentPart[] = []
  let output = ""
  for (const item of content) {
    const p = acpBlockPart(item?.content)
    if (p) parts.push(p)
    else if (typeof item?.content?.text === "string") output += item.content.text
  }
  if (output === "" && parts.length) output = parts.map(summarizePart).join("\n")
  return { output, parts }
}

/** An ACP image/audio ContentBlock ({type, mimeType, data, uri?}) as a §1B part: data wins,
 *  a uri-only block is a URL part; any other block is not a part. */
function acpBlockPart(blk: any): ContentPart | undefined {
  if (!blk || (blk.type !== "image" && blk.type !== "audio")) return undefined
  const mimeType: string = blk.mimeType ?? ""
  if (typeof blk.data === "string" && blk.data) return { type: blk.type, mimeType, data: blk.data }
  if (typeof blk.uri === "string" && blk.uri) return { type: blk.type, mimeType, url: blk.uri }
  return undefined
}

/** One inbound part as a RunResult.files entry. */
function acpRunFile(p: ContentPart, from: string, callId: string): RunFile {
  const f: RunFile = { type: p.type as RunFile["type"], mimeType: (p as any).mimeType, from, callId }
  if ((p as any).url) f.url = (p as any).url
  if ((p as any).data) f.data = (p as any).data
  return f
}
