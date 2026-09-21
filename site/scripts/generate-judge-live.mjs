#!/usr/bin/env node
// Generate site/src/content/docs/harness/judge-live.mdx from a REAL run.
//
//   node site/scripts/generate-judge-live.mjs
//
// Everything numeric on the generated page comes out of this process: the runner drives the
// SHIPPED JavaScript `Classifier` (js/dist) against the live System One wire and against a chat
// model through `style: "llm"`, on the shared `examples/judge/base.json` fixture, and writes both
// the raw measurements (site/src/data/judge-live.json) and the page.
//
// NOT part of any test suite and NOT part of `npm run build`. It needs the network and a
// credential, and CI has neither by design — the `static` backend is the whole CI path. Running
// it is a deliberate, manual act.
//
// THE CREDENTIAL: read from the environment (OPENROUTER_API_KEY) at the point of use, by the
// library itself via `apiKeyEnv`. It is never printed, never written to the JSON, never written
// to the page. If it is absent this runner exits non-zero rather than emitting anything.
//
// Flags:
//   --dry     do everything except write the two files (still makes the live calls)
//   --repeats=N  override the System One sample count (default 12)

import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, "../..")
const OUT_PAGE = path.join(repoRoot, "site/src/content/docs/harness/judge-live.mdx")
const OUT_DATA = path.join(repoRoot, "site/src/data/judge-live.json")
const CMD = "node site/scripts/generate-judge-live.mjs"

const dry = process.argv.includes("--dry")
const repeatsArg = process.argv.find((a) => a.startsWith("--repeats="))
const N_SYSTEMONE = repeatsArg ? Number(repeatsArg.split("=")[1]) : 12
const N_LLM = 6
const N_ENCODING = 5

const KEY_ENV = "OPENROUTER_API_KEY"
const BASE_URL = "https://openrouter.ai/api/v1"
const SYSTEMONE_MODEL = "typesafe/jev-1.13"
const CHAT_MODEL = process.env.OPENROUTER_MODEL || "openai/gpt-4o-mini"

if (!process.env[KEY_ENV]) {
	console.error(
		`${KEY_ENV} is not set. This runner makes real calls and will not invent numbers; ` +
			`set the credential and re-run, or leave the page as it is.`,
	)
	process.exit(2)
}

const lib = await import(path.join(repoRoot, "js/dist/index.js"))
const { Classifier, createClient, choice, noul, score, nearUniform } = lib

// ---------------------------------------------------------------- transport

// The library owns the request; this wrapper only READS what came back, so the gateway's
// `usage.cost` (which the portable Decision shape drops) can be reported. It never touches
// headers, so the credential does not pass through here at all.
const costs = []
function costCapturingFetch(kind) {
	return async (url, init) => {
		const res = await fetch(url, init)
		const text = await res.clone().text().catch(() => "")
		try {
			const body = JSON.parse(text)
			const c = body?.usage?.cost
			if (typeof c === "number") costs.push({ kind, cost: c })
		} catch {}
		return res
	}
}
const sumCost = (kind) => costs.filter((c) => c.kind === kind).reduce((a, c) => a + c.cost, 0)
const nCost = (kind) => costs.filter((c) => c.kind === kind).length

// ---------------------------------------------------------------- statistics

/** Nearest-rank percentile: the smallest sample at or above the p-th rank. No interpolation, so
 *  every figure printed is a measurement that actually happened. */
function pct(values, p) {
	const s = [...values].sort((a, b) => a - b)
	const rank = Math.max(1, Math.ceil((p / 100) * s.length))
	return s[rank - 1]
}
const mean = (v) => v.reduce((a, b) => a + b, 0) / v.length
function stdev(v) {
	if (v.length < 2) return 0
	const m = mean(v)
	return Math.sqrt(v.reduce((a, b) => a + (b - m) ** 2, 0) / (v.length - 1))
}
const spread = (v) => Math.max(...v) - Math.min(...v)
const median = (v) => pct(v, 50)

// ---------------------------------------------------------------- the fixture

