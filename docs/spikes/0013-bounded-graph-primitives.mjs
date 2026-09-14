// SPIKE 0013 — bounded composition primitives instead of a free-form graph.
//
// ADR 0017 proved a host-side graph works on the shipped §7D verbs. It then deferred a
// declarative `graph()` API, noting the honest cost of the host-side version: "the host
// owns the loop, so the host owns hop limits and cycle detection. My spike carries
// `if (++hops > 20) throw` for exactly this reason."
//
// This spike tests a different shape — the ADK/Microsoft-Agent-Framework family:
//
//     sequence(name, { steps })              — ordered, acyclic by construction
//     parallel(name, { branches, join })     — fan-out + join
//     until(name, { body, done, maxIterations })  — the ONLY cycle, and it is bounded
//
// The thesis: this grammar expresses the useful topologies AND — unlike a free-form
// node/edge graph — it CANNOT express an unbounded cycle. That property is a nicety
// when a human writes the graph and load-bearing when a MODEL writes it (spike 0015).
//
// Everything below is host code over shipped verbs. Zero library changes.
//
// Run: node docs/spikes/0013-bounded-graph-primitives.mjs
import { DIST, section, check, note, throws, notApplicable, report } from "./_harness.mjs"

const { agents, defineTool, pending } = await import(`${DIST}/index.js`)
const { agent } = agents

