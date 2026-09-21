/**
 * Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as `Tool` is the contract for an
 * ACTION. A System One model takes a state plus pre-declared, typed questions and returns
 * calibrated answers with no free text. It has no messages, no tool calling and no streaming,
 * so it never enters the §8 client loop.
 *
 * A classifier INTERPRETS; it never AUTHORISES. Schema validity is not correctness: a decision
 * can be confidently wrong, and "cannot hallucinate" means only that the returned value is in
 * the declared schema. Numeric limits, permission checks and allowlists stay in code. Nothing
 * here is a security control.
 */
import type { Client } from "./client.js"
import { type ErrorInfo, type ErrorTier, type MetricEvent, redactErrorBody, capErrorBody } from "./client.js"
import { retryAfterMs, isRetryableStatus } from "./retry.js"

// ---------------------------------------------------------------- constants

/** The System One endpoint base. */
export const DEFAULT_CLASSIFIER_BASE_URL = "https://api.typesafe.ai/v1"
/** The floating model alias. Pin it (e.g. `jev-1.13.0`) once thresholds are tuned. */
export const DEFAULT_CLASSIFIER_MODEL = "jev-latest"
/** The NAME of the env var holding the credential — never the value. */
export const DEFAULT_CLASSIFIER_API_KEY_ENV = "TYPESAFE_API_KEY"
/** Bounds ONE request; a classifier has no loop to bound. */
export const DEFAULT_CLASSIFIER_TIMEOUT_MS = 10_000

/**
 * A named BACKEND: `baseUrl`, `model` and `apiKeyEnv` are only jointly valid, so they travel as
 * a UNIT (ADR 0027 D1). `jev-latest` is TypeSafe's spelling; the gateway spells the same model
 * `typesafe/jev-1.13`, and mixing the two is issue #91.
 */
export type ClassifierBackend = "typesafe" | "openrouter"

/** The two servable pairings, as configurations rather than six independently-choosable cells. */
export const CLASSIFIER_BACKENDS: Readonly<Record<ClassifierBackend, { baseUrl: string; model: string; apiKeyEnv: string }>> = {
  typesafe: { baseUrl: DEFAULT_CLASSIFIER_BASE_URL, model: DEFAULT_CLASSIFIER_MODEL, apiKeyEnv: DEFAULT_CLASSIFIER_API_KEY_ENV },
  openrouter: { baseUrl: "https://openrouter.ai/api/v1", model: "typesafe/jev-1.13", apiKeyEnv: "OPENROUTER_API_KEY" },
}

/** Client-side cap on a choice's named options (§8B). */
export const MAX_CHOICE_OPTIONS = 255
/** Bounds of a score rubric (§8B). */
export const MIN_SCORE_LEVELS = 2
export const MAX_SCORE_LEVELS = 10

/**
 * The ABSOLUTE tolerance on `max|p - 1/n|`, compared INCLUSIVELY (§8B). Pinned across every
 * port; `examples/judge/near-uniform.json` pins both sides at 0.0499 / 0.0501.
 */
export const NEAR_UNIFORM_TOLERANCE = 0.05

// ---------------------------------------------------------------- questions

/**
 * Labels for the true and false cases of a noul question. Absent and empty are DIFFERENT values
 * and both are preserved on the wire: omit `criteria` entirely and the field is absent; pass
 * `{ true: "", false: "" }` and it is emitted with empty values.
 */
export interface NoulCriteria {
  true: string
  false: string
}

/** The probability that a statement holds, one number in 0..1. It carries NO confidence. */
export interface NoulQuestion {
  type: "noul"
  instructions: string
  /** Optional. Omitted ⇒ the field is ABSENT from the request, not null and not {}. */
  criteria?: NoulCriteria
}

/**
 * One option from a named set, 1..255 options.
 *
 * THE ENCODING OBLIGATION IS THE CALLER'S (§8B, docs/adr/0021 D1): `criteria[id]` is the only
 * thing that differentiates one option from another to the model. Passing the id itself, an
 * empty string, or one value repeated is schema-valid, returns HTTP 200 and a well-formed
 * distribution — and ranks at chance (measured: 17 apples described by consequence, 0/1/0
 * described by id).
 */
export interface ChoiceQuestion {
  type: "choice"
  instructions: string
  /** option id -> what picking it would MEAN. */
  criteria: Record<string, string>
}

/**
 * A rating against an ORDERED rubric of 2..10 levels. The array order IS the level numbering,
 * so it is never sorted — a "sort everything" canonicaliser silently renumbers the rubric.
 */
