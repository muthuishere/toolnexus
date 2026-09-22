/**
 * Regression suite for consumer issues #86–#93 (ADRs 0023–0028, DECISIONS.md).
 *
 * Hermetic: a scripted mock LLM over an injected `fetch`, no network, no API key.
 * The harnesses are the ones from `spikes/issues/*`, with the prose removed.
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  createClient,
  createToolkit,
  createClassifier,
  defineTool,
  loadSkills,
  listSkills,
  createBuiltinTools,
  answerOutput,
  answerDeclined,
  pending,
  redactErrorBody,
  LlmHttpError,
  RUN_STATUSES,
  CLASSIFIER_BACKENDS,
  backendMismatch,
  agents,
} from "../dist/index.js"

const { agent, TASK_STATUSES, TASK_LIMITS, canonicalLimit, S, L, loopUnsupported, AgentRuntime } = agents as any

/**
 * The A18 invariant as a PREDICATE, held by the suite and not by the public API (A19b): a limit
 * stop (`incomplete`, `timeout`) names its limit; every other status carries none, and any value
 * present is from the closed A14 set. Ports copy this predicate, never an export of it.
 * Returns "" when the pair is coherent, else what is wrong with it.
 */
function limitInvariant(r: { status: string; limit?: string }): string {
  const isLimitStop = r.status === S.incomplete || r.status === S.timeout
  if (isLimitStop && !r.limit) return `status "${r.status}" is a limit stop but names no limit`
  if (!isLimitStop && r.limit) return `status "${r.status}" is not a limit stop but carries limit "${r.limit}"`
  if (r.limit && !TASK_LIMITS.includes(r.limit)) return `limit "${r.limit}" is not in the closed vocabulary`
  return ""
}

// ---------------------------------------------------------------------------
// harness
// ---------------------------------------------------------------------------

