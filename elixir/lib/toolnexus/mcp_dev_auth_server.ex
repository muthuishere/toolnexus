defmodule Toolnexus.McpDevAuthServer do
  @moduledoc """
  DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION.

  An in-process OAuth 2.1 authorization server (SPEC §7C.2) that auto-approves
  every request as `"dev-user"` (no login, no consent): RFC 8414 metadata, JWKS,
  authorization code + PKCE S256, resource indicators (RFC 8707), `iss` in the
  response (RFC 9207), refresh-token rotation, pre-registered clients and Client
  ID Metadata Documents. **No dynamic client registration.** Plain HTTP on
  `127.0.0.1:<random>`, so a toolnexus client needs `auth.allowHttp` against it.

      {:ok, as} = Toolnexus.start_mcp_dev_auth_server(clients: %{"my-app" => ["http://127.0.0.1/callback"]})
      auth = Toolnexus.McpServerAuth.new(issuer: as.issuer, scopes: %{"*" => ["mcp"]})
      # ...
      Toolnexus.McpDevAuthServer.close(as)

  ## Options

    * `:clients` — pre-registered `client_id => [redirect_uri]` (a loopback redirect
      matches on any port, RFC 8252 §7.3)
    * `:secrets` — confidential clients, `client_id => secret` (basic or post)
    * `:access_ttl` — access-token lifetime in seconds (default 3600)
    * `:no_cimd` — stop advertising (and accepting) Client ID Metadata Documents
    * `:reject_refresh_resource` — answer `invalid_target` when a refresh carries
      `resource` (an Entra-like provider, for client tests)
    * `:http_options` — extra `Req` options for fetching CIMD documents
  """

  @behaviour Plug
  import Plug.Conn

  defstruct [:issuer, :state, :server, :key]

  @type t :: %__MODULE__{issuer: String.t(), state: pid(), server: pid(), key: tuple()}

  @doc "Start the dev AS on `127.0.0.1:<random>`."
  @spec start(keyword() | map()) :: {:ok, t()} | {:error, term()}
  def start(opts \\ []) do
    opts = Map.new(opts)
    key = :public_key.generate_key({:rsa, 2048, 65537})

    {:ok, st} =
      Agent.start(fn ->
        %{
          opts: opts,
          issuer: nil,
          codes: %{},
          rts: %{},
          auths: 0
        }
      end)

    d = %__MODULE__{state: st, key: key}

    case Bandit.start_link(
           plug: {__MODULE__, d},
           ip: {127, 0, 0, 1},
           port: 0,
           startup_log: false,
           thousand_island_options: [shutdown_timeout: 100]
         ) do
      {:ok, srv} ->
        Process.unlink(srv)
        {:ok, {_, port}} = ThousandIsland.listener_info(srv)
        issuer = "http://127.0.0.1:#{port}"
        Agent.update(st, &%{&1 | issuer: issuer})
        {:ok, %{d | issuer: issuer, server: srv}}

      e ->
        Agent.stop(st)
        e
    end
  end

  @doc "Stop the server."
  @spec close(t()) :: :ok
  def close(%__MODULE__{server: srv, state: st}) do
    _ = Supervisor.stop(srv)
    _ = Agent.stop(st)
    :ok
  end

  @doc "How many `/authorize` requests were served."
  @spec authorizations(t()) :: non_neg_integer()
  def authorizations(%__MODULE__{state: st}), do: Agent.get(st, & &1.auths)

  @doc "Sign an access token with the server's key (tests: forge expired or wrong-audience tokens)."
  @spec mint(t(), map()) :: String.t()
  def mint(%__MODULE__{key: key}, claims) do
    h = b64(Jason.encode!(%{"alg" => "RS256", "typ" => "JWT", "kid" => "dev"}))
    input = h <> "." <> b64(Jason.encode!(claims))
    input <> "." <> b64(:public_key.sign(input, :sha256, key))
  end

  defp b64(b), do: Base.url_encode64(b, padding: false)
  defp rand(n), do: Base.encode16(:crypto.strong_rand_bytes(n), case: :lower)

  # ── Plug ───────────────────────────────────────────────────────────────

  @impl Plug
  def init(d), do: d

  @impl Plug
  def call(conn, d) do
    st = Agent.get(d.state, & &1)
    iss = st.issuer

    case conn.request_path do
      "/.well-known/oauth-authorization-server" ->
        js(conn, 200, %{
          "issuer" => iss,
          "authorization_endpoint" => iss <> "/authorize",
          "token_endpoint" => iss <> "/token",
          "jwks_uri" => iss <> "/jwks",
          "response_types_supported" => ["code"],
          "grant_types_supported" => ["authorization_code", "refresh_token"],
          "code_challenge_methods_supported" => ["S256"],
          "token_endpoint_auth_methods_supported" => [
            "none",
            "client_secret_basic",
            "client_secret_post"
          ],
          "authorization_response_iss_parameter_supported" => true,
          "client_id_metadata_document_supported" => not (st.opts[:no_cimd] == true)
        })

      "/jwks" ->
        {:RSAPrivateKey, _, n, e, _, _, _, _, _, _, _} = d.key

        js(conn, 200, %{
          "keys" => [
            %{
              "kty" => "RSA",
              "kid" => "dev",
              "alg" => "RS256",
              "use" => "sig",
              "n" => b64(:binary.encode_unsigned(n)),
              "e" => b64(:binary.encode_unsigned(e))
            }
          ]
        })

      "/authorize" ->
        authorize(conn, d, st)

      "/token" ->
        token(conn, d)

      _ ->
        send_resp(conn, 404, "404 page not found\n")
    end
  end

  defp js(conn, status, v) do
    conn
    |> put_resp_header("content-type", "application/json")
    |> send_resp(status, Jason.encode!(v))
  end

  defp authorize(conn, d, st) do
    q = URI.decode_query(conn.query_string)
    Agent.update(d.state, &%{&1 | auths: &1.auths + 1})
    {cid, ru} = {q["client_id"] || "", q["redirect_uri"] || ""}

    back = fn params ->
      params = params ++ [{"state", q["state"] || ""}, {"iss", st.issuer}]
      sep = if String.contains?(ru, "?"), do: "&", else: "?"

      conn
      |> put_resp_header("location", ru <> sep <> URI.encode_query(params))
      |> send_resp(302, "")
    end

    cond do
      not redirect_allowed?(st, cid, ru) ->
        # never redirect to an unverified URI
        send_resp(conn, 400, "invalid_client or redirect_uri\n")

      q["response_type"] != "code" or q["code_challenge_method"] != "S256" or
          q["code_challenge"] in [nil, ""] ->
        back.([{"error", "invalid_request"}])

      q["resource"] in [nil, ""] ->
        back.([{"error", "invalid_target"}])

      true ->
        code = rand(16)

        grant = %{
          client_id: cid,
          redirect: ru,
          challenge: q["code_challenge"],
          resource: q["resource"],
          scope: q["scope"] || ""
        }

        Agent.update(d.state, &put_in(&1, [:codes, code], grant))
        back.([{"code", code}])
    end
  end

  defp redirect_allowed?(st, cid, redirect) do
    allowed =
      if String.starts_with?(cid, ["https://", "http://"]) do
        # Client ID Metadata Document
        with false <- st.opts[:no_cimd] == true,
             {:ok, %{"client_id" => ^cid, "client_name" => n} = doc}
             when is_binary(n) and n != "" <-
               Toolnexus.McpServerAuth.get_json(st.opts[:http_options], cid),
             uris when is_list(uris) <- doc["redirect_uris"] do
          uris
        else
          _ -> []
        end
      else
        Map.get(st.opts[:clients] || %{}, cid, [])
      end

    ru = URI.parse(redirect)

    Enum.any?(allowed, fn a ->
      au = URI.parse(a)

      a == redirect or
        (loopback?(ru.host) and au.host == ru.host and au.scheme == ru.scheme and
           au.path == ru.path)
    end)
  end

  defp loopback?(nil), do: false

  defp loopback?(host) do
    case :inet.parse_address(String.to_charlist(String.trim(host, "[") |> String.trim("]"))) do
      {:ok, {127, _, _, _}} -> true
      {:ok, {0, 0, 0, 0, 0, 0, 0, 1}} -> true
      _ -> false
    end
  end

  defp token(conn, d) do
    {:ok, body, conn} = read_body(conn)
    f = URI.decode_query(body)

    basic =
      case get_req_header(conn, "authorization") do
        ["Basic " <> b | _] ->
          with {:ok, dec} <- Base.decode64(b),
               [id, sec] <- :binary.split(dec, ":") do
            {URI.decode_www_form(id), URI.decode_www_form(sec)}
          else
            _ -> nil
          end

        _ ->
          nil
      end

    {status, v} = Agent.get_and_update(d.state, fn st -> grant(st, f, basic, d) end)
    js(conn, status, v)
  end

  defp grant(st, f, basic, d) do
    secrets = st.opts[:secrets] || %{}
    bad = fn e, st -> {{400, %{"error" => e}}, st} end

    {f, client_err} =
      case basic do
        {id, sec} ->
          if (f["client_id"] not in [nil, ""] and f["client_id"] != id) or
               secrets[id] in [nil, ""] or secrets[id] != sec,
             do: {f, true},
             else: {Map.put(f, "client_id", id), false}

        nil ->
          want = secrets[f["client_id"]]
          {f, want not in [nil, ""] and f["client_secret"] != want}
      end

    if client_err do
      {{401, %{"error" => "invalid_client"}}, st}
    else
      case f["grant_type"] do
        "authorization_code" ->
          case Map.pop(st.codes, f["code"] || "") do
            {nil, _} ->
              bad.("invalid_grant", st)

            {g, codes} ->
              # single use
              st = %{st | codes: codes}
              sum = b64(:crypto.hash(:sha256, f["code_verifier"] || ""))

              if sum != g.challenge or f["client_id"] != g.client_id or
                   f["redirect_uri"] != g.redirect or f["resource"] != g.resource,
                 do: bad.("invalid_grant", st),
                 else: issue(st, g, d)
          end

        "refresh_token" ->
          case Map.fetch(st.rts, f["refresh_token"] || "") do
            {:ok, g} ->
              if f["client_id"] != g.client_id do
                bad.("invalid_grant", st)
              else
                # rotation: a refresh token is single use
                st = %{st | rts: Map.delete(st.rts, f["refresh_token"])}
                res = f["resource"]

                if res not in [nil, ""] and
                     (res != g.resource or st.opts[:reject_refresh_resource] == true),
                   do: bad.("invalid_target", st),
                   else: issue(st, g, d)
              end

            :error ->
              bad.("invalid_grant", st)
          end

        _ ->
          bad.("unsupported_grant_type", st)
      end
    end
  end

  defp issue(st, g, d) do
    ttl = st.opts[:access_ttl] || 3600
    ttl = if is_integer(ttl) and ttl > 0, do: ttl, else: 3600
    rt = rand(24)
    now = System.system_time(:second)

    at =
      mint(d, %{
        "iss" => st.issuer,
        "sub" => "dev-user",
        "aud" => g.resource,
        "scope" => g.scope,
        "client_id" => g.client_id,
        "jti" => rand(8),
        "exp" => now + ttl,
        "iat" => now
      })

    {{200,
      %{
        "access_token" => at,
        "token_type" => "Bearer",
        "expires_in" => ttl,
        "refresh_token" => rt,
        "scope" => g.scope
      }}, put_in(st, [:rts, rt], g)}
  end
end
