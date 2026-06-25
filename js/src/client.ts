/**
 * Unified LLM client — the host loop. Give it a plain base URL + a style
 * ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
 * Toolkit. See ../../SPEC.md §8.
 */
import type { Toolkit } from "./toolkit.js"
import type { ToolResult } from "./types.js"

export type ClientStyle = "openai" | "anthropic"

export interface ClientOptions {
  baseUrl: string
  style: ClientStyle
  model: string
  apiKey?: string
  headers?: Record<string, string>
  systemPrompt?: string
  maxTurns?: number
  hooks?: Hooks
  /** Retries on transient LLM errors (429/5xx/network). Default 2. */
  retries?: number
  /** Base backoff in ms (exponential + jitter). Default 500. */
  retryBaseMs?: number
  /** Whole-run deadline in ms; aborts the run (and its in-flight request) when exceeded. */
  timeoutMs?: number
}

const RETRYABLE = new Set([429, 500, 502, 503, 504])

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(resolve, ms)
    signal.addEventListener("abort", () => { clearTimeout(t); reject(signal.reason ?? new Error("aborted")) }, { once: true })
  })
}

/**
 * Lifecycle hooks around the agent loop. Each may observe, and (where noted)
 * mutate or short-circuit. All may be async.
 */
export interface Hooks {
  /** Before each model call. Return { messages?, tools? } to replace them. */
  beforeLLM?(ev: { messages: any[]; tools: any[]; model: string; turn: number }): void | { messages?: any[]; tools?: any[] } | Promise<void | { messages?: any[]; tools?: any[] }>
  /** After each model call (observe: logging, cost, tracing). */
  afterLLM?(ev: { response: any; model: string; turn: number }): void | Promise<void>
  /** Before a tool runs. Return { result } to SHORT-CIRCUIT (deny/cache), { args } to rewrite. */
  beforeTool?(ev: { name: string; args: Record<string, unknown>; id?: string; turn: number }): void | { args?: Record<string, unknown>; result?: ToolResult } | Promise<void | { args?: Record<string, unknown>; result?: ToolResult }>
  /** After a tool runs. Return { result } to replace the result. */
  afterTool?(ev: { name: string; args: Record<string, unknown>; result: ToolResult; id?: string; turn: number }): void | { result?: ToolResult } | Promise<void | { result?: ToolResult }>
}

/** A tool call the model made, plus its result + metadata. */
export interface ToolCallRecord {
  name: string
  args: Record<string, unknown>
  output: string
  isError: boolean
  metadata?: Record<string, unknown>
}

/** Token usage, summed across every LLM round trip in the run. */
export interface Usage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface RunResult {
  text: string
  messages: any[]
  /** Every tool call made, with its output, error flag, and metadata. */
  toolCalls: ToolCallRecord[]
  /** Total number of tool calls (= toolCalls.length). */
  toolCallCount: number
  /** Number of LLM round trips. */
  turns: number
  /** Aggregated token usage across all turns. */
  usage: Usage
  /** The model used. */
  model: string
}

/** Events yielded by client.stream(). */
export type StreamEvent =
  | { type: "text"; delta: string }
  | { type: "tool_call"; id: string; name: string; args: Record<string, unknown> }
  | { type: "tool_result"; id: string; name: string; output: string; isError: boolean }
  | { type: "usage"; usage: Usage }
  | { type: "done"; result: RunResult }

function addUsage(acc: Usage, raw: any, style: ClientStyle): void {
  if (!raw) return
  if (style === "anthropic") {
    acc.promptTokens += raw.input_tokens ?? 0
    acc.completionTokens += raw.output_tokens ?? 0
    acc.totalTokens += (raw.input_tokens ?? 0) + (raw.output_tokens ?? 0)
  } else {
    acc.promptTokens += raw.prompt_tokens ?? 0
    acc.completionTokens += raw.completion_tokens ?? 0
    acc.totalTokens += raw.total_tokens ?? (raw.prompt_tokens ?? 0) + (raw.completion_tokens ?? 0)
  }
}

function resolveKey(opts: ClientOptions): string {
  if (opts.apiKey) return opts.apiKey
  const env = process.env
  const key =
    env.OPENROUTER_API_KEY ||
    (opts.style === "anthropic" ? env.ANTHROPIC_API_KEY : env.OPENAI_API_KEY) ||
    env.OPENAI_API_KEY ||
    env.ANTHROPIC_API_KEY
  if (!key) throw new Error("No API key: set apiKey or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY")
  return key
}