export interface ScoreQuestion {
  type: "score"
  instructions: string
  criteria: string[]
}

/** The closed set of question types, discriminated by `type`. */
export type Question = NoulQuestion | ChoiceQuestion | ScoreQuestion

/** A noul question. Pass `criteria` only when you mean it — absent ≠ empty on the wire. */
export function noul(instructions: string, criteria?: NoulCriteria): NoulQuestion {
  return criteria === undefined ? { type: "noul", instructions } : { type: "noul", instructions, criteria }
}

/** A choice question. Every description must say what picking that option would MEAN. */
export function choice(instructions: string, criteria: Record<string, string>): ChoiceQuestion {
  return { type: "choice", instructions, criteria: { ...criteria } }
}

/** A score question. The array order IS the level numbering and is never reordered. */
export function score(instructions: string, criteria: string[]): ScoreQuestion {
  return { type: "score", instructions, criteria: [...criteria] }
}

/**
 * Build a choice from any (name, description) pairs — a Tool, a skill, an agent, an A2A card
 * skill. The description must say what picking that option would MEAN; see the encoding
 * obligation on `ChoiceQuestion`.
 */
export function choiceOver(instructions: string, items: Record<string, string>): ChoiceQuestion {
  return choice(instructions, items)
}

/** The wire form of one question — the object itself, minus an absent `criteria`. */
function questionWire(q: Question): Record<string, unknown> {
  switch (q.type) {
    case "noul":
      return q.criteria === undefined
        ? { type: "noul", instructions: q.instructions }
        : { type: "noul", instructions: q.instructions, criteria: { true: q.criteria.true, false: q.criteria.false } }
    case "choice":
      return { type: "choice", instructions: q.instructions, criteria: { ...q.criteria } }
    case "score":
      return { type: "score", instructions: q.instructions, criteria: [...q.criteria] }
  }
}

/** Client-side limits, enforced BEFORE the request. The error names the key and the limit. */
function validateQuestion(key: string, q: Question): void {
  if (q.type === "choice") {
    const n = Object.keys(q.criteria ?? {}).length
    if (n < 1 || n > MAX_CHOICE_OPTIONS) {
      throw new Error(`classifier: question "${key}": a choice needs 1..${MAX_CHOICE_OPTIONS} options, got ${n}`)
    }
  } else if (q.type === "score") {
    const n = (q.criteria ?? []).length
    if (n < MIN_SCORE_LEVELS || n > MAX_SCORE_LEVELS) {
      throw new Error(
        `classifier: question "${key}": a score needs ${MIN_SCORE_LEVELS}..${MAX_SCORE_LEVELS} ordered levels, got ${n}`,
      )
    }
  }
}

// ---------------------------------------------------------------- the wire

/**
 * Canonical JSON: object keys sorted recursively in CODE-UNIT (ASCII) order, arrays NEVER
 * reordered, compact separators, and `<>&'"` plus non-ASCII transmitted raw.
 *
 * Hand-rolled because `JSON.stringify` has no recursive key sort, and its `replacer` ARRAY form
 * applies one key list at EVERY depth — it cannot express "sort whatever keys are here". The
 * per-value escaping and number formatting ARE delegated to `JSON.stringify`, which emits the
 * shortest round-tripping decimal (so 1.21 is "1.21" and integer 0 is "0", not "0.0") and does
 * not escape `<`, `>`, `&` or non-ASCII.
 *
 * `undefined` is ABSENT, never `null`: an object key whose value is undefined is dropped.
 */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map((v) => (v === undefined ? "null" : canonicalJson(v))).join(",") + "]"
  if (value !== null && typeof value === "object") {
    const obj = value as Record<string, unknown>
    // `sort()` with no comparator compares UTF-16 code units — ASCII order, and NOT the
    // culture-sensitive order `localeCompare` would give ("10_alpha" < "Alpha" < "beta" < "café").
    const keys = Object.keys(obj)
      .filter((k) => obj[k] !== undefined)
      .sort()
    return "{" + keys.map((k) => JSON.stringify(k) + ":" + canonicalJson(obj[k])).join(",") + "}"
  }
  const s = JSON.stringify(value)
  return s === undefined ? "null" : s
}

/**
 * The bytes the byte-identity claim covers: `model` + `questions`.
 *
 * `state` is deliberately NOT here. It is transmitted verbatim as the host supplied it and is
 * OUTSIDE the claim, because numbers do not canonicalise across languages (`-0.0` renders four
 * ways across our own seven runtimes). Do not re-widen this: a caller who needs their state
 * pinned canonicalises it themselves before handing it over.
 */
