defmodule Toolnexus.Judge do
  @moduledoc """
  Simple judgments — `ask` / `gate` (SPEC.md §8B, change `add-judge-adapters`).

  A thin layer over any `Toolnexus.Classifier`; the wire is unchanged. Builders
  produce exactly the §8B `evaluate(state, questions)` inputs, so the request body
  is byte-identical to hand-written maps.

      import Toolnexus.Judge
      state = state("You are Donkey Kong, you want to win.", %{message_received: msg})

      {:ok, answers} =
        ask(c, state, [
          noul(:is_appropriate, "Does message_received contain harmful language?"),
          noul(:does_this_help, "Does message_received help Donkey Kong win?")
        ])

      answers["is_appropriate"].band   #=> :yes | :no | :uncertain

  The role lives in the STATE, never in question instructions, and each question
  names the state field it judges (ADR 0035 D7).
  """
  alias Toolnexus.Classifier, as: C

  @default_bands %{low: 0.30, high: 0.70}

  @doc "The default cut-points: `%{low: 0.30, high: 0.70}`, exclusive on the confident side."
  def default_bands, do: @default_bands

  # ------------------------------------------------------------------ builders

  @doc "A named noul question (probability a statement holds)."
  def noul(name, instructions, criteria \\ nil),
    do: {to_string(name), %C.Noul{instructions: instructions, criteria: criteria}}

  @doc "A named choice question over `options` (id -> what picking it MEANS)."
  def choice(name, instructions, options),
    do: {to_string(name), C.choice_over(instructions, options)}

  @doc "A named score question over ORDERED `levels`."
  def score(name, instructions, levels),
    do: {to_string(name), %C.Score{instructions: instructions, criteria: levels}}

  @doc """
  An ordered question list -> the §8B questions map. A duplicate name is an error
  naming it, before any request. A map is passed through unchanged.
  """
  @spec questions([{String.t(), C.question()}] | map()) :: {:ok, map()} | {:error, String.t()}
  def questions(qs) when is_map(qs), do: {:ok, qs}

  def questions(list) when is_list(list) do
    Enum.reduce_while(list, {:ok, %{}}, fn {name, q}, {:ok, acc} ->
      name = to_string(name)

      if Map.has_key?(acc, name),
        do: {:halt, {:error, "duplicate question name #{inspect(name)}"}},
        else: {:cont, {:ok, Map.put(acc, name, q)}}
    end)
  end

  @doc "The question list (or map) as its §8B wire map."
  @spec wire([{String.t(), C.question()}] | map()) :: {:ok, map()} | {:error, String.t()}
  def wire(qs) do
    with {:ok, m} <- questions(qs), do: {:ok, C.questions_to_wire(m)}
  end

  @doc "A state map with string keys."
  def state(map) when is_map(map), do: stringify(map)
  def state(other), do: other

  @doc """
  `State(role, data)`: the data's fields at the top level plus `role`. A data value
  that is not a map goes under `data`.
  """
  def state(role, data) when is_map(data), do: Map.put(stringify(data), "role", role)
  def state(role, data), do: %{"role" => role, "data" => data}

  @doc "Sugar: context + message (+ extra fields) as the state map."
  def context(context, message, extra \\ %{}),
    do: Map.merge(%{"context" => context, "message" => message}, stringify(extra))

  @doc """
  One-line static classifier from one recorded decision: `state`, the question list
  (or map) and the raw `response` (map or JSON binary). Options pass to `create/1`.
  """
  def static(state, qs, response, opts \\ []) do
    with {:ok, qmap} <- questions(qs) do
      C.create(
        Keyword.merge(opts,
          style: "static",
          decisions: [%{state: state(state), questions: qmap, response: response}]
        )
      )
    end
  end

  # ------------------------------------------------------------------ answers

  defmodule Answer do
    @moduledoc """
    One named answer. `:band` is set for a noul (`:yes | :no | :uncertain`); `:sure`
    for a choice or score. `:raw` is the underlying `*Answer` struct.
    """
    defstruct [:name, :type, :raw, :band, :sure]

    @doc "The one number: noul probability, score value, or choice confidence."
    def value(%__MODULE__{raw: %C.NoulAnswer{noul: n}}), do: n
    def value(%__MODULE__{raw: %C.ScoreAnswer{score: s}}), do: s
    def value(%__MODULE__{raw: %C.ChoiceAnswer{confidence: c}}), do: c

    @doc "The picked option of a choice answer (nil otherwise)."
    def choice(%__MODULE__{raw: %C.ChoiceAnswer{choice: ch}}), do: ch
    def choice(%__MODULE__{}), do: nil

    @doc "True when the answer is not confident enough to act on."
    def uncertain?(%__MODULE__{type: "noul", band: b}), do: b == :uncertain
    def uncertain?(%__MODULE__{sure: s}), do: not s
  end

  @doc "Band a decision's answers by name."
  def answers(%C.Decision{answers: answers}, bands \\ nil) do
    b = bands(bands)
    Map.new(answers, fn {k, a} -> {k, answer(k, a, b)} end)
  end

  defp answer(k, %C.NoulAnswer{noul: p} = a, %{low: lo, high: hi}) do
    band =
      cond do
        p < lo -> :no
        p > hi -> :yes
        true -> :uncertain
      end

    %Answer{name: k, type: "noul", raw: a, band: band}
  end

  defp answer(k, %C.ChoiceAnswer{} = a, %{high: hi}),
    do: %Answer{name: k, type: "choice", raw: a, sure: a.confidence > hi and not a.near_uniform}

  defp answer(k, %C.ScoreAnswer{} = a, %{high: hi}),
    do: %Answer{name: k, type: "score", raw: a, sure: a.confidence > hi}

  # ------------------------------------------------------------------ ask / gate

  @doc "Evaluate once; every answer comes back by name, banded. Options: `:bands`."
  def ask(c, state, qs, opts \\ []) do
    with {:ok, qmap} <- questions(qs),
         {:ok, d} <- C.evaluate(c, state(state), qmap) do
      {:ok, answers(d, Keyword.get(opts, :bands))}
    end
  end

  defmodule Outcome do
    @moduledoc "A gate outcome. `request` is the §10 `input` Request when escalated."
    defstruct action: "", target: "", escalated: false, request: nil
  end

  defmodule Policy do
    @moduledoc """
    `rules` + fall-through. A non-empty `default` is the no-rule-fired action; empty
    escalates (`reason: "no rule fired"`). `skip_uncertain` skips an uncertain rule
    instead of escalating on it.
    """
    defstruct rules: [], default: "", bands: nil, skip_uncertain: false
  end

  @doc """
  Evaluate once and apply `rules` (a list, or a `Policy`) first-match. A rule is
  `%{question:, below: | at_least: | is:, action:, target:}` (atom or string keys).
  An uncertain, unsure or missing answer escalates to `needs_input`. With a plain
  rule list, no rule fired is action `""`.
  """
  def gate(c, state, qs, rules, opts \\ [])

  def gate(c, state, qs, %Policy{} = p, _opts) do
    with {:ok, answers} <- ask(c, state, qs, bands: p.bands), do: {:ok, decide(answers, p)}
  end

  def gate(c, state, qs, rules, opts) do
    with {:ok, answers} <- ask(c, state, qs, opts), do: {:ok, apply_rules(answers, rules)}
  end

  @doc "Pure half of gate: banded answers + rules -> Outcome (no rule fired = action \"\")."
  def apply_rules(answers, rules), do: run(answers, rules, false) || %Outcome{}

  @doc "Pure half of a Policy gate."
  def decide(answers, %Policy{} = p) do
    case run(answers, p.rules, p.skip_uncertain) do
      nil when p.default in [nil, ""] ->
        escalate(-1, nil, "no rule fired", answers)

      nil ->
        %Outcome{action: to_string(p.default)}

      out ->
        out
    end
  end

  defp run(answers, rules, skip?) do
    rules
    |> Enum.with_index()
    |> Enum.find_value(fn {r, i} ->
      r = Map.new(r, fn {k, v} -> {to_string(k), v} end)
      q = to_string(r["question"])

      case check(answers[q], r) do
        {:unsure, _} when skip? and is_map_key(answers, q) -> nil
        {:unsure, why} -> escalate(i, q, why, answers)
        true -> %Outcome{action: to_string(r["action"]), target: to_string(r["target"] || "")}
        false -> nil
      end
    end)
  end

  defp check(nil, _), do: {:unsure, :missing}

  defp check(%Answer{} = a, r) do
    cond do
      Answer.uncertain?(a) -> {:unsure, "uncertain"}
      Map.has_key?(r, "is") -> Answer.choice(a) == r["is"]
      Map.has_key?(r, "below") -> Answer.value(a) < r["below"]
      Map.has_key?(r, "at_least") -> Answer.value(a) >= r["at_least"]
      true -> {:unsure, "rule has no below/at_least/is"}
    end
  end

  defp escalate(i, q, why, answers) do
    reason =
      cond do
        why == :missing -> ~s(missing answer "#{q}")
        q -> "#{why}: #{inspect(q)}"
        true -> why
      end

    %Outcome{
      action: "needs_input",
      escalated: true,
      request: %Toolnexus.Request{
        id: if(q, do: "gate:#{i}:#{q}", else: "gate:default"),
        kind: "input",
        prompt: "The classifier is unsure (#{reason}); decide the next action.",
        data: %{"question" => q, "reason" => reason, "answers" => answers}
      }
    }
  end

  defp bands(nil), do: @default_bands

  defp bands(b),
    do:
      Map.merge(
        @default_bands,
        Map.new(b, fn {k, v} -> {String.to_existing_atom(to_string(k)), v} end)
      )

  defp stringify(m), do: Map.new(m, fn {k, v} -> {to_string(k), v} end)
