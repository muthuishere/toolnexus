/**
 * A2A (agent-to-agent) — OUTBOUND: call remote A2A agents.
 *
 * A remote agent publishes an Agent Card at `/.well-known/agent-card.json`
 * describing its skills. Each advertised skill becomes a uniform `Tool`
 * (source:"a2a") whose `execute` performs one JSON-RPC `SendMessage`
 * (non-blocking) to get a Task id, then polls `GetTask` until the Task reaches
 * a terminal state — mapping the Task's artifact/message text to a ToolResult.
 *
 * This is a genuine subset of real A2A (JSON-RPC 2.0 over one HTTP POST
 * endpoint), so a toolnexus agent interoperates with real A2A peers. See
 * ../../SPEC.md and openspec/changes/add-a2a-agents. Reuses the `${ENV}` header
 * expansion from mcp.ts; secrets live in the environment and are never logged.
 */
import { randomUUID } from "node:crypto"
import { type JSONSchema, type Tool, type ToolContext, type ToolResult, sanitize } from "./types.js"
import { expandEnvHeaders } from "./mcp.js"

const DEFAULT_TIMEOUT = 300_000
const DEFAULT_POLL_EVERY = 1_000

/** Terminal A2A task states — polling stops once one of these is reached. */
const TERMINAL = new Set(["completed", "failed", "canceled"])

// ---------------------------------------------------------------------------
// Wire types (the A2A subset we speak — verified against a2a-python).
// ---------------------------------------------------------------------------

export type A2ATaskState = "submitted" | "working" | "completed" | "failed" | "canceled" | string

export interface A2APart {
  kind: string
  text?: string
  [k: string]: unknown
}

export interface A2AMessage {
  role: string
  messageId?: string
  parts?: A2APart[]
  [k: string]: unknown
}

export interface A2AArtifact {
  parts?: A2APart[]
  [k: string]: unknown
}

export interface A2ATaskStatus {
  state: A2ATaskState
  message?: A2AMessage
  [k: string]: unknown
}

export interface A2ATask {
  id: string
  /** Groups a peer's turns into one conversation (A2A contextId). */
  contextId?: string
  status: A2ATaskStatus
  artifacts?: A2AArtifact[]
  history?: A2AMessage[]
  [k: string]: unknown
}

export interface A2ASkill {
  id?: string
  name?: string
  description?: string
  tags?: string[]
  examples?: string[]
  [k: string]: unknown
}

export interface AgentCard {
  name: string
  description?: string
  version?: string
  protocolVersion?: string
  capabilities?: { streaming?: boolean; pushNotifications?: boolean; [k: string]: unknown }
  defaultInputModes?: string[]
  defaultOutputModes?: string[]
  skills?: A2ASkill[]
  /** The JSON-RPC endpoint the agent listens on. */
  url?: string
  [k: string]: unknown
}

// ---------------------------------------------------------------------------
// Agent descriptor + factory.
// ---------------------------------------------------------------------------

export interface Agent {
  /** URL of the Agent Card (`/.well-known/agent-card.json`). */
  card: string
  /** `${ENV_VAR}` header values are expanded at call time and never logged. */
  headers?: Record<string, string>
  /** Overall poll budget in ms (default 300000). */
  timeout?: number
  /** Interval between `GetTask` polls in ms (default 1000). */
  pollEvery?: number
}

/** Build an Agent descriptor pointing at a remote Agent Card URL. */
export function agent(opts: Agent): Agent {
  return { card: opts.card, headers: opts.headers, timeout: opts.timeout, pollEvery: opts.pollEvery }
}

/** Normalize either an Agent descriptor or a bare card URL into an Agent. */
function toAgent(agentOrCardUrl: Agent | string, opts?: Omit<Agent, "card">): Agent {
  if (typeof agentOrCardUrl === "string") return agent({ card: agentOrCardUrl, ...opts })
  return agentOrCardUrl
}

// ---------------------------------------------------------------------------
// Config parsing — an `agents` block mirroring `mcpServers` (§2).
// ---------------------------------------------------------------------------

