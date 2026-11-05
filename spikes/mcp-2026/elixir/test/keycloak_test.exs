defmodule Mcp2026.KeycloakTest do
  # Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Excluded by default:
  # KEYCLOAK=1 mix test --include keycloak test/keycloak_test.exs
  use ExUnit.Case, async: false
  alias Mcp2026.{Client, LoopbackAuthorizer, OAuth, PauseAuthorizer, Server, ServerAuth, TokenStore}

  @moduletag :keycloak
  @issuer "http://localhost:8180/realms/toolnexus"
  @url "http://127.0.0.1:7199/mcp"

  setup_all do
    srv = %{Server.fixture() | auth: ServerAuth.new(issuer: @issuer, audience: @url,
      scopes: %{"*" => ["mcp:tools"], "greet" => ["mcp:greet"]})}
    s = Server.serve(srv, 7199)
    on_exit(fn -> Server.stop(s) end)
    :ok
  end

  defp client(opts \\ []) do
    Client.new(@url, auth: OAuth.new([client_ids: %{@issuer => "toolnexus-mcp"}, allow_http: true,
      authorizer: %LoopbackAuthorizer{open: KeycloakHeadless.opener("alice", "alice-test-pw")}] ++ opts))
  end

  test "the 8 checks pass against the Keycloak-protected server" do
    auth = client().auth
    assert Mcp2026.Checks.run(@url, fn c -> %{c | auth: auth} end)
  end

  test "refresh on expiry, with and without resource" do
    for omit <- [false, true] do
      c = client(omit_refresh_resource: omit)
      assert {:ok, _} = Client.call_tool(c, "echo", %{"text" => "a"})
      {:ok, old} = TokenStore.load(c.auth.store, @url)
      TokenStore.save(c.auth.store, @url, %{old | expiry: System.system_time(:second) - 60})
      assert {:ok, %{"content" => [%{"text" => "b"}]}} = Client.call_tool(c, "echo", %{"text" => "b"})
      {:ok, nt} = TokenStore.load(c.auth.store, @url)
      assert nt.access_token != old.access_token
    end
  end

  test "pause/resume" do
    c = client(authorizer: %PauseAuthorizer{redirect: "http://127.0.0.1:9/callback"})
    p = Mcp2026.pending_of(Client.call_tool(c, "echo", %{"text" => "after login"}))
    {:ok, cb} = KeycloakHeadless.login(p.url, "alice", "alice-test-pw")
    assert {:ok, %{"content" => [%{"text" => "after login"}]}} = OAuth.resume(c.auth, p.id, cb)
  end

  test "step-up to mcp:greet" do
    c = client()
    assert {:ok, r} = Client.call_tool(c, "greet", %{}, fn _, _ -> %{"action" => "accept", "content" => %{"name" => "Alice"}} end)
    assert Mcp2026.text(r) == "Hello, Alice!"
    {:ok, t} = TokenStore.load(c.auth.store, @url)
    assert "mcp:greet" in t.scopes
  end

  test "a token for another audience is rejected" do
    c = client()
    assert {:ok, _} = Client.call_tool(c, "echo", %{"text" => "a"})
    {:ok, t} = TokenStore.load(c.auth.store, @url)
    other = Server.serve(%{Server.fixture() | auth: ServerAuth.new(issuer: @issuer, audience: "http://127.0.0.1:7200/mcp", scopes: %{"*" => ["mcp:tools"]})})
    bad = Client.new(other.url, http: fn m, u, h, b -> Mcp2026.HTTP.request(m, u, [{"authorization", "Bearer " <> t.access_token} | h], b) end)
    assert {:error, _} = Client.call_tool(bad, "echo", %{"text" => "x"})
  end
end
