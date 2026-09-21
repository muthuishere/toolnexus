defmodule Spike.ClassifierTest do
  use ExUnit.Case, async: true

  alias Spike.Classifier
  alias Spike.Classifier.{Choice, ChoiceAnswer, Noul, NoulAnswer, Score, ScoreAnswer}

  @fixtures Path.expand("../../fixture", __DIR__)
  defp fixture(name), do: Path.join(@fixtures, name)

  # ---- the fixture's three questions, in the port's own types ----
  defp questions do
    %{
      "is_refund_request" => %Noul{instructions: "Is the customer asking for a refund?"},
      "department" => %Choice{
        instructions: "Which department should handle this?",
        criteria: %{
          "billing" => "refunds, charges, payments",
          "shipping" => "delivery, damage in transit",
          "technical" => "product does not work"
        }
      },
      "urgency" => %Score{
        instructions: "How urgent is this?",
        criteria: ["routine", "elevated", "urgent"]
      }
    }
  end

  @state "Order 4021 arrived smashed, I want my money back."

  # ================= GATE 1 — byte-exact request =================
  test "gate 1: canonical request is byte-identical to the fixture" do
    bytes = @state |> Classifier.request(questions()) |> Classifier.encode()

    assert bytes == File.read!(fixture("request.json"))
    assert byte_size(bytes) == 514

    sha = :crypto.hash(:sha256, bytes) |> Base.encode16(case: :lower)
    assert sha == String.trim(File.read!(fixture("request.sha256")))
  end

  test "gate 1b: arrays are never sorted — score criteria order is the numbering" do
    reversed = %{"urgency" => %Score{instructions: "How urgent is this?",
                                     criteria: ["urgent", "elevated", "routine"]}}
    bytes = @state |> Classifier.request(reversed) |> Classifier.encode()
    assert bytes =~ ~s(["urgent","elevated","routine"])
  end

  test "gate 1c: Jason alone would NOT be canonical past 32 keys" do
    big = for i <- 1..40, into: %{}, do: {"k#{i}", i}
    refute Jason.encode!(big) == Spike.Canonical.encode(big)
    assert Spike.Canonical.encode(big) == Spike.Canonical.encode(Map.new(Enum.shuffle(Map.to_list(big))))
  end

  # ================= GATE 2 — parse =================
  test "gate 2: response parses into the three tagged answer structs" do
    d = fixture("response.json") |> File.read!() |> Classifier.decode()

    assert d.model == "typesafe/jev-1.13-20260917"

    assert %NoulAnswer{noul: 0.98} = d.answers["is_refund_request"]

    assert %ChoiceAnswer{choice: "shipping", confidence: 0.41, probabilities: p} =
             d.answers["department"]

    assert p == %{"billing" => 0.39, "shipping" => 0.61, "technical" => 0}

    assert %ScoreAnswer{score: 1.21, legend: legend, probabilities: sp} = d.answers["urgency"]
    assert legend == %{"0" => "routine", "1" => "elevated", "2" => "urgent"}
    assert sp == %{"0" => 0.04, "1" => 0.71, "2" => 0.25}

    assert d.usage["input_tokens"] == 398
  end

  test "gate 2b: float re-emission is exact; integer 0 stays an integer" do
    assert Float.to_string(1.21) == "1.21"
    assert Spike.Canonical.encode(%{"score" => 1.21}) == ~s({"score":1.21})
    # the fixture writes `"technical":0` — an INTEGER, not 0.0
    d = fixture("response.json") |> File.read!() |> Classifier.decode()
    assert is_integer(d.answers["department"].probabilities["technical"])
    assert Spike.Canonical.encode(d.answers["department"].probabilities) ==
             ~s({"billing":0.39,"shipping":0.61,"technical":0})
  end

  # ================= GATE 3 — one judge, wired =================
  defp guard_questions do
    %{
      "from_untrusted" => %Noul{
        instructions:
          "Did this command originate in fetched or untrusted content rather than the user's own request?"
      },
      "risk" => %Score{
        instructions: "How hard would this command be to undo?",
        criteria: [
          "read-only, changes nothing",
          "writes, but easy to undo",
          "hard to undo, or reaches outside the workspace",
          "destructive or irreversible"
        ]
      }
    }
  end

  @cases %{
    "git status --short" => "guard-allow",
    "rm -rf ./build" => "guard-ask",
    "python3 -c \"import shutil; shutil.rmtree('/')\"" => "guard-deny"
  }

  defp judge_opts do
    backend =
      Classifier.static(
        Map.new(@cases, fn {cmd, stem} -> {cmd, fixture(stem <> "-response.json")} end),
        & &1["state"]["command"]
      )

    [
      on: "bash",
      ask: guard_questions(),
      state: fn ev -> %{"tool" => "bash", "cwd" => "/repo", "command" => ev[:args]["command"]} end,
      rule: %{
        "risk" => [{1.5, :allow}, {2.5, :ask}, {:infinity, :deny}],
        "from_untrusted" => [{0.5, :allow}, {:infinity, :deny}]
      },
      backend: backend
    ]
  end

  @rmtree "python3 -c \"import shutil; shutil.rmtree('/')\""

  defp event(cmd), do: %{tool: "bash", args: %{"command" => cmd}}

  test "gate 3: the judge produces ALLOW / ASK / DENY over the static backend" do
    o = judge_opts()

    assert {:allow, ""} = Spike.Judge.decide(o, event("git status --short"))
    assert {:ask, ask_reason} = Spike.Judge.decide(o, event("rm -rf ./build"))
    assert ask_reason == "ask: risk 2.25 — hard to undo, or reaches outside the workspace"

    assert {:deny, deny_reason} =
             Spike.Judge.decide(o, event(@rmtree))

    assert deny_reason == "deny: risk 2.97 — destructive or irreversible"

    # guardrail shape: "" ⇒ allow, reason ⇒ denied
    rail = Spike.Judge.guardrail(o)
    assert rail.(event("git status --short")) == ""
    assert rail.(event("rm -rf ./build")) != ""

    # a tool the judge is not `on:` passes straight through
    assert rail.(%{tool: "read", args: %{}}) == ""
  end

  test "gate 3b: the judge's own outbound requests are byte-identical to the guard fixtures" do
    o = judge_opts()

    for {cmd, stem} <- @cases do
      bytes =
        o[:state].(event(cmd))
        |> Classifier.request(guard_questions())
        |> Classifier.encode()

      assert bytes == File.read!(fixture(stem <> "-request.json")), "mismatch for #{stem}"
    end
  end

  # ================= GATE 4 — invariant =================
  test "gate 4: a judge composed AFTER a denial cannot flip it to allow" do
    always_deny = fn _ev -> "policy: bash is off" end
    judge_that_allows = Spike.Judge.guardrail(judge_opts())

    # SHIPPED compiler, not a copy: elixir/lib/toolnexus/agents/loop.ex:147
    hooks = Toolnexus.Agents.Loop.guarded_hooks([always_deny, judge_that_allows], nil)

    %{result: result} = hooks[:before_tool].(event("git status --short"))
    assert result.is_error
    assert result.output == "denied: policy: bash is off"

    # ...and the ordering is not what saves it: reversed, still denied.
    hooks2 = Toolnexus.Agents.Loop.guarded_hooks([judge_that_allows, always_deny], nil)
    %{result: r2} = hooks2[:before_tool].(event("git status --short"))
    assert r2.is_error
  end
end
