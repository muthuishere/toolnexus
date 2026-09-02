/**
 * Single-turn translation — the translator path (SPEC.md §11, ADR-0011).
 *
 * `client.translate(request)` is toolnexus used as a pure wire-format translator: OpenAI
 * shapes in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool
 * execution, no conversation state — so a caller can run it statelessly.
 *
 * It is the INBOUND half of the §5 adapters: `toOpenAI`/`toAnthropic`/`toGemini` send
 * declarations out, this reads the provider's tool calls back in.
 *
 * Use it when the CALLER owns the conversation and executes tools itself (the standard
 * OpenAI function-calling posture). When toolnexus owns the conversation, use the agent
 * loop with relay tools + `runWithAnswer` (§10) instead.
 */

import type { Toolkit } from "./toolkit.js"
import { encodeParts, inboundPart, type ContentPart } from "./content.js"
import type { Usage } from "./client.js"

/** An OpenAI-shaped chat request, handed over verbatim (§11). */
export interface TranslateRequest {
  /**
   * The OpenAI `messages` array, verbatim — including assistant turns carrying
   * `tool_calls` and `tool`-role results carrying `tool_call_id`.
   */
  messages: any[]
  /**
   * The OpenAI `tools` array, verbatim
   * (`{type:"function",function:{name,description,parameters}}`). Declaration-only —
   * nothing here is ever executed.
   */
  tools?: any[]
  /**
   * Declares an ordinary toolkit's tools to the provider WITHOUT executing any of them —
   * MCP tools, skills, native functions, A2A agents, builtins. Use this when you have a
   * toolkit and want the model's tool CALLS handed back to you to dispatch yourself.
   * Composes with `tools`; toolkit declarations come first.
   */
  toolkit?: Toolkit
  /** The OpenAI `tool_choice`, verbatim. Omitted when absent. */
  toolChoice?: any
  /** Overrides the system prompt. Absent uses any `system` message found in `messages`. */
  system?: string
  /** Overrides the per-provider default max tokens. */
  maxTokens?: number
  /** Aborts the in-flight provider call. */
  signal?: AbortSignal
}

/** One tool call the model asked for, in OpenAI shape (§11). */
export interface TranslatedToolCall {
  /** The tool-call id the caller must echo on its `tool` result message. */
  id: string
  /** The function name. */
  name: string
  /** Arguments as a JSON **string** — the OpenAI wire form, echoable byte-for-byte. */
  arguments: string
}

/** An OpenAI-shaped single-turn result (§11). */
export interface TranslateResult {
  /** Assistant text ("" when the model only called tools). */
  text: string
  /** The tool calls the model emitted, in provider order. None is ever dropped. */
  toolCalls: TranslatedToolCall[]
  /** The OpenAI finish reason. A turn with any tool call is always `"tool_calls"`. */
  finishReason: "stop" | "tool_calls" | "length" | "content_filter"
  /** This single call's token usage. */
  usage: Usage
  /** The model that answered. */
  model: string
  /** The provider's decoded response, for fields this type does not model. */
  raw?: any
}

/**
 * Maps a provider stop reason onto an OpenAI finish reason. Tool calls win: a turn that
 * emitted any tool call is always `"tool_calls"` to a conforming client.
 */
export function finishReasonFor(hasToolCalls: boolean, providerStop?: string): TranslateResult["finishReason"] {
  if (hasToolCalls) return "tool_calls"
  switch (providerStop) {
    case "max_tokens":
    case "length":
      return "length"
    case "refusal":
    case "content_filter":
      return "content_filter"
    default:
      return "stop"
  }
}

/** Flattens an OpenAI `content` value to text: the string form and the parts-array form. */
export function contentText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content
      .map((p) => (p && typeof p === "object" && typeof (p as any).text === "string" ? (p as any).text : ""))
      .join("")
  }
  return ""
}

/** Parses a tool-call arguments value into an object, tolerating both wire forms. */
export function argsObject(args: unknown): Record<string, unknown> {
  if (args && typeof args === "object") return args as Record<string, unknown>
  if (typeof args === "string" && args.trim()) {
    try {
      const parsed = JSON.parse(args)
      if (parsed && typeof parsed === "object") return parsed
    } catch {
      /* fall through to {} — a malformed arguments string is not fatal */
    }
  }
  return {}
}

/** Renders a tool-call arguments value as the JSON string the OpenAI wire format uses. */
export function argsString(args: unknown): string {
  if (typeof args === "string") return args
  if (args && typeof args === "object") return JSON.stringify(args)
  return "{}"
}

/** Reads an assistant message's OpenAI `tool_calls`. */
export function toolCallsOf(m: any): TranslatedToolCall[] {
  const raw = m?.tool_calls
  if (!Array.isArray(raw)) return []
  const out: TranslatedToolCall[] = []
  for (const tc of raw) {
    const fn = tc?.function
    if (!fn) continue
    out.push({ id: String(tc.id ?? ""), name: String(fn.name ?? ""), arguments: argsString(fn.arguments) })
  }
  return out
}

