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
import { createHash } from "node:crypto"
import type { Client } from "./client.js"
import { type ErrorInfo, type ErrorTier, type MetricEvent, redactErrorBody, capErrorBody } from "./client.js"
import { retryAfterMs, isRetryableStatus } from "./retry.js"
import { isContentPart, type ContentPart } from "./content.js"

// ---------------------------------------------------------------- constants

/** The System One endpoint base. */
export const DEFAULT_CLASSIFIER_BASE_URL = "https://api.typesafe.ai/v1"
/** The floating model alias. Pin it (e.g. `jev-1.13.0`) once thresholds are tuned. */
export const DEFAULT_CLASSIFIER_MODEL = "jev-latest"
/** The NAME of the env var holding the credential — never the value. */
export const DEFAULT_CLASSIFIER_API_KEY_ENV = "TYPESAFE_API_KEY"
/** Bounds ONE request; a classifier has no loop to bound. */
export const DEFAULT_CLASSIFIER_TIMEOUT_MS = 10_000
/** `evaluateBatch`: at most this many states in flight by default. */
export const DEFAULT_BATCH_CONCURRENCY = 16

/**
 * A named BACKEND: `baseUrl`, `model` and `apiKeyEnv` are only jointly valid, so they travel as
 * a UNIT (ADR 0027 D1). `jev-latest` is TypeSafe's spelling; the gateway spells the same model
 * `typesafe/jev-1.14`, and mixing the two is issue #91. `custom` is a self-hosted System One
 * endpoint and requires `baseUrl` + `supports` (SPEC §8B "Backends, images, cache and validation").
 */
export type ClassifierBackend = "typesafe" | "openrouter" | "cloudflare" | "vercel" | "custom"

/** What a backend+model can take. DATA: a fail-fast for what the table says, never a claim about
 * the vendor. Override per classifier with `ClassifierOptions.supports`. */
export interface ClassifierSupports {
  images: boolean
}

/** One row of the backend table. */
export interface ClassifierBackendPreset {
  /** `""` on `custom`, which requires `baseUrl`. May carry `${NAME}`, expanded at call time. */
  baseUrl: string
  model: string
  apiKeyEnv: string
  /** `systemone`: POST {baseUrl}/systemone. `cloudflare`: POST {baseUrl}, `/ai/run` envelope. */
  wire: "systemone" | "cloudflare"
  /** Send `TypeSafe-Danger-Zone-Images: true` when a request carries images. */
  imagesHeader: boolean
  /** The default support for a model on this backend; absent on `custom`. */
  supports?: (model: string) => ClassifierSupports
}

/** TypeSafe's required opt-in header for image ("danger zone") input. */
export const CLASSIFIER_IMAGES_HEADER = "TypeSafe-Danger-Zone-Images"
/** Image limits (docs.typesafe.ai/images): checked client-side, never resized. */
export const CLASSIFIER_IMAGE_MIN_SIDE = 280
export const CLASSIFIER_IMAGE_MAX_SIDE = 1568
export const CLASSIFIER_IMAGE_MAX_BYTES = 16 * 1024 * 1024
export const CLASSIFIER_REQUEST_IMAGE_BYTES = 32 * 1024 * 1024

/**
 * The backend table (PROPOSED defaults, owner to confirm — design.md PD-1..PD-4). Vercel and
 * Cloudflare-Jev images stay false until a live probe proves them.
 */
export const CLASSIFIER_BACKENDS: Readonly<Record<ClassifierBackend, Readonly<ClassifierBackendPreset>>> = Object.freeze({
  typesafe: {
    baseUrl: DEFAULT_CLASSIFIER_BASE_URL,
    model: DEFAULT_CLASSIFIER_MODEL,
    apiKeyEnv: DEFAULT_CLASSIFIER_API_KEY_ENV,
    wire: "systemone",
    imagesHeader: true,
    supports: () => ({ images: true }),
  },
  openrouter: {
    baseUrl: "https://openrouter.ai/api/v1",
    model: "typesafe/jev-1.14",
    apiKeyEnv: "OPENROUTER_API_KEY",
    wire: "systemone",
    imagesHeader: true,
    supports: () => ({ images: true }),
  },
  cloudflare: {
    baseUrl: "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run",
    model: "typesafe/jev",
    apiKeyEnv: "CLOUDFLARE_JEV_API_KEY",
    wire: "cloudflare",
    imagesHeader: false,
    supports: (model: string) => ({ images: model.includes("clef") }),
  },
  vercel: {
    baseUrl: "https://ai-gateway.vercel.sh/typesafe/v1",
    model: "typesafe-ai/jev",
    apiKeyEnv: "AI_GATEWAY_API_KEY",
    wire: "systemone",
    imagesHeader: true,
    supports: () => ({ images: false }),
  },
  custom: {
    baseUrl: "",
    model: DEFAULT_CLASSIFIER_MODEL,
    apiKeyEnv: DEFAULT_CLASSIFIER_API_KEY_ENV,
    wire: "systemone",
    imagesHeader: true,
  },
})