export class Client {
  constructor(private readonly opts: ClientOptions) {}

  async run(prompt: string, ctx: { toolkit: Toolkit; signal?: AbortSignal }): Promise<RunResult> {
    return this.opts.style === "anthropic"
      ? this.runAnthropic(prompt, ctx.toolkit, ctx.signal)
      : this.runOpenAI(prompt, ctx.toolkit, ctx.signal)
  }

  /** Streaming variant: async-iterate live events (text deltas, tool calls/results, usage, done). */
  stream(prompt: string, ctx: { toolkit: Toolkit; signal?: AbortSignal }): AsyncGenerator<StreamEvent, void, unknown> {
    return this.opts.style === "anthropic"
      ? this.streamAnthropic(prompt, ctx.toolkit, ctx.signal)
      : this.streamOpenAI(prompt, ctx.toolkit, ctx.signal)
  }

  private system(toolkit: Toolkit): string {
    return [this.opts.systemPrompt ?? "", toolkit.skillsPrompt()].filter(Boolean).join("\n\n")
  }

  /** Build a run-scoped abort signal from the optional run-level timeout + an external signal. */
  private makeSignal(external?: AbortSignal): AbortSignal {
    const ctrl = new AbortController()
    if (this.opts.timeoutMs) {
      const t = setTimeout(() => ctrl.abort(new Error(`run timeout after ${this.opts.timeoutMs}ms`)), this.opts.timeoutMs)
      ;(t as any).unref?.()
    }
    if (external) {
      if (external.aborted) ctrl.abort(external.reason)
      else external.addEventListener("abort", () => ctrl.abort(external.reason), { once: true })
    }
    return ctrl.signal
  }

