# usage: elixir main.exs <cli> [args...]    (oneshot.exs sits next to this file)
Mix.install([{:toolnexus, "~> 0.26"}])
Code.require_file("oneshot.exs", __DIR__)

add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers.",
    input_schema: %{"type" => "object", "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}}, "required" => ["a", "b"]},
    execute: fn %{"a" => a, "b" => b} -> "#{a + b}" end
  )

tk = Toolnexus.create_toolkit!(builtins: false, extra_tools: [add])
[command | args] = System.argv()
r = Toolnexus.Client.run(OneShot.client(command, args), "Use the add tool to add 19 and 23, then tell me the sum.", tk)
IO.puts("text: #{JSON.encode!(r.text)} toolCalls: #{JSON.encode!(r.tool_calls)}")
