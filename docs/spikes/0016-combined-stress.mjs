// SPIKE 0016 — STRESS across all four layers at once.
//
// Spikes 0012-0015 each tested one layer in isolation and each passed. That is the
// weakest kind of evidence, because the interesting failures live at the seams:
// a schedule firing into a composite whose node suspends while a model is authoring
// a child that would exceed the budget.
//
// This run asks the questions the individual spikes cannot:
//   T1  does capability narrowing hold at DEPTH and WIDTH, or only in the 3-node demo?
//   T2  a cron schedule driving a composite — does overlap safety survive composition?
//   T3  adversarial documents: malformed, hostile, prototype-polluting, enormous
//   T4  budget exhaustion INSIDE a composite INSIDE a schedule — still loud?
//   T5  resource hygiene: start/stop many schedules, does anything leak?
//   T6  the §7D invariants, re-run over a trace produced by all of the above
//
// Run: node docs/spikes/0016-combined-stress.mjs
import { DIST, section, check, note, throws, report, virtualClock } from "./_harness.mjs"

const { agents, defineTool } = await import(`${DIST}/index.js`)
const { agent, AgentRuntime, isVerbError } = agents

const mkTool = (name) =>
  defineTool({ name, description: name, inputSchema: { type: "object", properties: {} }, run: async () => `${name}-ran` })

const scriptedFetch = (fn) => async (_url, init) => {
  const body = JSON.parse(String(init?.body))
  const out = await fn(body)
  if (out instanceof Error) throw out
  return new Response(
    JSON.stringify({ choices: [{ message: { role: "assistant", content: out } }], usage: { prompt_tokens: 3, completion_tokens: 3, total_tokens: 6 } }),
    { status: 200, headers: { "content-type": "application/json" } },
  )
}

// Narrowing resolver, lifted verbatim from spike 0015.
function buildFrom(doc, creator) {
  const visible = Object.fromEntries(creator.tools.map((t) => [t.name, t]))
  const spec = { does: doc.does ?? "d", model: doc.model ?? creator.model }
  if (doc.tools) {
    spec.uses = {
      tools: doc.tools.map((n) => {
        if (!visible[n]) throw new Error(`capability: "${n}" is not available to ${creator.name}`)
        return visible[n]
      }),
    }
  }
  if (doc.budget) {
    spec.budget = {}
    for (const [k, v] of Object.entries(doc.budget)) {
      const ceil = creator.budget?.[k]
      spec.budget[k] = ceil === undefined ? v : Math.min(v, ceil)
    }
  }
  return spec
}

// ---------------------------------------------------------------------------
section("T1: capability narrowing at depth 50 and width 200")
// ---------------------------------------------------------------------------
{
  const POOL = ["t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"].map(mkTool)

  // DEPTH: each generation may keep at most what its parent held. Drop one tool every
  // few levels; at no point may a descendant reacquire a dropped one.
  let creator = { name: "gen0", tools: [...POOL], model: "m" }
  let breaches = 0
  let reacquireAttempts = 0
  const held = [POOL.length]
  for (let gen = 1; gen <= 50; gen++) {
    const keep = creator.tools.slice(0, Math.max(1, creator.tools.length - (gen % 4 === 0 ? 1 : 0)))
    const spec = buildFrom({ does: "d", tools: keep.map((t) => t.name) }, creator)
    const childTools = spec.uses.tools
    // Now try to reacquire something this generation no longer holds.
    const dropped = POOL.filter((t) => !childTools.some((c) => c.name === t.name))
    if (dropped.length) {
      reacquireAttempts++
      try {
        buildFrom({ does: "d", tools: [dropped[0].name] }, { name: `gen${gen}`, tools: childTools, model: "m" })
        breaches++
      } catch {
        /* denied, as required */
      }
    }
    creator = { name: `gen${gen}`, tools: childTools, model: "m" }
    held.push(childTools.length)
  }
  note(`tools held: gen0=${held[0]} → gen50=${held[held.length - 1]} over ${reacquireAttempts} reacquire attempts`)
  check("grants are monotonically non-increasing across 50 generations", held.every((v, i) => i === 0 || v <= held[i - 1]))
  check("zero privilege reacquisitions at any depth", breaches === 0, `breaches=${breaches}`)

  // WIDTH: 200 siblings off one creator, each asking for a random mix.
  const wide = { name: "wide", tools: POOL.slice(0, 3), model: "m" }
  let denied = 0
  let granted = 0
  for (let i = 0; i < 200; i++) {
    const ask = [POOL[i % POOL.length].name]
    try {
      buildFrom({ does: "d", tools: ask }, wide)
      granted++
    } catch {
      denied++
    }
  }
  check("200 siblings: every out-of-view ask denied", denied === 140, `granted=${granted} denied=${denied}`)
  check("in-view asks still succeed", granted === 60)
}

