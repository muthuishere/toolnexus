/**
 * SPIKE 3 — context compaction, as a beforeLLM helper (NO core loop change).
 *
 * The shipped loop already lets a beforeLLM hook REPLACE the working transcript
 * (`messages = ov.messages`), and that same array flows into RunResult.messages and
 * the ConversationStore. So compaction is a pure function wired through the existing
 * §8 hook — not a new subsystem. Below threshold it is a no-op (byte-identical to
 * today); above it, it summarizes the head and keeps a recent tail, TOOL-PAIR-SAFE
 * (the tail always begins at a user turn, so a `tool` message is never orphaned from
 * its `tool_calls`). Optional pre-compact "flush to memory" nudge (openclaw).
 */

export interface Message { role: string; content?: any; tool_calls?: any[]; tool_call_id?: string }

/** Cheap, deterministic token estimate (chars/4). Override for a real tokenizer. */
export function estimateTokens(messages: Message[]): number {
  let n = 0
  for (const m of messages) n += Math.ceil(JSON.stringify(m).length / 4)
  return n
}

export interface CompactorOptions {
  /** Compact when the transcript estimate exceeds this many tokens. */
  maxTokens: number
  /** Keep at least this many tokens of the most recent tail. Default maxTokens/2. */
  keepTail?: number
  /** Produce a summary of the older messages. May call an LLM. Required. */
  summarize: (older: Message[]) => Promise<string> | string
  /** Token estimator; default chars/4. */
  countTokens?: (messages: Message[]) => number
  /** Inject a system reminder to flush durable notes to memory BEFORE summarizing
   * (pairs with the §7E memory builtin). Default off. */
  flushToMemory?: boolean
}

export interface BeforeLLMEvent { messages: Message[]; tools: any[]; model: string; turn: number }

/**
 * Build a beforeLLM hook that compacts the transcript when it grows too large.
 * Returns `{ messages }` only when it actually compacts; otherwise nothing (no-op).
 */
export function compactor(opts: CompactorOptions) {
  const count = opts.countTokens ?? estimateTokens
  const keepTail = opts.keepTail ?? Math.floor(opts.maxTokens / 2)

  return async (ev: BeforeLLMEvent): Promise<{ messages: Message[] } | void> => {
    const msgs = ev.messages
    if (count(msgs) <= opts.maxTokens) return // under budget → byte-identical no-op

    // Preserve a leading system prompt verbatim (identity/soul/skills — never summarized).
    const head0 = msgs[0]?.role === "system" ? 1 : 0
    const system = msgs.slice(0, head0)

    // Find the split: the LARGEST tail (from a clean USER boundary) that fits keepTail.
    // Scanning to a user turn guarantees tool-pair safety — a tail starting at a user
    // message can never orphan a `tool` result from its `tool_calls`.
    let split = msgs.length
    for (let i = msgs.length - 1; i > head0; i--) {
      if (msgs[i].role === "user" && count(msgs.slice(i)) <= keepTail) split = i
      if (count(msgs.slice(i)) > keepTail) break
    }
    // If no clean user boundary was found in range, fall back to the last user turn so we
    // still never split a tool group (may keep more than keepTail — safety over size).
    if (split === msgs.length) {
      for (let i = msgs.length - 1; i > head0; i--) {
        if (msgs[i].role === "user") { split = i; break }
      }
    }
    if (split <= head0) return // nothing safely compactible

    const older = msgs.slice(head0, split)
    const tail = msgs.slice(split)
    if (older.length === 0) return

    const flushNote: Message[] = opts.flushToMemory
      ? [{ role: "system", content: "Before continuing: if anything from earlier is worth keeping, save it with the memory tool now — the earlier transcript is about to be summarized." }]
      : []

    const summary = await opts.summarize(older)
    const summaryMsg: Message = { role: "system", content: `[Summary of earlier conversation]\n${summary}` }
    return { messages: [...system, summaryMsg, ...flushNote, ...tail] }
  }
}
