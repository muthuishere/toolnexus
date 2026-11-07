/**
 * Conformance tests for SPEC.md §8B "Backends, images, cache and validation" (change
 * add-classifier-backends), run against the SHARED fixtures in `examples/judge/backends/`.
 * Hermetic: every request goes to an injected fake `fetch`; no network, no real keys.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { createServer } from "node:http"
import { fileURLToPath } from "node:url"
import {
  createClassifier,
  CLASSIFIER_BACKENDS,
  classifierKeyEnv,
  CLASSIFIER_IMAGES_HEADER,
  ClassifierUnsupported,
  MemoryCache,
  classifierModelCacheKey,
  classifierDecisionCacheKey,
  classifierDecisionCacheKeyBytes,
  validateDecision,
  thresholdBand,
  DEFAULT_THRESHOLDS,
  chunkQuestions,
  decodeDecision,
  noul,
  choice,
  score,
  raw,
  judge,
  answersOf,
  type AnyQuestion,
  type ClassifierBackend,
  type MetricEvent,
} from "../dist/index.js"

const load = (rel: string) =>
  JSON.parse(readFileSync(fileURLToPath(new URL(`../../examples/judge/${rel}`, import.meta.url)), "utf8"))

/** Wire question map -> typed questions (raw for unknown types). */
function questionsOf(m: Record<string, any>): Record<string, AnyQuestion> {
  const out: Record<string, AnyQuestion> = {}
  for (const [k, w] of Object.entries(m)) {
    if (w.type === "noul") out[k] = noul(w.instructions, w.criteria)
    else if (w.type === "choice") out[k] = choice(w.instructions, w.criteria)
    else if (w.type === "score") out[k] = score(w.instructions, w.criteria)
    else out[k] = raw(w.type, w.instructions, w.criteria)
  }
  return out
}

/** {"$image":{mimeType,data}} -> image part, {"$text":s} -> text part. */
function partsOf(v: any): any {
  if (Array.isArray(v)) return v.map(partsOf)
  if (v && typeof v === "object") {
    if (v.$image) return { type: "image", mimeType: v.$image.mimeType, data: v.$image.data }
    if (typeof v.$text === "string") return { type: "text", text: v.$text }
    return Object.fromEntries(Object.entries(v).map(([k, e]) => [k, partsOf(e)]))
  }
  return v
}

const NOUL_REPLY = JSON.stringify({ model: "jev-1.14.0", answers: { q: { type: "noul", noul: 0.91 } }, usage: { input_tokens: 1, output_tokens: 1 } })

interface Seen {
  url: string
  headers: Record<string, string>
  body: string
}

function fakeFetch(reply: (body: string, n: number) => [number, string]) {
  const seen: Seen[] = []
  const f: any = async (url: string, init: any) => {
    seen.push({ url, headers: init.headers, body: init.body })
    const [status, text] = reply(init.body, seen.length)
    return new Response(text, { status, headers: { "content-type": "application/json" } })
  }
  return { fetch: f as typeof fetch, seen }
}

// ---------------------------------------------------------------- presets

test("backends: the table matches presets.json, and the construction errors are exact", () => {
  const fx = load("backends/presets.json")
  assert.deepEqual(Object.keys(CLASSIFIER_BACKENDS).sort(), Object.keys(fx.backends).sort())
  for (const [name, row] of Object.entries<any>(fx.backends)) {
    const p = CLASSIFIER_BACKENDS[name as ClassifierBackend]
    assert.equal(p.baseUrl, row.baseUrl ?? "", name)
    assert.equal(p.model, row.model, name)
    assert.equal(p.apiKeyEnv, row.apiKeyEnv, name)
    assert.equal(p.wire, row.wire, name)
    assert.equal(p.imagesHeader, row.imagesHeader, name)
    if (row.supportsImages === null) assert.equal(p.supports, undefined, name)
    else for (const [m, want] of Object.entries(row.supportsImages)) assert.equal(p.supports!(m).images, want, `${name} ${m}`)
  }
  assert.throws(() => createClassifier({ backend: "nope" as any }), (e: Error) => e.message === fx.unknownBackendError)
  assert.throws(() => createClassifier({ backend: "custom" }), (e: Error) => e.message === fx.customError)
  assert.throws(() => createClassifier({ backend: "custom", baseUrl: "http://x" }), (e: Error) => e.message === fx.customError)
  assert.throws(() => createClassifier({ backend: "custom", supports: { images: true } }), (e: Error) => e.message === fx.customError)
  assert.ok(createClassifier({ backend: "custom", baseUrl: "http://x", supports: { images: false } }))
  // The check is systemone-only: a non-wire style never touches the endpoint.
  assert.ok(createClassifier({ backend: "custom", style: "static", decisions: [] }))
})

