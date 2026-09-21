## Why

Every child process toolnexus spawns inherits the **entire** parent environment, so a third-party
MCP binary named in a pasted `mcp.json`, or a shell command the *model itself wrote*, receives the
host's whole keyring. This is proven, not theoretical: a test running `env | grep
ACME_BILLING_TOKEN` through the shipped builtin `bash` tool returns the credential's value.

The remote-MCP path already does the right thing — `headers` expands only the `${VAR}` names a
server was given, and never logs them. The two spawn paths do not, which makes the
least-privilege story inconsistent inside one library. It also breaks this workspace's standing
rule that a secret's value must not reach a process that does not need it.

This is gap **M4** in `docs/adr/0003-mcp-host-lifecycle-and-liveness.md`, which is **already
accepted for promotion on its own**. That ADR scoped M4 to MCP stdio. The builtin shell tool has
the same defect and is the more dangerous of the two, because the model chooses the command.

## What Changes

- **New `inheritEnv` control on stdio MCP servers**, three-state, exactly as ADR 0003 M4 designed:
  - `undefined` ⇒ inherit the whole parent environment (today's behaviour, unchanged)
  - `[]` ⇒ a documented **safe base** only (`PATH`, `HOME`, … plus the Windows equivalents)
  - `[names]` ⇒ the safe base **plus** only those named variables
- **`defaultInheritEnv` on the loader/toolkit options**, so a host can narrow every server at once
  without editing each entry. A server's own `inheritEnv` overrides it.
- **The same control for the builtin shell tool** (`bash`), which ADR 0003 M4 did not cover.
- **The safe-base set is pinned in `SPEC.md` and must be byte-identical in all seven ports.** A
  set that drifts per language means the same config leaks differently depending on which port a
  team deployed, which is the single failure this repo exists to prevent.
- **Not a behaviour change when absent.** Every default is `undefined`, so a host that sets
  nothing gets a byte-identical run. This narrows only *which names* cross the boundary; values
  still resolve from the live environment at spawn time, never from a config file, and are never
  logged.
- **Not** a sandbox, and the docs must say so: this scopes an environment, it does not isolate a
  process. Filesystem and network reach are unchanged and remain the host's job.

## Capabilities

### New Capabilities
- `child-process-env`: the one contract both spawn paths obey — the three-state `inheritEnv`
  semantics, the byte-identical safe-base set (including its Windows members), precedence between
  a per-server value and the loader default, and the rule that explicit `env`/`environment`
  entries always win and are never logged.

### Modified Capabilities
- `mcp-load-lifecycle`: a stdio server's child environment is built per `child-process-env`
  instead of unconditionally inheriting the parent; adds `inheritEnv` per server and
  `defaultInheritEnv` on the loader options.
- `builtin-tools`: the shell tool builds its child environment per `child-process-env` instead of
  unconditionally inheriting the parent.

## Impact

- **Spec**: `SPEC.md` §2 (MCP config + stdio launch) and §4A (built-in tools) both move.
- **Code, all seven ports**: `golang/mcp.go:474` (`os.Environ()`), `js/src/mcp.ts:309`
  (`{...process.env}`) and their python / java / csharp / elixir / clojure equivalents; the shell
  builtin in each port (`golang/builtin.go:331`, `js/src/builtin.ts:160`,
  `python/src/toolnexus/builtin.py:174`, …).
- **Fixtures**: shared `examples/mcp-scoped-env/` so every port proves the same config produces
  the same child environment.
- **Docs**: the support-agent scenario (which tells readers to put `${CRM_TOKEN}` in `mcp.json`)
  and the coding-agent scenario (which hands a model a real shell) both gain the narrowing, plus
  the explicit "this is not a sandbox" boundary.
- **Open owner decision, recorded as QM4**: whether the default eventually flips to the safe base.
  That is a breaking change and belongs to a major version. This proposal keeps `undefined` =
  inherit-all so it ships non-breaking, and recommends the flip be scheduled.
- **Out of scope**: process isolation, filesystem scoping, and tool-call idempotency on the §10
  retry pass (tracked separately).
