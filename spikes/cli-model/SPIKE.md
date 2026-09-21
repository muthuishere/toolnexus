# Spike — ADR 0026: CLI-backed model source

Spikes ADR 0026's Gate (`docs/adr/0026-cli-backed-model-source-the-envelope-is-the-contract.md`)
against issue #97. Go only, hermetic, no network, no real agent CLI invoked. Everything lives
under `spikes/cli-model/` and is self-contained (its own `go.mod`, `replace` pointing at
`../../golang`).

## What was built

- `fakecli/` — a scripted, hermetic stand-in for a one-shot agent CLI (devin/claude/codex/copilot
  shaped). It never touches the network; it plays back an ordered JSON script of canned response
  bodies, one per process launch, tracked via an on-disk counter file (`-state`). Reads a prompt
  from `-prompt-file` (file channel) or `-prompt` (argv channel), writes its reply to stdout or to
  `-out` (codex's `--output-last-message` shape). `-echo-to` dumps the exact bytes it received, for
  the passthrough assertion.
- `climodel/generate.go` — a CLI-backed `Generate` that plugs directly into
  `toolnexus.CreateInProcessClient` (`golang/inprocess.go`), unmodified. It:
  - assembles the `<openai_request endpoint="/v1/chat/completions">{verbatim body}</openai_request>`
    envelope from `InProcessRequest.Body` — the same map the client would have sent over the wire —
    and an `<instructions>` block;
  - launches the configured CLI (argv template with `{{file}}`/`{{prompt}}`/`{{out}}`/`{{model}}`
    placeholders);
  - strictly parses the `<openai_response>` back, dispatching on **what the message contains**,
    never on `finish_reason` or any custom `kind` field;
  - on any drift, resends the **same** envelope with a specific `<repair>` complaint appended, up
    to `RepairBudget` additional attempts, then errors.
- `climodel/generate_test.go`, `climodel/argvlimit_test.go` — the gate tests themselves.

Command reference used throughout:

```
cd spikes/cli-model
GOFLAGS=-mod=mod GOPROXY=off go build ./...
GOFLAGS=-mod=mod GOPROXY=off go vet ./...
GOFLAGS=-mod=mod GOPROXY=off go test ./... -v
gofmt -l .
```

`GOPROXY=off` was used throughout to prove no network access is needed (all deps were already in
the local module cache from the parent `golang/` module).

## What was run, verbatim

```
$ cd spikes/cli-model && GOFLAGS=-mod=mod GOPROXY=off go test ./... -v
=== RUN   TestArgvChannel_FailsOnALargePrompt_FileChannelDoesNot
    argvlimit_test.go:64: confirmed E2BIG on argv channel: climodel: CLI launch failed on attempt 0: fork/exec /var/folders/.../fakecli: argument list too long (stderr: )
--- PASS: TestArgvChannel_FailsOnALargePrompt_FileChannelDoesNot (0.21s)
=== RUN   TestMeasureArgMax
    argvlimit_test.go:88: getconf ARG_MAX = 1048576
    argvlimit_test.go:115: measured argv ceiling on this machine: fits at 1028994 bytes of message content, fails by 1033090 bytes (total argv includes the envelope wrapper + inherited environment, not just this payload)
--- PASS: TestMeasureArgMax (0.10s)
=== RUN   TestDriftA_ToolCallsWinOverFinishReasonAndKind
--- PASS: TestDriftA_ToolCallsWinOverFinishReasonAndKind (0.01s)
=== RUN   TestDriftB_ContentAsObjectTriggersRepairThenSucceeds
--- PASS: TestDriftB_ContentAsObjectTriggersRepairThenSucceeds (0.01s)
=== RUN   TestDriftC_ArgumentsAsObjectTriggersRepairThenSucceeds
--- PASS: TestDriftC_ArgumentsAsObjectTriggersRepairThenSucceeds (0.01s)
=== RUN   TestDriftD_DuplicateToolCallTriggersRepairThenSucceeds
--- PASS: TestDriftD_DuplicateToolCallTriggersRepairThenSucceeds (0.01s)
=== RUN   TestRepairBudgetExhausted_ErrorsInsteadOfLoopingForever
--- PASS: TestRepairBudgetExhausted_ErrorsInsteadOfLoopingForever (0.01s)
=== RUN   TestVerbatimPassthrough_ByteEqualIncludingUnknownKey
--- PASS: TestVerbatimPassthrough_ByteEqualIncludingUnknownKey (0.01s)
=== RUN   TestCostReporting_RepairAttemptsAreInvisibleToClientMetricsUnlessSelfReported
--- PASS: TestCostReporting_RepairAttemptsAreInvisibleToClientMetricsUnlessSelfReported (0.01s)
PASS
ok  	github.com/muthuishere/toolnexus/spikes/climodel/climodel	0.727s
?   	github.com/muthuishere/toolnexus/spikes/climodel/fakecli	[no test files]
```

`go vet ./...` and `gofmt -l .` both produced no output (clean).

Machine: macOS (Darwin 25.4.0, arm64), go1.26.3.

## Verdict per Gate item

### 1. The envelope contract survives a hostile model

**HOLDS**, for all four drifts, reproduced against the fake CLI with strict-parse + bounded repair.

- **1a — `kind:"answer"` WITH a populated `tool_calls` array (most important one).**
  `TestDriftA_ToolCallsWinOverFinishReasonAndKind` sends a reply with
  `"finish_reason":"answer"`, a `"kind":"answer"` field inside `message`, **and** a populated
  `tool_calls` array. `parseAndValidate` (`generate.go`) dispatches purely on
  `len(msg.ToolCalls) > 0` — it never reads `finish_reason` or `kind` at all. The call surfaces
  with **zero repairs** (`RepairBudget: 0`, and the test asserts exactly 1 launch): the fix isn't
  "detect and repair" for this one, it's "never look at the wrong field in the first place." This
  is the literal statement from the ADR: *what the message contains has to win.*

- **1b — structured payload in `content` as an object instead of `arguments`.**
  `TestDriftB_ContentAsObjectTriggersRepairThenSucceeds`. Strict parse rejects any `content` whose
  JSON value isn't absent/`null`/a string (`generate.go`'s Drift-B check trims and looks at the
  first byte — a bare `{` or `[` is rejected). Repair complaint: *"message.content must be a JSON
  string (or absent), not a JSON object/array; put a tool call's structured payload in
  tool_calls[].function.arguments instead."* Second scripted reply is compliant; 2 launches total,
  final result is a correct tool call.

