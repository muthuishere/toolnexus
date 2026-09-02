defmodule Toolnexus.McpServe do
  @moduledoc """
  MCP — INBOUND: serve a toolkit as an MCP server (SPEC §7C).

  The inbound mirror of the A2A `serve` (§7B). Where A2A advertises the
  toolkit's **skills** and fulfils a Task through the whole client loop, MCP
  advertises the toolkit's **unified tools** (every source) and dispatches
  each `tools/call` straight to `Tool.execute`. The calling MCP client *is*
  the LLM host, so there is no client, no Task, and no TaskStore — just
  `tools/list` + `tools/call` over the toolkit registry.

  Transport: **streamable-HTTP**, mounted at `POST /mcp` on the
  `Toolnexus.Serve` server alongside the A2A routes (stateless — no session).
  Deferred per §7C: stdio, resources, prompts, sampling.
  """

  import Plug.Conn

  alias Toolnexus.{ContentPart, Context, Tool, ToolResult}

  @default_name "toolnexus"
  @default_version "0.1.0"
  @protocol_version "2025-06-18"

  @doc """
  The tools the MCP profile exposes: every toolkit tool, filtered to the
  profile's `tools` list when set (unknown names in the filter are ignored,
  never an error).
  """
  @spec exposed_tools([Tool.t()], map() | nil) :: [Tool.t()]
  def exposed_tools(tools, cfg) do
    case cfg && cfg_get(cfg, :tools) do
      wanted when is_list(wanted) ->
        wanted = MapSet.new(wanted)
        Enum.filter(tools, &MapSet.member?(wanted, &1.name))

      _ ->
        tools
    end
  end

  @doc """
  Handle one streamable-HTTP MCP request (`/mcp`). Stateless: each POST is a
  self-contained JSON-RPC exchange; notifications are accepted with `202`.
  Non-POST methods answer `405` (like the JS SDK's stateless transport).
  """
  @spec handle_http(Plug.Conn.t(), [Tool.t()], map() | nil, (map() -> any()) | nil) ::
          Plug.Conn.t()
  def handle_http(conn, tools, cfg, on_call) do
    case conn.method do
      "POST" ->
        {:ok, body, conn} = read_body(conn)

        case Jason.decode(body) do
          {:ok, %{"method" => method} = msg} ->
            case msg["id"] do
              nil ->
                # A notification (e.g. notifications/initialized): accepted, no body.
                send_resp(conn, 202, "")

              id ->
                json(conn, 200, handle_rpc(id, method, msg["params"] || %{}, tools, cfg, on_call))
            end

          _ ->
            json(conn, 400, rpc_error(nil, -32700, "Parse error"))
        end

      _ ->
        send_resp(conn, 405, "Method Not Allowed")
    end
  end

  # --- JSON-RPC method dispatch (§7C) ------------------------------------------

  defp handle_rpc(id, "initialize", params, _tools, cfg, _on_call) do
    rpc_result(id, %{
      "protocolVersion" => params["protocolVersion"] || @protocol_version,
      "capabilities" => %{"tools" => %{}},
      "serverInfo" => %{
        "name" => (cfg && cfg_get(cfg, :name)) || @default_name,
        "version" => (cfg && cfg_get(cfg, :version)) || @default_version
      }
    })
  end

  defp handle_rpc(id, "ping", _params, _tools, _cfg, _on_call), do: rpc_result(id, %{})

  defp handle_rpc(id, "tools/list", _params, tools, _cfg, _on_call) do
    rpc_result(id, %{
      "tools" =>
        Enum.map(tools, fn t ->
          %{"name" => t.name, "description" => t.description, "inputSchema" => t.input_schema}
        end)
    })
  end

  defp handle_rpc(id, "tools/call", params, tools, _cfg, on_call) do
    name = to_string(params["name"] || "")

    case Enum.find(tools, &(&1.name == name)) do
      nil ->
        rpc_error(id, -32602, "Unknown tool: #{name}")

      %Tool{} = tool ->
        started = now_ms()

        # An execute raise becomes an isError result — never a crash.
        result =
          try do
            case tool.execute.(params["arguments"] || %{}, %Context{}) do
              %ToolResult{} = r -> r
              other -> %ToolResult{output: to_string(other), is_error: false}
            end
          rescue
            e -> %ToolResult{output: Exception.message(e), is_error: true}
          end

        # Host callback errors are isolated; metadata is never on the MCP wire.
        if is_function(on_call, 1) do
          try do
            on_call.(%{
              name: tool.name,
              source: tool.source,
              ms: now_ms() - started,
              is_error: result.is_error
            })
          rescue
            _ -> :ok
          end
        end

        rpc_result(id, %{
          "content" => [%{"type" => "text", "text" => result.output}] ++ mcp_blocks(result),
          "isError" => result.is_error
        })
    end
  end

  defp handle_rpc(id, method, _params, _tools, _cfg, _on_call),
    do: rpc_error(id, -32601, "Method not found: #{method}")

  # SPEC §7B: a tool's non-text parts follow the text block as MCP content blocks —
  # image ⇒ image, audio ⇒ audio, file with data ⇒ embedded resource, file with url ⇒
  # resource_link. A result with no parts emits exactly the one text block it always did.
  defp mcp_blocks(%ToolResult{parts: parts}) when is_list(parts),
    do: parts |> Enum.map(&ContentPart.from_map/1) |> Enum.flat_map(&mcp_block/1)

  defp mcp_blocks(_), do: []

  defp mcp_block(%ContentPart{type: "text"}), do: []

  defp mcp_block(%ContentPart{type: type, data: data} = p) when type in ["image", "audio"] and is_binary(data),
    do: [%{"type" => type, "data" => data, "mimeType" => p.mime_type}]

  defp mcp_block(%ContentPart{type: "file", data: data} = p) when is_binary(data) do
    resource =
      %{"uri" => p.name || "resource", "blob" => data}
      |> then(fn r -> if p.mime_type, do: Map.put(r, "mimeType", p.mime_type), else: r end)

    [%{"type" => "resource", "resource" => resource}]
  end

  defp mcp_block(%ContentPart{type: "file", url: url} = p) when is_binary(url) do
    [
      %{"type" => "resource_link", "uri" => url}
      |> then(fn b -> if p.mime_type, do: Map.put(b, "mimeType", p.mime_type), else: b end)
      |> then(fn b -> if p.name, do: Map.put(b, "name", p.name), else: b end)
    ]
  end

  defp mcp_block(_), do: []

  # --- plumbing ----------------------------------------------------------------

  defp rpc_result(id, result), do: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

  defp rpc_error(id, code, message),
    do: %{"jsonrpc" => "2.0", "id" => id, "error" => %{"code" => code, "message" => message}}

  defp json(conn, status, payload) do
    conn
    |> put_resp_content_type("application/json")
    |> send_resp(status, Jason.encode!(payload))
  end

  defp cfg_get(cfg, key), do: Map.get(cfg, key, Map.get(cfg, Atom.to_string(key)))

  defp now_ms, do: System.monotonic_time(:millisecond)
end
