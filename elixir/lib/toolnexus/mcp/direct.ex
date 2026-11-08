defmodule Toolnexus.Mcp.Direct do
  @moduledoc false
  # The MCP 2026-07-28 path of the MCP source (SPEC §2.2–§2.8): discover first,
  # then expose the server's tools with the same naming / result mapping / filters
  # as the frozen path, or `search_tools` + `call_tool` in tool-search mode.
  # Everything stateful (stdio child, OAuth state, token store, pause store, tool
  # cache) lives under the source's supervisor so `Mcp.close/1` tears it down.

  alias Toolnexus.{Answer, Tool, ToolResult}
  alias Toolnexus.Mcp.{Client2026, OAuth, RemoteSkills, Stdio2026, ToolSearch, Wire}
  alias Toolnexus.Mcp.Transport.StreamableHttp

  @default_timeout 30_000

  @doc """
  Probe one server. Returns `{:modern, client, discover_result}`,
  `{:fallback, cfg, adopt}` (cfg possibly carrying an OAuth bearer header;
  `adopt` = the live stdio probe child to continue on, or nil) or
  `{:failed, reason}`.
  """
  def probe(sup, cfg, opts) do
    timeout = cfg["timeout"] || @default_timeout
    wait_for = opts[:wait_for]

    case client(sup, cfg, timeout, opts) do
      {:ok, c} ->
        case Client2026.probe(c, wait_for) do
          {:modern, r} ->
            {:modern, c, r}

          # §2.2a (amended): a stdio server that ANSWERED discover is reused —
          # the same child continues into the frozen `initialize`.
          {:fallback, :reply} when c.stdio != nil ->
            {:fallback, cfg, c.stdio}

          {:fallback, _} ->
            Client2026.close(c)
            {:fallback, fallback_cfg(cfg, c), nil}

          {:error, e} ->
            Client2026.close(c)
            {:failed, text(e)}
        end

      {:error, e} ->
        {:failed, text(e)}
    end
  end

  defp client(sup, cfg, timeout, opts) do
    if remote?(cfg) do
      headers = cfg["headers"] |> StreamableHttp.expand_env_headers() |> Enum.to_list()
      start = &start_agent(sup, &1)

      auth =
        OAuth.new(cfg["auth"],
          token_store: opts[:token_store],
          open_url: opts[:open_url],
          http: opts[:http],
          start: start
        )

      {:ok,
       Client2026.new(
         url: cfg["url"],
         headers: headers,
         auth: auth,
         timeout: timeout,
         http: opts[:http]
       )}
    else
      case DynamicSupervisor.start_child(sup, {Stdio2026, cfg}) do
        {:ok, pid} -> {:ok, Client2026.new(stdio: pid, timeout: timeout)}
        {:error, e} -> {:error, e}
      end
    end
  end

  # §2A.1a: the frozen path carries the bearer token as an Authorization header
  # (fetched once at fallback start; it has no 401 hook of its own).
  defp fallback_cfg(cfg, %Client2026{auth: %OAuth{} = o, url: url}) do
    case OAuth.bearer(o, url) do
      nil ->
        cfg

      tok ->
        Map.put(cfg, "headers", Map.put(cfg["headers"] || %{}, "Authorization", "Bearer " <> tok))
    end
  end

  defp fallback_cfg(cfg, _c), do: cfg

  @doc "Load a discovered server: `{:connected, tools, client, skills}` or `{:failed, reason}`."
  def load(sup, name, cfg, c, discover, opts, filter) do
    wait_for = opts[:wait_for]

    with {:ok, defs, ttl} <- Client2026.list_tools(c, wait_for),
         {:ok, skills} <- skills(c, discover, wait_for) do
      defs = filter.(defs)
      {:ok, pauses} = start_agent(sup, fn -> %{} end)
      run = &run(&1, &2, wait_for, pauses, name)

      tools =
        if cfg["toolSearch"] == true do
          {:ok, cache} = start_agent(sup, fn -> {defs, expiry(ttl)} end)
          search_tools(name, cfg, c, cache, ttl, filter, run, wait_for)
        else
          Enum.map(defs, &direct_tool(name, &1, c, run))
        end

      {:connected, tools, c, skills}
    else
      {:error, e} ->
        Client2026.close(c)
        {:failed, text(e)}
    end
  end

  @doc "Inventory: the server's unfiltered tool defs."
  def list_defs(c, wait_for) do
    case Client2026.list_tools(c, wait_for) do
      {:ok, defs, _} -> {:ok, defs}
      {:error, e} -> {:error, text(e)}
    end
  end

  defp skills(c, discover, wait_for) do
    if is_map(get_in(discover, ["capabilities", "extensions", Wire.skills_ext()])),
      do: RemoteSkills.fetch(c, wait_for),
      else: {:ok, []}
  end

  defp start_agent(sup, init) do
    DynamicSupervisor.start_child(sup, %{
      id: Agent,
      start: {Agent, :start_link, [init]},
      restart: :temporary
    })
  end

  defp expiry(0), do: :never
  defp expiry(ttl), do: System.monotonic_time(:millisecond) + ttl

  # --- tools ---------------------------------------------------------------------

  defp direct_tool(server, def, c, run) do
    original = def["name"]

    %Tool{
      name: Tool.sanitize(server) <> "_" <> Tool.sanitize(original),
      description: def["description"] || "",
      input_schema: Toolnexus.Mcp.merged_schema(def, true),
      source: "mcp",
      hints: Toolnexus.Mcp.mcp_hints(def["annotations"]),
      execute: fn args, ctx ->
        run.(ctx, fn -> Client2026.call_tool(c, original, args || %{}) end)
      end
    }
  end

  defp search_tools(server, cfg, c, cache, ttl, filter, run, wait_for) do
    about =
      case cfg["description"] do
        d when is_binary(d) and d != "" -> d
        _ -> server
      end

    prefix = Tool.sanitize(server) <> "_"

    tools = fn ->
      case Agent.get(cache, & &1) do
        {defs, :never} ->
          {:ok, defs}

        {defs, exp} ->
          if System.monotonic_time(:millisecond) < exp do
            {:ok, defs}
          else
            with {:ok, fresh, _} <- Client2026.list_tools(c, wait_for) do
              fresh = filter.(fresh)
              Agent.update(cache, fn _ -> {fresh, expiry(ttl)} end)
              {:ok, fresh}
            end
          end
      end
    end

    search = %Tool{
      name: prefix <> "search_tools",
      description: ToolSearch.search_description(about),
      input_schema: ToolSearch.search_schema(),
      source: "mcp",
      execute: fn args, _ctx ->
        args = args || %{}

        limit =
          if is_number(args["limit"]) and args["limit"] > 0, do: trunc(args["limit"]), else: 5

        case tools.() do
          {:ok, defs} ->
            hits =
              ToolSearch.rank(
                defs,
                to_string(args["query"] || ""),
                limit,
                cfg["description"] || ""
              )

            %ToolResult{output: Jason.encode!(hits), metadata: %{server: server}}

          {:error, e} ->
            %ToolResult{output: text(e), is_error: true, metadata: %{server: server}}
        end
      end
    }

    call = %Tool{
      name: prefix <> "call_tool",
      description: ToolSearch.call_description(),
      input_schema: ToolSearch.call_schema(),
      source: "mcp",
      execute: fn args, ctx ->
        args = args || %{}
        name = to_string(args["name"] || "")
        arguments = if is_map(args["arguments"]), do: args["arguments"], else: %{}

        known =
          case tools.() do
            {:ok, defs} -> Enum.any?(defs, &(&1["name"] == name))
            _ -> false
          end

        if known,
          do: run.(ctx, fn -> Client2026.call_tool(c, name, arguments) end),
          else: %ToolResult{
            output: ~s(unknown tool "#{name}" on #{server}),
            is_error: true,
            metadata: %{server: server}
          }
      end
    }

    [search, call]
  end

  # Run a tool call: resume a paused call when ctx.answer names it, else start
  # fresh; answer pendings inline through wait_for, or suspend (§10) without one.
  defp run(ctx, start, wait_for, pauses, server) do
    answer = ctx && Map.get(ctx, :answer)
    id = answer && Answer.get(answer, :id)
    k = id && Agent.get_and_update(pauses, &Map.pop(&1, id))

    outcome = if is_function(k, 1), do: k.(Answer.coerce(answer)), else: start.()

    case Client2026.resolve(outcome, wait_for) do
      {:ok, result} ->
        Toolnexus.Mcp.map_call_result(result, server)

      {:pending, req, k} ->
        Agent.update(pauses, &Map.put(&1, req.id, k))

        %ToolResult{
          output: req.prompt <> if(req.url, do: ": " <> req.url, else: ""),
          is_error: true,
          metadata: %{pending: req, server: server}
        }

      {:error, e} ->
        %ToolResult{output: text(e), is_error: true, metadata: %{server: server}}
    end
  end

  @doc false
  def text(%Toolnexus.Mcp.RpcError{message: m}), do: m
  def text({:http_status, s, body}), do: String.trim("HTTP #{s} #{body}")
  def text(:not_rpc), do: "response is not a JSON-RPC response for the request"
  def text(:timeout), do: "MCP request timed out"
  def text(:closed), do: "stdio server exited"
  def text(e) when is_binary(e), do: e
  def text(e), do: inspect(e)

  defp remote?(cfg) do
    case cfg["type"] do
      "remote" -> true
      "local" -> false
      _ -> is_binary(cfg["url"]) and cfg["url"] != ""
    end
  end
end
