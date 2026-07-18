/**
 * Level-1 subagent surface — SPEC.md §7D.
 *
 * One new noun: `agent(name, spec)`. An Agent is (identity/system prompt × a
 * filtered toolkit view × the shipped client loop), and — the §7A/§7B axiom
 * completed locally — an Agent IS a Tool: `asTool()` bridges it into the classic
 * API's `extraTools`; `run()` executes one-shot against a private runtime.
 * Everything compiles down to the six host verbs in runtime.ts.
 */
import fs from "node:fs"
import { defineTool } from "../native.js"
import type { Answer, Request, Tool } from "../types.js"
import { AgentRuntime, isVerbError, type AgentDef, type Budget, type Handle, type RuntimeOptions, type TaskResult } from "./runtime.js"

/** The declarative agent spec (§7D Level-1 surface). */
export interface AgentSpec {
  /** Required routing description — what a delegating model sees. */
  does: string
  /** The toolkit VIEW for this agent (scoping is the security model). */
  uses?: { tools?: Tool[] }
  /** Identity: inline soul text (becomes the system prompt). */
  soul?: string
  /** Path form of `soul` — read when the registry is built. */
  soulFile?: string
  /** Declaring agents here IS the subagent wiring: they become the `task`
   * tool's only reachable targets. No team ⇒ no `task` tool. */
  team?: Agent[]
  budget?: Budget
  /** Model id; default "inherit" (the runtime's `llm.model`). */
  model?: string
  /** §10 interpreter authority for this agent's subtree. */
  waitFor?: (request: Request) => Promise<Answer>
  onSpawn?: AgentDef["onSpawn"]
  onClose?: AgentDef["onClose"]
}

/** Runtime options for a one-shot `run`/`asTool` (the registry is derived). */
export type AgentRunOptions = Omit<RuntimeOptions, "registry">

/** A one-shot run's result, with the runtime attached for durable resume. */
export interface AgentRunResult extends TaskResult {
  /** The runtime that ran this agent — call `runtime.resume(answer)` after a
   * `status:"pending"` result to continue from the checkpoint. */
  runtime: AgentRuntime
}

export class Agent {
  constructor(readonly name: string, readonly spec: AgentSpec) {}

  /**
   * Collect this agent plus its transitive team graph into a runtime registry —
   * the registry IS the reachable graph; unreachable agents are not present.
   */
  registry(acc: Record<string, AgentDef> = {}): Record<string, AgentDef> {
    if (acc[this.name]) return acc
    let soul = this.spec.soul ?? ""
    if (this.spec.soulFile) soul = fs.readFileSync(this.spec.soulFile, "utf8")
    acc[this.name] = {
      name: this.name,
      does: this.spec.does,
      soul,
      model: this.spec.model ?? "inherit",
      tools: this.spec.uses?.tools,
      team: (this.spec.team ?? []).map((a) => a.name),
      budget: this.spec.budget,
      waitFor: this.spec.waitFor,
      onSpawn: this.spec.onSpawn,
      onClose: this.spec.onClose,
    }
    for (const t of this.spec.team ?? []) t.registry(acc)
    return acc
  }

  /** Build a runtime scoped to this agent's reachable team graph. */
  createRuntime(opts: AgentRunOptions = {}): AgentRuntime {
    return new AgentRuntime({ ...opts, registry: this.registry() })
  }

  /**
   * Level 1: one-shot. Builds a runtime, runs this agent to completion, and tears
   * the tree down — unless the run suspends durably (`status:"pending"`), in which
   * case the tree stays parked and `result.runtime.resume(answer)` continues it.
   */
  async run(prompt: string, opts: AgentRunOptions = {}): Promise<AgentRunResult> {
    const runtime = this.createRuntime(opts)
    const h = runtime.spawn(runtime.root, this.name)
    if (isVerbError(h)) {
      return { text: h.error, isError: true, status: "error", turns: 0, totalTokens: 0, runtime }
    }
    const woke = runtime.wake(h, prompt)
    if (!woke.ok) {
      return { ...(await runtime.wait(h as Handle)), runtime }
    }
    const r = await runtime.wait(h as Handle)
    if (r.status !== "pending") await runtime.close(runtime.root)
    return { ...r, runtime }
  }

  /**
   * The bridge: an Agent IS a Tool. Drop the result into the classic API's
   * `extraTools`; the agent's own loop runs in isolation and the classic run
   * receives one tool result carrying only the final text + metadata
   * `{agent, turns, totalTokens}`.
   */
  asTool(opts: AgentRunOptions = {}): Tool {
    return defineTool({
      name: this.name,
      description: this.spec.does,
      inputSchema: {
        type: "object",
        properties: { prompt: { type: "string", description: "The task for this agent" } },
        required: ["prompt"],
      },
      run: async (args: Record<string, unknown>) => {
        const r = await this.run(String(args.prompt), opts)
        return {
          output: r.text,
          isError: r.isError,
          metadata: { agent: this.name, turns: r.turns, totalTokens: r.totalTokens },
        }
      },
    })
  }
}

/** The one new noun: define an agent. */
export function agent(name: string, spec: AgentSpec): Agent {
  return new Agent(name, spec)
}
