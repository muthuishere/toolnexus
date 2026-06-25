/**
 * Unified LLM client — the host loop. Give it a plain base URL + a style
 * ("openai" | "anthropic"), and it runs the tool-calling agent loop against a
 * Toolkit. See ../../SPEC.md §8.
 */
import type { Toolkit } from "./toolkit.js"

export type ClientStyle = "openai" | "anthropic"

export interface ClientOptions {
  baseUrl: string
  style: ClientStyle
  model: string
  apiKey?: string
  headers?: Record<string, string>
  systemPrompt?: string
  maxTurns?: number
}

export interface RunResult {
  text: string
  messages: any[]
  toolCalls: { name: string; args: Record<string, unknown> }[]
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

  // ---- OpenAI-style: POST {baseUrl}/chat/completions ----
  private async runOpenAI(prompt: string, toolkit: Toolkit): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const messages: any[] = []
    const system = this.system(toolkit)
    if (system) messages.push({ role: "system", content: system })
    messages.push({ role: "user", content: prompt })
    const tools = toolkit.toOpenAI()
    const toolCalls: RunResult["toolCalls"] = []

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
      const res = await fetch(`${this.opts.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json", ...this.opts.headers },
        body: JSON.stringify({ model: this.opts.model, messages, tools, tool_choice: "auto" }),
      })
      if (!res.ok) throw new Error(`LLM ${res.status}: ${await res.text()}`)
      const data: any = await res.json()
      const msg = data.choices[0].message
      messages.push(msg)
      const calls = msg.tool_calls ?? []
      if (calls.length === 0) return { text: msg.content ?? "", messages, toolCalls }
      // execute all tool calls in this turn concurrently (true parallel tool calling)
      const results = await Promise.all(
        calls.map(async (call: any) => {
          const args = safeJson(call.function.arguments)
          toolCalls.push({ name: call.function.name, args })
          const result = await toolkit.execute(call.function.name, args)
          return { role: "tool", tool_call_id: call.id, content: result.output }
        }),
      )
      messages.push(...results)
    }
    return { text: lastText(messages), messages, toolCalls }
  }

  // ---- Anthropic-style: POST {baseUrl}/messages ----
  private async runAnthropic(prompt: string, toolkit: Toolkit): Promise<RunResult> {
    const key = resolveKey(this.opts)
    const base = this.opts.baseUrl.replace(/\/$/, "")
    const endpoint = base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`
    const system = this.system(toolkit)
    const messages: any[] = [{ role: "user", content: prompt }]
    const tools = toolkit.toAnthropic()
    const toolCalls: RunResult["toolCalls"] = []

    for (let turn = 0; turn < (this.opts.maxTurns ?? 10); turn++) {
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
      messages.push({ role: "assistant", content: data.content })
      const uses = (data.content ?? []).filter((b: any) => b.type === "tool_use")
      if (uses.length === 0) {
        const text = (data.content ?? []).filter((b: any) => b.type === "text").map((b: any) => b.text).join("")
        return { text, messages, toolCalls }
      }
      // execute all tool_use blocks in this turn concurrently (true parallel tool calling)
      const results = await Promise.all(
        uses.map(async (use: any) => {
          toolCalls.push({ name: use.name, args: use.input ?? {} })
          const result = await toolkit.execute(use.name, use.input ?? {})
          return { type: "tool_result", tool_use_id: use.id, content: result.output, is_error: result.isError }
        }),
      )
      messages.push({ role: "user", content: results })
    }
    return { text: "", messages, toolCalls }
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
