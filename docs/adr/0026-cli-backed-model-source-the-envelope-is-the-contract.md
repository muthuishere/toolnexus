# ADR 0026 — CLI-backed model source: pass the body through byte-for-byte

- **Status:** Proposed — 2026-09-21. Spike gate below.
- **Date:** 2026-09-21
- **Driver:** issue #97. Working implementation in a consumer's tree: 53 offline tests
  plus a live `devin` run choosing its own calls across native, built-in, skill and
  MCP tools.
- **Related:** ADR 0025 (the warm-session sibling), ADR 0023 (retries must be off here).

## Context

Most agent CLIs offer only one-shot `-p` mode, and for many hosts that is enough.
`examples/onnx-in-process` shows the seam for a local *model*; a local *agent CLI* is
the other common case and has nothing equivalent, so every host hand-rolls the same
adapter.

Three findings from the working implementation are worth more than the feature itself.

**1. The CLI is configuration, not code.** The presets differ only in argv:

```
devin    --prompt-file {{file}} -p --permission-mode dangerous [--model M]
claude   -p {{prompt}} [--model M]
copilot  -p {{prompt}} --log-level none --no-color [--model M]
codex    exec --skip-git-repo-check --output-last-message {{out}} [-m M] {{prompt}}
```

Prefer a prompt **file** where the CLI supports one — no length limit, no quoting
hazard. `codex` additionally needs "read the answer from this file, not stdout".

**2. Pass the request body through verbatim.** A CLI has no tool-call channel, so the
adapter needs a response contract. What worked was handing the CLI the **exact**
assembled OpenAI body inside an `<openai_request>` envelope and asking for an
`<openai_response>` back. Byte-for-byte passthrough means `tools`, `tool_choice`,
`response_format` **and anything toolnexus adds later** reach the model without the
adapter learning about them first. Re-rendering as prose loses information: the
reporter's first version dropped `tool_call_id`, so the model could not tell which of
several parallel results it was looking at.

**3. Validate and repair — never massage.** Real drifts observed from a live model:

- `kind:"answer"` **with** a populated `tool_calls` array. Dispatch on the declared
  finish reason and the call is silently dropped. **What the message contains must win.**
- the structured payload in `content` as an object instead of in `arguments`
- `arguments` as a JSON-encoded string rather than an object
- re-calling a tool whose result is already in the transcript

The fix that held: parse strictly; on failure resend the **same** request with the
specific complaint appended, up to a small repair budget, then error. *Degrading a
malformed reply into plain content is how a dropped tool call becomes a confidently
wrong answer.*

## Decision (proposed)

Ship a CLI model source as a **`Generate`** (same seam as ADR 0025), with an argv
template, a file-or-argv prompt channel, a stdout-or-file response channel, the
verbatim-body envelope, strict parse + bounded repair, and retries **off** by default
(ADR 0023).

The open question this ADR does **not** settle: whether the four presets ship in the
library or as an example. A preset is a compatibility promise about someone else's CLI
flags, which change without warning and cannot be tested in CI.

## Gate (what the spike must show)

1. **The envelope contract survives a hostile model.** Reproduce all four drifts above
   against a scripted fake CLI and show strict-parse + repair catching each, and the
   `kind:"answer"` + `tool_calls` case specifically **not** dropping the call.
2. **Verbatim passthrough is real** — assert the bytes the CLI receives are byte-equal
   to the assembled body, including a key the adapter has never heard of.
3. **The repair budget terminates.** A model that never complies must error, not loop.
4. Prompt-file vs argv: show the argv path failing on a large prompt, which is the
   justification for the file channel.
5. Cost honesty — a repair is a second full CLI launch (~15s). Whatever the run
   reports must count it (ADR 0022, cost is always reported).