// ---------------------------------------------------------------- images

test("images: lifting, references, wire bodies, header and limits match images.json", async () => {
  const fx = load("backends/images.json")
  const prev = process.env.CLOUDFLARE_ACCOUNT_ID
  process.env.CLOUDFLARE_ACCOUNT_ID = "acct-test"
  try {
    for (const c of fx.cases) {
      const f = fakeFetch(() => [200, c.backend === "cloudflare" ? JSON.stringify({ success: true, result: JSON.parse(NOUL_REPLY) }) : NOUL_REPLY])
      const cl = createClassifier({
        backend: c.backend,
        model: c.model,
        supports: c.supports,
        fetch: f.fetch,
        apiKeyEnv: "TN_TEST_UNSET_KEY",
      })
      const run = cl.evaluate(partsOf(c.state), questionsOf(fx.questions))
      if (c.expect.unsupported) {
        await assert.rejects(run, (e: Error) => e instanceof ClassifierUnsupported && e.message === c.expect.error, c.name)
        assert.equal(f.seen.length, 0, `${c.name}: a request was sent`)
        continue
      }
      if (c.expect.errorPrefix) {
        await assert.rejects(
          run,
          (e: Error) => !(e instanceof ClassifierUnsupported) && e.message.startsWith(c.expect.errorPrefix),
          c.name,
        )
        assert.equal(f.seen.length, 0, `${c.name}: a request was sent`)
        continue
      }
      await run
      assert.equal(f.seen.length, 1, c.name)
      assert.equal(f.seen[0].body, c.expect.body, c.name)
      assert.equal(f.seen[0].headers[CLASSIFIER_IMAGES_HEADER] === "true", c.expect.imagesHeader, `${c.name} header`)
      const preset = CLASSIFIER_BACKENDS[c.backend as ClassifierBackend]
      if (c.expect.url) {
        assert.equal(c.expect.url, preset.baseUrl, c.name)
        assert.equal(f.seen[0].url, c.expect.url.replace("${CLOUDFLARE_ACCOUNT_ID}", "acct-test"), `${c.name}: url expands at call time`)
      } else {
        assert.equal(f.seen[0].url, preset.baseUrl + "/systemone", c.name)
      }
    }
  } finally {
    if (prev === undefined) delete process.env.CLOUDFLARE_ACCOUNT_ID
    else process.env.CLOUDFLARE_ACCOUNT_ID = prev
  }
})

test("images: other styles get the state verbatim; host headers win; non-image parts fail", async () => {
  const img = { type: "image", mimeType: "image/png", data: "iVBORw0KGgoAAAANSUhEUgAAASwAAAEsCAIAAAAAAAAA" }
  let got: unknown
  const custom = createClassifier({ style: "custom", evaluate: (s) => ((got = s), decodeDecision(JSON.parse(NOUL_REPLY), "m")) })
  await custom.evaluate({ photo: img }, { q: noul("x") })
  assert.deepEqual(got, { photo: img })

  const f = fakeFetch(() => [200, NOUL_REPLY])
  const cl = createClassifier({ fetch: f.fetch, headers: { [CLASSIFIER_IMAGES_HEADER]: "false" } })
  await cl.evaluate({ photo: img }, { q: noul("x") })
  assert.equal(f.seen[0].headers[CLASSIFIER_IMAGES_HEADER], "false")

  await assert.rejects(cl.evaluate({ a: { type: "audio", mimeType: "audio/wav", data: "AAAA" } }, { q: noul("x") }), /audio parts are not supported/)
  await assert.rejects(cl.evaluate({ a: { type: "image", mimeType: "image/png", url: "https://x/y.png" } }, { q: noul("x") }), /needs inline data/)
  assert.equal(f.seen.length, 1)
})

// ---------------------------------------------------------------- cloudflare

