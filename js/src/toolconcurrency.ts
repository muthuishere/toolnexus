/**
 * Tool scheduling (SPEC §8 "Tool scheduling"): the calls of one model response run in emitted
 * order as consecutive runs — safe calls overlap under a cap, each unsafe call runs alone.
 */
import type { Tool } from "./types.js"

/** The cap when neither the option nor TOOLNEXUS_MAX_TOOL_CONCURRENCY sets one. */
export const DEFAULT_MAX_TOOL_CONCURRENCY = 10

/** option > env TOOLNEXUS_MAX_TOOL_CONCURRENCY > 10. Non-positive or non-numeric values fall back. */
export function resolveMaxToolConcurrency(option?: number): number {
  if (typeof option === "number" && Number.isInteger(option) && option > 0) return option
  const raw = typeof process !== "undefined" ? process.env.TOOLNEXUS_MAX_TOOL_CONCURRENCY : undefined
  if (raw !== undefined && /^\s*[+]?\d+\s*$/.test(raw)) {
    const n = Number.parseInt(raw, 10)
    if (n > 0) return n
  }
  return DEFAULT_MAX_TOOL_CONCURRENCY
}

/** Whether a call to `tool` with `args` may run alongside other safe calls. The tool's own
 * predicate wins; otherwise only a tool whose hints say readOnly is safe. */
export function isConcurrencySafe(tool: Tool, args: Record<string, unknown>): boolean {
  if (tool.concurrencySafe) return tool.concurrencySafe(args)
  return tool.hints?.readOnly === true
}

/** Runs `fn` over `items` in emitted order: safe items overlap (at most `cap`), an unsafe item
 * waits for everything before it and runs alone. Results keep item order. The first failure
 * stops new items from starting and is rethrown once the running ones settle. */
export async function scheduleCalls<T, R>(
  items: T[],
  safe: (item: T) => boolean,
  cap: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const out = new Array<R>(items.length)
  const running = new Set<Promise<void>>()
  let failed: { e: unknown } | undefined
  for (let i = 0; i < items.length && !failed; i++) {
    const item = items[i]
    if (!safe(item)) {
      await Promise.all(running)
      if (failed) break
      try {
        out[i] = await fn(item)
      } catch (e) {
        failed = { e }
      }
      continue
    }
    while (running.size >= cap) await Promise.race(running)
    if (failed) break
    const p: Promise<void> = fn(item).then(
      (r) => {
        out[i] = r
      },
      (e) => {
        if (!failed) failed = { e }
      },
    ).finally(() => running.delete(p))
    running.add(p)
  }
  await Promise.all(running)
  if (failed) throw failed.e
  return out
}
