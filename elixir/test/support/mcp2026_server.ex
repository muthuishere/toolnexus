# TEST SUPPORT ONLY: an MCP 2026-07-28 fixture server ported from spikes/mcp-2026/elixir.
defmodule Mcp2026T.RpcError do
  @moduledoc false
  defexception [:code, :message, :data]

  def to_map(%__MODULE__{} = e) do
    m = %{"code" => e.code, "message" => e.message}
    if e.data == nil, do: m, else: Map.put(m, "data", e.data)
  end
end

defmodule Mcp2026T.Server do
  @moduledoc """
  Stateless MCP 2026-07-28 server over `POST /mcp`, as a Plug (`plug: {Mcp2026T.Server, server}`).

  Build with `new/0` + `tool/4`, or `fixture/0` (tools echo + greet, skill hello-world).
  Protect it by setting `:auth` to a `Mcp2026T.ServerAuth`.
  """
  @behaviour Plug
  import Plug.Conn
  alias Mcp2026T.{RpcError, ServerAuth, Skills}

  defstruct info: %{}, tools: [], handlers: %{}, skills: [], auth: nil

  @max_body 4 * 1024 * 1024
  @fixture Path.expand("../../../examples/mcp-2026/fixture.json", __DIR__)

  def new, do: %__MODULE__{}

  @doc """
  Register a code-defined tool. `handler.(args, ctx)` gets `ctx.principal` (nil when unprotected)
  and returns a string (text result), a map with "content" (a full CallToolResult),
  `{:error, message}` (isError result) or any JSON value (structured result).
  """
  def tool(%__MODULE__{} = s, name, description, handler) when is_function(handler, 2) do
    tools =
      [
        %{"name" => name, "description" => description, "inputSchema" => %{"type" => "object"}}
        | Enum.reject(s.tools, &(&1["name"] == name))
      ]

    %{s | handlers: Map.put(s.handlers, name, handler), tools: Enum.sort_by(tools, & &1["name"])}
  end

  @doc "The shared spike fixture (../fixture.json, or $FIXTURE)."
  def fixture do
    path = System.get_env("FIXTURE") || @fixture
    {:ok, s} = load(path)
    s
  end

  def load(path) do
    with {:ok, raw} <- File.read(path), {:ok, fx} <- Jason.decode(raw) do
      tools = fx["tools"] || []

      if Enum.all?(tools, &is_binary(&1["name"])) do
        root = Path.join(Path.dirname(path), fx["skillsDir"] || ".")
        skills = Skills.load_dir(root)

        {:ok,
         %__MODULE__{
           info: fx["server"] || %{},
           tools: Enum.sort_by(tools, & &1["name"]),
           skills: skills
         }}
      else
        {:error, "fixture tool without a name"}
      end
    end
  end

  @doc "Serve on 127.0.0.1:port (0 = random). Returns %{url: \"http://127.0.0.1:N/mcp\", port: N, pid: pid}."
  def serve(%__MODULE__{} = s, port \\ 0), do: start_plug({__MODULE__, s}, port, "/mcp")

  @doc false
  def start_plug(plug, port, path \\ "") do
    {:ok, pid} =
      Bandit.start_link(
        plug: plug,
        ip: {127, 0, 0, 1},
        port: port,
        startup_log: false,
        thousand_island_options: [shutdown_timeout: 100]
      )

    {:ok, {_, p}} = ThousandIsland.listener_info(pid)
    %{url: "http://127.0.0.1:#{p}" <> path, base: "http://127.0.0.1:#{p}", port: p, pid: pid}
  end

  def stop(%{pid: pid}), do: Supervisor.stop(pid)

  defp server_info(s), do: %{Toolnexus.Mcp.Wire.meta_server_info() => s.info}

  # ---- Plug ----

  @impl true
  def init(s), do: s

  @impl true
  def call(conn, s) do
    cond do
      s.auth != nil and conn.method == "GET" and ServerAuth.metadata_path?(conn) ->
        ServerAuth.serve_metadata(s.auth, conn)

      conn.request_path != "/mcp" ->
        send_resp(conn, 404, "404 page not found")

      conn.method != "POST" ->
        send_resp(conn, 405, "")

      true ->
        handle_post(conn, s)
    end
  end

  defp handle_post(conn, s) do
    case read_body(conn, length: @max_body, read_length: @max_body) do
      {:ok, body, conn} ->
        case Jason.decode(body) do
          {:ok, %{} = req}
          when not is_map_key(req, "params") or is_map(:erlang.map_get("params", req)) or
                 :erlang.map_get("params", req) == nil ->
            handle_req(conn, s, req)

          _ ->
            write_rpc(conn, 400, nil, nil, %RpcError{code: -32700, message: "Parse error"})
        end

      _ ->
        write_rpc(conn, 413, nil, nil, %RpcError{
          code: -32600,
          message: "Request body too large or unreadable"
        })
    end
  end

  defp handle_req(conn, _s, %{"id" => nil}), do: send_resp(conn, 202, "")

  defp handle_req(conn, s, req) when not is_map_key(req, "id"),
    do: handle_req(conn, s, Map.put(req, "id", nil))

  defp handle_req(conn, s, req) do
    method = if is_binary(req["method"]), do: req["method"], else: ""
    params = req["params"] || %{}

    case validate(conn, method, params) do
      {status, %RpcError{} = e} ->
        write_rpc(conn, status, req["id"], nil, e)

      :ok ->
        auth =
          if s.auth, do: ServerAuth.check(s.auth, conn, method, params), else: {:ok, nil}

        case auth do
          {:halt, conn} ->
            conn

          {:ok, principal} ->
            case dispatch(s, %{principal: principal}, method, params) do
              {:ok, res} -> write_rpc(conn, 200, req["id"], res, nil)
              {:error, %RpcError{code: -32601} = e} -> write_rpc(conn, 404, req["id"], nil, e)
              {:error, e} -> write_rpc(conn, 200, req["id"], nil, e)
            end
        end
    end
  end

  defp hdr(conn, name), do: conn |> get_req_header(name) |> List.first() |> Kernel.||("")

  defp validate(conn, method, p) do
    mismatch = fn msg -> {400, %RpcError{code: -32020, message: "Header mismatch: " <> msg}} end
    hv = hdr(conn, "mcp-protocol-version")
    hm = hdr(conn, "mcp-method")
    mv = get_in(p, ["_meta", Toolnexus.Mcp.Wire.meta_version()])
    mv = if is_binary(mv), do: mv, else: ""

    named = method in ["tools/call", "resources/read", "prompts/get"]
    want = if method == "resources/read", do: p["uri"], else: p["name"]
    want = if is_binary(want), do: want, else: ""
    {got, ok} = decode_header(hdr(conn, "mcp-name"))

    cond do
      hv == "" ->
        mismatch.("MCP-Protocol-Version header missing")

      hm != method ->
        mismatch.("Mcp-Method header value '#{hm}' does not match body value '#{method}'")

      named and (not ok or got != want) ->
        mismatch.("Mcp-Name header value '#{got}' does not match body value '#{want}'")

      mv != hv ->
        mismatch.("MCP-Protocol-Version header value '#{hv}' does not match _meta value '#{mv}'")

      mv != Toolnexus.Mcp.Wire.protocol_version() ->
        {400, unsupported(mv)}

      true ->
        :ok
    end
  end

  @doc false
  def unsupported(mv),
    do: %RpcError{
      code: -32022,
      message: "Unsupported protocol version",
      data: %{"supported" => [Toolnexus.Mcp.Wire.protocol_version()], "requested" => mv}
    }

  defp decode_header("=?base64?" <> rest = v) when byte_size(v) >= 11 do
    if String.ends_with?(rest, "?=") do
      case Base.decode64(binary_part(rest, 0, byte_size(rest) - 2)) do
        {:ok, b} -> {b, true}
        :error -> {"", false}
      end
    else
      {v, true}
    end
  end

  defp decode_header(v), do: {v, v != ""}

  @doc false
  def dispatch(s, ctx, method, p) do
    pv = Toolnexus.Mcp.Wire.protocol_version()

    case method do
      "server/discover" ->
        {:ok,
         %{
           "resultType" => "complete",
           "supportedVersions" => [pv],
           "capabilities" => %{
             "tools" => %{},
             "resources" => %{},
             "extensions" => %{Toolnexus.Mcp.Wire.skills_ext() => %{}}
           },
           "_meta" => server_info(s),
           "ttlMs" => 3_600_000,
           "cacheScope" => "public"
         }}

      "tools/list" ->
        {:ok,
         %{
           "resultType" => "complete",
           "tools" => s.tools,
           "ttlMs" => 60_000,
           "cacheScope" => "public",
           "_meta" => server_info(s)
         }}

      "tools/call" ->
        call_tool(s, ctx, p)

      "skills/list" ->
        {:ok,
         %{
           "resultType" => "complete",
           "skills" => Enum.map(s.skills, &Skills.entry/1),
           "ttlMs" => 60_000,
           "cacheScope" => "public",
           "_meta" => server_info(s)
         }}

      "skills/get" ->
        uri = str(p["uri"])

        with {:ok, _, _} <- uri_ok(uri, "Invalid skill URI: ") do
          case Enum.find(s.skills, &(&1.uri == uri)) do
            nil ->
              {:error, invalid("Unknown skill: " <> uri)}

            sk ->
              {:ok,
               %{
                 "resultType" => "complete",
                 "skill" => Skills.entry(sk),
                 "_meta" => server_info(s)
               }}
          end
        end

      "resources/read" ->
        uri = str(p["uri"])

        with {:ok, _, _} <- uri_ok(uri, "Invalid resource URI: ") do
          case Enum.find_value(s.skills, fn sk -> Enum.find(sk.files, &(elem(&1, 0) == uri)) end) do
            nil ->
              {:error, invalid("Resource not found: " <> uri)}

            {_, data} ->
              mime = if String.ends_with?(uri, ".md"), do: "text/markdown", else: "text/plain"

              {:ok,
               %{
                 "resultType" => "complete",
                 "contents" => [%{"uri" => uri, "mimeType" => mime, "text" => data}],
                 "ttlMs" => 60_000,
                 "cacheScope" => "public",
                 "_meta" => server_info(s)
               }}
          end
        end

      _ ->
        {:error, %RpcError{code: -32601, message: "Method not found: " <> method}}
    end
  end

  defp uri_ok(uri, prefix) do
    case Skills.parse_uri(uri) do
      {:ok, n, r} -> {:ok, n, r}
      {:error, e} -> {:error, invalid(prefix <> e)}
    end
  end

  defp invalid(msg), do: %RpcError{code: -32602, message: msg}
  defp str(v) when is_binary(v), do: v
  defp str(_), do: ""

  defp call_tool(s, ctx, p) do
    name = str(p["name"])
    args = if is_map(p["arguments"]), do: p["arguments"], else: %{}

    case s.handlers[name] do
      nil -> fixture_tool(s, name, args, p)
      h -> {:ok, tool_result(h.(args, ctx))}
    end
  end

  defp tool_result({:error, e}),
    do: %{
      "resultType" => "complete",
      "content" => [%{"type" => "text", "text" => to_string(e)}],
      "isError" => true
    }

  defp tool_result(t) when is_binary(t),
    do: %{
      "resultType" => "complete",
      "content" => [%{"type" => "text", "text" => t}],
      "isError" => false
    }

  defp tool_result(%{"content" => _} = r), do: Map.put_new(r, "resultType", "complete")

  defp tool_result(v),
    do: %{
      "resultType" => "complete",
      "content" => [%{"type" => "text", "text" => Jason.encode!(v)}],
      "structuredContent" => v,
      "isError" => false
    }

  defp fixture_tool(s, name, args, p) do
    text = fn t, structured ->
      {:ok,
       %{
         "resultType" => "complete",
         "content" => [%{"type" => "text", "text" => t}],
         "isError" => false,
         "_meta" => server_info(s),
         "structuredContent" => structured
       }}
    end

    case name do
      "echo" ->
        t = str(args["text"])
        text.(t, %{"text" => t})

      "greet" ->
        case p["inputResponses"] do
          %{"name" => %{"action" => "accept", "content" => %{"name" => n}}}
          when is_binary(n) and n != "" ->
            text.("Hello, #{n}!", %{"greeting" => "Hello, #{n}!"})

          _ ->
            # Missing/declined answer: ask (again), per MRTR error-handling guidance.
            {:ok,
             %{
               "resultType" => "input_required",
               "_meta" => server_info(s),
               "inputRequests" => %{
                 "name" => %{
                   "method" => "elicitation/create",
                   "params" => %{
                     "mode" => "form",
                     "message" => "What is your name?",
                     "requestedSchema" => %{
                       "type" => "object",
                       "properties" => %{"name" => %{"type" => "string"}},
                       "required" => ["name"]
                     }
                   }
                 }
               }
             }}
        end

      _ ->
        {:error, invalid("Unknown tool: " <> name)}
    end
  end

  defp write_rpc(conn, status, id, result, e) do
    msg =
      if e,
        do: %{"jsonrpc" => "2.0", "id" => id, "error" => RpcError.to_map(e)},
        else: %{"jsonrpc" => "2.0", "id" => id, "result" => result}

    conn
    |> put_resp_content_type("application/json", nil)
    |> send_resp(status, Jason.encode!(msg))
  end
