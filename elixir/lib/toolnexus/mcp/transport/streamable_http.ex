defmodule Toolnexus.Mcp.Transport.Sse do
  @moduledoc """
  Incremental server-sent-events parser: feed chunks, collect complete events.
  MCP-style streams (`event:`/`data:` lines, no `[DONE]` sentinel).
  """

  defstruct buffer: "", event: nil, data: []

  def new, do: %__MODULE__{}

  @doc "Feed a chunk; returns `{events, state}` where each event is `%{event: name, data: string}`."
  def feed(%__MODULE__{} = s, chunk) do
    parts = String.split(s.buffer <> chunk, "\n")
    {lines, [rest]} = Enum.split(parts, -1)

    {events, s} =
      Enum.reduce(lines, {[], %{s | buffer: ""}}, fn line, {evs, st} ->
        handle_line(String.trim_trailing(line, "\r"), evs, st)
      end)

    {Enum.reverse(events), %{s | buffer: rest}}
  end

  @doc "Parse a complete buffered SSE body (lenient: flushes a trailing unterminated event)."
  def parse_all(binary) do
    {events, s} = feed(new(), binary <> "\n")

    case dispatch(s) do
      nil -> events
      ev -> events ++ [ev]
    end
  end

  defp handle_line("", evs, st) do
    case dispatch(st) do
      nil -> {evs, %{st | event: nil, data: []}}
      ev -> {[ev | evs], %{st | event: nil, data: []}}
    end
  end

  defp handle_line(":" <> _, evs, st), do: {evs, st}
  defp handle_line("event:" <> v, evs, st), do: {evs, %{st | event: String.trim(v)}}
  defp handle_line("data:" <> v, evs, st), do: {evs, %{st | data: [strip_space(v) | st.data]}}
  # other fields (id:, retry:) and anything unknown: ignored
  defp handle_line(_, evs, st), do: {evs, st}

  defp dispatch(%{data: []}), do: nil

  defp dispatch(st),
    do: %{event: st.event || "message", data: st.data |> Enum.reverse() |> Enum.join("\n")}

  defp strip_space(" " <> rest), do: rest
  defp strip_space(v), do: v
end

