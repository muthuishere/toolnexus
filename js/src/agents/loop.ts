/**
 * Loop — a live execution of an Agent, and the completion gate that stops it
 * claiming `done` too early. A layer over the shipped §8 client: nothing here
 * changes existing behaviour.
 *
 * The placement law this encodes:
 *
 *   AgentSpec (the harness) answers "MAY it?"    — capability, ceilings. Per problem.
 *   RunOptions             answers "with WHAT?"  — model for this call.  Per call.
 *   Loop                   answers "DID it?"     — status, turns.        Observed.
 *   none of them           answers "is it RIGHT?"— a tool, skill or agent.
 *
 * So Loop takes no options: it is read, not configured.
 */
import type { PromptInput } from "../content.js"
import { createClient, type ClientOptions, type Hooks, type RunResult } from "../client.js"
import type { Toolkit } from "../toolkit.js"
import type { Agent } from "./agent.js"

/** A POLICY check on a tool call — "may it?", never "is it right?".
 *  Return `"allow"` (or nothing) to permit; any other string DENIES with that reason. */
export type Guardrail = (ev: {
  name: string
  args: Record<string, unknown>
  id?: string
  turn: number
}) => string | undefined | void

/** The verdict a completion verifier returns. */
export interface Verdict {
  ok: boolean
  reason?: string
}

/** The gate that stops an agent claiming `done` before its work verifies. */
export interface Completion {
  /** Judges the run. Receives the tool calls ACCUMULATED across attempts. */
  verify: (result: RunResult) => Verdict | Promise<Verdict>
  /** REQUIRED. An unbounded verify loop is a denial-of-service on the caller's bill. */
  maxAttempts: number
}

/** What varies PER CALL. `model` is here — not on the spec's default and not on the
 *  Loop — so the same conversation may change model between turns. */
export interface LoopRunOptions {
  /** Overrides the agent's model for this call only. Omitted ⇒ the agent's. */
  model?: string
}

/** What a run reports. `status` reuses the SHIPPED vocabulary — no new status
 *  strings are minted (SPEC.md pins TaskStatus identical across ports). */
export interface Outcome {
  text: string
  status: "done" | "incomplete" | "pending" | "error"
  /** Named whenever `status` is not `done` — never a silent stop. */
  stoppedBy?: string
  attempts: number
  turns: number
  result?: RunResult
}

/**
 * Compiles a spec's guardrails into one `beforeTool` with FIRST-DENY-WINS,
 * composed ahead of any hook the spec already set. No guardrails ⇒ the spec's
 * hooks are returned untouched, so absent is byte-identical.
 */
export function guardedHooks(guardrails: Guardrail[] | undefined, hooks: Hooks | undefined): Hooks | undefined {
  if (!guardrails || guardrails.length === 0) return hooks
  const prior = hooks?.beforeTool
  return {
    ...(hooks ?? {}),
    beforeTool: async (ev) => {
      for (const g of guardrails) {
        const verdict = g(ev)
        // A guardrail is SYNCHRONOUS by contract. An async one returns a Promise,
        // which is truthy and !== "allow", so it would silently DENY every call
        // with `denied: [object Promise]`. Fail loudly instead, and say where
        // asynchronous work belongs.
        if (verdict != null && typeof verdict !== "string") {
          const what = typeof (verdict as { then?: unknown }).then === "function" ? "a Promise" : typeof verdict
          throw new TypeError(
            `guardrail returned ${what}: a guardrail must return a string synchronously ` +
              `("" or "allow" to permit, any other string to deny). ` +
              `Do asynchronous work in hooks.beforeTool, which is awaited.`,
          )
        }
        if (verdict && verdict !== "allow") {
          return { result: { output: `denied: ${verdict}`, isError: true } }
        }
      }
      return prior ? prior(ev) : undefined
    },
  }
}

/**
 * The built-in completion verifier. Reads the SHIPPED `todowrite` builtin's
 * result metadata and requires every item to be checked.
 *
 * Structural, not domain: it counts unchecked boxes and never learns what a todo
 * means, so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒
 * pass, so the gate never punishes an agent that does not use the builtin.
 */
export function allTodosDone(result: RunResult): Verdict {
  for (let i = result.toolCalls.length - 1; i >= 0; i--) {
    if (result.toolCalls[i].name !== "todowrite") continue
    const todos = result.toolCalls[i].metadata?.todos
    if (!Array.isArray(todos)) return { ok: true }
    const open = todos
      .filter((t: any) => !t?.completed)
      .map((t: any) => String(t?.text ?? ""))
    return open.length > 0
      ? { ok: false, reason: `${open.length} item(s) still open: ${open.join("; ")}` }
      : { ok: true }
  }
  return { ok: true }
}

/**
 * Wraps a client run with the completion gate. SHARED by the standalone Loop and
 * the §7D runtime turn, so a delegated child gets exactly the same guarantee as a
 * directly-driven one.
 *
 * Rule 2 in force: a run that is `pending` (suspended on a human) or otherwise
 * non-done already carries its own reason, so the gate never re-judges it. That
 * keeps `pending` and `incomplete` distinct — the caller can always tell whether
 * it owes an Answer or a fix.
 */
