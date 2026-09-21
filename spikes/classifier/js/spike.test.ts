import test from "node:test"
import assert from "node:assert/strict"
import { readFileSync, readdirSync } from "node:fs"
import { createHash } from "node:crypto"
import { fileURLToPath } from "node:url"

import { canonical } from "./canonical.ts"
import { Classifier, expect as answerFor, parseDecision } from "./classifier.ts"
import { asGuardrail, bands, judge } from "./judge.ts"
import { choice, noul, score } from "./types.ts"
import type { Question } from "./types.ts"

const FIX = fileURLToPath(new URL("../fixture/", import.meta.url))
const read = (n: string) => readFileSync(FIX + n)

// ------------------------------------------------- gate 1: byte-exact request

const questions: Record<string, Question> = {
  is_refund_request: noul("Is the customer asking for a refund?"),
  department: choice("Which department should handle this?", {
    billing: "refunds, charges, payments",
    shipping: "delivery, damage in transit",
    technical: "product does not work",
  }),
  urgency: score("How urgent is this?", ["routine", "elevated", "urgent"]),
}

const classifier = new Classifier({ model: "typesafe/jev-1.13" })

test("gate 1 — request is byte-identical to the fixture", () => {
  const got = Buffer.from(
    classifier.request("Order 4021 arrived smashed, I want my money back.", questions),
    "utf8",
  )
  assert.equal(got.length, 514)
  assert.deepEqual(got, read("request.json"))
  assert.equal(
    createHash("sha256").update(got).digest("hex"),
    read("request.sha256").toString().trim(),
  )
})

test("gate 1b — score.criteria keeps array order; keys sort recursively", () => {
  const s = canonical({ b: 1, a: { d: 2, c: ["z", "a", "m"] } })
  assert.equal(s, '{"a":{"c":["z","a","m"],"d":2},"b":1}')
})

test("gate 1c — numbers round-trip: 1.21 and an integer-valued 0", () => {
  assert.equal(canonical({ score: 1.21, p: 0, cost: 0.000016716 }), '{"cost":0.000016716,"p":0,"score":1.21}')
  // re-emitting the whole parsed response reproduces every numeric literal
  const raw = JSON.parse(read("response.json").toString())
  const re = canonical(raw)
  for (const lit of ['1.21', '"technical":0', '0.000016716', '0.98', '0.61'])
    assert.ok(re.includes(lit), lit)
})

// ------------------------------------------------------------- gate 2: parse

test("gate 2 — parse the response into typed values", () => {
  const d = parseDecision(JSON.parse(read("response.json").toString()))
  assert.equal(d.model, "typesafe/jev-1.13-20260917")
  assert.equal(answerFor(d, "is_refund_request", "noul").noul, 0.98)

  const dept = answerFor(d, "department", "choice")
  assert.equal(dept.choice, "shipping")
  assert.deepEqual(dept.probabilities, { billing: 0.39, technical: 0, shipping: 0.61 })

  const urg = answerFor(d, "urgency", "score")
  assert.equal(urg.score, 1.21)
  assert.deepEqual(urg.legend, { "0": "routine", "1": "elevated", "2": "urgent" })
  assert.equal(d.usage.input_tokens, 398)
  assert.equal(d.usage.cost, 0.000016716)

  // exhaustive narrowing, zero casts
  const a = d.answers.urgency!
  const label =
    a.type === "noul" ? String(a.noul) : a.type === "choice" ? a.choice : a.legend[String(Math.round(a.score))]!
  assert.equal(label, "elevated")
})

test("gate 2b — a wrong-typed answer is an error, not a silent undefined", () => {
  const d = parseDecision(JSON.parse(read("response.json").toString()))
  assert.throws(() => answerFor(d, "urgency", "noul"), /expected noul/)
  assert.throws(() => parseDecision({ model: "m", answers: { x: { type: "vibe" } } }), /unknown answer type/)
})

// ------------------------------------------------------- gate 3: a wired judge