/** A fetch that replays scripted assistant messages and records every request body. */
function scripted(messages: any[]) {
  const sent: any[] = []
  let i = 0
  const fetchImpl = async (_url: string, init: any) => {
    sent.push(JSON.parse(init.body))
    const message = messages[Math.min(i, messages.length - 1)]
    i++
    return new Response(
      JSON.stringify({
        choices: [{ index: 0, message, finish_reason: message.tool_calls ? "tool_calls" : "stop" }],
        usage: { prompt_tokens: 3, completion_tokens: 2, total_tokens: 5 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  return { fetchImpl, sent }
}

const say = (content: string) => ({ role: "assistant", content })
const callTool = (name: string, args: Record<string, unknown>, id = "c1") => ({
  role: "assistant",
  tool_calls: [{ id, type: "function", function: { name, arguments: JSON.stringify(args) } }],
})

const baseOpts = (fetchImpl: any, over: Record<string, unknown> = {}) => ({
  baseUrl: "http://scripted.invalid",
  style: "openai" as const,
  model: "test-model",
  apiKey: "unused",
  fetch: fetchImpl,
  ...over,
})

// ===========================================================================
// D1 — #86: a toolkit-less completion (ADR 0023)
// ===========================================================================

test("#86 ask() with no ctx at all is a completion, not a TypeError", async () => {
  const { fetchImpl, sent } = scripted([say("an old silent pond")])
  const client = createClient(baseOpts(fetchImpl))
  const r = await client.ask("write me a haiku")
  assert.equal(r.text, "an old silent pond")
  assert.equal(r.status, "done")
  // The wire assertion: NO `tools` key and NO `tool_choice` key. Not an empty array.
  assert.equal("tools" in sent[0], false, "a toolkit-less body must carry no `tools` key")
  assert.equal("tool_choice" in sent[0], false, "a toolkit-less body must carry no `tool_choice` key")
})

test("#86 run()/stream()/conversation() take no toolkit either", async () => {
  const { fetchImpl, sent } = scripted([say("ok")])
  const client = createClient(baseOpts(fetchImpl))

  const r = await client.run("hi")
  assert.equal(r.text, "ok")

  const r2 = await client.ask("hi", {}) // the empty bag the issue reported crashing
  assert.equal(r2.text, "ok")

  const conv = client.conversation()
  assert.equal((await conv.send("hi")).text, "ok")

  for (const body of sent) {
    assert.equal("tools" in body, false)
    assert.equal("tool_choice" in body, false)
  }
})

test("#86 an EMPTY toolkit and NO toolkit are byte-identical on the wire", async () => {
  const a = scripted([say("ok")])
  const b = scripted([say("ok")])
  const tk = await createToolkit({ builtins: false })
  await createClient(baseOpts(a.fetchImpl)).ask("write me a haiku", { toolkit: tk })
  await createClient(baseOpts(b.fetchImpl)).ask("write me a haiku")
  assert.deepEqual(a.sent[0], b.sent[0])
  assert.deepEqual(Object.keys(a.sent[0]).sort(), ["messages", "model"])
  await tk.close()
})

test("#86 with a systemPrompt and no toolkit, the system message is the prompt alone", async () => {
  const { fetchImpl, sent } = scripted([say("ok")])
  await createClient(baseOpts(fetchImpl, { systemPrompt: "You are terse." })).run("hi")
  assert.equal(sent[0].messages[0].role, "system")
  assert.equal(sent[0].messages[0].content, "You are terse.")
})

test("#86 the anthropic style is toolkit-less too", async () => {
  const sent: any[] = []
  const fetchImpl = async (_u: string, init: any) => {
    sent.push(JSON.parse(init.body))
    return new Response(
      JSON.stringify({ content: [{ type: "text", text: "haiku" }], stop_reason: "end_turn", usage: { input_tokens: 1, output_tokens: 1 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const r = await createClient(baseOpts(fetchImpl, { style: "anthropic" })).ask("write me a haiku")
  assert.equal(r.text, "haiku")
  assert.equal("tools" in sent[0], false)
  assert.equal("tool_choice" in sent[0], false)
})

// ===========================================================================
// D2 — #87: the Loop honours the Spec (ADR 0024)
// ===========================================================================

test("#87 a Loop guardrail DENIES: the tool's execute is never entered", async () => {
  let entered = 0
  const danger = defineTool({
    name: "bash",
    description: "runs a command",
    inputSchema: { type: "object", properties: { cmd: { type: "string" } } },
    run: async () => {
      entered++
      return "ran"
    },
  })
  const { fetchImpl } = scripted([callTool("bash", { cmd: "rm -rf /" }), say("gave up")])
  const tk = await createToolkit({ builtins: false, extraTools: [danger] })
  const a = agent("guarded", {
    does: "does things",
    guardrails: [(ev: any) => (ev.name === "bash" ? "bash is denied by policy" : "allow")],
  })
  const out = await a.loop(baseOpts(fetchImpl), tk).run("do it")
  // Asserted on the SIDE EFFECT, never on the model's text: a policy that shows up only in
  // prose is a policy the model can talk its way around.
  assert.equal(entered, 0, "a denied tool must never be entered")
  assert.equal(out.status, "done")
  await tk.close()
})

test("#87 the CALLER's systemPrompt wins over the spec's soul", async () => {
  const { fetchImpl, sent } = scripted([say("ok")])
  const tk = await createToolkit({ builtins: false })
  const a = agent("souled", { does: "x", soul: "SOUL TEXT" })
  await a.loop(baseOpts(fetchImpl, { systemPrompt: "CALLER TEXT" }), tk).run("hi")
  assert.equal(sent[0].messages[0].content, "CALLER TEXT")

  // ...and with no caller prompt, the soul is the default.
  const two = scripted([say("ok")])
  await a.loop(baseOpts(two.fetchImpl), tk).run("hi")
  assert.equal(two.sent[0].messages[0].content, "SOUL TEXT")
  await tk.close()
})

test("#87 Spec.model and Spec.budget.maxTurns are Loop defaults", async () => {
  const { fetchImpl, sent } = scripted([callTool("noop", {}, "x1"), callTool("noop", {}, "x2"), say("late")])
  const noop = defineTool({
    name: "noop",
    description: "no-op",
    inputSchema: { type: "object", properties: {} },
    run: async () => "noop",
  })
  const tk = await createToolkit({ builtins: false, extraTools: [noop] })
  const a = agent("specced", { does: "x", model: "spec-model", budget: { maxTurns: 1 } })
  // "inherit" is how js spells "the caller did not choose a model" (ClientOptions.model is required).
  const out = await a.loop(baseOpts(fetchImpl, { model: "inherit" }), tk).run("go")
  assert.equal(sent[0].model, "spec-model", "the spec's model is the Loop default")
  assert.equal(out.status, "incomplete", "the spec's maxTurns bounds the Loop")
  assert.equal(out.turns, 1)
  await tk.close()
})

test("#87 loopUnsupported names what a Loop drive cannot honour", () => {
  assert.deepEqual(loopUnsupported({ does: "x" }), [], "absent ⇒ nothing lost")
  const spec = {
    does: "x",
    uses: { tools: [1] },
    team: [{}],
    waitFor: async () => ({ id: "1", ok: true }),
    onMetric: () => {},
  }
  // A FIXED canonical vocabulary, identical in all seven ports — not js's own field spelling.
  assert.deepEqual(loopUnsupported(spec), ["tools", "team", "waitFor", "onMetric"])
})

// ===========================================================================
// D3 — #88/#90: runtime legibility (ADR 0025)
// ===========================================================================

function delegationRuntime() {
  const mockFetch: any = async (_u: string, init: any) => {
    const body = JSON.parse(String(init.body))
    const toolMsgs = body.messages.filter((m: any) => m.role === "tool")
    const reply =
      body.model === "m-parent"
        ? toolMsgs.length === 0
          ? callTool("task", { agent: "child", prompt: "do the work" }, "t1")
          : say(`parent: ${toolMsgs[0].content}`)
        : say("child done")
    return new Response(
      JSON.stringify({
        choices: [{ index: 0, message: reply, finish_reason: reply.tool_calls ? "tool_calls" : "stop" }],
        usage: { prompt_tokens: 60, completion_tokens: 40, total_tokens: 100 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const registry = {
    parent: { name: "parent", does: "delegates", model: "m-parent", team: ["child"] },
    child: { name: "child", does: "works", model: "m-child" },
  }
  return new AgentRuntime({ fetch: mockFetch, registry })
}

test("#88 totalTokens is the cumulative TREE total on every status; ownTokens is the per-agent figure", async () => {
  const rt = delegationRuntime()
  const h = rt.spawn(rt.root, "parent")
  rt.wake(h, "go")
  const r = await rt.wait(h)
  assert.equal(r.status, "done")
  const child = h.children[0]
  assert.ok(child, "the parent delegated")
  // The assertion whose absence let fourteen doc sentences drift.
  assert.ok(r.totalTokens >= child.usageTotal, `parent ${r.totalTokens} >= child ${child.usageTotal}`)
  assert.equal(r.totalTokens, h.usageTotal)
  assert.equal(r.ownTokens, h.ownTokens)
  assert.ok(r.ownTokens < r.totalTokens, "own excludes the child's spend")
  assert.equal(r.totalTokens, r.ownTokens + child.usageTotal)
  await rt.close(rt.root)
})

test("#90 a limit stop NAMES the limit on TaskResult", async () => {
  const loopFetch: any = async () =>
    new Response(
      JSON.stringify({
        choices: [{ index: 0, message: callTool("noop", {}, "l1"), finish_reason: "tool_calls" }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  const noop = defineTool({
    name: "noop",
    description: "no-op",
    inputSchema: { type: "object", properties: {} },
    run: async () => "noop",
  })
  const rt = new AgentRuntime({
    fetch: loopFetch,
    registry: { looper: { name: "looper", does: "never finishes", model: "m", tools: [noop], budget: { maxTurns: 2 } } },
  })
  const h = rt.spawn(rt.root, "looper")
  rt.wake(h, "go")
  const r = await rt.wait(h)
  assert.equal(r.status, "incomplete")
  assert.equal(r.limit, "maxTurns", "a stop you cannot explain to a user is a bug, not a state")
  assert.ok(TASK_LIMITS.includes(r.limit), "and the value is from the CLOSED cross-port vocabulary")

  // A BUDGET stop names its exhausted pool too — that name used to be computed and discarded.
  const again = rt.wake(h, "again")
  assert.equal(again.ok, false)
  assert.equal(h.lastResult.limit, "maxTurns")
  await rt.close(rt.root)
})

test("#90 resume returns the resumed TaskResult", async () => {
  let asked = 0
  const suspendFetch: any = async (_u: string, init: any) => {
    const body = JSON.parse(String(init.body))
    const toolMsgs = body.messages.filter((m: any) => m.role === "tool")
    const approved = toolMsgs.some((m: any) => String(m.content).includes("secret-token"))
    const reply = approved ? say("final: secret-token") : callTool("check_secret", {}, `a${++asked}`)
    return new Response(
      JSON.stringify({
        choices: [{ index: 0, message: reply, finish_reason: reply.tool_calls ? "tool_calls" : "stop" }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const checkSecret = defineTool({
    name: "check_secret",
    description: "needs approval",
    inputSchema: { type: "object", properties: {} },
    run: async (_a: any, ctx: any) => (ctx?.answer?.ok ? "secret-token" : pending({ kind: "approval", prompt: "approve?" })),
  })
  const rt = new AgentRuntime({
    fetch: suspendFetch,
    registry: { asker: { name: "asker", does: "asks", model: "m", tools: [checkSecret] } },
  })
  const h = rt.spawn(rt.root, "asker")
  rt.wake(h, "go")
  const halted = await rt.wait(h)
  assert.equal(halted.status, "pending")
  assert.ok(halted.pending)

  const resumed = await rt.resume(answerOutput(halted.pending.id, "approved"))
  assert.ok(resumed, "resume returns the resumed result, not void")
  assert.equal(resumed.status, "done")
  assert.equal(resumed.text, "final: secret-token")
  assert.ok(resumed.totalTokens >= halted.totalTokens, "turns and usage grow, never reset")
  await rt.close(rt.root)
})

// ===========================================================================
// D4 — #89: the Answer payload contract (ADR 0026)
// ===========================================================================

test("#89 answerOutput builds the payload so the host never names the key", () => {
  assert.deepEqual(answerOutput("req-1", "staging"), { id: "req-1", ok: true, data: { output: "staging" } })
  assert.deepEqual(answerDeclined("req-1"), { id: "req-1", ok: false, reason: "declined" })
  assert.deepEqual(answerDeclined("req-1", "expired"), { id: "req-1", ok: false, reason: "expired" })
})

test("#89 a non-string output is an ERROR, never a silent degrade to \"\"", () => {
  assert.throws(() => answerOutput("req-1", { value: "staging" } as any), /output must be a string, got object/)
  assert.throws(() => answerOutput("req-1", 42 as any), /output must be a string, got number/)
  assert.throws(() => answerOutput("req-1", null as any), /output must be a string, got null/)
  assert.throws(() => answerOutput("", "x"), /id is required/)
})

// ===========================================================================
// D5 — #91/#92: what the library hands back when it fails (ADR 0027)
// ===========================================================================

test("#91 the classifier backend is a PAIRING, and a known mismatch fails at construction", () => {
  assert.deepEqual(Object.keys(CLASSIFIER_BACKENDS).sort(), ["openrouter", "typesafe"])
  const c = createClassifier({ backend: "openrouter" })
  assert.ok(c, "the preset sets baseUrl + model + apiKeyEnv as a unit")
  assert.throws(
    () => createClassifier({ baseUrl: "https://openrouter.ai/api/v1", model: "jev-latest" }),
    /is TypeSafe's spelling; on openrouter\.ai use "typesafe\/jev-1\.13"/,
    "the mixture the issue hit is refused before the wire, not 700ms later as a 400",
  )
  assert.equal(backendMismatch("https://api.typesafe.ai/v1", "jev-latest"), "", "the default pairing is fine")
  assert.equal(backendMismatch("https://openrouter.ai/api/v1", "typesafe/jev-1.13"), "")
})

test("#92 the two status vocabularies are named values, and they are DIFFERENT sets", () => {
  assert.deepEqual([...RUN_STATUSES], ["done", "pending", "incomplete"])
  assert.equal(TASK_STATUSES.includes("timeout"), true, "`timeout` is the AGENT vocabulary")
  assert.equal(RUN_STATUSES.includes("timeout" as any), false, "and never the client one")
  assert.ok(TASK_STATUSES.length > RUN_STATUSES.length)
})

test("#92 an account identifier is REDACTED from a provider error body (a cap is not redaction)", () => {
  // The spike's body is 96 bytes — well inside the 200-char cap, which is why both steps exist.
  const body = '{"error":{"message":"not a valid model ID","code":400},"user_id":"user_2FAKEfakefakefake"}'
  assert.ok(body.length < 200)
  const red = redactErrorBody(body)
  assert.equal(red.includes("user_2FAKE"), false, "the account id must not survive")
  assert.ok(red.includes('"user_id":"«redacted»"'), "the SHAPE survives, the value does not")
  assert.ok(red.includes("not a valid model ID"), "the actual cause is untouched")
  for (const key of ["account_id", "org_id", "organization"]) {
    assert.ok(redactErrorBody(`{"${key}":"org_SECRET"}`).includes("«redacted»"), key)
    assert.equal(redactErrorBody(`{"${key}":"org_SECRET"}`).includes("org_SECRET"), false, key)
  }
  // The cap is MESSAGE-only (addendum A5): the typed field carries the whole redacted body.
  assert.equal(redactErrorBody("x".repeat(500)).length, 500)
  assert.equal(new LlmHttpError(400, "x".repeat(500)).body.length, 500)
  assert.ok(new LlmHttpError(400, "x".repeat(500)).message.length < 260, "the message is capped")
})

test("#92 a provider failure is a VALUE: status / body / retryAfter, 401 bodies blanked", async () => {
  const fail = (status: number, body: string, headers: Record<string, string> = {}) =>
    async () => new Response(body, { status, headers })

  const client = createClient(baseOpts(fail(400, '{"error":"bad","user_id":"user_2FAKE"}'), { retries: 0 }))
  const e = await client.run("hi").then(() => null, (x: unknown) => x)
  assert.ok(e instanceof LlmHttpError, `expected a typed error, got ${e}`)
  assert.equal((e as LlmHttpError).status, 400)
  assert.equal((e as LlmHttpError).body.includes("user_2FAKE"), false)
  assert.equal((e as LlmHttpError).message.includes("user_2FAKE"), false, "and nothing leaks via the message")

  const auth = createClient(baseOpts(fail(401, 'Authorization: Bearer sk-LEAKED'), { retries: 0 }))
  const e2 = (await auth.run("hi").then(() => null, (x: unknown) => x)) as LlmHttpError
  assert.equal(e2.status, 401)
  assert.equal(e2.body, "", "a 401 body routinely echoes the credential that was sent")
  assert.equal(e2.message.includes("sk-LEAKED"), false)

  const throttled = createClient(baseOpts(fail(429, "slow down", { "retry-after": "3" }), { retries: 0 }))
  const e3 = (await throttled.run("hi").then(() => null, (x: unknown) => x)) as LlmHttpError
  assert.equal(e3.status, 429)
  assert.equal(e3.retryAfter, "3", "retryAfter is a field, not something to parse out of a sentence")
})

test("#92 MUST NOT REGRESS: a 4xx fails fast — exactly one attempt under retries:4", async () => {
  let hits = 0
  const fetchImpl: any = async () => {
    hits++
    return new Response('{"error":"bad request"}', { status: 400 })
  }
  const client = createClient(baseOpts(fetchImpl, { retries: 4, retryBaseMs: 1 }))
  await assert.rejects(() => client.run("hi"), /LLM 400/)
  assert.equal(hits, 1, "the retryable set is ENUMERATED {429,500,502,503,504,529}, never `any 5xx`")

  // ...and a 503 IS in that set, so the budget is really spent.
  let hits5 = 0
  const five: any = async () => {
    hits5++
    return new Response("upstream", { status: 503 })
  }
  await assert.rejects(() => createClient(baseOpts(five, { retries: 2, retryBaseMs: 1 })).run("hi"), /LLM 503/)
  assert.equal(hits5, 3)
})

test("#92 MUST NOT REGRESS: ClassifierUsage.cost is OPTIONAL — absent is not zero", async () => {
  const noCost: any = async () =>
    new Response(JSON.stringify({ model: "jev-1.13.0", answers: { q: { type: "noul", noul: 0.5 } }, usage: { input_tokens: 1, output_tokens: 1 } }), {
      status: 200,
      headers: { "content-type": "application/json" },
    })
  const c = createClassifier({ fetch: noCost, apiKeyEnv: "TN_TEST_UNSET_KEY" })
  const d = await c.evaluate({}, { q: { kind: "noul", instructions: "is it?" } as any })
  assert.equal(d.usage.cost, undefined, "a backend that does not say cost must not read as $0.00")
})

test("#92 the run timeout NAMES the budget it blew", async () => {
  const never: any = (_u: string, init: any) =>
    new Promise((_res, rej) => {
      init.signal?.addEventListener("abort", () => rej(init.signal.reason), { once: true })
    })
  const client = createClient(baseOpts(never, { timeoutMs: 1 }))
  await assert.rejects(() => client.run("hi"), /run timeout after 1ms/)
})

// ===========================================================================
// D6 — #93: a skill the writing tool accepts (ADR 0028)
// ===========================================================================

function skillsFixture(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-issue93-"))
  const write = (name: string, body: string) => {
    fs.mkdirSync(path.join(dir, name), { recursive: true })
    fs.writeFileSync(path.join(dir, name, "SKILL.md"), body)
  }
  // 1. tab indentation — YAML refuses it; Claude Code writes it and loads it.
  write("broken-tab", "---\nname: broken-tab\ndescription: Uses a tab\n\tbad: indented\n---\nbody\n")
  // 2. the `Trigger on:` sentence — a plain scalar with a second colon.
  write("trigger-on", "---\nname: trigger-on\ndescription: Does a thing. Trigger on: do it, make it.\n---\nbody\n")
  // 3. an unterminated flow sequence — the `yaml` package RECOVERS it into a sequence, so the
  //    fallback never runs. The gate is that it keeps its name and gains NO invented description.
  write("broken-flow", "---\nname: broken-flow\ndescription: [unterminated\n---\nbody\n")
  // 3b. a tab the rescue cannot rescue: YAML refuses, and `name` is indented so first-wins finds
  //     nothing at column 0. Genuinely malformed ⇒ still skipped, with the native detail.
  write("broken-nameless", "---\n\tname: broken-nameless\ndescription: nope\n---\nbody\n")
  // 4. a block literal — the YAML path must own this, untouched.
  write("block-literal", "---\nname: block-literal\ndescription: |\n  line one\n  line two\n---\nbody\n")
  // 5. a valid file with no name.
  write("no-name", "---\ndescription: nameless\n---\nbody\n")
  return dir
}

test("#93 lenient frontmatter is INVERTED: YAML first, line-wise only on what YAML refused", () => {
  const dir = skillsFixture()
  try {
    const inv = listSkills(dir)
    const by = Object.fromEntries(inv.skills.map((s: any) => [s.name, s]))

    // Rescued — with the description Claude Code displays, not an invented one.
    assert.ok(by["broken-tab"], "a tab-indented SKILL.md is no longer invisible")
    assert.equal(by["broken-tab"].description, "Uses a tab")

    // The `Trigger on:` sentence parses as YAML today only if the value is quoted; unquoted it
    // is a refused mapping, and the rescue returns the WHOLE sentence, as the writing tool shows.
    assert.ok(by["trigger-on"], "the Trigger-on sentence loads")
    assert.equal(by["trigger-on"].description, "Does a thing. Trigger on: do it, make it.")

    // Block scalars keep their exact multi-line value — the YAML path never ran the fallback.
    assert.equal(by["block-literal"].description, "line one\nline two")

    // The fallback INVENTS nothing: a value it cannot read stays absent.
    assert.ok(by["broken-flow"], "yaml recovers this one, so it keeps its name")
    assert.equal(by["broken-flow"].description, undefined, "and gains NO invented description")

    // A frontmatter the rescue genuinely cannot rescue is STILL refused.
    assert.equal(by["broken-nameless"], undefined)
    const bad = inv.skipped.find((s: any) => s.location.includes("broken-nameless"))
    assert.ok(bad, "broken-nameless is still skipped")
    assert.equal(bad.reason, "malformed-frontmatter", "the typed reason stays byte-identical")
    assert.ok(bad.detail && bad.detail.length > 0, "and now carries the native parser error")

    // A valid header with no name keeps its own, already-typed reason.
    const nameless = inv.skipped.find((s: any) => s.location.includes("no-name"))
    assert.equal(nameless.reason, "missing-name")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#93 loadSkills EXPOSES the skips — a host must not need listSkills to learn what vanished", () => {
  const dir = skillsFixture()
  try {
    const src = loadSkills(dir)
    assert.ok(Array.isArray(src.skipped), "loadSkills reports what it could not load")
    assert.equal(src.skipped.length, 2, "broken-nameless + no-name")
    assert.deepEqual(src.skipped.map((s: any) => s.reason).sort(), ["malformed-frontmatter", "missing-name"])
    assert.ok(src.skills["broken-tab"], "and the rescued ones really load")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#93 a corpus with no malformed files is UNCHANGED (the additive property)", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-issue93-clean-"))
  try {
    fs.mkdirSync(path.join(dir, "fine"))
    fs.writeFileSync(path.join(dir, "fine", "SKILL.md"), "---\nname: fine\ndescription: >\n  folded one\n  folded two\n---\nbody\n")
    const inv = listSkills(dir)
    assert.equal(inv.skipped.length, 0)
    assert.equal(inv.skills[0].description, "folded one folded two", "the folded scalar is untouched")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#93 discovery order is pinned: the TOP-LEVEL copy wins a duplicate name (js's winner FLIPS)", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-issue93-order-"))
  try {
    const write = (rel: string, body: string) => {
      fs.mkdirSync(path.join(dir, rel), { recursive: true })
      fs.writeFileSync(path.join(dir, rel, "SKILL.md"), body)
    }
    // Both shapes, exactly as `~/.claude/skills` carries them for docx/pdf/pptx.
    write("docx", "---\nname: docx\ndescription: top-level\n---\nTOP LEVEL BODY\n")
    write("synced/6636deadbeef/docx", "---\nname: docx\ndescription: synced copy\n---\nSYNCED BODY\n")

    const inv = listSkills(dir)
    const docx = inv.skills.find((s: any) => s.name === "docx")
    // Code-point order on the path relative to the root: "docx/…" < "synced/…", so the
    // top-level copy is discovered first and first-wins keeps it. js previously kept the
    // `synced/<hash>` copy — same name, DIFFERENT file and different `content`.
    assert.equal(docx.content.trim(), "TOP LEVEL BODY")
    assert.equal(docx.description, "top-level")
    assert.ok(docx.location.endsWith(path.join("docx", "SKILL.md")))
    assert.equal(docx.location.includes("synced"), false)

    const dup = inv.skipped.find((s: any) => s.reason === "duplicate-name")
    assert.ok(dup.location.includes("synced"), "the synced copy is the one reported as duplicate")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#90 the `limit` vocabulary is CLOSED and canonical — no internal pool name may leak (A14)", () => {
  // Spelled exactly as SPEC's `Budget` spells the fields, plus the two non-budget stops.
  assert.deepEqual(
    [...TASK_LIMITS],
    ["maxTurns", "maxTokens", "maxToolCalls", "maxWallMs", "maxChildren", "maxConcurrent", "maxDepth", "completion", "timeout"],
  )
  // Every dimension js can actually stop on maps onto it — including the wall clock.
  for (const v of ["maxTurns", "maxTokens", "maxToolCalls", "maxWallMs", "completion", "timeout"]) {
    assert.equal(canonicalLimit(v), v, v)
  }
  // An internal spelling is mapped away, never passed through.
  for (const leak of ["tokens", "toolCalls", "wallMs", "maxWall", undefined]) {
    assert.equal(canonicalLimit(leak as any), undefined, String(leak))
  }
})

test("#90 a wall-clock budget stop reports maxWallMs, and a wait deadline reports timeout", async () => {
  let now = 0
  const clock = { now: () => now, setTimeout: (f: any, ms: number) => setTimeout(f, ms), clearTimeout: (t: any) => clearTimeout(t) }
  const fetchImpl: any = async () =>
    new Response(
      JSON.stringify({
        choices: [{ index: 0, message: say("ok"), finish_reason: "stop" }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  const rt = new AgentRuntime({
    fetch: fetchImpl,
    clock,
    registry: { slowpoke: { name: "slowpoke", does: "x", model: "m", budget: { maxWallMs: 10 } } },
  })
  const h = rt.spawn(rt.root, "slowpoke")
  now = 100 // the wall clock passed the handle's deadline
  const woke = rt.wake(h, "go")
  assert.equal(woke.ok, false)
  assert.equal(h.lastResult.limit, "maxWallMs", "the wall-clock dimension is spelled as Budget spells it")
  assert.equal(h.lastResult.status, "incomplete")
  await rt.close(rt.root)
})

test("#93 A15: depth beats code point — a top-level `xlsx` wins over `synced/<uuid>/xlsx`", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-issue93-depth-"))
  try {
    const write = (rel: string, body: string) => {
      fs.mkdirSync(path.join(dir, rel), { recursive: true })
      fs.writeFileSync(path.join(dir, rel, "SKILL.md"), body)
    }
    // `xlsx` sorts AFTER `synced`, so a pure code-point sort hands the win to the NESTED copy.
    // A docx-only fixture passes under both rules, which is why six ports missed this.
    write("xlsx", "---\nname: xlsx\ndescription: top-level\n---\nTOP LEVEL BODY\n")
    write("synced/6636deadbeef/xlsx", "---\nname: xlsx\ndescription: synced copy\n---\nSYNCED BODY\n")
    // ...and a name BEFORE `synced`, so both directions are pinned by the same fixture.
    write("docx", "---\nname: docx\ndescription: top-level\n---\nTOP LEVEL BODY\n")
    write("synced/6636deadbeef/docx", "---\nname: docx\ndescription: synced copy\n---\nSYNCED BODY\n")

    const inv = listSkills(dir)
    for (const name of ["xlsx", "docx"]) {
      const s = inv.skills.find((x: any) => x.name === name)
      assert.equal(s.content.trim(), "TOP LEVEL BODY", `${name}: a shallower path must beat a nested copy`)
      assert.equal(s.description, "top-level", name)
      assert.equal(s.location.includes("synced"), false, name)
    }
    assert.equal(inv.skipped.filter((s: any) => s.reason === "duplicate-name").length, 2)
    for (const d of inv.skipped.filter((s: any) => s.reason === "duplicate-name")) {
      assert.ok(d.location.includes("synced"), "the nested copies are the duplicates")
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// A18 — the INVARIANT, not the instance: a limit stop names its limit;
//       a non-limit stop leaves it empty. Every value from its own closed set.
// ---------------------------------------------------------------------------

/** Assert the pair on ONE result, plus membership in both closed vocabularies. */
function assertLimitInvariant(r: any, what: string) {
  assert.ok(TASK_STATUSES.includes(r.status), `${what}: status "${r.status}" is outside the 7-value task set`)
  if (r.limit !== undefined) {
    assert.ok(TASK_LIMITS.includes(r.limit), `${what}: limit "${r.limit}" is outside the 9-value A14 set`)
  }
  assert.equal(limitInvariant(r), "", `${what}: ${limitInvariant(r)}`)
}

test("#90 A18 INVARIANT: a limit stop names its limit; a non-limit stop leaves it empty", async () => {
  assert.equal(S.timeout, "timeout")
  assert.equal(L.maxWallMs, "maxWallMs")

  const reply = (m: any) =>
    new Response(
      JSON.stringify({
        choices: [{ index: 0, message: m, finish_reason: m.tool_calls ? "tool_calls" : "stop" }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  const noop = defineTool({
    name: "noop",
    description: "no-op",
    inputSchema: { type: "object", properties: {} },
    run: async () => "noop",
  })
  const looping: any = async () => reply(callTool("noop", {}, "l1"))
  const finishing: any = async () => reply(say("all done"))

  const seen: Array<[string, any]> = []

  // 1. done — a NON-limit stop. It must carry no `limit` at all.
  {
    const rt = new AgentRuntime({ fetch: finishing, registry: { w: { name: "w", does: "x", model: "m" } } })
    const h = rt.spawn(rt.root, "w")
    rt.wake(h, "go")
    const r = await rt.wait(h)
    assert.equal(r.status, S.done)
    assert.equal(r.limit, undefined, "a `done` result must not name a limit")
    seen.push(["done", r])
    await rt.close(rt.root)
  }

  // 2. budget `incomplete` — a LIMIT stop. It must name which one.
  {
    const rt = new AgentRuntime({
      fetch: looping,
      registry: { w: { name: "w", does: "x", model: "m", tools: [noop], budget: { maxTurns: 1 } } },
    })
    const h = rt.spawn(rt.root, "w")
    rt.wake(h, "go")
    const r = await rt.wait(h)
    assert.equal(r.status, S.incomplete)
    assert.equal(r.limit, L.maxTurns)
    seen.push(["incomplete/turns", r])

    // the token-pool dimension of the same stop
    const rt2 = new AgentRuntime({
      fetch: looping,
      registry: { w: { name: "w", does: "x", model: "m", tools: [noop], budget: { maxTokens: 1 } } },
    })
    const h2 = rt2.spawn(rt2.root, "w")
    rt2.wake(h2, "go")
    await rt2.wait(h2)
    const refused = rt2.wake(h2, "again")
    assert.equal(refused.ok, false)
    assert.equal(h2.lastResult.status, S.incomplete)
    assert.equal(h2.lastResult.limit, L.maxTokens, "the pool name is MAPPED, never leaked as `tokens`")
    seen.push(["incomplete/tokens", h2.lastResult])
    await rt.close(rt.root)
    await rt2.close(rt2.root)
  }

  // 3. closed — a NON-limit stop, driven EXPLICITLY on both construction sites.
  //    (Golang's warning: wait-after-close returns the SETTLED last result, so a handle that
  //    already stopped for a budget would assert the wrong thing here. These never ran.)
  {
    const rt = new AgentRuntime({ fetch: finishing, registry: { w: { name: "w", does: "x", model: "m" } } })
    // 3a. a waiter registered BEFORE the close receives the close's own final result.
    const early = rt.spawn(rt.root, "w")
    const waiting = rt.wait(early)
    await rt.close(early)
    const r1 = await waiting
    assert.equal(r1.status, S.closed)
    assert.equal(r1.limit, undefined, "a `closed` result must not name a limit")
    seen.push(["closed/waiter", r1])

    // 3b. a wait AFTER the close, on a handle that never ran, so there is no settled result.
    const late = rt.spawn(rt.root, "w")
    await rt.close(late)
    const r2 = await rt.wait(late)
    assert.equal(r2.status, S.closed)
    assert.equal(r2.limit, undefined)
    seen.push(["closed/post", r2])
    await rt.close(rt.root)
  }

  // 4. pending — a NON-limit stop (a suspension is not a limit).
  {
    const asker = defineTool({
      name: "ask_human",
      description: "asks",
      inputSchema: { type: "object", properties: {} },
      run: async (_a: any, ctx: any) => (ctx?.answer?.ok ? "answered" : pending({ kind: "input", prompt: "which env?" })),
    })
    let asked = 0
    const suspend: any = async () => reply(callTool("ask_human", {}, `q${++asked}`))
    const rt = new AgentRuntime({ fetch: suspend, registry: { w: { name: "w", does: "x", model: "m", tools: [asker] } } })
    const h = rt.spawn(rt.root, "w")
    rt.wake(h, "go")
    const r = await rt.wait(h)
    assert.equal(r.status, S.pending)
    assert.equal(r.limit, undefined, "a suspension is not a limit stop")
    seen.push(["pending", r])
    await rt.close(rt.root, { force: true })
  }

  // 5. wait-deadline `timeout` — a LIMIT stop, and the instance that shipped broken in 3 ports.
  {
    const hang: any = (_u: string, init: any) =>
      new Promise((_res, rej) => init.signal?.addEventListener("abort", () => rej(init.signal.reason), { once: true }))
    const rt = new AgentRuntime({ fetch: hang, registry: { w: { name: "w", does: "x", model: "m" } } })
    const h = rt.spawn(rt.root, "w")
    rt.wake(h, "go")
    const r = await rt.wait(h, { timeoutMs: 5 })
    assert.equal(r.status, S.timeout)
    assert.equal(r.limit, L.timeout, "status and limit must agree — they used to contradict each other")
    seen.push(["timeout", r])
    await rt.close(rt.root, { force: true })
  }

  // 6. error — a NON-limit stop (a spawn refusal).
  {
    const rt = new AgentRuntime({ fetch: finishing, registry: { w: { name: "w", does: "x", model: "m" } } })
    const bad = rt.spawn(rt.root, "nope")
    assert.ok(bad.error, "unknown agent")
    const r = await agent("solo", { does: "x", team: [] }).run("go", { fetch: finishing })
    seen.push(["agent.run", r])
    await rt.close(rt.root)
  }

  // The invariant over every result the drives produced — this is the assertion that would have
  // caught BOTH the reported wait-deadline instance and golang's latent forwarded-limit one.
  assert.ok(seen.length >= 8, `expected every status branch to be driven, got ${seen.length}`)
  for (const [what, r] of seen) assertLimitInvariant(r, what)
  const statuses = new Set(seen.map(([, r]) => r.status))
  for (const st of ["done", "incomplete", "closed", "pending", "timeout"]) {
    assert.ok(statuses.has(st), `the invariant must be driven over a ${st} result`)
  }
})

test("#93 A22: the §0.10 skills prompt sorts by CODE POINT, never by locale collation", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-a22-"))
  try {
    const write = (name: string, desc: string) => {
      fs.mkdirSync(path.join(dir, name.replace(/[^a-z0-9]/gi, "_")), { recursive: true })
      fs.writeFileSync(
        path.join(dir, name.replace(/[^a-z0-9]/gi, "_"), "SKILL.md"),
        `---\nname: ${name}\ndescription: ${desc}\n---\nbody\n`,
      )
    }
    // Names a LOCALE collation and CODE POINT rank differently. In en-US, "café" collates
    // between "cab" and "cz" (the accent is a tertiary difference); by code point, U+00E9
    // sorts AFTER every ASCII letter, so "café" comes last. Same for the uppercase pair:
    // locale collation is case-insensitive at the primary level, code point puts "Zebra"
    // (U+005A) before every lowercase name (U+0061+).
    write("czech", "third by code point")
    write("café", "last by code point")
    write("Zebra", "first by code point")
    write("apple", "second by code point")

    const prompt = loadSkills(dir).prompt()
    const order = prompt
      .split("\n")
      .filter((l) => l.startsWith("- **"))
      .map((l) => l.slice(4, l.indexOf("**:")))
    assert.deepEqual(order, ["Zebra", "apple", "café", "czech"], "code point: uppercase first, U+00E9 last")
    // The locale answer — which is what shipped, and which varies with the machine's ICU data.
    assert.notDeepEqual(order, [...order].sort((a, b) => a.localeCompare(b)), "and it is NOT the locale order")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

/** The paths listed in a `<skill_files>` block, relative to the skill dir. */
function filesIn(block: string, root: string): string[] {
  return [...block.matchAll(/<file>([\s\S]*?)<\/file>/g)].map((m) => path.relative(root, m[1].trim()))
}

test("#93 A22/A25/A27: the <skill_files> sample — plain code point over the RELATIVE path, capped AFTER", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-a22-files-"))
  try {
    const root = path.join(dir, "demo")
    fs.mkdirSync(path.join(root, "alpha"), { recursive: true })
    fs.mkdirSync(path.join(root, "b-nested"), { recursive: true })
    fs.writeFileSync(path.join(root, "SKILL.md"), "---\nname: demo\ndescription: d\n---\nbody\n")

    // The fixture DISCRIMINATES every way the rule could be wrong (A27) — written in an order
    // that is none of the three, so raw readdir order cannot accidentally pass either:
    //
    //  rule 1  bare name vs relative path: `b-nested/zz-a.txt` follows `alpha.txt` by relative
    //          path, but sorts LAST by bare name (`zz-a.txt`), after `z.txt`.
    //  rule 2  flat relative path vs per-level walk: `alpha-b.txt` beats `alpha/f.txt` because
    //          `-` is 0x2D and `/` is 0x2F — a per-level walk descends `alpha/` first instead.
    //  rule 3  the cap: `z.txt` and `b-nested/zz-a.txt` must be ABSENT under a cap of 3, not
    //          merely late. Capping DURING the walk would reach the root's files first and
    //          include `z.txt`.
    //  locale  `ßeta.txt` collates as "ss" (before `z`) but is 0xDF by code point (after it),
    //          and has no NFD decomposition, so macOS normalisation cannot make it vacuous.
    for (const f of ["z.txt", "\u00dfeta.txt", "alpha.txt", "alpha-b.txt"]) fs.writeFileSync(path.join(root, f), "x")
    fs.writeFileSync(path.join(root, "alpha", "f.txt"), "x")
    fs.writeFileSync(path.join(root, "b-nested", "zz-a.txt"), "x")

    const EXPECTED = [
      "alpha-b.txt",
      "alpha.txt",
      path.join("alpha", "f.txt"),
      path.join("b-nested", "zz-a.txt"),
      "z.txt",
      "\u00dfeta.txt",
    ]
    const src = loadSkills(dir)
    const out = await src.tool.execute({ name: "demo" })
    const names = filesIn(out.output.slice(out.output.indexOf("<skill_files>"), out.output.indexOf("</skill_files>")), root)
    assert.deepEqual(names, EXPECTED)

    // ...and it is none of the three wrong rules, stated as assertions so a regression to any of
    // them fails here rather than silently.
    const byName = [...EXPECTED].sort((a, b) => (path.basename(a) < path.basename(b) ? -1 : 1))
    const byDepth = [...EXPECTED].sort((a, b) => a.split(path.sep).length - b.split(path.sep).length || (a < b ? -1 : 1))
    assert.notDeepEqual(names, byName, "not bare-name order")
    assert.notDeepEqual(names, byDepth, "not the DISCOVERY (depth-first) comparator")
    assert.notDeepEqual(names, [...EXPECTED].sort((a, b) => a.localeCompare(b)), "not locale order")

    // rule 3: the cap takes a PREFIX of that order, and the late files are ABSENT.
    const capped = loadSkills({ dirs: dir, sampleLimit: 3 })
    const out2 = await capped.tool.execute({ name: "demo" })
    const names2 = filesIn(out2.output.slice(out2.output.indexOf("<skill_files>"), out2.output.indexOf("</skill_files>")), root)
    assert.deepEqual(names2, EXPECTED.slice(0, 3))
    assert.equal(names2.includes("z.txt"), false, "a late file must be ABSENT, not merely late")
    assert.equal(names2.includes(path.join("b-nested", "zz-a.txt")), false)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

/**
 * The PRE-FIX walk, reproduced (A27d): a LIFO stack over each directory's entries, i.e. a
 * directory's own files before it descends. Used to prove a cap fixture is not vacuous ON THIS
 * FILESYSTEM rather than on the machine the fixture was designed on — a hand probe is correct
 * where it ran, a self-proving fixture says so when it stops being correct.
 */
function walkOrder(root: string): string[] {
  const out: string[] = []
  const stack = [root]
  while (stack.length) {
    const cur = stack.pop()!
    for (const e of fs.readdirSync(cur, { withFileTypes: true })) {
      const full = path.join(cur, e.name)
      if (e.isDirectory()) stack.push(full)
      else if (e.isFile()) out.push(path.relative(root, full).split(path.sep).join("/"))
    }
  }
  return out
}

/** Fail loudly when first-N-by-walk and first-N-by-sort would select the SAME SET — the fixture
 *  would then pass against the pre-fix implementation and assert nothing (A27c/A27d). */
function assertCapFixtureDiscriminates(root: string, n: number, expectedSorted: string[]) {
  const byWalk = new Set(walkOrder(root).slice(0, n))
  const bySort = new Set(expectedSorted.slice(0, n))
  const same = byWalk.size === bySort.size && [...byWalk].every((x) => bySort.has(x))
  assert.equal(
    same,
    false,
    `fixture is vacuous on this filesystem: first-${n} by walk ${JSON.stringify([...byWalk])} ` +
      `equals first-${n} by sort ${JSON.stringify([...bySort])}`,
  )
}

test("#93 A24/A27c: `glob` — the cap selects a different SET by walk order than by sort order", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-a24-glob-"))
  try {
    // PROBED against js's real walk: `walkFiles` is a LIFO stack over name-sorted entries, so it
    // yields a directory's own FILES first and descends afterwards. The fixture therefore fills
    // the cap from the root before the walk ever reaches `a-dir/zz.txt`, which sorts FIRST.
    //   by WALK : m.txt, n.txt, z.txt, a-dir/zz.txt   -> cap 2 = {m.txt, n.txt}
    //   by SORT : a-dir/zz.txt, m.txt, n.txt, z.txt   -> cap 2 = {a-dir/zz.txt, m.txt}
    // Different SETS, not merely different orders — otherwise the old code passes by accident.
    fs.mkdirSync(path.join(dir, "a-dir"), { recursive: true })
    for (const f of ["m.txt", "n.txt", "z.txt", "\u00dfeta.txt"]) fs.writeFileSync(path.join(dir, f), "x")
    fs.writeFileSync(path.join(dir, "a-dir", "zz.txt"), "x")

    const glob = createBuiltinTools().find((t: any) => t.name === "glob")!
    const all = await glob.execute({ pattern: "**/*.txt", path: dir })
    assert.deepEqual(all.output.split("\n"), ["a-dir/zz.txt", "m.txt", "n.txt", "z.txt", "\u00dfeta.txt"])
    assert.equal(all.output.includes("\\"), false, "A28: `/` on every platform, and it is the sorted string")

    assertCapFixtureDiscriminates(dir, 2, ["a-dir/zz.txt", "m.txt", "n.txt", "z.txt", "\u00dfeta.txt"])
    const capped = await glob.execute({ pattern: "**/*.txt", path: dir, limit: 2 })
    assert.deepEqual(capped.output.split("\n"), ["a-dir/zz.txt", "m.txt"])
    assert.equal(capped.output.includes("n.txt"), false, "the walk's first-2 must NOT be the answer")
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#93 A24/A27c/A28: `grep` — sorted by path then LINE NUMERICALLY, capped AFTER", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-a24-grep-"))
  try {
    // Same probed shape: the root's matches fill a cap of 2 before the walk reaches
    // `a-nested/zz-a.txt`, which sorts FIRST. By walk the cap selects m.txt's two hits; by sort
    // it selects a-nested's two. Different SETS.
    fs.mkdirSync(path.join(dir, "a-nested"), { recursive: true })
    // Lines 2 and 10: sorting the RENDERED "path:line:text" as one string puts :10 before :2.
    fs.writeFileSync(path.join(dir, "m.txt"), ["no", "needle two", "no", "no", "no", "no", "no", "no", "no", "needle ten"].join("\n"))
    fs.writeFileSync(path.join(dir, "n.txt"), "needle n\n")
    fs.writeFileSync(path.join(dir, "a-nested", "zz-a.txt"), "needle first\nneedle second\n")

    const grep = createBuiltinTools().find((t: any) => t.name === "grep")!
    const all = await grep.execute({ pattern: "needle", path: dir })
    assert.deepEqual(all.output.split("\n"), [
      "a-nested/zz-a.txt:1:needle first",
      "a-nested/zz-a.txt:2:needle second",
      "m.txt:2:needle two",
      "m.txt:10:needle ten", // NUMERIC, not lexical — a string sort puts :10 before :2
      "n.txt:1:needle n",
    ])
    assert.equal(all.output.includes("\\"), false, "A28: `/` on every platform, and it is the sorted string")

    // CONTENT, not cosmetics: the cap takes the SORTED prefix, so the walk's first hits are ABSENT.
    // Over FILES: the walk reaches m.txt before a-nested/zz-a.txt, so its hits would fill a cap
    // of 2 first. Proven here rather than assumed from a probe.
    assertCapFixtureDiscriminates(dir, 1, ["a-nested/zz-a.txt", "m.txt", "n.txt"])
    const capped = await grep.execute({ pattern: "needle", path: dir, limit: 2 })
    assert.deepEqual(capped.output.split("\n"), ["a-nested/zz-a.txt:1:needle first", "a-nested/zz-a.txt:2:needle second"])
    assert.equal(capped.output.includes("m.txt"), false, "the walk's first-2 must NOT be the answer")
    assert.equal(capped.output.includes("n.txt"), false)
  } finally {
    fs.rmSync(dir, { recursive: true, force: true })
  }
})

test("#93 D6.7d: the 17-file frontmatter fixture table, compared as STRINGS not verdicts", () => {
  // The shared arbiter every port reproduces row for row. Seven ports AGREEING is a different
  // claim from the table being able to tell a wrong implementation from a right one, so this
  // asserts each DESCRIPTION's exact value, not merely accept-vs-skip: a verdict-only comparison
  // survives a parser that keeps a YAML comment tail, and cannot be the arbiter it is used as.
  const fixtures = path.resolve(fileURLToPath(import.meta.url), "../../../spikes/issues/93/fixtures")
  assert.ok(fs.existsSync(fixtures), `the shared fixture corpus is missing: ${fixtures}`)

  const inv = listSkills(fixtures)
  const got = Object.fromEntries(inv.skills.map((s: any) => [s.name, s.description]))

  assert.deepEqual(got, {
    anchors: "A skill using a YAML anchor and alias.",
    "block-folded": "A folded description that runs across two source lines.",
    "block-literal": "First line of the description.\nSecond line, with a colon: still fine inside a block scalar.",
    // YAML refuses the unterminated flow sequence's VALUE but recovers the mapping: the name
    // survives, and no description is INVENTED for it.
    "broken-flow": undefined,
    // Rescued by the line-wise fallback — YAML refuses a tab, the writing tool does not.
    "broken-tab": "tab-indented continuation",
    "colon-space": "Work out billable hours from git commits and session logs. Trigger on: update the timesheet, do my timesheet, how many hours did I work.",
    "colon-space-quoted": "Work out billable hours. Trigger on: update the timesheet, do my timesheet.",
    "colon-space-single": "Work out billable hours. Trigger on: update the timesheet.",
    // THE MUTATION ROW. ` #` opens a YAML comment, so the tail is NOT part of the value. A
    // parser that reads `key: rest-of-line` keeps "#stockloop and report. Trigger on: tag it."
    // and is wrong — which only a string comparison can see.
    "hash-inline": "Tag things with",
    "list-block": "A skill with a block-sequence key.",
    "list-value": "A skill that also declares a list-valued key.",
    "nested-map": "A skill with a nested mapping key.",
    plain: "An ordinary skill with an ordinary one-line description.",
    "url-colon": "Fetch pages from https://example.com/docs and summarise them.",
  })

  assert.deepEqual(
    inv.skipped.map((s: any) => [path.basename(path.dirname(s.location)), s.reason]).sort(),
    [["broken-unclosed", "missing-name"], ["no-frontmatter", "missing-name"], ["no-name", "missing-name"]],
  )
  assert.equal(inv.skills.length, 14)
  assert.equal(inv.skipped.length, 3)
})
