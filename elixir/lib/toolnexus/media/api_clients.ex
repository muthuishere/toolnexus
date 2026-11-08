defmodule Toolnexus.Media.A2E do
  @moduledoc """
  Built-in a2e.ai client (SPEC §7G.9): a style client over the embedded preset
  `examples/media-styles/a2e.json` (shipped verbatim in `priv/media/a2e.json`), named
  `a2e`, key `A2E_API_KEY` (read at call time), gather default `download`. `model`, when
  set, is written to the image body's `model_type`.
  """
  alias Toolnexus.Media.{Config, StyleClient}

  @doc "The embedded preset (parsed)."
  def style, do: Jason.decode!(Toolnexus.Media.a2e_json())

  @doc "Build the client from the shared config (§7G.4)."
  @spec new(keyword() | map()) :: StyleClient.t()
  def new(config \\ []) do
    config = Config.new(config)
    style = style()

    style =
      case Config.model_for(config, "image", true, nil) do
        nil -> style
        m -> put_in(style, ["verbs", "image", "defaults", "model_type"], m)
      end

    %StyleClient{
      style: style,
      config: config,
      name: "a2e",
      key_env: "A2E_API_KEY",
      default_mode: "download"
    }
  end
end

defmodule Toolnexus.Media.ElevenLabs do
  @moduledoc """
  Built-in ElevenLabs client (SPEC §7G.9): speech (incl. a cloned voice by name), music,
  transcribe, cloneVoice. Key `ELEVENLABS_API_KEY`, header `xi-api-key`.
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Config, Gather, Job, Result}

  defstruct config: %Config{}

  @env "ELEVENLABS_API_KEY"
  @default_url "https://api.elevenlabs.io"
  @args %{
    "speech" => [{"text", true}, {"voice", false}],
    "music" => [{"prompt", true}, {"seconds", false}, {"instrumental", false}],
    "transcribe" => [{"audio", true}, {"language", false}],
    "cloneVoice" => [{"name", true}, {"samples", true}]
  }

  @doc "Build the client from the shared config (§7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl true
  def name(_), do: "elevenlabs"
  @impl true
  def supports?(_, verb), do: Map.has_key?(@args, verb)
  @impl true
  def args(_, verb), do: for({n, r} <- Map.get(@args, verb, []), do: %{name: n, required: r})

  defp job(c, verb, ctx) do
    case Job.key(c.config, @env) do
      nil ->
        {:error, Job.no_key("elevenlabs", @env)}

      key ->
        base = Job.base_url(c.config, @default_url)

        {:ok,
         Job.new("elevenlabs", verb, c.config, ctx, base: base, headers: %{"xi-api-key" => key}),
         base}
    end
  end

  @impl true
  def speech(c, args, ctx) do
    with {:ok, job, base} <- job(c, "speech", ctx),
         {:ok, voice_id} <- voice(job, base, args["voice"]) do
      model = Config.model_for(c.config, "speech", true, "eleven_flash_v2_5")
      body = Job.merge_options(%{"text" => args["text"], "model_id" => model}, ctx)
      url = "#{base}/v1/text-to-speech/#{Job.pct(voice_id)}?output_format=mp3_22050_32"

      with {:ok, resp} <- Job.checked(job, :post, url, body: {:json, body}),
           {:ok, f} <- Gather.collect(job, {:bytes, bin(resp.body), "audio/mpeg"}) do
        {:ok, %Result{files: [f], job_id: resp.headers["request-id"], status: "completed"}}
      end
    end
  end

  defp voice(_job, _base, nil), do: {:ok, "JBFqnCBsd6RMkjVDRZzb"}

  defp voice(job, base, voice) do
    url = "#{base}/v2/voices?page_size=100&search=#{Job.pct(voice)}"

    with {:ok, j, _} <- Job.json(job, :get, url) do
      voices = if is_map(j) and is_list(j["voices"]), do: j["voices"], else: []

      found =
        Enum.find(voices, fn v ->
          is_map(v) and
            ((is_binary(v["name"]) and String.downcase(v["name"]) == String.downcase(voice)) or
               v["voice_id"] == voice)
        end)

      cond do
        found -> {:ok, found["voice_id"]}
        String.length(voice) == 20 and not String.contains?(voice, " ") -> {:ok, voice}
        true -> {:error, Job.fail(job, ~s(speech: voice "#{voice}" not found))}
      end
    end
  end

  @impl true
  def music(c, args, ctx) do
    with {:ok, job, base} <- job(c, "music", ctx) do
      seconds = args["seconds"] || 10
      body = %{"prompt" => args["prompt"], "music_length_ms" => round(seconds * 1000)}

      body =
        if args["instrumental"] == true, do: Map.put(body, "force_instrumental", true), else: body

      body = Job.merge_options(body, ctx)
      url = "#{base}/v1/music?output_format=mp3_44100_128"

      with {:ok, resp} <- Job.checked(job, :post, url, body: {:json, body}),
           {:ok, f} <- Gather.collect(job, {:bytes, bin(resp.body), "audio/mpeg"}) do
        {:ok, %Result{files: [f], status: "completed"}}
      end
    end
  end

  @impl true
  def transcribe(c, args, ctx) do
    with {:ok, job, base} <- job(c, "transcribe", ctx),
         {:ok, file} <- Job.read_input(job, args["audio"]) do
      model = Config.model_for(c.config, "transcribe", false, "scribe_v1")

      parts =
        [{:field, "model_id", model}] ++
          if(args["language"], do: [{:field, "language_code", args["language"]}], else: []) ++
          [{:file, "file", file}]

      text_result(job, "#{base}/v1/speech-to-text", Job.merge_fields(parts, ctx), "text")
    end
  end

  @impl true
  def clone_voice(c, args, ctx) do
    with {:ok, job, base} <- job(c, "cloneVoice", ctx),
         {:ok, files} <- read_all(job, List.wrap(args["samples"])) do
      parts = [{:field, "name", args["name"]}] ++ Enum.map(files, &{:file, "files", &1})
      text_result(job, "#{base}/v1/voices/add", Job.merge_fields(parts, ctx), "voice_id")
    end
  end

  defp read_all(job, samples) do
    Enum.reduce_while(samples, {:ok, []}, fn s, {:ok, acc} ->
      case Job.read_input(job, s) do
        {:ok, f} -> {:cont, {:ok, acc ++ [f]}}
        err -> {:halt, err}
      end
    end)
  end

  @doc false
  def text_result(job, url, parts, field) do
    with {:ok, j, _} <- Job.json(job, :post, url, body: Job.multipart(parts)) do
      case is_map(j) && j[field] do
        t when is_binary(t) -> {:ok, %Result{text: t, status: "completed"}}
        _ -> {:error, Job.fail(job, "#{job.verb}: no result at #{field}")}
      end
    end
  end

  @doc false
  def bin(b) when is_binary(b), do: b
  def bin(nil), do: ""
  def bin(b), do: Jason.encode!(b)
end

defmodule Toolnexus.Media.OpenAI do
  @moduledoc """
  Built-in OpenAI client (SPEC §7G.9): image, video, speech, transcribe. Key
  `OPENAI_API_KEY` (Bearer); `url` points it at any OpenAI-compatible server.
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Config, ElevenLabs, Gather, Job, Result}

  defstruct config: %Config{}

  @env "OPENAI_API_KEY"
  @default_url "https://api.openai.com/v1"
  @args %{
    "image" => [{"prompt", true}, {"width", false}, {"height", false}],
    "video" => [{"prompt", true}, {"seconds", false}],
    "speech" => [{"text", true}, {"voice", false}],
    "transcribe" => [{"audio", true}, {"language", false}]
  }
  @formats %{"png" => "image/png", "jpeg" => "image/jpeg", "webp" => "image/webp"}

  @doc "Build the client from the shared config (§7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl true
  def name(_), do: "openai"
  @impl true
  def supports?(_, verb), do: Map.has_key?(@args, verb)
  @impl true
  def args(_, verb), do: for({n, r} <- Map.get(@args, verb, []), do: %{name: n, required: r})

  defp job(c, verb, ctx) do
    case Job.key(c.config, @env) do
      nil ->
        {:error, Job.no_key("openai", @env)}

      key ->
        base = Job.base_url(c.config, @default_url)
        headers = %{"Authorization" => "Bearer " <> key}
        {:ok, Job.new("openai", verb, c.config, ctx, base: base, headers: headers), base}
    end
  end

  @impl true
  def image(c, args, ctx) do
    with {:ok, job, base} <- job(c, "image", ctx) do
      model = Config.model_for(c.config, "image", true, "gpt-image-1-mini")
      w = args["width"] || args["height"]
      h = args["height"] || args["width"]
      size = if w, do: "#{w}x#{h}", else: "1024x1024"
      body = %{"model" => model, "prompt" => args["prompt"], "n" => 1, "size" => size}

      body =
        if String.starts_with?(model, "gpt-image"),
          do: Map.put(body, "quality", "low"),
          else: body

      with {:ok, j, _} <-
             Job.json(job, :post, "#{base}/images/generations",
               body: {:json, Job.merge_options(body, ctx)}
             ) do
        mime = Map.get(@formats, is_map(j) && j["output_format"], "image/png")

        shapes =
          for e <- (is_map(j) && is_list(j["data"]) && j["data"]) || [],
              is_map(e),
              s = shape(e, mime),
              s != nil,
              do: s

        usage =
          usage(j, [{"input_tokens", "inputTokens"}, {"output_tokens", "outputTokens"}])

        if shapes == [],
          do: {:error, Job.fail(job, "image: no result at data")},
          else: files_result(job, shapes, %{usage: usage})
      end
    end
  end

  defp shape(%{"b64_json" => b}, mime) when is_binary(b) and b != "", do: {:base64, b, mime}
  defp shape(%{"url" => u}, _mime) when is_binary(u) and u != "", do: {:url, u, "image"}
  defp shape(_, _), do: nil

  @impl true
  def speech(c, args, ctx) do
    with {:ok, job, base} <- job(c, "speech", ctx) do
      body = %{
        "model" => Config.model_for(c.config, "speech", false, "gpt-4o-mini-tts"),
        "input" => args["text"],
        "voice" => args["voice"] || "alloy"
      }

      with {:ok, resp} <-
             Job.checked(job, :post, "#{base}/audio/speech",
               body: {:json, Job.merge_options(body, ctx)}
             ),
           {:ok, f} <- Gather.collect(job, {:bytes, ElevenLabs.bin(resp.body), "audio/mpeg"}) do
        {:ok, %Result{files: [f], status: "completed"}}
      end
    end
  end

  @impl true
  def transcribe(c, args, ctx) do
    with {:ok, job, base} <- job(c, "transcribe", ctx),
         {:ok, file} <- Job.read_input(job, args["audio"]) do
      parts =
        [
          {:field, "model",
           Config.model_for(c.config, "transcribe", false, "gpt-4o-mini-transcribe")}
        ] ++
          if(args["language"], do: [{:field, "language", args["language"]}], else: []) ++
          [{:file, "file", file}]

      ElevenLabs.text_result(
        job,
        "#{base}/audio/transcriptions",
        Job.merge_fields(parts, ctx),
        "text"
      )
    end
  end

  @impl true
  def video(c, args, ctx) do
    with {:ok, job, base} <- job(c, "video", ctx) do
      body = %{
        "model" => Config.model_for(c.config, "video", false, "sora-2"),
        "prompt" => args["prompt"],
        "seconds" => to_string(args["seconds"] || 4)
      }

      interval = Config.positive(c.config.loop.interval_ms, 5000)

      with {:ok, j, _} <-
             Job.json(job, :post, "#{base}/videos", body: {:json, Job.merge_options(body, ctx)}),
           {:ok, id} <- job_id(job, j, "id") do
        job = %{job | job_id: id}
        url = "#{base}/videos/#{Job.pct(id)}"

        polled =
          Job.poll(job, interval, fn ->
            with {:ok, p, _} <- Job.json(job, :get, url) do
              status = if is_binary(p["status"]), do: p["status"], else: ""
              Job.progress(job, status)

              case status do
                "completed" ->
                  :done

                "failed" ->
                  msg = get_in(p, ["error", "message"])
                  msg = if is_binary(msg) and msg != "", do: ": " <> msg, else: ""
                  {:error, Job.fail(job, "job #{id} failed#{msg}")}

                _ ->
                  :cont
              end
            end
          end)

        with :done <- polled,
             {:ok, resp} <- Job.checked(job, :get, url <> "/content"),
             {:ok, f} <- Gather.collect(job, {:bytes, ElevenLabs.bin(resp.body), "video/mp4"}) do
          {:ok, %Result{files: [f], job_id: id, status: "completed"}}
        end
      end
    end
  end

  @doc false
  # §7G.8: only a non-empty string or an integral number is a job id.
  def job_id(job, j, field) do
    case is_map(j) && j[field] do
      id when is_binary(id) and id != "" -> {:ok, id}
      id when is_integer(id) -> {:ok, Integer.to_string(id)}
      id when is_float(id) and id == trunc(id) -> {:ok, Integer.to_string(trunc(id))}
      _ -> {:error, Job.fail(job, "#{job.verb}: no job id at #{field}")}
    end
  end

  @doc false
  # §7G.12 D21: map the provider's own `usage` object, never estimated. A field the
  # provider omits is absent; no fields ⇒ nil.
  def usage(j, mapping) do
    u = (is_map(j) && is_map(j["usage"]) && j["usage"]) || %{}
    m = for {from, to} <- mapping, is_number(u[from]), into: %{}, do: {to, u[from]}
    if m == %{}, do: nil, else: m
  end

  @doc false
  def files_result(job, shapes, extra) do
    Enum.reduce_while(shapes, {:ok, []}, fn s, {:ok, acc} ->
      case Gather.collect(job, s) do
        {:ok, f} -> {:cont, {:ok, acc ++ [f]}}
        err -> {:halt, err}
      end
    end)
    |> case do
      {:ok, files} -> {:ok, struct(%Result{files: files, status: "completed"}, extra || %{})}
      err -> err
    end
  end
