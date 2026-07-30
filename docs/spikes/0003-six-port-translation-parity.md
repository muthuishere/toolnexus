# Spike 0003 — six-port translation parity, verified by byte-diff

**Status:** RESOLVED — **GREEN.** All six ports produce **byte-identical** inbound
translation for a shared adversarial fixture.
**Date:** 2026-07-30 · branch `feat/relay-mode`
**Verifies:** ADR-0011 / OpenSpec `add-single-turn-translation` · `SPEC.md` §11
**Fixture:** `docs/spikes/0003-translation-parity-fixture.json` (the agreed output)

## Why

Per-port test suites prove each port satisfies its *own* assertions. They do **not** prove
the ports agree — that is the thing this repo exists to protect, and inbound message
translation is the fiddliest surface in ADR-0011 (the design named it the top divergence
risk). So the ports were diffed against each other rather than trusted individually.

## Method

One fixture designed to hit every rule at once, run through each port's pure translation
functions, serialized, key-sorted, and diffed against Go (the reference port). The fixture
deliberately combines:

- a `system` message needing to be **hoisted** out of the message list;
- a user turn whose `content` is a **parts array** needing flattening;
- an assistant turn with **both** text and tool calls;
- one tool call with `arguments` as a **JSON string** and one as an **object** (both wire
  forms in the same turn);
- **two consecutive** tool results, which must **merge into one** user turn;
- a trailing user turn after the results, which must **not** be swallowed by the merge;
- tool declaration translation (`parameters` → `input_schema`);
- `tool_choice: "required"` mapping;
- both `finishReason` branches (a length stop, and tool calls winning).

## Result

```
js:      IDENTICAL to go
python:  IDENTICAL to go
java:    IDENTICAL to go
csharp:  IDENTICAL to go
elixir:  IDENTICAL to go
```

Every rule agreed on the first diff — no port needed a correction to reach parity. The
agreed output is committed as the fixture, so a future port (or a refactor) can be checked
against it directly.

## What this does and does not prove

**Proves:** the six ports' inbound translation is byte-identical on the hardest combined
case, including the consecutive-results merge and the dual `arguments` forms.

**Does not prove:** the outbound half (provider response → OpenAI shape) — that is covered
per-port by the ported suites (13 js / 20 python / 12 golang / 13 java / 20 csharp / 27
elixir), not by this diff. Streaming translation is out of scope entirely (a documented
follow-up). And the fixture is hermetic: no live provider was called, so "Anthropic accepts
this shape" remains a documented provider requirement rather than something observed.

## Reproducing

The dump harnesses were deliberately **not** committed — each was a throwaway env-gated
`PARITY_OUT` writer calling the port's public translation functions
(`openAIMessagesToAnthropic`, `openAIToolsToAnthropic`, `openAIToolChoiceToAnthropic`,
`finishReasonFor`) and writing JSON. Re-creating one is a few lines per port; the fixture is
the durable artifact. If this becomes a routine check it should graduate into a committed
conformance harness rather than living in a spike.
