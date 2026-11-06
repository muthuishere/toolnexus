/**
 * JS CLIENT runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md). Mirrors
 * golang/internal/interop/mcp2026client: prints PASS <id> / FAIL <id>: <reason>, exit 0 iff all
 * passed. Test tooling only: lives outside src/, never published.
 *
 *   node --experimental-strip-types client.ts <base> <skillsDir>
 *   node --experimental-strip-types client.ts --keycloak <mcpURL> <skillsDir>
 */
import fs from "node:fs"
import http from "node:http"
import path from "node:path"
import type { AddressInfo } from "node:net"
import { createToolkit, loadMcp, MemoryTokenStore, pendingOf, type Answer, type McpLoadOptions, type McpSource, type Request, type Tool, type ToolResult } from "../../dist/index.js"

let failed = false

async function scenario(id: string, fn: () => Promise<void>) {
  try {
    await fn()
    console.log(`PASS ${id}`)
  } catch (e) {
    failed = true
    console.log(`FAIL ${id}: ${String((e as Error)?.message ?? e).replace(/\n/g, " ")}`)
  }
}

async function answers(r: Request): Promise<Answer> {
  if (r.prompt === "What is your name?") return { id: r.id, ok: true, data: { name: "toolnexus" } }
  if (r.prompt === "Really wipe?") return { id: r.id, ok: true, data: { confirm: true } }
  throw new Error(`unexpected request ${JSON.stringify(r.prompt)}`)
}

async function load(cfg: object, opts: McpLoadOptions): Promise<McpSource> {
  const src = await loadMcp({ srv: cfg }, opts)
  if (src.status.srv !== "connected") {
    await src.close()
    throw new Error(`not connected: ${JSON.stringify(src.status)}`)
  }
  return src
}

function tool(src: McpSource, name: string): Tool {
  const t = src.tools.find((x) => x.name === name)
  if (!t) throw new Error(`no tool ${name}`)
  return t
}

const run = (src: McpSource, name: string, args: Record<string, unknown> = {}): Promise<ToolResult> => tool(src, name).execute(args, {})

async function expect(src: McpSource, name: string, args: Record<string, unknown>, want: string) {
  const r = await run(src, name, args)
  if (r.isError || r.output !== want) throw new Error(`${name}: got ${JSON.stringify(r.output)} (isError=${r.isError}), want ${JSON.stringify(want)}`)
}

async function get(u: string) {
  const r = await fetch(u)
  await r.arrayBuffer()
}

/** hello-world's SKILL.md without its frontmatter, trimmed. */
function skillBody(dir: string): string {
  let s = fs.readFileSync(path.join(dir, "hello-world", "SKILL.md"), "utf8").replace(/\r\n/g, "\n")
  if (s.startsWith("---\n")) {
    const i = s.slice(4).indexOf("\n---")
    if (i >= 0) s = s.slice(4 + i + 4)
  }
  return s.trim()
}

const hasScope = (scopes: string[] | undefined, s: string) => (scopes ?? []).includes(s)

