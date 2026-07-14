defmodule Toolnexus.Mcp.Protocol do
  @moduledoc """
  Pure JSON-RPC 2.0 codec for the in-house MCP client (SPEC §2).

  Framing/correlation helpers plus the MCP methods toolnexus speaks:
  `initialize`, `notifications/initialized`, `tools/list` (cursor pagination),
  `tools/call`, inbound `ping`, and the inbound `elicitation/create` bridge
  onto §10 `Request`/`Answer` (mirrors js/src/mcp.ts `elicitationToRequest` /
  `answerToElicitResult` exactly).
  """

  alias Toolnexus.{Answer, Request}

  @protocol_version "2025-06-18"
  @jsonrpc "2.0"

  @doc "The MCP protocol version this client advertises."
  def protocol_version, do: @protocol_version

  @doc "clientInfo version — the toolnexus app version from mix."
  def client_version do
    case Application.spec(:toolnexus, :vsn) do
      nil -> "0.0.0"
      vsn -> to_string(vsn)
    end
  end

  # --- framing -------------------------------------------------------------

  def request(id, method, params \\ nil) do
    msg = %{"jsonrpc" => @jsonrpc, "id" => id, "method" => method}
    if params == nil, do: msg, else: Map.put(msg, "params", params)
  end

  def notification(method, params \\ nil) do
    msg = %{"jsonrpc" => @jsonrpc, "method" => method}
    if params == nil, do: msg, else: Map.put(msg, "params", params)
  end

  def response(id, result), do: %{"jsonrpc" => @jsonrpc, "id" => id, "result" => result}

  def error_response(id, code, message),
    do: %{"jsonrpc" => @jsonrpc, "id" => id, "error" => %{"code" => code, "message" => message}}

  @doc "Encode a message as one newline-delimited JSON line (stdio framing)."
  def encode_line(msg), do: Jason.encode!(msg) <> "\n"

  @doc """
  Classify a decoded inbound message:
  `{:request, id, method, params}` (server→client request, e.g. ping/elicitation),
  `{:response, id, {:ok, result} | {:error, error}}`, `{:notification, method, params}`,
  or `:unknown`. Unknown notifications are tolerated silently by the caller.
  """
  def classify(%{"method" => method} = msg) do
    case msg do
      %{"id" => id} -> {:request, id, method, msg["params"] || %{}}
      _ -> {:notification, method, msg["params"] || %{}}
    end
  end

  def classify(%{"id" => id} = msg) do
    cond do
      Map.has_key?(msg, "result") -> {:response, id, {:ok, msg["result"]}}
      Map.has_key?(msg, "error") -> {:response, id, {:error, msg["error"]}}
      true -> :unknown
    end
  end

  def classify(_), do: :unknown

  # --- methods ---------------------------------------------------------------

  @doc """
  `initialize` params. When `elicitation?` the client advertises the
  `elicitation` capability so servers may elicit (§2 elicitation bridge);
  without a waitFor the capability is not advertised and a compliant server
  will not elicit.
  """
  def initialize_params(elicitation?) do
    %{
      "protocolVersion" => @protocol_version,
      "capabilities" => if(elicitation?, do: %{"elicitation" => %{}}, else: %{}),
      "clientInfo" => %{"name" => "toolnexus", "version" => client_version()}
    }
  end

  def initialized_notification, do: notification("notifications/initialized")

  def tools_list_request(id, cursor),
    do: request(id, "tools/list", if(cursor, do: %{"cursor" => cursor}, else: %{}))

  def tools_call_request(id, name, args),
    do: request(id, "tools/call", %{"name" => name, "arguments" => args || %{}})

  @doc "Reply to an inbound `ping`."
  def ping_response(id), do: response(id, %{})

  # --- elicitation bridge (§2 + §10; mirrors js/src/mcp.ts) --------------------

  @doc """
  Map an MCP `elicitation/create` request onto a §10 `Request`:
  form mode → `kind:"input"` (with `requestedSchema` carried in `data.schema`);
  URL mode (`mode == "url"`) → `kind:"authorization"` with `url`.
  """
  def elicitation_to_request(params) when is_map(params) do
    is_url = params["mode"] == "url"

    req = %Request{
      id: elc_id(),
      kind: if(is_url, do: "authorization", else: "input"),
      prompt: to_string(params["message"] || "")
    }

    cond do
      is_url && params["url"] not in [nil, ""] ->
        %{req | url: params["url"]}

      !is_url && params["requestedSchema"] != nil ->
        %{req | data: %{"schema" => params["requestedSchema"]}}

      true ->
        req
    end
  end

  @doc """
  Map a resolved §10 `Answer` back onto an MCP elicit result:
  `ok` → accept (with `answer.data` as content); `reason=="declined"` → decline;
  everything else → cancel.
  """
  def answer_to_elicit_result(%Answer{ok: true, data: data}),
    do: %{"action" => "accept", "content" => data || %{}}

  def answer_to_elicit_result(%Answer{reason: "declined"}), do: %{"action" => "decline"}
  def answer_to_elicit_result(%Answer{}), do: %{"action" => "cancel"}

  defp elc_id do
    millis = System.system_time(:millisecond) |> Integer.to_string(36) |> String.downcase()
    "elc-#{millis}-#{:erlang.unique_integer([:positive, :monotonic])}"
  end
end
