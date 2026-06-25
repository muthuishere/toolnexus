/**
 * Provider adapters — turn the uniform tool list into each LLM's tool schema.
 * Execution is identical for every provider: read the tool name + args the model
 * returned, call toolkit.execute(name, args), feed output back.
 */
import type { Tool } from "./types.js"

export function toOpenAI(tools: Tool[]) {
  return tools.map((t) => ({
    type: "function" as const,
    function: { name: t.name, description: t.description, parameters: t.inputSchema },
  }))
}

export function toAnthropic(tools: Tool[]) {
  return tools.map((t) => ({
    name: t.name,
    description: t.description,
    input_schema: t.inputSchema,
  }))
}

export function toGemini(tools: Tool[]) {
  return [
    {
      functionDeclarations: tools.map((t) => ({
        name: t.name,
        description: t.description,
        parameters: t.inputSchema,
      })),
    },
  ]
}
