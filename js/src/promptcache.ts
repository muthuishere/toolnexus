/**
 * Prompt caching (SPEC §8 "Prompt caching", change add-prompt-cache). The engine keeps the
 * request prefix stable and marks it for the provider's cache.
 */

/** `ClientOptions.promptCache`. */
export interface PromptCacheOptions {
  /** Groups requests that share a prefix (one per session). Sent as openai `prompt_cache_key`.
   * Ignored by anthropic, which caches by prefix. */
  key?: string
  /** Ask for the provider's longer cache lifetime where one exists (anthropic `ttl: "1h"`). */
  longTTL?: boolean
}

function withCacheControl(v: unknown, mark: Record<string, unknown>): unknown {
  if (!v || typeof v !== "object" || Array.isArray(v)) return v
  return { ...(v as Record<string, unknown>), cache_control: mark }
}

/**
 * Mark the cacheable prefix in an assembled body, in place. The transcript's own objects are
 * never mutated: every touched entry is copied.
 *  - anthropic: `cache_control` on the last tool, the system prompt (a string becomes one text
 *    block) and the last content block of the latest message (3 of the 4 allowed breakpoints).
 *  - openai: `prompt_cache_key = key` when set; the prefix is cached automatically.
 *  - gemini: nothing yet (explicit cachedContent is deferred).
 */
export function applyPromptCache(body: Record<string, any>, style: string, o: PromptCacheOptions): void {
  if (style === "anthropic") {
    const mark: Record<string, unknown> = { type: "ephemeral" }
    if (o.longTTL) mark.ttl = "1h"
    if (Array.isArray(body.tools) && body.tools.length > 0) {
      const out = [...body.tools]
      out[out.length - 1] = withCacheControl(out[out.length - 1], mark)
      body.tools = out
    }
    if (typeof body.system === "string") {
      if (body.system !== "") body.system = [{ type: "text", text: body.system, cache_control: mark }]
    } else if (Array.isArray(body.system) && body.system.length > 0) {
      const out = [...body.system]
      out[out.length - 1] = withCacheControl(out[out.length - 1], mark)
      body.system = out
    }
    if (Array.isArray(body.messages) && body.messages.length > 0) {
      const out = [...body.messages]
      const last = out[out.length - 1]
      if (last && typeof last === "object" && !Array.isArray(last)) {
        const m: Record<string, unknown> = { ...last }
        if (typeof m.content === "string") {
          m.content = [{ type: "text", text: m.content, cache_control: mark }]
        } else if (Array.isArray(m.content) && m.content.length > 0) {
          const blocks = [...m.content]
          blocks[blocks.length - 1] = withCacheControl(blocks[blocks.length - 1], mark)
          m.content = blocks
        }
        out[out.length - 1] = m
      }
      body.messages = out
    }
  } else if (style === "openai") {
    if (o.key) body.prompt_cache_key = o.key
  }
}
