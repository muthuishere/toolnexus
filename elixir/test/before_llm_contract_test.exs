defmodule Toolnexus.BeforeLlmContractTest do
  @moduledoc """
  SPEC §8 `beforeLLM` contract (change `add-judge-batteries`), across EVERY entry point:
  run (openai + anthropic), stream (openai + anthropic), translate (both styles), the
  §7D loop and the one-shot agent run.

    * a failing hook (raise or `{:error, _}`) stops the call: the error propagates and
      NO provider request is sent;
    * a `model` override is transmitted for that turn only; absent => configured;
    * the model REPORTED is the model transmitted: `llm` events per turn, `run` event
      + `RunResult.model` = the last model call's model (incl. pending/failed runs),
      `translate` `result.model`.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.{Agents, Client, Request, Tool, ToolResult}

  @styles ["openai", "anthropic"]

  # ---- a recording transport: turn 0 calls `deploy`, later turns answer text ----

  defp sse(lines), do: Enum.map_join(lines, "", &("data: " <> Jason.encode!(&1) <> "\n\n"))

  defp reply("openai", 0, false),
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
                "function" => %{"name" => "deploy", "arguments" => "{}"}
              }
            ]
          }
        }
      ]
    }

  defp reply("openai", _, false),
    do: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => "done"}}]}

  defp reply("anthropic", 0, false),
    do: %{
      "content" => [%{"type" => "tool_use", "id" => "c1", "name" => "deploy", "input" => %{}}]
    }

  defp reply("anthropic", _, false), do: %{"content" => [%{"type" => "text", "text" => "done"}]}

  defp reply("openai", 0, true),
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
                    "function" => %{"name" => "deploy", "arguments" => "{}"}
                  }
                ]
              }
            }
          ]
        }
      ]) <> "data: [DONE]\n\n"

  defp reply("openai", _, true),
    do: sse([%{"choices" => [%{"delta" => %{"content" => "done"}}]}]) <> "data: [DONE]\n\n"

  defp reply("anthropic", 0, true),
    do:
      sse([
        %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1}}},
        %{
          "type" => "content_block_start",
          "index" => 0,
          "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "deploy"}
        },
        %{
          "type" => "content_block_delta",
          "index" => 0,
          "delta" => %{"type" => "input_json_delta", "partial_json" => "{}"}
        },
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}}
      ])

  defp reply("anthropic", _, true),
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

  defp recorder(style, opts \\ []) do
    {:ok, agent} = Agent.start_link(fn -> [] end)
    fail = opts[:fail]

    transport = fn req ->
      n = Agent.get_and_update(agent, fn b -> {length(b), b ++ [req.body]} end)

      if fail do
        {:ok, %{status: 400, headers: %{}, body: %{"error" => "bad"}}}
      else
        {:ok, %{status: 200, headers: %{}, body: reply(style, n, req.body["stream"] == true)}}
      end
    end

    {agent, transport}
  end

  defp bodies(agent), do: Agent.get(agent, & &1)

  defp client(style, transport, extra) do
    me = self()

    Client.create(
      [
        base_url: "http://x/v1",
        style: style,
        model: "configured",
        api_key: "k",
        transport: transport,
        max_turns: 4,
        retries: 0,
        retry_base_ms: 1,
        on_metric: fn ev -> send(me, {:metric, ev}) end
      ] ++ extra
    )
  end

  defp deploy(result \\ ToolResult.ok("D")) do
    %Tool{
      name: "deploy",
      description: "deploy",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _, _ -> result end
    }
  end

  defp metrics(kind) do
    receive_all([])
    |> Enum.filter(&(&1.event == kind))
    |> Enum.map(& &1.model)
  end

  defp receive_all(acc) do
    receive do
      {:metric, ev} -> receive_all(acc ++ [ev])
    after
      0 -> acc
    end
  end

  defp do_run(c, :run), do: Client.run(c, "go", [deploy()])

  defp do_run(c, :stream) do
    events = Client.stream(c, "go", [deploy()]) |> Enum.to_list()
    %{type: "done", result: r} = List.last(events)
    r
  end

  # ---- 1. a failing before_llm stops the call, zero requests --------------

  defp failure(:raise), do: {RuntimeError, fn _ -> raise "hook boom" end}
  defp failure(:error), do: {Toolnexus.HookError, fn _ -> {:error, "hook boom"} end}

  for style <- @styles, mode <- [:run, :stream, :translate], how <- [:raise, :error] do
    @style style
    @mode mode
    @how how
    test "failing before_llm (#{how}) stops #{mode} (#{style}) with no request" do
      {exc, hook} = failure(@how)
      {agent, transport} = recorder(@style)
      c = client(@style, transport, hooks: %{before_llm: hook})

      assert_raise exc, ~r/hook boom/, fn ->
        case @mode do
          :translate -> Client.translate(c, [%{"role" => "user", "content" => "hi"}])
          m -> do_run(c, m)
        end
      end

      assert bodies(agent) == []
    end
  end

  for how <- [:raise, :error] do
    @how how
    test "failing before_llm (#{how}) stops the §7D loop run with no request" do
      {exc, hook} = failure(@how)
      {agent, transport} = recorder("openai")

      loop =
        Agents.loop(
          Agents.agent("m", does: "x"),
          %{
            base_url: "http://x.invalid",
            style: "openai",
            model: "configured",
            api_key: "k",
            transport: transport,
            hooks: %{before_llm: hook}
          },
          nil
        )

      assert_raise exc, ~r/hook boom/, fn -> Agents.Loop.run(loop, "go") end
      assert bodies(agent) == []
    end

    test "failing before_llm (#{how}) fails the one-shot agent run with no request" do
      {_exc, hook} = failure(@how)
      {agent, transport} = recorder("openai")
      a = Agents.agent("m", does: "x", hooks: %{before_llm: hook})

      r = Agents.run(a, [transport: transport], "go")
      # fixture examples/agent-hooks H7: the handle boundary resolves an error result, never raises
      assert r.is_error and r.status == "error" and r.text =~ "hook boom"
      assert bodies(agent) == []
    end
  end

  # ---- 4/5. model override transmitted + reported, every loop -------------

  for style <- @styles, mode <- [:run, :stream] do
    @style style
    @mode mode
    test "override on the last turn is transmitted and reported (#{mode}, #{style})" do
      {agent, transport} = recorder(@style)
      hooks = %{before_llm: fn ev -> if ev.turn == 1, do: %{model: "last-model"} end}
      r = do_run(client(@style, transport, hooks: hooks), @mode)

      assert r.status == "done"
      assert Enum.map(bodies(agent), & &1["model"]) == ["configured", "last-model"]
      assert r.model == "last-model"
      evs = receive_all([])
      assert for(e <- evs, e.event == "llm", do: e.model) == ["configured", "last-model"]
      assert for(e <- evs, e.event == "run", do: e.model) == ["last-model"]
    end

    test "override on the first turn only: last call reports configured (#{mode}, #{style})" do
      {agent, transport} = recorder(@style)
      hooks = %{before_llm: fn ev -> if ev.turn == 0, do: %{model: "small-fast"} end}
      r = do_run(client(@style, transport, hooks: hooks), @mode)

      assert Enum.map(bodies(agent), & &1["model"]) == ["small-fast", "configured"]
      assert r.model == "configured"
      evs = receive_all([])
      assert for(e <- evs, e.event == "llm", do: e.model) == ["small-fast", "configured"]
      assert for(e <- evs, e.event == "run", do: e.model) == ["configured"]
    end

    test "no override: configured model transmitted and reported (#{mode}, #{style})" do
      {agent, transport} = recorder(@style)
      hooks = %{before_llm: fn _ -> %{} end}
      r = do_run(client(@style, transport, hooks: hooks), @mode)

      assert Enum.map(bodies(agent), & &1["model"]) == ["configured", "configured"]
      assert r.model == "configured"
      assert metrics("llm") == ["configured", "configured"]
    end

    test "pending run reports the last call's override (#{mode}, #{style})" do
      {_agent, transport} = recorder(@style)
      req = %Request{id: "r1", kind: "input", prompt: "which env?"}
      tool = deploy(%ToolResult{output: "", metadata: %{pending: req}})
      c = client(@style, transport, hooks: %{before_llm: fn _ -> %{model: "small-fast"} end})

      r =
        case @mode do
          :run ->
            Client.run(c, "go", [tool])

          :stream ->
            Client.stream(c, "go", [tool])
            |> Enum.to_list()
            |> Enum.find_value(fn
              %{result: %{status: "pending"} = r} -> r
              _ -> nil
            end)
        end

      assert r.status == "pending"
      assert r.model == "small-fast"
      assert metrics("run") == ["small-fast"]
    end

    test "failed run reports the override in its run + llm events (#{mode}, #{style})" do
      {_agent, transport} = recorder(@style, fail: true)
      c = client(@style, transport, hooks: %{before_llm: fn _ -> %{model: "small-fast"} end})

      assert_raise Toolnexus.ProviderError, fn -> do_run(c, @mode) end
      evs = receive_all([])
      assert for(e <- evs, e.event == "llm", do: e.model) == ["small-fast"]
      assert for(e <- evs, e.event == "run", do: e.model) == ["small-fast"]
    end
  end

  for style <- @styles do
    @style style
    test "translate result.model + llm event carry the transmitted model (#{style})" do
      {agent, transport} = recorder(@style)
      c = client(@style, transport, hooks: %{before_llm: fn _ -> %{model: "small-fast"} end})
      r = Client.translate(c, [%{"role" => "user", "content" => "hi"}])
      assert [%{"model" => "small-fast"}] = bodies(agent)
      assert r.model == "small-fast"
      assert metrics("llm") == ["small-fast"]
    end

    test "translate without override keeps the configured model (#{style})" do
      {agent, transport} = recorder(@style)

      r =
        Client.translate(client(@style, transport, []), [%{"role" => "user", "content" => "hi"}])

      assert [%{"model" => "configured"}] = bodies(agent)
      assert r.model == "configured"
      assert metrics("llm") == ["configured"]
    end
  end
end