end

defmodule Mcp2026T.Principal do
  @moduledoc "The authenticated caller, visible to tool handlers as `ctx.principal`."
  defstruct subject: "", scopes: [], claims: %{}
end

defmodule Mcp2026T.ServerAuth do
  @moduledoc """
  Protects a `Mcp2026T.Server` as an OAuth 2.1 protected resource (MCP 2026-07-28 basic/authorization).

      %{server | auth: ServerAuth.new(issuer: iss, scopes: %{"*" => ["mcp:read"], "greet" => ["mcp:greet"]})}

  `scopes["*"]` is required on every request (also advertised as scopes_supported); `scopes[tool]` is
  additionally required for that tools/call. `:audience` defaults to scheme://host/mcp of the request.
  `:verify` (`fn token -> {:ok, %Principal{}} | {:error, reason} end`) replaces the default RS256
  JWT/JWKS verifier (opaque tokens, introspection, API keys).
  """
  import Plug.Conn
  alias Toolnexus.Mcp.Wire, as: HTTP
  alias Mcp2026T.Principal

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
    body = %{
      "resource" => audience(a, conn),
      "authorization_servers" => [a.issuer],
      "scopes_supported" => a.scopes["*"],
      "bearer_methods_supported" => ["header"]
    }

    conn |> put_resp_content_type("application/json", nil) |> send_resp(200, Jason.encode!(body))
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
         true <-
           (is_number(claims["exp"]) and claims["exp"] > System.system_time(:second)) ||
             {:error, "expired"} do
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
    with {:ok, b} <- Base.url_decode64(s, padding: false), do: Jason.decode(b)
  end

  defp key(a, kid) do
    :global.trans(
      {{__MODULE__, a.cache}, self()},
      fn ->
        st = Agent.get(a.cache, & &1)
        now = System.monotonic_time(:millisecond)

        cond do
          st.keys != nil and Map.has_key?(st.keys, kid) ->
            {:ok, st.keys[kid]}

          st.keys != nil and now - st.fetched < 30_000 ->
            # garbage kids must not hammer the AS
            {:error, "unknown kid"}

          true ->
            with {:ok, md} <- Toolnexus.Mcp.OAuth.discover_as(a.http, a.issuer),
                 {:ok, %{"keys" => ks}} <- HTTP.get_json(a.http, to_string(md["jwks_uri"])) do
              keys =
                for %{"kty" => "RSA", "kid" => k, "n" => n, "e" => e} <- ks,
                    {:ok, nb} <- [Base.url_decode64(n, padding: false)],
                    {:ok, eb} <- [Base.url_decode64(e, padding: false)],
                    into: %{},
                    do:
                      {k,
                       {:RSAPublicKey, :binary.decode_unsigned(nb), :binary.decode_unsigned(eb)}}

              Agent.update(a.cache, fn _ -> %{keys: keys, fetched: now} end)
              if Map.has_key?(keys, kid), do: {:ok, keys[kid]}, else: {:error, "unknown kid"}
            else
              _ -> {:error, "jwks unavailable"}
            end
        end
      end,
      [node()],
      :infinity
    )
  end
