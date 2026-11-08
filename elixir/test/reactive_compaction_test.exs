defmodule Toolnexus.ReactiveCompactionTest do
  @moduledoc """
  SPEC §7F.1 reactive compaction on context overflow (issue #194). Replays the whole
  shared fixture `conformance/reactive_compaction/cases.json`: every `detection` case
  through `Client.context_overflow?/2`, every `loops` case through run AND stream in both
  styles against an in-test fake endpoint (the `:transport` seam).
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Client, Tool, ToolResult}

  @fixture Path.expand("../../conformance/reactive_compaction/cases.json", __DIR__)
  @cases @fixture |> File.read!() |> Jason.decode!()

  test "detection cases" do
    for c <- @cases["detection"] do
      assert Client.context_overflow?(c["status"], c["body"]) == c["overflow"], c["name"]

      decoded = with {:ok, m} <- Jason.decode(c["body"]), do: m, else: (_ -> c["body"])
      assert Client.context_overflow?(c["status"], decoded) == c["overflow"], c["name"]
    end

    refute Client.context_overflow?(nil, "context window")
    refute Client.context_overflow?(400, nil)
  end

  test "ContextOverflowError message is pinned" do
    e = Toolnexus.ContextOverflowError.exception(compactions: 2, status: 400, body: "x")

    assert Exception.message(e) ==
             "context overflow: 2 reactive compaction(s) did not make the prompt fit"

    assert {e.status, e.body, e.compactions} == {400, "x", 2}
  end

  # ---- scripted replies ----

  defp sse(lines), do: Enum.map_join(lines, "", &("data: " <> Jason.encode!(&1) <> "\n\n"))

  defp reply("openai", "text", false),
    do: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => "done"}}]}

  defp reply("openai", "tool", false),
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
                "function" => %{"name" => "t", "arguments" => "{}"}
              }
            ]
          }
        }
      ]
    }

  defp reply("anthropic", "text", false),
    do: %{"content" => [%{"type" => "text", "text" => "done"}]}

  defp reply("anthropic", "tool", false),
    do: %{"content" => [%{"type" => "tool_use", "id" => "c1", "name" => "t", "input" => %{}}]}

  defp reply("openai", "text", true),
    do: sse([%{"choices" => [%{"delta" => %{"content" => "done"}}]}]) <> "data: [DONE]\n\n"

  defp reply("openai", "tool", true),
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
                    "function" => %{"name" => "t", "arguments" => "{}"}
                  }
                ]
              }
            }
          ]
        }
      ]) <> "data: [DONE]\n\n"

  defp reply("anthropic", "text", true),
    do:
      sse([
        %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
        %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "text"}},
        %{
          "type" => "content_block_delta",
          "index" => 0,
          "delta" => %{"type" => "text_delta", "text" => "done"}
        },
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "end_turn"}}
      ])

  defp reply("anthropic", "tool", true),
    do:
      sse([
        %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
        %{
          "type" => "content_block_start",
          "index" => 0,
          "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "t"}
        },
        %{
          "type" => "content_block_delta",
          "index" => 0,
          "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}
        },
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}}
      ])

  defp error_body(raw), do: with({:ok, m} <- Jason.decode(raw), do: m, else: (_ -> raw))

  defp transport(style, script, calls) do
    fn req ->
      n = Agent.get_and_update(calls, fn n -> {n, n + 1} end)
      step = Enum.at(script, n) || "text"

      case step do
        "overflow" ->
          ob = @cases["overflowBodies"][style]
          {:ok, %{status: ob["status"], headers: %{}, body: error_body(ob["body"])}}

        "bad400" ->
          b = @cases["bad400"]
          {:ok, %{status: b["status"], headers: %{}, body: error_body(b["body"])}}

        kind ->
          {:ok,
           %{status: 200, headers: %{}, body: reply(style, kind, req.body["stream"] == true)}}
      end
    end
  end

  defp history,
    do:
      for(
        i <- 0..7,
        do: %{"role" => if(rem(i, 2) == 0, do: "user", else: "assistant"), "content" => "h#{i}"}
      )

  defp tool,
    do: %Tool{
      name: "t",
      description: "t",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _, _ -> ToolResult.ok("ok") end
    }

  defp exec(:run, client, _store), do: Client.run(client, "go", [tool()], history: history())

  defp exec(:stream, client, store) do
    Toolnexus.Client.InMemoryConversationStore.save(store, "s", history())
    evs = client |> Client.stream("go", [tool()], id: "s") |> Enum.to_list()
    List.last(evs).result
  end

  for c <-
        Jason.decode!(
          File.read!(Path.expand("../../conformance/reactive_compaction/cases.json", __DIR__))
        )["loops"],
      style <- ["openai", "anthropic"],
      mode <- [:run, :stream] do
    @c c
    @style style
    @mode mode
    test "#{c["name"]} [#{style} #{mode}]" do
      c = @c
      {:ok, calls} = Agent.start_link(fn -> 0 end)
      {:ok, hooks} = Agent.start_link(fn -> 0 end)
      store = Toolnexus.Client.InMemoryConversationStore.new()

      hook = fn %{messages: [_ | rest], tools: _, model: _, turn: _} ->
        Agent.update(hooks, &(&1 + 1))
        if c["hookNoop"], do: nil, else: %{messages: rest}
      end

      opts = [
        base_url: "http://x/v1",
        style: @style,
        model: "m",
        api_key: "k",
        transport: transport(@style, c["script"], calls),
        max_turns: 20,
        retries: 0,
        retry_base_ms: 1,
        store: store
      ]

      opts = if c["option"], do: opts ++ [compact_on_overflow: hook], else: opts
      client = Client.create(opts)
      expect = c["expect"]

      case expect["error"] do
        nil ->
          r = exec(@mode, client, store)
          assert r.text == expect["text"]
          assert Agent.get(hooks, & &1) == expect["compactions"]

        "LlmHttpError" ->
          e = assert_raise Toolnexus.ProviderError, fn -> exec(@mode, client, store) end
          assert e.status == expect["status"]

        "ContextOverflowError" ->
          e = assert_raise Toolnexus.ContextOverflowError, fn -> exec(@mode, client, store) end
          assert e.compactions == expect["compactions"]
          assert Exception.message(e) == expect["message"]
          assert e.status == 400
      end

      assert Agent.get(calls, & &1) == c["calls"]
    end
  end

  test "compaction replaces the transcript and does not consume a turn" do
    {:ok, calls} = Agent.start_link(fn -> 0 end)
    {:ok, bl} = Agent.start_link(fn -> 0 end)

    client =
      Client.create(
        base_url: "http://x/v1",
        model: "m",
        api_key: "k",
        retries: 0,
        transport: transport("openai", ["overflow", "text"], calls),
        hooks: %{before_llm: fn _ -> Agent.update(bl, &(&1 + 1)) && nil end},
        compact_on_overflow: fn %{messages: [_ | rest]} -> %{messages: rest} end
      )

    r = Client.run(client, "go", [tool()], history: history())
    assert r.turns == 1
    assert Agent.get(bl, & &1) == 1
    assert hd(r.messages)["content"] == "h1"
  end

  test "a non-ProviderError is not intercepted" do
    client =
      Client.create(
        base_url: "http://x/v1",
        model: "m",
        api_key: "k",
        retries: 0,
        transport: fn _ -> {:error, %RuntimeError{message: "boom"}} end,
        compact_on_overflow: fn _ -> nil end
      )

    assert_raise RuntimeError, fn -> Client.run(client, "go", [tool()]) end
  end
end
