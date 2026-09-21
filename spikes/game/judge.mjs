// The Jev client: canonical bytes, one reused connection, and a validation
// contract that NEVER repairs. An invalid distribution means no action, not a
// patched-up action.
const URL_DEFAULT = "https://openrouter.ai/api/v1/systemone"

/** Sort objects recursively; NEVER sort arrays. `score.criteria` is an array
 *  whose order IS the level numbering, so a naive deep sort renumbers the rubric.
 *  (spikes/classifier/reports/00-live-backend.md, F1.) */
export function canonical(v) {
  const norm = (x) =>
    Array.isArray(x) ? x.map(norm)
    : x && typeof x === "object"
      ? Object.fromEntries(Object.keys(x).sort().map((k) => [k, norm(x[k])]))
      : x
  return JSON.stringify(norm(v))
}

// ONE connection for the whole run. Per-call TLS is ~40-70 ms of pure waste and
// the measured cold-vs-warm gap was 384 ms -> 331 ms (FEASIBILITY.md). Node's
// global fetch dispatcher already pools and keeps alive; what matters is that we
// never tear the process down between decisions, and that we warm it before the
// first timed tick (see `warm()` in run.mjs).

export class Judge {
  constructor({ model = "typesafe/jev-1.13", apiKey, url = URL_DEFAULT } = {}) {
    this.model = model
    this.apiKey = apiKey
    this.url = url
    this.calls = 0
    this.cost = 0
    this.inputTokens = 0
  }

  body(state, qs) {
    return canonical({ model: this.model, state, questions: qs })
  }

  async ask(state, qs, signal) {
    const body = this.body(state, qs)
    const res = await fetch(this.url, {
      method: "POST",
      signal,
      headers: { "content-type": "application/json", authorization: `Bearer ${this.apiKey}` },
      body,
    })
    const text = await res.text()
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text.slice(0, 180)}`)
    const raw = JSON.parse(text)
    this.calls++
    this.cost += raw?.usage?.cost ?? 0
    this.inputTokens += raw?.usage?.input_tokens ?? 0
    return raw
  }
}

/** `jev-ultrafast`'s contract, copied term for term. Throws; the caller treats a
 *  throw as "no action executed", never as "something close enough". */
export function validateChoice(answer, ids) {
  const fail = (why) => { throw new Error(`Invalid TypeSafe response; no action executed (${why})`) }
  if (!answer || answer.type !== "choice") fail("not a choice answer")
  const { choice, probabilities: p } = answer
  if (!ids.includes(choice)) fail("choice not in the option set")
  const keys = Object.keys(p ?? {})
  if (keys.length !== ids.length || !ids.every((i) => keys.includes(i))) fail("probability keys != option ids")
  let sum = 0
  for (const k of keys) {
    const v = p[k]
    if (!Number.isFinite(v) || v < 0 || v > 1) fail(`probability ${k} out of [0,1]`)
    sum += v
  }
  if (Math.abs(sum - 1) >= 0.02) fail("probabilities do not sum to 1")
  const max = Math.max(...keys.map((k) => p[k]))
  if (p[choice] < max - 1e-6) fail("choice is not the argmax")
  return answer
}

/** Runner-up, for the dotted ring in the HUD (`jev-tetris`). */
export function runnerUp(p, choice) {
  const rest = Object.entries(p).filter(([k]) => k !== choice).sort((a, b) => b[1] - a[1])
  return rest.length ? rest[0][0] : null
}
