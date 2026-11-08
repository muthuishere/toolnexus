defmodule Toolnexus.AcpSeamTest do
  @moduledoc """
  SPEC §8C (ADR 0041) — replays conformance/acp-client/cases.json against the
  port's fake ACP agent (`test/support/fake_acp_server.exs`, scenario `seam`)
  and its own MCP client. (`stream.inProcess` is replayed in in_process_test.exs.)
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Acp, Client, Context, Mcp, Native, Toolkit}

  @moduletag timeout: 60_000

  @cases Path.expand("../../conformance/acp-client/cases.json", __DIR__)
  @fixture_script Path.expand("support/fake_acp_server.exs", __DIR__)
  @examples_skills Path.expand("../../examples/skills", __DIR__)

  defp cases, do: @cases |> File.read!() |> Jason.decode!()

  defp fake_cmd do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    ["elixir", "-pa", jason_ebin, @fixture_script, "seam"]
  end

  defp connect!(opts) do
    {:ok, pid} = Acp.connect(fake_cmd(), opts)
    on_exit(fn -> Acp.close(pid) end)
    pid
  end

  defp recorded(pid) do
    {:ok, text} = Acp.prompt(pid, "q")
    Jason.decode!(text)
  end

  defp bare_toolkit, do: Toolnexus.create_toolkit!(builtins: false)

  # -- stream.acpDrip ----------------------------------------------------------

  test "stream.acpDrip: deltas arrive incrementally through loadACP + the in-process client" do
    fx = cases()["stream"]["acpDrip"]
    pid = connect!(env: fx["fakeEnv"])
    client = Client.create_in_process(model: "acp", generate_stream: Acp.generate_stream(pid))

    t0 = System.monotonic_time(:millisecond)

    timed =
      client
      |> Client.stream("q", bare_toolkit())
      |> Enum.map(fn ev -> {System.monotonic_time(:millisecond) - t0, ev} end)

    events = Enum.map(timed, &elem(&1, 1))
    assert for(%{type: "text", delta: d} <- events, do: d) == fx["chunks"]

    want = fx["expectThought"]

    assert [%{delta: d, via: v}] = Enum.filter(events, &(&1.type == "thought"))
    assert {d, v} == {want["delta"], want["via"]}

    {first_at, _} = Enum.find(timed, fn {_, ev} -> ev.type == "text" end)
    {done_at, %{type: "done", result: done}} = List.last(timed)
    assert done.text == fx["expectDoneText"]
    assert done_at - first_at >= fx["minLeadMs"]

    assert Client.run(client, "again", bare_toolkit()).text == fx["expectDoneText"]
  end

  # -- stream.acpToolUpdate (#188) ---------------------------------------------

  test "stream.acpToolUpdate: rawInput emits tool_call_args, title is remembered" do
    fx = cases()["stream"]["acpToolUpdate"]
    pid = connect!(env: fx["fakeEnv"])
    client = Client.create_in_process(model: "acp", generate_stream: Acp.generate_stream(pid))

    events = client |> Client.stream("q", bare_toolkit()) |> Enum.to_list()

    tool_events =
      Enum.filter(events, &(&1.type in ["tool_call", "tool_call_args", "tool_result"]))

    got =
      Enum.map(tool_events, fn
        %{type: "tool_call"} = e ->
          %{"type" => e.type, "id" => e.id, "name" => e.name, "via" => e.via}

        %{type: "tool_call_args"} = e ->
          %{"type" => e.type, "id" => e.id, "name" => e.name, "args" => e.args, "via" => e.via}

        %{type: "tool_result"} = e ->
          %{
            "type" => e.type,
            "id" => e.id,
            "name" => e.name,
            "output" => e.output,
            "isError" => e.is_error,
            "via" => e.via
          }
      end)

    assert got == fx["expectEvents"]
    assert %{type: "done", result: done} = List.last(events)
    assert done.text == fx["expectDoneText"]
  end

  # -- passthrough ---------------------------------------------------------------

  test "passthrough: mcpServers verbatim and in order, sessionMeta, clientCapsMeta, model" do
    fx = cases()["passthrough"]
    o = fx["options"]

    rec =
      connect!(
        mcp_servers: o["mcpServers"],
        session_meta: o["sessionMeta"],
        client_caps_meta: o["clientCapsMeta"],
        model: o["model"]
      )
      |> recorded()

    assert rec["session/new"]["mcpServers"] == o["mcpServers"]
    assert rec["session/new"]["_meta"] == o["sessionMeta"]
    assert rec["initialize"]["clientCapabilities"] == fx["expectInitializeClientCapabilities"]

    assert Map.take(rec["session/set_config_option"], ["configId", "value"]) ==
             fx["expectSetConfigOption"]

    assert rec["session/set_config_option"]["sessionId"] == "sess-1"
    refute Map.has_key?(rec, "session/set_model")
  end

  test "passthrough: set_model fallback when set_config_option is rejected" do
    fx = cases()["passthrough"]
    fb = fx["fallbackSetModel"]
    rec = connect!(env: fb["fakeEnv"], model: fx["options"]["model"]) |> recorded()

    assert Map.take(rec["session/set_model"], ["modelId"]) == fb["expectSetModel"]
  end

  test "liveSwitch: set_mode and set_model on a running client, no restart" do
    fx = cases()["liveSwitch"]
    pid = connect!([])

    assert :ok = Acp.set_mode(pid, fx["mode"])
    assert :ok = Acp.set_model(pid, fx["model"])
    rec = recorded(pid)

    assert Map.take(rec["session/set_mode"], ["modeId"]) == fx["expectSetMode"]
    assert rec["session/set_mode"]["sessionId"] == "sess-1"

    assert Map.take(rec["session/set_config_option"], ["configId", "value"]) ==
             fx["expectSetConfigOption"]

    assert rec["session/set_config_option"]["sessionId"] == "sess-1"
    assert rec["_counts"]["initialize"] == fx["expectInitializeCount"]
    assert rec["_counts"]["session/new"] == fx["expectSessionNewCount"]
  end

  test "liveSwitch: set_model falls back to set_model on a running client" do
    fx = cases()["liveSwitch"]
    pid = connect!(env: fx["fallback"]["fakeEnv"])

    assert :ok = Acp.set_model(pid, fx["model"])
    rec = recorded(pid)
    assert Map.take(rec["session/set_model"], ["modelId"]) == fx["fallback"]["expectSetModel"]
  end

  test "liveSwitch: both model requests rejected fails the call only" do
    pid = connect!(env: %{"TN_FAKE_NO_CONFIG_OPTION" => "1", "TN_FAKE_NO_SET_MODEL" => "1"})
    assert {:error, {:set_model, "m-2", _e1, _e2}} = Acp.set_model(pid, "m-2")
    assert {:ok, _} = Acp.prompt(pid, "q")
  end

  test "liveSwitch: a rejected mode fails only that call" do
    fx = cases()["liveSwitch"]
    pid = connect!(env: fx["reject"]["fakeEnv"])

    assert {:error, err} = Acp.set_mode(pid, fx["mode"])
    assert inspect(err) =~ fx["reject"]["expectErrorContains"]
    assert {:ok, _} = Acp.prompt(pid, "q")
  end

  test "liveSwitch: a closed client refuses" do
    pid = connect!([])
    Acp.close(pid)
    assert {:error, _} = Acp.set_mode(pid, "plan")
    assert {:error, _} = Acp.set_model(pid, "m")
  end

  test "passthrough: bare — mcpServers [] and no _meta keys" do
    bare = cases()["passthrough"]["bareExpect"]
    rec = connect!([]) |> recorded()

    assert rec["session/new"]["mcpServers"] == bare["mcpServers"]
    assert Map.has_key?(rec["session/new"], "_meta") == bare["sessionNewHasMeta"]

    assert Map.has_key?(rec["initialize"]["clientCapabilities"], "_meta") ==
             bare["clientCapabilitiesHasMeta"]

    refute Map.has_key?(rec, "session/set_config_option")
  end

  # -- agentExit / unknownRequest --------------------------------------------------

  test "agentExit: the generate fails promptly with the pinned error" do
    fx = cases()["agentExit"]
    pid = connect!(env: fx["fakeEnv"], timeout: 30_000)
    gen = Acp.generate(pid)

    {us, err} =
      :timer.tc(fn ->
        try do
          gen.(%{messages: [%{"role" => "user", "content" => "q"}]})
          nil
        rescue
          e in RuntimeError -> e
        end
      end)

    assert err, "generate did not fail"
    assert Exception.message(err) =~ fx["expectError"]
    assert us < 10_000_000
  end

  test "unknownRequest: an unimplemented agent->client request is answered -32601" do
    fx = cases()["unknownRequest"]
    pid = connect!(env: fx["fakeEnv"])
    # the fake answers "ok" only when it received error code -32601 (expectCode)
    assert fx["expectCode"] == -32601
    assert {:ok, fx["expectText"]} == Acp.prompt(pid, "q")
  end

  # -- asMcpServer -------------------------------------------------------------------

  defp echo_tool do
    Native.define_tool(
      name: "echo",
      description: "Echo text",
      input_schema: %{
        "type" => "object",
        "properties" => %{"text" => %{"type" => "string"}},
        "required" => ["text"]
      },
      execute: fn args -> args["text"] end
    )
  end

  defp raw(method, url, headers) do
    Req.request!(
      method: method,
      url: url,
      headers: headers,
      json: %{"jsonrpc" => "2.0", "id" => 1, "method" => "tools/list"},
      retry: false,
      decode_body: false
    )
  end

  test "asMcpServer: loopback, bearer-guarded, served verbatim, idempotent close" do
    fx = cases()["asMcpServer"]

    tk =
      Toolnexus.create_toolkit!(
        skills_dir: [@examples_skills],
        extra_tools: [echo_tool()],
        builtins: false
      )

    srv = Toolkit.as_mcp_server(tk)
    other = Toolkit.as_mcp_server(tk)
    on_exit(fn -> srv.close.() && other.close.() end)

    assert srv.url =~ Regex.compile!(fx["urlPattern"])
    assert srv.token =~ Regex.compile!(fx["tokenPattern"])
    assert fx["twoServersDistinctTokens"] and srv.token != other.token
    other.close.()

    no = raw(:post, srv.url, [])
    assert no.status == fx["noTokenStatus"]

    for {k, v} <- fx["noTokenHeader"],
        do: assert(Req.Response.get_header(no, String.downcase(k)) == [v])

    assert raw(:post, srv.url, [{"authorization", "Bearer nope"}]).status ==
             fx["wrongTokenStatus"]

    auth = [{"authorization", "Bearer " <> srv.token}]
    assert raw(:get, srv.url, auth).status == fx["getWithTokenStatus"]
    other_path = String.replace_suffix(srv.url, "/mcp", "/other")
    assert raw(:post, other_path, auth).status == fx["otherPathStatus"]

    ok = raw(:post, srv.url, auth ++ [{"accept", "application/json, text/event-stream"}])
    names = Jason.decode!(ok.body)["result"]["tools"] |> Enum.map(& &1["name"])
    for n <- fx["toolsListContains"], do: assert(n in names)

    # the port's own MCP client
    source =
      Mcp.load(%{
        "mcpServers" => %{
          "s" => %{
            "url" => srv.url,
            "headers" => %{"Authorization" => "Bearer " <> srv.token},
            "timeout" => 10_000
          }
        }
      })

    on_exit(fn -> Mcp.close(source) end)
    assert source.status == %{"s" => "connected"}
    call = fx["callEcho"]
    echo = Enum.find(source.tools, &(&1.name == "s_" <> call["name"]))
    assert echo.execute.(call["arguments"], %Context{}).output == call["expectText"]
    assert Enum.any?(source.tools, &(&1.name == "s_skill"))

    assert :ok = srv.close.()
    assert :ok = srv.close.()
    assert fx["afterCloseRefused"]
    assert_raise Req.TransportError, fn -> raw(:post, srv.url, auth) end
  end
end
