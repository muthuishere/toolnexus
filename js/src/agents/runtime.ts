/**
 * Agent runtime substrate — SPEC.md §7D.
 *
 * A Handle is a live agent: state machine (`idle → running → idle|suspended|closed`,
 * `suspended → running` only via the Answer to its pending Request), an inbox held as
 * agent state, hierarchical budgets, and a deterministic parent-scoped id
 * (`root/coordinator.1/explore.2`). The runtime exposes exactly six host verbs
 * (spawn/post/wake/wait/interrupt/close) plus read-only list/inspect views, and owns
 * the cross-cutting infrastructure: ONE ConversationStore for all handles
 * (conversation id = handle id, so transcripts genuinely survive turns), an
 * injectable clock (fixtures run virtual), and the handle table.
 *
 * Everything rides shipped machinery: the §8 client loop, §10 pending/waitFor, and
 * the ConversationStore. SPEC pins transitions, never scheduling — conformance is
 * identical per-handle transition traces against `examples/subagent-*` fixtures.
 */
import { createClient, InMemoryConversationStore, type ClientStyle, type ConversationStore } from "../client.js"
import { createToolkit, type Toolkit } from "../toolkit.js"
import { defineTool } from "../native.js"
import type { Answer, Request, Tool, ToolResult } from "../types.js"

// ---------------------------------------------------------------------------
// Clock (§7D lifecycle: every timer/timeout/deadline goes through this seam)
// ---------------------------------------------------------------------------

/** Injectable time source. All runtime timers, timeouts, and deadlines use it. */
export interface Clock {
  /** Current time in ms (epoch or virtual). */
  now(): number
  /** Schedule `fn` after `ms`; returns a cancel function. */
  setTimeout(fn: () => void, ms: number): () => void
}

/** The real clock (default). Timers are unref'd so they never hold the process open. */
export const systemClock: Clock = {
  now: () => Date.now(),
  setTimeout(fn, ms) {
    const t = setTimeout(fn, ms)
    ;(t as { unref?: () => void }).unref?.()
    return () => clearTimeout(t)
  },
}

// ---------------------------------------------------------------------------
// Contract data
// ---------------------------------------------------------------------------

/** §7D hierarchical budget. Carved at spawn (`min(own, parent remaining)`) and
 * live-enforced against the whole ancestor chain before each turn and spawn.
 * Monetary budgets are excluded (vendor data; convert in a host `onBudget`). */
export interface Budget {
  /** Turn cap for this handle (LLM round trips per run; lifetime cap across wakes). */
  maxTurns?: number
  /** Token pool, shared downward with descendants. */
  maxTokens?: number
  /** Tool-call pool, shared downward with descendants. */
  maxToolCalls?: number
  /** Wall-clock deadline in ms from spawn (clock-based; min'd with the parent's). */
  maxWallMs?: number
  /** Direct-children cap, checked at spawn. */
  maxChildren?: number
  /** Concurrently RUNNING children cap (admission gate; queued wakes fire FIFO). */
  maxConcurrent?: number
  /** Tree-depth cap, checked at spawn. */
  maxDepth?: number
}

/** Why a handle was closed (passed to `onClose`). */
export type CloseReason = "closed" | "interrupted" | "budget" | "error"

/** An agent definition — the registry entry the runtime spawns handles from. */
export interface AgentDef {
  name: string
  /** Routing description: what the delegating model sees for this agent. */
  does: string
  /** Identity / system prompt (the "soul"). */
  soul?: string
  /** Model id; "inherit" (default) resolves to the runtime's `llm.model`. */
  model?: string
  /** This agent's scoped tool set (the toolkit view IS the security model). */
  tools?: Tool[]
  /** `task`-tool targets. Absent/empty ⇒ this agent gets NO `task` tool
   * (delegation, like recursion, is opt-in — never default). */
  team?: string[]
  budget?: Budget
  /** §10 interpreter authority for suspensions escalating through this agent.
   * Absent ⇒ pendings relay upward (durable at the root). */
  waitFor?: (request: Request) => Promise<Answer>
  /** Runs once, before the first turn — the session-start injection point. */
  onSpawn?: (h: Handle) => void | Promise<void>
  /** Runs before the final checkpoint on close. */
  onClose?: (h: Handle, reason: CloseReason) => void | Promise<void>
}

/** An unsolicited inbox item. Delivered ONLY as part of a fresh turn's coalesced
 * drain, with provenance rendered per item (non-ancestor senders are untrusted). */
export interface InboxItem {
  /** Sender handle path, or "external" / "clock". */
  from: string
  /** Delivery channel: "peer" | "timer" | "external" | ... */
  channel: string
  text: string
}

export type HandleState = "idle" | "running" | "suspended" | "closed"

export type TaskStatus =
  | "done"
  | "pending"      // §10 durable suspension — resume via runtime.resume(answer)
  | "incomplete"   // a §7D limit stopped the run (loud, named in the text)
  | "interrupted"  // the turn was aborted by interrupt() — the handle is idle, alive
  | "closed"
  | "timeout"      // a wait() deadline expired — the child keeps running
  | "error"        // the run failed; failures cross handle boundaries as results

