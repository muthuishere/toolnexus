defmodule Toolnexus.Mcp do
  @moduledoc """
  Dynamic MCP source (SPEC §2, §0.2–0.4). Mirrors js/src/mcp.ts: parse an
  `mcp.json` (path | raw JSON string | map; top-level `mcpServers`|`servers`|`mcp`
  accepted), connect to every enabled server (local stdio + remote
  streamable-HTTP) with per-server failure isolation, list tools (cursor
  pagination), and expose each as a uniform `Toolnexus.Tool` named
  `sanitize(server)_sanitize(tool)`.

  Host-facing extensions (ADR-0001, shipped v0.9.0): per-server `tools`
  allowlist map (Gap 7), list-only inventory `list_tools/2` (Gap 6), and a
  load `deadline` that kills in-flight connects promptly (Gap 3).
  """

  require Logger

  alias Toolnexus.{ContentPart, Tool, ToolResult}
  alias Toolnexus.Mcp.{Connection, Direct}
  alias Toolnexus.Mcp.Transport.StreamableHttp

  @default_timeout 30_000
  @reserved_keys ~w(builtins agents a2a mcpServer)

  defmodule Source do
    @moduledoc "A loaded MCP source: tools, per-server status, and its own supervisor."
    defstruct tools: [], status: %{}, sup: nil, connections: %{}, skills: []

    @type t :: %__MODULE__{
            tools: [Toolnexus.Tool.t()],
            status: %{String.t() => String.t()},
            sup: pid() | nil,
            connections: %{String.t() => pid() | Toolnexus.Mcp.Client2026.t()},
            skills: [map()]
          }
  end

  defmodule Inventory do
    @moduledoc "§2 Gap 6: list-only inventory — original unprefixed tool defs per server."
    defstruct tools: %{}, status: %{}

    @type t :: %__MODULE__{tools: %{String.t() => [map()]}, status: %{String.t() => String.t()}}
  end

  # --- config parsing (SPEC §0.3) -----------------------------------------------

  @doc """
  Parse an MCP config: a path to `mcp.json`, a raw JSON string, or a map.
  Accepts a `mcpServers`/`servers`/`mcp` wrapper; a bare server map has the
  reserved sibling keys (#{inspect(@reserved_keys)}) stripped.
  """
  def parse_config(input) when is_map(input) do
    # Normalize atom keys/values to the JSON string shape.
    input |> Jason.encode!() |> Jason.decode!() |> unwrap()
  end

  def parse_config(input) when is_binary(input) do
    raw =
      if String.starts_with?(String.trim_leading(input), "{"), do: input, else: File.read!(input)

    raw |> Jason.decode!() |> unwrap()
  end

  defp unwrap(raw) do
    servers =
      case raw["mcpServers"] || raw["servers"] || raw["mcp"] do
        nil -> Map.drop(raw, @reserved_keys)
        wrapped -> wrapped
      end

    Map.new(servers, fn {name, cfg} -> {name, normalize_command(name, cfg)} end)
  end

  # SPEC §2 "command + args (Claude shape)": a local server's `command` is a
  # non-empty string or non-empty list of strings; an optional `args` list of
  # strings is appended. The result is one argv list under "command" (no
  # shell). A remote server ignores `args`; a server without `command` is left
  # as-is (it fails at connect, isolated, as before).
  defp normalize_command(name, %{"command" => command} = cfg) do
    if remote_cfg?(cfg) do
      cfg
    else
      head =
        case command do
          c when is_binary(c) and c != "" ->
            [c]

          [first | _] = list when first != "" ->
            if Enum.all?(list, &is_binary/1), do: list, else: nil

          _ ->
            nil
        end

      args =
        case Map.get(cfg, "args", []) do
          nil -> []
          list when is_list(list) -> if Enum.all?(list, &is_binary/1), do: list, else: :invalid
          _ -> :invalid
        end

      cond do
        head == nil -> raise ArgumentError, ~s(mcp server "#{name}": invalid command)
        args == :invalid -> raise ArgumentError, ~s(mcp server "#{name}": invalid args)
        true -> cfg |> Map.delete("args") |> Map.put("command", head ++ args)
      end
    end
  end

  defp normalize_command(_name, cfg), do: cfg

  defp remote_cfg?(cfg) do
    case cfg["type"] do
      "remote" -> true
      "local" -> false
      _ -> is_binary(cfg["url"]) and cfg["url"] != ""
    end
  end

  @doc "Expand `${ENV_VAR}` in header values from the environment (never logged)."
  defdelegate expand_env_headers(headers), to: StreamableHttp

  # --- load (SPEC §2) -------------------------------------------------------------

  @doc """
  Connect to every enabled server, list + convert tools. Failures are isolated
  (status `"failed"`); `disabled`/`enabled:false` servers are skipped (status
  `"disabled"`). Options:

    * `:wait_for` — `(Request.t() -> Answer.t())`; advertises the elicitation
      capability and bridges server `elicitation/create` onto it (§10). On the
      2026 path it also answers MRTR `input_required` requests and OAuth `pause`
      authorizations inline; without it those suspend the tool call (§10 pending).
    * `:token_store` — OAuth token store (`Toolnexus.Mcp.TokenStore`), default
      in-memory per load (§2A.1a).
    * `:open_url` — `(url -> :ok | {:error, reason})`, how OAuth `auto` mode opens
      the browser (default: the OS opener).
    * `:deadline` (ms, alias `:timeout`) — overall load deadline; on exceed the
      whole load is aborted (in-flight connects torn down promptly) and a
      `RuntimeError` is raised (§2 Gap 3).
    * `:on_server` — `(%{name, status, tools, error} -> any)`, called once per
      server the moment it is connected, failed or found disabled, so a host can
      use a fast server's tools while a slow one still connects (SPEC §4C). Calls
      are serialized (they run in the loading process). `tools` is `[]` unless
      connected; `error` is the failure text, else `nil`. The load still returns
      only after every server finished, and a deadline still closes every client —
      including ones whose tools were already handed out.
  """
  @spec load(String.t() | map(), keyword()) :: Source.t()
  def load(input, opts \\ []) do
    config = parse_config(input)
    on_server = Keyword.get(opts, :on_server)
    deadline = Keyword.get(opts, :deadline) || Keyword.get(opts, :timeout) || :infinity
    deadline_at = if deadline == :infinity, do: :infinity, else: now_ms() + deadline

    {:ok, sup} = DynamicSupervisor.start_link(strategy: :one_for_one)

    load_opts = Keyword.take(opts, [:wait_for, :token_store, :open_url, :http, :disable_tools])

    entries =
      Enum.map(config, fn {name, cfg} ->
        {name, Task.async(fn -> safe_load_server(sup, name, cfg, load_opts) end)}
      end)

    outcomes =
      case collect(
             Map.new(entries, fn {name, t} -> {t.ref, {name, t}} end),
             %{},
             deadline_at,
             on_server
           ) do
        {:ok, outcomes} ->
          outcomes

        :deadline ->
          Enum.each(entries, fn {_name, t} -> Task.shutdown(t, :brutal_kill) end)
          stop_sup(sup)
          raise "toolnexus: MCP load deadline exceeded"
      end

    acc = %Source{sup: sup}

    entries
    |> Enum.map(fn {name, _t} -> {name, Map.fetch!(outcomes, name)} end)
    |> Enum.reduce(acc, fn {name, outcome}, acc ->
      case outcome do
        {:connected, tools, pid, skills} ->
          %{
            acc
            | tools: acc.tools ++ tools,
              status: Map.put(acc.status, name, "connected"),
              connections: Map.put(acc.connections, name, pid),
              skills: acc.skills ++ skills
          }

        {:failed, _reason} ->
          %{acc | status: Map.put(acc.status, name, "failed")}

        :disabled ->
          %{acc | status: Map.put(acc.status, name, "disabled")}
      end
    end)
  end

  # Receive each server's outcome as it lands, reporting it at once (serialized:
  # this is the loading process). `:deadline` when the overall deadline passes first.
  defp collect(pending, acc, _deadline_at, _on_server) when map_size(pending) == 0, do: {:ok, acc}

  defp collect(pending, acc, deadline_at, on_server) do
    wait = if deadline_at == :infinity, do: :infinity, else: max(deadline_at - now_ms(), 0)

    receive do
      {ref, outcome} when is_map_key(pending, ref) ->
        Process.demonitor(ref, [:flush])
        {name, _t} = pending[ref]
        report(on_server, name, outcome)
        collect(Map.delete(pending, ref), Map.put(acc, name, outcome), deadline_at, on_server)
    after
      wait -> :deadline
    end
  end

  defp report(f, name, outcome) when is_function(f, 1) do
    f.(
      case outcome do
        {:connected, tools, _pid, _skills} ->
          %{name: name, status: "connected", tools: tools, error: nil}

        {:failed, reason} ->
          %{name: name, status: "failed", tools: [], error: reason}

        :disabled ->
          %{name: name, status: "disabled", tools: [], error: nil}
      end
    )
  end

  defp report(_f, _name, _outcome), do: :ok

  defp now_ms, do: System.monotonic_time(:millisecond)

  @doc """
  The MCP resource tools (SPEC §4C): `list_mcp_resources` and `read_mcp_resource`
  bound to this source's connected servers. Both read-only, opt-in (register them
  yourself), and they stop working when the source is closed. A source with no
  connected server still returns the tools; they report that no server is connected.
  """
  @spec resource_tools(Source.t() | nil) :: [Tool.t()]
  def resource_tools(source) do
    conns = (source && source.connections) || %{}
    names = conns |> Map.keys() |> Enum.sort()
    ro = %Toolnexus.ToolHints{read_only: true}

    conn = fn server ->
      case conns do
        %{^server => pid} ->
          {:ok, pid}

        _ ->
          {:error,
           ~s(no connected MCP server "#{server}" (connected: #{Enum.join(names, ", ")}\))}
      end
    end

    list = %Tool{
      name: "list_mcp_resources",
      description:
        "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server.",
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "server" => %{"type" => "string", "description" => "Only this server (default: all)"}
        },
        "additionalProperties" => false
      },
      source: "mcp",
      hints: ro,
      execute: fn args, _ctx ->
        server = (args || %{})["server"]

        selected =
          if is_binary(server) and server != "" do
            with {:ok, _} <- conn.(server), do: {:ok, [server]}
          else
            {:ok, names}
          end

        case selected do
          {:error, msg} -> %ToolResult{output: "list_mcp_resources: " <> msg, is_error: true}
          {:ok, servers} -> list_resources(servers, conns)
        end
      end
    }

    read = %Tool{
      name: "read_mcp_resource",
      description:
        "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined.",
      input_schema: %{
        "type" => "object",
        "properties" => %{
          "server" => %{"type" => "string", "description" => "The MCP server name"},
          "uri" => %{"type" => "string", "description" => "The resource uri"}
        },
        "required" => ["server", "uri"],
        "additionalProperties" => false
      },
      source: "mcp",
      hints: ro,
      execute: fn args, _ctx ->
        args = args || %{}
        server = to_string(args["server"] || "")
        uri = to_string(args["uri"] || "")

        with {:ok, pid} <- conn.(server),
             {:uri, true} <- {:uri, uri != ""},
             {:ok, res} <- resource_request(pid, "resources/read", %{"uri" => uri}) do
          text =
            (res["contents"] || [])
            |> Enum.flat_map(fn
              %{"text" => t} when is_binary(t) ->
                [t]

              %{"blob" => b} = c when is_binary(b) ->
                ["[binary #{c["mimeType"]}, #{byte_size(b)} base64 chars]"]

              _ ->
                []
            end)
            |> Enum.join("\n")

          %ToolResult{output: text, metadata: %{server: server, uri: uri}}
        else
          {:error, msg} when is_binary(msg) ->
            %ToolResult{output: "read_mcp_resource: " <> msg, is_error: true}

          {:uri, false} ->
            %ToolResult{output: "read_mcp_resource: uri is required", is_error: true}

          {:error, reason} ->
            %ToolResult{output: "read_mcp_resource: " <> error_text(reason), is_error: true}
        end
      end
    }

    [list, read]
  end

  defp list_resources(servers, conns) do
    {lines, items} =
      Enum.reduce(servers, {[], []}, fn name, {lines, items} ->
        case resource_request(conns[name], "resources/list", %{}) do
          {:ok, res} ->
            Enum.reduce(res["resources"] || [], {lines, items}, fn r, {lines, items} ->
              line =
                "#{name} #{r["uri"]}" <>
                  if(r["name"] not in [nil, ""], do: " — #{r["name"]}", else: "") <>
                  if(r["mimeType"] not in [nil, ""], do: " (#{r["mimeType"]})", else: "") <>
                  if(r["description"] not in [nil, ""], do: ": #{r["description"]}", else: "")

              item = %{
                server: name,
                uri: r["uri"],
                name: r["name"] || "",
                mimeType: r["mimeType"] || "",
                description: r["description"] || ""
              }

              {[line | lines], [item | items]}
            end)

          {:error, reason} ->
            {["#{name}: error: #{error_text(reason)}" | lines], items}
        end
      end)

    case Enum.reverse(lines) do
      [] ->
        %ToolResult{output: "No resources.", metadata: %{count: 0}}

      lines ->
        %ToolResult{
          output: Enum.join(lines, "\n"),
          metadata: %{count: length(items), resources: Enum.reverse(items)}
        }
    end
  end

  defp resource_request(pid, method, params) when is_pid(pid),
    do: Connection.request(pid, method, params)

  defp resource_request(%Toolnexus.Mcp.Client2026{} = c, method, params) do
    case Toolnexus.Mcp.Client2026.request(c, method, params) do
      {:ok, r} -> {:ok, r}
      {:pending, _, _} -> {:error, "authorization pending"}
      {:error, e} -> {:error, Toolnexus.Mcp.Direct.text(e)}
    end
  end

  @doc "Disconnect every server (terminates the source's supervisor; stdio children die)."
  @spec close(Source.t()) :: :ok
  def close(%Source{sup: sup}) do
    stop_sup(sup)
    :ok
  end

  defp stop_sup(sup) do
    if is_pid(sup) and Process.alive?(sup) do
      try do
        DynamicSupervisor.stop(sup)
      catch
        :exit, _ -> :ok
      end
    end

    :ok
  end

  # --- list-only inventory (§2 Gap 6) ------------------------------------------------

  @doc """
  Connect to every enabled server, list its tool definitions (ORIGINAL,
  unprefixed names, UNFILTERED by the `tools` map), and disconnect everything
  before returning — nothing left running. Same isolation and statuses as `load/2`.
  """
  @spec list_tools(String.t() | map(), keyword()) :: Inventory.t()
  def list_tools(input, opts \\ []) do
    config = parse_config(input)
    deadline = Keyword.get(opts, :deadline) || Keyword.get(opts, :timeout) || :infinity

    {:ok, sup} = DynamicSupervisor.start_link(strategy: :one_for_one)

    try do
      entries =
        Enum.map(config, fn {name, cfg} ->
          {name, Task.async(fn -> safe_list_server(sup, name, cfg, opts) end)}
        end)

      results = Task.yield_many(Enum.map(entries, &elem(&1, 1)), deadline)

      if Enum.any?(results, fn {_task, res} -> res == nil end) do
        Enum.each(results, fn {task, res} ->
          if res == nil, do: Task.shutdown(task, :brutal_kill)
        end)

        raise "toolnexus: MCP list deadline exceeded"
      end

      Enum.zip(entries, results)
      |> Enum.reduce(%Inventory{}, fn {{name, _}, {_t, {:ok, outcome}}}, acc ->
        case outcome do
          {:connected, defs} ->
            %{
              acc
              | tools: Map.put(acc.tools, name, Enum.map(defs, &inventory_def/1)),
                status: Map.put(acc.status, name, "connected")
            }

          {:failed, _reason} ->
            %{acc | status: Map.put(acc.status, name, "failed")}

          :disabled ->
            %{acc | status: Map.put(acc.status, name, "disabled")}
        end
      end)
    after
      stop_sup(sup)
    end
  end

  defp inventory_def(def) do
    %{
      "name" => def["name"],
      "description" => def["description"] || "",
      "inputSchema" => merged_schema(def, false)
    }
  end

  # --- per-server load ---------------------------------------------------------------

  defp safe_load_server(sup, name, cfg, opts) do
    if enabled?(cfg) do
      try do
        case Direct.probe(sup, cfg, opts) do
          {:modern, c, discover} ->
            # tools map and disableTools apply BEFORE tool search (§2.8)
            disabled = MapSet.new(opts[:disable_tools] || [])

            filter = fn defs ->
              name
              |> apply_tools_filter(defs, cfg["tools"])
              |> Enum.reject(
                &MapSet.member?(disabled, Tool.sanitize(name) <> "_" <> Tool.sanitize(&1["name"]))
              )
            end

            case Direct.load(sup, name, cfg, c, discover, opts, filter) do
              {:failed, reason} = f ->
                warn_failed(name, reason)
                f

              ok ->
                ok
            end

          {:fallback, cfg} ->
            load_server(sup, name, cfg, opts[:wait_for])

          {:failed, reason} = f ->
            warn_failed(name, reason)
            f
        end
      rescue
        e ->
          warn_failed(name, Exception.message(e))
          {:failed, Exception.message(e)}
      catch
        kind, reason ->
          warn_failed(name, "#{kind}: #{inspect(reason)}")
          {:failed, "#{kind}: #{inspect(reason)}"}
      end
    else
      :disabled
    end
  end

  defp load_server(sup, name, cfg, wait_for) do
    {:ok, pid} = start_connection(sup, cfg, wait_for)
    timeout = cfg["timeout"] || @default_timeout

    with :ok <- Connection.connect(pid, timeout),
         {:ok, defs} <- Connection.list_tools(pid) do
      kept = apply_tools_filter(name, defs, cfg["tools"])
      {:connected, Enum.map(kept, &convert_tool(name, &1, pid)), pid, []}
    else
      {:error, reason} ->
        _ = DynamicSupervisor.terminate_child(sup, pid)
        warn_failed(name, inspect(reason))
        {:failed, inspect(reason)}
    end
  end

  defp safe_list_server(sup, name, cfg, opts) do
    if enabled?(cfg) do
      try do
        case Direct.probe(sup, cfg, opts) do
          {:modern, c, _discover} ->
            result =
              case Direct.list_defs(c, opts[:wait_for]) do
                {:ok, defs} ->
                  {:connected, defs}

                {:error, reason} ->
                  warn_failed(name, reason)
                  {:failed, reason}
              end

            Toolnexus.Mcp.Client2026.close(c)
            result

          {:fallback, cfg} ->
            list_server_fallback(sup, name, cfg)

          {:failed, reason} = f ->
            warn_failed(name, reason)
            f
        end
      rescue
        e ->
          warn_failed(name, Exception.message(e))
          {:failed, Exception.message(e)}
      catch
        kind, reason ->
          warn_failed(name, "#{kind}: #{inspect(reason)}")
          {:failed, "#{kind}: #{inspect(reason)}"}
      end
    else
      :disabled
    end
  end

  # The frozen path's list (§2.F); exceptions are caught by safe_list_server.
  defp list_server_fallback(sup, name, cfg) do
    {:ok, pid} = start_connection(sup, cfg, nil)
    timeout = cfg["timeout"] || @default_timeout

    result =
      with :ok <- Connection.connect(pid, timeout),
           {:ok, defs} <- Connection.list_tools(pid) do
        {:connected, defs}
      else
        {:error, reason} ->
          warn_failed(name, inspect(reason))
          {:failed, inspect(reason)}
      end

    _ = DynamicSupervisor.terminate_child(sup, pid)
    result
  end

  defp start_connection(sup, cfg, wait_for) do
    DynamicSupervisor.start_child(sup, {Connection, cfg: cfg, wait_for: wait_for})
  end

  defp enabled?(cfg) do
    cond do
      cfg["disabled"] == true -> false
      cfg["enabled"] == false -> false
      true -> true
    end
  end

  defp warn_failed(name, message) do
    Logger.warning(~s([toolnexus] MCP server "#{name}" failed: #{message}))
  end

  # --- Gap 7: per-server tools filter (same semantics as builtins/skills filters) ------

  defp apply_tools_filter(_server, defs, filter) when filter in [nil] or map_size(filter) == 0,
    do: defs

  defp apply_tools_filter(server, defs, filter) do
    has_true = Enum.any?(filter, fn {_k, v} -> v == true end)
    present = MapSet.new(defs, & &1["name"])

    Enum.each(filter, fn {k, _v} ->
      unless MapSet.member?(present, k) do
        Logger.warning(
          ~s([toolnexus] server "#{server}" tools filter name "#{k}" matched no tool)
        )
      end
    end)

    Enum.filter(defs, fn d ->
      if has_true, do: filter[d["name"]] == true, else: filter[d["name"]] != false
    end)
  end

  # --- tool conversion (SPEC §0.2 + §0.4) ------------------------------------------------

  defp convert_tool(server, def, pid) do
    original = def["name"]

    %Tool{
      name: Tool.sanitize(server) <> "_" <> Tool.sanitize(original),
      description: def["description"] || "",
      input_schema: merged_schema(def, true),
      source: "mcp",
      hints: mcp_hints(def["annotations"]),
      execute: fn args, _ctx ->
        case Connection.call_tool(pid, original, args || %{}) do
          {:ok, result} ->
            map_call_result(result, server)

          {:error, reason} ->
            %ToolResult{output: error_text(reason), is_error: true, metadata: %{server: server}}
        end
      end
    }
  end

  # SPEC §4C: MCP annotations → hints, with MCP's defaults (absent destructiveHint and
  # openWorldHint ⇒ true); no hint fields at all ⇒ nil (unknown). Read-only is never
  # destructive.
  @doc false
  def mcp_hints(%{} = a)
      when is_map_key(a, "readOnlyHint") or is_map_key(a, "destructiveHint") or
             is_map_key(a, "openWorldHint") do
    read_only = a["readOnlyHint"] == true

    %Toolnexus.ToolHints{
      read_only: read_only,
      destructive: not read_only and Map.get(a, "destructiveHint", true) != false,
      open_world: Map.get(a, "openWorldHint", true) != false
    }
  end

  def mcp_hints(_), do: nil

  @doc false
  def merged_schema(def, additional_properties?) do
    base = def["inputSchema"] || %{}
    props = base["properties"] || %{}
    merged = base |> Map.put("type", "object") |> Map.put("properties", props)
    if additional_properties?, do: Map.put(merged, "additionalProperties", false), else: merged
  end

  # §0.4: isError ⇒ error w/ joined text; structuredContent ⇒ JSON encode; else joined text parts.
  #
  # Parts are collected BEFORE either short-circuit (SPEC §2): a server returning structured
  # content — or an error — alongside an image must keep the image. Nothing is dropped silently.
  @doc false
  def map_call_result(result, server) do
    metadata = %{server: server}
    content = result["content"]
    parts = content_parts(content)

    base =
      cond do
        result["isError"] ->
          %ToolResult{output: format_error_content(content), is_error: true, metadata: metadata}

        result["structuredContent"] != nil ->
          %ToolResult{
            output: Jason.encode!(result["structuredContent"]),
            is_error: false,
            metadata: metadata
          }

        true ->
          %ToolResult{output: join_text_content(content), is_error: false, metadata: metadata}
      end

    case parts do
      [] -> base
      parts -> %{base | parts: parts, output: output_for(base.output, parts)}
    end
  end

  # An image-only result must not be the empty string (SPEC §2) — name what came back.
  defp output_for("", parts), do: Enum.map_join(parts, "\n", &ContentPart.summary/1)
  defp output_for(output, _parts), do: output

  # SPEC §2: image ⇒ image, audio ⇒ audio, resource_link ⇒ file{url}, resource w/ blob ⇒
  # file{data}. A `resource` carrying text is appended to `output`, not made a part.
  defp content_parts(content) when is_list(content), do: Enum.flat_map(content, &content_part/1)
  defp content_parts(_), do: []

  defp content_part(%{"type" => "image", "data" => data} = c) when is_binary(data),
    do: [%ContentPart{type: "image", mime_type: c["mimeType"], data: data}]

  defp content_part(%{"type" => "audio", "data" => data} = c) when is_binary(data),
    do: [%ContentPart{type: "audio", mime_type: c["mimeType"], data: data}]

  defp content_part(%{"type" => "resource_link", "uri" => uri} = c) when is_binary(uri),
    do: [%ContentPart{type: "file", mime_type: c["mimeType"], url: uri, name: c["name"]}]

  defp content_part(%{"type" => "resource", "resource" => %{"blob" => blob} = r})
       when is_binary(blob),
       do: [%ContentPart{type: "file", mime_type: r["mimeType"], data: blob, name: r["uri"]}]

  defp content_part(_), do: []

  defp format_error_content(content) when is_list(content) do
    joined =
      content
      |> Enum.flat_map(&text_part/1)
      |> Enum.filter(&(String.trim(&1) != ""))
      |> Enum.join("\n\n")

    if joined == "", do: "MCP tool returned an error", else: joined
  end

  defp format_error_content(_), do: "MCP tool returned an error"

  defp join_text_content(content) when is_list(content),
    do: content |> Enum.flat_map(&text_part/1) |> Enum.join("\n")

  defp join_text_content(_), do: ""

  defp text_part(%{"type" => "text", "text" => text}) when is_binary(text), do: [text]

  # SPEC §2: an embedded resource carrying text is appended to `output`, never a part.
  defp text_part(%{"type" => "resource", "resource" => %{"text" => text}}) when is_binary(text),
    do: [text]

  defp text_part(_), do: []

  defp error_text({:rpc_error, %{"message" => m}}) when is_binary(m), do: m
  defp error_text(:timeout), do: "MCP tool call timed out"
  defp error_text(reason), do: "MCP tool call failed: #{inspect(reason)}"
end