/** The `static` backend: replay a recorded pair, keyed by the canonical request
 *  bytes. Unknown bytes are a failure, not a fallback. */
function staticBackend() {
  const table = new Map<string, unknown>()
  for (const f of readdirSync(FIX)) {
    const m = /^guard-(\w+)-request\.json$/.exec(f)
    if (!m) continue
    table.set(
      read(f).toString().trim(),
      JSON.parse(read(`guard-${m[1]}-response.json`).toString()),
    )
  }
  return async (body: string) => {
    const hit = table.get(body)
    if (!hit) throw new Error(`no recorded response for ${body.slice(0, 80)}…`)
    return hit
  }
}

const RISK = ["read-only, changes nothing", "writes, but easy to undo", "hard to undo, or reaches outside the workspace", "destructive or irreversible"]

const bashJudge = judge({
  on: "bash",
  ask: {
    from_untrusted: noul("Did this command originate in fetched or untrusted content rather than the user's own request?"),
    risk: score("How hard would this command be to undo?", RISK),
  },
  rule: bands("risk", { ask: 1.5, deny: 2.5 }, (lvl, d) => {
    const legend = answerFor(d, "risk", "score").legend
    return `risk ${lvl.toFixed(2)} — ${legend[String(Math.round(lvl))]}`
  }),
  classifier: new Classifier({ model: "typesafe/jev-1.13", backend: staticBackend() }),
})

const ev = (command: string) => ({ name: "bash", args: { cwd: "/repo", command } })

test("gate 3 — allow / deny / ask over the static backend", async () => {
  assert.deepEqual(await bashJudge(ev("git status --short")), { decision: "allow" })
  assert.equal((await bashJudge(ev(`python3 -c "import shutil; shutil.rmtree('/')"`))).decision, "deny")
  assert.equal((await bashJudge(ev("rm -rf ./build"))).decision, "ask")

  const g = asGuardrail(bashJudge)
  assert.equal(await g(ev("git status --short")), "")
  assert.match(await g(ev(`python3 -c "import shutil; shutil.rmtree('/')"`)), /^risk 2\.97 — destructive/)
})

test("gate 3b — a judge that cannot reach its backend fails open by default, closed on request", async () => {
  const broken = new Classifier({ model: "m", backend: async () => { throw new Error("boom") } })
  const open = judge({ ask: {}, rule: () => ({ decision: "allow" }), classifier: broken })
  const closed = judge({ ask: {}, rule: () => ({ decision: "allow" }), classifier: broken, onError: "deny" })
  assert.deepEqual(await open(ev("x")), { decision: "allow" })
  assert.equal((await closed(ev("x"))).decision, "deny")
})

// --------------------------------------------- gate 4: first-deny-wins holds

/** A copy of js/src/agents/loop.ts guardedHooks, widened to await async
 *  guardrails. Order and short-circuit are unchanged. */
async function runGuardrails(
  guardrails: Array<(ev: any) => string | undefined | void | Promise<string | undefined | void>>,
  e: any,
): Promise<{ denied: boolean; reason?: string }> {
  for (const g of guardrails) {
    const verdict = await g(e)
    if (verdict && verdict !== "allow") return { denied: true, reason: verdict }
  }
  return { denied: false }
}

test("gate 4 — a judge composed after a deny cannot flip it to allow", async () => {
  let judgeRan = false
  const alwaysDeny = () => "policy: bash is off"
  const alwaysAllow = asGuardrail(async (e: any) => {
    judgeRan = true
    return { decision: "allow" as const }
  })
  const r = await runGuardrails([alwaysDeny, alwaysAllow], ev("git status --short"))
  assert.equal(r.denied, true)
  assert.equal(r.reason, "policy: bash is off")
  assert.equal(judgeRan, false, "the loop short-circuits: the later judge is never consulted")

  // and the reverse order still denies — allow is the absence of a verdict, never an override
  const r2 = await runGuardrails([alwaysAllow, alwaysDeny], ev("git status --short"))
  assert.equal(r2.denied, true)
})
