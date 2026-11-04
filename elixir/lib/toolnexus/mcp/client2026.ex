defmodule Toolnexus.Mcp.RpcError do
  @moduledoc "A JSON-RPC error returned by an MCP server."
  defexception [:code, :message, :data]
end

defmodule Toolnexus.Mcp.Client2026 do
  @moduledoc """
  Stateless MCP 2026-07-28 client over Streamable HTTP or stdio (SPEC §2.2–§2.6).
  No MCP SDK, no `initialize`, no session.

  Every call returns `{:ok, result}`, `{:error, reason}` or
  `{:pending, %Toolnexus.Request{}, resume}` where `resume.(answer)` continues
  with the same three shapes. Pending covers MRTR `input_required` (§2.5) and an
  OAuth `pause` (§2A.1). `resolve/2` answers pendings inline through a §10 `waitFor`.

  Retry rule (§2.6): a request is re-sent ONLY after a pre-dispatch rejection —
  HTTP 401, or 403 `insufficient_scope`, carrying `WWW-Authenticate` — at most 3
  times. A lost connection, timeout, 5xx or unreadable response is an error and is
  never retried.
  """
  alias Toolnexus.{Answer, Request}
  alias Toolnexus.Mcp.{OAuth, Protocol, RpcError, Stdio2026, Wire}

  defstruct url: nil,
            headers: [],
            auth: nil,
            stdio: nil,
            http: nil,
            timeout: 30_000,
            ids: nil,
            info: %{"name" => "toolnexus", "version" => "0"}

  @max_rounds 32

  @type outcome :: {:ok, map()} | {:error, term()} | {:pending, Request.t(), (Answer.t() -> outcome)}

  @doc """
  Options: `:url` (HTTP) or `:stdio` (a `Toolnexus.Mcp.Stdio2026` pid), `:headers`
  (`[{name, value}]`, already env-expanded), `:auth` (`Toolnexus.Mcp.OAuth`),
  `:timeout` (ms), `:http` (request function, see `Toolnexus.Mcp.Wire`).
  """
  def new(opts) do
    c = struct!(__MODULE__, opts)
    timeout = c.timeout

    %{
      c
      | ids: :atomics.new(1, []),
        http: c.http || fn m, u, h, b -> Wire.request(m, u, h, b, timeout) end,
        info: %{"name" => "toolnexus", "version" => version()}
    }
  end

  defp version do
    case Application.spec(:toolnexus, :vsn) do
      nil -> "0"
      v -> to_string(v)
    end
  end

  @doc "Answer every pending inline through `wait_for` (nil ⇒ returned as is)."
  def resolve({:pending, req, k}, wait_for) when is_function(wait_for, 1),
    do: resolve(k.(Answer.coerce(wait_for.(req))), wait_for)

  def resolve(outcome, _wait_for), do: outcome

  @doc "Send one request; `_meta` is merged into params."
  @spec request(%__MODULE__{}, String.t(), map() | nil) :: outcome
  def request(%__MODULE__{} = c, method, params \\ nil) do
    params =
      Map.put(params || %{}, "_meta", %{
        Wire.meta_version() => Wire.protocol_version(),
        Wire.meta_client_caps() => %{"elicitation" => %{"form" => %{}}},
        Wire.meta_client_info() => c.info
      })

    id = :atomics.add_get(c.ids, 1, 1)

    body =
      Jason.encode!(%{"jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params})

    res =
      if c.stdio,
        do: Stdio2026.round_trip(c.stdio, id, body, c.timeout),
        else: http_round_trip(c, method, params, body, 0)

    case res do
      {:pending, _, _} = p -> p
      {:ok, msg} -> unwrap(msg, id)
      err -> err
    end
  end

  defp unwrap(%{"id" => id, "error" => %{} = e}, id),
    do: {:error, %RpcError{code: e["code"], message: to_string(e["message"]), data: e["data"]}}

  defp unwrap(%{"id" => id, "result" => %{} = r}, id), do: {:ok, Map.put_new(r, "resultType", "complete")}
  defp unwrap(_, _), do: {:error, :not_rpc}

  defp http_round_trip(c, method, params, body, attempt) do
    sent = if c.auth, do: OAuth.bearer(c.auth, c.url)

    case post(c, method, params, body, sent) do
      {:error, e} ->
        {:error, e}

      {:ok, resp} ->
        www = Wire.header(resp, "www-authenticate")

        cond do
          c.auth == nil or resp.status not in [401, 403] or www == "" ->
            decode(resp)

          attempt >= 3 ->
            {:error, "authorization failed: HTTP #{resp.status} after #{attempt + 1} attempts"}

          true ->
            retry = fn -> request(c, method, Map.delete(params, "_meta")) end

            case OAuth.challenge(c.auth, c.url, resp.status, www, sent, retry) do
              :ok -> http_round_trip(c, method, params, body, attempt + 1)
              {:pending, req} -> {:pending, req, &answer_auth(c, req, &1)}
              err -> err
            end
        end
    end
  end

  # §2A.1a pause answer: ok + data.callbackUrl completes the flow and sends the
  # paused request for the first time; !ok ⇒ "authorization denied: <reason>".
  defp answer_auth(c, req, %Answer{ok: true, data: data}) do
    cb = (data || %{})["callbackUrl"] || (data || %{})[:callbackUrl]
    OAuth.resume(c.auth, req.id, cb || "")
  end

  defp answer_auth(_c, _req, %Answer{reason: r}),
    do: {:error, "authorization denied: #{if r in [nil, ""], do: "cancelled", else: r}"}

  defp decode(%{status: status} = resp) when status >= 500, do: {:error, {:http_status, status, resp_text(resp)}}

  defp decode(resp) do
    msg =
      if String.starts_with?(Wire.header(resp, "content-type"), "text/event-stream") do
        # The last data: payload that carries a result or error (the final response).
        resp.body
        |> String.split(~r/\r?\n/)
        |> Enum.reduce(nil, fn line, acc ->
          with "data:" <> d <- line,
               {:ok, %{} = m} <- Jason.decode(String.trim(d)),
               true <- Map.has_key?(m, "result") or Map.has_key?(m, "error") do
            m
          else
            _ -> acc
          end
        end)
      else
        case Jason.decode(resp.body) do
          {:ok, %{} = m} -> m
          _ -> nil
        end
      end

    cond do
      is_map(msg) and (is_map(msg["result"]) or is_map(msg["error"])) -> {:ok, msg}
      resp.status >= 300 -> {:error, {:http_status, resp.status, resp_text(resp)}}
      true -> {:error, :not_rpc}
    end
  end

  defp resp_text(resp), do: resp.body |> to_string() |> String.slice(0, 200)

  defp post(c, method, params, body, bearer) do
    name =
      case method do
        m when m in ["tools/call", "prompts/get"] -> params["name"]
        "resources/read" -> params["uri"]
        _ -> nil
      end

    hs =
      [
        {"content-type", "application/json"},
        {"accept", "application/json, text/event-stream"},
        {"mcp-protocol-version", Wire.protocol_version()},
        {"mcp-method", method}
      ] ++
        if(name, do: [{"mcp-name", Wire.encode_header(to_string(name))}], else: []) ++
        c.headers ++
        if(bearer, do: [{"authorization", "Bearer " <> bearer}], else: [])

    c.http.(:post, c.url, hs, body)
  end

  @doc """
  `server/discover` (§2.2/§2.2a): `{:modern, result}`, `:fallback`, `{:pending, ...}`
  or `{:error, reason}` (a load failure). `discover_timeout` bounds a stdio probe.
  """
  def discover(%__MODULE__{} = c, wait_for) do
    c = if c.stdio, do: %{c | timeout: min(c.timeout, 5_000)}, else: c

    case resolve(request(c, "server/discover"), wait_for) do
      {:ok, r} ->
        if Wire.protocol_version() in List.wrap(r["supportedVersions"]),
          do: {:modern, r},
          else: :fallback

      {:error, %RpcError{}} ->
        :fallback

      {:error, :not_rpc} ->
        :fallback

      # 5xx: some SDK servers answer an unknown method with 500 (§2.2a, amended)
      {:error, {:http_status, s, _}} when s in [400, 404, 405] or s >= 500 ->
        :fallback

      {:error, :timeout} when c.stdio != nil ->
        :fallback

      {:pending, _req, _k} ->
        {:error, "authorization pending"}

      {:error, e} ->
        {:error, e}
    end
  end

  @doc """
  `tools/list`, following no cursor (§2.9). Returns `{:ok, tools, ttl_ms}`.
  """
  def list_tools(c, wait_for) do
    case resolve(request(c, "tools/list"), wait_for) do
      {:ok, r} ->
        tools = for t <- List.wrap(r["tools"]), is_map(t), is_binary(t["name"]), do: t
        ttl = if is_number(r["ttlMs"]), do: trunc(r["ttlMs"]), else: 0
        {:ok, tools, ttl}

      {:pending, _, _} ->
        {:error, "authorization pending"}

      err ->
        err
    end
  end

  @doc """
  `tools/call` with the MRTR loop (§2.5): each `input_required` request becomes a
  pending §10 Request; its answer is folded into `inputResponses` and the SAME
  call is re-sent with `requestState` echoed. A paused round resumes that round;
  earlier rounds are never re-asked.
  """
  @spec call_tool(%__MODULE__{}, String.t(), map()) :: outcome
  def call_tool(c, name, args), do: round(c, name, args || %{}, nil, nil, 0)

  defp round(_c, name, _args, _responses, _state, n) when n >= @max_rounds,
    do: {:error, "tools/call #{name}: too many input_required rounds"}

  defp round(c, name, args, responses, state, n) do
    p = %{"name" => name, "arguments" => args}
    p = if responses, do: Map.put(p, "inputResponses", responses), else: p
    p = if state != nil, do: Map.put(p, "requestState", state), else: p
    after_call(request(c, "tools/call", p), c, name, args, n)
  end

  defp after_call({:pending, req, k}, c, name, args, n),
    do: {:pending, req, fn a -> after_call(k.(a), c, name, args, n) end}

  defp after_call({:ok, %{"resultType" => "complete"} = r}, _c, _name, _args, _n), do: {:ok, r}

  defp after_call({:ok, %{"resultType" => "input_required"} = r}, c, name, args, n) do
    reqs = if is_map(r["inputRequests"]), do: Enum.sort(r["inputRequests"]), else: []
    ask(reqs, %{}, name, fn responses -> round(c, name, args, responses, r["requestState"], n + 1) end)
  end

  defp after_call({:ok, %{"resultType" => t}}, _c, name, _args, _n),
    do: {:error, "tools/call #{name}: unknown resultType #{inspect(t)}"}

  defp after_call(err, _c, _name, _args, _n), do: err

  defp ask([], acc, _name, next), do: next.(acc)

  defp ask([{key, %{"method" => "elicitation/create", "params" => %{} = p}} | rest], acc, name, next) do
    req = Protocol.elicitation_to_request(p)

    {:pending, req,
     fn answer ->
       ask(rest, Map.put(acc, key, Protocol.answer_to_elicit_result(answer)), name, next)
     end}
  end

  defp ask([{key, _} | _], _acc, name, _next),
    do: {:error, "tools/call #{name}: unsupported input request #{inspect(key)}"}

  @doc "Stop a stdio server; no-op for HTTP."
  def close(%__MODULE__{stdio: nil}), do: :ok
  def close(%__MODULE__{stdio: pid}), do: Stdio2026.close(pid)
end
