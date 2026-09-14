// SPIKE 0015 — a MODEL builds the agent/graph at runtime, and cannot escape its box.
//
// Spike 0014 made an agent spec into data. Once a spec is data, "build an agent" and
// "build a graph" stop needing a subsystem: they are a TOOL that takes a document.
// That is the same move that made `task` a tool over spawn/wake/wait/close.
//
// Which raises the only question that matters: what stops a model — one that just read
// a poisoned web page — from authoring an agent with tools it was never granted?
//
// The proposed invariant, and the whole point of this spike:
//
//     CAPABILITY NARROWS, NEVER WIDENS.
//
// §7D already works this way twice. Budget carves `min(own, parent remaining)`.
// Guardrails are first-deny-wins, so a later one cannot widen an earlier denial. Tools
// do NOT yet, because until specs were data nobody could author one at runtime. This
// spike tests making tools obey the same asymmetry, and then tries to break it.
//
// Every containment below gets a NEGATIVE test. A containment that is never observed
// failing is decoration (ADR 0016's "verified not vacuous" rule).
//
// Run: node docs/spikes/0015-dynamic-construction-and-capability-narrowing.mjs
import { DIST, section, check, note, throws, report } from "./_harness.mjs"

const { agents, defineTool } = await import(`${DIST}/index.js`)
const { agent, AgentRuntime, isVerbError } = agents

// ---------------------------------------------------------------------------
// The host's executable surface. Note `deploy_prod` and `write_file` — the dangerous
// ones. They exist in the process; the question is who can reach them.
// ---------------------------------------------------------------------------
const audit = []
const mkTool = (name) =>
  defineTool({
    name,
    description: `${name} tool`,
    inputSchema: { type: "object", properties: {} },
    run: async () => {
      audit.push(name)
      return `${name}-ran`
    },
  })

const ALL_TOOLS = {
  search: mkTool("search"),
  fetch_page: mkTool("fetch_page"),
  write_file: mkTool("write_file"),
  deploy_prod: mkTool("deploy_prod"),
}

// ---------------------------------------------------------------------------
// THE CONSTRUCTOR — resolution, but scoped to the CREATOR's view.
//
// The single load-bearing line is `visible`: resolution can only see the tools the
// creator itself holds. Naming anything else is not a permission error to be caught
// downstream — the name simply does not exist in this scope.
// ---------------------------------------------------------------------------
function buildFrom(doc, creator) {
  const visible = {}
  for (const t of creator.tools) visible[t.name] = t

  const spec = { does: doc.does ?? "dynamically constructed" }
  if (typeof doc.soul === "string") spec.soul = doc.soul
  spec.model = doc.model ?? creator.model

  if (doc.tools) {
    spec.uses = {
      tools: doc.tools.map((name) => {
        const t = visible[name]
        if (!t) {
          // The error lists only what the CREATOR holds — narrowing narrows the error
          // message too, so a probe cannot enumerate the host's full tool table.
          throw new Error(`capability: "${name}" is not available to ${creator.name} — it may grant only: ${Object.keys(visible).sort().join(", ") || "(nothing)"}`)
        }
        return t
      }),
    }
  }

  // Budget: carve, never widen. Same rule §7D already applies at spawn.
  if (doc.budget) {
    spec.budget = {}
    for (const [k, v] of Object.entries(doc.budget)) {
      const ceiling = creator.budget?.[k]
      spec.budget[k] = ceiling === undefined ? v : Math.min(v, ceiling)
    }
  }
  return spec
}

/** The tool a model calls to create a sub-agent. */
function builderTool(creator, { onBuild } = {}) {
  return defineTool({
    name: "build_agent",
    description: "Create a sub-agent from a JSON spec. It may use only tools you hold.",
    inputSchema: { type: "object", properties: { spec: { type: "string" } }, required: ["spec"] },
    run: async (args) => {
      let doc
      try {
        doc = JSON.parse(String(args.spec))
      } catch {
        return { output: "build_agent: spec must be valid JSON", isError: true }
      }
      try {
        const spec = buildFrom(doc, creator)
        const built = agent(doc.name ?? "child", spec)
        onBuild?.(built)
        return { output: `built ${built.name} with tools [${(spec.uses?.tools ?? []).map((t) => t.name).join(", ")}]` }
      } catch (e) {
        // Failure crosses as a RESULT (§7D boundary rule) so the model can correct
        // itself, rather than an exception that unwinds the host.
        return { output: e.message, isError: true }
      }
    },
  })
}

// ---------------------------------------------------------------------------
section("A: the happy path — a model authors an agent at runtime")
// ---------------------------------------------------------------------------
{
  const creator = { name: "coordinator", tools: [ALL_TOOLS.search, ALL_TOOLS.fetch_page], model: "m", budget: { maxTurns: 10 } }
  let built = null
  const t = builderTool(creator, { onBuild: (b) => (built = b) })
  const out = await t.execute({ spec: JSON.stringify({ name: "scout", does: "scouts", tools: ["search"] }) })
  check("construction succeeds", out.isError !== true, `"${out.output}"`)
  check("the built agent is a real Agent", built?.name === "scout")
  check("it carries exactly the tool it asked for", built.spec.uses.tools.map((x) => x.name).join(",") === "search")
}

