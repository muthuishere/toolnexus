## Why

`harden-suspension-layer` fixed G3 (concurrent suspensions surface the first deterministically, no
transcript leak) in the **non-streaming** loops of all five ports. During that work the Go and C# port
agents independently found the **same G3 bug still present in their streaming loops**: `stream()`'s
tool dispatch accumulates a last-write-wins `halted` and pushes every suspension's placeholder,
instead of halting on the first suspension in order the way the JS, Java, and Python streaming loops
already do. That is a cross-port parity defect — the streaming path diverges from the non-streaming
path and from the other ports.

## What Changes

- Bring the **streaming** loops (`streamOpenAI` / `streamAnthropic` and their per-port equivalents) to
  the same first-in-order halt as the non-streaming loops: on the first suspension that halts (no
  `waitFor`), emit the `pending` stream event, record that one tool result, yield the `done`
  `RunResult{status:"pending"}`, and stop — later concurrent suspensions do not enter the transcript.
- Confirmed already correct (no change) in **JS, Java, Python** streaming loops; the fix targets **Go
  and C#** streaming loops only.
- Add a streaming G3 regression test to the two ports being fixed (and verify the three already-correct
  ports still pass).

## Impact

- **Affected spec:** `suspension` (adds a scenario pinning that the first-in-order halt applies to
  streaming, not only non-streaming).
- **Affected code:** the streaming tool-dispatch loops in `golang/client.go` and
  `csharp/src/Toolnexus/LlmClient.cs` (both OpenAI + Anthropic streaming variants).
- **Non-breaking:** streaming consumers that only ever had ≤1 suspension per turn are unaffected;
  the change only removes the loss/leak when >1 suspends concurrently with no `waitFor`.
- **Depends on:** `harden-suspension-layer` (the G3 requirement it extends).
