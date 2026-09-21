## 1. Pin the contract before any port moves

- [ ] 1.1 `SPEC.md` §2 — add `inheritEnv` to the stdio server config table and `defaultInheritEnv`
      to the loader options; state the three states and that an omitted value is byte-identical to today.
- [ ] 1.2 `SPEC.md` §4A — add `inheritEnv` to the builtin options for the `bash` tool.
- [ ] 1.3 `SPEC.md` — add the safe-base name list once, in full, with its POSIX and Windows members
      and the "absent ⇒ omitted, never blank" rule. Every port cites this one list.
- [ ] 1.4 `SPEC.md` — state the precedence chain: safe base → named passthrough → explicit
      `env`/`environment`, with explicit always winning and never needing to be named.
- [ ] 1.5 `SPEC.md` — state plainly that this is not process isolation, and that filesystem and
      network reach are unchanged.
- [ ] 1.6 `docs/adr/0003-mcp-host-lifecycle-and-liveness.md` — mark M4 as promoted into this change,
      and record the QM4 answer (default stays inherit-all; flip recommended for the next major).

## 2. Shared fixtures — the thing that catches drift

- [ ] 2.1 `examples/mcp-scoped-env/mcp.json` — three stdio servers exercising all three states:
      one omitting `inheritEnv`, one with `[]`, one with `["TN_SCOPED_ALLOWED"]`.
- [ ] 2.2 `examples/mcp-scoped-env/echo-env.*` — a tiny server/stub that reports the **names** it
      received (never values), so the fixture can be asserted without printing a secret.
- [ ] 2.3 `examples/mcp-scoped-env/expected-names.json` — the expected child name-set per server,
      per platform. This file is the cross-port oracle.
- [ ] 2.4 Conformance: compare each port's produced name-set against `expected-names.json` rather
      than asserting per-port behaviour, so a port that collapses `[]` into "absent" fails loudly.

## 3. Go (reference port)

- [ ] 3.1 Model the three states so omitted ≠ empty — `*[]string`, not `[]string`; decode
      `inheritEnv` from JSON accordingly.
- [ ] 3.2 Add the safe-base list and a single `childEnv(inherit *[]string, explicit map[string]string) []string`
      helper used by **both** spawn paths.
- [ ] 3.3 `mcp.go:474` — replace `os.Environ()` with `childEnv(...)`; add `defaultInheritEnv` to the loader options.
- [ ] 3.4 `builtin.go:331` — set `cmd.Env` from `childEnv(...)`; add `inheritEnv` to the builtin options.
- [ ] 3.5 Tests: all three states on both paths; absent-name omitted not blanked; explicit entry
      overrides inherited; the leak regression (`env | grep ACME_BILLING_TOKEN` returns nothing with `[]`).
- [ ] 3.6 Test: a server starved of a needed variable is `failed`, is not fatal, and its diagnostic
      contains the variable's name and no value.

## 4. Per-language parity — each port lands items 1–4 of the Go list plus its own tests

- [ ] 4.1 **js** — `mcp.ts:309` and `builtin.ts:160`; distinguish an absent key from `[]` in the
      config decode; `undefined` vs `[]` must not collapse through optional chaining or `??`.
- [ ] 4.2 **python** — `builtin.py:174` (`subprocess.run(..., env=...)`) and the MCP stdio launch;
      `None` vs `[]` must not collapse through a falsy check.
- [ ] 4.3 **java** — `ProcessBuilder.environment()` starts populated from the parent, so it must be
      **cleared** before repopulating; a null vs empty `List<String>` distinction.
- [ ] 4.4 **csharp** — `ProcessStartInfo.Environment` likewise starts populated and must be cleared;
      nullable `IReadOnlyList<string>` for the distinction.
- [ ] 4.5 **elixir** — `:erlang.open_port` `:env` takes charlists and does **not** clear by default;
      `nil` vs `[]` must not collapse (an empty list is truthy in Elixir, which helps here).
- [ ] 4.6 **clojure** — plain `.cljc`, no reader conditionals, verified on JVM **and** cljgo;
      `nil` vs `[]` must not collapse through `seq`/`empty?` checks.
- [ ] 4.7 Every port: run the shared fixture against `expected-names.json` and confirm the name-set matches.

## 5. Docs — and the honesty guardrail

- [ ] 5.1 `cookbook/mcp-servers` — document `inheritEnv` / `defaultInheritEnv` with the three states
      and the safe-base list, in all seven language tabs.
- [ ] 5.2 `cookbook/builtins` (or the built-in tools page) — the same for the `bash` tool.
- [ ] 5.3 `scenarios/support-agent` — this page already tells readers to put `${CRM_TOKEN}` in
      `mcp.json`; show the orders server narrowed to `[]` and the CRM server to `["CRM_TOKEN"]`.
- [ ] 5.4 `scenarios/coding-agent` — narrow the shell in the full assembly, since that page hands a
      model a real shell.
- [ ] 5.5 Every one of those pages states that this is **not** a sandbox: filesystem and network
      reach are unchanged and remain the host's job. Never call it a security boundary.
- [ ] 5.6 `CHANGELOG.md` — one entry under `## Unreleased` written from the user's side: what they
      get, why it matters, that the default is unchanged, and that the flip is recommended for the
      next major. Name what is NOT done (no process isolation, no filesystem scoping).

## 6. Verify

- [ ] 6.1 Run the narrowest useful suite per touched port and record which ran.
- [ ] 6.2 Confirm absent-configuration runs are byte-identical to `main` on at least one port, so
      the non-breaking claim is tested rather than asserted.
- [ ] 6.3 Re-run the leak regression on all seven ports; none may return a credential value.
- [ ] 6.4 `openspec validate add-scoped-child-env`.
