/**
 * Conformance tests for SPEC.md §8B, run against the SHARED fixtures in `examples/judge/`.
 * The fixtures are the contract; nothing here re-derives what correct is believed to look like.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import {
  createClassifier,
  canonicalRequest,
  canonicalJson,
  nearUniform,
  degenerateCriteria,
  levels,
  noul,
  choice,
  score,
  choiceOver,
  NEAR_UNIFORM_TOLERANCE,
  MAX_CHOICE_OPTIONS,
  DEFAULT_CLASSIFIER_BASE_URL,
  DEFAULT_CLASSIFIER_MODEL,
  DEFAULT_CLASSIFIER_API_KEY_ENV,
  createClient,
  createToolkit,
  decodeDecision,
  type Question,
  type RecordedDecision,
  type MetricEvent,
} from "../dist/index.js"

// ---------------------------------------------------------------- fixtures

interface Fixture {
  name: string
  request: { model: string; state: unknown; questions: Record<string, any> }
  canonical: string
  canonicalSha256: string
  canonicalBytes: number
  response?: unknown
  expect?: any
  entries?: Fixture[]
}

const fixtureUrl = (name: string) => new URL(`../../examples/judge/${name}.json`, import.meta.url)

function load(name: string): Fixture {
  return JSON.parse(readFileSync(fileURLToPath(fixtureUrl(name)), "utf8"))
}

const sha256 = (s: string) => createHash("sha256").update(s, "utf8").digest("hex")
const byteLen = (s: string) => Buffer.byteLength(s, "utf8")

/**
 * Rebuilds the typed questions from a fixture's raw JSON. The absent-vs-empty distinction is
 * preserved: a noul with no `criteria` key gets none, one with `{"true":"","false":""}` gets an
 * empty one, and the two produce different bytes.
 */
function questionsOf(f: Fixture): Record<string, Question> {
  const out: Record<string, Question> = {}
  for (const [key, raw] of Object.entries(f.request.questions)) {
    switch (raw.type) {
      case "noul":
        out[key] = noul(raw.instructions, raw.criteria)
        break
      case "choice":
        out[key] = choice(raw.instructions, raw.criteria)
        break
      case "score":
        out[key] = score(raw.instructions, raw.criteria)
        break
      default:
        throw new Error(`question ${key}: unknown type ${raw.type}`)
    }
  }
  return out
}

/** A `static` classifier answering this fixture's own request. */
function staticFrom(f: Fixture, extra: Record<string, unknown> = {}) {
  const questions = questionsOf(f)
  const c = createClassifier({
    style: "static",
    model: f.request.model,
    decisions: [{ state: f.request.state, questions, response: f.response }],
    ...extra,
  })
  return { c, questions }
}

// ---------------------------------------------------------------- the bytes

test("canonical bytes and sha256 match every fixture", () => {
  for (const name of ["base", "hardened", "numbers", "wide", "degenerate", "near-uniform"]) {
    const f = load(name)
    const got = canonicalRequest(f.request.model, questionsOf(f))
    assert.equal(got, f.canonical, `${name}: canonical bytes differ`)
    assert.equal(byteLen(got), f.canonicalBytes, `${name}: canonicalBytes`)
    assert.equal(sha256(got), f.canonicalSha256, `${name}: sha256`)
  }
})

test("decisions.json: three entries share one payload and one hash", () => {
  const f = load("decisions")
  assert.equal(f.entries!.length, 3)
  for (const [i, e] of f.entries!.entries()) {
    const got = canonicalRequest(e.request.model, questionsOf(e))
    assert.equal(got, e.canonical, `entry ${i}`)
    assert.equal(sha256(got), e.canonicalSha256, `entry ${i}`)
  }
  // One hash across all three: they differ only in state, which is outside the claim.
  assert.equal(new Set(f.entries!.map((e) => e.canonicalSha256)).size, 1)
})

test("an absent criteria is not an empty one", () => {
  const absent = canonicalRequest("m", { q: noul("i") })
  const empty = canonicalRequest("m", { q: noul("i", { true: "", false: "" }) })
  assert.ok(!absent.includes("criteria"), `absent criteria leaked: ${absent}`)
  assert.ok(empty.includes('"criteria":{"false":"","true":""}'), `empty criteria lost: ${empty}`)
  assert.notEqual(absent, empty)
})

