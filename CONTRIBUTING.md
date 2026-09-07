# Contributing

`SPEC.md` is the contract; `js/` and `golang/` (and other language ports referenced in
`CLAUDE.md`) are implementations of it. Read `CLAUDE.md` first — it lays out the port
architecture and why behavior is defined in the spec before it's written in code.

## The one rule that matters most

**Behavior changes go into `SPEC.md` first, not straight into a port's code.** If your
change alters observable behavior (a tool's output shape, adapter mapping, the client
loop), update the spec section it belongs to as part of the same PR — a port that quietly
drifts from `SPEC.md` is exactly the failure mode this structure exists to prevent.

## Running the tests

```bash
cd js     && npm install && npm test     # builds, then runs the node:test suite (218 tests currently)
cd golang && go build ./... && go vet ./... && go test -race ./...
```

Both verified clean on current main before this was written. If your change touches
behavior shared across ports, run both — a fix in one port that isn't mirrored in the
other is a spec violation waiting to be found by someone else's bug report.

## What a good PR looks like

- **Spec change + implementation change together**, when behavior moves. Don't split them
  across PRs — a reviewer needs to see the contract and the code that satisfies it in the
  same diff.
- **Small and single-purpose.** Touch only what the task needs; note unrelated issues you
  notice rather than fixing them inline (this is stated as a house rule in `CLAUDE.md`
  too — applies to outside contributions the same way).
- **Dependency bumps**: if `npm audit` flags something in `js/`, a fix-only PR (no other
  changes) is welcome and easy to review — that's exactly the shape of the audit fix that
  landed alongside this guide.

## Filing an issue

State which port(s) you're seeing the problem on and whether the behavior matches
`SPEC.md` as written — if it doesn't, that's a straightforward port bug; if `SPEC.md`
itself seems wrong for the case you have in mind, say that instead, since the fix looks
different (an ADR-style conversation about the spec, not a patch to one port).
