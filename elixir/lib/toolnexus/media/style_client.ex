defmodule Toolnexus.Media.StyleClient do
  @moduledoc """
  A media client over a JSON style (SPEC §7G.7) — the engine every style-defined API
  client runs on. Build one with `Toolnexus.Media.style_client/2`; `Toolnexus.Media.A2E`
  is one over the embedded a2e preset.
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Config, Error, Gather, Job, Result}

  defstruct style: %{}, config: %Config{}, name: "style", key_env: nil, default_mode: "url"

  @type t :: %__MODULE__{}

  @env_var ~r/\$\{([A-Za-z0-9_]+)\}/

  @doc "Read one style JSON file. `{:ok, style}` or `{:error, %Toolnexus.Media.Error{}}`."
  @spec load_style(Path.t()) :: {:ok, map()} | {:error, Error.t()}
  def load_style(path) do
    with {:ok, text} <- File.read(path),
         {:ok, %{} = style} <- Jason.decode(text),
         :ok <- validate(style) do
      {:ok, style}
    else
      {:error, %Error{}} = err ->
        err

      {:error, %Jason.DecodeError{} = e} ->
        {:error, Error.failed("media: #{path}: #{Exception.message(e)}")}

      {:error, reason} when is_atom(reason) ->
        {:error, Error.failed("media: #{path}: #{:file.format_error(reason)}")}

      {:ok, _} ->
        {:error, Error.failed("media: #{path}: style must be a JSON object")}
    end
  end

  @doc "Make a style client (`{:ok, client}` or `{:error, %Error{}}` for an invalid style)."
  @spec new(map(), keyword() | map()) :: {:ok, t()} | {:error, Error.t()}
  def new(style, config \\ []) do
    with :ok <- validate(style) do
      {:ok, %__MODULE__{style: style, config: Config.new(config), name: style["name"] || "style"}}
    end
  end

  @doc "`new/2`, raising `Toolnexus.Media.Error` for an invalid style."
  @spec new!(map(), keyword() | map()) :: t()
  def new!(style, config \\ []) do
    case new(style, config) do
      {:ok, c} -> c
      {:error, e} -> raise e
    end
  end

  # §7G.7 / §7G.8: an unknown verb, or an args entry mapped to "", is a style error.
  defp validate(style) do
    verbs = style["verbs"] || %{}

    unknown =
      verbs |> Map.keys() |> Enum.sort() |> Enum.find(&(&1 not in Toolnexus.Media.verb_names()))

    empty =
      for {_verb, spec} <- Enum.sort(verbs),
          is_map(spec),
          {k, ""} <- Enum.sort(spec["args"] || %{}),
          do: k

    cond do
      unknown -> {:error, Error.failed(~s(style: unknown verb "#{unknown}"))}
      empty != [] -> {:error, Error.failed(~s(style: empty path for argument "#{hd(empty)}"))}
      true -> :ok
    end
  end

  @impl true
  def name(%__MODULE__{name: n}), do: n

  @impl true
  def supports?(%__MODULE__{style: s}, verb), do: is_map((s["verbs"] || %{})[verb])

  @impl true
  def args(%__MODULE__{style: s}, verb) do
    spec = (s["verbs"] || %{})[verb] || %{}
    mapped = spec["args"] || %{}
    extra = spec["required"] || []

    for a <- Toolnexus.Media.canonical_args(verb),
        Map.has_key?(mapped, a.name) or a.name in extra do
      %{name: a.name, required: (a.required and Map.has_key?(mapped, a.name)) or a.name in extra}
    end
  end

  @impl true
  def image(c, args, ctx), do: run(c, "image", args, ctx)
  @impl true
  def video(c, args, ctx), do: run(c, "video", args, ctx)
  @impl true
  def speech(c, args, ctx), do: run(c, "speech", args, ctx)
  @impl true
  def music(c, args, ctx), do: run(c, "music", args, ctx)
  @impl true
  def transcribe(c, args, ctx), do: run(c, "transcribe", args, ctx)
  @impl true
  def clone_voice(c, args, ctx), do: run(c, "cloneVoice", args, ctx)
  @impl true
  def talk(c, args, ctx), do: run(c, "talk", args, ctx)

  # ---- engine (§7G.7) ----

  defp run(%__MODULE__{} = c, verb, args, ctx) do
    style = c.style
    spec = style["verbs"][verb]
    config = c.config
    key = if c.key_env, do: Job.key(config, c.key_env), else: config.key

    if c.key_env && key == nil do
      {:error, Job.no_key(c.name, c.key_env)}
    else
      poll = spec["poll"] || %{}

      timeout =
        Config.positive(config.loop.timeout_ms, Config.positive(poll["timeoutMs"], 600_000))

      interval =
        Config.positive(config.loop.interval_ms, Config.positive(poll["intervalMs"], 5000))

      base = Job.base_url(config, String.trim_trailing(style["baseUrl"] || "", "/"))

      headers =
        Map.new(style["headers"] || %{}, fn {k, v} ->
          {k,
           Regex.replace(@env_var, v, fn _, var ->
             if is_binary(key), do: key, else: System.get_env(var) || ""
           end)}
        end)

      job =
        Job.new(c.name, verb, config, ctx,
          base: base,
          headers: headers,
          timeout_ms: timeout,
          default_mode: c.default_mode
        )

      args = Map.reject(args, fn {_k, v} -> is_nil(v) end)

      body =
        args
        |> Enum.filter(fn {k, _} -> Map.has_key?(spec["args"] || %{}, k) end)
        |> Enum.reduce(spec["defaults"] || %{}, fn {k, v}, acc ->
          put_path(acc, spec["args"][k], v)
        end)
        |> Job.merge_options(ctx)

      start = spec["start"] || %{}

      with {:ok, resp} <-
             call(job, style, start["method"] || "POST", base <> (start["path"] || ""), body) do
        case spec["mode"] do
          "async" -> poll(job, style, spec, resp, interval)
          _ -> result(job, spec, resp, %Result{status: "completed"})
        end
      end
    end
  end

  defp call(job, style, method, url, body) do
    opts = if body, do: [body: {:json, body}], else: []

    with {:ok, j, _resp} <- Job.json(job, method, url, opts) do
      envelope(job, style["errorEnvelope"], j)
    end
  end

  defp poll(job, style, spec, resp, interval) do
    p = spec["poll"] || %{}
    id_path = spec["idPath"]

    case read_path(resp, id_path) do
      {:ok, id}
      when (is_binary(id) and id != "") or is_integer(id) or (is_float(id) and id == trunc(id)) ->
        id = render(id)
        job = %{job | job_id: id}
        path = String.replace(p["path"] || "", "{id}", Job.pct(id))
        url = Job.base_url(job.config, String.trim_trailing(style["baseUrl"] || "", "/")) <> path

        Job.poll(job, interval, fn ->
          with {:ok, r} <- call(job, style, p["method"] || "GET", url, nil) do
            status =
              case read_path(r, p["statusPath"]) do
                {:ok, s} when is_binary(s) -> s
                {:ok, s} when is_number(s) -> render(s)
                _ -> ""
              end

            Job.progress(job, status)

            cond do
              status in (p["done"] || []) ->
                result(job, spec, r, %Result{status: status, job_id: id})

              status in (p["failed"] || []) ->
                msg =
                  case read_path(r, p["errorPath"]) do
                    {:ok, m} when is_binary(m) and m != "" -> ": " <> m
                    _ -> ""
                  end

                {:error, Job.fail(job, "job #{id} #{status}#{msg}")}

              true ->
                :cont
            end
          end
        end)

      _ ->
        {:error, Job.fail(job, "#{job.verb}: no job id at #{id_path}")}
    end
  end

  defp result(job, spec, resp, %Result{} = r) do
    path = spec["resultPath"]
    found = read_path(resp, path)
    verb = job.verb

    case spec["kind"] do
      "text" ->
        case found do
          {:ok, t} when is_binary(t) or is_number(t) -> {:ok, %{r | text: render(t), files: []}}
          _ -> {:error, Job.fail(job, "#{verb}: no result at #{path}")}
        end

      kind ->
        urls =
          case found do
            {:ok, u} when is_binary(u) -> [u]
            {:ok, l} when is_list(l) -> l |> List.flatten() |> Enum.filter(&is_binary/1)
            _ -> []
          end
          |> Enum.reject(&(&1 == ""))

        if urls == [] do
          {:error, Job.fail(job, "#{verb}: no result at #{path}")}
        else
          urls
          |> Enum.reduce_while({:ok, []}, fn u, {:ok, acc} ->
            case Gather.collect(job, {:url, u, kind}) do
              {:ok, f} -> {:cont, {:ok, acc ++ [f]}}
              err -> {:halt, err}
            end
          end)
          |> case do
            {:ok, files} -> {:ok, %{r | files: files}}
            err -> err
          end
        end
    end
  end

  defp envelope(_job, nil, json), do: {:ok, json}

  defp envelope(job, env, json) do
    case read_path(json, env["codePath"]) do
      {:ok, code} ->
        if Enum.any?(env["okValues"] || [], &(&1 == code)) do
          {:ok, json}
        else
          msg =
            case read_path(json, env["messagePath"]) do
              {:ok, m} when is_binary(m) -> m
              _ -> ""
            end

          {:error, Job.fail(job, "API error #{render(code)}: #{msg}")}
        end

      :absent ->
        {:ok, json}
    end
  end

  # ---- values ----

  @doc false
  def render(s) when is_binary(s), do: s
  def render(n) when is_integer(n), do: Integer.to_string(n)
  def render(f) when is_float(f) and f == trunc(f), do: Integer.to_string(trunc(f))
  def render(f) when is_float(f), do: to_string(f)
  def render(other), do: Jason.encode!(other)

  @doc false
  # dotPath read: `{:ok, value}` or `:absent`.
  def read_path(_v, nil), do: :absent
  # §7G.8: an empty dotPath is the whole value.
  def read_path(v, ""), do: {:ok, v}
  def read_path(v, path), do: do_read(v, String.split(path, "."))

  defp do_read(v, []), do: {:ok, v}

  defp do_read(m, [seg | rest]) when is_map(m) do
    case Map.fetch(m, seg) do
      {:ok, v} -> do_read(v, rest)
      :error -> :absent
    end
  end

  defp do_read(l, [seg | rest]) when is_list(l) do
    if digits?(seg) do
      case Enum.fetch(l, String.to_integer(seg)) do
        {:ok, v} -> do_read(v, rest)
        :error -> :absent
      end
    else
      :absent
    end
  end

  defp do_read(_, _), do: :absent

  @doc false
  # dotPath write: creates objects, and arrays when the next segment is numeric
  # (an existing object is kept; a numeric segment is then a string key).
  def put_path(container, path, value), do: do_put(container, String.split(path, "."), value)

  defp do_put(_c, [], value), do: value

  defp do_put(c, [seg | rest], value) do
    if digits?(seg) and not is_map(c) do
      list = if is_list(c), do: c, else: []
      i = String.to_integer(seg)

      list =
        if length(list) <= i, do: list ++ List.duplicate(nil, i + 1 - length(list)), else: list

      List.replace_at(list, i, do_put(Enum.at(list, i), rest, value))
    else
      map = if is_map(c), do: c, else: %{}
      Map.put(map, seg, do_put(Map.get(map, seg), rest, value))
    end
  end

  defp digits?(seg), do: seg != "" and String.match?(seg, ~r/^\d+$/)
end
