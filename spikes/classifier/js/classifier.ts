import { canonical } from "./canonical.ts"
import type { Answer, Decision, Question, Usage } from "./types.ts"

/** The transport seam (ADR 0019: one seam, injected). Takes the canonical body,
 *  returns the parsed-but-untyped response. */
export type Backend = (body: string) => Promise<unknown>

export interface ClassifierOptions {
  model: string
  backend?: Backend
  baseUrl?: string
  apiKey?: string
  fetch?: typeof fetch
}

export class Classifier {
  readonly opts: ClassifierOptions
  // NOTE: written long-hand — Node's strip-only TS loader rejects parameter properties.
  constructor(opts: ClassifierOptions) {
    this.opts = opts
  }

  /** The exact bytes that go on the wire. Pure — this is gate 1. */
  request(state: unknown, questions: Record<string, Question>): string {
    return canonical({ model: this.opts.model, state, questions })
  }

  async evaluate(state: unknown, questions: Record<string, Question>): Promise<Decision> {
    const backend = this.opts.backend ?? httpBackend(this.opts)
    return parseDecision(await backend(this.request(state, questions)))
  }
}

export function httpBackend(opts: ClassifierOptions): Backend {
  const url = (opts.baseUrl ?? "https://openrouter.ai/api/v1/systemone")
  const doFetch = opts.fetch ?? fetch
  return async (body) => {
    const headers: Record<string, string> = { "content-type": "application/json" }
    if (opts.apiKey) headers.authorization = `Bearer ${opts.apiKey}`
    const res = await doFetch(url, { method: "POST", headers, body })
    const text = await res.text()
    if (!res.ok) throw new Error(`classifier HTTP ${res.status}: ${text.slice(0, 200)}`)
    return JSON.parse(text)
  }
}

// ---------------------------------------------------------------- parsing

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v)

function num(v: unknown, where: string): number {
  if (typeof v !== "number" || !Number.isFinite(v)) throw new Error(`${where}: expected number`)
  return v
}

function str(v: unknown, where: string): string {
  if (typeof v !== "string") throw new Error(`${where}: expected string`)
  return v
}

function numMap(v: unknown, where: string): Record<string, number> {
  if (!isRecord(v)) throw new Error(`${where}: expected object`)
  const out: Record<string, number> = {}
  for (const k of Object.keys(v)) out[k] = num(v[k], `${where}.${k}`)
  return out
}

function strMap(v: unknown, where: string): Record<string, string> {
  if (!isRecord(v)) throw new Error(`${where}: expected object`)
  const out: Record<string, string> = {}
  for (const k of Object.keys(v)) out[k] = str(v[k], `${where}.${k}`)
  return out
}

/** unknown -> Answer with no casts: every branch CONSTRUCTS a literal that the
 *  union already accepts, so the discriminant is checked by the compiler rather
 *  than asserted by us. */
export function parseAnswer(raw: unknown, where: string): Answer {
  if (!isRecord(raw)) throw new Error(`${where}: expected object`)
  const type = str(raw.type, `${where}.type`)
  switch (type) {
    case "noul":
      return { type: "noul", noul: num(raw.noul, `${where}.noul`) }
    case "choice":
      return {
        type: "choice",
        choice: str(raw.choice, `${where}.choice`),
        probabilities: numMap(raw.probabilities, `${where}.probabilities`),
        confidence: num(raw.confidence, `${where}.confidence`),
      }
    case "score":
      return {
        type: "score",
        score: num(raw.score, `${where}.score`),
        legend: strMap(raw.legend, `${where}.legend`),
        probabilities: numMap(raw.probabilities, `${where}.probabilities`),
        confidence: num(raw.confidence, `${where}.confidence`),
      }
    default:
      throw new Error(`${where}: unknown answer type ${JSON.stringify(type)}`)
  }
}

export function parseDecision(raw: unknown): Decision {
  if (!isRecord(raw)) throw new Error("decision: expected object")
  const answersRaw = raw.answers
  if (!isRecord(answersRaw)) throw new Error("decision.answers: expected object")
  const answers: Record<string, Answer> = {}
  for (const k of Object.keys(answersRaw)) answers[k] = parseAnswer(answersRaw[k], `answers.${k}`)
  const u = isRecord(raw.usage) ? raw.usage : {}
  const usage: Usage = {
    input_tokens: num(u.input_tokens ?? 0, "usage.input_tokens"),
    output_tokens: num(u.output_tokens ?? 0, "usage.output_tokens"),
  }
  if (typeof u.cost === "number") usage.cost = u.cost
  return { model: str(raw.model, "decision.model"), answers, usage }
}

/** Narrowing helpers so a caller reads one answer without a cast and without
 *  losing the "wrong type" case. */
export function expect<T extends Answer["type"]>(
  d: Decision,
  key: string,
  type: T,
): Extract<Answer, { type: T }> {
  const a = d.answers[key]
  if (!a) throw new Error(`no answer for ${key}`)
  if (a.type !== type) throw new Error(`${key}: expected ${type}, got ${a.type}`)
  // THE ONE CAST IN THE SPIKE. TS narrows a discriminated union against a literal
  // ("noul"), but not against a generic type parameter T, so `a` is still `Answer`
  // here (TS2322). Non-generic accessors (asNoul/asChoice/asScore) need no cast at
  // all — the generic ergonomics cost exactly this one assertion.
  return a as Extract<Answer, { type: T }>
}
