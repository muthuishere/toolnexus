ExUnit.start(exclude: [:keycloak, :github])
Code.require_file("../examples/support/keycloak_headless.exs", __DIR__)

defmodule TestEnv do
  @moduledoc "Dev AS + protected fixture server with counted tools `tick` (base scope) and `charge` (mcp:charge)."
  alias Mcp2026.{Client, DevAuthServer, LoopbackAuthorizer, OAuth, PauseAuthorizer, Server, ServerAuth}

  def start(opts \\ []) do
    as = DevAuthServer.start()
    DevAuthServer.put_client(as, "test-client", ["http://127.0.0.1/callback"])
    counts = :counters.new(2, [])

    scopes = Keyword.get(opts, :scopes, %{"*" => ["mcp:read"], "greet" => ["mcp:greet"], "charge" => ["mcp:charge"]})

    srv =
      Server.fixture()
      |> Server.tool("tick", "counts", fn _, _ -> :counters.add(counts, 1, 1); "tick" end)
      |> Server.tool("charge", "counts, extra scope", fn _, _ -> :counters.add(counts, 2, 1); "charged" end)
      |> Server.tool("whoami", "who", fn _, ctx -> "hello " <> ctx.principal.subject end)

    ts = Server.serve(%{srv | auth: ServerAuth.new(issuer: as.issuer, scopes: scopes)})
    %{as: as, url: ts.url, ts: ts, counts: counts}
  end

  def ticks(e), do: :counters.get(e.counts, 1)
  def charges(e), do: :counters.get(e.counts, 2)

  def auto_client(e, opts \\ []) do
    Client.new(e.url, auth: OAuth.new([client_id: "test-client",
      authorizer: %LoopbackAuthorizer{open: &DevAuthServer.approve(e.as, &1)}] ++ opts))
  end

  def pause_client(e, opts \\ []) do
    Client.new(e.url, auth: OAuth.new([client_id: "test-client",
      authorizer: %PauseAuthorizer{redirect: "http://127.0.0.1/callback"}] ++ opts))
  end

  @doc "Visit an authorization URL as the user and return the AS's redirect (not followed)."
  def visit(url) do
    {:ok, %{status: 302} = r} = Mcp2026.HTTP.request(:get, url, [], nil)
    Mcp2026.HTTP.header(r, "location")
  end

  def expire_token(c) do
    {:ok, t} = Mcp2026.TokenStore.load(c.auth.store, c.url)
    Mcp2026.TokenStore.save(c.auth.store, c.url, %{t | expiry: System.system_time(:second) - 60})
  end
end
