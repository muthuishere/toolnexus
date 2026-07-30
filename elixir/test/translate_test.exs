defmodule Toolnexus.TranslateTest do
  @moduledoc """
  Single-turn translation suite (SPEC.md §11, ADR-0011). Ports `golang/translate_test.go` —
  Go's assertions are the cross-port oracle. Hermetic: a local Bandit stub stands in for the
  provider and records what it was actually sent.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Client
  alias Toolnexus.Translate

  # ---- local Bandit stub that records the request body ----

  defmodule Stub do
    @behaviour Plug
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, raw, conn} = read_body(conn)
      body = if raw == "", do: nil, else: Jason.decode!(raw)
      reply = Agent.get_and_update(agent, fn st -> {st.reply, %{st | captured: body}} end)
      conn |> put_resp_content_type("application/json") |> send_resp(200, Jason.encode!(reply))
    end
  end

  defp start_stub(reply) do
    {:ok, agent} = Agent.start_link(fn -> %{reply: reply, captured: nil} end)
    port = free_port()

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {Stub, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    {"http://127.0.0.1:#{port}", agent}
  end

  defp free_port do
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)
    port
  end

  defp sent(agent), do: Agent.get(agent, & &1.captured)
  defp sent_json(agent), do: Jason.encode!(sent(agent))

  defp client(base, style),
    do: Client.create(base_url: base, style: style, model: "stub", api_key: "k")

  # The OpenAI `tools` array a client sends, verbatim.
  defp openai_tools do
    [
      %{
        "type" => "function",
        "function" => %{
          "name" => "get_weather",
          "description" => "Get the weather",
          "parameters" => %{
            "type" => "object",
            "properties" => %{"city" => %{"type" => "string"}},
            "required" => ["city"]
          }
        }
      }
    ]
  end

  # Every content block across the sent messages, for structural assertions.
  defp blocks_of(agent) do
    (sent(agent)["messages"] || [])
    |> Enum.flat_map(fn m ->
      case m["content"] do
        blocks when is_list(blocks) -> blocks
        _ -> []
      end
    end)
  end

  defp user(text), do: %{"role" => "user", "content" => text}

  # ---- Anthropic upstream: the real translation ----

  test "an Anthropic tool_use turn comes back as an OpenAI tool call" do
    {base, agent} =
      start_stub(%{
        "content" => [
          %{"type" => "tool_use", "id" => "toolu_1", "name" => "get_weather", "input" => %{"city" => "Chennai"}}
        ],
        "stop_reason" => "tool_use",
        "usage" => %{"input_tokens" => 10, "output_tokens" => 5}
      })

    res =
      Client.translate(client(base, "anthropic"), [user("weather in Chennai?")], tools: openai_tools())

    assert res.finish_reason == "tool_calls"
    assert [tc] = res.tool_calls
    assert tc.id == "toolu_1"
    assert tc.name == "get_weather"
    # arguments must be a JSON STRING (the OpenAI wire shape), not an object
    assert is_binary(tc.arguments)
    assert Jason.decode!(tc.arguments)["city"] == "Chennai"
    assert res.usage.total_tokens > 0

    json = sent_json(agent)
    assert json =~ "input_schema"
    assert json =~ "get_weather"
    refute json =~ ~s("parameters"), "OpenAI-shaped 'parameters' leaked to the Anthropic upstream"
  end

  test "a multi-turn tool exchange survives (the flattening bug)" do
    {base, agent} =
      start_stub(%{
        "content" => [%{"type" => "text", "text" => "It is 31C in Chennai."}],
        "stop_reason" => "end_turn",
        "usage" => %{"input_tokens" => 20, "output_tokens" => 8}
      })

    res =
      Client.translate(
        client(base, "anthropic"),
        [
          %{"role" => "system", "content" => "Be terse."},
          user("weather in Chennai?"),
          %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" => [
              %{
                "id" => "call_abc",
                "type" => "function",
                "function" => %{"name" => "get_weather", "arguments" => ~s({"city":"Chennai"})}
              }
            ]
          },
          %{"role" => "tool", "tool_call_id" => "call_abc", "content" => "31C, clear"}
        ],
        tools: openai_tools()
      )

    assert res.finish_reason == "stop"
    assert res.text == "It is 31C in Chennai."
    assert sent(agent)["system"] == "Be terse.", "system not hoisted out of messages"

    json = sent_json(agent)

    for want <- ["tool_use", "tool_result", "call_abc", "31C, clear"] do
      assert json =~ want, "multi-turn structure lost #{want}"
    end

    # the tool_use's input is an OBJECT upstream, re-parsed from the JSON string
    use_block = Enum.find(blocks_of(agent), &(&1["type"] == "tool_use"))
    assert use_block, "no tool_use block reached the provider"
    assert use_block["input"]["city"] == "Chennai", "tool_use input not re-parsed to an object"
  end

  test "three consecutive tool results merge into ONE user turn" do
    {base, agent} =
      start_stub(%{"content" => [%{"type" => "text", "text" => "done"}], "stop_reason" => "end_turn"})

    call = fn id -> %{"id" => id, "function" => %{"name" => "f", "arguments" => "{}"}} end
    result = fn id, content -> %{"role" => "tool", "tool_call_id" => id, "content" => content} end

    Client.translate(client(base, "anthropic"), [
      user("do three things"),
      %{"role" => "assistant", "tool_calls" => [call.("a"), call.("b"), call.("c")]},
      result.("a", "ra"),
      result.("b", "rb"),
      result.("c", "rc")
    ])

    result_turns =
      (sent(agent)["messages"] || [])
      |> Enum.filter(fn m ->
        is_list(m["content"]) and Enum.any?(m["content"], &(&1["type"] == "tool_result"))
      end)

    assert length(result_turns) == 1, "tool results spread over more than one user turn"

    assert length(Enum.filter(hd(result_turns)["content"], &(&1["type"] == "tool_result"))) == 3,
           "merged turn does not carry all three results"

    assert length(Enum.filter(blocks_of(agent), &(&1["type"] == "tool_use"))) == 3,
           "want 3 tool_use blocks upstream"
  end

  test "parallel tool calls are all returned, in provider order" do
    {base, _agent} =
      start_stub(%{
        "content" => [
          %{"type" => "text", "text" => "calling three"},
          %{"type" => "tool_use", "id" => "t1", "name" => "alpha", "input" => %{"n" => 1}},
          %{"type" => "tool_use", "id" => "t2", "name" => "beta", "input" => %{"n" => 2}},
          %{"type" => "tool_use", "id" => "t3", "name" => "gamma", "input" => %{"n" => 3}}
        ],
        "stop_reason" => "tool_use"
      })

    res = Client.translate(client(base, "anthropic"), [user("go")], tools: openai_tools())

    assert Enum.map(res.tool_calls, & &1.name) == ["alpha", "beta", "gamma"]
    assert res.text == "calling three", "text alongside tool calls was lost"
    assert res.finish_reason == "tool_calls"
    assert length(Translate.tool_calls_json(res)) == 3
    assert hd(Translate.tool_calls_json(res))["type"] == "function"
  end

  test "executes nothing and keeps no state across calls" do
    {base, agent} =
      start_stub(%{
        "content" => [%{"type" => "tool_use", "id" => "t1", "name" => "danger", "input" => %{}}],
        "stop_reason" => "tool_use"
      })

    {:ok, counter} = Agent.start_link(fn -> 0 end)

    danger = %Toolnexus.Tool{
      name: "danger",
      description: "must not run",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "native",
      execute: fn _args, _ctx ->
        Agent.update(counter, &(&1 + 1))
        %Toolnexus.ToolResult{output: "RAN"}
      end
    }

    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [danger])
    c = client(base, "anthropic")

    for _ <- 1..3 do
      res = Client.translate(c, [user("go")], toolkit: tk)
      assert [tc] = res.tool_calls
      assert tc.name == "danger"
    end

    assert Agent.get(counter, & &1) == 0,
           "translate EXECUTED a tool — it must never execute anything"

    # no history accumulated between the three independent calls
    assert length(sent(agent)["messages"]) == 1, "state leaked between translate calls"
  end

  test "a toolkit is declared natively but never executed" do
    {base, agent} =
      start_stub(%{
        "content" => [
          %{"type" => "tool_use", "id" => "tu_9", "name" => "my_native_tool", "input" => %{"x" => 1}}
        ],
        "stop_reason" => "tool_use"
      })

    {:ok, counter} = Agent.start_link(fn -> 0 end)

    tool = %Toolnexus.Tool{
      name: "my_native_tool",
      description: "an ordinary executable tool",
      input_schema: %{"type" => "object", "properties" => %{"x" => %{"type" => "number"}}},
      source: "native",
      execute: fn _args, _ctx ->
        Agent.update(counter, &(&1 + 1))
        %Toolnexus.ToolResult{output: "SHOULD NOT RUN"}
      end
    }

    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [tool])
    res = Client.translate(client(base, "anthropic"), [user("use the tool")], toolkit: tk)

    assert Agent.get(counter, & &1) == 0, "translate executed a toolkit tool"
    assert [tc] = res.tool_calls
    assert tc.name == "my_native_tool"
    assert tc.id == "tu_9"
    json = sent_json(agent)
    assert json =~ "input_schema"
    assert json =~ "my_native_tool"
  end

  test "a toolkit and an OpenAI tools array compose" do
    {base, agent} =
      start_stub(%{"content" => [%{"type" => "text", "text" => "ok"}], "stop_reason" => "end_turn"})

    tool = %Toolnexus.Tool{
      name: "server_side_tool",
      description: "gateway's own",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "native",
      execute: fn _args, _ctx -> %Toolnexus.ToolResult{output: "x"} end
    }

    tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [tool])
    Client.translate(client(base, "anthropic"), [user("go")], toolkit: tk, tools: openai_tools())

    json = sent_json(agent)

    for want <- ["server_side_tool", "get_weather"] do
      assert json =~ want, "composed declaration missing #{want}"
    end
  end

  test "tool_choice maps onto Anthropic's shape" do
    cases = [
      {nil, nil},
      {"auto", nil},
      {"required", ~s("type":"any")},
      {"none", ~s("type":"none")},
      {%{"type" => "function", "function" => %{"name" => "get_weather"}}, ~s("name":"get_weather")}
    ]

    for {choice, want} <- cases do
      {base, agent} =
        start_stub(%{"content" => [%{"type" => "text", "text" => "ok"}], "stop_reason" => "end_turn"})

      Client.translate(client(base, "anthropic"), [user("go")],
        tools: openai_tools(),
        tool_choice: choice
      )

      present = sent(agent)["tool_choice"]

      if is_nil(want) do
        assert is_nil(present), "tool_choice #{inspect(choice)} should be omitted"
      else
        assert present, "tool_choice #{inspect(choice)} missing"
        assert Jason.encode!(present) =~ want, "tool_choice did not map to #{want}"
      end
    end
  end

  test "finish reason maps from the provider stop reason" do
    for {stop, want} <- [
          {"end_turn", "stop"},
          {"max_tokens", "length"},
          {"refusal", "content_filter"},
          {"stop_sequence", "stop"}
        ] do
      {base, _agent} =
        start_stub(%{"content" => [%{"type" => "text", "text" => "x"}], "stop_reason" => stop})

      res = Client.translate(client(base, "anthropic"), [user("go")])
      assert res.finish_reason == want, "stop_reason #{stop}"
    end
  end

  test "arguments are accepted as an object as well as a string" do
    {base, agent} =
      start_stub(%{"content" => [%{"type" => "text", "text" => "ok"}], "stop_reason" => "end_turn"})

    Client.translate(client(base, "anthropic"), [
      user("go"),
      # some clients send arguments as an object rather than a JSON string
      %{
        "role" => "assistant",
        "tool_calls" => [
          %{"id" => "z", "function" => %{"name" => "f", "arguments" => %{"city" => "Madurai"}}}
        ]
      },
      %{"role" => "tool", "tool_call_id" => "z", "content" => "done"}
    ])

    use_block = Enum.find(blocks_of(agent), &(&1["type"] == "tool_use"))
    assert use_block, "no tool_use block upstream"

    assert use_block["input"]["city"] == "Madurai",
           "object-form arguments were not carried through"
  end

  test "content given as parts is flattened to text" do
    {base, agent} =
      start_stub(%{"content" => [%{"type" => "text", "text" => "ok"}], "stop_reason" => "end_turn"})

    Client.translate(client(base, "anthropic"), [
      %{
        "role" => "user",
        "content" => [
          %{"type" => "text", "text" => "part one "},
          %{"type" => "text", "text" => "part two"}
        ]
      }
    ])

    assert sent_json(agent) =~ "part one part two"
  end

  test "LLM hooks fire exactly once and no tool hook fires" do
    {base, _agent} =
      start_stub(%{
        "content" => [%{"type" => "tool_use", "id" => "t1", "name" => "get_weather", "input" => %{}}],
        "stop_reason" => "tool_use"
      })

    {:ok, counts} = Agent.start_link(fn -> %{before: 0, after: 0, tool: 0} end)
    bump = fn key -> Agent.update(counts, &Map.update!(&1, key, fn n -> n + 1 end)) end

    c =
      Client.create(
        base_url: base,
        style: "anthropic",
        model: "stub",
        api_key: "k",
        hooks: %{
          before_llm: fn _ev ->
            bump.(:before)
            nil
          end,
          after_llm: fn _ev -> bump.(:after) end,
          before_tool: fn _ev ->
            bump.(:tool)
            nil
          end,
          after_tool: fn _ev ->
            bump.(:tool)
            nil
          end
        }
      )

    Client.translate(c, [user("go")], tools: openai_tools())

    got = Agent.get(counts, & &1)
    assert got.before == 1, "before_llm did not fire exactly once"
    assert got.after == 1, "after_llm did not fire exactly once"
    assert got.tool == 0, "a tool hook fired, but no tool runs in translate"
  end

  # ---- OpenAI upstream: near-passthrough ----

  test "an OpenAI upstream passes tools and arguments through unchanged" do
    {base, agent} =
      start_stub(%{
        "choices" => [
          %{
            "message" => %{
              "content" => "",
              "tool_calls" => [
                %{
                  "id" => "call_1",
                  "type" => "function",
                  "function" => %{"name" => "get_weather", "arguments" => ~s({"city":"Madurai"})}
                }
              ]
            },
            "finish_reason" => "tool_calls"
          }
        ],
        "usage" => %{"prompt_tokens" => 3, "completion_tokens" => 4, "total_tokens" => 7}
      })

    res = Client.translate(client(base, "openai"), [user("weather?")], tools: openai_tools())

    assert res.finish_reason == "tool_calls"
    assert [tc] = res.tool_calls
    assert tc.arguments == ~s({"city":"Madurai"}), "arguments not byte-for-byte"
    assert res.usage.total_tokens == 7
    assert sent_json(agent) =~ ~s("parameters"), "OpenAI tools were altered on an OpenAI upstream"
  end
  # ---- pure translation functions: the fallback and edge clauses ----

  describe "pure functions" do
    test "content_text handles the string, parts and unknown forms" do
      assert Translate.content_text("hi") == "hi"
      assert Translate.content_text([%{"text" => "a"}, %{"text" => "b"}]) == "ab"
      # non-text parts are ignored, not rendered
      assert Translate.content_text([%{"type" => "image", "url" => "u"}, %{"text" => "t"}]) == "t"
      assert Translate.content_text(nil) == ""
      assert Translate.content_text(42) == ""
    end

    test "args_object tolerates both wire forms and malformed input" do
      assert Translate.args_object(%{"a" => 1}) == %{"a" => 1}
      assert Translate.args_object(~s({"a":1})) == %{"a" => 1}
      assert Translate.args_object("") == %{}
      assert Translate.args_object("   ") == %{}
      # a malformed arguments string is not fatal
      assert Translate.args_object("{not json") == %{}
      # valid JSON that is not an object is not usable as arguments
      assert Translate.args_object("[1,2]") == %{}
      assert Translate.args_object(nil) == %{}
    end

    test "args_string renders the OpenAI wire form" do
      assert Translate.args_string(~s({"a":1})) == ~s({"a":1})
      assert Translate.args_string(%{"a" => 1}) == ~s({"a":1})
      assert Translate.args_string(nil) == "{}"
    end

    test "tool_calls_of ignores malformed entries" do
      assert Translate.tool_calls_of(%{}) == []
      assert Translate.tool_calls_of(%{"tool_calls" => "nope"}) == []
      # an entry without a function object is skipped rather than crashing
      assert Translate.tool_calls_of(%{"tool_calls" => [%{"id" => "x"}]}) == []
    end

    test "finish_reason_for maps every branch, with tool calls winning" do
      assert Translate.finish_reason_for(true, "end_turn") == "tool_calls"
      assert Translate.finish_reason_for(true, nil) == "tool_calls"
      assert Translate.finish_reason_for(false, "max_tokens") == "length"
      assert Translate.finish_reason_for(false, "length") == "length"
      assert Translate.finish_reason_for(false, "refusal") == "content_filter"
      assert Translate.finish_reason_for(false, "content_filter") == "content_filter"
      assert Translate.finish_reason_for(false, "anything_else") == "stop"
      assert Translate.finish_reason_for(false, nil) == "stop"
    end

    test "openai_tools_to_anthropic skips junk and passes native declarations through" do
      assert Translate.openai_tools_to_anthropic(nil) == []
      assert Translate.openai_tools_to_anthropic(["not a map"]) == []
      # a function entry with no name is not a usable declaration
      assert Translate.openai_tools_to_anthropic([%{"function" => %{}}]) == []
      # already provider-native passes through untouched
      native = %{"name" => "n", "input_schema" => %{"type" => "object"}}
      assert Translate.openai_tools_to_anthropic([native]) == [native]
      # a non-map `parameters` falls back to an empty object schema
      [decl] = Translate.openai_tools_to_anthropic([%{"function" => %{"name" => "f", "parameters" => "bad"}}])
      assert decl["input_schema"] == %{"type" => "object", "properties" => %{}}
      refute Map.has_key?(decl, "description")
    end

    test "openai_tool_choice_to_anthropic returns nil for the provider default" do
      assert Translate.openai_tool_choice_to_anthropic(nil) == nil
      assert Translate.openai_tool_choice_to_anthropic("auto") == nil
      assert Translate.openai_tool_choice_to_anthropic("nonsense") == nil
      assert Translate.openai_tool_choice_to_anthropic(%{"function" => %{}}) == nil
      assert Translate.openai_tool_choice_to_anthropic("any") == %{"type" => "any"}
    end

    test "has_system_message? spots both system and developer roles" do
      refute Translate.has_system_message?([])
      refute Translate.has_system_message?([%{"role" => "user"}])
      refute Translate.has_system_message?(["junk"])
      assert Translate.has_system_message?([%{"role" => "system"}])
      assert Translate.has_system_message?([%{"role" => "developer"}])
    end

    test "openai_messages_to_anthropic drops an empty assistant turn" do
      # an assistant turn with neither text nor tool calls would be rejected upstream
      {msgs, ""} =
        Translate.openai_messages_to_anthropic([
          %{"role" => "user", "content" => "hi"},
          %{"role" => "assistant", "content" => nil}
        ])

      assert msgs == [%{"role" => "user", "content" => "hi"}]
    end

    test "openai_messages_to_anthropic joins multiple system messages" do
      {_msgs, system} =
        Translate.openai_messages_to_anthropic([
          %{"role" => "system", "content" => "one"},
          %{"role" => "developer", "content" => "two"},
          %{"role" => "user", "content" => "hi"}
        ])

      assert system == "one\n\ntwo"
    end

    test "openai_messages_to_anthropic keeps a user content-block array as-is" do
      blocks = [%{"type" => "image", "source" => %{}}]
      {msgs, ""} = Translate.openai_messages_to_anthropic([%{"role" => "user", "content" => blocks}])
      assert msgs == [%{"role" => "user", "content" => blocks}]
    end

    test "openai_messages_to_anthropic flushes trailing tool results into a final user turn" do
      {msgs, ""} =
        Translate.openai_messages_to_anthropic([
          %{"role" => "tool", "tool_call_id" => "a", "content" => "ra"}
        ])

      assert msgs == [
               %{"role" => "user", "content" => [%{"type" => "tool_result", "content" => "ra", "tool_use_id" => "a"}]}
             ]
    end

    test "openai_messages_to_anthropic omits tool_use_id when the caller sent none" do
      {msgs, ""} = Translate.openai_messages_to_anthropic([%{"role" => "tool", "content" => "orphan"}])
      [%{"content" => [block]}] = msgs
      refute Map.has_key?(block, "tool_use_id")
    end

    test "tool_calls_json renders an envelope-ready array" do
      res = %Translate.Result{tool_calls: [%Translate.ToolCall{id: "i", name: "n", arguments: "{}"}]}

      assert Translate.tool_calls_json(res) == [
               %{"id" => "i", "type" => "function", "function" => %{"name" => "n", "arguments" => "{}"}}
             ]
    end
  end

end
