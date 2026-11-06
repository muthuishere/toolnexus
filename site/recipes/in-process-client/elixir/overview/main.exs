# usage: elixir main.exs
Mix.install([{:toolnexus, "~> 0.25"}])

add =
  Toolnexus.define_tool(
    name: "add",
    description: "Add two numbers.",
    input_schema: %{"type" => "object", "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}}, "required" => ["a", "b"]},
    execute: fn %{"a" => a, "b" => b} -> "#{a + b}" end
  )

# Your "model": first turn asks for a tool, second turn streams the answer.
client =
  Toolnexus.Client.create_in_process(
    model: "my-model",
    generate_stream: fn req, emit ->
      last = List.last(req.messages)

      if last["role"] != "tool" do
        %{tool_calls: [%{name: "add", arguments: %{"a" => 2, "b" => 3}}]}
      else
        words = ["The ", "sum ", "is ", to_string(last["content"]), "."]
        Enum.each(words, &emit.(%{type: "text", delta: &1})) # deltas must add up to content
        %{content: Enum.join(words)}
      end
    end
  )

toolkit = Toolnexus.create_toolkit!(builtins: false, extra_tools: [add])

for ev <- Toolnexus.Client.stream(client, "What is 2 + 3?", toolkit) do
  case ev.type do
    "text" -> IO.write(ev.delta)
    "tool_call" -> IO.puts("[calling #{ev.name}]")
    _ -> :ok
  end
end

IO.puts("") # The sum is 5.