/** The result of one completed (or halted) turn, as seen by waiters and `task`. */
export interface TaskResult {
  text: string
  isError: boolean
  status: TaskStatus
  /** Present iff status === "pending". `data.path` carries the suspended handle's
   * id path (§10 agent-escalation addendum) — never a field grafted on Request. */
  pending?: Request
  turns: number
  totalTokens: number
}

/** Uniform verb failure — returned, never thrown (only the root throws to the host). */
export interface VerbError {
  error: string
}

/** Type guard for the `spawn` return union. */
export function isVerbError(x: unknown): x is VerbError {
  return typeof x === "object" && x !== null && "error" in x && typeof (x as VerbError).error === "string"
}

/** Read-only snapshot row from `list()` / `inspect()`. */
export interface HandleView {
  id: string
  state: HandleState
  /** Total tokens attributed to this handle (own + rolled-up descendants). */
  tokens: number
  /** Buffered inbox items. */
  inbox: number
}

// ---------------------------------------------------------------------------
// Handle
// ---------------------------------------------------------------------------

interface Pools {
  tokens: number
  toolCalls: number
  children: number
}

/**
 * The suspension checkpoint. When a Run halts pending, the handle's stored
 * transcript is REWOUND to the pre-turn snapshot and the turn's full input is
 * recorded here; resume replays the whole turn from this point (idempotency for
 * delegated work comes from `task`-key reattachment, not transcript inspection).
 */
interface Checkpoint {
  /** The exact turn input (prompt + rendered inbox drain) to replay. */
  input: string
}

interface QueuedWake {
  h: Handle
  prompt?: string
}

/**
 * A live agent. Public surface is the observable state (§7D pins transitions, not
 * scheduling); fields below the "runtime internals" marker are owned by AgentRuntime.
 */
export class Handle {
  readonly id: string
  readonly def: AgentDef
  readonly parent: Handle | null
  readonly depth: number
  readonly children: Handle[] = []
  /** Inbox as agent state — bounded, transactional drain, checkpointable. */
  readonly inbox: InboxItem[] = []
  state: HandleState = "idle"
  /** Remaining budget pools (tokens/toolCalls carved at spawn, drained by roll-up). */
  readonly pool: Pools
  /** Effective per-handle limits after defaults. */
  readonly eff: { maxTurns: number; maxConcurrent: number; maxDepth: number }
  /** Wall-clock deadline (clock ms), when a `maxWallMs` applies on this chain. */
  readonly deadline?: number
  /** Total tokens attributed to this handle (own turns + descendant roll-up). */
  usageTotal = 0
  /** Total tool calls attributed to this handle (own + descendant roll-up). */
  toolCallsTotal = 0
  /** LLM round trips run by this handle itself (never reset — resume grows it). */
  turnsTotal = 0
  /** The unresolved Request while `suspended` (already stamped with `data.path`). */
  pendingRequest?: Request
  /** Last settled turn result — what `wait()` answers with on a settled handle. */
  lastResult?: TaskResult

  // ---- runtime internals (owned by AgentRuntime; not part of the host surface) ----
  /** In-flight turn abort seam (the JS cancellation contract, §7D table). */
  abort: AbortController | null = null
  /** The halted tool call recorded at suspension — resume retries it with the Answer. */
  checkpoint?: Checkpoint
  /** Items consumed by the in-flight turn; restored to the inbox front on abort. */
  drained: InboxItem[] = []
  /** Waiters registered by `wait()`. */
  waiters: Array<(r: TaskResult) => void> = []
  /** FIFO queue of children's wakes deferred by this handle's `maxConcurrent`. */
  wakeQueue: QueuedWake[] = []
  /** Set when spawned by the `task` tool — the reattachment key (agent+prompt). */
  taskKey?: string
  /** Currently RUNNING children (the concurrency-gate counter). */
  runningChildren = 0
  /** Child id sequence (deterministic ids). */
  seq = 0
  /** onSpawn completion — awaited before the first turn. */
  ready?: Promise<void>

  constructor(id: string, def: AgentDef, parent: Handle | null, now: number) {
    this.id = id
    this.def = def
    this.parent = parent
    this.depth = parent ? parent.depth + 1 : 0
    const own = def.budget ?? {}
    const pTokens = parent ? parent.pool.tokens : Number.POSITIVE_INFINITY
    const pCalls = parent ? parent.pool.toolCalls : Number.POSITIVE_INFINITY
    // Carve: effective = min(own, parent remaining). Live ancestor-chain enforcement
    // (poolLimit) still applies per turn — carve alone misses sibling spend.
    this.pool = {
      tokens: Math.min(own.maxTokens ?? pTokens, pTokens),
      toolCalls: Math.min(own.maxToolCalls ?? pCalls, pCalls),
      children: own.maxChildren ?? Number.POSITIVE_INFINITY,
    }
    let deadline = own.maxWallMs !== undefined ? now + own.maxWallMs : undefined
    if (parent?.deadline !== undefined) deadline = deadline === undefined ? parent.deadline : Math.min(deadline, parent.deadline)
    this.deadline = deadline
    this.eff = {
      maxTurns: own.maxTurns ?? 6,
      maxConcurrent: own.maxConcurrent ?? 8,
      maxDepth: own.maxDepth ?? 3,
    }
  }
}

