/**
 * Toolkit — aggregates dynamic MCP tools + the skill tool + custom tools into a
 * single uniform list, with provider adapters and a single execute() router.
 */
import { type Tool, type ToolContext, type ToolResult, type McpStatus } from "./types.js"
import { loadMcp, type McpSource } from "./mcp.js"
import { loadSkills, type SkillDef, type SkillSource } from "./skill.js"
import { selectBuiltins, type BuiltinsConfig } from "./builtin.js"
import { agentTools, parseAgentsConfig, type Agent } from "./a2a.js"
import { startA2AServer, type A2AConfig, type OnTask, type ServeHandle } from "./serve.js"
import { type MCPServeConfig, type OnCall } from "./mcpserve.js"
import { toAnthropic, toGemini, toOpenAI } from "./adapters.js"
import type { Client } from "./client.js"

export interface ToolkitOptions {
  mcpConfig?: string | object
  skillsDir?: string | string[]
  /** Skills supplied as data, bypassing the filesystem (§3, S1). */
  skills?: SkillDef[]
  /** Lazy provider of data-supplied skills, resolved once at toolkit build (§3, S1). */
  skillProvider?: () => SkillDef[] | Promise<SkillDef[]>
  /** Per-agent skill allowlist keyed on name; same semantics as the MCP tools filter (§3, S2). */
  skillsFilter?: Record<string, boolean>
  /** Drop tools by their FINAL exposed name across every source (MCP `server_tool`, builtins,
   * native, a2a, extras), applied after aggregation — the simple "just turn these off" list.
   * Empty/undefined ⇒ nothing disabled. Composes with builtins/skillsFilter/server allowlists. */
  disableTools?: string[]
  /** Drop skills by name from the `skill` catalog — sugar over a drop-list in `skillsFilter`
   * (each name folded in as `false`, without overriding an explicit filter entry). */
  disableSkills?: string[]
  /** Sibling-file sample cap: 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit <skill_files> (§3, S5). */
  skillSampleLimit?: number
  extraTools?: Tool[]
  /** Built-in tools (§4A). On by default. false | { disabled:true } | { enabled:false } ⇒ off. */
  builtins?: BuiltinsConfig
  /** Remote A2A agents — each advertised skill becomes a tool (source:"a2a"). */
  agents?: Agent[]
  /**
   * Host resolver for out-of-band input (§10). When set, connected MCP servers may elicit input
   * from the human mid-`tools/call` and it is bridged onto this `waitFor` (form→kind:"input",
   * URL→kind:"authorization"). Typically the same function passed to the client. Omit ⇒ MCP
   * elicitation is not advertised.
   */
  waitFor?: (request: import("./types.js").Request) => Promise<import("./types.js").Answer>
  /** §2 Gap 3. Bounds the MCP load: each connect/list is capped by the server timeout and the
   * whole load aborts promptly if this signal fires. Omit ⇒ unbounded as before. */
  signal?: AbortSignal
}

export class Toolkit {
  private byName = new Map<string, Tool>()
  private constructor(
    private readonly mcp: McpSource | undefined,
    private readonly skill: SkillSource | undefined,
    builtins: Tool[],
    agents: Tool[],
    extraTools: Tool[],
    /** A2A profile read from a top-level `a2a` config block (serve() falls back to this). */
    private readonly a2aConfig: A2AConfig | undefined,
    /** MCP serve profile read from a top-level `mcpServer` config block. */
    private readonly mcpServerConfig: MCPServeConfig | undefined,
    /** Final-name drop-list (§4A) applied across every source after aggregation. */
    disableTools: string[] = [],
  ) {
    // Builtins are the lowest-precedence source: a host extraTools entry with
    // the same name shadows a builtin (SPEC §4). Drop shadowed builtins up
    // front, then apply the normal first-wins dedupe for MCP/skill/agents/extras.
    const extraNames = new Set(extraTools.map((t) => t.name))
    const activeBuiltins = builtins.filter((b) => !extraNames.has(b.name))
    const disabled = new Set(disableTools)
    const all = [...(mcp?.tools ?? []), ...(skill ? [skill.tool] : []), ...activeBuiltins, ...agents, ...extraTools]
    for (const t of all) {
      if (disabled.has(t.name)) continue // §4A drop-list by final exposed name
      if (this.byName.has(t.name)) {
        console.warn(`[toolnexus] duplicate tool name "${t.name}" — keeping first`)
        continue
      }
      this.byName.set(t.name, t)
    }
  }

