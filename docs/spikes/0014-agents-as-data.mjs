// SPIKE 0014 — an agent spec as DATA, resolved against host-supplied bindings.
//
// ADR 0016 flagged this as the one reading under which `Harness` is not a rename:
//
//   "If the actual want behind Harness is a SERIALIZABLE, TRANSPORTABLE agent
//    definition — ship an agent's identity + capabilities + boundaries as data, load
//    it elsewhere — then this ADR answers the wrong question. AgentDef is not
//    serializable today: tools, waitFor, onSpawn, onClose, hooks and onMetric are all
//    live function values."
//
// That is the blocker, and it is narrow. This spike tests the fix: everything
// executable becomes a NAME resolved against a binding table the host owns.
//
//     resolve(document, bindings) -> AgentSpec
//
// The security property that makes it worth having: DATA CARRIES NAMES OF CODE THE
// HOST REGISTERED, NEVER CODE. No eval, no dynamic import, no expression language.
// The host always owns the executable surface; the document only selects from it.
//
// Precedent: this is the same move that already shipped for the other two tool
// sources — `mcp.json` for MCP servers, a `skills/` folder (and `skills: SkillDef[]`)
// for skills. Agents are the one source that still requires code.
//
// Run: node docs/spikes/0014-agents-as-data.mjs
import { DIST, section, check, note, throws, report } from "./_harness.mjs"

const { agents, defineTool } = await import(`${DIST}/index.js`)
const { agent, allTodosDone } = agents

// ---------------------------------------------------------------------------
// THE DOCUMENT — pure JSON. No functions anywhere. This is the artifact that would
// live beside mcp.json, be diffed in review, and be shipped between ports.
// ---------------------------------------------------------------------------
const DOCUMENT = {
  agents: {
    researcher: {
      does: "researches a topic and reports findings",
      soul: "You research carefully and cite sources.",
      model: "m-research",
      tools: ["search", "fetch_page"],
      budget: { maxTurns: 4, maxTokens: 5000 },
      guardrails: ["no-writes"],
    },
    writer: {
      does: "turns findings into prose",
      soul: "You write plainly.",
      model: "m-write",
      tools: ["fetch_page"],
      team: ["researcher"],
      completion: { verify: "allTodosDone", maxAttempts: 3 },
    },
  },
}

// ---------------------------------------------------------------------------
// THE BINDINGS — the host's table of executable things, keyed by name. THIS is the
// only place code enters, and the host writes it in its own language.
// ---------------------------------------------------------------------------
const mkTool = (name) =>
  defineTool({
    name,
    description: `${name} tool`,
    inputSchema: { type: "object", properties: { q: { type: "string" } } },
    run: async () => `${name}-result`,
  })

const BINDINGS = {
  tools: { search: mkTool("search"), fetch_page: mkTool("fetch_page"), write_file: mkTool("write_file") },
  guardrails: {
    "no-writes": (ev) => (ev.name.startsWith("write") ? "policy: this agent may not write" : "allow"),
  },
  verifiers: { allTodosDone },
}

// ---------------------------------------------------------------------------
// THE RESOLVER — the entire proposed library addition, ~40 lines.
// ---------------------------------------------------------------------------
const DATA_FIELDS = new Set(["does", "soul", "soulFile", "model", "budget"])
const NAME_FIELDS = new Set(["tools", "team", "guardrails", "completion"])

