/** Spike types — the Question/Answer contract of ADR 0020, in TS idiom. */

/** A question. `criteria` is three different JSON shapes, so it lives on the
 *  member, not on a shared base: the union discriminates it for free. */
export type Question =
  | { type: "noul"; instructions: string; criteria?: { true: string; false: string } }
  | { type: "choice"; instructions: string; criteria: Record<string, string> }
  | { type: "score"; instructions: string; criteria: string[] }

export type Answer =
  | { type: "noul"; noul: number }
  | {
      type: "choice"
      choice: string
      probabilities: Record<string, number>
      confidence: number
    }
  | {
      type: "score"
      score: number
      legend: Record<string, string>
      probabilities: Record<string, number>
      confidence: number
    }

export interface Usage {
  input_tokens: number
  output_tokens: number
  cost?: number
}

export interface Decision {
  model: string
  answers: Record<string, Answer>
  usage: Usage
}

/** Convenience constructors — they exist so a caller never hand-writes `type`. */
export const noul = (instructions: string, criteria?: { true: string; false: string }): Question =>
  criteria ? { type: "noul", instructions, criteria } : { type: "noul", instructions }

export const choice = (instructions: string, criteria: Record<string, string>): Question => ({
  type: "choice",
  instructions,
  criteria,
})

export const score = (instructions: string, criteria: string[]): Question => ({
  type: "score",
  instructions,
  criteria,
})
