// The Jev ranker (with the shuffle control), and the code baseline it fails open
// to. Both are handed the SAME board and the SAME option sentences.
import { Judge, runnerUp, validateChoice } from "../judge.mjs"
import { deck, describeBoard, questions } from "./encode.mjs"
import { bodyLength, foodDistance, legalMoves, room } from "./game.mjs"

const decision = (arm, chosen, extra = {}) => ({ arm, chosen, ...extra })

// ---------------------------------------------------------------- code baseline
//
// Greedy toward the apple, but only through moves that leave at least as much
// room as the snake is long — the standard safe-greedy snake bot. It is the
// published baseline AND Jev's fail-open path, so a degraded run is this.

export function codeRanker(g) {
  const ids = legalMoves(g)
  if (!ids.length) return null
  const safe = ids.filter((d) => room(g, d) >= bodyLength(g))
  const pool = safe.length ? safe : ids
  return pool.slice().sort((a, b) => {
    const fa = foodDistance(g, a) ?? 99, fb = foodDistance(g, b) ?? 99
    return fa - fb || room(g, b) - room(g, a)
  })[0]
}

export const codeArm = {
  name: "code",
  async decide(g) { return decision("code", codeRanker(g), { outcome: "applied" }) },
}

// ---------------------------------------------------------------- jev

export function jevArm({ apiKey, model = "typesafe/jev-1.13", control = null, style = "prose" }) {
  const judge = new Judge({ apiKey, model })
  return {
    name: control ? `jev+${control}` : style === "prose" ? "jev" : `jev/${style}`,
    judge,
    async decide(g, { deadlineMs }) {
      const { ids } = deck(g, style)
      const state = describeBoard(g, style)
      const qs = questions(g, style)
      const t0 = performance.now()
      const ac = new AbortController()
      const timer = setTimeout(() => ac.abort(), deadlineMs)
      try {
        const raw = await judge.ask(state, qs, ac.signal)
        const latency = performance.now() - t0
        const pick = validateChoice(raw.answers?.move, ids) // throws; never repaired
        let probs = pick.probabilities
        let chosen = pick.choice
        if (control === "shuffle") {
          // SHUFFLE CONTROL: keep Jev's numbers, permute which move each belongs
          // to. If play survives this, the code is steering and the judge is not.
          const vals = ids.map((i) => probs[i])
          for (let i = vals.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1))
            ;[vals[i], vals[j]] = [vals[j], vals[i]]
          }
          probs = Object.fromEntries(ids.map((i, k) => [i, vals[k]]))
          chosen = ids.reduce((a, b) => (probs[a] >= probs[b] ? a : b))
        }
        return decision(this.name, chosen, {
          outcome: "applied", latency, probabilities: probs, confidence: pick.confidence,
          runnerUp: runnerUp(probs, chosen),
          nouls: { is_cornered: raw.answers?.is_cornered?.noul ?? null,
                   should_chase: raw.answers?.should_chase?.noul ?? null },
          cost: raw.usage?.cost ?? 0, state,
        })
      } catch (err) {
        const latency = performance.now() - t0
        const late = ac.signal.aborted
        // Fail open to the baseline, and LABEL it on screen and in the record.
        return decision(this.name, codeRanker(g), {
          outcome: late ? "late" : "error", latency,
          error: late ? "deadline" : String(err.message).slice(0, 120), state,
        })
      } finally { clearTimeout(timer) }
    },
  }
}
