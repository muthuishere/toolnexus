# Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as `Tool` is for an ACTION.
# Mirrors js/examples/judge.ts.
#
# Run from the elixir/ directory (deps fetched):
#     mix run examples/judge.exs
#
# With OPENROUTER_API_KEY set it calls the live System One backend; with no key it
# replays one recorded decision through the `static` backend, so the example runs
# offline with no credential.

alias Toolnexus.Classifier
alias Toolnexus.Classifier.{Choice, Decision, Noul, Score}

# ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
ticket =
  "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge " <>
    "has not been refunded. I am not blocked from working, but I would like the money back this week."

# All three question types in ONE call: many questions, one round trip, one state ingest.
# The questions are INDEPENDENT — one answer is never context for another.
#
# The `choice` descriptions are the whole ball game (ADR 0021): `criteria[id]` is the only thing
# that tells the model what picking `billing` rather than `technical` would MEAN. Options described
# by their own id are schema-valid, return HTTP 200 — and rank at chance (17 apples -> 0). So:
# every option carries a real sentence, all three use the SAME template ("own it here when the
# problem is X: a, b, c"), and no arithmetic is pushed onto the model — the host does the counting
# and hands over the conclusion.
questions = %{
  "wants_money_back" => %Noul{instructions: "Is the customer asking for money to be returned?"},
  "department" => %Choice{
    instructions: "Which desk should own this ticket?",
    criteria: %{
      "billing" =>
        "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed",
      "shipping" =>
        "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit",
      "technical" =>
        "own it here when the problem is the product itself: a login that fails, a feature that errors"
    }
  },
  "urgency" => %Score{
    instructions: "How fast does this ticket need a human?",
    criteria: [
      "the customer is working normally and is waiting on an answer",
      "the customer is inconvenienced and will chase if nobody replies today",
      "the customer is blocked from working right now and every hour costs them"
    ]
  }
}

model = "typesafe/jev-1.13"

# One decision recorded off the live backend, so this file runs with no key and no network.
recorded = %{
  state: ticket,
  questions: questions,
  response: %{
    "model" => "typesafe/jev-1.13-20260917",
    "answers" => %{
      "wants_money_back" => %{"type" => "noul", "noul" => 0.99},
      "department" => %{
        "type" => "choice",
        "choice" => "billing",
        "probabilities" => %{"technical" => 0, "shipping" => 0, "billing" => 1},
        "confidence" => 1
      },
      "urgency" => %{
        "type" => "score",
        "score" => 0.49,
        "legend" => %{
          "0" => "the customer is working normally and is waiting on an answer",
          "1" => "the customer is inconvenienced and will chase if nobody replies today",
          "2" => "the customer is blocked from working right now and every hour costs them"
        },
        "probabilities" => %{"0" => 0.52, "1" => 0.48, "2" => 0},
        "confidence" => 0.27
      }
    },
    "usage" => %{"input_tokens" => 516, "output_tokens" => 72, "cost" => 0.000021672}
  }
}

live = System.get_env("OPENROUTER_API_KEY") not in [nil, ""]

opts =
  if live do
    [
      # serves the System One wire today
      base_url: "https://openrouter.ai/api/v1",
      model: model,
      # the NAME of an env var, never the value
      api_key_env: "OPENROUTER_API_KEY",
      on_metric: fn
        %{event: "classifier.warning", warning: w} -> IO.puts("warning: " <> w)
        _ -> :ok
      end
    ]
  else
    [style: "static", model: model, decisions: [recorded]]
  end

{:ok, judge} = Classifier.create(opts)

IO.puts(
  if live,
    do: "backend: systemone (live)",
    else: "backend: static (recorded — set OPENROUTER_API_KEY to go live)"
)

{:ok, d} = Classifier.evaluate(judge, ticket, questions)

{:ok, want} = Decision.noul(d, "wants_money_back")
{:ok, dept} = Decision.choice(d, "department")
{:ok, urg} = Decision.score(d, "urgency")

IO.puts("\nmodel answering: #{d.model}")

IO.puts(
  "wants_money_back: #{want.noul}   (a noul carries NO confidence — the number IS the answer)"
)

IO.puts(
  "department:       #{dept.choice}  p=#{Jason.encode!(dept.probabilities)} confidence=#{dept.confidence}"
)

IO.puts(
  "urgency:          #{urg.score}  of 0..#{map_size(urg.legend) - 1}  p=#{Jason.encode!(urg.probabilities)}"
)

level = round(urg.score)

IO.puts(
  "  level #{level}: #{Map.fetch!(urg.legend, to_string(level))}   (a score MAY fall between levels)"
)

# The two health flags, and what they actually mean.
IO.puts(
  "\ncalibrated: #{d.calibrated}  — these probabilities came from a calibrated backend, so a " <>
    "threshold tuned here transfers. An 'llm'-style backend reports false and your thresholds " <>
    "do NOT carry over."
)

IO.puts(
  "near_uniform(department): #{dept.near_uniform}  — max|p - 1/n| <= 0.05, derived from the " <>
    "response. True would mean the model had nothing to rank on (usually undescribed options). " <>
    "Advisory, NOT correctness."
)

cost = if is_nil(d.usage.cost), do: "", else: " / $#{d.usage.cost}"
IO.puts("\nusage: #{d.usage.input_tokens} in / #{d.usage.output_tokens} out" <> cost)