export interface AgentConfig {
  card: string
  headers?: Record<string, string>
  timeout?: number
  pollEvery?: number
  enabled?: boolean
  disabled?: boolean
}

export type AgentsConfig = Record<string, AgentConfig>

/** MCP `isEnabled` precedence: `disabled:true` wins, then `enabled:false`. */
function isEnabled(cfg: AgentConfig): boolean {
  if (cfg.disabled === true) return false
  if (cfg.enabled === false) return false
  return true
}

/**
 * Parse an `agents` block (Record<name, AgentConfig>) into Agent descriptors,
 * skipping disabled entries. The config key is just an identifier — a tool's
 * name prefix comes from the fetched card's `name`, not the key.
 */
export function parseAgentsConfig(block: AgentsConfig | undefined): Agent[] {
  if (!block) return []
  const out: Agent[] = []
  for (const cfg of Object.values(block)) {
    if (!cfg || typeof cfg.card !== "string") continue
    if (!isEnabled(cfg)) continue
    out.push(agent({ card: cfg.card, headers: cfg.headers, timeout: cfg.timeout, pollEvery: cfg.pollEvery }))
  }
  return out
}

// ---------------------------------------------------------------------------
// JSON-RPC transport (single POST) + card fetch. Reuses httpTool's ${ENV}
// header expansion, timeout handling, and non-2xx → error mapping.
// ---------------------------------------------------------------------------

/** POST one JSON-RPC 2.0 request and return `result` (throws on error/non-2xx). */
async function jsonRpc(
  endpoint: string,
  method: string,
  params: unknown,
  headers: Record<string, string>,
  timeout: number,
  signal?: AbortSignal,
): Promise<unknown> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true })
  try {
    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...headers },
      body: JSON.stringify({ jsonrpc: "2.0", id: randomUUID(), method, params }),
      signal: controller.signal,
    })
    const text = await res.text()
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`)
    const payload = safeParse(text) as { result?: unknown; error?: { message?: string; code?: number } }
    if (payload && payload.error) {
      throw new Error(payload.error.message ?? `JSON-RPC error ${payload.error.code ?? ""}`.trim())
    }
    return payload?.result
  } finally {
    clearTimeout(timer)
  }
}

/** Fetch and parse the Agent Card at its URL (GET). */
async function fetchCard(
  cardUrl: string,
  headers: Record<string, string>,
  timeout: number,
  signal?: AbortSignal,
): Promise<AgentCard> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)
  if (signal) signal.addEventListener("abort", () => controller.abort(), { once: true })
  try {
    const res = await fetch(cardUrl, { method: "GET", headers, signal: controller.signal })
    const text = await res.text()
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`)
    return JSON.parse(text) as AgentCard
  } finally {
    clearTimeout(timer)
  }
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/** Abortable sleep — resolves early (without error) if `signal` fires. */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve()
    const onAbort = () => {
      clearTimeout(timer)
      resolve()
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort)
      resolve()
    }, ms)
    signal?.addEventListener("abort", onAbort, { once: true })
  })
}

/** Concatenate a Task's text output: artifact text parts, else last agent message. */
function extractOutput(task: A2ATask): string {
  const parts: string[] = []
  for (const artifact of task.artifacts ?? []) {
    for (const part of artifact.parts ?? []) {
      if (part.kind === "text" && typeof part.text === "string") parts.push(part.text)
    }
  }
  if (parts.length > 0) return parts.join("\n")
  // Fallback: the last agent message in history.
  const history = task.history ?? []
  for (let i = history.length - 1; i >= 0; i--) {
    const msg = history[i]
    if (msg.role !== "agent") continue
    const text = (msg.parts ?? [])
      .filter((p) => p.kind === "text" && typeof p.text === "string")
      .map((p) => p.text)
      .join("\n")
    if (text) return text
  }
  return ""
}

/** Text of a Task's status.message (used for failed/canceled error output). */
function statusMessageText(task: A2ATask): string {
  const msg = task.status?.message
  if (!msg) return ""
  return (msg.parts ?? [])
    .filter((p) => p.kind === "text" && typeof p.text === "string")
    .map((p) => p.text)
    .join("\n")
}

