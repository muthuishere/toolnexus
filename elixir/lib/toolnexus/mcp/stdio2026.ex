defmodule Toolnexus.Mcp.Stdio2026 do
  @moduledoc """
  stdio transport for the MCP 2026-07-28 path (SPEC §2.3): spawn the server, one
  JSON-RPC message per line, responses demultiplexed by `id` (concurrent calls
  allowed). The child's stderr is passed through to this VM's stderr and never
  parsed. A dead child fails every pending call instead of hanging. The child
  dies with this process (close / supervisor shutdown).
  """
  use GenServer, restart: :temporary, shutdown: 5_000

  def child_spec(cfg), do: %{id: __MODULE__, start: {__MODULE__, :start_link, [cfg]}, restart: :temporary}

  def start_link(cfg), do: GenServer.start_link(__MODULE__, cfg)

  @doc "Send one request line and wait up to `timeout` ms for its response."
  def round_trip(pid, id, body, timeout) do
    GenServer.call(pid, {:send, id, body}, timeout)
  catch
    :exit, {:timeout, _} ->
      GenServer.cast(pid, {:forget, id})
      {:error, :timeout}

    :exit, _ ->
      {:error, :closed}
  end

  @doc "Stop the server (and its child)."
  def close(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end

  @impl true
  def init(cfg) do
    Process.flag(:trap_exit, true)

    with {:ok, [cmd | args]} <- command(cfg),
         {:ok, exe} <- resolve(cmd) do
      env =
        for {k, v} <- config_env(cfg), do: {to_charlist(to_string(k)), to_charlist(to_string(v))}

      opts =
        [:binary, :exit_status, :use_stdio, :hide, {:line, 16 * 1024 * 1024}, {:args, args}] ++
          if(env == [], do: [], else: [{:env, env}]) ++
          case cfg["cwd"] do
            cwd when is_binary(cwd) and cwd != "" -> [{:cd, to_charlist(cwd)}]
            _ -> []
          end

      try do
        port = Port.open({:spawn_executable, exe}, opts)

        os_pid =
          case Port.info(port, :os_pid) do
            {:os_pid, p} -> p
            _ -> nil
          end

        {:ok, %{port: port, os_pid: os_pid, pending: %{}, err: nil, buf: ""}}
      rescue
        e -> {:stop, {:spawn_failed, Exception.message(e)}}
      end
    else
      {:error, reason} -> {:stop, reason}
    end
  end

  defp command(cfg) do
    case cfg["command"] do
      [_ | _] = list -> {:ok, Enum.map(list, &to_string/1)}
      c when is_binary(c) and c != "" -> {:ok, [c | Enum.map(List.wrap(cfg["args"]), &to_string/1)]}
      _ -> {:error, :no_command}
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
      {_, %{} = env} -> env
      _ -> %{}
    end
  end

  @impl true
  def handle_call({:send, _id, _body}, _from, %{err: e} = s) when e != nil,
    do: {:reply, {:error, e}, s}

  def handle_call({:send, id, body}, from, s) do
    Port.command(s.port, [body, "\n"])
    {:noreply, %{s | pending: Map.put(s.pending, id, from)}}
  end

  @impl true
  def handle_cast({:forget, id}, s), do: {:noreply, %{s | pending: Map.delete(s.pending, id)}}

  @impl true
  def handle_info({port, {:data, {:noeol, part}}}, %{port: port} = s),
    do: {:noreply, %{s | buf: s.buf <> part}}

  def handle_info({port, {:data, {:eol, part}}}, %{port: port} = s) do
    line = s.buf <> part
    s = %{s | buf: ""}

    # Only responses (id, no method) matter; notifications, server requests and
    # noise are ignored.
    case Jason.decode(line) do
      {:ok, %{"id" => id} = m} when not is_map_key(m, "method") and id != nil ->
        case Map.pop(s.pending, id) do
          {nil, _} ->
            {:noreply, s}

          {from, rest} ->
            GenServer.reply(from, {:ok, m})
            {:noreply, %{s | pending: rest}}
        end

      _ ->
        {:noreply, s}
    end
  end

  def handle_info({port, {:exit_status, _}}, %{port: port} = s) do
    Enum.each(s.pending, fn {_, from} -> GenServer.reply(from, {:error, :closed}) end)
    {:noreply, %{s | pending: %{}, err: :closed, os_pid: nil}}
  end

  def handle_info(_, s), do: {:noreply, s}

  @impl true
  def terminate(_, s) do
    if s.err == nil do
      try do
        Port.close(s.port)
      rescue
        _ -> :ok
      end
    end

    ensure_dead(s.os_pid)
    :ok
  end

  defp ensure_dead(nil), do: :ok

  defp ensure_dead(os_pid) do
    spid = Integer.to_string(os_pid)
    alive? = fn -> match?({_, 0}, System.cmd("kill", ["-0", spid], stderr_to_stdout: true)) end

    gone =
      Enum.any?(1..20, fn _ ->
        if alive?.(), do: Process.sleep(25) && false, else: true
      end)

    unless gone, do: System.cmd("kill", ["-KILL", spid], stderr_to_stdout: true)
    :ok
  end
end
