# Tasks — add-acp-session-delta

**Implemented in `golang/` only, hermetically tested, not yet run against a live agent.** The
other six ports are open on purpose.

## Spec

- [x] `proposal.md`, `design.md`, spec delta `specs/acp-session-delta/spec.md`
- [x] `openspec validate add-acp-session-delta --strict`
- [x] ADR 0036 — the delta reversal of ADR 0031's state decision (Proposed)
- [x] With the first port's code: `SPEC.md §8` gains a "Session delta — golang only so far"
      subsection pinning the continuation preamble, the opening/continuation rules and `config`
- [ ] When the last port lands: fold that subsection into the main ACP text, drop the
      "golang only" status, and remove the supersedes line from the base formula
- [x] `CHANGELOG.md` `## Unreleased` — the Go implementation and what is still open
- [ ] When every port lands: the full `CHANGELOG.md` entry (token saving, `config`, marker removed,
      the per-turn-system-prompt caveat)

## Per-language parity checklist

Each port: opening vs continuation decision exactly per `design.md` · continuation preamble bytes
(test against `SPEC.md` once moved there) · state discarded on any failed turn · `config` applied
after every `session/new` · raw `configOptions` readable · supersedes marker removed · hermetic
tests over real pipes against the port's fake ACP server for every scenario in the spec delta
(continuation carries only the tool result · opening carries everything once · tools change →
`session/new` · edited/compacted history → `session/new` · unrelated conversation → `session/new` ·
retried request → `session/new` · failed turn → `session/new` · config at load and on reset ·
rejected config fails load · configOptions readable) · the existing tool-calling tests still green.

- [x] `golang/` — `acp.go` (planTurn, openSession, ConfigOptions, Config), tests in
      `acp_test.go` / `acp_toolcall_test.go`, incl. `Ask` with memory continuing one session
- [ ] `js/`
- [ ] `python/`
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/`
- [ ] `clojure/`

## Examples (each port's existing ACP example)

- [x] Go: default `opencode acp`; codex and devin documented; `TOOLNEXUS_ACP_MODEL` → `config`;
      prints the agent's config options; uses `Ask` so turn 2 is a continuation
- [ ] The other six ports' examples
- [ ] Run the Go example once against each real agent and correct the commands/package names if they
      differ from `design.md`
- [ ] Cookbook (`site/.../local-and-in-process-models.mdx`): `config`, the three example commands,
      and the caveat that a per-turn-changing system prompt or tool list forfeits the saving

## Live verification (needs the agents installed and logged in)

- [ ] opencode, codex, devin: a multi-turn tool loop completes; continuation prompts are followed;
      record observed session resets per run
