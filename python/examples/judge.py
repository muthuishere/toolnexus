"""Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as `Tool` is for an ACTION.

Mirrors js/examples/judge.ts. Run from a venv where the package is installed
(`uv pip install -e .`):

    python examples/judge.py

With OPENROUTER_API_KEY set it calls the live System One backend; with no key it
replays one recorded decision through the `static` backend, so the example runs
offline with no credential.
"""
from __future__ import annotations

import asyncio
import json
import os

from toolnexus import (
    ChoiceQuestion,
    NoulQuestion,
    RecordedDecision,
    ScoreQuestion,
    create_classifier,
)

# ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
TICKET = (
    "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge "
    "has not been refunded. I am not blocked from working, but I would like the money back this week."
)

# All three question types in ONE call: many questions, one round trip, one state ingest.
# The questions are INDEPENDENT — one answer is never context for another.
#
# The `choice` descriptions are the whole ball game (ADR 0021): `criteria[id]` is the only thing
# that tells the model what picking `billing` rather than `technical` would MEAN. Options described
# by their own id are schema-valid, return HTTP 200 — and rank at chance (17 apples -> 0). So:
# every option carries a real sentence, all three use the SAME template ("own it here when the
# problem is X: a, b, c"), and no arithmetic is pushed onto the model — the host does the counting
# and hands over the conclusion.
QUESTIONS = {
    "wants_money_back": NoulQuestion("Is the customer asking for money to be returned?"),
    "department": ChoiceQuestion(
        "Which desk should own this ticket?",
        {
            "billing": "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed",
            "shipping": "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit",
            "technical": "own it here when the problem is the product itself: a login that fails, a feature that errors",
        },
    ),
    "urgency": ScoreQuestion(
        "How fast does this ticket need a human?",
        [
            "the customer is working normally and is waiting on an answer",
            "the customer is inconvenienced and will chase if nobody replies today",
            "the customer is blocked from working right now and every hour costs them",
        ],
    ),
}

MODEL = "typesafe/jev-1.13"

#: One decision recorded off the live backend, so this file runs with no key and no network.
RECORDED = RecordedDecision(
    state=TICKET,
    questions=QUESTIONS,
    response={
        "model": "typesafe/jev-1.13-20260917",
        "answers": {
            "wants_money_back": {"type": "noul", "noul": 0.99},
            "department": {
                "type": "choice",
                "choice": "billing",
                "probabilities": {"technical": 0, "shipping": 0, "billing": 1},
                "confidence": 1,
            },
            "urgency": {
                "type": "score",
                "score": 0.49,
                "legend": {
                    "0": "the customer is working normally and is waiting on an answer",
                    "1": "the customer is inconvenienced and will chase if nobody replies today",
                    "2": "the customer is blocked from working right now and every hour costs them",
                },
                "probabilities": {"0": 0.52, "1": 0.48, "2": 0},
                "confidence": 0.27,
            },
        },
        "usage": {"input_tokens": 516, "output_tokens": 72, "cost": 0.000021672},
    },
)


async def main() -> None:
    live = bool(os.environ.get("OPENROUTER_API_KEY"))
    if live:
        judge = create_classifier(
            base_url="https://openrouter.ai/api/v1",  # serves the System One wire today
            model=MODEL,
            api_key_env="OPENROUTER_API_KEY",  # the NAME of an env var, never the value
            on_metric=lambda ev: ev["event"] == "classifier.warning" and print("warning:", ev["warning"]),
        )
    else:
        judge = create_classifier(style="static", model=MODEL, decisions=[RECORDED])

    print(
        "backend: systemone (live)"
        if live
        else "backend: static (recorded — set OPENROUTER_API_KEY to go live)"
    )

    d = await judge.evaluate(TICKET, QUESTIONS)

    want = d.noul("wants_money_back")
    dept = d.choice("department")
    urg = d.score("urgency")

    print(f"\nmodel answering: {d.model}")
    print(f"wants_money_back: {want.noul}   (a noul carries NO confidence — the number IS the answer)")
    print(f"department:       {dept.choice}  p={json.dumps(dept.probabilities)} confidence={dept.confidence}")
    print(f"urgency:          {urg.score}  of 0..{len(urg.legend) - 1}  p={json.dumps(urg.probabilities)}")
    level = round(urg.score)
    print(f"  level {level}: {urg.legend[str(level)]}   (a score MAY fall between levels)")

    # The two health flags, and what they actually mean.
    print(
        f"\ncalibrated: {d.calibrated}  — these probabilities came from a calibrated backend, so a "
        "threshold tuned here transfers. An 'llm'-style backend reports false and your thresholds "
        "do NOT carry over."
    )
    print(
        f"near_uniform(department): {dept.near_uniform}  — max|p - 1/n| <= 0.05, derived from the "
        "response. True would mean the model had nothing to rank on (usually undescribed options). "
        "Advisory, NOT correctness."
    )
    cost = "" if d.usage.cost is None else f" / ${d.usage.cost}"
    print(f"\nusage: {d.usage.input_tokens} in / {d.usage.output_tokens} out{cost}")


if __name__ == "__main__":
    asyncio.run(main())
