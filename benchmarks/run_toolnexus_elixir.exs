# toolnexus (Elixir port) PERFORMANCE runner.
#
# Measures framework overhead (not model latency) against the shared, zero-cost
# mock LLM + the shared stdio MCP server, on the fixed benchmark scenario:
#   "What's the weather in Paris and what is 2+2?" -> 2 tool calls -> final text.
#
# It measures:
#   * cold init  = build toolkit (spawn MCP child + connect + tool discovery)
#   * per-request wall time for the fixed scenario, N runs (after warmup)
# and prints ONE aggregated JSON line matching the other runners' keys:
#   {"name","framework","language":"elixir","init_ms","tool_count",
#    "mean_ms","p50_ms","p95_ms","n","final_ok"}
#
# It is self-contained: it starts `python benchmarks/mock_llm.py --port <free>`
# itself, waits for health, runs, and tears the mock down. Nothing hits a real
# LLM; zero cost.
#
# Run from the elixir port dir so the compiled app + deps are on the path:
#   cd elixir && BENCH_REPO=<repo> mix run ../benchmarks/run_toolnexus_elixir.exs --config mcp
#   cd elixir && BENCH_REPO=<repo> mix run ../benchmarks/run_toolnexus_elixir.exs --config native
#
# Env: BENCH_REPO (repo root, required), MCP_PYTHON / PYTHON (python for the
# mock + MCP child; default "python3"), BENCH_RUNS (30), BENCH_WARMUP (5).

defmodule ElixirBench do
  alias Toolnexus.{Client, Toolkit}

  @repo System.get_env("BENCH_REPO") ||
          raise("BENCH_REPO env var is required (repo root)")
  @python System.get_env("MCP_PYTHON") || System.get_env("PYTHON") || "python3"
  @question "What's the weather in Paris and what is 2+2?"
  @expected_prefix "The weather in Paris"

  # ---- mock LLM lifecycle -------------------------------------------------

  def free_port do
    {:ok, s} = :gen_tcp.listen(0, [:binary, {:active, false}, {:ip, {127, 0, 0, 1}}])
    {:ok, p} = :inet.port(s)
    :gen_tcp.close(s)
    p
  end

  def start_mock(port) do
    exe = System.find_executable(@python) || raise("python not found: #{@python}")

    port_ref =
      Port.open({:spawn_executable, exe}, [
        :binary,
        :exit_status,
        args: ["#{@repo}/benchmarks/mock_llm.py", "--port", "#{port}"]
      ])

    os_pid =
      case Port.info(port_ref, :os_pid) do
        {:os_pid, pid} -> pid
        _ -> nil
      end

    wait_health("http://127.0.0.1:#{port}/health", 100)
    {port_ref, os_pid}
  end

  defp wait_health(_url, 0), do: raise("mock LLM did not become healthy")

  defp wait_health(url, tries) do
    case Req.get(url, retry: false, receive_timeout: 500, connect_options: [timeout: 500]) do
      {:ok, %{status: 200}} -> :ok
      _ -> Process.sleep(50); wait_health(url, tries - 1)
    end
  end

  def stop_mock({port_ref, os_pid}) do
    if os_pid, do: System.cmd("kill", ["-9", "#{os_pid}"], stderr_to_stdout: true)
    if is_port(port_ref) and Port.info(port_ref) != nil, do: Port.close(port_ref)
  rescue
    _ -> :ok
  end

  # ---- toolkit variants ---------------------------------------------------

  def mcp_config do
    %{
      "mcpServers" => %{
        "bench-tools" => %{
          "type" => "local",
          "command" => [@python, "#{@repo}/benchmarks/mcp_server.py"],
          "enabled" => true,
          "timeout" => 30_000
        }
      }
    }
  end

  # Native Elixir tools with the SAME names/behavior as the shared MCP server,
  # for the "(native)" variant (no MCP child; loop + tool overhead only).
  def native_tools do
    weather =
      Toolnexus.define_tool(
        name: "get_weather",
        description: "Get the current weather for a city.",
        input_schema: %{
          "type" => "object",
          "properties" => %{"city" => %{"type" => "string"}},
          "required" => ["city"]
        },
        execute: fn args -> "The weather in #{args["city"]} is Sunny, 22C." end
      )

    add =
      Toolnexus.define_tool(
        name: "add",
        description: "Add two integers.",
        input_schema: %{
          "type" => "object",
          "properties" => %{"a" => %{"type" => "integer"}, "b" => %{"type" => "integer"}},
          "required" => ["a", "b"]
        },
        execute: fn args -> "#{trunc(args["a"] + args["b"])}" end
      )

    [weather, add]
  end

  def build("native"), do: Toolnexus.create_toolkit!(extra_tools: native_tools(), builtins: false)
  def build(_mcp), do: Toolnexus.create_toolkit!(mcp_config: mcp_config(), builtins: false)

  # ---- aggregation (same method as run_all.py) ----------------------------

  def pct(sorted, p) do
    n = length(sorted)
    k = round(p / 100 * (n - 1)) |> max(0) |> min(n - 1)
    Enum.at(sorted, k)
  end

  def round3(x), do: Float.round(x * 1.0, 3)

  # ---- main ---------------------------------------------------------------

  def main(config) do
    runs = String.to_integer(System.get_env("BENCH_RUNS") || "30")
    warmup = String.to_integer(System.get_env("BENCH_WARMUP") || "5")

    port = free_port()
    mock = start_mock(port)
    mock_url = "http://127.0.0.1:#{port}"

    label = if config == "native", do: " (native)", else: ""

    try do
      # ---- cold init: toolkit build (MCP connect + discovery) + client ----
      t0 = System.monotonic_time(:microsecond)
      tk = build(config)

      client =
        Client.create(
          base_url: mock_url,
          style: "openai",
          model: "mock-model",
          api_key: "sk-mock-not-a-real-key"
        )

      init_ms = (System.monotonic_time(:microsecond) - t0) / 1000.0
      tool_count = tk |> Toolkit.tools() |> length()

      # ---- warmup ----
      for _ <- 1..warmup, do: Client.run(client, @question, tk)

      # ---- measured runs ----
      {latencies, final_text} =
        Enum.reduce(1..runs, {[], ""}, fn _, {acc, _} ->
          s = System.monotonic_time(:microsecond)
          res = Client.run(client, @question, tk)
          lat = (System.monotonic_time(:microsecond) - s) / 1000.0
          {[lat | acc], res.text}
        end)

      Toolkit.close(tk)

      sorted = Enum.sort(latencies)
      mean = Enum.sum(latencies) / length(latencies)

      line = %{
        "name" => "toolnexus-elixir-#{config}",
        "framework" => "toolnexus-elixir-#{config}#{label}",
        "language" => "elixir",
        "init_ms" => Float.round(init_ms, 1),
        "tool_count" => tool_count,
        "mean_ms" => round3(mean),
        "p50_ms" => round3(pct(sorted, 50)),
        "p95_ms" => round3(pct(sorted, 95)),
        "n" => length(latencies),
        "final_ok" => is_binary(final_text) and String.starts_with?(final_text, @expected_prefix)
      }

      IO.puts(Jason.encode!(line))
    after
      stop_mock(mock)
    end
  end
end

config =
  case System.argv() do
    ["--config", c | _] -> c
    [c | _] when c not in [] -> c
    _ -> "mcp"
  end

ElixirBench.main(config)
