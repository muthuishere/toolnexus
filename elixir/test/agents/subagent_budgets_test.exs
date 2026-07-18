defmodule Toolnexus.Agents.BudgetsTest do
  @moduledoc """
  §7D S7 — hierarchical budgets: carve, LIVE ancestor-chain enforcement, loud
  `incomplete` stops. Shared fixture: `examples/subagent-budgets/fixture.json`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  import Toolnexus.TestAgentHelpers

  test "S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud" do
    rt = new_rt()
    {:ok, c} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator", %{max_tokens: 100, max_children: 2})
    {:ok, kid} = Runtime.spawn_agent(rt, c, "explore", %{max_tokens: 500})

    # carve: child asked 500, parent pool 100 → effective 100
    assert Runtime.snapshot(kid).pool_tokens == 100,
           "carve: effective 100, got #{Runtime.snapshot(kid).pool_tokens}"

    {:ok, kid2} = Runtime.spawn_agent(rt, c, "explore")
    kid3 = Runtime.spawn_agent(rt, c, "explore")

    # maxChildren enforced at spawn
    assert match?({:error, _}, kid3) and elem(kid3, 1) =~ "maxChildren", "maxChildren enforced at spawn"

    Runtime.wake(rt, kid, "go")
    # burns 80 tokens (2 turns×40) from both kid and parent pools
    Runtime.wait(rt, kid)

    # roll-up drains the PARENT pool too
    assert Runtime.snapshot(c).pool_tokens == 20,
           "roll-up drains the PARENT pool too, got #{Runtime.snapshot(c).pool_tokens}"

    Runtime.wake(rt, kid2, "go")
    r2 = Runtime.wait(rt, kid2)
    # pool nearly gone: sibling still ran (20 left > 0 at admission; live walk allows)
    assert r2.status == "done", "pool nearly gone: sibling still ran"

    r3 = Runtime.run_turn(rt, kid2, "again")

    # pool exhausted → status=incomplete via the LIVE ancestor walk (kid2's own carve
    # has remainder; the parent pool is negative) — partial work preserved, NO crash
    assert r3.status == "incomplete" and r3.text =~ "budget exhausted",
           "pool exhausted → status=incomplete: #{r3.status}"

    Runtime.shutdown(rt)

    rt4 =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(%{"looper" => %{budget: %{max_turns: 3}}})
      })

    {:ok, lp} = Runtime.spawn_agent(rt4, Runtime.root(rt4), "looper")
    Runtime.wake(rt4, lp, "loop forever")
    rl = Runtime.wait(rt4, lp)

    # maxTurns cap is LOUD: status=incomplete (not silent done)
    assert rl.status == "incomplete", "maxTurns cap is LOUD: #{rl.status}"

    Runtime.shutdown(rt4)
  end
end
