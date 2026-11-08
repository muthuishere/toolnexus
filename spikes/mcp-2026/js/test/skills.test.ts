import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtemp, readFile, readdir } from "node:fs/promises"
import http from "node:http"
import { tmpdir } from "node:os"
import path from "node:path"
import * as mcp from "../src/index.ts"

test("parseSkillURI accepts / rejects", () => {
  const ok: Record<string, [string, string]> = {
    "skill://hello-world/SKILL.md": ["hello-world", "SKILL.md"],
    "skill://a/refs/doc.md": ["a", "refs/doc.md"],
    "skill://a/with%20space.md": ["a", "with space.md"],
    "skill://a/..dots-in-name..x.md": ["a", "..dots-in-name..x.md"],
  }
  for (const [u, [n, r]] of Object.entries(ok)) assert.deepEqual(mcp.parseSkillURI(u), { name: n, rel: r }, u)
  for (const u of [
    "skill://a/../b", "skill://a/%2e%2e/b", "skill://a/%2E%2E%2Fetc", "skill://%2e%2e/x",
    "skill://a/..%2F..%2Fetc%2Fpasswd", "skill://a/%252e%252e/x", "skill://a/%252F", "skill://a/x%5C..%5Cy",
    "skill://a//etc", "skill:///etc/passwd", "skill://a/%2Fetc", "skill://a/%00", "skill://a/C:/x",
    "skill://a/.", "skill://a", "skill://a/%zz", "file:///etc/passwd",
  ]) assert.throws(() => mcp.parseSkillURI(u), Error, u)
})

test("server rejects traversal URIs with -32602", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const c = new mcp.Client(s.url)
  for (const m of ["resources/read", "skills/get"]) {
    await assert.rejects(c.call(m, { uri: "skill://hello-world/%2e%2e/%2e%2e/fixture.json" }), (e: unknown) => e instanceof mcp.RpcError && e.code === -32602)
  }
  await assert.rejects(c.call("skills/get", { uri: "skill://nope/SKILL.md" }), (e: unknown) => e instanceof mcp.RpcError && e.code === -32602)
})

test("skills/get digests match resources/read", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const c = new mcp.Client(s.url)
  const r = await c.call("skills/get", { uri: "skill://hello-world/SKILL.md" })
  assert.deepEqual(r.skill.resources.map((x: any) => x.uri), ["skill://hello-world/SKILL.md", "skill://hello-world/scripts/greet.sh"])
  const md = await c.call("resources/read", { uri: "skill://hello-world/SKILL.md" })
  assert.equal(md.contents[0].mimeType, "text/markdown")
  assert.equal(r.skill.resources[0].size, Buffer.byteLength(md.contents[0].text))
})

test("downloadSkill writes the skill", async (t) => {
  const s = await mcp.fixtureServer().listen()
  t.after(() => s.close())
  const dir = await mkdtemp(path.join(tmpdir(), "dl"))
  await new mcp.Client(s.url).downloadSkill("skill://hello-world/SKILL.md", dir)
  assert.ok((await readFile(path.join(dir, "hello-world", "SKILL.md"), "utf8")).length > 0)
})

test("downloadSkill refuses a hostile resource and writes nothing", async (t) => {
  for (const evil of ["skill://hello-world/%2e%2e%2f%2e%2e%2fpwned", "skill://hello-world/..%2F..%2Fpwned",
    "skill://hello-world/%252e%252e/pwned", "skill://other-skill/pwned"]) {
    const srv = http.createServer(async (req, res) => {
      let b = ""
      for await (const ch of req) b += ch
      const m = JSON.parse(b)
      const result = m.method === "skills/get"
        ? { skill: { uri: "skill://hello-world/SKILL.md", resources: [{ uri: "skill://hello-world/SKILL.md" }, { uri: evil }] } }
        : { contents: [{ uri: evil, text: "pwned" }] }
      res.end(JSON.stringify({ jsonrpc: "2.0", id: m.id, result }))
    })
    await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
    const root = await mkdtemp(path.join(tmpdir(), "evil"))
    await assert.rejects(new mcp.Client(`http://127.0.0.1:${(srv.address() as { port: number }).port}/mcp`)
      .downloadSkill("skill://hello-world/SKILL.md", path.join(root, "a", "b")), Error, evil)
    await mcp.closeServer(srv)
    assert.deepEqual((await readdir(root, { recursive: true })), [], evil)
  }
})

test("frontmatter: flat key: value", () => {
  assert.deepEqual(mcp.parseFrontmatter('---\r\nname: x\r\ndescription: "a: b"\r\n  nested: no\r\n---\r\nbody'), { name: "x", description: "a: b" })
  assert.deepEqual(mcp.parseFrontmatter("no frontmatter"), {})
})
