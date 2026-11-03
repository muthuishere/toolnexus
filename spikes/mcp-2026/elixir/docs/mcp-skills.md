# MCP skills extension

> **Elixir port of the Go reference spike — other ports pending (openspec `upgrade-mcp-2026-07-28`).** The API
> below is `spikes/mcp-2026/elixir` (modules `Mcp2026.*`). Every code block is copied verbatim from a script under
> `examples/` that `test/examples_test.exs` runs and checks; `test/docs_test.exs` fails if a block here stops
> matching its source.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills.exs -->
```elixir
# 1. Does the server speak the skills extension?
{:ok, d} = Client.call(c, "server/discover")
has_skills = Map.has_key?(d["capabilities"]["extensions"], Mcp2026.skills_ext())
IO.puts("skills extension: #{has_skills}")

# 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
{:ok, list} = Client.call(c, "skills/list")
for sk <- list["skills"], do: IO.puts("skill #{sk["uri"]}: #{sk["frontmatter"]["name"]}")

# 3. Load one skill's instructions: resources/read on its SKILL.md.
uri = "skill://hello-world/SKILL.md"
{:ok, r} = Client.call(c, "resources/read", %{"uri" => uri})
[%{"text" => body} | _] = r["contents"]
IO.puts("first heading: " <> Enum.find(String.split(body, "\n"), "", &String.starts_with?(&1, "# ")))
```

## Download a whole skill

<!-- from: examples/04-skills.exs -->
```elixir
# 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
dir = Path.join(System.tmp_dir!(), "skills-#{System.unique_integer([:positive])}")
:ok = Client.download_skill(c, uri, dir)
for p <- Path.wildcard(Path.join(dir, "**/*")) |> Enum.sort(), File.regular?(p),
    do: IO.puts("downloaded: " <> Path.relative_to(p, dir))
File.rm_rf!(dir)
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

`Client.download_skill/3` validates every resource URI before writing anything: the URI is percent-decoded once,
then any leftover `%`, `\`, NUL, `:`, or empty / `.` / `..` segment is refused (`Mcp2026.Skills.parse_uri/1`),
as is a file outside the skill (`Path.safe_relative/1`). Every file's sha256 must match its `digest`. The server
applies the same URI check (`-32602`).

## Serving skills

`Mcp2026.Server.fixture/0` serves the skills directory named in the fixture (`skillsDir`). Frontmatter parsing is
flat `key: value` only (same as Go, for byte parity); the repo's `elixir/` port already depends on `yaml_elixir`
for real frontmatter.
