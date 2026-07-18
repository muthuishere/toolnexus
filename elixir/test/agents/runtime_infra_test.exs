defmodule Toolnexus.Agents.RuntimeInfraTest do
  @moduledoc """
  §7D runtime obligations beyond the fixture scenarios: runtime-owned client
  lifecycle (one store, one metrics registry, no leaked processes), transcripts
  surviving turns, the injectable clock, wait semantics (spawner-only, settled
  answers immediately), sorted registry composition, forced close escalation
  without resurrection, extra budget dimensions, and the as_tool §10 bridge.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents
  alias Toolnexus.Agents.{Handle, Runtime}
  alias Toolnexus.Client.InMemoryConversationStore
  alias Toolnexus.TestAgentMock, as: Mock
  alias Toolnexus.TestVirtualClock
  import Toolnexus.TestAgentHelpers

  test "runtime-owned client lifecycle: one store + one metrics registry shared by every handle; transcripts survive turns" do
    rt = new_rt()
    ctx = Runtime.ctx(rt)
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    {:ok, explore} = Runtime.spawn_agent(rt, Runtime.root(rt), "explore")

    # every handle's client rides the runtime's store and metrics registry
    for h <- [peer, explore] do
      snap = Runtime.snapshot(h)
      assert snap.store == ctx.store, "handle #{snap.id} shares the runtime store"
      assert snap.metrics == ctx.metrics, "handle #{snap.id} shares the runtime metrics registry"
    end

    # two turns on one handle → ONE conversation, both turns present
    r1 = Runtime.run_turn(rt, peer, "1. first item")
    r2 = Runtime.run_turn(rt, peer, "1. second wake")
    assert r1.text != nil and r2.text != nil

    stored = InMemoryConversationStore.get(Runtime.conversation_store(rt), "root/peer.1")
    user_msgs = Enum.filter(stored, &(&1["role"] == "user"))

    assert length(user_msgs) == 2 and Enum.at(user_msgs, 0)["content"] =~ "first item" and
             Enum.at(user_msgs, 1)["content"] =~ "second wake",
           "transcript survives turns (both turns in one conversation)"

    # repeated turns leak no processes (the spike leaked a store+metrics Agent per turn)
    before_count = length(Process.list())
    for _ <- 1..5, do: Runtime.run_turn(rt, peer, "1. again")

    # transient Run processes need a beat to unwind — poll until the count settles
    delta =
      Enum.reduce_while(1..20, nil, fn _, _ ->
        d = length(Process.list()) - before_count
        if d <= 2, do: {:halt, d}, else: (Process.sleep(50) && {:cont, d})
      end)

    assert delta <= 2, "no leaked store/metrics processes from turns (delta #{delta})"

    Runtime.shutdown(rt)
  end

  test "shutdown tears the runtime-owned infrastructure down (nothing leaks past the runtime)" do
    rt = new_rt()
    ctx = Runtime.ctx(rt)
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.run_turn(rt, peer, "1. hello")
    %InMemoryConversationStore{pid: store_pid} = ctx.store

    Runtime.shutdown(rt)
    Process.sleep(20)

    for {name, pid} <- [sup: ctx.sup, gate: ctx.gate, trace: ctx.trace, metrics: ctx.metrics, store: store_pid, handle: peer] do
      refute Process.alive?(pid), "#{name} still alive after shutdown"
    end
  end

  test "injectable clock: wait timeout fires on virtual time, not wall time" do
    vc = TestVirtualClock.new()

    rt =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(),
        clock: TestVirtualClock.clock(vc)
      })

    {:ok, s} = Runtime.spawn_agent(rt, Runtime.root(rt), "slow")
    Runtime.wake(rt, s, "work slowly")
    Process.sleep(30)

    w = Task.async(fn -> Runtime.wait(rt, s, 1_000) end)
    Process.sleep(30)
    # no wall time passes — the timeout fires only when the virtual clock advances
    refute Task.yield(w, 50), "wait did not time out on wall time"

    TestVirtualClock.advance(vc, 1_000)
    r = Task.await(w, 1_000)
    assert r.is_error and r.text =~ "wait timeout after 1000ms", "virtual-clock timeout fired: #{r.text}"

    Runtime.close(rt, force: true)
    Runtime.shutdown(rt)
  end

  test "wait is spawner-only: a sibling credential is refused; the host may wait" do
    rt = new_rt()
    {:ok, a} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")

    # a handle-scoped caller that did not spawn `a` is refused
    refused = Handle.wait(a, nil, as: "root/somebody-else.1")
    assert refused.is_error and refused.text =~ "wait is spawner-only", "sibling wait refused: #{refused.text}"

    # the spawner's id is accepted; the host (default) is accepted
    Runtime.wake(rt, a, "1. go")
    assert Handle.wait(a, nil, as: "root").status == "done"
    assert Runtime.wait(rt, a).status == "done"

    Runtime.shutdown(rt)
  end

  test "wait next-or-last: settled handles (idle-with-result AND suspended) answer immediately" do
    rt = new_rt()
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.wake(rt, peer, "1. go")
    Runtime.wait(rt, peer)

    # a wait AFTER completion answers immediately with the last result
    t0 = System.monotonic_time(:millisecond)
    r = Runtime.wait(rt, peer, 5_000)
    assert r.status == "done" and System.monotonic_time(:millisecond) - t0 < 100,
           "wait after completion answered immediately"

    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    r1 = Runtime.wait(rt, p)
    assert r1.status == "pending"

    # a NEW wait on the already-suspended handle answers immediately with its pending
    r2 = Runtime.wait(rt, p, 5_000)
    assert r2.status == "pending" and r2.pending.kind == "approval",
           "wait on a suspended handle answered immediately with its pending"

    Runtime.shutdown(rt)
  end

  test "task description advertises the team sorted by name; out-of-team targets are refused with the team list" do
    reg =
      registry(%{
        "coordinator" => %{task_targets: ["peer", "explore", "asker"]}
      })

    rt = Runtime.new(%{transport: Mock.transport(&Mock.demo/1), registry: reg})
    {:ok, c} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")
    snap = Runtime.snapshot(c)

    task = Runtime.task_tool(Runtime.ctx(rt), c, snap.id, reg["coordinator"])

    # sorted by name, composed from each agent's `does`
    assert task.description ==
             "Delegate a subtask to an isolated subagent. Available agents — " <>
               "asker: needs approvals; explore: read-only research; peer: team member"

    # out-of-team target ⇒ uniform error naming the team
    r = task.execute.(%{"agent" => "slow", "prompt" => "x"}, %Toolnexus.Context{})
    assert r.is_error and r.output =~ "not in this agent's team (asker, explore, peer)"

    # unknown agent within targets ⇒ loud spawn error listing known agents sorted
    reg2 = registry(%{"coordinator" => %{task_targets: ["ghost"]}})
    rt2 = Runtime.new(%{transport: Mock.transport(&Mock.demo/1), registry: reg2})
    {:ok, c2} = Runtime.spawn_agent(rt2, Runtime.root(rt2), "coordinator")
    task2 = Runtime.task_tool(Runtime.ctx(rt2), c2, "root/coordinator.1", reg2["coordinator"])
    r2 = task2.execute.(%{"agent" => "ghost", "prompt" => "x"}, %Toolnexus.Context{})
    assert r2.is_error and r2.output =~ "unknown agent \"ghost\""

    Runtime.shutdown(rt)
    Runtime.shutdown(rt2)
  end

  test "agents without a team get NO task tool (delegation is opt-in, never default)" do
    rt = new_rt()
    {:ok, e} = Runtime.spawn_agent(rt, Runtime.root(rt), "explore")
    {:ok, c} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")

    # snapshots expose defs; tool lists are built at init — probe via a turn's schema is
    # indirect, so assert on the builder itself
    ctx = Runtime.ctx(rt)
    explore_tools = ctx.registry["explore"][:tools] || []
    assert Enum.all?(explore_tools, &(&1.name != "task"))
    assert Runtime.snapshot(e).def[:task_targets] in [nil, []]
    assert Runtime.snapshot(c).def[:task_targets] == ["explore"]

    Runtime.shutdown(rt)
  end

  test "forced close: escalates the in-flight Run, restores drained items, never resurrects a closed handle" do
    rt = new_rt(%{shutdown_ms: 50})
    {:ok, s} = Runtime.spawn_agent(rt, Runtime.root(rt), "slow")
    Runtime.post(rt, s, %{from: "root", channel: "peer", text: "keep me"})
    Runtime.wake(rt, s, "work slowly")
    Process.sleep(50)
    assert Runtime.snapshot(s).state == :running

    # graceful close escalates after shutdown_ms (the m-slow Run never finishes)
    Runtime.close(rt)
    snap = Runtime.snapshot(s)
    assert snap.state == :closed, "graceful close escalated to abort after shutdown_ms"
    # the drained item was restored before close completed
    assert Enum.map(snap.inbox, & &1.text) == ["keep me"], "drained item restored on forced close"

    # no stray :DOWN resurrects the closed handle
    Process.sleep(50)
    assert Runtime.snapshot(s).state == :closed, "closed handle stayed closed"
    Runtime.shutdown(rt)
  end

  test "budget extras: maxToolCalls and maxWallMs stop loudly with the limit named" do
    rt = new_rt()
    {:ok, e} = Runtime.spawn_agent(rt, Runtime.root(rt), "explore", %{max_tool_calls: 1})
    assert Runtime.run_turn(rt, e, "go").status == "done"
    r = Runtime.run_turn(rt, e, "go again")
    assert r.status == "incomplete" and r.text =~ "maxToolCalls", "maxToolCalls stop is loud: #{r.text}"
    Runtime.shutdown(rt)

    vc = TestVirtualClock.new()

    rt2 =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry: registry(),
        clock: TestVirtualClock.clock(vc)
      })

    {:ok, p} = Runtime.spawn_agent(rt2, Runtime.root(rt2), "peer", %{max_wall_ms: 500})
    assert Runtime.run_turn(rt2, p, "1. go").status == "done"
    TestVirtualClock.advance(vc, 1_000)
    r2 = Runtime.run_turn(rt2, p, "1. late")
    assert r2.status == "incomplete" and r2.text =~ "maxWallMs", "maxWallMs stop is loud: #{r2.text}"
    Runtime.shutdown(rt2)
  end

  test "a refused wake SETTLES the handle: wait observes the incomplete result" do
    rt = new_rt()
    {:ok, e} = Runtime.spawn_agent(rt, Runtime.root(rt), "explore", %{max_tokens: 100})
    # two runs at 80 tokens each exhaust the 100-token pool
    assert Runtime.run_turn(rt, e, "go").status == "done"
    assert Runtime.run_turn(rt, e, "go on").status == "done"

    ack = Runtime.wake(rt, e, "again")
    assert ack.ok == false and ack.is_error =~ "budget exhausted", "wake acked the refusal loudly"

    # the refusal SETTLED the handle — wait answers immediately, never hangs
    r = Runtime.wait(rt, e, 1_000)
    assert r.status == "incomplete" and r.limit == "maxTokens",
           "refused wake settled with incomplete: #{inspect(r)}"

    Runtime.shutdown(rt)
  end

  test "maxDepth enforced at spawn (default 3, measured from root)" do
    rt = new_rt()
    {:ok, a} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    {:ok, b} = Runtime.spawn_agent(rt, a, "peer")
    {:ok, c} = Runtime.spawn_agent(rt, b, "peer")
    assert {:error, msg} = Runtime.spawn_agent(rt, c, "peer")
    assert msg =~ "maxDepth", "maxDepth named in the spawn error: #{msg}"
    Runtime.shutdown(rt)
  end

  test "as_tool §10 bridge: a durably suspending agent surfaces pending; retry with ctx.answer resumes" do
    asker = Agents.agent("asker", %{does: "needs approvals", uses: %{tools: [check_secret()]}, model: "m-asker"})
    tool = Agents.as_tool(asker, %{transport: Mock.transport(&Mock.demo/1)})

    # without an answer: the agent suspends durably → §10 pending tool result
    r1 = tool.execute.(%{"prompt" => "get the secret"}, %Toolnexus.Context{})
    assert r1.is_error and match?(%Toolnexus.Request{kind: "approval"}, r1.metadata.pending),
           "agent suspension rides metadata.pending"

    # §10 retry with ctx.answer: the Answer becomes the agent's interpreter
    r2 =
      tool.execute.(%{"prompt" => "get the secret"}, %Toolnexus.Context{
        answer: %Toolnexus.Answer{id: r1.metadata.pending.id, ok: true}
      })

    assert r2.is_error == false and r2.output =~ "asker-done: secret-token",
           "retry-with-answer resumed the agent: #{r2.output}"

    assert r2.metadata.agent == "asker" and r2.metadata.total_tokens > 0
  end

  test "onSpawn fires once before the first turn; interrupt of a suspended handle cancels its pending" do
    test_pid = self()

    reg =
      registry(%{
        "peer" => %{on_spawn: fn snap -> send(test_pid, {:spawned, snap.id, snap.state}) end}
      })

    rt = Runtime.new(%{transport: Mock.transport(&Mock.demo/1), registry: reg})
    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    assert_receive {:spawned, "root/peer.1", :idle}, 500

    {:ok, ap} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, ap, "do the secret thing")
    assert Runtime.wait(rt, ap).status == "pending"

    # operator escape hatch: interrupt a suspended handle → pending cancelled → idle
    leaf = Runtime.list(rt) |> Enum.find(&String.contains?(&1.id, "asker"))
    assert leaf.state == :suspended
    Runtime.interrupt(rt, ap)
    assert Runtime.snapshot(ap).state == :idle and Runtime.snapshot(ap).pending_req == nil
    assert Runtime.trace(rt) |> Enum.any?(&(&1 =~ "suspended→idle (interrupt cancelled pending"))

    _ = p
    Runtime.shutdown(rt)
  end
end
