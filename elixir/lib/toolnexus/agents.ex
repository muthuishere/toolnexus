defmodule Toolnexus.Agents do
  @moduledoc """
  Sub-agents — the Level-1 surface over the agent runtime (SPEC §7D).

  One axiom: **an Agent is a Tool** — (system prompt × a filtered toolkit view ×
  the §8 client loop), invocable as a uniform `Toolnexus.Tool`. One new noun:
  `agent/2`. Everything compiles to the six runtime verbs.

      explore = Toolnexus.Agents.agent("explore", does: "read-only research", uses: %{tools: [lookup]})

      coder =
        Toolnexus.Agents.agent("coder",
          does: "implements changes",
          soul_file: "AGENTS.md",
          team: [explore]
        )

      r = Toolnexus.Agents.run(coder, [transport: transport, llm: %{model: "gpt-4o-mini"}], "fix the failing test")
      r.text

  `as_tool/2` is the bridge the other way: it turns an agent into a `%Toolnexus.Tool{}`
  for the classic API's extra tools. If the agent suspends durably (§10), the tool
  result carries `metadata.pending`, and a §10 retry with `ctx.answer` resumes it —
  agent suspension and tool suspension are the same machinery.
  """

  alias Toolnexus.Agents.Runtime
  alias Toolnexus.ToolResult

  defmodule AgentDef do
    @moduledoc "An agent definition — a name plus its spec (see `Toolnexus.Agents.agent/2`)."
    @enforce_keys [:name, :spec]
    defstruct [:name, :spec]

    @type t :: %__MODULE__{name: String.t(), spec: map()}
  end

  @doc """
  The one new noun. Spec fields (§7D Level-1 surface):

    * `:does` — required routing description (advertised to delegating parents)
    * `:uses` — toolkit view, `%{tools: [%Toolnexus.Tool{}]}`
    * `:soul` — inline system prompt, or `:soul_file` — path to read it from
    * `:team` — list of `agent/2` values this agent may delegate to via `task`
      (children never inherit delegation — recursion is opt-in per definition)
    * `:budget` — `%{max_turns, max_tokens, max_tool_calls, max_wall_ms,
      max_children, max_concurrent, max_depth}`
    * `:model` — default `"inherit"` (the runtime's `llm.model`)
    * `:wait_for` — §10 interpreter authority (`(Request -> Answer)`)
    * `:on_spawn` / `:on_close` — lifecycle callbacks
  """
  @spec agent(String.t(), keyword() | map()) :: AgentDef.t()
  def agent(name, spec), do: %AgentDef{name: name, spec: Map.new(spec)}

  @doc """
  Collect this agent + its whole team graph into a runtime registry — the
  transitive closure of the entry agent's team graph; unreachable agents are
  not present (§7D team scoping).
  """
  @spec registry(AgentDef.t(), map()) :: map()
  def registry(%AgentDef{} = a, acc \\ %{}) do
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
          # task targets = ONLY this agent's team, sorted (never the whole registry)
          task_targets: team |> Enum.map(& &1.name) |> Enum.sort()
        })

      Enum.reduce(team, acc, fn t, acc -> registry(t, acc) end)
    end
  end

  @doc """
  Level 1: one-shot — build a runtime, run the agent to completion, tear down.

  `rt_opts` are `Toolnexus.Agents.Runtime` options (`:transport`, `:llm`, ...).
  Returns `%{text, is_error, status, pending, turns, total_tokens}`. When the run
  suspends durably (`status: "pending"`), the live runtime rides along under
  `:runtime` so the host can `Runtime.resume/2` and must shut it down itself;
  otherwise the runtime is already torn down.
  """
  @spec run(AgentDef.t(), keyword() | map(), String.t()) :: map()
  def run(%AgentDef{} = a, rt_opts, prompt) do
    rt = Runtime.new(rt_opts |> Map.new() |> Map.put(:registry, registry(a)))

    case Runtime.spawn_agent(rt, Runtime.root(rt), a.name) do
      {:error, msg} ->
        Runtime.shutdown(rt)
        %{text: msg, is_error: true, status: "done", pending: nil, turns: 0, total_tokens: 0}

      {:ok, h} ->
        Runtime.wake(rt, h, prompt)
        r = Runtime.wait(rt, h)

        if r.status == "pending" do
          Map.put(r, :runtime, rt)
        else
          Runtime.shutdown(rt)
          r
        end
    end
  end

  @doc """
  The bridge: an Agent IS a Tool — drop it into the classic API's extra tools.

  A durable suspension surfaces as a §10 pending tool result; the client's
  retry-with-`ctx.answer` re-runs the agent with that Answer as its interpreter.
  """
  @spec as_tool(AgentDef.t(), keyword() | map()) :: Toolnexus.Tool.t()
  def as_tool(%AgentDef{} = a, rt_opts) do
    Toolnexus.define_tool(%{
      name: a.name,
      description: a.spec[:does],
      input_schema: %{
        "type" => "object",
        "properties" => %{"prompt" => %{"type" => "string"}},
        "required" => ["prompt"]
      },
      execute: fn args, ctx ->
        prompt = to_string(args["prompt"] || "")

        r =
          if ctx.answer do
            run_with_answer(a, rt_opts, prompt, ctx.answer)
          else
            run(a, rt_opts, prompt)
          end

        case r do
          %{status: "pending", runtime: rt} = r ->
            Runtime.shutdown(rt)
            %ToolResult{output: r.pending.prompt, is_error: true, metadata: %{pending: r.pending}}

          r ->
            %ToolResult{
              output: r.text,
              is_error: r.is_error,
              metadata: %{agent: a.name, turns: r.turns, total_tokens: r.total_tokens}
            }
        end
      end
    })
  end

  # §10 retry path: the outer host's Answer becomes this run's interpreter.
  defp run_with_answer(a, rt_opts, prompt, answer) do
    one_shot = fn _req -> answer end
    rt = Runtime.new(rt_opts |> Map.new() |> Map.put(:registry, registry(a)))

    case Runtime.spawn_agent(rt, Runtime.root(rt), a.name) do
      {:error, msg} ->
        Runtime.shutdown(rt)
        %{text: msg, is_error: true, status: "done", pending: nil, turns: 0, total_tokens: 0}

      {:ok, h} ->
        r = Runtime.run_turn(rt, h, prompt, one_shot)

        if r.status == "pending" do
          Map.put(r, :runtime, rt)
        else
          Runtime.shutdown(rt)
          r
        end
    end
  end
end
