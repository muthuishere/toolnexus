# usage: elixir serve.exs
Mix.install([{:toolnexus, "~> 0.30"}])

add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers.",
    input_schema: %{"type" => "object", "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}}, "required" => ["a", "b"]},
    execute: fn %{"a" => a, "b" => b} -> "#{a + b}" end
  )

# Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
toolkit = Toolnexus.create_toolkit!(builtins: false, extra_tools: [add])
server = Toolnexus.Toolkit.as_mcp_server(toolkit) # %{"tools" => [...]} narrows what is exposed
IO.puts("MCP endpoint: #{server.url}")            # http://127.0.0.1:<port>/mcp, POST only

# Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
entry = %{"type" => "http", "name" => "toolnexus", "url" => server.url,
          "headers" => [%{"name" => "Authorization", "value" => "Bearer " <> server.token}]}

# Plain OTP :httpc, so the recipe needs no HTTP dependency.
{:ok, _} = Application.ensure_all_started(:inets)
headers = [{~c"accept", ~c"application/json, text/event-stream"},
           {~c"authorization", String.to_charlist(hd(entry["headers"])["value"])}]
body = JSON.encode!(%{jsonrpc: "2.0", id: 1, method: "tools/list", params: %{}})
{:ok, {{_, status, _}, _, _}} =
  :httpc.request(:post, {String.to_charlist(entry["url"]), headers, ~c"application/json", body}, [], [])
IO.puts("tools/list: #{status}")
server.close.()
