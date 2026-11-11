# usage: elixir main.exs npx -y @agentclientprotocol/claude-agent-acp@0.100.0
Mix.install([{:toolnexus, "~> 0.30"}])

add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers.",
    input_schema: %{"type" => "object", "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}}, "required" => ["a", "b"]},
    execute: fn %{"a" => a, "b" => b} -> "#{a + b}" end
  )

toolkit = Toolnexus.create_toolkit!(builtins: false, extra_tools: [add])
server = Toolnexus.Toolkit.as_mcp_server(toolkit)

# Permission policy: allow tool calls whose title names one of your tools, reject the rest.
# Return an optionId to pick it; return nil to answer with the first reject option.
allow_only = fn names ->
  fn params ->
    title = get_in(params, ["toolCall", "title"]) || ""
    if Enum.any?(names, &String.contains?(title, &1)),
      do: Enum.find_value(params["options"], &(String.starts_with?(&1["kind"], "allow") && &1["optionId"])),
      else: nil
  end
end

argv = System.argv()
model_opt = if m = System.get_env("ACP_MODEL"), do: [model: m], else: [] # e.g. "haiku"; never validated

{:ok, agent} =
  Toolnexus.Acp.connect(argv,
    [
      mcp_servers: [%{"type" => "http", "name" => "toolnexus", "url" => server.url,
                      "headers" => [%{"name" => "Authorization", "value" => "Bearer " <> server.token}]}],
      # Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
      session_meta: %{"claudeCode" => %{"options" => %{"settingSources" => [], "strictMcpConfig" => true,
                                                       "plugins" => [], "skills" => [], "tools" => []}}},
      on_permission: allow_only.(Enum.map(toolkit.tools, & &1.name)),
      on_update: fn u ->
        if get_in(u, ["update", "sessionUpdate"]) == "usage_update",
          do: IO.puts(:stderr, "[usage] " <> JSON.encode!(u["update"]))
      end,
      timeout: 300_000
    ] ++ model_opt
  )

client = Toolnexus.Client.create_in_process(model: hd(argv), generate: Toolnexus.Acp.generate(agent))

try do
  result = Toolnexus.Client.run(client, "Use the add tool to add 17 and 25. Reply with just the sum.",
                                Toolnexus.create_toolkit!(builtins: false))
  IO.puts(result.text)
after
  Toolnexus.Acp.close(agent)
  server.close.()
end
