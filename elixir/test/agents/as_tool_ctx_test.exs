defmodule Toolnexus.Agents.AsToolCtxTest do
  @moduledoc """
  SPEC §4C (change add-go-host-asks, golang agents/astool_ctx_test.go): an agent run as
  a tool stops with the calling turn, and `as_tool_with/2` runs the child under the
  parent turn's gate (`:hooks`) and ask bridge (runtime `:wait_for`).
  """
  use ExUnit.Case, async: false

  alias Toolnexus.{Agents, Answer, Context, Request, ToolHints, ToolResult}
  alias Toolnexus.TestAgentMock, as: Mock

  # An LLM that never answers: the child is stuck until something cancels it.
  defp blocking(me) do
    fn _req ->
      send(me, {:llm_blocked, self()})

      receive do
        :never -> :ok
      end
    end
  end

  test "a tool-context signal cancels the child run" do
    child = Agents.agent("child", does: "d", soul: "s")
    tool = Agents.as_tool(child, transport: blocking(self()), llm: %{model: "m"})
    {:ok, flag} = Agent.start_link(fn -> false end)
    signal = fn -> Agent.get(flag, & &1) end

    spawn(fn ->
      Process.sleep(100)
      Agent.update(flag, fn _ -> true end)
    end)

    t0 = System.monotonic_time(:millisecond)
    r = tool.execute.(%{"prompt" => "go"}, %Context{signal: signal})
    assert System.monotonic_time(:millisecond) - t0 < 3_000
    assert r.is_error
    assert String.starts_with?(r.output, "cancelled")
    assert r.metadata.agent == "child"

    # an already-aborted signal never starts the child
    dead = spawn(fn -> :ok end)
    Process.sleep(10)
    r = tool.execute.(%{"prompt" => "go"}, %Context{signal: dead})
    assert r.is_error and String.starts_with?(r.output, "cancelled")
  end

  test "killing the calling turn takes the child down with it" do
    child = Agents.agent("child", does: "d", soul: "s")
    tool = Agents.as_tool(child, transport: blocking(self()), llm: %{model: "m"})
    caller = spawn(fn -> tool.execute.(%{"prompt" => "go"}, %Context{}) end)
    assert_receive {:llm_blocked, llm}, 2_000
    ref = Process.monitor(llm)
    Process.exit(caller, :kill)
    assert_receive {:DOWN, ^ref, _, _, _}, 3_000
  end

  test "as_tool_with: the child's mutating tool goes through the parent's gate and ask bridge" do
    me = self()

    danger =
      %{
        Toolnexus.define_tool(
          name: "danger",
          description: "mutates",
          execute: fn _ ->
            send(me, :ran)
            "did it"
          end
        )
        | hints: %ToolHints{destructive: true}
      }

    {:ok, n} = Agent.start_link(fn -> 0 end)

    script =
      Mock.transport(fn _body ->
        if Agent.get_and_update(n, &{&1 + 1, &1 + 1}) == 1,
          do: Mock.openai_response(Mock.tool_call("danger", %{}, "d1")),
          else: Mock.openai_response(%{"content" => "child done"})
      end)

    child = Agents.agent("child", does: "d", soul: "s", uses: %{tools: [danger]})

    tool =
      Agents.as_tool_with(child, fn %Context{} ->
        [
          transport: script,
          llm: %{model: "m"},
          hooks: %{
            before_tool: fn ev ->
              send(me, {:gated, ev.name})

              unless ev.hints && ev.hints.read_only do
                req = %Request{id: "a1", kind: "approval", prompt: "allow #{ev.name}?"}

                %{
                  result: %ToolResult{
                    output: req.prompt,
                    is_error: true,
                    metadata: %{pending: req}
                  }
                }
              end
            end
          },
          wait_for: fn req ->
            send(me, {:asked, req.prompt})
            %Answer{id: req.id, ok: true}
          end
        ]
      end)

    r = tool.execute.(%{"prompt" => "go"}, %Context{})
    refute r.is_error
    assert r.output == "child done"
    assert_received {:gated, "danger"}
    assert_received {:asked, "allow danger?"}
    assert_received :ran
  end
end
