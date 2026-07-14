defmodule Toolnexus.ClientTest do
  use ExUnit.Case, async: true

  import ExUnit.CaptureLog

  alias Toolnexus.{Answer, Request, Tool, ToolResult}
  alias Toolnexus.Client
  alias Toolnexus.Client.{InMemoryConversationStore, MetricsRegistry}

  # ---- local Bandit stub implementing the openai / anthropic wire shapes ----

  defmodule Stub do
    @behaviour Plug
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, raw, conn} = read_body(conn)
      body = if raw == "", do: nil, else: Jason.decode!(raw)

      handler =
        Agent.get_and_update(agent, fn st ->
          {h, rest} =
            case st.handlers do
              [h | rest] -> {h, rest}
              [] -> {nil, []}
            end

          {h, %{st | handlers: rest, captured: st.captured ++ [%{path: conn.request_path, body: body}]}}
        end)

      case handler && handler.(body) do
        {:json, status, resp} ->
          conn |> put_resp_content_type("application/json") |> send_resp(status, Jason.encode!(resp))

        {:sse, text} ->
          conn |> put_resp_content_type("text/event-stream") |> send_resp(200, text)

        nil ->
          conn |> put_resp_content_type("application/json") |> send_resp(500, ~s({"error":"no handler"}))
      end
    end
  end

  defp start_stub(handlers) do
    {:ok, agent} = Agent.start_link(fn -> %{handlers: handlers, captured: []} end)
    port = free_port()

    start_supervised!(
      Supervisor.child_spec({Bandit, plug: {Stub, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
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

  defp captured(agent), do: Agent.get(agent, & &1.captured)

  # ---- response builders ----

  defp openai_usage, do: %{"prompt_tokens" => 5, "completion_tokens" => 4, "total_tokens" => 9}

  defp openai_text(text, usage \\ nil) do
    {:json, 200,
     %{
       "choices" => [%{"message" => %{"role" => "assistant", "content" => text}}],
       "usage" => usage || openai_usage()
     }}
  end

  defp openai_calls(calls) do
    tool_calls =
      Enum.map(calls, fn {id, name, args_json} ->
        %{"id" => id, "type" => "function", "function" => %{"name" => name, "arguments" => args_json}}
      end)

    {:json, 200,
     %{
       "choices" => [%{"message" => %{"role" => "assistant", "content" => nil, "tool_calls" => tool_calls}}],
       "usage" => openai_usage()
     }}
  end

  defp anthropic_text(text) do
    {:json, 200,
     %{
       "content" => [%{"type" => "text", "text" => text}],
       "usage" => %{"input_tokens" => 3, "output_tokens" => 2}
     }}
  end

  defp anthropic_tool_use(uses) do
    content = Enum.map(uses, fn {id, name, input} -> %{"type" => "tool_use", "id" => id, "name" => name, "input" => input} end)
    {:json, 200, %{"content" => content, "usage" => %{"input_tokens" => 3, "output_tokens" => 2}}}
  end

  # ---- tool builders ----

  defp tool(name, fun, source \\ "native") do
    %Tool{
      name: name,
      description: "test tool #{name}",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: source,
      execute: fun
    }
  end

  defp add_tool do
    tool("add", fn args, _ctx ->
      ToolResult.ok(to_string(trunc(args["a"] + args["b"])))
    end)
  end

  defp pending_result(id, prompt) do
    %ToolResult{
      output: prompt,
      is_error: true,
      metadata: %{pending: %Request{id: id, kind: "authorization", prompt: prompt, url: "https://x/auth"}}
    }
  end

  defp make_client(base, extra \\ []) do
    Client.create([base_url: base, style: "openai", model: "gpt-x", api_key: "k", retry_base_ms: 1] ++ extra)
  end

  # ---- single turn ----

  test "openai: single turn text" do
    {base, agent} = start_stub([fn _ -> openai_text("hello") end])
    client = make_client(base, system_prompt: "be brief")

    result = Client.run(client, "hi", [])

    assert result.text == "hello"
    assert result.status == "done"
    assert result.turns == 1
    assert result.tool_calls == []
    assert result.tool_call_count == 0
    assert result.usage == %{prompt_tokens: 5, completion_tokens: 4, total_tokens: 9}
    assert result.model == "gpt-x"

    [%{path: "/chat/completions", body: body}] = captured(agent)
    assert body["model"] == "gpt-x"
    assert [%{"role" => "system", "content" => "be brief"}, %{"role" => "user", "content" => "hi"}] = body["messages"]
  end

  test "anthropic: single turn text, /v1/messages path, max_tokens default 4096" do
    {base, agent} = start_stub([fn _ -> anthropic_text("hi there") end])
    client = make_client(base, style: "anthropic", system_prompt: "sp")

    result = Client.run(client, "hello", %{tools: [], prompt: "skills here"})

    assert result.text == "hi there"
    assert result.usage == %{prompt_tokens: 3, completion_tokens: 2, total_tokens: 5}

    [%{path: "/v1/messages", body: body}] = captured(agent)
    assert body["max_tokens"] == 4096
    # system = system_prompt <> "\n\n" <> skills prompt (both present)
    assert body["system"] == "sp\n\nskills here"
    assert [%{"role" => "user", "content" => "hello"}] = body["messages"]
  end

  # ---- tool-call turn then final ----

  test "openai: tool-call turn then final, provider tool-result shape" do
    {base, agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "add", ~s({"a":2,"b":3})}]) end,
        fn _ -> openai_text("the sum is 5") end
      ])

    client = make_client(base)
    result = Client.run(client, "add them", [add_tool()])

    assert result.text == "the sum is 5"
    assert result.turns == 2
    assert result.tool_call_count == 1
    assert [%{name: "add", output: "5", is_error: false}] = result.tool_calls
    assert result.usage.total_tokens == 18

    [_first, %{body: second}] = captured(agent)
    assert Enum.any?(second["messages"], fn m ->
             m["role"] == "tool" and m["tool_call_id"] == "c1" and m["content"] == "5"
           end)
  end

  test "anthropic: tool_use then final, tool_result content-block shape" do
    {base, agent} =
      start_stub([
        fn _ -> anthropic_tool_use([{"t1", "add", %{"a" => 2, "b" => 3}}]) end,
        fn _ -> anthropic_text("5 it is") end
      ])

    client = make_client(base, style: "anthropic")
    result = Client.run(client, "add", [add_tool()])

    assert result.text == "5 it is"
    assert result.turns == 2

    [_first, %{body: second}] = captured(agent)
    last = List.last(second["messages"])
    assert last["role"] == "user"

    assert [%{"type" => "tool_result", "tool_use_id" => "t1", "content" => "5", "is_error" => false}] =
             last["content"]
  end

  # ---- parallel multi-tool call: concurrent execution, original order preserved ----

  test "openai: parallel tool calls run concurrently, results fed back in call order" do
    slow = tool("slow", fn _, _ -> Process.sleep(100); ToolResult.ok("slow-out") end)
    fast = tool("fast", fn _, _ -> ToolResult.ok("fast-out") end)

    {base, agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "slow", "{}"}, {"c2", "fast", "{}"}]) end,
        fn _ -> openai_text("done") end
      ])

    client = make_client(base)
    t0 = System.monotonic_time(:millisecond)
    result = Client.run(client, "go", [slow, fast])
    elapsed = System.monotonic_time(:millisecond) - t0

    assert result.text == "done"
    # concurrent: two 100ms-class tools must not serialize (slow=100ms, so well under 200ms)
    assert elapsed < 180

    assert [%{name: "slow", output: "slow-out"}, %{name: "fast", output: "fast-out"}] = result.tool_calls

    [_first, %{body: second}] = captured(agent)
    tool_msgs = Enum.filter(second["messages"], &(&1["role"] == "tool"))
    assert Enum.map(tool_msgs, & &1["tool_call_id"]) == ["c1", "c2"]
    assert Enum.map(tool_msgs, & &1["content"]) == ["slow-out", "fast-out"]
  end

  # ---- gap 5: empty tool list omits tools / tool_choice ----

  test "gap 5: zero tools => openai body has NO tools/tool_choice keys" do
    {base, agent} = start_stub([fn _ -> openai_text("ok") end])
    client = make_client(base)
    Client.run(client, "hi", [])

    [%{body: body}] = captured(agent)
    refute Map.has_key?(body, "tools")
    refute Map.has_key?(body, "tool_choice")
  end

  test "gap 5: zero tools => anthropic body has NO tools key" do
    {base, agent} = start_stub([fn _ -> anthropic_text("ok") end])
    client = make_client(base, style: "anthropic")
    Client.run(client, "hi", [])

    [%{body: body}] = captured(agent)
    refute Map.has_key?(body, "tools")
    assert Map.has_key?(body, "max_tokens")
  end

  test "non-empty tools => openai body includes tools + tool_choice auto" do
    {base, agent} = start_stub([fn _ -> openai_text("ok") end])
    client = make_client(base)
    Client.run(client, "hi", [add_tool()])

    [%{body: body}] = captured(agent)
    assert body["tool_choice"] == "auto"

    assert [%{"type" => "function", "function" => %{"name" => "add", "parameters" => _}}] = body["tools"]
  end

  # ---- gap 1: request_params merge (caller wins) + body_transform runs LAST ----

  test "gap 1: request_params caller-wins merge, forbidden keys stripped, body_transform last" do
    {base, agent} = start_stub([fn _ -> anthropic_text("ok") end])

    client =
      make_client(base,
        style: "anthropic",
        request_params: %{"max_tokens" => 5, "temperature" => 0.1, "messages" => "FORBIDDEN"},
        body_transform: fn body -> Map.put(body, "note", "mt=#{body["max_tokens"]}") end
      )

    log =
      capture_log(fn ->
        Client.run(client, "hi", [])
      end)

    assert log =~ ~s(request_params key "messages" is not allowed)

    [%{body: body}] = captured(agent)
    # caller wins over the anthropic max_tokens default
    assert body["max_tokens"] == 5
    assert body["temperature"] == 0.1
    # forbidden key ignored — messages untouched
    assert is_list(body["messages"])
    # body_transform ran AFTER the merge (saw the merged max_tokens)
    assert body["note"] == "mt=5"
  end

  # ---- max_turns cap ----

  test "max_turns caps the loop" do
    handler = fn _ -> openai_calls([{"c#{System.unique_integer([:positive])}", "add", ~s({"a":1,"b":1})}]) end
    {base, agent} = start_stub([handler, handler, handler])

    client = make_client(base, max_turns: 2)
    result = Client.run(client, "loop forever", [add_tool()])

    assert result.turns == 2
    assert result.status == "done"
    assert result.text == ""
    assert result.tool_call_count == 2
    assert length(captured(agent)) == 2
  end

  # ---- retries ----

  test "retries: 500 then 200 succeeds" do
    {base, agent} =
      start_stub([
        fn _ -> {:json, 500, %{"error" => "boom"}} end,
        fn _ -> openai_text("recovered") end
      ])

    client = make_client(base)
    result = Client.run(client, "hi", [])

    assert result.text == "recovered"
    assert length(captured(agent)) == 2
  end

  test "non-retryable status raises LLM error" do
    {base, _agent} = start_stub([fn _ -> {:json, 400, %{"error" => "bad"}} end])
    client = make_client(base)

    assert_raise RuntimeError, ~r/^LLM 400:/, fn -> Client.run(client, "hi", []) end
  end

  # ---- ask / ConversationStore ----

  test "ask: store round-trip continues the conversation" do
    {base, agent} =
      start_stub([
        fn _ -> openai_text("one") end,
        fn _ -> openai_text("two") end
      ])

    client = make_client(base)

    r1 = Client.ask(client, "q1", [], "conv-1")
    assert r1.text == "one"

    r2 = Client.ask(client, "q2", [], "conv-1")
    assert r2.text == "two"

    [_first, %{body: second}] = captured(agent)

    assert Enum.map(second["messages"], &{&1["role"], &1["content"]}) == [
             {"user", "q1"},
             {"assistant", "one"},
             {"user", "q2"}
           ]

    # store is the single source of truth
    stored = InMemoryConversationStore.get(Client.conversation_store(client), "conv-1")
    assert stored == r2.messages
  end

  test "ask without id is a stateless one-shot" do
    {base, agent} = start_stub([fn _ -> openai_text("a") end, fn _ -> openai_text("b") end])
    client = make_client(base)

    Client.ask(client, "q1", [])
    Client.ask(client, "q2", [])

    [_, %{body: second}] = captured(agent)
    assert [%{"role" => "user", "content" => "q2"}] = second["messages"]
  end

  test "conversation_store/1 returns the exact instance passed in (gap 4)" do
    store = InMemoryConversationStore.new()
    client = Client.create(base_url: "http://x", style: "openai", model: "m", api_key: "k", store: store)
    assert Client.conversation_store(client) == store
  end

  # ---- §10 suspension ----

  test "pending without wait_for: run returns status pending immediately (no hang)" do
    login = tool("login", fn _, _ -> pending_result("r1", "Login required: https://x/auth") end)
    {base, agent} = start_stub([fn _ -> openai_calls([{"c1", "login", "{}"}]) end])

    client = make_client(base)
    result = Client.run(client, "log me in", [login])

    assert result.status == "pending"
    assert %Request{id: "r1", kind: "authorization"} = result.pending
    assert result.text == "Login required: https://x/auth"
    # only one LLM round trip happened — the run halted instead of feeding the error back
    assert length(captured(agent)) == 1
    # the halted call's placeholder result IS in the transcript
    assert Enum.count(result.messages, &(&1["role"] == "tool")) == 1
  end

  test "pending with wait_for ok: re-execute SAME tool ONCE with Context.answer, continue" do
    {:ok, execs} = Agent.start_link(fn -> [] end)

    login =
      tool("login", fn _args, ctx ->
        Agent.update(execs, &(&1 ++ [ctx.answer]))

        case ctx.answer do
          %Answer{ok: true, data: %{"v" => v}} -> ToolResult.ok("resolved:#{v}")
          _ -> pending_result("r1", "Login required")
        end
      end)

    {base, agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "login", "{}"}]) end,
        fn _ -> openai_text("welcome") end
      ])

    client =
      make_client(base,
        wait_for: fn %Request{id: id} -> %Answer{id: id, ok: true, data: %{"v" => "42"}} end
      )

    result = Client.run(client, "log me in", [login])

    assert result.status == "done"
    assert result.text == "welcome"
    # executed exactly twice: once suspending (no answer), once with the answer
    assert [nil, %Answer{ok: true}] = Agent.get(execs, & &1)

    [_first, %{body: second}] = captured(agent)
    tool_msg = Enum.find(second["messages"], &(&1["role"] == "tool"))
    assert tool_msg["content"] == "resolved:42"
  end

  test "pending with wait_for !ok: error result fed back, loop continues (G2 text)" do
    login = tool("login", fn _, _ -> pending_result("r1", "Login required") end)

    {base, agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "login", "{}"}]) end,
        fn _ -> openai_text("ok, skipping login") end
      ])

    {:ok, events} = Agent.start_link(fn -> [] end)

    client =
      make_client(base,
        wait_for: fn %Request{id: id} -> %Answer{id: id, ok: false, reason: "declined"} end,
        on_metric: fn ev -> Agent.update(events, &(&1 ++ [ev])) end
      )

    result = Client.run(client, "log me in", [login])
    assert result.status == "done"

    [_first, %{body: second}] = captured(agent)
    tool_msg = Enum.find(second["messages"], &(&1["role"] == "tool"))
    assert tool_msg["content"] == "declined/expired: Login required"

    # G2: the suspension is NOT a tool error — pending:true, is_error:false
    tool_events = events |> Agent.get(& &1) |> Enum.filter(&(&1.event == "tool"))
    assert [%{is_error: false, pending: true}] = tool_events
  end

  test "wait_for ok but tool suspends again: unresolved error fed back (never loop)" do
    login = tool("login", fn _, _ -> pending_result("r1", "Login required") end)

    {base, agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "login", "{}"}]) end,
        fn _ -> openai_text("gave up") end
      ])

    client = make_client(base, wait_for: fn %Request{id: id} -> %Answer{id: id, ok: true} end)
    result = Client.run(client, "go", [login])

    assert result.status == "done"
    [_first, %{body: second}] = captured(agent)
    tool_msg = Enum.find(second["messages"], &(&1["role"] == "tool"))
    assert tool_msg["content"] == "unresolved: Login required"
  end

  test "G3: two concurrent suspensions, no wait_for => halts on FIRST in call order" do
    # first call is SLOWER — order must come from call order, not completion order
    p1 = tool("p1", fn _, _ -> Process.sleep(60); pending_result("req-1", "first prompt") end)
    p2 = tool("p2", fn _, _ -> pending_result("req-2", "second prompt") end)

    {base, _agent} = start_stub([fn _ -> openai_calls([{"c1", "p1", "{}"}, {"c2", "p2", "{}"}]) end])

    client = make_client(base)
    result = Client.run(client, "go", [p1, p2])

    assert result.status == "pending"
    assert result.pending.id == "req-1"
    # the later suspension's placeholder never enters the transcript
    tool_msgs = Enum.filter(result.messages, &(&1["role"] == "tool"))
    assert length(tool_msgs) == 1
    assert hd(tool_msgs)["tool_call_id"] == "c1"
    assert [%{name: "p1"}] = result.tool_calls
  end

  # ---- streaming ----

  test "openai streaming: text deltas, usage, done" do
    sse = """
    data: {"choices":[{"delta":{"content":"Hel"}}]}

    data: {"choices":[{"delta":{"content":"lo"}}]}

    data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

    data: [DONE]
    """

    {base, agent} = start_stub([fn _ -> {:sse, sse} end])
    client = make_client(base)

    events = client |> Client.stream("hi", []) |> Enum.to_list()

    assert [
             %{type: "text", delta: "Hel"},
             %{type: "text", delta: "lo"},
             %{type: "usage", usage: %{total_tokens: 3}},
             %{type: "done", result: result}
           ] = events

    assert result.text == "Hello"
    assert result.status == "done"

    # streaming body carries stream + stream_options.include_usage
    [%{body: body}] = captured(agent)
    assert body["stream"] == true
    assert body["stream_options"] == %{"include_usage" => true}
  end

  test "openai streaming: tool call, pending event, done pending (§10)" do
    login = tool("login", fn _, _ -> pending_result("r9", "Login required") end)

    sse = """
    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"login","arguments":"{}"}}]}}]}

    data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

    data: [DONE]
    """

    {base, _agent} = start_stub([fn _ -> {:sse, sse} end])
    client = make_client(base)

    events = client |> Client.stream("go", [login]) |> Enum.to_list()

    assert Enum.any?(events, &match?(%{type: "tool_call", id: "c1", name: "login"}, &1))
    assert Enum.any?(events, &match?(%{type: "pending", request: %Request{id: "r9"}}, &1))

    %{type: "done", result: result} = List.last(events)
    assert result.status == "pending"
    assert result.pending.id == "r9"
  end

  test "openai streaming: tool round then final turn" do
    sse1 = """
    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"add","arguments":"{\\"a\\":2,\\"b\\":3}"}}]}}]}

    data: [DONE]
    """

    sse2 = """
    data: {"choices":[{"delta":{"content":"sum=5"}}]}

    data: [DONE]
    """

    {base, agent} = start_stub([fn _ -> {:sse, sse1} end, fn _ -> {:sse, sse2} end])
    client = make_client(base)

    events = client |> Client.stream("add", [add_tool()]) |> Enum.to_list()

    assert Enum.any?(events, &match?(%{type: "tool_call", id: "c1", name: "add"}, &1))
    assert Enum.any?(events, &match?(%{type: "tool_result", id: "c1", name: "add", output: "5", is_error: false}, &1))

    %{type: "done", result: result} = List.last(events)
    assert result.text == "sum=5"
    assert result.turns == 2

    [_first, %{body: second}] = captured(agent)
    assert Enum.any?(second["messages"], fn m -> m["role"] == "tool" and m["content"] == "5" end)
  end

  test "anthropic streaming: text deltas and done" do
    sse = """
    event: message_start
    data: {"type":"message_start","message":{"usage":{"input_tokens":4,"output_tokens":0}}}

    event: content_block_start
    data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}
    """

    {base, agent} = start_stub([fn _ -> {:sse, sse} end])
    client = make_client(base, style: "anthropic")

    events = client |> Client.stream("hi", []) |> Enum.to_list()

    assert [
             %{type: "text", delta: "Hi"},
             %{type: "text", delta: " there"},
             %{type: "usage", usage: usage},
             %{type: "done", result: result}
           ] = events

    assert result.text == "Hi there"
    assert usage == %{prompt_tokens: 4, completion_tokens: 3, total_tokens: 7}

    [%{path: "/v1/messages", body: body}] = captured(agent)
    assert body["stream"] == true
  end

  test "stream with id saves transcript to the store on done" do
    sse = """
    data: {"choices":[{"delta":{"content":"yo"}}]}

    data: [DONE]
    """

    {base, _agent} = start_stub([fn _ -> {:sse, sse} end])
    client = make_client(base)

    [%{type: "done", result: result} | _] =
      client |> Client.stream("hi", [], id: "s1") |> Enum.filter(&(&1.type == "done"))

    stored = InMemoryConversationStore.get(Client.conversation_store(client), "s1")
    assert stored == result.messages
  end

  test "ask with on_text forwards deltas and returns the final RunResult" do
    sse = """
    data: {"choices":[{"delta":{"content":"a"}}]}

    data: {"choices":[{"delta":{"content":"b"}}]}

    data: [DONE]
    """

    {base, _agent} = start_stub([fn _ -> {:sse, sse} end])
    client = make_client(base)
    {:ok, deltas} = Agent.start_link(fn -> [] end)

    result = Client.ask(client, "hi", [], on_text: fn d -> Agent.update(deltas, &(&1 ++ [d])) end)

    assert Agent.get(deltas, & &1) == ["a", "b"]
    assert result.text == "ab"
  end

  # ---- hooks ----

  test "before_tool short-circuit and after_tool transform" do
    {base, _agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "add", ~s({"a":1,"b":1})}, {"c2", "blocked", "{}"}]) end,
        fn _ -> openai_text("done") end
      ])

    hooks = %{
      before_tool: fn %{name: name} ->
        if name == "blocked", do: %{result: ToolResult.error("denied by policy")}
      end,
      after_tool: fn %{result: %ToolResult{} = result} ->
        %{result: %ToolResult{result | output: "[" <> result.output <> "]"}}
      end
    }

    client = make_client(base, hooks: hooks)
    result = Client.run(client, "go", [add_tool(), tool("blocked", fn _, _ -> ToolResult.ok("nope") end)])

    assert [
             %{name: "add", output: "[2]"},
             %{name: "blocked", output: "denied by policy", is_error: true}
           ] = result.tool_calls
  end

  test "before_llm can replace tools; emptied list omits tools key (gap 5 after override)" do
    {base, agent} = start_stub([fn _ -> openai_text("ok") end])
    client = make_client(base, hooks: %{before_llm: fn _ev -> %{tools: []} end})
    Client.run(client, "hi", [add_tool()])

    [%{body: body}] = captured(agent)
    refute Map.has_key?(body, "tools")
    refute Map.has_key?(body, "tool_choice")
  end

  # ---- metrics ----

  # The exact Prometheus text the js port renders for this fixed event set
  # (same reference as golang/client_metrics_test.go). BYTE-IDENTICAL contract.
  @metrics_reference """
  # HELP toolnexus_llm_requests_total Total LLM requests.
  # TYPE toolnexus_llm_requests_total counter
  toolnexus_llm_requests_total{model="gpt-x",status="ok"} 1
  # HELP toolnexus_llm_tokens_total Total tokens, by type.
  # TYPE toolnexus_llm_tokens_total counter
  toolnexus_llm_tokens_total{type="completion"} 2
  toolnexus_llm_tokens_total{type="prompt"} 3
  # HELP toolnexus_llm_request_duration_seconds LLM request duration in seconds.
  # TYPE toolnexus_llm_request_duration_seconds histogram
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.05"} 0
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.1"} 0
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.25"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="0.5"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="1"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="2.5"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="5"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="10"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="30"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="60"} 1
  toolnexus_llm_request_duration_seconds_bucket{model="gpt-x",le="+Inf"} 1
  toolnexus_llm_request_duration_seconds_sum{model="gpt-x"} 0.12
  toolnexus_llm_request_duration_seconds_count{model="gpt-x"} 1
  # HELP toolnexus_tool_calls_total Total tool calls.
  # TYPE toolnexus_tool_calls_total counter
  toolnexus_tool_calls_total{tool="add",source="native",is_error="false",pending="false"} 1
  # HELP toolnexus_tool_duration_seconds Tool execution duration in seconds.
  # TYPE toolnexus_tool_duration_seconds histogram
  toolnexus_tool_duration_seconds_bucket{tool="add",le="0.05"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="0.1"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="0.25"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="0.5"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="1"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="2.5"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="5"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="10"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="30"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="60"} 1
  toolnexus_tool_duration_seconds_bucket{tool="add",le="+Inf"} 1
  toolnexus_tool_duration_seconds_sum{tool="add"} 0.04
  toolnexus_tool_duration_seconds_count{tool="add"} 1
  # HELP toolnexus_run_errors_total Total run errors.
  # TYPE toolnexus_run_errors_total counter
  """

  test "metrics: byte-identical Prometheus text for the fixed event set" do
    client = Client.create(base_url: "http://x", style: "openai", model: "gpt-x", api_key: "k")

    # Before any activity: only # HELP / # TYPE lines, no series samples.
    before = Client.metrics(client)

    for line <- String.split(before, "\n", trim: true) do
      assert String.starts_with?(line, "#"), "before activity: unexpected sample line #{inspect(line)}"
    end

    MetricsRegistry.record(client.registry, %{event: "llm", model: "gpt-x", status: "ok", ms: 120, prompt_tokens: 3, completion_tokens: 2})
    MetricsRegistry.record(client.registry, %{event: "tool", tool: "add", source: "native", is_error: false, ms: 40})
    MetricsRegistry.record(client.registry, %{event: "run", model: "gpt-x", turns: 2, tool_calls: 1, total_tokens: 16, ms: 200})

    assert Client.metrics(client) == @metrics_reference
  end

  test "metrics accumulate from a real run and stay parseable" do
    {base, _agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "add", ~s({"a":2,"b":3})}]) end,
        fn _ -> openai_text("5") end
      ])

    client = make_client(base)
    Client.run(client, "add", [add_tool()])
    text = Client.metrics(client)

    assert text =~ ~s(toolnexus_llm_requests_total{model="gpt-x",status="ok"} 2)
    assert text =~ ~s(toolnexus_tool_calls_total{tool="add",source="native",is_error="false",pending="false"} 1)
    assert String.ends_with?(text, "\n")
  end

  # ---- on_metric semantic events ----

  test "on_metric: llm / tool / run events fire with aggregated numbers" do
    {base, _agent} =
      start_stub([
        fn _ -> openai_calls([{"c1", "add", ~s({"a":2,"b":3})}]) end,
        fn _ -> openai_text("5") end
      ])

    {:ok, events} = Agent.start_link(fn -> [] end)
    client = make_client(base, on_metric: fn ev -> Agent.update(events, &(&1 ++ [ev])) end)
    Client.run(client, "add", [add_tool()])

    evs = Agent.get(events, & &1)
    assert Enum.count(evs, &(&1.event == "llm")) == 2
    assert Enum.count(evs, &(&1.event == "tool")) == 1

    run_ev = List.last(evs)
    assert %{event: "run", model: "gpt-x", turns: 2, tool_calls: 1, total_tokens: 18} = run_ev
  end
end
