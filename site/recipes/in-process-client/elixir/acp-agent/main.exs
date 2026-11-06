# usage: elixir main.exs npx -y @agentclientprotocol/claude-agent-acp@0.100.0
Mix.install([{:toolnexus, "~> 0.25"}])

add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers.",
    input_schema: %{"type" => "object", "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}}, "required" => ["a", "b"]},
    execute: fn %{"a" => a, "b" => b} -> IO.puts("\n[tool] add(#{a}, #{b})"); "#{a + b}" end
  )

# 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
toolkit = Toolnexus.create_toolkit!(builtins: false, extra_tools: [add])
server = Toolnexus.Toolkit.as_mcp_server(toolkit)

# 2. Start the agent; mcp_servers is sent to session/new exactly as written.
[command | _] = argv = System.argv()
{:ok, agent} =
  Toolnexus.Acp.connect(argv,
    mcp_servers: [%{"type" => "http", "name" => "toolnexus", "url" => server.url,
                    "headers" => [%{"name" => "Authorization", "value" => "Bearer " <> server.token}]}],
    timeout: 300_000 # one turn of a real agent can take minutes
  )

# 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
client = Toolnexus.Client.create_in_process(model: command, generate_stream: Toolnexus.Acp.generate_stream(agent))
empty = Toolnexus.create_toolkit!(builtins: false)

try do
  for ev <- Toolnexus.Client.stream(client, "Use the add tool to add 17 and 25. Reply with just the sum.", empty) do
    case ev do
      %{type: "text", delta: d} -> IO.write(d)                       # live text as the agent writes it
      %{type: "tool_call", via: "acp", name: n} -> IO.puts("\n[agent tool] #{n}")
      %{type: "done", result: r} -> IO.puts("\nfinal: #{r.text}")
      _ -> :ok
    end
  end
after
  Toolnexus.Acp.close(agent) # ends the agent process
  server.close.()
end
