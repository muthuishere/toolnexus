defmodule Toolnexus.Spike.DemoUxTest do
  @moduledoc """
  SPIKE demo 2 — the Level-1/2/bridge UX (S10–S13) on the same mock LLM
  (port of js/spike/demo-ux.ts; same check names).
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Spike.{Agents, MockLlm, Runtime}

  defp lookup do
    Toolnexus.define_tool(%{
      name: "lookup",
      description: "look something up",
      input_schema: %{"type" => "object", "properties" => %{"q" => %{"type" => "string"}}},
      execute: fn args -> "data(#{args["q"]})" end
    })
  end

  test "S10 Level-1 UX: agent() + team wiring + soul file" do
    soul_path = Path.join(System.tmp_dir!(), "AGENTS-spike.md")
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

    r = Agents.run(coder, %{llm_plug: &MockLlm.ux/1}, "fix the failing test")

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

  test "S12 Level-2 UX: the directory IS the agent · heartbeat · silent OK" do
    dir = Path.join(System.tmp_dir!(), "mia-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    File.write!(Path.join(dir, "SOUL.md"), "You are Mia. Warm, brief.")
    File.write!(Path.join(dir, "USER.md"), "The user is Muthu.")
    File.write!(Path.join(dir, "MEMORY.md"), "- Likes green tea.")
    File.write!(Path.join(dir, "HEARTBEAT.md"), "On heartbeat: if it is watering day, remind to water the plants.")

    mia = Agents.agent_from_dir(dir, %{model: "m-mia"})
    direct = Agents.run(mia, %{llm_plug: &MockLlm.ux/1}, "hello")

    # bootstrap files discovered + injected as ## sections (openclaw order)
    assert direct.text == "soul-sections:[SOUL.md,USER.md,MEMORY.md]",
           "bootstrap files discovered + injected as ## sections: #{direct.text}"

    {:ok, reports_acc} = Agent.start_link(fn -> [] end)

    started =
      Agents.start_agent(mia, %{llm_plug: &MockLlm.ux/1}, %{
        every_ms: 25,
        on_report: fn t -> Agent.update(reports_acc, &(&1 ++ [t])) end
      })

    Process.sleep(140)
    started.stop.()

    hb_turns = started.rt |> Runtime.trace() |> Enum.count(&(&1 =~ "idle→running"))
    # heartbeat woke the agent repeatedly
    assert hb_turns >= 2, "heartbeat woke the agent repeatedly, turns=#{hb_turns}"

    reports = Agent.get(reports_acc, & &1)
    # HEARTBEAT_OK wakes stayed SILENT (no reports for quiet beats)
    assert Enum.all?(reports, &(&1 =~ "water")), "HEARTBEAT_OK wakes stayed SILENT: #{inspect(reports)}"

    # agent closed cleanly on stop()
    assert Runtime.snapshot(started.handle).state == :closed, "agent closed cleanly on stop()"
  end

  test "S13 bridge: an Agent IS a Tool in the classic toolkit/client API" do
    explore = Agents.agent("explore", %{does: "read-only research", uses: %{tools: [lookup()]}, model: "m-explore"})
    toolkit = [Agents.as_tool(explore, %{llm_plug: &MockLlm.ux/1})]

    client =
      Toolnexus.Client.create(
        base_url: "http://mock.local",
        style: "openai",
        model: "m-old-api",
        api_key: "spike",
        http_options: [plug: MockLlm.plain_plug(&MockLlm.ux/1)]
      )

    r = Toolnexus.Client.run(client, "summarize", toolkit)

    # old API called the agent like any tool
    assert r.text =~ "old-api summary:" and r.text =~ "bug at line 42",
           "old API called the agent like any tool: #{r.text}"

    # agent metadata surfaced through the tool result
    assert hd(r.tool_calls).metadata.agent == "explore", "agent metadata surfaced through the tool result"
  end
end
