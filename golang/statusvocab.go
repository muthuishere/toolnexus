package toolnexus

// There are TWO closed status vocabularies in this library and they share a
// field name. That collision is a real defect and it is what sent a consumer
// branching on `res.Status == "timeout"` against a §8 RunResult, where the value
// can never appear (ADR 0027 D2, issue #92).
//
//	§8  client RunResult.Status : done | pending | incomplete        (here)
//	§7D agent  TaskResult.Status: done | pending | incomplete |
//	                              interrupted | closed | timeout | error
//	                              (package agents — see agents.TaskStatus*)
//
// "timeout" belongs EXCLUSIVELY to the agent runtime's Wait(handle, timeout) —
// a wait deadline where the child keeps running. A §8 whole-run deadline is
// reported as RunStatusIncomplete with RunLimitTimeout, which invents no new
// value in this set.
//
// The constants below exist so the values stop being prose plus inline literals
// — which is precisely how the wrong vocabulary gets read. Fields stay strings:
// naming the values is the fix, renaming public fields is not.
//
// KNOWN RESIDUAL GAP: these constants pin the VALUES; nothing yet pins the
// invariant that a third vocabulary cannot land on a third field called
// `status`. Tracked as a follow-up conformance row (DECISIONS A7).
const (
	// RunStatusDone — the loop produced a final answer.
	RunStatusDone = "done"
	// RunStatusPending — a tool suspended and no WaitFor was configured (§10);
	// RunResult.Pending carries the Request.
	RunStatusPending = "pending"
	// RunStatusIncomplete — a LIMIT stopped the run with no final answer.
	// RunResult.Limit names which; partial work is preserved.
	RunStatusIncomplete = "incomplete"
)

// The Limit vocabulary that accompanies RunStatusIncomplete. Identical strings
// in all seven ports, like the status vocabulary they sit beside.
const (
	// RunLimitMaxTurns — the turn cap was exhausted while the model was still
	// emitting tool calls.
	RunLimitMaxTurns = "maxTurns"
	// RunLimitCompletion — the agent's completion gate refused the work for the
	// last permitted attempt.
	RunLimitCompletion = "completion"
	// RunLimitTimeout — the whole-run deadline (ClientOptions.TimeoutMs) expired.
	// The error is a *RunTimeoutError; the result is NOT the zero value.
	RunLimitTimeout = "timeout"
)