export function canonicalRequest(model: string, questions: Record<string, Question>): string {
  const qs: Record<string, unknown> = {}
  for (const [key, q] of Object.entries(questions)) {
    if (!q) throw new Error(`classifier: question "${key}" is missing`)
    qs[key] = questionWire(q)
  }
  return canonicalJson({ model, questions: qs })
}

// ---------------------------------------------------------------- answers

/** The probability that a statement holds. NO confidence: the number is the answer. */
export interface NoulAnswer {
  type: "noul"
  noul: number
}

/** One option from the offered set, with a probability for EVERY offered option. */
export interface ChoiceAnswer {
  type: "choice"
  choice: string
  probabilities: Record<string, number>
  confidence: number
  /**
   * DERIVED from `probabilities` on decode and never read from the wire (§8B):
   * `max|p - 1/n| <= 0.05`, inclusive, `n` = the number of entries in the map.
   *
   * ADVISORY, NOT a correctness signal. It detects an encoding that gave the model nothing to
   * rank on — it cannot distinguish a good encoding from a subtly wrong one. `calibrated`
   * carries the same caveat.
   */
  nearUniform: boolean
}

/** A rating against the ordered rubric. `score` MAY fall between levels (1.21 is a real answer). */
export interface ScoreAnswer {
  type: "score"
  score: number
  legend: Record<string, string>
  probabilities: Record<string, number>
  confidence: number
}

/**
 * One typed answer, discriminated by `type`. Named `DecisionAnswer` because §10 already owns
 * `Answer` (the suspension resolution) — same idea, different seam.
 */
export type DecisionAnswer = NoulAnswer | ChoiceAnswer | ScoreAnswer

/** The wire's usage block. `cost` is absent on some backends. */
export interface ClassifierUsage {
  inputTokens: number
  outputTokens: number
  cost?: number
}

/** The legend in LEVEL order, which the map itself loses ("2" sorts before "10" as a level). */
export function levels(answer: ScoreAnswer): string[] {
  return Object.keys(answer.legend)
    .sort((a, b) => (a.length !== b.length ? a.length - b.length : a < b ? -1 : a > b ? 1 : 0))
    .map((k) => answer.legend[k])
}

/**
 * One answer per question, keyed by the CALLER's keys. The keys are addressing, not content:
 * they are never transmitted, so a key may be a tool, skill or agent name verbatim.
 */
export class Decision {
  constructor(
    /** What actually answered, which may be more specific than the model that was asked for. */
    readonly model: string,
    readonly answers: Record<string, DecisionAnswer>,
    readonly usage: ClassifierUsage,
    /**
     * Whether the probabilities are calibrated. `systemone` reports true; `llm` reports false
     * unless it derived them from provider token probabilities. A THRESHOLD TUNED AGAINST ONE
     * BACKEND DOES NOT TRANSFER TO ANOTHER.
     */
    readonly calibrated: boolean,
  ) {}

  /** Typed read. Throws (never returns undefined) on a wrong-type or absent key. */
  noul(key: string): NoulAnswer {
    return this.typed(key, "noul")
  }
  choice(key: string): ChoiceAnswer {
    return this.typed(key, "choice")
  }
  score(key: string): ScoreAnswer {
    return this.typed(key, "score")
  }

  private typed<T extends DecisionAnswer["type"]>(key: string, want: T): Extract<DecisionAnswer, { type: T }> {
    const a = this.answers[key]
    if (a === undefined) throw new Error(`classifier: no answer "${key}" in this decision`)
    if (a.type !== want) throw new Error(`classifier: answer "${key}" is a ${a.type} answer, not ${want}`)
    return a as Extract<DecisionAnswer, { type: T }>
  }
}

/**
 * Whether a choice answer's probability map is indistinguishable from flat (§8B):
 *
 *     nearUniform  ⇔  max over i of |p_i - 1/n|  <=  0.05
 *
 * `n` is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED — not
 * renormalised, not sorted, not rounded; an offered option absent from the map counts as 0 by
 * not being an entry. The tolerance is ABSOLUTE (a relative band collapses below the wire's
 * two-decimal rounding on a 255-option roster) and the comparison is INCLUSIVE. `n === 1` is
 * trivially uniform. An empty map has no distribution at all and is reported false.
 */