test("cloudflare: envelope unwrap matches cloudflare-replies.json; errors never echo the envelope", async () => {
  const fx = load("backends/cloudflare-replies.json")
  for (const c of fx.cases) {
    const f = fakeFetch(() => [200, JSON.stringify(c.reply)])
    const cl = createClassifier({ backend: "cloudflare", baseUrl: "http://cf.test/run", fetch: f.fetch })
    const run = cl.evaluate("x", questionsOf(fx.questions))
    if (c.expect.error) {
      await assert.rejects(run, (e: Error) => e.message === c.expect.error, c.name)
    } else {
      const d = await run
      assert.equal(d.model, c.expect.model, c.name)
      assert.equal(d.noul("q").noul, c.expect.noul, c.name)
    }
    assert.equal(f.seen[0].url, "http://cf.test/run", `${c.name}: no /systemone suffix`)
  }
})

test("cloudflare: an error names the endpoint UNEXPANDED, never the account id", async () => {
  process.env.TN_TEST_ACCOUNT = "secretacct"
  try {
    const f = fakeFetch(() => [500, "boom"])
    const cl = createClassifier({ backend: "cloudflare", baseUrl: "http://cf.test/accounts/${TN_TEST_ACCOUNT}/ai/run", fetch: f.fetch, retries: 0 })
    await assert.rejects(cl.evaluate("x", { q: noul("y") }), (e: Error) => !e.message.includes("secretacct") && e.message.includes("${TN_TEST_ACCOUNT}"))
    assert.equal(f.seen[0].url, "http://cf.test/accounts/secretacct/ai/run")
    const thrower: any = async (url: string) => {
      throw new Error(`connect failed to ${url}`)
    }
    const c2 = createClassifier({ backend: "cloudflare", baseUrl: "http://cf.test/accounts/${TN_TEST_ACCOUNT}/ai/run", fetch: thrower, retries: 0 })
    await assert.rejects(c2.evaluate("x", { q: noul("y") }), (e: Error) => !e.message.includes("secretacct"))
  } finally {
    delete process.env.TN_TEST_ACCOUNT
  }
})

// ---------------------------------------------------------------- cache keys

test("cache: key bytes and hashes match cache-keys.json", () => {
  const fx = load("backends/cache-keys.json")
  for (const c of fx.cases) {
    const qs = questionsOf(c.questions)
    assert.equal(classifierModelCacheKey(c.endpoint, c.model), c.modelKey, c.name)
    assert.equal(classifierDecisionCacheKeyBytes(c.endpoint, c.served, c.state, c.assets, qs), c.decisionKeyBytes, c.name)
    assert.equal(classifierDecisionCacheKey(c.endpoint, c.served, c.state, c.assets, qs), c.decisionKey, c.name)
  }
})

// ---------------------------------------------------------------- V1 / T1 / C1

test("validateDecision matches validate-cases.json", () => {
  const fx = load("backends/validate-cases.json")
  for (const c of fx.cases) {
    const err = validateDecision(questionsOf(c.questions), decodeDecision(c.response, "m"))
    if (c.valid) assert.equal(err, undefined, `${c.name}: ${err?.message}`)
    else {
      assert.ok(err, c.name)
      assert.ok(err!.message.startsWith("classifier: invalid decision: "), c.name)
      assert.ok(err!.message.includes(`"${c.key}"`), `${c.name}: ${err!.message}`)
    }
  }
})

test("Thresholds are inclusive and differ from Bands, per thresholds-cases.json", () => {
  const fx = load("backends/thresholds-cases.json")
  assert.deepEqual({ ...DEFAULT_THRESHOLDS }, fx.default)
  for (const c of fx.cases) {
    const d = decodeDecision({ model: "m", answers: { a: c.answer } }, "m")
    const a = d.answers.a
    assert.equal(thresholdBand(a, c.thresholds), c.band, `${c.name} thresholds`)
    const w: any = answersOf(d).a
    const bandsBand = w.type === "noul" ? w.band : w.sure ? "yes" : "uncertain"
    assert.equal(bandsBand, c.bandsBand, `${c.name} bands`)
  }
})

test("chunkQuestions: ASCII key order, size >= 1", () => {
  const qs = { c: noul("c"), a: noul("a"), b: noul("b") }
  assert.deepEqual(chunkQuestions(qs, 2).map((m) => Object.keys(m)), [["a", "b"], ["c"]])
  assert.throws(() => chunkQuestions(qs, 0), /chunk size must be at least 1/)
})

