# Embeddable MCP handler (SPEC §7C.1): mount a toolkit as an MCP server on YOUR
# listener, with per-request auth, per-identity tools and a confirm gate.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/mcp_handler.exs
#
# Offline and hermetic: it starts a local Bandit listener, talks raw JSON-RPC to
# it, prints what each identity sees, then stops.

alias Toolnexus.{McpHandler, ToolAnnotations, ToolResult}
alias Toolnexus.McpHandler.Decision

Application.ensure_all_started(:req)

search =
  Toolnexus.define_tool(
    name: "search",
    description: "Search records",
    execute: fn _args, ctx -> "results for #{McpHandler.identity(ctx)}" end
  )

delete =
  Toolnexus.define_tool(
    name: "delete_record",
    description: "Delete a record (admins only)",
    annotations: %ToolAnnotations{destructive_hint: true},
    execute: fn args -> "deleted #{args["id"]}" end
  )

handler =
  Toolnexus.mcp_handler(nil,
    name: "records",
    stateless: true,
    # The host's own auth: a bearer token becomes the identity.
    authenticate: fn conn ->
      case Plug.Conn.get_req_header(conn, "authorization") do
        ["Bearer " <> user] when user in ["alice", "admin"] -> {:ok, user}
        _ -> {:error, :unauthorized}
      end
    end,
    www_authenticate: fn _conn, _err -> ~s(Bearer realm="records") end,
    # Tools resolved per request: only admins see delete_record.
    list_tools: fn ctx ->
      if McpHandler.identity(ctx) == "admin", do: [search, delete], else: [search]
    end,
    # A destructive call needs confirmation; this client cannot elicit, so the
    # host-shaped fallback result is returned instead.
    before_call: fn _ctx, tool, args ->
      if tool.annotations && tool.annotations.destructive_hint && args["confirm"] != true,
        do: %Decision{
          kind: :confirm,
          message: "Really delete?",
          result: ToolResult.ok("re-call with confirm: true")
        },
        else: :allow
    end,
    on_call: fn ev ->
      IO.puts("  on_call #{ev.name} decision=#{ev.decision} by #{ev.identity}")
    end
  )

{:ok, server} =
  Bandit.start_link(plug: {McpHandler, handler}, ip: {127, 0, 0, 1}, port: 0, startup_log: false)

{:ok, {_, port}} = ThousandIsland.listener_info(server)
url = "http://127.0.0.1:#{port}/mcp"

rpc = fn token, method, params ->
  Req.post!(url,
    json: %{"jsonrpc" => "2.0", "id" => 1, "method" => method, "params" => params},
    headers: [
      {"authorization", "Bearer #{token}"},
      {"accept", "application/json, text/event-stream"}
    ],
    retry: false
  ).body
end

for user <- ["alice", "admin"] do
  names = rpc.(user, "tools/list", %{})["result"]["tools"] |> Enum.map(& &1["name"])
  IO.puts("#{user} sees #{inspect(names)}")
end

IO.puts(
  "alice -> delete_record: " <>
    inspect(rpc.("alice", "tools/call", %{"name" => "delete_record"})["error"])
)

first = rpc.("admin", "tools/call", %{"name" => "delete_record", "arguments" => %{"id" => "7"}})
IO.puts("admin, unconfirmed: " <> hd(first["result"]["content"])["text"])

second =
  rpc.("admin", "tools/call", %{
    "name" => "delete_record",
    "arguments" => %{"id" => "7", "confirm" => true}
  })

IO.puts("admin, confirmed:   " <> hd(second["result"]["content"])["text"])

status = Req.post!(url, json: %{}, retry: false).status
IO.puts("no token -> HTTP #{status}")

Supervisor.stop(server)
McpHandler.stop(handler)
