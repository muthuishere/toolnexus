defmodule Spike.Judge do
  @moduledoc """
  `judge/1` turns a Classifier into a guardrail: the same `(event -> verdict)`
  shape the shipped port already composes with first-deny-wins
  (`Toolnexus.Agents.Loop.guarded_hooks/2`, `elixir/lib/toolnexus/agents/loop.ex:147`).

  Options:
    * `:on`      — tool name(s) this judge applies to; anything else is allowed untouched
    * `:ask`     — `%{key => Question}`, the questions put to the classifier
    * `:state`   — `(event -> term())`, what the classifier is shown
    * `:rule`    — `%{key => [{upper_bound, verdict}, ...]}` bands, upper-exclusive,
                   evaluated in order; verdict is `:allow | :ask | :deny`
    * `:backend` — `(request -> Decision)`
  """

  alias Spike.Classifier
  alias Spike.Classifier.{NoulAnswer, ScoreAnswer}

  @type verdict :: :allow | :ask | :deny

  @doc "Evaluate and return `{verdict, reason}`."
  @spec decide(keyword(), map()) :: {verdict(), String.t()}
  def decide(opts, event) do
    if applies?(opts[:on], event) do
      decision =
        opts[:backend].(
          Classifier.request(opts[:state].(event), opts[:ask])
        )

      opts[:rule]
      |> Enum.map(fn {key, bands} -> band(bands, decision.answers[key], key) end)
      |> Enum.max_by(&rank(elem(&1, 0)))
    else
      {:allow, ""}
    end
  end

  @doc "The guardrail-shaped form: `\"\"` ⇒ allow, a reason string ⇒ denied."
  @spec guardrail(keyword()) :: (map() -> String.t())
  def guardrail(opts) do
    fn event ->
      case decide(opts, event) do
        {:allow, _} -> ""
        {_, reason} -> reason
      end
    end
  end

  defp applies?(nil, _event), do: true
  defp applies?(on, event) when is_list(on), do: event[:tool] in on
  defp applies?(on, event), do: event[:tool] == on

  defp rank(:allow), do: 0
  defp rank(:ask), do: 1
  defp rank(:deny), do: 2

  # Bands read the numeric face of an answer; the struct says which field that is.
  defp band(bands, %ScoreAnswer{score: v} = a, key), do: pick(bands, v, key, legend(a, v))
  defp band(bands, %NoulAnswer{noul: v}, key), do: pick(bands, v, key, nil)

  defp legend(%ScoreAnswer{legend: l}, v) when is_map(l),
    do: l[Integer.to_string(round(v))]

  defp legend(_, _), do: nil

  defp pick([{upper, verdict} | rest], value, key, label) do
    if upper == :infinity or value < upper do
      {verdict, reason(verdict, key, value, label)}
    else
      pick(rest, value, key, label)
    end
  end

  defp reason(:allow, _key, _value, _label), do: ""

  defp reason(verdict, key, value, label) do
    detail = if label, do: " — #{label}", else: ""
    "#{verdict}: #{key} #{fmt(value)}#{detail}"
  end

  defp fmt(v) when is_float(v), do: Float.to_string(v)
  defp fmt(v), do: to_string(v)
end
