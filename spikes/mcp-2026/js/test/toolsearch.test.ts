import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtemp, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import * as mcp from "../src/index.ts"

/** 10 services x 10 verbs, realistic descriptions + schemas (same as toolsearch_test.go). */
async function hundredToolServer() {
  const services = [["github", "issue"], ["slack", "message"], ["jira", "ticket"], ["gmail", "email"], ["calendar", "event"],
    ["drive", "file"], ["stripe", "invoice"], ["postgres", "row"], ["s3", "object"], ["notion", "page"]]
  const verbs = ["create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"]
  const tools = services.flatMap(([s, o]) => verbs.map((v) => ({
    name: `${s}_${v}_${o}`,
    description: `${v} a ${o} in ${s}. Requires the workspace to be connected; returns the ${o} as JSON with its id, timestamps and owner.`,
    inputSchema: { type: "object", properties: {
      id: { type: "string", description: `The ${o} id (omit when creating).` },
      query: { type: "string", description: "Free-text filter applied server-side." },
      body: { type: "object", description: `Fields of the ${o} to write.` },
      options: { type: "object", properties: { dryRun: { type: "boolean" }, limit: { type: "integer" } } },
    }, required: ["query"] },
  })))
  const dir = await mkdtemp(path.join(tmpdir(), "big"))
  await writeFile(path.join(dir, "f.json"), JSON.stringify({ server: { name: "big", version: "1" }, tools, skillsDir: "." }))
  return mcp.Server.load(path.join(dir, "f.json")).listen()
}

test("tool search: ranking and size", async (t) => {
  const s = await hundredToolServer()
  t.after(() => s.close())
  const all = await new mcp.Client(s.url).tools()
  assert.equal(all.length, 100)
  const lazy = new mcp.Client(s.url, { toolSearch: true, description: "SaaS workspace (GitHub, Slack, Jira, ...)" })
  const meta = await lazy.tools()
  for (const [q, want] of Object.entries({ "create slack message": "slack_create_message", "search stripe invoices": "stripe_search_invoice",
    "archive a notion page": "notion_archive_page", "s3 export": "s3_export_object" })) {
    const hits = await lazy.searchTools(q, 5)
    assert.equal(hits[0]?.name, want, q)
    assert.equal(hits[0]?.server, lazy.description)
  }
  const r = await lazy.invoke("search_tools", { query: "create slack message" })
  const [eb, lb, rb] = [JSON.stringify(all).length, JSON.stringify(meta).length, JSON.stringify(r.content).length]
  t.diagnostic(`tool JSON bytes: eager(100 tools)=${eb} lazy(2 meta-tools)=${lb} + one search_tools result(5 hits)=${rb}`)
  assert.equal(eb, 56421) // byte-identical to the Go spike's measurement
  assert.equal(lb, 519)
  assert.ok(lb * 10 < eb)
})

test("tool search: invoke calls the found tool; direct names hidden", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const c = new mcp.Client(s.url, { toolSearch: true })
  const hits = await c.searchTools("echo text back", 3)
  assert.equal(hits[0]?.name, "echo")
  assert.equal(mcp.text(await c.invoke("call_tool", { name: "echo", arguments: { text: "found" } })), "found")
  await assert.rejects(c.invoke("echo", {}))
})

test("tokens split camelCase / snake_case", () => {
  assert.deepEqual(mcp.tokens("getUserByID list_items-v2"), ["get", "user", "by", "id", "list", "items", "v2"])
})
