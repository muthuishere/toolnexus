/**
 * A CLI client end to end: your installed agent CLI is the model, and your own toolkit
 * (an MCP server from mcp config, a skill, a native tool) is what it calls. Also the
 * live check for SPEC §8C (the TS twin of golang/examples/cliclient).
 *
 *   node --experimental-strip-types examples/cliclient.ts claude opencode        # Auto
 *   node --experimental-strip-types examples/cliclient.ts --transport cli claude # one-shot
 *   CLAUDE_BIN=/path/to/claude …                                                  # binary path
 *
 * claude has no ACP mode of its own, so it is launched through the ACP adapter
 * (acpCommand). Each run makes three fresh random tokens; a check passes only when the
 * toolkit itself executed the tool AND the answer contains the token.
 */
import { randomBytes } from "node:crypto"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { CliStyles, CliTransports, createCliClient, createToolkit, defineTool, type CliOptions } from "../dist/index.js"

const token = (p: string) => `${p}-${randomBytes(4).toString("hex").toUpperCase()}`

// A stdio MCP server with one tool, get_code (exposed as vault_get_code).
async function fixtureMCP() {
  const { McpServer } = await import("@modelcontextprotocol/sdk/server/mcp.js")
  const { StdioServerTransport } = await import("@modelcontextprotocol/sdk/server/stdio.js")
  const s = new McpServer({ name: "vault", version: "0.1.0" })
  s.tool("get_code", "Returns the vault code.", async () => ({ content: [{ type: "text", text: process.env.VAULT_CODE ?? "" }] }))
  await s.connect(new StdioServerTransport())
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv[0] === "fixture-mcp") return fixtureMCP()
  let transport = "auto"
  let model = ""
  const names: string[] = []
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--transport") transport = argv[++i]
    else if (argv[i] === "--model") model = argv[++i]
    else names.push(argv[i])
  }
  const styles: Record<string, CliOptions> = {
    claude: { style: CliStyles.Claude, command: process.env.CLAUDE_BIN || "claude", acpCommand: ["npx", "-y", "@agentclientprotocol/claude-agent-acp@0.100.0"] },
    opencode: { style: CliStyles.Opencode, command: "opencode" },
    copilot: { style: CliStyles.Copilot, command: "copilot" },
    devin: { style: CliStyles.Devin, command: "devin" },
    codex: { style: CliStyles.Codex, command: "codex" },
    gemini: { style: CliStyles.Gemini, command: "gemini" },
    goose: { style: CliStyles.Goose, command: "goose" },
  }
  const results: any[] = []
  for (const name of names) {
    const o = styles[name]
    if (!o) throw new Error(`unknown style ${name}`)
    if (transport === "acp") o.transport = CliTransports.ACP
    if (transport === "cli") o.transport = CliTransports.CLI
    if (model) o.model = model
    results.push(...(await runStyle(name, o)))
  }
  console.log(JSON.stringify(results, null, 2))
}

async function runStyle(name: string, o: CliOptions) {
  const codes = { mcp: token("VAULT"), skill: token("RECIPE"), native: token("BADGE") }
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "cliclient-"))
  const skillDir = path.join(dir, "skills", "secret-recipe")
  fs.mkdirSync(skillDir, { recursive: true })
  fs.writeFileSync(path.join(skillDir, "SKILL.md"), `---\nname: secret-recipe\ndescription: The secret recipe code.\n---\nThe secret recipe code is ${codes.skill}.\n`)
  const badge = defineTool({ name: "get_badge_number", description: "Returns the user's badge number.", run: () => codes.native })
  const self = new URL(import.meta.url).pathname
  const tk = await createToolkit({
    mcpConfig: {
      mcpServers: {
        vault: { type: "local", command: [process.execPath, "--experimental-strip-types", self, "fixture-mcp"], environment: { VAULT_CODE: codes.mcp } },
      },
    },
    skillsDir: path.join(dir, "skills"),
    extraTools: [badge],
    builtins: false,
  })
  const checks = [
    { name: "mcp", prompt: "What is the vault code? Use the vault_get_code tool.", token: codes.mcp, tool: "vault_get_code" },
    { name: "skill", prompt: "Load the secret-recipe skill and tell me the secret recipe code.", token: codes.skill, tool: "load_skill" },
    { name: "native", prompt: "What is my badge number? Use the get_badge_number tool.", token: codes.native, tool: "get_badge_number" },
  ]
  const out: any[] = []
  try {
    for (const c of checks) {
      const client = createCliClient({
        ...o,
        toolkit: tk,
        systemPrompt: "You are a terse assistant. Answer in one short sentence and include any code verbatim.",
      })
      const start = Date.now()
      const row: any = { style: name, check: c.name }
      let text = ""
      let ran = false
      try {
        const res = await client.run(c.prompt, { toolkit: tk })
        text = res.text
        for (const tc of res.toolCalls) ran ||= (tc.name === "skill" ? "load_skill" : tc.name) === c.tool
      } catch (e) {
        row.error = (e as Error).message
      }
      const info = client.acpSession()
      if (info) {
        ran ||= info.servedCalls.includes(c.tool)
        row.servedCalls = info.servedCalls
        row.permissions = info.permissions
      }
      row.transport = client.transport()
      row.ms = Date.now() - start
      if (client.fallbackReason()) row.fallback = client.fallbackReason()
      row.text = text
      row.pass = !row.error && ran && text.includes(c.token)
      await client.close()
      out.push(row)
      console.error(`${name} ${c.name} ${row.transport} pass=${row.pass} ${row.ms}ms`)
    }
  } finally {
    await tk.close()
    fs.rmSync(dir, { recursive: true, force: true })
  }
  return out
}

await main()
