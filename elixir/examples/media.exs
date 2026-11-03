# Media generation (SPEC §7G) against the shared a2e.ai preset — both faces: the typed
# verb (`Media.image/3`) and the tool face (`Media.tools/1`, invoked directly).
#
# Run from the elixir/ directory (deps fetched):
#     A2E_API_KEY=... mix run examples/media.exs
#
# Without A2E_API_KEY it prints a hint and exits 0, so hermetic runs never touch the
# network. The key is read by the preset's ${A2E_API_KEY} header and is never printed.

alias Toolnexus.Media

if System.get_env("A2E_API_KEY") in [nil, ""] do
  IO.puts("set A2E_API_KEY to run the media example (skipping: no network call made)")
  System.halt(0)
end

here = Path.dirname(__ENV__.file)
preset = Path.expand(Path.join([here, "..", "..", "examples", "media-styles", "a2e.json"]))

{:ok, style} = Media.load_style(preset)
media = Media.new(style, on_progress: fn p -> IO.puts("  ..#{p.verb} #{p.status}") end)

# 1. Typed verb.
{:ok, img} =
  Media.image(media, %{prompt: "a red fox in fresh snow, watercolor", width: 768, height: 768})

IO.puts("image status: #{img.status}")
for f <- img.files, do: IO.puts("  file: #{f.type} #{f.mime_type} #{f.url}")

# 2. Tool face — the same engine as plain Tools an LLM would call.
tools = Media.tools(media)
for t <- tools, do: IO.puts("tool: #{t.name}")

speech = Enum.find(tools, &(&1.name == "generate_speech"))
res = speech.execute.(%{"text" => "Hello from toolnexus."}, %Toolnexus.Context{})
IO.puts("speech output: #{res.output} is_error: #{res.is_error}")
for p <- res.parts || [], do: IO.puts("  part: #{p.type} #{p.mime_type} #{p.url}")
