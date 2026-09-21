
# Gate item 1, Elixir: does the keyword-list option (elixir/lib/toolnexus/client.ex
# defstruct :retries default 2 at :278, merged with `client.retries || 2` at :356)
# distinguish "unset" from "explicit 0"?  Elixir's `||` treats only nil/false as
# falsy -- 0 is truthy -- so if :retries is *present* in opts (even as 0), struct!/2
# stores 0 and `0 || 2` keeps 0. Only a MISSING :retries key falls back through the
# defstruct default of 2. Prove it against a real client with an injected :transport
# that always fails, counting invocations.
#
# Run with: mix run elixir_check.exs   (from elixir/, see driver script)

{:ok, agent} = Agent.start_link(fn -> 0 end)

failing_transport = fn _request ->
  Agent.update(agent, &(&1 + 1))
  {:ok, %{status: 500, headers: %{}, body: %{"error" => "boom"}}}
end

toolkit = Toolnexus.Toolkit.build([])

client0 =
  Toolnexus.Client.create(
    base_url: "http://example.invalid",
    style: "openai",
    model: "test-model",
    api_key: "x",
    retries: 0,
    transport: failing_transport
  )

try do
  Toolnexus.Client.run(client0, "hi", toolkit)
rescue
  _ -> :expected
end

c0 = Agent.get(agent, & &1)
IO.puts("calls with retries: 0 -> #{c0}")

if c0 != 1 do
  IO.puts("FAIL: expected 1 call, got #{c0}")
  System.halt(1)
end

Agent.update(agent, fn _ -> 0 end)

client1 =
  Toolnexus.Client.create(
    base_url: "http://example.invalid",
    style: "openai",
    model: "test-model",
    api_key: "x",
    # no :retries key at all
    transport: failing_transport
  )

try do
  Toolnexus.Client.run(client1, "hi", toolkit)
rescue
  _ -> :expected
end

c1 = Agent.get(agent, & &1)
IO.puts("calls with retries UNSET -> #{c1}")

if c1 != 3 do
  IO.puts("FAIL: expected 3 calls, got #{c1}")
  System.halt(1)
end

IO.puts(
  "ELIXIR VERDICT: retries: 0 != unset. Keyword-list presence + truthy-0 `||` already distinguishes them. No -1 sentinel needed."
)
