/**
 * `transcriptSearchTool` — read-only search over a {@link ConversationStore} (SPEC.md §7E
 * "Memory hierarchy" → transcript search). The ground-truth tier of agent memory: a dreaming
 * persona checks its notes against what actually happened, not against its own notes.
 */
import { defineTool } from "./native.js"
import type { Tool } from "./types.js"
import type { ConversationEntry, ConversationStore } from "./client.js"

/** Options for {@link transcriptSearchTool}. */
export interface TranscriptSearchOptions {
  /** Most hits returned per call (and the ceiling for the `limit` input). Default 20. */
  maxResults?: number
  /** Snippet length in characters (Unicode code points, not bytes). Default 200. */
  snippetChars?: number
  /** Tool name. Default `transcript_search`. */
  name?: string
}

/** A store that can enumerate its conversations — what transcript search requires. */
export type ListableConversationStore = ConversationStore & { list(): Promise<ConversationEntry[]> }

export const TRANSCRIPT_SEARCH_DESCRIPTION =
  "Search past conversation transcripts — the ground truth of what actually happened. " +
  "Case-insensitive substring match over message text. Returns one line per matching " +
  "message: <conversationId>#<index> <role>: <snippet>. Read-only."

const WS = /[ \t\n\r\f\v]+/g

/** Collapse every run of ASCII whitespace to one space and trim (§7E). */
function normalize(s: string): string {
  return s.replace(WS, " ").replace(/^ | $/g, "")
}

/** ASCII-only case fold — never Unicode lowercasing (it changes lengths, differs per port). */
function fold(s: string): string {
  return s.replace(/[A-Z]/g, (c) => String.fromCharCode(c.charCodeAt(0) + 32))
}

/** Code-point order (not UTF-16 code-unit order, which differs above U+FFFF). */
function byCodePoint(a: string, b: string): number {
  const x = Array.from(a)
  const y = Array.from(b)
  for (let i = 0; i < x.length && i < y.length; i++) {
    const d = x[i].codePointAt(0)! - y[i].codePointAt(0)!
    if (d !== 0) return d
  }
  return x.length - y.length
}

/** A copy with every object's keys sorted by code point, recursively (§7E); arrays keep order. */
function sortKeys(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sortKeys)
  if (v && typeof v === "object" && Object.getPrototypeOf(v) === Object.prototype) {
    const o = v as Record<string, unknown>
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(o).sort(byCodePoint)) out[k] = sortKeys(o[k])
    return out
  }
  return v
}

function partsText(content: unknown): string[] {
  if (typeof content === "string") return [content]
  if (!Array.isArray(content)) return []
  const out: string[] = []
  for (const p of content) {
    if (!p || typeof p !== "object") continue
    const part = p as Record<string, unknown>
    if (part.type === "text" && typeof part.text === "string") out.push(part.text)
    else if (part.type === "tool_use") out.push(JSON.stringify(sortKeys(part.input ?? {})))
    else if (part.type === "tool_result") out.push(...partsText(part.content))
  }
  return out
}

/** The searchable text of one stored message (§7E): string content, text parts, tool_use
 * input as compact JSON, tool_result content, then each OpenAI tool call's `arguments`
 * string — pieces joined by "\n", then whitespace-normalized. Images are not text. */
export function messageText(msg: unknown): string {
  if (!msg || typeof msg !== "object") return ""
  const m = msg as Record<string, unknown>
  const pieces = partsText(m.content)
  if (Array.isArray(m.tool_calls)) {
    for (const tc of m.tool_calls as any[]) {
      const a = tc?.function?.arguments
      if (typeof a === "string") pieces.push(a)
    }
  }
  return normalize(pieces.join("\n"))
}

/** Snippet of `snippetChars` code points centred on the match; `…` marks elision. */
function snippet(cps: string[], at: number, qlen: number, size: number): string {
  if (cps.length <= size) return cps.join("")
  let start = at + Math.floor(qlen / 2) - Math.floor(size / 2)
  start = Math.max(0, Math.min(start, cps.length - size))
  const end = start + size
  return (start > 0 ? "…" : "") + cps.slice(start, end).join("") + (end < cps.length ? "…" : "")
}

/**
 * A read-only {@link Tool} over `store` (§7E). Construction throws unless the store has
 * `list()`. Input `{query, conversationId?, limit?}`; output one line per hit,
 * `<conversationId>#<index> <role>: <snippet>`, `no matches` when none, and a trailing
 * `(+<k> more)` when hits exceed the limit. Never writes.
 */
export function transcriptSearchTool(store: ConversationStore, opts: TranscriptSearchOptions = {}): Tool {
  if (!store || typeof (store as Partial<ListableConversationStore>).list !== "function") {
    throw new Error("transcriptSearchTool: the store must implement list()")
  }
  const listable = store as ListableConversationStore
  const maxResults = opts.maxResults ?? 20
  const snippetChars = opts.snippetChars ?? 200
  if (!Number.isInteger(maxResults) || maxResults < 1) throw new Error("transcriptSearchTool: maxResults must be an integer >= 1")
  if (!Number.isInteger(snippetChars) || snippetChars < 1) throw new Error("transcriptSearchTool: snippetChars must be an integer >= 1")
  return defineTool({
    name: opts.name ?? "transcript_search",
    description: TRANSCRIPT_SEARCH_DESCRIPTION,
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Text to find (case-insensitive substring)." },
        conversationId: { type: "string", description: "Search only this conversation." },
        limit: { type: "integer", description: `Most hits to return (default and max ${maxResults}).` },
      },
      required: ["query"],
    },
    run: async (args: Record<string, unknown>) => {
      const query = typeof args.query === "string" ? normalize(args.query) : ""
      if (query === "") return { output: "query is required", isError: true }
      let limit = maxResults
      if (args.limit !== undefined) {
        if (!Number.isInteger(args.limit) || (args.limit as number) < 1) {
          return { output: "limit must be an integer >= 1", isError: true }
        }
        limit = Math.min(args.limit as number, maxResults)
      }
      const ids =
        typeof args.conversationId === "string" ? [args.conversationId] : (await listable.list()).map((e) => e.id)
      const q = Array.from(fold(query))
      const hits: string[] = []
      let total = 0
      for (const id of ids) {
        const msgs = await listable.get(id)
        if (!msgs) continue
        msgs.forEach((msg, index) => {
          const cps = Array.from(messageText(msg))
          const hay = Array.from(fold(cps.join("")))
          const at = indexOfCps(hay, q)
          if (at < 0) return
          total++
          if (hits.length < limit) {
            const role = typeof (msg as any)?.role === "string" ? (msg as any).role : "unknown"
            hits.push(`${id}#${index} ${role}: ${snippet(cps, at, q.length, snippetChars)}`)
          }
        })
      }
      if (total === 0) return "no matches"
      if (total > hits.length) hits.push(`(+${total - hits.length} more)`)
      return hits.join("\n")
    },
  })
}

function indexOfCps(hay: string[], needle: string[]): number {
  outer: for (let i = 0; i + needle.length <= hay.length; i++) {
    for (let j = 0; j < needle.length; j++) if (hay[i + j] !== needle[j]) continue outer
    return i
  }
  return -1
}
