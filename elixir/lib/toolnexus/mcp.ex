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

  alias Toolnexus.{Tool, ToolResult}
  alias Toolnexus.Mcp.Connection
  alias Toolnexus.Mcp.Transport.StreamableHttp

  @default_timeout 30_000
  @reserved_keys ~w(builtins agents a2a mcpServer)

  defmodule Source do
    @moduledoc "A loaded MCP source: tools, per-server status, and its own supervisor."
    defstruct tools: [], status: %{}, sup: nil, connections: %{}

    @type t :: %__MODULE__{
            tools: [Toolnexus.Tool.t()],
            status: %{String.t() => String.t()},
            sup: pid() | nil,
            connections: %{String.t() => pid()}
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
    case raw["mcpServers"] || raw["servers"] || raw["mcp"] do
      nil -> Map.drop(raw, @reserved_keys)
      wrapped -> wrapped
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
      capability and bridges server `elicitation/create` onto it (§10).
    * `:deadline` (ms, alias `:timeout`) — overall load deadline; on exceed the
      whole load is aborted (in-flight connects torn down promptly) and a
      `RuntimeError` is raised (§2 Gap 3).
  """
  @spec load(String.t() | map(), keyword()) :: Source.t()
  def load(input, opts \\ []) do
    config = parse_config(input)
    wait_for = Keyword.get(opts, :wait_for)
    deadline = Keyword.get(opts, :deadline) || Keyword.get(opts, :timeout) || :infinity

    {:ok, sup} = DynamicSupervisor.start_link(strategy: :one_for_one)

    entries =
      Enum.map(config, fn {name, cfg} ->
        {name, Task.async(fn -> safe_load_server(sup, name, cfg, wait_for) end)}
      end)

    results = Task.yield_many(Enum.map(entries, &elem(&1, 1)), deadline)

    if Enum.any?(results, fn {_task, res} -> res == nil end) do
      Enum.each(results, fn {task, res} ->
        if res == nil, do: Task.shutdown(task, :brutal_kill)
      end)

      stop_sup(sup)
      raise "toolnexus: MCP load deadline exceeded"
    end

    acc = %Source{sup: sup}

    Enum.zip(entries, results)
    |> Enum.reduce(acc, fn {{name, _task}, {_task2, {:ok, outcome}}}, acc ->
      case outcome do
        {:connected, tools, pid} ->
          %{
            acc
            | tools: acc.tools ++ tools,
              status: Map.put(acc.status, name, "connected"),
              connections: Map.put(acc.connections, name, pid)
          }

        :failed ->
          %{acc | status: Map.put(acc.status, name, "failed")}

        :disabled ->
          %{acc | status: Map.put(acc.status, name, "disabled")}
      end
    end)
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
          {name, Task.async(fn -> safe_list_server(sup, name, cfg) end)}
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

          :failed ->
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

  defp safe_load_server(sup, name, cfg, wait_for) do
    if enabled?(cfg) do
      try do
        load_server(sup, name, cfg, wait_for)
      rescue
        e ->
          warn_failed(name, Exception.message(e))
          :failed
      catch
        kind, reason ->
          warn_failed(name, "#{kind}: #{inspect(reason)}")
          :failed
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
      {:connected, Enum.map(kept, &convert_tool(name, &1, pid)), pid}
    else
      {:error, reason} ->
        _ = DynamicSupervisor.terminate_child(sup, pid)
        warn_failed(name, inspect(reason))
        :failed
    end
  end

  defp safe_list_server(sup, name, cfg) do
    if enabled?(cfg) do
      try do
        {:ok, pid} = start_connection(sup, cfg, nil)
        timeout = cfg["timeout"] || @default_timeout

        result =
          with :ok <- Connection.connect(pid, timeout),
               {:ok, defs} <- Connection.list_tools(pid) do
            {:connected, defs}
          else
            {:error, reason} ->
              warn_failed(name, inspect(reason))
              :failed
          end

        _ = DynamicSupervisor.terminate_child(sup, pid)
        result
      rescue
        e ->
          warn_failed(name, Exception.message(e))
          :failed
      catch
        kind, reason ->
          warn_failed(name, "#{kind}: #{inspect(reason)}")
          :failed
      end
    else
      :disabled
    end
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

  defp merged_schema(def, additional_properties?) do
    base = def["inputSchema"] || %{}
    props = base["properties"] || %{}
    merged = base |> Map.put("type", "object") |> Map.put("properties", props)
    if additional_properties?, do: Map.put(merged, "additionalProperties", false), else: merged
  end

  # §0.4: isError ⇒ error w/ joined text; structuredContent ⇒ JSON encode; else joined text parts.
  defp map_call_result(result, server) do
    metadata = %{server: server}

    cond do
      result["isError"] ->
        %ToolResult{
          output: format_error_content(result["content"]),
          is_error: true,
          metadata: metadata
        }

      result["structuredContent"] != nil ->
        %ToolResult{
          output: Jason.encode!(result["structuredContent"]),
          is_error: false,
          metadata: metadata
        }

      true ->
        %ToolResult{
          output: join_text_content(result["content"]),
          is_error: false,
          metadata: metadata
        }
    end
  end

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
  defp text_part(_), do: []

  defp error_text({:rpc_error, %{"message" => m}}) when is_binary(m), do: m
  defp error_text(:timeout), do: "MCP tool call timed out"
  defp error_text(reason), do: "MCP tool call failed: #{inspect(reason)}"
end
