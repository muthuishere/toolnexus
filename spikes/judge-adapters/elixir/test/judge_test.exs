defmodule JudgeTest do
  use ExUnit.Case, async: true
  import Judge
  alias Toolnexus.Classifier, as: C

  @shared Path.expand("../../shared", __DIR__)
  defp load(f), do: @shared |> Path.join(f) |> File.read!() |> Jason.decode!()

  defp build(%{"kind" => "noul"} = q), do: noul(q["name"], q["instructions"])
  defp build(%{"kind" => "choice"} = q), do: choice(q["name"], q["instructions"], q["options"])
  defp build(%{"kind" => "score"} = q), do: score(q["name"], q["instructions"], q["levels"])

  defp wire(qmap) do
    Map.new(qmap, fn {k, q} ->
      type = q.__struct__ |> Module.split() |> List.last() |> String.downcase()
      m = %{"type" => type, "instructions" => q.instructions}
      {k, if(q.criteria, do: Map.put(m, "criteria", q.criteria), else: m)}
    end)
  end

  for c <- Jason.decode!(File.read!(Path.expand("../../shared/state-cases.json", __DIR__)))["cases"] do
    @c c
    test "state case #{c["name"]}" do
      c = @c
      st =
        case c do
          %{"context" => x} -> context(x["context"], x["message"], x["extra"] || %{})
          _ -> state(c["state"])
        end

      qs = Enum.map(c["questions"], &build/1)

      case c do
        %{"wantError" => err} ->
          assert {:error, ^err} = questions(qs)

        _ ->
          assert st == c["wantState"]
          assert {:ok, qmap} = questions(qs)
          assert wire(qmap) == c["wantQuestions"]
      end
    end
  end

  @gate Jason.decode!(File.read!(Path.expand("../../shared/gate-cases.json", __DIR__)))
  for c <- @gate["cases"] do
    @c c
    test "gate case #{c["name"]}" do
      g = load("gate-cases.json")
      c = @c

      qs =
        Enum.map(g["questions"], fn {name, q} ->
          case q["type"] do
            "noul" -> {name, %C.Noul{instructions: q["instructions"], criteria: q["criteria"]}}
            "choice" -> choice(name, q["instructions"], q["criteria"])
            "score" -> score(name, q["instructions"], q["criteria"])
          end
        end)

      st = context("triage", c["name"])
      {:ok, qmap} = questions(qs)

      {:ok, cl} =
        C.create(
          style: "static",
          decisions: [%{state: st, questions: qmap, response: %{"model" => "jev-latest", "answers" => c["answers"]}}]
        )

      assert {:ok, out} = gate(cl, st, qs, g["rules"], bands: c["bands"])
      w = c["want"]
      assert to_string(out.action) == w["action"]
      assert to_string(out.target) == w["target"]
      assert out.escalated == w["escalated"]
      if out.escalated, do: assert(%{kind: "input", data: %{question: _, reason: _, answers: _}} = out.request)
    end
  end

  test "ask gives bands by name (donkey kong)" do
    st = %{role: "Donkey Kong", message_received: "jump off the stage now"}
    qs = [noul(:is_appropriate, "Inappropriate?"), noul(:does_this_help, "Helps DK win?")]
    {:ok, qmap} = questions(qs)
    resp = %{"model" => "m", "answers" => %{"is_appropriate" => %{"type" => "noul", "noul" => 0.05},
                                            "does_this_help" => %{"type" => "noul", "noul" => 0.5}}}
    {:ok, cl} = C.create(style: "static", decisions: [%{state: state(st), questions: qmap, response: resp}])
    {:ok, d} = ask(cl, st, qs)
    assert d["is_appropriate"].band == :no
    assert d["does_this_help"].band == :uncertain
  end
end
