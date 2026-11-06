# MCP skills extension

> **Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/golang` (import path `toolnexus/spikes/mcp2026`). Every code block is copied
> verbatim from a program under `examples/` that `examples_test.go` runs and checks; `docs_test.go` fails if a
> block here stops matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/main.go -->
```go
	// 1. Does the server speak the skills extension?
	d, err := c.Call("server/discover", nil)
	if err != nil {
		log.Fatal(err)
	}
	ext, _ := d["capabilities"].(map[string]any)["extensions"].(map[string]any)
	_, hasSkills := ext[mcp.SkillsExt]
	fmt.Println("skills extension:", hasSkills)

	// 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
	list, err := c.Call("skills/list", nil)
	if err != nil {
		log.Fatal(err)
	}
	for _, s := range list["skills"].([]any) {
		sk := s.(map[string]any)
		fm, _ := sk["frontmatter"].(map[string]any)
		fmt.Printf("skill %s: %s\n", sk["uri"], fm["name"])
	}

	// 3. Load one skill's instructions: resources/read on its SKILL.md.
	const uri = "skill://hello-world/SKILL.md"
	r, err := c.Call("resources/read", mcp.Obj{"uri": uri})
	if err != nil {
		log.Fatal(err)
	}
	body := r["contents"].([]any)[0].(map[string]any)["text"].(string)
	fmt.Println("first heading:", firstHeading(body))
```

## Download a whole skill

<!-- from: examples/04-skills/main.go -->
```go
	// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
	dir, _ := os.MkdirTemp("", "skills")
	defer os.RemoveAll(dir)
	if err := c.DownloadSkill(uri, dir); err != nil {
		log.Fatal(err)
	}
	filepath.WalkDir(dir, func(p string, e os.DirEntry, _ error) error {
		if !e.IsDir() {
			rel, _ := filepath.Rel(dir, p)
			fmt.Println("downloaded:", filepath.ToSlash(rel))
		}
		return nil
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

`DownloadSkill` validates every resource URI before writing anything: the URI is percent-decoded once, then any
leftover `%`, `\`, NUL, `:`, or empty / `.` / `..` segment is refused, as is a file outside the skill. Every
file's sha256 must match its `digest`. The server applies the same URI check (`-32602`).

## Serving skills

The spike server serves the skills directory named in its fixture (`skillsDir`). Frontmatter parsing is flat
`key: value` only; a real YAML subset is pending in the ports.
