defmodule Toolnexus.InProcessTest do
  @moduledoc """
  create_in_process — a model in this process, with no wire configuration.
  openspec/changes/add-in-process-client. Mirrored in all seven ports.
  """
  use ExUnit.Case, async: true

  alias Toolnexus.Client

  defp add_tool do
    Toolnexus.Native.define_tool(%{
      name: "add",
      description: "Add two numbers.",
      input_schema: %{"type" => "object",
                      "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
                      "required" => ["a", "b"]},
      execute: fn args -> to_string(trunc(args["a"] + args["b"])) end
    })
  end

  defp bare_toolkit do
    {:ok, tk} = Toolnexus.create_toolkit(builtins: false)
    tk
  end

  defp add_toolkit do
    {:ok, tk} = Toolnexus.create_toolkit(builtins: false, extra_tools: [add_tool()])
    tk
  end

  test "no wire configuration is required" do
    # No :base_url. No :api_key. No :style. That is the whole point.
    client = Client.create_in_process(model: "my-local", generate: fn _req -> %{content: "hello from in-process"} end)
    r = Client.run(client, "hi", bare_toolkit())
    assert r.text == "hello from in-process"
    assert r.status == "done"
  end

  test "generate sees the assembled request" do
    {:ok, seen} = Agent.start_link(fn -> nil end)

    client =
      Client.create_in_process(
        model: "my-local",
        system_prompt: "You are terse.",
        generate: fn req -> Agent.update(seen, fn _ -> req end); %{content: "ok"} end
      )

    Client.run(client, "What is 2 + 3?", add_toolkit())
    req = Agent.get(seen, & &1)

    assert req.model == "my-local"
    assert length(req.tools) >= 1, "tool schemas are offered"
    blob = Jason.encode!(req.messages)
    assert blob =~ "terse"
    assert blob =~ "2 + 3"
  end

  test "tool calls loop back with the result" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    client =
      Client.create_in_process(
        model: "m",
        generate: fn _req ->
          if Agent.get_and_update(n, &{&1, &1 + 1}) == 0 do
            %{tool_calls: [%{name: "add", arguments: %{"a" => 2, "b" => 3}}]}
          else
            %{content: "the answer is 5"}
          end
        end
      )

    r = Client.run(client, "What is 2 + 3?", add_toolkit())
    assert length(r.tool_calls) == 1
    call = List.first(r.tool_calls)
    assert call.name == "add"
    assert call.output == "5"
  end

  for {label, args} <- [structured: %{"a" => 2, "b" => 3}, pre_encoded: ~s({"a":2,"b":3})] do
    test "arguments may be #{label}" do
      args = unquote(Macro.escape(args))
      {:ok, n} = Agent.start_link(fn -> 0 end)

      client =
        Client.create_in_process(
          model: "m",
          generate: fn _req ->
            if Agent.get_and_update(n, &{&1, &1 + 1}) == 0,
              do: %{tool_calls: [%{name: "add", arguments: args}]},
              else: %{content: "done"}
          end
        )

      r = Client.run(client, "go", add_toolkit())
      assert List.first(r.tool_calls).output == "5"
    end
  end

  test "usage is optional and derived" do
    bare = Client.create_in_process(model: "m", generate: fn _ -> %{content: "x"} end)
    assert Client.run(bare, "hi", bare_toolkit()).usage.total_tokens == 0

    counted =
      Client.create_in_process(
        model: "m",
        generate: fn _ -> %{content: "x", usage: %{prompt_tokens: 11, completion_tokens: 4}} end
      )

    r = Client.run(counted, "hi", bare_toolkit())
    assert r.usage.prompt_tokens == 11
    assert r.usage.total_tokens == 15, "total is derived when not given"
  end

  test "streaming is refused loudly" do
    client = Client.create_in_process(model: "m", generate: fn _ -> %{content: "x"} end)

    # The stream is LAZY (Stream.resource), so it must be enumerated to run at all.
    err =
      assert_raise ArgumentError, fn ->
        client |> Client.stream("hi", bare_toolkit()) |> Enum.to_list()
      end

    assert Exception.message(err) =~ "does not support streaming"
  end

  test "generate is required, and wire options are refused" do
    assert_raise ArgumentError, ~r/`:generate` function/, fn ->
      Client.create_in_process(model: "m")
    end

    assert_raise ArgumentError, ~r/no wire to configure/, fn ->
      Client.create_in_process(model: "m", generate: fn _ -> %{} end, base_url: "http://x")
    end
  end
end
