/**
 * Replays ../conformance/serve_mcp/cases.json (SPEC §7C) against toolkit.serve's MCP
 * profile over real loopback HTTP. The Go reference is golang/serve_mcp_conformance_test.go.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { createToolkit, defineTool } from "../dist/index.js"

const casesPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../conformance/serve_mcp/cases.json")
const cases = JSON.parse(fs.readFileSync(casesPath, "utf8")).cases

for (const c of cases) {
  test(`serve mcp conformance: ${c.name}`, async () => {
    const echo = defineTool({ name: "echo", description: "echo", run: () => "x" })
    const tk = await createToolkit({ builtins: false, extraTools: [echo] })
    const srv = await tk.serve("127.0.0.1:0", { mcp: {} })
    try {
      const ac = new AbortController()
      const timer = setTimeout(() => ac.abort(), 5000)
      const res = await fetch(srv.url + c.request.path, { method: c.request.method, headers: c.request.headers ?? {}, signal: ac.signal })
      clearTimeout(timer)
      await res.body?.cancel()
      assert.equal(res.status, c.expect.status)
      for (const [k, v] of Object.entries(c.expect.headers ?? {})) assert.equal(res.headers.get(k), v)
    } finally {
      await srv.stop()
      await tk.close()
    }
  })
}
