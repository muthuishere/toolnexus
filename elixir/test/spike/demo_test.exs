defmodule Toolnexus.Spike.DemoTest do
  @moduledoc """
  SPIKE demo — 9 acceptance scenarios for the agent-runtime substrate v2
  (port of js/spike/demo.ts; same check names, same mock LLM script).
  Zero network: the LLM is a scripted in-process plug keyed by body["model"].
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Spike.{MockLlm, Runtime}
  alias Toolnexus.{Request, ToolResult}

  # ---------- scoped tools ----------
  defp lookup do
    Toolnexus.define_tool(%{
      name: "lookup",
      description: "look something up",
      input_schema: %{"type" => "object", "properties" => %{"q" => %{"type" => "string"}}},
      execute: fn args -> "data(#{args["q"]})" end
    })
  end

  defp check_secret do
    Toolnexus.define_tool(%{
      name: "check_secret",
      description: "needs human approval",
      input_schema: %{"type" => "object", "properties" => %{}},
      execute: fn _args, ctx ->
        if ctx.answer && ctx.answer.ok do
          "secret-token"
        else
          %ToolResult{
            output: "approve secret access?",
            is_error: false,
            metadata: %{
              pending: %Request{
                id: "req-#{System.unique_integer([:positive])}",
                kind: "approval",
                prompt: "approve secret access?"
              }
            }
          }
        end
      end
    })
  end

  # ---------- registry ----------
  defp registry(overrides \\ %{}) do
    base = %{
      "coordinator" => %{name: "coordinator", description: "splits and delegates", system_prompt: "coordinate", model: "m-coordinator"},
      "explore" => %{name: "explore", description: "read-only research", system_prompt: "explore", model: "m-explore", tools: [lookup()]},
      "approverParent" => %{name: "approverParent", description: "delegates, holds approval authority", system_prompt: "", model: "m-approver-parent"},
      "asker" => %{name: "asker", description: "needs approvals", system_prompt: "", model: "m-asker", tools: [check_secret()]},
      "peer" => %{name: "peer", description: "team member", system_prompt: "", model: "m-peer"},
      "looper" => %{name: "looper", description: "never finishes", system_prompt: "", model: "m-loop", tools: [lookup()]},
      "slow" => %{name: "slow", description: "slow worker", system_prompt: "", model: "m-slow"}
    }

    Enum.reduce(overrides, base, fn {k, v}, acc -> Map.update!(acc, k, &Map.merge(&1, v)) end)
  end

  defp new_rt(opts \\ %{}) do
    Runtime.new(Map.merge(%{llm_plug: &MockLlm.demo/1, registry: registry()}, Map.new(opts)))
  end

  test "S1/S2 task fan-out · context isolation · parallel · usage roll-up" do
    rt = new_rt()
    {:ok, coord} = Runtime.spawn_agent(rt, rt.root, "coordinator")
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

    # usage rolls up to parent (G3 ledger): own 2×40 + children 2×(2×40) = 240
    assert Runtime.snapshot(coord).usage_total == 240,
           "usage rolls up to parent, got #{Runtime.snapshot(coord).usage_total}"

    # children auto-closed after task
    coord_id = Runtime.snapshot(coord).id

    assert Enum.all?(Runtime.list(rt), fn h -> h.id == coord_id or h.state == :closed end),
           "children auto-closed after task"
  end

  test "S3 child suspends → parent's waitFor answers (nearest interpreter)" do
    rt =
      Runtime.new(%{
        llm_plug: &MockLlm.demo/1,
        registry: registry(%{"approverParent" => %{wait_for: fn req -> %{id: req.id, ok: true} end}})
      })

    {:ok, p} = Runtime.spawn_agent(rt, rt.root, "approverParent")
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
  end

  test "S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint" do
    rt = new_rt()
    {:ok, p} = Runtime.spawn_agent(rt, rt.root, "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    r1 = Runtime.wait(rt, p)

    # root run returned status=pending
    assert r1.status == "pending", "root run returned status=pending: #{r1.status}"
    # Request carries the handle PATH to the leaf
    assert "approverParent.1" in (r1.pending.path || []), "Request carries the handle PATH to the leaf"
    # both levels parked (suspended), zero tokens burning
    assert Enum.all?(Runtime.list(rt), &(&1.state == :suspended)), "both levels parked (suspended)"

    before = Enum.find(Runtime.list(rt), &String.contains?(&1.id, "asker")).tokens
    Runtime.resume(rt, %{id: r1.pending.id, ok: true})
    tr = Enum.join(Runtime.trace(rt), "\n")

    # leaf resumed AT checkpoint (prior turns preserved)
    assert tr =~ "resume with Answer(ok=true) at checkpoint", "leaf resumed AT checkpoint"
    # parent cascade REATTACHED to the finished child (no re-execution)
    assert tr =~ "task replay → REATTACH", "parent cascade REATTACHED to the finished child"
    # parent reached done after cascade
    assert Runtime.snapshot(p).state == :idle, "parent reached done after cascade: #{Runtime.snapshot(p).state}"
    # child did not restart from scratch (usage grew, not reset)
    assert Enum.find(Runtime.list(rt), &String.contains?(&1.id, "asker")).tokens > before,
           "child did not restart from scratch (usage grew, not reset)"
  end

  test "S5 unsolicited rail: posts coalesce into ONE turn, provenance marked, ticks dedupe" do
    rt = new_rt()
    {:ok, peer} = Runtime.spawn_agent(rt, rt.root, "peer")
    Runtime.post(rt, peer, %{from: "root/coordinator.1", channel: "peer", text: "update 1"})
    Runtime.post(rt, peer, %{from: "external", channel: "external", text: "webhook payload"})
    for _ <- 1..3, do: Runtime.post(rt, peer, %{from: "clock", channel: "timer", text: "tick"})
    Runtime.wake(rt, peer)
    r = Runtime.wait(rt, peer)

    # one wake = ONE turn for all items
    assert r.turns == 1, "one wake = ONE turn for all items, got #{r.turns}"
    # 2 messages + 3 ticks coalesced to 3 items
    assert r.text == "processed 3 items", "2 messages + 3 ticks coalesced to 3 items: #{r.text}"
  end

  test "S6 backpressure: loud inbox reject · per-parent concurrency queue · global turn gate" do
    rt = new_rt(%{inbox_cap: 2})
    {:ok, peer} = Runtime.spawn_agent(rt, rt.root, "peer")
    Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "1"})
    Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "2"})
    third = Runtime.post(rt, peer, %{from: "a", channel: "peer", text: "3"})

    # inbox gate: third post REJECTED loudly to sender
    assert third.ok == false and third.is_error =~ "inbox full", "inbox gate: third post REJECTED loudly"

    rt2 =
      Runtime.new(%{
        llm_plug: &MockLlm.demo/1,
        registry: registry(%{"coordinator" => %{budget: %{max_concurrent: 1}}})
      })

    {:ok, c2} = Runtime.spawn_agent(rt2, rt2.root, "coordinator")
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

    rt3 = new_rt(%{max_concurrent_turns: 1})
    {:ok, c3} = Runtime.spawn_agent(rt3, rt3.root, "coordinator")
    Runtime.wake(rt3, c3, "go")
    Runtime.wait(rt3, c3)

    # global turn gate: with cap 1, max observed concurrent turns = 1
    assert Runtime.max_observed_concurrent_turns(rt3) == 1,
           "global turn gate: max=#{Runtime.max_observed_concurrent_turns(rt3)}"
  end

  test "S7 budgets: hierarchical carve · exhaustion = incomplete (never crash) · maxTurns loud" do
    rt = new_rt()
    {:ok, c} = Runtime.spawn_agent(rt, rt.root, "coordinator", %{max_tokens: 100, max_children: 2})
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
    # pool nearly gone: sibling still ran (20 left > 0)
    assert r2.status == "done", "pool nearly gone: sibling still ran"

    r3 = Runtime.run_turn(rt, kid2, "again")

    # pool exhausted → status=incomplete, partial work preserved, NO crash
    assert r3.status == "incomplete" and r3.text =~ "budget exhausted",
           "pool exhausted → status=incomplete: #{r3.status}"

    rt4 =
      Runtime.new(%{
        llm_plug: &MockLlm.demo/1,
        registry: registry(%{"looper" => %{budget: %{max_turns: 3}}})
      })

    {:ok, lp} = Runtime.spawn_agent(rt4, rt4.root, "looper")
    Runtime.wake(rt4, lp, "loop forever")
    rl = Runtime.wait(rt4, lp)

    # maxTurns cap is LOUD: status=incomplete (not silent done)
    assert rl.status == "incomplete", "maxTurns cap is LOUD: #{rl.status}"
  end

  test "S8 interrupt = turn-abort → idle (inbox intact); NOT a kill" do
    rt = new_rt()
    {:ok, s} = Runtime.spawn_agent(rt, rt.root, "slow")
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
    # inbox survived the interrupt
    assert length(Runtime.snapshot(s).inbox) == 1, "inbox survived the interrupt"
  end

  test "S9 stop-all: close(root) cascades leaf-first; closed handles reject posts" do
    test_pid = self()
    cb = fn snap, _reason -> send(test_pid, {:closed, snap.id}) end

    rt =
      Runtime.new(%{
        llm_plug: &MockLlm.demo/1,
        registry: registry(%{"coordinator" => %{on_close: cb}, "peer" => %{on_close: cb}})
      })

    {:ok, c} = Runtime.spawn_agent(rt, rt.root, "coordinator")
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
  end
end
