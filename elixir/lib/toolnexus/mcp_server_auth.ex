defmodule Toolnexus.McpPrincipal do
  @moduledoc """
  The verified caller (SPEC §7C.2). `Toolnexus.McpServerAuth.authenticate/2` returns it,
  so hooks and tools read it with `Toolnexus.McpHandler.identity/1`.
  """
  defstruct subject: "", scopes: [], claims: %{}

  @type t :: %__MODULE__{subject: String.t(), scopes: [String.t()], claims: map()}
end

defmodule Toolnexus.McpAuthError do
  @moduledoc """
  An `:authenticate` failure with an explicit HTTP status and challenge
  (`Toolnexus.McpServerAuth` returns it; any authenticate hook may). On either
  wire the handler answers `status` (nil/0 ⇒ 401) with `WWW-Authenticate` =
  `challenge` (a non-empty `:www_authenticate` hook result wins) and the body
  `{"error":"unauthorized"}`. `reason` is never sent on the wire.
  """
  defexception status: 401, challenge: "", reason: nil

  @type t :: %__MODULE__{status: integer(), challenge: String.t(), reason: String.t() | nil}

  @impl true
  def message(%{reason: r}) when is_binary(r) and r != "", do: r
  def message(_), do: "unauthorized"
end

defmodule Toolnexus.McpServerAuth do
  @moduledoc """
  MCP server authorization (SPEC §2A.2, §7C.2): a `Toolnexus.McpHandler` as an
  OAuth 2.1 protected resource. A plain helper that produces the handler's
  `:authenticate` hook and the RFC 9728 metadata plug; the host mounts both.
  Identity, tenants and token storage stay the host's.

      auth = Toolnexus.McpServerAuth.new(issuer: "https://login.example", scopes: %{"*" => ["mcp"]})

      handler =
        Toolnexus.mcp_handler(toolkit,
          protocol_versions: ["2026-07-28"],
          authenticate: Toolnexus.McpServerAuth.authenticator(auth)
        )

      # in a router: forward "/.well-known/oauth-protected-resource", to: Toolnexus.McpServerAuth, init_opts: auth
      # and mount {Toolnexus.McpHandler, handler} at "/mcp"

  ## Options (`new/1`)

    * `:issuer` — the authorization server's issuer (advertised in the PRM document;
      the JWT `iss` the default verifier requires). Required.
    * `:audience` — the canonical resource URI (PRM `resource`, the JWT `aud`).
      Absent ⇒ derived per request: `<scheme>://<Host><resource_path>`.
    * `:resource_path` — where the handler is mounted. Default `"/mcp"`.
    * `:scopes` — `%{"*" => [..], "<tool>" => [..]}`: `"*"` is required for every
      request (and advertised as `scopes_supported`); a tool name adds scopes for that
      `tools/call` (read from the validated `Mcp-Method` / `Mcp-Name` headers, so it
      applies on the 2026-07-28 wire; older wires check `"*"` only). Exact match.
    * `:verify` — `fn conn, token, audience -> {:ok, %Toolnexus.McpPrincipal{}} | {:error, reason} end`
      replaces the default RS256/JWKS verifier (opaque tokens, introspection).
    * `:http_options` — extra `Req` options for the AS-metadata / JWKS fetches.

  The token and the failure reason never appear on the wire.
  """

  @behaviour Plug
  import Plug.Conn

  alias Toolnexus.{McpAuthError, McpPrincipal}

  defstruct issuer: nil,
            audience: nil,
            resource_path: "/mcp",
            scopes: %{},
            verify: nil,
            http_options: [],
            cache: nil

  @type t :: %__MODULE__{}

  @prm "/.well-known/oauth-protected-resource"

  @doc "Build an auth helper (see the module doc for options)."
  @spec new(keyword() | map()) :: t()
  def new(opts) do
    {:ok, cache} = Agent.start(fn -> %{keys: nil, fetched: 0} end)
    a = struct!(__MODULE__, Map.new(opts))
    %{a | cache: cache, resource_path: blank(a.resource_path, "/mcp"), scopes: a.scopes || %{}}
  end

  @doc "Stop the JWKS cache process."
  @spec stop(t()) :: :ok
  def stop(%__MODULE__{cache: c}), do: Agent.stop(c)

  @doc "The `:authenticate` hook for `Toolnexus.mcp_handler/2`."
  @spec authenticator(t()) :: (Plug.Conn.t() ->
                                 {:ok, McpPrincipal.t()} | {:error, McpAuthError.t()})
  def authenticator(%__MODULE__{} = a), do: fn conn -> authenticate(a, conn) end

  @doc """
  Authenticate one request. Missing bearer ⇒ 401; a failed verify ⇒ 401
  `error="invalid_token"`; a missing scope ⇒ 403 `error="insufficient_scope"` naming
  the full needed set.
  """
  @spec authenticate(t(), Plug.Conn.t()) :: {:ok, McpPrincipal.t()} | {:error, McpAuthError.t()}
  def authenticate(%__MODULE__{} = a, conn) do
    base = a.scopes["*"] || []

    case get_req_header(conn, "authorization") do
      ["Bearer " <> tok | _] ->
        if String.trim(tok) == "" do
          {:error, challenge(a, conn, 401, base, nil)}
        else
          verify(a, conn, tok, base)
        end

      _ ->
        {:error, challenge(a, conn, 401, base, nil)}
    end
  end

  defp verify(a, conn, tok, base) do
    aud = audience(a, conn)

    result =
      try do
        if is_function(a.verify, 3), do: a.verify.(conn, tok, aud), else: verify_jwt(a, tok, aud)
      rescue
        _ -> {:error, "verify raised"}
      end

    case result do
      {:ok, %McpPrincipal{} = p} ->
        need = Enum.uniq(base ++ tool_scopes(a, conn))

        if Enum.all?(need, &(&1 in (p.scopes || []))),
          do: {:ok, p},
          else: {:error, challenge(a, conn, 403, need, "insufficient_scope")}

      _ ->
        {:error, challenge(a, conn, 401, base, "invalid_token")}
    end
  end

  defp tool_scopes(a, conn) do
    with ["tools/call" | _] <- get_req_header(conn, "mcp-method"),
         {name, true} <-
           Toolnexus.McpHandler.Wire2026.decode_header_value(
             conn |> get_req_header("mcp-name") |> List.first() || ""
           ) do
      a.scopes[name] || []
    else
      _ -> []
    end
  end

  defp challenge(a, conn, status, scopes, error) do
    v = ~s(Bearer resource_metadata="#{metadata_url(a, conn)}")
    v = if scopes != [], do: v <> ~s(, scope="#{Enum.join(scopes, " ")}"), else: v
    v = if error, do: v <> ~s(, error="#{error}"), else: v
    %McpAuthError{status: status, challenge: v, reason: error || "missing bearer token"}
  end

  defp origin(conn) do
    host = conn |> get_req_header("host") |> List.first() || "#{conn.host}:#{conn.port}"
    "#{conn.scheme}://#{host}"
  end

  @doc "The canonical resource URI for this request (`:audience`, else derived)."
  @spec audience(t(), Plug.Conn.t()) :: String.t()
  def audience(%{audience: aud}, _conn) when is_binary(aud) and aud != "", do: aud
  def audience(a, conn), do: origin(conn) <> a.resource_path

  @doc "The RFC 9728 metadata URL the challenge names."
  @spec metadata_url(t(), Plug.Conn.t()) :: String.t()
  def metadata_url(a, conn) do
    case a.audience && Regex.run(~r{^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]+)([^?#]*)}, a.audience) do
      [_, scheme, host, path] -> "#{scheme}://#{host}#{@prm}#{path}"
      _ -> origin(conn) <> @prm <> a.resource_path
    end
  end

  @doc "The metadata plug spec: `{Toolnexus.McpServerAuth, auth}`."
  @spec metadata(t()) :: {module(), t()}
  def metadata(%__MODULE__{} = a), do: {__MODULE__, a}

  # ── Plug: the RFC 9728 protected resource metadata ─────────────────────

  @impl Plug
  def init(%__MODULE__{} = a), do: a

  @impl Plug
  def call(conn, %__MODULE__{} = a) do
    if conn.method == "GET" and conn.request_path in [@prm, @prm <> a.resource_path] do
      body = %{
        "resource" => audience(a, conn),
        "authorization_servers" => [a.issuer],
        "scopes_supported" => a.scopes["*"] || [],
        "bearer_methods_supported" => ["header"]
      }

      conn
      |> put_resp_header("content-type", "application/json")
      |> send_resp(200, Jason.encode!(body))
    else
      send_resp(conn, 404, "404 page not found\n")
    end
  end

  # ── default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ──

  defp verify_jwt(a, tok, aud) do
    now = System.system_time(:second)

    with [h, c, s] <- String.split(tok, "."),
         {:ok, %{"alg" => "RS256"} = hdr} <- seg(h),
         {:ok, %{} = claims} <- seg(c),
         {:ok, key} <- key(a, hdr["kid"]),
         {:ok, sig} <- Base.url_decode64(s, padding: false),
         true <- :public_key.verify(h <> "." <> c, :sha256, sig, key),
         true <- claims["iss"] == a.issuer,
         true <- aud_ok?(claims["aud"], aud),
         true <- is_number(claims["exp"]) and trunc(claims["exp"]) > now do
      scopes = if is_binary(claims["scope"]), do: String.split(claims["scope"]), else: []
      sub = if is_binary(claims["sub"]), do: claims["sub"], else: ""
      {:ok, %McpPrincipal{subject: sub, scopes: scopes, claims: claims}}
    else
      _ -> {:error, "invalid token"}
    end
  end

  defp aud_ok?(x, aud) when is_binary(x), do: x == aud
  defp aud_ok?(x, aud) when is_list(x), do: aud in x
  defp aud_ok?(_, _), do: false

  defp seg(s) do
    with {:ok, b} <- Base.url_decode64(s, padding: false), do: Jason.decode(b)
  end

  # The JWKS key for kid; an unknown kid refetches at most every 30 s.
  defp key(a, kid) do
    Agent.get_and_update(
      a.cache,
      fn st ->
        now = System.monotonic_time(:millisecond)

        cond do
          st.keys != nil and Map.has_key?(st.keys, kid) ->
            {{:ok, st.keys[kid]}, st}

          st.keys != nil and now - st.fetched < 30_000 ->
            {{:error, "unknown kid"}, st}

          true ->
            case fetch_keys(a) do
              {:ok, keys} ->
                reply =
                  if Map.has_key?(keys, kid), do: {:ok, keys[kid]}, else: {:error, "unknown kid"}

                {reply, %{keys: keys, fetched: now}}

              e ->
                {e, st}
            end
        end
      end,
      30_000
    )
  end

  defp fetch_keys(a) do
    with {:ok, md} <- discover_as(a, a.issuer),
         {:ok, %{"keys" => ks}} when is_list(ks) <-
           get_json(a.http_options, to_string(md["jwks_uri"])) do
      keys =
        for %{"kty" => "RSA", "kid" => k, "n" => n, "e" => e} <- ks,
            {:ok, nb} <- [Base.url_decode64(n, padding: false)],
            {:ok, eb} <- [Base.url_decode64(e, padding: false)],
            into: %{},
            do: {k, {:RSAPublicKey, :binary.decode_unsigned(nb), :binary.decode_unsigned(eb)}}

      {:ok, keys}
    else
      _ -> {:error, "jwks unavailable"}
    end
  end

  # AS metadata in the SPEC §2A.1 step 3 order; its issuer must match.
  defp discover_as(a, issuer) do
    u = URI.parse(issuer)
    origin = "#{u.scheme}://#{authority(issuer)}"
    path = String.trim_trailing(u.path || "", "/")

    cands =
      [
        origin <> "/.well-known/oauth-authorization-server" <> path,
        origin <> "/.well-known/openid-configuration" <> path
      ] ++ if(path != "", do: [origin <> path <> "/.well-known/openid-configuration"], else: [])

    Enum.find_value(cands, {:error, "authorization server metadata not found"}, fn c ->
      case get_json(a.http_options, c) do
        {:ok, %{"issuer" => ^issuer} = md} -> {:ok, md}
        _ -> nil
      end
    end)
  end

  defp authority(url) do
    case Regex.run(~r{^[A-Za-z][A-Za-z0-9+.\-]*://([^/?#]+)}, url) do
      [_, auth] -> auth
      _ -> ""
    end
  end

  @doc false
  def get_json(opts, url) do
    req =
      Keyword.merge(
        [
          url: url,
          headers: [{"accept", "application/json"}],
          retry: false,
          decode_body: false,
          receive_timeout: 10_000
        ],
        opts || []
      )

    case Req.get(req) do
      {:ok, %{status: 200, body: body}} -> Jason.decode(body)
      {:ok, %{status: s}} -> {:error, "GET #{url}: HTTP #{s}"}
      {:error, e} -> {:error, e}
    end
  rescue
    e -> {:error, e}
  end

  defp blank(v, d) when v in [nil, ""], do: d
  defp blank(v, _), do: v
end
