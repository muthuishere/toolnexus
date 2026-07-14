defmodule Toolnexus.Adapters do
  @moduledoc """
  Provider adapters — turn the uniform tool list into each LLM's tool schema (SPEC §0.7).

  Execution is identical for every provider: read the tool name + args the model
  returned, call `execute(name, args)`, feed output back. All emitted maps are
  string-keyed so their JSON encoding is byte-identical across ports.
  """

  alias Toolnexus.Tool

  @doc "OpenAI shape: `{type: \"function\", function: {name, description, parameters}}` per tool."
  @spec to_openai([Tool.t()]) :: [map()]
  def to_openai(tools) do
    Enum.map(tools, fn t ->
      %{
        "type" => "function",
        "function" => %{
          "name" => t.name,
          "description" => t.description,
          "parameters" => t.input_schema
        }
      }
    end)
  end

  @doc "Anthropic shape: `{name, description, input_schema}` per tool."
  @spec to_anthropic([Tool.t()]) :: [map()]
  def to_anthropic(tools) do
    Enum.map(tools, fn t ->
      %{
        "name" => t.name,
        "description" => t.description,
        "input_schema" => t.input_schema
      }
    end)
  end

  @doc """
  Gemini shape: a single-element list wrapping all declarations —
  `[%{functionDeclarations: [{name, description, parameters}, …]}]`.
  The wrapper is emitted even for an empty tool list (JS parity).
  """
  @spec to_gemini([Tool.t()]) :: [map()]
  def to_gemini(tools) do
    [
      %{
        "functionDeclarations" =>
          Enum.map(tools, fn t ->
            %{
              "name" => t.name,
              "description" => t.description,
              "parameters" => t.input_schema
            }
          end)
      }
    ]
  end
end
