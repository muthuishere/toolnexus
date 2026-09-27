// Spike: a thin, readable judge surface over toolnexus's Classifier (no library changes).
import * as lib from "../../../js/dist/index.js"
import type { Classifier, Question, DecisionAnswer, Request } from "../../../js/dist/index.js"

export type Band = "yes" | "no" | "uncertain"
export interface Bands { low: number; high: number }
export const DEFAULT_BANDS: Bands = { low: 0.3, high: 0.7 }

export interface Named { name: string; question: Question }
export const noul = (name: string, instructions: string, criteria?: lib.NoulCriteria): Named =>
  ({ name, question: lib.noul(instructions, criteria) })
export const choice = (name: string, instructions: string, options: Record<string, string>): Named =>
  ({ name, question: lib.choice(instructions, options) })
export const score = (name: string, instructions: string, levels: string[]): Named =>
  ({ name, question: lib.score(instructions, levels) })

/** Sugar: `{context, message, ...extra}`. */
export const state = (context: string, message: string, extra: Record<string, unknown> = {}) =>
  ({ context, message, ...extra })

/** Ordered list -> the §8B questions map. A duplicate name is an error. */
export function questionMap(qs: Named[]): Record<string, Question> {
  const out: Record<string, Question> = {}
  for (const { name, question } of qs) {
    if (name in out) throw new Error(`duplicate question name ${JSON.stringify(name)}`)
    out[name] = question
  }
  return out
}

/** Band of one answer. Cut points are exclusive on the confident side. */
export function band(a: DecisionAnswer | undefined, b: Bands = DEFAULT_BANDS): Band {
  if (!a) return "uncertain"
  const p = a.type === "noul" ? a.noul : a.confidence
  if (a.type === "choice" && a.nearUniform) return "uncertain"
  if (a.type !== "noul") return p > b.high ? "yes" : "uncertain" // yes = "sure"
  return p < b.low ? "no" : p > b.high ? "yes" : "uncertain"
}

export type Answers = Record<string, DecisionAnswer & { band: Band }>
export async function ask(c: Classifier, st: unknown, qs: Named[], b: Bands = DEFAULT_BANDS): Promise<Answers> {
  const d = await c.evaluate(st, questionMap(qs))
  return Object.fromEntries(Object.entries(d.answers).map(([k, a]) => [k, { ...a, band: band(a, b) }]))
}

export interface Rule { question: string; below?: number; at_least?: number; is?: string; action: string; target?: string }
export interface Outcome { action: string; target: string; escalated: boolean; request?: Request; answers: Answers }

export async function gate(c: Classifier, st: unknown, qs: Named[], rules: Rule[], b: Bands = DEFAULT_BANDS): Promise<Outcome> {
  return apply(await ask(c, st, qs, b), rules)
}

/** Pure half: first-match rules; an unsure answer on rule i escalates and wins. */
export function apply(answers: Answers, rules: Rule[]): Outcome {
  for (const [i, r] of rules.entries()) {
    const a = answers[r.question]
    if (!a || a.band === "uncertain") {
      const reason = a ? `${a.type} answer is uncertain` : "missing answer"
      return {
        action: "needs_input", target: "", escalated: true, answers,
        request: { id: `gate:${i}:${r.question}`, kind: "input",
          prompt: `Classifier is unsure about "${r.question}" (${reason}). Decide rule ${i} (${r.action}).`,
          data: { question: r.question, reason, answers } },
      }
    }
    const v = a.type === "noul" ? a.noul : a.type === "score" ? a.score : NaN
    const fired = r.is !== undefined ? a.type === "choice" && a.choice === r.is
      : r.below !== undefined ? v < r.below : r.at_least !== undefined ? v >= r.at_least : false
    if (fired) return { action: r.action, target: r.target ?? "", escalated: false, answers }
  }
  return { action: "", target: "", escalated: false, answers }
}
