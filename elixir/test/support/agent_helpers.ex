defmodule Toolnexus.TestVirtualClock do
  @moduledoc false
  # A virtual `Toolnexus.Agents.Clock`: `now` is a counter, `send_after` parks the
  # message until `advance/2` crosses its deadline. Determinism without sleeping.

  def new do
    {:ok, pid} = Agent.start_link(fn -> %{now: 0, timers: []} end)
    pid
  end

  def clock(pid) do
    %{
      now: fn -> Agent.get(pid, & &1.now) end,
      send_after: fn dest, msg, ms ->
        Agent.update(pid, fn st -> %{st | timers: st.timers ++ [{st.now + ms, dest, msg}]} end)
        :ok
      end
    }
  end

  def advance(pid, ms) do
    due =
      Agent.get_and_update(pid, fn st ->
        now = st.now + ms
        {due, rest} = Enum.split_with(st.timers, fn {t, _, _} -> t <= now end)
        {due, %{st | now: now, timers: rest}}
      end)

    Enum.each(due, fn {_t, dest, msg} -> send(dest, msg) end)
  end
end

defmodule Toolnexus.TestAgentHelpers do
  @moduledoc false
  # Shared registry + tools for the §7D runtime tests — mirrors the shared
  # `examples/subagent-*` fixtures' registries (team scoping included).

  alias Toolnexus.{Request, ToolResult}
  alias Toolnexus.TestAgentMock, as: Mock
  alias Toolnexus.Agents.Runtime

  def lookup do
    Toolnexus.define_tool(%{
      name: "lookup",
      description: "look something up",
      input_schema: %{"type" => "object", "properties" => %{"q" => %{"type" => "string"}}},
      execute: fn args -> "data(#{args["q"]})" end
    })
  end

  def check_secret do
    Toolnexus.define_tool(%{
      name: "check_secret",
      description: "needs human approval",
      input_schema: %{"type" => "object", "properties" => %{}},
      execute: fn _args, ctx ->
        if ctx.answer && ctx.answer.ok do
          "secret-token"
        else
          %ToolResult{
            output: "approve secret access?",
            is_error: false,
            metadata: %{
              pending: %Request{
                id: "req-#{System.unique_integer([:positive])}",
                kind: "approval",
                prompt: "approve secret access?"
              }
            }
          }
        end
      end
    })
  end

  def registry(overrides \\ %{}) do
    base = %{
      "coordinator" => %{
        name: "coordinator",
        description: "splits and delegates",
        system_prompt: "coordinate",
        model: "m-coordinator",
        task_targets: ["explore"]
      },
      "explore" => %{
        name: "explore",
        description: "read-only research",
        system_prompt: "explore",
        model: "m-explore",
        tools: [lookup()]
      },
      "approverParent" => %{
        name: "approverParent",
        description: "delegates, holds approval authority",
        system_prompt: "",
        model: "m-approver-parent",
        task_targets: ["asker"]
      },
      "asker" => %{
        name: "asker",
        description: "needs approvals",
        system_prompt: "",
        model: "m-asker",
        tools: [check_secret()]
      },
      "peer" => %{name: "peer", description: "team member", system_prompt: "", model: "m-peer"},
      "looper" => %{name: "looper", description: "never finishes", system_prompt: "", model: "m-loop", tools: [lookup()]},
      "slow" => %{name: "slow", description: "slow worker", system_prompt: "", model: "m-slow"}
    }

    Enum.reduce(overrides, base, fn {k, v}, acc -> Map.update!(acc, k, &Map.merge(&1, v)) end)
  end

  def new_rt(opts \\ %{}) do
    Runtime.new(
      Map.merge(%{transport: Mock.transport(&Mock.demo/1), registry: registry()}, Map.new(opts))
    )
  end

  @doc false
  # Per-handle transition lines, as the fixtures' `expect.transitions` pin them.
  def transitions(trace, handle_id) do
    trace
    |> Enum.filter(&String.starts_with?(&1, handle_id <> ":"))
    |> Enum.map(fn line ->
      cond do
        line =~ "idle→running" -> "idle→running"
        line =~ "running→suspended" -> "running→suspended"
        line =~ "suspended→running" -> "suspended→running"
        line =~ "suspended→idle" -> "suspended→idle"
        line =~ "running→idle" -> "running→idle"
        line =~ "idle→closed" -> "idle→closed"
        line =~ "suspended→closed" -> "suspended→closed"
        line =~ "running→closed" -> "running→closed"
        true -> nil
      end
    end)
    |> Enum.reject(&is_nil/1)
  end
end