defmodule Toolnexus.Mcp.Transport.StreamableHttp do
  @moduledoc """
  Streamable-HTTP transport for a remote MCP server (SPEC §2 "remote"), built
  on Req. POSTs JSON-RPC to the configured url with the configured headers
  (after `${ENV_VAR}` expansion — values are never logged), accepts either a
  JSON response or an SSE stream, carries the `Mcp-Session-Id` response header
  onto subsequent requests, and DELETEs the session on close.

  Legacy SSE fallback (js/src/mcp.ts decision flow): if the streamable POST
  `initialize` fails for any reason, GET an SSE stream that supplies an
  `endpoint` event, then POST subsequent messages to that endpoint; responses
  arrive on the GET stream (delivered to the owner as `{:transport_msg, msg}`).
  """

  alias Toolnexus.Mcp.Protocol
  alias Toolnexus.Mcp.Transport.Sse

  defstruct [
    :url,
    :headers,
    :timeout,
    :session,
    :protocol_version,
    :endpoint,
    :streamer,
    mode: :streamable
  ]

  @type t :: %__MODULE__{}

  @doc """
  Expand `${ENV_VAR}` in header values from the environment so tokens live in
  the environment, not the committed config. Values are never logged.
  """
  def expand_env_headers(nil), do: %{}

  def expand_env_headers(headers) when is_map(headers) do
    Map.new(headers, fn {k, v} ->
      {k,
       Regex.replace(~r/\$\{([A-Za-z0-9_]+)\}/, to_string(v), fn _, name ->
         System.get_env(name) || ""
       end)}
    end)
  end

  @doc """
  Connect: POST `initialize` (streamable), capture the session header, send
  `notifications/initialized`. On any initialize failure, try the legacy SSE
  fallback. `owner` is the connection pid that will receive `{:transport_msg, _}`
  messages in legacy mode (retargeted from the calling process after connect).
  """
  def connect(cfg, owner, elicitation?) do
    t = %__MODULE__{
      url: cfg["url"],
      headers: expand_env_headers(cfg["headers"]),
      timeout: cfg["timeout"] || 30_000
    }

    init = Protocol.request(0, "initialize", Protocol.initialize_params(elicitation?))

    case post_rpc(t, 0, init) do
      {:ok, result, _extras, t} ->
        t = %{t | protocol_version: result["protocolVersion"] || Protocol.protocol_version()}

        case send_msg(t, Protocol.initialized_notification()) do
          :ok -> {:ok, t}
          {:error, reason} -> {:error, {:initialized_notify_failed, reason}}
        end

      {:error, primary} ->
        legacy_connect(t, owner, elicitation?, primary)
    end
  end

  @doc """
  Streamable-mode request/response: POST the message, parse the JSON or SSE
  reply, return `{:ok, result, extra_msgs, t}` where `extra_msgs` are any other
  JSON-RPC messages (server requests/notifications) seen on the stream.
  """
  def post_rpc(%__MODULE__{} = t, id, msg) do
    case do_post(t, msg, id) do
      {:ok, t, msgs} ->
        {mine, extras} = Enum.split_with(msgs, &match?(%{"id" => ^id}, &1))

        case mine do
          [%{"result" => result} | _] -> {:ok, result, extras, t}
          [%{"error" => error} | _] -> {:error, {:rpc_error, error}}
          [] -> {:error, :no_response}
        end

      {:error, _} = err ->
        err
    end
  end

  @doc "Fire-and-forget send (notifications, responses to server requests; legacy-mode requests)."
  def send_msg(%__MODULE__{mode: :legacy} = t, msg) do
    case Req.post(
           url: t.endpoint,
           json: msg,
           headers: base_headers(t),
           retry: false,
           receive_timeout: t.timeout,
           connect_options: [timeout: t.timeout]
         ) do
      {:ok, %Req.Response{status: s}} when s in 200..299 -> :ok
      {:ok, %Req.Response{status: s}} -> {:error, {:http_status, s}}
      {:error, reason} -> {:error, reason}
    end
  end

  def send_msg(%__MODULE__{} = t, msg) do
    case do_post(t, msg, nil) do
      {:ok, _t, _msgs} -> :ok
      {:error, _} = err -> err
    end
  end

  @doc "Close: DELETE the session if one was established; kill the legacy streamer."
  def close(%__MODULE__{} = t) do
    if t.streamer && Process.alive?(t.streamer), do: Process.exit(t.streamer, :kill)

    if t.session do
      _ =
        try do
          Req.delete(url: t.url, headers: base_headers(t), retry: false, receive_timeout: 3_000)
        rescue
          _ -> :ok
        catch
          _, _ -> :ok
        end
    end

    :ok
  end

  # --- streamable POST -------------------------------------------------------

  defp do_post(t, msg, expect_id) do
    opts = [
      url: t.url,
      json: msg,
      headers: base_headers(t),
      retry: false,
      compressed: false,
      receive_timeout: t.timeout,
      connect_options: [timeout: t.timeout],
      into: collector(expect_id)
    ]

    case Req.post(opts) do
      {:ok, %Req.Response{status: status} = resp} when status in 200..299 ->
        {:ok, capture_session(t, resp), extract_msgs(resp)}

      {:ok, %Req.Response{status: status}} ->
        {:error, {:http_status, status}}

      {:error, reason} ->
        {:error, reason}
    end
  rescue
    e -> {:error, {:transport, Exception.message(e)}}
  end

  # Incremental collector: accumulates the body and halts an SSE stream as soon
  # as the response to our request id is complete (some servers keep the POST
  # stream open after the response).
  defp collector(expect_id) do
    fn {:data, chunk}, {req, resp} ->
      body = if is_binary(resp.body), do: resp.body <> chunk, else: chunk
      resp = %{resp | body: body}

      if expect_id != nil and sse?(resp) and has_response?(body, expect_id) do
        {:halt, {req, resp}}
      else
        {:cont, {req, resp}}
      end
    end
  end

  defp sse?(resp) do
    resp
    |> Req.Response.get_header("content-type")
    |> Enum.any?(&String.contains?(&1, "text/event-stream"))
  end

  defp json?(resp) do
    resp
    |> Req.Response.get_header("content-type")
    |> Enum.any?(&String.contains?(&1, "application/json"))
  end

  defp has_response?(body, id) do
    body |> Sse.parse_all() |> Enum.any?(fn ev -> match?(%{"id" => ^id}, decode(ev.data)) end)
  end

  defp extract_msgs(resp) do
    cond do
      # Req's decode step may have already turned a JSON body into a map/list.
      is_map(resp.body) ->
        [resp.body]

      is_list(resp.body) ->
        Enum.filter(resp.body, &is_map/1)

      resp.body in [nil, ""] ->
        []

      sse?(resp) ->
        resp.body |> Sse.parse_all() |> Enum.map(&decode(&1.data)) |> Enum.reject(&is_nil/1)

      json?(resp) ->
        List.wrap(decode(resp.body)) |> Enum.reject(&is_nil/1)

      true ->
        []
    end
  end

  defp decode(data) do
    case Jason.decode(data) do
      {:ok, msg} when is_map(msg) -> msg
      _ -> nil
    end
  end

  defp capture_session(t, resp) do
    case Req.Response.get_header(resp, "mcp-session-id") do
      [session | _] when session != "" -> %{t | session: session}
      _ -> t
    end
  end

  defp base_headers(t) do
    headers =
      Map.to_list(t.headers || %{}) ++ [{"accept", "application/json, text/event-stream"}]

    headers = if t.session, do: [{"mcp-session-id", t.session} | headers], else: headers

    if t.protocol_version,
      do: [{"mcp-protocol-version", t.protocol_version} | headers],
      else: headers
  end

  # --- legacy SSE fallback (HTTP+SSE, 2024-11-05 style) ------------------------

  defp legacy_connect(t, owner, elicitation?, primary) do
    caller = self()
    {streamer, mref} = spawn_monitor(fn -> stream_get(caller, t) end)

    with {:ok, endpoint} <- await_endpoint(streamer, mref, t),
         t = %{t | mode: :legacy, endpoint: endpoint, streamer: streamer},
         :ok <-
           send_msg(
             t,
             Protocol.request(0, "initialize", Protocol.initialize_params(elicitation?))
           ),
         {:ok, result} <- await_init_response(t) do
      Process.demonitor(mref, [:flush])
      t = %{t | protocol_version: result["protocolVersion"] || Protocol.protocol_version()}

      case send_msg(t, Protocol.initialized_notification()) do
        :ok ->
          # Retarget the stream to the connection process for the rest of the
          # session (connect may run in a short-lived helper).
          send(streamer, {:retarget, owner})
          {:ok, t}

        {:error, reason} ->
          Process.exit(streamer, :kill)
          {:error, {:sse_fallback_failed, primary, reason}}
      end
    else
      {:error, reason} ->
        Process.demonitor(mref, [:flush])
        if Process.alive?(streamer), do: Process.exit(streamer, :kill)
        {:error, {:sse_fallback_failed, primary, reason}}
    end
  end

  defp await_endpoint(streamer, mref, t) do
    receive do
      {:sse_endpoint, endpoint} -> {:ok, URI.merge(t.url, endpoint) |> to_string()}
      {:DOWN, ^mref, :process, ^streamer, reason} -> {:error, {:sse_get_failed, reason}}
    after
      t.timeout -> {:error, :sse_endpoint_timeout}
    end
  end

  defp await_init_response(t) do
    receive do
      {:transport_msg, %{"id" => 0, "result" => result}} -> {:ok, result}
      {:transport_msg, %{"id" => 0, "error" => error}} -> {:error, {:rpc_error, error}}
    after
      t.timeout -> {:error, :initialize_timeout}
    end
  end

  # Long-lived GET stream: parses SSE events and forwards them to the owner —
  # `endpoint` events as {:sse_endpoint, url}, message events as {:transport_msg, msg}.
  defp stream_get(owner, t) do
    headers = Map.to_list(t.headers || %{}) ++ [{"accept", "text/event-stream"}]

    resp =
      Req.get!(
        url: t.url,
        headers: headers,
        retry: false,
        compressed: false,
        into: :self,
        receive_timeout: t.timeout,
        connect_options: [timeout: t.timeout]
      )

    if resp.status not in 200..299, do: exit({:sse_get_status, resp.status})
    stream_recv(owner, resp, Sse.new())
  end

  defp stream_recv(owner, resp, sse) do
    receive do
      {:retarget, pid} ->
        stream_recv(pid, resp, sse)

      message ->
        case Req.parse_message(resp, message) do
          {:ok, parts} ->
            {sse, done?} =
              Enum.reduce(parts, {sse, false}, fn
                {:data, chunk}, {sse, done?} ->
                  {events, sse} = Sse.feed(sse, chunk)
                  Enum.each(events, &dispatch_event(owner, &1))
                  {sse, done?}

                :done, {sse, _} ->
                  {sse, true}

                _, acc ->
                  acc
              end)

            if done?, do: :ok, else: stream_recv(owner, resp, sse)

          {:error, _reason} ->
            :ok

          :unknown ->
            stream_recv(owner, resp, sse)
        end
    end
  end

  defp dispatch_event(owner, %{event: "endpoint", data: data}),
    do: send(owner, {:sse_endpoint, data})

  defp dispatch_event(owner, %{data: data}) do
    case Jason.decode(data) do
      {:ok, msg} when is_map(msg) -> send(owner, {:transport_msg, msg})
      _ -> :ok
    end
  end
end
