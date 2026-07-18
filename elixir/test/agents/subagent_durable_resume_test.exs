defmodule Toolnexus.Agents.DurableResumeTest do
  @moduledoc """
  §7D S4 — durable pending at the root (`data.path` to the leaf), resume at the
  checkpoint, parent cascade REATTACHING by task key.
  Shared fixture: `examples/subagent-durable-resume/fixture.json`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.Runtime
  alias Toolnexus.Client.InMemoryConversationStore
  import Toolnexus.TestAgentHelpers

  test "S4 no interpreter anywhere → durable pending; resume() cascades from checkpoint" do
    rt = new_rt()
    {:ok, p} = Runtime.spawn_agent(rt, Runtime.root(rt), "approverParent")
    Runtime.wake(rt, p, "do the secret thing")
    r1 = Runtime.wait(rt, p)

    # root run returned status=pending
    assert r1.status == "pending", "root run returned status=pending: #{r1.status}"
    # Request carries the handle PATH in data.path (§10 addendum — the ONE portable slot)
    assert "approverParent.1" in (r1.pending.data["path"] || []), "Request carries the handle PATH to the leaf"
    # both levels parked (suspended), zero tokens burning
    assert Enum.all?(Runtime.list(rt), &(&1.state == :suspended)), "both levels parked (suspended)"

    # the store holds the PRE-TURN checkpoint: a suspended turn commits nothing —
    # resume replays the whole turn; idempotency comes from task-key reattachment,
    # never from a halted placeholder visible in history
    store = Runtime.conversation_store(rt)
    assert InMemoryConversationStore.get(store, "root/approverParent.1") == nil,
           "suspended parent turn did not advance the stored transcript"

    assert InMemoryConversationStore.get(store, "root/approverParent.1/asker.1") == nil,
           "suspended leaf turn did not advance the stored transcript"

    before = Enum.find(Runtime.list(rt), &String.contains?(&1.id, "asker")).tokens
    Runtime.resume(rt, %{id: r1.pending.id, ok: true})
    tr = Runtime.trace(rt)
    joined = Enum.join(tr, "\n")

    # leaf resumed AT checkpoint (prior turns preserved)
    assert joined =~ "resume with Answer(ok=true) at checkpoint", "leaf resumed AT checkpoint"
    # parent cascade REATTACHED to the finished child (no re-execution)
    assert joined =~ "task replay → REATTACH", "parent cascade REATTACHED to the finished child"
    # parent reached done after cascade
    assert Runtime.snapshot(p).state == :idle, "parent reached done after cascade: #{Runtime.snapshot(p).state}"
    # child did not restart from scratch (usage grew, not reset)
    assert Enum.find(Runtime.list(rt), &String.contains?(&1.id, "asker")).tokens > before,
           "child did not restart from scratch (usage grew, not reset)"

    # no duplicate child id appears in the trace (fixture: noDuplicateChildIds)
    refute Enum.any?(tr, &String.contains?(&1, "asker.2")), "no duplicate child spawned on resume"

    # fixture `phase2_expect.transitions` + the SPEC §7D durable-resume pin:
    # durable resume traces suspended→idle (Answer accepted, checkpoint restored)
    # then idle→running (the replay wake). NOTE: the shared fixture predates the
    # pin and omits the suspended→idle hop — SPEC.md is authoritative here.
    assert transitions(tr, "root/approverParent.1") ==
             ["idle→running", "running→suspended", "suspended→idle", "idle→running", "running→idle"]

    assert transitions(tr, "root/approverParent.1/asker.1") ==
             ["idle→running", "running→suspended", "suspended→idle", "idle→running", "running→idle", "idle→closed"]

    # after resume, the COMPLETED turns are committed — transcripts survive
    assert is_list(InMemoryConversationStore.get(store, "root/approverParent.1")),
           "resumed parent turn committed its transcript"

    Runtime.shutdown(rt)
  end
end
