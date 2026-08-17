defmodule Toolnexus.HarnessLoopTest do
  @moduledoc """
  Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).

  Hermetic — the `:transport` seam replays scripted assistant messages, so no
  network and no key. Mirrors golang/agents/loop_test.go, js/test/loop.test.ts,
  python/tests/test_harness_loop.py and the java/csharp suites case for case: the
  point of the change is that seven ports agree, and a test that exists in one
  port only is how that stops being true.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Agents
  alias Toolnexus.Agents.Loop

  # ---- the scripted "LLM" --------------------------------------------------

  defp scripted(messages) do
    {:ok, agent} = Agent.start_link(fn -> %{i: 0, models: []} end)

    transport = fn req ->
      state = Agent.get_and_update(agent, fn s ->
        {s, %{s | i: s.i + 1, models: s.models ++ [req.body["model"]]}}
      end)

      message = Enum.at(messages, min(state.i, length(messages) - 1))
      finish = if Map.has_key?(message, "tool_calls"), do: "tool_calls", else: "stop"

      {:ok,
       %{status: 200, headers: %{"content-type" => "application/json"},
         body: %{
           "choices" => [%{"index" => 0, "message" => message, "finish_reason" => finish}],
           "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
         }}}
    end

    {transport, fn -> Agent.get(agent, & &1.models) end}
  end

  defp say(content), do: %{"role" => "assistant", "content" => content}

  defp call_todo(todos) do
    %{"role" => "assistant",
      "tool_calls" => [%{"id" => "t1", "type" => "function",
        "function" => %{"name" => "todowrite", "arguments" => Jason.encode!(%{"todos" => todos})}}]}
  end

  defp todo(id, text, done), do: %{"id" => id, "text" => text, "completed" => done}

  defp base_opts(transport) do
    %{base_url: "http://scripted.invalid", style: "openai", model: "test-model",
      api_key: "unused", transport: transport}
  end

  defp todo_toolkit do
    {:ok, tk} =
      Toolnexus.create_toolkit(
        builtins: %{"tools" => %{
          "todowrite" => true, "bash" => false, "read" => false, "write" => false,
          "edit" => false, "glob" => false, "grep" => false, "webfetch" => false,
          "apply_patch" => false, "question" => false
        }}
      )

    tk
  end

  defp bare_toolkit do
    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    tk
  end

  # ---- the tests -----------------------------------------------------------

  test "harness/1 is the spec, not a wrapper" do
    spec = Loop.harness(does: "x", soul: "y")
    assert spec == [does: "x", soul: "y"]
  end

  test "absent completion and guardrails is unchanged" do
    {transport, _} = scripted([say("hello")])
    a = Agents.agent("plain", does: "answers")
    {out, _loop} = Loop.run(Agents.loop(a, base_opts(transport), bare_toolkit()), "hi")

    assert out.status == "done"
    assert out.text == "hello"
    assert out.attempts == 1
    assert out.stopped_by == nil, "a done run names no stop reason"
  end

  test "the gate blocks an open todo, then passes once closed" do
    # Attempt 1 must END with an open item: the client loops on tool calls, so a
    # closing todowrite in the same run would be judged and pass with no retry.
    {transport, _} =
      scripted([
        call_todo([todo("1", "draft", true), todo("2", "proofread", false)]),
        say("I think I am finished"),
        call_todo([todo("1", "draft", true), todo("2", "proofread", true)]),
        say("all done")
      ])

    a = Agents.agent("gated", does: "plans",
          completion: %{verify: &Loop.all_todos_done/1, max_attempts: 3})

    {out, _} = Loop.run(Agents.loop(a, base_opts(transport), todo_toolkit()), "do the thing")
    assert out.status == "done"
    assert out.attempts >= 2, "expected a retry, got #{out.attempts}"
  end

  test "an unverifiable run stops LOUDLY, bounded by max_attempts" do
    {transport, _} = scripted([say("done!")])

    a = Agents.agent("never", does: "never verifies",
          completion: %{verify: fn _ -> %{ok: false, reason: "always red"} end, max_attempts: 2})

    {out, _} = Loop.run(Agents.loop(a, base_opts(transport), bare_toolkit()), "go")
    assert out.status == "incomplete", "never a silent done"
    assert out.attempts == 2, "bounded by max_attempts"
    assert out.stopped_by =~ "always red"
    assert out.result.limit == "completion", "structured, so a caller can tell WHICH limit"
  end

  test "max_attempts is required, not defaulted" do
    {transport, _} = scripted([say("hi")])

    a = Agents.agent("bad", does: "x",
          completion: %{verify: fn _ -> %{ok: true, reason: ""} end, max_attempts: 0})

    assert_raise ArgumentError, ~r/max_attempts/, fn ->
      Loop.run(Agents.loop(a, base_opts(transport), bare_toolkit()), "go")
    end
  end

  test "no plan declared means the built-in verifier passes" do
    {transport, _} = scripted([say("answered without a plan")])

    a = Agents.agent("noplan", does: "x",
          completion: %{verify: &Loop.all_todos_done/1, max_attempts: 2})

    {out, _} = Loop.run(Agents.loop(a, base_opts(transport), todo_toolkit()), "go")
    assert out.status == "done", "the gate must not punish an agent for not using the builtin"
    assert out.attempts == 1
  end

  test "the gate judges ACCUMULATED work — a retry that drops the plan cannot escape" do
    # Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging
    # only the latest attempt would see "no plan" and pass.
    {transport, _} =
      scripted([call_todo([todo("1", "ship it", false)]), say("I am finished, honest")])

    a = Agents.agent("escaper", does: "x",
          completion: %{verify: &Loop.all_todos_done/1, max_attempts: 2})

    {out, _} = Loop.run(Agents.loop(a, base_opts(transport), todo_toolkit()), "go")
    assert out.status == "incomplete", "the earlier open plan must still be visible"
    assert out.stopped_by =~ "ship it"
  end

  test "guardrails: first deny wins, and a later guardrail cannot re-allow" do
    {:ok, seen} = Agent.start_link(fn -> 0 end)

    hooks =
      Loop.guarded_hooks(
        [
          fn ev -> if ev[:name] == "danger", do: "policy: no", else: "allow" end,
          fn _ -> Agent.update(seen, &(&1 + 1)); "allow" end
        ],
        nil
      )

    denied = hooks[:before_tool].(%{name: "danger", args: %{}, turn: 1})
    assert denied.result.is_error
    assert denied.result.output =~ "policy: no"
    assert Agent.get(seen, & &1) == 0, "a later guardrail never runs after a denial"

    assert hooks[:before_tool].(%{name: "safe", args: %{}, turn: 1}) == nil
    assert Agent.get(seen, & &1) == 1
  end

  test "guardrails run before an existing before_tool hook" do
    {:ok, prior} = Agent.start_link(fn -> 0 end)

    hooks =
      Loop.guarded_hooks(
        [fn ev -> if ev[:name] == "danger", do: "nope", else: "allow" end],
        %{before_tool: fn _ -> Agent.update(prior, &(&1 + 1)); nil end}
      )

    hooks[:before_tool].(%{name: "danger", args: %{}, turn: 1})
    assert Agent.get(prior, & &1) == 0, "denied => the prior hook is not reached"
    hooks[:before_tool].(%{name: "safe", args: %{}, turn: 1})
    assert Agent.get(prior, & &1) == 1, "allowed => the prior hook runs"
  end

  test "guardrails and the gate survive the registry projection" do
    child =
      Agents.agent("child",
        does: "does work",
        guardrails: [fn _ -> "denied by policy" end],
        completion: %{verify: &Loop.all_todos_done/1, max_attempts: 2}
      )

    def_ = Agents.registry(child)["child"]
    assert is_function(def_.hooks[:before_tool], 1), "the compiled guardrail must be projected"
    assert def_.completion.max_attempts == 2, "the gate travels with the agent"
  end

  test "a per-call model override reaches the wire; omitting it does not" do
    {transport, models} = scripted([say("a"), say("b")])
    loop = Agents.loop(Agents.agent("m", does: "x"), base_opts(transport), bare_toolkit())

    {_, loop} = Loop.run(loop, "one", model: "override-model")
    {_, _} = Loop.run(loop, "two")

    assert Enum.at(models.(), 0) == "override-model"
    assert Enum.at(models.(), 1) == "test-model", "the override does not persist"
  end

  test "turns accumulate across runs and status is observed" do
    {transport, _} = scripted([say("a"), say("b")])
    loop = Agents.loop(Agents.agent("t", does: "x"), base_opts(transport), bare_toolkit())
    assert loop.status == "idle"

    {_, loop} = Loop.run(loop, "one")
    after_first = loop.turns
    {_, loop} = Loop.run(loop, "two")

    assert loop.turns > after_first, "turns accumulate across runs"
    assert loop.status == "idle"
  end
end
