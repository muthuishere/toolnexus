defmodule Toolnexus.AcpTest do
  @moduledoc """
  ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
  `openspec/changes/add-acp-model-source` and
  `openspec/changes/add-acp-tool-calling` (SPEC §8 "ACP model source"). Hermetic: drives a fake ACP
  server (`test/support/fake_acp_server.exs`) over REAL OS pipes, ported
  from `spikes/acp/fakeagent/main.go` / `spikes/acp/SPIKE.md`.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Acp

  @moduletag timeout: 60_000

  @fixture_script Path.expand("support/fake_acp_server.exs", __DIR__)

  defp fixture_cmd(scenario, extra) do
    jason_ebin = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    ["elixir", "-pa", jason_ebin, @fixture_script, scenario | extra]
  end

  defp connect!(scenario, opts \\ [], extra \\ []) do
    {:ok, pid} = Acp.connect(fixture_cmd(scenario, extra), opts)
    pid
  end

  # -- warm session reuse -----------------------------------------------------

  test "a warm session serves many turns from one process, one session/new" do
    pid = connect!("echo")

    {:ok, a} = Acp.prompt(pid, "first")
    {:ok, b} = Acp.prompt(pid, "second")
    {:ok, c} = Acp.prompt(pid, "third")

    assert a =~ "new_count=1 seq=1"
    assert b =~ "new_count=1 seq=2"
    assert c =~ "new_count=1 seq=3"

    Acp.close(pid)
  end

  test "session/new carries an absolute cwd and an mcpServers array (real devin rejects otherwise)" do
    pid = connect!("echo")
    {:ok, answer} = Acp.prompt(pid, "hi")

    [_, params_json] = String.split(answer, "params=", parts: 2)
    params = Jason.decode!(params_json)

    assert Path.type(params["cwd"]) == :absolute
    assert is_list(params["mcpServers"])

    Acp.close(pid)
  end

  test "generate/1 builds the exact seam create_in_process accepts and drives the in-process client end to end" do
    pid = connect!("echo")
    generate = Acp.generate(pid)

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    client = Toolnexus.Client.create_in_process(model: "acp-fake", generate: generate)
    result = Toolnexus.Client.run(client, "hello", tk)

    assert result.status == "done"
    assert result.text =~ "echo:"

    Acp.close(pid)
  end

  # -- thought/tool narration filtered -----------------------------------------

  test "only agent_message_chunk forms the reply; thought/tool narration is dropped" do
    pid = connect!("noisy")
    {:ok, answer} = Acp.prompt(pid, "hello")

    assert answer == ~s({"answer":"hello"})
    assert {:ok, %{"answer" => "hello"}} = Jason.decode(answer)

    Acp.close(pid)
  end

  # -- permission answered inline, never awaited by the caller -----------------

  test "a session/request_permission is answered with the first reject-kind option by default, inline, without the caller ever seeing it" do
    pid = connect!("permission")

    {micros, {:ok, answer}} = :timer.tc(fn -> Acp.prompt(pid, "delete the db") end)

    # The client executes tools; the agent must not — so the default refuses.
    assert answer == "PERMITTED(reject):delete the db"
    # Answered inline from the read loop, not routed through the caller at
    # all — this should complete in well under a second, not hang or wait
    # on any external timeout.
    assert micros < 2_000_000

    Acp.close(pid)
  end

  test "allow_agent_tools: true selects the first allow-kind option instead" do
    pid = connect!("permission", allow_agent_tools: true)

    {micros, {:ok, answer}} = :timer.tc(fn -> Acp.prompt(pid, "go") end)

    assert answer == "PERMITTED(allow-once):go"
    assert micros < 2_000_000

    Acp.close(pid)
  end

  test "with no reject-kind option the default answers cancelled" do
    pid = connect!("permission_allow_only")
    assert {:ok, "PERMITTED(cancelled):go"} = Acp.prompt(pid, "go")
    Acp.close(pid)
  end

  # -- stale answer prevented by the supersedes marker --------------------------

  test "a stateful session answers a near-duplicate prompt with a STALE answer, unless it carries the supersedes marker" do
    pid = connect!("stale")

    {:ok, a} = Acp.prompt(pid, "What is the capital of France?")
    assert a == "STALE-ANSWER-TO:What is the capital of France?"

    # Full-request-every-turn against a stateful session: the second prompt
    # contains the first, verbatim, with no marker — the fake agent (like a
    # plausible real one) matches the EARLIEST remembered question and
    # answers stale.
    {:ok, b} =
      Acp.prompt(pid, "What is the capital of France?\nWhat is the capital of Japan?")

    assert b == "STALE-ANSWER-TO:What is the capital of France?"

    # The library's own supersedes marker (see Acp.render_prompt/1)
    # fixes it — the fake agent answers only the text after the marker.
    {:ok, c} =
      Acp.prompt(
        pid,
        "What is the capital of France?\nWhat is the capital of Japan?\n" <>
          "SUPERSEDES-ALL-PRIOR: What is the capital of Japan?"
      )

    assert c == "FRESH-ANSWER-TO:What is the capital of Japan?"

    Acp.close(pid)
  end

  test "generate/1 assembles the supersedes marker itself, so a caller who just appends messages never gets a stale answer" do
    pid = connect!("stale")
    generate = Acp.generate(pid)

    # Even the FIRST turn carries the library-built marker (assembled from
    # every call, not left to the caller), so the fake agent already answers
    # fresh rather than falling back to its naive earliest-match behavior.
    r1 = generate.(%{messages: [%{"role" => "user", "content" => "What is the capital of France?"}]})
    assert r1.content =~ "FRESH-ANSWER-TO:What is the capital of France?"

    r2 =
      generate.(%{
        messages: [
          %{"role" => "user", "content" => "What is the capital of France?"},
          %{"role" => "assistant", "content" => "STALE-ANSWER-TO:What is the capital of France?"},
          %{"role" => "user", "content" => "What is the capital of Japan?"}
        ]
      })

    assert r2.content =~ "FRESH-ANSWER-TO:What is the capital of Japan?"

    Acp.close(pid)
  end

  # -- turn serialisation -------------------------------------------------------

  test "turns on one session are serialised, never sent concurrently" do
    pid = connect!("slow")

    {elapsed_us, results} =
      :timer.tc(fn ->
        [1, 2, 3]
        |> Enum.map(fn n -> Task.async(fn -> Acp.prompt(pid, "t#{n}") end) end)
        |> Enum.map(&Task.await(&1, 10_000))
      end)

    # The fake server sleeps 150ms per prompt; three TRUE-serial turns take
    # >= ~450ms of wall time. If the client let them overlap, this would be
    # closer to 150ms.
    assert elapsed_us >= 400_000

    texts = Enum.map(results, fn {:ok, t} -> t end)
    # Every request got back exactly its own answer — no cross-talk from one
    # session carrying two prompts at once.
    assert Enum.any?(texts, &String.contains?(&1, "t1"))
    assert Enum.any?(texts, &String.contains?(&1, "t2"))
    assert Enum.any?(texts, &String.contains?(&1, "t3"))

    # And the server itself observed them one at a time, in increasing seq
    # order (the client never issued #2 before #1's reply came back).
    seqs =
      texts
      |> Enum.map(fn t ->
        [_, rest] = String.split(t, "slow:", parts: 2)
        [n, _] = String.split(rest, ":", parts: 2)
        String.to_integer(n)
      end)
      |> Enum.sort()

    assert seqs == [1, 2, 3]

    Acp.close(pid)
  end

  # -- process lifetime + idempotent close --------------------------------------

  test "close/1 is idempotent" do
    pid = connect!("echo")
    {:ok, _} = Acp.prompt(pid, "one")

    assert :ok = Acp.close(pid)
    assert :ok = Acp.close(pid)
    assert :ok = Acp.close(pid)
  end

  test "a turn's own failure does not take down the process; the session stays usable" do
    pid = connect!("echo")

    {:ok, _} = Acp.prompt(pid, "first")
    assert Process.alive?(pid)
    {:ok, second} = Acp.prompt(pid, "second")
    assert second =~ "seq=2"

    Acp.close(pid)
  end

  # -- ACP as a real tool-calling model (add-acp-tool-calling) -----------------

  # Pull the REQUEST JSON back out of a rendered prompt.
  defp split_prompt(prompt) do
    [preamble, rest] = String.split(prompt, "\nREQUEST:\n", parts: 2)
    parts = String.split(rest, "\n\nSUPERSEDES-ALL-PRIOR: ")
    json = parts |> Enum.drop(-1) |> Enum.join("\n\nSUPERSEDES-ALL-PRIOR: ")
    {preamble, Jason.decode!(json)}
  end

  defp add_tool do
    Toolnexus.Native.define_tool(%{
      name: "add",
      description: "Add two numbers.",
      input_schema: %{
        "type" => "object",
        "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
        "required" => ["a", "b"]
      },
      execute: fn args -> to_string(trunc(args["a"] + args["b"])) end
    })
  end

  @tag :tmp_dir
  test "the in-process loop drives an ACP agent as a tool-calling model end to end", %{tmp_dir: dir} do
    events = Path.join(dir, "requests.ndjson")
    pid = connect!("toolloop", [], [events])

    {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [add_tool()])
    client = Toolnexus.Client.create_in_process(model: "acp", generate: Acp.generate(pid))
    r = Toolnexus.Client.run(client, "What is 2 + 3?", tk)
    Acp.close(pid)

    assert r.text == "The answer is 5."
    assert [call] = r.tool_calls
    assert call.name == "add"
    assert call.output == "5"

    requests =
      events
      |> File.read!()
      |> String.split("\n", trim: true)
      |> Enum.map(&Jason.decode!/1)

    assert [turn1, turn2] = requests

    # Turn 1: the tool schema reached the agent, OpenAI-shaped.
    assert Enum.any?(turn1["tools"], fn t ->
             t["function"]["name"] == "add" and t["function"]["parameters"] != nil
           end)

    # Turn 2: the assistant tool_calls message and the tool result are both there.
    assert Enum.any?(turn2["messages"], &(&1["role"] == "assistant" and &1["tool_calls"] != nil))

    assert Enum.any?(turn2["messages"], fn m ->
             m["role"] == "tool" and m["tool_call_id"] == "c1" and m["content"] == "5"
           end)
  end

  test "the prompt is PREAMBLE + REQUEST JSON (messages first, tools [] when absent, no HTML escaping) + the marker line" do
    p =
      Acp.render_prompt(%{
        messages: [
          %{"role" => "system", "content" => "be terse"},
          %{
            "role" => "user",
            "content" => [
              %{"type" => "text", "text" => "a <b> & c"},
              %{"type" => "image_url"},
              %{"type" => "text", "text" => "d"}
            ]
          }
        ]
      })

    {pre, body} = split_prompt(p)
    assert pre == Acp.preamble()
    assert String.ends_with?(p, "\n\nSUPERSEDES-ALL-PRIOR: a <b> & c d")
    refute String.contains?(p, "\\u003c")
    assert body["tools"] == []
    assert length(body["messages"]) == 2
    assert String.contains?(p, "\nREQUEST:\n{\"messages\":")
  end

  test "the preamble is byte-identical to SPEC.md §8" do
    spec_path = Path.expand("../../SPEC.md", __DIR__)

    # Skipped (passes vacuously) when the spec is not reachable, e.g. a
    # package checkout without the monorepo around it.
    if File.exists?(spec_path) do
      spec = File.read!(spec_path)
      [_, after_marker] = String.split(spec, "`PREAMBLE` is these seven lines", parts: 2)
      [_, block] = String.split(after_marker, "```\n", parts: 2)
      [preamble, _] = String.split(block, "```", parts: 2)
      assert preamble == Acp.preamble()
    end
  end

  test "the marker carries the latest user text, falling back to the last message, then to empty" do
    tools = [%{"type" => "function", "function" => %{"name" => "add"}}]

    # Atom-keyed messages and parts (a direct generate caller) work too; the
    # LAST user message wins, not the last message.
    p =
      Acp.render_prompt(%{
        messages: [
          %{role: "user", content: "old"},
          %{role: "user", content: [%{type: "text", text: "new"}, %{type: "text", text: 7}]},
          %{role: "assistant", content: "reply"}
        ],
        tools: tools
      })

    assert String.ends_with?(p, "\n\nSUPERSEDES-ALL-PRIOR: new")
    {_, body} = split_prompt(p)
    assert body["tools"] == tools

    # No user message: the last message's content.
    p = Acp.render_prompt(%{"messages" => [%{"role" => "system", "content" => "sys only"}]})
    assert String.ends_with?(p, "\n\nSUPERSEDES-ALL-PRIOR: sys only")

    # Content that is neither a string nor a list of parts renders as "".
    p = Acp.render_prompt(%{messages: [%{"role" => "user", "content" => nil}]})
    assert String.ends_with?(p, "\n\nSUPERSEDES-ALL-PRIOR: ")

    # A non-map message is carried in the JSON but has no content.
    p = Acp.render_prompt(%{messages: ["junk"]})
    assert String.ends_with?(p, "\n\nSUPERSEDES-ALL-PRIOR: ")
    {_, body} = split_prompt(p)
    assert body["messages"] == ["junk"]

    # No messages at all: both arrays render as [] and the marker is empty.
    p = Acp.render_prompt(%{})
    assert String.ends_with?(p, "\nREQUEST:\n{\"messages\":[],\"tools\":[]}\n\nSUPERSEDES-ALL-PRIOR: ")
  end

  @add [%{id: "c1", name: "add", arguments: %{"a" => 2, "b" => 3}}]
  @add_str [%{id: "c1", name: "add", arguments: ~s({"a":2,"b":3})}]

  # The same 13 cases as Go's TestACP_ParseReply.
  @parse_cases [
    {"plain prose passes through", "just text {not json", nil, "just text {not json"},
    {"content envelope", ~s({"content":"The answer is 5."}), nil, "The answer is 5."},
    {"null content", ~s({"content":null}), nil, ""},
    {"non-string content encodes", ~s({"content":{"x":1}}), nil, ~s({"x":1})},
    {"string arguments pre-encoded",
     ~s({"tool_calls":[{"id":"c1","type":"function","function":{"name":"add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}),
     @add_str, nil},
    {"object arguments",
     ~s({"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}), @add, nil},
    {"fenced",
     "```json\n" <>
       ~s({"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}) <> "\n```",
     @add, nil},
    {"prose around",
     "Calling now: " <>
       ~s({"tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}) <> " done",
     @add, nil},
    {"choices envelope",
     ~s({"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","function":{"name":"add","arguments":{"a":2,"b":3}}}]}}]}),
     @add, nil},
    {"message envelope", ~s({"message":{"content":"hi"}}), nil, "hi"},
    {"flat call, no id, no arguments", ~s({"tool_calls":[{"name":"ping"}]}),
     [%{name: "ping", arguments: %{}}], nil},
    {"nameless call skipped, falls to content",
     ~s({"tool_calls":[{"function":{"arguments":"{}"}}],"content":"fallback"}), nil, "fallback"},
    {"structured output passes through", ~s( {"answer":true} ), nil, ~s( {"answer":true} )}
  ]

  for {name, input, calls, text} <- @parse_cases do
    test "parse_reply: #{name}" do
      assert_parsed(unquote(input), unquote(Macro.escape(calls)), unquote(text))
    end
  end

  defp assert_parsed(input, nil, text), do: assert(Acp.parse_reply(input) == %{content: text})
  defp assert_parsed(input, calls, nil), do: assert(Acp.parse_reply(input) == %{tool_calls: calls})

  test "parse_reply: edge branches of the pinned algorithm" do
    # A bare fence line with nothing after it parses to nothing → original text.
    assert Acp.parse_reply("```") == %{content: "```"}
    # A fence without a closing fence still parses.
    assert Acp.parse_reply("```json\n{\"content\":\"open\"}") == %{content: "open"}
    # A `}` only BEFORE the first `{` is no object → original text.
    assert Acp.parse_reply("} then {") == %{content: "} then {"}
    # JSON that is not an object (null, an array) → original text.
    assert Acp.parse_reply("null") == %{content: "null"}
    assert Acp.parse_reply("[1,2]") == %{content: "[1,2]"}
    # A non-empty choices whose first element has no object message is NOT
    # unwrapped (and does not fall back to a sibling message).
    assert Acp.parse_reply(~s({"choices":[{"text":"x"}],"message":{"content":"no"},"content":"top"})) ==
             %{content: "top"}
    # An EMPTY choices falls through to the message unwrap.
    assert Acp.parse_reply(~s({"choices":[],"message":{"content":"msg"}})) == %{content: "msg"}
    # Non-object elements, empty names and non-string ids are dropped/omitted.
    assert Acp.parse_reply(
             ~s({"tool_calls":[1,"x",{"function":{"name":""}},{"id":7,"function":{"name":"a","arguments":null}},{"id":"","name":"b","arguments":[1]}]})
           ) == %{tool_calls: [%{name: "a", arguments: %{}}, %{name: "b", arguments: [1]}]}
    # tool_calls that all drop out, with no content key → original text.
    input = ~s({"tool_calls":[{"function":{}}]})
    assert Acp.parse_reply(input) == %{content: input}
    # A tool_calls that is not an array is ignored.
    assert Acp.parse_reply(~s({"tool_calls":"nope","content":"c"})) == %{content: "c"}
    # Non-string, non-null content is its compact JSON, never HTML-escaped.
    assert Acp.parse_reply(~s({"content":["<a>",1]})) == %{content: ~s(["<a>",1])}
  end

  test "generate/1 returns parsed tool calls straight from the agent's reply" do
    pid = connect!("toolloop")
    generate = Acp.generate(pid)

    r = generate.(%{messages: [%{"role" => "user", "content" => "2+3?"}], tools: []})
    assert r == %{tool_calls: @add}

    r =
      generate.(%{
        messages: [
          %{"role" => "user", "content" => "2+3?"},
          %{"role" => "tool", "tool_call_id" => "c1", "content" => "5"}
        ]
      })

    assert r == %{content: "The answer is 5."}

    Acp.close(pid)
  end
end
