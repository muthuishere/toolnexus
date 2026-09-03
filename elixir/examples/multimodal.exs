# Live multimodal example: attach an image, and let a TOOL return one.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/multimodal.exs                          # offline: parts + blocks only
#     OPENROUTER_API_KEY=... mix run examples/multimodal.exs    # the live runs
#
# The key is read from the environment and never printed.
#
# TWO things are proved here, and only one of them is provable from the model's words:
#
#   1. THE IMAGE ARRIVED. The proof is the PROMPT-TOKEN DELTA between the identical
#      request without and with the image — not the answer. A model asked to name the
#      colours in an image it never received will name four colours anyway, confidently
#      and wrongly, which is exactly how the emission bug this release fixes stayed
#      hidden. Tokens come from the provider; the model's prose does not.
#   2. THE §8A RELOCATION RULE. A tool returns an image in its result parts; the loop
#      must relocate those parts onto the following user turn (a tool_result message
#      cannot carry an image block on either style) and the run must complete.

here = Path.dirname(__ENV__.file)
examples = Path.expand(Path.join([here, "..", "..", "examples"]))
fixture = Path.join([examples, "media", "fixture.png"])

alias Toolnexus.{Client, ContentPart, ToolResult}

question =
  "This is an 8x8 image with four solid quadrants. Name the colour of each quadrant " <>
    "in the order top-left, top-right, bottom-left, bottom-right. Answer with four " <>
    "colour words only."

colours = ~w(red green blue white)

# How many of the four fixture colours a piece of prose actually names.
named = fn text ->
  down = String.downcase(text || "")
  Enum.count(colours, &String.contains?(down, &1))
end

image = ContentPart.image!(fixture)
IO.puts("fixture: #{inspect(image)}")
IO.puts("openai block:    #{inspect(ContentPart.to_block(image, "openai") |> elem(1) |> Map.keys())}")
IO.puts("anthropic block: #{inspect(ContentPart.to_block(image, "anthropic") |> elem(1) |> Map.keys())}")

# A tool whose result carries the image — the §8A relocation path (SPEC §1B/§8A).
show =
  Toolnexus.define_tool(
    name: "show_fixture",
    description: "Return the 8x8 four-quadrant fixture image so you can look at it.",
    input_schema: %{"type" => "object", "properties" => %{}},
    execute: fn _args, _ctx ->
      %ToolResult{
        output: "the fixture image (image/png, 82 bytes)",
        parts: [ContentPart.image!(fixture)]
      }
    end
  )

tk = Toolnexus.create_toolkit!(builtins: false)
tk = Toolnexus.Toolkit.register(tk, [show])
empty = Toolnexus.create_toolkit!(builtins: false)

styles = [
  {"openai", System.get_env("OPENROUTER_MODEL_OPENAI") || "openai/gpt-4o-mini"},
  {"anthropic", System.get_env("OPENROUTER_MODEL_ANTHROPIC") || "anthropic/claude-haiku-4.5"}
]

case System.get_env("OPENROUTER_API_KEY") do
  nil ->
    IO.puts("\n(no OPENROUTER_API_KEY set — skipping the live runs)")

  key ->
    for {style, model} <- styles do
      client =
        Client.create(
          base_url: "https://openrouter.ai/api/v1",
          style: style,
          model: model,
          api_key: key,
          max_turns: 4,
          request_params: %{"max_tokens" => 40}
        )

      # 1. the identical request, without and with the image.
      text_only = Client.run(client, [ContentPart.text(question)], empty)
      with_image = Client.run(client, [ContentPart.text(question), ContentPart.image!(fixture)], empty)

      ptok_text = text_only.usage.prompt_tokens
      ptok_image = with_image.usage.prompt_tokens
      delta = ptok_image - ptok_text

      # 2. the relocation path: the model calls the tool, the image comes back in
      #    its result parts, and the loop must relocate it and finish.
      relocated =
        Client.run(
          client,
          "Call show_fixture, then " <> question,
          tk
        )

      relocation =
        if relocated.status == "done" and relocated.tool_call_count > 0, do: "ok", else: "FAILED"

      IO.puts("")
      IO.puts("[#{style}] #{model}")
      IO.puts("  text only  (#{ptok_text} ptok): #{String.replace(text_only.text, "\n", " ")}")
      IO.puts("  with image (#{ptok_image} ptok): #{String.replace(with_image.text, "\n", " ")}")
      IO.puts("  via tool   (#{relocated.tool_call_count} tool calls): #{String.replace(relocated.text, "\n", " ")}")

      IO.puts(
        "RESULT elixir style=#{style} ptok_text=#{ptok_text} ptok_image=#{ptok_image} " <>
          "delta=#{if delta >= 0, do: "+", else: ""}#{delta} " <>
          "colours=#{named.(with_image.text)}/4 relocation=#{relocation} " <>
          "reloc_colours=#{named.(relocated.text)}/4"
      )
    end
end

Toolnexus.Toolkit.close(tk)
Toolnexus.Toolkit.close(empty)
IO.puts("\nOK")