// ---------------------------------------------------------------- builders + cache

test("state(v).evaluate + cache: second call is a hit, sends nothing, and the event says cached", async () => {
  const f = fakeFetch(() => [200, NOUL_REPLY])
  const events: MetricEvent[] = []
  const cl = createClassifier({ fetch: f.fetch, cache: new MemoryCache(3_600_000, 100), onMetric: (e) => events.push(e) })
  const d1 = await cl.state("s").evaluate(judge.noul("q", "x"))
  assert.equal(d1.cached, false)
  const d2 = await cl.state("s").evaluate(judge.noul("q", "x"))
  assert.equal(d2.cached, true)
  assert.equal(f.seen.length, 1)
  const ev: any = events.filter((e) => e.event === "classifier.evaluate")[1]
  assert.equal(ev.cached, true)
  assert.equal(ev.questions, 1)
  assert.match(ev.questionsHash, /^[0-9a-f]{64}$/)
  await cl.state("other").evaluate(judge.noul("q", "x"))
  assert.equal(f.seen.length, 2, "a different state misses")
  await assert.rejects(cl.state("s").evaluate(judge.noul("q", "x"), judge.noul("q", "y")), /duplicate question name "q"/)
  assert.equal(f.seen.length, 2)
})

test("cache: async get/set work; invalid replies are never stored; builder fails closed, evaluate does not", async () => {
  const store = new Map<string, string>()
  const cache = { get: async (k: string) => store.get(k), set: async (k: string, v: string) => void store.set(k, v) }
  const bad = JSON.stringify({ model: "jev-1.14.0", answers: { q: { type: "noul", noul: 1.5 } } })
  const f = fakeFetch(() => [200, bad])
  const cl = createClassifier({ fetch: f.fetch, cache })
  await cl.evaluate("s", { q: noul("x") })
  await cl.evaluate("s", { q: noul("x") })
  assert.equal(f.seen.length, 2, "invalid reply was cached")
  assert.equal(store.size, 0)
  await assert.rejects(cl.state("s").evaluate(judge.noul("q", "x")), (e: Error) => e.message.startsWith('classifier: invalid decision: answer "q"'))

  const good = fakeFetch(() => [200, NOUL_REPLY])
  const c2 = createClassifier({ fetch: good.fetch, cache })
  await c2.evaluate("s", { q: noul("x") })
  assert.equal(store.size, 2)
  assert.equal((await c2.evaluate("s", { q: noul("x") })).cached, true)
  assert.equal(good.seen.length, 1)
  // A corrupt entry is a miss.
  for (const k of store.keys()) if (store.get(k)!.startsWith("{")) store.set(k, "not json")
  assert.equal((await c2.evaluate("s", { q: noul("x") })).cached, false)
})

test("cache follows the SERVED model: an alias move re-points the model key", async () => {
  let served = "jev-1.13.0"
  const f = fakeFetch(() => [200, JSON.stringify({ model: served, answers: { q: { type: "noul", noul: 0.5 } } })])
  const cache = new MemoryCache()
  const cl = createClassifier({ baseUrl: "http://s1.test", fetch: f.fetch, cache })
  await cl.state("s").evaluate(judge.noul("q", "x"))
  assert.equal(cache.get(classifierModelCacheKey("http://s1.test", "jev-latest")), "jev-1.13.0")
  served = "jev-1.14.0"
  await cl.state("other").evaluate(judge.noul("q", "x"))
  const d = await cl.state("s").evaluate(judge.noul("q", "x"))
  assert.equal(d.cached, false)
  assert.equal(f.seen.length, 3)
  assert.equal(d.model, "jev-1.14.0")
})

test("MemoryCache: ttl expiry and oldest-inserted eviction", () => {
  let now = 0
  const c = new MemoryCache(10, 2, () => now)
  c.set("a", "1")
  c.set("b", "2")
  c.set("c", "3")
  assert.equal(c.get("a"), undefined)
  assert.equal(c.get("b"), "2")
  now = 11
  assert.equal(c.get("c"), undefined)
})

