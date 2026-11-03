defmodule Mcp2026.Token do
  @moduledoc "An access token bound to the issuer that minted it and the resource it is for. Never logged."
  defstruct [:access_token, :refresh_token, :expiry, :issuer, :resource, :client_id, scopes: []]

  defimpl Inspect do
    def inspect(t, _), do: "#Token<issuer=#{t.issuer}, redacted>"
  end

  defimpl String.Chars do
    def to_string(t), do: "Token{issuer=#{t.issuer}, redacted}"
  end
end

defmodule Mcp2026.TokenStore do
  @moduledoc "Persists tokens per resource URL. Implementations must keep them confidential."
  @callback load(store :: struct, resource :: String.t()) :: {:ok, Mcp2026.Token.t()} | :error
  @callback save(store :: struct, resource :: String.t(), Mcp2026.Token.t()) :: :ok
  @callback delete(store :: struct, resource :: String.t()) :: :ok

  def load(%m{} = s, r), do: m.load(s, r)
  def save(%m{} = s, r, t), do: m.save(s, r, t)
  def delete(%m{} = s, r), do: m.delete(s, r)
end

defmodule Mcp2026.MemoryTokenStore do
  @moduledoc "In-memory `Mcp2026.TokenStore` (an Agent)."
  @behaviour Mcp2026.TokenStore
  defstruct [:pid]

  def new do
    {:ok, pid} = Agent.start_link(fn -> %{} end)
    %__MODULE__{pid: pid}
  end

  @impl true
  def load(%{pid: p}, r), do: Agent.get(p, &Map.fetch(&1, r))
  @impl true
  def save(%{pid: p}, r, t), do: Agent.update(p, &Map.put(&1, r, t))
  @impl true
  def delete(%{pid: p}, r), do: Agent.update(p, &Map.delete(&1, r))
end

defmodule Mcp2026.PauseAuthorizer do
  @moduledoc "Never blocks: every authorization becomes a `Mcp2026.Pending` (returned as `{:error, %PendingError{}}`)."
  defstruct [:redirect]

  def begin(%__MODULE__{redirect: r}),
    do: {:ok, %{redirect_uri: r, wait: fn _pending -> :pause end, close: fn -> :ok end}}
end

defmodule Mcp2026.LoopbackAuthorizer do
  @moduledoc """
  Opens a browser and listens on http://127.0.0.1:<random>/callback (RFC 8252 §7.3).
  `:open` is `fn url -> :ok | {:error, reason} end`; nil = the system browser.
  """
  defstruct [:open]

  defmodule Callback do
    @moduledoc false
    @behaviour Plug
    def init(o), do: o

    def call(conn, {parent, ref}) do
      if conn.request_path == "/callback" do
        url = "http://#{Plug.Conn.get_req_header(conn, "host") |> List.first()}#{conn.request_path}?#{conn.query_string}"
        send(parent, {ref, :callback, url})
        Plug.Conn.send_resp(conn, 200, "Authorization received. You can close this window.")
      else
        Plug.Conn.send_resp(conn, 404, "not found")
      end
    end
  end

  def begin(%__MODULE__{open: open}) do
    ref = make_ref()
    srv = Mcp2026.Server.start_plug({Callback, {self(), ref}}, 0)
    open = open || (&open_browser/1)

    wait = fn pending ->
      parent = self()
      # The opener may block (headless approvers follow redirects synchronously).
      spawn(fn -> send(parent, {ref, :opened, safe_open(open, pending.url)}) end)
      await(ref, true)
    end

    {:ok, %{redirect_uri: srv.base <> "/callback", wait: wait, close: fn -> Mcp2026.Server.stop(srv) end}}
  end

  defp await(ref, opener_pending) do
    receive do
      {^ref, :callback, url} ->
        {:ok, url}

      {^ref, :opened, :ok} when opener_pending ->
        await(ref, false)

      {^ref, :opened, {:error, e}} when opener_pending ->
        # a failed opener must not leave us waiting 5 minutes for a callback that cannot come
        {:error, "open authorization URL: #{inspect(e)}"}
    after
      300_000 -> {:error, "authorization timed out"}
    end
  end

  defp safe_open(open, url) do
    case open.(url) do
      {:error, _} = e -> e
      _ -> :ok
    end
  rescue
    e -> {:error, Exception.message(e)}
  end

  def open_browser(url) do
    {cmd, args} =
      case :os.type() do
        {:unix, :darwin} -> {"open", [url]}
        {:win32, _} -> {"rundll32", ["url.dll,FileProtocolHandler", url]}
        _ -> {"xdg-open", [url]}
      end

    case System.cmd(cmd, args, stderr_to_stdout: true) do
      {_, 0} -> :ok
      {out, _} -> {:error, out}
    end
  end
