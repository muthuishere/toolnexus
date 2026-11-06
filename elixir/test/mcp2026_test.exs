defmodule Toolnexus.Mcp2026Test do
  # MCP 2026-07-28 client path (SPEC §2.2–§2.8, §2A), hermetic: localhost only.
  use ExUnit.Case, async: false
  import ExUnit.CaptureLog

  alias Toolnexus.{Answer, Context, Mcp, Request, Toolkit, ToolResult}
  alias Toolnexus.Mcp.{Client2026, OAuth, RemoteSkills, Token, TokenStore, Wire}

  @moduletag timeout: 120_000
  @expected Path.expand("../../examples/mcp-2026/expected.json", __DIR__)
            |> File.read!()
            |> Jason.decode!()
  @stdio_script Path.expand("support/mcp2026_stdio_server.exs", __DIR__)

  # --- helpers ------------------------------------------------------------------

  defp fixture_server(opts \\ []) do
    counts = :counters.new(3, [])

    srv =
      Mcp2026T.Server.fixture()
      |> Mcp2026T.Server.tool("tick", "counts", fn _, _ ->
        :counters.add(counts, 1, 1)
        "tick"
      end)
      |> Mcp2026T.Server.tool("charge", "counts, extra scope", fn _, _ ->
        :counters.add(counts, 2, 1)
        "charged"
      end)

    {srv, as} =
      case opts[:scopes] do
        nil ->
          {srv, nil}

        scopes ->
          as = Mcp2026T.DevAuthServer.start()
          Mcp2026T.DevAuthServer.put_client(as, "test-client", ["http://127.0.0.1/callback"])
          {%{srv | auth: Mcp2026T.ServerAuth.new(issuer: as.issuer, scopes: scopes)}, as}
      end

    ts = Mcp2026T.Server.serve(srv)
    %{url: ts.url, as: as, counts: counts}
  end

  defp count(e, i), do: :counters.get(e.counts, i)

  defp load(servers, opts \\ []) do
    src = Mcp.load(%{"mcpServers" => servers}, opts)
    on_exit(fn -> Mcp.close(src) end)
    src
  end

  defp tool(src, name), do: Enum.find(src.tools, &(&1.name == name)) || flunk("no tool #{name}")
  defp exec(src, name, args, ctx \\ %Context{}), do: tool(src, name).execute.(args, ctx)

  defp stdio_cfg do
    jason = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    %{"command" => [System.find_executable("elixir"), "-pa", jason, @stdio_script]}
  end

  # A headless browser: follow redirects up to (not into) the loopback callback.
  defp approve(url) do
    {:ok, r} = Wire.request(:get, url, [], nil)
    loc = Wire.header(r, "location")

    if r.status in 301..308,
      do:
        (
          Wire.request(:get, loc, [], nil)
          :ok
        ),
      else: {:error, "no redirect"}
  end

  # Visit an authorization URL as the user; return where the AS redirected (not followed).
  defp visit(url) do
    {:ok, %{status: 302} = r} = Wire.request(:get, url, [], nil)
    Wire.header(r, "location")
  end

  defp pause_auth,
    do: %{
      "clientId" => "test-client",
      "mode" => "pause",
      "redirectUri" => "http://127.0.0.1/callback"
    }

  defp approving_wait_for(asked \\ nil) do
    fn %Request{} = r ->
      if asked, do: Agent.update(asked, &[r | &1])

      case r.kind do
        "authorization" -> %Answer{id: r.id, ok: true, data: %{"callbackUrl" => visit(r.url)}}
        "input" -> %Answer{id: r.id, ok: true, data: %{"name" => "toolnexus"}}
      end
    end
  end

  # --- wire ---------------------------------------------------------------------

  test "Mcp-Name sentinel encoding (§2.3)" do
    assert Wire.encode_header("echo") == "echo"
    assert Wire.encode_header(" x") == "=?base64?" <> Base.encode64(" x") <> "?="
    assert Wire.encode_header("héllo") == "=?base64?" <> Base.encode64("héllo") <> "?="
    assert Wire.encode_header("=?base64?eA==?=") =~ "=?base64?PT9"
  end

  test "skill URI containment rejects every expected.json case (§2.7)" do
    for u <- @expected["skillUriRejected"], do: assert({:error, _} = RemoteSkills.parse_uri(u), u)

    assert {:ok, "hello-world", "SKILL.md"} =
             RemoteSkills.parse_uri("skill://hello-world/SKILL.md")

    assert {:error, _} = RemoteSkills.parse_uri("file:///etc/passwd")
  end

  # --- HTTP direct path ---------------------------------------------------------

  test "HTTP: discover, tools named + mapped like the frozen path, filters, MRTR via waitFor" do
    e = fixture_server()
    {:ok, asked} = Agent.start_link(fn -> [] end)

    src =
      load(%{"fx" => %{"url" => e.url, "tools" => %{"tick" => false}}},
        wait_for: approving_wait_for(asked)
      )

    assert src.status == %{"fx" => "connected"}
    names = Enum.map(src.tools, & &1.name) |> Enum.sort()
    assert names == ["fx_charge", "fx_echo", "fx_greet"]
    assert tool(src, "fx_echo").input_schema["additionalProperties"] == false

    r = exec(src, "fx_echo", %{"text" => "hi toolnexus"})
    assert %ToolResult{is_error: false, output: ~s({"text":"hi toolnexus"})} = r

    step = Enum.find(@expected["steps"], &(&1["id"] =~ "MRTR"))
    r = exec(src, "fx_greet", %{})
    assert r.output == Jason.encode!(%{"greeting" => step["expect"]["text"]})

    assert [%Request{kind: "input", prompt: "What is your name?", data: %{"schema" => _}}] =
             Agent.get(asked, & &1)
  end

  test "HTTP: MRTR without waitFor suspends (§10 pending) and resumes the paused round" do
    e = fixture_server()
    src = load(%{"fx" => %{"url" => e.url}})
    r = exec(src, "fx_greet", %{})
    assert ToolResult.pending?(r)
    req = r.metadata.pending
    assert req.kind == "input"

    r2 =
      exec(src, "fx_greet", %{}, %Context{
        answer: %Answer{id: req.id, ok: true, data: %{"name" => "ada"}}
      })

    assert r2.output =~ "Hello, ada!"

    # declined ⇒ server asks again (fixture behaviour) ⇒ pending again, never a hang
    r3 = exec(src, "fx_greet", %{})

    r4 =
      exec(src, "fx_greet", %{}, %Context{answer: Answer.answer_declined(r3.metadata.pending.id)})

    assert ToolResult.pending?(r4)
  end

  test "HTTP: tool search exposes search_tools + call_tool only (§2.8)" do
    e = fixture_server()

    src =
      load(%{
        "fx" => %{"url" => e.url, "toolSearch" => true, "description" => "the fixture server"}
      })

    assert Enum.map(src.tools, & &1.name) == ["fx_search_tools", "fx_call_tool"]

    assert tool(src, "fx_search_tools").description ==
             "Search the tools of the fixture server by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."

    out = exec(src, "fx_search_tools", %{"query" => "echo text"}).output
    assert String.starts_with?(out, ~s([{"name":"echo","description":))
    [hit | _] = Jason.decode!(out)
    assert Map.keys(hit) |> Enum.sort() == Enum.sort(@expected["toolSearch"]["hitKeys"])
    assert hit["server"] == "the fixture server"
    assert exec(src, "fx_search_tools", %{"query" => "zzzz"}).output == "[]"

    assert exec(src, "fx_call_tool", %{"name" => "echo", "arguments" => %{"text" => "x"}}).output ==
             ~s({"text":"x"})

    r = exec(src, "fx_call_tool", %{"name" => "fx_echo"})
    assert {r.is_error, r.output} == {true, ~s(unknown tool "fx_echo" on fx)}

    src2 = load(%{"nodesc" => %{"url" => e.url, "toolSearch" => true}})
    assert tool(src2, "nodesc_search_tools").description =~ "Search the tools of nodesc by"
  end

  test "remote skills join the same skill inventory, body byte-identical to the local copy (§2.7)" do
    e = fixture_server()

    tk =
      Toolkit.build(mcp_config: %{"mcpServers" => %{"fx" => %{"url" => e.url}}}, builtins: false)

    on_exit(fn -> Toolkit.close(tk) end)
    out = Toolkit.get(tk, "skill").execute.(%{"name" => "hello-world"}, %Context{}).output

    local =
      Toolnexus.Skill.load(dirs: Path.expand("../../examples/skills", __DIR__)).tool.execute.(
        %{"name" => "hello-world"},
        %Context{}
      ).output

    body = fn s -> s |> String.split("Base directory") |> hd() end
    assert body.(out) == body.(local)
    assert out =~ "Base directory for this skill: skill://hello-world/"
    assert out =~ "<file>skill://hello-world/"

    dir = Path.join(System.tmp_dir!(), "tn-skill-#{System.unique_integer([:positive])}")
    c = Client2026.new(url: e.url)
    assert :ok = RemoteSkills.download(c, "skill://hello-world/SKILL.md", dir)

    assert File.read!(Path.join([dir, "hello-world", "SKILL.md"])) ==
             File.read!(Path.expand("../../examples/skills/hello-world/SKILL.md", __DIR__))

    assert {:error, _} = RemoteSkills.download(c, "skill://x/../secret", dir)
    File.rm_rf!(dir)
  end

  test "list_tools inventory uses the 2026 path" do
    e = fixture_server()
    inv = Mcp.list_tools(%{"fx" => %{"url" => e.url, "tools" => %{"echo" => true}}})
    assert inv.status == %{"fx" => "connected"}
    assert "tick" in Enum.map(inv.tools["fx"], & &1["name"])
  end

  test "resource tools work against a 2026 server" do
    e = fixture_server()
    src = load(%{"fx" => %{"url" => e.url}})
    [_list, read] = Mcp.resource_tools(src)
    r = read.execute.(%{"server" => "fx", "uri" => "skill://hello-world/SKILL.md"}, %Context{})
    assert r.output =~ "hello-world"
  end

  # --- fallback + failures ------------------------------------------------------

  defmodule OldServer do
    @moduledoc false
    # An old (2025) streamable server: errors before initialize; 500 on unknown methods.
    import Plug.Conn
    def init(o), do: o

    def call(conn, mode) do
      {:ok, body, conn} = read_body(conn)
      req = Jason.decode!(body)

      reply = fn r ->
        conn
        |> put_resp_content_type("application/json")
        |> send_resp(200, Jason.encode!(Map.merge(%{"jsonrpc" => "2.0", "id" => req["id"]}, r)))
      end

      case req["method"] do
        "server/discover" when mode == :http500 ->
          send_resp(conn, 500, "boom")

        "server/discover" when mode == :http404 ->
          send_resp(conn, 404, "nope")

        "server/discover" ->
          reply.(%{"error" => %{"code" => -32601, "message" => "Method not found"}})

        "initialize" ->
          reply.(%{
            "result" => %{
              "protocolVersion" => "2025-06-18",
              "capabilities" => %{"tools" => %{}},
              "serverInfo" => %{"name" => "old", "version" => "1"}
            }
          })

        "tools/list" ->
          reply.(%{
            "result" => %{
              "tools" => [%{"name" => "old_echo", "inputSchema" => %{"type" => "object"}}]
            }
          })

        "tools/call" ->
          reply.(%{
            "result" => %{
              "content" => [
                %{"type" => "text", "text" => "old:" <> req["params"]["arguments"]["text"]}
              ]
            }
          })

        nil ->
          send_resp(conn, 202, "")

        _ ->
          send_resp(conn, 202, "")
      end
    end
  end

  test "an old server falls back to the frozen path (-32601, HTTP 404, HTTP 500)" do
    for mode <- [:rpc, :http404, :http500] do
      {:ok, pid} =
        Bandit.start_link(
          plug: {OldServer, mode},
          ip: {127, 0, 0, 1},
          port: 0,
          startup_log: false
        )

      {:ok, {_, port}} = ThousandIsland.listener_info(pid)
      src = load(%{"old" => %{"url" => "http://127.0.0.1:#{port}/mcp"}})
      assert src.status == %{"old" => "connected"}, inspect(mode)
      assert exec(src, "old_old_echo", %{"text" => "x"}).output == "old:x"
    end
  end

  test "connection refused is a load failure, isolated" do
    log =
      capture_log(fn ->
        assert load(%{"x" => %{"url" => "http://127.0.0.1:1/mcp"}}).status == %{"x" => "failed"}
      end)

    assert log =~ "failed"
  end

  test "stdio: discover, echo, MRTR pending/resume, dead child fails the call" do
    src = load(%{"st" => stdio_cfg()})
    assert src.status == %{"st" => "connected"}
    assert exec(src, "st_echo", %{"text" => "hey"}).output == ~s({"text":"hey"})

    r = exec(src, "st_greet", %{})
    assert ToolResult.pending?(r)

    r2 =
      exec(src, "st_greet", %{}, %Context{
        answer: %Answer{id: r.metadata.pending.id, ok: true, data: %{"name" => "bo"}}
      })

    assert r2.output == "Hello, bo!"

    # concurrent calls are demultiplexed by id
    outs =
      1..8
      |> Enum.map(fn i ->
        Task.async(fn -> exec(src, "st_echo", %{"text" => "#{i}"}).output end)
      end)
      |> Task.await_many()

    assert outs == Enum.map(1..8, &~s({"text":"#{&1}"}))

    c =
      Client2026.new(
        stdio:
          elem(DynamicSupervisor.start_child(src.sup, {Toolnexus.Mcp.Stdio2026, stdio_cfg()}), 1),
        timeout: 10_000
      )

    assert {:error, :closed} = Client2026.call_tool(c, "die", %{})
    assert {:error, :closed} = Client2026.call_tool(c, "echo", %{"text" => "after"})
  end

  test "stdio: an old server (-32601 on discover) uses the frozen path" do
    jason = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    script = Path.expand("support/stdio_mcp_server.exs", __DIR__)

    src =
      load(%{"old" => %{"command" => [System.find_executable("elixir"), "-pa", jason, script]}})

    assert src.status == %{"old" => "connected"}
    assert exec(src, "old_echo", %{"text" => "z"}).output =~ "z"
  end

  # --- no hidden re-execution (§2.6) --------------------------------------------

  defp lossy_server(mode, counter) do
    {:ok, ln} =
      :gen_tcp.listen(0, [
        :binary,
        ip: {127, 0, 0, 1},
        active: false,
        packet: :http_bin,
        reuseaddr: true
      ])

    {:ok, port} = :inet.port(ln)

    spawn_link(fn ->
      Stream.repeatedly(fn -> :gen_tcp.accept(ln) end)
      |> Enum.each(fn {:ok, s} ->
        len = read_headers(s, 0)
        :inet.setopts(s, packet: :raw)
        {:ok, _body} = :gen_tcp.recv(s, len)
        :counters.add(counter, 1, 1)

        if mode == :bad_gateway,
          do:
            :gen_tcp.send(
              s,
              "HTTP/1.1 502 Bad Gateway\r\ncontent-length: 3\r\nconnection: close\r\n\r\nbad"
            )

        :gen_tcp.close(s)
      end)
    end)

    "http://127.0.0.1:#{port}/mcp"
  end

  defp read_headers(s, len) do
    case :gen_tcp.recv(s, 0) do
      {:ok, {:http_header, _, :"Content-Length", _, v}} -> read_headers(s, String.to_integer(v))
      {:ok, :http_eoh} -> len
      {:ok, _} -> read_headers(s, len)
    end
  end

  test "a lost or 5xx response is never retried" do
    for mode <- [:dropped, :bad_gateway] do
      n = :counters.new(1, [])

      assert {:error, _} =
               Client2026.call_tool(Client2026.new(url: lossy_server(mode, n)), "tick", %{})

      Process.sleep(50)
      assert :counters.get(n, 1) == 1, "#{mode}"
    end
  end

  # --- OAuth (§2A) ----------------------------------------------------------------

  @scopes %{"*" => ["mcp:read"], "charge" => ["mcp:charge"]}

  test "OAuth auto: openUrl hook logs in, tool runs once; refresh on expiry is single-flight" do
    e = fixture_server(scopes: @scopes)
    store = TokenStore.load(%{load: fn _ -> nil end}, "x") || Toolnexus.Mcp.MemoryTokenStore.new()

    src =
      load(%{"fx" => %{"url" => e.url, "auth" => %{"clientId" => "test-client"}}},
        open_url: &approve/1,
        token_store: store
      )

    assert src.status == %{"fx" => "connected"}
    assert exec(src, "fx_tick", %{}).output == "tick"
    assert count(e, 1) == 1
    assert Mcp2026T.DevAuthServer.get(e.as, :last_authorize_query)["resource"] == e.url

    assert Mcp2026T.DevAuthServer.get(e.as, :last_authorize_query)["code_challenge_method"] ==
             "S256"

    t = TokenStore.load(store, e.url)
    refute inspect(t) =~ t.access_token
    refute to_string(t) =~ t.access_token
    TokenStore.save(store, e.url, %{t | expires_at: System.system_time(:second) - 60})

    outs =
      1..20
      |> Enum.map(fn _ -> Task.async(fn -> exec(src, "fx_tick", %{}).output end) end)
      |> Task.await_many(30_000)

    assert Enum.all?(outs, &(&1 == "tick"))
    assert Mcp2026T.DevAuthServer.stats(e.as) == {1, 1}
    assert Mcp2026T.DevAuthServer.get(e.as, :last_token_form)["resource"] == e.url

    # step-up on 403 insufficient_scope: union of scopes, tool runs once
    assert exec(src, "fx_charge", %{}).output == "charged"
    assert count(e, 2) == 1

    assert Mcp2026T.DevAuthServer.get(e.as, :last_authorize_query)["scope"] ==
             "mcp:read mcp:charge"
  end

  test "OAuth pause during load: inline via waitFor; without waitFor the server fails 'authorization pending'" do
    e = fixture_server(scopes: @scopes)

    src =
      load(%{"fx" => %{"url" => e.url, "auth" => pause_auth()}}, wait_for: approving_wait_for())

    assert src.status == %{"fx" => "connected"}

    log =
      capture_log(fn ->
        s2 = load(%{"fx" => %{"url" => e.url, "auth" => pause_auth()}})
        assert s2.status == %{"fx" => "failed"}
      end)

    assert log =~ "authorization pending"

    # denied ⇒ "authorization denied: <reason>"
    deny = fn r -> Answer.answer_declined(r.id, "declined") end

    log =
      capture_log(fn ->
        load(%{"fx" => %{"url" => e.url, "auth" => pause_auth()}}, wait_for: deny)
      end)

    assert log =~ "authorization denied: declined"
  end

  test "OAuth pause during a tool call: pending, resume sends the paused call exactly once" do
    e = fixture_server(scopes: @scopes)
    store = Toolnexus.Mcp.MemoryTokenStore.new()

    src =
      load(%{"fx" => %{"url" => e.url, "auth" => pause_auth()}},
        wait_for: approving_wait_for(),
        token_store: store
      )

    # lose the token between calls and drop the wait_for path: build a second source sharing the store
    TokenStore.delete(store, e.url)

    # the load above confirmed the issuer; this source has none, so use the client directly
    o = OAuth.new(pause_auth(), token_store: store)
    c = Client2026.new(url: e.url, auth: o)

    assert {:pending,
            %Request{kind: "authorization", id: "auth-" <> _, prompt: "Authorize access to " <> _} =
              req, k} =
             Client2026.call_tool(c, "tick", %{})

    assert count(e, 1) == 0
    assert {:ok, r} = k.(%Answer{id: req.id, ok: true, data: %{"callbackUrl" => visit(req.url)}})
    assert hd(r["content"])["text"] == "tick"
    assert count(e, 1) == 1

    assert {:error, "unknown pending authorization" <> _} =
             OAuth.resume(o, req.id, "http://x/?a=b")

    _ = src
  end

  test "OAuth pause in an MRTR round resumes THAT round without re-asking" do
    e = fixture_server(scopes: %{"*" => ["mcp:read"]})
    store = Toolnexus.Mcp.MemoryTokenStore.new()
    o = OAuth.new(pause_auth(), token_store: store)
    c = Client2026.new(url: e.url, auth: o)
    {:pending, req, k} = Client2026.call_tool(c, "echo", %{"text" => "login"})
    {:ok, _} = k.(%Answer{id: req.id, ok: true, data: %{"callbackUrl" => visit(req.url)}})

    {:pending, %Request{kind: "input"} = ask, k} = Client2026.call_tool(c, "greet", %{})
    TokenStore.delete(store, e.url)

    {:pending, %Request{kind: "authorization"} = auth, k} =
      k.(%Answer{id: ask.id, ok: true, data: %{"name" => "once"}})

    assert {:ok, r} =
             k.(%Answer{id: auth.id, ok: true, data: %{"callbackUrl" => visit(auth.url)}})

    assert hd(r["content"])["text"] == "Hello, once!"
  end

  test "OAuth errors: iss mismatch, state mismatch, no S256, no client identity — nothing stored" do
    e = fixture_server(scopes: @scopes)
    Mcp2026T.DevAuthServer.set(e.as, :iss_override, "https://evil.example")

    c =
      Client2026.new(
        url: e.url,
        auth: OAuth.new(%{"clientId" => "test-client"}, open_url: &approve/1)
      )

    assert {:error, "authorization response iss mismatch (possible mix-up attack)"} =
             Client2026.call_tool(c, "tick", %{})

    assert TokenStore.load(c.auth.store, e.url) == nil
    Mcp2026T.DevAuthServer.set(e.as, :iss_override, nil)

    o = OAuth.new(pause_auth())
    {:pending, req, _} = Client2026.call_tool(Client2026.new(url: e.url, auth: o), "tick", %{})
    cb = visit(req.url) |> String.replace(~r/state=[^&]+/, "state=forged")
    assert {:error, "authorization response state mismatch"} = OAuth.resume(o, req.id, cb)
    assert TokenStore.load(o.store, e.url) == nil

    Mcp2026T.DevAuthServer.set(e.as, :no_cimd, true)
    c = Client2026.new(url: e.url, auth: OAuth.new(%{}))
    assert {:error, msg} = Client2026.call_tool(c, "tick", %{})

    for s <-
          hd(Enum.filter(@expected["auth"]["cases"], &(&1["id"] =~ "no CIMD")))["expect"][
            "errorContains"
          ],
        do: assert(msg =~ s)

    assert msg =~ "no client identity for #{e.as.issuer}"

    Mcp2026T.DevAuthServer.set(e.as, :no_s256, true)
    c = Client2026.new(url: e.url, auth: OAuth.new(%{"clientId" => "test-client"}))

    assert {:error, "authorization server does not advertise PKCE S256; refusing"} =
             Client2026.call_tool(c, "tick", %{})

    assert count(e, 1) == 0
  end

  test "OAuth: form-encoded token response; omitRefreshResource; CIMD client id" do
    e = fixture_server(scopes: @scopes)
    Mcp2026T.DevAuthServer.set(e.as, :form_token, true)

    c =
      Client2026.new(
        url: e.url,
        auth:
          OAuth.new(%{"clientId" => "test-client", "omitRefreshResource" => true},
            open_url: &approve/1
          )
      )

    assert {:ok, _} = Client2026.call_tool(c, "tick", %{})
    t = TokenStore.load(c.auth.store, e.url)
    assert %Token{} = t
    TokenStore.save(c.auth.store, e.url, %{t | expires_at: System.system_time(:second) - 60})
    Mcp2026T.DevAuthServer.set(e.as, :form_token, false)
    assert {:ok, _} = Client2026.call_tool(c, "tick", %{})
    refute Map.has_key?(Mcp2026T.DevAuthServer.get(e.as, :last_token_form), "resource")

    # CIMD: the client id IS the metadata URL (served by a local https-less doc is refused by
    # the dev AS, so only assert the authorize request carries it)
    o =
      OAuth.new(%{
        "clientMetadataUrl" => "https://app.example/client.json",
        "mode" => "pause",
        "redirectUri" => "http://127.0.0.1/callback"
      })

    {:pending, req, _} = Client2026.call_tool(Client2026.new(url: e.url, auth: o), "tick", %{})

    assert URI.decode_query(URI.parse(req.url).query)["client_id"] ==
             "https://app.example/client.json"
  end

  test "WWW-Authenticate parsing" do
    assert OAuth.parse_challenge(
             ~s(Bearer resource_metadata="https://x/y", scope="a b", error=invalid_token)
           ) ==
             %{"resource_metadata" => "https://x/y", "scope" => "a b", "error" => "invalid_token"}
  end

  test "stdio: string command + args, missing executable, and a silent child falls back after the probe timeout" do
    jason = Path.join(Mix.Project.build_path(), "lib/jason/ebin")
    src = load(%{"s" => %{"command" => "elixir", "args" => ["-pa", jason, @stdio_script]}})
    assert src.status == %{"s" => "connected"}

    log =
      capture_log(fn ->
        assert load(%{"x" => %{"command" => ["/nonexistent/tn-nope"]}}).status == %{
                 "x" => "failed"
               }

        # `cat` never answers a request: discover times out ⇒ frozen path, which then fails too
        assert load(%{"c" => %{"command" => ["cat"], "timeout" => 300}}).status == %{
                 "c" => "failed"
               }
      end)

    assert log =~ "failed"
    {:ok, pid} = Toolnexus.Mcp.Stdio2026.start_link(%{"command" => ["cat"]})

    assert {:error, :timeout} =
             Toolnexus.Mcp.Stdio2026.round_trip(pid, 1, ~s({"id":1,"method":"x"}), 100)

    Toolnexus.Mcp.Stdio2026.close(pid)
    assert {:error, :closed} = Toolnexus.Mcp.Stdio2026.round_trip(pid, 2, "{}", 100)
  end
end
