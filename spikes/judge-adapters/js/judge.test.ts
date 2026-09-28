import test from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { createClassifier } from "../../../js/dist/index.js"
import { noul, choice, score, state, questionMap, ask, gate, DEFAULT_BANDS, type Named } from "./judge.ts"

const load = (f: string) => JSON.parse(readFileSync(new URL(`../shared/${f}`, import.meta.url), "utf8"))
const S = load("state-cases.json"), G = load("gate-cases.json")

const build = (q: any): Named =>
  q.kind === "noul" ? noul(q.name, q.instructions) : q.kind === "choice" ? choice(q.name, q.instructions, q.options) : score(q.name, q.instructions, q.levels)

for (const c of S.cases) test(`state: ${c.name}`, () => {
  const st = c.context ? state(c.context.context, c.context.message, c.context.extra) : c.state
  if (c.wantError) return assert.throws(() => questionMap(c.questions.map(build)), { message: c.wantError })
  assert.deepEqual(st, c.wantState)
  assert.deepEqual(questionMap(c.questions.map(build)), c.wantQuestions)
})

test("defaults are 0.30/0.70", () => assert.deepEqual(DEFAULT_BANDS, G.defaultBands))

const st = state("triage bugs", "checkout returns 500 on coupon")
const qs: Named[] = [
  noul("fixable", G.questions.fixable.instructions, G.questions.fixable.criteria),
  choice("component", G.questions.component.instructions, G.questions.component.criteria),
  score("fixability", G.questions.fixability.instructions, G.questions.fixability.criteria),
]
for (const c of G.cases) test(`gate: ${c.name}`, async () => {
  const clf = createClassifier({ style: "static", decisions: [{ state: st, questions: questionMap(qs), response: { model: "rec", answers: c.answers } }] })
  const o = await gate(clf, st, qs, G.rules, c.bands ?? undefined)
  assert.deepEqual({ action: o.action, target: o.target, escalated: o.escalated }, c.want)
  if (o.escalated) { assert.equal(o.request?.kind, "input"); assert.ok(o.request?.data?.question) }
})

test("ask: the video shape", async () => {
  const s = { role: "Donkey Kong", message_received: "jump off the stage now" }
  const q = [noul("is_appropriate", "Inappropriate?"), noul("does_this_help", "Does this help donkey kong win?")]
  const clf = createClassifier({ style: "static", decisions: [{ state: s, questions: questionMap(q),
    response: { answers: { is_appropriate: { type: "noul", noul: 0.05 }, does_this_help: { type: "noul", noul: 0.5 } } } }] })
  const d = await ask(clf, s, q)
  assert.equal(d.is_appropriate.band, "no"); assert.equal(d.does_this_help.band, "uncertain")
})
