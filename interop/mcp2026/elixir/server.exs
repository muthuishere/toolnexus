# Elixir SERVER runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md).
# Mirrors golang/internal/interop/mcp2026server/main.go using only the public API plus the
# test-only dev authorization server (test/support, compiled in MIX_ENV=test). Test tooling,
# not shipped (lives outside elixir/, so not in the Hex package nor in coverage).
#
#   MIX_ENV=test mix run --no-compile server.exs [--keycloak] <skillsDir>
Logger.configure(level: :error)
alias Toolnexus.{McpDevAuthServer, McpHandler, McpPrincipal, McpServerAuth, Request, Tool, ToolResult}

{keycloak, args} =
  case System.argv() do
    ["--keycloak" | rest] -> {true, rest}
    rest -> {false, rest}
  end

skills_dir = List.first(args) || "../examples/skills"

issuer =
  if keycloak do
    "http://localhost:8180/realms/toolnexus"
  else
    {:ok, as} = McpDevAuthServer.start(clients: %{"interop-client" => ["http://127.0.0.1/callback"]})
    as.issuer
  end

obj = %{"type" => "object"}

tools = [
  %Tool{
    name: "echo",
    description: "Echo the given text back.",
    source: "native",
    input_schema: %{
      "type" => "object",
      "properties" => %{"text" => %{"type" => "string"}},
      "required" => ["text"]
    },
    execute: fn a, _ ->
      s = if is_binary(a["text"]), do: a["text"], else: ""
      %ToolResult{output: s, structured: %{"text" => s}}
    end
  },
  %Tool{
    name: "greet",
    description: "Greet the user; asks for their name.",
    source: "native",
    input_schema: obj,
    execute: fn _, ctx ->
      case ctx && ctx.answer do
        %{ok: true, data: data} when is_map(data) ->
          ToolResult.ok("Hello, #{data["name"] || data[:name]}!")

        _ ->
          %ToolResult{
            metadata: %{
              pending: %Request{
                id: "name",
                kind: "input",
                prompt: "What is your name?",
                data: %{
                  "schema" => %{
                    "type" => "object",
                    "properties" => %{"name" => %{"type" => "string"}},
                    "required" => ["name"]
                  }
                }
              }
            }
          }
      end
    end
  },
  %Tool{
    name: "wipe",
    description: "Destructive; asks for confirmation first.",
    source: "native",
    input_schema: obj,
    execute: fn _, _ -> ToolResult.ok("wiped") end
  },
  %Tool{
    name: "whoami",
    description: "Who is calling.",
    source: "native",
    input_schema: obj,
    execute: fn _, ctx ->
      case McpHandler.identity(ctx) do
        %McpPrincipal{subject: s} -> ToolResult.ok(s)
        _ -> ToolResult.ok("")
      end
    end
  }
]

{:ok, seen} = Agent.start_link(fn -> %{seen: [], calls: %{}} end)

cfg = [
  name: "interop",
  version: "1.0.0",
  protocol_versions: ["2026-07-28"],
  list_tools: fn _ -> tools end,
  list_skills: fn _ -> Toolnexus.load_mcp_skills(skills_dir) end,
  before_call: fn _ctx, t, _args ->
    if t.name == "wipe", do: {:confirm, "Really wipe?"}, else: :allow
  end,
  on_call: fn ev ->
    Agent.update(seen, fn s -> update_in(s.calls, &Map.update(&1, ev.name, 1, fn n -> n + 1 end)) end)
  end
]

{addr_port, auth} =
  if keycloak do
    {7199,
     McpServerAuth.new(
       issuer: issuer,
       audience: "http://127.0.0.1:7199/mcp",
       scopes: %{"*" => ["mcp:tools"], "whoami" => ["mcp:greet"]}
     )}
  else
    {0, McpServerAuth.new(issuer: issuer, scopes: %{"*" => ["mcp"], "whoami" => ["mcp:whoami"]})}
  end

open = Toolnexus.mcp_handler(nil, cfg)
protected = Toolnexus.mcp_handler(nil, cfg ++ [authenticate: McpServerAuth.authenticator(auth)])
legacy = Toolnexus.mcp_handler(nil, stateless: true, list_tools: fn _ -> tools end)

defmodule InteropRouter do
  @moduledoc false
  @behaviour Plug
  import Plug.Conn

  def init(o), do: o

  def call(conn, %{seen: seen} = o) do
    case conn.request_path do
      "/open/mcp" -> logged(conn, seen, "open", o.open)
      "/mcp" -> logged(conn, seen, "auth", o.protected)
      "/legacy/mcp" -> logged(conn, seen, "legacy", o.legacy)
      "/.well-known/oauth-protected-resource" <> _ -> McpServerAuth.call(conn, o.auth)
      "/log" ->
        body = Agent.get(seen, fn s -> %{"seen" => Enum.reverse(s.seen), "calls" => s.calls} end)

        conn
        |> put_resp_header("content-type", "application/json")
        |> send_resp(200, Jason.encode!(body))

      _ ->
        send_resp(conn, 404, "404 page not found\n")
    end
  end

  defp logged(conn, seen, mount, handler) do
    h = fn n -> conn |> get_req_header(n) |> List.first() |> Kernel.||("") end
    line = "#{mount} #{h.("mcp-protocol-version")} #{h.("mcp-method")}"
    Agent.update(seen, fn s -> %{s | seen: [line | s.seen]} end)
    Toolnexus.McpHandler.call(conn, handler)
  end
end

{:ok, srv} =
  Bandit.start_link(
    plug:
      {InteropRouter,
       %{seen: seen, open: open, protected: protected, legacy: legacy, auth: auth}},
    ip: {127, 0, 0, 1},
    port: addr_port,
    startup_log: false,
    thousand_island_options: [shutdown_timeout: 100]
  )

{:ok, {_, port}} = ThousandIsland.listener_info(srv)
IO.puts(Jason.encode!(%{"base" => "http://127.0.0.1:#{port}", "issuer" => issuer}))

# Serve until stdin closes (the runner owns our lifetime).
IO.read(:stdio, :eof)
System.halt(0)