- **1c — `arguments` as a bare JSON object rather than the OpenAI-contractual JSON-encoded
  string.** `TestDriftC_ArgumentsAsObjectTriggersRepairThenSucceeds`. Same shape: reject, name the
  fault (*"tool_calls[0].function.arguments must be a JSON-encoded STRING ..., not a bare JSON
  object"*), resend, succeed on attempt 2. **Interpretation note:** the ADR's one-line phrasing
  ("arguments arriving as a JSON-encoded string rather than an object") is genuinely ambiguous out
  of context — real OpenAI wire format mandates `arguments` always be a JSON-encoded *string*, so
  read literally the ADR's "expected" and "drift" are backwards from the real spec. This spike
  takes the reading that matches the real wire contract and the spirit of 1b (a structured payload
  landing somewhere other than a properly-encoded string): the **drift** is a bare object, the
  **fix** is a JSON string. Worth the ADR author confirming with a concrete example before this
  ships for real.

- **1d — re-calling a tool whose result is already in the transcript.**
  `TestDriftD_DuplicateToolCallTriggersRepairThenSucceeds`. `alreadyAnswered` scans
  `InProcessRequest.Messages` for a prior `assistant` `tool_calls` entry with the same name +
  semantically-equal arguments (compared via a JSON-normalize-and-remarshal, not string equality)
  that already has a matching `tool` role message by `tool_call_id`. Reject, complaint names the
  tool, resend, the compliant reply answers from the existing result instead. 2 launches.

### 2. Verbatim passthrough is real

**HOLDS.** `TestVerbatimPassthrough_ByteEqualIncludingUnknownKey` wires the CLI-backed `Generate`
through the *actual* `toolnexus.CreateInProcessClient` (not a hand-rolled harness), with a
`BodyTransform` that injects `x_vendor_extension_never_seen: 424242` — a key no adapter in this
repo has ever heard of. After `client.Run(...)`, the test reads back the **actual prompt file the
fake CLI process opened**, extracts the `<openai_request>...</openai_request>` payload, and asserts
its bytes are identical to an independent `json.Marshal` of the same decoded map (`canonicalBody`
in `generate.go`) — Go's `encoding/json` sorts map keys deterministically, so two marshals of an
equal map are byte-identical; this makes "byte-equal" a real assertion, not a semantic
deep-equal. It also asserts the literal substring `"x_vendor_extension_never_seen":424242` is
present in the bytes the CLI received. The envelope is built once from `json.Marshal(req.Body)` and
reused unchanged across every repair attempt (only the appended `<repair>` block differs) — so
nothing in this adapter re-renders, re-orders, or summarizes the body before handing it to the CLI.

### 3. The repair budget terminates

**HOLDS.** `TestRepairBudgetExhausted_ErrorsInsteadOfLoopingForever` scripts a fake CLI that
**never** produces a valid reply (fakecli's counter clamps to its last script entry once the script
is exhausted, so "never complies" is trivial to express — a 1-entry script). With
`RepairBudget: 2` (1 first try + 2 repairs = 3 allowed launches), `generate()` returns a typed
`*errBudgetExhausted` error and the `OnLaunch` counter confirms **exactly 3** launches — not 4, not
an unbounded loop. The loop in `generate()` has no other exit condition tied to time or retries; it
is a plain bounded `for` incrementing `attempt` and comparing against `opts.RepairBudget`.

### 4. Prompt-file vs argv

**HOLDS**, and the measured number matters more than the qualitative claim.

- `getconf ARG_MAX` on this machine: **1,048,576 bytes (1 MiB)**.
- `TestArgvChannel_FailsOnALargePrompt_FileChannelDoesNot`: an 8 MiB prompt over the **argv**
  channel fails with `fork/exec .../fakecli: argument list too long` — a real `E2BIG` from the OS,
  not a simulated one — while the *same* 8 MiB prompt over the **file** channel succeeds
  unchanged.
- `TestMeasureArgMax` binary-searches the real ceiling with a normal *inherited* developer-shell
  environment (not a stripped one — the realistic case, since `execve`'s limit is on **argv +
  environment combined**): it converges to **~1,028,994 bytes** of message content fitting and
  failing by ~1,033,090 bytes — i.e. the practical ceiling for this adapter's envelope (JSON
  wrapper + `<instructions>` block + one CLI process's inherited env) lands **within ~2% of the raw
  `ARG_MAX`**, consistent with the wrapper/env overhead being small relative to the 1 MiB budget.
  A dev shell's actual environment (`os.Environ()`) was used unmodified, so this is the number a
  real invocation on this box would actually hit, not a best case with `Env` zeroed out.
- Conclusion: **1 MiB is not a generous margin for a coding-agent prompt.** A single large file
  pasted into context, a skill's full instructions, or a multi-turn transcript being replayed
  routinely exceeds it. The file channel isn't a nice-to-have; on this box it's the difference
  between working and a hard OS-level failure with no retry that helps.

### 5. Cost honesty

**PARTIAL — real gap, with a documented mitigation path, not automatic.**

`TestCostReporting_RepairAttemptsAreInvisibleToClientMetricsUnlessSelfReported` scripts one hostile
reply + one compliant repair (2 real CLI process launches, confirmed via the adapter's own
`OnLaunch` hook) behind a single `client.Run(...)` call, with `toolnexus.InProcessOptions.OnMetric`
wired to count `"llm"` events. Result: **`OnLaunch` fires 2 times; toolnexus's own `"llm"`
`MetricEvent` fires exactly 1 time.**

This is structural, not a bug to fix in this adapter: `CreateInProcessClient`'s round tripper
(`golang/inprocess.go`) calls `Generate` once per HTTP-shaped request and emits one `"llm"` metric
per that one call (`client.go:456` `emitLLM`, fired once per `runOpenAI` iteration). Everything a
CLI-backed `Generate` does *inside* that one call — including every repair-driven relaunch of a
~15s process — is invisible on that seam by construction. ADR 0022 ("cost is always reported")
therefore does **not** hold automatically for CLI-backed repairs: a run's `TotalTokens`/cost as
reported by `toolnexus.Client` will under-count a run that needed repairs, silently, unless the
`Generate` implementation folds the extra cost in itself.

Two ways to close the gap, neither free:
1. **Self-report via a side channel** — this spike's `Options.OnLaunch` hook, which a host wires to
   its own counter/Prometheus metric/log line, independent of `toolnexus.OnMetric`. This is what
   the spike actually demonstrates working.
2. **Fold it into the returned `Usage`** — sum a per-launch cost/token estimate across all attempts
   before returning the final `InProcessResponse`, so the single `"llm"` event toolnexus does emit
   at least carries the true total. Not implemented here: a scripted fake CLI has no real token
   usage to sum, so this would be simulated rather than demonstrated, and the ADR's gate asks what
   the run *currently* reports, not what a future enhancement could report.

**Recommendation:** if ADR 0026 ships, it should explicitly document that repair-attempt cost is
NOT counted by `toolnexus`'s own metrics on this seam, and ship (2) as a built-in behavior of the
CLI-backed `Generate` (accumulate a per-launch token/cost estimate and report it as the final
`Usage`) rather than leaving it to every host to remember to wire `OnLaunch` themselves.

## Recommendation on the ADR's open question

**The four argv presets belong in `examples/`, not the library**, and this spike's own experience
argues for it harder than the ADR's stated reason:

- The ADR is right that a preset is "a compatibility promise about someone else's CLI flags, which
  change without warning and cannot be tested in CI" — that alone is disqualifying for shipping as
  a maintained library surface across seven ports.
- This spike additionally had to make a real judgment call to resolve drift 1c's ambiguous wording
  against the real OpenAI wire contract (see 1c above). A hand-maintained preset table for 4
  external CLIs, each with their own idiosyncratic response habits, would accumulate exactly this
  kind of undocumented judgment call per CLI, per drift, per release — with no CI to catch drift
  when a CLI's flags or its model's habits change.
- What *does* belong in the library, and is what this spike actually built and is generalizable:
  the **envelope**, the **strict-parse-then-repair state machine**, the **file-vs-argv channel
  abstraction with a measured real-world justification for preferring file**, and the **argv
  template substitution** (`{{prompt}}`/`{{file}}`/`{{out}}`/`{{model}}`). That's `Generate`-seam
  shaped, testable without a real CLI (as this spike proves), and stable. The four concrete
  `Command`+`ArgvTemplate` values for devin/claude/copilot/codex are exactly the part that a config
  file or `examples/cli-model/presets.json` should own — updatable without a release, and
  explicitly NOT a conformance-tested contract across the seven ports.

## What this spike does NOT settle

- Whether `RepairBudget`, the envelope wording, or the specific complaint strings should be part of
  `SPEC.md` (cross-language contract) or left as Go-idiomatic implementation detail — this spike is
  Go-only per the task, so it says nothing about the other six ports.
- Drift 1c's exact real-world shape (see the interpretation note above) — needs a concrete example
  from the reporter's live `devin` run before this is pinned in a real OpenSpec change.
- The "retries off" interaction from ADR 0023 was not exercised here: `CreateInProcessClient`
  already forces `OnError -> TierFail` (see `golang/inprocess.go`), so `Client.Run` never retries a
  failed `Generate` call regardless of this adapter — that part is inherited, not something this
  spike needed to prove.

## Repo hygiene

Everything lives under `spikes/cli-model/` (own `go.mod`, `replace ../../golang`). No files outside
this directory were modified. `git status` from the repo root shows only this new directory plus
the four pre-existing untracked ADR files from before this task started.