test("canonicalJson sorts recursively in code-unit order, never reorders arrays, escapes nothing", () => {
  // Code-unit order, not culture-sensitive order: "10_alpha" < "Alpha" < "beta" < "café".
  assert.equal(
    canonicalJson({ café: 4, beta: 3, Alpha: 2, "10_alpha": 1 }),
    '{"10_alpha":1,"Alpha":2,"beta":3,"café":4}',
  )
  // Recursive, which a JSON.stringify replacer ARRAY cannot express.
  assert.equal(canonicalJson({ b: { d: 1, c: 2 }, a: 3 }), '{"a":3,"b":{"c":2,"d":1}}')
  // An array's order IS its meaning (a score rubric's numbering).
  assert.equal(canonicalJson(["zero", "mid", "apex"]), '["zero","mid","apex"]')
  // <>&'" and non-ASCII raw.
  assert.equal(canonicalJson("<b>A & B</b> 'x' 日本語"), '"<b>A & B</b> \'x\' 日本語"')
  // Absent is absent — not null, not {}.
  assert.equal(canonicalJson({ a: undefined, b: 1 }), '{"b":1}')
  // Shortest round-tripping decimal: integer 0 stays "0".
  assert.equal(canonicalJson({ n: 0, f: 1.21 }), '{"f":1.21,"n":0}')
})

// ---------------------------------------------------------------- the parse

test("base fixture parses, zero probabilities stay entries, wrong-type reads throw", async () => {
  const f = load("base")
  const { c, questions } = staticFrom(f)
  const d = await c.evaluate(f.request.state, questions)
  assert.equal(d.calibrated, true)
  assert.equal(d.model, "typesafe/jev-1.13-20260917")

  const ch = d.choice("department")
  assert.equal(ch.choice, "shipping")
  assert.equal(ch.confidence, 0.41)
  assert.equal(ch.nearUniform, false)
  // A zero probability must stay an ENTRY, not vanish.
  assert.ok("technical" in ch.probabilities)
  assert.equal(ch.probabilities.technical, 0)

  assert.equal(d.noul("is_refund_request").noul, 0.98)

  const s = d.score("urgency")
  assert.equal(s.score, 1.21)
  assert.equal(s.confidence, 0.57)
  assert.deepEqual(levels(s), ["routine", "elevated", "urgent"])

  // A wrong-type read is an error, never an undefined.
  assert.throws(() => d.noul("department"), /is a choice answer, not noul/)
  assert.throws(() => d.choice("nope"), /no answer "nope"/)
})

test("numbers parse numerically, never as strings", async () => {
  const f = load("numbers")
  const { c, questions } = staticFrom(f)
  const d = await c.evaluate(f.request.state, questions)
  const s = d.score("urgency")
  assert.equal(s.score, 1.21)
  assert.equal(s.probabilities["0"], 0.04)
  assert.equal(d.noul("is_expensive").noul, 0)
  assert.equal(d.usage.cost, 1.6716e-5)
  // The fixture's own assertions, compared as numbers.
  for (const [path, want] of Object.entries(f.expect.parse as Record<string, number>)) {
    const got = path.split(".").reduce<any>((acc, k) => acc[k], { answers: d.answers, usage: d.usage })
    assert.equal(got, want, path)
  }
})

test("wide: 40 probability keys, not near-uniform", async () => {
  const f = load("wide")
  const { c, questions } = staticFrom(f)
  const d = await c.evaluate(f.request.state, questions)
  const ch = d.choice("skill")
  assert.equal(ch.choice, "skill_07")
  assert.equal(Object.keys(ch.probabilities).length, 40)
  assert.equal(ch.nearUniform, false)
})

// ---------------------------------------------------------------- nearUniform

test("near-uniform fixture: all four answers, and the fixture's tolerance is ours", async () => {
  const f = load("near-uniform")
  assert.equal(f.expect.tolerance, NEAR_UNIFORM_TOLERANCE)
  const { c, questions } = staticFrom(f)
  const d = await c.evaluate(f.request.state, questions)
  for (const [key, want] of Object.entries(f.expect.answers as Record<string, any>)) {
    const ch = d.choice(key)
    assert.equal(ch.nearUniform, want.nearUniform, key)
    const target = 1 / Object.keys(ch.probabilities).length
    const max = Math.max(...Object.values(ch.probabilities).map((p) => Math.abs(p - target)))
    assert.ok(Math.abs(max - want.maxDeviation) < 1e-9, `${key}: maxDeviation ${max} != ${want.maxDeviation}`)
  }
})

