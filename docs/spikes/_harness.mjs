// Shared spike harness.
//
// A spike is EVIDENCE cited by an ADR, so two properties matter more than what it
// prints: it must be RUNNABLE BY ANYONE (no machine-specific paths), and it must be
// able to FAIL (a spike that always exits 0 proves nothing about the run you just
// did — it proves only that node started).
//
// `check()` prints in the same `  label: true` shape the earlier spikes used by
// hand, so ADR quotes stay accurate, and additionally records the result and sets a
// non-zero exit code when anything is false.

/** The built JS port, resolved relative to THIS file — never an absolute home dir. */
export const DIST = new URL("../../js/dist", import.meta.url).href

let passed = 0
let failed = 0
const failures = []

export function section(name) {
  console.log("")
  console.log(`=== ${name} ===`)
}

/** Print + record one assertion. `cond` must be exactly `true` to pass. */
export function check(label, cond, detail) {
  const ok = cond === true
  if (ok) passed++
  else {
    failed++
    failures.push(label)
  }
  console.log(`  ${label}: ${ok}${detail === undefined ? "" : `  ${detail}`}`)
  return ok
}

/** A line of context that is not itself an assertion. */
export function note(line) {
  console.log(`  ${line}`)
}

/**
 * Record a deliberate NON-assertion, with the reason.
 *
 * Taken from the reference harness's `verify-package-invariants` rule (ADR 0016):
 * an absence must be explained, because an omission that stops being mentioned is
 * indistinguishable from something forgotten.
 */
export function notApplicable(label, why) {
  console.log(`  — not asserted: ${label}`)
  console.log(`      ${why}`)
}

/** Final tally. Sets process.exitCode = 1 if any check failed. */
export function report(title) {
  console.log("")
  if (failed === 0) {
    console.log(`PASS — ${title}: ${passed} checks, 0 failed`)
  } else {
    console.log(`FAIL — ${title}: ${passed} passed, ${failed} FAILED`)
    for (const f of failures) console.log(`  ✗ ${f}`)
    process.exitCode = 1
  }
}

/** Assert a thrown error, and that its message names the reason. */
export async function throws(label, fn, expectedSubstring) {
  let msg = null
  try {
    await fn()
  } catch (e) {
    msg = e instanceof Error ? e.message : String(e)
  }
  const ok = msg !== null && (!expectedSubstring || msg.includes(expectedSubstring))
  check(label, ok, msg === null ? "(did not throw)" : `"${msg.slice(0, 70)}"`)
  return msg
}

/** Yield real event-loop turns so in-flight promise chains can settle. */
export async function drain(turns = 6) {
  for (let i = 0; i < turns; i++) await new Promise((r) => setImmediate(r))
}

/**
 * A virtual clock for the §7D `clock` seam. Time only moves when you advance it,
 * so a schedule test is deterministic rather than a sleep race.
 */
export function virtualClock(startMs = 0) {
  let now = startMs
  let seq = 0
  const timers = new Map()
  return {
    now: () => now,
    setTimeout(fn, ms) {
      const id = seq++
      timers.set(id, { at: now + Math.max(0, ms), fn })
      return () => timers.delete(id)
    },
    /** Advance time, firing every timer due, in due-time order. */
    async advance(ms) {
      const target = now + ms
      for (;;) {
        let next = null
        for (const [id, t] of timers) {
          if (t.at <= target && (next === null || t.at < next[1].at)) next = [id, t]
        }
        if (!next) break
        const [id, t] = next
        timers.delete(id)
        now = t.at
        t.fn()
        // Let whatever the callback started actually settle before the next timer.
        // Microtask ticks are NOT enough: a scheduled fire wakes a handle whose turn
        // awaits a (mocked) fetch, so the chain needs real macrotask turns. A run that
        // is deliberately blocked stays blocked — this yields, it does not force.
        await drain()
      }
      now = target
    },
    pending: () => timers.size,
  }
}
