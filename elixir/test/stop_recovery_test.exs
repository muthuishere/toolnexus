defmodule Toolnexus.StopRecoveryTest do
  @moduledoc """
  SPEC §8 "Stop-reason recovery" — replays every case of conformance/stop_recovery in both
  styles (openai, anthropic) and both loops (run, stream) against a fake `:transport`.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Client, Native}

  @fixture Path.expand("../../conformance/stop_recovery/cases.json", __DIR__)
  @external_resource @fixture
  @doc_ Jason.decode!(File.read!(@fixture))

  test "pinned texts match the fixture" do
    m = @doc_["messages"]
    pinned = Client.stop_recovery_messages()
    assert pinned.nudge == m["NUDGE"]
    assert pinned.continue == m["CONTINUE"]
    assert pinned.corrective == m["CORRECTIVE"]
  end

  for c <- @doc_["cases"], style <- ["openai", "anthropic"], mode <- [:run, :stream] do
    @case c
    @style style
    @mode mode
    test "#{c["name"]} #{style} #{mode}" do
      replay(@case, @style, @mode, @doc_["messages"])
    end
  end

  test "option off is the default" do
    assert Client.create(base_url: "http://x.invalid", model: "m", api_key: "k").stop_recovery ==
             false
  end

  # ---- replay ----

  defp replay(c, style, mode, messages) do
    me = self()
    {:ok, agent} = Agent.start_link(fn -> {c["responses"], []} end)

    transport = fn req ->
      resp =
        Agent.get_and_update(agent, fn {[r | rest], bodies} ->
          {r, {rest, bodies ++ [req.body]}}
        end)

      body = if mode == :stream, do: sse(style, resp), else: json(style, resp)
      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    echo =
      Native.define_tool(
        name: "echo",
        description: "echo",
        input_schema: %{"type" => "object", "properties" => %{}},
        execute: fn args ->
          send(me, {:echo, args})
          "ok"
        end
      )

    client =
      Client.create(
        base_url: "http://fake.invalid",
        style: style,
        model: "m",
        api_key: "k",
        transport: transport,
        stop_recovery: c["stopRecovery"],
        max_turns: c["maxTurns"] || 10
      )

    result =
      case mode do
        :run ->
          Client.run(client, "hi", [echo])

        :stream ->
          events = client |> Client.stream("hi", [echo]) |> Enum.to_list()
          done = Enum.find(events, &(&1.type == "done"))

          # a corrected response emits no tool_call/tool_result events
          for t <- ["tool_call", "tool_result"] do
            assert Enum.count(events, &(&1.type == t)) == length(c["expect"]["toolRuns"])
          end

          done.result
      end

    exp = c["expect"]
    assert result.turns == exp["turns"], "turns"
    assert result.text == exp["text"], "text"
    assert length(result.tool_calls) == length(exp["toolRuns"])

    {_, bodies} = Agent.get(agent, & &1)
    assert length(bodies) == exp["turns"]

    # Each pinned user message is the LAST message of the next request, in order.
    recs =
      bodies
      |> Enum.drop(1)
      |> Enum.map(&List.last(&1["messages"]))
      |> Enum.filter(fn m ->
        m["role"] == "user" and is_binary(m["content"]) and m["content"] in Map.values(messages)
      end)

    assert recs ==
             Enum.map(exp["recoveries"], &%{"role" => "user", "content" => messages[&1]})

    runs = collect_echo([])
    assert runs == exp["toolRuns"]
  end

  defp collect_echo(acc) do
    receive do
      {:echo, args} -> collect_echo(acc ++ [args])
    after
      0 -> acc
    end
  end

  defp stop("openai", s),
    do: %{"end" => "stop", "truncated" => "length", "tool" => "tool_calls"}[s]

  defp stop("anthropic", s),
    do: %{"end" => "end_turn", "truncated" => "max_tokens", "tool" => "tool_use"}[s]

  defp calls(r), do: Enum.with_index(r["toolCalls"] || [])

  defp object_or_raw(raw) do
    case Jason.decode(raw) do
      {:ok, m} when is_map(m) -> m
      _ -> raw
    end
  end

  defp json("openai", r) do
    msg = %{"role" => "assistant", "content" => r["text"]}
    msg = if r["thinking"], do: Map.put(msg, "reasoning_content", r["thinking"]), else: msg

    msg =
      case calls(r) do
        [] ->
          msg

        cs ->
          Map.put(
            msg,
            "tool_calls",
            Enum.map(cs, fn {tc, i} ->
              %{
                "id" => "c#{i}",
                "type" => "function",
                "function" => %{"name" => tc["name"], "arguments" => tc["arguments"]}
              }
            end)
          )
      end

    %{
      "choices" => [%{"message" => msg, "finish_reason" => stop("openai", r["stop"])}],
      "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
    }
  end

  defp json("anthropic", r) do
    thinking =
      if r["thinking"],
        do: [%{"type" => "thinking", "thinking" => r["thinking"], "signature" => "sig"}],
        else: []

    text = if r["text"] not in [nil, ""], do: [%{"type" => "text", "text" => r["text"]}], else: []

    uses =
      Enum.map(calls(r), fn {tc, i} ->
        %{
          "type" => "tool_use",
          "id" => "u#{i}",
          "name" => tc["name"],
          "input" => object_or_raw(tc["arguments"])
        }
      end)

    %{
      "content" => thinking ++ text ++ uses,
      "stop_reason" => stop("anthropic", r["stop"]),
      "usage" => %{"input_tokens" => 1, "output_tokens" => 1}
    }
  end

  defp data(m), do: "data: " <> Jason.encode!(m) <> "\n\n"

  defp sse("openai", r) do
    chunk = fn delta, finish ->
      data(%{"choices" => [%{"index" => 0, "delta" => delta, "finish_reason" => finish}]})
    end

    think = if r["thinking"], do: chunk.(%{"reasoning_content" => r["thinking"]}, nil), else: ""
    text = if r["text"] not in [nil, ""], do: chunk.(%{"content" => r["text"]}, nil), else: ""

    tcs =
      Enum.map_join(calls(r), fn {tc, i} ->
        chunk.(
          %{
            "tool_calls" => [
              %{
                "index" => i,
                "id" => "c#{i}",
                "function" => %{"name" => tc["name"], "arguments" => tc["arguments"]}
              }
            ]
          },
          nil
        )
      end)

    think <> text <> tcs <> chunk.(%{}, stop("openai", r["stop"])) <> "data: [DONE]\n\n"
  end

  defp sse("anthropic", r) do
    blocks =
      if(r["thinking"], do: [{:thinking, r["thinking"]}], else: []) ++
        if(r["text"] not in [nil, ""], do: [{:text, r["text"]}], else: []) ++
        Enum.map(calls(r), fn {tc, i} -> {:use, tc, i} end)

    body =
      blocks
      |> Enum.with_index()
      |> Enum.map_join(fn
        {{:thinking, t}, idx} ->
          data(%{
            "type" => "content_block_start",
            "index" => idx,
            "content_block" => %{"type" => "thinking", "thinking" => ""}
          }) <>
            data(%{
              "type" => "content_block_delta",
              "index" => idx,
              "delta" => %{"type" => "thinking_delta", "thinking" => t}
            }) <> data(%{"type" => "content_block_stop", "index" => idx})

        {{:text, t}, idx} ->
          data(%{
            "type" => "content_block_start",
            "index" => idx,
            "content_block" => %{"type" => "text", "text" => ""}
          }) <>
            data(%{
              "type" => "content_block_delta",
              "index" => idx,
              "delta" => %{"type" => "text_delta", "text" => t}
            }) <> data(%{"type" => "content_block_stop", "index" => idx})

        {{:use, tc, i}, idx} ->
          data(%{
            "type" => "content_block_start",
            "index" => idx,
            "content_block" => %{"type" => "tool_use", "id" => "u#{i}", "name" => tc["name"]}
          }) <>
            data(%{
              "type" => "content_block_delta",
              "index" => idx,
              "delta" => %{"type" => "input_json_delta", "partial_json" => tc["arguments"]}
            }) <> data(%{"type" => "content_block_stop", "index" => idx})
      end)

    data(%{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}}) <>
      body <>
      data(%{
        "type" => "message_delta",
        "delta" => %{"stop_reason" => stop("anthropic", r["stop"])},
        "usage" => %{"output_tokens" => 1}
      }) <> data(%{"type" => "message_stop"})
  end
end
