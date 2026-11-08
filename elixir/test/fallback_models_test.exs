defmodule Toolnexus.FallbackModelsTest do
  @moduledoc """
  §8 Resilience — `:fallback_models`: advance to the next model only when the retry
  budget is exhausted on HTTP 503/529; never on 429, an `:on_error` `:fail`, or when the
  `TOOLNEXUS_DISABLE_MODEL_FALLBACK` kill switch is set.
  """
  # async: false — the kill-switch test mutates the process environment.
  use ExUnit.Case, async: false

  alias Toolnexus.Client

  @env "TOOLNEXUS_DISABLE_MODEL_FALLBACK"

  setup do
    System.delete_env(@env)
    on_exit(fn -> System.delete_env(@env) end)
  end

  defp openai_text(text) do
    %{
      "choices" => [%{"message" => %{"role" => "assistant", "content" => text}}],
      "usage" => %{"prompt_tokens" => 3, "completion_tokens" => 2, "total_tokens" => 5}
    }
  end

  # Fails every request for `bad_model` with `status`; any other model succeeds.
  defp transport(bad_model, status, body_fun \\ &openai_text/1) do
    {:ok, seen} = Agent.start_link(fn -> [] end)

    fun = fn req ->
      Agent.update(seen, &(&1 ++ [req.body["model"]]))

      if req.body["model"] == bad_model,
        do: {:ok, %{status: status, headers: %{}, body: %{"error" => "overloaded"}}},
        else: {:ok, %{status: 200, headers: %{}, body: body_fun.("served")}}
    end

    {fun, seen}
  end

  defp client(transport, opts) do
    Client.create(
      Keyword.merge(
        [
          base_url: "http://fallback.invalid",
          style: "openai",
          model: "m1",
          api_key: "k",
          retries: 0,
          retry_base_ms: 1,
          transport: transport
        ],
        opts
      )
    )
  end

  test "529 on m1 with retries 0 falls back to m2; m2 is reported everywhere" do
    {t, seen} = transport("m1", 529)
    me = self()

    c =
      client(t,
        fallback_models: ["m2"],
        on_metric: fn ev -> send(me, {:metric, ev}) end,
        hooks: %{after_llm: fn ev -> send(me, {:after, ev.model}) end}
      )

    r = Client.run(c, "go", [])
    assert r.text == "served"
    assert r.model == "m2"
    assert Agent.get(seen, & &1) == ["m1", "m2"]
    assert_receive {:after, "m2"}
    assert_receive {:metric, %{event: "llm", model: "m2", status: "ok"}}
    assert_receive {:metric, %{event: "run", model: "m2"}}
  end

  test "503 spends the retry budget on m1 first, then a fresh budget on m2" do
    {t, seen} = transport("m1", 503)
    r = Client.run(client(t, retries: 1, fallback_models: ["", "m1", "m2"]), "go", [])
    assert r.model == "m2"
    assert Agent.get(seen, & &1) == ["m1", "m1", "m2"]
  end

  test "exhausted chain surfaces the last error" do
    {:ok, seen} = Agent.start_link(fn -> [] end)

    t = fn req ->
      Agent.update(seen, &(&1 ++ [req.body["model"]]))
      {:ok, %{status: 529, headers: %{}, body: %{"error" => "overloaded"}}}
    end

    assert_raise Toolnexus.ProviderError, fn ->
      Client.run(client(t, fallback_models: ["m2"]), "go", [])
    end

    assert Agent.get(seen, & &1) == ["m1", "m2"]
  end

  test "429 never switches models" do
    {t, seen} = transport("m1", 429)

    assert_raise Toolnexus.ProviderError, fn ->
      Client.run(client(t, fallback_models: ["m2"]), "go", [])
    end

    assert Agent.get(seen, & &1) == ["m1"]
  end

  test "on_error :fail on 503 does not switch" do
    {t, seen} = transport("m1", 503)

    assert_raise Toolnexus.ProviderError, fn ->
      Client.run(
        client(t, retries: 2, fallback_models: ["m2"], on_error: fn _ -> :fail end),
        "go",
        []
      )
    end

    assert Agent.get(seen, & &1) == ["m1"]
  end

  test "kill switch env disables the chain (1 / TRUE)" do
    for v <- ["1", "TRUE"] do
      System.put_env(@env, v)
      {t, seen} = transport("m1", 529)

      assert_raise Toolnexus.ProviderError, fn ->
        Client.run(client(t, fallback_models: ["m2"]), "go", [])
      end

      assert Agent.get(seen, & &1) == ["m1"]
    end

    System.put_env(@env, "no")
    {t, _} = transport("m1", 529)
    assert Client.run(client(t, fallback_models: ["m2"]), "go", []).model == "m2"
  end

  test "absent option is unchanged: 529 surfaces after the budget" do
    {t, seen} = transport("m1", 529)

    assert_raise Toolnexus.ProviderError, fn -> Client.run(client(t, []), "go", []) end
    assert Agent.get(seen, & &1) == ["m1"]
  end

  test "stream emits a fallback event before any text" do
    sse = fn _ ->
      Enum.join(
        [
          ~s(data: {"choices":[{"delta":{"content":"hi"}}]}),
          ~s(data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}),
          "data: [DONE]"
        ],
        "\n\n"
      )
    end

    {t, seen} = transport("m1", 529, sse)
    events = Client.stream(client(t, fallback_models: ["m2"]), "hi", []) |> Enum.to_list()

    types = Enum.map(events, & &1.type)
    fb = Enum.find_index(types, &(&1 == "fallback"))
    txt = Enum.find_index(types, &(&1 == "text"))
    assert fb != nil and txt != nil and fb < txt
    assert Enum.at(events, fb) == %{type: "fallback", from: "m1", to: "m2"}
    assert %{type: "done", result: %{model: "m2", text: "hi"}} = List.last(events)
    assert Agent.get(seen, & &1) == ["m1", "m2"]
  end

  test "anthropic style falls back too" do
    {:ok, seen} = Agent.start_link(fn -> [] end)

    t = fn req ->
      Agent.update(seen, &(&1 ++ [req.body["model"]]))

      if req.body["model"] == "m1",
        do: {:ok, %{status: 529, headers: %{}, body: %{}}},
        else:
          {:ok,
           %{
             status: 200,
             headers: %{},
             body: %{
               "content" => [%{"type" => "text", "text" => "ok"}],
               "stop_reason" => "end_turn",
               "usage" => %{"input_tokens" => 1, "output_tokens" => 1}
             }
           }}
    end

    r = Client.run(client(t, style: "anthropic", fallback_models: ["m2"]), "go", [])
    assert r.model == "m2" and r.text == "ok"
    assert Agent.get(seen, & &1) == ["m1", "m2"]
  end
end