export function nearUniform(probabilities: Record<string, number>): boolean {
  const values = Object.values(probabilities ?? {})
  if (values.length === 0) return false
  if (values.length === 1) return true
  const target = 1 / values.length
  return values.every((p) => Math.abs(p - target) <= NEAR_UNIFORM_TOLERANCE)
}

/**
 * Decode a backend response into a Decision. The discriminated decode is the single place
 * `nearUniform` is derived. A malformed answer is an error, never a repaired one (ADR 0020).
 */
export function decodeDecision(raw: unknown, fallbackModel: string): Decision {
  if (raw === null || typeof raw !== "object") throw new Error("classifier: response is not an object")
  const body = raw as Record<string, any>
  const answers: Record<string, DecisionAnswer> = {}
  for (const [key, value] of Object.entries(body.answers ?? {})) {
    if (value === null || typeof value !== "object") throw new Error(`classifier: answer "${key}" is not an object`)
    const a = value as Record<string, any>
    switch (a.type) {
      case "noul":
        answers[key] = { type: "noul", noul: num(a.noul) }
        break
      case "choice":
        answers[key] = {
          type: "choice",
          choice: String(a.choice ?? ""),
          probabilities: numbers(a.probabilities),
          confidence: num(a.confidence),
          nearUniform: nearUniform(numbers(a.probabilities)),
        }
        break
      case "score":
        answers[key] = {
          type: "score",
          score: num(a.score),
          legend: strings(a.legend),
          probabilities: numbers(a.probabilities),
          confidence: num(a.confidence),
        }
        break
      default:
        throw new Error(`classifier: answer "${key}": unknown type ${JSON.stringify(a.type)}`)
    }
  }
  const usage: ClassifierUsage = {
    inputTokens: num(body.usage?.input_tokens),
    outputTokens: num(body.usage?.output_tokens),
  }
  // Absent stays absent: a cost of 0 is a real cost and undefined is "not reported".
  if (body.usage?.cost !== undefined) usage.cost = num(body.usage.cost)
  // Absent ⇒ true: the systemone wire reports calibration by being itself. A backend that is
  // not calibrated says so explicitly.
  return new Decision(String(body.model ?? fallbackModel), answers, usage, body.calibrated !== false)
}

function num(v: unknown): number {
  return typeof v === "number" ? v : 0
}
/** A zero probability stays an ENTRY: the map is copied key-for-key, never filtered. */
function numbers(v: unknown): Record<string, number> {
  const out: Record<string, number> = {}
  for (const [k, p] of Object.entries((v ?? {}) as Record<string, unknown>)) out[k] = num(p)
  return out
}
function strings(v: unknown): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, s] of Object.entries((v ?? {}) as Record<string, unknown>)) out[k] = String(s)
  return out
}

// ---------------------------------------------------------------- options

/** `"systemone" | "llm" | "custom" | "static"` — the same slot as the client's `style`. */
export type ClassifierStyle = "systemone" | "llm" | "custom" | "static"

/**
 * One entry of the `static` corpus. It is keyed on the canonical request AND the state: several
 * recorded entries legitimately share one questions payload and differ only in state (the three
 * guard bands of `examples/judge/decisions.json` do exactly that), so a corpus keyed on the
 * canonical request alone cannot tell them apart.
 */
export interface RecordedDecision {
  state: unknown
  questions: Record<string, Question>
  /** The recorded backend response body, verbatim. */
  response: unknown
}

/** The host's own backend (`style: "custom"`). Every wire option is ignored. */
export type EvaluateFn = (
  state: unknown,
  questions: Record<string, Question>,
  signal?: AbortSignal,
) => Promise<Decision> | Decision

/**
 * Mirrors §8 `ClientOptions` field-for-field wherever a field makes sense, so a host that has
 * configured one has configured the other.
 */
