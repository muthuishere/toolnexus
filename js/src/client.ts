/**
 * Unified LLM client — the host loop. Give it a plain base URL + a style
 * ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
 * Toolkit. See ../../SPEC.md §8.
 */
import type { Toolkit } from "./toolkit.js"
import type { ToolResult, Request, Answer } from "./types.js"
import { pendingOf } from "./types.js"

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
  /** Conversation provider for `ask(prompt, { id })`. Default: in-memory (process lifetime).
   * Supply a file/db store to persist conversations across processes. */
  store?: ConversationStore
  /** Observability sink — receives semantic metric events ({event:"llm"|"tool"|"run"…}) as the
   * loop runs (§8 Observability). Forward to statsd/logs/OTel/anything. No cost when unset. */
  onMetric?: (ev: MetricEvent) => void
  /** §10 Suspension resolver. When a tool returns Pending (metadata.pending), the client calls
   * this, then retries the tool with Context.answer = the resolution. Its interior is unconstrained
   * (open a browser, message a channel, watch a file, forward to another agent). Omit for a durable
   * host: run() does not hang — it returns { status:"pending", pending:request } to resume later. */
  waitFor?: (request: Request) => Promise<Answer>
}

/**
 * Semantic observability events (§8). NOT counter/histogram primitives — readable records the
 * host can forward anywhere. The same events also feed the built-in Prometheus registry
 * (`client.metrics()`).
 */
export type MetricEvent =
  | { event: "llm"; model: string; status: "ok" | "error"; ms: number; promptTokens: number; completionTokens: number }
  | { event: "tool"; tool: string; source: string; isError: boolean; ms: number; pending?: boolean }
  | { event: "run"; model: string; turns: number; toolCalls: number; totalTokens: number; ms: number; error?: string }

/**
 * Where `ask()` conversations are remembered — two methods. Ship the in-memory
 * default; implement this for a file/db/redis provider to persist across processes.
 */
export interface ConversationStore {
  /** Return the stored transcript for `id`, or undefined if none. */
  get(id: string): Promise<any[] | undefined>
  /** Persist the (updated) transcript for `id`. */
  save(id: string, messages: any[]): Promise<void>
}

/** Default conversation provider — keeps transcripts in memory for the client's lifetime. */
export class InMemoryConversationStore implements ConversationStore {
  private readonly map = new Map<string, any[]>()
  async get(id: string): Promise<any[] | undefined> {
    const m = this.map.get(id)
    return m ? [...m] : undefined
  }
  async save(id: string, messages: any[]): Promise<void> {
    this.map.set(id, [...messages])
  }
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
  /** "done" normally; "pending" iff a tool suspended and no waitFor was configured (§10). */
  status: "done" | "pending"
  /** The unresolved suspension — present iff status === "pending" (§10). */
  pending?: Request
}

/** Events yielded by client.stream(). */
export type StreamEvent =
  | { type: "text"; delta: string }
  | { type: "tool_call"; id: string; name: string; args: Record<string, unknown> }
  | { type: "tool_result"; id: string; name: string; output: string; isError: boolean }
  | { type: "usage"; usage: Usage }
  | { type: "pending"; request: Request }
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