test("states(...).evaluate: per-item results in order; one failure never fails the batch", async () => {
  const f = fakeFetch((body) => (body.includes('"state":"bad"') ? [400, '{"error":"nope"}'] : [200, NOUL_REPLY]))
  const cl = createClassifier({ fetch: f.fetch, retries: 0 })
  const rs = await cl.states("a", "bad", "c").evaluate(judge.noul("q", "Is the object red?"))
  assert.equal(rs.length, 3)
  assert.equal(rs[0].decision?.model, "jev-1.14.0")
  assert.ok(rs[1].error && !rs[1].decision)
  assert.ok(rs[2].decision && !rs[2].error)
  assert.deepEqual(await cl.states().evaluate(judge.noul("q", "x")), [])
  await assert.rejects(cl.states("a").evaluate(judge.noul("q", "x"), judge.noul("q", "x")), /duplicate question name/)
  assert.equal(f.seen.length, 3)
})

// ---------------------------------------------------------------- open union, L1

test("raw questions go out as written; unknown answer types decode to raw answers", async () => {
  const f = fakeFetch(() => [200, JSON.stringify({ model: "jev-1.14.0", answers: { spot: { type: "box", box: [1, 2, 3, 4] } } })])
  const cl = createClassifier({ fetch: f.fetch })
  const d = await cl.state("s").evaluate(judge.raw("spot", "box", "Where is the mug?"))
  assert.equal(f.seen[0].body, '{"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"}')
  const r = d.raw("spot")
  assert.equal(r.type, "box")
  assert.deepEqual(r.value.box, [1, 2, 3, 4])
  await assert.rejects(cl.evaluate("s", { x: raw("", "y") }), /a raw question needs a type/)
})

test("L1: every classifier.evaluate event carries questions / questionsHash / cached", async () => {
  const fx = load("base.json")
  const events: any[] = []
  const cl = createClassifier({
    style: "custom",
    model: fx.request.model,
    evaluate: () => decodeDecision({ model: "m", answers: {} }, "m"),
    onMetric: (e) => events.push(e),
  })
  await cl.evaluate(fx.request.state, questionsOf(fx.request.questions))
  assert.equal(events[0].questionsHash, fx.canonicalSha256)
  assert.equal(events[0].questions, 3)
  assert.equal(events[0].cached, false)
  // The builder path validates on custom too, and the error event carries L1.
  await assert.rejects(cl.state(fx.request.state).evaluate(judge.noul("q", "x")), /invalid decision: no answer "q"/)
  const err = events.at(-1)
  assert.equal(err.status, "error")
  assert.equal(err.questions, 1)
  assert.match(err.questionsHash, /^[0-9a-f]{64}$/)
})

// ---------------------------------------------------------------- key routing (#155)

test("key routing: a preset's env key reaches only its own host (key-routing.json)", async () => {
  const fx = load("backends/key-routing.json")
  for (const c of fx.captureCases) {
    const auths: (string | undefined)[] = []
    const srv = createServer((req, res) => {
      auths.push(req.headers.authorization)
      req.resume()
      req.on("end", () => {
        const reply = c.backend === "cloudflare" ? JSON.stringify({ success: true, result: JSON.parse(NOUL_REPLY) }) : NOUL_REPLY
        res.writeHead(200, { "content-type": "application/json" }).end(reply)
      })
    })
    await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
    const saved: Record<string, string | undefined> = {}
    for (const [k, v] of Object.entries<string>(c.env)) {
      saved[k] = process.env[k]
      process.env[k] = v
    }
    try {
      const port = (srv.address() as any).port
      const cl = createClassifier({
        backend: c.backend,
        baseUrl: `http://127.0.0.1:${port}/v1`,
        apiKeyEnv: c.apiKeyEnv,
        headers: c.headers,
        supports: c.backend === "custom" ? { images: false } : undefined,
        retries: 0,
      })
      await cl.evaluate("x", questionsOf(fx.questions))
      assert.ok(auths.length > 0, `${c.name}: no request reached the capture server`)
      for (const a of auths) assert.equal(a ?? null, c.expectAuthorization, c.name)
    } finally {
      for (const [k, v] of Object.entries(saved)) v === undefined ? delete process.env[k] : (process.env[k] = v)
      await new Promise((r) => srv.close(r))
    }
  }
  for (const h of fx.hostCases) assert.equal(classifierKeyEnv(h.backend, h.baseUrl), h.expectKeyEnv, `${h.backend} ${h.baseUrl}`)
})
