defmodule Toolnexus.Media.Job do
  @moduledoc false
  # The shared API base (SPEC §7G.8): one job's state plus the HTTP engine every API
  # client (style or built-in) runs on — redirects followed here with one rule set,
  # client headers only to the base origin, every request bounded by the job deadline,
  # 402/429 ⇒ unavailable, polling, progress, input fetches and multipart bodies.

  alias Toolnexus.Media.{Cancelled, Config, Error}

  # §7G.12 D23: every media HTTP request carries this unless the client headers set one.
  @user_agent "toolnexus/#{Mix.Project.config()[:version]} (elixir)"

  @doc false
  def user_agent, do: @user_agent

  defstruct name: nil,
            verb: nil,
            config: %Config{},
            ctx: %{},
            base_origin: nil,
            headers: %{},
            start_ms: 0,
            deadline: nil,
            timeout_ms: 600_000,
            job_id: nil,
            default_mode: "download"

  @redirects [301, 302, 303, 307, 308]
  @max_redirects 10

  @doc false
  def new(name, verb, %Config{} = config, ctx, opts) do
    now = System.monotonic_time(:millisecond)
    timeout = opts[:timeout_ms] || Config.positive(config.loop.timeout_ms, 600_000)
    deadline = now + timeout
    ctx_deadline = (ctx || %{})[:deadline]
    deadline = if is_integer(ctx_deadline), do: min(deadline, ctx_deadline), else: deadline

    %__MODULE__{
      name: name,
      verb: verb,
      config: config,
      ctx: ctx || %{},
      base_origin: opts[:base] && origin(opts[:base]),
      headers: opts[:headers] || %{},
      start_ms: now,
      deadline: deadline,
      timeout_ms: timeout,
      default_mode: opts[:default_mode] || "download"
    }
  end

  # ---- errors ----

  @doc false
  def timeout_error(%__MODULE__{job_id: nil} = job),
    do: %Error{message: "#{job.verb}: timed out after #{job.timeout_ms}ms", status: :timeout}

  def timeout_error(%__MODULE__{} = job),
    do: %Error{
      message: "job #{job.job_id} timed out after #{job.timeout_ms}ms",
      status: :timeout,
      job_id: job.job_id
    }

  @doc false
  def fail(%__MODULE__{} = job, msg),
    do: %Error{message: msg, status: :failed, job_id: job.job_id}

  @doc false
  # §7G.2 / §7G.8: 402, or 429 naming quota/credit/billing/balance ⇒ unavailable.
  def status_error(%__MODULE__{} = job, status, raw) do
    text = body_text(raw)
    capped = cap(text, 1000)

    if status == 402 or (status == 429 and Regex.match?(~r/quota|credit|billing|balance/i, text)) do
      %Error{
        message: "#{job.name}: HTTP #{status}: #{capped}",
        status: :unavailable,
        job_id: job.job_id
      }
    else
      fail(job, "HTTP #{status}: #{capped}")
    end
  end

  defp body_text(raw) when is_binary(raw), do: raw
  defp body_text(nil), do: ""
  defp body_text(raw), do: Jason.encode!(raw)

  @doc false
  # Cap at `n` Unicode code points; "…" appended when truncated.
  def cap(text, n) do
    cps = String.codepoints(text)
    if length(cps) <= n, do: text, else: Enum.join(Enum.take(cps, n)) <> "…"
  end

  # ---- progress / cancellation ----

  @doc false
  def progress(%__MODULE__{} = job, status) do
    payload = %{
      verb: job.verb,
      client: job.name,
      job_id: job.job_id,
      status: status,
      elapsed_ms: System.monotonic_time(:millisecond) - job.start_ms
    }

    cond do
      is_function(job.ctx[:progress], 1) -> job.ctx[:progress].(payload)
      is_function(job.config.loop.on_progress, 1) -> job.config.loop.on_progress.(payload)
      true -> :ok
    end

    :ok
  end

  @doc false
  def cancelled?(%__MODULE__{ctx: ctx}), do: signalled?(ctx[:signal])

  @doc false
  def signalled?(nil), do: false
  def signalled?(pid) when is_pid(pid), do: not Process.alive?(pid)
  def signalled?(f) when is_function(f, 0), do: f.() == true
  def signalled?(_), do: false

  @doc false
  # Wait `interval`, then run `step` until it stops returning `:cont`. After a
  # non-terminal step past the deadline ⇒ the timed-out error.
  def poll(%__MODULE__{} = job, interval, step) do
    Process.sleep(interval)
    if cancelled?(job), do: raise(Cancelled)

    case step.() do
      :cont ->
        if System.monotonic_time(:millisecond) >= job.deadline,
          do: {:error, timeout_error(job)},
          else: poll(job, interval, step)

      other ->
        other
    end
  end

  # ---- HTTP ----

  @doc false
  # `opts`: `:body` (`{:json, term}` | `{:raw, iodata, content_type}`), `:headers`
  # (`false` ⇒ send no client headers at all), `:max_bytes`.
  def send(%__MODULE__{} = job, method, url, opts \\ []) do
    method = method |> to_string() |> String.downcase() |> String.to_atom()

    body =
      case opts[:body] do
        {:json, term} -> {Jason.encode!(term), "application/json"}
        {:raw, data, ctype} -> {IO.iodata_to_binary(data), ctype}
        nil -> nil
      end

    hop(job, method, url, body, opts, 0)
  end

  defp hop(job, method, url, body, opts, hops) do
    headers =
      if opts[:headers] != false and origin(url) == job.base_origin, do: job.headers, else: %{}

    headers =
      case body do
        {_, ctype} -> Map.put(headers, "Content-Type", ctype)
        nil -> headers
      end

    headers =
      if Enum.any?(headers, fn {k, _} -> String.downcase(to_string(k)) == "user-agent" end),
        do: headers,
        else: Map.put(headers, "User-Agent", @user_agent)

    remaining = max(job.deadline - System.monotonic_time(:millisecond), 0)

    if remaining == 0 do
      {:error, timeout_error(job)}
    else
      with {:ok, resp} <-
             request(
               job,
               method,
               url,
               headers,
               body && elem(body, 0),
               remaining,
               opts[:max_bytes]
             ) do
        loc = if resp.status in @redirects, do: resp.headers["location"]

        cond do
          loc in [nil, ""] ->
            {:ok, resp}

          hops >= @max_redirects ->
            {:error, fail(job, "#{job.verb}: too many redirects")}

          true ->
            next = url |> URI.merge(loc) |> URI.to_string()

            {method, body} =
              if resp.status in [301, 302, 303] and method not in [:get, :head],
                do: {:get, nil},
                else: {method, body}

            hop(job, method, next, body, opts, hops + 1)
        end
      end
    end
  end

  defp request(job, method, url, headers, body, remaining, max_bytes) do
    case job.config.http_client do
      f when is_function(f, 1) ->
        req = %{
          method: method,
          url: url,
          headers: headers,
          body: body,
          receive_timeout: remaining
        }

        case f.(req) do
          {:ok, %{status: status} = resp} ->
            {:ok,
             %{
               status: status,
               headers: norm_headers(Map.get(resp, :headers)),
               body: Map.get(resp, :body)
             }}

          {:error, e} ->
            transport_error(job, e)
        end

      _ ->
        req_request(job, method, url, headers, body, remaining, max_bytes)
    end
  end

  defp declared_over?(resp, max_bytes) do
    case Req.Response.get_header(resp, "content-length") do
      [v | _] ->
        case Integer.parse(v) do
          {n, ""} -> n > max_bytes
          _ -> false
        end

      _ ->
        false
    end
  end

  # The halted body: exactly maxBytes + 1 bytes, so every caller's size check rejects it —
  # including a body cut short because Content-Length already announced too much.
  defp over_body(acc, max_bytes) when byte_size(acc) > max_bytes,
    do: binary_part(acc, 0, max_bytes + 1)

  defp over_body(acc, max_bytes), do: acc <> :binary.copy(<<0>>, max_bytes + 1 - byte_size(acc))

  defp req_request(job, method, url, headers, body, remaining, max_bytes) do
    opts =
      [method: method, url: url, headers: Map.to_list(headers), retry: false, decode_body: false] ++
        if(body, do: [body: body], else: [])

    opts =
      if max_bytes do
        # D14: read at most maxBytes + 1 bytes; a Content-Length over it stops at once.
        Keyword.put(opts, :into, fn {:data, data}, {req, resp} ->
          acc = resp.body <> data
          resp = %{resp | body: acc}

          if byte_size(acc) > max_bytes or declared_over?(resp, max_bytes),
            do: {:halt, {req, %{resp | body: over_body(acc, max_bytes)}}},
            else: {:cont, {req, resp}}
        end)
      else
        opts
      end

    connect = Keyword.put(job.config.http_options[:connect_options] || [], :timeout, remaining)

    # Host http_options first; the job-deadline bound and `redirect: false` (we follow
    # redirects ourselves) are applied after, so a host option cannot override them.
    opts =
      opts
      |> Keyword.merge(job.config.http_options)
      |> Keyword.put(:redirect, false)
      |> Keyword.put(:receive_timeout, remaining)
      |> Keyword.put(:connect_options, connect)

    case Req.request(opts) do
      {:ok, %Req.Response{status: status, headers: h, body: b}} ->
        {:ok, %{status: status, headers: norm_headers(h), body: b}}

      {:error, e} ->
        transport_error(job, e)
    end
  end

  defp transport_error(job, e) do
    if timeout_error?(e) or System.monotonic_time(:millisecond) >= job.deadline,
      do: {:error, timeout_error(job)},
      else: {:error, fail(job, "#{job.verb}: #{err_text(e)}")}
  end

  defp timeout_error?(%{reason: :timeout}), do: true
  defp timeout_error?(:timeout), do: true
  defp timeout_error?(_), do: false

  defp err_text(e) when is_exception(e), do: Exception.message(e)
  defp err_text(e), do: inspect(e)

  defp norm_headers(nil), do: %{}

  defp norm_headers(h) do
    Map.new(h, fn {k, v} ->
      v =
        case v do
          [first | _] -> to_string(first)
          v -> to_string(v)
        end

      {String.downcase(to_string(k)), v}
    end)
  end

  @doc false
  def origin(url) do
    u = URI.parse(url)
    {u.scheme && String.downcase(u.scheme), u.host && String.downcase(u.host), u.port}
  end

  @doc false
  # send + status check; `{:ok, resp}` for 2xx.
  def checked(job, method, url, opts \\ []) do
    with {:ok, resp} <- send(job, method, url, opts) do
      if resp.status in 200..299,
        do: {:ok, resp},
        else: {:error, status_error(job, resp.status, resp.body)}
    end
  end

  @doc false
  # checked + JSON decode ⇒ `{:ok, json, resp}`.
  def json(job, method, url, opts \\ []) do
    with {:ok, resp} <- checked(job, method, url, opts) do
      case decode(resp.body) do
        {:ok, j} -> {:ok, j, resp}
        :error -> {:error, fail(job, "#{job.verb}: response is not JSON")}
      end
    end
  end

  @doc false
  def decode(raw) when is_binary(raw) do
    case Jason.decode(raw) do
      {:ok, j} -> {:ok, j}
      _ -> :error
    end
  end

  def decode(raw) when is_map(raw) or is_list(raw), do: {:ok, raw}
  def decode(_), do: :error

  @doc false
  # The key: config `key`, else the env var at call time. `nil` ⇒ unavailable.
  def key(%Config{key: k}, _env) when is_binary(k) and k != "", do: k

  def key(_config, env) do
    case System.get_env(env) do
      v when is_binary(v) and v != "" -> v
      _ -> nil
    end
  end

  @doc false
  def no_key(name, env), do: Error.unavailable("#{name}: no API key (set #{env})")

  @doc false
  def base_url(%Config{url: u}, _default) when is_binary(u) and u != "",
    do: String.trim_trailing(u, "/")

  def base_url(_config, default), do: default

  @doc false
  # RFC 3986: keep A-Z a-z 0-9 - . _ ~, percent-encode every other byte (uppercase hex).
  def pct(s), do: URI.encode(to_string(s), &URI.char_unreserved?/1)

  # ---- inputs (§7G.1) ----

  @doc false
  def read_input(%__MODULE__{} = job, value) do
    value = to_string(value)

    fail = fn reason ->
      {:error, fail(job, ~s(#{job.verb}: cannot read "#{value}": #{reason}))}
    end

    cond do
      String.starts_with?(value, ["http://", "https://"]) ->
        max = Config.positive(job.config.gather.max_bytes, 104_857_600)

        case send(job, :get, value, headers: false, max_bytes: max) do
          {:ok, %{status: s, body: b}} when s in 200..299 and is_binary(b) ->
            if byte_size(b) > max,
              do: fail.("file exceeds gather.maxBytes (#{max} bytes)"),
              else: {:ok, input(URI.parse(value).path || "", b)}

          {:ok, %{status: s}} ->
            fail.("HTTP #{s}")

          {:error, %Error{status: :timeout}} = err ->
            err

          {:error, %Error{message: m}} ->
            fail.(m)
        end

      true ->
        path = String.replace_prefix(value, "file://", "")
        max = Config.positive(job.config.gather.max_bytes, 104_857_600)

        # D14: a local file over maxBytes fails before any request is made.
        case File.stat(path) do
          {:ok, %File.Stat{size: size}} when size > max ->
            fail.("file exceeds gather.maxBytes (#{max} bytes)")

          _ ->
            read_local(path, max, fail)
        end
    end
  end

  defp read_local(path, max, fail) do
    case File.read(path) do
      {:ok, b} when byte_size(b) > max -> fail.("file exceeds gather.maxBytes (#{max} bytes)")
      {:ok, b} -> {:ok, input(path, b)}
      {:error, r} -> fail.(:file.format_error(r))
    end
  end

  defp input(path, bytes) do
    name =
      case path |> String.split("/") |> List.last() do
        nil -> "input"
        "" -> "input"
        n -> n
      end

    %{
      filename: name,
      content_type: Toolnexus.Media.Gather.mime_of(name) || "application/octet-stream",
      bytes: bytes
    }
  end

  @doc false
  # parts: {:field, name, value} | {:file, name, %{filename, content_type, bytes}}
  def multipart(parts) do
    boundary = "toolnexus-" <> Base.encode16(:crypto.strong_rand_bytes(12), case: :lower)

    body =
      Enum.map(parts, fn
        {:field, name, value} ->
          [
            "--",
            boundary,
            "\r\nContent-Disposition: form-data; name=\"",
            name,
            "\"\r\n\r\n",
            to_string(value),
            "\r\n"
          ]

        {:file, name, f} ->
          [
            "--",
            boundary,
            "\r\nContent-Disposition: form-data; name=\"",
            name,
            "\"; filename=\"",
            f.filename,
            "\"\r\nContent-Type: ",
            f.content_type,
            "\r\n\r\n",
            f.bytes,
            "\r\n"
          ]
      end) ++ ["--", boundary, "--\r\n"]

    {:raw, body, "multipart/form-data; boundary=" <> boundary}
  end

  @doc false
  # providerOptions (function face only): overwrite JSON body keys last.
  def merge_options(body, %{provider_options: po}) when is_map(po), do: Map.merge(body, po)
  def merge_options(body, _ctx), do: body

  @doc false
  # providerOptions for multipart: add/replace fields.
  def merge_fields(parts, %{provider_options: po}) when is_map(po) and map_size(po) > 0 do
    {replaced, rest} =
      Enum.map_reduce(parts, po, fn
        {:field, name, _} = p, acc ->
          case Map.pop(acc, name) do
            {nil, acc} -> {p, acc}
            {v, acc} -> {{:field, name, v}, acc}
          end

        p, acc ->
          {p, acc}
      end)

    replaced ++ Enum.map(Enum.sort(rest), fn {k, v} -> {:field, k, v} end)
  end

  def merge_fields(parts, _ctx), do: parts
end
