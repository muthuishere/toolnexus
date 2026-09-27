defmodule Judge do
  @moduledoc """
  Spike: a judgment as simple as the request.

      import Judge
      {:ok, d} = Judge.ask(c, %{role: role, message_received: msg}, [
        noul(:is_appropriate, "Does the message contain inappropriate language?"),
        noul(:does_this_help, "Does this help donkey kong win?")
      ])
      d["is_appropriate"].band   #=> :yes | :no | :uncertain
  """
  alias Toolnexus.Classifier, as: C

  @bands %{low: 0.30, high: 0.70}

  # ------------------------------------------------------------ builders

  def noul(name, instructions), do: {to_string(name), %C.Noul{instructions: instructions}}

  def choice(name, instructions, options),
    do: {to_string(name), C.choice_over(instructions, options)}

  def score(name, instructions, levels),
    do: {to_string(name), %C.Score{instructions: instructions, criteria: levels}}

  @doc "Sugar: context + message (+ extra keys) as the state map."
  def context(context, message, extra \\ %{}),
    do: Map.merge(%{"context" => context, "message" => message}, stringify(extra))

  @doc "Ordered question list -> the §8B questions map. Duplicate names are an error."
  def questions(list) do
    Enum.reduce_while(list, {:ok, %{}}, fn {name, q}, {:ok, acc} ->
      if Map.has_key?(acc, name),
        do: {:halt, {:error, "duplicate question name #{inspect(name)}"}},
        else: {:cont, {:ok, Map.put(acc, name, q)}}
    end)
  end

  def state(map) when is_map(map), do: stringify(map)
  def state(other), do: other

  # ------------------------------------------------------------ ask / gate

  @doc "Evaluate once; every answer comes back by name with a `:band`."
  def ask(c, state, qs, opts \\ []) do
    b = bands(opts)

    with {:ok, qmap} <- questions(qs),
         {:ok, d} <- C.evaluate(c, state(state), qmap) do
      {:ok, Map.new(d.answers, fn {k, a} -> {k, Map.put(a, :band, band(a, b))} end)}
    end
  end

  @doc """
  Evaluate once, apply `rules` first-match. Rules: `%{question:, below: | at_least: | is:,
  action:, target:}`. An uncertain/missing answer on rule i escalates to `:needs_input`.
  """
  def gate(c, state, qs, rules, opts \\ []) do
    with {:ok, answers} <- ask(c, state, qs, opts), do: {:ok, apply_rules(answers, rules)}
  end

  @doc "Pure half of gate: banded answers + rules -> outcome."
  def apply_rules(answers, rules) do
    rules
    |> Enum.with_index()
    |> Enum.find_value(%{action: nil, target: nil, escalated: false, request: nil}, fn {r, i} ->
      r = Map.new(r, fn {k, v} -> {to_atom(k), v} end)

      case check(answers[to_string(r.question)], r) do
        {:unsure, why} ->
          %{action: :needs_input, target: nil, escalated: true, request: request(i, r, why, answers)}

        true ->
          %{action: to_atom(r.action), target: r[:target], escalated: false, request: nil}

        false ->
          nil
      end
    end)
  end

  # ------------------------------------------------------------ internals

  defp check(nil, _), do: {:unsure, "missing answer"}
  defp check(%{band: :uncertain} = a, _), do: {:unsure, "uncertain: #{inspect(Map.delete(a, :band))}"}
  defp check(%C.ChoiceAnswer{choice: ch}, %{is: is}), do: ch == is
  defp check(a, %{below: v}), do: value(a) < v
  defp check(a, %{at_least: v}), do: value(a) >= v
  defp check(_, _), do: {:unsure, "rule does not fit the answer type"}

  defp value(%C.NoulAnswer{noul: n}), do: n
  defp value(%C.ScoreAnswer{score: s}), do: s
  defp value(_), do: nil

  defp band(%C.NoulAnswer{noul: p}, %{low: lo}) when p < lo, do: :no
  defp band(%C.NoulAnswer{noul: p}, %{high: hi}) when p > hi, do: :yes
  defp band(%C.NoulAnswer{}, _), do: :uncertain
  defp band(%C.ChoiceAnswer{near_uniform: false, confidence: c}, %{high: hi}) when c > hi, do: :yes
  defp band(%C.ScoreAnswer{confidence: c}, %{high: hi}) when c > hi, do: :yes
  defp band(_, _), do: :uncertain

  defp request(i, r, why, answers) do
    %{
      id: "gate:#{i}:#{r.question}",
      kind: "input",
      prompt: "Classifier is unsure about #{inspect(r.question)} (#{why}). Decide rule #{i} (#{r.action}).",
      data: %{question: r.question, reason: why, answers: answers}
    }
  end

  defp bands(opts), do: Map.merge(@bands, Map.new(Keyword.get(opts, :bands) || %{}, fn {k, v} -> {to_atom(k), v} end))

  defp stringify(m), do: Map.new(m, fn {k, v} -> {to_string(k), v} end)
  defp to_atom(a) when is_atom(a), do: a
  defp to_atom(s), do: String.to_atom(s)
end
