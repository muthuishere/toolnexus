defmodule Toolnexus.Agents.CompactionTest do
  @moduledoc """
  Context compaction (SPEC §7F) — the C1–C6 acceptance scenarios (11 checks), ported
  from `js/spike/compaction-demo.ts` and asserted against the shared
  `examples/compaction/fixture.json` transcript shape:

    * C1 — no-op below budget (byte-identical)
    * C2 — summarize head, keep tail, above budget
    * C3 — tool-pair safety: no orphaned tool result; tail begins at a user turn
    * C4 — leading system prompt preserved verbatim
    * C5 — flush_to_memory injects a pre-compact reminder
    * C6 — wired via before_llm in a real run through the shipped client loop

  The transcript is generated deterministically from the fixture (system prompt +
  `userTurns` groups of user / assistant+tool_calls / tool / assistant), so this port
  produces the same bytes as every other.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Agents.Compaction
  alias Toolnexus.{Client, Native}

  @repo_root Path.expand("../../..", __DIR__)
  @fixture Path.join(@repo_root, "examples/compaction/fixture.json")

  # Build the transcript exactly as examples/compaction/fixture.json prescribes.
  defp transcript(user_turns) do
    fix = @fixture |> File.read!() |> Jason.decode!()
    system_prompt = fix["input"]["systemPrompt"]
    pad = String.duplicate("pad ", 40)
    data = String.duplicate("data ", 40)

    groups =
      Enum.flat_map(0..(user_turns - 1), fn i ->
        [
          %{"role" => "user", "content" => "question #{i} #{pad}"},
          %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{"id" => "c#{i}", "type" => "function", "function" => %{"name" => "lookup", "arguments" => "{}"}}
            ]
          },
          %{"role" => "tool", "tool_call_id" => "c#{i}", "content" => "result #{i} #{data}"},
          %{"role" => "assistant", "content" => "answer #{i}"}
        ]
      end)

    [%{"role" => "system", "content" => system_prompt} | groups]
  end

  defp summarize(older), do: "summarized #{length(older)} messages"

  defp ev(messages), do: %{messages: messages, tools: [], model: "m", turn: 0}

  # C1 -------------------------------------------------------------------------
  test "C1: below budget → no-op (byte-identical)" do
    hook = Compaction.compactor(max_tokens: 100_000, summarize: &summarize/1)
    assert hook.(ev(transcript(3))) == nil
  end

  # C2 -------------------------------------------------------------------------
  test "C2: above budget → summarize head, keep tail" do
    msgs = transcript(30)
    before = Compaction.estimate_tokens(msgs)
    hook = Compaction.compactor(max_tokens: 2000, keep_tail: 800, summarize: &summarize/1)
    out = hook.(ev(msgs))

    assert %{messages: result} = out
    assert Compaction.estimate_tokens(result) < before

    assert Enum.any?(result, fn m ->
             String.starts_with?(to_string(m["content"]), "[Summary of earlier conversation]")
           end)
  end

  # C3 -------------------------------------------------------------------------
  test "C3: tool-pair safety — no orphaned tool result; tail begins at a user turn" do
    hook = Compaction.compactor(max_tokens: 2000, keep_tail: 800, summarize: &summarize/1)
    %{messages: t} = hook.(ev(transcript(30)))

    safe? =
      t
      |> Enum.with_index()
      |> Enum.all?(fn
        {%{"role" => "tool", "tool_call_id" => id}, i} ->
          t
          |> Enum.take(i)
          |> Enum.any?(fn m ->
            m["role"] == "assistant" and
              Enum.any?(m["tool_calls"] || [], &(&1["id"] == id))
          end)

        _ ->
          true
      end)

    assert safe?, "a tool message was orphaned from its tool_calls"

    first_non_system = Enum.find(t, &(&1["role"] != "system"))
    assert first_non_system["role"] == "user"
  end

  # C4 -------------------------------------------------------------------------
  test "C4: leading system prompt preserved verbatim" do
    hook = Compaction.compactor(max_tokens: 2000, summarize: &summarize/1)
    %{messages: [first | _]} = hook.(ev(transcript(30)))
    assert first["role"] == "system"
    assert String.contains?(first["content"], "SOUL")
  end

  # C5 -------------------------------------------------------------------------
  test "C5: flush_to_memory injects a pre-compact reminder" do
    hook = Compaction.compactor(max_tokens: 2000, summarize: &summarize/1, flush_to_memory: true)
    %{messages: result} = hook.(ev(transcript(30)))

    assert Enum.any?(result, fn m ->
             String.contains?(to_string(m["content"]), "save it with the memory tool")
           end)
  end

  # --- coverage: edge paths ---------------------------------------------------

  test "keep_tail too small for any group → falls back to the most recent user turn (safety over size)" do
    msgs = transcript(30)
    # keep_tail = 1 fits no user-boundary tail, so the split rule falls back to the
    # last user turn; a tool result must still never be orphaned.
    hook = Compaction.compactor(max_tokens: 2000, keep_tail: 1, summarize: &summarize/1)
    %{messages: t} = hook.(ev(msgs))

    # first non-system message is a user turn
    assert Enum.find(t, &(&1["role"] != "system"))["role"] == "user"
    # summary present, and the older body was actually compacted (result smaller)
    assert Enum.any?(t, &String.starts_with?(to_string(&1["content"]), "[Summary"))
    assert Compaction.estimate_tokens(t) < Compaction.estimate_tokens(msgs)
  end

  test "nothing safely compactible above the preserved head → no-op (nil)" do
    # A transcript that is only the preserved system head: there is no conversational
    # body to summarize, so even 'over budget' the hook is a no-op.
    msgs = [%{"role" => "system", "content" => "You are Ava. SOUL."}]

    hook = Compaction.compactor(max_tokens: 0, keep_tail: 100_000, summarize: &summarize/1)
    assert hook.(ev(msgs)) == nil
  end

  test "count_tokens override replaces the default estimator" do
    msgs = transcript(3)
    # Force 'over budget' via a custom counter so compaction fires on a small transcript.
    hook =
      Compaction.compactor(
        max_tokens: 5,
        keep_tail: 2,
        summarize: &summarize/1,
        count_tokens: fn m -> length(m) end
      )

    assert %{messages: result} = hook.(ev(msgs))
    assert Enum.any?(result, &String.starts_with?(to_string(&1["content"]), "[Summary"))
  end

  test "estimate_tokens sums ceil(byte_size(json)/4) per message" do
    m = %{"role" => "user", "content" => "hello"}
    # Jason.encode! is compact, no HTML escaping, no trailing newline — byte-length parity with JS.
    expected = ceil(byte_size(Jason.encode!(m)) / 4)
    assert Compaction.estimate_tokens([m]) == expected
  end

  # C6 -------------------------------------------------------------------------
  test "C6: wired via before_llm — the loop keeps going past a compaction" do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    transport = fn req ->
      n = Agent.get_and_update(calls, &{&1, &1 + 1})

      compacted =
        Enum.any?(req.body["messages"], fn m ->
          String.starts_with?(to_string(m["content"] || ""), "[Summary")
        end)

      message =
        if n >= 6 do
          %{"role" => "assistant", "content" => "final (compacted=#{compacted})"}
        else
          %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{"id" => "c#{n}", "type" => "function", "function" => %{"name" => "pad", "arguments" => "{}"}}
            ]
          }
        end

      body = %{
        "choices" => [%{"message" => message}],
        "usage" => %{"prompt_tokens" => 50, "completion_tokens" => 10, "total_tokens" => 60}
      }

      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    pad =
      Native.define_tool(
        name: "pad",
        description: "pads",
        execute: fn _args -> String.duplicate("x ", 600) end
      )

    client =
      Client.create(
        base_url: "http://mock.local",
        style: "openai",
        model: "m",
        api_key: "spike",
        max_turns: 20,
        transport: transport,
        system_prompt: "You are Ava. SOUL.",
        hooks: %{before_llm: Compaction.compactor(max_tokens: 600, keep_tail: 250, summarize: &summarize/1)}
      )

    r = Client.run(client, "start", [pad])

    assert r.status == "done" and String.starts_with?(r.text, "final"), r.text
    assert String.contains?(r.text, "compacted=true"), r.text
    assert Compaction.estimate_tokens(r.messages) < 4000
  end
end