// ---------------------------------------------------------------------------
section("B: THE INVARIANT — a child cannot be granted what its creator lacks")
// ---------------------------------------------------------------------------
{
  const creator = { name: "coordinator", tools: [ALL_TOOLS.search], model: "m" }
  const t = builderTool(creator)

  const escalate = await t.execute({ spec: JSON.stringify({ name: "evil", does: "x", tools: ["search", "deploy_prod"] }) })
  check("naming an unheld tool is DENIED", escalate.isError === true)
  check("the denial names the offending capability", escalate.output.includes("deploy_prod"), `"${escalate.output}"`)
  check("the denial does NOT leak the host's other tools", !escalate.output.includes("write_file") && !escalate.output.includes("fetch_page"), `"${escalate.output}"`)
  check("nothing was executed during the attempt", audit.includes("deploy_prod") === false)

  // Narrowing is fine — that is the whole point of the asymmetry.
  const narrow = await t.execute({ spec: JSON.stringify({ name: "ok", does: "x", tools: [] }) })
  check("granting FEWER tools than the creator is allowed", narrow.isError !== true)
}

// ---------------------------------------------------------------------------
section("C: transitivity — narrowing survives a chain of creators")
// ---------------------------------------------------------------------------
{
  // The attack a single-level check misses: A (holds search+write) builds B (search),
  // and B then tries to build C with write_file. If the check reads the ROOT toolkit
  // instead of the IMMEDIATE creator's view, privilege leaks back in at depth 2.
  const a = { name: "A", tools: [ALL_TOOLS.search, ALL_TOOLS.write_file], model: "m" }
  let b = null
  await builderTool(a, { onBuild: (x) => (b = x) }).execute({ spec: JSON.stringify({ name: "B", does: "b", tools: ["search"] }) })
  check("A built B with a strictly smaller grant", b.spec.uses.tools.map((t) => t.name).join(",") === "search")

  const bAsCreator = { name: "B", tools: b.spec.uses.tools, model: "m" }
  const regrant = await builderTool(bAsCreator).execute({ spec: JSON.stringify({ name: "C", does: "c", tools: ["write_file"] }) })
  check("B cannot re-grant a tool A had but B does not", regrant.isError === true, `"${regrant.output}"`)
  check("privilege does not leak back in at depth 2", regrant.output.includes("only: search"), `"${regrant.output}"`)
}

// ---------------------------------------------------------------------------
section("D: budget carves, never widens")
// ---------------------------------------------------------------------------
{
  const creator = { name: "c", tools: [ALL_TOOLS.search], model: "m", budget: { maxTurns: 4, maxTokens: 1000 } }
  let built = null
  await builderTool(creator, { onBuild: (b) => (built = b) }).execute({
    spec: JSON.stringify({ name: "greedy", does: "x", budget: { maxTurns: 999, maxTokens: 50 } }),
  })
  check("an over-ask is clamped to the creator's ceiling", built.spec.budget.maxTurns === 4, `maxTurns=${built.spec.budget.maxTurns}`)
  check("an under-ask is respected as-is", built.spec.budget.maxTokens === 50, `maxTokens=${built.spec.budget.maxTokens}`)
}