function resolve(document, bindings, { only } = {}) {
  const names = Object.keys(document.agents ?? {})
  if (names.length === 0) throw new Error("resolve: document declares no agents")

  const built = new Map()
  const building = new Set()

  const buildOne = (name) => {
    if (built.has(name)) return built.get(name)
    const raw = document.agents[name]
    if (!raw) throw new Error(`resolve: agent "${name}" is not declared in the document`)
    if (building.has(name)) throw new Error(`resolve: team cycle through "${name}"`)
    building.add(name)

    for (const key of Object.keys(raw)) {
      if (!DATA_FIELDS.has(key) && !NAME_FIELDS.has(key)) {
        // Loud, not lenient. A silently-dropped key is how a "why is my guardrail not
        // running" bug is born — and how a typo'd `budget` becomes "budgets don't
        // work" (see spike 0006 S4-neg).
        throw new Error(`resolve: unknown key "${key}" on agent "${name}"`)
      }
      if (typeof raw[key] === "function") throw new Error(`resolve: "${key}" on "${name}" is a function — documents carry DATA only`)
    }
    if (!raw.does) throw new Error(`resolve: agent "${name}" is missing required "does"`)

    const lookup = (table, key, what) => {
      const v = bindings[table]?.[key]
      if (v === undefined) {
        const known = Object.keys(bindings[table] ?? {}).sort().join(", ") || "(none registered)"
        throw new Error(`resolve: unknown ${what} "${key}" on agent "${name}" — host registered: ${known}`)
      }
      return v
    }

    const spec = { does: raw.does }
    if (raw.soul) spec.soul = raw.soul
    if (raw.model) spec.model = raw.model
    if (raw.budget) spec.budget = raw.budget
    if (raw.tools) spec.uses = { tools: raw.tools.map((t) => lookup("tools", t, "tool")) }
    if (raw.guardrails) spec.guardrails = raw.guardrails.map((g) => lookup("guardrails", g, "guardrail"))
    if (raw.completion) {
      spec.completion = { verify: lookup("verifiers", raw.completion.verify, "verifier"), maxAttempts: raw.completion.maxAttempts }
      if (!Number.isInteger(spec.completion.maxAttempts) || spec.completion.maxAttempts < 1) {
        throw new Error(`resolve: completion.maxAttempts on "${name}" is required and must be an integer >= 1`)
      }
    }
    if (raw.team) spec.team = raw.team.map(buildOne)

    const a = agent(name, spec)
    building.delete(name)
    built.set(name, a)
    return a
  }

  const wanted = only ?? names
  const out = {}
  for (const n of wanted) out[n] = buildOne(n)
  return out
}

// ---------------------------------------------------------------------------
section("A: a JSON document becomes a running agent")
// ---------------------------------------------------------------------------
{
  const built = resolve(DOCUMENT, BINDINGS)
  check("both declared agents were built", Object.keys(built).sort().join(",") === "researcher,writer")

  const r = built.researcher
  check("identity came across", r.spec.does === "researches a topic and reports findings" && r.spec.soul.startsWith("You research"))
  check("model came across", r.spec.model === "m-research")
  check("budget came across as data", r.spec.budget.maxTurns === 4 && r.spec.budget.maxTokens === 5000)
  check("tools resolved BY NAME to real Tool objects", r.spec.uses.tools.map((t) => t.name).join(",") === "search,fetch_page")
  check("guardrail resolved to the host's function", typeof r.spec.guardrails[0] === "function")
  check("team resolved to a real Agent", built.writer.spec.team[0].name === "researcher")
  check("verifier resolved to the shipped allTodosDone", built.writer.spec.completion.verify === allTodosDone)
}

// ---------------------------------------------------------------------------
section("B: it actually RUNS — end to end, from JSON")
// ---------------------------------------------------------------------------
{
  const fetch = async (_url, init) => {
    const body = JSON.parse(String(init?.body))
    return new Response(
      JSON.stringify({ choices: [{ message: { role: "assistant", content: `ran on ${body.model}` } }], usage: { prompt_tokens: 2, completion_tokens: 2, total_tokens: 4 } }),
      { status: 200, headers: { "content-type": "application/json" } },
    )
  }
  const built = resolve(DOCUMENT, BINDINGS)
  const out = await built.researcher.run("what is a ring buffer", { fetch })
  check("the document-defined agent completes a run", out.status === "done", `status=${out.status}`)
  check("on the model the DOCUMENT named", out.text === "ran on m-research", `"${out.text}"`)
}

