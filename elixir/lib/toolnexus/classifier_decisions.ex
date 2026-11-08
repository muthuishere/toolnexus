# The Decisions API wire (SPEC.md §8B "Decisions wire", OpenSpec change
# add-classifier-openai-decisions, issue #157). OpenAI's POST /v1/decisions takes
# the same three question kinds as System One under other names (predicate /
# choice / score) as an ORDERED LIST, and answers in that order. This module maps
# a §8B question set onto that list and reads the answers back into the System
# One reply shape, so a caller never sees which wire answered.

defmodule Toolnexus.Classifier.Decisions do
  @moduledoc false

  alias Toolnexus.Classifier.{Canonical, Choice, Noul, Raw, Score, Unsupported}

  @max_questions 400
  @max_choice_options 255

  @doc "The Decisions API's per-request question cap."
  def max_questions, do: @max_questions

  @doc """
  The question set as the Decisions API list, in ASCII key order. Caller keys are
  NOT sent: they are addressing, and answers come back in request order. Every
  check here fires before any request.
  """
  def questions(backend, model, questions) do
    n = map_size(questions)

    if n > @max_questions do
      {:error,
       "classifier: the decisions wire takes at most #{@max_questions} questions, got #{n}"}
    else
      questions
      |> sorted()
      |> Enum.reduce_while({:ok, []}, fn {k, q}, {:ok, acc} ->
        case question(backend, model, k, q) do
          {:ok, item} -> {:cont, {:ok, [item | acc]}}
          err -> {:halt, err}
        end
      end)
      |> case do
        {:ok, items} -> {:ok, Enum.reverse(items)}
        err -> err
      end
    end
  end

  defp question(_b, _m, _k, %Noul{instructions: i, criteria: c}) do
    instr =
      case c do
        nil ->
          i

        c ->
          c = Map.new(c, fn {k, v} -> {to_string(k), v} end)
          i <> "\n\nTrue means: " <> c["true"] <> "\nFalse means: " <> c["false"]
      end

    {:ok, %{"type" => "predicate", "instructions" => instr}}
  end

  defp question(_b, _m, k, %Choice{instructions: i, criteria: c}) do
    if map_size(c) < 2 do
      {:error,
       "classifier: question #{inspect(k)}: the decisions wire needs 2..#{@max_choice_options} choice options, got #{map_size(c)}"}
    else
      choices =
        c
        |> Enum.map(fn {id, d} -> {to_string(id), d} end)
        |> Enum.sort()
        |> Enum.map(fn
          {id, d} when d in [nil, ""] -> %{"value" => id}
          {id, d} -> %{"value" => id, "description" => d}
        end)

      {:ok, %{"type" => "choice", "instructions" => i, "choices" => choices}}
    end
  end

  defp question(_b, _m, _k, %Score{instructions: i, criteria: c}),
    do:
      {:ok,
       %{
         "type" => "score",
         "instructions" => i,
         "levels" => Enum.map(c, &%{"label" => to_string(&1)})
       }}

  defp question(b, m, _k, %Raw{type: t}),
    do: {:error, %Unsupported{backend: b, model: m, feature: "raw question type #{inspect(t)}"}}

  @doc """
  The state as sent: a string verbatim, anything else as its canonical JSON text;
  with images, one user message holding that text then the images as data URLs,
  in collection order.
  """
  def input(state, imgs) do
    text = if is_binary(state), do: state, else: Canonical.encode(state)

    case imgs do
      [] ->
        text

      imgs ->
        images =
          Enum.map(imgs, fn {_ref, fmt, data} ->
            %{"type" => "input_image", "image_url" => "data:image/#{fmt};base64,#{data}"}
          end)

        [%{"role" => "user", "content" => [%{"type" => "input_text", "text" => text} | images]}]
    end
  end

  @doc """
  A Decisions API reply as a System One-shaped body (canonical JSON), so decode,
  validation and the cache stay one path. `calibrated` is false: a Decisions
  model is not a System One model.
  """
  def reply(raw, questions) do
    decoded = if is_binary(raw), do: Jason.decode(raw), else: {:ok, raw}

    case decoded do
      {:ok, %{"answers" => answers} = r} when is_list(answers) ->
        keys = sorted(questions)

        if length(answers) != length(keys) do
          {:error,
           "classifier: decisions: #{length(answers)} answers for #{length(keys)} questions"}
        else
          keys
          |> Enum.zip(answers)
          |> Enum.reduce_while({:ok, %{}}, fn {{k, q}, a}, {:ok, acc} ->
            case answer(k, q, a) do
              {:ok, v} -> {:cont, {:ok, Map.put(acc, k, v)}}
              err -> {:halt, err}
            end
          end)
          |> case do
            {:ok, out} ->
              usage = if is_map(r["usage"]), do: r["usage"], else: %{}

              {:ok,
               Canonical.encode(%{
                 "answers" => out,
                 "calibrated" => false,
                 "model" => r["model"] || "",
                 "usage" => %{
                   "input_tokens" => usage["input_tokens"] || 0,
                   "output_tokens" => usage["output_tokens"] || 0
                 }
               })}

            err ->
              err
          end
        end

      _ ->
        {:error, "classifier: decisions: malformed reply"}
    end
  end

  defp answer(k, _q, %{"type" => "refusal"}),
    do: {:error, "classifier: decisions: question #{inspect(k)} was refused"}

  defp answer(k, q, %{} = a) do
    want =
      case q do
        %Noul{} -> "predicate"
        %Choice{} -> "choice"
        %Score{} -> "score"
      end

    if a["type"] != want do
      {:error,
       "classifier: decisions: answer #{inspect(k)} is a #{a["type"]} answer, not #{want}"}
    else
      probs =
        Map.new(List.wrap(a["probabilities"]), fn p ->
          {value(p["value"]), p["probability"] || 0}
        end)

      {:ok, shape(q, a, probs)}
    end
  end

  defp answer(_k, _q, _a), do: {:error, "classifier: decisions: malformed reply"}

  defp shape(%Noul{}, a, _probs), do: %{"type" => "noul", "noul" => a["probability"] || 0}

  defp shape(%Choice{}, a, probs),
    do: %{
      "type" => "choice",
      "choice" => value(a["choice"]),
      "probabilities" => probs,
      "confidence" => a["confidence"] || 0
    }

  defp shape(%Score{criteria: c}, a, probs),
    do: %{
      "type" => "score",
      "score" => a["score"] || 0,
      "legend" =>
        c |> Enum.with_index() |> Map.new(fn {l, j} -> {Integer.to_string(j), to_string(l)} end),
      "probabilities" => probs,
      "confidence" => a["confidence"] || 0
    }

  # A choice value or a level index as the §8B key: a string as written, a boolean
  # as "true"/"false", a number in its shortest form.
  defp value(v) when is_binary(v), do: v
  defp value(v) when is_boolean(v), do: to_string(v)
  defp value(v) when is_integer(v), do: Integer.to_string(v)

  defp value(v) when is_float(v) and v == trunc(v) and abs(v) < 1.0e15,
    do: Integer.to_string(trunc(v))

  defp value(v) when is_float(v), do: :erlang.float_to_binary(v, [:short])
  defp value(nil), do: ""
  defp value(v), do: inspect(v)

  defp sorted(questions),
    do: questions |> Enum.map(fn {k, q} -> {to_string(k), q} end) |> Enum.sort_by(&elem(&1, 0))
end
