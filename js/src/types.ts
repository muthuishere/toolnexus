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

export type ToolSource = "mcp" | "skill" | "builtin" | "native" | "http" | "a2a" | "custom"

export interface ToolResult {
  output: string
  isError: boolean
  metadata?: Record<string, unknown>
}

export interface ToolContext {
  signal?: AbortSignal
  timeout?: number
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
