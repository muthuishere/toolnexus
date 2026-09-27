# Shared spike contract — judge adapters (all 7 ports)

Goal: make a judgment as simple as the video's request, with no hand-written JSON.

    state     = a map (or sugar: context + message [+ extra map])
    questions = an ordered LIST: noul(name, instructions) · choice(name, instructions, options) · score(name, instructions, levels)
    bands     = cut-offs {low, high}, default 0.30 / 0.70, overridable per call
    gate(classifier, state, questions, rules, bands) -> {action, target, escalated, request}

- `state-cases.json`: the builders must yield exactly `wantState` / `wantQuestions` (the existing §8B inputs); duplicate names are an error naming the key.
- `gate-cases.json`: the answers are recorded responses for the `static` classifier; each case must yield `want`. `bands: null` means default.
  - Cut points are EXCLUSIVE on the confident side: exactly low or exactly high is uncertain.
  - A choice is sure only if confidence > high AND not nearUniform. A score is sure only if confidence > high.
  - Missing answer / uncertain -> escalate `needs_input` with a §10-shaped request (kind "input", data has question, reason, answers).
  - Rules are first-match in order; an escalation of rule i wins over later rules.
- The spike lives in the port's spikes area, does NOT modify library code, and runs hermetically.
