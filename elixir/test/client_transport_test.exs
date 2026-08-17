defmodule Toolnexus.ClientTransportTest do
  @moduledoc """
  §8 Gap 2 — the first-class `:transport` seam: the host's function makes the wire
  call; retries, Retry-After, deadline, and classification still run around it.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Client

  defp openai_text(text) do
    %{
      "choices" => [%{"message" => %{"role" => "assistant", "content" => text}}],
      "usage" => %{"prompt_tokens" => 3, "completion_tokens" => 2, "total_tokens" => 5}
    }
  end

  defp client(transport, opts \\ []) do
    Client.create(
      Keyword.merge(
        [base_url: "http://transport.invalid", style: "openai", model: "m", api_key: "k", transport: transport],
        opts
      )
    )
  end

  test "transport makes the wire call and sees the request shape" do
    me = self()

    transport = fn req ->
      send(me, {:req, req})
      {:ok, %{status: 200, headers: %{}, body: openai_text("via transport")}}
    end

    r = Client.run(client(transport), "hello", [])
    assert r.text == "via transport" and r.status == "done"
    assert r.usage.total_tokens == 5

    assert_receive {:req, req}
    assert req.method == :post
    assert req.url == "http://transport.invalid/chat/completions"
    assert req.headers["authorization"] == "Bearer k"
    assert req.body["model"] == "m" and is_list(req.body["messages"])
  end

  test "retries still wrap the transport: 500 then 200 succeeds; Retry-After honored" do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    transport = fn _req ->
      n = Agent.get_and_update(calls, &{&1, &1 + 1})

      if n == 0,
        do: {:ok, %{status: 500, headers: %{"retry-after" => ["1"]}, body: %{"error" => "boom"}}},
        else: {:ok, %{status: 200, headers: %{}, body: openai_text("recovered")}}
    end

    t0 = System.monotonic_time(:millisecond)
    r = Client.run(client(transport, retries: 2, retry_base_ms: 1), "go", [])
    assert r.text == "recovered"
    assert Agent.get(calls, & &1) == 2
    # Retry-After: 1s honored around the host transport
    assert System.monotonic_time(:millisecond) - t0 >= 1_000
  end

  test "transport {:error, e} is retried, then raised when retries are exhausted" do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    transport = fn _req ->
      Agent.update(calls, &(&1 + 1))
      {:error, %RuntimeError{message: "conn refused"}}
    end

    assert_raise RuntimeError, "conn refused", fn ->
      Client.run(client(transport, retries: 1, retry_base_ms: 1), "go", [])
    end

    # 1 initial + 1 retry
    assert Agent.get(calls, & &1) == 2
  end

  test "non-retryable status via transport surfaces as LLM error" do
    transport = fn _req -> {:ok, %{status: 401, headers: %{}, body: %{"error" => "bad key"}}} end
    assert_raise RuntimeError, ~r/LLM 401/, fn -> Client.run(client(transport), "go", []) end
  end

  test "streaming rides the transport too (SSE body as binary)" do
    sse =
      Enum.join(
        [
          ~s(data: {"choices":[{"delta":{"content":"hel"}}]}),
          ~s(data: {"choices":[{"delta":{"content":"lo"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}),
          "data: [DONE]"
        ],
        "\n\n"
      )

    transport = fn req ->
      assert req.body["stream"] == true
      {:ok, %{status: 200, headers: %{}, body: sse}}
    end

    events = Client.stream(client(transport), "hi", []) |> Enum.to_list()
    assert %{type: "done", result: %{text: "hello"}} = List.last(events)
  end
  # Retry-After is parsed identically in all seven ports: the delay-seconds form
  # only (RFC 9110 §10.2.3) — ASCII digits in 0..2_147_483_647. This port's parser
  # is private, so the rule is asserted where it is observable: how long the client
  # actually waits. Before this change `Integer.parse/1` read "5.9" as 5 and waited
  # five seconds where every other port backed off, and `n > 0` discarded a valid 0.
  defp retry_delay_ms(retry_after, retry_base_ms) do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    transport = fn _req ->
      n = Agent.get_and_update(calls, &{&1, &1 + 1})

      if n == 0,
        do: {:ok, %{status: 429, headers: %{"retry-after" => [retry_after]}, body: %{"error" => "slow down"}}},
        else: {:ok, %{status: 200, headers: %{}, body: openai_text("ok")}}
    end

    t0 = System.monotonic_time(:millisecond)
    r = Client.run(client(transport, retries: 2, retry_base_ms: retry_base_ms), "go", [])
    assert r.text == "ok"
    System.monotonic_time(:millisecond) - t0
  end

  test "a fractional Retry-After is ignored, not truncated to whole seconds" do
    # Old behaviour: Integer.parse("5.9") -> {5, ".9"} -> a five-second wait.
    assert retry_delay_ms("5.9", 5) < 1_000
  end

  test "Retry-After: 0 means retry now, not 'no opinion'" do
    # Old behaviour: the `n > 0` guard discarded 0, so backoff applied instead.
    assert retry_delay_ms("0", 600) < 400
  end

  test "an out-of-range Retry-After cannot stall the run" do
    assert retry_delay_ms("2147483648", 5) < 1_000
  end

  test "a negative Retry-After falls back to backoff, never an immediate retry" do
    assert retry_delay_ms("-5", 400) >= 300
  end

  test "an HTTP-date Retry-After falls back to backoff" do
    assert retry_delay_ms("Wed, 21 Oct 2015 07:28:00 GMT", 5) < 1_000
  end

end
