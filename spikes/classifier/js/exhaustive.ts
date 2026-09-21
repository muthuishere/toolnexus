import type { Answer, Question } from "./types.ts"

const never = (x: never): never => {
  throw new Error(`unreachable: ${JSON.stringify(x)}`)
}

/** Compile-time proof that `type` exhausts BOTH unions, and that each branch sees
 *  only its own `criteria` shape — no casts, no optional chaining. */
export function describe(q: Question): string {
  switch (q.type) {
    case "noul":
      return q.criteria ? `${q.criteria.true} / ${q.criteria.false}` : "true/false"
    case "choice":
      return Object.keys(q.criteria).join("|")
    case "score":
      return q.criteria.map((c, i) => `${i}=${c}`).join(",")
    default:
      return never(q)
  }
}

export function summarise(a: Answer): number {
  switch (a.type) {
    case "noul":
      return a.noul
    case "choice":
      return a.confidence
    case "score":
      return a.score
    default:
      return never(a)
  }
}
