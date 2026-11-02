## Decisions

**Safety comes from hints, not a new label.** `ToolHints.readOnly` already exists on every builtin
and on MCP tools whose server sends annotations. Reusing it means MCP read-only tools get
parallelism for free. An explicit predicate covers per-input cases (e.g. `bash` with a read-only
command, a tool with a `dryRun` flag).

**Unknown means unsafe.** A tool with no hints may write. Serialising it costs latency; racing it
costs correctness. The host opts in.

**Scheduling in emitted order.** Calls are offered to the scheduler in the order the model emitted
them. A safe call takes a slot (blocking the offering loop when the cap is reached) and runs. An
unsafe call first waits for everything already running, then runs alone; later calls are not
offered until it returns. This is the "consecutive runs" partition without materialising it, and it
needs no change to how results are collected: each call still writes its own slot, so order is
untouched.

**Relay and `task` are always safe.** A relay call is executed by the host, and §10 surfaces every
relay call of a turn in one Request — serialising them would split that Request. A `task` call
spawns an isolated child whose concurrency is already governed by the runtime's per-parent gate;
serialising them would remove the fan-out §7D promises. Agent-as-tool (`asTool`) is NOT
special-cased: a child agent with write tools is exactly the case the rule protects.

**Cap resolution.** Option > env > 10. The env var lets an operator throttle a deployed host
without a code change. Invalid values fall back rather than fail, matching other env knobs.

**Background runs are a recipe.** `wake` already returns before the turn ends and `wait` returns
the next settle. A seventh verb or an `onDone` hook would duplicate `wait`, and §7D's "composition,
not API" rule argues against it. The recipe is documented and tested in every port.

## Not done

- No per-tool cap (e.g. "at most 2 webfetch at once"); the cap is per response.
- `todowrite` keeps its existing `readOnly` hint, so two `todowrite` calls in one response may
  overlap (last writer wins), as before.
