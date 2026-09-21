// Two rankers: Jev, and the pure-code baseline that is also Jev's fail-open path.
// Both are handed the SAME wave and the SAME plan sentences — only the ranker
// changes. (A frontier-LLM arm and a keyword control were cut; see README.)
import { deck, describeWave, questions, timeToCommit } from "./encode.mjs"
import { Judge, runnerUp, validateChoice } from "../judge.mjs"

const decision = (arm, chosen, extra = {}) => ({ arm, chosen, ...extra })

// ---------------------------------------------------------------- arm: code
//
// The published baseline. It is ALSO the fail-open path for every other arm, so
// when an arm degrades you are looking at this, and the HUD says so by name.

export function codeRanker(group, speed) {
  const { ids } = deck(group, speed)
  if (ids.includes("one_leap")) return "one_leap"
  if (group.length === 1) {
    const o = group[0]
    if (o.type === "PTERODACTYL") return o.yPos >= 95 ? "leap" : o.yPos >= 70 ? "crouch" : "hold"
    return "leap"
  }
  return ids.includes("mixed") ? "mixed" : "leap_leap"
}

export const codeArm = {
  name: "code",
  async decide(group, speed) {
    return decision("code", codeRanker(group, speed), { outcome: "applied" })
  },
}

// ---------------------------------------------------------------- arm: jev

export function jevArm({ apiKey, model = "typesafe/jev-1.13", control = null }) {
  const judge = new Judge({ apiKey, model })
  return {
    name: control ? `jev+${control}` : "jev",
    judge,
    async decide(group, speed, { deadlineMs }) {
      const { ids } = deck(group, speed)
      const state = describeWave(group, speed)
      const qs = questions(group, speed)
      const t0 = performance.now()
      const ac = new AbortController()
      const timer = setTimeout(() => ac.abort(), Math.max(150, deadlineMs))
      try {
        const raw = await judge.ask(state, qs, ac.signal)
        const latency = performance.now() - t0
        const pick = validateChoice(raw.answers?.plan, ids) // throws; never repaired
        let probs = pick.probabilities
        let chosen = pick.choice
        if (control === "shuffle") {
          // SHUFFLE CONTROL: keep Jev's numbers, permute which plan each belongs
          // to. If play survives this, the code is steering and the judge isn't.
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
          nouls: { is_urgent: raw.answers?.is_urgent?.noul ?? null,
                   is_crowded: raw.answers?.is_crowded?.noul ?? null },
          cost: raw.usage?.cost ?? 0, state,
        })
      } catch (err) {
        const latency = performance.now() - t0
        const late = ac.signal.aborted
        // Fail open to the code ranker, and LABEL it. An omission that stops being
        // mentioned is indistinguishable from something that worked.
        return decision(this.name, codeRanker(group, speed), {
          outcome: late ? "late" : "error", latency,
          error: late ? "deadline" : String(err.message).slice(0, 120), state,
        })
      } finally { clearTimeout(timer) }
    },
  }
}

/** The budget a decision must beat: the last moment the FIRST step still lands. */
export const deadlineFor = (group, speed) => timeToCommit(group[0], speed)