// ---------------------------------------------------------------------------
// Skill → Tool.
// ---------------------------------------------------------------------------

const TASK_SCHEMA: JSONSchema = {
  type: "object",
  properties: { task: { type: "string", description: "The task to send to the agent, in natural language." } },
  required: ["task"],
  additionalProperties: false,
}

/** Build one `source:"a2a"` Tool for a single advertised skill of an agent. */
function skillTool(agentName: string, endpoint: string, skill: A2ASkill, ag: Agent): Tool {
  const skillId = skill.id ?? skill.name ?? ""
  const name = sanitize(agentName) + "_" + sanitize(skillId)
  const timeout = ag.timeout ?? DEFAULT_TIMEOUT
  const pollEvery = ag.pollEvery ?? DEFAULT_POLL_EVERY
  return {
    name,
    description: skill.description ?? skill.name ?? skillId,
    inputSchema: TASK_SCHEMA,
    source: "a2a",
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      const start = Date.now()
      const budget = ctx?.timeout ?? timeout
      const headers = expandEnvHeaders(ag.headers) ?? {}
      const taskText = String(args?.task ?? "")
      let polls = 0
      let taskId = ""
      let state: A2ATaskState = "submitted"
      const meta = () => ({ agent: agentName, taskId, state, polls, ms: Date.now() - start })
      try {
        // 1. SendMessage (non-blocking) → a submitted Task.
        let task = (await jsonRpc(
          endpoint,
          "SendMessage",
          {
            message: { role: "user", messageId: randomUUID(), parts: [{ kind: "text", text: taskText }] },
            configuration: { blocking: false },
          },
          headers,
          budget,
          ctx?.signal,
        )) as A2ATask
        taskId = task?.id ?? ""
        state = task?.status?.state ?? "submitted"

        // 2. Poll GetTask until terminal / timeout / cancel.
        while (!TERMINAL.has(state)) {
          if (ctx?.signal?.aborted) {
            state = "canceled"
            return { output: `A2A task ${taskId} canceled`, isError: true, metadata: meta() }
          }
          if (Date.now() - start >= budget) {
            return {
              output: `A2A task ${taskId} timed out after ${budget}ms (state=${state})`,
              isError: true,
              metadata: meta(),
            }
          }
          await sleep(pollEvery, ctx?.signal)
          // Cancelled during the wait → stop before another GetTask.
          if (ctx?.signal?.aborted) {
            state = "canceled"
            return { output: `A2A task ${taskId} canceled`, isError: true, metadata: meta() }
          }
          task = (await jsonRpc(endpoint, "GetTask", { id: taskId }, headers, budget, ctx?.signal)) as A2ATask
          polls++
          state = task?.status?.state ?? state
        }

        // 3. Map the terminal Task → ToolResult.
        if (state === "completed") {
          return { output: extractOutput(task), isError: false, metadata: meta() }
        }
        const detail = statusMessageText(task)
        return {
          output: `A2A task ${taskId} ${state}${detail ? `: ${detail}` : ""}`,
          isError: true,
          metadata: meta(),
        }
      } catch (e) {
        return { output: e instanceof Error ? e.message : String(e), isError: true, metadata: meta() }
      }
    },
  }
}

/**
 * Resolve an Agent to its tools: fetch the card, read `skills[]`, and produce
 * one Tool per skill. The agent name prefix comes from the card's `name`.
 */
export async function agentTools(ag: Agent): Promise<Tool[]> {
  const timeout = ag.timeout ?? DEFAULT_TIMEOUT
  const headers = expandEnvHeaders(ag.headers) ?? {}
  const card = await fetchCard(ag.card, headers, timeout)
  const agentName = card.name ?? "agent"
  // The card's `url` is the JSON-RPC endpoint; fall back to the card origin.
  const endpoint = card.url ?? new URL(ag.card).origin
  return (card.skills ?? []).map((skill) => skillTool(agentName, endpoint, skill, ag))
}
