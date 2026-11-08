// The 8 cross-port conformance checks, runnable against ANY port's server.
import { Client } from "./client.ts"
import { CODE_HEADER_MISMATCH, CODE_UNSUPPORTED_PROTOCOL, PROTOCOL_VERSION, RpcError, SKILLS_EXT, text } from "./protocol.ts"

const SKIP = Symbol("skip")

/** Runs the checks, printing PASS/FAIL/SKIP lines; mk builds a configured client (URL, auth, stdio...). */
export async function runChecks(target: string | (() => Client | Promise<Client>), log: (l: string) => void = console.log): Promise<boolean> {
  const mk = typeof target === "string" ? () => new Client(target) : target
  let ok = true
  const step = async (name: string, f: () => Promise<unknown>) => {
    try {
      if ((await f()) === SKIP) log(`SKIP ${name} (not applicable over stdio)`)
      else log(`PASS ${name}`)
    } catch (e) {
      ok = false
      log(`FAIL ${name}: ${(e as Error)?.message ?? e}`)
    }
  }
  const expectCode = async (p: Promise<unknown>, code: number) => {
    try {
      await p
    } catch (e) {
      if (e instanceof RpcError && e.code === code) return
      throw new Error(`want error ${code}, got ${(e as Error)?.message ?? e}`)
    }
    throw new Error(`want error ${code}, got success`)
  }
  const c = await mk()
  const extra: Client[] = []
  const fresh = async () => {
    const x = await mk()
    extra.push(x)
    return x
  }

  await step("server/discover", async () => {
    const r = await c.call("server/discover")
    if (!r.capabilities?.extensions || !(SKILLS_EXT in r.capabilities.extensions)) throw new Error(`skills extension not advertised: ${JSON.stringify(r.capabilities)}`)
    if (!Array.isArray(r.supportedVersions) || !r.supportedVersions.includes(PROTOCOL_VERSION))
      throw new Error(`supportedVersions lacks ${PROTOCOL_VERSION}: ${JSON.stringify(r.supportedVersions)}`)
  })
  await step("unsupported version -> -32022", async () => {
    const bad = await fresh()
    bad.version = "1900-01-01"
    await expectCode(bad.call("server/discover"), CODE_UNSUPPORTED_PROTOCOL)
  })
  await step("header mismatch -> -32020", async () => {
    const bad = await fresh()
    if (bad.transport) return SKIP // stdio has no HTTP headers to mismatch
    bad.headerOverride = { "Mcp-Name": "greet" }
    await expectCode(bad.call("tools/call", { name: "echo", arguments: { text: "x" } }), CODE_HEADER_MISMATCH)
  })
  await step("tools/list", async () => {
    const r = await c.call("tools/list")
    const names = (r.tools ?? []).map((t: any) => t?.name)
    if (!names.includes("echo") || !names.includes("greet")) throw new Error(`tools: ${names}`)
    if (r.ttlMs == null || r.cacheScope == null || r.resultType !== "complete") throw new Error(`missing ttlMs/cacheScope/resultType: ${JSON.stringify(r)}`)
  })
  await step("tools/call echo", async () => {
    const r = await c.callTool("echo", { text: "hi toolnexus" })
    if (text(r) !== "hi toolnexus") throw new Error(`got ${JSON.stringify(r)}`)
  })
  await step("tools/call greet (MRTR input_required round trip)", async () => {
    let asked = 0
    const r = await c.callTool("greet", {}, (_k, req) => {
      asked++
      if (req.method !== "elicitation/create") throw new Error(`unexpected input request ${req.method}`)
      return { action: "accept", content: { name: "toolnexus" } }
    })
    if (asked !== 1 || text(r) !== "Hello, toolnexus!") throw new Error(`asked=${asked} result=${JSON.stringify(r)}`)
  })
  const uri = "skill://hello-world/SKILL.md"
  await step("skills/list", async () => {
    const r = await c.call("skills/list")
    if (!(r.skills ?? []).some((s: any) => s?.uri === uri)) throw new Error(`hello-world not listed: ${JSON.stringify(r)}`)
  })
  await step("skills/get hello-world", async () => {
    const r = await c.call("skills/get", { uri })
    if (r.skill?.frontmatter?.name !== "hello-world" || !r.skill?.resources?.length) throw new Error(`got ${JSON.stringify(r)}`)
  })
  for (const x of [c, ...extra]) await x.close()
  return ok
}
