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

export type ToolSource = "mcp" | "skill" | "builtin" | "native" | "http" | "a2a" | "media" | "custom"

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
  /**
   * Machine-readable result (MCP `structuredContent`, SPEC §1). Absent ⇒ byte-identical wire.
   * When set and `output` is empty, MCP serve emits the JSON text of `structured` as the text block.
   */
  structured?: unknown
  /** Protocol metadata for the wire result (MCP `_meta`, SPEC §1). Absent ⇒ omitted. */
  meta?: Record<string, unknown>
}

/** MCP tool annotations (SPEC §1, §7C.1), sent verbatim on an MCP `tools/list` entry. */
export interface ToolAnnotations {
  title?: string
  readOnlyHint?: boolean
  destructiveHint?: boolean
  idempotentHint?: boolean
  openWorldHint?: boolean
}

export interface ToolContext {
  signal?: AbortSignal
  timeout?: number
  /** Present ONLY on a post-waitFor retry (§10): the resolution of a prior suspension. */
  answer?: Answer
  /** The model's id for this tool call, set by the client loop (§4C). A message's calls run
   * concurrently, so this is how a tool tells its sibling calls apart. Absent outside the loop. */
  callId?: string
  /** Set by `toolkit.mcpHandler` (§7C.1): the inbound MCP request this call serves. Read it with
   * `identityFrom(ctx)` / `requestFrom(ctx)` / `mcpSessionIdFrom(ctx)` / `mcpRequestMetaFrom(ctx)`. */
  mcp?: import("./mcphandler.js").McpContext
}

/**
 * Advisory behavior hints (named after MCP tool annotations, §4C), for host permission gates
 * built on `hooks.beforeTool`. Absent on a tool = unknown; treat it as mutating. Never sent to
 * the model: no adapter or wire output carries them.
 */
export interface ToolHints {
  /** The tool does not change its environment. */
  readOnly: boolean
  /** The tool may overwrite or delete things (meaningful when not readOnly). */
  destructive: boolean
  /** The tool reaches outside the host (network, external systems). */
  openWorld: boolean
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

/**
 * Typed constructor for the common resume payload: "here is the tool's output" (ADR 0026 D2).
 * Hand-building `{ id, ok: true, data: { output } }` is where hosts get the key wrong; this
 * removes the key from the host's hands entirely.
 *
 * `output` MUST be a string. A non-string is an ERROR, never a silent degrade to `""` — a
 * fabricated empty result is indistinguishable from an answer the human never gave.
 */
export function answerOutput(id: string, output: string): Answer {
  if (typeof output !== "string") {
    throw new TypeError(`answerOutput: output must be a string, got ${output === null ? "null" : typeof output}`)
  }
  if (!id) throw new TypeError("answerOutput: id is required (it must match the Request's id)")
  return { id, ok: true, data: { output } }
}

/** The matching refusal: `ok:false` with an advisory reason the loop does not branch on. */
export function answerDeclined(id: string, reason: Answer["reason"] = "declined"): Answer {
  if (!id) throw new TypeError("answerDeclined: id is required (it must match the Request's id)")
  return { id, ok: false, reason }
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
  /** What the tool may do (§4C). Builtins always carry hints; MCP tools carry them when the
   * server sends annotations. Advisory: a server can mislabel its tools. */
  hints?: ToolHints
  /** Decides per call whether this tool may run alongside other safe calls of the same model
   * response (SPEC §8 "Tool scheduling"). Absent ⇒ safe only when `hints.readOnly`. */
  concurrencySafe?: (args: Record<string, unknown>) => boolean
  /** JSON Schema of `ToolResult.structured` (SPEC §1); absent ⇒ omitted everywhere. */
  outputSchema?: Record<string, unknown>
  /** MCP annotations (§7C.1), verbatim on the MCP wire; absent ⇒ derived from `hints`, else omitted. */
  annotations?: ToolAnnotations
}

export type McpStatus = "connected" | "disabled" | "failed"

/** Replace anything outside [a-zA-Z0-9_-] with "_" (same as opencode). */
export const sanitize = (value: string) => value.replace(/[^a-zA-Z0-9_-]/g, "_")
