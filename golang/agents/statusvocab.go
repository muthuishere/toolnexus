package agents

// The §7D agent status vocabulary — SEVEN values, and a DIFFERENT SET from the
// §8 client's three (toolnexus.RunStatus*). The two share a field name, which is
// the collision behind issue #92: a host that read this closed set and branched
// on `"timeout"` against a §8 RunResult was reading the right documentation for
// the wrong type.
//
//	§7D agent  TaskResult.Status (here)   : done pending incomplete
//	                                        interrupted closed timeout error
//	§8  client RunResult.Status           : done pending incomplete
//
// "timeout" is exclusively this set's, and exclusively Wait(handle, timeout) —
// a WAIT deadline, where the child keeps running. A §8 run deadline surfaces as
// TaskStatusIncomplete with LimitTimeout.
//
// KNOWN RESIDUAL GAP: these constants pin the values, not the invariant that no
// third vocabulary may land on a third `status` field (DECISIONS A7).
const (
	// TaskStatusDone — the turn produced a final answer.
	TaskStatusDone = "done"
	// TaskStatusPending — parked durably on an unresolved §10 Request.
	TaskStatusPending = "pending"
	// TaskStatusIncomplete — a limit stopped it; TaskResult.Limit names which.
	TaskStatusIncomplete = "incomplete"
	// TaskStatusInterrupted — the in-flight turn was aborted; the inbox is intact.
	TaskStatusInterrupted = "interrupted"
	// TaskStatusClosed — the handle was closed.
	TaskStatusClosed = "closed"
	// TaskStatusTimeout — a WAIT deadline expired while the child kept running.
	// Never a §8 RunResult status.
	TaskStatusTimeout = "timeout"
	// TaskStatusError — the turn failed; only the root may throw to the host.
	TaskStatusError = "error"
)

// The TaskResult.Limit vocabulary is CLOSED and canonical: it names the Budget
// field that stopped the run, spelled exactly as SPEC spells that field, plus
// the two stops that are not Budget fields. Identical strings in all seven
// ports — this is the field hosts branch on, so four ports emitting four
// spellings of it defeats the point of adding it (DECISIONS A14).
const (
	// Budget fields, SPEC spelling (note maxWallMs, not maxWall — the Budget
	// field is a DURATION IN MILLISECONDS and the name says so).
	LimitMaxTurns      = "maxTurns"
	LimitMaxTokens     = "maxTokens"
	LimitMaxToolCalls  = "maxToolCalls"
	LimitMaxWallMs     = "maxWallMs"
	LimitMaxChildren   = "maxChildren"
	LimitMaxConcurrent = "maxConcurrent"
	LimitMaxDepth      = "maxDepth"
	// Not Budget fields: the completion gate refused the work, or the §8
	// whole-run deadline expired.
	LimitCompletion = "completion"
	LimitTimeout    = "timeout"
)
