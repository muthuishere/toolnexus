/**
 * Native / annotation tools — turn a plain function into a uniform Tool.
 * See ../../SPEC.md §6.
 */
import type { JSONSchema, Tool, ToolContext, ToolResult } from "./types.js"

export interface DefineToolOptions {
  name: string
  description: string
  inputSchema?: JSONSchema
  source?: Tool["source"]
  /** Return a string (wrapped as output) or a full ToolResult. */
  run: (args: Record<string, unknown>, ctx?: ToolContext) => unknown | Promise<unknown>
}

const EMPTY_SCHEMA: JSONSchema = { type: "object", properties: {}, additionalProperties: false }

/** Wrap a function as a Tool. The run() return value becomes the tool output. */
export function defineTool(opts: DefineToolOptions): Tool {
  return {
    name: opts.name,
    description: opts.description,
    inputSchema: opts.inputSchema ?? EMPTY_SCHEMA,
    source: opts.source ?? "native",
    async execute(args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
      try {
        const out = await opts.run(args ?? {}, ctx)
        if (out && typeof out === "object" && "output" in (out as object) && "isError" in (out as object)) {
          return out as ToolResult
        }
        return { output: typeof out === "string" ? out : JSON.stringify(out), isError: false }
      } catch (e) {
        return { output: e instanceof Error ? e.message : String(e), isError: true }
      }
    },
  }
}

/**
 * Optional decorator sugar: `@tool("description", { name, inputSchema })`.
 * Decorated methods/functions are collected; read them with `collectTools(obj)`.
 */
const REGISTRY = new WeakMap<object, Tool[]>()

export function tool(description: string, opts?: { name?: string; inputSchema?: JSONSchema }) {
  return function (value: any, context: any) {
    const fn = typeof value === "function" ? value : undefined
    if (!fn) return value
    const name = opts?.name ?? (context?.name ? String(context.name) : fn.name)
    const t = defineTool({ name, description, inputSchema: opts?.inputSchema, run: (args, ctx) => fn(args, ctx) })
    if (context?.addInitializer) {
      context.addInitializer(function (this: object) {
        const list = REGISTRY.get(this) ?? []
        list.push(t)
        REGISTRY.set(this, list)
      })
    }
    ;(fn as any).__tool = t
    return value
  }
}

/** Gather tools defined with the @tool decorator on an instance or a plain object of fns. */
export function collectTools(obj: object): Tool[] {
  const fromInstance = REGISTRY.get(obj) ?? []
  const fromProps: Tool[] = []
  for (const key of Object.keys(obj)) {
    const v = (obj as any)[key]
    if (typeof v === "function" && v.__tool) fromProps.push(v.__tool)
  }
  return [...fromInstance, ...fromProps]
}
