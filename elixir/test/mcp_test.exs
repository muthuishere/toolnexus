defmodule Toolnexus.McpTest.HttpStub do
  @moduledoc """
  Minimal streamable-HTTP MCP server stub (JSON-RPC over POST). Records every
  request (method + headers + body) into a public ETS table for assertions.
  tools/list paginates once via nextCursor; tools/call replies over SSE to
  exercise the SSE-response parser; DELETE (session close) is recorded.
  """
  import Plug.Conn

  def init(opts), do: Map.new(opts)

  def call(conn, %{table: table}) do
    {:ok, body, conn} = read_body(conn)

    :ets.insert(
      table,
      {System.unique_integer([:monotonic, :positive]), conn.method, Map.new(conn.req_headers),
       body}
    )

    case conn.method do
      "DELETE" ->
        :ets.insert(table, {:deleted, true})
        send_resp(conn, 200, "")

      "POST" ->
        handle(conn, Jason.decode!(body))

      _ ->
        send_resp(conn, 405, "")
    end
  end

  defp handle(conn, %{"method" => "initialize", "id" => id}) do
    conn
    |> put_resp_header("mcp-session-id", "sess-abc")
    |> json(
      rpc(id, %{
        "protocolVersion" => "2025-06-18",
        "capabilities" => %{"tools" => %{}},
        "serverInfo" => %{"name" => "http-stub", "version" => "0"}
      })
    )
  end

  defp handle(conn, %{"method" => "notifications/" <> _}), do: send_resp(conn, 202, "")

  defp handle(conn, %{"method" => "tools/list", "id" => id} = msg) do
    case get_in(msg, ["params", "cursor"]) do
      nil -> json(conn, rpc(id, %{"tools" => [tool("t1")], "nextCursor" => "c1"}))
      "c1" -> json(conn, rpc(id, %{"tools" => [tool("t2")]}))
    end
  end

  defp handle(conn, %{"method" => "tools/call", "id" => id} = msg) do
    text =
      "called: #{get_in(msg, ["params", "name"])}/#{get_in(msg, ["params", "arguments", "text"])}"

    payload =
      Jason.encode!(
        rpc(id, %{"content" => [%{"type" => "text", "text" => text}], "isError" => false})
      )

    conn
    |> put_resp_content_type("text/event-stream")
    |> send_resp(200, "event: message\ndata: #{payload}\n\n")
  end

  defp handle(conn, %{"id" => id}),
    do:
      json(conn, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "error" => %{"code" => -32601, "message" => "nf"}
      })

  defp tool(name) do
    %{
      "name" => name,
      "description" => "stub tool #{name}",
      "inputSchema" => %{"type" => "object", "properties" => %{"text" => %{"type" => "string"}}}
    }
  end

  defp rpc(id, result), do: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

  defp json(conn, payload),
    do:
      conn |> put_resp_content_type("application/json") |> send_resp(200, Jason.encode!(payload))
end

defmodule Toolnexus.McpTest.HangingStub do
  @moduledoc "Accepts the request and never responds (per-server timeout test)."
  def init(opts), do: opts

  def call(conn, _opts) do
    Process.sleep(60_000)
    Plug.Conn.send_resp(conn, 204, "")
  end
end