async function hermetic(base: string, skillsDir: string) {
  const open = { url: base + "/open/mcp" }

  await scenario("discover", async () => {
    const src = await load(open, { waitFor: answers })
    try {
      const got = src.tools.map((t) => t.name).join(",")
      if (got !== "srv_echo,srv_greet,srv_whoami,srv_wipe") throw new Error(`tools ${got}`)
      await expect(src, "srv_echo", { text: "hi" }, `{"text":"hi"}`)
      const l = (await (await fetch(base + "/log")).json()) as { seen?: string[] }
      for (const s of l.seen ?? []) {
        if (s.startsWith("open ") && !s.startsWith("open 2026-07-28 ")) throw new Error(`non-2026 request on the 2026 mount: ${JSON.stringify(s)}`)
      }
    } finally {
      await src.close()
    }
  })

  await scenario("mrtr_input", async () => {
    const src = await load(open, { waitFor: answers })
    try { await expect(src, "srv_greet", {}, "Hello, toolnexus!") } finally { await src.close() }
  })

  await scenario("mrtr_confirm", async () => {
    const src = await load(open, { waitFor: answers })
    try { await expect(src, "srv_wipe", {}, "wiped") } finally { await src.close() }
  })

  await scenario("mrtr_pending", async () => {
    const src = await load(open, {})
    try {
      const r = await run(src, "srv_greet")
      const req = pendingOf(r)
      if (!req || req.kind !== "input" || req.prompt !== "What is your name?") throw new Error(`not pending input: ${JSON.stringify(r)}`)
    } finally {
      await src.close()
    }
  })

  await scenario("skills", async () => {
    const tk = await createToolkit({ mcpConfig: { mcpServers: { srv: open } } })
    try {
      const skill = tk.get("skill")
      if (!skill) throw new Error("no skill tool")
      const r = await skill.execute({ name: "hello-world" }, {})
      if (r.isError || !r.output.includes(skillBody(skillsDir))) throw new Error(`remote skill output lacks the SKILL.md body: ${JSON.stringify(r.output.slice(0, 200))}`)
    } finally {
      await tk.close()
    }
  })

  await scenario("tool_search", async () => {
    const src = await load({ url: base + "/open/mcp", toolSearch: true, description: "interop" }, { waitFor: answers })
    try {
      if (src.tools.length !== 2) throw new Error(`tool search exposes ${src.tools.length} tools, want 2`)
      let r = await src.tools[0].execute({ query: "echo text" }, {})
      if (!r.output.startsWith(`[{"name":"echo"`)) throw new Error(`search output ${JSON.stringify(r.output.slice(0, 200))}`)
      r = await src.tools[1].execute({ name: "greet" }, {})
      if (r.output !== "Hello, toolnexus!") throw new Error(`call_tool greet ${JSON.stringify(r.output)}`)
    } finally {
      await src.close()
    }
  })

  const authCfg = { url: base + "/mcp", auth: { clientId: "interop-client", allowHttp: true } }
  const store = new MemoryTokenStore()
  let authed: McpSource | undefined
  await scenario("oauth_preregistered", async () => {
    authed = await load(authCfg, { openUrl: get, tokenStore: store, waitFor: answers })
    await expect(authed, "srv_echo", { text: "authed" }, `{"text":"authed"}`)
  })

  await scenario("oauth_stepup", async () => {
    if (!authed) throw new Error("needs oauth_preregistered")
    try {
      await expect(authed, "srv_whoami", {}, "dev-user")
      const tok = await store.load(base + "/mcp")
      if (!tok || !hasScope(tok.scopes, "mcp:whoami")) throw new Error(`stored token scopes lack mcp:whoami: ${JSON.stringify(tok?.scopes)}`)
    } finally {
      await authed.close()
    }
  })

  await scenario("oauth_cimd", async () => {
    let docURL = ""
    const hs = http.createServer((_q, s) => {
      s.writeHead(200, { "Content-Type": "application/json" })
      s.end(JSON.stringify({ client_id: docURL, client_name: "interop CIMD", redirect_uris: ["http://127.0.0.1/callback"] }))
    })
    await new Promise<void>((r) => hs.listen(0, "127.0.0.1", r))
    docURL = `http://127.0.0.1:${(hs.address() as AddressInfo).port}/client.json`
    let bad: Error | undefined
    const follow = async (u: string) => {
      if (!u.includes("client_id=" + encodeURIComponent(docURL))) bad = new Error(`authorize URL lacks the CIMD client_id: ${u}`)
      await get(u)
    }
    try {
      const src = await load({ url: base + "/mcp", auth: { clientMetadataUrl: docURL, allowHttp: true } }, { openUrl: follow })
      try { await expect(src, "srv_echo", { text: "cimd" }, `{"text":"cimd"}`) } finally { await src.close() }
      if (bad) throw bad
    } finally {
      hs.closeAllConnections()
      hs.close()
    }
  })

  await scenario("oauth_no_identity", async () => {
    const src = await loadMcp({ srv: { url: base + "/mcp", auth: { allowHttp: true } } }, { openUrl: () => undefined })
    try {
      if (src.status.srv !== "failed") throw new Error(`status ${JSON.stringify(src.status)}, want failed`)
    } finally {
      await src.close()
    }
  })

  await scenario("legacy_fallback", async () => {
    const src = await load({ url: base + "/legacy/mcp" }, {})
    try { await expect(src, "srv_echo", { text: "old" }, `{"text":"old"}`) } finally { await src.close() }
  })
}

