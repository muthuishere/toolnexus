defmodule Toolnexus.Agents.LifecycleTest do
  @moduledoc """
  §7D S5/S6/S8/S9 — unsolicited rail, three backpressure gates, interrupt
  (transactional drain), leaf-first close cascade.
  Shared fixture: `examples/subagent-lifecycle/fixture.json`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  import Toolnexus.TestAgentHelpers

  test "S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe" do
    rt = new_rt()
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.post(rt, peer, %{from: "root/coordinator.1", channel: "peer", text: "update 1"})
    Runtime.post(rt, peer, %{from: "external", channel: "external", text: "webhook payload"})
    for _ <- 1..3, do: Runtime.post(rt, peer, %{from: "clock", channel: "timer", text: "tick"})
    Runtime.wake(rt, peer)
    r = Runtime.wait(rt, peer)

    # one wake = ONE turn for all items
    assert r.turns == 1, "one wake = ONE turn for all items, got #{r.turns}"
    # 2 messages + 3 ticks coalesced to 3 items
    assert r.text == "processed 3 items", "2 messages + 3 ticks coalesced to 3 items: #{r.text}"

    Runtime.shutdown(rt)
  end

  test "S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate" do
    rt = new_rt(%{inbox_cap: 2})
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "1"})
    Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "2"})
    third = Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "3"})

    # inbox gate: third post REJECTED loudly to sender
    assert third.ok == false and third.is_error =~ "inbox full", "inbox gate: third post REJECTED loudly"
    Runtime.shutdown(rt)

    rt2 =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(%{"coordinator" => %{budget: %{max_concurrent: 1}}})
      })

    {:ok, c2} = Runtime.spawn_agent(rt2, Runtime.root(rt2), "coordinator")
    Runtime.wake(rt2, c2, "go")
    r2 = Runtime.wait(rt2, c2)
    tr2 = Runtime.trace(rt2)

    # concurrency gate: 2nd child QUEUED then DEQUEUED, still completes
    assert r2.status == "done" and Enum.any?(tr2, &(&1 =~ "wake QUEUED")) and
             Enum.any?(tr2, &(&1 =~ "DEQUEUED wake")),
           "concurrency gate: 2nd child QUEUED then DEQUEUED, still completes: #{r2.text}"

    # concurrency gate: never >1 child turn in flight… but parent turn allowed
    assert Runtime.max_observed_concurrent_turns(rt2) <= 2,
           "never >1 child turn in flight, max=#{Runtime.max_observed_concurrent_turns(rt2)}"

    Runtime.shutdown(rt2)

    rt3 = new_rt(%{max_concurrent_turns: 1})
    {:ok, c3} = Runtime.spawn_agent(rt3, Runtime.root(rt3), "coordinator")
    Runtime.wake(rt3, c3, "go")
    Runtime.wait(rt3, c3)

    # global turn gate: with cap 1, max observed concurrent turns = 1 (and no deadlock —
    # the gate wraps only the LLM HTTP call, never a whole Run)
    assert Runtime.max_observed_concurrent_turns(rt3) == 1,
           "global turn gate: max=#{Runtime.max_observed_concurrent_turns(rt3)}"

    Runtime.shutdown(rt3)
  end

  test "S8 interrupt = turn-abort → idle (inbox intact); NOT a kill" do
    rt = new_rt()
    {:ok, s} = Runtime.spawn_agent(rt, Runtime.root(rt), "slow")
    Runtime.post(rt, s, %{from: "root", channel: "peer", text: "for later"})
    Runtime.wake(rt, s, "work slowly")
    Process.sleep(80)

    # agent is mid-turn (running)
    assert Runtime.snapshot(s).state == :running, "agent is mid-turn (running)"

    w = Task.async(fn -> Runtime.wait(rt, s) end)
    Process.sleep(30)
    Runtime.interrupt(rt, s)
    r = Task.await(w, 5_000)

    # turn aborted → isError result, status=interrupted
    assert r.is_error and r.status == "interrupted", "turn aborted → isError, status=interrupted: #{r.status}"
    # agent is IDLE (alive), not closed
    assert Runtime.snapshot(s).state == :idle, "agent is IDLE (alive), not closed: #{Runtime.snapshot(s).state}"
    # inbox survived the interrupt (transactional drain restored the item)
    assert length(Runtime.snapshot(s).inbox) == 1, "inbox survived the interrupt"

    Runtime.shutdown(rt)
  end

  test "S9 stop-all: close(root) cascades leaf-first; closed handles reject posts" do
    test_pid = self()
    cb = fn snap, _reason -> send(test_pid, {:closed, snap.id}) end

    rt =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(%{"coordinator" => %{on_close: cb}, "peer" => %{on_close: cb}})
      })

    {:ok, c} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")
    {:ok, k1} = Runtime.spawn_agent(rt, c, "peer")
    # grandchild
    {:ok, k2} = Runtime.spawn_agent(rt, k1, "peer")
    [c_id, k1_id, k2_id] = Enum.map([c, k1, k2], &Runtime.snapshot(&1).id)
    Runtime.close(rt)

    order =
      for _ <- 1..3 do
        receive do
          {:closed, id} -> id
        after
          500 -> :timeout
        end
      end

    # leaf-first order (grandchild → child → parent)
    assert order == [k2_id, k1_id, c_id], "leaf-first order, got #{inspect(order)}"

    p = Runtime.post(rt, k1, %{from: "x", channel: "peer", text: "late"})
    # post after close = loud isError
    assert p.ok == false and p.is_error =~ "closed", "post after close = loud isError"

    Runtime.shutdown(rt)
  end
end
