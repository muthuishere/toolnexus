defmodule Toolnexus.ToolSchedulingTest do
  @moduledoc """
  add-tool-concurrency (SPEC §8 "Tool scheduling"), ported from golang/tool_concurrency_test.go.
  A local Bandit stub answers turn 1 with one call per name and turn 2 with "done", in all four
  wire modes (openai/anthropic × run/stream). Plus the §7D background-run recipe.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Client, Tool, ToolHints, ToolResult, ToolScheduling}
  alias Toolnexus.Agents.Runtime
  import Toolnexus.TestAgentHelpers

  defmodule Stub do
    @behaviour Plug
    import Plug.Conn

    def init(state), do: state

    def call(conn, {agent, names}) do
      {:ok, raw, conn} = read_body(conn)
      body = if raw == "", do: %{}, else: Jason.decode!(raw)
      first = Agent.get_and_update(agent, fn n -> {n == 0, n + 1} end)
      anth = String.contains?(conn.request_path, "messages")
      ids = Enum.with_index(names, 1)

      if body["stream"] do
        events =
          cond do
            not anth and first ->
              Enum.map(ids, fn {n, i} ->
                %{
                  "choices" => [
                    %{
                      "delta" => %{
                        "tool_calls" => [
                          %{
                            "index" => i - 1,
                            "id" => "c#{i}",
                            "type" => "function",
                            "function" => %{"name" => n, "arguments" => "{}"}
                          }
                        ]
                      }
                    }
                  ]
                }
              end) ++ [%{"choices" => [%{"delta" => %{}, "finish_reason" => "tool_calls"}]}]

            not anth ->
              [
                %{"choices" => [%{"delta" => %{"content" => "done"}}]},
                %{"choices" => [%{"delta" => %{}, "finish_reason" => "stop"}]}
              ]

            first ->
              Enum.flat_map(ids, fn {n, i} ->
                [
                  %{
                    "type" => "content_block_start",
                    "index" => i - 1,
                    "content_block" => %{"type" => "tool_use", "id" => "c#{i}", "name" => n}
                  },
                  %{
                    "type" => "content_block_delta",
                    "index" => i - 1,
                    "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}
                  },
                  %{"type" => "content_block_stop", "index" => i - 1}
                ]
              end) ++
                [
                  %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}},
                  %{"type" => "message_stop"}
                ]

            true ->
              [
                %{
                  "type" => "content_block_start",
                  "index" => 0,
                  "content_block" => %{"type" => "text"}
                },
                %{
                  "type" => "content_block_delta",
                  "index" => 0,
                  "delta" => %{"type" => "text_delta", "text" => "done"}
                },
                %{"type" => "message_delta", "delta" => %{"stop_reason" => "end_turn"}},
                %{"type" => "message_stop"}
              ]
          end

        text =
          Enum.map_join(events, "", &"data: #{Jason.encode!(&1)}\n\n") <>
            if(anth, do: "", else: "data: [DONE]\n\n")

        conn |> put_resp_content_type("text/event-stream") |> send_resp(200, text)
      else
        resp =
          cond do
            anth and first ->
              %{
                "content" =>
                  Enum.map(ids, fn {n, i} ->
                    %{"type" => "tool_use", "id" => "c#{i}", "name" => n, "input" => %{}}
                  end),
                "stop_reason" => "tool_use"
              }

            anth ->
              %{"content" => [%{"type" => "text", "text" => "done"}], "stop_reason" => "end_turn"}

            first ->
              %{
                "choices" => [
                  %{
                    "message" => %{
                      "role" => "assistant",
                      "content" => nil,
                      "tool_calls" =>
                        Enum.map(ids, fn {n, i} ->
                          %{
                            "id" => "c#{i}",
                            "type" => "function",
                            "function" => %{"name" => n, "arguments" => "{}"}
                          }
                        end)
                    }
                  }
                ]
              }

            true ->
              %{"choices" => [%{"message" => %{"role" => "assistant", "content" => "done"}}]}
          end

        conn |> put_resp_content_type("application/json") |> send_resp(200, Jason.encode!(resp))
      end
    end
  end

  defp start_stub(names) do
    {:ok, agent} = Agent.start_link(fn -> 0 end)
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {Stub, {agent, names}}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    "http://127.0.0.1:#{port}"
  end

  # A tracker: an Agent holding inflight/peak/log.
  defp tracker, do: elem(Agent.start_link(fn -> %{inflight: 0, peak: 0, log: []} end), 1)

  defp tool(tr, name, read_only) do
    %Tool{
      name: name,
      description: name,
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "native",
      hints: if(read_only, do: %ToolHints{read_only: true}),
      execute: fn _args, _ctx ->
        Agent.update(tr, fn s ->
          n = s.inflight + 1
          %{s | inflight: n, peak: max(s.peak, n), log: s.log ++ ["start #{name}"]}
        end)

        Process.sleep(60)

        Agent.update(tr, fn s ->
          %{s | inflight: s.inflight - 1, log: s.log ++ ["end #{name}"]}
        end)

        %ToolResult{output: "out #{name}", is_error: false}
      end
    }
  end

  defp tools(tr, names), do: Enum.map(names, &tool(tr, &1, String.starts_with?(&1, "r")))
  defp reads(n), do: Enum.map(1..n, &"r#{&1}")

  defp run_sched({style, stream?}, names, tools, cap \\ nil) do
    base = start_stub(names)
    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: tools)

    opts = [
      base_url: if(style == "openai", do: base <> "/v1", else: base),
      style: style,
      model: "m",
      api_key: "k"
    ]

    client = Client.create(if(cap, do: Keyword.put(opts, :max_tool_concurrency, cap), else: opts))

    result =
      if stream? do
        client
        |> Client.stream("go", tk)
        |> Enum.find(&(&1.type == "done"))
        |> Map.fetch!(:result)
      else
        case Client.run(client, "go", tk) do
          {:ok, r} -> r
          r -> r
        end
      end

    Toolnexus.Toolkit.close(tk)
    Enum.map(result.tool_calls, & &1.name)
  end

  @modes [{"openai", false}, {"anthropic", false}, {"openai", true}, {"anthropic", true}]

  for {style, stream?} = mode <- @modes do
    @mode mode
    test "#{style} stream=#{stream?}: two unsafe calls serialize in emitted order" do
      tr = tracker()
      got = run_sched(@mode, ["w1", "w2"], tools(tr, ["w1", "w2"]))
      s = Agent.get(tr, & &1)
      assert s.peak == 1
      assert s.log == ["start w1", "end w1", "start w2", "end w2"]
      assert got == ["w1", "w2"]
    end

    test "#{style} stream=#{stream?}: read-only calls overlap" do
      tr = tracker()
      got = run_sched(@mode, reads(6), tools(tr, reads(6)))
      assert Agent.get(tr, & &1.peak) == 6
      assert got == reads(6)
    end

    test "#{style} stream=#{stream?}: the cap is honoured" do
      tr = tracker()
      run_sched(@mode, reads(6), tools(tr, reads(6)), 2)
      assert Agent.get(tr, & &1.peak) == 2
    end

    test "#{style} stream=#{stream?}: mixed runs keep order" do
      tr = tracker()
      names = ["r1", "r2", "w", "r3", "r4"]
      got = run_sched(@mode, names, tools(tr, names))
      s = Agent.get(tr, & &1)
      pos = fn e -> Enum.find_index(s.log, &(&1 == e)) end
      for r <- ["r1", "r2"], do: assert(pos.("end #{r}") < pos.("start w"), inspect(s.log))
      for r <- ["r3", "r4"], do: assert(pos.("start #{r}") > pos.("end w"), inspect(s.log))
      assert s.peak == 2
      assert got == names
    end
  end

  test "env override, option wins, invalid values fall back" do
    assert ToolScheduling.resolve_max_tool_concurrency(nil, fn _ -> "3" end) == 3
    assert ToolScheduling.resolve_max_tool_concurrency(4, fn _ -> "3" end) == 4

    for bad <- ["0", "-2", "abc", "", nil] do
      assert ToolScheduling.resolve_max_tool_concurrency(nil, fn _ -> bad end) == 10, inspect(bad)
    end

    assert ToolScheduling.resolve_max_tool_concurrency(0, fn _ -> nil end) == 10

    System.put_env("TOOLNEXUS_MAX_TOOL_CONCURRENCY", "3")

    try do
      tr = tracker()
      run_sched({"openai", false}, reads(6), tools(tr, reads(6)))
      assert Agent.get(tr, & &1.peak) == 3
    after
      System.delete_env("TOOLNEXUS_MAX_TOOL_CONCURRENCY")
    end
  end

  test "the safety rule" do
    tk = Toolnexus.create_toolkit!([])

    want = %{
      "read" => true,
      "grep" => true,
      "glob" => true,
      "webfetch" => true,
      "bash" => false,
      "write" => false,
      "edit" => false,
      "apply_patch" => false
    }

    for {name, safe} <- want do
      t = Toolnexus.Toolkit.get(tk, name)
      assert t, name
      assert ToolScheduling.concurrency_safe?(t, %{}) == safe, name
    end

    Toolnexus.Toolkit.close(tk)

    plain = Toolnexus.define_tool(name: "x", description: "", execute: fn _ -> "" end)
    refute ToolScheduling.concurrency_safe?(plain, %{})

    own =
      Toolnexus.define_tool(
        name: "y",
        description: "",
        execute: fn _ -> "" end,
        hints: %ToolHints{read_only: true},
        concurrency_safe: fn a -> a["dry"] == true end
      )

    refute ToolScheduling.concurrency_safe?(own, %{})
    assert ToolScheduling.concurrency_safe?(own, %{"dry" => true})
  end

  # SPEC §7D "Background runs": spawn + wake return at once with the id; a background wait
  # delivers the completion to the host's callback. No new API; this pins the recipe.
  test "background run recipe: id at once, completion via a background wait" do
    rt = new_rt()
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")
    id = Runtime.snapshot(h).id
    assert is_binary(id) and id != ""
    parent = self()
    Runtime.wake(rt, h, "answer using two lookups")
    spawn(fn -> send(parent, {:done, id, Runtime.wait(rt, h)}) end)
    assert_receive {:done, ^id, r}, 5000
    assert r.status == "done", r.text
    assert String.contains?(r.text, "found:")
  end
end