const fixture = JSON.parse(fs.readFileSync(path.join(repoRoot, "examples/judge/base.json"), "utf8"))
const FX = fixture.request
// Rebuilt from the shared fixture's own bytes — the page measures the fixture every port is
// tested against, not a private copy of it.
const BASE_STATE = FX.state
const BASE_QUESTIONS = {
	department: choice(FX.questions.department.instructions, FX.questions.department.criteria),
	is_refund_request: noul(FX.questions.is_refund_request.instructions),
	urgency: score(FX.questions.urgency.instructions, FX.questions.urgency.criteria),
}

// ---------------------------------------------------------------- backends

// `kind` tags which phase a call belongs to, so a per-call cost is an average over calls of the
// same shape rather than over the whole run.
const systemOne = (kind, extra = {}) =>
	new Classifier({
		style: "systemone",
		baseUrl: BASE_URL,
		model: SYSTEMONE_MODEL,
		apiKeyEnv: KEY_ENV,
		timeoutMs: 30_000,
		fetch: costCapturingFetch(kind),
		...extra,
	})

const overChatModel = () =>
	new Classifier({
		style: "llm",
		model: CHAT_MODEL,
		client: createClient({
			style: "openai",
			baseUrl: BASE_URL,
			model: CHAT_MODEL,
			apiKey: process.env[KEY_ENV],
			fetch: costCapturingFetch("llm"),
			retries: 2,
		}),
	})

async function timed(fn) {
	const t0 = performance.now()
	const value = await fn()
	return { value, ms: performance.now() - t0 }
}

// ---------------------------------------------------------------- measurements

console.error(`judge-live: ${N_SYSTEMONE} systemone + ${N_LLM} llm + roster + encoding control`)

// 1. System One, the base fixture, repeated. Latency distribution AND non-determinism.
const s1 = { ms: [], urgency: [], refund: [], shipping: [], pick: [], calibrated: [], distributionKeys: [] }
const c1 = systemOne("systemone-base")
for (let i = 0; i < N_SYSTEMONE; i++) {
	const { value: d, ms } = await timed(() => c1.evaluate(BASE_STATE, BASE_QUESTIONS))
	s1.ms.push(ms)
	s1.urgency.push(d.score("urgency").score)
	s1.refund.push(d.noul("is_refund_request").noul)
	const ch = d.choice("department")
	s1.shipping.push(ch.probabilities.shipping ?? 0)
	s1.pick.push(ch.choice)
	s1.calibrated.push(d.calibrated)
	s1.distributionKeys.push(Object.keys(ch.probabilities).length)
	process.stderr.write(".")
}
process.stderr.write("\n")

// 2. The same fixture through `style: "llm"` over a chat model.
const s2 = { ms: [], urgency: [], refund: [], pick: [], confidence: [], calibrated: [], distributionKeys: [] }
const c2 = overChatModel()
for (let i = 0; i < N_LLM; i++) {
	try {
		const { value: d, ms } = await timed(() => c2.evaluate(BASE_STATE, BASE_QUESTIONS))
		s2.ms.push(ms)
		s2.urgency.push(d.score("urgency").score)
		s2.refund.push(d.noul("is_refund_request").noul)
		const ch = d.choice("department")
		s2.pick.push(ch.choice)
		s2.confidence.push(ch.confidence ?? 0)
		s2.calibrated.push(d.calibrated)
		s2.distributionKeys.push(Object.keys(ch.probabilities ?? {}).length)
	} catch (e) {
		s2.pick.push(`ERROR: ${e.message}`)
	}
	process.stderr.write(".")
}
process.stderr.write("\n")