end

defmodule Mcp2026T.Skills do
  @moduledoc """
  Skills extension (SEP-2640) helpers: loading skill folders for the server, `skill://` URI safety
  (python-sdk #3685) and `download/3` for the client.

  A skill URI is percent-decoded ONCE, then rejected if it still contains `%` (double encoding), a
  backslash, NUL, `:` or an empty / `.` / `..` segment. Server (before lookup) and client (before
  writing files) both use `parse_uri/1`.
  """

  @doc false
  def load_dir(root) do
    Path.wildcard(Path.join(root, "**/SKILL.md"))
    |> Enum.flat_map(fn p ->
      case load_skill(Path.dirname(p)) do
        {:ok, sk} -> [sk]
        _ -> []
      end
    end)
    |> Enum.sort_by(& &1.uri)
  end

  defp load_skill(dir) do
    fm = parse_frontmatter(File.read!(Path.join(dir, "SKILL.md")))
    name = fm["name"]

    if is_binary(name) and name != "" and name == Path.basename(dir) do
      files =
        Path.wildcard(Path.join(dir, "**/*"), match_dot: true)
        |> Enum.filter(&File.regular?/1)
        |> Enum.map(&{"skill://#{name}/" <> Path.relative_to(&1, dir), File.read!(&1)})
        |> Enum.sort()

      {:ok, %{uri: "skill://#{name}/SKILL.md", frontmatter: fm, files: files}}
    else
      {:error, "skill #{dir}: name must match dir"}
    end
  end

  @doc "Flat `key: value` YAML subset (the fixture skills use nothing more)."
  def parse_frontmatter(md) do
    md = String.replace(md, "\r\n", "\n")

    with "---\n" <> rest <- md, [head, _] <- :binary.split(rest, "\n---") do
      for line <- String.split(head, "\n"),
          not String.starts_with?(line, " "),
          [k, v] <- [:binary.split(line, ":")],
          into: %{} do
        {String.trim(k), v |> String.trim() |> String.trim("\"") |> String.trim("'")}
      end
    else
      _ -> %{}
    end
  end

  @doc false
  def entry(sk) do
    res =
      for {uri, data} <- sk.files,
          do: %{"uri" => uri, "digest" => digest(data), "size" => byte_size(data)}

    %{"uri" => sk.uri, "frontmatter" => sk.frontmatter, "resources" => res}
  end

  def digest(data), do: "sha256:" <> Base.encode16(:crypto.hash(:sha256, data), case: :lower)

  @doc "Split skill://<name>/<path> into `{:ok, name, rel}` or `{:error, reason}`."
  def parse_uri("skill://" <> rest) do
    case decode_once(rest) do
      :error ->
        {:error, "bad percent-encoding"}

      {:ok, dec} ->
        segs = String.split(dec, "/")

        cond do
          String.contains?(dec, ["%", "\\", <<0>>]) ->
            {:error, "skill URI has encoded, backslash or NUL characters"}

          length(segs) < 2 ->
            {:error, "skill URI needs skill://<name>/<path>"}

          Enum.any?(segs, &(&1 in ["", ".", ".."] or String.contains?(&1, ":"))) ->
            {:error, "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"}

          true ->
            [name | rel] = segs
            {:ok, name, Enum.join(rel, "/")}
        end
    end
  end

  def parse_uri(_), do: {:error, "not a skill:// URI"}

  # Strict single percent-decode (URI.decode raises on bad escapes).
  defp decode_once(s) do
    {:ok, URI.decode(s)}
  rescue
    ArgumentError -> :error
  end
end
