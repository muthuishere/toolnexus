// O4 / js — does the kill reach the grandchild, and does it still reach it when
// the surrounding `await` is aborted rather than timing out?
//
// Command shape is the one from spikes/.../s1-orphan/probe.mjs and it matters:
//   sleep 0.2; sh -c 'sleep 1; touch MARKER'
// Without the leading `sleep 0.2` the shell execs the single command, there is
// no grandchild, and a naive kill looks correct.
//
// Marker present 2 s later ⇒ the grandchild outlived the kill.
//
// ARMS
//   control   no kill at all            MUST be ORPHAN_SURVIVED (else the probe
//                                       cannot write the marker and every other
//                                       arm's "killed" is meaningless)
//   naive     child.kill("SIGKILL")     what js/src/builtin.ts:163 ships today
//   group     detached + kill(-pid)     the candidate, timeout path
//   abort-naive  ctx.signal aborts, child.kill()      today's shape + an abort
//   abort-group  ctx.signal aborts, kill(-pid)        the candidate, abort path
import { spawn } from "node:child_process"
import fs from "node:fs"
import path from "node:path"
import os from "node:os"

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-o4-"))
const TIMEOUT = 300
const GRACE = 200

/** Mirrors the shape of bashTool(): a Promise that resolves on close, with a
 *  ctx.signal an outer caller can abort. */
function bashLike(mode, marker, signal) {
  return new Promise((resolve) => {
    const command = `sleep 0.2; sh -c 'sleep 1; touch ${marker}'`
    const detached = mode.endsWith("group")
    const child = spawn(command, { shell: true, detached })
    const killTree = () => {
      try {
        process.kill(-child.pid, "SIGTERM")
      } catch {}
      setTimeout(() => {
        try {
          process.kill(-child.pid, "SIGKILL")
        } catch {}
      }, GRACE)
    }
    const kill = detached ? killTree : () => child.kill("SIGKILL")
    let timer = null
    if (!mode.startsWith("abort") && mode !== "control") timer = setTimeout(kill, TIMEOUT)
    if (signal) signal.addEventListener("abort", kill, { once: true })
    child.on("close", () => {
      if (timer) clearTimeout(timer)
      resolve("closed")
    })
    child.on("error", () => resolve("error"))
  })
}

async function arm(mode) {
  const marker = path.join(dir, `${mode}.marker`)
  const ac = new AbortController()
  const p = bashLike(mode, marker, mode.startsWith("abort") ? ac.signal : undefined)
  let outcome = "n/a"
  if (mode.startsWith("abort")) {
    // The caller aborts mid-await — an AbortSignal does NOT reject the promise
    // by itself, so measure whether the await ever returns and what the child did.
    setTimeout(() => ac.abort(), TIMEOUT)
    outcome = await Promise.race([p, new Promise((r) => setTimeout(() => r("await-still-pending-at-2s"), 2000))])
  } else if (mode === "control") {
    await new Promise((r) => setTimeout(r, 100))
    outcome = "not-awaited"
  } else {
    outcome = await p
  }
  await new Promise((r) => setTimeout(r, 2000))
  const survived = fs.existsSync(marker)
  console.log(
    `${mode.padEnd(12)} ${survived ? "ORPHAN_SURVIVED " : "killed_whole_job"}  await=${outcome}`,
  )
  return survived
}

console.log(`== O4 js (node ${process.version}, ${os.platform()}) ==`)
const control = await arm("control")
if (!control) console.log("!! CONTROL DID NOT SURVIVE — the probe cannot write its marker; every row below is meaningless")
await arm("naive")
await arm("group")
await arm("abort-naive")
await arm("abort-group")

// A second control: prove `process.kill(-pid)` needs `detached`. Without it the
// child shares OUR process group and a group kill would target our own group.
const child = spawn("sleep 5", { shell: true })
await new Promise((r) => setTimeout(r, 100))
let pgidNote
try {
  // getpgid of a non-detached child == our own pgid
  const same = process.pid !== child.pid
  pgidNote = `non_detached_child_pid=${child.pid} our_pid=${process.pid} distinct=${same}`
} finally {
  child.kill("SIGKILL")
}
console.log(`NOTE ${pgidNote} — kill(-pid) on a NON-detached child signals a pgid we do not own (EPERM/ESRCH) or, worse, is our own group`)
