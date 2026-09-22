# The builtin execution seam — agreed design, not yet built

- **Status:** DESIGN ONLY. No code, no ADR, no OpenSpec change. Not authorised.
- **Date:** 2026-09-22
- **Who:** toolnexus maintainer session + the wfnexus consumer session
  (`github.com/muthuishere/wfnexus`, formerly bug-fixer-platform), which is the
  only consumer today with a real sandbox requirement and a real escape to test against.
- **Why written:** the design was settled across two sessions and would otherwise
  live only in transcripts. Recording it so the eventual ADR starts from here.

## The problem

`CreateBuiltinTools()` builds `bash`, `read`, `write`, `edit`, `apply_patch`, `grep`,
`glob` closed over the host process — `exec.Command` and `os.ReadFile` directly. A host
cannot interpose. So the only way to sandbox a toolnexus agent is to disable the builtins
entirely and reimplement all ten, which is the hand-copying ADR 0012 exists to prevent,
and it discards our prompts, schemas and behaviour.

The evidence is not hypothetical. On a live wfnexus run an agent executed
`cd <platform repo> && git log --all --grep=…`, then `git checkout -b`, `git stash`,
`git reset` — in the platform's own repository. `workdir` sets only the INITIAL
directory; a `cd` in the command string leaves it, bash reports success, nothing errors.
Their ADR 0006 ships a `cd`/`pushd`/`git -C` guardrail and labels it honestly as a
guardrail and NOT a sandbox: an agent with a shell still has `env`, a symlink, a python
one-liner. No host can fix this from outside the tool.

## The decision: the seam has TWO shapes, deliberately

A single uniform shape would quietly re-delegate a guarantee we spent the whole
#86-#93 batch establishing. The split:

| tool | sandbox does | library does |
|---|---|---|
| `bash` | **decides** — argv in, bytes + exit code out | nothing; does not interpret |
| `glob`, `grep`, `<skill_files>` sample | **enumerates only** — returns the FULL candidate set (paths relative to the walk root, plus per-hit data for grep) | **orders and truncates**, in one place, in all seven ports |
| `read`, `write`, `edit`, `apply_patch` | performs the point operation | error and result shaping |

**Why the listing tools are not opaque.** A25/A26 made those listings deterministic by
taking the ordering decision away from the filesystem: collect the whole candidate set,
sort by path relative to the walk root in plain code point, THEN truncate. If the sandbox
walks, sorts AND caps, every sandbox image becomes part of the conformance surface — two
images, or one image on two kernels with different readdir behaviour, ship different
`<skill_files>` contents for the same directory. That divergence is measured, not
theoretical: clojure's two hosts returned different file SETS for the same directory
under a cap-before-sort mutation (JVM `{alpha-b.txt, mß.txt}` vs cljgo
`{a-dir/zz.txt, alpha/f.txt}`).

**The cap is what makes it matter.** Without a cap that bites, sorting is cosmetic and
either side could do it. With one, the sort chooses WHICH FILES THE MODEL SEES — and that
is a library guarantee or it is nothing. That is the counter to a reviewer arguing for
one uniform shape.

**The failure mode if we get this wrong is the worst class available:** it is invisible.
"The model saw slightly different files today" does not read as a bug, it reads as the
model being flaky, and no host would ever diagnose it.

## Three failure kinds, not one (wfnexus's addition)

The seam must distinguish:
1. **could not run at all** — image pull failed, OOM kill ⇒ the host should retry the
   step elsewhere;
2. **ran and failed** — non-zero exit ⇒ the MODEL should see the output and react;
3. host error.

Collapsing 1 and 2 breaks a consumer's retry policy (wfnexus ADR 0012): it cannot tell a
flaky worker from a bad command and will retry things that can never succeed. This
matches the rule #89 established in the #86-#93 batch: when the HOST has provably made a
mistake, error to the host; when the MODEL has, hand the model something it can act on.

## Spikes, in order, BEFORE any API is fixed

1. **Ordering survives the boundary.** Same tree, same cap, cap bites: bytes identical
   between a host-built listing and a seam-built one, AND a deliberately hostile
   enumeration order from the seam must not change the output. *If a container has to
   implement our ordering rule to pass, the seam is in the wrong place.* This assertion
   can fail, which is what makes it worth running.
2. **Conformance goldens.** Does routing the file tools through the seam change any
   golden/conformance output?
3. **Failed exec as a tool result.** A container hiccup must arrive as something the model
   can react to, not a host error — one hiccup must not kill a 40-turn run. Prior art: the
   `skill` tool's not-found path returns a tool result listing what IS available.

Also unresolved and worth a spike: what the seam does to `read`'s offset/limit and the
`<skill_files>` sample, the two builtins explicitly ruled OUT of the batch's ordering work.

## Suggested shape (the seam matters more than the spelling)

    type BuiltinExec func(ctx context.Context, cmd BuiltinCommand) (BuiltinResult, error)

Nil ⇒ byte-identical to today (host exec). Set ⇒ every builtin routes through it.
Target use: `podman run --rm --network=none --read-only --cap-drop=ALL --pid=private
-v <run-worktree>:/workspace:rw -w /workspace <image>`, under which the escape above dies
by construction — the platform's repo is not in the mount namespace, so there is nothing
to `cd` into.

## Process

ADR + spikes first, Go first, then all seven ports. Blocked on owner authorisation.
