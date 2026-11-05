defmodule Mcp2026.DocsTest do
  # Every code block in docs/*.md preceded by <!-- from: <path> --> must appear verbatim in that
  # file (an example that examples_test.exs runs). Edit the example, then re-copy the block.
  use ExUnit.Case, async: true

  test "doc snippets match their examples" do
    root = Path.expand("..", __DIR__)
    pages = Path.wildcard(Path.join(root, "docs/*.md"))
    assert length(pages) == 6

    total =
      for page <- pages, reduce: 0 do
        acc ->
          b = File.read!(page)
          assert b =~ "Elixir port of the Go reference spike", "#{page}: missing the spike banner"
          blocks = Regex.scan(~r/<!-- from: (\S+) -->\n```[a-z]*\n(.*?)\n```/s, b)

          for [_, src, code] <- blocks do
            assert File.read!(Path.join(root, src)) =~ code, "#{page}: block from #{src} no longer matches:\n#{code}"
          end

          acc + length(blocks)
      end

    assert total >= 12
  end
end
