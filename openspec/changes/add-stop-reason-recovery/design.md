# Design — add-stop-reason-recovery

## Decisions

1. **Opt-in boolean** `stopRecovery`, default false (see proposal). Fixed budgets 1 / 3 / 1 per
   run call.
2. **Classification order**: malformed tool call, then truncated, then empty answer. A truncated
   response that carries tool calls is handled as a tool response (rule 1 catches the usual case,
   truncated arguments).
3. **"Empty answer" instead of "thinking present"**: the trigger is "no tool calls, trimmed text
   empty, not truncated". Detecting reasoning content differs per provider and per stream format
   and OpenAI-style reasoning models hide it entirely; the observable failure (no answer) is the
   same. This keeps the rule identical across 8 ports and both loops.
4. **Drop vs keep the assistant turn**: nudge and corrective *remove* the bad assistant message
   (an empty Anthropic assistant turn and an OpenAI `tool_calls` message without tool results are
   both invalid requests); continue *keeps* the partial so the model sees where it stopped.
5. **Plain user message** in both styles, no prefill (newer models reject assistant prefill, OpenAI
   has none).
6. **Recoveries are turns**: counted, hooked, bounded by `maxTurns`; never taken on the last turn so
   no dangling pinned message ends a transcript.
7. **Carry**: continued text concatenates into the final `text`; reset when tools run.

## Implementation shape (every port)

In each loop, after the assistant message is assembled and before tools are scheduled:

```
if stopRecovery and turn < maxTurns-1:
  if calls and any(malformed) and corrective < 1: corrective++; pop assistant; push user CORRECTIVE; continue
  if !calls and truncated and continues < 3:      continues++; carry += text; push user CONTINUE; continue
  if !calls and !truncated and trim(text)=="" and nudges < 1: nudges++; pop assistant; push user NUDGE; continue
if calls: carry = ""            (tools run)
final text = carry + text
```
