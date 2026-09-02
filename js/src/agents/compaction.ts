/**
 * Context compaction (SPEC.md §7F) — keep a long-lived agent under budget.
 *
 * A long-running agent grows its transcript until it overflows the model's window.
 * `compactor(opts)` returns a **`beforeLLM` hook** (§8) that summarizes the older
 * transcript and keeps a recent tail. It rides the existing seam: the client loop
 * applies a `beforeLLM` message rewrite by *replacing* the working transcript, and
 * that array flows on into `RunResult.messages` and the `ConversationStore`. So
 * compaction is a pure `messages → messages` helper and adds **no loop behavior** —
 * it is the canonical use of the §8 `beforeLLM` hook.
 *
 * Invariants (SPEC §7F):
 *   1. At/below `maxTokens` the hook is a **no-op** — it returns nothing and the run
 *      is byte-identical to a run with no compactor.
 *   2. **Tool-pair safety** — the retained tail always begins at a `user` turn, so no
 *      `tool` message is ever orphaned from the `assistant` carrying its `tool_call_id`.
 *   3. The **leading system prompt** (identity / soul / skills) is preserved verbatim;
 *      only the body between it and the tail is summarized.
 *
 *   import { agents } from "toolnexus"
 *   const client = createClient({
 *     …,
 *     hooks: { beforeLLM: agents.compactor({ maxTokens: 120_000, summarize }) },
 *   })
 */
import { partTokens } from "../content.js"

/**
 * A chat message in the working transcript. Structural and provider-neutral — only the
 * fields compaction reasons about are named; anything else on the message is carried
 * through untouched.
 */
export interface Message {
  role: string
  content?: unknown
  tool_calls?: unknown[]
  tool_call_id?: string
  [key: string]: unknown
}

/**
 * Cheap, deterministic token estimate: `ceil(chars/4)` over each message's JSON
 * serialization, summed. This is an *estimator*, not a tokenizer — exactness is the
 * host's call. Override via `CompactorOptions.countTokens` for a real tokenizer.
 */
export function estimateTokens(messages: Message[]): number {
  let total = 0
  for (const m of messages) {
    const { text, partBytes } = stripPartPayloads(m)
    total += Math.ceil(text.length / 4)
    // §1B: a media part is charged from its BYTE LENGTH. Scoring it by its `mimeType`
    // string would price a 5 MB image at ~3 tokens, so the compactor would evict text and
    // keep every image forever — the exact opposite of what a budget is for. Charging the
    // base64 verbatim is the other failure: it prices an 82-byte PNG like 30 lines of prose.
    for (const bytes of partBytes) total += partTokens(bytes)
  }
  return total
}

/** Keys whose string value is a media payload rather than prose. */
const PAYLOAD_KEYS = new Set(["data", "file_data", "blob"])

/**
 * Serialize a message for estimation with its media payloads replaced by "", returning the
 * decoded byte length of each one so the caller can charge it properly. Recognises both the
 * canonical `ContentPart` (`data`) and every provider block shape it encodes to
 * (`image_url.url` as a data: URL, `file.file_data`, `input_audio.data`).
 */
function stripPartPayloads(m: Message): { text: string; partBytes: number[] } {
  const partBytes: number[] = []
  const walk = (v: unknown, key?: string): unknown => {
    if (typeof v === "string") {
      const b64 = PAYLOAD_KEYS.has(key ?? "") ? v : v.startsWith("data:") ? v.slice(v.indexOf(",") + 1) : undefined
      if (b64 === undefined) return v
      partBytes.push(Math.max(0, Math.floor((b64.length * 3) / 4)))
      return ""
    }
    if (Array.isArray(v)) return v.map((x) => walk(x))
    if (v && typeof v === "object") {
      const out: Record<string, unknown> = {}
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) out[k] = walk(val, k)
      return out
    }
    return v
  }
  return { text: JSON.stringify(walk(m)) ?? "", partBytes }
}

