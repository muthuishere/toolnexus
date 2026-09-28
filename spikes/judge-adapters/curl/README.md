# Where does the role go? (live, jev-1.13.0, 2026-09-27)

The wire has no role/system field: only `model`, `state` (string | object | array) and `questions`.
Question `instructions` may be an object too. Variants of the video's Donkey Kong request:

| variant | good msg: inappropriate / helps | bad msg: inappropriate / helps |
|---|---|---|
| A state = JSON **string** with role (the video) | 0.02 / 0.76 | **0.57** / 0.02 |
| B state = object with role | 0.02 / 0.78 | **0.55** / 0.02 |
| C state = object, no role | 0.06 / 0.64 | 0.90 / 0.12 |
| D state = plain text message | 0.06 / 0.70 | 0.84 / 0.13 |
| **F role in state + the moderation question names the field it judges** | **0.02 / 0.79** | **0.96 / 0.02** |
| E role inside the one question that needs it | 0.06 / 0.62 | 0.90 / 0.05 |

`is_appropriate` asks "does the message contain inappropriate language" (so high = inappropriate).

- A ≈ B: a JSON string and an object behave the same, so send objects.
- A state-level role **helps the question it frames** (helps: 0.78 vs 0.64) but **drags an unrelated
  question into the uncertain band**: the insult drops from 0.90 to 0.55 as a moderation read.
- Putting the role in only the question that needs it (E) keeps moderation sharp (0.90), at the cost of
  a less confident "helps" on the good message (0.62).
- A role costs about 40 input tokens here.

Conclusion (after F): **keep the role in the state**, as the video does. The 0.55 came from a vague
question ("does the message contain…"), not from where the role sat. F asks "Does message_received
contain insults, profanity or harmful topics?", which points at the exact field, and it is the sharpest
row in the table on both messages. So: role goes in state, and each question names the state field it
judges.
One sample per cell; Jev is near-deterministic, but these are not averages.
