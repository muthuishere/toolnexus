defmodule Toolnexus.Media.Result do
  @moduledoc """
  The outcome of one media verb (SPEC §7G `MediaResult`).

  `files` is always a list of `Toolnexus.ContentPart` (`type`, `mime_type`, `url`);
  `text` is set only for `kind: "text"` verbs; `job_id` only for async verbs.
  """
  defstruct files: [], text: nil, job_id: nil, status: "completed"

  @type t :: %__MODULE__{
          files: [Toolnexus.ContentPart.t()],
          text: String.t() | nil,
          job_id: String.t() | nil,
          status: String.t()
        }
end

defmodule Toolnexus.Media.Cancelled do
  @moduledoc "Raised by the function face when a media call's `:signal` reports cancellation (§7G)."
  defexception message: "media: cancelled"
end

defmodule Toolnexus.Media do
  @moduledoc """
  Media generation (SPEC §7G, source `"media"`): image, video, speech, transcription,
  voice cloning and talking-head video behind one engine with two faces — the typed
  verbs below (function face) and `tools/1` (tool face, for an LLM).

  Providers are **styles**: JSON data loaded with `load_style/1` (or passed as a
  string-keyed map). The library names no vendor; presets live in
  `examples/media-styles/`.

      {:ok, style} = Toolnexus.Media.load_style("examples/media-styles/some.json")
      media = Toolnexus.Media.new(style, on_progress: &IO.inspect/1)
      {:ok, %Toolnexus.Media.Result{files: files}} = Toolnexus.Media.image(media, prompt: "a cat")

      Toolnexus.create_toolkit!(media: media)   # registers generate_image, ...

  **Arguments** are a map or keyword list of the normalised args (`verbs.json`). Atom
  keys may be snake_case (`:aspect_ratio` ⇒ `"aspectRatio"`). `:provider_options`
  (or `"providerOptions"`) is a map whose top-level keys overwrite the request body
  last; the tool face never exposes it.

  **Cancellation** is the process idiom: run the call in a `Task` and
  `Task.shutdown/1` it (or `Task.await/2` with a timeout). The tool face also honours
  the context's cooperative `signal` between polls, and the function face accepts a
  `:signal` (pid or zero-arity predicate) the same way: the function face raises
  `Toolnexus.Media.Cancelled`, the tool face returns `is_error` with its message.
  """

  alias Toolnexus.{ContentPart, Context, Tool, ToolResult}
  alias Toolnexus.Media.Result

  defstruct styles: [], on_progress: nil, transport: nil, http_options: []

  @type t :: %__MODULE__{
          styles: [map()],
          on_progress: (map() -> any()) | nil,
          transport: (map() -> {:ok, map()} | {:error, term()}) | nil,
          http_options: keyword()
        }

  # verbs.json — embedded verbatim (order, names, types, descriptions, required).
  @verbs [
    %{
      verb: "image",
      tool: "generate_image",
      description: "Generate an image from a text prompt.",
      args: [
        %{name: "prompt", type: "string", description: "What to draw.", required: true},
        %{name: "width", type: "integer", description: "Width in pixels."},
        %{name: "height", type: "integer", description: "Height in pixels."},
        %{name: "aspectRatio", type: "string", description: "Aspect ratio, e.g. 16:9."},
        %{name: "seed", type: "integer", description: "Seed for reproducible output."}
      ]
    },
    %{
      verb: "video",
      tool: "generate_video",
      description:
        "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
      args: [
        %{
          name: "prompt",
          type: "string",
          description: "What should happen in the video.",
          required: true
        },
        %{name: "image", type: "string", description: "URL of the starting image."},
        %{name: "seconds", type: "integer", description: "Video length in seconds."},
        %{name: "aspectRatio", type: "string", description: "Aspect ratio, e.g. 9:16."}
      ]
    },
    %{
      verb: "speech",
      tool: "generate_speech",
      description: "Speak text aloud and return the audio.",
      args: [
        %{name: "text", type: "string", description: "The text to speak.", required: true},
        %{name: "voice", type: "string", description: "Voice or speaker id."}
      ]
    },
    %{
      verb: "transcribe",
      tool: "transcribe_audio",
      description: "Transcribe speech in an audio URL to text.",
      args: [%{name: "audio", type: "string", description: "URL of the audio.", required: true}]
    },
    %{
      verb: "cloneVoice",
      tool: "clone_voice",
      description: "Create a cloned voice from audio sample URLs and return its voice id.",
      args: [
        %{name: "name", type: "string", description: "Name for the new voice.", required: true},
        %{
          name: "samples",
          type: "array",
          items: "string",
          description: "URLs of audio samples of the voice.",
          required: true
        }
      ]
    },
    %{
      verb: "talking",
      tool: "talking_video",
      description: "Make the person in an image speak the given audio (lip-synced video).",
      args: [
        %{name: "image", type: "string", description: "URL of the face image.", required: true},
        %{name: "audio", type: "string", description: "URL of the speech audio.", required: true}
      ]
    }
  ]

  @mime %{
    "png" => "image/png",
    "jpg" => "image/jpeg",
    "jpeg" => "image/jpeg",
    "webp" => "image/webp",
    "gif" => "image/gif",
    "mp4" => "video/mp4",
    "webm" => "video/webm",
    "mov" => "video/quicktime",
    "mp3" => "audio/mpeg",
    "wav" => "audio/wav",
    "ogg" => "audio/ogg",
    "m4a" => "audio/mp4",
    "flac" => "audio/flac"
  }

  @env_var ~r/\$\{([A-Za-z0-9_]+)\}/

  @doc "The canonical verb table (`conformance/media/verbs.json`)."
  @spec verbs() :: [map()]
  def verbs, do: @verbs

  @doc "Read one style JSON file. Returns `{:ok, style}` (string-keyed map) or `{:error, msg}`."
  @spec load_style(Path.t()) :: {:ok, map()} | {:error, String.t()}
  def load_style(path) do
    with {:ok, text} <- File.read(path),
         {:ok, %{} = style} <- Jason.decode(text) do
      {:ok, style}
    else
      {:error, %Jason.DecodeError{} = e} ->
        {:error, "media: #{path}: #{Exception.message(e)}"}

      {:error, reason} when is_atom(reason) ->
        {:error, "media: #{path}: #{:file.format_error(reason)}"}

      {:ok, _} ->
        {:error, "media: #{path}: style must be a JSON object"}
    end
  end

  @doc """
  Build a media engine from one style or a list (a verb resolves to the FIRST style
  that declares it).

  Options: `:on_progress` — `fn %{verb, job_id, status} -> ... end` per poll;
  `:transport` — the same injectable seam as `Toolnexus.Client` (`fn request ->
  {:ok, %{status, headers, body}} | {:error, e}`, `request = %{method, url, headers,
  body, receive_timeout}` with `body` the un-marshalled JSON map, `nil` on polls);
  `:http_options` — extra `Req` options (e.g. `plug:` in tests).
  """
  @spec new(map() | [map()], keyword() | map()) :: t()
  def new(styles, opts \\ []) do
    opts = Map.new(opts)

    %__MODULE__{
      styles: if(is_list(styles), do: styles, else: [styles]),
      on_progress: opts[:on_progress],
      transport: opts[:transport],
      http_options: opts[:http_options] || []
    }
  end

  @doc "Generate an image (`prompt`, `width`, `height`, `aspectRatio`, `seed`)."
  def image(media, args, opts \\ []), do: generate(media, "image", args, opts)
  @doc "Generate a video (`prompt`, `image`, `seconds`, `aspectRatio`)."
  def video(media, args, opts \\ []), do: generate(media, "video", args, opts)
  @doc "Text to speech (`text`, `voice`)."
  def speech(media, args, opts \\ []), do: generate(media, "speech", args, opts)
  @doc "Speech to text (`audio`); the result's `text` is set."
  def transcribe(media, args, opts \\ []), do: generate(media, "transcribe", args, opts)
  @doc "Clone a voice (`name`, `samples`)."
  def clone_voice(media, args, opts \\ []), do: generate(media, "cloneVoice", args, opts)
  @doc "Talking-head video (`image`, `audio`)."
  def talking(media, args, opts \\ []), do: generate(media, "talking", args, opts)

  @doc """
  Run any verb (`"image"`, `:clone_voice`, ...) with normalised args.
  Options: `:signal` — cooperative cancellation (pid or zero-arity predicate).
  """
  @spec generate(t(), String.t() | atom(), map() | keyword(), keyword()) ::
          {:ok, Result.t()} | {:error, String.t()}
  def generate(%__MODULE__{} = media, verb, args, opts \\ []) do
    {provider_options, args} = split_args(args)
    run(media, verb_name(verb), args, provider_options, opts[:signal])
  end

  @doc "One `Tool` (source `\"media\"`) per verb the styles support, in `verbs.json` order."
  @spec tools(t()) :: [Tool.t()]
  def tools(%__MODULE__{} = media) do
    for v <- @verbs, spec = resolve(media, v.verb), spec != nil do
      mapped = spec["args"] || %{}
      args = Enum.filter(v.args, &Map.has_key?(mapped, &1.name))

      %Tool{
        name: v.tool,
        description: v.description,
        source: "media",
        input_schema: %{
          "type" => "object",
          "properties" => Map.new(args, &{&1.name, arg_schema(&1)}),
          "required" => for(a <- args, a[:required], do: a.name)
        },
        execute: fn tool_args, ctx -> execute(media, v.verb, tool_args, ctx) end
      }
    end
  end

  defp arg_schema(%{type: "array"} = a),
    do: %{"type" => "array", "items" => %{"type" => a.items}, "description" => a.description}

  defp arg_schema(a), do: %{"type" => a.type, "description" => a.description}

  defp execute(media, verb, args, ctx) do
    signal = if is_struct(ctx, Context), do: ctx.signal

    outcome =
      try do
        run(media, verb, stringify(args || %{}), nil, signal)
      rescue
        e in Toolnexus.Media.Cancelled -> {:error, Exception.message(e)}
      end

    case outcome do
      {:ok, %Result{} = r} ->
        meta = %{source: "media", verb: verb, status: r.status}
        meta = if r.job_id, do: Map.put(meta, :jobId, r.job_id), else: meta
        kind = resolve(media, verb)["kind"]

        if kind == "text" do
          %ToolResult{output: r.text, metadata: meta}
        else
          word = %{"image" => "image", "video" => "video", "audio" => "audio"}[kind]
          urls = Enum.map_join(r.files, "\n", & &1.url)
          %ToolResult{output: "#{word} ready: " <> urls, parts: r.files, metadata: meta}
        end

      {:error, msg} ->
        ToolResult.error(msg)
    end
  end

  # ---- engine ----

  defp run(media, verb, args, provider_options, signal) do
    # Edge rule: a nil-valued arg is absent.
    args = Map.reject(args, fn {_k, v} -> is_nil(v) end)

    with {:ok, spec, style} <- find(media, verb),
         :ok <- check_args(verb, spec, args) do
      # Immutable data: `defaults` is never mutated, so no explicit deep copy is needed.
      body =
        Enum.reduce(args, spec["defaults"] || %{}, fn {k, v}, acc ->
          put_path(acc, spec["args"][k], v)
        end)

      body = Map.merge(body, provider_options || %{})
      start = spec["start"] || %{}

      with {:ok, resp} <- call(media, style, verb, start["method"] || "POST", start["path"], body) do
        case spec["mode"] do
          "async" -> poll(media, style, verb, spec, resp, signal)
          _ -> result(verb, spec, resp, %Result{status: "completed"})
        end
      end
    end
  end

  defp find(media, verb) do
    Enum.find_value(media.styles, {:error, ~s(media: no style supports "#{verb}")}, fn style ->
      case style["verbs"] do
        %{^verb => spec} -> {:ok, spec, style}
        _ -> nil
      end
    end)
  end

  defp resolve(media, verb) do
    case find(media, verb) do
      {:ok, spec, _} -> spec
      _ -> nil
    end
  end

  defp check_args(verb, spec, args) do
    mapped = spec["args"] || %{}

    case Enum.find(Map.keys(args) |> Enum.sort(), &(not Map.has_key?(mapped, &1))) do
      nil -> :ok
      key -> {:error, ~s(#{verb}: argument "#{key}" not supported by this style)}
    end
  end

  defp poll(media, style, verb, spec, resp, signal) do
    p = spec["poll"] || %{}
    id_path = spec["idPath"]

    case read_path(resp, id_path) do
      {:ok, id} when is_binary(id) or is_integer(id) or (is_float(id) and id == trunc(id)) ->
        id = render(id)
        interval = p["intervalMs"] || 5000
        timeout = p["timeoutMs"] || 600_000
        path = String.replace(p["path"] || "", "{id}", id)
        t0 = System.monotonic_time(:millisecond)
        poll_loop(media, style, verb, spec, p, id, path, interval, timeout, t0, signal)

      _ ->
        {:error, "#{verb}: no job id at #{id_path}"}
    end
  end

  defp poll_loop(media, style, verb, spec, p, id, path, interval, timeout, t0, signal) do
    Process.sleep(interval)

    if cancelled?(signal) do
      raise Toolnexus.Media.Cancelled
    else
      with {:ok, resp} <- call(media, style, verb, p["method"] || "GET", path, nil) do
        status =
          case read_path(resp, p["statusPath"]) do
            {:ok, s} when is_binary(s) -> s
            {:ok, s} when is_number(s) -> render(s)
            _ -> ""
          end

        if media.on_progress, do: media.on_progress.(%{verb: verb, job_id: id, status: status})

        cond do
          status in (p["done"] || []) ->
            result(verb, spec, resp, %Result{status: status, job_id: id})

          status in (p["failed"] || []) ->
            msg =
              case read_path(resp, p["errorPath"]) do
                {:ok, m} when is_binary(m) and m != "" -> ": " <> m
                _ -> ""
              end

            {:error, "job #{id} #{status}#{msg}"}

          System.monotonic_time(:millisecond) - t0 >= timeout ->
            {:error, "job #{id} timed out after #{timeout}ms"}

          true ->
            poll_loop(media, style, verb, spec, p, id, path, interval, timeout, t0, signal)
        end
      end
    end
  end

  defp cancelled?(nil), do: false
  defp cancelled?(pid) when is_pid(pid), do: not Process.alive?(pid)
  defp cancelled?(f) when is_function(f, 0), do: f.() == true
  defp cancelled?(_), do: false

  defp result(verb, spec, resp, %Result{} = r) do
    path = spec["resultPath"]
    found = read_path(resp, path)

    case spec["kind"] do
      "text" ->
        case found do
          {:ok, t} when is_binary(t) or is_number(t) -> {:ok, %{r | text: render(t), files: []}}
          _ -> {:error, "#{verb}: no result at #{path}"}
        end

      kind ->
        urls =
          case found do
            {:ok, u} when is_binary(u) -> [u]
            {:ok, l} when is_list(l) -> l |> List.flatten() |> Enum.filter(&is_binary/1)
            _ -> []
          end
          |> Enum.reject(&(&1 == ""))

        if urls == [],
          do: {:error, "#{verb}: no result at #{path}"},
          else: {:ok, %{r | files: Enum.map(urls, &file(kind, &1))}}
    end
  end

  defp file(kind, url) do
    type = if kind == "video", do: "file", else: kind

    ext =
      (URI.parse(url).path || "")
      |> Path.extname()
      |> String.trim_leading(".")
      |> String.downcase()

    default = %{"image" => "image/png", "video" => "video/mp4", "audio" => "audio/mpeg"}[kind]
    %ContentPart{type: type, mime_type: Map.get(@mime, ext, default), url: url}
  end

  # ---- HTTP ----

  defp call(media, style, verb, method, path, body) do
    url = String.trim_trailing(style["baseUrl"] || "", "/") <> (path || "")

    headers =
      Map.new(style["headers"] || %{}, fn {k, v} ->
        {k, Regex.replace(@env_var, v, fn _, var -> System.get_env(var) || "" end)}
      end)

    headers = if body, do: Map.put(headers, "Content-Type", "application/json"), else: headers
    method = method |> to_string() |> String.downcase() |> String.to_atom()

    with {:ok, status, raw} <- prefix(verb, send_request(media, method, url, headers, body)) do
      cond do
        status not in 200..299 ->
          {:error, "HTTP #{status}: #{raw}"}

        true ->
          case decode(raw) do
            {:ok, json} -> envelope(style["errorEnvelope"], json)
            :error -> {:error, "#{verb}: response is not JSON"}
          end
      end
    end
  end

  defp send_request(%{transport: t}, method, url, headers, body) when is_function(t, 1) do
    case t.(%{method: method, url: url, headers: headers, body: body, receive_timeout: nil}) do
      {:ok, %{status: status, body: b}} -> {:ok, status, b}
      {:error, e} -> {:error, err_text(e)}
    end
  end

  defp send_request(media, method, url, headers, body) do
    opts =
      [method: method, url: url, headers: headers, retry: false, decode_body: false] ++
        if(body, do: [body: Jason.encode!(body)], else: [])

    case Req.request(Keyword.merge(opts, media.http_options)) do
      {:ok, %Req.Response{status: status, body: b}} -> {:ok, status, b}
      {:error, e} -> {:error, err_text(e)}
    end
  end

  # Edge rule: a transport error (no HTTP status) ⇒ "<verb>: <message>".
  defp prefix(verb, {:error, msg}), do: {:error, "#{verb}: #{msg}"}
  defp prefix(_verb, ok), do: ok

  defp err_text(e) when is_exception(e), do: Exception.message(e)
  defp err_text(e), do: inspect(e)

  # A transport may hand back an already-decoded body; raw text is decoded here.
  defp decode(raw) when is_binary(raw) do
    case Jason.decode(raw) do
      {:ok, j} -> {:ok, j}
      _ -> :error
    end
  end

  defp decode(raw) when is_map(raw) or is_list(raw), do: {:ok, raw}
  defp decode(_), do: :error

  defp envelope(nil, json), do: {:ok, json}

  defp envelope(env, json) do
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

          {:error, "API error #{render(code)}: #{msg}"}
        end

      :absent ->
        {:ok, json}
    end
  end

  # ---- values ----

  defp render(s) when is_binary(s), do: s
  defp render(n) when is_integer(n), do: Integer.to_string(n)
  defp render(f) when is_float(f) and f == trunc(f), do: Integer.to_string(trunc(f))
  defp render(f) when is_float(f), do: to_string(f)
  defp render(other), do: Jason.encode!(other)

  @doc false
  # dotPath read: `{:ok, value}` or `:absent`.
  def read_path(_v, nil), do: :absent
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
  # dotPath write: creates objects, and arrays when the next segment is numeric.
  def put_path(container, path, value), do: do_put(container, String.split(path, "."), value)

  defp do_put(_c, [], value), do: value

  defp do_put(c, [seg | rest], value) do
    if digits?(seg) do
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

  defp split_args(args) do
    args = stringify(args)
    {po, args} = Map.pop(args, "providerOptions")
    {po2, args} = Map.pop(args, "provider_options")
    {Map.new(po || po2 || %{}, fn {k, v} -> {to_string(k), v} end), args}
  end

  defp stringify(args) do
    Map.new(args, fn
      {k, v} when is_atom(k) -> {k |> Atom.to_string() |> camelize(), v}
      {k, v} -> {to_string(k), v}
    end)
  end

  defp camelize("provider_options"), do: "provider_options"

  defp camelize(s) do
    [h | t] = String.split(s, "_")
    h <> Enum.map_join(t, &String.capitalize/1)
  end

  defp verb_name(v) when is_atom(v), do: v |> Atom.to_string() |> camelize()
  defp verb_name(v), do: v
end
