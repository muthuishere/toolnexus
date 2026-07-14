defmodule Toolnexus.Mcp.Transport.Stdio do
  @moduledoc """
  Stdio transport for a local MCP server (SPEC §2 "local").

  Spawns the configured command as an OS child via an Erlang Port
  (`:spawn_executable` through `/bin/sh -c 'exec ... 2>/dev/null'` so stderr is
  discarded like the JS SDK default, and so the port's `os_pid` IS the child's
  pid after `exec`). Env = full parent env + config `environment`/`env` merged
  over it (ports inherit the parent env; `:env` entries override) — matching
  what js/go do today. Newline-delimited JSON framing with partial-line
  buffering happens in the owning connection via `split_lines/1`.

  ADR-0007 lifetime rule: the child is owned by the connection process that
  opened the port — it outlives connect and dies on close. `close/1` closes
  the port and then *ensures* the OS child is dead (kill by OS pid if needed).
  """

  alias Toolnexus.Mcp.Protocol

  defstruct [:port, :os_pid]

  @type t :: %__MODULE__{port: port() | nil, os_pid: integer() | nil}

  @doc "Spawn the configured command. Returns `{:ok, t}` or `{:error, reason}`."
  def open(cfg) do
    with {:ok, [cmd | args]} <- command_list(cfg),
         {:ok, path} <- resolve(cmd) do
      env =
        for {k, v} <- config_env(cfg) do
          {String.to_charlist(to_string(k)), String.to_charlist(to_string(v))}
        end

      opts = [
        :binary,
        :exit_status,
        # `exec "$0" "$@" 2>/dev/null`: sh execs into the real command, so the
        # port's os_pid is the child's pid and stderr is discarded.
        args: ["-c", ~S(exec "$0" "$@" 2>/dev/null), path | args]
      ]

      opts = if env == [], do: opts, else: [{:env, env} | opts]

      opts =
        case cfg["cwd"] do
          cwd when is_binary(cwd) and cwd != "" -> [{:cd, String.to_charlist(cwd)} | opts]
          _ -> opts
        end

      try do
        port = Port.open({:spawn_executable, "/bin/sh"}, opts)

        os_pid =
          case Port.info(port, :os_pid) do
            {:os_pid, p} -> p
            _ -> nil
          end

        {:ok, %__MODULE__{port: port, os_pid: os_pid}}
      rescue
        e -> {:error, {:spawn_failed, Exception.message(e)}}
      end
    end
  end

  @doc "Send one JSON-RPC message as a newline-delimited JSON line."
  def send_msg(%__MODULE__{port: port}, msg) do
    Port.command(port, Protocol.encode_line(msg))
    :ok
  rescue
    _ -> {:error, :closed}
  end

  @doc """
  Split a stdout buffer into complete lines + the trailing partial line.
  Returns `{lines, rest}` with blank lines dropped.
  """
  def split_lines(buffer) do
    parts = String.split(buffer, "\n")
    {lines, [rest]} = Enum.split(parts, -1)
    {lines |> Enum.map(&String.trim_trailing(&1, "\r")) |> Enum.reject(&(&1 == "")), rest}
  end

  @doc """
  Close the port and ensure the OS child is dead (ADR-0007): well-behaved
  children exit on stdin EOF; stragglers are killed by OS pid.
  """
  def close(%__MODULE__{port: port, os_pid: os_pid}) do
    if port != nil and Port.info(port) != nil do
      try do
        Port.close(port)
      rescue
        _ -> :ok
      end
    end

    ensure_dead(os_pid)
    :ok
  end

  defp ensure_dead(nil), do: :ok

  defp ensure_dead(os_pid) do
    spid = Integer.to_string(os_pid)

    alive_after_grace =
      Enum.reduce_while(1..20, alive?(spid), fn _, alive ->
        if alive do
          Process.sleep(25)
          {:cont, alive?(spid)}
        else
          {:halt, false}
        end
      end)

    if alive_after_grace do
      _ = System.cmd("kill", ["-KILL", spid], stderr_to_stdout: true)
    end

    :ok
  end

  defp alive?(spid) do
    match?({_, 0}, System.cmd("kill", ["-0", spid], stderr_to_stdout: true))
  end

  defp command_list(cfg) do
    case cfg["command"] do
      [_ | _] = list ->
        {:ok, Enum.map(list, &to_string/1)}

      cmd when is_binary(cmd) and cmd != "" ->
        # Claude-desktop style: "command" string + optional "args" list.
        {:ok, [cmd | Enum.map(List.wrap(cfg["args"] || []), &to_string/1)]}

      _ ->
        {:error, :no_command}
    end
  end

  defp resolve(cmd) do
    cond do
      String.contains?(cmd, "/") and File.exists?(cmd) -> {:ok, Path.expand(cmd)}
      path = System.find_executable(cmd) -> {:ok, path}
      true -> {:error, {:executable_not_found, cmd}}
    end
  end

  defp config_env(cfg) do
    case {cfg["environment"], cfg["env"]} do
      {%{} = env, _} when map_size(env) > 0 -> env
      {_, %{} = env} when map_size(env) > 0 -> env
      _ -> %{}
    end
  end
end