test("nearUniform boundary, n=1 and the empty map", () => {
  assert.equal(nearUniform({ a: 0.5499, b: 0.4501 }), true) // 0.0499 deviation
  assert.equal(nearUniform({ a: 0.5501, b: 0.4499 }), false) // 0.0501 deviation
  assert.equal(nearUniform({ only: 1 }), true)
  assert.equal(nearUniform({}), false)
})

// ---------------------------------------------------------------- degenerate

test("degenerate criteria: three keys warn once each, described never, bytes unchanged", async () => {
  const f = load("degenerate")
  const questions = questionsOf(f)
  const warned: string[] = []
  const c = createClassifier({
    style: "custom",
    model: f.request.model,
    evaluate: () => emptyDecision(),
    onMetric: (ev: MetricEvent) => {
      if (ev.event === "classifier.warning") {
        warned.push(ev.question)
        assert.ok(ev.warning.includes(ev.question), `warning must name the key: ${ev.warning}`)
      }
    },
  })
  // Twice: detection is ONCE PER QUESTION KEY, so a per-turn judge does not flood the sink.
  await c.evaluate(f.request.state, questions)
  await c.evaluate(f.request.state, questions)
  assert.deepEqual(warned.sort(), f.expect.warnings)
  for (const key of f.expect.noWarning) assert.ok(!warned.includes(key), `${key} must not warn`)
  // Detection, never repair: the bytes are the bytes the same questions produce without it.
  assert.equal(sha256(canonicalRequest(f.request.model, questions)), f.canonicalSha256)
})

test("the degenerate predicate", () => {
  assert.ok(degenerateCriteria({ a: "", b: "" }))
  assert.ok(degenerateCriteria({ a: "a", b: "b" }))
  assert.ok(degenerateCriteria({ a: "same", b: "same" }))
  assert.equal(degenerateCriteria({ a: "one thing", b: "another" }), "")
  assert.equal(degenerateCriteria({ a: "", b: "described" }), "")
  // n === 1 is NEVER reported: there is nothing to differentiate.
  assert.equal(degenerateCriteria({ only: "only" }), "")
})

// ---------------------------------------------------------------- the static backend

test("the static backend distinguishes the three guard bands, and errors on an unrecorded state", async () => {
  const f = load("decisions")
  const decisions: RecordedDecision[] = f.entries!.map((e) => ({
    state: e.request.state,
    questions: questionsOf(e),
    response: e.response,
  }))
  const c = createClassifier({ style: "static", model: f.entries![0].request.model, decisions })
  const wantScores = [0.02, 2.25, 2.97]
  for (const [i, e] of f.entries!.entries()) {
    const d = await c.evaluate(e.request.state, questionsOf(e))
    assert.equal(d.score("risk").score, wantScores[i], `band ${i}`)
  }
  // An unrecorded state is an error, never a guess at a neighbouring band.
  await assert.rejects(
    () => c.evaluate({ command: "unseen", cwd: "/repo", tool: "bash" }, questionsOf(f.entries![0])),
    /no recorded decision/,
  )
})

// ---------------------------------------------------------------- limits

test("limits are rejected pre-flight, naming the key and the limit, with no HTTP call", async () => {
  let calls = 0
  const spy: typeof fetch = async () => {
    calls++
    throw new Error("no request should have been sent")
  }
  const tooMany: Record<string, string> = {}
  for (let i = 0; i <= MAX_CHOICE_OPTIONS; i++) tooMany[`opt_${i}`] = `description ${i}`
  const cases: Array<[Question, RegExp]> = [
    [choice("?", tooMany), /1\.\.255 options/],
    [choice("?", {}), /1\.\.255 options/],
    [score("?", ["only"]), /2\.\.10 ordered levels/],
    [score("?", Array.from({ length: 11 }, (_, i) => `l${i}`)), /2\.\.10 ordered levels/],
  ]
  for (const [q, want] of cases) {
    const c = createClassifier({ fetch: spy })
    await assert.rejects(() => c.evaluate("s", { the_key: q }), (e: Error) => {
      assert.match(e.message, /the_key/)
      assert.match(e.message, want)
      return true
    })
  }
  assert.equal(calls, 0, "a request was sent despite a pre-flight failure")
})

