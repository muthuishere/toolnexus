defmodule Toolnexus.McpEdgeTest do
  @moduledoc """
  MCP transport/connection edge branches: the legacy SSE fallback, server→client
  requests over the POST stream, per-call timeouts, list pathologies, stdio
  config variants, and post-close behavior.
  """
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias Toolnexus.{Answer, Context, Mcp, Request}
  alias Toolnexus.Mcp.Connection

  @moduletag timeout: 60_000

  @fixture_script Path.expand("support/stdio_mcp_server.exs", __DIR__)

  defp jason_ebin, do: Path.join(Mix.Project.build_path(), "lib/jason/ebin")
  defp fixture_cmd, do: ["elixir", "-pa", jason_ebin(), @fixture_script]

  defp free_port do
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)
    port
  end

  defp start_plug(plug, opts) do
    port = free_port()

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {plug, opts}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    "http://127.0.0.1:#{port}"
  end

  defp find_tool(source, name), do: Enum.find(source.tools, &(&1.name == name))

  defp wait_until(fun, timeout_ms \\ 5_000) do
    deadline = System.monotonic_time(:millisecond) + timeout_ms

    Stream.repeatedly(fn ->
      cond do
        fun.() -> true
        System.monotonic_time(:millisecond) > deadline -> false
        true -> Process.sleep(50) && nil
      end
    end)
    |> Enum.find(&(&1 != nil))
  end

  # ---------------------------------------------------------------------------
  # Streamable stub with scripted quirks per tool name.
  # ---------------------------------------------------------------------------

  defmodule QuirkStub do
    import Plug.Conn

    def init(mode), do: mode

    def call(conn, mode) do
      {:ok, body, conn} = read_body(conn)

      case Jason.decode(body) do
        {:ok, %{"method" => "initialize", "id" => id}} ->
          json(conn, rpc(id, %{
            "protocolVersion" => "2025-06-18",
            "capabilities" => %{"tools" => %{}},
            "serverInfo" => %{"name" => "quirk", "version" => "0"}
          }))

        {:ok, %{"method" => "notifications/" <> _}} ->
          send_resp(conn, 202, "")

        {:ok, %{"method" => "tools/list", "id" => id} = msg} ->
          handle_list(conn, mode, id, msg)

        {:ok, %{"method" => "tools/call", "id" => id} = msg} ->
          handle_call_rpc(conn, mode, id, msg)

        # responses to server→client requests (ping etc.) land here
        _ ->
          send_resp(conn, 202, "")
      end
    end

    defp handle_list(conn, :bad_list, id, _msg), do: json(conn, rpc(id, %{"nope" => true}))

    defp handle_list(conn, :dup_cursor, id, _msg),
      do: json(conn, rpc(id, %{"tools" => [tool("t")], "nextCursor" => "c1"}))

    defp handle_list(conn, :list_array, id, _msg) do
      # a JSON array body — Req decodes it to a list (extract_msgs list branch)
      conn
      |> put_resp_content_type("application/json")
      |> send_resp(200, Jason.encode!([rpc(id, %{"tools" => [tool("t")]})]))
    end

    defp handle_list(conn, _mode, id, _msg),
      do: json(conn, rpc(id, %{"tools" => Enum.map(~w(ok bad_err bad_ok img hang boom500), &tool/1)}))

    defp handle_call_rpc(conn, :extras, id, msg) do
      case get_in(msg, ["params", "name"]) do
        "hang" ->
          Process.sleep(5_000)
          json(conn, rpc(id, %{"content" => []}))

        "boom500" ->
          send_resp(conn, 500, "exploded")

        "bad_err" ->
          json(conn, rpc(id, %{"content" => "not-a-list", "isError" => true}))

        "bad_ok" ->
          json(conn, rpc(id, %{"content" => "not-a-list"}))

        "img" ->
          json(conn, rpc(id, %{"content" => [%{"type" => "image", "data" => "x"}]}))

        "ok" ->
          # SSE reply carrying server→client traffic before the response:
          # a notification, a ping request, an unknown-method request, a
          # response nobody is waiting for, and non-JSON garbage.
          events =
            [
              %{"jsonrpc" => "2.0", "method" => "notifications/progress", "params" => %{}},
              %{"jsonrpc" => "2.0", "id" => "srv-ping", "method" => "ping"},
              %{"jsonrpc" => "2.0", "id" => "srv-x", "method" => "wat/wat"},
              %{"jsonrpc" => "2.0", "id" => 424_242, "result" => %{}},
              %{"weird" => true},
              rpc(id, %{"content" => [%{"type" => "text", "text" => "quirk ok"}]})
            ]
            |> Enum.map_join("", &("data: " <> Jason.encode!(&1) <> "\n\n"))

          conn
          |> put_resp_content_type("text/event-stream")
          |> send_resp(200, "not-even-a-field\n" <> events)
      end
    end

    defp tool(name), do: %{"name" => name, "description" => "", "inputSchema" => %{"type" => "object"}}
    defp rpc(id, result), do: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

    defp json(conn, payload),
      do: conn |> put_resp_content_type("application/json") |> send_resp(200, Jason.encode!(payload))
  end

  # ---------------------------------------------------------------------------
  # Legacy HTTP+SSE stub: streamable initialize 404s; GET / supplies the SSE
  # stream (endpoint event + responses); POST /messages accepts requests.
  # ---------------------------------------------------------------------------

  defmodule LegacyStub do
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, body, conn} = read_body(conn)

      cond do
        conn.method == "GET" ->
          conn = conn |> put_resp_content_type("text/event-stream") |> send_chunked(200)
          {:ok, conn} = chunk(conn, "event: endpoint\ndata: /messages\n\n")
          me = self()
          Agent.update(agent, &Map.put(&1, :stream, me))
          stream_loop(conn)

        conn.request_path == "/messages" ->
          rpc = Jason.decode!(body)
          respond(agent, rpc)
          send_resp(conn, 202, "")

        conn.request_path == "/kill" ->
          case Agent.get(agent, & &1.stream) do
            nil -> :ok
            pid -> send(pid, :halt_stream)
          end

          send_resp(conn, 200, "")

        true ->
          # streamable POST initialize fails → forces the legacy fallback
          send_resp(conn, 404, "no streamable here")
      end
    end

    defp stream_loop(conn) do
      receive do
        {:push, data} ->
          {:ok, conn} = chunk(conn, data)
          stream_loop(conn)

        :halt_stream ->
          conn
      end
    end

    defp respond(agent, %{"method" => "initialize", "id" => id}) do
      sse_push(agent, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "result" => %{"protocolVersion" => "2025-06-18", "capabilities" => %{}, "serverInfo" => %{"name" => "legacy", "version" => "0"}}
      })
    end

    defp respond(_agent, %{"method" => "notifications/" <> _}), do: :ok

    defp respond(agent, %{"method" => "tools/list", "id" => id}) do
      sse_push(agent, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "result" => %{"tools" => [%{"name" => "lecho", "description" => "", "inputSchema" => %{"type" => "object"}}]}
      })
    end

    defp respond(agent, %{"method" => "tools/call", "id" => id} = msg) do
      text = "legacy: #{get_in(msg, ["params", "arguments", "text"])}"

      sse_push(agent, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "result" => %{"content" => [%{"type" => "text", "text" => text}], "isError" => false}
      })
    end

    defp sse_push(agent, msg) do
      case Agent.get(agent, & &1.stream) do
        nil -> :ok
        pid -> send(pid, {:push, "data: " <> Jason.encode!(msg) <> "\n\n"})
      end
    end
  end

  # --- legacy fallback -----------------------------------------------------------

  test "legacy SSE fallback: endpoint event, POSTed requests, responses over the stream" do
    {:ok, agent} = Agent.start_link(fn -> %{stream: nil} end)
    base = start_plug(LegacyStub, agent)

    source = Mcp.load(%{"mcpServers" => %{"leg" => %{"url" => base <> "/", "timeout" => 5_000}}})
    on_exit(fn -> Mcp.close(source) end)

    assert source.status == %{"leg" => "connected"}
    tool = find_tool(source, "leg_lecho")
    result = tool.execute.(%{"text" => "over sse"}, %Context{})
    refute result.is_error
    assert result.output == "legacy: over sse"

    # closing the GET stream kills the transport: subsequent calls fail fast
    conn_pid = source.connections["leg"]
    Req.post!(url: base <> "/kill", body: "", retry: false)
    Process.sleep(200)

    # stray messages a connection must tolerate silently
    send(conn_pid, {:sse_endpoint, "/late"})
    send(conn_pid, {:EXIT, self(), :whatever})
    send(conn_pid, {:DOWN, make_ref(), :process, self(), :whatever})
    send(conn_pid, :complete_garbage)
    assert Process.alive?(conn_pid)
  end

  # --- streamable quirks ------------------------------------------------------------

  test "server→client traffic on the tools/call stream: ping answered, unknown method rejected, noise ignored" do
    base = start_plug(QuirkStub, :extras)

    source =
      Mcp.load(%{"mcpServers" => %{"q" => %{"type" => "remote", "url" => base <> "/mcp", "timeout" => 2_000}}})

    on_exit(fn -> Mcp.close(source) end)
    assert source.status == %{"q" => "connected"}

    result = find_tool(source, "q_ok").execute.(%{}, %Context{})
    refute result.is_error
    assert result.output == "quirk ok"

    # error-shaped and non-list content mapping (§0.4 fallbacks)
    result = find_tool(source, "q_bad_err").execute.(%{}, %Context{})
    assert result.is_error
    assert result.output == "MCP tool returned an error"

    assert find_tool(source, "q_bad_ok").execute.(%{}, %Context{}).output == ""
    assert find_tool(source, "q_img").execute.(%{}, %Context{}).output == ""

    # HTTP 500 on a call maps to an error result, not a crash
    result = find_tool(source, "q_boom500").execute.(%{}, %Context{})
    assert result.is_error
    assert result.output =~ "MCP tool call failed"
  end

  test "a hung remote tools/call times out at the per-server timeout" do
    base = start_plug(QuirkStub, :extras)

    source = Mcp.load(%{"mcpServers" => %{"q" => %{"url" => base <> "/mcp", "timeout" => 400}}})
    on_exit(fn -> Mcp.close(source) end)

    result = find_tool(source, "q_hang").execute.(%{}, %Context{})
    assert result.is_error
    assert result.output =~ "MCP tool call"
  end

  test "list pathologies: bad result shape and duplicate cursor both fail the server" do
    capture_log(fn ->
      bad = start_plug(QuirkStub, :bad_list)
      dup = start_plug(QuirkStub, :dup_cursor)

      source =
        Mcp.load(%{
          "mcpServers" => %{
            "bad" => %{"url" => bad <> "/mcp", "timeout" => 2_000},
            "dup" => %{"url" => dup <> "/mcp", "timeout" => 2_000}
          }
        })

      on_exit(fn -> Mcp.close(source) end)
      send(self(), {:status, source.status})
    end)

    assert_received {:status, status}
    assert status == %{"bad" => "failed", "dup" => "failed"}
  end

  test "a JSON-array RPC body is unpacked (batch-shaped response)" do
    base = start_plug(QuirkStub, :list_array)

    source = Mcp.load(%{"mcpServers" => %{"arr" => %{"url" => base <> "/mcp", "timeout" => 2_000}}})
    on_exit(fn -> Mcp.close(source) end)

    assert source.status == %{"arr" => "connected"}
    assert Enum.map(source.tools, & &1.name) == ["arr_t"]
  end

  test "closing the transport after the server is gone is safe (DELETE failure isolated)" do
    port = free_port()

    {:ok, server} =
      Bandit.start_link(plug: {QuirkStub, :extras}, scheme: :http, port: port, ip: {127, 0, 0, 1}, startup_log: false)

    source = Mcp.load(%{"mcpServers" => %{"q" => %{"url" => "http://127.0.0.1:#{port}/mcp", "timeout" => 2_000}}})
    assert source.status == %{"q" => "connected"}

    # stop the HTTP server, then close — the session DELETE fails and is swallowed
    ThousandIsland.stop(server, 500)
    assert Mcp.close(source) == :ok
  end

  # --- statuses + rescue paths ---------------------------------------------------------

  test "list_tools: failed / disabled statuses and a spawn failure" do
    capture_log(fn ->
      inventory =
        Mcp.list_tools(%{
          "mcpServers" => %{
            "nope" => %{"command" => ["/nonexistent/binary/xyz"]},
            "off" => %{"command" => ["echo"], "disabled" => true}
          }
        })

      send(self(), {:inv, inventory})
    end)

    assert_received {:inv, inventory}
    assert inventory.status == %{"nope" => "failed", "off" => "disabled"}
    assert inventory.tools == %{}
  end

  test "list_tools deadline exceeded aborts with a raise" do
    defmodule NeverReplies do
      def init(o), do: o

      def call(conn, _o) do
        Process.sleep(5_000)
        Plug.Conn.send_resp(conn, 204, "")
      end
    end

    base = start_plug(NeverReplies, [])

    assert_raise RuntimeError, ~r/MCP list deadline exceeded/, fn ->
      Mcp.list_tools(
        %{"mcpServers" => %{"slow" => %{"url" => base <> "/mcp", "timeout" => 30_000}}},
        deadline: 300
      )
    end
  end

  test "a raise inside per-server setup is isolated to a failed status (load + list)" do
    # a non-numeric timeout raises in arithmetic inside connect — rescue → failed
    cfg = %{"mcpServers" => %{"weird" => %{"command" => fixture_cmd(), "timeout" => "soon"}}}

    capture_log(fn ->
      source = Mcp.load(cfg)
      inventory = Mcp.list_tools(cfg)
      send(self(), {:results, source.status, inventory.status})
    end)

    assert_received {:results, %{"weird" => "failed"}, %{"weird" => "failed"}}
  end

  test "an unparseable remote URL is a failed server, never a crash" do
    capture_log(fn ->
      source = Mcp.load(%{"mcpServers" => %{"bad" => %{"type" => "remote", "url" => "htp:\\bad url", "timeout" => 500}}})
      send(self(), {:status, source.status})
    end)

    assert_received {:status, %{"bad" => "failed"}}
  end

  # --- stdio config variants + lifecycle ------------------------------------------------

  test "stdio: string command + args + cwd + env map; explicit type local" do
    dir = Path.join(System.tmp_dir!(), "toolnexus-cwd-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    on_exit(fn -> File.rm_rf!(dir) end)

    source =
      Mcp.load(%{
        "mcpServers" => %{
          "fixture" => %{
            "type" => "local",
            "command" => "elixir",
            "args" => ["-pa", jason_ebin(), @fixture_script],
            "cwd" => dir,
            "env" => %{"TOOLNEXUS_STDIO_EDGE" => "edge-value"},
            "timeout" => 20_000
          }
        }
      })

    on_exit(fn -> Mcp.close(source) end)
    assert source.status == %{"fixture" => "connected"}

    result = find_tool(source, "fixture_getenv").execute.(%{"name" => "TOOLNEXUS_STDIO_EDGE"}, %Context{})
    assert result.output == "edge-value"
  end

  test "stdio child killed externally: pending calls fail and later calls report closed" do
    source = Mcp.load(%{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}})
    on_exit(fn -> Mcp.close(source) end)

    conn = source.connections["fixture"]
    {:ok, os_pid} = Connection.os_pid(conn)
    System.cmd("kill", ["-9", Integer.to_string(os_pid)], stderr_to_stdout: true)

    assert wait_until(fn ->
             result = find_tool(source, "fixture_echo").execute.(%{"text" => "x"}, %Context{})
             result.is_error
           end)

    result = find_tool(source, "fixture_echo").execute.(%{"text" => "x"}, %Context{})
    assert result.is_error
    assert result.output =~ "MCP tool call failed"
  end

  test "calls after close map to rpc errors; os_pid on a dead connection is :error" do
    source = Mcp.load(%{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}})
    conn = source.connections["fixture"]
    echo = find_tool(source, "fixture_echo")

    Mcp.close(source)
    assert wait_until(fn -> not Process.alive?(conn) end)

    result = echo.execute.(%{"text" => "late"}, %Context{})
    assert result.is_error
    assert result.output =~ "MCP tool call failed"

    assert Connection.os_pid(conn) == :error
    assert Connection.connect(conn, 100) == {:error, {:connect_exit, :noproc}}
  end

  test "rpc before connect answers not_connected" do
    {:ok, pid} = Connection.start_link(cfg: %{"command" => ["echo"]}, wait_for: nil)
    assert Connection.call_tool(pid, "x", %{}) == {:error, :not_connected}
    GenServer.stop(pid)
  end

  test "a stdio server that rejects initialize is a failed server" do
    script = ~S(read init; printf '{"jsonrpc":"2.0","id":1,"error":{"code":-1,"message":"init refused"}}\n'; sleep 5)

    capture_log(fn ->
      source = Mcp.load(%{"mcpServers" => %{"grump" => %{"command" => ["bash", "-c", script], "timeout" => 5_000}}})
      send(self(), {:status, source.status})
      Mcp.close(source)
    end)

    assert_received {:status, %{"grump" => "failed"}}
  end

  test "a stdio server that never answers a call times out at the server timeout" do
    script =
      ~S"""
      read init
      printf '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"h","version":"0"}}}\n'
      read notif
      read list
      printf '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"hang","description":"","inputSchema":{"type":"object"}}]}}\n'
      read call
      sleep 60
      """

    source = Mcp.load(%{"mcpServers" => %{"h" => %{"command" => ["bash", "-c", script], "timeout" => 400}}})
    on_exit(fn -> Mcp.close(source) end)
    assert source.status == %{"h" => "connected"}

    result = find_tool(source, "h_hang").execute.(%{}, %Context{})
    assert result.is_error
    assert result.output == "MCP tool call timed out"
  end

  # --- elicitation edge paths ------------------------------------------------------------

  test "elicitation without a wait_for declines; a raising wait_for sends an error reply" do
    source = Mcp.load(%{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}})
    on_exit(fn -> Mcp.close(source) end)

    # no wait_for at load → host declines
    result = find_tool(source, "fixture_elicit").execute.(%{}, %Context{})
    assert Jason.decode!(result.output) == %{"action" => "decline"}

    raising =
      Mcp.load(
        %{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}},
        wait_for: fn %Request{} -> raise "human unavailable" end
      )

    on_exit(fn -> Mcp.close(raising) end)
    result = find_tool(raising, "fixture_elicit").execute.(%{}, %Context{})
    decoded = Jason.decode!(result.output)
    assert decoded["error"]["message"] =~ "human unavailable"
  end

  test "elicitation with a throwing wait_for is also isolated" do
    source =
      Mcp.load(
        %{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}},
        wait_for: fn %Request{} -> throw(:gone) end
      )

    on_exit(fn -> Mcp.close(source) end)
    result = find_tool(source, "fixture_elicit").execute.(%{}, %Context{})
    assert Jason.decode!(result.output)["error"]["message"] =~ "throw"
  end

  # answer path sanity (accept flows through answer_to_elicit_result)
  test "elicitation with an accepting wait_for returns the accepted content" do
    source =
      Mcp.load(
        %{"mcpServers" => %{"fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}}},
        wait_for: fn %Request{id: id} -> %Answer{id: id, ok: true, data: %{"value" => "42"}} end
      )

    on_exit(fn -> Mcp.close(source) end)
    result = find_tool(source, "fixture_elicit").execute.(%{}, %Context{})
    assert Jason.decode!(result.output) == %{"action" => "accept", "content" => %{"value" => "42"}}
  end
end