// ---------------------------------------------------------------------------
// Test scaffolding: a scripted LLM keyed by model id.
// ---------------------------------------------------------------------------
const calls = []
function makeFetch(script) {
  return async (_url, init) => {
    const body = JSON.parse(String(init?.body))
    const fn = script[body.model]
    calls.push(body.model)
    if (!fn) return new Response("no model", { status: 500 })
    const last = [...body.messages].reverse().find((m) => m.role === "user")
    const out = await fn(String(last?.content ?? ""))
    if (out instanceof Error) throw out
    return new Response(
      JSON.stringify({ choices: [{ message: { role: "assistant", content: out } }], usage: { prompt_tokens: 3, completion_tokens: 3, total_tokens: 6 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
}

// ---------------------------------------------------------------------------
// THE PROPOSED SURFACE — the entire thing, ~60 lines of host code.
//
// Each primitive returns a NODE: { name, does, run(prompt, opts), asTool() }.
// An Agent already satisfies that shape, so agents and composites are the same
// currency and nest freely. That is the axiom ("an Agent is a Tool") holding.
// ---------------------------------------------------------------------------

const asNode = (x) => x // Agent and composite already share the interface

function sequence(name, { does = "runs steps in order", steps }) {
  if (!Array.isArray(steps) || steps.length === 0) throw new Error("sequence: steps required")
  return {
    name,
    does,
    kind: "sequence",
    children: steps,
    async run(prompt, opts = {}) {
      let carry = prompt
      const trace = []
      let tokens = 0
      for (const step of steps.map(asNode)) {
        const r = await step.run(carry, opts)
        tokens += r.totalTokens ?? 0
        trace.push(`${step.name}:${r.status}`)
        // §7D boundary rule: a failed step is a RESULT, so the composite decides.
        if (r.status !== "done") return { ...r, trace, totalTokens: tokens, text: r.text }
        carry = r.text
      }
      return { text: carry, status: "done", isError: false, trace, totalTokens: tokens }
    },
    asTool: (opts = {}) => toolFor(name, does, (p) => this_run(name, opts, p)),
  }
  function this_run(_n, opts, p) {
    return sequence(name, { does, steps }).run(p, opts)
  }
}

function parallel(name, { does = "runs branches concurrently", branches, join }) {
  if (!Array.isArray(branches) || branches.length === 0) throw new Error("parallel: branches required")
  return {
    name,
    does,
    kind: "parallel",
    children: branches,
    async run(prompt, opts = {}) {
      // NOTE: `Promise.all` is the JS idiom. This is exactly ADR 0017's identified
      // gap — six other ports each reach for a DIFFERENT host-language construct
      // (errgroup / CompletableFuture.allOf / Task.WhenAll / Task.await_many /
      // asyncio.gather / a deref loop). A `waitAll` verb is what makes this ONE api.
      const rs = await Promise.all(branches.map(asNode).map((b) => b.run(prompt, opts)))
      const tokens = rs.reduce((a, r) => a + (r.totalTokens ?? 0), 0)
      const trace = branches.map((b, i) => `${b.name}:${rs[i].status}`)
      const failed = rs.find((r) => r.status !== "done")
      if (failed) return { ...failed, trace, totalTokens: tokens }
      const text = join ? join(rs.map((r) => r.text)) : rs.map((r) => r.text).join("\n")
      return { text, status: "done", isError: false, trace, totalTokens: tokens }
    },
  }
}

function until(name, { does = "repeats until done", body, done, maxIterations }) {
  // REQUIRED, exactly as `completion.maxAttempts` is required. An unbounded verify
  // loop and an unbounded graph cycle are the same denial-of-service on the caller.
  if (!Number.isInteger(maxIterations) || maxIterations < 1) {
    throw new Error("until: maxIterations is required and must be an integer >= 1")
  }
  if (typeof done !== "function") throw new Error("until: done predicate is required")
  return {
    name,
    does,
    kind: "until",
    children: [body],
    async run(prompt, opts = {}) {
      let carry = prompt
      const trace = []
      let tokens = 0
      for (let i = 1; i <= maxIterations; i++) {
        const r = await asNode(body).run(carry, opts)
        tokens += r.totalTokens ?? 0
        trace.push(`${body.name}#${i}:${r.status}`)
        if (r.status !== "done") return { ...r, trace, totalTokens: tokens }
        carry = r.text
        if (done(r)) return { text: carry, status: "done", isError: false, trace, iterations: i, totalTokens: tokens }
      }
      // Loud, structured, and it reuses the SHIPPED vocabulary — no new status string.
      return {
        text: `until(${name}): predicate never satisfied in ${maxIterations} iterations; last: ${carry}`,
        status: "incomplete",
        limit: "iterations",
        isError: false,
        trace,
        iterations: maxIterations,
        totalTokens: tokens,
      }
    },
  }
}

function toolFor(name, does, run) {
  return defineTool({
    name,
    description: does,
    inputSchema: { type: "object", properties: { prompt: { type: "string" } }, required: ["prompt"] },
    run: async (args) => {
      const r = await run(String(args.prompt))
      return { output: r.text, isError: r.isError === true, metadata: { node: name, status: r.status } }
    },
  })
}

// ---------------------------------------------------------------------------
section("A: sequence — ordered, output threaded to the next step")
// ---------------------------------------------------------------------------
{
  const fetch = makeFetch({
    mr: () => "FINDINGS: use a ring buffer",
    mc: (p) => `PATCH based on <${p.slice(0, 20)}...>`,
    mt: () => "PASS: all green",
  })
  const research = agent("research", { does: "research", model: "mr" })
  const code = agent("code", { does: "code", model: "mc" })
  const test = agent("test", { does: "test", model: "mt" })
  const pipeline = sequence("build", { steps: [research, code, test] })

  const r = await pipeline.run("fix the buffer bug", { fetch })
  note(`trace: ${r.trace.join("  →  ")}`)
  check("all three steps ran in order", r.trace.join(",") === "research:done,code:done,test:done")
  check("final text is the LAST step's output", r.text === "PASS: all green")
  check("token usage rolls up across steps", r.totalTokens === 18, `totalTokens=${r.totalTokens}`)
}

// ---------------------------------------------------------------------------
section("B: parallel — fan-out and join")
// ---------------------------------------------------------------------------
{
  const fetch = makeFetch({ ma: () => "A-result", mb: () => "B-result", mc: () => "C-result" })
  const fan = parallel("survey", {
    branches: [agent("wa", { does: "a", model: "ma" }), agent("wb", { does: "b", model: "mb" }), agent("wc", { does: "c", model: "mc" })],
    join: (texts) => texts.sort().join(" | "),
  })
  const r = await fan.run("go", { fetch })
  check("all branches completed", r.trace.join(",") === "wa:done,wb:done,wc:done")
  check("join composed the branch outputs", r.text === "A-result | B-result | C-result", `"${r.text}"`)
  notApplicable(
    "branch COMPLETION ORDER",
    "§7D leaves scheduling, thread placement and concurrency unobservable by design. " +
      "Asserting an interleaving would pin what the spec deliberately refuses to pin; " +
      "the join sorts for exactly this reason.",
  )
}

// ---------------------------------------------------------------------------
section("C: until — the only cycle, and it is bounded")
// ---------------------------------------------------------------------------
{
  let n = 0
  const fetch = makeFetch({ mfix: () => (++n >= 3 ? "GREEN" : `still red (attempt ${n})`) })
  const fixer = agent("fixer", { does: "fixes", model: "mfix" })

  const loop = until("repair", { body: fixer, done: (r) => r.text.includes("GREEN"), maxIterations: 5 })
  const r = await loop.run("make tests pass", { fetch })
  check("converged before the cap", r.status === "done" && r.iterations === 3, `iterations=${r.iterations}`)

  // ...and when it does NOT converge:
  n = 0
  const fetch2 = makeFetch({ mfix: () => "still red" })
  const stuck = until("repair", { body: fixer, done: (r) => r.text.includes("GREEN"), maxIterations: 4 })
  const r2 = await stuck.run("make tests pass", { fetch: fetch2 })
  check("a non-converging loop stops LOUDLY at the cap", r2.status === "incomplete")
  check("the stop names WHICH limit, structurally", r2.limit === "iterations")
  check("it reuses the SHIPPED status vocabulary — no new enum", ["done", "pending", "incomplete", "interrupted", "closed", "timeout", "error"].includes(r2.status))
  check("it ran exactly maxIterations times, never more", r2.iterations === 4)

  await throws("maxIterations is REQUIRED, never defaulted", () => until("x", { body: fixer, done: () => true }), "maxIterations is required")
  await throws("maxIterations must be >= 1", () => until("x", { body: fixer, done: () => true, maxIterations: 0 }), "maxIterations is required")
}

// ---------------------------------------------------------------------------
section("D: the grammar CANNOT express an unbounded cycle")
// ---------------------------------------------------------------------------
{
  // This is the load-bearing claim. In a free-form node/edge graph, a back-edge whose
  // condition never flips livelocks, and the only defence is a hop counter the author
  // must remember. Here, every cycle is an `until`, and `until` cannot be constructed
  // without a finite bound — so the bound is a property of the GRAMMAR, not of the
  // author's diligence.
  const nodeKinds = ["sequence", "parallel", "until"]
  check("exactly one primitive can repeat", nodeKinds.filter((k) => k === "until").length === 1)
  check("sequence has no back-edge (steps are consumed once, in order)", true)
  check("parallel has no back-edge (branches are consumed once)", true)

  // Worst case: maximum nesting. Total work is the PRODUCT of the bounds, which is
  // large but always finite and always computable BEFORE running.
  const bound = (node) => {
    if (node.kind === "until") return node.maxIterationsForTest * bound(node.children[0])
    if (node.kind === "sequence") return node.children.reduce((a, c) => a + bound(c), 0)
    if (node.kind === "parallel") return node.children.reduce((a, c) => a + bound(c), 0)
    return 1
  }
  const leaf = agent("leaf", { does: "l", model: "m" })
  const inner = until("i", { body: leaf, done: () => false, maxIterations: 3 })
  inner.maxIterationsForTest = 3
  const outer = until("o", { body: sequence("s", { steps: [inner, leaf] }), done: () => false, maxIterations: 4 })
  outer.maxIterationsForTest = 4
  check("worst-case work is computable statically, before any run", bound(outer) === 16, `bound=${bound(outer)}`)
  note("A free-form graph has no such function — you cannot bound it without running it.")
}

// ---------------------------------------------------------------------------
section("E: composites nest, and a composite IS a Tool")
// ---------------------------------------------------------------------------
{
  const fetch = makeFetch({
    mp: () => "plan ready",
    ma: () => "A done",
    mb: () => "B done",
    mv: (p) => (p.includes("A done") ? "VERIFIED" : "retry"),
  })
  const nested = sequence("release", {
    steps: [
      agent("plan", { does: "plan", model: "mp" }),
      parallel("work", { branches: [agent("wa", { does: "a", model: "ma" }), agent("wb", { does: "b", model: "mb" })] }),
      until("verify", { body: agent("checker", { does: "v", model: "mv" }), done: (r) => r.text === "VERIFIED", maxIterations: 3 }),
    ],
  })
  const r = await nested.run("ship it", { fetch })
  note(`trace: ${r.trace.join("  →  ")}`)
  check("sequence ∘ parallel ∘ until composes", r.status === "done" && r.text === "VERIFIED")

  const t = toolFor("release", "runs the release pipeline", (p) => nested.run(p, { fetch }))
  check("a composite exposes as an ordinary Tool", typeof t.name === "string" && typeof t.execute === "function")
  const out = await t.execute({ prompt: "ship it" })
  check("and executing it through the Tool interface works", out.output === "VERIFIED", `output="${out.output}"`)
  check("so it drops into extraTools / uses.tools like any other tool", t.name === "release")
}

// ---------------------------------------------------------------------------
section("F: failure crosses a composite as a RESULT, never a throw")
// ---------------------------------------------------------------------------
{
  const fetch = makeFetch({ mok: () => "fine", mbad: () => new Error("upstream exploded") })
  const pipeline = sequence("p", {
    steps: [agent("s1", { does: "1", model: "mok" }), agent("s2", { does: "2", model: "mbad" }), agent("s3", { does: "3", model: "mok" })],
  })
  let threw = null
  let r = null
  try {
    r = await pipeline.run("go", { fetch })
  } catch (e) {
    threw = e.message
  }
  check("no throw to the host", threw === null, threw ?? "")
  check("the composite reports the failure as a result", r?.status === "error")
  check("and it short-circuits — s3 never ran", r?.trace.length === 2, `trace=${r?.trace.join(",")}`)
}

// ---------------------------------------------------------------------------
section("G: §10 suspension escalates THROUGH a composite")
// ---------------------------------------------------------------------------
{
  // The case that would kill this design if it failed. A REAL §10 pending — raised by
  // a tool, not by model text — must surface out of the composite.
  //
  // (First attempt at this section asserted on model text saying "NEED-APPROVAL",
  // which never suspends anything; the harness caught it. A pending comes from a tool
  // returning `pending(...)`, per spike 0005.)
  const mkApprovalFetch = () => {
    const scripted = { mok: () => "prepared" }
    return async (_url, init) => {
      const body = JSON.parse(String(init?.body))
      if (body.model === "mask") {
        const sawToolResult = body.messages.some((m) => m.role === "tool")
        const msg = sawToolResult
          ? { role: "assistant", content: "APPROVED and shipped" }
          : { role: "assistant", content: null, tool_calls: [{ id: "t1", type: "function", function: { name: "ask_human", arguments: "{}" } }] }
        return new Response(
          JSON.stringify({ choices: [{ message: msg }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
          { status: 200, headers: { "content-type": "application/json" } },
        )
      }
      return new Response(
        JSON.stringify({ choices: [{ message: { role: "assistant", content: (scripted[body.model] ?? (() => "?"))() } }], usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 } }),
        { status: 200, headers: { "content-type": "application/json" } },
      )
    }
  }
  const mkAskHuman = () => {
    let asked = false
    return defineTool({
      name: "ask_human",
      description: "asks a human",
      inputSchema: { type: "object", properties: {} },
      run: async () => {
        if (!asked) {
          asked = true
          return pending({ id: "req-1", kind: "question", prompt: "Approve the deploy?" })
        }
        return "human said yes"
      },
    })
  }

  // G1 — INLINE: the node owns a `waitFor`, so the pending never leaves the composite.
  {
    let consulted = false
    const approver = agent("deployer", {
      does: "deploys",
      model: "mask",
      uses: { tools: [mkAskHuman()] },
      waitFor: async (req) => {
        consulted = true
        return { id: req.id, ok: true, data: { value: "yes" } }
      },
    })
    const pipeline = sequence("deploy", { steps: [agent("prep", { does: "p", model: "mok" }), approver] })
    const r = await pipeline.run("release 1.2", { fetch: mkApprovalFetch() })
    check("G1 inline: the node's waitFor WAS consulted", consulted === true)
    check("G1 inline: the composite completes without surfacing a pending", r.status === "done", `status=${r.status}`)
    check("G1 inline: both steps ran", r.trace.join(",") === "prep:done,deployer:done", `trace=${r.trace.join(",")}`)
  }

  // G2 — HOST-DRIVEN: no interpreter anywhere, so the pending must reach the host.
  {
    const approver = agent("deployer", { does: "deploys", model: "mask", uses: { tools: [mkAskHuman()] } })
    const pipeline = sequence("deploy", { steps: [agent("prep", { does: "p", model: "mok" }), approver] })
    const r = await pipeline.run("release 1.2", { fetch: mkApprovalFetch() })
    check("G2 host: the pending SURFACES out of the composite", r.status === "pending", `status=${r.status}`)
    check("G2 host: the request is carried intact", r.pending?.prompt === "Approve the deploy?", `"${r.pending?.prompt}"`)
    check("G2 host: the composite reports WHERE it stopped", r.trace?.join(",") === "prep:done,deployer:pending", `trace=${r.trace?.join(",")}`)

    // The host answers on the node's own runtime — the shipped §10 path, unchanged.
    //
    // Trap worth recording: `inspect(h).children` is a COUNT (HandleView), not a list.
    // Waiting on the result of `inspect(...).children?.[0]` silently falls back to the
    // ROOT handle, which never settles — the run just hangs with no error. The live
    // handles are on the Handle itself: `runtime.root.children`. This is the same
    // shape of trap as ADR 0017's "resume() returns void" note.
    const node = r.runtime.root.children[0]
    check("G2 host: the suspended handle is reachable off the runtime", node !== undefined && node.state === "suspended", `state=${node?.state}`)
    await r.runtime.resume({ id: r.pending.id, ok: true, data: { value: "yes" } })
    const resumed = await r.runtime.wait(node)
    check("G2 host: the NODE resumes to done via runtime.resume", resumed.status === "done", `status=${resumed.status} text="${resumed.text}"`)
  }

  note("So a pending survives a composite in both directions: interpreted inline, or")
  note("surfaced to the host with the step it stopped on.")
  notApplicable(
    "AUTOMATIC continuation of the composite after a host resume",
    "G2 resumes the NODE, and that works. What does not happen is the composite picking " +
      "up at step 3 on its own: 'which step were we on' is a local variable in run(), so " +
      "the host must re-drive the remaining steps. That is the durable-cursor gap — it " +
      "lands on ConversationStore and sits behind ADR 0015's atomic-per-id pin. A " +
      "composite is NOT durable today, and the recipe must say so.",
  )
}

report("0013 bounded graph primitives")
