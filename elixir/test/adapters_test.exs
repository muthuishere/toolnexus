defmodule Toolnexus.AdaptersTest do
  use ExUnit.Case, async: true

  alias Toolnexus.{Adapters, Tool, ToolResult}

  @schema %{
    "type" => "object",
    "properties" => %{"q" => %{"type" => "string", "description" => "The query"}},
    "required" => ["q"],
    "additionalProperties" => false
  }

  defp tools do
    [
      %Tool{
        name: "search",
        description: "Search for things",
        input_schema: @schema,
        source: "native",
        execute: fn _a, _c -> ToolResult.ok("") end
      },
      %Tool{
        name: "noop",
        description: "Do nothing",
        input_schema: %{"type" => "object", "properties" => %{}, "additionalProperties" => false},
        source: "custom",
        execute: fn _a, _c -> ToolResult.ok("") end
      }
    ]
  end

  test "to_openai matches the golden §0.7 shape" do
    golden =
      Jason.decode!("""
      [
        {"type":"function","function":{"name":"search","description":"Search for things",
          "parameters":{"type":"object","properties":{"q":{"type":"string","description":"The query"}},
                        "required":["q"],"additionalProperties":false}}},
        {"type":"function","function":{"name":"noop","description":"Do nothing",
          "parameters":{"type":"object","properties":{},"additionalProperties":false}}}
      ]
      """)

    assert Adapters.to_openai(tools()) == golden
  end

  test "to_anthropic matches the golden §0.7 shape" do
    golden =
      Jason.decode!("""
      [
        {"name":"search","description":"Search for things",
         "input_schema":{"type":"object","properties":{"q":{"type":"string","description":"The query"}},
                         "required":["q"],"additionalProperties":false}},
        {"name":"noop","description":"Do nothing",
         "input_schema":{"type":"object","properties":{},"additionalProperties":false}}
      ]
      """)

    assert Adapters.to_anthropic(tools()) == golden
  end

  test "to_gemini wraps all declarations in a single functionDeclarations element" do
    golden =
      Jason.decode!("""
      [
        {"functionDeclarations":[
          {"name":"search","description":"Search for things",
           "parameters":{"type":"object","properties":{"q":{"type":"string","description":"The query"}},
                         "required":["q"],"additionalProperties":false}},
          {"name":"noop","description":"Do nothing",
           "parameters":{"type":"object","properties":{},"additionalProperties":false}}
        ]}
      ]
      """)

    assert Adapters.to_gemini(tools()) == golden
  end

  test "empty tool list: openai/anthropic emit [], gemini still wraps (JS parity)" do
    assert Adapters.to_openai([]) == []
    assert Adapters.to_anthropic([]) == []
    assert Adapters.to_gemini([]) == [%{"functionDeclarations" => []}]
  end

  test "emitted maps are string-keyed and JSON round-trip unchanged" do
    for encoded <- [
          Adapters.to_openai(tools()),
          Adapters.to_anthropic(tools()),
          Adapters.to_gemini(tools())
        ] do
      assert encoded |> Jason.encode!() |> Jason.decode!() == encoded
    end
  end
end