// ---------------------------------------------------------------- secrets

test("no credential and no expanded header value reaches an error or a metric", async () => {
  const key = "sk-live-NEVER-IN-AN-ERROR"
  const tenant = "tenant-NEVER-IN-AN-ERROR"
  process.env.TEST_JUDGE_KEY = key
  process.env.TEST_JUDGE_TENANT = tenant
  let sawAuth = ""
  let sawTenant = ""
  // A real gateway happily reflects what it was sent back in its own 401 body.
  const gateway: typeof fetch = async (_url, init) => {
    const h = (init!.headers ?? {}) as Record<string, string>
    sawAuth = h["Authorization"] ?? ""
    sawTenant = h["X-Tenant"] ?? ""
    return new Response(JSON.stringify({ error: `bad credential ${sawAuth} for ${sawTenant}` }), { status: 401 })
  }
  const events: MetricEvent[] = []
  const c = createClassifier({
    baseUrl: "https://gateway.example/v1",
    apiKeyEnv: "TEST_JUDGE_KEY",
    headers: { "X-Tenant": "${TEST_JUDGE_TENANT}" },
    retries: 0,
    fetch: gateway,
    onMetric: (ev) => events.push(ev),
  })
  const err = await c.evaluate("s", { q: noul("?") }).then(
    () => undefined,
    (e: Error) => e,
  )
  assert.ok(err, "expected a 401 failure")
  // The credential and the header DID reach the wire — they are use-only, not unused …
  assert.equal(sawAuth, `Bearer ${key}`)
  assert.equal(sawTenant, tenant, "${ENV} header must expand at call time")
  // … and nowhere else.
  const haystack = [err!.message, err!.stack ?? "", JSON.stringify(events)].join("\n")
  for (const secret of [key, tenant, "NEVER-IN-AN-ERROR"]) {
    assert.ok(!haystack.includes(secret), `a secret leaked: ${haystack}`)
  }
  assert.match(err!.message, /401/)
  assert.match(err!.message, /gateway\.example/)
  delete process.env.TEST_JUDGE_KEY
  delete process.env.TEST_JUDGE_TENANT
})

test("a backend's own cause survives intact on a non-auth status", async () => {
  const c = createClassifier({
    baseUrl: "https://gateway.example/v1",
    apiKeyEnv: "TEST_JUDGE_UNSET",
    retries: 0,
    fetch: async () =>
      new Response(JSON.stringify({ error: "Too many choices. Must have at most 255 choices." }), { status: 400 }),
  })
  await assert.rejects(() => c.evaluate("s", { q: noul("?") }), /Too many choices/)
})

// ---------------------------------------------------------------- the wire

test("systemone posts the canonical body with state verbatim, through the Gap 1 pipeline", async () => {
  const f = load("base")
  let url = ""
  let body = ""
  const c = createClassifier({
    baseUrl: "https://gateway.example/v1/",
    model: f.request.model,
    apiKeyEnv: "TEST_JUDGE_UNSET",
    fetch: async (u, init) => {
      url = String(u)
      body = String(init!.body)
      return new Response(JSON.stringify(f.response), { status: 200 })
    },
  })
  const d = await c.evaluate(f.request.state, questionsOf(f))
  assert.equal(url, "https://gateway.example/v1/systemone")
  const sent = JSON.parse(body)
  assert.equal(sent.state, f.request.state)
  assert.equal(canonicalJson({ model: sent.model, questions: sent.questions }), f.canonical)
  assert.equal(d.choice("department").choice, "shipping")

  // requestParams wins on collision; bodyTransform runs last.
  let transformed = ""
  const c2 = createClassifier({
    baseUrl: "https://gateway.example/v1",
    model: f.request.model,
    apiKeyEnv: "TEST_JUDGE_UNSET",
    requestParams: { tenant: "acme", model: "overridden" },
    bodyTransform: (b) => ({ ...b, wrapped: true }),
    fetch: async (_u, init) => {
      transformed = String(init!.body)
      return new Response(JSON.stringify(f.response), { status: 200 })
    },
  })
  await c2.evaluate(f.request.state, questionsOf(f))
  const b2 = JSON.parse(transformed)
  assert.equal(b2.tenant, "acme")
  assert.equal(b2.model, "overridden")
  assert.equal(b2.wrapped, true)
})

