defmodule Toolnexus.Agents.Loop do
  @moduledoc """
  Loop — a live execution of an Agent, and the completion gate that stops it
  claiming `done` too early. A layer over the shipped §8 client: nothing here
  changes existing behaviour.

  The placement law this encodes:

      agent spec (the harness) answers "MAY it?"      — capability, ceilings.  Per problem.
      run opts                 answers "with WHAT?"   — model for this call.   Per call.
      Loop                     answers "DID it?"      — status, turns.         Observed.
      none of them             answers "is it RIGHT?" — a tool, skill or agent.

  So a loop takes no options: it is read, not configured.

  A loop is a plain struct rather than a process. It threads history and turn
  counts through `run/3`'s return value, which keeps it usable from anywhere —
  including inside the runtime's own turn process, where a second GenServer would
  just be a supervision problem.
  """

  alias Toolnexus.Client

  defstruct [:agent, :options, :toolkit, status: "idle", turns: 0, history: []]

  @type verdict :: %{ok: boolean(), reason: String.t()}
  @type guardrail :: (map() -> String.t() | nil)

  @typedoc """
  The gate that stops an agent claiming `done` before its work verifies:

    * `:verify` — judges the run; receives the tool calls ACCUMULATED across attempts
    * `:max_attempts` — REQUIRED; an unbounded verify loop is a denial-of-service
      on the caller's own bill
  """
  @type completion :: %{required(:verify) => (Client.RunResult.t() -> verdict()),
                        required(:max_attempts) => pos_integer()}

  @typedoc "What a run reports. `status` reuses the SHIPPED vocabulary."
  @type outcome :: %{
          text: String.t(),
          status: String.t(),
          stopped_by: String.t() | nil,
          attempts: non_neg_integer(),
          turns: non_neg_integer(),
          result: Client.RunResult.t() | nil
        }

  @doc """
  `harness/1` is a NAME, not a type.

  An agent spec already IS the harness — tools, soul, team, budget, model, policy,
  ceilings — so this is the word landing in the API without a second concept to
  learn. `agent("x", harness(does: "..."))` and `agent("x", does: "...")` are
  indistinguishable.
  """
  @spec harness(keyword() | map()) :: keyword() | map()
  def harness(spec), do: spec

  @doc "Open a LIVE EXECUTION of an agent, over client OPTIONS (not a built client)."
  @spec new(map(), keyword() | map(), term()) :: %__MODULE__{}
  def new(agent, options, toolkit) do
    %__MODULE__{agent: agent, options: Map.new(options), toolkit: toolkit}
  end

  @doc """
  Run one request. Returns `{outcome, loop}` — the loop carries forward the turn
  count and the transcript, so a caller may run again on the same conversation.
  """
  @spec run(%__MODULE__{}, String.t(), keyword()) :: {outcome(), %__MODULE__{}}
  def run(%__MODULE__{} = loop, prompt, opts \\ []) do
    spec = loop.agent.spec
    completion = spec[:completion]
    client = Client.create(client_options(loop, spec, opts[:model]))

    state = %{loop: %{loop | status: "running"}, attempts: 0, client: client}

    {result, state} =
      run_gated(
        fn text, st ->
          r = Client.run(st.client, text, st.loop.toolkit, history: st.loop.history)

          {r,
           %{
             st
             | attempts: st.attempts + 1,
               loop: %{st.loop | turns: st.loop.turns + r.turns, history: r.messages}
           }}
        end,
        prompt,
        completion,
        state
      )

    status = result.status || "done"

    if status != "done" do
      stopped_by =
        if result.limit == "completion", do: result.text, else: "run reported #{status}"

      {%{
         text: result.text,
         status: status,
         stopped_by: stopped_by,
         attempts: state.attempts,
         turns: state.loop.turns,
         result: result
       }, %{state.loop | status: status}}
    else
      {%{
         text: result.text,
         status: "done",
         stopped_by: nil,
         attempts: state.attempts,
         turns: state.loop.turns,
         result: result
       }, %{state.loop | status: "idle"}}
    end
  end

  # Applies a per-call model override via `:request_params` (`model` is not in the
  # forbidden set — the client forbids only messages/tools/stream).
  defp client_options(loop, spec, model) do
    opts = loop.options

    opts =
      if spec[:soul] && !opts[:system_prompt],
        do: Map.put(opts, :system_prompt, spec[:soul]),
        else: opts

    opts = Map.put(opts, :hooks, guarded_hooks(spec[:guardrails], spec[:hooks] || opts[:hooks]))

    if model && model != "" do
      params = Map.put(opts[:request_params] || %{}, "model", model)
      Map.put(opts, :request_params, params)
    else
      opts
    end
  end

  @doc """
  Compile guardrails into one `:before_tool` with FIRST-DENY-WINS, composed ahead
  of any hook already set. No guardrails ⇒ `hooks` is returned untouched, so absent
  is byte-identical.
  """
  @spec guarded_hooks([guardrail()] | nil, map() | nil) :: map() | nil
  def guarded_hooks(guardrails, hooks) when guardrails in [nil, []], do: hooks

  def guarded_hooks(guardrails, hooks) do
    base = hooks || %{}
    prior = base[:before_tool]

    before_tool = fn ev ->
      denial =
        Enum.find_value(guardrails, fn rail ->
          case rail.(ev) do
            verdict when is_binary(verdict) and verdict != "" and verdict != "allow" -> verdict
            _ -> nil
          end
        end)

      cond do
        denial != nil -> %{result: %Toolnexus.ToolResult{output: "denied: #{denial}", is_error: true}}
        prior != nil -> prior.(ev)
        true -> nil
      end
    end

    Map.put(base, :before_tool, before_tool)
  end

  @doc """
  The built-in completion verifier. Reads the SHIPPED `todowrite` builtin's result
  metadata and requires every item to be checked.

  Structural, not domain: it counts unchecked boxes and never learns what a todo
  means, so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒
  pass, so the gate never punishes an agent that does not use the builtin.
  """
  @spec all_todos_done(Client.RunResult.t()) :: verdict()
  def all_todos_done(%{tool_calls: calls}) do
    calls
    |> Enum.reverse()
    |> Enum.find(fn c -> field(c, :name) == "todowrite" end)
    |> case do
      nil ->
        %{ok: true, reason: ""}

      call ->
        metadata = field(call, :metadata) || %{}
        todos = metadata["todos"] || metadata[:todos]

        if is_list(todos) do
          open =
            todos
            |> Enum.reject(&(field(&1, :completed) == true))
            |> Enum.map(&to_string(field(&1, :text) || ""))

          if open == [],
            do: %{ok: true, reason: ""},
            else: %{
              ok: false,
              reason: "#{length(open)} item(s) still open: #{Enum.join(open, "; ")}"
            }
        else
          %{ok: true, reason: ""}
        end
    end
  end

  defp field(m, key) when is_map(m), do: Map.get(m, key) || Map.get(m, to_string(key))
  defp field(_, _), do: nil

  @doc """
  Wrap a client run with the completion gate. SHARED by the standalone loop and the
  §7D runtime turn, so a delegated child gets exactly the same guarantee as a
  directly-driven one.

  `ask` takes `(prompt, state)` and returns `{result, state}`, which is what lets
  the same function serve a struct-threading loop and a process-held turn.

  Rule 2 in force: a run that is `pending` (suspended on a human) or otherwise
  non-done already carries its own reason, so the gate never re-judges it. That
  keeps `pending` and `incomplete` distinct — the caller can always tell whether it
  owes an Answer or a fix.
  """
  @spec run_gated((String.t(), term() -> {Client.RunResult.t(), term()}), String.t(),
                  completion() | nil, term()) :: {Client.RunResult.t(), term()}
  def run_gated(ask, prompt, nil, state), do: ask.(prompt, state)

  def run_gated(ask, prompt, completion, state) do
    max_attempts = completion[:max_attempts]

    unless is_integer(max_attempts) and max_attempts >= 1 do
      raise ArgumentError, "toolnexus: completion.max_attempts must be an integer >= 1"
    end

    unless is_function(completion[:verify], 1) do
      raise ArgumentError, "toolnexus: completion.verify is required"
    end

    gate_loop(ask, prompt, completion, state, 1, [], "", nil)
  end

  defp gate_loop(ask, prompt, completion, state, attempt, accumulated, reason, last)
       when attempt > completion.max_attempts do
    _ = ask
    _ = prompt
    _ = state
    _ = accumulated

    # Structured, not prose: `limit` is how a caller (and the §7D runtime) tells
    # WHICH limit stopped the run. `text` carries the human reason.
    {%{
       last
       | status: "incomplete",
         limit: "completion",
         text: "completion.verify failed #{completion.max_attempts}x: #{reason}"
     }, state}
  end

  defp gate_loop(ask, prompt, completion, state, attempt, accumulated, reason, _last) do
    text = if attempt == 1, do: prompt, else: "Your work did not verify: #{reason}. Fix it and finish."
    {r, state} = ask.(text, state)

    # The gate judges the ACCUMULATED work, so an agent cannot escape it by
    # declining to re-declare its plan on a retry.
    accumulated = accumulated ++ r.tool_calls
    r = %{r | tool_calls: accumulated, tool_call_count: length(accumulated)}

    cond do
      r.status != nil and r.status != "done" ->
        # The run stopped for its own reason (suspension, budget). If the gate was
        # mid-retry the caller must learn BOTH — otherwise a budget stop masks the
        # verification failure and they never see why it was looping.
        r =
          if reason != "" and r.status != "pending",
            do: %{r | text: "#{r.text} [while verifying: attempt #{attempt} last failed: #{reason}]"},
            else: r

        {r, state}

      true ->
        case completion.verify.(r) do
          %{ok: true} ->
            {r, state}

          %{ok: false} = v ->
            why = if v[:reason] in [nil, ""], do: "unspecified", else: v.reason
            gate_loop(ask, prompt, completion, state, attempt + 1, accumulated, why, r)
        end
    end
  end
end
