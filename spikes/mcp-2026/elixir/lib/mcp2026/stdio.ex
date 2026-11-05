defmodule Mcp2026.Stdio do
  @moduledoc """
  stdio transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC message per
  line on stdin/stdout. The server's stderr is its log stream: inherited (passed through to this
  VM's stderr), never parsed. No headers, no auth — the `_meta` carries the version.
  """
  use GenServer
  alias Mcp2026.Client

  @doc """
  Spawn `cmd` with `args` and return a `%Mcp2026.Client{}` with the same API as the HTTP client.
  Options: `:env` (list of {"K", "V"}), `:cd`. Call `Mcp2026.Client.close/1` to stop it.
  """
  def start(cmd, args \\ [], opts \\ []) do
    exe = System.find_executable(cmd) || cmd

    with {:ok, pid} <- GenServer.start_link(__MODULE__, {exe, args, opts}) do
      c = Client.new("")
      {:ok, %{c | stdio: pid}}
    end
  end

  @impl true
  def init({exe, args, opts}) do
    env = for {k, v} <- Keyword.get(opts, :env, []), do: {to_charlist(k), to_charlist(v)}

    port_opts =
      [:binary, :exit_status, :use_stdio, :hide, {:line, 16 * 1024 * 1024}, {:args, args}, {:env, env}] ++
        if(opts[:cd], do: [{:cd, opts[:cd]}], else: [])

    port = Port.open({:spawn_executable, exe}, port_opts)
    {:ok, %{port: port, pending: %{}, err: nil, buf: ""}}
  end

  @doc false
  def round_trip(pid, id, body) do
    GenServer.call(pid, {:send, id, body}, Mcp2026.HTTP.default_timeout())
  catch
    :exit, {:timeout, _} ->
      GenServer.cast(pid, {:forget, id})
      {:error, "stdio request timed out"}

    :exit, _ ->
      {:error, "stdio server closed its stdout"}
  end

  @doc false
  def close(pid) do
    os_pid = GenServer.call(pid, :os_pid)
    GenServer.stop(pid)

    if os_pid do
      alive = fn -> match?({_, 0}, System.cmd("kill", ["-0", "#{os_pid}"], stderr_to_stdout: true)) end

      exited =
        Enum.any?(1..20, fn _ ->
          alive.() == false or (Process.sleep(100) && false)
        end)

      unless exited, do: System.cmd("kill", ["-9", "#{os_pid}"], stderr_to_stdout: true)
    end

    :ok
  end

  @impl true
  def handle_call({:send, _id, _body}, _from, %{err: e} = s) when e != nil, do: {:reply, {:error, e}, s}

  def handle_call({:send, id, body}, from, s) do
    Port.command(s.port, [body, "\n"])
    {:noreply, %{s | pending: Map.put(s.pending, id, from)}}
  end

  def handle_call(:os_pid, _from, s) do
    case s.err == nil && Port.info(s.port, :os_pid) do
      {:os_pid, n} -> {:reply, n, s}
      _ -> {:reply, nil, s}
    end
  end

  @impl true
  def handle_cast({:forget, id}, s), do: {:noreply, %{s | pending: Map.delete(s.pending, id)}}

  @impl true
  def handle_info({port, {:data, {:noeol, part}}}, %{port: port} = s), do: {:noreply, %{s | buf: s.buf <> part}}

  def handle_info({port, {:data, {:eol, part}}}, %{port: port} = s) do
    line = s.buf <> part
    s = %{s | buf: ""}

    # Only responses (id, no method) matter; notifications / server requests / noise are ignored.
    case JSON.decode(line) do
      {:ok, %{"id" => id} = m} when not is_map_key(m, "method") and id != nil ->
        case Map.pop(s.pending, id) do
          {nil, _} ->
            {:noreply, s}

          {from, rest} ->
            reply =
              if is_map(m["result"]) or is_map(m["error"]),
                do: {:ok, m},
                else: {:error, "response has neither result nor error"}

            GenServer.reply(from, reply)
            {:noreply, %{s | pending: rest}}
        end

      _ ->
        {:noreply, s}
    end
  end

  def handle_info({port, {:exit_status, _}}, %{port: port} = s) do
    err = "stdio server closed its stdout"
    Enum.each(s.pending, fn {_, from} -> GenServer.reply(from, {:error, err}) end)
    {:noreply, %{s | pending: %{}, err: err}}
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

    :ok
  end

  @doc """
  Serve `server` as newline-delimited JSON-RPC on this VM's stdin/stdout until stdin closes. No auth
  and no HTTP headers on stdio; only the `_meta` protocol version is checked.
  """
  def serve(%Mcp2026.Server{} = server) do
    case IO.binread(:stdio, :line) do
      :eof ->
        :ok

      {:error, _} ->
        :ok

      line ->
        case JSON.decode(line) do
          {:ok, %{"id" => id} = req} when id != nil ->
            p = if is_map(req["params"]), do: req["params"], else: %{}
            mv = get_in(p, ["_meta", Mcp2026.meta_version()])

            res =
              if mv != Mcp2026.protocol_version(),
                do: {:error, Mcp2026.Server.unsupported(if(is_binary(mv), do: mv, else: ""))},
                else: Mcp2026.Server.dispatch(server, %{principal: nil}, to_string(req["method"]), p)

            msg =
              case res do
                {:ok, r} -> %{"jsonrpc" => "2.0", "id" => id, "result" => r}
                {:error, e} -> %{"jsonrpc" => "2.0", "id" => id, "error" => Mcp2026.RpcError.to_map(e)}
              end

            IO.binwrite(:stdio, JSON.encode!(msg) <> "\n")

          {:ok, _} ->
            :ok

          _ ->
            IO.binwrite(:stdio, JSON.encode!(%{"jsonrpc" => "2.0", "id" => nil, "error" => %{"code" => -32700, "message" => "Parse error"}}) <> "\n")
        end

        serve(server)
    end
  end
end
