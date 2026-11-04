defmodule Toolnexus.Mcp.Token do
  @moduledoc """
  An OAuth access token bound to the issuer that minted it and the resource (MCP
  server URL) it is for (SPEC §2A.1a). Its `inspect`/`to_string` forms never show a
  token value.
  """
  defstruct [
    :access_token,
    :refresh_token,
    :expires_at,
    :issuer,
    :client_id,
    :resource,
    scopes: []
  ]

  @type t :: %__MODULE__{
          access_token: String.t(),
          refresh_token: String.t() | nil,
          expires_at: integer() | nil,
          issuer: String.t(),
          client_id: String.t(),
          resource: String.t() | nil,
          scopes: [String.t()]
        }

  defimpl Inspect do
    def inspect(t, _), do: "#Toolnexus.Mcp.Token<issuer=#{t.issuer}, redacted>"
  end

  defimpl String.Chars do
    def to_string(t), do: "Token{issuer=#{t.issuer}, redacted}"
  end
end

defmodule Toolnexus.Mcp.TokenStore do
  @moduledoc """
  Where OAuth tokens live, keyed by resource (the MCP server URL) — SPEC §2A.1a
  `tokenStore`. Pass a struct whose module implements this behaviour, or a map of
  functions `%{load: fn resource -> token | nil end, save: fn resource, token -> any
  end, delete: fn resource -> any end}`. Implementations must keep tokens
  confidential. Default: `Toolnexus.Mcp.MemoryTokenStore` (one per load).
  """
  @callback load(store :: struct(), resource :: String.t()) :: Toolnexus.Mcp.Token.t() | nil
  @callback save(store :: struct(), resource :: String.t(), Toolnexus.Mcp.Token.t()) :: any()
  @callback delete(store :: struct(), resource :: String.t()) :: any()

  @doc false
  def load(%{load: f}, r) when is_function(f, 1), do: f.(r)
  def load(%m{} = s, r), do: m.load(s, r)
  @doc false
  def save(%{save: f}, r, t) when is_function(f, 2), do: f.(r, t)
  def save(%m{} = s, r, t), do: m.save(s, r, t)
  @doc false
  def delete(%{delete: f}, r) when is_function(f, 1), do: f.(r)
  def delete(%m{} = s, r), do: m.delete(s, r)
end

defmodule Toolnexus.Mcp.MemoryTokenStore do
  @moduledoc "In-memory `Toolnexus.Mcp.TokenStore` (an Agent)."
  @behaviour Toolnexus.Mcp.TokenStore
  defstruct [:pid]

  @doc "Start one. `start` (optional) starts the Agent, e.g. under a supervisor."
  def new(start \\ nil) do
    init = fn -> %{} end
    {:ok, pid} = if start, do: start.(init), else: Agent.start_link(init)
    %__MODULE__{pid: pid}
  end

  @impl true
  def load(%{pid: p}, r), do: Agent.get(p, &Map.get(&1, r))
  @impl true
  def save(%{pid: p}, r, t), do: Agent.update(p, &Map.put(&1, r, t))
  @impl true
  def delete(%{pid: p}, r), do: Agent.update(p, &Map.delete(&1, r))
end

