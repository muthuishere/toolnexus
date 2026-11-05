import { test } from "node:test"
import assert from "node:assert/strict"
import { PassThrough } from "node:stream"
import * as mcp from "../src/index.ts"

// A tiny stdio server child (TEST ONLY — the product server is HTTP only). It logs a JSON-looking
// line to stderr first, which the client must not parse.
const serverScript = `
import * as mcp from ${JSON.stringify(new URL("../src/index.ts", import.meta.url).href)}
process.stderr.write('stdio-test-server ready {"jsonrpc":"2.0","id":1,"result":{}}\\n')
await mcp.serveStdio(mcp.fixtureServer())
`

function spawnServer(stderr: "ignore" | PassThrough = "ignore", script = serverScript) {
  return mcp.connectStdio(process.execPath, { args: ["--input-type=module", "-e", script], stderr })
}

test("stdio: same 8 checks (header check SKIPs)", async () => {
  const lines: string[] = []
  assert.ok(await mcp.runChecks(() => spawnServer(), (l) => lines.push(l)), lines.join("\n"))
  assert.equal(lines.filter((l) => l.startsWith("PASS")).length, 7)
  assert.ok(lines.some((l) => l.startsWith("SKIP header mismatch")))
})

test("stdio: stderr captured not parsed; concurrent calls demuxed by id", async () => {
  const err = new PassThrough()
  let log = ""
  err.on("data", (d) => (log += d))
  const c = await spawnServer(err)
  const rs = await Promise.all(Array.from({ length: 20 }, (_, i) => c.callTool("echo", { text: `msg-${i}` })))
  rs.forEach((r, i) => assert.equal(mcp.text(r), `msg-${i}`))
  await c.close()
  assert.ok(log.includes("stdio-test-server ready"), log)
})

test("stdio: an exited server is an error, not a hang", async () => {
  const c = await spawnServer("ignore", "process.exit(3)")
  await assert.rejects(c.call("tools/list"))
  await c.close()
})
