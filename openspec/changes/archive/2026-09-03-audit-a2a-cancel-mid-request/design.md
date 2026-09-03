# Design — A2A cancel contract audit

## The mechanism matrix (why the fix is per-port)

Whether an abort can *interrupt* an in-flight RPC differs per port; the contract (the returned
result) must not. Two transport classes:

**Interruptible** — the in-flight request dies when the signal fires, and the error path must
recognise that death as a cancel:

| port | interrupt mechanism | error observed |
|---|---|---|
| js | `fetch(signal)` rejects | `AbortError` into `catch` |
| golang | `jsonRPC(ctx, …)` | `context.Canceled` wrapped in `err` |
| java | `CancelToken.cancel()` interrupts the run thread | `InterruptedException` into `catch (Exception)` |
| csharp | linked `CancellationTokenSource` | `OperationCanceledException` (already filtered by `when (token.IsCancellationRequested)`) |

**Non-interruptible** — the in-flight RPC runs to completion/timeout and the abort is observed at
the next poll-loop check. The residual race is narrower: an RPC that fails *for its own reasons*
while the signal is set must still report the cancel, so the error path re-checks the signal there
too:

| port | transport | where the abort lands |
|---|---|---|
| python | blocking `urllib` in `asyncio.to_thread` | next loop check, or `except Exception` if the RPC raises |
| clojure | koine `http/request` takes no ctx | next loop check, or the `rpc!` `{:error …}` path |
| elixir | `Req.request` blocking the caller process | next loop check, or `rescue` |

The fix is the same sentence in every port: **on any error/exit path, if the signal has fired,
return the canceled result — never a raw transport error.**

## Elixir signal semantics (the one genuine design decision)

`Toolnexus.Context.signal` is typed `reference() | pid() | nil` but is read nowhere — the port
shipped the field without semantics. This change defines them, mirroring the clojure port
(`(:aborted? ctx)`, a caller-owned predicate) so the two functional ports agree:

- `signal` as a **zero-arity function** `(-> boolean())` — aborted when it returns true. Fully
  caller-owned (back it with an `Agent`, `:atomics`, a GenServer call — the library holds no
  state). This is the primary form.
- `signal` as a **pid** — aborted when the process is no longer alive (`Process.alive?/1`). The
  OTP-native token: spawn a watcher, kill it to cancel.
- `reference()` is **removed from the type**: nothing implements it, and an ETS/registry backing
  it would need an OTP application the library deliberately does not have. The `@type` changes to
  `(-> boolean()) | pid() | nil`; the field itself is unchanged.

The A2A execute then wires the same four points js has: check at the top of the poll loop, an
abortable sleep (20ms chunks, like python's `_sleep`), check after the sleep, and re-check in
`rescue`.

## The shared regression-test shape (deterministic mid-request abort)

One stub shape covers every port, interruptible or not, without wall-clock races:

1. Stub agent: `SendMessage` → `submitted` task `t1`; `GetTask` #1 → `working`; `GetTask` #2+
   **hangs ~300ms, then 500s**. The stub counts `GetTask` calls.
2. Execute with a small `pollEvery`. The test waits until the stub reports `GetTask` #2 has
   **started** (the in-flight window is demonstrably open), then aborts.
3. Assert `isError: true`, output `"A2A task t1 canceled"`, `metadata.state == "canceled"`.
   - Interruptible ports: the hung request dies immediately → error path → canceled.
   - Non-interruptible ports: the 500 arrives ~300ms later → error path with signal set →
     canceled.
4. Assert no further `GetTask` calls after the abort settles.

A second test pins the golang-shaped gap on every port: `SendMessage` itself hangs-then-500s;
abort mid-`SendMessage`; same assertions. Each test is verified to **fail without the fix** before
the port's fix is applied.

## Why not make non-interruptible transports interruptible

Cancelling an in-flight `urllib`/`koine`/`Req` call would need per-port machinery (task
cancellation races, process isolation) out of proportion to the gain: the abort is already
observed at the next poll boundary (≤ `pollEvery` after the RPC settles, bounded by the call
budget). The contract pins the *result*, not the latency, and the poll loop's "stop before the
next `GetTask`" is already honored. Recorded here so a future change can revisit deliberately.
