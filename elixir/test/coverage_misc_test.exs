defmodule Toolnexus.CoverageMiscTest do
  @moduledoc """
  Edge-branch tests for the smaller modules (types, protocol, builtin, http,
  serve, mcp_serve, a2a, SSE parser) — the uncovered branches the main suites
  don't reach.
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Answer, Builtin, Context, Http, Request, Serve, Tool, Toolkit, ToolResult}
  alias Toolnexus.Mcp.Protocol
  alias Toolnexus.Mcp.Transport.Sse

  defp run_builtin(name, args) do
    tool = Enum.find(Builtin.tools(), &(&1.name == name))
    tool.execute.(args, %Context{})
  end

  defp tmp_dir! do
    dir = Path.join(System.tmp_dir!(), "toolnexus-misc-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    on_exit(fn -> File.rm_rf!(dir) end)
    dir
  end

  defp free_port do
    {:ok, sock} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(sock)
    :gen_tcp.close(sock)
    port
  end

  # --- types.ex ------------------------------------------------------------------

  test "Request JSON: byte-identical keys, optional fields only when set, round-trip" do
    minimal = %Request{id: "r1", kind: "input", prompt: "p"}
    assert Jason.decode!(Jason.encode!(minimal)) == %{"id" => "r1", "kind" => "input", "prompt" => "p"}

    full = %Request{
      id: "r2",
      kind: "authorization",
      prompt: "auth",
      url: "https://x",
      data: %{"a" => 1},
      expires_at: "2026-01-01T00:00:00Z"
    }

    encoded = Jason.decode!(Jason.encode!(full))

    assert encoded == %{
             "id" => "r2",
             "kind" => "authorization",
             "prompt" => "auth",
             "url" => "https://x",
             "data" => %{"a" => 1},
             "expiresAt" => "2026-01-01T00:00:00Z"
           }

    assert Request.from_map(encoded) == full
  end

  test "Answer JSON: optional data/reason only when set, round-trip" do
    assert Jason.decode!(Jason.encode!(%Answer{id: "a1", ok: true})) == %{"id" => "a1", "ok" => true}

    full = %Answer{id: "a2", ok: false, data: %{"v" => 2}, reason: "declined"}
    encoded = Jason.decode!(Jason.encode!(full))
    assert encoded == %{"id" => "a2", "ok" => false, "data" => %{"v" => 2}, "reason" => "declined"}
    assert Answer.from_map(encoded) == full
  end

  test "ToolResult.pending? is false for plain results and true only for a Request" do
    refute ToolResult.pending?(ToolResult.ok("x"))
    refute ToolResult.pending?(%ToolResult{output: "x", metadata: %{pending: "not-a-request"}})
    assert ToolResult.pending?(%ToolResult{metadata: %{pending: %Request{id: "1", kind: "k", prompt: "p"}}})
  end

  # --- mcp/protocol.ex --------------------------------------------------------------

  test "protocol: framing helpers and classification" do
    assert Protocol.protocol_version() == "2025-06-18"
    assert is_binary(Protocol.client_version())

    assert Protocol.error_response(7, -32601, "nope") ==
             %{"jsonrpc" => "2.0", "id" => 7, "error" => %{"code" => -32601, "message" => "nope"}}

    assert Protocol.ping_response(3) == %{"jsonrpc" => "2.0", "id" => 3, "result" => %{}}
    assert Protocol.encode_line(%{"a" => 1}) == ~s({"a":1}\n)

    assert Protocol.classify(%{"method" => "notifications/x"}) == {:notification, "notifications/x", %{}}
    assert Protocol.classify(%{"id" => 1, "error" => %{"code" => 1}}) == {:response, 1, {:error, %{"code" => 1}}}
    assert Protocol.classify(%{"id" => 1}) == :unknown
    assert Protocol.classify("junk") == :unknown
  end

  test "protocol: elicitation url mode without a url keeps a bare authorization request" do
    req = Protocol.elicitation_to_request(%{"mode" => "url", "message" => "go"})
    assert req.kind == "authorization"
    assert req.prompt == "go"
    assert req.url == nil

    # form mode without a schema keeps a bare input request
    req = Protocol.elicitation_to_request(%{"message" => "m"})
    assert req.kind == "input"
    assert req.data == nil

    assert Protocol.answer_to_elicit_result(%Answer{id: "1", ok: true, data: %{"v" => 1}}) ==
             %{"action" => "accept", "content" => %{"v" => 1}}

    assert Protocol.answer_to_elicit_result(%Answer{id: "1", ok: false, reason: "declined"}) ==
             %{"action" => "decline"}

    assert Protocol.answer_to_elicit_result(%Answer{id: "1", ok: false}) == %{"action" => "cancel"}
  end

  # --- SSE parser -----------------------------------------------------------------

  test "Sse: comments, unknown fields, no-space data, event names, trailing flush" do
    events = Sse.parse_all(": a comment\nid: 5\nevent: custom\ndata:one\ndata: two\n\nevent: tail\ndata: end")
    assert events == [%{event: "custom", data: "one\ntwo"}, %{event: "tail", data: "end"}]

    # feed splits across chunk boundaries; blank line with no data dispatches nothing
    {evs, st} = Sse.feed(Sse.new(), "\n\ndata: he")
    assert evs == []
    {evs, _st} = Sse.feed(st, "llo\n\n")
    assert evs == [%{event: "message", data: "hello"}]
  end

  # --- builtin edge branches ---------------------------------------------------------

  test "builtin wrapper: a raise inside a tool body becomes a prefixed error" do
    # str/1 on a map raises Protocol.UndefinedError inside `read`
    result = run_builtin("read", %{"path" => %{"boom" => true}})
    assert result.is_error
    assert result.output =~ "read:"
  end

  test "bash: missing workdir errors, timeout kills the child" do
    result = run_builtin("bash", %{"command" => "echo hi", "workdir" => "/nonexistent/dir/xyz"})
    assert result.is_error
    assert result.output =~ "bash:"

    result = run_builtin("bash", %{"command" => "sleep 5", "timeout" => 150})
    assert result.is_error
    assert result.output =~ "bash: command timed out after 150ms"
  end

  test "write/edit/grep/glob/apply_patch: required-arg guards" do
    assert run_builtin("write", %{"content" => "x"}).output == "write: path is required"
    assert run_builtin("edit", %{"oldString" => "a", "newString" => "b"}).output == "edit: path is required"
    assert run_builtin("grep", %{}).output == "grep: pattern is required"
    assert run_builtin("glob", %{}).output == "glob: pattern is required"
    assert run_builtin("apply_patch", %{}).output == "apply_patch: patchText is required"
  end

  test "edit: missing file reports the posix error" do
    result = run_builtin("edit", %{"path" => "/nonexistent/f.txt", "oldString" => "a", "newString" => "b"})
    assert result.is_error
    assert result.output =~ "edit: /nonexistent/f.txt:"
  end

  test "glob: ? and ** without slash, missing root, dangling symlink skipped" do
    dir = tmp_dir!()
    File.write!(Path.join(dir, "foo.txt"), "")
    File.write!(Path.join(dir, "fox.txt"), "")
    File.write!(Path.join(dir, "notes.md"), "")
    File.ln_s!(Path.join(dir, "gone"), Path.join(dir, "dangling"))

    result = run_builtin("glob", %{"pattern" => "fo?.txt", "path" => dir})
    assert result.output == "foo.txt\nfox.txt"

    result = run_builtin("glob", %{"pattern" => "**.md", "path" => dir})
    assert result.output == "notes.md"

    result = run_builtin("glob", %{"pattern" => "*", "path" => Path.join(dir, "missing")})
    assert result.output == ""
  end

  test "grep: unreadable file is skipped" do
    dir = tmp_dir!()
    File.write!(Path.join(dir, "ok.txt"), "needle")
    locked = Path.join(dir, "locked.txt")
    File.write!(locked, "needle")
    File.chmod!(locked, 0o000)
    on_exit(fn -> File.chmod(locked, 0o644) end)

    result = run_builtin("grep", %{"pattern" => "needle", "path" => dir})
    assert result.output == "#{Path.join(dir, "ok.txt")}:1:needle"
  end

  test "webfetch: a connection error is a webfetch-prefixed error" do
    port = free_port()
    result = run_builtin("webfetch", %{"url" => "http://127.0.0.1:#{port}/x", "timeout" => 1})
    assert result.is_error
    assert result.output =~ "webfetch:"
  end

  test "apply_patch: grammar errors and update semantics" do
    result = run_builtin("apply_patch", %{"patchText" => "hello"})
    assert result.output == "apply_patch: missing '*** Begin Patch'"

    dir = tmp_dir!()

    # update on a missing file aborts with no writes
    patch = """
    *** Begin Patch
    *** Update File: #{Path.join(dir, "absent.txt")}
    -a
    +b
    *** End Patch
    """

    result = run_builtin("apply_patch", %{"patchText" => patch})
    assert result.is_error
    assert result.output =~ "apply_patch:"

    # context lines (space-prefixed and bare), blank lines between ops, and a
    # pure-insertion hunk on a file without a trailing newline
    target = Path.join(dir, "t.txt")
    File.write!(target, "keep\nold\ntail")
    grow = Path.join(dir, "grow.txt")
    File.write!(grow, "base")

    patch = """
    *** Begin Patch

    *** Update File: #{target}
     keep
    -old
    +new
    tail
    *** Update File: #{grow}
    +appended
    *** End Patch
    """

    result = run_builtin("apply_patch", %{"patchText" => patch})
    refute result.is_error
    assert File.read!(target) == "keep\nnew\ntail"
    assert File.read!(grow) == "base\nappended"
  end

  # --- http.ex edge branches -----------------------------------------------------------

  test "http tool: missing placeholder arg becomes empty, POST with no leftover args sends no body" do
    port = free_port()
    {:ok, agent} = Agent.start_link(fn -> nil end)

    defmodule EchoPathPlug do
      import Plug.Conn

      def init(agent), do: agent

      def call(conn, agent) do
        {:ok, body, conn} = read_body(conn)
        Agent.update(agent, fn _ -> %{path: conn.request_path, body: body} end)
        send_resp(conn, 200, "ok")
      end
    end

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {EchoPathPlug, agent}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    tool =
      Http.tool(
        name: "t",
        description: "d",
        method: "POST",
        url: "http://127.0.0.1:#{port}/items/{id}"
      )

    result = tool.execute.(%{"id" => 42}, %Context{})
    refute result.is_error
    assert Agent.get(agent, & &1) == %{path: "/items/42", body: ""}

    # missing placeholder → empty segment (str(nil))
    result = tool.execute.(%{}, %Context{})
    refute result.is_error
    assert Agent.get(agent, & &1).path == "/items/"
  end

  test "http tool: transport error and a raised option error both map to error results" do
    port = free_port()

    dead =
      Http.tool(name: "t", description: "d", method: "GET", url: "http://127.0.0.1:#{port}/x")

    result = dead.execute.(%{}, %Context{})
    assert result.is_error

    bad =
      Http.tool(
        name: "t2",
        description: "d",
        method: "GET",
        url: "http://127.0.0.1:#{port}/x",
        req_options: [not_a_real_option: 1]
      )

    result = bad.execute.(%{}, %Context{})
    assert result.is_error
  end

  # --- serve.ex leftovers ---------------------------------------------------------------

  defmodule ExplodingStore do
    @behaviour Toolnexus.Serve.TaskStore
    defstruct [:inner]

    @impl true
    def get(%__MODULE__{inner: inner}, id), do: Serve.InMemoryTaskStore.get(inner, id)

    @impl true
    def save(%__MODULE__{inner: inner}, task) do
      if task["status"]["state"] in ["completed", "failed"], do: raise("store down")
      Serve.InMemoryTaskStore.save(inner, task)
    end
  end

  test "fulfilment isolates a store write failure and an on_task raise" do
    {:ok, seen} = Agent.start_link(fn -> [] end)
    store = %ExplodingStore{inner: Serve.InMemoryTaskStore.new()}

    st = %{
      store: store,
      run_task: fn _text, _ctx -> %Toolnexus.Client.RunResult{text: "done", status: "done"} end,
      on_task: fn ev ->
        Agent.update(seen, &(&1 ++ [ev.state]))
        raise "listener down"
      end
    }

    assert Serve.fulfil("tid", "hello", nil, st) == :ok
    assert Agent.get(seen, & &1) == ["completed"]
  end

  test "addr parsing: bare port, 0.0.0.0 display host, unparseable host falls back" do
    tk = Toolnexus.create_toolkit!(builtins: false)

    h1 = Toolkit.serve(tk, "0")
    on_exit(fn -> Serve.stop(h1) end)
    assert h1.url == "http://127.0.0.1:#{h1.port}"

    h2 = Toolkit.serve(tk, "0.0.0.0:0")
    on_exit(fn -> Serve.stop(h2) end)
    assert h2.url =~ "http://127.0.0.1:"

    h3 = Toolkit.serve(tk, "localhost:0")
    on_exit(fn -> Serve.stop(h3) end)
    assert h3.url == "http://localhost:#{h3.port}"

    assert Serve.card_path() == "/.well-known/agent-card.json"
  end

  # --- mcp_serve.ex leftovers -------------------------------------------------------------

  test "mcp serve: bare string tool return is wrapped; an on_call raise is isolated" do
    bare = %Tool{
      name: "bare",
      description: "returns a bare string",
      input_schema: %{"type" => "object", "properties" => %{}},
      source: "custom",
      execute: fn _args, _ctx -> "just text" end
    }

    tk = Toolnexus.create_toolkit!(extra_tools: [bare], builtins: false)
    handle = Toolkit.serve(tk, "127.0.0.1:0", mcp: %{}, on_call: fn _ev -> raise "sink down" end)
    on_exit(fn -> Serve.stop(handle) end)

    resp =
      Req.post!(
        url: handle.url <> "/mcp",
        json: %{"jsonrpc" => "2.0", "id" => 1, "method" => "tools/call", "params" => %{"name" => "bare"}},
        retry: false
      )

    assert resp.body["result"] == %{
             "content" => [%{"type" => "text", "text" => "just text"}],
             "isError" => false
           }
  end

  # --- a2a.ex leftovers -----------------------------------------------------------------

  test "a2a: a non-JSON 200 GetTask body is tolerated (result nil) until the budget expires" do
    defmodule GarbageAgent do
      import Plug.Conn

      def init(base), do: base

      def call(conn, base) do
        cond do
          conn.method == "GET" ->
            conn
            |> put_resp_content_type("application/json")
            |> send_resp(
              200,
              Jason.encode!(%{"name" => "G", "url" => base.(), "skills" => [%{"id" => "s"}]})
            )

          true ->
            {:ok, body, conn} = read_body(conn)
            rpc = Jason.decode!(body)

            case rpc["method"] do
              "SendMessage" ->
                conn
                |> put_resp_content_type("application/json")
                |> send_resp(
                  200,
                  Jason.encode!(%{
                    "jsonrpc" => "2.0",
                    "id" => rpc["id"],
                    "result" => %{"id" => "g1", "status" => %{"state" => "working"}}
                  })
                )

              _ ->
                send_resp(conn, 200, "not json at all")
            end
        end
      end
    end

    port = free_port()
    base = "http://127.0.0.1:#{port}"

    start_supervised!(
      Supervisor.child_spec(
        {Bandit, plug: {GarbageAgent, fn -> base <> "/" end}, scheme: :http, port: port, ip: {127, 0, 0, 1}},
        id: make_ref()
      )
    )

    [tool] =
      Toolnexus.A2a.agent_tools(
        Toolnexus.A2a.agent(card: base <> "/card.json", timeout: 150, poll_every: 20)
      )

    result = tool.execute.(%{"task" => "x"}, %Context{})
    assert result.is_error
    assert result.output =~ "timed out after 150ms (state=working)"
  end

  # --- top-level delegates -----------------------------------------------------------------

  test "Toolnexus.define_tool delegates to Native" do
    tool = Toolnexus.define_tool(name: "d", description: "x", execute: fn _ -> "y" end)
    assert %Tool{name: "d", source: "native"} = tool
    assert tool.execute.(%{}, %Context{}).output == "y"
  end
end
