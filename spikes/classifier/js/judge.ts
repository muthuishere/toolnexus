import { Classifier, expect as answerFor } from "./classifier.ts"
import type { Decision, Question } from "./types.ts"

/** Three outcomes, because a judgment has three — the shipped Guardrail type has
 *  only two, so `asGuardrail` below is where ASK gets collapsed. */
export type Verdict = { decision: "allow" } | { decision: "ask" | "deny"; reason: string }

export interface JudgeOptions {
  /** Which tool calls this judge looks at. Omit ⇒ all. */
  on?: string | string[] | ((name: string) => boolean)
  /** The pre-declared questions. */
  ask: Record<string, Question>
  /** Thresholds in ONE reviewable place. */
  rule: (d: Decision) => Verdict
  classifier: Classifier
  /** What to do when the classifier itself fails. Default "allow" (fail-open). */
  onError?: "allow" | "deny"
}

export interface ToolEvent {
  name: string
  args: Record<string, unknown>
  id?: string
  turn?: number
}

const matches = (on: JudgeOptions["on"], name: string): boolean =>
  on === undefined
    ? true
    : typeof on === "function"
      ? on(name)
      : Array.isArray(on)
        ? on.includes(name)
        : on === name

export function judge(opts: JudgeOptions): (ev: ToolEvent) => Promise<Verdict> {
  return async (ev) => {
    if (!matches(opts.on, ev.name)) return { decision: "allow" }
    let d: Decision
    try {
      d = await opts.classifier.evaluate({ tool: ev.name, ...ev.args }, opts.ask)
    } catch (err) {
      return opts.onError === "deny"
        ? { decision: "deny", reason: `judge unavailable: ${(err as Error).message}` }
        : { decision: "allow" }
    }
    return opts.rule(d)
  }
}

/** Bands over an ordered `score` answer — the level numbering IS the array order
 *  of `criteria`, which is why that array must never be sorted. */
export function bands(
  key: string,
  cuts: { ask: number; deny: number },
  reason: (level: number, d: Decision) => string,
): (d: Decision) => Verdict {
  return (d) => {
    const s = answerFor(d, key, "score").score
    if (s >= cuts.deny) return { decision: "deny", reason: reason(s, d) }
    if (s >= cuts.ask) return { decision: "ask", reason: reason(s, d) }
    return { decision: "allow" }
  }
}

/** The shipped `Guardrail` shape: "" (or undefined) ⇒ allow, reason ⇒ deny.
 *  NOTE: the shipped type is SYNCHRONOUS — see the report. */
export const asGuardrail =
  (j: (ev: ToolEvent) => Promise<Verdict>) =>
  async (ev: ToolEvent): Promise<string> => {
    const v = await j(ev)
    return v.decision === "allow" ? "" : v.reason
  }
