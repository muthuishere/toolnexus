# MCP skills extension

> **JS port of the Go reference spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/js` (`import * as mcp from "./src/index.ts"`; Node 22.18+, no dependencies, no MCP SDK). Every
> code block is copied verbatim from a program under `examples/` that `test/examples.test.ts` runs and checks;
> `test/docs.test.ts` fails if a block here stops matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/main.ts -->
```ts
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
```

## Download a whole skill

<!-- from: examples/04-skills/main.ts -->
```ts
// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
const dir = await mkdtemp(path.join(tmpdir(), "skills"))
await c.downloadSkill(uri, dir)
const files = (await readdir(dir, { recursive: true, withFileTypes: true })).filter((e) => e.isFile())
for (const f of files.map((e) => path.relative(dir, path.join(e.parentPath, e.name)).split(path.sep).join("/")).sort()) {
  console.log("downloaded:", f)
}
await rm(dir, { recursive: true })
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

`downloadSkill` validates every resource URI before writing anything: the URI is percent-decoded once, then any
leftover `%`, `\`, NUL, `:`, or empty / `.` / `..` segment is refused, as is a file outside the skill. Every
file's sha256 must match its `digest`. The server applies the same URI check (`-32602`).

## Serving skills

The spike server serves the skills directory named in its fixture (`skillsDir`, via `Server.load(path)` /
`fixtureServer()`). Frontmatter parsing is flat `key: value` only; a real YAML subset is pending in the ports.