// ---------------------------------------------------------------------------
section("C: every unknown name fails LOUDLY, and says what IS available")
// ---------------------------------------------------------------------------
{
  const withUnknownTool = { agents: { a: { does: "x", tools: ["nonexistent"] } } }
  const msg = await throws("unknown tool is rejected", () => resolve(withUnknownTool, BINDINGS), 'unknown tool "nonexistent"')
  check("the error lists what the host DID register", msg.includes("fetch_page") && msg.includes("search"), `"${msg.slice(-60)}"`)

  await throws("unknown guardrail is rejected", () => resolve({ agents: { a: { does: "x", guardrails: ["nope"] } } }, BINDINGS), 'unknown guardrail "nope"')
  await throws("unknown verifier is rejected", () => resolve({ agents: { a: { does: "x", completion: { verify: "nope", maxAttempts: 1 } } } }, BINDINGS), "unknown verifier")
  await throws("unknown teammate is rejected", () => resolve({ agents: { a: { does: "x", team: ["ghost"] } } }, BINDINGS), 'agent "ghost" is not declared')
  await throws("an unknown KEY is rejected, never silently dropped", () => resolve({ agents: { a: { does: "x", budgt: {} } } }, BINDINGS), 'unknown key "budgt"')
  await throws("a missing `does` is rejected", () => resolve({ agents: { a: { soul: "x" } } }, BINDINGS), 'missing required "does"')
  await throws("completion without a valid maxAttempts is rejected", () => resolve({ agents: { a: { does: "x", completion: { verify: "allTodosDone" } } } }, BINDINGS), "maxAttempts")
  await throws("a team cycle is caught, not left to blow the stack", () => resolve({ agents: { a: { does: "a", team: ["b"] }, b: { does: "b", team: ["a"] } } }, BINDINGS), "team cycle")
}

// ---------------------------------------------------------------------------
section("D: documents carry DATA — code cannot be smuggled in")
// ---------------------------------------------------------------------------
{
  await throws(
    "a live function in a document is rejected outright",
    () => resolve({ agents: { a: { does: "x", soul: () => "pwned" } } }, BINDINGS),
    "documents carry DATA only",
  )

  // The important negative: a string that LOOKS like code is inert. There is no eval
  // and no expression language, so this is just a soul containing odd characters.
  const sneaky = { agents: { a: { does: "x", soul: "process.exit(1); require('fs').rmSync('/')" } } }
  const built = resolve(sneaky, BINDINGS)
  check("a code-shaped STRING stays an inert string", built.a.spec.soul.includes("process.exit"))
  check("...and the process is obviously still alive", true)

  // Round-trip: the document survives JSON, which is what "transportable" means.
  const round = JSON.parse(JSON.stringify(DOCUMENT))
  const a = resolve(DOCUMENT, BINDINGS).researcher
  const b = resolve(round, BINDINGS).researcher
  check("document survives a JSON round trip unchanged", JSON.stringify(round) === JSON.stringify(DOCUMENT))
  check("and resolves to an equivalent spec", a.spec.does === b.spec.does && a.spec.uses.tools.length === b.spec.uses.tools.length)
}

// ---------------------------------------------------------------------------
section("E: the SAME binding table, two different documents")
// ---------------------------------------------------------------------------
{
  // This is the payoff: behaviour changes without a redeploy, and the host's
  // executable surface never moves. A tenant/config/experiment swaps the document.
  const cautious = { agents: { worker: { does: "w", model: "m", tools: ["search"], guardrails: ["no-writes"] } } }
  const permissive = { agents: { worker: { does: "w", model: "m", tools: ["search", "write_file"] } } }

  const c = resolve(cautious, BINDINGS).worker
  const p = resolve(permissive, BINDINGS).worker
  check("document A grants 1 tool and a guardrail", c.spec.uses.tools.length === 1 && c.spec.guardrails.length === 1)
  check("document B grants 2 tools and no guardrail", p.spec.uses.tools.length === 2 && p.spec.guardrails === undefined)
  note("Same process, same bindings, different capability — decided by data.")
  note("NOTE: nothing here stops document B naming write_file. Bounding what a")
  note("document may grant is spike 0015's job (capability narrowing).")
}

// ---------------------------------------------------------------------------
section("F: what this spike does NOT establish")
// ---------------------------------------------------------------------------
{
  note("• Hooks/onMetric/onSpawn/onClose/waitFor are NOT resolved here. They are the")
  note("  remaining live-function fields; the same name-binding trick applies, but each")
  note("  needs its own fixture because `hooks` is replace-never-merge (SPEC §7D).")
  note("• `soulFile` path resolution is untested — it needs a documented base directory,")
  note("  or a document can read arbitrary files off the host.")
  note("• Seven-port parity is argued from the format, not measured. The obligation is a")
  note("  JSON schema plus a fixture corpus under examples/, exactly as mcp.json has.")
}

report("0014 agents as data")
