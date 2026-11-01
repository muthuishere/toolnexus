/**
 * Tool tiers (SPEC §8 "Prompt caching" → Tool tiers, change add-prompt-cache phase 2). With
 * `ClientOptions.toolTiers` set, only CORE tools are sent with full schemas. LISTED tools appear
 * as "name — one line" and DEFERRED tools by name only, both inside the description of a
 * `tool_search` tool that returns full schemas on demand, as a tool result: the tools array
 * never changes mid-session, so the cached prefix survives. A loaded tool is called by name, or
 * through `call_tool {name, arguments}`, which the loop unwraps BEFORE hooks.
 */
import type { Tool, ToolResult } from "./types.js"

/** `ClientOptions.toolTiers`. */
export interface ToolTierOptions {
  /** Always sent with their full schema. */
  core?: string[]
  /** Named only. Every other tool is listed (name + one line). A tool registered after the
   * plan froze is deferred and found by search. */
  deferred?: string[]
  /** Caps the tokens sent up front (core schemas + tool_search + call_tool). Absent/0 ⇒ 5% of
   * `contextWindow`; both absent ⇒ no budget. Over budget, the least-used core tools move to
   * listed, then the least-used listed tools to deferred. */
  budgetTokens?: number
  contextWindow?: number
  /** Host per-tool usage stat (higher = more used), read only when the plan is built. */
  usageCount?: (name: string) => number
  /** Token estimate of a JSON-encodable value; default ceil(bytes/4). */
  countTokens?: (v: unknown) => number
}

export type ToolTier = "core" | "listed" | "deferred"

/** The frozen tier assignment for one toolkit. */
export interface ToolPlan {
  tier: Map<string, ToolTier>
  core: string[]
  listed: string[]
  deferred: string[]
  search: Tool
  call: Tool
}

const cmp = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

export function defaultCountTokens(v: unknown): number {
  return Math.ceil(Buffer.byteLength(JSON.stringify(v) ?? "", "utf8") / 4)
}

export function toolDef(t: Tool): Record<string, unknown> {
  return { name: t.name, description: t.description, inputSchema: t.inputSchema }
}

function firstLine(s: string): string {
  const i = s.indexOf("\n")
  if (i >= 0) s = s.slice(0, i)
  // Go cuts at 120 BYTES; cut at 120 UTF-8 bytes too, without splitting a character.
  if (Buffer.byteLength(s, "utf8") > 120) {
    let out = ""
    for (const ch of s) {
      if (Buffer.byteLength(out + ch, "utf8") > 120) break
      out += ch
    }
    s = out + "…"
  }
  return s
}

const notRun = async (): Promise<ToolResult> => ({ output: "engine-internal tool: run by the client loop", isError: true })

function rebuild(p: ToolPlan, byName: Map<string, Tool>): void {
  p.core = []
  p.listed = []
  p.deferred = []
  for (const [n, t] of p.tier) (t === "core" ? p.core : t === "listed" ? p.listed : p.deferred).push(n)
  p.core.sort(cmp)
  p.listed.sort(cmp)
  p.deferred.sort(cmp)
  let d =
    'Load the full schema of tools that are not shown in full, then call them by name (or with call_tool). Query by keywords, or "select:a,b" for exact names.'
  if (p.listed.length) {
    d += "\n\nAvailable tools:"
    for (const n of p.listed) {
      d += "\n- " + n
      const line = firstLine(byName.get(n)?.description ?? "")
      if (line) d += " — " + line
    }
  }
  if (p.deferred.length) d += "\n\nMore tools (name only): " + p.deferred.join(", ")
  p.search = {
    name: "tool_search",
    description: d,
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Keywords, or select:name1,name2" },
        limit: { type: "number", description: "Maximum results for a keyword query (default 5)" },
      },
      required: ["query"],
      additionalProperties: false,
    },
    source: "builtin",
    hints: { readOnly: true, destructive: false, openWorld: false },
    execute: notRun,
  }
  p.call = {
    name: "call_tool",
    description: "Call a tool loaded with tool_search, by name, with its arguments.",
    inputSchema: {
      type: "object",
      properties: { name: { type: "string" }, arguments: { type: "object" } },
      required: ["name"],
      additionalProperties: false,
    },
    source: "builtin",
    execute: notRun,
  }
}

