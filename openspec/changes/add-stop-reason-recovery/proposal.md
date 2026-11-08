# add-stop-reason-recovery

## Why

Issue #191, requested by Karya (go-agent-cli) from a real run: three provider stop shapes end a
toolnexus run badly today.

- A **thinking-only `end_turn`** (reasoning blocks, no text) returns `text: ""` — the host gets
  nothing although one more call would have produced the answer.
- A **`max_tokens` / `length`** stop returns a half answer as if it were final.
- A **malformed tool call** (arguments that are not a JSON object, often itself a truncation)
  silently runs the tool with `{}`.

Every host that drives a long-lived agent writes the same three recoveries by hand around the
loop, and cannot do it well from outside it (it would need to re-enter the loop mid-transcript).

## What changes

- New client option `stopRecovery` (boolean, **default false**), all eight ports. On:
  malformed tool call ⇒ drop that response, append a pinned `CORRECTIVE` user message, retry once;
  truncated ⇒ keep the partial, append `CONTINUE`, up to 3 times, final text = concatenation;
  empty answer ⇒ drop it, append `NUDGE`, once.
- Pinned byte-exact message texts in `SPEC.md` §8 *Stop-reason recovery*.
- Run and stream, both styles, resume paths, §7D agent runs. Not `translate`, not the in-process client.
- Shared fixture `conformance/stop_recovery/cases.json`; option-parity manifest row.

## Why opt-in

Default-on would change transcripts, turn counts, token spend and `RunResult.text` for every
existing host on upgrade — the parity fixtures and every host's recorded transcripts would move.
A recovery spends money (up to 5 extra calls per run). The option is one boolean for a host that
wants it (Karya sets it). It can become default-on before 1.0 once it has run in a host.

## Not in scope

- A stream event or metric announcing a recovery (hosts can observe it in `beforeLLM`: the last
  message is the pinned text). Possible follow-up.
- Configurable budgets or message texts (fixed 1/3/1 and pinned texts keep ports byte-identical).
- Detecting unknown tool names or schema-invalid (but well-formed) arguments.
