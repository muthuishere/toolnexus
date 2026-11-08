defmodule Toolnexus.RoundBoundaryTest do
  @moduledoc """
  SPEC §8 `after_round` (change `add-round-boundary-hook`, #192). Shared fixture:
  examples/round-boundary/fixture.json (R1-R6) — run + stream × openai + anthropic and
  the §7D loop. Hermetic: a scripted transport ("@call" = an echo tool call).
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Agents, Client, Tool, ToolResult}

  defp sse(lines), do: Enum.map_join(lines, "", &("data: " <> Jason.encode!(&1) <> "\n\n"))

  @args ~s({"text":"hi"})

  defp reply("openai", "@call", false),
    do: %{
      "choices" => [
        %{
          "message" => %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{
                "id" => "c1",
                "type" => "function",
                "function" => %{"name" => "echo", "arguments" => @args}
              }
            ]
          }
        }
      ]
    }

  defp reply("openai", t, false),
    do: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => t}}]}

  defp reply("anthropic", "@call", false),
    do: %{
      "content" => [
        %{"type" => "tool_use", "id" => "c1", "name" => "echo", "input" => %{"text" => "hi"}}
      ],
      "stop_reason" => "tool_use"
    }

  defp reply("anthropic", t, false),
    do: %{"content" => [%{"type" => "text", "text" => t}], "stop_reason" => "end_turn"}

  defp reply("openai", "@call", true),
    do:
      sse([
        %{
          "choices" => [
            %{
              "delta" => %{
                "tool_calls" => [
                  %{
                    "index" => 0,
                    "id" => "c1",
                    "type" => "function",
                    "function" => %{"name" => "echo", "arguments" => @args}
                  }
                ]
              }
            }
          ]
        }
      ]) <> "data: [DONE]\n\n"

  defp reply("openai", t, true),
    do: sse([%{"choices" => [%{"delta" => %{"content" => t}}]}]) <> "data: [DONE]\n\n"

  defp reply("anthropic", "@call", true),
    do:
      sse([
        %{
          "type" => "content_block_start",
          "index" => 0,
          "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "echo"}
        },
        %{
          "type" => "content_block_delta",
          "index" => 0,
          "delta" => %{"type" => "input_json_delta", "partial_json" => @args}
        },
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}}
      ])

  defp reply("anthropic", t, true),
    do:
      sse([
        %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "text"}},
        %{
          "type" => "content_block_delta",
          "index" => 0,
          "delta" => %{"type" => "text_delta", "text" => t}
        },
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "end_turn"}}
      ])

  defp recorder(style, replies) do
    {:ok, agent} = Agent.start_link(fn -> [] end)

    transport = fn req ->
      n = Agent.get_and_update(agent, fn b -> {length(b), b ++ [req.body]} end)
      r = Enum.at(replies, min(n, length(replies) - 1))
      {:ok, %{status: 200, headers: %{}, body: reply(style, r, req.body["stream"] == true)}}
    end

    {agent, transport}
  end

  defp bodies(agent), do: Agent.get(agent, & &1)

  defp opts(style, transport, hooks, max_turns) do
    [
      base_url: "http://x/v1",
      style: style,
      model: "m",
      api_key: "k",
      transport: transport,
      max_turns: max_turns,
      retries: 0,
      retry_base_ms: 1
    ] ++ if(hooks, do: [hooks: hooks], else: [])
  end

  defp echo do
    %Tool{
      name: "echo",
      description: "echo",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn args, _ -> ToolResult.ok(to_string(args["text"])) end
    }
  end

  defp go(:run, o), do: Client.run(Client.create(o), "go", [echo()])

  defp go(:stream, o) do
    events = Client.stream(Client.create(o), "go", [echo()]) |> Enum.to_list()
    %{type: "done", result: r} = List.last(events)
    r
  end

  defp go(:agent, o) do
    {out, _} =
      Agents.Loop.run(Agents.loop(Agents.agent("a", does: "x"), Map.new(o), [echo()]), "go")

    out.result
  end

  defp tail(agent, i, n), do: bodies(agent) |> Enum.at(i) |> Map.get("messages") |> Enum.take(-n)

  defp record(pid), do: fn ev -> send(pid, {:round, {ev.turn, ev.final, ev.text}}) end

  defp rounds(acc \\ []) do
    receive do
      {:round, r} -> rounds(acc ++ [r])
    after
      0 -> acc
    end
  end

  for style <- ["openai", "anthropic"], mode <- [:run, :stream, :agent] do
    @style style
    @mode mode

    test "R1 inject after tool results (#{style} #{mode})" do
      {agent, t} = recorder(@style, ["@call", "done"])
      rec = record(self())

      hook = fn ev ->
        rec.(ev)
        if ev.final, do: nil, else: %{inject: ["todo: 2 open"]}
      end

      r = go(@mode, opts(@style, t, %{after_round: hook}, 10))
      assert length(bodies(agent)) == 2
      assert r.text == "done"
      assert List.last(tail(agent, 1, 1)) == %{"role" => "user", "content" => "todo: 2 open"}
      assert rounds() == [{0, false, ""}, {1, true, "done"}]
    end

    test "R2 continue when the model stops (#{style} #{mode})" do
      {agent, t} = recorder(@style, ["first", "second"])
      rec = record(self())

      hook = fn ev ->
        rec.(ev)
        if ev.final and ev.turn == 0, do: %{inject: ["keep going"]}, else: nil
      end

      r = go(@mode, opts(@style, t, %{after_round: hook}, 10))
      assert length(bodies(agent)) == 2
      assert r.text == "second"
      [a, u] = tail(agent, 1, 2)
      assert a["role"] == "assistant"
      assert u == %{"role" => "user", "content" => "keep going"}
      assert rounds() == [{0, true, "first"}, {1, true, "second"}]
    end

    test "R3 empty injection ends normally (#{style} #{mode})" do
      {agent, t} = recorder(@style, ["only"])
      rec = record(self())
      r = go(@mode, opts(@style, t, %{after_round: fn ev -> rec.(ev) && %{inject: [""]} end}, 10))
      assert length(bodies(agent)) == 1
      assert r.text == "only"
      assert rounds() == [{0, true, "only"}]
    end

    test "R4 no continuation past max_turns (#{style} #{mode})" do
      {agent, t} = recorder(@style, ["only"])
      rec = record(self())

      r =
        go(
          @mode,
          opts(@style, t, %{after_round: fn ev -> rec.(ev) && %{inject: ["again"]} end}, 1)
        )

      assert length(bodies(agent)) == 1
      assert r.text == "only"
      assert rounds() == []
    end

    test "R5 a failing hook stops the run (#{style} #{mode})" do
      for {exc, hook} <- [
            {RuntimeError, fn _ -> raise "round-boom" end},
            {Toolnexus.HookError, fn _ -> {:error, "round-boom"} end}
          ] do
        {agent, t} = recorder(@style, ["@call", "never"])

        assert_raise exc, ~r/round-boom/, fn ->
          go(@mode, opts(@style, t, %{after_round: hook}, 10))
        end

        assert length(bodies(agent)) == 1
      end
    end

    test "absent hook is byte-identical (#{style} #{mode})" do
      {a, ta} = recorder(@style, ["@call", "done"])
      {b, tb} = recorder(@style, ["@call", "done"])
      go(@mode, opts(@style, ta, nil, 10))
      go(@mode, opts(@style, tb, %{after_round: fn _ -> nil end}, 10))
      assert bodies(a) == bodies(b)
    end
  end

  test "a wrong-arity after_round is loud" do
    {_agent, t} = recorder("openai", ["only"])

    assert_raise ArgumentError, ~r/after_round/, fn ->
      go(:run, opts("openai", t, %{after_round: fn _, _ -> nil end}, 10))
    end
  end
end