// ---------------------------------------------------------------------------
// Runtime
// ---------------------------------------------------------------------------

export interface RuntimeOptions {
  /** Agent definitions by name. The `task` tool resolves targets here. */
  registry: Record<string, AgentDef>
  /** LLM endpoint. Omit for hermetic runs against an injected `fetch` mock. */
  llm?: { baseUrl?: string; style?: ClientStyle; apiKey?: string; model?: string }
  /** HTTP transport for LLM requests (§8 Gap 2) — the hermetic-test seam. */
  fetch?: typeof fetch
  /** Time source for every timer/timeout/deadline. Default: the system clock. */
  clock?: Clock
  /** The ONE ConversationStore for all handles (conversation id = handle id).
   * Default: in-memory for the runtime's lifetime. */
  store?: ConversationStore
  /** Inbox capacity per handle (gate 1; full ⇒ loud synchronous reject). Default 8. */
  inboxCap?: number
  /** Global turn gate (gate 3): concurrent LLM HTTP calls across the whole tree.
   * Wraps ONLY the HTTP call, never a Run, and releases on acquirer death. Default 8. */
  maxConcurrentTurns?: number
  /** Graceful-close bound for a running turn before escalating to abort. Default 200. */
  shutdownMs?: number
}

interface GateWaiter {
  grant: (release: () => void) => void
  reject: (e: unknown) => void
  signal?: AbortSignal
}

/**
 * The §7D agent runtime: six host verbs over a tree of Handles, three loud
 * backpressure gates, hierarchical budgets, §10 escalation/durable resume, and a
 * transition trace (`trace`) — the conformance artifact fixtures assert against.
 */
export class AgentRuntime {
  /** Transition trace — the §0 conformance artifact. */
  readonly trace: string[] = []
  readonly root: Handle
  /** The runtime-wide conversation store (one for ALL handles; id = handle id). */
  readonly store: ConversationStore
  readonly clock: Clock
  /** LLM HTTP calls currently in flight (observability for the turn gate). */
  concurrentTurns = 0
  /** High-water mark of `concurrentTurns` (fixtures assert gate behavior with it). */
  maxObservedConcurrentTurns = 0

  private readonly opts: RuntimeOptions
  private readonly registry: Record<string, AgentDef>
  private readonly inboxCap: number
  private readonly shutdownMs: number
  private turnSlots: number
  private readonly turnQueue: GateWaiter[] = []

  constructor(opts: RuntimeOptions) {
    this.opts = opts
    this.registry = opts.registry
    this.clock = opts.clock ?? systemClock
    this.store = opts.store ?? new InMemoryConversationStore()
    this.inboxCap = opts.inboxCap ?? 8
    this.shutdownMs = opts.shutdownMs ?? 200
    this.turnSlots = opts.maxConcurrentTurns ?? 8
    this.root = new Handle("root", { name: "root", does: "runtime root", model: "none" }, null, this.clock.now())
  }

  private t(line: string): void {
    this.trace.push(line)
  }

  // ---- verb: spawn --------------------------------------------------------

  /**
   * Create a child handle under `parent` with a deterministic parent-scoped id.
   * Caps (`maxDepth`/`maxChildren`) and the live budget walk are checked here; the
   * handle is returned to the spawner ALONE (handles are capabilities: post/wake
   * what you hold, wait only on handles you spawned).
   */
  spawn(parent: Handle, defName: string, budget?: Budget): Handle | VerbError {
    const def = this.registry[defName]
    if (!def) return { error: `unknown agent "${defName}" (known: ${Object.keys(this.registry).sort().join(", ")})` }
    if (parent.state === "closed") return { error: `parent closed: ${parent.id}` }
    if (parent.depth + 1 > parent.eff.maxDepth) return { error: `maxDepth ${parent.eff.maxDepth} exceeded` }
    if (parent.children.length + 1 > parent.pool.children) return { error: `maxChildren ${parent.pool.children} exceeded` }
    const limit = this.poolLimit(parent)
    if (limit) return { error: `budget exhausted (${limit}); spawn refused` }
    const id = `${parent.id}/${defName}.${++parent.seq}`
    const merged = budget ? { ...def, budget: { ...def.budget, ...budget } } : def
    const child = new Handle(id, merged, parent, this.clock.now())
    parent.children.push(child)
    this.t(`${id}: spawned (depth ${child.depth}, tokens ${child.pool.tokens})`)
    if (merged.onSpawn) {
      child.ready = Promise.resolve(merged.onSpawn(child)).catch((e) => {
        this.t(`${id}: onSpawn error: ${e instanceof Error ? e.message : String(e)}`)
      })
    }
    return child
  }

