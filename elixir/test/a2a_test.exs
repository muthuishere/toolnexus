defmodule Toolnexus.A2aTest do
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias Toolnexus.{A2a, Context, Toolkit}

  @moduletag timeout: 30_000

  # ---------------------------------------------------------------------------
  # Stub A2A agent: serves the Agent Card + a scripted SendMessage/GetTask flow.
  # `mode` picks the task lifecycle; every request is recorded in the agent.
  # ---------------------------------------------------------------------------

  defmodule StubAgent do
    @behaviour Plug
    import Plug.Conn

    def init(agent), do: agent

    def call(conn, agent) do
      {:ok, body, conn} = read_body(conn)
      st = Agent.get(agent, & &1)

      cond do
        conn.method == "GET" and conn.request_path == "/.well-known/agent-card.json" ->
          Agent.update(agent, &%{&1 | headers: Map.new(conn.req_headers)})

          case st.card_status do
            200 -> json(conn, 200, st.card)
            status -> send_resp(conn, status, "no card")
          end

        conn.method == "POST" ->
          rpc = Jason.decode!(body)
          Agent.update(agent, &%{&1 | rpcs: &1.rpcs ++ [rpc]})
          handle(conn, agent, st, rpc)

        true ->
          send_resp(conn, 404, "nope")
      end
    end

    defp handle(conn, _agent, %{mode: :rpc_error}, %{"method" => "SendMessage", "id" => id}) do
      json(conn, 200, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "error" => %{"code" => -32000, "message" => "agent exploded"}
      })
    end

    defp handle(conn, _agent, _st, %{"method" => "SendMessage", "id" => id}) do
      json(conn, 200, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "result" => %{"id" => "t1", "status" => %{"state" => "submitted"}}
      })
    end

    defp handle(conn, agent, st, %{"method" => "GetTask", "id" => id, "params" => %{"id" => task_id}}) do
      polls = Agent.get_and_update(agent, fn s -> {s.polls + 1, %{s | polls: s.polls + 1}} end)

      task =
        case st.mode do
          :completed ->
            if polls < 2 do
              %{"id" => task_id, "status" => %{"state" => "working"}}
            else
              %{
                "id" => task_id,
                "status" => %{"state" => "completed"},
                "artifacts" => [
                  %{"parts" => [%{"kind" => "text", "text" => "part one"}, %{"kind" => "data"}]},
                  %{"parts" => [%{"kind" => "text", "text" => "part two"}]}
                ]
              }
            end

          :history_fallback ->
            %{
              "id" => task_id,
              "status" => %{"state" => "completed"},
              "artifacts" => [],
              "history" => [
                %{"role" => "user", "parts" => [%{"kind" => "text", "text" => "ask"}]},
                %{"role" => "agent", "parts" => [%{"kind" => "text", "text" => "from history"}]}
              ]
            }

          :failed ->
            %{
              "id" => task_id,
              "status" => %{
                "state" => "failed",
                "message" => %{"role" => "agent", "parts" => [%{"kind" => "text", "text" => "boom"}]}
              }
            }

          :canceled ->
            %{"id" => task_id, "status" => %{"state" => "canceled"}}

          # input-required is NOT terminal for the outbound poller (JS parity):
          # the loop keeps polling until its budget expires.
          :input_required ->
            %{"id" => task_id, "status" => %{"state" => "input-required"}}
        end

      json(conn, 200, %{"jsonrpc" => "2.0", "id" => id, "result" => task})
    end

    defp handle(conn, _agent, _st, %{"id" => id}) do
      json(conn, 200, %{
        "jsonrpc" => "2.0",
        "id" => id,
        "error" => %{"code" => -32601, "message" => "no such method"}
      })
    end

    defp json(conn, status, payload) do
      conn
      |> put_resp_content_type("application/json")
      |> send_resp(status, Jason.encode!(payload))
    end
  end

  defp start_stub(opts \\ []) do
    port = free_port()
    url = "http://127.0.0.1:#{port}"

    card =
      Keyword.get(opts, :card, %{
        "name" => "Calc Agent",
        "description" => "adds things",
        "version" => "1.0.0",
        "url" => url <> "/",
        "skills" => [
          %{"id" => "add", "name" => "add", "description" => "Add two numbers"},
          %{"name" => "echo!"}
        ]
      })

    {:ok, agent} =
      Agent.start_link(fn ->
        %{
          mode: Keyword.get(opts, :mode, :completed),
          card: card,
          card_status: Keyword.get(opts, :card_status, 200),
          polls: 0,
          rpcs: [],
          headers: %{}
        }
      end)

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {StubAgent, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    {url, agent}
  end

  defp free_port do
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)
    port
  end

  defp rpcs(agent), do: Agent.get(agent, & &1.rpcs)

  # --- card resolution → tools ---------------------------------------------------

  test "agent_tools: fetches the card and emits one sanitized a2a tool per skill" do
    {url, agent} = start_stub()

    System.put_env("TOOLNEXUS_A2A_TOKEN", "sekret")
    on_exit(fn -> System.delete_env("TOOLNEXUS_A2A_TOKEN") end)

    tools =
      A2a.agent_tools(
        A2a.agent(
          card: url <> "/.well-known/agent-card.json",
          headers: %{"authorization" => "Bearer ${TOOLNEXUS_A2A_TOKEN}"}
        )
      )

    assert Enum.map(tools, & &1.name) == ["Calc_Agent_add", "Calc_Agent_echo_"]
    [add, echo] = tools
    assert add.source == "a2a"
    assert add.description == "Add two numbers"
    # skill with no description/id falls back to name
    assert echo.description == "echo!"

    assert add.input_schema == %{
             "type" => "object",
             "properties" => %{
               "task" => %{
                 "type" => "string",
                 "description" => "The task to send to the agent, in natural language."
               }
             },
             "required" => ["task"],
             "additionalProperties" => false
           }

    # ${ENV} header expanded on the card fetch and never logged
    assert Agent.get(agent, & &1.headers)["authorization"] == "Bearer sekret"
  end

  test "endpoint falls back to the card URL origin when the card has no url" do
    {url, agent} =
      start_stub(
        card: %{
          "name" => "NoUrl",
          "skills" => [%{"id" => "s", "description" => "d"}]
        }
      )

    [tool] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json"))
    result = tool.execute.(%{"task" => "x"}, %Context{})
    refute result.is_error

    # the SendMessage went to the origin (the stub answered it)
    assert Enum.any?(rpcs(agent), &(&1["method"] == "SendMessage"))
  end

  # --- execute: submit → poll → terminal state -------------------------------------

  test "completed: SendMessage then GetTask polls; artifact text joined with newlines" do
    {url, agent} = start_stub(mode: :completed)
    [add, _] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json", poll_every: 20))

    result = add.execute.(%{"task" => "add 1 and 2"}, %Context{})
    refute result.is_error
    assert result.output == "part one\npart two"
    assert result.metadata.agent == "Calc Agent"
    assert result.metadata.task_id == "t1"
    assert result.metadata.state == "completed"
    assert result.metadata.polls == 2
    assert is_integer(result.metadata.ms)

    # wire shape of the SendMessage (§7A)
    [send_msg | _] = rpcs(agent)
    assert send_msg["method"] == "SendMessage"
    assert send_msg["jsonrpc"] == "2.0"
    assert get_in(send_msg, ["params", "configuration", "blocking"]) == false
    assert get_in(send_msg, ["params", "message", "role"]) == "user"
    assert [%{"kind" => "text", "text" => "add 1 and 2"}] = get_in(send_msg, ["params", "message", "parts"])
    assert is_binary(get_in(send_msg, ["params", "message", "messageId"]))

    get_task = Enum.find(rpcs(agent), &(&1["method"] == "GetTask"))
    assert get_task["params"] == %{"id" => "t1"}
  end

  test "completed with no artifact text falls back to the last agent history message" do
    {url, _} = start_stub(mode: :history_fallback)
    [tool | _] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json", poll_every: 10))

    result = tool.execute.(%{"task" => "x"}, %Context{})
    refute result.is_error
    assert result.output == "from history"
  end

  test "failed: error result carries the state and status message detail" do
    {url, _} = start_stub(mode: :failed)
    [tool | _] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json", poll_every: 10))

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output == "A2A task t1 failed: boom"
    assert result.metadata.state == "failed"
  end

  test "canceled: error result without detail" do
    {url, _} = start_stub(mode: :canceled)
    [tool | _] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json", poll_every: 10))

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output == "A2A task t1 canceled"
  end

  test "input-required is non-terminal for the outbound poller: budget expires (JS parity)" do
    {url, _} = start_stub(mode: :input_required)

    [tool | _] =
      A2a.agent_tools(
        A2a.agent(card: url <> "/.well-known/agent-card.json", timeout: 150, poll_every: 20)
      )

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output == "A2A task t1 timed out after 150ms (state=input-required)"
    assert result.metadata.polls >= 1
  end

  test "ctx.timeout overrides the agent budget" do
    {url, _} = start_stub(mode: :input_required)

    [tool | _] =
      A2a.agent_tools(
        A2a.agent(card: url <> "/.well-known/agent-card.json", timeout: 60_000, poll_every: 20)
      )

    result = tool.execute.(%{"task" => "x"}, %Context{timeout: 120})
    assert result.is_error
    assert result.output =~ "timed out after 120ms"
  end

  test "a JSON-RPC error reply becomes an error ToolResult with the error message" do
    {url, _} = start_stub(mode: :rpc_error)
    [tool | _] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json"))

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output == "agent exploded"
    assert result.metadata.task_id == ""
  end

  test "an HTTP-level failure on the endpoint becomes an error ToolResult" do
    {url, _} = start_stub(card: %{"name" => "Dead", "url" => "http://127.0.0.1:1/", "skills" => [%{"id" => "s"}]})
    [tool] = A2a.agent_tools(A2a.agent(card: url <> "/.well-known/agent-card.json", timeout: 1_000))

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output != ""
  end

  test "card fetch failure raises from agent_tools and is isolated by the toolkit" do
    {url, _} = start_stub(card_status: 500)
    card_url = url <> "/.well-known/agent-card.json"

    assert_raise RuntimeError, ~r/HTTP 500/, fn ->
      A2a.agent_tools(A2a.agent(card: card_url))
    end

    log =
      capture_log(fn ->
        tk = Toolnexus.create_toolkit!(agents: [A2a.agent(card: card_url)], builtins: false)
        send(self(), {:tk, tk})
      end)

    assert_received {:tk, tk}
    assert Toolkit.tools(tk) == []
    assert log =~ "A2A agent"
    assert log =~ "failed"
  end

  # --- config block parsing (`agents` mirrors mcpServers) --------------------------

  test "parse_agents_config skips disabled entries and maps pollEvery" do
    block = %{
      "one" => %{"card" => "http://x/card.json", "timeout" => 5, "pollEvery" => 7},
      "off" => %{"card" => "http://y/card.json", "disabled" => true},
      "off2" => %{"card" => "http://z/card.json", "enabled" => false},
      "bad" => %{"nope" => true}
    }

    assert A2a.parse_agents_config(block) == [
             %{card: "http://x/card.json", headers: nil, timeout: 5, poll_every: 7}
           ]

    assert A2a.parse_agents_config(nil) == []
  end

  test "a top-level agents config block resolves through the toolkit" do
    {url, _} = start_stub()

    tk =
      Toolnexus.create_toolkit!(
        mcp_config: %{
          "mcpServers" => %{},
          "agents" => %{"calc" => %{"card" => url <> "/.well-known/agent-card.json"}}
        },
        builtins: false
      )

    assert Enum.map(Toolkit.tools(tk), & &1.name) == ["Calc_Agent_add", "Calc_Agent_echo_"]
  end

  test "toolkit add_agent registers tools at runtime (descriptor or bare URL)" do
    {url, _} = start_stub()
    card_url = url <> "/.well-known/agent-card.json"

    tk = Toolnexus.create_toolkit!(builtins: false)
    tk = Toolkit.add_agent(tk, card_url, poll_every: 10)
    assert "Calc_Agent_add" in Enum.map(Toolkit.tools(tk), & &1.name)

    tk2 = Toolnexus.create_toolkit!(builtins: false)
    tk2 = Toolkit.add_agent(tk2, %{card: card_url})
    assert "Calc_Agent_add" in Enum.map(Toolkit.tools(tk2), & &1.name)
  end

  test "Toolnexus.agent/1 delegate builds the descriptor" do
    assert Toolnexus.agent(card: "http://c") == %{
             card: "http://c",
             headers: nil,
             timeout: nil,
             poll_every: nil
           }
  end
end