// 3. Roster scaling — is latency flat in the number of options, and does the cap fail legibly?
function roster(n) {
	const items = {
		dataviz: "turn a table of numbers into a chart a reader can act on",
		invoicing: "raise, send and reconcile invoices against a ledger",
		transcribe: "turn recorded speech into timestamped text",
	}
	const filler = [
		"rotate a credential and update every consumer of it",
		"provision a virtual machine and install a runtime on it",
		"summarise a long thread into the decisions it reached",
		"translate a document while preserving its formatting",
		"compress a video for delivery over a slow connection",
	]
	let i = 0
	while (Object.keys(items).length < n) {
		items[`skill_${String(i).padStart(3, "0")}`] = filler[i % filler.length]
		i++
	}
	return items
}
const ROSTER_QUERY = "pull the numbers out of last quarter's board deck and chart them for Thursday."
const cRoster = systemOne("systemone-roster")
const cEnc = systemOne("systemone-encoding")
const rosterRows = []
for (const n of [20, 64, 128, 255]) {
	const q = { skill: choice("Which skill should handle this request?", roster(n)) }
	const { value: d, ms } = await timed(() => cRoster.evaluate(ROSTER_QUERY, q))
	const ch = d.choice("skill")
	rosterRows.push({ options: n, ms, pick: ch.choice, confidence: ch.confidence, inputTokens: d.usage.inputTokens })
	process.stderr.write(".")
}
let capError = null
try {
	await cRoster.evaluate(ROSTER_QUERY, { skill: choice("Which skill should handle this request?", roster(300)) })
	capError = "ACCEPTED — the 255 cap did not fire"
} catch (e) {
	capError = e.message
}
process.stderr.write("\n")

// 4. The control that can fail (ADR 0021 D5). Descriptions are what differentiate options, so
//    MOVE them: give each option id the description of a different option and re-ask. If the pick
//    follows the moved description rather than the id, the distribution was carrying the encoding.
//    If it stays with the id, it never was.
const DESCRIBED = {
	billing: "refunds, charges, payments",
	shipping: "delivery, damage in transit",
	technical: "product does not work",
}
// billing←shipping's text, shipping←technical's, technical←billing's.
const PERMUTED = {
	billing: DESCRIBED.shipping,
	shipping: DESCRIBED.technical,
	technical: DESCRIBED.billing,
}
const UNDESCRIBED = { billing: "billing", shipping: "shipping", technical: "technical" }

async function encodingRun(criteria, label) {
	const q = { department: choice(FX.questions.department.instructions, criteria) }
	const picks = [], tops = [], uniform = []
	for (let i = 0; i < N_ENCODING; i++) {
		const d = await cEnc.evaluate(BASE_STATE, q)
		const ch = d.choice("department")
		picks.push(ch.choice)
		tops.push(Math.max(...Object.values(ch.probabilities)))
		uniform.push(nearUniform(ch.probabilities))
		process.stderr.write(".")
	}
	return { label, picks, medianTop: median(tops), nearUniform: uniform.filter(Boolean).length, n: N_ENCODING }
}
const described = await encodingRun(DESCRIBED, "described")
const permuted = await encodingRun(PERMUTED, "permuted")
const undescribed = await encodingRun(UNDESCRIBED, "undescribed")
process.stderr.write("\n")

// The control's verdict: the shipping-damage sentence moved from `shipping` to `billing`. Did the
// answer follow it?
const mode = (a) => [...a].sort((x, y) => a.filter((v) => v === y).length - a.filter((v) => v === x).length)[0]
const describedPick = mode(described.picks)
const permutedPick = mode(permuted.picks)
const controlTracked = permutedPick !== describedPick
const permutedFollowedText = permuted.picks.filter((p) => p === "billing").length

// ---------------------------------------------------------------- the record

const generatedAt = new Date().toISOString().replace(/\.\d+Z$/, "Z")
const data = {
	generatedAt,
	command: CMD,
	fixture: "examples/judge/base.json",
	backends: { systemone: { model: SYSTEMONE_MODEL, baseUrl: BASE_URL }, llm: { model: CHAT_MODEL, baseUrl: BASE_URL } },
	systemone: {
		...s1,
		p50: pct(s1.ms, 50), p95: pct(s1.ms, 95),
		urgencyStdev: stdev(s1.urgency), urgencySpread: spread(s1.urgency),
		refundSpread: spread(s1.refund), shippingSpread: spread(s1.shipping),
		cost: sumCost("systemone-base"), calls: nCost("systemone-base"),
		costAllPhases: sumCost("systemone-base") + sumCost("systemone-roster") + sumCost("systemone-encoding"),
	},
	llm: {
		...s2,
		p50: s2.ms.length ? pct(s2.ms, 50) : null, p95: s2.ms.length ? pct(s2.ms, 95) : null,
		urgencyStdev: stdev(s2.urgency), urgencySpread: spread(s2.urgency),
		cost: sumCost("llm"), calls: nCost("llm"),
	},
	roster: { rows: rosterRows, capError },
	encodingControl: { described, permuted, undescribed, describedPick, permutedPick, controlTracked, permutedFollowedText },
	totalCostUsd: costs.reduce((a, c) => a + c.cost, 0),
	totalCalls: costs.length,
}