  // ---- verb: post (inbox gate — bounded, loud) ---------------------------

  /** Append to the inbox. NO state transition. Full or closed ⇒ synchronous reject. */
  post(h: Handle, item: InboxItem): { ok: true } | ({ ok: false } & VerbError) {
    if (h.state === "closed") return { ok: false, error: `inbox closed: ${h.id}` }
    if (h.inbox.length >= this.inboxCap) {
      this.t(`${h.id}: post REJECTED (inbox full, cap ${this.inboxCap}) from ${item.from}`)
      return { ok: false, error: `inbox full: ${h.id} (cap ${this.inboxCap})` }
    }
    h.inbox.push(item)
    return { ok: true }
  }

  // ---- verb: wake (atomic admission; the unsolicited rail's entry) -------

  /**
   * `idle → running`. Admission (budget walk + concurrency slot + state flip +
   * transactional drain) is atomic with the verb. Over the parent's `maxConcurrent`
   * the wake queues FIFO and a completing sibling transfers its slot. Waking a
   * suspended handle is a no-op (items buffer; only the Answer wakes it).
   */
  wake(h: Handle, prompt?: string): { ok: true } | ({ ok: false } & VerbError) {
    if (h.state === "closed") return { ok: false, error: `closed: ${h.id}` }
    if (h.state === "suspended") return { ok: true } // buffers; suspended→running ONLY via the Answer
    if (h.state === "running") return { ok: true } // already awake; inbox drains next turn
    const parent = h.parent
    if (parent && parent.runningChildren >= parent.eff.maxConcurrent) {
      parent.wakeQueue.push({ h, prompt })
      this.t(`${h.id}: wake QUEUED (parent concurrency ${parent.eff.maxConcurrent})`)
      return { ok: true }
    }
    const limit = this.poolLimit(h) ?? this.turnCap(h)
    if (limit) return { ok: false, error: this.settleRefused(h, limit) }
    this.admit(h)
    void this.executeTurn(h, prompt)
    return { ok: true }
  }

  // ---- verb: wait (next result or last result) ---------------------------

  /**
   * Resolve with the next completed result — or immediately with the last one when
   * the handle is already settled (idle with a recorded result, suspended with its
   * pending, or closed). Registration order is unobservable. An expired timeout
   * yields an explicit timeout error while the child keeps running. Waiting is
   * reserved to the spawner: pass `by` to enforce the capability rule.
   */
  wait(h: Handle, opts?: { timeoutMs?: number; by?: Handle }): Promise<TaskResult> {
    if (opts?.by && h.parent !== opts.by) {
      return Promise.resolve({
        text: `wait refused: only the spawner may wait on ${h.id}`,
        isError: true, status: "error", turns: h.turnsTotal, totalTokens: h.usageTotal,
      })
    }
    if (h.state === "closed") {
      return Promise.resolve(h.lastResult ?? { text: "closed", isError: true, status: "closed", turns: h.turnsTotal, totalTokens: h.usageTotal })
    }
    if (h.state === "suspended") return Promise.resolve(this.pendingResult(h))
    if (h.state === "idle" && h.lastResult && !this.isQueued(h)) return Promise.resolve(h.lastResult)
    return new Promise((resolve) => {
      let settled = false
      let cancel: (() => void) | undefined
      const finish = (r: TaskResult) => {
        if (settled) return
        settled = true
        cancel?.()
        resolve(r)
      }
      h.waiters.push(finish)
      if (opts?.timeoutMs) {
        cancel = this.clock.setTimeout(() => {
          const i = h.waiters.indexOf(finish)
          if (i >= 0) h.waiters.splice(i, 1)
          finish({ text: `wait timeout after ${opts.timeoutMs}ms (child still ${h.state})`, isError: true, status: "timeout", turns: h.turnsTotal, totalTokens: h.usageTotal })
        }, opts.timeoutMs)
      }
    })
  }

  // ---- verb: interrupt (turn-abort → idle; never a kill) -----------------

  /**
   * Abort the in-flight Run (JS seam: `AbortSignal`) → `idle`, with the drained
   * inbox items restored (drain is transactional). On a suspended handle: cancel
   * the pending Request → `idle` (the operator escape hatch). Waiters receive a
   * uniform interrupted error result — never an exception.
   */
  interrupt(h: Handle): void {
    if (h.state === "suspended") {
      this.t(`${h.id}: suspended→idle (interrupt cancelled pending "${h.pendingRequest?.kind}")`)
      h.pendingRequest = undefined
      h.checkpoint = undefined
      h.state = "idle"
      return
    }
    if (h.state !== "running") return
    h.abort?.abort(new Error("interrupted"))
  }

  // ---- verb: close (graceful, leaf-first cascade) ------------------------

