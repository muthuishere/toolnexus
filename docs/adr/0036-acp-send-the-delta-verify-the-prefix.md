# ADR 0036 — ACP: send the delta, verify the prefix

- **Status:** Accepted for trial — **implemented in `golang/` only**
  (`openspec/changes/add-acp-session-delta`). Hermetic tests pass against a fake ACP agent; it has
  **not been run against a live agent** yet. To verify on a machine with an agent installed and
  logged in: `cd golang && go run ./examples/acp` (opencode by default; codex and devin commands are
  in the example header; `TOOLNEXUS_ACP_MODEL` selects a model). Turn 2 should go out as a
  continuation on the same session. Other ports follow once that holds.
- **Date:** 2026-10-04
- **Revises:** ADR 0031's state decision ("full request every turn plus a supersedes marker").
- **Related:** `openspec/changes/add-acp-tool-calling` (the ACP agent as a tool-calling model).

## Context

Once the ACP agent became a real tool-calling model, each prompt carries the tool schemas as well
as the conversation. ADR 0031's full-request-every-turn rule then resends a fixed ~8.5k tokens (a
50-tool agent plus its system prompt) and the whole growing history on every turn, into a session
that retains every prompt. Context growth is quadratic in turns; a 10-turn tool loop puts roughly
140k tokens into the session where ~18k would carry the same information.

ADR 0031 rejected sending only the delta for one reason: the client would own a second copy of the
conversation that can drift from `ConversationStore`.

## Decision

Send the delta, and verify instead of trust. The client records only what it has already sent to
the current session and the reply it returned. A request that is exactly that conversation, plus
the agent's reply, plus at least one new message, with identical tools, is sent as a continuation
of only the new messages. **Anything else opens a fresh session** on the still-warm process and
sends the full opening prompt. `ConversationStore` remains the sole source of truth; the record is
never used to construct a request, only to decide whether the cheap path is safe.

The supersedes marker is dropped: it compensated for duplicate copies of the conversation in one
session, which this design never creates.

## Consequences

- Linear rather than quadratic context growth for the common case — a tool loop over a fixed tool
  set and system prompt.
- A host that changes its system prompt or tools every turn pays a fresh session every turn: no
  saving, no regression, and no wrong answers. Documented, not hidden.
- Abandoned sessions stay alive inside the agent process (ACP v1 has no stable session close).
- Model choice rides on ACP's own session config options as a pass-through (`config`), so the
  library still contains nothing specific to any agent.

## How to check it live

1. `cd golang && go run ./examples/acp` with opencode installed and logged in (or set
   `TOOLNEXUS_ACP_CMD` to the codex or devin command from the example header).
2. Expect: the agent's config options printed; turn 1 calls `clock`; turn 2 answers from the
   earlier result. Both turns run on one process and one session.
3. What would falsify the design: the agent ignoring the continuation preamble (prose instead of
   JSON), or losing the tools between turns — record which agent and the reply, and bring it back.