test("retries honour Retry-After and the onError tier, and emit one ok metric", async () => {
  const f = load("base")
  let attempts = 0
  const events: MetricEvent[] = []
  const c = createClassifier({
    baseUrl: "https://gateway.example/v1",
    model: f.request.model,
    apiKeyEnv: "TEST_JUDGE_UNSET",
    retries: 2,
    retryBaseMs: 1,
    onMetric: (ev) => events.push(ev),
    fetch: async () => {
      attempts++
      if (attempts === 1) return new Response("slow down", { status: 429, headers: { "retry-after": "0" } })
      return new Response(JSON.stringify(f.response), { status: 200 })
    },
  })
  await c.evaluate(f.request.state, questionsOf(f))
  assert.equal(attempts, 2)
  const ok = events.filter((e) => e.event === "classifier.evaluate")
  assert.equal(ok.length, 1)
  assert.equal(ok[0].event === "classifier.evaluate" && ok[0].status, "ok")

  // onError "fail" stops the budget early.
  let tries = 0
  const c2 = createClassifier({
    baseUrl: "https://gateway.example/v1",
    apiKeyEnv: "TEST_JUDGE_UNSET",
    retries: 5,
    onError: () => "fail",
    fetch: async () => {
      tries++
      return new Response("nope", { status: 503 })
    },
  })
  await assert.rejects(() => c2.evaluate("s", { q: noul("?") }), /503/)
  assert.equal(tries, 1)
})

test("529 Overloaded retries by default; other unlisted 5xx do not until retryableStatuses says so", async () => {
  // TypeSafe documents 529 Overloaded as "retry with backoff"; the default set made it terminal.
  // It is now in the defaults. The set stays an ENUMERATION, so 520-527 and 501 remain terminal
  // until a host opts in — which is what `retryableStatuses` is for.
  const attemptsFor = async (status: number, retryableStatuses?: readonly number[]) => {
    let attempts = 0
    const f = load("base")
    const c = createClassifier({
      baseUrl: "https://gateway.example/v1",
      model: f.request.model,
      apiKeyEnv: "TEST_JUDGE_UNSET",
      retries: 2,
      retryBaseMs: 1,
      retryableStatuses,
      fetch: async () => {
        attempts++
        if (attempts === 1) return new Response("transient", { status })
        return new Response(JSON.stringify(f.response), { status: 200 })
      },
    })
    await c.evaluate(f.request.state, questionsOf(f)).catch(() => {})
    return attempts
  }

  assert.equal(await attemptsFor(529), 2, "529 is retryable by default")
  assert.equal(await attemptsFor(429), 2, "429 still retries")
  assert.equal(await attemptsFor(520), 1, "an unlisted 5xx is terminal by default")
  assert.equal(await attemptsFor(501), 1, "a permanent 5xx is terminal by default")

  // Additive, the Cloudflare case: 520-527 opted in, and the defaults still stand beside them.
  const cloudflare = [520, 521, 522, 523, 524, 525, 526, 527]
  assert.equal(await attemptsFor(520, cloudflare), 2, "an opted-in status retries")
  assert.equal(await attemptsFor(429, cloudflare), 2, "the defaults are not replaced")
  assert.equal(await attemptsFor(501, cloudflare), 1, "a status outside both sets stays terminal")

  // 4xx other than 429 is terminal; the option is a default, and onError has the final say.
  assert.equal(await attemptsFor(422), 1)
  let tries = 0
  const c4 = createClassifier({
    baseUrl: "https://gateway.example/v1",
    apiKeyEnv: "TEST_JUDGE_UNSET",
    retries: 3,
    retryBaseMs: 1,
    retryableStatuses: [520],
    onError: () => "fail",
    fetch: async () => {
      tries++
      return new Response("cloudflare", { status: 520 })
    },
  })
  await assert.rejects(() => c4.evaluate("s", { q: noul("?") }), /520/)
  assert.equal(tries, 1, "onError overrides a status the host itself listed")
})