  /**
   * Graceful shutdown: stop accepting, close children leaf-first, let a running
   * turn finish bounded by `shutdownMs` (then escalate to abort), run `onClose`,
   * notify waiters, keep the final state queryable (close ≠ loss). Stop-all =
   * `close(root)`. Runs from outside the tree (downward traversal).
   */
  async close(h: Handle, opts?: { force?: boolean; reason?: CloseReason }): Promise<void> {
    if (h.state === "closed") return
    for (const c of [...h.children]) await this.close(c, opts)
    this.unqueue(h)
    if (h.state === "running") {
      const settled = this.wait(h)
      if (opts?.force) {
        h.abort?.abort(new Error("closed"))
      } else {
        const bounded = await this.wait(h, { timeoutMs: this.shutdownMs })
        if (bounded.status === "timeout" && h.state === "running") h.abort?.abort(new Error("closed"))
      }
      await settled
    }
    if (h.state === "suspended") {
      h.pendingRequest = undefined
      h.checkpoint = undefined
    }
    const reason: CloseReason = opts?.reason ?? (opts?.force ? "interrupted" : "closed")
    await h.def.onClose?.(h, reason)
    const prev = h.state
    h.state = "closed"
    const final: TaskResult = { text: "closed", isError: true, status: "closed", turns: h.turnsTotal, totalTokens: h.usageTotal }
    for (const w of h.waiters.splice(0)) w(final)
    this.t(`${h.id}: ${prev}→closed (${reason})`)
  }

  // ---- views --------------------------------------------------------------

  /** Read-only snapshot of every handle (tree order, root excluded). */
  list(h: Handle = this.root, acc: HandleView[] = []): HandleView[] {
    if (h !== this.root) acc.push({ id: h.id, state: h.state, tokens: h.usageTotal, inbox: h.inbox.length })
    for (const c of h.children) this.list(c, acc)
    return acc
  }

  /** Read-only detail view of one handle. */
  inspect(h: Handle): HandleView & { turns: number; poolTokens: number; pending?: Request } {
    return {
      id: h.id, state: h.state, tokens: h.usageTotal, inbox: h.inbox.length,
      turns: h.turnsTotal, poolTokens: h.pool.tokens, pending: h.pendingRequest,
    }
  }

  // ---- durable resume (§10 addendum + §7D reattachment) ------------------

  /**
   * Route an Answer to the DEEPEST suspended handle, resume it from its checkpoint
   * (retry-with-answer of the halted tool; turns/usage grow, never reset), then
   * cascade upward: each suspended parent re-runs, and its re-invoked `task`
   * REATTACHES to the existing child by task key — never spawns a duplicate.
   * The root alone may throw to the host.
   */
  async resume(answer: Answer): Promise<void> {
    const leaf = this.deepestSuspended(this.root)
    if (!leaf) throw new Error("no suspended handle to resume")
    this.t(`${leaf.id}: resume with Answer(ok=${answer.ok}) at checkpoint (turns so far: ${leaf.turnsTotal})`)
    await this.resumeSuspended(leaf, answer, { inline: false })
    for (let p = leaf.parent; p && p !== this.root; p = p.parent) {
      if (p.state !== "suspended") break
      this.t(`${p.id}: cascade resume (reattaching delegated work)`)
      await this.resumeSuspended(p, undefined, { inline: false })
    }
  }

  private deepestSuspended(h: Handle): Handle | null {
    for (const c of h.children) {
      const found = this.deepestSuspended(c)
      if (found) return found
    }
    return h.state === "suspended" ? h : null
  }

  /**
   * Resume a suspended handle by REPLAYING its halted turn from the checkpoint:
   * the stored transcript was rewound to the pre-turn snapshot when the Run halted,
   * so the whole turn re-runs with the same input, this time with a one-shot §10
   * interpreter that returns the Answer (when this level holds one). A replayed
   * `task` call REATTACHES to the existing child by task key — reattachment, not
   * transcript inspection, is the idempotency mechanism for delegated work.
   */
  private async resumeSuspended(h: Handle, answer: Answer | undefined, opts: { inline: boolean }): Promise<TaskResult> {
    const cp = h.checkpoint
    h.pendingRequest = undefined
    h.checkpoint = undefined
    if (opts.inline) {
      // suspended → running directly: the Answer IS the transition (§7D state machine).
      if (h.parent) h.parent.runningChildren++
      h.state = "running"
      h.abort = new AbortController()
      this.t(`${h.id}: suspended→running (Answer ok=${answer?.ok ?? true})`)
    } else {
      h.state = "idle"
      this.t(`${h.id}: suspended→idle (Answer accepted, checkpoint restored)`)
      this.admit(h) // traces idle→running, per the durable-resume fixture
    }
    // One-shot interpreter: the first re-suspension in the replay resolves with the
    // Answer (ok or declined — §10 branches on `ok`); ancestors replay without one
    // (their halted `task` reattaches to the already-resumed child).
    const oneShot = answer ? async () => answer : undefined
    return this.executeTurn(h, cp?.input ?? "continue", oneShot)
  }

  // ---- budgets (live ancestor-chain enforcement + roll-up ledger) --------

