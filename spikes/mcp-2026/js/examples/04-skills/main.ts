// Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
//
// Run: node examples/04-skills/main.ts
//
// A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
// skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
// files themselves through resources/read on skill:// URIs.
import { mkdtemp, readdir, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import * as mcp from "../../src/index.ts"

const ts = await mcp.fixtureServer().listen() // serves examples/skills/hello-world
const c = new mcp.Client(ts.url)

// 1. Does the server speak the skills extension?
const d = await c.call("server/discover")
console.log("skills extension:", mcp.SKILLS_EXT in (d.capabilities?.extensions ?? {}))

// 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
const list = await c.call("skills/list")
for (const sk of list.skills) console.log(`skill ${sk.uri}: ${sk.frontmatter?.name}`)

// 3. Load one skill's instructions: resources/read on its SKILL.md.
const uri = "skill://hello-world/SKILL.md"
const r = await c.call("resources/read", { uri })
const body: string = r.contents[0].text
console.log("first heading:", body.split("\n").find((l) => l.startsWith("# ")))

// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
const dir = await mkdtemp(path.join(tmpdir(), "skills"))
await c.downloadSkill(uri, dir)
const files = (await readdir(dir, { recursive: true, withFileTypes: true })).filter((e) => e.isFile())
for (const f of files.map((e) => path.relative(dir, path.join(e.parentPath, e.name)).split(path.sep).join("/")).sort()) {
  console.log("downloaded:", f)
}
await rm(dir, { recursive: true })

await ts.close()
