# A declined durable resume must carry the host's reason (issue #125)

## Why

On the durable resume path (`runWithAnswer` / `askWithAnswer`), an `Answer` built with
`answerDeclined(id, reason)` fell through to the "no result supplied on resume for <name>"
filler. The model saw a generic error and the reason the human gave was lost. The inline relay
path already reports `relay declined for <name>: <reason>`, so the same answer meant two
different things depending on whether the host waited inline or durably.

## What changes

- SPEC §10 (durable resume) pins it: an answer with `ok == false` resolves **every** outstanding
  call of the halted turn with an error `tool_result` whose content is
  `relay declined for <name>: <reason>` (`reason` defaults to `declined`; `<name>` falls back to
  the call id). Any `data` on a declined answer is ignored. The filler survives only for a
  genuine partial `ok:true` relay answer.
- Shared fixture `conformance/resume_decline/cases.json`.

## Port scope

The durable resume entry point ships in **golang** and **rust** only (SPEC §10 status note). Both
are fixed and replay the fixture. `js`, `python`, `java`, `csharp`, `elixir`, `clojure` have no
answer-carrying resume, so they cannot exhibit the bug; their inline `ok:false` relay text is
already `relay declined for <name>: <reason>`. Whichever port adds the durable resume later
must replay the same fixture.