defmodule Toolnexus.McpTest do
  use ExUnit.Case, async: false
  import ExUnit.CaptureLog

  alias Toolnexus.{Answer, Context, Mcp, Request}
  alias Toolnexus.Mcp.Connection

  @moduletag timeout: 60_000

  @repo_root Path.expand("../..", __DIR__)
  @fixture_script Path.expand("support/stdio_mcp_server.exs", __DIR__)

  defp fixture_cmd do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    ["elixir", "-pa", jason_ebin, @fixture_script]
  end

  defp fixture_config(overrides \\ %{}) do
    %{
      "mcpServers" => %{
        "fixture" => Map.merge(%{"command" => fixture_cmd(), "timeout" => 20_000}, overrides)
      }
    }
  end

  defp free_port do
    {:ok, socket} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(socket)
    :gen_tcp.close(socket)
    port
  end

  defp os_alive?(os_pid) do
    match?({_, 0}, System.cmd("kill", ["-0", Integer.to_string(os_pid)], stderr_to_stdout: true))
  end

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

  defp find_tool(source, name), do: Enum.find(source.tools, &(&1.name == name))
  defp execute(tool, args), do: tool.execute.(args, %Context{})

  # --- config parsing (SPEC §0.3) --------------------------------------------

  describe "config parsing" do
    test "parses the shared examples/mcp.json (shape only, no connect)" do
      config = Mcp.parse_config(Path.join(@repo_root, "examples/mcp.json"))
      assert Map.keys(config) |> Enum.sort() == ["everything", "example-remote"]

      assert config["everything"]["command"] == [
               "npx",
               "-y",
               "@modelcontextprotocol/server-everything"
             ]

      assert config["everything"]["type"] == "local"
      assert config["example-remote"]["url"] == "https://example.com/mcp"
      assert config["example-remote"]["enabled"] == false
    end

    test "accepts raw JSON string, map, and all three wrapper keys" do
      server = %{"command" => ["echo"]}
      assert Mcp.parse_config(~s({"mcpServers":{"a":{"command":["echo"]}}})) == %{"a" => server}
      assert Mcp.parse_config(%{"servers" => %{"a" => server}}) == %{"a" => server}
      assert Mcp.parse_config(%{"mcp" => %{"a" => server}}) == %{"a" => server}
      # bare map: reserved sibling keys stripped
      bare = %{
        "a" => server,
        "builtins" => false,
        "agents" => %{},
        "a2a" => %{},
        "mcpServer" => %{}
      }

      assert Mcp.parse_config(bare) == %{"a" => server}
    end

    test "expands ${ENV_VAR} in header values" do
      System.put_env("TOOLNEXUS_TEST_HDR", "tok-123")
      on_exit(fn -> System.delete_env("TOOLNEXUS_TEST_HDR") end)

      assert Mcp.expand_env_headers(%{
               "authorization" => "Bearer ${TOOLNEXUS_TEST_HDR}",
               "x" => "${MISSING_VAR_XYZ}"
             }) ==
               %{"authorization" => "Bearer tok-123", "x" => ""}
    end
  end

  # --- stdio (SPEC §2 local + §0.2/§0.4 + ADR-0007) ----------------------------

  describe "stdio server" do
    test "connects, prefixes tool names, round-trips calls; child alive after load, dead after close" do
      source = Mcp.load(fixture_config(%{"environment" => %{"FIXTURE_ENV_VAR" => "hello-env"}}))
      assert source.status == %{"fixture" => "connected"}

      names = source.tools |> Enum.map(& &1.name) |> Enum.sort()

      assert names == [
               "fixture_echo",
               "fixture_elicit",
               "fixture_fail",
               "fixture_getenv",
               "fixture_structured"
             ]

      # ADR-0007: the child must outlive connect.
      assert {:ok, os_pid} = Connection.os_pid(source.connections["fixture"])
      assert os_alive?(os_pid)

      echo = find_tool(source, "fixture_echo")
      assert echo.source == "mcp"
      assert echo.input_schema["type"] == "object"
      assert echo.input_schema["additionalProperties"] == false

      result = execute(echo, %{"text" => "hi"})
      assert result.output == "echo: hi"
      refute result.is_error
      assert result.metadata == %{server: "fixture"}

      # §0.4: structuredContent ⇒ JSON encode
      structured = execute(find_tool(source, "fixture_structured"), %{"text" => "s1"})
      refute structured.is_error
      assert Jason.decode!(structured.output) == %{"echoed" => "s1"}

      # §0.4: isError ⇒ error with joined text
      failed = execute(find_tool(source, "fixture_fail"), %{"text" => "x"})
      assert failed.is_error
      assert failed.output == "boom: x"

      # env: full parent env + config env merged over it
      env = execute(find_tool(source, "fixture_getenv"), %{"name" => "FIXTURE_ENV_VAR"})
      assert env.output == "hello-env"
      path = execute(find_tool(source, "fixture_getenv"), %{"name" => "PATH"})
      assert path.output != ""

      Mcp.close(source)

      # ADR-0007: the child must die on close.
      assert wait_until(fn -> not os_alive?(os_pid) end),
             "stdio child #{os_pid} still alive after close"
    end

    test "failed server is isolated; good server still loads" do
      config = %{
        "mcpServers" => %{
          "bad" => %{"command" => ["definitely-not-a-real-command-xyz-123"]},
          "fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}
        }
      }

      log =
        capture_log(fn ->
          source = Mcp.load(config)
          assert source.status == %{"bad" => "failed", "fixture" => "connected"}
          assert Enum.all?(source.tools, &String.starts_with?(&1.name, "fixture_"))
          Mcp.close(source)
        end)

      assert log =~ ~s(MCP server "bad" failed)
    end

    test "disabled servers are skipped (both spellings)" do
      config = %{
        "mcpServers" => %{
          "off1" => %{"command" => ["echo"], "enabled" => false},
          "off2" => %{"command" => ["echo"], "disabled" => true}
        }
      }

      source = Mcp.load(config)
      assert source.status == %{"off1" => "disabled", "off2" => "disabled"}
      assert source.tools == []
      Mcp.close(source)
    end
  end

  # --- elicitation bridge (§2 + §10) --------------------------------------------

  describe "elicitation bridge" do
    test "form mode -> kind input with data.schema; accept carries answer.data" do
      me = self()

      wait_for = fn %Request{} = request ->
        send(me, {:elicited, request})
        %Answer{id: request.id, ok: true, data: %{"value" => "42"}}
      end

      source = Mcp.load(fixture_config(), wait_for: wait_for)
      result = execute(find_tool(source, "fixture_elicit"), %{"mode" => "form"})
      refute result.is_error

      assert Jason.decode!(result.output) == %{
               "action" => "accept",
               "content" => %{"value" => "42"}
             }

      assert_received {:elicited,
                       %Request{kind: "input", prompt: "Enter value", url: nil, data: data}}

      assert data == %{
               "schema" => %{
                 "type" => "object",
                 "properties" => %{"value" => %{"type" => "string"}}
               }
             }

      Mcp.close(source)
    end

    test "url mode -> kind authorization; ok:false reason declined -> decline, else cancel" do
      me = self()

      wait_for = fn %Request{} = request ->
        send(me, {:elicited, request})
        %Answer{id: request.id, ok: false, reason: "declined"}
      end

      source = Mcp.load(fixture_config(), wait_for: wait_for)
      result = execute(find_tool(source, "fixture_elicit"), %{"mode" => "url"})
      assert Jason.decode!(result.output) == %{"action" => "decline"}

      assert_received {:elicited,
                       %Request{
                         kind: "authorization",
                         prompt: "Authorize access",
                         url: "https://example.com/authorize",
                         data: nil
                       }}

      Mcp.close(source)

      # non-"declined" reason -> cancel
      source2 = Mcp.load(fixture_config(), wait_for: fn r -> %Answer{id: r.id, ok: false} end)
      result2 = execute(find_tool(source2, "fixture_elicit"), %{"mode" => "url"})
      assert Jason.decode!(result2.output) == %{"action" => "cancel"}
      Mcp.close(source2)
    end

    test "without a wait_for the client declines" do
      source = Mcp.load(fixture_config())
      result = execute(find_tool(source, "fixture_elicit"), %{"mode" => "form"})
      assert Jason.decode!(result.output) == %{"action" => "decline"}
      Mcp.close(source)
    end
  end

  # --- Gap 7: per-server tools filter -----------------------------------------------

  describe "tools filter" do
    test "allowlist: >=1 true keeps only true names" do
      source = Mcp.load(fixture_config(%{"tools" => %{"echo" => true}}))
      assert Enum.map(source.tools, & &1.name) == ["fixture_echo"]
      Mcp.close(source)
    end

    test "droplist: only-false drops those names from all-on" do
      source = Mcp.load(fixture_config(%{"tools" => %{"fail" => false, "elicit" => false}}))
      names = source.tools |> Enum.map(& &1.name) |> Enum.sort()
      assert names == ["fixture_echo", "fixture_getenv", "fixture_structured"]
      Mcp.close(source)
    end

    test "unknown names are ignored with a warning" do
      log =
        capture_log(fn ->
          source = Mcp.load(fixture_config(%{"tools" => %{"echo" => true, "nope" => true}}))
          assert Enum.map(source.tools, & &1.name) == ["fixture_echo"]
          Mcp.close(source)
        end)

      assert log =~ ~s(server "fixture" tools filter name "nope" matched no tool)
    end
  end

  # --- Gap 6: list-only inventory ----------------------------------------------------

  describe "inventory list_tools" do
    test "returns ORIGINAL unprefixed defs, unfiltered, and leaves nothing running" do
      inv = Mcp.list_tools(fixture_config(%{"tools" => %{"echo" => true}}))
      assert inv.status == %{"fixture" => "connected"}

      names = inv.tools["fixture"] |> Enum.map(& &1["name"]) |> Enum.sort()
      # unfiltered (the tools map authors the allowlist) and original names
      assert names == ["echo", "elicit", "fail", "getenv", "structured"]

      [echo] = Enum.filter(inv.tools["fixture"], &(&1["name"] == "echo"))
      assert echo["description"] == "Echo the given text back."
      assert echo["inputSchema"]["type"] == "object"
      refute Map.has_key?(echo["inputSchema"], "additionalProperties")

      # teardown: no orphaned fixture process
      assert wait_until(fn ->
               {out, _} = System.cmd("pgrep", ["-f", @fixture_script], stderr_to_stdout: true)
               String.trim(out) == ""
             end),
             "orphan stdio fixture process left after list_tools"
    end
  end

  # --- streamable HTTP (SPEC §2 remote) -----------------------------------------------

  describe "streamable HTTP" do
    setup do
      table = :ets.new(:mcp_stub, [:public, :duplicate_bag])
      port = free_port()

      start_supervised!(
        {Bandit,
         plug: {Toolnexus.McpTest.HttpStub, [table: table]},
         scheme: :http,
         ip: {127, 0, 0, 1},
         port: port}
      )

      %{table: table, url: "http://127.0.0.1:#{port}/mcp"}
    end

    test "happy path: session header carried, ${ENV} headers expanded, pagination followed, DELETE on close",
         %{table: table, url: url} do
      System.put_env("TOOLNEXUS_TEST_TOKEN", "secret-token")
      on_exit(fn -> System.delete_env("TOOLNEXUS_TEST_TOKEN") end)

      config = %{
        "mcpServers" => %{
          "remote" => %{
            "url" => url,
            "headers" => %{"authorization" => "Bearer ${TOOLNEXUS_TEST_TOKEN}"},
            "timeout" => 10_000
          }
        }
      }

      source = Mcp.load(config)
      assert source.status == %{"remote" => "connected"}

      # pagination: t1 from page 1 (nextCursor once), t2 from page 2
      assert source.tools |> Enum.map(& &1.name) |> Enum.sort() == ["remote_t1", "remote_t2"]

      # tools/call replies over SSE — exercises the SSE response parser
      result = execute(find_tool(source, "remote_t1"), %{"text" => "hi"})
      refute result.is_error
      assert result.output == "called: t1/hi"

      Mcp.close(source)
      assert wait_until(fn -> :ets.lookup(table, :deleted) != [] end), "no DELETE on close"

      requests = :ets.tab2list(table) |> Enum.filter(&is_integer(elem(&1, 0)))

      # every request carried the expanded env header
      assert Enum.all?(requests, fn {_, _, headers, _} ->
               headers["authorization"] == "Bearer secret-token"
             end)

      # post-initialize requests carried the session id
      post_init = Enum.filter(requests, fn {_, _, _, body} -> body =~ "tools/" end)
      assert post_init != []

      assert Enum.all?(post_init, fn {_, _, headers, _} ->
               headers["mcp-session-id"] == "sess-abc"
             end)

      # DELETE carried the session too
      assert Enum.any?(requests, fn {_, method, headers, _} ->
               method == "DELETE" and headers["mcp-session-id"] == "sess-abc"
             end)
    end
  end

  describe "hanging HTTP server" do
    test "per-server timeout marks only that server failed" do
      port = free_port()

      start_supervised!(
        {Bandit,
         plug: Toolnexus.McpTest.HangingStub,
         scheme: :http,
         ip: {127, 0, 0, 1},
         port: port,
         thousand_island_options: [shutdown_timeout: 10]}
      )

      config = %{
        "mcpServers" => %{
          "hang" => %{"url" => "http://127.0.0.1:#{port}/mcp", "timeout" => 500},
          "fixture" => %{"command" => fixture_cmd(), "timeout" => 20_000}
        }
      }

      log =
        capture_log(fn ->
          started = System.monotonic_time(:millisecond)
          source = Mcp.load(config)
          elapsed = System.monotonic_time(:millisecond) - started

          assert source.status == %{"hang" => "failed", "fixture" => "connected"}
          # bounded: streamable attempt + SSE fallback, each ~500ms, plus slack
          assert elapsed < 15_000
          Mcp.close(source)
        end)

      assert log =~ ~s(MCP server "hang" failed)
    end

    test "overall load deadline aborts the whole load promptly (Gap 3)" do
      port = free_port()

      start_supervised!(
        {Bandit,
         plug: Toolnexus.McpTest.HangingStub,
         scheme: :http,
         ip: {127, 0, 0, 1},
         port: port,
         thousand_island_options: [shutdown_timeout: 10]}
      )

      config = %{
        "mcpServers" => %{
          "hang" => %{"url" => "http://127.0.0.1:#{port}/mcp", "timeout" => 30_000}
        }
      }

      started = System.monotonic_time(:millisecond)

      assert_raise RuntimeError, ~r/deadline exceeded/, fn ->
        Mcp.load(config, deadline: 400)
      end

      assert System.monotonic_time(:millisecond) - started < 5_000
    end
  end
end
