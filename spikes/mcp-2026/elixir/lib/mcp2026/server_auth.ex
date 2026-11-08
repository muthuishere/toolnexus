defmodule Mcp2026.Principal do
  @moduledoc "The authenticated caller, visible to tool handlers as `ctx.principal`."
  defstruct subject: "", scopes: [], claims: %{}
end

defmodule Mcp2026.ServerAuth do
  @moduledoc """
  Protects a `Mcp2026.Server` as an OAuth 2.1 protected resource (MCP 2026-07-28 basic/authorization).

      %{server | auth: ServerAuth.new(issuer: iss, scopes: %{"*" => ["mcp:read"], "greet" => ["mcp:greet"]})}

  `scopes["*"]` is required on every request (also advertised as scopes_supported); `scopes[tool]` is
  additionally required for that tools/call. `:audience` defaults to scheme://host/mcp of the request.
  `:verify` (`fn token -> {:ok, %Principal{}} | {:error, reason} end`) replaces the default RS256
  JWT/JWKS verifier (opaque tokens, introspection, API keys).
  """
  import Plug.Conn
  alias Mcp2026.{HTTP, Principal}

  defstruct issuer: nil, audience: nil, scopes: %{}, verify: nil, http: nil, cache: nil

  @prm_path "/.well-known/oauth-protected-resource"

  def new(opts) do
    {:ok, cache} = Agent.start_link(fn -> %{keys: nil, fetched: 0} end)
    a = struct!(%__MODULE__{cache: cache}, opts)
    %{a | http: a.http || HTTP.default()}
  end

  defp host(conn), do: get_req_header(conn, "host") |> List.first() || "#{conn.host}:#{conn.port}"
  defp audience(%{audience: a}, _conn) when is_binary(a) and a != "", do: a
  defp audience(_, conn), do: "#{conn.scheme}://#{host(conn)}/mcp"

  @doc false
  def metadata_path?(conn), do: conn.request_path in [@prm_path, @prm_path <> "/mcp"]

  @doc false
  # RFC 9728 PRM at both the path-inserted and root well-known URIs.
  def serve_metadata(a, conn) do
    body = %{"resource" => audience(a, conn), "authorization_servers" => [a.issuer],
             "scopes_supported" => a.scopes["*"], "bearer_methods_supported" => ["header"]}

    conn |> put_resp_content_type("application/json", nil) |> send_resp(200, JSON.encode!(body))
  end

  @doc false
  # {:ok, principal} or {:halt, conn} after writing 401/403.
  def check(a, conn, method, p) do
    base = a.scopes["*"] || []

    challenge = fn status, extra, scopes ->
      v = ~s(Bearer resource_metadata="#{conn.scheme}://#{host(conn)}#{@prm_path}/mcp")
      v = if scopes != [], do: v <> ~s(, scope="#{Enum.join(scopes, " ")}"), else: v

      {:halt,
       conn
       |> put_resp_header("www-authenticate", v <> extra)
       |> put_resp_content_type("application/json", nil)
       |> send_resp(status, ~s({"error":"unauthorized"}))}
    end

    case get_req_header(conn, "authorization") do
      ["Bearer " <> tok | _] when tok != "" ->
        verify = a.verify || fn t -> verify_jwt(a, t, audience(a, conn)) end

        case verify.(tok) do
          {:ok, %Principal{} = pr} ->
            need = base ++ if(method == "tools/call", do: a.scopes[p["name"]] || [], else: [])

            if Enum.all?(need, &(&1 in pr.scopes)),
              do: {:ok, pr},
              else: challenge.(403, ~s(, error="insufficient_scope"), need)

          _ ->
            # never echo the token or the detailed reason
            challenge.(401, ~s(, error="invalid_token"), base)
        end

      _ ->
        challenge.(401, "", base)
    end
  end

  # ---- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ----

  defp verify_jwt(a, tok, aud) do
    with [h, c, s] <- String.split(tok, "."),
         {:ok, %{"alg" => "RS256"} = hdr} <- seg(h),
         {:ok, %{} = claims} <- seg(c),
         {:ok, sig} <- Base.url_decode64(s, padding: false),
         {:ok, key} <- key(a, hdr["kid"]),
         true <- :public_key.verify(h <> "." <> c, :sha256, sig, key) || {:error, "bad signature"},
         true <- claims["iss"] == a.issuer || {:error, "wrong issuer"},
         true <- aud_contains(claims["aud"], aud) || {:error, "wrong audience"},
         true <- (is_number(claims["exp"]) and claims["exp"] > System.system_time(:second)) || {:error, "expired"} do
      sc = if is_binary(claims["scope"]), do: String.split(claims["scope"]), else: []
      {:ok, %Principal{subject: to_string(claims["sub"] || ""), scopes: sc, claims: claims}}
    else
      {:error, _} = e -> e
      _ -> {:error, "bad jwt"}
    end
  end

  defp aud_contains(x, aud) when is_binary(x), do: x == aud
  defp aud_contains(x, aud) when is_list(x), do: aud in x
  defp aud_contains(_, _), do: false

  defp seg(s) do
    with {:ok, b} <- Base.url_decode64(s, padding: false), do: JSON.decode(b)
  end

  defp key(a, kid) do
    :global.trans({{__MODULE__, a.cache}, self()}, fn ->
      st = Agent.get(a.cache, & &1)
      now = System.monotonic_time(:millisecond)

      cond do
        st.keys != nil and Map.has_key?(st.keys, kid) ->
          {:ok, st.keys[kid]}

        st.keys != nil and now - st.fetched < 30_000 ->
          {:error, "unknown kid"}  # garbage kids must not hammer the AS

        true ->
          with {:ok, md} <- Mcp2026.OAuth.discover_as(a.http, a.issuer),
               {:ok, %{"keys" => ks}} <- HTTP.get_json(a.http, to_string(md["jwks_uri"])) do
            keys =
              for %{"kty" => "RSA", "kid" => k, "n" => n, "e" => e} <- ks,
                  {:ok, nb} <- [Base.url_decode64(n, padding: false)],
                  {:ok, eb} <- [Base.url_decode64(e, padding: false)],
                  into: %{},
                  do: {k, {:RSAPublicKey, :binary.decode_unsigned(nb), :binary.decode_unsigned(eb)}}

            Agent.update(a.cache, fn _ -> %{keys: keys, fetched: now} end)
            if Map.has_key?(keys, kid), do: {:ok, keys[kid]}, else: {:error, "unknown kid"}
          else
            _ -> {:error, "jwks unavailable"}
          end
      end
    end, [node()], :infinity)
  end
end