/**
 * Converts an OpenAI `messages` array into Anthropic-native messages plus the extracted
 * system prompt, preserving the tool structure a text flattening destroys (§11):
 *
 * - an assistant turn's `tool_calls` become `tool_use` blocks (arguments re-parsed to objects),
 * - a `tool`-role result becomes a `tool_result` block keyed by `tool_call_id`, MERGED into a
 *   single user turn when consecutive (providers want one result-bearing turn per assistant turn),
 * - `system`/`developer` messages are hoisted out, since Anthropic takes system separately.
 */
export function openAIMessagesToAnthropic(messages: any[]): { messages: any[]; system: string } {
  const out: any[] = []
  const systemParts: string[] = []
  let pendingResults: any[] = []

  const flush = () => {
    if (pendingResults.length) {
      out.push({ role: "user", content: pendingResults })
      pendingResults = []
    }
  }

  for (const m of messages ?? []) {
    if (!m || typeof m !== "object") continue
    switch (m.role) {
      case "system":
      case "developer": {
        flush()
        const s = contentText(m.content)
        if (s) systemParts.push(s)
        break
      }
      case "tool":
      case "function": {
        const block: any = { type: "tool_result", content: contentText(m.content) }
        if (m.tool_call_id) block.tool_use_id = String(m.tool_call_id)
        pendingResults.push(block)
        break
      }
      case "assistant": {
        flush()
        const blocks: any[] = []
        const s = contentText(m.content)
        if (s) blocks.push({ type: "text", text: s })
        for (const tc of toolCallsOf(m)) {
          blocks.push({ type: "tool_use", id: tc.id, name: tc.name, input: argsObject(tc.arguments) })
        }
        if (!blocks.length) break // an empty assistant turn would be rejected
        out.push({ role: "assistant", content: blocks })
        break
      }
      default: {
        flush()
        // A `content` array has its text parts concatenated and its NON-text parts translated
        // into Anthropic's block shape (§11). Six ports used to pass a text-empty array through
        // raw and undocumented; one specified mapping replaces that, so nothing is flattened
        // away and nothing is dropped.
        const blocks = contentPartBlocks(m.content)
        if (blocks) { out.push({ role: "user", content: blocks }); break }
        const s = contentText(m.content)
        if (s) out.push({ role: "user", content: s })
      }
    }
  }
  flush()
  return { messages: out, system: systemParts.join("\n\n") }
}

/**
 * Translate an OpenAI-shaped `content` array into Anthropic blocks — but ONLY when it
 * actually carries something non-text; an all-text array still concatenates, so the
 * common case is byte-identical. Both spellings are accepted: a literal `ContentPart`
 * and the provider-native block that same part encodes to.
 */
function contentPartBlocks(content: unknown): any[] | undefined {
  if (!Array.isArray(content) || content.length === 0) return undefined
  const parts: ContentPart[] = []
  for (const raw of content) {
    const p = inboundPart(raw)
    if (!p) return undefined // an array we do not understand is left to contentText, as before
    parts.push(p)
  }
  if (parts.every((p) => p.type === "text")) return undefined
  return encodeParts(parts, { style: "anthropic", provenance: "attached" })
}

/**
 * Converts an OpenAI `tools` array into Anthropic tool declarations. Entries that are
 * already provider-native pass through; anything unrecognized is skipped.
 */
export function openAIToolsToAnthropic(tools: any[] | undefined): any[] {
  const out: any[] = []
  for (const t of tools ?? []) {
    if (!t || typeof t !== "object") continue
    const fn = (t as any).function
    if (!fn) {
      if ((t as any).name) out.push(t) // already native
      continue
    }
    if (!fn.name) continue
    const decl: any = { name: fn.name }
    if (fn.description) decl.description = fn.description
    decl.input_schema = fn.parameters ?? { type: "object", properties: {} }
    out.push(decl)
  }
  return out
}

/**
 * Maps OpenAI `tool_choice` onto Anthropic's shape. Returns undefined for absent/"auto"
 * (the provider default) and for anything unrecognized.
 */
export function openAIToolChoiceToAnthropic(choice: unknown): any {
  if (typeof choice === "string") {
    if (choice === "required" || choice === "any") return { type: "any" }
    if (choice === "none") return { type: "none" }
    return undefined
  }
  if (choice && typeof choice === "object") {
    const name = (choice as any).function?.name
    if (name) return { type: "tool", name }
  }
  return undefined
}

/** True when the message list already carries a system-ish message. */
export function hasSystemMessage(messages: any[]): boolean {
  return (messages ?? []).some((m) => m?.role === "system" || m?.role === "developer")
}
