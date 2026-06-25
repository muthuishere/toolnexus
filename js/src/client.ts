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

  async run(prompt: string, ctx: { toolkit: Toolkit }): Promise<RunResult> {
    return this.opts.style === "anthropic" ? this.runAnthropic(prompt, ctx.toolkit) : this.runOpenAI(prompt, ctx.toolkit)
  }

  private system(toolkit: Toolkit): string {
    return [this.opts.systemPrompt ?? "", toolkit.skillsPrompt()].filter(Boolean).join("\n\n")
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
  private async runOpenAI(prompt: string, toolkit: Toolkit): Promise<RunResult> {
    const key = resolveKey(this.opts)
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
      const res = await fetch(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
        body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto" }),
      })
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
  private async runAnthropic(prompt: string, toolkit: Toolkit): Promise<RunResult> {
    const key = resolveKey(this.opts)
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
      const res = await fetch(endpoint, {
        method: "POST",
        headers: {
          "x-api-key": key,
          "anthropic-version": "2023-06-01",
          "Content-Type": "application/json",
          ...this.opts.headers,
        },
        body: JSON.stringify({ model: this.opts.model, max_tokens: 4096, system, messages, tools }),
      })
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
}

export function createClient(opts: ClientOptions): Client {
  return new Client(opts)
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
