// Every code block in docs/*.md is preceded by <!-- from: <path> --> and must appear verbatim in
// that file (an example that examples.test.ts runs). Edit the example, then re-copy the block.
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFile, readdir } from "node:fs/promises"
import { fileURLToPath } from "node:url"
import path from "node:path"

const root = fileURLToPath(new URL("..", import.meta.url))
const fromBlock = /<!-- from: (\S+) -->\n```[a-z]*\n([\s\S]*?)\n```/g

test("docs snippets match their examples", async (t) => {
  const pages = (await readdir(path.join(root, "docs"))).filter((f) => f.endsWith(".md")).sort()
  assert.deepEqual(pages, ["mcp-authentication.md", "mcp-providers.md", "mcp-quickstart.md", "mcp-skills.md", "mcp-spec-support.md", "mcp-tool-search.md"])
  let total = 0
  for (const page of pages) {
    const md = await readFile(path.join(root, "docs", page), "utf8")
    assert.ok(md.includes("JS port of the Go reference spike"), `${page}: missing the spike banner`)
    for (const [, src, block] of md.matchAll(fromBlock)) {
      total++
      const code = await readFile(path.join(root, src), "utf8")
      assert.ok(code.includes(block), `${page}: block from ${src} no longer matches the example:\n${block}`)
    }
  }
  assert.ok(total >= 16, `only ${total} blocks`)
  t.diagnostic(`${total} code blocks checked across ${pages.length} pages`)
})