  /** fetch with retry + exponential backoff on 429/5xx/network, honoring Retry-After; aborts via signal. */
  private async llmFetch(url: string, init: RequestInit, signal: AbortSignal): Promise<Response> {
    const retries = this.opts.retries ?? 2
    const base = this.opts.retryBaseMs ?? 500
    let lastErr: unknown
    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        const res = await fetch(url, { ...init, signal })
        if (res.ok || !RETRYABLE.has(res.status) || attempt === retries) return res
        const ra = Number(res.headers.get("retry-after"))
        await delay(ra ? ra * 1000 : base * 2 ** attempt + Math.random() * 100, signal)
      } catch (e) {
        if (signal.aborted) throw e // abort/timeout: don't retry
        lastErr = e
        if (attempt === retries) throw e
        await delay(base * 2 ** attempt + Math.random() * 100, signal)
      }
    }
    throw lastErr
  }

  /** Run one tool through beforeTool/afterTool hooks (mutate args, short-circuit, transform result). */
  private async runTool(toolkit: Toolkit, name: string, args: Record<string, unknown>, id: string | undefined, turn: number) {
    const h = this.opts.hooks
    let a = args
    if (h?.beforeTool) {
      const ov = await h.beforeTool({ name, args: a, id, turn })
      if (ov?.result) return { args: a, result: ov.result } // short-circuit (deny/cache/dry-run)
      if (ov?.args) a = ov.args
    }
    let result = await toolkit.execute(name, a)
    if (h?.afterTool) {
      const ov = await h.afterTool({ name, args: a, result, id, turn })
      if (ov?.result) result = ov.result
    }
    return { args: a, result }
  }

  // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
  private async runOpenAI(prompt: string, toolkit: Toolkit, external?: AbortSignal): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    let messages: any[] = []
    const system = this.system(toolkit)
    if (system) messages.push({ role: "system", content: system })
    messages.push({ role: "user", content: prompt })
    let tools = toolkit.toOpenAI()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
      turns++
      if (this.opts.hooks?.beforeLLM) {
        const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
        if (ov?.messages) messages = ov.messages
        if (ov?.tools) tools = ov.tools
      }
      const res = await this.llmFetch(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
        body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto" }),
      }, signal)
      if (!res.ok) throw new Error(`LLM ${res.status}: ${await res.text()}`)
      const data: any = await res.json()
      addUsage(usage, data.usage, "openai")
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: this.opts.model, turn })
      const msg = data.choices[0].message
      messages.push(msg)
      const calls = msg.tool_calls ?? []
      if (calls.length === 0) return this.result(msg.content ?? "", messages, toolCalls, turns, usage)
      // execute all tool calls in this turn concurrently (true parallel tool calling)
      const results = await Promise.all(
        calls.map(async (call: any) => {
          const { args, result } = await this.runTool(toolkit, call.function.name, safeJson(call.function.arguments), call.id, turn)
          toolCalls.push({ name: call.function.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
          return { role: "tool", tool_call_id: call.id, content: result.output }
        }),
      )
      messages.push(...results)
    }
    return this.result(lastText(messages), messages, toolCalls, turns, usage)
  }

  private result(text: string, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage): RunResult {
    return { text, messages, toolCalls, toolCallCount: toolCalls.length, turns, usage, model: this.opts.model }
  }

  // ---- Anthropic-style: POST {baseUrl}/messages ----
  private async runAnthropic(prompt: string, toolkit: Toolkit, external?: AbortSignal): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    let messages: any[] = [{ role: "user", content: prompt }]
    let tools = toolkit.toAnthropic()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
      turns++
      if (this.opts.hooks?.beforeLLM) {
        const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
        if (ov?.messages) messages = ov.messages
        if (ov?.tools) tools = ov.tools
      }
      const res = await this.llmFetch(endpoint, {
        method: "POST",
        headers: {
          "x-api-key": key,
          "anthropic-version": "2023-06-01",
          "Content-Type": "application/json",
          ...this.opts.headers,
        },
        body: JSON.stringify({ model: this.opts.model, max_tokens: 4096, system, messages, tools }),
      }, signal)
      if (!res.ok) throw new Error(`LLM ${res.status}: ${await res.text()}`)
      const data: any = await res.json()
      addUsage(usage, data.usage, "anthropic")
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: this.opts.model, turn })
      messages.push({ role: "assistant", content: data.content })
      const uses = (data.content ?? []).filter((b: any) => b.type === "tool_use")
      if (uses.length === 0) {
        const text = (data.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
        return this.result(text, messages, toolCalls, turns, usage)
      }
      // execute all tool_use blocks in this turn concurrently (true parallel tool calling)
      const results = await Promise.all(
        uses.map(async (use: any) => {
          const { args, result } = await this.runTool(toolkit, use.name, use.input ?? {}, use.id, turn)
          toolCalls.push({ name: use.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
          return { type: "tool_result", tool_use_id: use.id, content: result.output, is_error: result.isError }
        }),
      )
      messages.push({ role: "user", content: results })
    }
    return this.result("", messages, toolCalls, turns, usage)
  }

  // ---- Streaming: OpenAI-style ----
  private async *streamOpenAI(prompt: string, toolkit: Toolkit, external?: AbortSignal): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    let messages: any[] = []
    const system = this.system(toolkit)
    if (system) messages.push({ role: "system", content: system })
    messages.push({ role: "user", content: prompt })
    let tools = toolkit.toOpenAI()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
      turns++
      if (this.opts.hooks?.beforeLLM) {
        const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
        if (ov?.messages) messages = ov.messages
        if (ov?.tools) tools = ov.tools
      }
      const res = await this.llmFetch(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
        body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto", stream: true, stream_options: { include_usage: true } }),
      }, signal)
      if (!res.ok || !res.body) throw new Error(`LLM ${res.status}: ${await res.text()}`)

      let content = ""
      const acc = new Map<number, { id: string; name: string; args: string }>()
      for await (const line of sseLines(res.body)) {
        if (!line.startsWith("data:")) continue
        const payload = line.slice(5).trim()
        if (payload === "[DONE]") break
        const j = safeParse(payload)
        if (j?.usage) addUsage(usage, j.usage, "openai")
        const choice = j?.choices?.[0]
        const d = choice?.delta
        if (d?.content) { content += d.content; yield { type: "text", delta: d.content } }
        for (const tc of d?.tool_calls ?? []) {
          const slot = acc.get(tc.index) ?? { id: "", name: "", args: "" }
          if (tc.id) slot.id = tc.id
          if (tc.function?.name) slot.name += tc.function.name
          if (tc.function?.arguments) slot.args += tc.function.arguments
          acc.set(tc.index, slot)
        }
      }
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: this.opts.model, turn })

      const calls = [...acc.values()]
      if (calls.length === 0) {
        messages.push({ role: "assistant", content })
        yield { type: "usage", usage }
        yield { type: "done", result: this.result(content, messages, toolCalls, turns, usage) }
        return
      }
      messages.push({ role: "assistant", content: content || null, tool_calls: calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: c.args } })) })
      for (const c of calls) yield { type: "tool_call", id: c.id, name: c.name, args: safeJson(c.args) }
      const settled = await Promise.all(calls.map((c) => this.runTool(toolkit, c.name, safeJson(c.args), c.id, turn)))
      for (let i = 0; i < calls.length; i++) {
        const c = calls[i], { args, result } = settled[i]
        toolCalls.push({ name: c.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
        messages.push({ role: "tool", tool_call_id: c.id, content: result.output })
        yield { type: "tool_result", id: c.id, name: c.name, output: result.output, isError: result.isError }
      }
    }
    yield { type: "done", result: this.result(lastText(messages), messages, toolCalls, turns, usage) }
  }

  // ---- Streaming: Anthropic-style ----
  private async *streamAnthropic(prompt: string, toolkit: Toolkit, external?: AbortSignal): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    let messages: any[] = [{ role: "user", content: prompt }]
    let tools = toolkit.toAnthropic()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
      turns++
      if (this.opts.hooks?.beforeLLM) {
        const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
        if (ov?.messages) messages = ov.messages
        if (ov?.tools) tools = ov.tools
      }
      const res = await this.llmFetch(endpoint, {
        method: "POST",
        headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "Content-Type": "application/json", ...this.opts.headers },
        body: JSON.stringify({ model: this.opts.model, max_tokens: 4096, system, messages, tools, stream: true }),
      }, signal)
      if (!res.ok || !res.body) throw new Error(`LLM ${res.status}: ${await res.text()}`)

      const blocks = new Map<number, { type: string; text?: string; id?: string; name?: string; json?: string }>()
      let stopReason = ""
      for await (const line of sseLines(res.body)) {
        if (!line.startsWith("data:")) continue
        const j = safeParse(line.slice(5).trim())
        if (!j) continue
        if (j.type === "message_start") addUsage(usage, j.message?.usage, "anthropic")
        else if (j.type === "content_block_start") blocks.set(j.index, { type: j.content_block.type, id: j.content_block.id, name: j.content_block.name, text: "", json: "" })
        else if (j.type === "content_block_delta") {
          const b = blocks.get(j.index)!
          if (j.delta.type === "text_delta") { b.text += j.delta.text; yield { type: "text", delta: j.delta.text } }
          else if (j.delta.type === "input_json_delta") b.json += j.delta.partial_json
        } else if (j.type === "message_delta") { stopReason = j.delta?.stop_reason ?? stopReason; addUsage(usage, j.usage, "anthropic") }
      }
      if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: this.opts.model, turn })

      const content = [...blocks.values()].map((b) => b.type === "tool_use" ? { type: "tool_use", id: b.id, name: b.name, input: safeJson(b.json || "{}") } : { type: "text", text: b.text })
      messages.push({ role: "assistant", content })
      const uses = content.filter((b: any) => b.type === "tool_use")
      if (stopReason !== "tool_use" || uses.length === 0) {
        const text = content.filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
        yield { type: "usage", usage }
        yield { type: "done", result: this.result(text, messages, toolCalls, turns, usage) }
        return
      }
      for (const u of uses as any[]) yield { type: "tool_call", id: u.id, name: u.name, args: u.input }
      const settled = await Promise.all((uses as any[]).map((u) => this.runTool(toolkit, u.name, u.input ?? {}, u.id, turn)))
      const results: any[] = []
      for (let i = 0; i < uses.length; i++) {
        const u = uses[i] as any, { args, result } = settled[i]
        toolCalls.push({ name: u.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
        results.push({ type: "tool_result", tool_use_id: u.id, content: result.output, is_error: result.isError })
        yield { type: "tool_result", id: u.id, name: u.name, output: result.output, isError: result.isError }
      }
      messages.push({ role: "user", content: results })
    }
    yield { type: "done", result: this.result("", messages, toolCalls, turns, usage) }
  }
}

export function createClient(opts: ClientOptions): Client {
  return new Client(opts)
}

/** Read an SSE stream line-by-line from a fetch body (web ReadableStream). */
async function* sseLines(body: ReadableStream<Uint8Array>): AsyncGenerator<string> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buf = ""
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf("\n")) >= 0) {
      yield buf.slice(0, idx).replace(/\r$/, "")
      buf = buf.slice(idx + 1)
    }
  }
  if (buf) yield buf
}

function safeParse(s: string): any {
  try {
    return JSON.parse(s)
  } catch {
    return undefined
  }
}

function safeJson(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s || "{}")
  } catch {
    return {}
  }
}

function lastText(messages: any[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "assistant" && typeof messages[i].content === "string") return messages[i].content
  }
  return ""
}
