defmodule Toolnexus.Native do
  @moduledoc """
  Native tools — turn a plain function into a uniform `Toolnexus.Tool` (SPEC §6).

  The `execute` function's return value becomes the tool output:
    * a `Toolnexus.ToolResult` passes through unchanged
    * a binary is wrapped as a success output
    * anything else is JSON-encoded (JS `JSON.stringify` parity)
    * a raise becomes `%ToolResult{is_error: true, output: <message>}`
  """

  alias Toolnexus.{Tool, ToolResult}

  @empty_schema %{"type" => "object", "properties" => %{}, "additionalProperties" => false}

  @doc """
  Wrap a function as a Tool.

  Options (keyword list or map):
    * `:name` (required) — tool name
    * `:description` (required) — tool description
    * `:input_schema` — JSON schema map (default: empty object schema)
    * `:source` — tool source (default `"native"`)
    * `:execute` (required) — `fn args -> ... end` or `fn args, ctx -> ... end`
  """
  @spec define_tool(keyword() | map()) :: Tool.t()
  def define_tool(opts) do
    opts = Map.new(opts)
    run = Map.fetch!(opts, :execute)

    %Tool{
      name: Map.fetch!(opts, :name),
      description: Map.fetch!(opts, :description),
      input_schema: Map.get(opts, :input_schema) || @empty_schema,
      source: Map.get(opts, :source) || "native",
      execute: fn args, ctx ->
        try do
          case call_run(run, args || %{}, ctx) do
            %ToolResult{} = result -> result
            out when is_binary(out) -> %ToolResult{output: out, is_error: false}
            out -> %ToolResult{output: Jason.encode!(out), is_error: false}
          end
        rescue
          e -> %ToolResult{output: Exception.message(e), is_error: true}
        end
      end
    }
  end

  defp call_run(run, args, ctx) do
    case Function.info(run, :arity) do
      {:arity, 1} -> run.(args)
      _ -> run.(args, ctx)
    end
  end
end
