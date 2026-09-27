/**
 * Simple judgments (SPEC.md §8B "Simple judgments — ask / gate", change `add-judge-adapters`).
 * A thin layer over any `Classifier`: named question builders, a role-carrying `State`,
 * `ask` (answers by name with bands) and `gate` / `Policy` (rules that escalate instead of
 * deciding when unsure). The wire is unchanged — the builders produce exactly the §8B
 * `evaluate(state, questions)` inputs.
 *
 * A gate never authorises; it only declines to decide.
 */
import {
  Classifier,
  Decision,
  type DecisionAnswer,
  type NoulCriteria,
  type Question,
  type RecordedDecision,
  noul as noulQ,
  choice as choiceQ,
  score as scoreQ,
} from "./classifier.js"
import type { Request } from "./types.js"

// ---------------------------------------------------------------- questions

/** One named question of an ordered question list. */
export interface NamedQuestion {
  name: string
  question: Question
}

/** `noul` question with a name. Name the state field it judges in `instructions`. */
export function noul(name: string, instructions: string, criteria?: NoulCriteria): NamedQuestion {
  return { name, question: noulQ(instructions, criteria) }
}
/** `choice` question with a name; `options` maps option -> what picking it MEANS. */
export function choice(name: string, instructions: string, options: Record<string, string>): NamedQuestion {
  return { name, question: choiceQ(instructions, options) }
}
/** `score` question with a name; `levels` is the ordered rubric. */
export function score(name: string, instructions: string, levels: string[]): NamedQuestion {
  return { name, question: scoreQ(instructions, levels) }
}

/** Ordered list -> the §8B questions map. A duplicate name is an error naming it. */
export function questionMap(questions: readonly NamedQuestion[]): Record<string, Question> {
  const out: Record<string, Question> = {}
  for (const { name, question } of questions) {
    if (Object.prototype.hasOwnProperty.call(out, name)) throw new Error(`duplicate question name ${JSON.stringify(name)}`)
    out[name] = question
  }
  return out
}

// ---------------------------------------------------------------- state

/** `role` next to the data's fields; a non-object value goes under `data`. */
export function State(role: string, data: unknown): Record<string, unknown> {
  if (data !== null && typeof data === "object" && !Array.isArray(data)) return { role, ...(data as object) }
  return { role, data }
}

/** Sugar: `{context, message, ...extra}`. */
export function context(ctx: string, message: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { context: ctx, message, ...extra }
}

// ---------------------------------------------------------------- bands + answers

export type Band = "yes" | "no" | "uncertain"
export interface Bands {
  low: number
  high: number
}
/** Default cut-points; exclusive on the confident side. */
export const DEFAULT_BANDS: Readonly<Bands> = Object.freeze({ low: 0.3, high: 0.7 })

/** One answer from `ask`: the §8B answer plus `band` (noul) or `sure` (choice/score). */
export type Answer = DecisionAnswer & {
  /** noul only. */
  band?: Band
  /** choice / score only. */
  sure?: boolean
  /** The one number: noul probability, score value, or choice confidence. */
  value(): number
  /** The picked option of a choice answer ("" otherwise). JS keeps §8B's `choice` string FIELD on a
   * choice answer (a method of the same name would shadow it), so this is `pick()`. */
  pick(): string
}
export type Answers = Record<string, Answer>

function noulBand(p: number, b: Bands): Band {
  return p < b.low ? "no" : p > b.high ? "yes" : "uncertain"
}

function wrap(a: DecisionAnswer, b: Bands): Answer {
  const extra =
    a.type === "noul"
      ? { band: noulBand(a.noul, b) }
      : { sure: a.confidence > b.high && !(a.type === "choice" && a.nearUniform) }
  const value = () => (a.type === "noul" ? a.noul : a.type === "score" ? a.score : a.confidence)
  const pick = () => (a.type === "choice" ? a.choice : "")
  const out = { ...a, ...extra } as Answer
  Object.defineProperty(out, "value", { value, enumerable: false })
  Object.defineProperty(out, "pick", { value: pick, enumerable: false })
  return out
}

/** Band / sure every answer of a decision. */
export function answersOf(decision: Decision, bands: Bands = DEFAULT_BANDS): Answers {
  return Object.fromEntries(Object.entries(decision.answers).map(([k, a]) => [k, wrap(a, bands)]))
}

/** Evaluate once; answers by name, each with a band (noul) or `sure` (choice/score). */
export async function ask(
  classifier: Classifier,
  state: unknown,
  questions: readonly NamedQuestion[],
  bands: Bands = DEFAULT_BANDS,
): Promise<Answers> {
  return answersOf(await classifier.evaluate(state, questionMap(questions)), bands)
}

// ---------------------------------------------------------------- gate / policy

export interface Rule {
  question: string
  below?: number
  at_least?: number
  is?: string
  action: string
  target?: string
}

export interface GateOutcome {
  action: string
  target: string
  escalated: boolean
  /** Present iff escalated: a §10 `input` Request. */
  request?: Request
  answers: Answers
}

export interface Policy {
  rules: Rule[]
  /** The no-rule-fired action; empty escalates with reason "no rule fired". */
  default?: string
  bands?: Bands
  /** Skip a rule whose answer is uncertain instead of escalating on it. */
  skipUncertain?: boolean
}

