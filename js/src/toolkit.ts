/**
 * Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
 * single uniform list, with provider adapters and a single execute() router.
 */
import { type Tool, type ToolContext, type ToolResult, type McpStatus } from "./types.js"
import { loadMcp, type McpSource } from "./mcp.js"
import { loadSkills, type SkillSource } from "./skill.js"
import { toAnthropic, toGemini, toOpenAI } from "./adapters.js"

export interface ToolkitOptions {
  mcpConfig?: string | object
  skillsDir?: string | string[]
  extraTools?: Tool[]
}

export class Toolkit {
  private byName = new Map<string, Tool>()
  private constructor(
    private readonly mcp: McpSource | undefined,
    private readonly skill: SkillSource | undefined,
    extraTools: Tool[],
  ) {
    const all = [...(mcp?.tools ?? []), ...(skill ? [skill.tool] : []), ...extraTools]
    for (const t of all) {
      if (this.byName.has(t.name)) {
        console.warn(`[toolnexus] duplicate tool name "${t.name}" — keeping first`)
        continue
      }
      this.byName.set(t.name, t)
    }
  }

  static async create(opts: ToolkitOptions): Promise<Toolkit> {
    const mcp = opts.mcpConfig !== undefined ? await loadMcp(opts.mcpConfig) : undefined
    const skill = opts.skillsDir !== undefined ? loadSkills(opts.skillsDir) : undefined
    return new Toolkit(mcp, skill, opts.extraTools ?? [])
  }

  tools(): Tool[] {
    return [...this.byName.values()]
  }

  get(name: string): Tool | undefined {
    return this.byName.get(name)
  }

  /** Add a tool at runtime (native, http, or any custom Tool). First name wins. */
  register(...tools: Tool[]): this {
    for (const t of tools) {
      if (this.byName.has(t.name)) {
        console.warn(`[toolnexus] duplicate tool name "${t.name}" — keeping first`)
        continue
      }
      this.byName.set(t.name, t)
    }
    return this
  }

  async execute(name: string, args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> {
    const tool = this.byName.get(name)
    if (!tool) {
      return { output: `Unknown tool: ${name}`, isError: true }
    }
    return tool.execute(args ?? {}, ctx)
  }

  /** Markdown skill catalog for the system prompt. */
  skillsPrompt(): string {
    return this.skill ? this.skill.prompt() : ""
  }

  mcpStatus(): Record<string, McpStatus> {
    return this.mcp?.status ?? {}
  }

  toOpenAI() {
    return toOpenAI(this.tools())
  }
  toAnthropic() {
    return toAnthropic(this.tools())
  }
  toGemini() {
    return toGemini(this.tools())
  }

  async close(): Promise<void> {
    await this.mcp?.close()
  }
}

export function createToolkit(opts: ToolkitOptions): Promise<Toolkit> {
  return Toolkit.create(opts)
}