/** Build a tier plan over `tools` (exported for tests and host UIs). */
export function buildToolPlan(o: ToolTierOptions, tools: Tool[]): ToolPlan {
  const count = o.countTokens ?? defaultCountTokens
  const usage = (n: string) => (o.usageCount ? o.usageCount(n) : 0)
  const byName = new Map(tools.map((t) => [t.name, t] as const))
  const tier = new Map<string, ToolTier>()
  for (const t of tools) tier.set(t.name, "listed")
  for (const n of o.deferred ?? []) if (tier.has(n)) tier.set(n, "deferred")
  for (const n of o.core ?? []) if (tier.has(n)) tier.set(n, "core")
  const p = { tier } as ToolPlan
  rebuild(p, byName)

  let budget = o.budgetTokens ?? 0
  if (!budget && o.contextWindow && o.contextWindow > 0) budget = Math.floor(o.contextWindow / 20)
  if (budget > 0) {
    const cost = () => {
      let n = count(toolDef(p.search)) + count(toolDef(p.call))
      for (const name of p.core) n += count(toolDef(byName.get(name)!))
      return n
    }
    // least used first; ties: bigger schema first, then name
    const order = (names: string[]) =>
      [...names].sort((a, b) => {
        const ua = usage(a), ub = usage(b)
        if (ua !== ub) return ua - ub
        const sa = count(toolDef(byName.get(a)!)), sb = count(toolDef(byName.get(b)!))
        if (sa !== sb) return sb - sa
        return cmp(a, b)
      })
    for (const n of order(p.core)) {
      if (cost() <= budget) break
      tier.set(n, "listed")
      rebuild(p, byName)
    }
    for (const n of order(p.listed)) {
      if (cost() <= budget) break
      tier.set(n, "deferred")
      rebuild(p, byName)
    }
  }
  return p
}

/** `call_tool {name, arguments}` → the real call; `undefined` when `name` is not call_tool. */
export function unwrapCallTool(name: string, args: Record<string, unknown>): { name: string; args: Record<string, unknown> } | undefined {
  if (name !== "call_tool") return undefined
  const real = typeof args?.name === "string" ? args.name : ""
  const inner = args?.arguments && typeof args.arguments === "object" && !Array.isArray(args.arguments) ? (args.arguments as Record<string, unknown>) : {}
  return { name: real, args: inner }
}

/** Keys sorted at every level, so the loaded definitions print the same bytes as the Go port. */
function sortKeys(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sortKeys)
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {}
    for (const k of Object.keys(v as object).sort(cmp)) out[k] = sortKeys((v as Record<string, unknown>)[k])
    return out
  }
  return v
}

/** Run `tool_search` over every tool (any tier). */
export function toolSearch(all: Tool[], get: (name: string) => Tool | undefined, args: Record<string, unknown>): ToolResult {
  const q = (typeof args.query === "string" ? args.query : "").trim()
  if (!q) return { output: "tool_search: query is required", isError: true }
  let picked: Tool[] = []
  if (q.startsWith("select:")) {
    const missing: string[] = []
    for (const raw of q.slice("select:".length).split(",")) {
      const n = raw.trim()
      if (!n) continue
      const t = get(n)
      if (t) picked.push(t)
      else missing.push(n)
    }
    if (missing.length && !picked.length) return { output: "tool_search: no such tool: " + missing.join(", "), isError: true }
  } else {
    const limit = typeof args.limit === "number" && args.limit >= 1 ? Math.trunc(args.limit) : 5
    const terms = q.toLowerCase().split(/\s+/).filter(Boolean)
    const hits: Array<{ t: Tool; score: number }> = []
    for (const t of all) {
      const name = t.name.toLowerCase(), desc = t.description.toLowerCase()
      let s = 0
      for (const term of terms) {
        if (name.includes(term)) s += 3
        if (desc.includes(term)) s += 1
      }
      if (s > 0) hits.push({ t, score: s })
    }
    hits.sort((a, b) => b.score - a.score || cmp(a.t.name, b.t.name))
    picked = hits.slice(0, limit).map((h) => h.t)
  }
  if (!picked.length) return { output: "No matching tools.", isError: false, metadata: { count: 0 } }
  return {
    output: "Loaded tools (call by name, or with call_tool):\n" + JSON.stringify(sortKeys(picked.map(toolDef)), null, 2),
    isError: false,
    metadata: { count: picked.length, tools: picked.map((t) => t.name) },
  }
}
