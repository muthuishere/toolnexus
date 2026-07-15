# Elixir LangChain (brainlid/langchain) COMPETITOR runner.
#
# Same mock LLM + same fixed scenario as the toolnexus Elixir runner:
#   "What's the weather in Paris and what is 2+2?" -> 2 tool calls -> final text.
#
# LangChain Elixir has no MCP client, so this uses NATIVE `LangChain.Function`
# tools (get_weather, add) with identical names/behavior — the same shape as
# the toolnexus "native" variant, so it's a fair overhead comparison.
#
# Prints ONE aggregated JSON line with the shared keys:
#   {"name","framework","language":"elixir","init_ms","tool_count",
#    "mean_ms","p50_ms","p95_ms","n","final_ok"}
#
# Run from this dir:
#   BENCH_REPO=<repo> mix run run.exs
# Env: BENCH_REPO (required), MCP_PYTHON/PYTHON (mock launcher; default python3),
#      BENCH_RUNS (30), BENCH_WARMUP (5).

Logger.configure(level: :warning)

alias LangChain.Chains.LLMChain
alias LangChain.ChatModels.ChatOpenAI
alias LangChain.Function
alias LangChain.Message
alias LangChain.Message.ContentPart

repo = System.get_env("BENCH_REPO") || raise("BENCH_REPO env var is required")
python = System.get_env("MCP_PYTHON") || System.get_env("PYTHON") || "python3"
runs = String.to_integer(System.get_env("BENCH_RUNS") || "30")
warmup = String.to_integer(System.get_env("BENCH_WARMUP") || "5")
question = "What's the weather in Paris and what is 2+2?"
expected_prefix = "The weather in Paris"

# ---- mock LLM lifecycle (self-contained, zero cost) ----------------------
free_port = fn ->
  {:ok, s} = :gen_tcp.listen(0, [:binary, {:active, false}, {:ip, {127, 0, 0, 1}}])
  {:ok, p} = :inet.port(s)
  :gen_tcp.close(s)
  p
end

wait_health = fn url ->
  Enum.reduce_while(1..100, :error, fn _, _ ->
    case Req.get(url, retry: false, receive_timeout: 500) do
      {:ok, %{status: 200}} -> {:halt, :ok}
      _ -> Process.sleep(50); {:cont, :error}
    end
  end)
end

port = free_port.()
exe = System.find_executable(python) || raise("python not found: #{python}")

mock =
  Port.open({:spawn_executable, exe}, [
    :binary,
    :exit_status,
    args: ["#{repo}/benchmarks/mock_llm.py", "--port", "#{port}"]
  ])

os_pid =
  case Port.info(mock, :os_pid) do
    {:os_pid, pid} -> pid
    _ -> nil
  end

:ok = wait_health.("http://127.0.0.1:#{port}/health")
endpoint = "http://127.0.0.1:#{port}/chat/completions"

# ---- native tools (same names/behavior as the shared MCP server) ---------
weather =
  Function.new!(%{
    name: "get_weather",
    description: "Get the current weather for a city.",
    parameters_schema: %{
      "type" => "object",
      "properties" => %{"city" => %{"type" => "string"}},
      "required" => ["city"]
    },
    function: fn args, _ctx -> {:ok, "The weather in #{args["city"]} is Sunny, 22C."} end
  })

add =
  Function.new!(%{
    name: "add",
    description: "Add two integers.",
    parameters_schema: %{
      "type" => "object",
      "properties" => %{"a" => %{"type" => "integer"}, "b" => %{"type" => "integer"}},
      "required" => ["a", "b"]
    },
    function: fn args, _ctx -> {:ok, "#{trunc(args["a"] + args["b"])}"} end
  })

tools = [weather, add]

run_once = fn ->
  llm =
    ChatOpenAI.new!(%{
      endpoint: endpoint,
      model: "mock-model",
      api_key: "sk-mock-not-a-real-key",
      stream: false
    })

  {:ok, chain} =
    %{llm: llm, verbose: false}
    |> LLMChain.new!()
    |> LLMChain.add_tools(tools)
    |> LLMChain.add_message(Message.new_user!(question))
    |> LLMChain.run(mode: :while_needs_response)

  ContentPart.content_to_string(chain.last_message.content) || ""
end

pct = fn sorted, p ->
  n = length(sorted)
  k = round(p / 100 * (n - 1)) |> max(0) |> min(n - 1)
  Enum.at(sorted, k)
end

try do
  # ---- cold init: first full chain build + run (LangChain has no persistent
  # toolkit/connection to build, so init = constructing the LLM + chain). ----
  t0 = System.monotonic_time(:microsecond)
  _ = ChatOpenAI.new!(%{endpoint: endpoint, model: "mock-model", api_key: "x", stream: false})
  init_ms = (System.monotonic_time(:microsecond) - t0) / 1000.0

  for _ <- 1..warmup, do: run_once.()

  {latencies, final_text} =
    Enum.reduce(1..runs, {[], ""}, fn _, {acc, _} ->
      s = System.monotonic_time(:microsecond)
      text = run_once.()
      lat = (System.monotonic_time(:microsecond) - s) / 1000.0
      {[lat | acc], text}
    end)

  sorted = Enum.sort(latencies)
  mean = Enum.sum(latencies) / length(latencies)

  line = %{
    "name" => "langchain-elixir",
    "framework" => "langchain-elixir (native)",
    "language" => "elixir",
    "init_ms" => Float.round(init_ms, 1),
    "tool_count" => length(tools),
    "mean_ms" => Float.round(mean, 3),
    "p50_ms" => Float.round(pct.(sorted, 50) * 1.0, 3),
    "p95_ms" => Float.round(pct.(sorted, 95) * 1.0, 3),
    "n" => length(latencies),
    "final_ok" => is_binary(final_text) and String.starts_with?(final_text, expected_prefix)
  }

  IO.puts(Jason.encode!(line))
after
  if os_pid, do: System.cmd("kill", ["-9", "#{os_pid}"], stderr_to_stdout: true)
  if Port.info(mock) != nil, do: Port.close(mock)
end
