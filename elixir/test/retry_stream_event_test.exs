defmodule Toolnexus.RetryStreamEventTest do
  @moduledoc """
  §8 Resilience — `x-should-retry` override and the streaming-only `retry` event (#189).
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Client

  @openai_sse Enum.join(
                [
                  ~s(data: {"choices":[{"delta":{"content":"ok"}}]}),
                  "data: [DONE]"
                ],
                "\n\n"
              )

  @anthropic_sse """
  data: {"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}

  data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

  data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

  data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}
  """

  defp openai_text(text),
    do: %{"choices" => [%{"message" => %{"role" => "assistant", "content" => text}}]}

  # A transport that replays `responses` in order (the last one repeats), counting calls.
  defp scripted(responses) do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    t = fn _req ->
      n = Agent.get_and_update(calls, &{&1, &1 + 1})
      Enum.at(responses, n) || List.last(responses)
    end

    {t, fn -> Agent.get(calls, & &1) end}
  end

  defp client(transport, opts \\ []) do
    Client.create(
      Keyword.merge(
        [
          base_url: "http://retry.invalid",
          style: "openai",
          model: "m",
          api_key: "k",
          transport: transport,
          retries: 2,
          retry_base_ms: 1
        ],
        opts
      )
    )
  end

  for {style, sse} <- [{"openai", @openai_sse}, {"anthropic", @anthropic_sse}] do
    @style style
    @sse sse
    test "#{style}: 429 + Retry-After: 0 streams a retry event before the answer" do
      {t, _} =
        scripted([
          {:ok, %{status: 429, headers: %{"retry-after" => ["0"]}, body: %{"e" => 1}}},
          {:ok, %{status: 200, headers: %{}, body: @sse}}
        ])

      events = Client.stream(client(t, style: @style), "hi", []) |> Enum.to_list()

      assert [
               %{type: "retry", attempt: 1, max: 2, delay_ms: 0, status: 429, reason: "HTTP 429"}
               | rest
             ] = events

      types = Enum.map(rest, & &1.type)
      assert "text" in types
      assert List.last(types) == "done"
      assert List.last(events).result.text == "ok"
    end
  end

  test "a network error streams a retry event with reason network and no status" do
    {t, _} =
      scripted([
        {:error, %RuntimeError{message: "conn refused"}},
        {:ok, %{status: 200, headers: %{}, body: @openai_sse}}
      ])

    [ev | _] = Client.stream(client(t), "hi", []) |> Enum.to_list()
    assert %{type: "retry", attempt: 1, max: 2, reason: "network"} = ev
    refute Map.has_key?(ev, :status)
    assert is_integer(ev.delay_ms) and ev.delay_ms >= 1
  end

  test "a 400 without x-should-retry emits no retry event and surfaces the error" do
    {t, count} = scripted([{:ok, %{status: 400, headers: %{}, body: %{"e" => "bad"}}}])
    me = self()

    assert_raise Toolnexus.ProviderError, fn ->
      Client.stream(client(t), "hi", []) |> Enum.each(&send(me, {:ev, &1}))
    end

    refute_received {:ev, %{type: "retry"}}
    assert count.() == 1
  end

  test "run emits no retry event and is unchanged by a retried 429" do
    {t, count} =
      scripted([
        {:ok, %{status: 429, headers: %{"retry-after" => ["0"]}, body: %{}}},
        {:ok, %{status: 200, headers: %{}, body: openai_text("done")}}
      ])

    r = Client.run(client(t), "hi", [])
    assert r.text == "done" and r.status == "done"
    assert count.() == 2
  end

  test "x-should-retry: true makes a 400 retryable" do
    {t, count} =
      scripted([
        {:ok, %{status: 400, headers: %{"x-should-retry" => [" true "]}, body: %{}}},
        {:ok, %{status: 200, headers: %{}, body: openai_text("retried")}}
      ])

    assert Client.run(client(t), "hi", []).text == "retried"
    assert count.() == 2
  end

  test "X-Should-Retry: False makes a 503 terminal after a single attempt" do
    {t, count} =
      scripted([
        {:ok, %{status: 503, headers: %{"X-Should-Retry" => ["False"]}, body: %{}}},
        {:ok, %{status: 200, headers: %{}, body: openai_text("never")}}
      ])

    err = assert_raise Toolnexus.ProviderError, fn -> Client.run(client(t), "hi", []) end
    assert err.status == 503
    assert count.() == 1
  end

  test "an unrecognised x-should-retry value keeps the default classification" do
    {t, count} =
      scripted([{:ok, %{status: 400, headers: %{"x-should-retry" => ["maybe"]}, body: %{}}}])

    assert_raise Toolnexus.ProviderError, fn -> Client.run(client(t), "hi", []) end
    assert count.() == 1
  end

  test "on_error still has the final say and sees retryable: true" do
    me = self()

    {t, count} =
      scripted([
        {:ok, %{status: 400, headers: %{"x-should-retry" => ["true"]}, body: %{}}},
        {:ok, %{status: 200, headers: %{}, body: openai_text("never")}}
      ])

    on_error = fn info ->
      send(me, {:info, info})
      :fail
    end

    assert_raise Toolnexus.ProviderError, fn ->
      Client.run(client(t, on_error: on_error), "hi", [])
    end

    assert_received {:info, %{retryable: true, status: 400}}
    assert count.() == 1
  end
end
