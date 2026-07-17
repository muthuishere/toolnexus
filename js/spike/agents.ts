/**
 * SPIKE — Level-1/2 UX sugar over the substrate (see audit doc "The UX").
 * One new noun: agent(). Everything compiles to the six verbs in runtime.ts.
 *
 *   const explore = agent("explore", { does: "...", uses: { tools: [lookup] } })
 *   const coder   = agent("coder",   { does: "...", soul: "./AGENTS.md", team: [explore] })
 *   const r = await coder.run({ fetch: mockFetch }, "fix the bug")
 *
 *   const mia = agentFromDir("~/mia")          // the directory IS the agent
 *   const rt  = startRuntime({ fetch })         // Level 3 lives underneath
 */
import fs from "node:fs"
import path from "node:path"
import { defineTool, type Tool, type Request, type Answer } from "../dist/index.js"
import { AgentRuntime, type AgentDef, type Budget, type Handle, type RuntimeOptions, type TaskResult } from "./runtime.ts"

export interface AgentSpec {
  /** What the delegating model sees — the routing description. */
  does: string
  /** The toolkit VIEW for this agent (scoping = the whole security model). */
  uses?: { tools?: Tool[] }
  /** Identity: inline text, or a path to a soul file (AGENTS.md / SOUL.md). */
  soul?: string
  /** Path form of soul — read at build time. */
  soulFile?: string
  /** task-tool targets: listing agents here IS the subagent wiring. */
  team?: Agent[]
  budget?: Budget
  /** Model id; "inherit" = runtime default. */
  model?: string
  /** §10 interpreter authority for this agent's subtree. */
  waitFor?: (req: Request) => Promise<Answer>
  onSpawn?: AgentDef["onSpawn"]
  onClose?: AgentDef["onClose"]
}

export class Agent {
  readonly name: string
  readonly spec: AgentSpec
  constructor(name: string, spec: AgentSpec) {
    this.name = name
    this.spec = spec
  }

  /** Collect this agent + its whole team graph into a runtime registry. */
  registry(acc: Record<string, AgentDef> = {}): Record<string, AgentDef> {
    if (acc[this.name]) return acc
    let soul = this.spec.soul ?? ""
    if (this.spec.soulFile) soul = fs.readFileSync(this.spec.soulFile, "utf8")
    acc[this.name] = {
      name: this.name,
      description: this.spec.does,
      systemPrompt: soul,
      model: this.spec.model ?? "inherit",
      tools: this.spec.uses?.tools,
      budget: this.spec.budget,
      waitFor: this.spec.waitFor,
      onSpawn: this.spec.onSpawn,
      onClose: this.spec.onClose,
      /** team scoping: task targets = ONLY this agent's team (not the whole registry) */
      taskTargets: (this.spec.team ?? []).map((a) => a.name),
    } as AgentDef
    for (const t of this.spec.team ?? []) t.registry(acc)
    return acc
  }

  /** Level 1: one-shot — build a runtime, run to completion, tear down. */
  async run(rtOpts: Omit<RuntimeOptions, "registry">, prompt: string): Promise<TaskResult> {
    const rt = new AgentRuntime({ ...rtOpts, registry: this.registry() })
    const h = rt.spawn(rt.root, this.name)
    if ("isError" in h) return { text: h.isError, isError: true, status: "done", turns: 0, totalTokens: 0 }
    rt.wake(h, prompt)
    const r = await rt.wait(h)
    if (r.status !== "pending") await rt.close(rt.root)
    ;(r as TaskResult & { rt?: AgentRuntime }).rt = rt // exposed for durable resume
    return r
  }

  /** Bridge: an Agent IS a Tool — drop it into the OLD API's extraTools. */
  asTool(rtOpts: Omit<RuntimeOptions, "registry">): Tool {
    const self = this
    return defineTool({
      name: this.name,
      description: this.spec.does,
      inputSchema: { type: "object", properties: { prompt: { type: "string" } }, required: ["prompt"] },
      async run(args: Record<string, unknown>) {
        const r = await self.run(rtOpts, String(args.prompt))
        return { output: r.text, isError: r.isError, metadata: { agent: self.name, turns: r.turns, totalTokens: r.totalTokens } }
      },
    })
  }
}

/** The one new noun. */
export function agent(name: string, spec: AgentSpec): Agent {
  return new Agent(name, spec)
}

/**
 * Level 2: the directory IS the agent. Discovers (all optional, openclaw order):
 * AGENTS.md, SOUL.md, IDENTITY.md, USER.md, TOOLS.md, HEARTBEAT.md, MEMORY.md.
 * Injected as named sections at session start (cache doctrine: onSpawn reads memory in).
 */
export const BOOTSTRAP_ORDER = ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]
const MAX_BOOTSTRAP_FILE_BYTES = 2 * 1024 * 1024

export function agentFromDir(dir: string, opts?: Partial<AgentSpec> & { name?: string }): Agent {
  const sections: string[] = []
  for (const f of BOOTSTRAP_ORDER) {
    const p = path.join(dir, f)
    if (fs.existsSync(p)) {
      const stat = fs.statSync(p)
      let body = fs.readFileSync(p, "utf8")
      if (stat.size > MAX_BOOTSTRAP_FILE_BYTES) body = body.slice(0, MAX_BOOTSTRAP_FILE_BYTES) + "\n[truncated: file exceeds bootstrap cap]"
      sections.push(`## ${f}\n\n${body.trim()}`)
    }
  }
  const name = opts?.name ?? path.basename(dir)
  return agent(name, {
    does: opts?.does ?? `persona agent from ${dir}`,
    soul: sections.join("\n\n"),
    ...opts,
  })
}

/** The HEARTBEAT_OK sentinel — a silent wake produces no outbound message. */
export const HEARTBEAT_OK = "HEARTBEAT_OK"

export interface StartedAgent {
  handle: Handle
  rt: AgentRuntime
  stop: () => Promise<void>
  /** Fires only for NON-silent heartbeat results (HEARTBEAT_OK stripped). */
  onReport?: (text: string) => void
}

/**
 * Level 2: start a long-lived persona — heartbeat posts a tick to its own inbox
 * (unsolicited rail; ticks coalesce) and wakes it; HEARTBEAT_OK results stay silent.
 */
export function startAgent(
  a: Agent,
  rtOpts: Omit<RuntimeOptions, "registry">,
  opts?: { everyMs?: number; onReport?: (text: string) => void },
): StartedAgent {
  const rt = new AgentRuntime({ ...rtOpts, registry: a.registry() })
  const spawned = rt.spawn(rt.root, a.name)
  if ("isError" in spawned) throw new Error(spawned.isError)
  const handle = spawned
  let timer: ReturnType<typeof setInterval> | undefined
  if (opts?.everyMs) {
    timer = setInterval(() => {
      rt.post(handle, { from: "clock", channel: "timer", text: "tick" })
      if (handle.state === "idle") {
        void rt.runTurn(handle, "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.").then((r) => {
          if (!r.isError && !r.text.includes(HEARTBEAT_OK)) opts.onReport?.(r.text)
        })
      }
    }, opts.everyMs)
  }
  return {
    handle,
    rt,
    stop: async () => {
      if (timer) clearInterval(timer)
      await rt.close(rt.root)
    },
  }
}
