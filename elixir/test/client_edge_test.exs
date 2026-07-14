defmodule Toolnexus.ClientEdgeTest do
  @moduledoc """
  Client-loop edge branches the main client suite doesn't reach: anthropic
  streaming tool rounds, deadlines, retry variants, env key resolution, hook
  fallbacks, and error propagation.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Answer, Request, Tool, ToolResult}
  alias Toolnexus.Client
  alias Toolnexus.Client.MetricsRegistry

  # ---- stub LLM: {:json, status, map} | {:sse, text} | {:text, status, binary} ----

  defmodule Stub do
    @behaviour Plug
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, raw, conn} = read_body(conn)
      body = if raw == "", do: nil, else: Jason.decode!(raw)

      handler =
        Agent.get_and_update(agent, fn st ->
          case st.handlers do
            [h | rest] -> {h, %{st | handlers: rest, captured: st.captured ++ [%{body: body, headers: Map.new(conn.req_headers)}]}}
            [] -> {nil, %{st | captured: st.captured ++ [%{body: body, headers: Map.new(conn.req_headers)}]}}
          end
        end)

      case handler && handler.(body) do
        {:json, status, resp} ->
          conn |> put_resp_content_type("application/json") |> send_resp(status, Jason.encode!(resp))

        {:sse, text} ->
          conn |> put_resp_content_type("text/event-stream") |> send_resp(200, text)

        {:text, status, text} ->
          conn |> put_resp_content_type("text/plain") |> send_resp(status, text)

        {:retry_after, value} ->
          conn
          |> put_resp_header("retry-after", value)
          |> put_resp_content_type("text/plain")
          |> send_resp(429, "throttled")

        nil ->
          conn |> put_resp_content_type("application/json") |> send_resp(500, ~s({"error":"no handler"}))
      end
    end
  end

  defp start_stub(handlers) do
    {:ok, agent} = Agent.start_link(fn -> %{handlers: handlers, captured: []} end)
    port = free_port()

    start_supervised!(
      Supervisor.child_spec({Bandit, plug: {Stub, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}}, id: make_ref())
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

  defp usage, do: %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}

  defp openai_text(text), do: {:json, 200, %{"choices" => [%{"message" => %{"role" => "assistant", "content" => text}}], "usage" => usage()}}

  defp anthropic_text(text),
    do: {:json, 200, %{"content" => [%{"type" => "text", "text" => text}], "usage" => %{"input_tokens" => 1, "output_tokens" => 1}}}

  defp anthropic_tool_use(id, name, input) do
    {:json, 200,
     %{
       "content" => [%{"type" => "tool_use", "id" => id, "name" => name, "input" => input}],
       "usage" => %{"input_tokens" => 1, "output_tokens" => 1}
     }}
  end

  defp tool(name, fun) do
    %Tool{
      name: name,
      description: "t #{name}",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "native",
      execute: fun
    }
  end

  defp echo_tool, do: tool("echo", fn args, _ -> ToolResult.ok("echoed: #{args["text"]}") end)

  defp pending_tool do
    tool("suspend", fn _args, ctx ->
      if ctx && ctx.answer do
        ToolResult.ok("resumed")
      else
        %ToolResult{
          output: "need input",
          is_error: true,
          metadata: %{pending: %Request{id: "r1", kind: "input", prompt: "need input"}}
        }
      end
    end)
  end

  defp client(url, opts \\ []) do
    Client.create(
      Keyword.merge([base_url: url, style: "openai", model: "m", api_key: "k", retries: 0], opts)
    )
  end

  # SSE builders ---------------------------------------------------------------

  defp sse(lines), do: Enum.map_join(lines, "", &("data: " <> Jason.encode!(&1) <> "\n\n"))

  defp anthropic_tool_sse do
    ": comment\n" <>
      sse([
        %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 2, "output_tokens" => 0}}},
        %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "text"}},
        %{"type" => "content_block_delta", "index" => 0, "delta" => %{"type" => "text_delta", "text" => "calling"}},
        %{"type" => "content_block_start", "index" => 1, "content_block" => %{"type" => "tool_use", "id" => "c1", "name" => "echo"}},
        %{"type" => "content_block_delta", "index" => 1, "delta" => %{"type" => "input_json_delta", "partial_json" => ~s({"text":)}},
        %{"type" => "content_block_delta", "index" => 1, "delta" => %{"type" => "input_json_delta", "partial_json" => ~s("hi"})}},
        %{"type" => "content_block_delta", "index" => 1, "delta" => %{"type" => "unknown_delta"}},
        %{"type" => "totally_unknown"},
        %{"type" => "message_delta", "delta" => %{"stop_reason" => "tool_use"}, "usage" => %{"output_tokens" => 3}}
      ]) <> "data: not-json\n\n"
  end

  defp anthropic_text_sse(text, stop \\ "end_turn") do
    sse([
      %{"type" => "message_start", "message" => %{"usage" => %{"input_tokens" => 1, "output_tokens" => 0}}},
      %{"type" => "content_block_start", "index" => 0, "content_block" => %{"type" => "text"}},
      %{"type" => "content_block_delta", "index" => 0, "delta" => %{"type" => "text_delta", "text" => text}},
      %{"type" => "message_delta", "delta" => %{"stop_reason" => stop}, "usage" => %{"output_tokens" => 1}}
    ])
  end

  # --- anthropic streaming -----------------------------------------------------------

  test "anthropic streaming: tool_use round (json deltas) then final text" do
    {url, _} = start_stub([fn _ -> {:sse, anthropic_tool_sse()} end, fn _ -> {:sse, anthropic_text_sse("done!")} end])
    c = client(url, style: "anthropic")

    events = Client.stream(c, "go", [echo_tool()]) |> Enum.to_list()

    assert Enum.any?(events, &match?(%{type: "tool_call", name: "echo", args: %{"text" => "hi"}}, &1))
    assert Enum.any?(events, &match?(%{type: "tool_result", output: "echoed: hi"}, &1))
    assert %{type: "done", result: result} = List.last(events)
    assert result.text == "done!"
    assert result.tool_call_count == 1
  end

  test "anthropic streaming: suspension emits pending and halts the run as pending" do
    {url, _} = start_stub([fn _ -> {:sse, anthropic_tool_sse()} end])
    c = client(url, style: "anthropic")

    events = Client.stream(c, "go", [tool("echo", fn _, _ ->
      %ToolResult{output: "p", is_error: true, metadata: %{pending: %Request{id: "r9", kind: "input", prompt: "p"}}}
    end)]) |> Enum.to_list()

    assert Enum.any?(events, &match?(%{type: "pending", request: %Request{id: "r9"}}, &1))
    assert %{type: "done", result: %{status: "pending", pending: %Request{id: "r9"}}} = List.last(events)
  end

  test "anthropic streaming with :id loads and saves history; max_turns caps immediately" do
    {url, agent} = start_stub([fn _ -> {:sse, anthropic_text_sse("hi again")} end])
    c = client(url, style: "anthropic")

    store = Client.conversation_store(c)
    Toolnexus.Client.InMemoryConversationStore.save(store, "conv", [
      %{"role" => "user", "content" => "before"},
      %{"role" => "assistant", "content" => [%{"type" => "text", "text" => "earlier"}]}
    ])

    events = Client.stream(c, "next", [], id: "conv") |> Enum.to_list()
    assert %{type: "done", result: %{text: "hi again"}} = List.last(events)
    [%{body: body}] = captured(agent)
    assert length(body["messages"]) == 3

    # max_turns 0: done immediately, no LLM call
    c0 = client(url, style: "anthropic", max_turns: 0)
    events = Client.stream(c0, "x", []) |> Enum.to_list()
    assert [%{type: "done", result: %{text: "", turns: 0}}] = events
  end

  test "openai streaming: with :id history and max_turns 0" do
    {url, _} = start_stub([])

    c0 = client(url, max_turns: 0)
    events = Client.stream(c0, "x", []) |> Enum.to_list()
    assert [%{type: "done", result: %{text: "", turns: 0}}] = events

    {url2, agent2} =
      start_stub([
        fn _ ->
          {:sse,
           "data: " <>
             Jason.encode!(%{"choices" => [%{"delta" => %{"content" => "ok"}}]}) <>
             "\n\ndata: garbage\n\ndata: [DONE]\n\n"}
        end
      ])

    c = client(url2)
    store = Client.conversation_store(c)
    Toolnexus.Client.InMemoryConversationStore.save(store, "cv", [%{"role" => "user", "content" => "old"}])

    events = Client.stream(c, "new", [], id: "cv") |> Enum.to_list()
    assert %{type: "done", result: %{text: "ok"}} = List.last(events)
    [%{body: body}] = captured(agent2)
    assert Enum.map(body["messages"], & &1["content"]) == ["old", "new"]
  end

  test "streaming: an LLM failure is re-raised out of the stream (openai + anthropic)" do
    port = free_port()
    dead = "http://127.0.0.1:#{port}"

    assert_raise Req.TransportError, fn ->
      Client.stream(client(dead), "x", []) |> Enum.to_list()
    end

    assert_raise Req.TransportError, fn ->
      Client.stream(client(dead, style: "anthropic"), "x", []) |> Enum.to_list()
    end
  end

  # --- anthropic non-streaming edges ---------------------------------------------------

  test "anthropic: ask with id threads history; max_turns 0 short-circuits" do
    {url, agent} = start_stub([fn _ -> anthropic_text("one") end, fn _ -> anthropic_text("two") end])
    c = client(url, style: "anthropic")

    assert Client.ask(c, "first", [], id: "conv").text == "one"
    assert Client.ask(c, "second", [], id: "conv").text == "two"

    [_, %{body: second}] = captured(agent)
    assert Enum.map(second["messages"], & &1["role"]) == ["user", "assistant", "user"]

    c0 = client(url, style: "anthropic", max_turns: 0)
    assert Client.run(c0, "x", []).turns == 0
  end

  test "anthropic: LLM transport failure emits a run error and raises" do
    port = free_port()
    {:ok, events} = Agent.start_link(fn -> [] end)

    c =
      client("http://127.0.0.1:#{port}",
        style: "anthropic",
        on_metric: fn ev -> Agent.update(events, &(&1 ++ [ev])) end
      )

    assert_raise Req.TransportError, fn -> Client.run(c, "x", []) end
    assert Enum.any?(Agent.get(events, & &1), &(&1.event == "run" and Map.has_key?(&1, :error)))
  end

  test "anthropic non-streaming: suspension without wait_for halts pending (G3 anthropic path)" do
    {url, _} = start_stub([fn _ -> anthropic_tool_use("c1", "suspend", %{}) end])
    c = client(url, style: "anthropic")

    result = Client.run(c, "x", [pending_tool()])
    assert result.status == "pending"
    assert result.pending.prompt == "need input"
  end

  test "anthropic: resolve_pending re-executes with the answer and after_tool transforms it" do
    {url, _} = start_stub([fn _ -> anthropic_tool_use("c1", "suspend", %{}) end, fn _ -> anthropic_text("finished") end])

    c =
      client(url,
        style: "anthropic",
        wait_for: fn %Request{id: id} -> %Answer{id: id, ok: true, data: %{"v" => 1}} end,
        hooks: %{
          after_tool: fn %{result: r} ->
            if r.output == "resumed", do: %{result: %ToolResult{output: "resumed+hooked"}}, else: :keep
          end
        }
      )

    result = Client.run(c, "x", [pending_tool()])
    assert result.status == "done"
    assert Enum.map(result.tool_calls, & &1.output) == ["resumed+hooked"]
  end

  # --- deadline / retry / key resolution ------------------------------------------------

  test "timeout_ms: an exceeded deadline raises; an armed deadline still allows fast runs" do
    {url, _} = start_stub([])

    c = client(url, timeout_ms: 0)
    assert_raise RuntimeError, ~r/run timeout after 0ms/, fn -> Client.run(c, "x", []) end

    {url2, _} = start_stub([openai_text("fast") |> then(fn resp -> fn _ -> resp end end)])
    c2 = client(url2, timeout_ms: 5_000)
    assert Client.run(c2, "x", []).text == "fast"
  end

  test "retry: plain 429 backs off, Retry-After seconds honored, bad value falls back" do
    {url, agent} =
      start_stub([
        fn _ -> {:text, 429, "slow down"} end,
        fn _ -> openai_text("after-retry") end
      ])

    c = client(url, retries: 1, retry_base_ms: 1)
    assert Client.run(c, "x", []).text == "after-retry"
    assert length(captured(agent)) == 2

    # honored Retry-After: 1 → ~1s pause, then success
    {url2, _} = start_stub([fn _ -> {:retry_after, "1"} end, fn _ -> openai_text("waited") end])
    t0 = System.monotonic_time(:millisecond)
    assert Client.run(client(url2, retries: 1, retry_base_ms: 1), "x", []).text == "waited"
    assert System.monotonic_time(:millisecond) - t0 >= 900

    # unparseable Retry-After falls back to the exponential backoff
    {url3, _} = start_stub([fn _ -> {:retry_after, "soon"} end, fn _ -> openai_text("backed-off") end])
    assert Client.run(client(url3, retries: 1, retry_base_ms: 1), "x", []).text == "backed-off"
  end

  test "a network error is retried then re-raised when attempts are exhausted" do
    port = free_port()
    c = client("http://127.0.0.1:#{port}", retries: 1, retry_base_ms: 1)
    assert_raise Req.TransportError, fn -> Client.run(c, "x", []) end
  end

  test "non-2xx with a text body raises with the raw body text" do
    {url, _} = start_stub([fn _ -> {:text, 400, "bad request body"} end])
    c = client(url)
    assert_raise RuntimeError, ~r/LLM 400: bad request body/, fn -> Client.run(c, "x", []) end
  end

  test "streaming non-2xx raises the LLM error" do
    {url, _} = start_stub([fn _ -> {:json, 400, %{"error" => "nope"}} end])
    c = client(url)

    assert_raise RuntimeError, ~r/LLM 400/, fn ->
      Client.stream(c, "x", []) |> Enum.to_list()
    end
  end

  test "api key resolution: env fallbacks per style, ArgumentError when nothing set" do
    saved =
      for name <- ~w(OPENROUTER_API_KEY OPENAI_API_KEY ANTHROPIC_API_KEY), into: %{} do
        {name, System.get_env(name)}
      end

    on_exit(fn ->
      Enum.each(saved, fn
        {k, nil} -> System.delete_env(k)
        {k, v} -> System.put_env(k, v)
      end)
    end)

    Enum.each(Map.keys(saved), &System.delete_env/1)

    {url, agent} = start_stub([fn _ -> openai_text("via-openai-env") end, fn _ -> anthropic_text("via-anthropic-env") end])

    assert_raise ArgumentError, ~r/No API key/, fn ->
      Client.run(Client.create(base_url: url, style: "openai", model: "m"), "x", [])
    end

    System.put_env("OPENAI_API_KEY", "env-oai")
    c = Client.create(base_url: url, style: "openai", model: "m", retries: 0, headers: %{"X-Custom" => "1"})
    assert Client.run(c, "x", []).text == "via-openai-env"

    System.delete_env("OPENAI_API_KEY")
    System.put_env("ANTHROPIC_API_KEY", "env-ant")
    c = Client.create(base_url: url, style: "anthropic", model: "m", retries: 0)
    assert Client.run(c, "x", []).text == "via-anthropic-env"

    [openai_req, anthropic_req] = captured(agent)
    assert openai_req.headers["authorization"] == "Bearer env-oai"
    assert openai_req.headers["x-custom"] == "1"
    assert anthropic_req.headers["x-api-key"] == "env-ant"
  end

  # --- hooks + toolkit-protocol fallbacks -----------------------------------------------

  test "hook fallbacks: before_llm non-map, after_llm fires, before_tool args rewrite" do
    {:ok, seen} = Agent.start_link(fn -> [] end)

    {url, _} =
      start_stub([
        fn _ ->
          {:json, 200,
           %{
             "choices" => [
               %{
                 "message" => %{
                   "role" => "assistant",
                   "content" => nil,
                   "tool_calls" => [
                     %{"id" => "c1", "type" => "function", "function" => %{"name" => "echo", "arguments" => ~s({"text":"orig"})}}
                   ]
                 }
               }
             ],
             "usage" => usage()
           }}
        end,
        fn _ -> openai_text("done") end
      ])

    c =
      client(url,
        hooks: %{
          before_llm: fn _ -> :not_a_map end,
          after_llm: fn ev -> Agent.update(seen, &(&1 ++ [ev.turn])) end,
          before_tool: fn %{args: args} -> %{args: Map.put(args, "text", "rewritten")} end,
          after_tool: fn _ -> :not_a_map end
        }
      )

    result = Client.run(c, "x", [echo_tool()])
    assert result.text == "done"
    assert [%{output: "echoed: rewritten"}] = result.tool_calls
    assert Agent.get(seen, & &1) == [0, 1]
  end

  test "tool not found, raw raise, and non-ToolResult returns are normalized" do
    {url, _} =
      start_stub([
        fn _ ->
          {:json, 200,
           %{
             "choices" => [
               %{
                 "message" => %{
                   "role" => "assistant",
                   "content" => nil,
                   "tool_calls" => [
                     %{"id" => "c1", "type" => "function", "function" => %{"name" => "ghost", "arguments" => "{}"}},
                     %{"id" => "c2", "type" => "function", "function" => %{"name" => "raiser", "arguments" => "{}"}},
                     %{"id" => "c3", "type" => "function", "function" => %{"name" => "barestr", "arguments" => "{}"}}
                   ]
                 }
               }
             ],
             "usage" => usage()
           }}
        end,
        fn _ -> openai_text("done") end
      ])

    raiser = tool("raiser", fn _, _ -> raise "tool blew up" end)
    barestr = tool("barestr", fn _, _ -> "just a string" end)

    result = Client.run(client(url), "x", [raiser, barestr])
    assert [ghost, raised, bare] = result.tool_calls
    assert ghost == %{name: "ghost", args: %{}, output: "Tool not found: ghost", is_error: true, metadata: nil}
    assert raised.output == "tool blew up"
    assert raised.is_error
    assert bare == %{name: "barestr", args: %{}, output: "just a string", is_error: false, metadata: nil}
  end

  test "toolkit protocol fallbacks: nil toolkit and a prompt function" do
    {url, agent} = start_stub([fn _ -> openai_text("a") end, fn _ -> openai_text("b") end])

    assert Client.run(client(url), "x", nil).text == "a"
    [%{body: first}] = captured(agent)
    refute Map.has_key?(first, "tools")

    assert Client.run(client(url), "x", %{tools: [], prompt: fn -> "FN PROMPT" end}).text == "b"
    [_, %{body: second}] = captured(agent)
    assert [%{"role" => "system", "content" => "FN PROMPT"} | _] = second["messages"]
  end

  test "MetricsRegistry ignores unknown events" do
    pid = MetricsRegistry.new()
    MetricsRegistry.record(pid, %{event: "mystery"})
    assert MetricsRegistry.render(pid) =~ "# HELP toolnexus_llm_requests_total"
  end
end