export interface ClassifierOptions {
  /** Default `"systemone"`. */
  style?: ClassifierStyle
  /** A named `baseUrl` + `model` + `apiKeyEnv` pairing (`"typesafe"` | `"openrouter"`), set as a
   * UNIT. Default `"typesafe"`. The three individual options still win where given, for a
   * self-hosted origin — but a known-bad mixture is refused at construction, not 700 ms later
   * as an "Unknown model" 400. */
  backend?: ClassifierBackend
  /** Default `https://api.typesafe.ai/v1`. OpenRouter serves this wire today. */
  baseUrl?: string
  /** Default `"jev-latest"`. Pin it once thresholds are tuned. */
  model?: string
  /** The NAME of the env var holding the credential, never the value — read at call time and
   * never logged. §8's `apiKey` takes a value; this option deliberately does not. */
  apiKeyEnv?: string
  /** Extra headers. Values expand `${ENV_VAR}` from the environment AT CALL TIME and are NEVER
   * logged, identically to remote-MCP headers (§2). */
  headers?: Record<string, string>
  /** Bounds ONE request (a classifier has no loop to bound). Default 10 s. */
  timeoutMs?: number
  /** §8 Gap 2 injectable transport. Scope is the classifier path only. */
  fetch?: typeof fetch
  /** Retries on transient errors (`408`/`429`/`500`/`502`/`503`/`504`/`529` + network). Default 2.
   * Widen the status set with `retryableStatuses`. */
  retries?: number
  /** Base backoff in ms (exponential). Default 500. */
  retryBaseMs?: number
  /**
   * Extra HTTP statuses to treat as retryable, ADDED to the default set
   * (`429`/`500`/`502`/`503`/`504`/`529` plus `408` here). It can only widen: a host cannot remove `429` and
   * lose `Retry-After` handling with it. This sets the DEFAULT classification; `onError` still
   * runs per attempt and has the final say, so `onError` returning `"fail"` overrides a status
   * listed here. Example: a Cloudflare-fronted origin that answers `520`–`527`.
   */
  retryableStatuses?: readonly number[]

  /** §8 Resilience. REUSES the client's `ErrorInfo`/`ErrorTier` and the `Retry-After`
   * delay-seconds rule verbatim — there is no second retry policy, and no `"suspend"` tier. */
  onError?: (info: ErrorInfo) => ErrorTier
  /** §8 Gap 1. Extra top-level keys shallow-merged into the body after the classifier builds its
   * own; a `requestParams` key WINS on collision. Omit ⇒ body byte-identical. */
  requestParams?: Record<string, unknown>
  /** §8 Gap 1. Receives the assembled body after the `requestParams` merge and returns the body
   * to send. Order: base body → requestParams merge → bodyTransform → marshal, exactly as §8. */
  bodyTransform?: (body: Record<string, any>) => Record<string, any> | undefined | void
  /** Emits `classifier.evaluate` events into the SAME §8 sink, and carries the
   * degenerate-criteria warning as `classifier.warning`. */
  onMetric?: (ev: MetricEvent) => void
  /** `style: "llm"` only — the §8 Client to emulate over. */
  client?: Client
  /** `style: "custom"` only — the host's own function. */
  evaluate?: EvaluateFn
  /** `style: "static"` only — the recorded corpus. */
  decisions?: RecordedDecision[]
}

// ---------------------------------------------------------------- classifier

/** The whole seam: one verb. */
export class Classifier {
  private readonly style: ClassifierStyle
  private readonly baseUrl: string
  private readonly model: string
  private readonly apiKeyEnv: string
  private readonly timeoutMs: number
  private readonly corpus = new Map<string, unknown>()
  /** The once-per-question-key set for the degenerate warning, so a per-turn judge does not
   * flood the sink. */
  private readonly warned = new Set<string>()

  constructor(private readonly opts: ClassifierOptions) {
    this.style = opts.style ?? "systemone"
    const preset = CLASSIFIER_BACKENDS[opts.backend ?? "typesafe"]
    this.baseUrl = opts.baseUrl ?? preset.baseUrl
    this.model = opts.model ?? preset.model
    this.apiKeyEnv = opts.apiKeyEnv ?? preset.apiKeyEnv
    const mismatch = backendMismatch(this.baseUrl, this.model)
    if (mismatch) throw new Error(mismatch)
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_CLASSIFIER_TIMEOUT_MS
    switch (this.style) {
      case "systemone":
        break
      case "llm":
        if (!opts.client) throw new Error('classifier: style "llm" requires `client`')
        break
      case "custom":
        if (!opts.evaluate) throw new Error('classifier: style "custom" requires `evaluate`')
        break
      case "static":
        for (const [i, rec] of (opts.decisions ?? []).entries()) {
          try {
            this.corpus.set(this.staticKey(rec.state, rec.questions), rec.response)
          } catch (e) {
            throw new Error(`classifier: recorded decision ${i}: ${(e as Error).message}`)
          }
        }
        break
      default: {
        const unknown: never = this.style
        throw new Error(`classifier: unknown style ${JSON.stringify(unknown)}`)
      }
    }
  }

