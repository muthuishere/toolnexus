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
      input_schema: %{
        "type" => "object",
        "properties" => %{"a" => %{"type" => "number"}, "b" => %{"type" => "number"}},
        "required" => ["a", "b"]
      },
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
    client =
      Client.create_in_process(
        model: "my-local",
        generate: fn _req -> %{content: "hello from in-process"} end
      )

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
        generate: fn req ->
          Agent.update(seen, fn _ -> req end)
          %{content: "ok"}
        end
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

  test "failures are not retried by default — there is no wire" do
    {:ok, calls} = Agent.start_link(fn -> 0 end)

    client =
      Client.create_in_process(
        model: "m",
        generate: fn _ ->
          Agent.update(calls, &(&1 + 1))
          raise "my model blew up"
        end
      )

    started = System.monotonic_time(:millisecond)

    assert_raise RuntimeError, ~r/my model blew up/, fn ->
      Client.run(client, "hi", bare_toolkit())
    end

    assert Agent.get(calls, & &1) == 1, "a local failure is not transient"
    assert System.monotonic_time(:millisecond) - started < 500
  end

  test "generate is required, and wire options are refused" do
    assert_raise ArgumentError, ~r/`:generate` function/, fn ->
      Client.create_in_process(model: "m")
    end

    assert_raise ArgumentError, ~r/no wire to configure/, fn ->
      Client.create_in_process(model: "m", generate: fn _ -> %{} end, base_url: "http://x")
    end
  end

  # conformance/acp-client/cases.json "stream.inProcess" — replayed from the fixture.
  @stream_case Path.expand("../../conformance/acp-client/cases.json", __DIR__)

  defp ev_from_fixture(e) do
    base = %{type: e["type"]}
    base = if e["delta"], do: Map.put(base, :delta, e["delta"]), else: base
    base = if e["via"], do: Map.put(base, :via, e["via"]), else: base
    base = if e["id"], do: Map.put(base, :id, e["id"]), else: base
    base = if e["name"], do: Map.put(base, :name, e["name"]), else: base
    if Map.has_key?(e, "args"), do: Map.put(base, :args, e["args"]), else: base
  end

  defp await_seen(seen, delta, wait_ms, msg) do
    deadline = System.monotonic_time(:millisecond) + wait_ms

    Stream.repeatedly(fn -> delta in Agent.get(seen, & &1) end)
    |> Enum.reduce_while(nil, fn
      true, _ ->
        {:halt, :ok}

      false, _ ->
        if System.monotonic_time(:millisecond) > deadline, do: raise(msg)
        Process.sleep(5)
        {:cont, nil}
    end)
  end

  test "generate_stream streams incrementally: each delta reaches the consumer before generate continues" do
    fx = @stream_case |> File.read!() |> Jason.decode!() |> get_in(["stream", "inProcess"])
    {:ok, seen} = Agent.start_link(fn -> [] end)

    client =
      Client.create_in_process(
        model: "m",
        generate_stream: fn _req, emit ->
          for e <- fx["emits"] do
            emit.(ev_from_fixture(e))

            if e["awaitConsumer"],
              do: await_seen(seen, e["delta"], fx["waitMs"], fx["notIncrementalError"])
          end

          %{content: fx["returns"]["content"]}
        end
      )

    events =
      client
      |> Client.stream(fx["prompt"], bare_toolkit())
      |> Stream.each(fn
        %{type: "text", delta: d} -> Agent.update(seen, &[d | &1])
        _ -> :ok
      end)
      |> Enum.to_list()

    refute Enum.any?(events, &(&1.type == "error"))
    assert for(%{type: "text", delta: d} <- events, do: d) == fx["expectTextDeltas"]

    for want <- fx["expectPassThrough"] do
      assert Enum.any?(events, fn ev ->
               ev.type == want["type"] and
                 Enum.all?(Map.delete(want, "type"), fn {k, v} ->
                   Map.get(ev, String.to_existing_atom(k)) == v
                 end)
             end),
             "missing pass-through #{inspect(want)} in #{inspect(events)}"
    end

    %{type: "done", result: done} = List.last(events)
    assert done.text == fx["expectDoneText"]
    assert length(done.tool_calls) == fx["expectDoneToolCalls"]

    # The fixture pins it: plain generate + stream still refuses loudly.
    assert fx["plainGenerateStillRefuses"]
  end

  test "generate_stream alone serves run/4 with a no-op emit; with both, generate serves run/4" do
    stream_only =
      Client.create_in_process(
        model: "m",
        generate_stream: fn _req, emit ->
          emit.(%{type: "text", delta: "ignored"})
          %{content: "whole"}
        end
      )

    assert Client.run(stream_only, "hi", bare_toolkit()).text == "whole"

    both =
      Client.create_in_process(
        model: "m",
        generate: fn _ -> %{content: "plain"} end,
        generate_stream: fn _req, _emit -> %{content: "streamed"} end
      )

    assert Client.run(both, "hi", bare_toolkit()).text == "plain"

    events = both |> Client.stream("hi", bare_toolkit()) |> Enum.to_list()
    assert for(%{type: "text", delta: d} <- events, do: d) == ["streamed"]
  end

  test "generate_stream returning tool calls drives the loop; a raise surfaces to the consumer" do
    {:ok, n} = Agent.start_link(fn -> 0 end)

    client =
      Client.create_in_process(
        model: "m",
        generate_stream: fn _req, _emit ->
          case Agent.get_and_update(n, &{&1, &1 + 1}) do
            0 -> %{tool_calls: [%{name: "add", arguments: %{"a" => 2, "b" => 3}}]}
            _ -> %{content: "5"}
          end
        end
      )

    events = client |> Client.stream("add", add_toolkit()) |> Enum.to_list()
    types = Enum.map(events, & &1.type)
    assert "tool_call" in types and "tool_result" in types
    assert List.last(events).result.text == "5"

    boom = Client.create_in_process(model: "m", generate_stream: fn _, _ -> raise "boom" end)

    assert_raise RuntimeError, ~r/boom/, fn ->
      boom |> Client.stream("hi", bare_toolkit()) |> Enum.to_list()
    end

    assert_raise ArgumentError, ~r/arity 2/, fn ->
      Client.create_in_process(model: "m", generate_stream: fn _ -> %{} end)
    end

    assert_raise ArgumentError, ~r/arity 1/, fn ->
      Client.create_in_process(model: "m", generate: fn _, _ -> %{} end)
    end
  end
end