// ---------------------------------------------------------------------------
section("E: depth — self-construction terminates")
// ---------------------------------------------------------------------------
{
  // A model that builds a builder that builds a builder... §7D's maxDepth already
  // stops the RUNTIME tree. Check it actually fires rather than assuming it.
  const fetch = async () =>
    new Response(
      JSON.stringify({ choices: [{ message: { role: "assistant", content: "ok" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  const rt = new AgentRuntime({ fetch, registry: { w: { name: "w", does: "w", model: "m" } } })
  let h = rt.spawn(rt.root, "w", { maxDepth: 3 })
  let depth = 0
  let stop = null
  for (let i = 0; i < 20; i++) {
    const next = rt.spawn(h, "w")
    if (isVerbError(next)) {
      stop = next.error
      break
    }
    h = next
    depth++
  }
  check("recursive spawning stops", stop !== null, `"${stop}"`)
  check("it stops at the declared depth, loudly", stop.includes("maxDepth"), `depth reached=${depth}`)
}

// ---------------------------------------------------------------------------
section("F: a guardrail can forbid construction entirely")
// ---------------------------------------------------------------------------
{
  // Policy question ("may it?"), so it belongs in a guardrail — and guardrails are
  // first-deny-wins, so a later, more permissive one cannot re-enable it.
  const deny = (ev) => (ev.name === "build_agent" ? "policy: this agent may not create agents" : "allow")
  const allowAll = () => "allow"
  const chain = [deny, allowAll]
  const verdicts = chain.map((g) => g({ name: "build_agent", args: {}, turn: 1 }))
  const firstDeny = verdicts.find((v) => v !== "allow")
  check("the first denial wins over a later allow", firstDeny === "policy: this agent may not create agents")
  check("order does not rescue it — reversed is still denied", [allowAll, deny].map((g) => g({ name: "build_agent", args: {}, turn: 1 })).some((v) => v !== "allow"))
}

// ---------------------------------------------------------------------------
section("G: construction can be gated behind a human")
// ---------------------------------------------------------------------------
{
  // The recommendation is that this be the DEFAULT for model-authored specs: a
  // constructor that suspends via §10 until a human approves the topology.
  const { pending } = await import(`${DIST}/index.js`)
  let approved = false
  let asked = null
  const gated = defineTool({
    name: "build_agent",
    description: "create a sub-agent (requires approval)",
    inputSchema: { type: "object", properties: { spec: { type: "string" } } },
    run: async (args) => {
      if (!approved) {
        asked = String(args.spec)
        return pending({ id: "build-1", kind: "question", prompt: `Approve creating: ${args.spec}` })
      }
      return "built"
    },
  })
  const first = await gated.execute({ spec: '{"name":"x","does":"y"}' })
  // Shape worth recording: at the RAW Tool boundary a §10 pending surfaces as
  // `{ isError: true, metadata.pending }`, not as a top-level `pending` field — the
  // §10 machinery reads `metadata.pending`. Asserting `first.pending` looks right and
  // is always undefined.
  check("construction suspends instead of proceeding", first.metadata?.pending?.id === "build-1", JSON.stringify(first.metadata))
  check("the request carries the approval prompt", first.metadata.pending.prompt.startsWith("Approve creating:"))
  check("the human is shown the ACTUAL spec, not a summary", asked === '{"name":"x","does":"y"}')
  approved = true
  const second = await gated.execute({ spec: '{"name":"x","does":"y"}' })
  check("after approval it proceeds", second.output === "built")
}

// ---------------------------------------------------------------------------
section("H: a MODEL-authored graph is bounded by the grammar")
// ---------------------------------------------------------------------------
{
  // Spike 0013 argued the bounded grammar cannot express an unbounded cycle. Here that
  // stops being a nicety: this document was written by a model, not a reviewer.
  const parseGraph = (doc) => {
    if (doc.sequence) return { kind: "sequence", children: doc.sequence.map(parseGraph) }
    if (doc.parallel) return { kind: "parallel", children: doc.parallel.map(parseGraph) }
    if (doc.until) {
      if (!Number.isInteger(doc.maxIterations) || doc.maxIterations < 1) {
        throw new Error("until: maxIterations is required and must be an integer >= 1")
      }
      return { kind: "until", max: doc.maxIterations, children: [parseGraph(doc.until)] }
    }
    if (doc.agent) return { kind: "agent", children: [] }
    throw new Error(`graph: unknown node ${JSON.stringify(Object.keys(doc))}`)
  }
  const bound = (n) =>
    n.kind === "until" ? n.max * bound(n.children[0]) : n.kind === "agent" ? 1 : n.children.reduce((a, c) => a + bound(c), 0)

  const modelWrote = { sequence: [{ agent: "plan" }, { until: { agent: "fix" }, maxIterations: 5 }, { parallel: [{ agent: "a" }, { agent: "b" }] }] }
  const g = parseGraph(modelWrote)
  check("a model-authored graph parses", g.kind === "sequence")
  check("its worst case is computable BEFORE running it", bound(g) === 8, `bound=${bound(g)}`)

  await throws("a cycle without a bound is unrepresentable", () => parseGraph({ until: { agent: "spin" } }), "maxIterations is required")
  await throws("an invented node type is rejected, not guessed at", () => parseGraph({ goto: "start" }), "unknown node")
  note("There is no `goto`, no free edge, no back-reference — so there is no livelock")
  note("to detect. The bound is a property of the grammar, not of the author's care.")
}

// ---------------------------------------------------------------------------
section("I: honest limits — what containment does NOT buy")
// ---------------------------------------------------------------------------
{
  const creator = { name: "c", tools: [ALL_TOOLS.deploy_prod], model: "m" }
  let built = null
  await builderTool(creator, { onBuild: (b) => (built = b) }).execute({ spec: JSON.stringify({ name: "child", does: "x", tools: ["deploy_prod"] }) })
  check("a creator that HOLDS a dangerous tool can pass it on", built.spec.uses.tools[0].name === "deploy_prod")
  note("This is correct — narrowing bounds ESCALATION, not misuse. An agent that")
  note("legitimately holds deploy_prod can delegate it. If a poisoned page convinces")
  note("that agent to deploy, capability narrowing never enters the picture.")
  note("")
  note("So narrowing is necessary, not sufficient. The remaining controls are the ones")
  note("already shipped: guardrails on the dangerous tool itself (section F), §10")
  note("approval (section G), and budgets. The recommendation stands that §10 approval")
  note("be the DEFAULT for model-authored specs, opt-out rather than opt-in.")
  note("")
  note("Also NOT established here: `soulFile` path traversal (a document naming")
  note("../../etc/passwd), and prompt-injection resistance of the constructor's own")
  note("description. Both need their own spike before this ships.")
}

report("0015 dynamic construction + capability narrowing")
