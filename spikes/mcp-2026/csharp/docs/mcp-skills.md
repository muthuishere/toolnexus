# MCP skills extension

> **C# port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the spike in
> `spikes/mcp-2026/csharp` (project `src/Mcp2026`, namespace `Mcp2026`). Every code block is copied verbatim from a
> program under `examples/` that `ExamplesTests` runs and checks; `DocsTests` fails if a block here stops matching
> its source. Behaviour and example output are identical to the Go reference spike.

Servers can serve agent skills (SEP-2640, extension `io.modelcontextprotocol/skills`). A skill is a folder with a
`SKILL.md` (YAML frontmatter + instructions) and resource files, addressed as `skill://<name>/<path>`.

| method | returns |
|---|---|
| `skills/list` | every skill's `uri` + `frontmatter` (cheap: what the model sees first) |
| `skills/get {uri}` | one skill: `frontmatter` + `resources` (`uri`, `digest: sha256:…`, `size`) |
| `resources/read {uri}` | a file's text |

<!-- from: examples/04-skills/Program.cs -->
```csharp
// 1. Does the server speak the skills extension?
var d = await c.CallAsync("server/discover");
var hasSkills = d["capabilities"]?["extensions"] is JsonObject ext && ext.ContainsKey(Mcp.SkillsExt);
Console.WriteLine("skills extension: " + (hasSkills ? "true" : "false"));

// 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
var list = await c.CallAsync("skills/list");
foreach (var sk in list["skills"]!.AsArray())
    Console.WriteLine($"skill {sk!["uri"]}: {sk["frontmatter"]?["name"]}");

// 3. Load one skill's instructions: resources/read on its SKILL.md.
const string uri = "skill://hello-world/SKILL.md";
var r = await c.CallAsync("resources/read", new JsonObject { ["uri"] = uri });
var body = J.Str(r["contents"]![0]!["text"]);
Console.WriteLine("first heading: " + body.Split('\n').FirstOrDefault(l => l.StartsWith("# ")));
```

## Download a whole skill

<!-- from: examples/04-skills/Program.cs -->
```csharp
// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
var dir = Directory.CreateTempSubdirectory("skills").FullName;
try
{
    await c.DownloadSkillAsync(uri, dir);
    foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).Select(f => Path.GetRelativePath(dir, f).Replace('\\', '/')).Order(StringComparer.Ordinal))
        Console.WriteLine("downloaded: " + f);
}
finally { Directory.Delete(dir, true); }
```

```text
skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh
```

`DownloadSkillAsync` validates every resource URI before writing anything: the URI is percent-decoded once, then
any leftover `%`, `\`, NUL, `:`, or empty / `.` / `..` segment is refused, as is a file outside the skill. Every
file's sha256 must match its `digest`. The server applies the same URI check (`-32602`).

## Serving skills

The spike server serves the skills directory named in its fixture (`skillsDir`). Frontmatter parsing is flat
`key: value` only; a real YAML subset is pending in the ports.
