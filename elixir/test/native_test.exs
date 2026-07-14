defmodule Toolnexus.NativeTest do
  use ExUnit.Case, async: true

  alias Toolnexus.{Native, Tool, ToolResult}

  test "string return becomes a success output" do
    tool =
      Native.define_tool(
        name: "greet",
        description: "Say hello",
        execute: fn args -> "hello #{args["name"]}" end
      )

    assert %Tool{name: "greet", source: "native"} = tool
    result = tool.execute.(%{"name" => "muthu"}, nil)
    assert result == %ToolResult{output: "hello muthu", is_error: false}
  end

  test "non-string return is JSON-encoded (JSON.stringify parity)" do
    tool = Native.define_tool(name: "j", description: "d", execute: fn _ -> %{"a" => 1} end)
    assert tool.execute.(%{}, nil) == %ToolResult{output: ~s({"a":1}), is_error: false}

    tool2 = Native.define_tool(name: "n", description: "d", execute: fn _ -> nil end)
    assert tool2.execute.(%{}, nil).output == "null"

    tool3 = Native.define_tool(name: "l", description: "d", execute: fn _ -> [1, 2, 3] end)
    assert tool3.execute.(%{}, nil).output == "[1,2,3]"
  end

  test "a raise becomes is_error with the error message" do
    tool = Native.define_tool(name: "boom", description: "d", execute: fn _ -> raise "kaboom" end)
    assert tool.execute.(%{}, nil) == %ToolResult{output: "kaboom", is_error: true}

    tool2 =
      Native.define_tool(name: "arg", description: "d", execute: fn _ -> raise ArgumentError, "bad arg" end)

    assert tool2.execute.(%{}, nil) == %ToolResult{output: "bad arg", is_error: true}
  end

  test "a returned ToolResult passes through unchanged" do
    passthrough = %ToolResult{output: "nope", is_error: true, metadata: %{code: 7}}
    tool = Native.define_tool(name: "p", description: "d", execute: fn _ -> passthrough end)
    assert tool.execute.(%{}, nil) == passthrough
  end

  test "default input schema is the empty object schema; source defaults to native" do
    tool = Native.define_tool(name: "t", description: "d", execute: fn _ -> "x" end)

    assert tool.input_schema == %{
             "type" => "object",
             "properties" => %{},
             "additionalProperties" => false
           }

    assert tool.source == "native"
  end

  test "explicit input_schema and source are honored" do
    schema = %{"type" => "object", "properties" => %{"x" => %{"type" => "number"}}}
    tool = Native.define_tool(name: "t", description: "d", input_schema: schema, source: "custom", execute: fn _ -> "x" end)
    assert tool.input_schema == schema
    assert tool.source == "custom"
  end

  test "nil args are normalized to an empty map" do
    tool = Native.define_tool(name: "t", description: "d", execute: fn args -> Jason.encode!(args) end)
    assert tool.execute.(nil, nil).output == "{}"
  end

  test "arity-2 execute receives the context" do
    tool =
      Native.define_tool(
        name: "ctx",
        description: "d",
        execute: fn _args, ctx -> "session=#{ctx.session_id}" end
      )

    result = tool.execute.(%{}, %Toolnexus.Context{session_id: "s1"})
    assert result.output == "session=s1"
  end
end