  /**
   * The whole contract: a state plus typed questions in, a Decision out. Questions are
   * INDEPENDENT — one answer is never context for another.
   */
  async evaluate(state: unknown, questions: Record<string, Question>, signal?: AbortSignal): Promise<Decision> {
    const start = Date.now()
    const keys = Object.keys(questions ?? {}).sort()
    if (keys.length === 0) {
      const err = new Error("classifier: no questions to evaluate")
      this.emitError(start, err)
      throw err
    }
    try {
      // Limits are enforced CLIENT-SIDE, before the request: the caller finds out faster and
      // more legibly than from the backend's own 400. Keys are walked in sorted order so the
      // same malformed set always names the same key first.
      for (const key of keys) {
        const q = questions[key]
        if (!q) throw new Error(`classifier: question "${key}" is missing`)
        validateQuestion(key, q)
      }
    } catch (e) {
      this.emitError(start, e)
      throw e
    }
    // Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED and the
    // warning is the entire observable effect.
    this.reportDegenerate(keys, questions)

    let decision: Decision
    try {
      switch (this.style) {
        case "custom":
          decision = await this.opts.evaluate!(state, questions, signal)
          break
        case "static":
          decision = this.evaluateStatic(state, questions)
          break
        case "llm":
          decision = await this.evaluateLLM(state, questions, signal)
          break
        default:
          decision = await this.evaluateSystemOne(state, questions, signal)
      }
    } catch (e) {
      this.emitError(start, e)
      throw e
    }
    this.emit({
      event: "classifier.evaluate",
      model: decision.model,
      status: "ok",
      ms: Date.now() - start,
      promptTokens: decision.usage.inputTokens,
      completionTokens: decision.usage.outputTokens,
    })
    return decision
  }

  // ---------------------------------------------------------------- degenerate

  /**
   * Emits ONE warning per degenerate question key per classifier, naming the key, and changes
   * nothing about the request. Repairing would invent option descriptions the caller did not
   * write, and the library has no way to know what the options mean.
   */
  private reportDegenerate(keys: string[], questions: Record<string, Question>): void {
    for (const key of keys) {
      const q = questions[key]
      if (q.type !== "choice") continue
      const reason = degenerateCriteria(q.criteria)
      if (!reason || this.warned.has(key)) continue
      this.warned.add(key)
      this.emit({
        event: "classifier.warning",
        question: key,
        warning:
          `classifier: question "${key}" has degenerate criteria (${reason}) — every option reads ` +
          "the same to the model and the answer ranks at chance; describe what picking each " +
          "option would MEAN (SPEC.md §8B)",
      })
    }
  }

  // ---------------------------------------------------------------- backends

  /**
   * Assembles the request: the canonical model + questions, plus state VERBATIM as the host
   * supplied it, then the §8 Gap 1 pipeline in §8 order (base → requestParams merge →
   * bodyTransform → marshal).
   */
  private body(state: unknown, questions: Record<string, Question>): string {
    const qs: Record<string, unknown> = {}
    for (const [key, q] of Object.entries(questions)) qs[key] = questionWire(q)
    let body: Record<string, any> = { model: this.model, questions: qs, state }
    // A requestParams key WINS on collision.
    if (this.opts.requestParams) body = { ...body, ...this.opts.requestParams }
    if (this.opts.bodyTransform) body = this.opts.bodyTransform(body) || body
    return canonicalJson(body)
  }

  /** Identifies a recorded decision by the canonical request AND the state. */
  private staticKey(state: unknown, questions: Record<string, Question>): string {
    return canonicalRequest(this.model, questions) + "\u0000" + canonicalJson(state)
  }

  private evaluateStatic(state: unknown, questions: Record<string, Question>): Decision {
    const raw = this.corpus.get(this.staticKey(state, questions))
    if (raw === undefined) throw new Error("classifier: static: no recorded decision for this request+state")
    return decodeDecision(raw, this.model)
  }

  private async evaluateSystemOne(
    state: unknown,
    questions: Record<string, Question>,
    signal?: AbortSignal,
  ): Promise<Decision> {
    const text = await this.post(this.body(state, questions), signal)
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch (e) {
      throw new Error(`classifier: response was not JSON: ${(e as Error).message}`)
    }
    return decodeDecision(parsed, this.model)
  }