// ---- live Keycloak (interop/mcp2026/keycloak) ----

const KC_CLIENT_ID = "toolnexus-mcp"
const KC_USER = "alice"
const KC_PASSWORD = "alice-test-pw" // throwaway user of the local-only realm-toolnexus.json

const unescapeHtml = (s: string) => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")

/** A headless browser: follow the authorize URL through Keycloak's login (and consent) forms, then deliver the loopback callback. */
function kcOpener() {
  const jar = new Map<string, string>() // cookie name -> value (single host: localhost:8180)
  const cookieHeader = () => [...jar].map(([k, v]) => `${k}=${v}`).join("; ")
  const remember = (r: Response) => {
    for (const c of r.headers.getSetCookie()) {
      const [nv] = c.split(";")
      const i = nv.indexOf("=")
      if (i > 0) jar.set(nv.slice(0, i).trim(), nv.slice(i + 1).trim())
    }
  }
  // Follows redirects by hand (cookies), stopping at a 127.0.0.1 callback.
  const step = async (u: string, init: RequestInit = {}): Promise<{ r: Response; url: string; body: string }> => {
    let url = u
    for (let i = 0; i < 10; i++) {
      const r = await fetch(url, { ...init, redirect: "manual", headers: { ...(init.headers as Record<string, string>), Cookie: cookieHeader() }, signal: AbortSignal.timeout(15_000) })
      remember(r)
      const body = await r.text()
      const loc = r.headers.get("location")
      if (r.status >= 300 && r.status < 400 && loc) {
        const next = new URL(loc, url)
        if (next.hostname === "127.0.0.1") return { r, url, body }
        url = next.toString()
        init = {}
        continue
      }
      return { r, url, body }
    }
    throw new Error("keycloak login: too many redirects")
  }
  return async (authURL: string) => {
    let { r, url, body } = await step(authURL)
    for (let page = 0; r.status === 200 && page < 3; page++) {
      let action: string
      let form: URLSearchParams
      const login = /<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/.exec(body)
      const any = /<form[^>]*action="([^"]+)"/.exec(body)
      if (login) {
        action = login[1]
        form = new URLSearchParams({ username: KC_USER, password: KC_PASSWORD, credentialId: "" })
      } else if (any && body.includes(`name="accept"`)) {
        action = any[1]
        form = new URLSearchParams({ accept: "Yes" })
      } else break
      ;({ r, url, body } = await step(new URL(unescapeHtml(action), url).toString(), {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: form.toString(),
      }))
    }
    if (r.status !== 302) throw new Error(`keycloak login: HTTP ${r.status}`)
    await get(new URL(r.headers.get("location")!, url).toString())
  }
}

async function keycloak(mcpURL: string) {
  const store = new MemoryTokenStore()
  let src: McpSource | undefined
  await scenario("kc_login", async () => {
    src = await load({ url: mcpURL, auth: { clientId: KC_CLIENT_ID, allowHttp: true } }, { openUrl: kcOpener(), tokenStore: store, waitFor: answers })
    await expect(src, "srv_echo", { text: "keycloak" }, `{"text":"keycloak"}`)
  })
  await scenario("kc_stepup", async () => {
    if (!src) throw new Error("needs kc_login")
    try {
      const r = await run(src, "srv_whoami")
      if (r.isError || !r.output) throw new Error(`whoami ${JSON.stringify(r)}`)
      const tok = await store.load(mcpURL)
      if (!tok || !hasScope(tok.scopes, "mcp:greet")) throw new Error(`stored token scopes lack mcp:greet: ${JSON.stringify(tok?.scopes)}`)
    } finally {
      await src.close()
    }
  })
}

const argv = process.argv.slice(2)
if (argv.length === 3 && argv[0] === "--keycloak") await keycloak(argv[1])
else if (argv.length === 2) await hermetic(argv[0], argv[1])
else {
  console.error("usage: client.ts <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>")
  process.exit(2)
}
process.exit(failed ? 1 : 0)