  static async create(opts: ToolkitOptions): Promise<Toolkit> {
    const mcp = opts.mcpConfig !== undefined ? await loadMcp(opts.mcpConfig, { waitFor: opts.waitFor, signal: opts.signal }) : undefined
    // Skills come from directories, in-memory data, and/or a lazy provider (§3,
    // S1). The provider is resolved here (create() is async) and merged with the
    // data list; a provider failure is isolated so other sources still load.
    let skill: SkillSource | undefined
    if (opts.skillsDir !== undefined || opts.skills !== undefined || opts.skillProvider !== undefined) {
      let providerDefs: SkillDef[] = []
      if (opts.skillProvider) {
        try {
          providerDefs = await opts.skillProvider()
        } catch (e) {
          console.warn(`[toolnexus] skill provider failed: ${e instanceof Error ? e.message : String(e)}`)
          providerDefs = []
        }
      }
      const dataDefs = [...(opts.skills ?? []), ...providerDefs]
      // disableSkills is sugar over a skillsFilter drop-list: fold each name in as false
      // WITHOUT overriding an explicit skillsFilter entry (explicit entries win).
      const filter =
        opts.disableSkills && opts.disableSkills.length > 0
          ? { ...Object.fromEntries(opts.disableSkills.map((n) => [n, false])), ...opts.skillsFilter }
          : opts.skillsFilter
      skill = loadSkills({
        dirs: opts.skillsDir,
        skills: dataDefs.length > 0 ? dataDefs : undefined,
        filter,
        sampleLimit: opts.skillSampleLimit,
      })
    }
    // The toggle comes from the `builtins` option, or a top-level `builtins`
    // key on a parsed config object — same precedence as MCP's isEnabled.
    let builtinsCfg = opts.builtins
    if (
      builtinsCfg === undefined &&
      opts.mcpConfig &&
      typeof opts.mcpConfig === "object" &&
      "builtins" in opts.mcpConfig
    ) {
      builtinsCfg = (opts.mcpConfig as { builtins?: BuiltinsConfig }).builtins
    }
    const builtins = selectBuiltins(builtinsCfg)

    // Remote A2A agents come from the `agents:[...]` option plus a top-level
    // `agents` block on a parsed config object (mirrors mcpServers). Each is
    // resolved to its skill tools; a failing agent never breaks the toolkit.
    const agentDescriptors: Agent[] = [...(opts.agents ?? [])]
    if (opts.mcpConfig && typeof opts.mcpConfig === "object" && "agents" in opts.mcpConfig) {
      agentDescriptors.push(...parseAgentsConfig((opts.mcpConfig as { agents?: Parameters<typeof parseAgentsConfig>[0] }).agents))
    }
    const agentToolLists = await Promise.all(
      agentDescriptors.map((a) =>
        agentTools(a).catch((e) => {
          console.warn(`[toolnexus] A2A agent "${a.card}" failed: ${e instanceof Error ? e.message : String(e)}`)
          return [] as Tool[]
        }),
      ),
    )
    const agentToolsFlat = agentToolLists.flat()

    // A2A inbound profile: a top-level `a2a` block on a parsed config object
    // (mirrors builtins/agents). serve() prefers its inline `a2a` over this.
    let a2aConfig: A2AConfig | undefined
    if (opts.mcpConfig && typeof opts.mcpConfig === "object" && "a2a" in opts.mcpConfig) {
      a2aConfig = (opts.mcpConfig as { a2a?: A2AConfig }).a2a
    }

    // MCP inbound profile: a top-level `mcpServer` (singular) block — distinct
    // from the client-side `mcpServers` block. serve()/serveStdio() prefer their
    // inline `mcp` over this.
    let mcpServerConfig: MCPServeConfig | undefined
    if (opts.mcpConfig && typeof opts.mcpConfig === "object" && "mcpServer" in opts.mcpConfig) {
      mcpServerConfig = (opts.mcpConfig as { mcpServer?: MCPServeConfig }).mcpServer
    }

    return new Toolkit(mcp, skill, builtins, agentToolsFlat, opts.extraTools ?? [], a2aConfig, mcpServerConfig, opts.disableTools ?? [])
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

  /**
   * Fetch a remote A2A agent's card at runtime and register its skills as tools.
   * Accepts an Agent descriptor or a bare card URL. First-name-wins dedupe.
   */
  async addAgent(agentOrCardUrl: Agent | string, opts?: Omit<Agent, "card">): Promise<this> {
    const ag: Agent = typeof agentOrCardUrl === "string" ? { card: agentOrCardUrl, ...opts } : agentOrCardUrl
    const tools = await agentTools(ag)
    return this.register(...tools)
  }

  /**
   * Serve this toolkit as an agent over HTTP. When the `a2a` profile is present
   * (inline, or a top-level `a2a` config block the toolkit was built from), it
   * mounts the A2A Agent Card (`/.well-known/agent-card.json`, built from skills)
   * and a JSON-RPC endpoint (`SendMessage` + `GetTask`) fulfilled by the client.
   * A message's A2A `contextId` keys the conversation via `client.ask`, so a
   * peer's turns are remembered through the client's ConversationStore.
   * When `a2a` is absent, no A2A routes are mounted. Returns a stoppable handle.
   */
  serve(
    addr: string,
    opts: { client?: Client; a2a?: A2AConfig; onTask?: OnTask; mcp?: MCPServeConfig; onCall?: OnCall },
  ): Promise<ServeHandle> {
    const a2a = opts.a2a ?? this.a2aConfig
    const mcp = opts.mcp ?? this.mcpServerConfig
    const skills = this.skill ? Object.values(this.skill.skills) : []
    return startA2AServer({
      addr,
      a2a,
      skills,
      runTask: (text, contextId) =>
        contextId
          ? opts.client!.ask(text, { toolkit: this, id: contextId })
          : opts.client!.run(text, { toolkit: this }),
      onTask: opts.onTask,
      mcp,
      mcpTools: mcp ? this.tools() : undefined,
      onCall: opts.onCall,
    })
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