defmodule Toolnexus.Mcp.OAuth do
  @moduledoc """
  OAuth 2.1 client for MCP servers (SPEC §2A.1, MCP 2026-07-28 `basic/authorization`).

  Flow: 401/403 + `WWW-Authenticate` → Protected Resource Metadata (RFC 9728) →
  AS metadata (RFC 8414, then OIDC) → client identity (pre-registered
  `clientId`/`clientIds`, else a Client ID Metadata Document — **never** dynamic
  client registration) → authorization code + PKCE S256 + `resource` → `iss` +
  `state` validation → token → retry with `Authorization: Bearer`.

  Modes: `"auto"` opens the browser (`:open_url`, default the OS opener) and
  receives the callback on `http://127.0.0.1:<random>/callback`; `"pause"` returns
  a §10 `Request{kind:"authorization"}` that is completed by `resume/3` with the
  callback URL the AS redirected to.

  Tokens, codes, verifiers and secrets are never logged and never appear in errors.
  """
  alias Toolnexus.Mcp.{MemoryTokenStore, Token, TokenStore, Wire}
  alias Toolnexus.Request

  defstruct mode: "auto",
            redirect_uri: nil,
            open_url: nil,
            client_id: nil,
            client_secret: nil,
            token_auth_method: nil,
            client_ids: %{},
            client_metadata_url: nil,
            store: nil,
            omit_refresh_resource: false,
            allow_http: false,
            http: nil,
            pid: nil

  @prm_path "/.well-known/oauth-protected-resource"
  @auto_timeout 300_000
  @pause_ttl 600

  @doc """
  Build from an mcp.json `auth` object (camelCase keys, SPEC §2A.1) plus options
  `:token_store`, `:open_url`, `:http`, `:start` (fun starting an Agent, e.g. under
  a supervisor).
  """
  def new(cfg, opts \\ []) do
    cfg = cfg || %{}
    start = opts[:start] || (&Agent.start_link/1)
    {:ok, pid} = start.(fn -> %{issuer: %{}, flows: %{}} end)

    %__MODULE__{
      mode: if(cfg["mode"] == "pause", do: "pause", else: "auto"),
      redirect_uri: blank(cfg["redirectUri"]),
      open_url: opts[:open_url],
      client_id: blank(cfg["clientId"]),
      client_secret: blank(cfg["clientSecret"]),
      token_auth_method: blank(cfg["tokenAuthMethod"]),
      client_ids: if(is_map(cfg["clientIds"]), do: cfg["clientIds"], else: %{}),
      client_metadata_url: blank(cfg["clientMetadataUrl"]),
      omit_refresh_resource: cfg["omitRefreshResource"] == true,
      allow_http: cfg["allowHttp"] == true,
      store: opts[:token_store] || MemoryTokenStore.new(opts[:start]),
      http: opts[:http] || Wire.default(),
      pid: pid
    }
  end

  defp blank(v) when is_binary(v) and v != "", do: v
  defp blank(_), do: nil

  # One login / one refresh at a time per OAuth (refresh tokens rotate). Re-entrant
  # for the same process.
  defp locked(o, fun), do: :global.trans({{__MODULE__, o.pid}, self()}, fun, [node()], :infinity)

  defp issuer_for(o, resource), do: Agent.get(o.pid, &Map.get(&1.issuer, resource))

  @doc """
  The bearer token to send to `resource`, refreshing an expired one (single-flight);
  `nil` before the server's issuer is confirmed in this load (SPEC §2A.1 step 1, 11).
  """
  def bearer(o, resource) do
    iss = issuer_for(o, resource)

    case TokenStore.load(o.store, resource) do
      %Token{issuer: ^iss} = t when iss != nil ->
        if expired?(t) do
          locked(o, fn ->
            with %Token{} = t <- TokenStore.load(o.store, resource),
                 :ok <- if(expired?(t), do: refresh(o, t), else: :ok),
                 %Token{} = t <- TokenStore.load(o.store, resource) do
              t.access_token
            else
              _ -> nil
            end
          end)
        else
          t.access_token
        end

      _ ->
        nil
    end
  end

  defp expired?(%Token{expires_at: nil}), do: false
  defp expired?(%Token{expires_at: e}), do: System.system_time(:second) > e - 10

  defp refresh(_o, %Token{refresh_token: rt}) when rt in [nil, ""],
    do: {:error, "no refresh token"}

  defp refresh(o, t) do
    with {:ok, md} <- discover_as(o.http, t.issuer) do
      form = [
        {"grant_type", "refresh_token"},
        {"refresh_token", t.refresh_token},
        {"client_id", t.client_id}
      ]

      form = if o.omit_refresh_resource, do: form, else: form ++ [{"resource", t.resource}]

      case token_request(o, md["token_endpoint"], form, t) do
        {:ok, nt} ->
          nt =
            if nt.refresh_token in [nil, ""], do: %{nt | refresh_token: t.refresh_token}, else: nt

          TokenStore.save(o.store, t.resource, nt)
          :ok

        err ->
          TokenStore.delete(o.store, t.resource)
          err
      end
    end
  end

  @doc false
  # A 401/403 with WWW-Authenticate. `:ok` = retry now; `{:pending, request}` = paused
  # (complete with resume/3, which then runs `retry`); `{:error, msg}`.
  def challenge(o, resource, status, www, sent, retry) do
    params = parse_challenge(www)
    locked(o, fn -> do_challenge(o, resource, status, params, sent, retry) end)
  end

  defp do_challenge(o, resource, status, params, sent, retry) do
    cur = TokenStore.load(o.store, resource)
    known = issuer_for(o, resource)
    refreshable = cur != nil and cur.refresh_token not in [nil, ""]

    cond do
      cur != nil and known != nil and cur.issuer == known and cur.access_token != sent and
          not expired?(cur) ->
        # someone else refreshed/authorized while we waited
        :ok

      status == 401 and refreshable and params["error"] == "invalid_token" and known != nil and
          refresh(o, cur) == :ok ->
        :ok

      true ->
        cur = if status == 401 and params["error"] == "invalid_token", do: nil, else: cur
        authorize(o, resource, status, params, cur, retry)
    end
  end

  defp authorize(o, resource, status, params, cur, retry) do
    if status == 403 and params["error"] != "insufficient_scope",
      do: {:error, "forbidden: #{params["error"]}"},
      else: do_authorize(o, resource, status, params, cur, retry)
  end

  defp do_authorize(o, resource, status, params, cur, retry) do
    with {:ok, prm} <- fetch_prm(o, resource, params["resource_metadata"]),
         {:ok, issuer} <- first_issuer(o, prm),
         {:ok, md} <- discover_as(o.http, issuer),
         :ok <- require_s256(md) do
      Agent.update(o.pid, &put_in(&1, [:issuer, resource], issuer))

      if status == 401 and cur != nil and cur.issuer == issuer and params["error"] in [nil, ""] do
        # first request after a restart: the issuer is now confirmed, send the stored token
        :ok
      else
        start_flow(o, resource, status, params, cur, retry, prm, issuer, md)
      end
    end
  end

  defp require_s256(md) do
    if "S256" in List.wrap(md["code_challenge_methods_supported"]),
      do: :ok,
      else: {:error, "authorization server does not advertise PKCE S256; refusing"}
  end

  defp first_issuer(o, prm) do
    case prm["authorization_servers"] do
      [iss | _] when is_binary(iss) ->
        if o.allow_http or String.starts_with?(iss, "https://"),
          do: {:ok, iss},
          else: {:error, "authorization server must use https"}

      _ ->
        {:error, "PRM lists no authorization_servers"}
    end
  end

  defp start_flow(o, resource, status, params, cur, retry, prm, issuer, md) do
    scopes = String.split(params["scope"] || "")

    scopes =
      if scopes == [],
        do: for(s <- List.wrap(prm["scopes_supported"]), is_binary(s), do: s),
        else: scopes

    scopes = if cur != nil and status == 403, do: Enum.uniq(scopes ++ cur.scopes), else: scopes
    cimd = md["client_id_metadata_document_supported"] == true

    client_id =
      blank(Map.get(o.client_ids, issuer)) || o.client_id ||
        if(o.client_metadata_url && cimd, do: o.client_metadata_url)

    if client_id == nil do
      why =
        if cimd,
          do: "auth.clientMetadataUrl is not set",
          else: "it does not support Client ID Metadata Documents"

      {:error,
       "no client identity for #{issuer}: #{why} and no client id is configured — register an app with #{issuer} and set auth.clientId (dynamic client registration is not supported)"}
    else
      run_flow(o, %{
        resource: resource,
        issuer: issuer,
        client_id: client_id,
        scopes: scopes,
        retry: retry,
        md: md
      })
    end
  end

  defp run_flow(%{mode: "pause"} = o, f) do
    if o.redirect_uri == nil do
      {:error, "auth mode pause needs redirectUri"}
    else
      {f, req} = prepare(f, o.redirect_uri)
      now = System.system_time(:second)

      Agent.update(o.pid, fn st ->
        # drop abandoned pendings so they cannot pile up
        flows = st.flows |> Enum.reject(fn {_, x} -> now > x.expires end) |> Map.new()
        %{st | flows: Map.put(flows, req.id, f)}
      end)

      {:pending, req}
    end
  end

  defp run_flow(o, f) do
    ref = make_ref()
    want = if o.redirect_uri, do: URI.parse(o.redirect_uri).port || 0, else: 0
    {:ok, srv} = start_callback(self(), ref, want)

    try do
      {:ok, {_, port}} = ThousandIsland.listener_info(srv)
      {f, req} = prepare(f, o.redirect_uri || "http://127.0.0.1:#{port}/callback")
      parent = self()
      open = o.open_url || (&open_browser/1)
      # The opener may block (a headless approver follows redirects synchronously).
      spawn(fn -> send(parent, {ref, :opened, safe_open(open, req.url)}) end)

      with {:ok, cb} <- await(ref, true), do: complete(o, f, cb)
    after
      stop_server(srv)
    end
  end

  defp prepare(f, redirect) do
    verifier = Wire.rand_str(32)
    expires = System.system_time(:second) + @pause_ttl
    md = f.md

    f =
      Map.merge(f, %{
        redirect: redirect,
        verifier: verifier,
        state: Wire.rand_str(16),
        token_endpoint: md["token_endpoint"],
        expires: expires,
        iss_supported: md["authorization_response_iss_parameter_supported"] == true
      })

    q =
      [
        {"response_type", "code"},
        {"client_id", f.client_id},
        {"redirect_uri", redirect},
        {"state", f.state},
        {"code_challenge", Base.url_encode64(:crypto.hash(:sha256, verifier), padding: false)},
        {"code_challenge_method", "S256"},
        {"resource", f.resource}
      ] ++ if(f.scopes != [], do: [{"scope", Enum.join(f.scopes, " ")}], else: [])

    req = %Request{
      id: "auth-" <> Wire.rand_str(12),
      kind: "authorization",
      prompt: "Authorize access to " <> f.resource,
      url: to_string(md["authorization_endpoint"]) <> "?" <> URI.encode_query(q),
      expires_at: expires |> DateTime.from_unix!() |> DateTime.to_iso8601()
    }

    {f, req}
  end

  defp await(ref, opener_pending) do
    receive do
      {^ref, :callback, url} ->
        {:ok, url}

      {^ref, :opened, :ok} when opener_pending ->
        await(ref, false)

      {^ref, :opened, {:error, e}} when opener_pending ->
        # a failed opener must not leave us waiting 5 minutes for a callback that cannot come
        {:error, "open authorization URL: #{e}"}
    after
      @auto_timeout -> {:error, "authorization timed out"}
    end
  end

  defp safe_open(open, url) do
    case open.(url) do
      {:error, e} -> {:error, if(is_binary(e), do: e, else: inspect(e))}
      _ -> :ok
    end
  rescue
    e -> {:error, Exception.message(e)}
  end

  @doc false
  def open_browser(url) do
    {cmd, args} =
      case :os.type() do
        {:unix, :darwin} -> {"open", [url]}
        {:win32, _} -> {"rundll32", ["url.dll,FileProtocolHandler", url]}
        _ -> {"xdg-open", [url]}
      end

    case System.cmd(cmd, args, stderr_to_stdout: true) do
      {_, 0} -> :ok
      {out, _} -> {:error, String.trim(out)}
    end
  rescue
    e -> {:error, Exception.message(e)}
  end

  defmodule Callback do
    @moduledoc false
    @behaviour Plug
    def init(o), do: o

    def call(conn, {parent, ref}) do
      if conn.request_path == "/callback" do
        url = "http://127.0.0.1:#{conn.port}/callback?#{conn.query_string}"
        send(parent, {ref, :callback, url})
        Plug.Conn.send_resp(conn, 200, "Authorization received. You can close this window.")
      else
        Plug.Conn.send_resp(conn, 404, "not found")
      end
    end
  end

  defp start_callback(parent, ref, port) do
    Bandit.start_link(
      plug: {Callback, {parent, ref}},
      ip: {127, 0, 0, 1},
      port: port,
      startup_log: false,
      thousand_island_options: [shutdown_timeout: 100]
    )
  end

  defp stop_server(srv) do
    Process.unlink(srv)
    Supervisor.stop(srv)
  catch
    :exit, _ -> :ok
  end

  @doc """
  Finish a paused authorization with the callback URL the AS redirected to, then
  send the paused request for the first time; returns that request's outcome.
  """
  def resume(o, id, callback_url) do
    f =
      Agent.get_and_update(o.pid, fn st ->
        {st.flows[id], %{st | flows: Map.delete(st.flows, id)}}
      end)

    cond do
      f == nil -> {:error, "unknown pending authorization #{inspect(id)}"}
      System.system_time(:second) > f.expires -> {:error, "pending authorization expired"}
      true -> with :ok <- complete(o, f, to_string(callback_url)), do: f.retry.()
    end
  end

  defp complete(o, f, callback_url) do
    pairs = (URI.parse(callback_url).query || "") |> URI.query_decoder() |> Enum.to_list()
    get = fn k -> Enum.find_value(pairs, fn {kk, v} -> kk == k && v end) end
    iss = get.("iss")

    # RFC 9207 §2.4 — before anything else, error responses included.
    cond do
      iss != nil and iss != f.issuer ->
        {:error, "authorization response iss mismatch (possible mix-up attack)"}

      iss == nil and f.iss_supported ->
        {:error, "authorization response missing iss"}

      get.("state") != f.state ->
        {:error, "authorization response state mismatch"}

      get.("error") not in [nil, ""] ->
        {:error, "authorization denied: #{get.("error")}"}

      true ->
        form = [
          {"grant_type", "authorization_code"},
          {"code", get.("code") || ""},
          {"redirect_uri", f.redirect},
          {"client_id", f.client_id},
          {"code_verifier", f.verifier},
          {"resource", f.resource}
        ]

        t = %Token{
          issuer: f.issuer,
          resource: f.resource,
          client_id: f.client_id,
          scopes: f.scopes
        }

        with {:ok, t} <- token_request(o, f.token_endpoint, form, t) do
          TokenStore.save(o.store, f.resource, t)
          :ok
        end
    end
  end

  defp token_request(o, ep, form, %Token{} = base) do
    method =
      cond do
        base.client_id == o.client_metadata_url or o.client_secret == nil -> "none"
        o.token_auth_method == nil -> "client_secret_basic"
        true -> o.token_auth_method
      end

    form =
      case method do
        "client_secret_post" -> form ++ [{"client_secret", o.client_secret}]
        # RFC 6749 §2.3.1: authenticate with one method only
        "client_secret_basic" -> List.keydelete(form, "client_id", 0)
        _ -> form
      end

    hs =
      [{"content-type", "application/x-www-form-urlencoded"}, {"accept", "application/json"}] ++
        if method == "client_secret_basic",
          do: [
            {"authorization",
             "Basic " <>
               Base.encode64(
                 URI.encode_www_form(base.client_id) <>
                   ":" <> URI.encode_www_form(o.client_secret)
               )}
          ],
          else: []

    case o.http.(:post, to_string(ep), hs, URI.encode_query(form, :www_form)) do
      {:error, e} -> {:error, "token endpoint: #{e}"}
      {:ok, resp} -> parse_token(resp, base)
    end
  end

  defp parse_token(resp, base) do
    tr =
      case Jason.decode(resp.body) do
        {:ok, %{} = m} ->
          m

        _ ->
          # RFC 6749 §5.1 mandates JSON; some ASes (GitHub) still answer form-encoded.
          if String.starts_with?(
               Wire.header(resp, "content-type"),
               "application/x-www-form-urlencoded"
             ),
             do: URI.decode_query(resp.body),
             else: %{}
      end

    at = tr["access_token"]

    if resp.status != 200 or not is_binary(at) or at == "" do
      # never include the body
      {:error, String.trim_trailing("token endpoint: HTTP #{resp.status} #{tr["error"]}")}
    else
      exp =
        case tr["expires_in"] do
          n when is_integer(n) and n > 0 ->
            System.system_time(:second) + n

          s when is_binary(s) ->
            case Integer.parse(s) do
              {n, _} when n > 0 -> System.system_time(:second) + n
              _ -> nil
            end

          _ ->
            nil
        end

      sc =
        if is_binary(tr["scope"]) and tr["scope"] != "",
          do: String.split(tr["scope"]),
          else: base.scopes

      {:ok,
       %{base | access_token: at, refresh_token: tr["refresh_token"], expires_at: exp, scopes: sc}}
    end
  end

  # challenge URL if present, else path-inserted well-known, else root.
  defp fetch_prm(o, resource, hinted) do
    cands =
      if hinted in [nil, ""] do
        u = URI.parse(resource)
        root = "#{u.scheme}://#{authority(u)}" <> @prm_path
        p = String.trim_trailing(u.path || "", "/")
        if(p != "", do: [root <> p], else: []) ++ [root]
      else
        [hinted]
      end

    Enum.reduce_while(cands, {:error, "protected resource metadata: not found"}, fn c, _ ->
      case Wire.get_json(o.http, c) do
        {:ok, %{"resource" => ^resource} = m} ->
          {:halt, {:ok, m}}

        {:ok, %{} = m} ->
          {:halt, {:error, "PRM resource #{inspect(m["resource"])} does not match #{resource}"}}

        {:ok, _} ->
          {:cont, {:error, "protected resource metadata: not an object"}}

        {:error, e} ->
          {:cont, {:error, "protected resource metadata: #{e}"}}
      end
    end)
  end

  defp authority(%URI{host: h, port: p, scheme: s}) do
    if (s == "https" and p == 443) or (s == "http" and p == 80), do: h, else: "#{h}:#{p}"
  end

  @doc false
  # RFC 8414 then OIDC, in the spec's order, with issuer equality.
  def discover_as(http, issuer) do
    u = URI.parse(issuer)
    base = "#{u.scheme}://#{authority(u)}"
    p = String.trim_trailing(u.path || "", "/")

    cands =
      if p != "",
        do: [
          base <> "/.well-known/oauth-authorization-server" <> p,
          base <> "/.well-known/openid-configuration" <> p,
          base <> p <> "/.well-known/openid-configuration"
        ],
        else: [
          base <> "/.well-known/oauth-authorization-server",
          base <> "/.well-known/openid-configuration"
        ]

    Enum.find_value(cands, {:error, "no authorization server metadata for #{issuer}"}, fn c ->
      case Wire.get_json(http, c) do
        {:ok, %{"issuer" => ^issuer} = m} -> {:ok, m}
        {:ok, %{} = m} -> {:error, "AS metadata issuer #{inspect(m["issuer"])} != #{issuer}"}
        _ -> nil
      end
    end)
  end

  @doc false
  # Parse `Bearer k="v", k2=v2` (RFC 6750 §3).
  def parse_challenge(h) do
    h = h |> String.trim() |> String.replace_prefix("Bearer", "") |> String.trim()
    params(h, %{})
  end

  defp params("", acc), do: acc

  defp params(h, acc) do
    case :binary.split(h, "=") do
      [_] ->
        acc

      [k, rest] ->
        k = k |> String.trim() |> String.downcase()
        rest = String.trim(rest)

        {v, rest} =
          if String.starts_with?(rest, "\""),
            do: quoted(binary_part(rest, 1, byte_size(rest) - 1), ""),
            else: unquoted(rest)

        rest = rest |> String.trim() |> String.replace_prefix(",", "") |> String.trim()
        params(rest, Map.put(acc, k, v))
    end
  end

  defp quoted("\\" <> <<c::utf8, rest::binary>>, acc), do: quoted(rest, acc <> <<c::utf8>>)
  defp quoted("\"" <> rest, acc), do: {acc, rest}
  defp quoted(<<c::utf8, rest::binary>>, acc), do: quoted(rest, acc <> <<c::utf8>>)
  defp quoted(_, acc), do: {acc, ""}

  defp unquoted(s) do
    case :binary.split(s, ",") do
      [v, rest] -> {String.trim(v), "," <> rest}
      [v] -> {String.trim(v), ""}
    end
  end
end
