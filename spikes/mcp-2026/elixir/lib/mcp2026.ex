defmodule Mcp2026 do
  @moduledoc """
  In-house MCP 2026-07-28 (stateless Streamable HTTP + stdio) client and server. No MCP SDK.

  Wire constants and small helpers shared by every module.
  """

  @protocol_version "2026-07-28"
  def protocol_version, do: @protocol_version
  def meta_version, do: "io.modelcontextprotocol/protocolVersion"
  def meta_client_caps, do: "io.modelcontextprotocol/clientCapabilities"
  def meta_client_info, do: "io.modelcontextprotocol/clientInfo"
  def meta_server_info, do: "io.modelcontextprotocol/serverInfo"
  def skills_ext, do: "io.modelcontextprotocol/skills"

  def code_method_not_found, do: -32601
  def code_invalid_params, do: -32602
  def code_header_mismatch, do: -32020
  def code_unsupported_protocol, do: -32022

  @doc "First text content of a tool result (\"\" if none)."
  def text(%{"content" => [%{"text" => t} | _]}) when is_binary(t), do: t
  def text(_), do: ""

  @doc "The `%Mcp2026.Pending{}` inside an error, or nil."
  def pending_of({:error, %Mcp2026.PendingError{pending: p}}), do: p
  def pending_of(%Mcp2026.PendingError{pending: p}), do: p
  def pending_of(_), do: nil

  @doc false
  def rand_str(n), do: Base.url_encode64(:crypto.strong_rand_bytes(n), padding: false)
end

defmodule Mcp2026.RpcError do
  @moduledoc "A JSON-RPC error returned by a server."
  defexception [:code, :message, :data]

  @doc false
  def to_map(%__MODULE__{} = e) do
    m = %{"code" => e.code, "message" => e.message}
    if e.data == nil, do: m, else: Map.put(m, "data", e.data)
  end
end

defmodule Mcp2026.Pending do
  @moduledoc "Mirrors toolnexus SPEC §10 Request (kind \"authorization\")."
  defstruct [:id, :kind, :prompt, :url, :expires_at]
end

defmodule Mcp2026.PendingError do
  @moduledoc "Returned instead of blocking when the authorizer pauses. Resume with `Mcp2026.OAuth.resume/3`."
  defexception [:pending]
  def message(%{pending: p}), do: "authorization pending: " <> p.prompt
end
