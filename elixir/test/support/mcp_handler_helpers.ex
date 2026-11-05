defmodule Toolnexus.McpHandlerHelpers do
  @moduledoc false
  # Shared HTTP helpers for the McpHandler suites: run a handler on a real
  # Bandit listener and talk raw streamable HTTP to it.

  def start_handler(handler), do: start_plug({Toolnexus.McpHandler, handler})

  @doc "Serve any plug spec on an ephemeral loopback port; returns its base URL."
  def start_plug(plug) do
    {:ok, pid} =
      Bandit.start_link(
        plug: plug,
        ip: {127, 0, 0, 1},
        port: 0,
        startup_log: false
      )

    {:ok, {_ip, port}} = ThousandIsland.listener_info(pid)

    "http://127.0.0.1:#{port}/"
  end

  @doc "One raw request; returns {status, headers_map, body_string}."
  def raw(method, url, opts \\ []) do
    headers =
      [{"accept", opts[:accept] || "application/json, text/event-stream"}] ++
        if(method == :post,
          do: [{"content-type", opts[:content_type] || "application/json"}],
          else: []
        ) ++
        if(opts[:sid], do: [{"mcp-session-id", opts[:sid]}], else: []) ++
        if(opts[:version], do: [{"mcp-protocol-version", opts[:version]}], else: []) ++
        if(opts[:token], do: [{"authorization", opts[:token]}], else: [])

    # `:headers` apply last: an empty value removes that header.
    headers =
      Enum.reduce(opts[:headers] || [], headers, fn {k, v}, acc ->
        k = String.downcase(k)
        acc = Enum.reject(acc, fn {n, _} -> n == k end)
        if v in [nil, ""], do: acc, else: acc ++ [{k, v}]
      end)

    body =
      cond do
        is_binary(opts[:body]) -> opts[:body]
        opts[:body] != nil -> Jason.encode!(opts[:body])
        true -> ""
      end

    resp =
      Req.request!(
        method: method,
        url: url,
        headers: headers,
        body: body,
        retry: false,
        decode_body: false,
        compressed: false
      )

    hdrs =
      Map.new(resp.headers, fn {k, v} -> {String.downcase(k), Enum.join(List.wrap(v), ",")} end)

    {resp.status, hdrs, resp.body}
  end

  def post(url, body, opts \\ []), do: raw(:post, url, Keyword.put(opts, :body, body))

  def decode({_s, _h, body}), do: Jason.decode!(body)

  def rpc(id, method, params \\ nil) do
    m = %{"jsonrpc" => "2.0", "id" => id, "method" => method}
    if params, do: Map.put(m, "params", params), else: m
  end

  @doc "initialize; returns the session id (or nil)."
  def init_session(url, opts \\ []) do
    caps = if opts[:elicit], do: %{"elicitation" => %{}}, else: %{}

    {200, h, _} =
      post(
        url,
        rpc(1, "initialize", %{"protocolVersion" => "2025-06-18", "capabilities" => caps}),
        opts
      )

    h["mcp-session-id"]
  end

  @doc "Start a streaming request in a task; every chunk is sent to the caller as {:chunk, data}."
  def stream(method, url, opts) do
    parent = self()

    headers =
      [{"accept", opts[:accept] || "application/json, text/event-stream"}] ++
        if(method == :post, do: [{"content-type", "application/json"}], else: []) ++
        if(opts[:sid], do: [{"mcp-session-id", opts[:sid]}], else: []) ++
        if(opts[:token], do: [{"authorization", opts[:token]}], else: [])

    Task.async(fn ->
      Req.request(
        method: method,
        url: url,
        headers: headers,
        body: if(opts[:body], do: Jason.encode!(opts[:body]), else: ""),
        retry: false,
        compressed: false,
        receive_timeout: 10_000,
        into: fn {:data, data}, acc ->
          send(parent, {:chunk, data})
          {:cont, acc}
        end
      )
    end)
  end

  @doc "Wait for an SSE chunk whose data JSON matches `pred`."
  def await_event(pred, timeout \\ 5_000) do
    receive do
      {:chunk, data} ->
        found =
          data
          |> String.split("\n")
          |> Enum.filter(&String.starts_with?(&1, "data: "))
          |> Enum.map(&Jason.decode!(String.replace_prefix(&1, "data: ", "")))
          |> Enum.find(pred)

        found || await_event(pred, timeout)
    after
      timeout -> raise "no matching SSE event"
    end
  end
end
