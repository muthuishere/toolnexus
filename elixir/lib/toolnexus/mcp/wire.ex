defmodule Toolnexus.Mcp.Wire do
  @moduledoc """
  MCP 2026-07-28 wire constants (SPEC §2.1) and a minimal HTTP client on OTP
  `:httpc` (no MCP SDK, no redirects followed, never retries).

  A request function has the shape `(method, url, headers, body) ->
  {:ok, %{status, headers, body}} | {:error, reason}` with lower-cased header names.
  HTTPS verifies against the OS trust store plus any certificate registered with
  `trust_cert/1` (tests: a local dev authorization server).
  """

  @protocol_version "2026-07-28"
  @cert_key {__MODULE__, :extra_cacerts}

  def protocol_version, do: @protocol_version
  def meta_version, do: "io.modelcontextprotocol/protocolVersion"
  def meta_client_caps, do: "io.modelcontextprotocol/clientCapabilities"
  def meta_client_info, do: "io.modelcontextprotocol/clientInfo"
  def meta_server_info, do: "io.modelcontextprotocol/serverInfo"
  def skills_ext, do: "io.modelcontextprotocol/skills"

  @doc false
  def rand_str(n), do: Base.url_encode64(:crypto.strong_rand_bytes(n), padding: false)

  @doc """
  The `Mcp-Name` header value (SPEC §2.3): sent verbatim unless it has
  leading/trailing whitespace, a byte outside printable ASCII, or looks like the
  sentinel — then `=?base64?<std-base64>?=`.
  """
  def encode_header(v) do
    safe =
      v == String.trim(v) and
        not (String.starts_with?(v, "=?base64?") and String.ends_with?(v, "?=")) and
        Enum.all?(:binary.bin_to_list(v), &(&1 >= 0x20 and &1 <= 0x7E))

    if safe, do: v, else: "=?base64?" <> Base.encode64(v) <> "?="
  end

  @doc "Trust an extra DER CA certificate for HTTPS in this VM (dev/test only)."
  def trust_cert(der), do: :persistent_term.put(@cert_key, [der | extra_certs()])

  defp extra_certs, do: :persistent_term.get(@cert_key, [])

  @doc "One HTTP request; `timeout` in ms. Never follows redirects."
  def request(method, url, headers, body, timeout \\ 30_000) do
    {ct, hs} =
      Enum.reduce(headers, {nil, []}, fn {k, v}, {ct, acc} ->
        if String.downcase(k) == "content-type",
          do: {to_charlist(v), acc},
          else: {ct, [{to_charlist(k), to_charlist(v)} | acc]}
      end)

    u = to_charlist(url)
    hs = Enum.reverse(hs)
    req = if body == nil, do: {u, hs}, else: {u, hs, ct || ~c"application/octet-stream", body}
    opts = [timeout: timeout, connect_timeout: min(timeout, 10_000), autoredirect: false]
    opts = if String.starts_with?(url, "https:"), do: [{:ssl, ssl_opts()} | opts], else: opts

    case :httpc.request(method, req, opts, body_format: :binary) do
      {:ok, {{_, status, _}, rh, rb}} ->
        {:ok,
         %{
           status: status,
           headers: for({k, v} <- rh, do: {String.downcase(to_string(k)), to_string(v)}),
           body: rb
         }}

      {:error, reason} ->
        {:error, "HTTP #{method |> to_string() |> String.upcase()} #{url}: #{inspect(reason)}"}
    end
  end

  defp ssl_opts do
    [
      verify: :verify_peer,
      cacerts: extra_certs() ++ :public_key.cacerts_get(),
      customize_hostname_check: [match_fun: :public_key.pkix_verify_hostname_match_fun(:https)]
    ]
  end

  @doc "A header value from a response (or header list); `\"\"` when absent."
  def header(%{headers: hs}, name), do: header(hs, name)

  def header(hs, name) when is_list(hs),
    do: Enum.find_value(hs, "", fn {k, v} -> k == name && v end)

  @doc "GET and decode a 200 JSON body."
  def get_json(http, url) do
    case http.(:get, url, [{"accept", "application/json"}], nil) do
      {:ok, %{status: 200, body: b}} ->
        case Jason.decode(b) do
          {:ok, v} -> {:ok, v}
          _ -> {:error, "GET #{url}: bad JSON"}
        end

      {:ok, %{status: s}} ->
        {:error, "GET #{url}: HTTP #{s}"}

      {:error, e} ->
        {:error, e}
    end
  end

  @doc "The default request function (`request/5` with a 30 s timeout)."
  def default, do: &request/4
end