/** Options for {@link compactor}. */
export interface CompactorOptions {
  /** Compact only when the transcript estimate exceeds this; at/below ⇒ no-op. */
  maxTokens: number
  /** Keep at least this many tokens of the most recent tail. Default `maxTokens/2`. */
  keepTail?: number
  /** Produce a summary of the older messages. MAY call an LLM (async). Required. */
  summarize: (older: Message[]) => Promise<string> | string
  /** Token estimator; default `ceil(chars/4)` summed over messages. */
  countTokens?: (messages: Message[]) => number
  /**
   * When set, inject a pre-compact system reminder telling the model to persist
   * durable facts via the §7E `memory` tool before the head is summarized. Off by
   * default. Composes with the memory builtin.
   */
  flushToMemory?: boolean
}

/** The event a `beforeLLM` hook receives (§8). */
export interface BeforeLLMEvent {
  messages: Message[]
  tools: unknown[]
  model: string
  turn: number
}

/** The system reminder injected when `flushToMemory` is set. */
const FLUSH_TO_MEMORY_REMINDER =
  "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized."

/** Prefix on the summary system message; hosts/tests key off this to detect a compaction. */
const SUMMARY_PREFIX = "[Summary of earlier conversation]\n"

/**
 * Build a `beforeLLM` hook (§8) that compacts the transcript once it exceeds
 * `maxTokens`. Below budget it returns nothing (a byte-identical no-op); above it, it
 * replaces the transcript with `[leading system prompt, summary system message,
 * (flush reminder?), …recent tail]`, splitting at a clean `user` boundary so tool
 * groups are never broken.
 */
export function compactor(
  opts: CompactorOptions,
): (ev: BeforeLLMEvent) => Promise<{ messages: Message[] } | undefined> {
  const count = opts.countTokens ?? estimateTokens
  const keepTail = opts.keepTail ?? Math.floor(opts.maxTokens / 2)

  return async (ev: BeforeLLMEvent): Promise<{ messages: Message[] } | undefined> => {
    const msgs = ev.messages
    if (count(msgs) <= opts.maxTokens) return undefined // under budget → no-op

    // Preserve a leading system prompt verbatim (identity / soul / skills — never summarized).
    const headEnd = msgs[0]?.role === "system" ? 1 : 0
    const system = msgs.slice(0, headEnd)

    const split = findTailStart(msgs, headEnd, keepTail, count)
    if (split <= headEnd) return undefined // nothing safely compactible

    const older = msgs.slice(headEnd, split)
    if (older.length === 0) return undefined
    const tail = msgs.slice(split)

    const flushNote: Message[] = opts.flushToMemory
      ? [{ role: "system", content: FLUSH_TO_MEMORY_REMINDER }]
      : []

    const summary = await opts.summarize(older)
    const summaryMsg: Message = { role: "system", content: `${SUMMARY_PREFIX}${summary}` }

    return { messages: [...system, summaryMsg, ...flushNote, ...tail] }
  }
}

/**
 * Does this message carry a tool result? Under the anthropic dialect a tool result is a
 * `user` message whose content holds `tool_result` blocks, so such a message is a member
 * of a tool group rather than a conversational boundary.
 */
function carriesToolResult(m: Message): boolean {
  const content = (m as { content?: unknown }).content
  if (!Array.isArray(content)) return false
  return content.some(
    (b) => typeof b === "object" && b !== null && (b as { type?: unknown }).type === "tool_result",
  )
}

/** A valid tail boundary: a `user` turn that is not carrying a tool result. */
function isBoundary(m: Message): boolean {
  return m.role === "user" && !carriesToolResult(m)
}

/**
 * Locate the index where the retained tail begins. Tool-pair safety requires the tail to
 * start at a `user` turn that is not a tool-result carrier: the largest such tail that
 * still fits `keepTail`. If none fits, fall back to the most recent valid boundary
 * (favoring safety over size). Returns `msgs.length` (no tail) only when there is no
 * valid boundary after the head at all — the whole body is then summarized, which is
 * bounded and cannot orphan anything since nothing is retained.
 */
function findTailStart(
  msgs: Message[],
  headEnd: number,
  keepTail: number,
  count: (messages: Message[]) => number,
): number {
  let split = msgs.length
  for (let i = msgs.length - 1; i > headEnd; i--) {
    if (count(msgs.slice(i)) > keepTail) break
    if (isBoundary(msgs[i])) split = i
  }
  if (split !== msgs.length) return split

  // No boundary fit within keepTail — extend back to the most recent valid one.
  for (let i = msgs.length - 1; i > headEnd; i--) {
    if (isBoundary(msgs[i])) return i
  }
  return msgs.length
}
