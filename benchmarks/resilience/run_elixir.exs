# toolnexus (Elixir port) RESILIENCE runner.
#
# Runs the eight adverse-condition scenarios (R1-R8) against the shared
# resilience mock LLM + local MCP stubs, printing ONE JSON line per scenario:
#   {"lang","scenario","outcome","detail","elapsed_ms"}
# outcome ∈ graceful | crash | hang | wrong. Run with `mix run` from the elixir
# port dir so the compiled app + deps are on the path. Nothing calls a real LLM.
#
# Env: BENCH_REPO, MCP_PYTHON, MOCK_BASE, MCP_HANG.

defmodule ResRunner do
  alias Toolnexus.{Client, Toolkit}

  @mcp_py System.get_env("MCP_PYTHON")
  @mock_base System.get_env("MOCK_BASE", "http://127.0.0.1:8901")
  @mcp_hang System.get_env("MCP_HANG")
  @good "#{System.get_env("BENCH_REPO")}/benchmarks/mcp_server.py"
  @hard_cap_ms 20_000

  def good_cfg,
    do: %{"type" => "local", "command" => [@mcp_py, @good], "enabled" => true, "timeout" => 30000}

  def closed_port do
    {:ok, s} = :gen_tcp.listen(0, [:binary, {:active, false}, {:ip, {127, 0, 0, 1}}])
    {:ok, p} = :inet.port(s)
    :gen_tcp.close(s)
    p
  end

  def boom_tool do
    Toolnexus.define_tool(
      name: "boom",
      description: "A tool that always raises.",
      input_schema: %{"type" => "object", "properties" => %{}},
      execute: fn _args -> raise "boom: native tool failed on purpose" end
    )
  end

  def tool_names(tk), do: tk |> Toolkit.tools() |> Enum.map(& &1.name) |> Enum.sort()

  # ---- scenarios: each returns {outcome, detail} ----

  def r1 do
    cfg = %{
      "mcpServers" => %{
        "good" => good_cfg(),
        "bad" => %{"type" => "local", "command" => ["/nonexistent/tn-badcmd-xyz"], "enabled" => true, "timeout" => 2000}
      }
    }

    case Toolnexus.create_toolkit(mcp_config: cfg, builtins: false) do
      {:ok, tk} ->
        st = Toolkit.mcp_status(tk)
        names = tool_names(tk)
        ok = st["bad"] == "failed" and st["good"] == "connected" and length(names) > 0
        {gw(ok), "status=#{inspect(st)} tools=#{inspect(names)}"}

      {:error, e} ->
        {"crash", "create_toolkit errored: #{Exception.message(e)}"}
    end
  end

  def r2 do
    port = closed_port()

    cfg = %{
      "mcpServers" => %{
        "good" => good_cfg(),
        "remote" => %{"type" => "remote", "url" => "http://127.0.0.1:#{port}/mcp", "enabled" => true, "timeout" => 2000}
      }
    }

    case Toolnexus.create_toolkit(mcp_config: cfg, builtins: false) do
      {:ok, tk} ->
        st = Toolkit.mcp_status(tk)
        names = tool_names(tk)
        ok = st["remote"] == "failed" and st["good"] == "connected" and length(names) > 0
        {gw(ok), "status=#{inspect(st)} tools=#{inspect(names)}"}

      {:error, e} ->
        {"crash", "create_toolkit errored: #{Exception.message(e)}"}
    end
  end

  def r3 do
    cfg = %{
      "mcpServers" => %{
        "good" => good_cfg(),
        "hang" => %{"type" => "local", "command" => [@mcp_py, @mcp_hang], "enabled" => true, "timeout" => 2000}
      }
    }

    case Toolnexus.create_toolkit(mcp_config: cfg, builtins: false) do
      {:ok, tk} ->
        st = Toolkit.mcp_status(tk)
        names = tool_names(tk)
        ok = st["hang"] == "failed" and st["good"] == "connected" and length(names) > 0
        {gw(ok), "status=#{inspect(st)} tools=#{inspect(names)}"}

      {:error, e} ->
        {"crash", "create_toolkit errored: #{Exception.message(e)}"}
    end
  end

  def r4 do
    path = Path.join(System.tmp_dir!(), "tn-bad-#{System.unique_integer([:positive])}.json")
    File.write!(path, ~s({ "mcpServers": { "x": { "type": "local", ))

    try do
      case Toolnexus.create_toolkit(mcp_config: path, builtins: false) do
        {:ok, tk} ->
          Toolkit.close(tk)
          {"wrong", "malformed config did not error"}

        {:error, e} ->
          {"graceful", "returned {:error, ...}: #{trunc(Exception.message(e), 80)}"}
      end
    rescue
      e -> {"graceful", "raised #{inspect(e.__struct__)}: #{trunc(Exception.message(e), 80)}"}
    after
      File.rm(path)
    end
  end

  def run_llm(suffix, retries, tools) do
    {:ok, tk} = Toolnexus.create_toolkit(extra_tools: tools, builtins: false)

    client =
      Client.create(
        base_url: "#{@mock_base}/#{suffix}",
        style: "openai",
        model: "mock-model",
        api_key: "sk-mock-not-real",
        retries: retries,
        retry_base_ms: 1
      )

    Client.run(client, "hello", tk)
  end

  def r5 do
    try do
      r = run_llm("e402", 2, [])
      {"wrong", "402 did not surface; text=#{inspect(r.text)}"}
    rescue
      e -> {"graceful", "surfaced #{inspect(e.__struct__)}: #{trunc(Exception.message(e), 80)}"}
    end
  end

  def r6 do
    suffix = "retry/#{System.unique_integer([:positive])}"

    try do
      r = run_llm(suffix, 3, [])

      if r.text && String.contains?(r.text, "RESILIENCE_OK") do
        {"graceful", "retried then succeeded: text=#{inspect(r.text)}"}
      else
        {"wrong", "unexpected final text=#{inspect(r.text)}"}
      end
    rescue
      e -> {"crash", "did not recover from 429: #{trunc(Exception.message(e), 80)}"}
    end
  end

  def r7 do
    try do
      r = run_llm("e500", 2, [])
      {"wrong", "500 did not surface; text=#{inspect(r.text)}"}
    rescue
      e -> {"graceful", "surfaced after retries #{inspect(e.__struct__)}: #{trunc(Exception.message(e), 80)}"}
    end
  end

  def r8 do
    try do
      r = run_llm("boom", 2, [boom_tool()])
      boom_errs = Enum.filter(r.tool_calls, &(&1.name == "boom" and &1.is_error))

      if r.text && String.contains?(r.text, "RESILIENCE_OK") and boom_errs != [] do
        {"graceful", "tool error fed back, loop reached final: text=#{inspect(r.text)}"}
      else
        {"wrong", "text=#{inspect(r.text)} boom_errors=#{length(boom_errs)}"}
      end
    rescue
      e -> {"crash", "tool error not fed back: #{trunc(Exception.message(e), 80)}"}
    end
  end

  def gw(true), do: "graceful"
  def gw(false), do: "wrong"

  def trunc(s, n) when byte_size(s) > n, do: binary_part(s, 0, n)
  def trunc(s, _n), do: s

  def scenarios,
    do: [
      {"R1", &r1/0},
      {"R2", &r2/0},
      {"R3", &r3/0},
      {"R4", &r4/0},
      {"R5", &r5/0},
      {"R6", &r6/0},
      {"R7", &r7/0},
      {"R8", &r8/0}
    ]

  def run_one(key, fn_) do
    t0 = System.monotonic_time(:millisecond)
    task = Task.async(fn_)

    {outcome, detail} =
      case Task.yield(task, @hard_cap_ms) || Task.shutdown(task, :brutal_kill) do
        {:ok, {o, d}} -> {o, d}
        nil -> {"hang", "exceeded #{@hard_cap_ms}ms hard cap"}
        {:exit, reason} -> {"crash", "task exited: #{inspect(reason)}"}
      end

    elapsed = System.monotonic_time(:millisecond) - t0

    %{
      "lang" => "elixir",
      "scenario" => key,
      "outcome" => outcome,
      "detail" => detail,
      "elapsed_ms" => elapsed / 1
    }
  end

  def main do
    only = System.get_env("ONLY_SCENARIO")

    for {key, fn_} <- scenarios(), only in [nil, "", key] do
      line = run_one(key, fn_)
      IO.puts(Jason.encode!(line))
    end
  end
end

ResRunner.main()
