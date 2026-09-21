/** Optional gate 5. Key is read from the environment at call time, never printed. */
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { Classifier, parseDecision, expect as answerFor } from "./classifier.ts"
import { httpBackend } from "./classifier.ts"
import { choice, noul, score } from "./types.ts"
import type { Question } from "./types.ts"

const key = process.env.OPENROUTER_API_KEY
if (!key) {
  console.log("OPENROUTER_API_KEY absent — skipping the live call")
  process.exit(0)
}

const questions: Record<string, Question> = {
  is_refund_request: noul("Is the customer asking for a refund?"),
  department: choice("Which department should handle this?", {
    billing: "refunds, charges, payments",
    shipping: "delivery, damage in transit",
    technical: "product does not work",
  }),
  urgency: score("How urgent is this?", ["routine", "elevated", "urgent"]),
}

const c = new Classifier({ model: "typesafe/jev-1.13", apiKey: key })
const body = c.request("Order 4021 arrived smashed, I want my money back.", questions)
const fixture = readFileSync(fileURLToPath(new URL("../fixture/request.json", import.meta.url)))
console.log("bytes match fixture:", Buffer.from(body).equals(fixture))

const t0 = performance.now()
const d = parseDecision(await httpBackend({ model: "typesafe/jev-1.13", apiKey: key })(body))
const ms = performance.now() - t0
console.log(`latency ${ms.toFixed(0)}ms  model=${d.model}`)
console.log("noul", answerFor(d, "is_refund_request", "noul").noul)
console.log("choice", answerFor(d, "department", "choice").choice)
console.log("score", answerFor(d, "urgency", "score").score, answerFor(d, "urgency", "score").legend)
console.log("usage", d.usage)