test("a TypeSafe-shaped usage block reports an ABSENT cost, not a free call", async () => {
  // TypeSafe's own API returns model/answers/usage and no `cost` key at all. Reporting 0 there
  // would read as "this call was free" when the truth is "this backend does not say".
  const c = createClassifier({
    baseUrl: "https://api.typesafe.ai/v1",
    model: "jev-latest",
    apiKeyEnv: "TEST_JUDGE_UNSET",
    fetch: async () =>
      new Response(
        JSON.stringify({
          model: "jev-1.13.0",
          answers: { q: { type: "noul", noul: 0.98 } },
          usage: { input_tokens: 331, output_tokens: 48 },
        }),
        { status: 200 },
      ),
  })
  const d = await c.evaluate("s", { q: noul("?") })
  assert.equal(d.usage.inputTokens, 331)
  assert.equal(d.usage.cost, undefined)
})

// ---------------------------------------------------------------- llm style

test("the llm style renders one structured-output call and reports calibrated: false", async () => {
  const reply = {
    answers: {
      pick: { type: "choice", choice: "north", probabilities: { north: 0.9, south: 0.1 }, confidence: 0.9 },
    },
  }
  const client = createClient({
    baseUrl: "https://llm.example/v1",
    style: "openai",
    model: "gpt-test",
    apiKey: "k",
    fetch: async () =>
      new Response(
        JSON.stringify({
          choices: [{ message: { content: "here you go:\n" + JSON.stringify(reply) }, finish_reason: "stop" }],
          usage: { prompt_tokens: 11, completion_tokens: 3 },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
  })
  const c = createClassifier({ style: "llm", client, model: "gpt-test" })
  const d = await c.evaluate(
    { where: "a fork" },
    { pick: choiceOver("Which way?", { north: "toward the apple", south: "toward the tail" }) },
  )
  assert.equal(d.calibrated, false, "the llm style never claims calibration")
  assert.equal(d.choice("pick").choice, "north")
  assert.equal(d.choice("pick").nearUniform, false)
  assert.equal(d.usage.inputTokens, 11)
  assert.equal(d.usage.outputTokens, 3)
})

// ---------------------------------------------------------------- construction

test("createClassifier applies the defaults and rejects an incomplete style", async () => {
  assert.throws(() => createClassifier({ style: "llm" }), /requires `client`/)
  assert.throws(() => createClassifier({ style: "custom" }), /requires `evaluate`/)
  assert.throws(() => createClassifier({ style: "nonsense" as any }), /unknown style/)
  // The defaults, observed rather than asserted about private state.
  let url = ""
  const c = createClassifier({
    apiKeyEnv: DEFAULT_CLASSIFIER_API_KEY_ENV,
    fetch: async (u) => {
      url = String(u)
      return new Response(JSON.stringify({ model: "m", answers: {}, usage: {} }), { status: 200 })
    },
  })
  const d = await c.evaluate("s", { q: noul("?") })
  assert.equal(url, `${DEFAULT_CLASSIFIER_BASE_URL}/systemone`)
  assert.equal(DEFAULT_CLASSIFIER_MODEL, "jev-latest")
  assert.equal(d.calibrated, true)
})

// ---------------------------------------------------------------- non-breaking

test("constructing a Classifier changes no client request byte", async () => {
  const capture = async (): Promise<string> => {
    let body = ""
    const client = createClient({
      baseUrl: "https://llm.example/v1",
      style: "openai",
      model: "m",
      apiKey: "k",
      fetch: async (_u, init) => {
        body = String(init!.body)
        return new Response(
          JSON.stringify({ choices: [{ message: { content: "done" }, finish_reason: "stop" }], usage: {} }),
          { status: 200, headers: { "content-type": "application/json" } },
        )
      },
    })
    const toolkit = await createToolkit({})
    await client.run("hello", { toolkit })
    return body
  }
  const without = await capture()
  // Construct one, and exercise it, before capturing again.
  const c = createClassifier({
    style: "custom",
    evaluate: () => emptyDecision(),
  })
  await c.evaluate("s", { q: noul("?") })
  assert.equal(await capture(), without)
})

/** A minimal Decision for a stub backend, built by the library's own decoder so it cannot
 * drift from the real shape. */
function emptyDecision() {
  return decodeDecision({ model: "m", answers: {}, usage: { input_tokens: 0, output_tokens: 0 } }, "m")
}
