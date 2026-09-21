# Optional gate 5. Reads OPENROUTER_API_KEY from the env at call time; never prints it.
alias Spike.Classifier
alias Spike.Classifier.{Choice, ChoiceAnswer, Noul, NoulAnswer, Score, ScoreAnswer}

questions = %{
  "is_refund_request" => %Noul{instructions: "Is the customer asking for a refund?"},
  "department" => %Choice{
    instructions: "Which department should handle this?",
    criteria: %{
      "billing" => "refunds, charges, payments",
      "shipping" => "delivery, damage in transit",
      "technical" => "product does not work"
    }
  },
  "urgency" => %Score{instructions: "How urgent is this?", criteria: ["routine", "elevated", "urgent"]}
}

state = "Order 4021 arrived smashed, I want my money back."
t0 = System.monotonic_time(:millisecond)
d = Classifier.evaluate(Classifier.live(), state, questions)
ms = System.monotonic_time(:millisecond) - t0

IO.puts("latency_ms=#{ms}")
IO.puts("model=#{d.model}")
IO.inspect(d.answers, label: "answers")
IO.puts("shapes_ok=#{match?(%NoulAnswer{}, d.answers["is_refund_request"]) and
  match?(%ChoiceAnswer{}, d.answers["department"]) and match?(%ScoreAnswer{}, d.answers["urgency"])}")
