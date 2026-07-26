defmodule Toolnexus.Agents.AgentHooksTest do
  @moduledoc """
  Shared fixture: `examples/agent-hooks/fixture.json` (scenarios H1-H6).

  §7D "The §8 seams on an agent run": `:hooks` / `:on_metric` forwarded verbatim
  into each handle's client, resolved def-over-runtime (replace, never merge), so
  a §7F compactor reaches a long-lived agent. Unset leaves the run unchanged.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents.{Compaction, Runtime}
  alias Toolnexus.Client.InMemoryConversationStore
  import Toolnexus.TestAgentHelpers

  # A before_llm that reports it ran by messaging the test process.
  defp marker(tag) do
    me = self()

    %{
      before_llm: fn %{messages: _} ->
        send(me, {:hook, tag})
        nil
      end
    }
  end

  test "runtime-wide hooks reach an agent turn, and a rewrite survives the run" do
    me = self()

    hooks = %{
      before_llm: fn %{messages: messages} ->
        send(me, {:hook, :runtime})
        %{messages: messages ++ [%{"role" => "system", "content" => "INJECTED-BY-HOOK"}]}
      end
    }

    rt = new_rt(%{hooks: hooks})
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    r = Runtime.run_turn(rt, h, "1. hello")

    assert r.text != nil
    assert_received {:hook, :runtime}, "before_llm never ran inside the agent turn"

    stored = InMemoryConversationStore.get(Runtime.conversation_store(rt), "root/peer.1")

    assert Enum.any?(stored, &(&1["content"] == "INJECTED-BY-HOOK")),
           "the hook's transcript rewrite did not survive into the run"

    Runtime.shutdown(rt)
  end

  test "runtime-wide on_metric receives the §8 semantic events" do
    me = self()
    rt = new_rt(%{on_metric: fn ev -> send(me, {:metric, ev[:event] || ev["event"]}) end})
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.run_turn(rt, h, "1. hi")

    assert_received {:metric, "llm"}
    assert_received {:metric, "run"}

    Runtime.shutdown(rt)
  end

  test "a def's hooks REPLACE the runtime's for that agent only (never merged)" do
    reg = registry()
    reg = put_in(reg["peer"][:hooks], marker(:def_peer))

    rt = new_rt(%{hooks: marker(:runtime), registry: reg})
    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    {:ok, explore} = Runtime.spawn_agent(rt, Runtime.root(rt), "explore")

    Runtime.run_turn(rt, peer, "1. hi")
    assert_received {:hook, :def_peer}
    refute_received {:hook, :runtime}, "peer's def hook must REPLACE the runtime hook"

    Runtime.run_turn(rt, explore, "1. hi")
    assert_received {:hook, :runtime}, "explore has no def hook, so it inherits the runtime's"

    Runtime.shutdown(rt)
  end

  test "hooks and on_metric resolve INDEPENDENTLY (override one, inherit the other)" do
    me = self()
    reg = registry()
    reg = put_in(reg["peer"][:hooks], marker(:def_peer))

    rt =
      new_rt(%{
        registry: reg,
        hooks: marker(:runtime),
        on_metric: fn ev -> send(me, {:metric, ev[:event] || ev["event"]}) end
      })

    {:ok, peer} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    Runtime.run_turn(rt, peer, "1. hi")

    assert_received {:hook, :def_peer}

    assert_received {:metric, "run"},
                    "peer overrode :hooks only, so it must still inherit the runtime :on_metric"

    Runtime.shutdown(rt)
  end

  test "a real §7F compactor attached per-agent compacts an agent turn" do
    {:ok, store_agent} = Agent.start_link(fn -> %{} end)
    store = %InMemoryConversationStore{pid: store_agent}

    compactor =
      Compaction.compactor(
        max_tokens: 200,
        keep_tail: 80,
        summarize: fn _older -> "SUMMARY-OF-HEAD" end
      )

    reg = registry()
    reg = put_in(reg["peer"][:system_prompt], "SOUL-VERBATIM")
    reg = put_in(reg["peer"][:hooks], %{before_llm: compactor})

    rt = new_rt(%{registry: reg, store: store})
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")

    seeded =
      [%{"role" => "system", "content" => "SOUL-VERBATIM"}] ++
        Enum.flat_map(1..40, fn _ ->
          [
            %{"role" => "user", "content" => String.duplicate("q", 200)},
            %{"role" => "assistant", "content" => String.duplicate("a", 200)}
          ]
        end)

    InMemoryConversationStore.save(store, "root/peer.1", seeded)
    assert length(InMemoryConversationStore.get(store, "root/peer.1")) == 81

    Runtime.run_turn(rt, h, "1. next question")
    after_run = InMemoryConversationStore.get(store, "root/peer.1")

    assert length(after_run) < 81, "compaction did not shrink the transcript on an agent run"
    assert hd(after_run)["content"] == "SOUL-VERBATIM", "leading system prompt not verbatim"

    assert Enum.any?(after_run, &(&1["content"] =~ "SUMMARY-OF-HEAD")),
           "summary system message missing"

    Runtime.shutdown(rt)
  end

  test "a wrong-arity before_llm is LOUD, not silently skipped" do
    rt = new_rt(%{hooks: %{before_llm: fn _messages, _tools, _turn -> nil end}})
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")

    r = Runtime.run_turn(rt, h, "1. hi")

    assert r.is_error, "a wrong-arity hook must surface as an error, never run silently"
    assert r.text =~ "before_llm must be a 1-arity function"

    Runtime.shutdown(rt)
  end

  test "unset ⇒ the run is unchanged (no hooks, no sink)" do
    rt = new_rt()
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "peer")
    r = Runtime.run_turn(rt, h, "1. hello")

    assert r.status == "done"
    assert r.turns == 1
    stored = InMemoryConversationStore.get(Runtime.conversation_store(rt), "root/peer.1")
    assert length(stored) == 2

    Runtime.shutdown(rt)
  end
end