/** Per-call token counts from one raw usage payload (not summed). */
function perCall(raw: any, style: ClientStyle): { prompt: number; completion: number } {
  if (!raw) return { prompt: 0, completion: 0 }
  if (style === "anthropic") return { prompt: raw.input_tokens ?? 0, completion: raw.output_tokens ?? 0 }
  return { prompt: raw.prompt_tokens ?? 0, completion: raw.completion_tokens ?? 0 }
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

// ---- Prometheus metrics registry (zero-dependency; §8 Observability) ----

/** FIXED across all ports for byte-parity of `..._duration_seconds` histograms. Seconds. */
const DURATION_BUCKETS = [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60]

interface Hist {
  counts: number[]
  sum: number
  count: number
}

/** Escape a Prometheus label value: backslash, double-quote, newline. */
function escapeLabel(v: string): string {
  return v.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n")
}

/** Render an ordered list of label pairs to `k="v",k="v"` (order is load-bearing for parity). */
function labelStr(pairs: [string, string][]): string {
  return pairs.map(([k, v]) => `${k}="${escapeLabel(v)}"`).join(",")
}

function inc(m: Map<string, number>, key: string, by = 1): void {
  m.set(key, (m.get(key) ?? 0) + by)
}

function observe(m: Map<string, Hist>, key: string, seconds: number): void {
  let h = m.get(key)
  if (!h) {
    h = { counts: new Array(DURATION_BUCKETS.length).fill(0), sum: 0, count: 0 }
    m.set(key, h)
  }
  h.sum += seconds
  h.count++
  for (let i = 0; i < DURATION_BUCKETS.length; i++) if (seconds <= DURATION_BUCKETS[i]) h.counts[i]++
}

/**
 * In-memory cumulative registry that turns the same semantic events `onMetric` sees into the
 * Prometheus counters/histograms of §8, and renders the text exposition format by hand (no dep).
 */
class MetricsRegistry {
  private readonly llmRequests = new Map<string, number>() // labels: model,status
  private readonly llmTokens = new Map<string, number>() // labels: type
  private readonly llmDuration = new Map<string, Hist>() // labels: model
  private readonly toolCalls = new Map<string, number>() // labels: tool,source,is_error
  private readonly toolDuration = new Map<string, Hist>() // labels: tool
  private readonly runErrors = new Map<string, number>() // labels: model

  record(ev: MetricEvent): void {
    if (ev.event === "llm") {
      inc(this.llmRequests, labelStr([["model", ev.model], ["status", ev.status]]))
      if (ev.status === "ok") {
        inc(this.llmTokens, labelStr([["type", "prompt"]]), ev.promptTokens)
        inc(this.llmTokens, labelStr([["type", "completion"]]), ev.completionTokens)
      }
      observe(this.llmDuration, labelStr([["model", ev.model]]), ev.ms / 1000)
    } else if (ev.event === "tool") {
      inc(this.toolCalls, labelStr([["tool", ev.tool], ["source", ev.source], ["is_error", String(ev.isError)], ["pending", String(ev.pending ?? false)]]))
      observe(this.toolDuration, labelStr([["tool", ev.tool]]), ev.ms / 1000)
    } else if (ev.event === "run") {
      if (ev.error) inc(this.runErrors, labelStr([["model", ev.model]]))
    }
  }

  render(): string {
    const out: string[] = []
    this.renderCounter(out, "toolnexus_llm_requests_total", "Total LLM requests.", this.llmRequests)
    this.renderCounter(out, "toolnexus_llm_tokens_total", "Total tokens, by type.", this.llmTokens)
    this.renderHistogram(out, "toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", this.llmDuration)
    this.renderCounter(out, "toolnexus_tool_calls_total", "Total tool calls.", this.toolCalls)
    this.renderHistogram(out, "toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", this.toolDuration)
    this.renderCounter(out, "toolnexus_run_errors_total", "Total run errors.", this.runErrors)
    return out.join("\n") + "\n"
  }

  private renderCounter(out: string[], name: string, help: string, m: Map<string, number>): void {
    out.push(`# HELP ${name} ${help}`)
    out.push(`# TYPE ${name} counter`)
    for (const key of [...m.keys()].sort()) {
      out.push(key ? `${name}{${key}} ${m.get(key)}` : `${name} ${m.get(key)}`)
    }
  }

  private renderHistogram(out: string[], name: string, help: string, m: Map<string, Hist>): void {
    out.push(`# HELP ${name} ${help}`)
    out.push(`# TYPE ${name} histogram`)
    for (const key of [...m.keys()].sort()) {
      const h = m.get(key)!
      for (let i = 0; i < DURATION_BUCKETS.length; i++) {
        out.push(`${name}_bucket{${withLe(key, String(DURATION_BUCKETS[i]))}} ${h.counts[i]}`)
      }
      out.push(`${name}_bucket{${withLe(key, "+Inf")}} ${h.count}`)
      out.push(key ? `${name}_sum{${key}} ${h.sum}` : `${name}_sum ${h.sum}`)
      out.push(key ? `${name}_count{${key}} ${h.count}` : `${name}_count ${h.count}`)
    }
  }
}

function withLe(base: string, le: string): string {
  return base ? `${base},le="${le}"` : `le="${le}"`
}

export class Client {
  /** Conversation provider for `ask()` — from opts.store, else in-memory. */
  private readonly store: ConversationStore
  /** Cumulative Prometheus registry fed by the same events `onMetric` sees. */
  private readonly registry = new MetricsRegistry()
  constructor(private readonly opts: ClientOptions) {
    this.store = opts.store ?? new InMemoryConversationStore()
  }

  /** Prometheus text exposition of cumulative metrics (§8). Always valid, empty-but-valid before activity. */
  metrics(): string {
    return this.registry.render()
  }

  /** Feed a semantic metric event to the built-in registry and the optional onMetric sink. */
  private emit(ev: MetricEvent): void {
    this.registry.record(ev)
    this.opts.onMetric?.(ev)
  }

  /** Emit the terminal `run` metric event and build the RunResult. */
  private endRun(runStart: number, text: string, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage): RunResult {
    this.emit({ event: "run", model: this.opts.model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart })
    return this.result(text, messages, toolCalls, turns, usage)
  }

  /** Emit a `run` error metric event (once, on a thrown run). */
  private emitRunError(runStart: number, toolCalls: ToolCallRecord[], turns: number, usage: Usage, e: unknown): void {
    this.emit({ event: "run", model: this.opts.model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart, error: e instanceof Error ? e.message : String(e) })
  }

  async run(prompt: string, ctx: { toolkit: Toolkit; signal?: AbortSignal; history?: any[] }): Promise<RunResult> {
    return this.opts.style === "anthropic"
      ? this.runAnthropic(prompt, ctx.toolkit, ctx.signal, ctx.history)
      : this.runOpenAI(prompt, ctx.toolkit, ctx.signal, ctx.history)
  }

  /**
   * Stateful ask. With an `id`, the client's `store` remembers the conversation:
   * it loads that id's transcript, runs, saves the updated transcript, and returns
   * the answer — so the next `ask` with the same `id` continues it. Without an
   * `id` it is a stateless one-shot (identical to `run`).
   */
  async ask(prompt: string, ctx: { toolkit: Toolkit; id?: string; on_text?: (delta: string) => void; signal?: AbortSignal }): Promise<RunResult> {
    // Block-style streaming: run the streaming loop, forward text deltas, still return the
    // final RunResult. Memory (id load/save) is handled by stream() itself, so no duplication.
    if (ctx.on_text) {
      let result: RunResult | undefined
      for await (const ev of this.stream(prompt, { toolkit: ctx.toolkit, id: ctx.id, signal: ctx.signal })) {
        if (ev.type === "text") ctx.on_text(ev.delta)
        else if (ev.type === "done") result = ev.result
      }
      return result!
    }
    if (!ctx.id) return this.run(prompt, { toolkit: ctx.toolkit, signal: ctx.signal })
    const history = (await this.store.get(ctx.id)) ?? []
    const result = await this.run(prompt, { toolkit: ctx.toolkit, signal: ctx.signal, history })
    await this.store.save(ctx.id, result.messages)
    return result
  }

  /** A stateful multi-turn conversation that retains history across sends. */
  conversation(ctx: { toolkit: Toolkit; signal?: AbortSignal }): Conversation {
    return new Conversation(this, ctx.toolkit, ctx.signal)
  }

  /**
   * Streaming variant: async-iterate live events (text deltas, tool calls/results, usage, done).
   * With an `id`, it is stateful (like `ask`): the thread's transcript is loaded as history before
   * streaming, and saved back to the ConversationStore on the terminal `done` event. No `id` ⇒ stateless.
   */
  async *stream(prompt: string, ctx: { toolkit: Toolkit; id?: string; signal?: AbortSignal }): AsyncGenerator<StreamEvent, void, unknown> {
    const history = ctx.id ? (await this.store.get(ctx.id)) ?? [] : undefined
    const gen = this.opts.style === "anthropic"
      ? this.streamAnthropic(prompt, ctx.toolkit, ctx.signal, history)
      : this.streamOpenAI(prompt, ctx.toolkit, ctx.signal, history)
    for await (const ev of gen) {
      if (ev.type === "done" && ctx.id) await this.store.save(ctx.id, ev.result.messages)
      yield ev
    }
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
    const source = toolkit.get(name)?.source ?? "custom"
    const t0 = Date.now()
    let a = args
    if (h?.beforeTool) {
      const ov = await h.beforeTool({ name, args: a, id, turn })
      if (ov?.result) {
        // short-circuit (deny/cache/dry-run, or a guard-raised suspension — path B); still a tool
        // call for metrics, but a suspension is classified pending, never an error.
        this.emitTool(name, source, ov.result, t0)
        return { args: a, result: ov.result }
      }
      if (ov?.args) a = ov.args
    }
    let result = await toolkit.execute(name, a)
    // A suspension (§10) is not a real result: skip afterTool's failure path on it — the resolved
    // result (post-waitFor) still flows through afterTool in resolvePending — and never count it as
    // a tool error.
    if (h?.afterTool && !pendingOf(result)) {
      const ov = await h.afterTool({ name, args: a, result, id, turn })
      if (ov?.result) result = ov.result
    }
    this.emitTool(name, source, result, t0)
    return { args: a, result }
  }

  /** Emit the `tool` metric, classifying a §10 suspension as `pending` (isError:false), not a failure. */
  private emitTool(name: string, source: string, result: ToolResult, t0: number) {
    const req = pendingOf(result)
    this.emit({ event: "tool", tool: name, source, isError: req ? false : result.isError, ms: Date.now() - t0, ...(req ? { pending: true } : {}) })
  }

  /** One non-streaming LLM call, with an `llm` metric event (ok/error + per-call tokens + ms). */
  private async llmCallJson(url: string, init: RequestInit, signal: AbortSignal, style: ClientStyle): Promise<any> {
    const t0 = Date.now()
    try {
      const res = await this.llmFetch(url, init, signal)
      if (!res.ok) throw new Error(`LLM ${res.status}: ${await res.text()}`)
      const data: any = await res.json()
      const tok = perCall(data.usage, style)
      this.emit({ event: "llm", model: this.opts.model, status: "ok", ms: Date.now() - t0, promptTokens: tok.prompt, completionTokens: tok.completion })
      return data
    } catch (e) {
      this.emit({ event: "llm", model: this.opts.model, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
      throw e
    }
  }

  // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
  private async runOpenAI(prompt: string, toolkit: Toolkit, external?: AbortSignal, history?: any[]): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history] : []
    if (!messages.length) {
      const system = this.system(toolkit)
      if (system) messages.push({ role: "system", content: system })
    }
    messages.push({ role: "user", content: prompt })
    let tools = toolkit.toOpenAI()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
        }
        const data: any = await this.llmCallJson(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
          method: "POST",
          headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
          body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto" }),
        }, signal, "openai")
        addUsage(usage, data.usage, "openai")
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: this.opts.model, turn })
        const msg = data.choices[0].message
        messages.push(msg)
        const calls = msg.tool_calls ?? []
        if (calls.length === 0) return this.endRun(runStart, msg.content ?? "", messages, toolCalls, turns, usage)
        // execute all tool calls in this turn concurrently (true parallel tool calling)
        const settled = await Promise.all(
          calls.map(async (call: any) => {
            let { args, result } = await this.runTool(toolkit, call.function.name, safeJson(call.function.arguments), call.id, turn)
            let halted: Request | undefined
            const req = pendingOf(result)
            if (req) {
              const r = await this.resolvePending(toolkit, call.function.name, args, req, call.id, turn)
              result = r.result
              halted = r.halted
            }
            return { call, args, result, halted }
          }),
        )
        // Record in tool-call order; on the FIRST durable halt, surface it and stop — deterministic,
        // and the later suspensions' placeholder results never enter the transcript (they re-suspend
        // on resume). Mirrors the streaming path.
        for (const s of settled) {
          toolCalls.push({ name: s.call.function.name, args: s.args, output: s.result.output, isError: s.result.isError, metadata: s.result.metadata })
          messages.push({ role: "tool", tool_call_id: s.call.id, content: s.result.output })
          if (s.halted) return this.pendingRun(runStart, s.halted, messages, toolCalls, turns, usage)
        }
      }
      return this.endRun(runStart, lastText(messages), messages, toolCalls, turns, usage)
    } catch (e) {
      this.emitRunError(runStart, toolCalls, turns, usage, e)
      throw e
    }
  }

  private result(text: string, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage): RunResult {
    return { text, messages, toolCalls, toolCallCount: toolCalls.length, turns, usage, model: this.opts.model, status: "done" }
  }

  /** §10: a run halted because a tool suspended and no `waitFor` was configured. */
  private pendingRun(runStart: number, request: Request, messages: any[], toolCalls: ToolCallRecord[], turns: number, usage: Usage): RunResult {
    this.emit({ event: "run", model: this.opts.model, turns, toolCalls: toolCalls.length, totalTokens: usage.totalTokens, ms: Date.now() - runStart })
    return { text: request.prompt, messages, toolCalls, toolCallCount: toolCalls.length, turns, usage, model: this.opts.model, status: "pending", pending: request }
  }

  /**
   * §10: resolve a suspended tool result. Calls `waitFor(request)`, and on `ok` re-executes the
   * tool once with `Context.answer = answer`. Returns the resolved result, or `{ halted }` when
   * no `waitFor` is configured (the run should stop and surface the request).
   */
  private async resolvePending(
    toolkit: Toolkit, name: string, args: Record<string, unknown>, request: Request, id: string | undefined, turn: number,
  ): Promise<{ result: ToolResult; halted?: Request }> {
    if (!this.opts.waitFor) return { result: { output: request.prompt, isError: true }, halted: request }
    const answer = await this.opts.waitFor(request)
    if (!answer?.ok) return { result: { output: `declined/expired: ${request.prompt}`, isError: true } }
    const t0 = Date.now()
    let result = await toolkit.execute(name, args, { answer })
    if (this.opts.hooks?.afterTool) {
      const ov = await this.opts.hooks.afterTool({ name, args, result, id, turn })
      if (ov?.result) result = ov.result
    }
    this.emitTool(name, toolkit.get(name)?.source ?? "custom", result, t0)
    if (pendingOf(result)) result = { output: `unresolved: ${request.prompt}`, isError: true } // never loop forever
    return { result }
  }

  // ---- Anthropic-style: POST {baseUrl}/messages ----
  private async runAnthropic(prompt: string, toolkit: Toolkit, external?: AbortSignal, history?: any[]): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history, { role: "user", content: prompt }] : [{ role: "user", content: prompt }]
    let tools = toolkit.toAnthropic()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
        }
        const data: any = await this.llmCallJson(endpoint, {
          method: "POST",
          headers: {
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
            ...this.opts.headers,
          },
          body: JSON.stringify({ model: this.opts.model, max_tokens: 4096, system, messages, tools }),
        }, signal, "anthropic")
        addUsage(usage, data.usage, "anthropic")
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: data, model: this.opts.model, turn })
        messages.push({ role: "assistant", content: data.content })
        const uses = (data.content ?? []).filter((b: any) => b.type === "tool_use")
        if (uses.length === 0) {
          const text = (data.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
          return this.endRun(runStart, text, messages, toolCalls, turns, usage)
        }
        // execute all tool_use blocks in this turn concurrently (true parallel tool calling)
        let halted: Request | undefined
        const results = await Promise.all(
          uses.map(async (use: any) => {
            let { args, result } = await this.runTool(toolkit, use.name, use.input ?? {}, use.id, turn)
            const req = pendingOf(result)
            if (req) {
              const r = await this.resolvePending(toolkit, use.name, args, req, use.id, turn)
              result = r.result
              if (r.halted) halted = r.halted
            }
            toolCalls.push({ name: use.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
            return { type: "tool_result", tool_use_id: use.id, content: result.output, is_error: result.isError }
          }),
        )
        messages.push({ role: "user", content: results })
        if (halted) return this.pendingRun(runStart, halted, messages, toolCalls, turns, usage)
      }
      return this.endRun(runStart, "", messages, toolCalls, turns, usage)
    } catch (e) {
      this.emitRunError(runStart, toolCalls, turns, usage, e)
      throw e
    }
  }

  // ---- Streaming: OpenAI-style ----
  private async *streamOpenAI(prompt: string, toolkit: Toolkit, external?: AbortSignal, history?: any[]): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const runStart = Date.now()
    let messages: any[] = history && history.length ? [...history] : []
    if (!messages.length) {
      const system = this.system(toolkit)
      if (system) messages.push({ role: "system", content: system })
    }
    messages.push({ role: "user", content: prompt })
    let tools = toolkit.toOpenAI()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
        }
        const t0 = Date.now()
        const beforeP = usage.promptTokens, beforeC = usage.completionTokens
        let content = ""
        const acc = new Map<number, { id: string; name: string; args: string }>()
        try {
          const res = await this.llmFetch(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
            method: "POST",
            headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
            body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto", stream: true, stream_options: { include_usage: true } }),
          }, signal)
          if (!res.ok || !res.body) throw new Error(`LLM ${res.status}: ${await res.text()}`)
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
        } catch (e) {
          this.emit({ event: "llm", model: this.opts.model, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
          throw e
        }
        this.emit({ event: "llm", model: this.opts.model, status: "ok", ms: Date.now() - t0, promptTokens: usage.promptTokens - beforeP, completionTokens: usage.completionTokens - beforeC })
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: this.opts.model, turn })

        const calls = [...acc.values()]
        if (calls.length === 0) {
          messages.push({ role: "assistant", content })
          yield { type: "usage", usage }
          yield { type: "done", result: this.endRun(runStart, content, messages, toolCalls, turns, usage) }
          return
        }
        messages.push({ role: "assistant", content: content || null, tool_calls: calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: c.args } })) })
        for (const c of calls) yield { type: "tool_call", id: c.id, name: c.name, args: safeJson(c.args) }
        const settled = await Promise.all(calls.map((c) => this.runTool(toolkit, c.name, safeJson(c.args), c.id, turn)))
        for (let i = 0; i < calls.length; i++) {
          const c = calls[i]
          let { args, result } = settled[i]
          const req = pendingOf(result)
          if (req) {
            yield { type: "pending", request: req } // surface BEFORE waitFor so a channel can push the link
            const r = await this.resolvePending(toolkit, c.name, args, req, c.id, turn)
            result = r.result
            if (r.halted) {
              toolCalls.push({ name: c.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
              messages.push({ role: "tool", tool_call_id: c.id, content: result.output })
              yield { type: "done", result: this.pendingRun(runStart, r.halted, messages, toolCalls, turns, usage) }
              return
            }
          }
          toolCalls.push({ name: c.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
          messages.push({ role: "tool", tool_call_id: c.id, content: result.output })
          yield { type: "tool_result", id: c.id, name: c.name, output: result.output, isError: result.isError }
        }
      }
      yield { type: "done", result: this.endRun(runStart, lastText(messages), messages, toolCalls, turns, usage) }
    } catch (e) {
      this.emitRunError(runStart, toolCalls, turns, usage, e)
      throw e
    }
  }

  // ---- Streaming: Anthropic-style ----
  private async *streamAnthropic(prompt: string, toolkit: Toolkit, external?: AbortSignal, history?: any[]): AsyncGenerator<StreamEvent, void, unknown> {
    const key = resolveKey(this.opts)
    const signal = this.makeSignal(external)
    const runStart = Date.now()
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    let messages: any[] = history && history.length ? [...history, { role: "user", content: prompt }] : [{ role: "user", content: prompt }]
    let tools = toolkit.toAnthropic()
    const toolCalls: ToolCallRecord[] = []
    const usage: Usage = { promptTokens: 0, completionTokens: 0, totalTokens: 0 }
    let turns = 0

    try {
      for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
        turns++
        if (this.opts.hooks?.beforeLLM) {
          const ov = await this.opts.hooks.beforeLLM({ messages, tools, model: this.opts.model, turn })
          if (ov?.messages) messages = ov.messages
          if (ov?.tools) tools = ov.tools
        }
        const t0 = Date.now()
        const beforeP = usage.promptTokens, beforeC = usage.completionTokens
        const blocks = new Map<number, { type: string; text?: string; id?: string; name?: string; json?: string }>()
        let stopReason = ""
        try {
          const res = await this.llmFetch(endpoint, {
            method: "POST",
            headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "Content-Type": "application/json", ...this.opts.headers },
            body: JSON.stringify({ model: this.opts.model, max_tokens: 4096, system, messages, tools, stream: true }),
          }, signal)
          if (!res.ok || !res.body) throw new Error(`LLM ${res.status}: ${await res.text()}`)
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
        } catch (e) {
          this.emit({ event: "llm", model: this.opts.model, status: "error", ms: Date.now() - t0, promptTokens: 0, completionTokens: 0 })
          throw e
        }
        this.emit({ event: "llm", model: this.opts.model, status: "ok", ms: Date.now() - t0, promptTokens: usage.promptTokens - beforeP, completionTokens: usage.completionTokens - beforeC })
        if (this.opts.hooks?.afterLLM) await this.opts.hooks.afterLLM({ response: { streamed: true, usage }, model: this.opts.model, turn })

        const content = [...blocks.values()].map((b) => b.type === "tool_use" ? { type: "tool_use", id: b.id, name: b.name, input: safeJson(b.json || "{}") } : { type: "text", text: b.text })
        messages.push({ role: "assistant", content })
        const uses = content.filter((b: any) => b.type === "tool_use")
        if (stopReason !== "tool_use" || uses.length === 0) {
          const text = content.filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
          yield { type: "usage", usage }
          yield { type: "done", result: this.endRun(runStart, text, messages, toolCalls, turns, usage) }
          return
        }
        for (const u of uses as any[]) yield { type: "tool_call", id: u.id, name: u.name, args: u.input }
        const settled = await Promise.all((uses as any[]).map((u) => this.runTool(toolkit, u.name, u.input ?? {}, u.id, turn)))
        const results: any[] = []
        for (let i = 0; i < uses.length; i++) {
          const u = uses[i] as any
          let { args, result } = settled[i]
          const req = pendingOf(result)
          if (req) {
            yield { type: "pending", request: req }
            const r = await this.resolvePending(toolkit, u.name, args, req, u.id, turn)
            result = r.result
            if (r.halted) {
              toolCalls.push({ name: u.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
              results.push({ type: "tool_result", tool_use_id: u.id, content: result.output, is_error: result.isError })
              messages.push({ role: "user", content: results })
              yield { type: "done", result: this.pendingRun(runStart, r.halted, messages, toolCalls, turns, usage) }
              return
            }
          }
          toolCalls.push({ name: u.name, args, output: result.output, isError: result.isError, metadata: result.metadata })
          results.push({ type: "tool_result", tool_use_id: u.id, content: result.output, is_error: result.isError })
          yield { type: "tool_result", id: u.id, name: u.name, output: result.output, isError: result.isError }
        }
        messages.push({ role: "user", content: results })
      }
      yield { type: "done", result: this.endRun(runStart, "", messages, toolCalls, turns, usage) }
    } catch (e) {
      this.emitRunError(runStart, toolCalls, turns, usage, e)
      throw e
    }
  }
}

export function createClient(opts: ClientOptions): Client {
  return new Client(opts)
}

/** Stateful multi-turn conversation: each send() continues the same transcript (memory). */
export class Conversation {
  /** Full running transcript (system + user + assistant + tool messages). */
  messages: any[] = []
  constructor(private readonly client: Client, private readonly toolkit: Toolkit, private readonly signal?: AbortSignal) {}

  /** Send the next user turn; prior history is retained automatically. */
  async send(prompt: string): Promise<RunResult> {
    const result = await this.client.run(prompt, { toolkit: this.toolkit, signal: this.signal, history: this.messages })
    this.messages = result.messages
    return result
  }

  /** Reset the conversation memory. */
  reset(): void {
    this.messages = []
  }
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
