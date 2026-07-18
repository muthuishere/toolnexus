defmodule Toolnexus.Agents.FanoutTest do
  @moduledoc """
  §7D S1/S2 — parallel task fan-out, context isolation, usage roll-up.
  Shared fixture: `examples/subagent-fanout/fixture.json`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.Runtime
  import Toolnexus.TestAgentHelpers

  test "S1/S2 task fan-out · context isolation · parallel · usage roll-up" do
    rt = new_rt()
    {:ok, coord} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")
    Runtime.wake(rt, coord, "answer using two lookups")
    r = Runtime.wait(rt, coord)

    # parent reaches done
    assert r.status == "done", "parent reaches done: #{r.text}"
    # parent got BOTH child answers
    assert String.contains?(r.text, "found:data(x)") and length(String.split(r.text, "found:")) == 3,
           "parent got BOTH child answers: #{r.text}"

    # parent ran 2 turns only (children's turns not in parent)
    assert r.turns == 2, "parent ran 2 turns only, got #{r.turns}"

    # two children spawned with deterministic ids
    tr = Runtime.trace(rt)

    assert Enum.any?(tr, &String.contains?(&1, "/coordinator.1/explore.1:")) and
             Enum.any?(tr, &String.contains?(&1, "/explore.2:")),
           "two children spawned with deterministic ids"

    # children ran CONCURRENTLY (observed ≥2 turns in flight)
    assert Runtime.max_observed_concurrent_turns(rt) >= 2,
           "children ran CONCURRENTLY, max=#{Runtime.max_observed_concurrent_turns(rt)}"

    # usage rolls up to parent (the ledger): own 2×40 + children 2×(2×40) = 240
    assert Runtime.snapshot(coord).usage_total == 240,
           "usage rolls up to parent, got #{Runtime.snapshot(coord).usage_total}"

    # children auto-closed after task
    coord_id = Runtime.snapshot(coord).id

    assert Enum.all?(Runtime.list(rt), fn h -> h.id == coord_id or h.state == :closed end),
           "children auto-closed after task"

    # fixture `expect.transitions` — per-handle transition traces (§0 method)
    assert transitions(tr, "root/coordinator.1") == ["idle→running", "running→idle"]
    assert transitions(tr, "root/coordinator.1/explore.1") == ["idle→running", "running→idle", "idle→closed"]
    assert transitions(tr, "root/coordinator.1/explore.2") == ["idle→running", "running→idle", "idle→closed"]

    Runtime.shutdown(rt)
  end
end
