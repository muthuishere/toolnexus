defmodule Toolnexus.Http do
  @moduledoc """
  HTTP / REST tools — declare a remote endpoint as a uniform `Toolnexus.Tool` (SPEC §7).

  Behavior mirrors the JS reference (`js/src/http.ts`):
    1. `{placeholder}`s in the URL are substituted from args (URL-encoded, consumed)
    2. args named in `:query` — or all remaining args on GET — become querystring params
    3. remaining args become the body (json | form | raw) on non-GET/HEAD requests
    4. `${ENV_VAR}` in header values expands from the environment at call time —
       expanded values are never logged
    5. non-2xx ⇒ `is_error` with `"HTTP <status>: <body>"`, otherwise the body text
  """

  alias Toolnexus.{Tool, ToolResult}

  @default_timeout 30_000
  @empty_schema %{"type" => "object", "properties" => %{}, "additionalProperties" => false}
  @placeholder ~r/\{(\w+)\}/
  @env_var ~r/\$\{([A-Z0-9_]+)\}/i

  @doc """
  Wrap an HTTP endpoint as a Tool.

  Options (keyword list or map):
    * `:name`, `:description`, `:method`, `:url` (required)
    * `:headers` — map of header name → value; values may contain `${ENV_VAR}`
    * `:query` — list of arg names sent as querystring instead of the body
    * `:body` — body encoding for non-GET requests: `"json"` (default), `"form"`, `"raw"`
    * `:input_schema` — JSON schema map (default: empty object schema)
    * `:timeout` — request timeout in ms (default 30000)
    * `:result_mode` — `"text"` (default), `"json"`, or `"status+text"`
    * `:req_options` — internal: extra `Req.new/1` options (e.g. `plug:` for tests)
  """
  @spec tool(keyword() | map()) :: Tool.t()
  def tool(opts) do
    opts = Map.new(opts)
    method = opts |> Map.fetch!(:method) |> to_string() |> String.upcase()

    cfg = %{
      method: method,
      url: Map.fetch!(opts, :url),
      headers: Map.get(opts, :headers) || %{},
      query_set: MapSet.new(Map.get(opts, :query) || []),
      body_mode: to_string(Map.get(opts, :body) || "json"),
      result_mode: to_string(Map.get(opts, :result_mode) || "text"),
      timeout: Map.get(opts, :timeout) || @default_timeout,
      req_options: Map.get(opts, :req_options) || []
    }

    %Tool{
      name: Map.fetch!(opts, :name),
      description: Map.fetch!(opts, :description),
      input_schema: Map.get(opts, :input_schema) || @empty_schema,
      source: "http",
      execute: fn args, _ctx -> run(cfg, args || %{}) end
    }
  end

  defp run(cfg, args) do
    # 1. substitute {placeholders} in the URL from args (consumed afterwards)
    {url, rest} = substitute(cfg.url, args)

    # 2. querystring args
    {pairs, rest} = split_query(rest, cfg.query_set, cfg.method)
    qs = URI.encode_query(pairs)
    url = if qs == "", do: url, else: url <> if(String.contains?(url, "?"), do: "&", else: "?") <> qs

    # 3. body
    headers = expand_headers(cfg.headers)
    {headers, body} = build_body(cfg.method, cfg.body_mode, headers, rest)

    req =
      Req.new(
        [
          method: method_atom(cfg.method),
          url: url,
          headers: headers,
          body: body,
          receive_timeout: round(cfg.timeout),
          retry: false,
          decode_body: false
        ] ++ cfg.req_options
      )

    case Req.request(req) do
      {:ok, %Req.Response{status: status, body: rbody}} ->
        text = to_text(rbody)

        if status in 200..299 do
          output =
            case cfg.result_mode do
              "status+text" -> "#{status}\n#{text}"
              "json" -> Jason.encode!(safe_parse(text))
              _ -> text
            end

          %ToolResult{output: output, is_error: false, metadata: %{status: status}}
        else
          %ToolResult{output: "HTTP #{status}: #{text}", is_error: true, metadata: %{status: status}}
        end

      {:error, e} ->
        %ToolResult{output: Exception.message(e), is_error: true}
    end
  rescue
    e -> %ToolResult{output: Exception.message(e), is_error: true}
  end

  # -- URL placeholder substitution (sequential, each key consumed like JS) ----

  defp substitute(url, args) do
    case Regex.run(@placeholder, url) do
      nil ->
        {url, args}

      [whole, key] ->
        val = args |> Map.get(key) |> str() |> encode_uri_component()
        url = String.replace(url, whole, val, global: false)
        substitute(url, Map.delete(args, key))
    end
  end

  # encodeURIComponent parity: unreserved chars plus ! * ' ( )
  defp encode_uri_component(s) do
    URI.encode(s, fn c -> URI.char_unreserved?(c) or c in ~c"!*'()" end)
  end

  defp split_query(args, query_set, method) do
    args
    |> Map.keys()
    |> Enum.sort()
    |> Enum.reduce({[], args}, fn key, {pairs, rest} ->
      if MapSet.member?(query_set, key) or method == "GET" do
        {pairs ++ [{key, str(Map.get(rest, key))}], Map.delete(rest, key)}
      else
        {pairs, rest}
      end
    end)
  end

  defp build_body(method, _mode, headers, _rest) when method in ["GET", "HEAD"],
    do: {headers, nil}

  defp build_body(_method, _mode, headers, rest) when map_size(rest) == 0,
    do: {headers, nil}

  defp build_body(_method, "form", headers, rest) do
    headers = Map.put_new(headers, "Content-Type", "application/x-www-form-urlencoded")
    {headers, rest |> Enum.map(fn {k, v} -> {k, str(v)} end) |> Enum.sort() |> URI.encode_query()}
  end

  defp build_body(_method, "raw", headers, rest), do: {headers, str(Map.get(rest, "body"))}

  defp build_body(_method, _json, headers, rest) do
    headers = Map.put_new(headers, "Content-Type", "application/json")
    {headers, Jason.encode!(rest)}
  end

  @doc false
  # Expand ${ENV_VAR} in header values from the environment (values never logged).
  def expand_headers(headers) do
    Map.new(headers, fn {k, v} ->
      {k, Regex.replace(@env_var, v, fn _, name -> System.get_env(name) || "" end)}
    end)
  end

  defp method_atom(method), do: method |> String.downcase() |> String.to_atom()

  defp safe_parse(text) do
    case Jason.decode(text) do
      {:ok, v} -> v
      {:error, _} -> text
    end
  end

  defp to_text(body) when is_binary(body), do: body
  defp to_text(body) when is_list(body), do: IO.iodata_to_binary(body)
  defp to_text(body), do: Jason.encode!(body)

  defp str(nil), do: ""
  defp str(v) when is_binary(v), do: v
  defp str(v), do: to_string(v)
end