end

defmodule Toolnexus.Judge.Tape do
  @moduledoc """
  Record live decisions by call name, replay them with no network. A replay miss
  names the key.

      tape = Tape.new()
      {:ok, rec} = Tape.record(tape, "plan", live)   # evaluates live, stores the decision
      {:ok, rep} = Tape.replay(tape, "plan")          # returns it, no request sent
  """
  alias Toolnexus.Classifier, as: C

  @doc "A new, empty tape (optionally seeded with `%{name => Decision}`)."
  def new(seed \\ %{}) do
    {:ok, pid} = Agent.start(fn -> seed end)
    pid
  end

  @doc "Every recorded decision by call name."
  def decisions(tape), do: Agent.get(tape, & &1)

  @doc "A classifier that evaluates through `live` and records the decision under `name`."
  def record(tape, name, %C{} = live) do
    C.create(
      style: "custom",
      evaluate: fn state, qs ->
        with {:ok, d} <- C.evaluate(live, state, qs) do
          Agent.update(tape, &Map.put(&1, to_string(name), d))
          {:ok, d}
        end
      end
    )
  end

  @doc "A classifier that replays the decision recorded under `name`."
  def replay(tape, name) do
    name = to_string(name)

    C.create(
      style: "custom",
      evaluate: fn _state, _qs ->
        case Map.fetch(decisions(tape), name) do
          {:ok, d} -> {:ok, d}
          :error -> {:error, "tape: no recorded decision for call #{inspect(name)}"}
        end
      end
    )
  end
end
