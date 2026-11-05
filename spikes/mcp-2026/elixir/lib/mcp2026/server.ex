defmodule Mcp2026.Server do
  @moduledoc """
  Stateless MCP 2026-07-28 server over `POST /mcp`, as a Plug (`plug: {Mcp2026.Server, server}`).

  Build with `new/0` + `tool/4`, or `fixture/0` (tools echo + greet, skill hello-world).
  Protect it by setting `:auth` to a `Mcp2026.ServerAuth`.
  """
  @behaviour Plug
  import Plug.Conn
  alias Mcp2026.{RpcError, ServerAuth, Skills}

  defstruct info: %{}, tools: [], handlers: %{}, skills: [], auth: nil

  @max_body 4 * 1024 * 1024
  @fixture Path.expand("../../../fixture.json", __DIR__)

  def new, do: %__MODULE__{}

  @doc """
  Register a code-defined tool. `handler.(args, ctx)` gets `ctx.principal` (nil when unprotected)
  and returns a string (text result), a map with "content" (a full CallToolResult),
  `{:error, message}` (isError result) or any JSON value (structured result).
  """
  def tool(%__MODULE__{} = s, name, description, handler) when is_function(handler, 2) do
    tools =
      [%{"name" => name, "description" => description, "inputSchema" => %{"type" => "object"}}
       | Enum.reject(s.tools, &(&1["name"] == name))]

    %{s | handlers: Map.put(s.handlers, name, handler), tools: Enum.sort_by(tools, & &1["name"])}
  end

  @doc "The shared spike fixture (../fixture.json, or $FIXTURE)."
  def fixture do
    path = System.get_env("FIXTURE") || @fixture
    {:ok, s} = load(path)
    s
  end

  def load(path) do
    with {:ok, raw} <- File.read(path), {:ok, fx} <- JSON.decode(raw) do
      tools = fx["tools"] || []

      if Enum.all?(tools, &is_binary(&1["name"])) do
        root = Path.join(Path.dirname(path), fx["skillsDir"] || ".")
        skills = Skills.load_dir(root)
        {:ok, %__MODULE__{info: fx["server"] || %{}, tools: Enum.sort_by(tools, & &1["name"]), skills: skills}}
      else
        {:error, "fixture tool without a name"}
      end
    end
  end

  @doc "Serve on 127.0.0.1:port (0 = random). Returns %{url: \"http://127.0.0.1:N/mcp\", port: N, pid: pid}."
  def serve(%__MODULE__{} = s, port \\ 0), do: start_plug({__MODULE__, s}, port, "/mcp")

  @doc false
  def start_plug(plug, port, path \\ "") do
    {:ok, pid} =
      Bandit.start_link(plug: plug, ip: {127, 0, 0, 1}, port: port, startup_log: false,
        thousand_island_options: [shutdown_timeout: 100])

    {:ok, {_, p}} = ThousandIsland.listener_info(pid)
    %{url: "http://127.0.0.1:#{p}" <> path, base: "http://127.0.0.1:#{p}", port: p, pid: pid}
  end

  def stop(%{pid: pid}), do: Supervisor.stop(pid)

  defp server_info(s), do: %{Mcp2026.meta_server_info() => s.info}

  # ---- Plug ----

  @impl true
  def init(s), do: s

  @impl true
  def call(conn, s) do
    cond do
      s.auth != nil and conn.method == "GET" and ServerAuth.metadata_path?(conn) ->
        ServerAuth.serve_metadata(s.auth, conn)

      conn.request_path != "/mcp" ->
        send_resp(conn, 404, "404 page not found")

      conn.method != "POST" ->
        send_resp(conn, 405, "")

      true ->
        handle_post(conn, s)
    end
  end

  defp handle_post(conn, s) do
    case read_body(conn, length: @max_body, read_length: @max_body) do
      {:ok, body, conn} ->
        case JSON.decode(body) do
          {:ok, %{} = req} when not is_map_key(req, "params") or is_map(:erlang.map_get("params", req)) or
                                   :erlang.map_get("params", req) == nil ->
            handle_req(conn, s, req)
          _ -> write_rpc(conn, 400, nil, nil, %RpcError{code: -32700, message: "Parse error"})
        end

      _ ->
        write_rpc(conn, 413, nil, nil, %RpcError{code: -32600, message: "Request body too large or unreadable"})
    end
  end

  defp handle_req(conn, _s, %{"id" => nil}), do: send_resp(conn, 202, "")
  defp handle_req(conn, s, req) when not is_map_key(req, "id"), do: handle_req(conn, s, Map.put(req, "id", nil))

  defp handle_req(conn, s, req) do
    method = if is_binary(req["method"]), do: req["method"], else: ""
    params = req["params"] || %{}

    case validate(conn, method, params) do
      {status, %RpcError{} = e} ->
        write_rpc(conn, status, req["id"], nil, e)

      :ok ->
        auth =
          if s.auth, do: ServerAuth.check(s.auth, conn, method, params), else: {:ok, nil}

        case auth do
          {:halt, conn} ->
            conn

          {:ok, principal} ->
            case dispatch(s, %{principal: principal}, method, params) do
              {:ok, res} -> write_rpc(conn, 200, req["id"], res, nil)
              {:error, %RpcError{code: -32601} = e} -> write_rpc(conn, 404, req["id"], nil, e)
              {:error, e} -> write_rpc(conn, 200, req["id"], nil, e)
            end
        end
    end
  end

  defp hdr(conn, name), do: conn |> get_req_header(name) |> List.first() |> Kernel.||("")

  defp validate(conn, method, p) do
    mismatch = fn msg -> {400, %RpcError{code: Mcp2026.code_header_mismatch(), message: "Header mismatch: " <> msg}} end
    hv = hdr(conn, "mcp-protocol-version")
    hm = hdr(conn, "mcp-method")
    mv = get_in(p, ["_meta", Mcp2026.meta_version()])
    mv = if is_binary(mv), do: mv, else: ""

    named = method in ["tools/call", "resources/read", "prompts/get"]
    want = if method == "resources/read", do: p["uri"], else: p["name"]
    want = if is_binary(want), do: want, else: ""
    {got, ok} = decode_header(hdr(conn, "mcp-name"))

    cond do
      hv == "" -> mismatch.("MCP-Protocol-Version header missing")
      hm != method -> mismatch.("Mcp-Method header value '#{hm}' does not match body value '#{method}'")
      named and (not ok or got != want) -> mismatch.("Mcp-Name header value '#{got}' does not match body value '#{want}'")
      mv != hv -> mismatch.("MCP-Protocol-Version header value '#{hv}' does not match _meta value '#{mv}'")
      mv != Mcp2026.protocol_version() -> {400, unsupported(mv)}
      true -> :ok
    end
  end

  @doc false
  def unsupported(mv),
    do: %RpcError{code: Mcp2026.code_unsupported_protocol(), message: "Unsupported protocol version",
      data: %{"supported" => [Mcp2026.protocol_version()], "requested" => mv}}

  defp decode_header("=?base64?" <> rest = v) when byte_size(v) >= 11 do
    if String.ends_with?(rest, "?=") do
      case Base.decode64(binary_part(rest, 0, byte_size(rest) - 2)) do
        {:ok, b} -> {b, true}
        :error -> {"", false}
      end
    else
      {v, true}
    end
  end

  defp decode_header(v), do: {v, v != ""}

  @doc false
  def dispatch(s, ctx, method, p) do
    pv = Mcp2026.protocol_version()

    case method do
      "server/discover" ->
        {:ok, %{"resultType" => "complete", "supportedVersions" => [pv],
          "capabilities" => %{"tools" => %{}, "resources" => %{}, "extensions" => %{Mcp2026.skills_ext() => %{}}},
          "_meta" => server_info(s), "ttlMs" => 3_600_000, "cacheScope" => "public"}}

      "tools/list" ->
        {:ok, %{"resultType" => "complete", "tools" => s.tools, "ttlMs" => 60_000, "cacheScope" => "public",
          "_meta" => server_info(s)}}

      "tools/call" ->
        call_tool(s, ctx, p)

      "skills/list" ->
        {:ok, %{"resultType" => "complete", "skills" => Enum.map(s.skills, &Skills.entry/1), "ttlMs" => 60_000,
          "cacheScope" => "public", "_meta" => server_info(s)}}

      "skills/get" ->
        uri = str(p["uri"])

        with {:ok, _, _} <- uri_ok(uri, "Invalid skill URI: ") do
          case Enum.find(s.skills, &(&1.uri == uri)) do
            nil -> {:error, invalid("Unknown skill: " <> uri)}
            sk -> {:ok, %{"resultType" => "complete", "skill" => Skills.entry(sk), "_meta" => server_info(s)}}
          end
        end

      "resources/read" ->
        uri = str(p["uri"])

        with {:ok, _, _} <- uri_ok(uri, "Invalid resource URI: ") do
          case Enum.find_value(s.skills, fn sk -> Enum.find(sk.files, &(elem(&1, 0) == uri)) end) do
            nil ->
              {:error, invalid("Resource not found: " <> uri)}

            {_, data} ->
              mime = if String.ends_with?(uri, ".md"), do: "text/markdown", else: "text/plain"

              {:ok, %{"resultType" => "complete", "contents" => [%{"uri" => uri, "mimeType" => mime, "text" => data}],
                "ttlMs" => 60_000, "cacheScope" => "public", "_meta" => server_info(s)}}
          end
        end

      _ ->
        {:error, %RpcError{code: Mcp2026.code_method_not_found(), message: "Method not found: " <> method}}
    end
  end

  defp uri_ok(uri, prefix) do
    case Skills.parse_uri(uri) do
      {:ok, n, r} -> {:ok, n, r}
      {:error, e} -> {:error, invalid(prefix <> e)}
    end
  end

  defp invalid(msg), do: %RpcError{code: Mcp2026.code_invalid_params(), message: msg}
  defp str(v) when is_binary(v), do: v
  defp str(_), do: ""

  defp call_tool(s, ctx, p) do
    name = str(p["name"])
    args = if is_map(p["arguments"]), do: p["arguments"], else: %{}

    case s.handlers[name] do
      nil -> fixture_tool(s, name, args, p)
      h -> {:ok, tool_result(h.(args, ctx))}
    end
  end

  defp tool_result({:error, e}),
    do: %{"resultType" => "complete", "content" => [%{"type" => "text", "text" => to_string(e)}], "isError" => true}

  defp tool_result(t) when is_binary(t),
    do: %{"resultType" => "complete", "content" => [%{"type" => "text", "text" => t}], "isError" => false}

  defp tool_result(%{"content" => _} = r), do: Map.put_new(r, "resultType", "complete")

  defp tool_result(v),
    do: %{"resultType" => "complete", "content" => [%{"type" => "text", "text" => JSON.encode!(v)}],
      "structuredContent" => v, "isError" => false}

  defp fixture_tool(s, name, args, p) do
    text = fn t, structured ->
      {:ok, %{"resultType" => "complete", "content" => [%{"type" => "text", "text" => t}], "isError" => false,
        "_meta" => server_info(s), "structuredContent" => structured}}
    end

    case name do
      "echo" ->
        t = str(args["text"])
        text.(t, %{"text" => t})

      "greet" ->
        case p["inputResponses"] do
          %{"name" => %{"action" => "accept", "content" => %{"name" => n}}} when is_binary(n) and n != "" ->
            text.("Hello, #{n}!", %{"greeting" => "Hello, #{n}!"})

          _ ->
            # Missing/declined answer: ask (again), per MRTR error-handling guidance.
            {:ok, %{"resultType" => "input_required", "_meta" => server_info(s), "inputRequests" => %{
              "name" => %{"method" => "elicitation/create", "params" => %{
                "mode" => "form", "message" => "What is your name?",
                "requestedSchema" => %{"type" => "object", "properties" => %{"name" => %{"type" => "string"}},
                  "required" => ["name"]}}}}}}
        end

      _ ->
        {:error, invalid("Unknown tool: " <> name)}
    end
  end

  defp write_rpc(conn, status, id, result, e) do
    msg = if e, do: %{"jsonrpc" => "2.0", "id" => id, "error" => RpcError.to_map(e)},
      else: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

    conn |> put_resp_content_type("application/json", nil) |> send_resp(status, JSON.encode!(msg))
  end
end