function unsure(a: Answer): boolean {
  return a.type === "noul" ? a.band === "uncertain" : !a.sure
}

function escalate(answers: Answers, id: string, question: string, reason: string, prompt: string): GateOutcome {
  return {
    action: "needs_input",
    target: "",
    escalated: true,
    answers,
    request: { id, kind: "input", prompt, data: { question, reason, answers } },
  }
}

function applyRules(answers: Answers, rules: readonly Rule[], skipUncertain: boolean): GateOutcome | undefined {
  for (const [i, r] of rules.entries()) {
    const a = answers[r.question]
    if (!a || unsure(a)) {
      if (a && skipUncertain) continue
      const reason = a ? `${a.type} answer is uncertain` : `missing answer ${JSON.stringify(r.question)}`
      return escalate(
        answers,
        `gate:${i}:${r.question}`,
        r.question,
        reason,
        `Classifier is unsure about "${r.question}" (${reason}). Decide rule ${i} (${r.action}).`,
      )
    }
    const v = a.type === "noul" ? a.noul : a.type === "score" ? a.score : NaN
    const fired =
      r.is !== undefined
        ? a.type === "choice" && a.choice === r.is
        : r.below !== undefined
          ? v < r.below
          : r.at_least !== undefined
            ? v >= r.at_least
            : false
    if (fired) return { action: r.action, target: r.target ?? "", escalated: false, answers }
  }
  return undefined
}

/** Pure half of `gate`: first-match rules; an unsure or missing answer on rule i escalates and wins. */
export function applyGate(answers: Answers, rules: readonly Rule[]): GateOutcome {
  return applyRules(answers, rules, false) ?? { action: "", target: "", escalated: false, answers }
}

/** Pure half of a `Policy`. */
export function applyPolicy(answers: Answers, policy: Policy): GateOutcome {
  const hit = applyRules(answers, policy.rules, policy.skipUncertain ?? false)
  if (hit) return hit
  if (policy.default) return { action: policy.default, target: "", escalated: false, answers }
  return escalate(answers, "gate:default", "", "no rule fired", "No rule fired. Decide the action.")
}

/** Ask, then apply rules; no rule fired -> empty action (use a `Policy` for a declared default). */
export async function gate(
  classifier: Classifier,
  state: unknown,
  questions: readonly NamedQuestion[],
  rules: readonly Rule[],
  bands: Bands = DEFAULT_BANDS,
): Promise<GateOutcome> {
  return applyGate(await ask(classifier, state, questions, bands), rules)
}

/** Ask, then apply a `Policy` (rules, default, bands, skipUncertain). */
export async function decide(
  classifier: Classifier,
  state: unknown,
  questions: readonly NamedQuestion[],
  policy: Policy,
): Promise<GateOutcome> {
  return applyPolicy(await ask(classifier, state, questions, policy.bands ?? DEFAULT_BANDS), policy)
}

// ---------------------------------------------------------------- static + tape

/** One-line `static` classifier from recorded decisions. */
export function staticClassifier(decisions: RecordedDecision[] | RecordedDecision, model?: string): Classifier {
  return new Classifier({ style: "static", decisions: Array.isArray(decisions) ? decisions : [decisions], model })
}

/** The recorded-body form of a Decision, decodable by `static`. */
function toRaw(d: Decision): Record<string, unknown> {
  const answers: Record<string, unknown> = {}
  for (const [k, a] of Object.entries(d.answers)) {
    if (a.type === "choice") {
      const { nearUniform: _derived, ...rest } = a
      answers[k] = rest
    } else answers[k] = { ...a }
  }
  const usage: Record<string, number> = { input_tokens: d.usage.inputTokens, output_tokens: d.usage.outputTokens }
  if (d.usage.cost !== undefined) usage.cost = d.usage.cost
  return { model: d.model, answers, usage, calibrated: d.calibrated }
}

/**
 * Records live decisions keyed by a caller-given call name, and replays them with no network.
 * `tape.classifier(name)` returns a Classifier: recording (live given) or replaying (no live).
 */
export class Tape {
  readonly entries: Record<string, RecordedDecision>

  constructor(
    private readonly live?: Classifier,
    entries: Record<string, RecordedDecision> = {},
  ) {
    this.entries = { ...entries }
  }

  /** A replay-only tape from saved entries. */
  static replay(entries: Record<string, RecordedDecision>): Tape {
    return new Tape(undefined, entries)
  }

  classifier(name: string): Classifier {
    const live = this.live
    if (live) {
      return new Classifier({
        style: "custom",
        evaluate: async (state, questions, signal) => {
          const d = await live.evaluate(state, questions, signal)
          this.entries[name] = { state, questions, response: toRaw(d) }
          return d
        },
      })
    }
    // Replay: resolved when evaluated (a miss fails then, naming the key, and sends nothing).
    return new Classifier({
      style: "custom",
      evaluate: async (state, questions, signal) => {
        const rec = this.entries[name]
        if (!rec) throw new Error(`tape: no recorded decision for call ${JSON.stringify(name)}`)
        const raw = rec.response as { model?: string }
        return staticClassifier(rec, raw?.model).evaluate(state, questions, signal)
      },
    })
  }
}
