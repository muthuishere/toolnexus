// SPIKE 0012 — cron as a `Schedule` seam over the SHIPPED heartbeat.
//
// Question: does toolnexus need a scheduler subsystem to offer cron, or is the §7E
// heartbeat already the whole mechanism minus a calendar?
//
// The claim under test: `startAgent(agent, run, { everyMs })` already does every hard
// part — injectable clock, tick coalescing, wake-ONLY-when-idle (overlap safety),
// silent no-op, graceful stop. The only thing missing is the schedule EXPRESSION.
// If that is true, cron is one interface with one method:
//
//     Schedule { nextAfter(t) -> t | null }
//
// ...plus a fixture table that pins the parser across seven ports. The library never
// grows a scheduler; it grows a calendar.
//
// Run: node docs/spikes/0012-schedule-seam-and-cron.mjs
import { DIST, section, check, note, throws, report, virtualClock } from "./_harness.mjs"

const { agents } = await import(`${DIST}/index.js`)
const { AgentRuntime, isVerbError } = agents

// ---------------------------------------------------------------------------
// The proposed seam, in full. This is the ENTIRE library addition being spiked.
// ---------------------------------------------------------------------------

/** every(ms) — today's `everyMs`, expressed as a Schedule. */
const every = (ms) => ({ kind: "every", nextAfter: (t) => t + ms })

/**
 * A deliberately NARROW cron subset — 5 fields, no Quartz extensions (`L`/`W`/`#`),
 * day-of-month and day-of-week OR'd (classic cron), UTC unless `tz` is given.
 *
 * The narrowness IS the design: seven ports each wrapping their local cron library
 * (robfig, Quartz, Cronos, croniter, cron-parser, crontab, ...) would inherit seven
 * different answers on exactly these edge cases. A small hand-rolled subset that a
 * fixture table pins is cheaper to keep identical than seven vendored parsers.
 */
function cron(expr) {
  const fields = expr.trim().split(/\s+/)
  if (fields.length !== 5) {
    throw new Error(`cron: expected 5 fields (min hour dom mon dow), got ${fields.length} in "${expr}"`)
  }
  const RANGES = [
    [0, 59], // minute
    [0, 23], // hour
    [1, 31], // day of month
    [1, 12], // month
    [0, 6], // day of week (0 = Sunday)
  ]
  const parseField = (raw, [lo, hi], idx) => {
    const allowed = new Set()
    for (const part of raw.split(",")) {
      const [spec, stepRaw] = part.split("/")
      const step = stepRaw === undefined ? 1 : Number(stepRaw)
      if (!Number.isInteger(step) || step < 1) throw new Error(`cron: bad step "${part}" in field ${idx}`)
      let from = lo
      let to = hi
      if (spec !== "*") {
        const bounds = spec.split("-")
        if (bounds.length > 2) throw new Error(`cron: bad range "${part}" in field ${idx}`)
        from = Number(bounds[0])
        to = bounds.length === 2 ? Number(bounds[1]) : bounds.length === 1 && stepRaw === undefined ? from : hi
        if (!Number.isInteger(from) || !Number.isInteger(to)) throw new Error(`cron: bad number "${part}" in field ${idx}`)
        if (from < lo || to > hi || from > to) throw new Error(`cron: "${part}" out of range ${lo}-${hi} in field ${idx}`)
      }
      for (let v = from; v <= to; v += step) allowed.add(v)
    }
    return allowed
  }
  const sets = fields.map((f, i) => parseField(f, RANGES[i], i))
  const domRestricted = fields[2] !== "*"
  const dowRestricted = fields[4] !== "*"

  return {
    kind: "cron",
    expr,
    /** Next fire strictly AFTER t, scanning minute by minute. Null if > 4 years out. */
    nextAfter(t) {
      const d = new Date(t)
      d.setUTCSeconds(0, 0)
      d.setUTCMinutes(d.getUTCMinutes() + 1)
      const limit = t + 4 * 366 * 24 * 60 * 60 * 1000
      while (d.getTime() <= limit) {
        const dom = sets[2].has(d.getUTCDate())
        const dow = sets[4].has(d.getUTCDay())
        // Classic cron: when BOTH are restricted the match is OR, not AND.
        const dayOk = domRestricted && dowRestricted ? dom || dow : dom && dow
        if (sets[0].has(d.getUTCMinutes()) && sets[1].has(d.getUTCHours()) && sets[3].has(d.getUTCMonth() + 1) && dayOk) {
          return d.getTime()
        }
        d.setUTCMinutes(d.getUTCMinutes() + 1)
      }
      return null
    },
  }
}