  /**
   * The one POST, with the §8 retry budget, reusing the client's `ErrorInfo`/`ErrorTier`
   * classifier and the `Retry-After` delay-seconds rule verbatim.
   *
   * NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE APPEARS ON ANY PATH OUT OF HERE: an
   * authentication failure names the status and the endpoint, nothing else, and a 401/403 body
   * is never echoed back (a gateway happily reflects a bad Authorization header into its own
   * 401 text).
   */
  private async post(payload: string, signal?: AbortSignal): Promise<string> {
    const endpoint = this.baseUrl.replace(/\/+$/, "") + "/systemone"
    const retries = this.opts.retries ?? 2
    const base = this.opts.retryBaseMs ?? 500
    const classify = this.opts.onError ?? ((info: ErrorInfo): ErrorTier => (info.retryable ? "retry" : "fail"))
    const doFetch = this.opts.fetch ?? fetch
    let lastErr: unknown
    for (let attempt = 0; ; attempt++) {
      const timer = new AbortController()
      const t = setTimeout(() => timer.abort(new Error(`classifier: request timeout after ${this.timeoutMs}ms`)), this.timeoutMs)
      ;(t as any).unref?.()
      const onOuter = () => timer.abort(signal!.reason)
      if (signal) {
        if (signal.aborted) {
          clearTimeout(t)
          throw signal.reason ?? new Error("aborted")
        }
        signal.addEventListener("abort", onOuter, { once: true })
      }
      let res: Response | undefined
      let thrown: unknown
      try {
        res = await doFetch(endpoint, {
          method: "POST",
          headers: this.requestHeaders(),
          body: payload,
          signal: timer.signal,
        })
      } catch (e) {
        thrown = e
      } finally {
        clearTimeout(t)
        signal?.removeEventListener("abort", onOuter)
      }
      if (res && res.ok) return await res.text()
      if (signal?.aborted) throw signal.reason ?? new Error("aborted") // caller cancellation is never retried
      let retryAfter: string | null = null
      if (res) {
        const text = await res.text().catch(() => "")
        retryAfter = res.headers.get("retry-after")
        lastErr = new Error(`classifier: POST ${endpoint}: HTTP ${res.status}${cause(res.status, text)}`)
      } else {
        lastErr = new Error(`classifier: POST ${endpoint}: ${errText(thrown)}`)
      }
      const retryable = res ? res.status === 408 || isRetryableStatus(res.status, this.opts.retryableStatuses) : true
      if (attempt >= retries) throw lastErr
      const tier = classify(res ? { status: res.status, attempt, retryable } : { error: thrown, attempt, retryable })
      if (tier !== "retry") throw lastErr
      // `??`, not `||`: Retry-After: 0 means "retry now", not "no opinion".
      await sleep(retryAfterMs(retryAfter) ?? base * 2 ** attempt)
    }
  }

  /** Credential and `${ENV_VAR}` headers resolve AT CALL TIME. Never logged, never returned. */
  private requestHeaders(): Record<string, string> {
    const headers: Record<string, string> = { "Content-Type": "application/json" }
    const key = process.env[this.apiKeyEnv]
    if (key) headers["Authorization"] = `Bearer ${key}`
    for (const [k, v] of Object.entries(this.opts.headers ?? {})) headers[k] = expandEnv(v)
    return headers
  }

  /**
   * The three question types rendered as ONE structured-output call on any §8 Client — the
   * vendor-neutral fallback, so a host with no System One credential runs the same questions on
   * a cheap chat model. `calibrated` is FALSE: the numbers are the model's self-report, not
   * token probabilities.
   */
  private async evaluateLLM(
    state: unknown,
    questions: Record<string, Question>,
    signal?: AbortSignal,
  ): Promise<Decision> {
    const qs: Record<string, unknown> = {}
    for (const [key, q] of Object.entries(questions)) qs[key] = questionWire(q)
    const prompt =
      "Answer every question about the state below. Questions are INDEPENDENT: one answer is " +
      "never context for another.\n\n" +
      `STATE:\n${canonicalJson(state)}\n\nQUESTIONS:\n${canonicalJson(qs)}\n\n` +
      "Reply with JSON only, no prose and no code fence, shaped exactly:\n" +
      '{"answers":{"<key>":{"type":"noul","noul":0.0}}}\n' +
      'A "noul" answer is {"type":"noul","noul":<0..1>}. A "choice" answer is ' +
      '{"type":"choice","choice":"<one offered option id>","probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. ' +
      'A "score" answer is {"type":"score","score":<a number within the rubric bounds, fractional allowed>,' +
      '"legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},"confidence":<0..1>}.'
    // No toolkit at all: a classifier has no loop and executes nothing (SPEC §0.10, ADR 0023).
    const run = await this.opts.client!.run(prompt, { signal })
    const payload = firstJsonObject(run.text)
    let parsed: unknown
    try {
      parsed = JSON.parse(payload)
    } catch (e) {
      // Never repaired: an unparseable answer is no answer (ADR 0020).
      throw new Error(`classifier: llm: ${(e as Error).message}`)
    }
    const d = decodeDecision(parsed, this.model)
    return new Decision(
      d.model || this.model,
      d.answers,
      { inputTokens: run.usage.promptTokens, outputTokens: run.usage.completionTokens },
      // The model reports no calibration and none is derived here (ADR 0020).
      false,
    )
  }

