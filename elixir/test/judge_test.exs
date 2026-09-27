defmodule Toolnexus.JudgeTest do
  use ExUnit.Case, async: true

  import Toolnexus.Judge
  alias Toolnexus.Classifier, as: C
  alias Toolnexus.Judge
  alias Toolnexus.Judge.{Answer, Outcome, Policy, Tape}

  @dir Path.expand("../../examples/judge/adapters", __DIR__)
  defp load(n), do: @dir |> Path.join(n) |> File.read!() |> Jason.decode!()

  defp build(%{"kind" => "noul", "name" => n, "instructions" => i}), do: noul(n, i)
  defp build(%{"kind" => "choice", "name" => n, "instructions" => i, "options" => o}), do: choice(n, i, o)
  defp build(%{"kind" => "score", "name" => n, "instructions" => i, "levels" => l}), do: score(n, i, l)

  defp qmap(wire) do
    Map.new(wire, fn
      {k, %{"type" => "noul"} = q} -> {k, %C.Noul{instructions: q["instructions"], criteria: q["criteria"]}}
      {k, %{"type" => "choice"} = q} -> {k, %C.Choice{instructions: q["instructions"], criteria: q["criteria"]}}
      {k, %{"type" => "score"} = q} -> {k, %C.Score{instructions: q["instructions"], criteria: q["criteria"]}}
    end)
  end

  # ------------------------------------------------------------ state cases

  for %{"name" => name} = kase <- Jason.decode!(File.read!(Path.expand("../../examples/judge/adapters/state-cases.json", __DIR__)))["cases"] do
    @kase kase
    test "state case #{name}" do
      k = @kase
      qs = Enum.map(k["questions"], &build/1)

      st =
        case k do
          %{"context" => cx} -> context(cx["context"], cx["message"], cx["extra"] || %{})
          %{"state" => s} -> state(s)
        end

      case k do
        %{"wantError" => want} ->
          assert {:error, ^want} = questions(qs)
          assert {:error, ^want} = wire(qs)

        _ ->
          assert st == k["wantState"]
          assert {:ok, w} = wire(qs)
          assert w == k["wantQuestions"]
      end
    end
  end

  test "duplicate name: no request is sent" do
    {:ok, c} = C.create(style: "custom", evaluate: fn _, _ -> flunk("request sent") end)
    assert {:error, "duplicate question name \"x\""} = ask(c, %{}, [noul(:x, "a"), noul("x", "b")])
  end

  test "State(role, data) and non-object wrapping" do
    assert state("r", %{message_received: "m"}) == %{"role" => "r", "message_received" => "m"}
    assert state("r", "plain text") == %{"role" => "r", "data" => "plain text"}
    assert state("s") == "s"
  end

  # ------------------------------------------------------------ byte identity

  test "builder request body is byte-identical to hand-written §8B maps" do
    k = hd(load("state-cases.json")["cases"])
    {:ok, built} = questions(Enum.map(k["questions"], &build/1))
    hand = qmap(k["wantQuestions"])
    assert C.canonical_request("jev-latest", built) == C.canonical_request("jev-latest", hand)

    me = self()

    transport = fn req ->
      send(me, {:body, req.body})
      {:ok, %{status: 200, headers: %{}, body: ~s({"answers":{}})}}
    end

    {:ok, c} = C.create(transport: transport, api_key_env: "TOOLNEXUS_TEST_UNSET_KEY")
    st = state(k["state"]["role"], Map.delete(k["state"], "role"))
    {:ok, _} = C.evaluate(c, st, built)
    assert_receive {:body, b1}
    {:ok, _} = C.evaluate(c, k["wantState"], hand)
    assert_receive {:body, b2}
    assert b1 == b2
  end

  test "question_to_wire is public" do
    assert C.question_to_wire(%C.Noul{instructions: "i"}) == %{"type" => "noul", "instructions" => "i"}
  end

  # ------------------------------------------------------------ gate cases

  @gate Jason.decode!(File.read!(Path.expand("../../examples/judge/adapters/gate-cases.json", __DIR__)))

  for %{"name" => name} = kase <- @gate["cases"] do
    @kase kase
    test "gate case #{name}" do
      g = @gate
      k = @kase
      qs = qmap(g["questions"])
      st = %{"case" => k["name"]}
      {:ok, c} = Judge.static(st, qs, %{"answers" => k["answers"]})
      opts = if k["bands"], do: [bands: k["bands"]], else: []
      assert {:ok, %Outcome{} = o} = gate(c, st, qs, g["rules"], opts)
      assert {o.action, o.target, o.escalated} == {k["want"]["action"], k["want"]["target"], k["want"]["escalated"]}

      if o.escalated do
        assert %Toolnexus.Request{kind: "input", data: %{"reason" => _, "answers" => _}} = o.request
      end
    end
  end

  test "missing answer reason names it" do
    o = apply_rules(%{}, [%{question: "component", is: "pricing", action: "skip_to"}])
    assert o.request.data["reason"] == ~s(missing answer "component")
  end

  # ------------------------------------------------------------ bands / answers

  defp noul_ans(p, b \\ nil), do: answers(%C.Decision{answers: %{"q" => %C.NoulAnswer{noul: p}}}, b)["q"]

  test "bands: exclusive cut-points, custom override, value/choice" do
    assert noul_ans(0.30).band == :uncertain
    assert noul_ans(0.70).band == :uncertain
    assert noul_ans(0.29).band == :no
    assert noul_ans(0.96).band == :yes
    assert Answer.value(noul_ans(0.96)) == 0.96
    assert noul_ans(0.55, %{low: 0.2, high: 0.5}).band == :yes
    assert noul_ans(0.55, %{"high" => 0.5}).band == :yes
    assert Answer.choice(noul_ans(0.5)) == nil

    ch = %C.ChoiceAnswer{choice: "a", confidence: 0.8, near_uniform: true}
    a = answers(%C.Decision{answers: %{"c" => ch}})["c"]
    refute a.sure
    assert Answer.choice(a) == "a"
    assert Answer.value(a) == 0.8
    assert answers(%C.Decision{answers: %{"c" => %{ch | near_uniform: false}}})["c"].sure
    assert Answer.value(answers(%C.Decision{answers: %{"s" => %C.ScoreAnswer{score: 1.5, confidence: 0.9}}})["s"]) == 1.5
    assert Judge.default_bands() == %{low: 0.30, high: 0.70}
  end

  test "rule without an operator escalates" do
    a = %{"q" => noul_ans(0.9)}
    assert %Outcome{escalated: true} = apply_rules(a, [%{"question" => "q", "action" => "x"}])
  end

  # ------------------------------------------------------------ Policy

  test "Policy: no rule fired escalates; default fires; skip_uncertain" do
    a = %{"u" => noul_ans(0.5), "y" => noul_ans(0.9)}
    rules = [%{question: "u", at_least: 0.5, action: "first"}, %{question: "y", at_least: 0.8, action: "second"}]

    o = decide(%{"y" => noul_ans(0.9)}, %Policy{rules: [%{question: "y", below: 0.1, action: "x"}]})
    assert o.escalated and o.request.data["reason"] == "no rule fired"

    assert %Outcome{action: "go", escalated: false} =
             decide(%{"y" => noul_ans(0.9)}, %Policy{rules: [], default: "go"})

    assert %Outcome{escalated: true} = decide(a, %Policy{rules: rules})
    assert %Outcome{action: "second"} = decide(a, %Policy{rules: rules, skip_uncertain: true})

    {:ok, c} = Judge.static(%{"s" => 1}, [noul(:y, "y?")], %{"answers" => %{"y" => %{"type" => "noul", "noul" => 0.9}}})
    assert {:ok, %Outcome{action: "ok"}} = gate(c, %{s: 1}, [noul(:y, "y?")], %Policy{default: "ok"})
  end

  # ------------------------------------------------------------ Tape

  test "Tape records and replays by call name; a miss names the key" do
    qs = [noul(:y, "y?")]
    {:ok, live} = Judge.static(%{"s" => 1}, qs, %{"answers" => %{"y" => %{"type" => "noul", "noul" => 0.9}}})
    tape = Tape.new()
    {:ok, rec} = Tape.record(tape, :plan, live)
    {:ok, a1} = ask(rec, %{s: 1}, qs)
    assert Map.keys(Tape.decisions(tape)) == ["plan"]
    {:ok, rep} = Tape.replay(tape, "plan")
    assert {:ok, ^a1} = ask(rep, %{anything: true}, qs)
    {:ok, miss} = Tape.replay(tape, "other")
    assert {:error, msg} = ask(miss, %{}, qs)
    assert msg =~ "\"other\""
  end

  # ------------------------------------------------------------ evaluateBatch

  defp batch_classifier do
    {:ok, c} =
      C.create(
        style: "custom",
        evaluate: fn %{"i" => i}, _ ->
          if i == 1, do: Process.sleep(20)
          if i == 99, do: {:error, "boom"}, else: C.decode_decision(%{"answers" => %{"q" => %{"type" => "noul", "noul" => i / 10}}})
        end
      )

    c
  end

  test "evaluate_batch returns decisions in state order" do
    qs = %{"q" => %C.Noul{instructions: "q?"}}
    {:ok, ds} = C.evaluate_batch(batch_classifier(), [%{"i" => 1}, %{"i" => 2}, %{"i" => 3}], qs, concurrency: 3)
    assert Enum.map(ds, & &1.answers["q"].noul) == [0.1, 0.2, 0.3]
  end

  test "evaluate_batch fails closed naming the index" do
    qs = %{"q" => %C.Noul{instructions: "q?"}}
    assert {:error, msg} = C.evaluate_batch(batch_classifier(), [%{"i" => 1}, %{"i" => 99}, %{"i" => 3}], qs)
    assert msg =~ "state 1"
  end

  test "evaluate_batch with no states errors and sends nothing" do
    {:ok, c} = C.create(style: "custom", evaluate: fn _, _ -> flunk("sent") end)
    assert {:error, _} = C.evaluate_batch(c, [], %{"q" => %C.Noul{}})
  end
end