/**
 * The driver: a Schedule + the SHIPPED runtime verbs. Note what is NOT here —
 * no queue, no thread pool, no persistence, no misfire heuristics. It posts a tick
 * and wakes when idle, exactly as §7E's heartbeat already does.
 *
 * `misfire` is REQUIRED, not defaulted, for the same reason `completion.maxAttempts`
 * is: silently choosing between "skip" and "replay 300 missed fires" is a decision
 * about someone else's bill.
 */
function startScheduled(rt, handle, schedule, { misfire, onFire }) {
  if (misfire !== "skip" && misfire !== "catchUp") {
    throw new Error('schedule: misfire policy is required — "skip" or "catchUp"')
  }
  let stopped = false
  let cancel
  let last = rt.clock.now()
  const fired = []

  const arm = () => {
    const next = schedule.nextAfter(last)
    if (next === null || stopped) return
    cancel = rt.clock.setTimeout(() => tick(next), Math.max(0, next - rt.clock.now()))
  }

  const tick = (due) => {
    if (stopped) return
    const now = rt.clock.now()
    // Catch-up: every scheduled instant we slept through, bounded by the scan.
    let missed = 0
    if (misfire === "catchUp") {
      let c = schedule.nextAfter(due)
      while (c !== null && c <= now) {
        missed++
        c = schedule.nextAfter(c)
      }
    }
    for (let i = 0; i < 1 + (misfire === "catchUp" ? missed : 0); i++) {
      rt.post(handle, { from: "clock", channel: "timer", text: "tick" })
    }
    fired.push({ due, at: now, coalescedMissed: misfire === "catchUp" ? missed : 0 })
    // Overlap safety, inherited verbatim from the heartbeat: wake ONLY when idle.
    // A run longer than the interval does not stack; its ticks sit in the inbox and
    // drain as ONE coalesced turn.
    if (handle.state === "idle") {
      const woke = rt.wake(handle, "Scheduled tick.")
      if (woke.ok) void rt.wait(handle).then((r) => onFire?.(r))
    }
    last = Math.max(now, due)
    arm()
  }

  arm()
  return { fired, stop: () => { stopped = true; cancel?.() } }
}

// ---------------------------------------------------------------------------
// (A) The calendar itself — a fixture table, which is how parity survives 7 ports
// ---------------------------------------------------------------------------
section("A: cron subset — the fixture table that pins seven ports")
{
  const FROM = Date.UTC(2026, 8, 14, 10, 30, 0) // Mon 2026-09-14 10:30:00Z
  const TABLE = [
    ["*/15 * * * *", "2026-09-14T10:45:00.000Z", "every 15 minutes"],
    ["0 * * * *", "2026-09-14T11:00:00.000Z", "hourly on the hour"],
    ["30 9 * * *", "2026-09-15T09:30:00.000Z", "daily 09:30 — already past today"],
    ["0 9 * * 1-5", "2026-09-15T09:00:00.000Z", "weekdays 09:00"],
    ["0 0 1 * *", "2026-10-01T00:00:00.000Z", "first of the month"],
    ["0 12 29 2 *", "2028-02-29T12:00:00.000Z", "leap day — skips 2027 entirely"],
  ]
  for (const [expr, expected, why] of TABLE) {
    const got = cron(expr).nextAfter(FROM)
    check(`${expr.padEnd(14)} → ${expected}`, got !== null && new Date(got).toISOString() === expected, `(${why})`)
  }

  // The single nastiest disagreement between real cron libraries.
  const bothRestricted = cron("0 0 13 * 5") // 13th of month OR any Friday
  const oct13 = bothRestricted.nextAfter(Date.UTC(2026, 8, 14))
  check(
    "dom AND dow both restricted ⇒ OR, not AND (classic cron)",
    new Date(oct13).toISOString() === "2026-09-18T00:00:00.000Z",
    `got ${new Date(oct13).toISOString()} — Fri 18th before the 13th of next month`,
  )

  await throws("a 6-field (seconds) expression is REJECTED, not guessed", () => cron("0 0 9 * * 1-5"), "expected 5 fields")
  await throws("an out-of-range field is REJECTED", () => cron("0 25 * * *"), "out of range")
  await throws("a Quartz extension is REJECTED rather than half-supported", () => cron("0 0 L * *"), "bad number")
}