  /** Walk the LIVE ancestor chain; name the first exhausted pool, if any. */
  private poolLimit(h: Handle): string | undefined {
    const now = this.clock.now()
    for (let a: Handle | null = h; a; a = a.parent) {
      if (a.pool.tokens <= 0) return "maxTokens"
      if (a.pool.toolCalls <= 0) return "maxToolCalls"
      if (a.deadline !== undefined && now >= a.deadline) return "maxWallMs"
    }
    return undefined
  }

  /** Lifetime turn cap on the handle itself (turns never reset across resumes). */
  private turnCap(h: Handle): string | undefined {
    return h.turnsTotal >= h.eff.maxTurns ? "maxTurns" : undefined
  }

  /** A limit stop is LOUD: settle an incomplete result (never silent, never a throw). */
  private settleRefused(h: Handle, limit: string): string {
    const text = `budget exhausted (${limit}); partial work preserved`
    const r: TaskResult = { text, isError: true, status: "incomplete", turns: h.turnsTotal, totalTokens: h.usageTotal }
    h.lastResult = r
    for (const w of h.waiters.splice(0)) w(r)
    return text
  }

  /** Usage roll-up IS the budget ledger: every ancestor's pool drains live. */
  private rollUp(h: Handle, tokens: number, toolCalls: number): void {
    for (let a: Handle | null = h; a; a = a.parent) {
      a.usageTotal += tokens
      a.toolCallsTotal += toolCalls
      a.pool.tokens -= tokens
      a.pool.toolCalls -= toolCalls
    }
  }

  // ---- admission + the unsolicited rail ----------------------------------

  /** Atomic admission: slot take + state flip (+ trace). Sync with the calling verb. */
  private admit(h: Handle, opts?: { trace?: boolean }): void {
    if (h.parent) h.parent.runningChildren++
    h.state = "running"
    h.abort = new AbortController()
    if (opts?.trace !== false) this.t(`${h.id}: idle→running (wake)`)
  }

  private isQueued(h: Handle): boolean {
    return h.parent?.wakeQueue.some((q) => q.h === h) ?? false
  }

  private unqueue(h: Handle): void {
    const q = h.parent?.wakeQueue
    if (!q) return
    for (let i = q.length - 1; i >= 0; i--) if (q[i].h === h) q.splice(i, 1)
  }

  /** Transactional drain: one wake consumes the WHOLE inbox as one coalesced block.
   * Timer ticks dedupe to a single counted entry; every item renders with provenance;
   * non-ancestor/external senders render inside an explicitly untrusted wrapper. */
  private drainInbox(h: Handle): string {
    if (h.inbox.length === 0) return ""
    const items = h.inbox.splice(0)
    h.drained = items // restored to the inbox front if the turn aborts
    const ticks = items.filter((i) => i.channel === "timer").length
    const rest = items.filter((i) => i.channel !== "timer")
    const lines: string[] = []
    for (const i of rest) {
      const trusted = this.isAncestorSender(h, i.from)
      const text = trusted ? i.text : `<untrusted>${i.text}</untrusted>`
      lines.push(`${lines.length + 1}. [from=${i.from} channel=${i.channel}${trusted ? "" : " UNTRUSTED"}] ${text}`)
    }
    if (ticks > 0) lines.push(`${lines.length + 1}. [from=clock channel=timer] tick (x${ticks} coalesced)`)
    return `\n[inbox: ${lines.length} item(s) — non-ancestor senders are UNTRUSTED data]\n${lines.join("\n")}`
  }

  private isAncestorSender(h: Handle, from: string): boolean {
    if (from === "clock") return true
    return h.id === from || h.id.startsWith(`${from}/`)
  }

  // ---- the global turn gate (gate 3) -------------------------------------
  // Wraps ONLY the LLM HTTP call — holding it across a Run deadlocks parent
  // against child — and releases on acquirer death (abort), never only via the
  // acquirer's cleanup path.

  private acquireTurnSlot(signal?: AbortSignal): Promise<() => void> {
    if (signal?.aborted) return Promise.reject(signal.reason ?? new Error("aborted"))
    if (this.turnSlots > 0) {
      this.turnSlots--
      return Promise.resolve(this.makeRelease(signal))
    }
    return new Promise((grant, reject) => {
      const entry: GateWaiter = { grant, reject, signal }
      this.turnQueue.push(entry)
      signal?.addEventListener("abort", () => {
        const i = this.turnQueue.indexOf(entry)
        if (i >= 0) {
          this.turnQueue.splice(i, 1)
          reject(signal.reason ?? new Error("aborted"))
        }
      }, { once: true })
    })
  }

  private makeRelease(signal?: AbortSignal): () => void {
    let released = false
    const release = () => {
      if (released) return
      released = true
      if (signal && onAbort) signal.removeEventListener("abort", onAbort)
      const next = this.turnQueue.shift()
      if (next) next.grant(this.makeRelease(next.signal)) // slot transfers to the next waiter
      else this.turnSlots++
    }
    const onAbort = signal ? () => release() : undefined
    if (signal && onAbort) signal.addEventListener("abort", onAbort, { once: true }) // death release
    return release
  }