end

defmodule Toolnexus.Media.OpenRouter do
  @moduledoc """
  Built-in OpenRouter client (SPEC §7G.9): image through `chat/completions` with image
  modalities (many models by id). Key `OPENROUTER_API_KEY` (Bearer).
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Config, Job, OpenAI}

  defstruct config: %Config{}

  @env "OPENROUTER_API_KEY"
  @default_url "https://openrouter.ai/api/v1"

  @doc "Build the client from the shared config (§7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl true
  def name(_), do: "openrouter"
  @impl true
  def supports?(_, verb), do: verb == "image"
  @impl true
  def args(_, "image"), do: [%{name: "prompt", required: true}]
  def args(_, _), do: []

  @impl true
  def image(c, args, ctx) do
    case Job.key(c.config, @env) do
      nil ->
        {:error, Job.no_key("openrouter", @env)}

      key ->
        base = Job.base_url(c.config, @default_url)

        job =
          Job.new("openrouter", "image", c.config, ctx,
            base: base,
            headers: %{"Authorization" => "Bearer " <> key}
          )

        body = %{
          "model" => Config.model_for(c.config, "image", true, "google/gemini-2.5-flash-image"),
          "modalities" => ["image", "text"],
          "messages" => [%{"role" => "user", "content" => args["prompt"]}]
        }

        with {:ok, j, _} <-
               Job.json(job, :post, "#{base}/chat/completions",
                 body: {:json, Job.merge_options(body, ctx)}
               ) do
          choices = (is_map(j) && is_list(j["choices"]) && j["choices"]) || []

          {text, shapes} =
            Enum.reduce(choices, {"", []}, fn ch, {t, s} ->
              msg = (is_map(ch) && ch["message"]) || %{}
              t = if is_binary(msg["content"]), do: t <> msg["content"], else: t

              imgs =
                for i <- (is_list(msg["images"]) && msg["images"]) || [],
                    u = get_in(i, ["image_url", "url"]),
                    is_binary(u) and u != "" do
                  if String.starts_with?(u, "data:"), do: {:data_url, u}, else: {:url, u, "image"}
                end

              {t, s ++ imgs}
            end)

          id = if is_map(j) and is_binary(j["id"]), do: j["id"]

          cond do
            shapes == [] ->
              tail = if text == "", do: "", else: ": " <> Job.cap(text, 200)
              {:error, Job.fail(job, "image: no image in response" <> tail)}

            true ->
              OpenAI.files_result(job, shapes, %{
                job_id: id,
                text: if(text == "", do: nil, else: text),
                usage:
                  OpenAI.usage(j, [
                    {"prompt_tokens", "inputTokens"},
                    {"completion_tokens", "outputTokens"},
                    {"cost", "costUsd"}
                  ])
              })
          end
        end
    end
  end
end

defmodule Toolnexus.Media.Fal do
  @moduledoc """
  Built-in fal client (SPEC §7G.9): the queue API for any hosted model id — image
  (default `fal-ai/flux/schnell`) and video (Kling by default). Key `FAL_API_KEY`,
  header `Authorization: Key <key>`.
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Config, Job, OpenAI}

  defstruct config: %Config{}

  @env "FAL_API_KEY"
  @default_url "https://queue.fal.run"
  @args %{
    "image" => [{"prompt", true}, {"width", false}, {"height", false}, {"seed", false}],
    "video" => [{"prompt", true}, {"image", false}, {"seconds", false}, {"aspectRatio", false}]
  }

  @doc "Build the client from the shared config (§7G.4)."
  def new(config \\ []), do: %__MODULE__{config: Config.new(config)}

  @impl true
  def name(_), do: "fal"
  @impl true
  def supports?(_, verb), do: Map.has_key?(@args, verb)
  @impl true
  def args(_, verb), do: for({n, r} <- Map.get(@args, verb, []), do: %{name: n, required: r})

  @impl true
  def image(c, args, ctx) do
    model = Config.model_for(c.config, "image", true, "fal-ai/flux/schnell")
    input = %{"prompt" => args["prompt"], "num_images" => 1}

    input =
      if String.contains?(model, "schnell"),
        do: Map.merge(input, %{"image_size" => "square", "num_inference_steps" => 4}),
        else: input

    w = args["width"] || args["height"]
    h = args["height"] || args["width"]
    input = if w, do: Map.put(input, "image_size", %{"width" => w, "height" => h}), else: input
    input = if args["seed"] != nil, do: Map.put(input, "seed", args["seed"]), else: input
    queue(c, "image", model, input, ctx)
  end

  @impl true
  def video(c, args, ctx) do
    model =
      Map.get(c.config.models, "video") ||
        if(args["image"],
          do: "fal-ai/kling-video/v2.1/standard/image-to-video",
          else: "fal-ai/kling-video/v2.1/standard/text-to-video"
        )

    input = %{"prompt" => args["prompt"], "duration" => to_string(args["seconds"] || 5)}
    input = if args["image"], do: Map.put(input, "image_url", args["image"]), else: input

    input =
      if args["aspectRatio"], do: Map.put(input, "aspect_ratio", args["aspectRatio"]), else: input

    queue(c, "video", model, input, ctx)
  end

  defp queue(c, verb, model, input, ctx) do
    case Job.key(c.config, @env) do
      nil ->
        {:error, Job.no_key("fal", @env)}

      key ->
        base = Job.base_url(c.config, @default_url)

        job =
          Job.new("fal", verb, c.config, ctx,
            base: base,
            headers: %{"Authorization" => "Key " <> key}
          )

        interval = Config.positive(c.config.loop.interval_ms, 500)

        with {:ok, j, _} <-
               Job.json(job, :post, "#{base}/#{model}",
                 body: {:json, Job.merge_options(input, ctx)}
               ),
             {:ok, id} <- OpenAI.job_id(job, j, "request_id") do
          job = %{job | job_id: id}
          status_url = str(j["status_url"]) || "#{base}/#{model}/requests/#{Job.pct(id)}/status"
          response_url = str(j["response_url"]) || "#{base}/#{model}/requests/#{Job.pct(id)}"

          polled =
            Job.poll(job, interval, fn ->
              with {:ok, p, _} <- Job.json(job, :get, status_url) do
                status = if is_map(p) and is_binary(p["status"]), do: p["status"], else: ""
                err = is_map(p) && p["error"]

                # D1: COMPLETED wins over an `error` field; else a non-empty error fails.
                cond do
                  status == "COMPLETED" ->
                    Job.progress(job, status)
                    :done

                  is_binary(err) and err != "" ->
                    {:error, Job.fail(job, "job #{id} failed: #{err}")}

                  true ->
                    Job.progress(job, status)
                    :cont
                end
              end
            end)

          with :done <- polled,
               {:ok, r, _} <- Job.json(job, :get, response_url) do
            case result_urls(r) do
              [] ->
                {:error, Job.fail(job, "#{verb}: no result in response")}

              urls ->
                OpenAI.files_result(job, Enum.map(urls, &{:url, &1, kind(verb)}), %{job_id: id})
            end
          end
        end
    end
  end

  defp kind("image"), do: "image"
  defp kind(_), do: "video"

  defp str(s) when is_binary(s) and s != "", do: s
  defp str(_), do: nil

  # Result URLs in order: images[].url, image.url, video.url, audio.url, audio_file.url.
  defp result_urls(r) when is_map(r) do
    images = for i <- (is_list(r["images"]) && r["images"]) || [], u = url(i), u != nil, do: u
    images ++ for(k <- ~w(image video audio audio_file), u = url(r[k]), u != nil, do: u)
  end

  defp result_urls(_), do: []

  defp url(%{"url" => u}) when is_binary(u) and u != "", do: u
  defp url(_), do: nil
end
