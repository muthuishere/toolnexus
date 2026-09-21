defmodule Toolnexus.Agents.InProcessTest do
  @moduledoc """
  ADR 0030 / issue #95: `Toolnexus.Agents.Runtime`'s `:in_process` option, the
  semantic counterpart to `:transport`, so a host whose model is an Elixir function
  doesn't have to hand-build a `:transport` to reach the sub-agent runtime. Ported
  from the Go reference (`golang/agents/inprocess_test.go`) and the spike at
  `spikes/inprocess-subagent/SPIKE.md`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Client
  alias Toolnexus.Agents.Runtime

  @registry %{
    "worker" => %{name: "worker", description: "a scripted worker", system_prompt: "", model: "m-worker"}
  }

  # ---- scripted model, shared by BOTH the top-level client and the sub-agent
  # runtime below — the reporter's actual case. It never touches HTTP: it is
  # handed the assembled in-process request (%{messages, tools, model, body}) and
  # returns one assistant message (%{content: ...}). It instruments concurrency
  # (via an Agent-held counter set) so the gate tests assert on real overlap, not
  # just a trust-me counter.

  defp start_model(hold_ms \\ 0) do
    {:ok, pid} = Agent.start_link(fn -> %{in_flight: 0, max_seen: 0, overlaps: 0, calls: 0} end)
    %{pid: pid, hold_ms: hold_ms}
  end

  defp model_generate(%{pid: pid, hold_ms: hold_ms}, req) do
    n =
      Agent.get_and_update(pid, fn st ->
        n = st.in_flight + 1
        overlaps = if n > 1, do: st.overlaps + 1, else: st.overlaps
        st = %{st | in_flight: n, calls: st.calls + 1, max_seen: max(st.max_seen, n), overlaps: overlaps}
        {n, st}
      end)

    if hold_ms > 0, do: Process.sleep(hold_ms)
    Agent.update(pid, fn st -> %{st | in_flight: st.in_flight - 1} end)
    %{content: "ok model=#{req.model} turn-done, in-flight was #{n}"}
  end

  defp model_stats(%{pid: pid}), do: Agent.get(pid, & &1)

  defp bare_toolkit do
    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    tk
  end

  test "one generate function serves BOTH the top-level in-process client and a sub-agent runtime, with no copied adapter code" do
    m = start_model()
    generate = fn req -> model_generate(m, req) end

    # Top-level client, driven by `generate` directly.
    client = Client.create_in_process(model: "spike-model", generate: generate)
    top = Client.run(client, "hello", bare_toolkit())
    assert top.status == "done"

    # Sub-agent runtime, driven by the SAME `generate` via Runtime's `:in_process`
    # option — which internally calls the SAME Toolnexus.Client.in_process_transport/1
    # export create_in_process/1 used above. No second adapter.
    rt = Runtime.new(%{in_process: generate, registry: @registry})
    {:ok, worker} = Runtime.spawn_agent(rt, Runtime.root(rt), "worker")
    Runtime.wake(rt, worker, "do the thing")
    sub = Runtime.wait(rt, worker, 5_000)
    assert sub.status == "done"

    assert model_stats(m).calls == 2, "one top-level Ask + one sub-agent turn"

    Runtime.shutdown(rt)
  end

  test "construction-time validation: :transport and :in_process together raise ArgumentError, never resolved by precedence" do
    m = start_model()
    generate = fn req -> model_generate(m, req) end
    transport = Client.in_process_transport(generate)

    assert_raise ArgumentError, ~r/mutually exclusive/, fn ->
      Runtime.new(%{transport: transport, in_process: generate, registry: @registry})
    end
  end

  test "construction-time validation: :llm and :in_process together raise ArgumentError" do
    m = start_model()
    generate = fn req -> model_generate(m, req) end

    assert_raise ArgumentError, ~r/mutually exclusive/, fn ->
      Runtime.new(%{llm: %{base_url: "http://example.invalid"}, in_process: generate, registry: @registry})
    end
  end

  test "the global turn gate holds at concurrency 1 on the :in_process path (falsifiable)" do
    m = start_model(15)
    generate = fn req -> model_generate(m, req) end

    rt = Runtime.new(%{in_process: generate, max_concurrent_turns: 1, registry: @registry})

    1..5
    |> Task.async_stream(
      fn i ->
        {:ok, w} = Runtime.spawn_agent(rt, Runtime.root(rt), "worker")
        Runtime.wake(rt, w, "job #{i}")
        r = Runtime.wait(rt, w, 5_000)
        assert r.status == "done"
      end,
      max_concurrency: 5,
      timeout: 10_000
    )
    |> Enum.each(fn {:ok, _} -> :ok end)

    stats = model_stats(m)

    assert stats.overlaps == 0,
           "gate FALSIFIED: #{stats.overlaps} call(s) observed >1 in flight (max_seen=#{stats.max_seen}) " <>
             "with max_concurrent_turns: 1 — the global turn gate did not wrap the :in_process transport"

    assert Runtime.max_observed_concurrent_turns(rt) == 1

    Runtime.shutdown(rt)
  end

  test "negative control: with max_concurrent_turns 5 the scripted model DOES observe overlap (proves the detector is real)" do
    m = start_model(15)
    generate = fn req -> model_generate(m, req) end

    rt = Runtime.new(%{in_process: generate, max_concurrent_turns: 5, registry: @registry})

    1..5
    |> Task.async_stream(
      fn i ->
        {:ok, w} = Runtime.spawn_agent(rt, Runtime.root(rt), "worker")
        Runtime.wake(rt, w, "job #{i}")
        Runtime.wait(rt, w, 5_000)
      end,
      max_concurrency: 5,
      timeout: 10_000
    )
    |> Enum.each(fn {:ok, _} -> :ok end)

    stats = model_stats(m)

    assert stats.overlaps > 0,
           "control INVALID: expected overlap with max_concurrent_turns: 5 and a 15ms hold, " <>
             "got 0 overlaps (max_seen=#{stats.max_seen}) — the detector proves nothing"

    Runtime.shutdown(rt)
  end
end