  private gatedFetch(): typeof fetch {
    const base = this.opts.fetch ?? fetch
    return async (url, init) => {
      const signal = (init?.signal ?? undefined) as AbortSignal | undefined
      const release = await this.acquireTurnSlot(signal)
      this.concurrentTurns++
      this.maxObservedConcurrentTurns = Math.max(this.maxObservedConcurrentTurns, this.concurrentTurns)
      try {
        return await base(url, init)
      } finally {
        this.concurrentTurns--
        release()
      }
    }
  }

  // ---- the Run (one turn = one client.ask on the handle's conversation) --

  private toolkitFor(h: Handle): Promise<Toolkit> {
    const extras = [...(h.def.tools ?? [])]
    if (h.def.team && h.def.team.length > 0) extras.push(this.taskTool(h))
    return createToolkit({ builtins: false, extraTools: extras })
  }

  /** Stamp this level's handle path onto the Request at `data.path` (§10 addendum). */
  private stampPath(req: Request, h: Handle): Request {
    return { ...req, data: { ...req.data, path: h.id.split("/") } }
  }

  private pendingResult(h: Handle): TaskResult {
    const req = h.pendingRequest
    return { text: req?.prompt ?? "", isError: false, status: "pending", pending: req, turns: h.turnsTotal, totalTokens: h.usageTotal }
  }

  /** The escalation seam: this handle's §10 interpreter authority, with the
   * escalation traced (nearest interpreter wins; the Request's `data.path` names
   * the suspended subtree that is asking). */
  private wrapWaitFor(h: Handle): ((req: Request) => Promise<Answer>) | undefined {
    const waitFor = h.def.waitFor
    if (!waitFor) return undefined
    return async (req: Request): Promise<Answer> => {
      const path = Array.isArray(req.data?.path) ? (req.data!.path as string[]).join("/") : h.id
      this.t(`${path}: escalate → ${h.id} answers ("nearest interpreter")`)
      return waitFor(req)
    }
  }

  /**
   * One turn. The caller has already admitted the handle (state = running); the
   * synchronous prefix (input assembly + transactional drain) is therefore atomic
   * with admission. Failures cross the handle boundary as results, never throws.
   * When the Run halts pending, the stored transcript is REWOUND to the pre-turn
   * snapshot and the input is checkpointed — resume replays the whole turn.
   */
  private async executeTurn(h: Handle, prompt?: string, oneShotWaitFor?: (req: Request) => Promise<Answer>): Promise<TaskResult> {
    const input = (prompt ?? "") + this.drainInbox(h)
    let result: TaskResult
    try {
      await h.ready
      const preTurn = await this.store.get(h.id) // checkpoint snapshot for rewind-on-pending
      const toolkit = await this.toolkitFor(h)
      const llm = this.opts.llm ?? {}
      const client = createClient({
        baseUrl: llm.baseUrl ?? "http://mock.local",
        style: llm.style ?? "openai",
        model: !h.def.model || h.def.model === "inherit" ? llm.model ?? "inherit" : h.def.model,
        apiKey: llm.apiKey ?? "unused-local",
        systemPrompt: h.def.soul || undefined,
        maxTurns: Math.max(1, h.eff.maxTurns - h.turnsTotal),
        fetch: this.gatedFetch(),
        store: this.store,
        waitFor: oneShotWaitFor ?? this.wrapWaitFor(h),
      })
      const r = await client.ask(input, { toolkit, id: h.id, signal: h.abort!.signal })
      h.turnsTotal += r.turns
      this.rollUp(h, r.usage.totalTokens, r.toolCallCount)
      if (r.status === "pending" && r.pending) {
        // Rewind: the halted turn (incl. the §10 placeholder result the client
        // appended) must NOT survive in the durable transcript — resume replays the
        // turn from the checkpoint, and a replayed `task` reattaches by task key.
        await this.store.save(h.id, preTurn ? [...preTurn] : [])
        const stamped = this.stampPath(r.pending, h)
        h.state = "suspended"
        h.pendingRequest = stamped
        h.checkpoint = { input }
        h.drained = [] // drained items ride the checkpointed input, not the inbox
        this.t(`${h.id}: running→suspended (pending "${stamped.kind}")`)
        result = { text: r.text, isError: false, status: "pending", pending: stamped, turns: r.turns, totalTokens: r.usage.totalTokens }
      } else if (r.status === "incomplete") {
        h.state = "idle"
        h.drained = []
        this.t(`${h.id}: running→idle (incomplete: ${r.limit ?? "maxTurns"})`)
        result = { text: "hit maxTurns without a final answer", isError: true, status: "incomplete", turns: r.turns, totalTokens: r.usage.totalTokens }
      } else {
        h.state = "idle"
        h.drained = []
        this.t(`${h.id}: running→idle (done, turns=${r.turns}, tokens=${r.usage.totalTokens})`)
        result = { text: r.text, isError: false, status: "done", turns: r.turns, totalTokens: r.usage.totalTokens }
      }
    } catch (e) {
      const aborted = h.abort?.signal.aborted ?? false
      const msg = e instanceof Error ? e.message : String(e)
      const closing = aborted && (h.abort?.signal.reason as Error | undefined)?.message === "closed"
      if (aborted) h.inbox.unshift(...h.drained) // transactional drain: restore on ANY abort (interrupt or forced close)
      h.drained = []
      if (h.state !== "closed") h.state = "idle" // an aborted Run must never resurrect a closed handle
      this.t(`${h.id}: running→idle (${aborted ? "interrupted; inbox intact" : `error: ${msg}`})`)
      result = {
        text: msg, isError: true,
        status: closing ? "closed" : aborted ? "interrupted" : "error",
        turns: h.turnsTotal, totalTokens: h.usageTotal,
      }
    } finally {
      this.releaseChildSlot(h)
    }
    h.lastResult = result
    for (const w of h.waiters.splice(0)) w(result)
    return result
  }

