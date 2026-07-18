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
end
