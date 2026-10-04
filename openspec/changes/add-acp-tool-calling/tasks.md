# Tasks — add-acp-tool-calling

## Spec

- [x] `proposal.md`, `design.md`, spec delta `specs/acp-tool-calling/spec.md`
- [x] `openspec validate add-acp-tool-calling --strict`
- [x] `SPEC.md §8` — new **ACP model source** subsection pinning the preamble and the parser
- [x] `add-acp-model-source` permission scenario amended to point here (unarchived, so edited in place)
- [x] `CHANGELOG.md` `## Unreleased`

## Per-language parity checklist

Each port: new prompt assembly · `parseAcpReply` · reject-by-default permission + opt-in ·
hermetic tests over real pipes for: tools + messages reach the agent · a full loop where the fake
agent asks for a tool, the loop executes it, and the agent answers from the result · parser cases
(plain, fenced, prose-wrapped, `choices` envelope, object arguments, non-envelope JSON passthrough,
nameless call skipped) · permission rejected by default, allowed on opt-in · existing ACP tests
still green.

- [ ] `golang/`
- [ ] `js/`
- [ ] `python/`
- [ ] `java/`
- [ ] `csharp/`
- [ ] `elixir/`
- [ ] `clojure/`

## Deliberately out of scope

- ACP `authenticate`, remote agents, login pass-through via a host endpoint — a later change
- Toolkit-as-MCP-server mode (agent runs the loop)
- Delta mode; streaming