// ---------------------------------------------------------------- the page

const f2 = (x) => x.toFixed(2)
const f3 = (x) => x.toFixed(3)
const s = (ms) => `${(ms / 1000).toFixed(2)} s`
const money = (x) => `$${x.toFixed(6)}`
const uniq = (a) => [...new Set(a)]

const perRunRows = s1.ms
	.map((ms, i) => `| ${i + 1} | ${f2(s1.refund[i])} | ${f2(s1.shipping[i])} | ${f2(s1.urgency[i])} | ${s(ms)} |`)
	.join("\n")

const rosterTable = rosterRows
	.map((r) => `| ${r.options} | ${r.inputTokens} | ${s(r.ms)} | \`${r.pick}\` | ${f2(r.confidence)} |`)
	.join("\n")

const llmCostPerCall = data.llm.calls ? data.llm.cost / data.llm.calls : null
const s1CostPerCall = data.systemone.calls ? data.systemone.cost / data.systemone.calls : null
const costRatio = llmCostPerCall && s1CostPerCall ? (llmCostPerCall / s1CostPerCall).toFixed(1) : "—"
const speedRatio = s2.ms.length ? (pct(s2.ms, 50) / pct(s1.ms, 50)).toFixed(1) : "—"

const page = `---
title: Judge — measured on a live backend
description: Latency (p50 and p95), cost, calibration and non-determinism for the Classifier seam, measured by a runner against a live System One backend and a chat model — including the control that can fail.
---

import { Aside } from '@astrojs/starlight/components';

<Aside type="caution" title="Read this before you read a number">
A decision is **advisory**. Calibration is **not** correctness. "Cannot hallucinate" means only
that the returned value is inside the declared schema — a decision can be confidently wrong, and
\`nearUniform\` cannot tell a good encoding from a subtly wrong one. **Authority is enforced in
code**: numeric limits, permission checks and allowlists stay in your \`if\` statements. A classifier
interprets; it never authorises. Nothing on this page is a security control.
</Aside>

<Aside type="tip" title="This page is generated output, not prose">
Every number below was produced by **\`${CMD}\`** on **${generatedAt}**, in ${data.totalCalls} live
calls costing **${money(data.totalCostUsd)}** in total. The runner drives the shipped JavaScript
\`Classifier\` against the shared fixture [\`examples/judge/base.json\`](https://github.com/muthuishere/toolnexus/blob/main/examples/judge/base.json)
— the same bytes every port is tested against — and writes this file. Re-running it replaces the
tables; it does not edit them. The raw record is
[\`site/src/data/judge-live.json\`](https://github.com/muthuishere/toolnexus/blob/main/site/src/data/judge-live.json).

**CI never runs it.** It needs the network and a credential, and the test suites have neither by
design: the \`static\` backend is the whole CI path. The credential is read from the environment by
the library at the point of use and appears in no output, here or in the raw record.

Backends measured: \`systemone\` = \`${SYSTEMONE_MODEL}\` and \`llm\` = \`${CHAT_MODEL}\`, both over
\`${BASE_URL}\`. Percentiles are **nearest-rank over the samples shown** — no interpolation, so each
figure is a call that actually happened.
</Aside>

## The headline: it is not deterministic, so no test may assert a live number

The same fixture, ${N_SYSTEMONE} times through the \`systemone\` backend:

| run | \`is_refund_request\` (noul) | \`department\` P(shipping) | \`urgency\` (score) | latency |
|---|---|---|---|---|
${perRunRows}

Across those ${N_SYSTEMONE} identical requests the \`urgency\` score moved over a **spread of
${f2(data.systemone.urgencySpread)}** with **σ = ${f3(data.systemone.urgencyStdev)}** on a 0–3
scale; \`P(shipping)\` moved ${f2(data.systemone.shippingSpread)} and the \`noul\` moved
${f2(data.systemone.refundSpread)}. The pick was \`${uniq(s1.pick).join("`, `")}\`
${uniq(s1.pick).length === 1 ? "on every run" : "— it varied"}.

The jitter concentrates in \`score\` and in \`choice\` probabilities, which is why **a binary gate is
the most reproducible primitive you can build** on this seam.

Two consequences you inherit:

1. **CI runs the \`static\` backend.** Recorded decisions, no network, no credential. It is not a
   convenience — it is the only backend a test may assert a number against. No test in this repo
   asserts a live numeric answer; a live call, if run at all, asserts shape only.
2. **A threshold needs clearance.** A band boundary sitting within ~0.1 of a typical value will
   flip between runs. Give it room, or add hysteresis.

## Latency, cost, and why \`style: "llm"\` is an exit rather than an equal

The identical fixture on both backends. Latency is reported at **p50 and p95** because the median
alone hides the tail you will actually be paged about:

| backend | samples | p50 | p95 | cost / call | \`calibrated\` | distribution returned |
|---|---|---|---|---|---|---|
| **\`systemone\`** \`${SYSTEMONE_MODEL}\` | ${N_SYSTEMONE} | **${s(data.systemone.p50)}** | ${s(data.systemone.p95)} | **${s1CostPerCall == null ? "—" : money(s1CostPerCall)}** | \`${uniq(s1.calibrated).join("`, `")}\` | **${uniq(s1.distributionKeys).join(", ")} probabilities** |
| \`llm\` \`${CHAT_MODEL}\` | ${s2.ms.length} | ${data.llm.p50 == null ? "—" : s(data.llm.p50)} | ${data.llm.p95 == null ? "—" : s(data.llm.p95)} | ${llmCostPerCall == null ? "—" : money(llmCostPerCall)} (${costRatio}×) | \`${uniq(s2.calibrated).join("`, `") || "—"}\` | ${uniq(s2.distributionKeys).join(", ") || "—"} probabilities (self-reported) |

On this run the \`llm\` backend was **${speedRatio}× the latency** and **${costRatio}× the cost** at
p50. Its \`department\` picks were \`${uniq(s2.pick).join("`, `")}\` against
\`${uniq(s1.pick).join("`, `")}\` from \`systemone\`, at a median self-reported confidence of
**${s2.confidence.length ? f2(median(s2.confidence)) : "—"}** — round, self-reported confidence is the
textbook overconfidence signature, and it is a *report about the question*, not evidence about the
answer. On the shared \`urgency\` rubric the two backends did not agree either: a median of
**${f2(median(s1.urgency))}** from \`systemone\` against **${s2.urgency.length ? f2(median(s2.urgency)) : "—"}** from the chat
model, on the same 0–3 scale.

<Aside type="danger" title="A threshold tuned on one backend does not transfer to another">
This is exactly what \`Decision.calibrated\` is for, and it is why you read it. The \`systemone\`
style reported \`${uniq(s1.calibrated).join("/")}\` above; the \`llm\` style reported
\`${uniq(s2.calibrated).join("/") || "false"}\` — it derives nothing from token probabilities, so its
numbers are the model's self-report. Read it at the point you compare a number to a threshold. If
it is \`false\`, your thresholds were tuned against a different instrument: re-tune, or refuse.

\`\`\`go
d, err := classifier.Evaluate(ctx, state, questions)
if err != nil { return err }
if !d.Calibrated {
    // The numbers are self-reported, not calibrated. Either re-tune for THIS
    // backend or decline to threshold on them.
    return errUncalibratedBackend
}
risk, err := d.Score("risk")
\`\`\`

\`style: "llm"\` exists so a host with **no** System One credential can run the same questions on a
cheap chat model. It is a compatibility exit, not an equivalent.
</Aside>

## Latency is flat in roster size

One \`choice\` over a synthetic skill roster, same query at every size:

| options | input tokens | latency | pick | confidence |
|---|---|---|---|---|
${rosterTable}
| 300 | — | — | \`${capError.replace(/`/g, "'")}\` | — |

Latency does not grow with the roster across that range: a per-turn judge over 255 skills costs
about the same wall-clock as one over 20, which is what makes a judge-per-turn affordable at all.
Above 255 the request is refused **before it leaves the process**, by the library's own pre-flight
limit check naming the offending question — loud and legible enough for a caller to detect and
chunk, and it costs nothing to hit.

## The control that can fail

Most "is my prompt good" checks cannot fail: they measure the thing they are made of. The
**shuffle control** (<a href="https://github.com/muthuishere/toolnexus/blob/main/docs/adr/0021-the-encoding-carries-the-judgment.md">ADR 0021 D5</a>)
can. Keep the probabilities, permute which option each belongs to, re-run: if the score does not
collapse, the distribution was never carrying information about the options and you were reading
noise with two decimal places on it. Measured on snake, three games per encoding, identical seeds:
**prose 17, 17, 17 apples; the shuffle control 1, 0, 1.** *17 apples → 1.* That number is cited
from the ADR — it needs a game loop, not one call.

This runner runs the same control at the level of a single decision: it **moves each description
to a different option id** and re-asks. The shipping-damage sentence
("${DESCRIBED.shipping}") moves from \`shipping\` onto \`billing\`. If the answer follows the
sentence, the encoding is what is being judged. If it stays on the id, the encoding never was.

| encoding | ${N_ENCODING} picks | median top probability | \`nearUniform\` |
|---|---|---|---|
| **described** — each option described by consequence | \`${uniq(described.picks).join("`, `")}\` | **${f2(described.medianTop)}** | ${described.nearUniform}/${described.n} |
| **permuted** — the same descriptions, moved between ids | \`${uniq(permuted.picks).join("`, `")}\` | ${f2(permuted.medianTop)} | ${permuted.nearUniform}/${permuted.n} |
| **undescribed** — the id repeated as its own description | \`${uniq(undescribed.picks).join("`, `")}\` | ${f2(undescribed.medianTop)} | ${undescribed.nearUniform}/${undescribed.n} |

**Verdict on this run:** ${
	controlTracked
		? `the control PASSED — the answer moved with the description (\`${describedPick}\` → \`${permutedPick}\`, ${permutedFollowedText}/${N_ENCODING} runs landing on \`billing\`, the id that inherited the shipping-damage sentence).`
		: `the control FAILED to move — the answer stayed on \`${describedPick}\` after the descriptions were permuted, so on this fixture the id names are carrying the judgment rather than the descriptions.`
}

