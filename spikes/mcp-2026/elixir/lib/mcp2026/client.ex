defmodule Mcp2026.Client do
  @moduledoc """
  Stateless MCP 2026-07-28 client over Streamable HTTP (`new/2`) or stdio (`Mcp2026.Stdio.start/2`).

  Retry rule (spec issue #3394): a request is re-sent ONLY after a 401, or a 403 insufficient_scope,
  carrying WWW-Authenticate: the server rejected it before dispatch, so the tool never ran. A lost
  connection, timeout, 5xx or unreadable response is returned as an error and NEVER retried.
  """
  alias Mcp2026.{HTTP, OAuth, PendingError, RpcError}

  defstruct url: "",
            version: "2026-07-28",
            info: %{"name" => "toolnexus-spike-elixir", "version" => "0.0.1"},
            caps: %{"elicitation" => %{"form" => %{}}},
            http: nil,
            auth: nil,
            tool_search: false,
            description: "",
            stdio: nil,
            state: nil,
            header_override: %{}

  @doc """
  Options: `:auth` (`Mcp2026.OAuth`), `:http` (request fun, see `Mcp2026.HTTP`), `:tool_search`,
  `:description`, `:version`. No handshake happens here.
  """
  def new(url, opts \\ []) do
    {:ok, state} = Agent.start_link(fn -> %{id: 0, tools: nil, expiry: 0} end)
    struct!(%__MODULE__{url: url, http: HTTP.default(), state: state}, opts)
  end

  @doc "Send one request; `_meta` is merged into params. Returns `{:ok, result}` or `{:error, reason}`."
  def call(%__MODULE__{} = c, method, params \\ nil) do
    params =
      Map.put(params || %{}, "_meta", %{
        Mcp2026.meta_version() => c.version,
        Mcp2026.meta_client_caps() => c.caps,
        Mcp2026.meta_client_info() => c.info
      })

    id = Agent.get_and_update(c.state, fn s -> {s.id + 1, %{s | id: s.id + 1}} end)
    body = JSON.encode!(%{"jsonrpc" => "2.0", "id" => id, "method" => method, "params" => params})

    res =
      if c.stdio,
        do: Mcp2026.Stdio.round_trip(c.stdio, id, body),
        else: http_round_trip(c, method, params, body, 0)

    case res do
      {:ok, %{"error" => %{} = e}} ->
        {:error, %RpcError{code: e["code"], message: e["message"], data: e["data"]}}

      {:ok, %{"result" => %{} = r}} ->
        {:ok, Map.put_new(r, "resultType", "complete")}

      {:ok, _} ->
        {:error, "response has neither result nor error"}

      err ->
        err
    end
  end

  defp http_round_trip(c, method, params, body, attempt) do
    sent = if c.auth, do: OAuth.bearer(c.auth, c.url)

    case post(c, method, params, body, sent) do
      {:error, e} ->
        {:error, e}

      {:ok, resp} ->
        www = HTTP.header(resp, "www-authenticate")

        cond do
          c.auth == nil or resp.status not in [401, 403] or www == "" ->
            decode(resp)

          attempt >= 3 ->
            {:error, "authorization failed: HTTP #{resp.status} after #{attempt + 1} attempts"}

          true ->
            retry = fn -> call(c, method, params) end

            case OAuth.challenge(c.auth, c.url, resp.status, www, sent, retry) do
              :ok -> http_round_trip(c, method, params, body, attempt + 1)
              err -> err
            end
        end
    end
  end

  defp decode(resp) do
    msg =
      if String.starts_with?(HTTP.header(resp, "content-type"), "text/event-stream") do
        # Take the last data: payload that carries a result or error (the final response).
        resp.body
        |> String.split(~r/\r?\n/)
        |> Enum.reduce(nil, fn line, acc ->
          with "data:" <> d <- line, {:ok, %{} = m} <- JSON.decode(d),
               true <- Map.has_key?(m, "result") or Map.has_key?(m, "error") do
            m
          else
            _ -> acc
          end
        end)
      else
        case JSON.decode(resp.body) do
          {:ok, %{} = m} -> m
          _ -> :bad
        end
      end

    case msg do
      :bad -> {:error, "HTTP #{resp.status}: unreadable response body"}
      %{"result" => r} when is_map(r) -> {:ok, msg}
      %{"error" => e} when is_map(e) -> {:ok, msg}
      _ -> {:error, "HTTP #{resp.status}: response has neither result nor error"}
    end
  end

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
        {"mcp-protocol-version", c.version},
        {"mcp-method", method}
      ] ++
        if(name, do: [{"mcp-name", encode_header(to_string(name))}], else: []) ++
        if(bearer, do: [{"authorization", "Bearer " <> bearer}], else: [])

    hs = Enum.reduce(c.header_override, hs, fn {k, v}, acc -> List.keystore(acc, String.downcase(k), 0, {String.downcase(k), v}) end)
    c.http.(:post, c.url, hs, body)
  end

  @doc false
  def encode_header(v) do
    safe =
      v == String.trim(v) and
        not (String.starts_with?(v, "=?base64?") and String.ends_with?(v, "?=")) and
        Enum.all?(:binary.bin_to_list(v), &(&1 >= 0x20 and &1 <= 0x7E))

    if safe, do: v, else: "=?base64?" <> Base.encode64(v) <> "?="
  end

  @doc """
  tools/call, looping over `input_required` rounds. `respond.(key, request)` answers one input
  request (stand-in for toolnexus §10 waitFor) and returns the answer map or `{:error, reason}`.
  """
  def call_tool(c, name, args \\ %{}, respond \\ nil), do: rounds(c, name, args || %{}, respond, nil, nil, 0)

  defp rounds(_c, name, _args, _respond, _resp, _state, 8),
    do: {:error, "tools/call #{name}: too many input_required rounds"}

  defp rounds(c, name, args, respond, responses, state, round) do
    p = %{"name" => name, "arguments" => args}
    p = if responses, do: Map.put(p, "inputResponses", responses), else: p
    p = if state != nil, do: Map.put(p, "requestState", state), else: p

    case call(c, "tools/call", p) do
      {:error, %PendingError{pending: pd}} = err ->
        # The paused request got 401/403 before dispatch: this round has NOT run. Resume sends exactly
        # this round (same inputResponses/requestState) for the first time; earlier rounds are not replayed.
        OAuth.set_retry(c.auth, pd.id, fn -> rounds(c, name, args, respond, responses, state, round) end)
        err

      {:ok, %{"resultType" => "input_required"} = res} ->
        reqs = if is_map(res["inputRequests"]), do: res["inputRequests"], else: %{}

        if respond == nil do
          {:error, "tools/call #{name}: server asked for input but no responder was given"}
        else
          answers =
            Enum.reduce_while(Enum.sort(reqs), {:ok, %{}}, fn
              {k, r}, {:ok, acc} when is_map(r) ->
                case respond.(k, r) do
                  {:error, _} = e -> {:halt, e}
                  ans -> {:cont, {:ok, Map.put(acc, k, ans)}}
                end

              {k, _}, _ ->
                {:halt, {:error, "tools/call #{name}: malformed input request #{inspect(k)}"}}
            end)

          with {:ok, a} <- answers, do: rounds(c, name, args, respond, a, res["requestState"], round + 1)
        end

      other ->
        other
    end
  end

  # ---- tools: eager list or lazy tool search ----

  @doc "Tool definitions for the model: every server tool, or (tool_search) search_tools + call_tool."
  def tools(%__MODULE__{tool_search: false} = c), do: list_tools(c)

  def tools(c) do
    about = if c.description != "", do: c.description, else: "this MCP server"

    {:ok,
     [
       %{"name" => "search_tools",
         "description" => "Search the tools of " <> about <> " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
         "inputSchema" => %{"type" => "object", "properties" => %{"query" => %{"type" => "string"}, "limit" => %{"type" => "integer"}}, "required" => ["query"]}},
       %{"name" => "call_tool", "description" => "Run a tool found with search_tools.",
         "inputSchema" => %{"type" => "object", "properties" => %{"name" => %{"type" => "string"}, "arguments" => %{"type" => "object"}}, "required" => ["name"]}}
     ]}
  end

  @doc "Run a tool the model asked for: a server tool, or search_tools / call_tool in tool_search mode."
  def invoke(c, name, args, respond \\ nil)
  def invoke(%__MODULE__{tool_search: false} = c, name, args, respond), do: call_tool(c, name, args, respond)

  def invoke(c, "search_tools", args, _respond) do
    limit = if is_number(args["limit"]) and args["limit"] > 0, do: trunc(args["limit"]), else: 5

    with {:ok, hits} <- search_tools(c, to_string(args["query"] || ""), limit) do
      {:ok, %{"resultType" => "complete", "content" => [%{"type" => "text", "text" => JSON.encode!(hits)}],
        "structuredContent" => %{"tools" => hits}, "isError" => false}}
    end
  end

  def invoke(c, "call_tool", args, respond),
    do: call_tool(c, to_string(args["name"] || ""), if(is_map(args["arguments"]), do: args["arguments"], else: %{}), respond)

  def invoke(_c, name, _args, _respond),
    do: {:error, "unknown tool #{inspect(name)} (tool_search mode exposes search_tools and call_tool)"}

  @doc "Rank the server's tools against query (BM25; name tokens count twice)."
  def search_tools(c, query, limit) do
    with {:ok, tools} <- list_tools(c), do: {:ok, Mcp2026.ToolSearch.rank(tools, query, limit, c.description)}
  end

  # tools/list cached for the server's ttlMs.
  defp list_tools(c) do
    now = System.monotonic_time(:millisecond)

    case Agent.get(c.state, & &1) do
      %{tools: t, expiry: e} when t != nil and now < e ->
        {:ok, t}

      _ ->
        with {:ok, r} <- call(c, "tools/list") do
          tools = for t <- List.wrap(r["tools"]), is_map(t), do: t
          ttl = if is_number(r["ttlMs"]), do: trunc(r["ttlMs"]), else: 0
          Agent.update(c.state, &%{&1 | tools: tools, expiry: now + ttl})
          {:ok, tools}
        end
    end
  end

  @doc "Download a whole skill to dir/<name>/ (see `Mcp2026.Skills.download/3`)."
  def download_skill(c, uri, dir), do: Mcp2026.Skills.download(c, uri, dir)

  @doc "Stop a stdio server (close stdin, kill after 2 s); no-op for HTTP clients."
  def close(%__MODULE__{stdio: nil}), do: :ok
  def close(%__MODULE__{stdio: s}), do: Mcp2026.Stdio.close(s)
