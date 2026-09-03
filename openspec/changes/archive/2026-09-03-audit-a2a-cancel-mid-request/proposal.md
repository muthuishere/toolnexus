# A2A cancel contract: an abort mid-request is a cancel, not a transport error

## Why

`SPEC.md:601-602` pins the §7A cancel contract: **`ctx` abort ⇒ `"A2A task <id> canceled"`**, with
`metadata.state` reporting `canceled`. GitHub issue #64 tracks the cross-port audit. The audit is
now complete, and four of seven ports violate the contract when the abort lands **while a
`SendMessage`/`GetTask` RPC is in flight** rather than between polls:

| port | verdict | evidence |
|---|---|---|
| js | ✅ fixed (the reference) | loop checks + `catch` re-checks `signal.aborted` (`js/src/a2a.ts:346-354`) |
| csharp | ✅ already correct | `catch (OperationCanceledException) when (token.IsCancellationRequested)` (`csharp/src/Toolnexus/A2A.cs:459-465`) |
| golang | ⚠️ half-guarded | `GetTask` error path checks `aborted()` (`golang/a2a.go:484`); the **`SendMessage` error path (`a2a.go:451-453`) does not** — an abort mid-`SendMessage` returns the raw `context canceled` string with state `submitted` |
| java | ❌ defective | `CancelToken.cancel()` interrupts the run thread (`LlmClient.java:242`); an abort mid-`HttpClient.send` throws `InterruptedException` into the generic `catch (Exception)` (`A2A.java:241-244`), returning `"java.lang.InterruptedException"` with the last known state |
| python | ❌ defective (narrow) | the in-flight `urllib` call is not interruptible, so the abort is observed at the next loop check — but `except Exception` (`a2a.py:355-356`) has no abort check, so an RPC that *fails* while the signal is set reports the raw error |
| clojure | ⚠️ narrow gap | poll loop checks `aborted?` both sides of the sleep; neither `rpc!` error path (`a2a.cljc:206-207` SendMessage, `:243-245` GetTask) does — same race as python |
| elixir | ❌ **missing entirely** | `Context.signal` exists in the struct (`types.ex:128`) but **nothing in `lib/` or `test/` reads it** — no abort semantics in the whole port. The poll loop (`a2a.ex:169-199`) has no cancel check and its comment even drops the `/ cancel` the other ports carry |

The js port already lived here: the same defect class surfaced as a "flaky" test
(`a2a: ctx cancel mid-poll stops further GetTask calls`, ~2 in 12 failures) that was actually
intermittently catching a real defect. That is the shape of the regression every port needs to be
pinned against.

## What Changes

- **Every error/exit path of an agent tool's `execute` re-checks the abort signal before
  reporting a transport error.** An abort observed *anywhere* — between polls, mid-`SendMessage`,
  mid-`GetTask` — returns `isError: true`, output `"A2A task <id> canceled"`,
  `metadata.state == "canceled"`. This is the whole contract; it is stated once and applies to all
  seven ports.
- **golang**: guard the `SendMessage` error path identically to the `GetTask` path.
- **java**: in the `catch (Exception)` path, a cancelled `ctx` yields the canceled result.
- **python**: in the `except Exception` path, an aborted signal yields the canceled result.
- **clojure**: both `rpc!` error paths check `aborted?` first.
- **elixir**: define and implement the port's abort semantics (see design) and wire the two
  poll-loop checks, an abortable sleep, and the `rescue` re-check — the same four points js has.
- **Not a breaking change.** The only results that change are ones currently returned as raw
  transport errors for a call the caller had already cancelled — i.e. results no caller can be
  relying on.

## Capabilities

### Modified Capabilities

- `a2a-outbound`: the "An agent tool submits and polls over A2A JSON-RPC" requirement's
  cancellation clause is strengthened from "abort the poll" to cover the mid-request window, with
  two new scenarios (abort mid-`GetTask`, abort mid-`SendMessage`).

## Impact

- **Code**: `golang/a2a.go`, `java/.../A2A.java`, `python/src/toolnexus/a2a.py`,
  `clojure/src/toolnexus/a2a.cljc`, `elixir/lib/toolnexus/a2a.ex` +
  `elixir/lib/toolnexus/types.ex` (the `signal` type gains `(-> boolean())`). js and csharp are
  the reference and need no code change.
- **Tests**: one new deterministic mid-request regression test per port (shape in design); js and
  csharp keep their existing tests as evidence.
- **Contract**: `SPEC.md:601-602`'s cancel clause is unchanged — the spec already pins the
  outcome; this change makes the ports true to it. No SPEC.md edit needed.
- **Risk**: low. Each fix is a re-ordering of an existing check onto an error path; no control
  flow changes on the success path, so a call that is never cancelled is byte-identical.
