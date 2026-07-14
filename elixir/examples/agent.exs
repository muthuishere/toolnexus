# Full agent example: MCP tools + skills + a native tool + an HTTP tool, driven by
# the unified client host loop. Mirrors js/examples/agent.ts and python/examples/agent.py.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/agent.exs                          # tools list only, exits cleanly
#     OPENROUTER_API_KEY=... mix run examples/agent.exs   # runs the live loop
#
# The `everything` MCP server needs `npx` on PATH. The API key is read from the
# environment and never printed.

here = Path.dirname(__ENV__.file)
examples = Path.expand(Path.join([here, "..", "..", "examples"]))

{:ok, tk} =
  Toolnexus.create_toolkit(
    mcp_config: Path.join(examples, "mcp.json"),
    skills_dir: Path.join(examples, "skills")
  )

# a native (annotation) tool
add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers and return the sum.",
    input_schema: %{
      "type" => "object",
      "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
      "required" => ["a", "b"]
    },
    execute: fn %{"a" => a, "b" => b}, _ctx -> to_string(a + b) end
  )

# an HTTP tool (public test API, no auth)
get_post =
  Toolnexus.Http.tool(
    name: "get_post",
    description: "Fetch a placeholder blog post by id from jsonplaceholder.",
    method: "GET",
    url: "https://jsonplaceholder.typicode.com/posts/{id}",
    input_schema: %{
      "type" => "object",
      "properties" => %{"id" => %{"type" => "integer"}},
      "required" => ["id"]
    }
  )

tk = Toolnexus.Toolkit.register(tk, [add, get_post])

IO.puts("MCP status: #{inspect(Toolnexus.Toolkit.mcp_status(tk))}")
IO.puts("Tools (#{length(Toolnexus.Toolkit.tools(tk))}):")

for t <- Toolnexus.Toolkit.tools(tk), do: IO.puts("  - #{t.name} (#{t.source})")

case System.get_env("OPENROUTER_API_KEY") do
  nil ->
    IO.puts("\n(no OPENROUTER_API_KEY set — skipping the live loop; tools listed above)")

  key ->
    client =
      Toolnexus.Client.create(
        base_url: "https://openrouter.ai/api/v1",
        style: "openai",
        model: System.get_env("OPENROUTER_MODEL") || "openai/gpt-4o-mini",
        api_key: key
      )

    result =
      Toolnexus.Client.run(
        client,
        "Use the add tool to add 21 and 21, then tell me the result.",
        tk
      )

    IO.puts("\nAgent answer:\n#{result.text}")
end

Toolnexus.Toolkit.close(tk)
