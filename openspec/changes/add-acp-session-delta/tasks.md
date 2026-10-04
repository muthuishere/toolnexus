# Tasks — add-acp-session-delta

**Spec-only change.** Nothing below is implemented. Every port box is open on purpose.

## Spec

- [x] `proposal.md`, `design.md`, spec delta `specs/acp-session-delta/spec.md`
- [x] `openspec validate add-acp-session-delta --strict`
- [x] ADR 0036 — the delta reversal of ADR 0031's state decision (Proposed)
- [ ] With the first port's code: move `design.md`'s pinned texts into `SPEC.md §8` (continuation
      preamble, opening/continuation rules, `config`), and remove the supersedes line from it
- [ ] With the code: `CHANGELOG.md` `## Unreleased` entry (token saving, `config`, marker removed,
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

- [ ] `golang/`
- [ ] `js/`
- [ ] `python/`
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/`
- [ ] `clojure/`

## Examples (each port's existing ACP example)

- [ ] Default `opencode acp`; document codex and devin as alternatives; show `config` with a model
- [ ] Run each example once against the real agent and correct the commands/package names if they
      differ from `design.md`
- [ ] Cookbook (`site/.../local-and-in-process-models.mdx`): `config`, the three example commands,
      and the caveat that a per-turn-changing system prompt or tool list forfeits the saving

## Live verification (needs the agents installed and logged in)

- [ ] opencode, codex, devin: a multi-turn tool loop completes; continuation prompts are followed;
      record observed session resets per run
