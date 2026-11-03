# Example 05 — lazy tool search: give the model 2 tools instead of 100.
#
# Run: mix run examples/05-tool-search.exs
#
# With tool_search: true, Client.tools/1 returns two meta-tools, search_tools(query, limit?) and
# call_tool(name, arguments). The model searches (BM25 over tool names and descriptions), then
# calls what it found. Client.invoke/4 runs whatever tool call the model emits.
alias Mcp2026.{Client, Server}

# A server with 100 tools: 10 services x 10 verbs.
services = ~w(github slack jira gmail calendar drive stripe postgres s3 notion)
verbs = ~w(create get list update delete search archive export share comment)

server =
  for svc <- services, verb <- verbs, reduce: Server.new() do
    s ->
      name = "#{svc}_#{verb}"
      Server.tool(s, name, "#{verb} an item in #{svc}.", fn args, _ctx -> "#{name} ran with #{args["text"]}" end)
  end

srv = Server.serve(server)
{:ok, all} = Client.tools(Client.new(srv.url))

# 1. Turn on tool search; description tells the model what this server is for.
c = Client.new(srv.url, tool_search: true, description: "SaaS workspace (GitHub, Slack, Jira, ...)")
{:ok, meta} = Client.tools(c)
size = fn v -> byte_size(JSON.encode!(v)) end
IO.puts("eager: #{length(all)} tools, #{size.(all)} bytes of schema")
IO.puts("lazy:  #{length(meta)} tools, #{size.(meta)} bytes of schema")

# 2. The model calls search_tools ...
{:ok, [top | _]} = Client.search_tools(c, "send a slack message create", 3)
IO.puts("top hit: " <> top["name"])

# 3. ... then call_tool with the name it found. invoke routes both meta-tools.
{:ok, res} = Client.invoke(c, "call_tool", %{"name" => top["name"], "arguments" => %{"text" => "hi team"}})
IO.puts("result: " <> Mcp2026.text(res))