/**
 * Raised BEFORE any network call when the backend table says the backend cannot take what the
 * request carries. Message, exact: `classifier: <backend> (<model>) does not support <feature>`.
 */
export class ClassifierUnsupported extends Error {
  constructor(
    readonly backend: string,
    readonly model: string,
    readonly feature: string,
  ) {
    super(`classifier: ${backend} (${model}) does not support ${feature}`)
    this.name = "ClassifierUnsupported"
  }
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

/** The modelled question types, discriminated by `type`. */
export type Question = NoulQuestion | ChoiceQuestion | ScoreQuestion

/**
 * The OPEN end of the question union (§8B): a System One type the library does not model (e.g.
 * Jev 1.14 `box`), sent as written with no client-side limits. `type` must be non-empty.
 */
export interface RawQuestion {
  type: string
  instructions: string
  /** Sent verbatim when present, absent otherwise. */
  criteria?: unknown
}

/** Every question `evaluate` accepts: the modelled three plus a raw one. */
export type AnyQuestion = Question | RawQuestion

const MODELLED_TYPES = new Set(["noul", "choice", "score"])

/** True when `q` is a raw question (its type is not noul/choice/score). Not a type guard: every
 * modelled question is structurally a `RawQuestion` too. */
export function isRawQuestion(q: AnyQuestion): boolean {
  return !MODELLED_TYPES.has(q?.type as string)
}

/** A raw question, sent as written. */
export function raw(type: string, instructions: string, criteria?: unknown): RawQuestion {
  return criteria === undefined ? { type, instructions } : { type, instructions, criteria }
}

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
export function questionWire(question: AnyQuestion): Record<string, unknown> {
  if (isRawQuestion(question)) {
    const r = question as RawQuestion
    return r.criteria === undefined
      ? { type: r.type, instructions: r.instructions }
      : { type: r.type, instructions: r.instructions, criteria: r.criteria }
  }
  const q = question as Question
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
function validateQuestion(key: string, question: AnyQuestion): void {
  const q = question as Question
  if (isRawQuestion(question)) {
    if (!question?.type) throw new Error(`classifier: question "${key}": a raw question needs a type`)
    return
  }
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
export function canonicalRequest(model: string, questions: Record<string, AnyQuestion>): string {
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

/**
 * An answer of a type the library does not model (the open union, §8B). It may appear in
 * `Decision.answers` at runtime (read it with `decision.raw(key)` or `isRawAnswer`); `value` is the
 * whole answer object as decoded, `type` included. It is kept out of `DecisionAnswer` so existing
 * `switch (a.type)` narrowing keeps compiling.
 */
export interface RawAnswer {
  type: string
  value: Record<string, unknown>
}

/** True when an answer is a raw (unmodelled-type) answer. */
export function isRawAnswer(a: unknown): a is RawAnswer {
  return !!a && typeof a === "object" && !MODELLED_TYPES.has((a as any).type) && "value" in (a as any)
}

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
    /** True when the decision came from `ClassifierOptions.cache` and no request was sent. */
    readonly cached: boolean = false,
  ) {}

  /** Typed read of a raw (unmodelled-type) answer. Throws on an absent key or a modelled type. */
  raw(key: string): RawAnswer {
    const a: unknown = this.answers[key]
    if (a === undefined) throw new Error(`classifier: no answer "${key}" in this decision`)
    if (!isRawAnswer(a)) throw new Error(`classifier: answer "${key}" is a ${(a as any).type} answer, not raw`)
    return a
  }

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
        // The union is OPEN (§8B): an unmodelled type (e.g. Jev 1.14 "box") decodes to a raw
        // answer instead of failing the whole decision.
        ;(answers as Record<string, unknown>)[key] = { type: String(a.type ?? ""), value: { ...a } } satisfies RawAnswer
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

/**
 * `"systemone" | "llm" | "custom" | "static"` — the same slot as the client's `style`.
 *
 * `"llm"` is DEPRECATED (ADR 0038 D3: System One is the single product wire). It keeps working
 * byte-identically, as an alias, for at least one minor release; there is no runtime warning.
 */
export type ClassifierStyle = "systemone" | "llm" | "custom" | "static"

/**
 * One entry of the `static` corpus. It is keyed on the canonical request AND the state: several
 * recorded entries legitimately share one questions payload and differ only in state (the three
 * guard bands of `examples/judge/decisions.json` do exactly that), so a corpus keyed on the
 * canonical request alone cannot tell them apart.
 */
export interface RecordedDecision {
  state: unknown
  questions: Record<string, AnyQuestion>
  /** The recorded backend response body, verbatim. */
  response: unknown
}

/** The host's own backend (`style: "custom"`). Every wire option is ignored. */
export type EvaluateFn = (
  state: unknown,
  questions: Record<string, AnyQuestion>,
  signal?: AbortSignal,
) => Promise<Decision> | Decision

/**
 * Mirrors §8 `ClientOptions` field-for-field wherever a field makes sense, so a host that has
 * configured one has configured the other.
 */
export interface ClassifierOptions {
  /** Default `"systemone"`. */
  style?: ClassifierStyle
  /** A named backend row (`"typesafe"` | `"openrouter"` | `"cloudflare"` | `"vercel"` | `"custom"`), set as a
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
  /** `style: "llm"` only — the §8 Client to emulate over.
   * @deprecated `style: "llm"` is deprecated (System One is the single wire); it still works. */
  client?: Client
  /** `style: "custom"` only — the host's own function. */
  evaluate?: EvaluateFn
  /** `style: "static"` only — the recorded corpus. */
  decisions?: RecordedDecision[]
  /** Overrides the backend table's support row for this classifier. Required with `backend: "custom"`. */
  supports?: ClassifierSupports
  /** Opt-in answer cache (§8B). Absent ⇒ no caching. Consulted by style `systemone` only; only
   * replies that pass `validateDecision` are stored. Methods may return values or Promises. */
  cache?: ClassifierCache
}

/** An injected answer cache. Values are opaque strings: the served model under a model key, the
 * System One reply body under a decision key. */
export interface ClassifierCache {
  get(key: string): string | undefined | null | Promise<string | undefined | null>
  set(key: string, value: string): void | Promise<void>
}

/** One state's outcome from `states(...).evaluate`: exactly one of `decision` / `error` is set. */
export interface BatchResult {
  decision?: Decision
  error?: Error
}

/** A named question (`judge.noul(...)` etc. build these). */
export interface NamedClassifierQuestion {
  name: string
  question: AnyQuestion
}

// ---------------------------------------------------------------- classifier

/** The whole seam: one verb. */
export class Classifier {
  private readonly style: ClassifierStyle
  private readonly baseUrl: string
  private readonly model: string
  private readonly apiKeyEnv: string
  private readonly timeoutMs: number
  private readonly backend: ClassifierBackend
  private readonly preset: Readonly<ClassifierBackendPreset>
  private readonly supports: ClassifierSupports
  private readonly corpus = new Map<string, unknown>()
  /** The once-per-question-key set for the degenerate warning, so a per-turn judge does not
   * flood the sink. */
  private readonly warned = new Set<string>()

  constructor(private readonly opts: ClassifierOptions) {
    this.style = opts.style ?? "systemone"
    this.backend = opts.backend ?? "typesafe"
    if (!Object.prototype.hasOwnProperty.call(CLASSIFIER_BACKENDS, this.backend)) {
      throw new Error(
        `classifier: unknown backend "${this.backend}" (want "typesafe", "openrouter", "cloudflare", "vercel" or "custom")`,
      )
    }
    const preset = CLASSIFIER_BACKENDS[this.backend]
    this.preset = preset
    if (this.backend === "custom" && this.style === "systemone" && (!opts.baseUrl || !opts.supports)) {
      throw new Error('classifier: backend "custom" requires baseUrl and supports')
    }
    this.baseUrl = opts.baseUrl || preset.baseUrl
    this.model = opts.model ?? preset.model
    this.apiKeyEnv = opts.apiKeyEnv ?? preset.apiKeyEnv
    const mismatch = backendMismatch(this.baseUrl, this.model)
    if (mismatch) throw new Error(mismatch)
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_CLASSIFIER_TIMEOUT_MS
    this.supports = opts.supports ? { ...opts.supports } : (preset.supports?.(this.model) ?? { images: false })
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
  async evaluate(state: unknown, questions: Record<string, AnyQuestion>, signal?: AbortSignal): Promise<Decision> {
    return this.run(state, questions, signal, false)
  }

  /**
   * Binds one state (a VALUE, not a session: nothing is sent until `evaluate`). Its `evaluate`
   * runs the same path as `evaluate` PLUS fail-closed validation (`validateDecision`).
   */
  state(value: unknown, opts: { signal?: AbortSignal } = {}): { evaluate(...questions: NamedClassifierQuestion[]): Promise<Decision> } {
    return {
      evaluate: async (...qs: NamedClassifierQuestion[]) => this.run(value, namedQuestions(qs), opts.signal, true),
    }
  }

  /**
   * Binds a batch of states. Its `evaluate` runs `state(v).evaluate` per state, at most 16 in
   * flight, and returns one `{decision | error}` per state IN ORDER. One state's failure never
   * fails the batch; only a question-set error (duplicate name) throws, before any request.
   */
  states(...values: unknown[]): { evaluate(...questions: NamedClassifierQuestion[]): Promise<BatchResult[]> } {
    return {
      evaluate: async (...qs: NamedClassifierQuestion[]) => {
        const questions = namedQuestions(qs)
        const out: BatchResult[] = new Array(values.length)
        let next = 0
        const worker = async () => {
          while (next < values.length) {
            const i = next++
            try {
              out[i] = { decision: await this.run(values[i], questions, undefined, true) }
            } catch (e) {
              out[i] = { error: e instanceof Error ? e : new Error(String(e)) }
            }
          }
        }
        await Promise.all(Array.from({ length: Math.min(DEFAULT_BATCH_CONCURRENCY, values.length) }, worker))
        return out
      },
    }
  }

  /** The one path. `validate` = fail closed on `validateDecision` (the value builders). */
  private async run(
    state: unknown,
    questions: Record<string, AnyQuestion>,
    signal: AbortSignal | undefined,
    validate: boolean,
  ): Promise<Decision> {
    const start = Date.now()
    const keys = Object.keys(questions ?? {}).sort()
    // L1: count + hash of the canonical request on every evaluate event ("" when unencodable).
    let qHash = ""
    try {
      qHash = sha256Hex(canonicalRequest(this.model, questions ?? {}))
    } catch {
      qHash = ""
    }
    const l1 = { questions: keys.length, questionsHash: qHash }
    if (keys.length === 0) {
      const err = new Error("classifier: no questions to evaluate")
      this.emitError(start, err, l1)
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
      this.emitError(start, e, l1)
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
          decision = await this.evaluateWire(state, questions, signal, validate)
      }
      if (validate && this.style !== "systemone") {
        const verr = validateDecision(questions, decision)
        if (verr) throw verr
      }
    } catch (e) {
      this.emitError(start, e, l1)
      throw e
    }
    this.emit({
      event: "classifier.evaluate",
      model: decision.model,
      status: "ok",
      ms: Date.now() - start,
      promptTokens: decision.usage.inputTokens,
      completionTokens: decision.usage.outputTokens,
      ...l1,
      cached: decision.cached === true,
    })
    return decision
  }

  /**
   * The same questions over many states (SPEC §8B "Batch"). Each state goes through
   * `evaluate`, at most `concurrency` (default 16) in flight; decisions come back in STATE
   * order. Fails closed: the first failing state's index is named and no decisions return.
   * No states is an error and sends nothing.
   */
  async evaluateBatch(
    states: readonly unknown[],
    questions: Record<string, AnyQuestion>,
    opts: { signal?: AbortSignal; concurrency?: number } = {},
  ): Promise<Decision[]> {
    if (!states || states.length === 0) throw new Error("classifier: no states to evaluate")
    const limit = Math.max(1, opts.concurrency ?? DEFAULT_BATCH_CONCURRENCY)
    const out: Decision[] = new Array(states.length)
    const errs: unknown[] = new Array(states.length)
    let next = 0
    const worker = async () => {
      while (next < states.length) {
        const i = next++
        try {
          out[i] = await this.evaluate(states[i], questions, opts.signal)
        } catch (e) {
          errs[i] = e
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(limit, states.length) }, worker))
    for (let i = 0; i < states.length; i++) {
      if (i in errs) throw new Error(`classifier: state ${i}: ${errText(errs[i])}`, { cause: errs[i] })
    }
    return out
  }

  // ---------------------------------------------------------------- degenerate

  /**
   * Emits ONE warning per degenerate question key per classifier, naming the key, and changes
   * nothing about the request. Repairing would invent option descriptions the caller did not
   * write, and the library has no way to know what the options mean.
   */
  private reportDegenerate(keys: string[], questions: Record<string, AnyQuestion>): void {
    for (const key of keys) {
      const q = questions[key] as Question
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
   * Assembles the request for the backend's wire: the canonical model + questions, plus the state
   * (VERBATIM unless it carried content parts) and, on systemone, `assets`; then the §8 Gap 1
   * pipeline in §8 order (base → requestParams merge → bodyTransform → marshal).
   */
  private body(p: PreparedRequest, questions: Record<string, AnyQuestion>): string {
    const qs: Record<string, unknown> = {}
    for (const [key, q] of Object.entries(questions)) qs[key] = questionWire(q)
    let body: Record<string, any>
    if (this.preset.wire === "cloudflare") {
      const input: Record<string, unknown> = { questions: qs, state: p.state }
      if (p.images.length > 0) input.images = p.images.map((im) => `data:image/${im.format};base64,${im.data}`)
      body = { model: this.model, input }
    } else {
      body = { model: this.model, questions: qs, state: p.state }
      if (p.assets) body.assets = p.assets
    }
    // A requestParams key WINS on collision.
    if (this.opts.requestParams) body = { ...body, ...this.opts.requestParams }
    if (this.opts.bodyTransform) body = this.opts.bodyTransform(body) || body
    return canonicalJson(body)
  }

  /** Identifies a recorded decision by the canonical request AND the state. */
  private staticKey(state: unknown, questions: Record<string, AnyQuestion>): string {
    return canonicalRequest(this.model, questions) + "\u0000" + canonicalJson(state)
  }

  private evaluateStatic(state: unknown, questions: Record<string, AnyQuestion>): Decision {
    const raw = this.corpus.get(this.staticKey(state, questions))
    if (raw === undefined) throw new Error("classifier: static: no recorded decision for this request+state")
    return decodeDecision(raw, this.model)
  }

  /** Lift images, apply the support table and the limits — all before any request. */
  private prepare(state: unknown): PreparedRequest {
    const { state: sent, images } = liftImages(state)
    if (images.length > 0 && !this.supports.images) throw new ClassifierUnsupported(this.backend, this.model, "images")
    checkImages(images)
    let assets: Record<string, unknown> | undefined
    if (images.length > 0) {
      assets = {}
      for (const im of images) assets[im.ref] = { type: "image", format: im.format, data_base64: im.data }
    }
    return { state: sent, images, assets }
  }

  /** The systemone style on every backend: lift, check, cache, post, decode. */
  private async evaluateWire(
    state: unknown,
    questions: Record<string, AnyQuestion>,
    signal: AbortSignal | undefined,
    validate: boolean,
  ): Promise<Decision> {
    const p = this.prepare(state)
    const cache = this.opts.cache
    let modelKey = ""
    if (cache) {
      modelKey = classifierModelCacheKey(this.baseUrl, this.model)
      const served = await cache.get(modelKey)
      if (typeof served === "string") {
        const hit = await cache.get(classifierDecisionCacheKey(this.baseUrl, served, p.state, p.assets, questions))
        if (typeof hit === "string") {
          try {
            const d = decodeDecision(JSON.parse(hit), served)
            if (!validateDecision(questions, d)) return new Decision(d.model, d.answers, d.usage, d.calibrated, true)
          } catch {
            // a corrupt entry is a miss
          }
        }
      }
    }
    const extra: Record<string, string> =
      p.images.length > 0 && this.preset.imagesHeader ? { [CLASSIFIER_IMAGES_HEADER]: "true" } : {}
    let text = await this.post(this.body(p, questions), signal, extra)
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch (e) {
      throw new Error(`classifier: response was not JSON: ${(e as Error).message}`)
    }
    if (this.preset.wire === "cloudflare") {
      parsed = unwrapCloudflare(parsed)
      text = canonicalJson(parsed)
    }
    const d = decodeDecision(parsed, this.model)
    const verr = validateDecision(questions, d)
    if (validate && verr) throw verr
    if (cache && !verr) {
      const served = d.model || this.model
      await cache.set(modelKey, served)
      await cache.set(classifierDecisionCacheKey(this.baseUrl, served, p.state, p.assets, questions), text)
    }
    return d
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
  private async post(payload: string, signal?: AbortSignal, extraHeaders: Record<string, string> = {}): Promise<string> {
    // `endpoint` is what errors name: the CONFIGURED base, ${ENV} unexpanded, so an account id in
    // a templated URL never reaches an error. `url` is what is dialled, expanded at call time.
    let endpoint = this.baseUrl.replace(/\/+$/, "")
    if (this.preset.wire !== "cloudflare") endpoint += "/systemone"
    const url = endpoint.includes("${") ? expandEnv(endpoint) : endpoint
    const retries = this.opts.retries ?? 2
    const base = this.opts.retryBaseMs ?? 500
    const classify = this.opts.onError ?? ((info: ErrorInfo): ErrorTier => (info.retryable ? "retry" : "fail"))
    const doFetch = this.opts.fetch ?? fetch
    let lastErr: unknown
    for (let attempt = 0; ; attempt++) {
      const timer = new AbortController()
      // NOT unref'd: this watchdog is the only thing that will end a request an
      // injected `fetch` never settles, and an unref'd timer cannot keep the
      // loop alive long enough to fire. It is cleared on every exit path below.
      const t = setTimeout(() => timer.abort(new Error(`classifier: request timeout after ${this.timeoutMs}ms`)), this.timeoutMs)
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
        res = await doFetch(url, {
          method: "POST",
          headers: this.requestHeaders(extraHeaders),
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
        lastErr = new Error(`classifier: POST ${endpoint}: ${errText(thrown).split(url).join(endpoint)}`)
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
  private requestHeaders(extra: Record<string, string> = {}): Record<string, string> {
    const headers: Record<string, string> = { "Content-Type": "application/json" }
    const key = process.env[this.apiKeyEnv]
    if (key) headers["Authorization"] = `Bearer ${key}`
    // The image opt-in header goes BEFORE host headers, which win.
    for (const [k, v] of Object.entries(extra)) headers[k] = v
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
    questions: Record<string, AnyQuestion>,
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

  private emitError(start: number, e: unknown, l1: { questions: number; questionsHash: string }): void {
    this.emit({
      event: "classifier.evaluate",
      model: this.model,
      status: "error",
      ms: Date.now() - start,
      promptTokens: 0,
      completionTokens: 0,
      error: errText(e),
      ...l1,
      cached: false,
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


// ---------------------------------------------------------------- backends, images, cache, validation
// SPEC.md §8B "Backends, images, cache and validation" (change add-classifier-backends, ADR 0038).
// Every default here is a PROPOSED default, owner to confirm (design.md PD-1..PD-15).

/** Ordered named questions -> the questions map. A duplicate name is an error naming it. */
function namedQuestions(qs: readonly NamedClassifierQuestion[]): Record<string, AnyQuestion> {
  const out: Record<string, AnyQuestion> = {}
  for (const { name, question } of qs) {
    if (Object.prototype.hasOwnProperty.call(out, name)) throw new Error(`duplicate question name ${JSON.stringify(name)}`)
    out[name] = question
  }
  return out
}

function sha256Hex(s: string): string {
  return createHash("sha256").update(s, "utf8").digest("hex")
}

interface LiftedImage {
  ref: string
  /** The MIME type until `checkImages` normalises it to `png` | `jpeg`. */
  format: string
  data: string
}

interface PreparedRequest {
  state: unknown
  images: LiftedImage[]
  assets?: Record<string, unknown>
}

function containsPart(v: unknown): boolean {
  if (Array.isArray(v)) return v.some(containsPart)
  if (v !== null && typeof v === "object") {
    if (isContentPart(v)) return true
    return Object.values(v as Record<string, unknown>).some(containsPart)
  }
  return false
}

/**
 * The §8B lifting walk. A state with no content part is returned VERBATIM (the same value), so a
 * text-only state is byte-identical to before. Otherwise depth-first, object keys in ASCII order,
 * arrays in order: an image part with inline data becomes a reference (its key when unused, else
 * the smallest unused `image<n>`), a text part becomes its text, any other part is an error.
 */
function liftImages(state: unknown): { state: unknown; images: LiftedImage[] } {
  if (!containsPart(state)) return { state, images: [] }
  const used = new Set<string>()
  const images: LiftedImage[] = []
  const nextRef = () => {
    for (let n = 1; ; n++) if (!used.has(`image${n}`)) return `image${n}`
  }
  const walk = (v: unknown, key: string): unknown => {
    if (Array.isArray(v)) return v.map((e) => walk(e, ""))
    if (v === null || typeof v !== "object") return v
    if (isContentPart(v)) {
      const part = v as ContentPart
      if (part.type === "text") return part.text
      if (part.type === "image") {
        if (!part.data) throw new Error("classifier: state: an image part needs inline data (a URL image cannot be sent)")
        const ref = key === "" || used.has(key) ? nextRef() : key
        used.add(ref)
        images.push({ ref, format: part.mimeType, data: part.data })
        return ref
      }
      throw new Error(`classifier: state: ${part.type} parts are not supported`)
    }
    const obj = v as Record<string, unknown>
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(obj).sort()) out[k] = walk(obj[k], k)
    return out
  }
  return { state: walk(state, ""), images }
}

const IMAGE_FORMATS: Record<string, string> = { "image/png": "png", "image/jpeg": "jpeg", "image/jpg": "jpeg" }

/** Format, per-image and per-request limits, in collection order; normalises `format`. */
function checkImages(images: LiftedImage[]): void {
  let total = 0
  for (const im of images) {
    const format = IMAGE_FORMATS[im.format]
    if (!format) throw new Error(`classifier: image "${im.ref}": type "${im.format}" is not supported (PNG or JPEG only)`)
    im.format = format
    if (im.data.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(im.data)) {
      throw new Error(`classifier: image "${im.ref}": data is not base64`)
    }
    const raw = Buffer.from(im.data, "base64")
    if (raw.length > CLASSIFIER_IMAGE_MAX_BYTES) {
      throw new Error(`classifier: image "${im.ref}": ${raw.length} bytes, over 16 MiB`)
    }
    const size = imageSize(format, raw)
    if (!size) throw new Error(`classifier: image "${im.ref}": not a readable ${format.toUpperCase()}`)
    const [w, h] = size
    const ok = (n: number) => n >= CLASSIFIER_IMAGE_MIN_SIDE && n <= CLASSIFIER_IMAGE_MAX_SIDE
    if (!ok(w) || !ok(h)) {
      throw new Error(
        `classifier: image "${im.ref}": ${w}x${h}; each side must be ${CLASSIFIER_IMAGE_MIN_SIDE}..${CLASSIFIER_IMAGE_MAX_SIDE} px`,
      )
    }
    total += raw.length
  }
  if (total > CLASSIFIER_REQUEST_IMAGE_BYTES) {
    throw new Error(`classifier: image "${images[images.length - 1].ref}": request images total ${total} bytes, over 32 MiB`)
  }
}

/** Width/height from a PNG IHDR or the first JPEG SOF marker; undefined when unreadable. */
function imageSize(format: string, b: Uint8Array): [number, number] | undefined {
  const be16 = (i: number) => (b[i] << 8) | b[i + 1]
  const be32 = (i: number) => ((b[i] << 24) >>> 0) + (b[i + 1] << 16) + (b[i + 2] << 8) + b[i + 3]
  if (format === "png") {
    const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
    if (b.length < 24 || sig.some((x, i) => b[i] !== x)) return undefined
    if (String.fromCharCode(b[12], b[13], b[14], b[15]) !== "IHDR") return undefined
    return [be32(16), be32(20)]
  }
  if (b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return undefined
  let i = 2
  while (i + 3 < b.length) {
    if (b[i] !== 0xff) return undefined
    const m = b[i + 1]
    if (m === 0xff) {
      i++
      continue
    }
    if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) {
      if (i + 8 >= b.length) return undefined
      return [be16(i + 7), be16(i + 5)]
    }
    i += 2 + be16(i + 2)
  }
  return undefined
}

/** The System One reply inside a Cloudflare `/ai/run` envelope. Its error list is never echoed. */
function unwrapCloudflare(env: unknown): unknown {
  if (env === null || typeof env !== "object" || Array.isArray(env) || (env as any).success !== true) {
    throw new Error("classifier: cloudflare: envelope not successful")
  }
  let result = (env as any).result
  if (result !== null && typeof result === "object" && !Array.isArray(result) && typeof result.state === "string") {
    if (result.state !== "Completed") throw new Error(`classifier: cloudflare: run state "${result.state}"`)
    result = result.result
  }
  return result
}

/** §8B model key: which model an endpoint + requested model (alias) served last. */
export function classifierModelCacheKey(endpoint: string, model: string): string {
  return sha256Hex(canonicalJson({ endpoint, kind: "model", model }))
}

/** §8B decision key, on the SERVED model. No assets ⇒ the `assets` member is omitted. */
export function classifierDecisionCacheKey(
  endpoint: string,
  servedModel: string,
  state: unknown,
  assets: Record<string, unknown> | undefined,
  questions: Record<string, AnyQuestion>,
): string {
  return sha256Hex(classifierDecisionCacheKeyBytes(endpoint, servedModel, state, assets, questions))
}

/** The canonical JSON a decision key hashes (exported for conformance tests). */
export function classifierDecisionCacheKeyBytes(
  endpoint: string,
  servedModel: string,
  state: unknown,
  assets: Record<string, unknown> | undefined,
  questions: Record<string, AnyQuestion>,
): string {
  const qs: Record<string, unknown> = {}
  for (const [k, q] of Object.entries(questions)) qs[k] = questionWire(q)
  const m: Record<string, unknown> = { endpoint, kind: "decision", model: servedModel, questions: qs, state }
  if (assets && Object.keys(assets).length > 0) m.assets = assets
  return canonicalJson(m)
}

/**
 * In-process TTL cache with a size cap (oldest-inserted out first). `ttlMs` 0 = no expiry; `max`
 * 0 = unbounded. toolnexus persists nothing itself: implement `ClassifierCache` to persist.
 */
export class MemoryCache implements ClassifierCache {
  private readonly m = new Map<string, { v: string; at: number }>()
  constructor(
    readonly ttlMs = 0,
    readonly max = 0,
    private readonly now: () => number = Date.now,
  ) {}
  get(key: string): string | undefined {
    const e = this.m.get(key)
    if (!e) return undefined
    if (this.ttlMs > 0 && this.now() - e.at > this.ttlMs) {
      this.m.delete(key)
      return undefined
    }
    return e.v
  }
  set(key: string, value: string): void {
    this.m.delete(key) // a re-set is a fresh insertion
    this.m.set(key, { v: value, at: this.now() })
    if (this.max > 0) while (this.m.size > this.max) this.m.delete(this.m.keys().next().value as string)
  }
  get size(): number {
    return this.m.size
  }
}

/**
 * V1: fail closed on a reply that does not answer exactly the questions asked, within their
 * schema. First failure wins, in the §8B order. Returns the error, or undefined when valid.
 */
export function validateDecision(
  questions: Record<string, AnyQuestion>,
  decision: { answers: Record<string, unknown> },
): Error | undefined {
  const bad = (msg: string) => new Error(`classifier: invalid decision: ${msg}`)
  const answers = decision.answers ?? {}
  const has = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k)
  const qKeys = Object.keys(questions).sort()
  for (const k of qKeys) if (!has(answers, k)) return bad(`no answer "${k}"`)
  const extra = Object.keys(answers)
    .filter((k) => !has(questions, k))
    .sort()
  if (extra.length > 0) return bad(`unexpected answer "${extra[0]}"`)
  const in01 = (p: unknown) => typeof p === "number" && !Number.isNaN(p) && p >= 0 && p <= 1
  const dist = (k: string, probs: Record<string, number>, offered: (o: string) => boolean): Error | undefined => {
    let sum = 0
    const pk = Object.keys(probs ?? {}).sort()
    for (const o of pk) {
      if (!offered(o)) return bad(`answer "${k}": probability for "${o}", which was not offered`)
      if (!in01(probs[o])) return bad(`answer "${k}": probability ${probs[o]} outside [0,1]`)
      sum += probs[o]
    }
    if (pk.length > 0 && Math.abs(sum - 1) > 0.02) return bad(`answer "${k}": probabilities sum to ${sum.toFixed(4)}`)
    return undefined
  }
  for (const k of qKeys) {
    const raw = questions[k]
    const q = raw as Question
    const a = answers[k] as any
    const qType = raw.type
    const aType = a?.type
    if (aType !== qType) return bad(`answer "${k}" is a ${aType} answer, not ${qType}`)
    if (isRawQuestion(raw)) continue
    if (q.type === "noul") {
      if (!in01(a.noul)) return bad(`answer "${k}": noul ${a.noul} outside [0,1]`)
    } else if (q.type === "choice") {
      const offered = (o: string) => has(q.criteria ?? {}, o)
      if (!offered(a.choice)) return bad(`answer "${k}": choice "${a.choice}" was not offered`)
      const err = dist(k, a.probabilities, offered)
      if (err) return err
      if (!in01(a.confidence)) return bad(`answer "${k}": confidence ${a.confidence} outside [0,1]`)
    } else {
      const n = (q.criteria ?? []).length
      if (typeof a.score !== "number" || Number.isNaN(a.score) || a.score < 0 || a.score > n - 1) {
        return bad(`answer "${k}": score ${a.score} outside [0,${n - 1}]`)
      }
      const offered = (o: string) => Array.from({ length: n }, (_, i) => String(i)).includes(o)
      const err = dist(k, a.probabilities, offered)
      if (err) return err
      if (!in01(a.confidence)) return bad(`answer "${k}": confidence ${a.confidence} outside [0,1]`)
    }
  }
  return undefined
}

/** jevx's INCLUSIVE cut-points (T1). They sit alongside `Bands` (exclusive), which stays the
 * default of `ask` / `gate`. */
export interface Thresholds {
  yes: number
  no: number
  minConfidence: number
}

/** 0.8 / 0.2 / 0.6 (jevx's builtin profile). */
export const DEFAULT_THRESHOLDS: Readonly<Thresholds> = Object.freeze({ yes: 0.8, no: 0.2, minConfidence: 0.6 })

/**
 * The band of one answer under `Thresholds`: noul `p >= yes` ⇒ yes, `p <= no` ⇒ no, else
 * uncertain; choice/score `confidence >= minConfidence` ⇒ yes, else uncertain. No near-uniform
 * demotion (that is `Bands`' rule). A raw answer is uncertain.
 */
export function thresholdBand(answer: DecisionAnswer | RawAnswer, t: Thresholds = DEFAULT_THRESHOLDS): "yes" | "no" | "uncertain" {
  const a = answer as any
  if (a.type === "noul") {
    if (a.noul >= t.yes) return "yes"
    if (a.noul <= t.no) return "no"
  } else if ((a.type === "choice" || a.type === "score") && a.confidence >= t.minConfidence) {
    return "yes"
  }
  return "uncertain"
}

/**
 * Split a question set into chunks of `size`, keys in ASCII order. A helper, not automatic:
 * joint calibration across a split is unproven, so the host decides to split and merges.
 */
export function chunkQuestions<Q extends AnyQuestion>(questions: Record<string, Q>, size: number): Record<string, Q>[] {
  if (!(size >= 1)) throw new Error(`classifier: chunk size must be at least 1, got ${size}`)
  const keys = Object.keys(questions).sort()
  const out: Record<string, Q>[] = []
  for (let i = 0; i < keys.length; i += size) {
    out.push(Object.fromEntries(keys.slice(i, i + size).map((k) => [k, questions[k]])))
  }
  return out
}
