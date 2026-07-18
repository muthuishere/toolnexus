defmodule Toolnexus.Agents.EscalationTest do
  @moduledoc """
  §7D S3 — nearest-interpreter escalation (§10 unchanged).
  Shared fixture: `examples/subagent-escalation/fixture.json`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  import Toolnexus.TestAgentHelpers

  test "S3 child suspends → parent's waitFor answers (nearest interpreter)" do
    rt =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(%{"approverParent" => %{wait_for: fn req -> %{id: req.id, ok: true} end}})
      })

    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    r = Runtime.wait(rt, p)

    # run completed (no durable pending)
    assert r.status == "done", "run completed (no durable pending): #{r.status}"
    # child's approval flowed through parent authority
    assert r.text =~ "asker-done: secret-token", "child's approval flowed through parent authority: #{r.text}"

    tr = Runtime.trace(rt)
    # trace shows suspended→running round-trip
    assert Enum.any?(tr, &(&1 =~ "running→suspended")) and Enum.any?(tr, &(&1 =~ "suspended→running")),
           "trace shows suspended→running round-trip"

    # escalation chose an ANCESTOR (not self)
    assert Enum.any?(tr, &(&1 =~ "escalate → root/approverParent.1 answers")),
           "escalation chose an ANCESTOR (not self)"

    # fixture `expect.transitions`
    assert transitions(tr, "root/approverParent.1") == ["idle→running", "running→idle"]

    assert transitions(tr, "root/approverParent.1/asker.1") ==
             ["idle→running", "running→suspended", "suspended→running", "running→idle", "idle→closed"]

    Runtime.shutdown(rt)
  end
end
