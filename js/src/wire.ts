/**
 * Canonical transcript → provider wire messages (SPEC.md §8A).
 *
 * The loop's `messages` array is the **canonical transcript**: a user turn carrying parts
 * holds `ContentPart[]`, and a tool turn carrying parts holds them under a `parts` key.
 * Neither shape is a wire shape. This module is the single place that turns the canonical
 * transcript into what each style actually accepts, so the four loop sites (run/stream ×
 * openai/anthropic) only ever have to *record* parts, never encode them.
 *
 * It is also where the **tool-result relocation rule** lives. Anthropic defines blocks
 * inside `tool_result.content`, so its parts are emitted natively, keyed to their
 * `tool_use_id`. OpenAI's `tool` message rejects an image outright (a hard 400 — "Image URLs
 * are only allowed for messages with role 'user'"), so there every non-text part from every
 * tool result answering one assistant turn is relocated, in tool-call order, into ONE
 * synthetic `user` message emitted immediately after the last tool message. That synthetic
 * message is an adapter artifact: it is built here, on the way out, and never written back
 * to the transcript, the `ConversationStore` or `translate` output — which is what keeps a
 * mid-conversation provider switch free of OpenAI-shaped residue.
 */
import type { ContentPart, PartStyle, UnsupportedPartMode } from "./content.js"
import { encodeParts, isContentPart, validatePart } from "./content.js"

/** Shared shaping options for a wire build. */
export interface WireOptions {
  onUnsupportedPart?: UnsupportedPartMode
  maxPartBytes?: number
}

/** The text preceding each relocated part, so the model can attribute it to its call. */
export const relocationHeader = (name: string, id: string) => `Output of tool ${name} (${id}):`

/** Split parts into the text ones (which ride along in place) and the rest. */
function split(parts: readonly ContentPart[]): { texts: ContentPart[]; others: ContentPart[] } {
  const texts: ContentPart[] = []
  const others: ContentPart[] = []
  for (const p of parts) (p.type === "text" ? texts : others).push(p)
  return { texts, others }
}

function partsOf(m: any): ContentPart[] | undefined {
  const p = m?.parts
  return Array.isArray(p) && p.length ? (p as ContentPart[]) : undefined
}

/** True when a message's `content` is a canonical `ContentPart[]` rather than a wire array. */
function isPartArray(content: unknown): content is ContentPart[] {
  return Array.isArray(content) && content.length > 0 && content.every(isContentPart)
}

/**
 * Build the OpenAI-style wire messages. Tool messages carry `output` plus text parts only;
 * their non-text parts are collected and flushed as one synthetic user message the moment
 * the run of tool messages ends.
 */
export function toOpenAIWire(messages: any[], opts: WireOptions = {}): any[] {
  const out: any[] = []
  let relocated: { name: string; id: string; parts: ContentPart[] }[] = []

  const flush = () => {
    if (!relocated.length) return
    const blocks: any[] = []
    for (const r of relocated) {
      blocks.push({ type: "text", text: relocationHeader(r.name, r.id) })
      blocks.push(...encodeParts(r.parts, { style: "openai", provenance: "derived", ...opts }))
    }
    relocated = []
    out.push({ role: "user", content: blocks })
  }

  for (const m of messages ?? []) {
    if (m?.role === "tool") {
      const parts = partsOf(m)
      const { parts: _drop, ...rest } = m ?? {}
      if (!parts) {
        out.push(m)
        continue
      }
      const { texts, others } = split(parts)
      const msg: any = { ...rest }
      if (texts.length) {
        msg.content = [{ type: "text", text: String(rest.content ?? "") }, ...encodeParts(texts, { style: "openai", provenance: "derived", ...opts })]
      }
      out.push(msg)
      if (others.length) relocated.push({ name: String(rest.name ?? ""), id: String(rest.tool_call_id ?? ""), parts: others })
      continue
    }
    flush()
    if (isPartArray(m?.content)) {
      out.push({ ...m, content: encodeParts(m.content, { style: "openai", provenance: "attached", ...opts }) })
      continue
    }
    out.push(m)
  }
  flush()
  return out
}

/**
 * Build the Anthropic-style wire messages. Nothing is relocated: a `tool_result` block that
 * carries parts gets them natively inside its own `content`, keyed to its `tool_use_id`.
 */
export function toAnthropicWire(messages: any[], opts: WireOptions = {}): any[] {
  return (messages ?? []).map((m) => {
    if (isPartArray(m?.content)) {
      return { ...m, content: encodeParts(m.content, { style: "anthropic", provenance: "attached", ...opts }) }
    }
    if (!Array.isArray(m?.content)) return m
    let touched = false
    const content = m.content.map((block: any) => {
      if (block?.type !== "tool_result") return block
      const parts = partsOf(block)
      if (!parts) return block
      touched = true
      const { parts: _drop, ...rest } = block
      return {
        ...rest,
        content: [
          { type: "text", text: String(rest.content ?? "") },
          ...encodeParts(parts, { style: "anthropic", provenance: "derived", ...opts }),
        ],
      }
    })
    return touched ? { ...m, content } : m
  })
}

/** Validate every attached part before assembly, so `maxPartBytes` cannot be bypassed. */
export function checkPromptParts(prompt: unknown, opts: WireOptions): void {
  if (!Array.isArray(prompt)) return
  for (const p of prompt) validatePart(p as ContentPart, opts.maxPartBytes)
}
