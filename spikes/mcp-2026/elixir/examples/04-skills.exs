# Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
#
# Run: mix run examples/04-skills.exs
#
# A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
# skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
# files themselves through resources/read on skill:// URIs.
alias Mcp2026.{Client, Server}

srv = Server.serve(Server.fixture())  # serves examples/skills/hello-world
c = Client.new(srv.url)

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

# 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
dir = Path.join(System.tmp_dir!(), "skills-#{System.unique_integer([:positive])}")
:ok = Client.download_skill(c, uri, dir)
for p <- Path.wildcard(Path.join(dir, "**/*")) |> Enum.sort(), File.regular?(p),
    do: IO.puts("downloaded: " <> Path.relative_to(p, dir))
File.rm_rf!(dir)
