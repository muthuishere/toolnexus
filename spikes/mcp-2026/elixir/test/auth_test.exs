defmodule Mcp2026.AuthTest do
  use ExUnit.Case, async: true
  import ExUnit.CaptureIO
  alias Mcp2026.{Client, DevAuthServer, HTTP, LoopbackAuthorizer, OAuth, PauseAuthorizer, Principal, Server, ServerAuth, TokenStore}

  test "the 8 checks pass against a protected server in auto mode" do
    e = TestEnv.start(scopes: %{"*" => ["mcp:read"]})
    out = capture_io(fn -> assert Mcp2026.Checks.run(e.url, fn c -> %{c | auth: TestEnv.auto_client(e).auth} end) end)
    assert length(Regex.scan(~r/^PASS /m, out)) == 8
  end

  test "pause/resume executes the call exactly once; a second resume fails" do
    e = TestEnv.start()
    c = TestEnv.pause_client(e)
    p = Mcp2026.pending_of(Client.call_tool(c, "tick"))
    assert %Mcp2026.Pending{kind: "authorization"} = p
    assert TestEnv.ticks(e) == 0
    cb = TestEnv.visit(p.url)
    assert {:ok, r} = OAuth.resume(c.auth, p.id, cb)
    assert Mcp2026.text(r) == "tick"
    assert {:error, _} = OAuth.resume(c.auth, p.id, cb)
    assert TestEnv.ticks(e) == 1
  end

  test "server 401 invalid_token -> one refresh -> executes once" do
    e = TestEnv.start()
    c = TestEnv.auto_client(e)
    assert {:ok, _} = Client.call_tool(c, "tick")
    {:ok, t} = TokenStore.load(c.auth.store, e.url)
    TokenStore.save(c.auth.store, e.url, %{t | access_token: "revoked-by-server"})
    assert {:ok, r} = Client.call_tool(c, "tick")
    assert Mcp2026.text(r) == "tick"
    assert {TestEnv.ticks(e), DevAuthServer.stats(e.as)} == {2, {1, 1}}
  end

  test "401 -> login -> 403 -> step-up -> executes once" do
    e = TestEnv.start()
    c = TestEnv.auto_client(e)
    assert {:ok, %{"content" => [%{"text" => "charged"}]}} = Client.call_tool(c, "charge")
    assert {TestEnv.charges(e), elem(DevAuthServer.stats(e.as), 0)} == {1, 2}
    {:ok, t} = TokenStore.load(c.auth.store, e.url)
    assert "mcp:charge" in t.scopes and "mcp:read" in t.scopes
  end

  test "a pause in a later MRTR round resumes THAT round without re-asking" do
    e = TestEnv.start(scopes: %{"*" => ["mcp:read"]})
    c = TestEnv.pause_client(e)
    p = Mcp2026.pending_of(Client.call_tool(c, "echo", %{"text" => "login"}))
    assert {:ok, _} = OAuth.resume(c.auth, p.id, TestEnv.visit(p.url))
    {:ok, asked} = Agent.start_link(fn -> 0 end)

    res = Client.call_tool(c, "greet", %{}, fn _, _ ->
      Agent.update(asked, &(&1 + 1))
      TokenStore.delete(c.auth.store, e.url)  # token lost between rounds -> round 1 gets 401
      %{"action" => "accept", "content" => %{"name" => "once"}}
    end)

    p = Mcp2026.pending_of(res)
    assert {:ok, r} = OAuth.resume(c.auth, p.id, TestEnv.visit(p.url))
    assert {Mcp2026.text(r), Agent.get(asked, & &1)} == {"Hello, once!", 1}
  end

  # A raw TCP server: runs the tool (dispatch), then loses the response.
  defp lossy_server(mode, counter) do
    srv = Server.new() |> Server.tool("tick", "", fn _, _ -> :counters.add(counter, 1, 1); "tick" end)
    {:ok, ln} = :gen_tcp.listen(0, [:binary, ip: {127, 0, 0, 1}, active: false, packet: :http_bin, reuseaddr: true])
    {:ok, port} = :inet.port(ln)

    spawn_link(fn ->
      Stream.repeatedly(fn -> :gen_tcp.accept(ln) end)
      |> Enum.each(fn {:ok, s} ->
        len = read_headers(s, 0)
        :inet.setopts(s, packet: :raw)
        {:ok, body} = :gen_tcp.recv(s, len)
        req = JSON.decode!(body)
        Server.dispatch(srv, %{principal: nil}, req["method"], req["params"])
        if mode == :bad_gateway, do: :gen_tcp.send(s, "HTTP/1.1 502 Bad Gateway\r\ncontent-length: 11\r\nconnection: close\r\n\r\nbad gateway")
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

  test "a lost or failed response is never retried (tool runs exactly once)" do
    for mode <- [:dropped, :bad_gateway] do
      n = :counters.new(1, [])
      assert {:error, _} = Client.call_tool(Client.new(lossy_server(mode, n)), "tick")
      Process.sleep(50)  # a hidden retry would land here
      assert :counters.get(n, 1) == 1, "#{mode}"
    end
  end

  test "resource on refresh is configurable (Entra-like AS rejects it)" do
    for omit <- [false, true] do
      e = TestEnv.start()
      DevAuthServer.set(e.as, :reject_refresh_resource, true)
      c = TestEnv.auto_client(e, omit_refresh_resource: omit)
      assert {:ok, _} = Client.call_tool(c, "echo", %{"text" => "1"})
      assert DevAuthServer.get(e.as, :last_authorize_query)["resource"] == e.url
      assert DevAuthServer.get(e.as, :last_token_form)["resource"] == e.url
      TestEnv.expire_token(c)
      assert {:ok, %{"content" => [%{"text" => "2"}]}} = Client.call_tool(c, "echo", %{"text" => "2"})
      {a, rf} = DevAuthServer.stats(e.as)

      if omit do
        assert {rf, a} == {1, 1}
        refute Map.has_key?(DevAuthServer.get(e.as, :last_token_form), "resource")
      else
        assert {rf, a} == {0, 2}  # default sends resource; rejected -> falls back to a new login
      end
    end

    e = TestEnv.start()
    c = TestEnv.auto_client(e)
    Client.call_tool(c, "echo", %{"text" => "1"})
    TestEnv.expire_token(c)
    Client.call_tool(c, "echo", %{"text" => "2"})
    f = DevAuthServer.get(e.as, :last_token_form)
    assert {f["grant_type"], f["resource"]} == {"refresh_token", e.url}
  end

  test "pre-registered confidential client: basic / post; a wrong secret falls back to a new authorization" do
    for method <- [nil, "client_secret_basic", "client_secret_post"] do
      e = TestEnv.start()
      DevAuthServer.set(e.as, :no_cimd, true)
      DevAuthServer.put_client(e.as, "conf", ["http://127.0.0.1/callback"])
      DevAuthServer.set(e.as, :secrets, %{"conf" => "test-only-secret"})
      opts = [client_id: "conf", client_secret: "test-only-secret", token_auth_method: method,
              authorizer: %LoopbackAuthorizer{open: &DevAuthServer.approve(e.as, &1)}]
      c = Client.new(e.url, auth: OAuth.new(opts))
      assert {:ok, %{"content" => [%{"text" => "ok"}]}} = Client.call_tool(c, "echo", %{"text" => "ok"})
      f = DevAuthServer.get(e.as, :last_token_form)
      assert Map.has_key?(f, "client_secret") == (method == "client_secret_post")

      TestEnv.expire_token(c)
      c2 = %{c | auth: %{c.auth | client_secret: "wrong", authorizer: %PauseAuthorizer{redirect: "http://127.0.0.1:9/callback"}}}
      assert Mcp2026.pending_of(Client.call_tool(c2, "echo", %{"text" => "x"})) != nil
    end
  end

  test "no CIMD and no client id is a clear error, before any authorization" do
    e = TestEnv.start()
    DevAuthServer.set(e.as, :no_cimd, true)
    c = Client.new(e.url, auth: OAuth.new(client_metadata_url: "https://app.example/client.json"))
    assert {:error, msg} = Client.call_tool(c, "echo", %{"text" => "x"})
    assert msg =~ "does not support Client ID Metadata Documents"
    assert msg =~ "register an app with #{e.as.issuer}"
    assert DevAuthServer.stats(e.as) == {0, 0}
  end

  test "iss mismatch and state mismatch are rejected" do
    e = TestEnv.start()
    DevAuthServer.set(e.as, :iss_override, "https://evil.example")
    assert {:error, "authorization response iss mismatch" <> _} = Client.call_tool(TestEnv.auto_client(e), "echo", %{"text" => "x"})

    e = TestEnv.start()
    c = TestEnv.pause_client(e)
    p = Mcp2026.pending_of(Client.call_tool(c, "echo", %{"text" => "x"}))
    cb = TestEnv.visit(p.url) |> String.replace(~r/state=[^&]+/, "state=forged")
    assert {:error, "authorization response state mismatch"} = OAuth.resume(c.auth, p.id, cb)
  end

  test "PKCE: the AS rejects a wrong verifier (token exchange fails)" do
    e = TestEnv.start()
    c = TestEnv.pause_client(e)
    p = Mcp2026.pending_of(Client.call_tool(c, "echo", %{"text" => "x"}))
    [code] = Regex.run(~r/code=([^&]+)/, TestEnv.visit(p.url), capture: :all_but_first)
    body = URI.encode_query(%{"grant_type" => "authorization_code", "code" => code, "client_id" => "test-client",
      "redirect_uri" => "http://127.0.0.1/callback", "code_verifier" => "wrong", "resource" => e.url})
    {:ok, r} = HTTP.request(:post, e.as.issuer <> "/token", [{"content-type", "application/x-www-form-urlencoded"}], body)
    assert {r.status, JSON.decode!(r.body)["error"]} == {400, "invalid_grant"}
  end

  test "server: JWT via JWKS, principal in handler; wrong aud / expired / garbage -> 401; per-tool 403" do
    e = TestEnv.start(scopes: %{"*" => ["mcp:read"], "charge" => ["mcp:charge"]})
    now = System.system_time(:second)
    good = %{"iss" => e.as.issuer, "sub" => "u1", "aud" => e.url, "scope" => "mcp:read", "exp" => now + 60}
    call = fn tok, name ->
      Client.call_tool(Client.new(e.url, http: fn m, u, h, b -> HTTP.request(m, u, [{"authorization", "Bearer " <> tok} | h], b) end), name)
    end
    assert {:ok, %{"content" => [%{"text" => "hello u1"}]}} = call.(DevAuthServer.mint(e.as, good), "whoami")

    for bad <- [Map.put(good, "aud", "http://other/mcp"), Map.put(good, "exp", now - 5), Map.put(good, "iss", "https://x")] do
      assert {:error, _} = call.(DevAuthServer.mint(e.as, bad), "whoami")
    end
    assert {:error, _} = call.("garbage", "whoami")

    {:ok, r} = HTTP.request(:post, e.url, [{"authorization", "Bearer " <> DevAuthServer.mint(e.as, good)}, {"content-type", "application/json"},
      {"mcp-protocol-version", "2026-07-28"}, {"mcp-method", "tools/call"}, {"mcp-name", "charge"}],
      JSON.encode!(%{"jsonrpc" => "2.0", "id" => 1, "method" => "tools/call", "params" => %{"name" => "charge",
        "_meta" => %{"io.modelcontextprotocol/protocolVersion" => "2026-07-28"}}}))
    assert r.status == 403
    assert HTTP.header(r, "www-authenticate") =~ ~s(error="insufficient_scope")
    assert HTTP.header(r, "www-authenticate") =~ ~s(scope="mcp:read mcp:charge")
    assert TestEnv.charges(e) == 0
  end

  test "server: custom verify replaces JWT" do
    srv = Server.new() |> Server.tool("whoami", "", fn _, ctx -> "hello " <> ctx.principal.subject end)
    v = fn "k1" -> {:ok, %Principal{subject: "svc", scopes: ["r"]}}; _ -> {:error, "no"} end
    url = Server.serve(%{srv | auth: ServerAuth.new(issuer: "https://as.example", scopes: %{"*" => ["r"]}, verify: v)}).url
    with_key = fn k -> Client.new(url, http: fn m, u, h, b -> HTTP.request(m, u, [{"authorization", "Bearer " <> k} | h], b) end) end
    assert {:ok, %{"content" => [%{"text" => "hello svc"}]}} = Client.call_tool(with_key.("k1"), "whoami")
    assert {:error, _} = Client.call_tool(with_key.("k2"), "whoami")
  end

  test "a failing browser opener surfaces instead of hanging" do
    e = TestEnv.start()
    c = Client.new(e.url, auth: OAuth.new(client_id: "test-client", authorizer: %LoopbackAuthorizer{open: fn _ -> {:error, "no browser"} end}))
    assert {:error, "open authorization URL" <> _} = Client.call_tool(c, "echo", %{"text" => "x"})
  end

  test "WWW-Authenticate parsing" do
    assert OAuth.parse_challenge(~s(Bearer resource_metadata="https://x/y", scope="a b", error=invalid_token)) ==
             %{"resource_metadata" => "https://x/y", "scope" => "a b", "error" => "invalid_token"}
    assert OAuth.parse_challenge(~s(Bearer k="a\\"b")) == %{"k" => ~s(a"b)}
  end

  test "tokens never print" do
    t = %Mcp2026.Token{access_token: "secret-at", refresh_token: "secret-rt", issuer: "https://i"}
    refute inspect(t) =~ "secret"
    refute to_string(t) =~ "secret"
  end
end
