defmodule Toolnexus.Spike.Agents do
  @moduledoc """
  SPIKE — Level-1/2 UX sugar over the substrate (port of js/spike/agents.ts).
  One new noun: `agent/2`. Everything compiles to the six verbs in Runtime.
  """

  alias Toolnexus.Spike.Runtime
  alias Toolnexus.ToolResult

  @bootstrap_order ["AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md"]
  @max_bootstrap_file_bytes 2 * 1024 * 1024
  @heartbeat_ok "HEARTBEAT_OK"

  def bootstrap_order, do: @bootstrap_order
  def heartbeat_ok, do: @heartbeat_ok

  @doc "The one new noun."
  def agent(name, spec), do: %{name: name, spec: Map.new(spec)}

  @doc "Collect this agent + its whole team graph into a runtime registry."
  def registry(a, acc \\ %{}) do
    if Map.has_key?(acc, a.name) do
      acc
    else
      spec = a.spec

      soul =
        cond do
          spec[:soul_file] -> File.read!(spec[:soul_file])
          spec[:soul] -> spec[:soul]
          true -> ""
        end

      team = spec[:team] || []

      acc =
        Map.put(acc, a.name, %{
          name: a.name,
          description: spec[:does],
          system_prompt: soul,
          model: spec[:model] || "inherit",
          tools: get_in(spec, [:uses, :tools]),
          budget: spec[:budget],
          wait_for: spec[:wait_for],
          on_spawn: spec[:on_spawn],
          on_close: spec[:on_close],
          # team scoping: task targets = ONLY this agent's team (not the whole registry)
          task_targets: Enum.map(team, & &1.name)
        })

      Enum.reduce(team, acc, fn t, acc -> registry(t, acc) end)
    end
  end

  @doc "Level 1: one-shot — build a runtime, run to completion, tear down."
  def run(a, rt_opts, prompt) do
    rt = Runtime.new(Map.put(Map.new(rt_opts), :registry, registry(a)))

    case Runtime.spawn_agent(rt, rt.root, a.name) do
      {:error, msg} ->
        %{text: msg, is_error: true, status: "done", pending: nil, turns: 0, total_tokens: 0, rt: rt}

      {:ok, h} ->
        Runtime.wake(rt, h, prompt)
        r = Runtime.wait(rt, h)
        if r.status != "pending", do: Runtime.close(rt)
        # exposed for durable resume
        Map.put(r, :rt, rt)
    end
  end

  @doc "Bridge: an Agent IS a Tool — drop it into the OLD API's extra tools."
  def as_tool(a, rt_opts) do
    Toolnexus.define_tool(%{
      name: a.name,
      description: a.spec[:does],
      input_schema: %{
        "type" => "object",
        "properties" => %{"prompt" => %{"type" => "string"}},
        "required" => ["prompt"]
      },
      execute: fn args ->
        r = run(a, rt_opts, to_string(args["prompt"] || ""))

        %ToolResult{
          output: r.text,
          is_error: r.is_error,
          metadata: %{agent: a.name, turns: r.turns, total_tokens: r.total_tokens}
        }
      end
    })
  end

  @doc """
  Level 2: the directory IS the agent. Discovers (all optional, openclaw order):
  AGENTS.md, SOUL.md, IDENTITY.md, USER.md, TOOLS.md, HEARTBEAT.md, MEMORY.md —
  injected as `## <file>` sections, capped at 2MB per file.
  """
  def agent_from_dir(dir, opts \\ %{}) do
    opts = Map.new(opts)

    sections =
      for f <- @bootstrap_order, path = Path.join(dir, f), File.exists?(path) do
        body = File.read!(path)

        body =
          if byte_size(body) > @max_bootstrap_file_bytes,
            do: binary_part(body, 0, @max_bootstrap_file_bytes) <> "\n[truncated: file exceeds bootstrap cap]",
            else: body

        "## #{f}\n\n#{String.trim(body)}"
      end

    name = opts[:name] || Path.basename(dir)

    spec =
      Map.merge(
        %{does: "persona agent from #{dir}", soul: Enum.join(sections, "\n\n")},
        Map.drop(opts, [:name])
      )

    agent(name, spec)
  end

  @doc """
  Level 2: start a long-lived persona — the heartbeat posts a tick to its own
  inbox (unsolicited rail; ticks coalesce) and wakes it; HEARTBEAT_OK results
  stay silent.
  """
  def start_agent(a, rt_opts, opts \\ %{}) do
    opts = Map.new(opts)
    rt = Runtime.new(Map.put(Map.new(rt_opts), :registry, registry(a)))
    {:ok, h} = Runtime.spawn_agent(rt, rt.root, a.name)
    on_report = opts[:on_report]

    looper =
      if opts[:every_ms] do
        every = opts[:every_ms]

        spawn(fn ->
          :timer.send_interval(every, :tick)
          heartbeat_loop(rt, h, on_report)
        end)
      end

    %{
      handle: h,
      rt: rt,
      stop: fn ->
        if looper, do: Process.exit(looper, :kill)
        Runtime.close(rt)
      end
    }
  end

  defp heartbeat_loop(rt, h, on_report) do
    receive do
      :tick ->
        Runtime.post(rt, h, %{from: "clock", channel: "timer", text: "tick"})

        if Runtime.snapshot(h).state == :idle do
          r =
            Runtime.run_turn(
              rt,
              h,
              "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK."
            )

          if not r.is_error and not String.contains?(r.text, @heartbeat_ok) and is_function(on_report, 1),
            do: on_report.(r.text)
        end

        heartbeat_loop(rt, h, on_report)
    end
  end
end
