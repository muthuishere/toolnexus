defmodule Mcp2026.HTTP do
  @moduledoc """
  Minimal HTTP client on OTP `:httpc` (30 s timeout, never follows redirects unless asked).

  HTTPS verifies against the OS trust store plus any `Mcp2026.DevAuthServer` certificate started
  in this VM (dev only), so `ServerAuth.new(issuer: as.issuer)` needs no TLS wiring.
  A request function has the shape `(method, url, headers, body) -> {:ok, resp} | {:error, reason}`
  where `resp = %{status: int, headers: [{lower_name, value}], body: binary}`.
  """

  @timeout 30_000
  @dev_key {__MODULE__, :dev_cacerts}

  def default_timeout, do: @timeout

  @doc false
  def trust_dev_cert(der) do
    :persistent_term.put(@dev_key, [der | :persistent_term.get(@dev_key, [])])
  end

  def request(method, url, headers, body, opts \\ []) do
    hs = for {k, v} <- headers, do: {to_charlist(k), to_charlist(v)}
    {ct, hs} = pop_ct(hs)
    u = to_charlist(url)
    req = if body == nil, do: {u, hs}, else: {u, hs, ct || ~c"application/octet-stream", body}

    http_opts = [timeout: @timeout, connect_timeout: 10_000, autoredirect: false, ssl: ssl_opts()]
    profile = Keyword.get(opts, :profile, :default)

    case :httpc.request(method, req, http_opts, [body_format: :binary], profile) do
      {:ok, {{_, status, _}, rh, rb}} ->
        {:ok, %{status: status, headers: for({k, v} <- rh, do: {to_string(k), to_string(v)}), body: rb}}

      {:error, reason} ->
        {:error, "HTTP #{method} #{url}: #{inspect(reason)}"}
    end
  end

  defp pop_ct(hs) do
    case Enum.split_with(hs, fn {k, _} -> :string.lowercase(k) == ~c"content-type" end) do
      {[{_, v} | _], rest} -> {v, rest}
      {[], rest} -> {nil, rest}
    end
  end

  defp ssl_opts do
    [
      verify: :verify_peer,
      cacerts: :persistent_term.get(@dev_key, []) ++ :public_key.cacerts_get(),
      customize_hostname_check: [match_fun: :public_key.pkix_verify_hostname_match_fun(:https)]
    ]
  end

  def header(%{headers: hs}, name), do: header(hs, name)

  def header(hs, name) when is_list(hs) do
    Enum.find_value(hs, "", fn {k, v} -> if k == name, do: v end)
  end

  @doc "GET and decode a 200 JSON body."
  def get_json(http, url) do
    case http.(:get, url, [{"accept", "application/json"}], nil) do
      {:ok, %{status: 200, body: b}} ->
        case JSON.decode(b) do
          {:ok, v} -> {:ok, v}
          _ -> {:error, "GET #{url}: bad JSON"}
        end

      {:ok, %{status: s}} ->
        {:error, "GET #{url}: HTTP #{s}"}

      {:error, e} ->
        {:error, e}
    end
  end

  @doc "GET following redirects (a headless browser for dev/tests). Returns the final response."
  def get_follow(url, max \\ 10, opts \\ []) do
    with {:ok, r} <- request(:get, url, [], nil, opts) do
      loc = header(r, "location")

      if r.status in 301..308 and loc != "" and max > 0,
        do: get_follow(URI.merge(url, loc) |> to_string(), max - 1, opts),
        else: {:ok, r}
    end
  end

  def default, do: &__MODULE__.request/4
end
