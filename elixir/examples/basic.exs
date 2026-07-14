# Minimal end-to-end example. Mirrors js/examples/basic.ts and python/examples/basic.py.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/basic.exs
#
# Uses the shared sample fixtures in ../examples/. The `everything` MCP server in
# that mcp.json needs `npx` on PATH; if it is missing the server is isolated
# (status "failed") and the rest still runs — the skill path needs no network.

here = Path.dirname(__ENV__.file)
examples = Path.expand(Path.join([here, "..", "..", "examples"]))

{:ok, tk} =
  Toolnexus.create_toolkit(
    mcp_config: Path.join(examples, "mcp.json"),
    skills_dir: Path.join(examples, "skills")
  )

IO.puts("MCP status: #{inspect(Toolnexus.Toolkit.mcp_status(tk))}")

IO.puts(
  "Tools: " <>
    inspect(Enum.map(Toolnexus.Toolkit.tools(tk), &"#{&1.name} (#{&1.source})"))
)

IO.puts("\nSystem-prompt skill catalog:\n" <> Toolnexus.Toolkit.skills_prompt(tk))

IO.puts("\nOpenAI tool schema (first 2):")
IO.puts(Jason.encode!(Enum.take(Toolnexus.Toolkit.to_openai(tk), 2), pretty: true))

# Load a skill (progressive disclosure) — works with no MCP servers running.
res = Toolnexus.Toolkit.execute(tk, "skill", %{"name" => "hello-world"})
IO.puts("\nskill(hello-world) ->\n" <> res.output)

Toolnexus.Toolkit.close(tk)