  // ---------------------------------------------------------------- metrics

  private emit(ev: MetricEvent): void {
    this.opts.onMetric?.(ev)
  }

  private emitError(start: number, e: unknown): void {
    this.emit({
      event: "classifier.evaluate",
      model: this.model,
      status: "error",
      ms: Date.now() - start,
      promptTokens: 0,
      completionTokens: 0,
      error: errText(e),
    })
  }
}

/** Builds a Classifier, applying the §8B defaults and rejecting a style whose required option
 * is missing before any call is made. */
export function createClassifier(opts: ClassifierOptions = {}): Classifier {
  return new Classifier(opts)
}

// ---------------------------------------------------------------- helpers

/**
 * The §8B predicate. Degenerate ⇔ ANY of:
 *   1. every value is empty (empty string or absent), or
 *   2. every value equals its own key, or
 *   3. every value is identical to every other value (n >= 2).
 *
 * A single-option choice (n === 1) is NEVER reported: there is nothing to differentiate. Returns
 * the reason, or "" when the criteria are fine.
 */
export function degenerateCriteria(criteria: Record<string, string>): string {
  const entries = Object.entries(criteria ?? {})
  if (entries.length < 2) return ""
  if (entries.every(([, v]) => (v ?? "") === "")) return "every description is empty"
  if (entries.every(([k, v]) => v === k)) return "every description is just its own option id"
  const first = entries[0][1]
  if (entries.every(([, v]) => v === first)) return "every description is identical"
  return ""
}

/**
 * Surfaces a backend's reported cause intact — EXCEPT on an authentication status: a 401/403
 * body routinely reflects the credential or the header that was sent, so it never reaches a log,
 * a metric, an error or a return value.
 */
function cause(status: number, body: string): string {
  if (status === 401 || status === 403) return ""
  // ONE policy, now shared with the §8 client path: redact account identifiers, then cap.
  const s = capErrorBody(redactErrorBody(body))
  return s ? ": " + s : ""
}

/**
 * The one mixture known to fail, caught before the wire: TypeSafe's unqualified `jev-*` model id
 * pointed at the OpenRouter gateway, which has never heard of it. Returns the message to fail
 * with, or `""` when the pairing is fine.
 */
export function backendMismatch(baseUrl: string, model: string): string {
  const host = baseUrl.replace(/^[a-z]+:\/\//i, "").split("/")[0].split(":")[0].toLowerCase()
  if (host !== "openrouter.ai" && !host.endsWith(".openrouter.ai")) return ""
  if (!/^jev-/.test(model)) return ""
  return `classifier: model "${model}" is TypeSafe's spelling; on openrouter.ai use "${CLASSIFIER_BACKENDS.openrouter.model}"`
}

/** `${ENV_VAR}` expands at call time; an unset var expands to the empty string, as §2. */
function expandEnv(value: string): string {
  return value.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, name: string) => process.env[name] ?? "")
}

/** The outermost JSON object of a model reply, which may arrive wrapped in a fence or prose. */
function firstJsonObject(s: string): string {
  const start = s.indexOf("{")
  const end = s.lastIndexOf("}")
  if (start < 0 || end <= start) throw new Error("classifier: llm: no JSON object in the reply")
  return s.slice(start, end + 1)
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function sleep(ms: number): Promise<void> {
  // NOT unref'd, unlike the timeout watchdog above. This timer is the retry
  // backoff we are about to await, so it is real pending work: unref'ing it
  // tells Node the process need not stay alive for it, and the loop can resolve
  // before the retry ever happens. The §8 client's `delay` has never unref'd for
  // the same reason. Caught on Node 22, where it cancelled every test after the
  // first one to exercise a classifier retry ("Promise resolution is still
  // pending but the event loop has already resolved"); Node 24 happened to hide it.
  return new Promise((resolve) => {
    setTimeout(resolve, ms)
  })
}