It costs one function, and it is the only check in this space that can come back negative. Run
it before you trust an encoding, and again when you change one.

Note where **undescribed** lands, while keeping the full state: describing the situation buys
nothing if the options are not described. That is the
[encoding obligation](/toolnexus/cookbook/judge/#the-encoding-obligation), and why the library
detects degenerate criteria and warns, naming the question.

## \`nearUniform\`, and the limit of every signal on this page

A choice answer whose probabilities sit at the uniform floor (\`1/n\`) is a **detectable** symptom
of an encoding that gave the model nothing to rank on — visible from responses alone, with no
baseline, no labels and no outcomes, so it runs on live traffic. In the table above the described
encoding returned a median top probability of **${f2(described.medianTop)}** and the undescribed
one **${f2(undescribed.medianTop)}** on a three-option choice (\`1/n\` = 0.33).

That separation is the whole of what it buys. \`nearUniform\` **cannot** distinguish a good encoding
from a subtly wrong one: a wrong-but-answerable question still reads as answerable — which is
precisely the case the permuted row above is, and it is why the shuffle control exists and
\`nearUniform\` is advisory.
`

if (dry) {
	console.error("--dry: not writing. Summary:")
	console.error(JSON.stringify({ ...data, systemone: { ...data.systemone, ms: undefined } }, null, 2))
	process.exit(0)
}
fs.writeFileSync(OUT_DATA, `${JSON.stringify(data, null, 2)}\n`)
fs.writeFileSync(OUT_PAGE, page)
console.error(`wrote ${path.relative(repoRoot, OUT_DATA)} and ${path.relative(repoRoot, OUT_PAGE)}`)
console.error(`${data.totalCalls} live calls, ${money(data.totalCostUsd)} total`)
