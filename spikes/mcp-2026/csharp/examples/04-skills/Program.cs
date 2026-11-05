// Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
//
// Run: dotnet run --project examples/04-skills
//
// A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
// skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
// files themselves through resources/read on skill:// URIs.
using System.Text.Json.Nodes;
using Mcp2026;

await using var ts = await HttpHost.StartAsync(Server.Fixture()); // serves examples/skills/hello-world
var c = new McpClient(ts.Url + "/mcp");

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

// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
var dir = Directory.CreateTempSubdirectory("skills").FullName;
try
{
    await c.DownloadSkillAsync(uri, dir);
    foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).Select(f => Path.GetRelativePath(dir, f).Replace('\\', '/')).Order(StringComparer.Ordinal))
        Console.WriteLine("downloaded: " + f);
}
finally { Directory.Delete(dir, true); }
