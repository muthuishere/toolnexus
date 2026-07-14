defmodule Toolnexus.ServeTest do
  use ExUnit.Case, async: false

  alias Toolnexus.{Client, Native, Request, Serve, Tool, Toolkit, ToolResult}
  alias Toolnexus.Serve.{FileTaskStore, InMemoryTaskStore}

  @moduletag timeout: 30_000

  # ---------------------------------------------------------------------------
  # Stub LLM (openai wire): each request pops the next scripted handler.
  # ---------------------------------------------------------------------------

  defmodule LlmStub do
    @behaviour Plug
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, raw, conn} = read_body(conn)
      body = Jason.decode!(raw)

      handler =
        Agent.get_and_update(agent, fn st ->
          case st.handlers do
            [h | rest] -> {h, %{st | handlers: rest, bodies: st.bodies ++ [body]}}
            [] -> {nil, %{st | bodies: st.bodies ++ [body]}}
          end
        end)

      resp =
        case handler && handler.(body) do
          nil -> {500, %{"error" => "no handler"}}
          {status, payload} -> {status, payload}
        end

      {status, payload} = resp

      conn
      |> put_resp_content_type("application/json")
      |> send_resp(status, Jason.encode!(payload))
    end
  end

  defp start_llm_stub(handlers) do
    {:ok, agent} = Agent.start_link(fn -> %{handlers: handlers, bodies: []} end)
    port = free_port()

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {LlmStub, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
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

  defp openai_text(text) do
    fn _body ->
      {200,
       %{
         "choices" => [%{"message" => %{"role" => "assistant", "content" => text}}],
         "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
       }}
    end
  end

  defp openai_call(name, args_json) do
    fn _body ->
      {200,
       %{
         "choices" => [
           %{
             "message" => %{
               "role" => "assistant",
               "content" => nil,
               "tool_calls" => [
                 %{"id" => "c1", "type" => "function", "function" => %{"name" => name, "arguments" => args_json}}
               ]
             }
           }
         ],
         "usage" => %{"prompt_tokens" => 1, "completion_tokens" => 1, "total_tokens" => 2}
       }}
    end
  end

  # ---------------------------------------------------------------------------
  # Fixtures.
  # ---------------------------------------------------------------------------

  defp echo_toolkit do
    Toolnexus.create_toolkit!(
      skills: [
        %{name: "greeter", description: "Says hello nicely", content: "Wave."},
        %{name: "internal", description: "Not advertised", content: "Hidden."}
      ],
      extra_tools: [
        Native.define_tool(
          name: "echo",
          description: "Echo the text back",
          execute: fn args -> "echoed: #{args["text"]}" end
        )
      ],
      builtins: false
    )
  end

  defp pending_tool do
    %Tool{
      name: "authorize",
      description: "Always suspends",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "native",
      execute: fn _args, _ctx ->
        %ToolResult{
          output: "Authorize me",
          is_error: true,
          metadata: %{pending: %Request{id: "r1", kind: "authorization", prompt: "Authorize me", url: "https://auth"}}
        }
      end
    }
  end

  defp client(base_url) do
    Client.create(base_url: base_url, style: "openai", model: "test-model", api_key: "k", retries: 0)
  end

  defp serve!(tk, opts) do
    handle = Toolkit.serve(tk, "127.0.0.1:0", opts)
    on_exit(fn -> Serve.stop(handle) end)
    handle
  end

  defp post_rpc(url, method, params) do
    body = %{"jsonrpc" => "2.0", "id" => "rq-1", "method" => method, "params" => params}
    resp = Req.post!(url: url <> "/", json: body, retry: false, decode_body: false)
    {resp.status, Jason.decode!(resp.body)}
  end

  defp send_message(url, text, context_id \\ nil) do
    message = %{
      "role" => "user",
      "messageId" => "m-1",
      "parts" => [%{"kind" => "text", "text" => text}]
    }

    message = if context_id, do: Map.put(message, "contextId", context_id), else: message
    {200, %{"result" => task}} = post_rpc(url, "SendMessage", %{"message" => message})
    task
  end

  defp await_task(url, id, states \\ ["completed", "failed", "input-required"]) do
    deadline = System.monotonic_time(:millisecond) + 5_000

    Stream.repeatedly(fn ->
      {200, %{"result" => task}} = post_rpc(url, "GetTask", %{"id" => id})

      cond do
        task["status"]["state"] in states -> task
        System.monotonic_time(:millisecond) > deadline -> :timeout
        true -> Process.sleep(25) && nil
      end
    end)
    |> Enum.find(&(&1 != nil))
  end

  # --- Agent Card (§7B) ------------------------------------------------------------

  test "GET /.well-known/agent-card.json serves the card built from skills, never tools" do
    tk = echo_toolkit()

    handle =
      serve!(tk,
        client: client("http://127.0.0.1:1"),
        a2a: %{
          name: "test-agent",
          description: "a test agent",
          version: "2.0.0",
          provider: %{organization: "deemwar", url: "https://deemwar.com"},
          skills: ["greeter"]
        }
      )

    assert handle.port > 0
    assert handle.url == "http://127.0.0.1:#{handle.port}"

    resp = Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false)
    card = resp.body

    assert card == %{
             "name" => "test-agent",
             "description" => "a test agent",
             "version" => "2.0.0",
             "protocolVersion" => "0.3.0",
             "capabilities" => %{"streaming" => false, "pushNotifications" => false},
             "defaultInputModes" => ["text"],
             "defaultOutputModes" => ["text"],
             "skills" => [%{"id" => "greeter", "name" => "greeter", "description" => "Says hello nicely"}],
             "provider" => %{"organization" => "deemwar", "url" => "https://deemwar.com"},
             "url" => handle.url <> "/"
           }
  end

  test "card defaults: name/description/version/protocol, all skills, no provider" do
    tk = echo_toolkit()
    handle = serve!(tk, client: client("http://127.0.0.1:1"), a2a: %{})

    card = Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false).body
    assert card["name"] == "toolnexus-agent"
    assert card["description"] == ""
    assert card["version"] == "0.1.0"
    assert card["protocolVersion"] == "0.3.0"
    assert Enum.map(card["skills"], & &1["id"]) == ["greeter", "internal"]
    refute Map.has_key?(card, "provider")
  end

  # --- SendMessage / GetTask lifecycle -----------------------------------------------

  test "SendMessage runs the client loop: tool call then final text → completed artifact" do
    {llm_url, llm} =
      start_llm_stub([
        openai_call("echo", ~s({"text":"hi"})),
        openai_text("The echo said: echoed: hi")
      ])

    {:ok, events} = Agent.start_link(fn -> [] end)
    tk = echo_toolkit()

    handle =
      serve!(tk,
        client: client(llm_url),
        a2a: %{name: "srv"},
        on_task: fn ev -> Agent.update(events, &(&1 ++ [ev])) end
      )

    task = send_message(handle.url, "please echo hi")
    assert %{"id" => id, "status" => %{"state" => "submitted"}} = task

    final = await_task(handle.url, id)
    assert final["status"]["state"] == "completed"
    assert [%{"artifactId" => artifact_id, "parts" => [part]}] = final["artifacts"]
    assert is_binary(artifact_id)
    assert part == %{"kind" => "text", "text" => "The echo said: echoed: hi"}

    # the LLM stub actually saw the toolkit's tools + the user prompt
    [first | _] = Agent.get(llm, & &1.bodies)
    assert Enum.any?(first["tools"], &(&1["function"]["name"] == "echo"))
    assert List.last(first["messages"])["content"] == "please echo hi"

    # onTask fired with telemetry on the terminal state
    assert [%{id: ^id, state: "completed", result: %Client.RunResult{} = rr}] = Agent.get(events, & &1)
    assert rr.tool_call_count == 1
  end

  test "contextId keys the conversation through client.ask (turns remembered)" do
    {llm_url, llm} = start_llm_stub([openai_text("first answer"), openai_text("second answer")])
    tk = echo_toolkit()
    handle = serve!(tk, client: client(llm_url), a2a: %{})

    t1 = send_message(handle.url, "turn one", "ctx-7")
    assert t1["contextId"] == "ctx-7"
    assert await_task(handle.url, t1["id"])["status"]["state"] == "completed"

    t2 = send_message(handle.url, "turn two", "ctx-7")
    assert await_task(handle.url, t2["id"])["status"]["state"] == "completed"

    # the second LLM call replays the remembered transcript of turn one
    [_, second] = Agent.get(llm, & &1.bodies)
    texts = Enum.map(second["messages"], & &1["content"])
    assert "turn one" in texts
    assert "first answer" in texts
    assert "turn two" in texts
  end

  test "a suspended run surfaces input-required carrying the request prompt, never completed" do
    {llm_url, _} = start_llm_stub([openai_call("authorize", "{}"), openai_text("never reached")])
    {:ok, events} = Agent.start_link(fn -> [] end)

    tk = echo_toolkit() |> Toolkit.register(pending_tool())

    handle =
      serve!(tk,
        client: client(llm_url),
        a2a: %{},
        on_task: fn ev -> Agent.update(events, &(&1 ++ [ev])) end
      )

    task = send_message(handle.url, "authorize please")
    final = await_task(handle.url, task["id"])

    assert final["status"]["state"] == "input-required"
    assert final["status"]["message"] == %{
             "role" => "agent",
             "parts" => [%{"kind" => "text", "text" => "Authorize me"}]
           }

    refute Map.has_key?(final, "artifacts")
    assert [%{state: "input-required", result: %Client.RunResult{status: "pending"}}] = Agent.get(events, & &1)
  end

  test "a fulfilment error becomes a failed Task (server stays alive)" do
    # dead LLM endpoint → client.run raises inside fulfilment
    {:ok, events} = Agent.start_link(fn -> [] end)
    tk = echo_toolkit()

    handle =
      serve!(tk,
        client: client("http://127.0.0.1:1"),
        a2a: %{},
        on_task: fn ev -> Agent.update(events, &(&1 ++ [ev])) end
      )

    task = send_message(handle.url, "boom")
    final = await_task(handle.url, task["id"])

    assert final["status"]["state"] == "failed"
    assert %{"role" => "agent", "parts" => [%{"kind" => "text", "text" => detail}]} = final["status"]["message"]
    assert detail != ""
    assert [%{state: "failed", result: nil}] = Agent.get(events, & &1)

    # the server is still serving
    assert Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false).status == 200
  end

  # --- JSON-RPC errors + routing -----------------------------------------------------

  test "GetTask unknown id → -32001, unknown method → -32601, parse error → -32700" do
    tk = echo_toolkit()
    handle = serve!(tk, client: client("http://127.0.0.1:1"), a2a: %{})

    {200, resp} = post_rpc(handle.url, "GetTask", %{"id" => "nope"})
    assert resp["error"] == %{"code" => -32001, "message" => "Task not found: nope"}

    {200, resp} = post_rpc(handle.url, "CancelTask", %{})
    assert resp["error"] == %{"code" => -32601, "message" => "Method not found: CancelTask"}

    resp = Req.post!(url: handle.url <> "/", body: "{not json", retry: false, decode_body: false)
    parsed = Jason.decode!(resp.body)
    assert parsed["error"] == %{"code" => -32700, "message" => "Parse error"}
    assert parsed["id"] == nil

    # non-card GET on an A2A server → 404
    assert Req.get!(url: handle.url <> "/other", retry: false).status == 404
  end

  test "no profile at all → every request 404s; stop() then close() are safe" do
    tk = echo_toolkit()
    handle = Toolkit.serve(tk, "127.0.0.1:0")

    assert Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false, decode_body: false).status ==
             404

    assert Req.post!(url: handle.url <> "/", json: %{}, retry: false, decode_body: false).status == 404

    assert Serve.stop(handle) == :ok
    assert Serve.close(handle) == :ok
  end

  test "a top-level a2a config block mounts the profile without an inline option" do
    {llm_url, _} = start_llm_stub([openai_text("ok")])

    tk =
      Toolnexus.create_toolkit!(
        mcp_config: %{"mcpServers" => %{}, "a2a" => %{"name" => "cfg-served"}},
        skills: [%{name: "s1", description: "d", content: "c"}],
        builtins: false
      )

    handle = serve!(tk, client: client(llm_url))
    card = Req.get!(url: handle.url <> "/.well-known/agent-card.json", retry: false).body
    assert card["name"] == "cfg-served"
    assert Enum.map(card["skills"], & &1["id"]) == ["s1"]
  end

  # --- TaskStore -----------------------------------------------------------------------

  test "file store: tasks persist as one JSON file per id" do
    {llm_url, _} = start_llm_stub([openai_text("stored")])
    dir = Path.join(System.tmp_dir!(), "toolnexus-tasks-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)

    tk = echo_toolkit()
    handle = serve!(tk, client: client(llm_url), a2a: %{store: "file:" <> dir})

    task = send_message(handle.url, "persist me")
    final = await_task(handle.url, task["id"])
    assert final["status"]["state"] == "completed"

    file = Path.join(dir, Toolnexus.Tool.sanitize(task["id"]) <> ".json")
    assert File.exists?(file)
    assert Jason.decode!(File.read!(file))["status"]["state"] == "completed"
  end

  test "resolve_store: memory default, file:<dir>, custom struct pass-through, unknown raises" do
    assert %InMemoryTaskStore{} = Serve.resolve_store(nil)
    assert %InMemoryTaskStore{} = Serve.resolve_store("memory")

    dir = Path.join(System.tmp_dir!(), "toolnexus-store-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    assert %FileTaskStore{dir: ^dir} = Serve.resolve_store("file:" <> dir)

    custom = InMemoryTaskStore.new()
    assert Serve.resolve_store(custom) == custom

    assert_raise ArgumentError, ~r/Unknown A2A store/, fn -> Serve.resolve_store("redis:x") end
  end

  test "FileTaskStore get: missing file and corrupt JSON both read as nil" do
    dir = Path.join(System.tmp_dir!(), "toolnexus-corrupt-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf!(dir) end)
    store = FileTaskStore.new(dir)

    assert FileTaskStore.get(store, "absent") == nil
    File.write!(Path.join(dir, "bad.json"), "{nope")
    assert FileTaskStore.get(store, "bad") == nil

    FileTaskStore.save(store, %{"id" => "t/1", "status" => %{"state" => "submitted"}})
    assert FileTaskStore.get(store, "t/1")["id"] == "t/1"
  end
end
