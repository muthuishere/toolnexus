defmodule Toolnexus.Agents.UxTest do
  @moduledoc """
  §7D S10–S13 — the Level-1 surface (`agent/2`, team scoping, `as_tool/2`) and the
  unsolicited-rail heartbeat recipe. (Directory bootstrap + heartbeat as API belong
  to the agent-home change — here they are userland recipes over the six verbs.)
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Agents
  alias Toolnexus.Agents.Runtime
  alias Toolnexus.TestAgentMock, as: Mock
  import Toolnexus.TestAgentHelpers, only: [lookup: 0]

  @bootstrap_order ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]

  defp ux_opts, do: %{transport: Mock.transport(&Mock.ux/1)}

  # Userland "the directory IS the agent" recipe: optional bootstrap files become
  # `## <file>` soul sections in a fixed order.
  defp soul_from_dir(dir) do
    for f <- @bootstrap_order, path = Path.join(dir, f), File.exists?(path) do
      "## #{f}\n\n#{String.trim(File.read!(path))}"
    end
    |> Enum.join("\n\n")
  end

  test "S10 Level-1 UX: agent() + team wiring + soul file" do
    soul_path = Path.join(System.tmp_dir!(), "AGENTS-shipped.md")
    File.write!(soul_path, "You are the CODER. Fix things surgically.")

    explore = Agents.agent("explore", %{does: "read-only research", uses: %{tools: [lookup()]}, model: "m-explore"})

    coder =
      Agents.agent("coder", %{
        does: "implements changes",
        soul_file: soul_path,
        team: [explore],
        model: "m-coder",
        budget: %{max_tokens: 10_000}
      })

    r = Agents.run(coder, ux_opts(), "fix the failing test")

    # coding agent completes via its team
    assert r.status == "done" and r.text =~ "bug at line 42", "coding agent completes via its team: #{r.text}"
    # soul FILE reached the system prompt
    assert r.text =~ "[soul:loaded]", "soul FILE reached the system prompt: #{r.text}"
  end

  test "S11 team scoping: task targets = the team, nothing else" do
    _stranger = Agents.agent("stranger", %{does: "should be unreachable", model: "m-explore", uses: %{tools: [lookup()]}})
    explore = Agents.agent("explore", %{does: "read-only research", uses: %{tools: [lookup()]}, model: "m-explore"})
    coder = Agents.agent("coder", %{does: "implements", team: [explore], model: "m-coder"})

    reg = Agents.registry(coder)

    # registry contains only the reachable graph
    assert reg |> Map.keys() |> Enum.sort() |> Enum.join(",") == "coder,explore",
           "registry contains only the reachable graph: #{inspect(Map.keys(reg))}"

    # coder's task targets are exactly its team
    assert reg["coder"][:task_targets] == ["explore"], "coder's task targets are exactly its team"
  end

  test "S12 userland recipe: the directory IS the agent · heartbeat rail · silent OK" do
    dir = Path.join(System.tmp_dir!(), "mia-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    File.write!(Path.join(dir, "SOUL.md"), "You are Mia. Warm, brief.")
    File.write!(Path.join(dir, "USER.md"), "The user is Muthu.")
    File.write!(Path.join(dir, "MEMORY.md"), "- Likes green tea.")
    File.write!(Path.join(dir, "HEARTBEAT.md"), "On heartbeat: if it is watering day, remind to water the plants.")

    mia = Agents.agent("mia", %{does: "persona agent from #{dir}", soul: soul_from_dir(dir), model: "m-mia"})
    direct = Agents.run(mia, ux_opts(), "hello")

    # bootstrap files discovered + injected as ## sections (fixed order)
    assert direct.text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]",
           "bootstrap files discovered + injected as ## sections: #{direct.text}"

    # heartbeat = the unsolicited rail in userland: post a tick, wake at idle,
    # filter HEARTBEAT_OK results host-side
    rt = Runtime.new(Map.put(ux_opts(), :registry, Agents.registry(mia)))
    {:ok, h} = Runtime.spawn_agent(rt, Runtime.root(rt), "mia")
    hb_prompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK."

    reports =
      for beat <- 1..3 do
        # two watering-day beats carry timer ticks; the third is a quiet beat
        if beat < 3, do: Runtime.post(rt, h, %{from: "clock", channel: "timer", text: "tick"})
        r = Runtime.run_turn(rt, h, hb_prompt)
        if not r.is_error and not String.contains?(r.text, "HEARTBEAT_OK"), do: r.text
      end
      |> Enum.reject(&is_nil/1)

    hb_turns = rt |> Runtime.trace() |> Enum.count(&(&1 =~ "idle→running"))
    # heartbeat woke the agent repeatedly
    assert hb_turns >= 2, "heartbeat woke the agent repeatedly, turns=#{hb_turns}"

    # HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)
    assert length(reports) == 2 and Enum.all?(reports, &(&1 =~ "water")),
           "HEARTBEAT_OK wakes stayed SILENT: #{inspect(reports)}"

    # agent closed cleanly on stop
    Runtime.close(rt)
    assert Runtime.snapshot(h).state == :closed, "agent closed cleanly on stop()"
    Runtime.shutdown(rt)
  end

  test "S13 bridge: an Agent IS a Tool in the classic toolkit/client API" do
    explore = Agents.agent("explore", %{does: "read-only research", uses: %{tools: [lookup()]}, model: "m-explore"})
    toolkit = [Agents.as_tool(explore, ux_opts())]

    client =
      Toolnexus.Client.create(
        base_url: "http://mock.local",
        style: "openai",
        model: "m-old-api",
        api_key: "test",
        transport: Mock.transport(&Mock.ux/1)
      )

    r = Toolnexus.Client.run(client, "summarize", toolkit)

    # old API called the agent like any tool
    assert r.text =~ "old-api summary:" and r.text =~ "bug at line 42",
           "old API called the agent like any tool: #{r.text}"

    # agent metadata surfaced through the tool result
    assert hd(r.tool_calls).metadata.agent == "explore", "agent metadata surfaced through the tool result"
  end
end
