defmodule Toolnexus.Agents.EdgeTest do
  @moduledoc """
  §7D edge behavior: verbs against closed/suspended/running handles, error and
  crash Runs crossing the boundary as results, refusal of a dequeued wake on an
  exhausted ancestor pool, host-injected stores, and registry closure edges.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents
  alias Toolnexus.Agents.{Handle, Runtime, TurnGate}
  alias Toolnexus.TestAgentMock, as: Mock
  import Toolnexus.TestAgentHelpers

  defmodule KVStore do
    @moduledoc false
    @behaviour Toolnexus.Client.ConversationStore
    defstruct [:pid]
    def new do
      {:ok, pid} = Agent.start_link(fn -> %{} end)
      %__MODULE__{pid: pid}
    end

    @impl true
    def get(%__MODULE__{pid: pid}, id), do: Agent.get(pid, &Map.get(&1, id))
    @impl true
    def save(%__MODULE__{pid: pid}, id, messages), do: Agent.update(pid, &Map.put(&1, id, messages))
  end

  test "verbs against closed handles: wait/run_now answer closed; spawn/post rejected; double close and stray kill_run are no-ops" do
    rt = new_rt()
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.close(rt)

    assert Runtime.wait(rt, h).status == "closed"
    assert Runtime.run_turn(rt, h, "x").status == "closed"
    assert Runtime.wake(rt, h, "x").is_error == "closed"
    assert {:error, "parent closed"} = Runtime.spawn_agent(rt, h, "peer")
    # direct verb calls on a closed handle are no-ops (state stays queryable)
    assert GenServer.call(h, {:close, "closed"}) == :ok
    assert GenServer.call(h, {:kill_run, :close}) == :ok
    assert Runtime.snapshot(h).state == :closed
    Runtime.shutdown(rt)
  end

  test "wake on suspended buffers (no transition); wake while running is a no-op ack; interrupt on idle is a no-op" do
    rt = new_rt()
    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    assert Runtime.wait(rt, p).status == "pending"

    leaf_pid =
      Runtime.snapshot(p).children |> hd() |> Map.fetch!(:pid)

    assert Runtime.snapshot(leaf_pid).state == :suspended
    # only the Answer wakes a suspended agent — wake is an ack, not a transition
    assert Runtime.wake(rt, leaf_pid, "hello?").ok == true
    assert Runtime.snapshot(leaf_pid).state == :suspended

    {:ok, s} = Runtime.spawn_agent(rt, Runtime.root(rt), "slow")
    Runtime.wake(rt, s, "work")
    Process.sleep(60)
    assert Runtime.wake(rt, s, "again").ok == true, "wake while running acks"
    # already-running run_now answers with an error result, never a second Run
    assert Runtime.run_turn(rt, s, "x").status == "error"
    Runtime.interrupt(rt, s)

    # interrupt on an idle handle is a no-op
    assert Runtime.interrupt(rt, s) == :ok
    assert Runtime.snapshot(s).state == :idle
    Runtime.shutdown(rt)
  end

  test "the ROOT handle itself can run turns (parent-nil wake path)" do
    rt = new_rt()
    root = Runtime.root(rt)
    assert Handle.wake(root, "hello").ok == true
    assert Handle.wait(root).status == "done"
    Runtime.shutdown(rt)
  end

  test "a failing LLM crosses the boundary as an error RESULT; a killed Run as a crash result — never an exception" do
    transport = fn %{body: body} ->
      case body["model"] do
        "m-error" ->
          {:error, %RuntimeError{message: "llm down"}}

        "m-suicide" ->
          Process.exit(self(), :kill)

        _ ->
          {:ok, %{status: 200, headers: %{}, body: Mock.demo(body)}}
      end
    end

    reg = registry(%{"peer" => %{model: "m-error"}, "slow" => %{model: "m-suicide"}})
    rt = Runtime.new(%{transport: transport, registry: reg})

    {:ok, e} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    r = Runtime.run_turn(rt, e, "go")
    assert r.is_error and r.status == "error" and r.text =~ "llm down"
    # the handle survives, idle
    assert Runtime.snapshot(e).state == :idle

    {:ok, s} = Runtime.spawn_agent(rt, Runtime.root(rt), "slow")
    r2 = Runtime.run_turn(rt, s, "go")
    assert r2.is_error and r2.status == "error" and r2.text =~ "run crashed"
    assert Runtime.snapshot(s).state == :idle
    Runtime.shutdown(rt)
  end

  test "a dequeued wake refused on the exhausted ancestor pool settles incomplete (loud through the task result)" do
    # parent pool 70; child1 burns 80 → pool negative; queued child2's slot-transfer
    # wake hits the LIVE ancestor walk and settles incomplete
    reg = registry(%{"coordinator" => %{budget: %{max_tokens: 70, max_concurrent: 1}}})
    rt = Runtime.new(%{transport: Mock.transport(&Mock.demo/1), registry: reg})
    {:ok, c} = Runtime.spawn_agent(rt, Runtime.root(rt), "coordinator")
    Runtime.wake(rt, c, "go")
    r = Runtime.wait(rt, c)

    assert r.text =~ "found:data(x)", "first child completed: #{r.text}"
    assert r.text =~ "[incomplete] budget exhausted", "second child settled incomplete loudly: #{r.text}"
    Runtime.shutdown(rt)
  end

  test "declined escalation: a waitFor answer without ok feeds the declined path" do
    rt =
      Runtime.new(%{
        transport: Mock.transport(&Mock.demo/1),
        registry:
          registry(%{
            "approverParent" => %{wait_for: fn _req -> :nope end},
            "asker" => %{budget: %{max_turns: 2}}
          })
      })

    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    r = Runtime.wait(rt, p)

    # the child kept getting declined → never produced the token → loud incomplete
    # crossed the task boundary as a result; the parent still finished
    assert r.status == "done" and r.text =~ "parent-final:", "declined path crossed as a result: #{r.text}"
    Runtime.shutdown(rt)
  end

  test "host-injected ConversationStore: the runtime uses it and leaves it alive on shutdown" do
    store = KVStore.new()
    rt = Runtime.new(%{transport: Mock.transport(&Mock.demo/1), registry: registry(), store: store})
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    assert Runtime.run_turn(rt, peer, "1. hi").status == "done"

    assert is_list(KVStore.get(store, "root/peer.1")), "turn persisted into the host store"
    Runtime.shutdown(rt)
    # a host-owned store is not the runtime's to kill
    assert Process.alive?(store.pid)
    assert is_list(KVStore.get(store, "root/peer.1")), "host store survives runtime shutdown"
  end

  test "turn gate: a release from a non-holder is ignored" do
    {:ok, gate} = TurnGate.start_link(1)
    assert TurnGate.release(gate) == :ok
    assert TurnGate.acquire(gate) == :ok
    assert TurnGate.max_observed(gate) == 1
    GenServer.stop(gate)
  end

  test "registry closure: duplicate team members collapse to one definition" do
    explore = Agents.agent("explore", %{does: "research", model: "m-explore", uses: %{tools: [lookup()]}})
    coder = Agents.agent("coder", %{does: "implements", team: [explore, explore], model: "m-coder"})
    assert Agents.registry(coder) |> Map.keys() |> Enum.sort() == ["coder", "explore"]
  end
end
