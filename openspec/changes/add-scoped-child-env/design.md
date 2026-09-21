## Context

toolnexus spawns child processes on exactly two paths, and both hand over the whole parent
environment:

| path | code | how |
|---|---|---|
| MCP stdio server | `golang/mcp.go:474`, `js/src/mcp.ts:309` | `os.Environ()` / `{...process.env}` |
| builtin `bash` | `golang/builtin.go:331`, `js/src/builtin.ts:160`, `python/src/toolnexus/builtin.py:174` | no env specified ⇒ inherit |

The remote-MCP path already models the right behaviour: `headers` expands only the `${VAR}` names
a server was given, at call time, never logged. The spawn paths are the inconsistency.

The exposure is proven, not inferred. A test against the shipped builtin:

```
command: env | grep ACME_BILLING_TOKEN
result : ACME_BILLING_TOKEN=sk-live-PRETEND-SECRET
```

That is a command the model writes, returning a credential to the model, from a default toolkit.

ADR 0003 gap M4 designed the fix for the MCP half and is **already accepted for promotion on its
own**. This change implements M4 as designed and extends the identical contract to the builtin
shell, which M4 did not cover.

## Goals / Non-Goals

**Goals:**
- One contract (`child-process-env`) obeyed by both spawn paths, byte-identical in seven ports.
- Ship non-breaking: absent configuration ⇒ byte-identical behaviour.
- Make the narrow case a one-liner (`defaultInheritEnv: []`) so the safe posture is cheap to adopt.
- Keep the secret-handling property the header path already has: values resolve from the live
  environment at spawn time, never from a config file, never logged.

**Non-Goals:**
- Process isolation, filesystem scoping, network scoping, user/privilege separation. A scoped
  child keeps the parent's filesystem and network reach. The docs must say this plainly; a
  narrowed environment that is *described* as a sandbox is worse than no narrowing at all.
- Flipping the default (QM4) — recorded, recommended, deferred to a major version.
- Tool-call idempotency on the §10 retry pass — a real adjacent gap, tracked separately.
- Scrubbing secrets out of the *prompt*. This is about the process boundary only.

## Decisions

**D1 — One capability, two consumers.** The three-state semantics, the safe-base set and the
precedence rules live in `child-process-env`; `mcp-load-lifecycle` and `builtin-tools` reference
it. Writing the safe base twice is how two ports would eventually disagree about it.

**D2 — Three states, and omitted must be distinguishable from empty.** `undefined` ⇒ inherit all,
`[]` ⇒ safe base, `[names]` ⇒ safe base + names. This is the single most error-prone part of the
port work: a language that collapses "absent" and "empty list" silently converts a deliberate
narrowing into today's behaviour, or vice versa. Go needs `*[]string` (or a sentinel), not
`[]string`; C# needs the nullable; Elixir/Clojure must not treat `[]` as falsy; and the JSON
decoder must distinguish an absent key from `[]`. Each port's task list calls this out.

**D3 — The safe base is a name list, pinned in `SPEC.md`.** POSIX: `HOME`, `LANG`, `LC_ALL`,
`PATH`, `TMPDIR`, `TZ`. Windows adds `APPDATA`, `COMSPEC`, `HOMEDRIVE`, `HOMEPATH`,
`LOCALAPPDATA`, `PATHEXT`, `SYSTEMDRIVE`, `SYSTEMROOT`, `TEMP`, `TMP`, `USERPROFILE`, `WINDIR`.
Chosen to satisfy exactly four needs — find a binary, write a temp file, resolve a home directory,
set locale/timezone — and nothing else. A name absent from the parent is **omitted**, never set
empty, because an empty `PATH` breaks a child more confusingly than a missing one.

**D4 — Precedence: safe base, then named passthrough, then explicit entries.** Explicit
`env`/`environment` always wins and never needs to be named in `inheritEnv`. This keeps the
existing config working untouched and means "I need exactly this value" has one obvious spelling.

**D5 — Default stays inherit-all (QM4).** Flipping it is the correct end state and is a breaking
change: a user whose MCP server reads `GITHUB_TOKEN` from ambient env would find it stops working
on a patch release. Ship the mechanism now, recommend the flip for the next major, and record the
recommendation where the decision will actually be made.

**D6 — Windows case-insensitivity is per-platform, not per-port.** Name matching follows the
platform's own environment semantics. A port that lowercases names everywhere would disagree with
its own OS.

**D7 — Failure stays isolated and value-free.** A server starved of a variable it needed fails
like any other server: status `failed`, not fatal, and the diagnostic names the missing
**variable name** only. The point of this change is to stop values crossing boundaries; leaking
one into an error message would defeat it.

## Risks / Trade-offs

**A false sense of safety is the main risk.** Narrowing the environment does nothing about the
filesystem — a model-written `cat ~/.aws/credentials` is unaffected. This is mitigated by spec
requirement (documentation states the limit) rather than by code, because it is a documentation
failure mode. It is the reason the spec forbids calling `inheritEnv` a security boundary.

**Adopters will break their own servers.** Setting `defaultInheritEnv: []` on a config whose
servers quietly relied on ambient variables makes them fail to start. This is the correct
behaviour and the reason the default does not flip. Mitigation: failures stay isolated (D7) and
name the missing variable, and the docs show narrowing one server before narrowing all.

**Seven-port drift on the three-state distinction (D2)** is the likeliest correctness bug, and it
fails *silently* in the dangerous direction — `[]` read as "absent" means the child still gets
everything while the config says it does not. Mitigation: the shared `examples/mcp-scoped-env/`
fixture exercises all three states, and the conformance check compares the resulting **name sets**
across ports rather than just asserting per-port behaviour.

**The safe base is a judgement call.** Too small and ordinary servers break; too large and the
narrowing means little. `TMPDIR`/`TEMP` in particular is a deliberate inclusion — most child
processes need a writable temp path, and omitting it would push adopters to abandon `[]`
entirely. Anything a team needs beyond the base is one name in a list.