export async function runGated(
  ask: (prompt: PromptInput) => Promise<RunResult>,
  prompt: PromptInput,
  completion: Completion | undefined,
): Promise<RunResult> {
  if (!completion) return ask(prompt)
  const { maxAttempts } = completion
  if (!Number.isInteger(maxAttempts) || maxAttempts < 1) {
    throw new Error("toolnexus: completion.maxAttempts must be an integer >= 1")
  }
  if (typeof completion.verify !== "function") {
    throw new Error("toolnexus: completion.verify is required")
  }

  let accumulated: RunResult["toolCalls"] = []
  let last: RunResult | undefined
  let reason = ""
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const p = attempt === 1 ? prompt : `Your work did not verify: ${reason}. Fix it and finish.`
    const r = await ask(p)
    // The gate judges the ACCUMULATED work, so an agent cannot escape it by
    // declining to re-declare its plan on a retry.
    accumulated = [...accumulated, ...r.toolCalls]
    const judged: RunResult = { ...r, toolCalls: accumulated }
    last = judged

    if (r.status && r.status !== "done") {
      // The run stopped for its own reason (suspension, budget). If the gate was
      // mid-retry the caller must learn BOTH — otherwise a budget stop masks the
      // verification failure and they never see why it was looping.
      if (reason && r.status !== "pending") {
        judged.text = `${r.text} [while verifying: attempt ${attempt} last failed: ${reason}]`
      }
      return judged
    }

    const verdict = await completion.verify(judged)
    if (verdict.ok) return judged
    reason = verdict.reason ?? "unspecified"
  }

  // Structured, not prose: `limit` is how a caller (and the §7D runtime) tells
  // WHICH limit stopped the run. `text` carries the human reason.
  return {
    ...(last as RunResult),
    status: "incomplete",
    limit: "completion",
    text: `completion.verify failed ${maxAttempts}×: ${reason}`,
  }
}

/**
 * The spec fields a Loop drive CANNOT honour, named rather than dropped silently.
 *
 * A `Loop` is one client run: it has no runtime, so it has no handle tree to spawn a `team`
 * into, no §10 suspension ledger for `waitFor`, and no per-agent metric sink routing. Those
 * fields are honoured by `Agent.run` / `AgentRuntime` only. `uses.tools` is not honoured either
 * — a Loop is handed its toolkit by the caller.
 *
 * Returns a FIXED CANONICAL vocabulary — `"tools"`, `"team"`, `"waitFor"`, `"onMetric"` —
 * identical in all seven ports, exactly as the `limit` strings are, and NOT this language's own
 * spelling of the field. Empty ⇒ nothing is lost.
 * Additive and advisory: a Loop never refuses to run because of it (that would be breaking).
 */
export function loopUnsupported(spec: {
  uses?: { tools?: unknown[] }
  team?: unknown[]
  waitFor?: unknown
  onMetric?: unknown
}): string[] {
  const out: string[] = []
  if (spec.uses?.tools && spec.uses.tools.length > 0) out.push("tools")
  if (spec.team && spec.team.length > 0) out.push("team")
  if (spec.waitFor) out.push("waitFor")
  if (spec.onMetric) out.push("onMetric")
  return out
}

/** A live execution of an Agent. Its only verbs are `run` and reading state. */
export class Loop {
  #status: Outcome["status"] | "idle" | "running" = "idle"
  #turns = 0

  constructor(
    private readonly agent: Agent,
    /** CLIENT OPTIONS rather than a built client, because a per-call `model`
     *  override must be able to change the model — which is fixed at construction. */
    private readonly options: ClientOptions,
    private readonly toolkit: Toolkit,
  ) {}

  /** Observed, never set by the caller. */
  get status(): string {
    return this.#status
  }

  /** Model round trips this loop has spent. */
  get turns(): number {
    return this.#turns
  }

  async run(prompt: PromptInput, opts: LoopRunOptions = {}): Promise<Outcome> {
    const completion = this.agent.spec.completion
    const client = createClient(this.#clientOptions(opts))
    this.#status = "running"

    let attempts = 0
    let history: any[] = []
    const ask = async (p: PromptInput): Promise<RunResult> => {
      attempts++
      const r = await client.run(p, { toolkit: this.toolkit, history })
      this.#turns += r.turns
      history = r.messages
      return r
    }

    try {
      const r = await runGated(ask, prompt, completion)
      if (r.status && r.status !== "done") {
        this.#status = r.status as Outcome["status"]
        const stoppedBy =
          r.limit === "completion" ? r.text : `run reported ${r.status}`
        return { text: r.text, status: r.status as Outcome["status"], stoppedBy, attempts, turns: this.#turns, result: r }
      }
      this.#status = "idle"
      return { text: r.text, status: "done", attempts, turns: this.#turns, result: r }
    } catch (e) {
      this.#status = "error"
      throw e
    }
  }

  /** Applies a per-call model override via `requestParams` (`model` is not in the
   *  forbidden set — the client forbids only messages/tools/stream). */
  #clientOptions(opts: LoopRunOptions): ClientOptions {
    const spec = this.agent.spec
    const base: ClientOptions = {
      ...this.options,
      // CALLER-WINS (ADR 0024 / issue #87). A `systemPrompt` the caller put on the client
      // options is an explicit instruction for THIS drive; the soul is the agent's default.
      // js used to let the soul override it, which python and csharp never did.
      systemPrompt: this.options.systemPrompt ?? spec.soul,
      // The spec's model and turn ceiling become Loop DEFAULTS — the same spec drives both
      // doors. `ClientOptions.model` is REQUIRED in js, so "the caller did not choose" is
      // spelled the way the runtime already spells it: absent, or the `"inherit"` sentinel.
      model: !this.options.model || this.options.model === "inherit"
        ? (spec.model && spec.model !== "inherit" ? spec.model : this.options.model)
        : this.options.model,
      maxTurns: this.options.maxTurns ?? spec.budget?.maxTurns,
      hooks: guardedHooks(spec.guardrails, spec.hooks ?? this.options.hooks),
    }
    if (!opts.model) return base
    return { ...base, requestParams: { ...(base.requestParams ?? {}), model: opts.model } }
  }
}