end

defmodule Mcp2026.ToolSearch do
  @moduledoc false
  # Lazy tool search ranking: BM25 over name (x2, camelCase/_ split) + description.

  def rank(tools, query, limit, server) do
    docs =
      Enum.map(tools, fn t ->
        nt = tokens(to_string(t["name"] || ""))
        nt ++ nt ++ tokens(if is_binary(t["description"]), do: t["description"], else: "")
      end)

    n = length(tools)
    df = docs |> Enum.flat_map(&Enum.uniq/1) |> Enum.frequencies()
    avg = Enum.sum(Enum.map(docs, &length/1)) / max(1, n)
    q = tokens(query)

    tools
    |> Enum.zip(docs)
    |> Enum.map(fn {t, d} ->
      tf = Enum.frequencies(d)

      s =
        Enum.reduce(q, 0.0, fn w, acc ->
          f = Map.get(tf, w, 0)

          if f == 0 do
            acc
          else
            idf = :math.log(1 + (n - df[w] + 0.5) / (df[w] + 0.5))
            acc + idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * length(d) / avg))
          end
        end)

      {t, s}
    end)
    |> Enum.filter(fn {_, s} -> s > 0 end)
    |> Enum.sort_by(fn {_, s} -> s end, :desc)
    |> Enum.take(limit)
    |> Enum.map(fn {t, _} ->
      %{"name" => t["name"], "description" => t["description"], "inputSchema" => t["inputSchema"], "server" => server}
    end)
  end

  @doc false
  def tokens(s) do
    {out, cur, _} =
      s
      |> String.to_charlist()
      |> Enum.reduce({[], [], false}, fn ch, {out, cur, prev_lower} ->
        str = <<ch::utf8>>
        upper = str != String.downcase(str)
        lower = str != String.upcase(str)
        alnum = String.match?(str, ~r/^[\p{L}\p{N}]$/u)

        cond do
          upper and prev_lower -> {flush(out, cur), [ch], lower}
          alnum -> {out, [ch | cur], lower}
          true -> {flush(out, cur), [], lower}
        end
      end)

    Enum.reverse(flush(out, cur))
  end

  defp flush(out, []), do: out
  defp flush(out, cur), do: [cur |> Enum.reverse() |> List.to_string() |> String.downcase() | out]
end
