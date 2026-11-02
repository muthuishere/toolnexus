# Prompt cache + tool tiers, hermetic (no network, no key). Mirrors the other ports'
# prompt-cache examples.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/prompt_cache_tiers.exs
#
# A stub transport plays an Anthropic-style model that first loads `deploy` with
# tool_search, then calls it through call_tool, then answers. The stub also simulates
# a prompt cache: from the second request on, the stable prefix is a cache read.

alias Toolnexus.{Client, Toolkit}

me = self()

deploy =
  Toolnexus.define_tool(
    name: "deploy",
    description: "Deploy the service to an environment.",
    execute: fn args -> "deployed to #{args["env"]}" end
  )

read = Toolnexus.define_tool(name: "read_file", description: "Read a file.", execute: fn _ -> "contents" end)
lint = Toolnexus.define_tool(name: "lint", description: "Lint the code.", execute: fn _ -> "clean" end)

toolkit = Toolkit.register(Toolnexus.create_toolkit!(builtins: false), [read, lint, deploy])

{:ok, step} = Agent.start_link(fn -> 0 end)

reply = fn content, n ->
  usage = %{"input_tokens" => 20, "output_tokens" => 5}
  usage =
    if n == 1,
      do: Map.put(usage, "cache_creation_input_tokens", 1200),
      else: Map.put(usage, "cache_read_input_tokens", 1200)

  %{"content" => content, "stop_reason" => "tool_use", "usage" => usage}
end

transport = fn req ->
  send(me, {:tools, Enum.map(req.body["tools"], & &1["name"])})
  n = Agent.get_and_update(step, &{&1 + 1, &1 + 1})

  body =
    case n do
      1 -> reply.([%{"type" => "tool_use", "id" => "s1", "name" => "tool_search", "input" => %{"query" => "select:deploy"}}], n)
      2 -> reply.([%{"type" => "tool_use", "id" => "c1", "name" => "call_tool", "input" => %{"name" => "deploy", "arguments" => %{"env" => "staging"}}}], n)
      _ -> reply.([%{"type" => "text", "text" => "Deployed to staging."}], n)
    end

  {:ok, %{status: 200, headers: %{}, body: body}}
end

client =
  Client.create(
    base_url: "https://api.example",
    style: "anthropic",
    model: "demo",
    api_key: "YOUR_KEY_HERE",
    system_prompt: "You deploy services.",
    transport: transport,
    prompt_cache: %{long_ttl: false},
    tool_tiers: %{core: ["read_file"]},
    hooks: %{
      before_tool: fn ev ->
        IO.puts("gate sees: #{ev.name} #{inspect(ev.args)}")
        nil
      end
    }
  )

IO.puts("tier plan: #{inspect(Client.tool_tier_plan(client, toolkit))}")

result = Client.run(client, "deploy to staging", toolkit)
IO.puts("answer: #{result.text}")
IO.puts("usage: #{inspect(result.usage)}")

arrays = for _ <- 1..3, do: (receive do {:tools, a} -> a end)
IO.puts("tools array (every request): #{inspect(hd(arrays))}, stable: #{length(Enum.uniq(arrays)) == 1}")

u = result.usage
IO.puts("input tokens across the run (prompt + cache read + cache write): #{u.prompt_tokens + Map.get(u, :cache_read_tokens, 0) + Map.get(u, :cache_write_tokens, 0)}")

true = result.text == "Deployed to staging."
true = length(Enum.uniq(arrays)) == 1
