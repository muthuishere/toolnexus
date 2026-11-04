# TEST SUPPORT ONLY (ported from spikes/mcp-2026/elixir).
defmodule Mcp2026T.DevAuthServer do
  @moduledoc """
  DEV/TEST ONLY. NEVER USE IN PRODUCTION.

  A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user" (no
  login, no consent screen), so local dev and tests exercise the real MCP auth flow: RFC 8414
  metadata, JWKS, code + PKCE S256, resource (RFC 8707), iss in the response (RFC 9207),
  refresh-token rotation, pre-registered + CIMD clients. TLS on https://127.0.0.1:<port>/dev;
  its certificate is trusted by `Mcp2026T.HTTP` in this VM.

      as = DevAuthServer.start(clients: %{"my-app" => ["http://127.0.0.1/callback"]})

  Knobs (`set/3`): :secrets, :access_ttl (s), :iss_override, :no_cimd, :reject_refresh_resource.
  """
  @behaviour Plug
  import Plug.Conn
  alias Toolnexus.Mcp.Wire, as: HTTP

  defstruct [:issuer, :pid, :server, :key]

  def start(opts \\ []) do
    key = :public_key.generate_key({:rsa, 2048, 65537})
    san = {:Extension, {2, 5, 29, 17}, false, [{:iPAddress, <<127, 0, 0, 1>>}]}

    data =
      :public_key.pkix_test_data(%{
        server_chain: %{root: [key: {:rsa, 2048, 65537}], peer: [key: {:rsa, 2048, 65537}, extensions: [san]]},
        client_chain: %{root: [key: {:rsa, 2048, 65537}], peer: [key: {:rsa, 2048, 65537}]}
      })

    sc = data.server_config
    Enum.each(sc[:cacerts], &HTTP.trust_cert/1)

    {:ok, pid} =
      Agent.start_link(fn ->
        %{clients: Keyword.get(opts, :clients, %{}), secrets: Keyword.get(opts, :secrets, %{}),
          access_ttl: Keyword.get(opts, :access_ttl, 3600), iss_override: nil, no_cimd: false,
          reject_refresh_resource: false, form_token: false, no_s256: false, last_token_form: %{}, last_authorize_query: %{},
          authorizations: 0, refreshes: 0, codes: %{}, rts: %{}, issuer: nil}
      end)

    d = %__MODULE__{pid: pid, key: key}

    {:ok, srv} =
      Bandit.start_link(plug: {__MODULE__, d}, scheme: :https, ip: {127, 0, 0, 1}, port: 0, startup_log: false,
        thousand_island_options: [shutdown_timeout: 100, transport_options: [cert: sc[:cert], key: sc[:key]]])

    {:ok, {_, port}} = ThousandIsland.listener_info(srv)
    issuer = "https://127.0.0.1:#{port}/dev"
    Agent.update(pid, &%{&1 | issuer: issuer})
    %{d | issuer: issuer, server: srv}
  end

  def close(%__MODULE__{server: s}), do: Supervisor.stop(s)

  @doc "Register a pre-registered client and its redirect URIs."
  def put_client(d, id, redirects), do: Agent.update(d.pid, &put_in(&1, [:clients, id], redirects))
  def set(d, key, value), do: Agent.update(d.pid, &Map.put(&1, key, value))
  def get(d, key), do: Agent.get(d.pid, &Map.get(&1, key))
  def stats(d), do: Agent.get(d.pid, &{&1.authorizations, &1.refreshes})

  @doc "Headless browser for dev/tests: visit an authorization URL and follow the redirect back to the callback."
  def approve(_d, url) do
    with {:ok, _} <- HTTP.get_follow(url), do: :ok
  end

  @doc "Sign an access token directly (tests: forge wrong audience / expired tokens)."
  def mint(d, claims) do
    h = Base.url_encode64(Jason.encode!(%{"alg" => "RS256", "typ" => "JWT", "kid" => "dev"}), padding: false)
    c = Base.url_encode64(Jason.encode!(claims), padding: false)
    sig = :public_key.sign(h <> "." <> c, :sha256, d.key)
    h <> "." <> c <> "." <> Base.url_encode64(sig, padding: false)
  end

  # ---- Plug ----

  @impl true
  def init(d), do: d

  @impl true
  def call(conn, d) do
    st = Agent.get(d.pid, & &1)
    iss = st.issuer

    case conn.request_path do
      "/.well-known/oauth-authorization-server/dev" ->
        js(conn, 200, %{"issuer" => iss, "authorization_endpoint" => iss <> "/authorize", "token_endpoint" => iss <> "/token",
          "jwks_uri" => iss <> "/jwks", "response_types_supported" => ["code"],
          "grant_types_supported" => ["authorization_code", "refresh_token"],
          "code_challenge_methods_supported" => if(st.no_s256, do: ["plain"], else: ["S256"]),
          "token_endpoint_auth_methods_supported" => ["none", "client_secret_basic", "client_secret_post"],
          "authorization_response_iss_parameter_supported" => true,
          "client_id_metadata_document_supported" => not st.no_cimd})

      "/dev/jwks" ->
        {:RSAPrivateKey, _, n, e, _, _, _, _, _, _, _} = d.key
        js(conn, 200, %{"keys" => [%{"kty" => "RSA", "kid" => "dev", "alg" => "RS256", "use" => "sig",
          "n" => Base.url_encode64(:binary.encode_unsigned(n), padding: false),
          "e" => Base.url_encode64(:binary.encode_unsigned(e), padding: false)}]})

      "/dev/authorize" ->
        authorize(conn, d, st)

      "/dev/token" ->
        token(conn, d)

      _ ->
        send_resp(conn, 404, "not found")
    end
  end

  defp js(conn, status, v), do: conn |> put_resp_content_type("application/json", nil) |> send_resp(status, Jason.encode!(v))

  defp redirect(conn, to), do: conn |> put_resp_header("location", to) |> send_resp(302, "")

  defp authorize(conn, d, st) do
    q = URI.decode_query(conn.query_string)
    Agent.update(d.pid, &%{&1 | authorizations: &1.authorizations + 1, last_authorize_query: q})
    {cid, ru} = {q["client_id"] || "", q["redirect_uri"] || ""}

    back = fn params -> redirect(conn, ru <> "?" <> URI.encode_query(params ++ [{"state", q["state"] || ""}])) end

    cond do
      not redirect_allowed?(st, cid, ru) ->
        send_resp(conn, 400, "invalid_client or redirect_uri")  # never redirect to an unverified URI

      q["response_type"] != "code" or q["code_challenge_method"] != "S256" or q["code_challenge"] in [nil, ""] ->
        back.([{"error", "invalid_request"}, {"iss", st.issuer}])

      q["resource"] in [nil, ""] ->
        back.([{"error", "invalid_target"}, {"iss", st.issuer}])

      true ->
        code = Toolnexus.Mcp.Wire.rand_str(16)
        c = %{client_id: cid, redirect: ru, challenge: q["code_challenge"], resource: q["resource"], scope: q["scope"] || ""}
        Agent.update(d.pid, &put_in(&1, [:codes, code], c))
        back.([{"code", code}, {"iss", st.iss_override || st.issuer}])
    end
  end

  # exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3)
  defp redirect_allowed?(st, cid, redirect) do
    allowed =
      if String.starts_with?(cid, "https://") do
        # Client ID Metadata Document
        case HTTP.get_json(HTTP.default(), cid) do
          {:ok, %{"client_id" => ^cid, "client_name" => n, "redirect_uris" => uris}} when is_binary(n) and n != "" -> uris
          _ -> []
        end
      else
        Map.get(st.clients, cid, [])
      end

    ru = URI.parse(redirect)

    Enum.any?(allowed, fn a ->
      au = URI.parse(a)

      a == redirect or
        (ru.host in ["127.0.0.1", "::1", "[::1]"] and au.host == ru.host and au.scheme == ru.scheme and au.path == ru.path)
    end)
  end

  defp token(conn, d) do
    {:ok, body, conn} = read_body(conn)
    f = URI.decode_query(body)

    basic =
      case get_req_header(conn, "authorization") do
        ["Basic " <> b | _] ->
          with {:ok, dec} <- Base.decode64(b), [id, sec] <- :binary.split(dec, ":"),
               do: {URI.decode_www_form(id), URI.decode_www_form(sec)}, else: (_ -> :bad)

        _ ->
          nil
      end

    Agent.get_and_update(d.pid, fn st -> grant(st, f, basic, d) end) |> then(fn {s, v} ->
      if get(d, :form_token) and s == 200,
        do: conn |> put_resp_content_type("application/x-www-form-urlencoded", nil) |> send_resp(200, URI.encode_query(v)),
        else: js(conn, s, v)
    end)
  end

  defp grant(st, f, basic, d) do
    st = %{st | last_token_form: f}
    bad = fn e, st -> {{400, %{"error" => e}}, st} end

    {f, client_err} =
      case basic do
        {id, sec} ->
          if (f["client_id"] not in [nil, ""] and f["client_id"] != id) or st.secrets[id] in [nil, ""] or st.secrets[id] != sec,
            do: {f, true}, else: {Map.put(f, "client_id", id), false}

        :bad -> {f, true}

        nil ->
          want = st.secrets[f["client_id"]]
          {f, want not in [nil, ""] and f["client_secret"] != want}
      end

    if client_err do
      {{401, %{"error" => "invalid_client"}}, st}
    else
      case f["grant_type"] do
        "authorization_code" ->
          case Map.pop(st.codes, f["code"]) do
            {nil, _} -> bad.("invalid_grant", st)
            {c, codes} ->
              st = %{st | codes: codes}  # single use
              sum = Base.url_encode64(:crypto.hash(:sha256, f["code_verifier"] || ""), padding: false)

              if sum != c.challenge or f["client_id"] != c.client_id or f["redirect_uri"] != c.redirect or f["resource"] != c.resource,
                do: bad.("invalid_grant", st),
                else: issue(st, c, d)
          end

        "refresh_token" ->
          case Map.pop(st.rts, f["refresh_token"]) do
            {c, rts} when c != nil ->
              if f["client_id"] != c.client_id do
                bad.("invalid_grant", st)
              else
                st = %{st | rts: rts}  # rotation (required for public clients)
                res = f["resource"]

                if res not in [nil, ""] and (res != c.resource or st.reject_refresh_resource),
                  do: bad.("invalid_target", st),
                  else: issue(%{st | refreshes: st.refreshes + 1}, c, d)
              end

            _ -> bad.("invalid_grant", st)
          end

        _ ->
          bad.("unsupported_grant_type", st)
      end
    end
  end

  defp issue(st, c, d) do
    rt = Toolnexus.Mcp.Wire.rand_str(24)
    now = System.system_time(:second)

    at = mint(d, %{"iss" => st.issuer, "sub" => "dev-user", "aud" => c.resource, "scope" => c.scope,
      "client_id" => c.client_id, "jti" => Toolnexus.Mcp.Wire.rand_str(8), "exp" => now + st.access_ttl, "iat" => now})

    {{200, %{"access_token" => at, "token_type" => "Bearer", "expires_in" => st.access_ttl, "refresh_token" => rt, "scope" => c.scope}},
     put_in(st, [:rts, rt], c)}
  end
end
