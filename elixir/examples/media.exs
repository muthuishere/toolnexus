# Media generation (SPEC §7G) — the E1 example contract, identical in every port.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/media.exs
#
# Clients, in this order, each only when present: Grok CLI, Codex CLI (on PATH),
# ElevenLabs (ELEVENLABS_API_KEY), then the paid fallbacks OpenAI (OPENAI_API_KEY),
# fal (FAL_API_KEY), A2E (A2E_API_KEY). Steps:
#
#   1. image "a red apple on a white table" (first client that can serve it)
#   2. speech through ElevenLabs — voice from MEDIA_VOICE, else the default
#   3. tool face: list Media.tools/1 and call generate_image, as an LLM would
#   4. video through Grok, only with MEDIA_VIDEO=1
#   5. paid video (OpenAI / fal) and talk (A2E), only with MEDIA_PAID=1
#
# Paid image clients keep their smallest / lowest-quality defaults. Each result prints
# client, verb, path or URL, mime type, size and provider-reported usage. Exits 1 if any
# attempted step errors; exits 0 with a hint when no client is available. Keys are read
# from the environment at call time and never printed.

alias Toolnexus.Media

defmodule MediaExample do
  def has?(env), do: System.get_env(env) not in [nil, ""]
  def on_path?(cli), do: System.find_executable(cli) != nil

  # Saved files are named <client>-<verb>-<millis>-<seq><ext> (§7G.5).
  def served_by(%Media.MediaFile{path: p}) when is_binary(p),
    do: p |> Path.basename() |> String.split("-") |> hd()

  def served_by(_), do: "?"

  def size(%Media.MediaFile{path: p}) when is_binary(p), do: File.stat!(p).size
  def size(%Media.MediaFile{data: d}) when is_binary(d), do: byte_size(Base.decode64!(d))
  def size(_), do: 0

  def usage(nil), do: ""
  def usage(u), do: " usage=" <> Jason.encode!(u)

  # Prints each file; returns true when the step errored.
  def report(step, {:ok, %Media.Result{} = r}, client) do
    for f <- r.files do
      IO.puts(
        "#{step}: client=#{client || served_by(f)} verb=#{step} file=#{f.path || f.url} " <>
          "mime=#{f.mime_type} size=#{size(f)}#{usage(r.usage)}"
      )
    end

    false
  end

  def report(step, {:error, %Media.Error{status: s, message: m}}, _client) do
    IO.puts("#{step}: ERROR #{s}: #{m}")
    true
  end
end

prompt = "a red apple on a white table"
cfg = [gather: [mode: "download"]]

grok = if MediaExample.on_path?("grok"), do: Media.grok(cfg)
codex = if MediaExample.on_path?("codex"), do: Media.codex(cfg)
eleven = if MediaExample.has?("ELEVENLABS_API_KEY"), do: Media.elevenlabs(cfg)
# OpenAI defaults: gpt-image-1-mini, 1024x1024, quality low.
openai = if MediaExample.has?("OPENAI_API_KEY"), do: Media.openai(cfg)
fal = if MediaExample.has?("FAL_API_KEY"), do: Media.fal(cfg)
a2e = if MediaExample.has?("A2E_API_KEY"), do: Media.a2e(cfg)

paid = Enum.reject([openai, fal, a2e], &is_nil/1)
clients = Enum.reject([grok, codex, eleven], &is_nil/1) ++ paid

if clients == [] do
  IO.puts(
    "no media client available — install/log in the grok or codex CLI, or set " <>
      "ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY (no network call made)"
  )

  System.halt(0)
end

IO.puts("clients: " <> Enum.map_join(clients, ", ", & &1.__struct__.name(&1)))
media = Media.create!(clients)
failed = []

# 1. image
{image, failed} =
  if Media.supports?(media, "image") do
    out = Media.image(media, %{prompt: prompt})
    {out, [MediaExample.report("image", out, nil) | failed]}
  else
    {nil, failed}
  end

# 2. speech (ElevenLabs)
{speech, failed} =
  if eleven do
    out =
      Media.speech(media, %{text: "Hello from toolnexus.", voice: System.get_env("MEDIA_VOICE")})

    {out, [MediaExample.report("speech", out, "elevenlabs") | failed]}
  else
    {nil, failed}
  end

# 3. tool face
failed =
  if Media.supports?(media, "image") do
    tools = Media.tools(media)
    IO.puts("tools: " <> Enum.map_join(tools, ", ", & &1.name))
    tool = Enum.find(tools, &(&1.name == "generate_image"))
    r = tool.execute.(%{"prompt" => prompt}, %Toolnexus.Context{})

    if r.is_error do
      IO.puts("generate_image: ERROR #{r.output}")
      [true | failed]
    else
      size = Enum.sum(for p <- r.parts || [], p.data, do: byte_size(Base.decode64!(p.data)))

      IO.puts(
        "generate_image: client=#{r.metadata.client} verb=image output=#{inspect(r.output)} " <>
          "parts=#{length(r.parts || [])} size=#{size}#{MediaExample.usage(r.metadata[:usage])}"
      )

      [false | failed]
    end
  else
    failed
  end

# 4. Grok video (opt-in)
failed =
  if System.get_env("MEDIA_VIDEO") == "1" and grok do
    out = Media.video(Media.create!(grok), %{prompt: prompt})
    [MediaExample.report("video", out, "grok") | failed]
  else
    failed
  end

# 5. paid video / talk (opt-in)
failed =
  if System.get_env("MEDIA_PAID") == "1" and paid != [] do
    pm = Media.create!(paid)

    failed =
      if Media.supports?(pm, "video"),
        do: [MediaExample.report("video", Media.video(pm, %{prompt: prompt}), nil) | failed],
        else: failed

    with {:ok, %{files: [img | _]}} <- image,
         {:ok, %{files: [voice | _]}} <- speech,
         true <- a2e != nil do
      out =
        Media.talk(Media.create!(a2e), %{
          image: img.path || img.url,
          audio: voice.path || voice.url
        })

      [MediaExample.report("talk", out, "a2e") | failed]
    else
      _ -> failed
    end
  else
    failed
  end

if Enum.any?(failed), do: System.halt(1), else: System.halt(0)