end

defmodule Mcp2026.OAuth do
  @moduledoc """
  Client-side OAuth for MCP 2026-07-28 (basic/authorization). Attach as `Client.new(url, auth: oauth)`.

  Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
  -> client id (pre-registered | CIMD; never DCR) -> code + PKCE S256 + resource (RFC 8707)
  -> iss (RFC 9207) + state check -> token -> retry with Bearer.

  Options: `:authorizer` (`%LoopbackAuthorizer{}` default, or `%PauseAuthorizer{redirect: url}`),
  `:client_id`, `:client_secret`, `:token_auth_method` ("none" | "client_secret_basic" |
  "client_secret_post"), `:client_ids` (issuer => id), `:client_metadata_url` (CIMD),
  `:store` (TokenStore; default in-memory), `:omit_refresh_resource`, `:http`, `:allow_http`.
  """
  alias Mcp2026.{HTTP, LoopbackAuthorizer, Pending, PendingError, Token, TokenStore}

  defstruct authorizer: nil,
            client_id: nil,
            client_secret: nil,
            token_auth_method: nil,
            client_ids: %{},
            client_metadata_url: nil,
            store: nil,
            omit_refresh_resource: false,
            http: nil,
            allow_http: false,
            pid: nil

  @prm_path "/.well-known/oauth-protected-resource"

  def new(opts \\ []) do
    {:ok, pid} = Agent.start_link(fn -> %{issuer: %{}, flows: %{}} end)
    o = struct!(%__MODULE__{pid: pid}, opts)
    %{o | store: o.store || Mcp2026.MemoryTokenStore.new(), http: o.http || HTTP.default()}
  end

  # One login / one refresh at a time per OAuth (refresh tokens rotate: a 2nd refresh would fail).
  defp locked(o, fun), do: :global.trans({{__MODULE__, o.pid}, self()}, fun, [node()], :infinity)

  defp issuer_for(o, resource), do: Agent.get(o.pid, &Map.get(&1.issuer, resource))
  def store(o), do: o.store

  @doc false
  # The token to send for resource ("" -> nil = none), refreshing if expired.
  def bearer(o, resource) do
    iss = issuer_for(o, resource)

    case TokenStore.load(o.store, resource) do
      {:ok, t} when iss != nil and t.issuer == iss ->
        if expired?(t) do
          locked(o, fn ->
            with {:ok, t} <- TokenStore.load(o.store, resource),
                 :ok <- if(expired?(t), do: refresh(o, t), else: :ok),
                 {:ok, t} <- TokenStore.load(o.store, resource) do
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

  defp expired?(%Token{expiry: nil}), do: false
  defp expired?(%Token{expiry: e}), do: System.system_time(:second) > e - 10

  defp refresh(_o, %Token{refresh_token: rt}) when rt in [nil, ""], do: {:error, "no refresh token"}

  defp refresh(o, t) do
    with {:ok, md} <- discover_as(o.http, t.issuer) do
      form = %{"grant_type" => "refresh_token", "refresh_token" => t.refresh_token, "client_id" => t.client_id}
      form = if o.omit_refresh_resource, do: form, else: Map.put(form, "resource", t.resource)

      case token_request(o, md["token_endpoint"], form, t.issuer, t.resource, t.client_id, t.scopes) do
        {:ok, nt} ->
          nt = if nt.refresh_token in [nil, ""], do: %{nt | refresh_token: t.refresh_token}, else: nt
          TokenStore.save(o.store, t.resource, nt)
          :ok

        err ->
          TokenStore.delete(o.store, t.resource)
          err
      end
    end
  end

  @doc false
  # Handle a 401/403: :ok = retry now; {:error, %PendingError{}} = paused. sent = token the request carried.
  def challenge(o, resource, status, www, sent, retry) do
    params = parse_challenge(www)
    locked(o, fn -> do_challenge(o, resource, status, params, sent, retry) end)
  end

  defp do_challenge(o, resource, status, params, sent, retry) do
    cur = case TokenStore.load(o.store, resource) do
      {:ok, t} -> t
      _ -> nil
    end

    known = issuer_for(o, resource)

    cond do
      cur != nil and known != nil and cur.issuer == known and cur.access_token != sent and not expired?(cur) ->
        :ok  # someone else refreshed/authorized while we waited

      status == 401 and cur != nil and cur.refresh_token not in [nil, ""] and params["error"] == "invalid_token" and
          refresh(o, cur) == :ok ->
        :ok

      true ->
        cur = if status == 401 and params["error"] == "invalid_token" and cur != nil and cur.refresh_token not in [nil, ""], do: nil, else: cur
        authorize(o, resource, status, params, cur, retry)
    end
  end

  defp authorize(_o, _resource, 403, %{"error" => e}, _cur, _retry) when e != "insufficient_scope",
    do: {:error, "forbidden: #{e}"}

  defp authorize(_o, _resource, 403, params, _cur, _retry) when not is_map_key(params, "error"),
    do: {:error, "forbidden: "}

  defp authorize(o, resource, status, params, cur, retry) do
    with {:ok, prm} <- fetch_prm(o, resource, params["resource_metadata"]),
         {:ok, issuer} <- first_issuer(o, prm),
         {:ok, md} <- discover_as(o.http, issuer),
         :ok <- if("S256" in List.wrap(md["code_challenge_methods_supported"]), do: :ok,
                  else: {:error, "authorization server does not advertise PKCE S256; refusing"}) do
      Agent.update(o.pid, &put_in(&1, [:issuer, resource], issuer))

      if status == 401 and cur != nil and cur.issuer == issuer and params["error"] in [nil, ""] do
        :ok  # first request after restart: we now know the issuer, so the stored token may be sent
      else
        start_flow(o, resource, status, params, cur, retry, prm, issuer, md)
      end
    end
  end

  defp first_issuer(o, prm) do
    case prm["authorization_servers"] do
      [iss | _] when is_binary(iss) ->
        # RFC 9728 §7.6: client's choice; we take the first
        if o.allow_http or String.starts_with?(iss, "https://"),
          do: {:ok, iss},
          else: {:error, "authorization server must use https"}

      _ ->
        {:error, "PRM lists no authorization_servers"}
    end
  end

  defp start_flow(o, resource, status, params, cur, retry, prm, issuer, md) do
    # Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
    scopes = String.split(params["scope"] || "")
    scopes = if scopes == [], do: for(s <- List.wrap(prm["scopes_supported"]), is_binary(s), do: s), else: scopes
    scopes = if cur != nil and status == 403, do: Enum.uniq(scopes ++ cur.scopes), else: scopes
    cimd = md["client_id_metadata_document_supported"] == true

    client_id =
      Map.get(o.client_ids, issuer) || o.client_id ||
        if(o.client_metadata_url && cimd, do: o.client_metadata_url)

    if client_id in [nil, ""] do
      # no DCR, ever: the user registers an app once and configures its id
      why = if cimd, do: "client_metadata_url is not set", else: "it does not support Client ID Metadata Documents"

      {:error,
       "no client identity for #{issuer}: #{why} and no client id is configured — register an app with #{issuer} and set OAuth client_id (dynamic client registration is not supported)"}
    else
      az = o.authorizer || %LoopbackAuthorizer{}

      with {:ok, sess} <- az.__struct__.begin(az) do
        verifier = Mcp2026.rand_str(32)
        expires = System.system_time(:second) + 600

        f = %{resource: resource, issuer: issuer, client_id: client_id, redirect: sess.redirect_uri,
              verifier: verifier, state: Mcp2026.rand_str(16), token_endpoint: md["token_endpoint"],
              scopes: scopes, retry: retry, expires: expires,
              iss_supported: md["authorization_response_iss_parameter_supported"] == true}

        q = [{"response_type", "code"}, {"client_id", client_id}, {"redirect_uri", f.redirect},
             {"state", f.state}, {"code_challenge", Base.url_encode64(:crypto.hash(:sha256, verifier), padding: false)},
             {"code_challenge_method", "S256"}, {"resource", resource}] ++
            if(scopes != [], do: [{"scope", Enum.join(scopes, " ")}], else: [])

        p = %Pending{id: "auth-" <> Mcp2026.rand_str(12), kind: "authorization",
                     url: md["authorization_endpoint"] <> "?" <> URI.encode_query(q),
                     prompt: "Authorize access to " <> resource,
                     expires_at: DateTime.from_unix!(expires) |> DateTime.to_iso8601()}

        case sess.wait.(p) do
          :pause ->
            now = System.system_time(:second)
            Agent.update(o.pid, fn st ->
              # drop abandoned pendings so they cannot pile up
              flows = st.flows |> Enum.reject(fn {_, x} -> now > x.expires end) |> Map.new()
              %{st | flows: Map.put(flows, p.id, f)}
            end)
            {:error, %PendingError{pending: p}}

          {:ok, cb} ->
            sess.close.()
            complete(o, f, cb)

          err ->
            sess.close.()
            err
        end
      end
    end
  end

  @doc false
  def set_retry(nil, _id, _f), do: :ok

  def set_retry(o, id, f) do
    Agent.update(o.pid, fn st ->
      if Map.has_key?(st.flows, id), do: put_in(st, [:flows, id, :retry], f), else: st
    end)
  end

  @doc "Finish a paused authorization and re-run the original operation; returns its result."
  def resume(o, id, callback_url) do
    f = Agent.get_and_update(o.pid, fn st -> {st.flows[id], %{st | flows: Map.delete(st.flows, id)}} end)

    cond do
      f == nil -> {:error, "unknown pending authorization #{inspect(id)}"}
      System.system_time(:second) > f.expires -> {:error, "pending authorization expired"}
      true -> with :ok <- complete(o, f, callback_url), do: f.retry.()
    end
  end

  @doc "`resume/3` for web apps: the pending is found by the callback's `state`."
  def resume_callback(o, callback_url) do
    st = URI.decode_query(URI.parse(callback_url).query || "")["state"]

    id =
      Agent.get(o.pid, fn s ->
        Enum.find_value(s.flows, fn {k, f} -> if st not in [nil, ""] and f.state == st, do: k end)
      end)

    resume(o, id, callback_url)
  end

  defp complete(o, f, callback_url) do
    q = URI.parse(callback_url).query || ""
    pairs = URI.query_decoder(q) |> Enum.to_list()
    get = fn k -> Enum.find_value(pairs, fn {kk, v} -> if kk == k, do: v end) end

    # RFC 9207 §2.4 (spec: Authorization Response Validation) — before anything else, error responses included.
    cond do
      get.("iss") != nil and get.("iss") != f.issuer ->
        {:error, "authorization response iss mismatch (possible mix-up attack)"}

      get.("iss") == nil and f.iss_supported ->
        {:error, "authorization response missing iss"}

      get.("state") != f.state ->
        {:error, "authorization response state mismatch"}

      get.("error") not in [nil, ""] ->
        {:error, "authorization denied: #{get.("error")}"}

      true ->
        form = %{"grant_type" => "authorization_code", "code" => get.("code") || "", "redirect_uri" => f.redirect,
                 "client_id" => f.client_id, "code_verifier" => f.verifier, "resource" => f.resource}

        with {:ok, t} <- token_request(o, f.token_endpoint, form, f.issuer, f.resource, f.client_id, f.scopes) do
          TokenStore.save(o.store, f.resource, t)
          :ok
        end
    end
  end

  defp token_request(o, ep, form, issuer, resource, client_id, scopes) do
    method =
      cond do
        client_id == o.client_metadata_url or o.client_secret in [nil, ""] -> "none"
        o.token_auth_method in [nil, ""] -> "client_secret_basic"
        true -> o.token_auth_method
      end

    form = if method == "client_secret_post", do: Map.put(form, "client_secret", o.client_secret), else: form
    # RFC 6749 §2.3.1: authenticate with one method only
    form = if method == "client_secret_basic", do: Map.delete(form, "client_id"), else: form

    hs = [{"content-type", "application/x-www-form-urlencoded"}, {"accept", "application/json"}] ++
      if method == "client_secret_basic",
        do: [{"authorization", "Basic " <> Base.encode64(URI.encode_www_form(client_id) <> ":" <> URI.encode_www_form(o.client_secret))}],
        else: []

    case o.http.(:post, to_string(ep), hs, URI.encode_query(form, :www_form)) do
      {:error, e} ->
        {:error, e}

      {:ok, resp} ->
        tr =
          case JSON.decode(resp.body) do
            {:ok, %{} = m} -> m
            _ ->
              # RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
              if String.starts_with?(HTTP.header(resp, "content-type"), "application/x-www-form-urlencoded"),
                do: URI.decode_query(resp.body),
                else: nil
          end

        at = tr && tr["access_token"]

        if resp.status != 200 or not is_binary(at) or at == "" do
          {:error, "token endpoint: HTTP #{resp.status} #{tr && tr["error"]}"}  # never include the body
        else
          exp = case tr["expires_in"] do
            n when is_integer(n) and n > 0 -> System.system_time(:second) + n
            s when is_binary(s) -> case Integer.parse(s) do
                {n, _} when n > 0 -> System.system_time(:second) + n
                _ -> nil
              end
            _ -> nil
          end

          sc = if is_binary(tr["scope"]) and tr["scope"] != "", do: String.split(tr["scope"]), else: scopes

          {:ok, %Token{access_token: at, refresh_token: tr["refresh_token"], issuer: issuer, resource: resource,
                       client_id: client_id, scopes: sc, expiry: exp}}
        end
    end
  end

  # header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements).
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

    Enum.reduce_while(cands, {:error, "no candidates"}, fn c, _ ->
      case HTTP.get_json(o.http, c) do
        {:ok, %{} = m} ->
          # RFC 9728 §3.3
          if m["resource"] == resource,
            do: {:halt, {:ok, m}},
            else: {:halt, {:error, "PRM resource #{inspect(m["resource"])} does not match #{resource}"}}

        {:ok, _} -> {:cont, {:error, "protected resource metadata: not an object"}}
        {:error, e} -> {:cont, {:error, "protected resource metadata: #{e}"}}
      end
    end)
  end

  defp authority(%URI{host: h, port: p, scheme: s}) do
    if (s == "https" and p == 443) or (s == "http" and p == 80), do: h, else: "#{h}:#{p}"
  end

  @doc false
  # RFC 8414 then OIDC endpoints in the spec's order, with issuer equality.
  def discover_as(http, issuer) do
    u = URI.parse(issuer)
    base = "#{u.scheme}://#{authority(u)}"
    p = String.trim_trailing(u.path || "", "/")

    cands =
      if p != "",
        do: [base <> "/.well-known/oauth-authorization-server" <> p, base <> "/.well-known/openid-configuration" <> p,
             base <> p <> "/.well-known/openid-configuration"],
        else: [base <> "/.well-known/oauth-authorization-server", base <> "/.well-known/openid-configuration"]

    Enum.find_value(cands, {:error, "no authorization server metadata for #{issuer}"}, fn c ->
      case HTTP.get_json(http, c) do
        {:ok, %{"issuer" => ^issuer} = m} -> {:ok, m}
        {:ok, %{} = m} -> {:error, "AS metadata issuer #{inspect(m["issuer"])} != #{issuer}"}
        _ -> nil
      end
    end)
  end

  @doc false
  # Parse `Bearer k="v", k2=v2` (RFC 6750 §3 / RFC 9110 auth-param).
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
        {v, rest} = if String.starts_with?(rest, "\""), do: quoted(binary_part(rest, 1, byte_size(rest) - 1), ""), else: unquoted(rest)
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

  # ---- web apps ----

  defmodule WebHandler do
    @moduledoc """
    A Plug serving one tool call to a browser user, pausing for authorization when needed:
    `call` runs; if it pauses, the browser is 302'd to the AS; when the AS redirects back to this
    same handler (use its URL as the PauseAuthorizer redirect), the original call is resumed and its
    text written. Errors become 502. Opts: `oauth: o, call: fn conn -> {:ok, result} | {:error, e} end`.
    """
    @behaviour Plug
    import Plug.Conn

    def init(o), do: o

    def call(conn, opts) do
      q = URI.decode_query(conn.query_string)

      res =
        if q["state"] not in [nil, ""] and (Map.has_key?(q, "code") or Map.has_key?(q, "error")) do
          host = get_req_header(conn, "host") |> List.first()
          url = "#{conn.scheme}://#{host}#{conn.request_path}?#{conn.query_string}"
          Mcp2026.OAuth.resume_callback(opts[:oauth], url)
        else
          opts[:call].(conn)
        end

      case res do
        {:error, %Mcp2026.PendingError{pending: p}} ->
          conn |> put_resp_header("location", p.url) |> send_resp(302, "")

        {:error, e} ->
          send_resp(conn, 502, if(is_binary(e), do: e, else: inspect(e)))

        {:ok, r} ->
          conn |> put_resp_content_type("text/plain") |> send_resp(200, Mcp2026.text(r))
      end
    end
  end
end