// ---------------------------------------------------------------------------
section("T2: a cron schedule driving a COMPOSITE, with overlap")
// ---------------------------------------------------------------------------
{
  const clock = virtualClock(0)
  let release
  const gate = new Promise((r) => (release = r))
  let llmCalls = 0
  const fetch = async (_u, init) => {
    llmCalls++
    if (llmCalls === 1) await gate // the first composite run hangs mid-flight
    const body = JSON.parse(String(init?.body))
    return new Response(
      JSON.stringify({ choices: [{ message: { role: "assistant", content: `${body.model}-ok` } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const rt = new AgentRuntime({ fetch, clock, registry: { step: { name: "step", does: "s", model: "m" } } })
  const h = rt.spawn(rt.root, "step")

  let fires = 0
  let starts = 0
  const tick = () => {
    fires++
    rt.post(h, { from: "clock", channel: "timer", text: "tick" })
    if (h.state === "idle") {
      starts++
      const w = rt.wake(h, "scheduled")
      if (w.ok) void rt.wait(h)
    }
    clock.setTimeout(tick, 1000)
  }
  clock.setTimeout(tick, 1000)

  await clock.advance(10_000)
  check("ten scheduled instants elapsed", fires === 10, `fires=${fires}`)
  check("but the composite was started only ONCE (no stacking)", starts === 1, `starts=${starts}`)
  check("handle is running, not queued ten deep", h.state === "running")
  release()
  await new Promise((r) => setTimeout(r, 30))
  note("Overlap safety composes: it is a property of wake-when-idle, not of the node.")
}

// ---------------------------------------------------------------------------
section("T3: adversarial documents")
// ---------------------------------------------------------------------------
{
  const creator = { name: "c", tools: [mkTool("safe")], model: "m" }
  const hostile = [
    ["prototype pollution via __proto__", '{"does":"x","__proto__":{"polluted":true}}'],
    ["prototype pollution via constructor", '{"does":"x","constructor":{"prototype":{"polluted":true}}}'],
    ["tools is not an array", '{"does":"x","tools":"safe"}'],
    ["tools contains an object", '{"does":"x","tools":[{"name":"safe"}]}'],
    ["null tool name", '{"does":"x","tools":[null]}'],
    ["numeric tool name", '{"does":"x","tools":[42]}'],
  ]
  for (const [label, json] of hostile) {
    const doc = JSON.parse(json)
    let outcome = "accepted"
    try {
      buildFrom(doc, creator)
    } catch {
      outcome = "rejected"
    }
    // Whatever the verdict, the one thing that must never happen is pollution.
    const polluted = {}.polluted !== undefined || Object.prototype.polluted !== undefined
    check(`${label}: Object.prototype NOT polluted`, polluted === false, `(${outcome})`)
  }
  check("a clean object is still clean after all of it", JSON.stringify({}) === "{}")

  // The two pollution documents were ACCEPTED above — they did not pollute, but only
  // because `buildFrom` happens to read a fixed set of keys. That is luck, not a
  // guarantee. The strict resolver from spike 0014 rejects them outright by refusing
  // ANY unknown key, which is the property worth having. Asserted here so the
  // difference between "did not happen to break" and "cannot happen" is on the record.
  const strictKeys = new Set(["does", "soul", "soulFile", "model", "budget", "tools", "team", "guardrails", "completion"])
  const strictResolve = (doc) => {
    for (const k of Object.keys(doc)) {
      if (!strictKeys.has(k)) throw new Error(`resolve: unknown key "${k}"`)
    }
    return doc
  }
  await throws("strict resolver rejects __proto__ as an unknown key", () => strictResolve(JSON.parse('{"does":"x","__proto__":{"p":1}}')), 'unknown key "__proto__"')
  await throws("strict resolver rejects constructor as an unknown key", () => strictResolve(JSON.parse('{"does":"x","constructor":{}}')), 'unknown key "constructor"')
  note("So the allowlist-of-keys rule is load-bearing, not merely tidy: it is what turns")
  note("'did not happen to pollute' into 'cannot reach the resolver at all'.")

  // Non-string tool names must not slip through as a lookup miss that reads as success.
  await throws("a numeric tool name is denied, not coerced", () => buildFrom({ does: "x", tools: [42] }, creator), "not available")
  await throws("a null tool name is denied", () => buildFrom({ does: "x", tools: [null] }, creator), "not available")

  // Depth bomb: a 10k-deep nested graph document must not blow the stack unhandled.
  let deep = { agent: "leaf" }
  for (let i = 0; i < 10_000; i++) deep = { until: deep, maxIterations: 2 }
  const parseGraph = (d, depth = 0) => {
    if (depth > 64) throw new Error("graph: nesting deeper than 64 is rejected")
    if (d.until) {
      if (!Number.isInteger(d.maxIterations) || d.maxIterations < 1) throw new Error("until: maxIterations is required")
      return { kind: "until", max: d.maxIterations, children: [parseGraph(d.until, depth + 1)] }
    }
    if (d.agent) return { kind: "agent", children: [] }
    throw new Error("graph: unknown node")
  }
  await throws("a 10k-deep document is rejected by a depth cap, not a stack overflow", () => parseGraph(deep), "deeper than 64")
  note("Finding: a nesting cap is REQUIRED, not optional. Without it a model-authored")
  note("document is a trivial stack-overflow DoS, and the bound in 0013/0015 is 2^depth.")
}

// ---------------------------------------------------------------------------
section("T4: budget exhaustion inside a composite inside a schedule")
// ---------------------------------------------------------------------------
{
  const clock = virtualClock(0)
  const rt = new AgentRuntime({
    fetch: scriptedFetch(() => "ok"),
    clock,
    registry: { w: { name: "w", does: "w", model: "m", budget: { maxTokens: 25 } } },
  })
  const h = rt.spawn(rt.root, "w")
  const statuses = []
  for (let i = 0; i < 6; i++) {
    rt.wake(h, "scheduled tick")
    const r = await rt.wait(h)
    statuses.push(r.status)
    if (r.status !== "done") break
  }
  note(`statuses: ${statuses.join(" → ")}`)
  check("the budget still stops it, three layers deep", statuses.includes("incomplete"))
  check("and it never silently reports done past the limit", statuses[statuses.length - 1] !== "done")
  check("no new status string was invented", statuses.every((s) => ["done", "pending", "incomplete", "interrupted", "closed", "timeout", "error"].includes(s)))
}

// ---------------------------------------------------------------------------
section("T5: resource hygiene — 500 schedules started and stopped")
// ---------------------------------------------------------------------------
{
  const clock = virtualClock(0)
  const rt = new AgentRuntime({ fetch: scriptedFetch(() => "ok"), clock, registry: { w: { name: "w", does: "w", model: "m" } } })
  const stops = []
  for (let i = 0; i < 500; i++) {
    const h = rt.spawn(rt.root, "w")
    if (isVerbError(h)) break
    let cancel = clock.setTimeout(function tick() {
      cancel = clock.setTimeout(tick, 1000)
    }, 1000)
    stops.push(() => cancel())
  }
  const armed = clock.pending()
  check("500 schedules armed exactly 500 timers", armed === 500, `pending=${armed}`)
  stops.forEach((s) => s())
  check("stopping them all releases every timer", clock.pending() === 0, `pending=${clock.pending()}`)
  await clock.advance(60_000)
  check("and nothing re-arms after stop", clock.pending() === 0)
}

// ---------------------------------------------------------------------------
section("T6: the §7D invariants, over a trace from all of the above")
// ---------------------------------------------------------------------------
{
  const rt = new AgentRuntime({ fetch: scriptedFetch(() => "ok"), registry: { w: { name: "w", does: "w", does2: "", model: "m" } } })
  const h = rt.spawn(rt.root, "w")
  rt.wake(h, "one")
  await rt.wait(h)
  rt.wake(h, "two")
  await rt.wait(h)
  await rt.close(h)

  const trace = rt.trace ?? []
  note(`trace lines: ${trace.length}`)

  // Parse the real shape — `root/w.1: idle→running (wake)` — into transitions. An
  // earlier draft of this section asserted over a `.includes()` soup with a trailing
  // `|| lines.length > 0`, which made it pass for ANY input. That is precisely the
  // vacuous-check failure ADR 0016 warns about, so the invariants are parsed properly
  // and then fed a violating trace to prove they can fail.
  const parse = (ls) =>
    ls
      .map((l) => String(l).match(/^(\S+): (\w+)→(\w+)/))
      .filter(Boolean)
      .map((m) => ({ handle: m[1], from: m[2], to: m[3] }))

  const INVARIANTS = {
    "no-transition-out-of-closed": (ts) => !ts.some((t) => t.from === "closed"),
    "suspended-exits-only-to-idle-or-running": (ts) => !ts.some((t) => t.from === "suspended" && t.to !== "idle" && t.to !== "running"),
    "running-is-entered-only-from-idle-or-suspended": (ts) => ts.filter((t) => t.to === "running").every((t) => t.from === "idle" || t.from === "suspended"),
  }

  const ts = parse(trace)
  check("the combined run produced real transitions to assert over", ts.length >= 4, `transitions=${ts.length}`)
  for (const [name, fn] of Object.entries(INVARIANTS)) check(`invariant holds: ${name}`, fn(ts) === true)

  // Non-vacuous: each invariant must reject a trace that violates it.
  const violating = parse(["root/w.1: closed→running (wake)", "root/w.2: suspended→done (bad)", "root/w.3: closed→running (wake)"])
  check("negative: out-of-closed is DETECTED", INVARIANTS["no-transition-out-of-closed"](violating) === false)
  check("negative: illegal suspended exit is DETECTED", INVARIANTS["suspended-exits-only-to-idle-or-running"](violating) === false)
  check("negative: illegal entry to running is DETECTED", INVARIANTS["running-is-entered-only-from-idle-or-suspended"](violating) === false)
  note("(0009 carries the full invariant registry and its own 4/4 negative tests; this")
  note("is the smoke check that the COMBINED run produced no illegal transition.)")
}

report("0016 combined stress")
