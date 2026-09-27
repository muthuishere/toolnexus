# Shared spike contract: judge adapters (all 7 ports)

Goal: a judgment as simple as the video's request. No hand-written JSON, no verbose type soup.

```
state     = a map (or sugar: context + message [+ extra map])
questions = an ordered LIST: noul(name, instructions) | choice(name, instructions, options) | score(name, instructions, levels)
bands     = cut-offs {low, high}; default 0.30 / 0.70; overridable per call
ask(classifier, state, questions)                -> answers by name, each with a band: yes | no | uncertain
gate(classifier, state, questions, rules, bands) -> {action, target, escalated, request}
```

The target feel (JS; each port uses its own idiom):

```ts
const d = await ask(c, { role, message_received }, [
  noul("is_appropriate", "Does the message contain inappropriate language?"),
  noul("does_this_help", "Does this help donkey kong win?"),
]);
d.is_appropriate.band   // "yes" | "no" | "uncertain"
```

- `state-cases.json`: the builders must yield exactly `wantState` / `wantQuestions` (the existing §8B inputs). A duplicate name is an error naming the key.
- `gate-cases.json`: `answers` are recorded responses for the `static` classifier. Each case must yield `want`. `bands: null` means the default.
  - Cut points are EXCLUSIVE on the confident side: exactly low or exactly high is uncertain.
  - A choice is sure only if confidence > high AND not nearUniform. A score is sure only if confidence > high.
  - A missing or uncertain answer escalates to `needs_input` with a §10-shaped request (kind "input"; data has question, reason, answers).
  - Rules are first-match, in order. An escalation on rule i wins over later rules.
- The spike lives in the port's spike area, does NOT modify library code, and runs hermetically.