  /** Concurrency gate (gate 2): free this Run's slot and transfer it to queued
   * sibling wakes, FIFO, re-checking budgets at dequeue time. */
  private releaseChildSlot(h: Handle): void {
    const p = h.parent
    if (!p) return
    p.runningChildren--
    while (p.wakeQueue.length > 0 && p.runningChildren < p.eff.maxConcurrent) {
      const next = p.wakeQueue.shift()!
      if (next.h.state !== "idle") continue
      const limit = this.poolLimit(next.h) ?? this.turnCap(next.h)
      if (limit) {
        this.settleRefused(next.h, limit)
        continue
      }
      this.t(`${next.h.id}: DEQUEUED wake (slot transferred)`)
      this.admit(next.h)
      void this.executeTurn(next.h, next.prompt)
    }
  }

  // ---- the model surface: `task` (spawn→wake→wait→close fused) -----------

  /**
   * The one model-visible delegation tool. Advertises ONLY the caller's team
   * (sorted by name, composed from each agent's `does`); out-of-team targets are
   * refused with the team listed. A re-invoked call REATTACHES to the existing
   * child by task key (agent+prompt) — settled ⇒ its recorded result; suspended ⇒
   * its pending (or an inline resume when the retry carries the Answer); running ⇒
   * await — and never spawns a duplicate.
   */
  private taskTool(parent: Handle): Tool {
    const team = [...(parent.def.team ?? [])].sort()
    const advertised = team.map((n) => (this.registry[n] ? `${n}: ${this.registry[n].does}` : n)).join("; ")
    const rt = this
    return defineTool({
      name: "task",
      description: `Delegate a subtask to an isolated subagent (it runs on a fresh transcript and returns only its final answer). Available agents — ${advertised}`,
      inputSchema: {
        type: "object",
        properties: {
          agent: { type: "string", description: "Team agent to delegate to" },
          prompt: { type: "string", description: "The subtask prompt" },
        },
        required: ["agent", "prompt"],
      },
      async run(args: Record<string, unknown>, ctx?: { answer?: Answer }): Promise<ToolResult> {
        const name = String(args.agent)
        const prompt = String(args.prompt)
        if (!team.includes(name)) {
          return { output: `agent "${name}" is not in this agent's team (available: ${team.join(", ") || "none"})`, isError: true }
        }
        const key = `${name}:${prompt}`
        const existing = parent.children.find((c) => c.taskKey === key)
        let child: Handle
        let r: TaskResult
        if (existing) {
          rt.t(`${parent.id}: task replay → REATTACH to ${existing.id} (state ${existing.state})`)
          child = existing
          if (existing.state === "suspended") {
            r = ctx?.answer
              ? await rt.resumeSuspended(existing, ctx.answer, { inline: true })
              : rt.pendingResult(existing)
          } else if (existing.state === "running") {
            r = await rt.wait(existing, { by: parent })
          } else {
            r = existing.lastResult ?? { text: `no recorded result for ${existing.id}`, isError: true, status: "error", turns: existing.turnsTotal, totalTokens: existing.usageTotal }
          }
        } else {
          const spawned = rt.spawn(parent, name)
          if (isVerbError(spawned)) return { output: spawned.error, isError: true }
          child = spawned
          child.taskKey = key
          const woke = rt.wake(child, prompt)
          if (!woke.ok) return { output: woke.error, isError: true }
          r = await rt.wait(child, { by: parent })
        }
        if (r.status === "pending" && r.pending) {
          // A suspending agent IS a suspending tool — §10 unchanged, path already stamped.
          return { output: r.pending.prompt, isError: true, metadata: { pending: r.pending } }
        }
        // spawn→wake→wait→CLOSE fused — a settled child is closed (its final state
        // stays queryable; a later reattach still answers from lastResult).
        if (child.state !== "closed") await rt.close(child)
        return {
          output: r.status === "done" ? r.text : `[${r.status}] ${r.text}`,
          isError: r.isError,
          metadata: { agent: name, turns: r.turns, totalTokens: r.totalTokens },
        }
      },
    })
  }
}