// ---------------------------------------------------------------------------
// (B) Does it drive the shipped runtime with zero library change?
// ---------------------------------------------------------------------------
const mkFetch = (reply) => async () =>
  new Response(
    JSON.stringify({ choices: [{ message: { role: "assistant", content: typeof reply === "function" ? await reply() : reply } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
    { status: 200, headers: { "content-type": "application/json" } },
  )

section("B: a cron schedule drives the SHIPPED verbs, on a virtual clock")
{
  const clock = virtualClock(Date.UTC(2026, 8, 14, 8, 59, 0))
  const rt = new AgentRuntime({
    fetch: mkFetch("reported"),
    clock,
    registry: { reporter: { name: "reporter", does: "reports", model: "m" } },
  })
  const h = rt.spawn(rt.root, "reporter")
  check("handle spawned", !isVerbError(h))

  const beats = []
  const sched = startScheduled(rt, h, cron("0 9 * * *"), { misfire: "skip", onFire: (r) => beats.push(r.text) })

  await clock.advance(2 * 60 * 1000) // cross 09:00
  check("fired once at 09:00", sched.fired.length === 1)
  await clock.advance(23 * 60 * 60 * 1000) // next day 08:01 — no fire yet
  check("no spurious fire before the next 09:00", sched.fired.length === 1)
  await clock.advance(60 * 60 * 1000) // cross 09:00 again
  check("fired again the next day", sched.fired.length === 2)
  note(`fire instants: ${sched.fired.map((f) => new Date(f.due).toISOString()).join(", ")}`)
  check("the agent actually ran on each fire", beats.length === 2, `beats=${JSON.stringify(beats)}`)
  sched.stop()
  await clock.advance(48 * 60 * 60 * 1000)
  check("stop() is final — no fires after it", sched.fired.length === 2)
}

// ---------------------------------------------------------------------------
// (C) Overlap: the property the heartbeat already gets right
// ---------------------------------------------------------------------------
section("C: a run SLOWER than the interval does not stack (wake-only-when-idle)")
{
  const clock = virtualClock(0)
  let release
  const gate = new Promise((r) => (release = r))
  let calls = 0
  const rt = new AgentRuntime({
    fetch: async () => {
      calls++
      await gate // first turn hangs until we let it go
      return new Response(
        JSON.stringify({ choices: [{ message: { role: "assistant", content: "slow" } }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
        { status: 200, headers: { "content-type": "application/json" } },
      )
    },
    clock,
    registry: { slow: { name: "slow", does: "slow", model: "m" } },
  })
  const h = rt.spawn(rt.root, "slow")
  const sched = startScheduled(rt, h, every(1000), { misfire: "skip" })

  await clock.advance(5000) // five ticks while the first turn is still in flight
  check("five scheduled instants elapsed", sched.fired.length === 5)
  check("but only ONE turn was ever started", calls === 1, `llm calls=${calls}`)
  check("handle is running, not queued five deep", h.state === "running")
  release()
  await new Promise((r) => setTimeout(r, 20))
  note("the missed ticks sat in the inbox and drain as ONE coalesced turn (§7D unsolicited rail)")
  sched.stop()
}

// ---------------------------------------------------------------------------
// (D) Misfire: required, named, and bounded
// ---------------------------------------------------------------------------
section("D: misfire policy is REQUIRED and observable")
{
  await throws("omitting misfire is a loud error, never a silent default", () => {
    const rt = new AgentRuntime({ fetch: mkFetch("x"), clock: virtualClock(0), registry: { a: { name: "a", does: "a", model: "m" } } })
    startScheduled(rt, rt.spawn(rt.root, "a"), every(1000), {})
  }, "misfire policy is required")

  // A process asleep for an hour, waking to an every-minute schedule.
  for (const policy of ["skip", "catchUp"]) {
    const clock = virtualClock(0)
    const rt = new AgentRuntime({ fetch: mkFetch("x"), clock, registry: { a: { name: "a", does: "a", model: "m" } } })
    const h = rt.spawn(rt.root, "a")
    const sched = startScheduled(rt, h, every(60_000), { misfire: policy })
    // Simulate a sleeping process: jump the clock without firing intermediate timers.
    await clock.advance(60_000) // one honest fire
    const before = sched.fired.length
    clock.now = ((t) => () => t)(clock.now() + 3600_000) // 60 minutes vanish
    await clock.advance(60_000)
    const last = sched.fired[sched.fired.length - 1]
    if (policy === "skip") {
      check("skip: a 60-minute outage replays NOTHING", last.coalescedMissed === 0, `fires=${sched.fired.length - before}`)
    } else {
      check("catchUp: the outage is counted, not lost", last.coalescedMissed > 0, `missed=${last.coalescedMissed}`)
      check("catchUp still coalesces — bounded by the inbox, not unbounded replay", last.coalescedMissed <= 61)
    }
    sched.stop()
  }
}

section("E: what this spike does NOT establish")
{
  note("• DST/timezone: the subset is UTC-only here. A `tz` field needs its own fixture")
  note("  table (spring-forward 02:30 must be pinned: skip, not double-fire).")
  note("• DURABILITY: this scheduler lives in-process and dies with it. `last` is a")
  note("  local variable. Surviving restart means persisting last-fire — which lands on")
  note("  ConversationStore and therefore behind ADR 0015's atomic-per-id pin.")
  note("• Seven-port parity is argued from the fixture table, not measured — only the")
  note("  JS port exists to run against today.")
}

report("0012 schedule seam + cron subset")
