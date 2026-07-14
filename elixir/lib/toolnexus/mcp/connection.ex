defmodule Toolnexus.Mcp.Connection do
  @moduledoc """
  One GenServer per configured MCP server (SPEC §2). Owns the transport
  (stdio port or streamable-HTTP state), correlates JSON-RPC request ids with
  per-call timeouts (config `timeout`, default 30000 ms), performs the
  `initialize` handshake, paginates `tools/list`, executes `tools/call`, and
  bridges inbound `elicitation/create` onto the host `wait_for` (§2 + §10):
  form → `kind:"input"`, URL → `kind:"authorization"`; no `wait_for` ⇒ decline.

  The stdio child's lifetime is owned by this process (ADR-0007): it outlives
  connect and is killed in `terminate/2` on close/supervisor shutdown.
  """

  use GenServer, restart: :temporary, shutdown: 10_000

  alias Toolnexus.Mcp.Protocol
  alias Toolnexus.Mcp.Transport.{Stdio, StreamableHttp}

  @default_timeout 30_000
  @max_list_pages 1_000

  # --- client API -------------------------------------------------------------

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts)

  @doc "Connect + initialize. Bounded by the server timeout; returns :ok | {:error, reason}."
  def connect(pid, timeout) do
    GenServer.call(pid, :connect, timeout * 3 + 5_000)
  catch
    :exit, reason -> {:error, {:connect_exit, exit_brief(reason)}}
  end

  @doc "List all tool definitions, following nextCursor pagination."
  def list_tools(pid), do: paginate(pid, nil, MapSet.new(), [], 0)

  @doc "Call a tool by its ORIGINAL (unprefixed) name."
  def call_tool(pid, name, args), do: rpc(pid, {:tools_call, name, args})

  @doc "The stdio child's OS pid (for lifetime assertions); :error for remote servers."
  def os_pid(pid) do
    GenServer.call(pid, :os_pid)
  catch
    :exit, _ -> :error
  end

  defp rpc(pid, req) do
    # The connection's own per-call timer guarantees a reply; :infinity here.
    GenServer.call(pid, {:rpc, req}, :infinity)
  catch
    :exit, reason -> {:error, {:rpc_exit, exit_brief(reason)}}
  end

  defp exit_brief({reason, _call}), do: reason
  defp exit_brief(reason), do: reason

  defp paginate(_pid, _cursor, _seen, _acc, page) when page >= @max_list_pages,
    do: {:error, {:too_many_pages, @max_list_pages}}

  defp paginate(pid, cursor, seen, acc, page) do
    case rpc(pid, {:tools_list, cursor}) do
      {:ok, %{"tools" => tools} = res} ->
        acc = acc ++ List.wrap(tools)

        case res["nextCursor"] do
          nil ->
            {:ok, acc}

          next ->
            if MapSet.member?(seen, next),
              do: {:error, {:duplicate_cursor, next}},
              else: paginate(pid, next, MapSet.put(seen, next), acc, page + 1)
        end

      {:ok, other} ->
        {:error, {:bad_list_result, other}}

      {:error, _} = err ->
        err
    end
  end

  # --- server -----------------------------------------------------------------

  @impl true
  def init(opts) do
    Process.flag(:trap_exit, true)
    cfg = Keyword.fetch!(opts, :cfg)

    {:ok,
     %{
       cfg: cfg,
       wait_for: Keyword.get(opts, :wait_for),
       timeout: cfg["timeout"] || @default_timeout,
       mode: nil,
       stdio: nil,
       http: nil,
       buffer: "",
       pending: %{},
       next_id: 1,
       dead: false
     }}
  end

  @impl true
  def handle_call(:connect, from, s) do
    if remote?(s.cfg), do: connect_remote(from, s), else: connect_stdio(from, s)
  end

  def handle_call(:os_pid, _from, s) do
    case s.stdio do
      %Stdio{os_pid: p} when is_integer(p) -> {:reply, {:ok, p}, s}
      _ -> {:reply, :error, s}
    end
  end

  def handle_call({:rpc, _req}, _from, %{dead: true} = s), do: {:reply, {:error, :closed}, s}

  def handle_call({:rpc, req}, from, s) do
    {id, s} = next_id(s)
    msg = build_request(id, req)

    case s.mode do
      :http_sync ->
        conn = self()
        http = s.http

        spawn(fn ->
          outcome =
            try do
              StreamableHttp.post_rpc(http, id, msg)
            rescue
              e -> {:error, {:transport, Exception.message(e)}}
            end

          send(conn, {:http_rpc_result, id, outcome})
        end)

        {:noreply, put_pending(s, id, {:call, from}, s.timeout + 2_000)}

      mode when mode in [:stdio, :http_legacy] ->
        case transport_send(s, msg) do
          :ok -> {:noreply, put_pending(s, id, {:call, from}, s.timeout)}
          {:error, reason} -> {:reply, {:error, reason}, s}
        end

      nil ->
        {:reply, {:error, :not_connected}, s}
    end
  end

  @impl true
  def handle_cast({:server_reply, id, {:result, result}}, s) do
    _ = transport_send(s, Protocol.response(id, result))
    {:noreply, s}
  end

  def handle_cast({:server_reply, id, {:error, message}}, s) do
    _ = transport_send(s, Protocol.error_response(id, -32603, message))
    {:noreply, s}
  end

  @impl true
  def handle_info({port, {:data, chunk}}, %{stdio: %Stdio{port: port}} = s) do
    {lines, rest} = Stdio.split_lines(s.buffer <> chunk)
    s = %{s | buffer: rest}

    s =
      Enum.reduce(lines, s, fn line, s ->
        case Jason.decode(line) do
          {:ok, msg} when is_map(msg) -> route(msg, s)
          _ -> s
        end
      end)

    {:noreply, s}
  end

  def handle_info({port, {:exit_status, code}}, %{stdio: %Stdio{port: port}} = s) do
    {:noreply, fail_all(%{s | dead: true}, {:server_exited, code})}
  end

  def handle_info({:transport_msg, msg}, s), do: {:noreply, route(msg, s)}

  def handle_info({:http_connect_result, result}, s) do
    case pop_pending(s, :connect) do
      {nil, s} ->
        {:noreply, s}

      {{:connect, from}, s} ->
        case result do
          {:ok, %StreamableHttp{mode: :legacy} = t} ->
            GenServer.reply(from, :ok)
            {:noreply, %{s | mode: :http_legacy, http: t}}

          {:ok, %StreamableHttp{} = t} ->
            GenServer.reply(from, :ok)
            {:noreply, %{s | mode: :http_sync, http: t}}

          {:error, reason} ->
            GenServer.reply(from, {:error, reason})
            {:noreply, s}
        end
    end
  end

  def handle_info({:http_rpc_result, id, outcome}, s) do
    case pop_pending(s, id) do
      {nil, s} ->
        {:noreply, s}

      {{:call, from}, s} ->
        case outcome do
          {:ok, result, extras, t} ->
            GenServer.reply(from, {:ok, result})
            {:noreply, Enum.reduce(extras, %{s | http: t}, &route/2)}

          {:error, reason} ->
            GenServer.reply(from, {:error, reason})
            {:noreply, s}
        end
    end
  end

  def handle_info({:rpc_timeout, id}, s) do
    case pop_pending(s, id) do
      {nil, s} ->
        {:noreply, s}

      {{:init, from}, s} ->
        GenServer.reply(from, {:error, :timeout})
        {:noreply, s}

      {{:call, from}, s} ->
        GenServer.reply(from, {:error, :timeout})
        {:noreply, s}

      {{:connect, from}, s} ->
        GenServer.reply(from, {:error, :timeout})
        {:noreply, s}
    end
  end

  # legacy streamer death, stray monitors/exits, stray sse endpoint events
  def handle_info({:DOWN, _ref, :process, pid, reason}, s) do
    if match?(%StreamableHttp{streamer: ^pid}, s.http),
      do: {:noreply, fail_all(%{s | dead: true}, {:stream_closed, reason})},
      else: {:noreply, s}
  end

  def handle_info({:EXIT, _pid, _reason}, s), do: {:noreply, s}
  def handle_info({:sse_endpoint, _}, s), do: {:noreply, s}
  def handle_info(_other, s), do: {:noreply, s}

  @impl true
  def terminate(_reason, s) do
    fail_all(s, :closed)
    if s.stdio, do: Stdio.close(s.stdio)
    if s.http, do: StreamableHttp.close(s.http)
    :ok
  end

  # --- connect helpers ---------------------------------------------------------

  defp connect_stdio(from, s) do
    case Stdio.open(s.cfg) do
      {:ok, t} ->
        {id, s} = next_id(s)
        msg = Protocol.request(id, "initialize", Protocol.initialize_params(s.wait_for != nil))
        s = %{s | mode: :stdio, stdio: t}

        case Stdio.send_msg(t, msg) do
          :ok -> {:noreply, put_pending(s, id, {:init, from}, s.timeout)}
          {:error, reason} -> {:reply, {:error, reason}, s}
        end

      {:error, reason} ->
        {:reply, {:error, reason}, s}
    end
  end

  defp connect_remote(from, s) do
    conn = self()
    cfg = s.cfg
    elicitation? = s.wait_for != nil

    # Run the (blocking) HTTP connect in a helper so this GenServer stays
    # responsive — a supervisor shutdown mid-connect is prompt (§2 Gap 3).
    spawn(fn ->
      result =
        try do
          StreamableHttp.connect(cfg, conn, elicitation?)
        rescue
          e -> {:error, {:transport, Exception.message(e)}}
        end

      send(conn, {:http_connect_result, result})
    end)

    # Streamable attempt + legacy fallback are each bounded by the server
    # timeout; the safety timer covers both plus slack.
    {:noreply, put_pending(s, :connect, {:connect, from}, s.timeout * 2 + 2_000)}
  end

  # --- inbound routing ----------------------------------------------------------

  defp route(msg, s) do
    case Protocol.classify(msg) do
      {:response, id, result} -> resolve(s, id, result)
      {:request, id, method, params} -> handle_server_request(id, method, params, s)
      # Unknown server notifications are tolerated silently (§2).
      {:notification, _method, _params} -> s
      :unknown -> s
    end
  end

  defp resolve(s, id, result) do
    case pop_pending(s, id) do
      {nil, s} ->
        s

      {{:init, from}, s} ->
        case result do
          {:ok, _init_result} ->
            _ = transport_send(s, Protocol.initialized_notification())
            GenServer.reply(from, :ok)

          {:error, error} ->
            GenServer.reply(from, {:error, {:rpc_error, error}})
        end

        s

      {{:call, from}, s} ->
        case result do
          {:ok, r} -> GenServer.reply(from, {:ok, r})
          {:error, error} -> GenServer.reply(from, {:error, {:rpc_error, error}})
        end

        s
    end
  end

  defp handle_server_request(id, "ping", _params, s) do
    _ = transport_send(s, Protocol.ping_response(id))
    s
  end

  defp handle_server_request(id, "elicitation/create", params, s) do
    case s.wait_for do
      nil ->
        # Without a waitFor the host degrades cleanly: decline.
        _ = transport_send(s, Protocol.response(id, %{"action" => "decline"}))
        s

      wait_for ->
        conn = self()

        # wait_for may block on a human — never block the connection loop.
        spawn(fn ->
          reply =
            try do
              request = Protocol.elicitation_to_request(params)
              answer = wait_for.(request)
              {:result, Protocol.answer_to_elicit_result(answer)}
            rescue
              e -> {:error, Exception.message(e)}
            catch
              kind, reason -> {:error, "#{kind}: #{inspect(reason)}"}
            end

          GenServer.cast(conn, {:server_reply, id, reply})
        end)

        s
    end
  end

  defp handle_server_request(id, _method, _params, s) do
    _ = transport_send(s, Protocol.error_response(id, -32601, "Method not found"))
    s
  end

  # --- plumbing ------------------------------------------------------------------

  defp build_request(id, {:tools_list, cursor}), do: Protocol.tools_list_request(id, cursor)

  defp build_request(id, {:tools_call, name, args}),
    do: Protocol.tools_call_request(id, name, args)

  defp transport_send(%{mode: :stdio, stdio: t}, msg), do: Stdio.send_msg(t, msg)

  defp transport_send(%{mode: mode, http: t}, msg) when mode in [:http_legacy, :http_sync] do
    # POSTs must not block the connection loop (responses to server requests,
    # notifications); fire in a helper.
    spawn(fn -> StreamableHttp.send_msg(t, msg) end)
    :ok
  end

  defp transport_send(_s, _msg), do: {:error, :not_connected}

  defp next_id(s), do: {s.next_id, %{s | next_id: s.next_id + 1}}

  defp put_pending(s, id, tag, timeout) do
    timer = Process.send_after(self(), {:rpc_timeout, id}, timeout)
    %{s | pending: Map.put(s.pending, id, {tag, timer})}
  end

  defp pop_pending(s, id) do
    case Map.pop(s.pending, id) do
      {nil, _} ->
        {nil, s}

      {{tag, timer}, rest} ->
        Process.cancel_timer(timer)
        {tag, %{s | pending: rest}}
    end
  end

  defp fail_all(s, reason) do
    Enum.each(s.pending, fn {_id, {tag, timer}} ->
      Process.cancel_timer(timer)

      case tag do
        {:init, from} -> GenServer.reply(from, {:error, reason})
        {:call, from} -> GenServer.reply(from, {:error, reason})
        {:connect, from} -> GenServer.reply(from, {:error, reason})
      end
    end)

    %{s | pending: %{}}
  end

  defp remote?(cfg) do
    case cfg["type"] do
      "remote" -> true
      "local" -> false
      _ -> is_binary(cfg["url"]) and cfg["url"] != ""
    end
  end
end
