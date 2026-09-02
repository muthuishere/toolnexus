/**
 * Shared contract types. See ../../SPEC.md.
 */

export type JSONSchema = {
  type: "object"
  properties?: Record<string, unknown>
  required?: string[]
  additionalProperties?: boolean
  [k: string]: unknown
}

import type { ContentPart } from "./content.js"

export type ToolSource = "mcp" | "skill" | "builtin" | "native" | "http" | "a2a" | "custom"

export interface ToolResult {
  output: string
  isError: boolean
  /**
   * §1B non-text output. `output` stays required and remains what the transcript,
   * compaction, token estimation and any text-only provider see; `parts` is the extra
   * channel a screenshot / PDF / audio clip travels on. Absent ⇒ byte-identical to a
   * pre-0.17 result, which is why it is optional rather than a fourth required field.
   */
  parts?: ContentPart[]
  metadata?: Record<string, unknown>
}

export interface ToolContext {
  signal?: AbortSignal
  timeout?: number
  /** Present ONLY on a post-waitFor retry (§10): the resolution of a prior suspension. */
  answer?: Answer
}

/**
 * §10 Suspension. A tool that needs an out-of-band, async resolution (login, approval,
 * input) returns a normal ToolResult carrying `metadata.pending = Request`. Byte-identical
 * wire data — it crosses languages, processes, and agents unchanged.
 */
export interface Request {
  id: string
  kind: string // "authorization" | "approval" | "input" | ... (open vocabulary)
  prompt: string
  url?: string
  data?: Record<string, unknown>
  expiresAt?: string
}

export interface Answer {
  id: string
  ok: boolean
  data?: Record<string, unknown>
  /**
   * When `ok` is false, why (advisory — the loop rule branches only on `ok`). Distinguishes an
   * explicit refusal from a dismissal/timeout; the MCP elicitation bridge maps `declined`→`decline`
   * and everything else→`cancel`. (R1)
   */
  reason?: "declined" | "cancelled" | "expired"
}

let _pendingSeq = 0
/** Producer helper: return a suspension. A ToolResult with `metadata.pending` = a Request. */
export function pending(request: Omit<Request, "id"> & { id?: string }): ToolResult {
  const id = request.id ?? `pnd-${Date.now().toString(36)}-${++_pendingSeq}`
  const req: Request = { ...request, id }
  return {
    output: req.prompt + (req.url ? `\n${req.url}` : ""),
    isError: true,
    metadata: { pending: req },
  }
}

/** Sugar for the common case: `kind:"authorization"` at a login URL. */
export function authRequired(url: string, prompt = "Authorization required to continue"): ToolResult {
  return pending({ kind: "authorization", prompt, url })
}

/** Read the suspension off a result, if any. */
export function pendingOf(result: ToolResult): Request | undefined {
  return (result.metadata as { pending?: Request } | undefined)?.pending
}

export interface Tool {
  name: string
  description: string
  inputSchema: JSONSchema
  source: ToolSource
  execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult>
}

export type McpStatus = "connected" | "disabled" | "failed"

/** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
export const sanitize = (value: string) => value.replace(/[^a-zA-Z0-9_-]/g, "_")
