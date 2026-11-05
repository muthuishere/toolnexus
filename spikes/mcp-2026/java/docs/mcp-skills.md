# MCP skills extension

> **Java port of the spike — the Go spike is the reference (openspec `upgrade-mcp-2026-07-28`).** The API below is
> `spikes/mcp-2026/java` (package `io.github.muthuishere.toolnexus.mcp2026`). Every code block is copied verbatim
> from a program under `examples/` that `ExamplesTest` runs and checks; `DocsTest` fails if a block here stops
> matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/Main.java -->
```java
            // 1. Does the server speak the skills extension?
            Map<String, Object> d = c.call("server/discover", null);
            Map<String, Object> ext = Mcp.map(Mcp.map(d.get("capabilities")).get("extensions"));
            System.out.println("skills extension: " + ext.containsKey(Mcp.SKILLS_EXT));

            // 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
            Map<String, Object> list = c.call("skills/list", null);
            for (Object s : Mcp.list(list.get("skills"))) {
                Map<String, Object> sk = Mcp.map(s);
                System.out.println("skill " + sk.get("uri") + ": " + Mcp.map(sk.get("frontmatter")).get("name"));
            }

            // 3. Load one skill's instructions: resources/read on its SKILL.md.
            String uri = "skill://hello-world/SKILL.md";
            Map<String, Object> r = c.call("resources/read", Mcp.obj("uri", uri));
            String body = Mcp.str(Mcp.map(Mcp.list(r.get("contents")).get(0)).get("text"));
            System.out.println("first heading: " + body.lines().filter(l -> l.startsWith("# ")).findFirst().orElse(""));
```

## Download a whole skill

<!-- from: examples/04-skills/Main.java -->
```java
            // 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
            Path dir = Files.createTempDirectory("skills");
            c.downloadSkill(uri, dir);
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

`Server.load(fixture)` serves the skills directory named in the fixture (`skillsDir`). Frontmatter parsing is flat
`key: value` only; a real YAML subset is pending in the ports.
