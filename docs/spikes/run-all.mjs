// Run every hermetic spike and exit non-zero if any of them fails.
//
// Spikes are cited as evidence by the ADRs, so they have to be re-runnable by
// somebody other than their author — and they have to be able to fail. Before the
// ADR 0020 pass they could do neither: every file hardcoded an absolute home
// directory, and none had an assertion, so `node <spike>` printed `false` on a real
// defect and still exited 0.
//
//   node docs/spikes/run-all.mjs          # hermetic spikes only
//   node docs/spikes/run-all.mjs --live   # also the ones needing a live model + key
//
// Requires the JS port to be built first:  cd js && npm install && npm run build
import { spawn } from "node:child_process"
import { readdir } from "node:fs/promises"

const HERE = new URL(".", import.meta.url)
const LIVE = new Set(["0011-live-harness-scenarios.mjs"]) // needs a real key + network
const wantLive = process.argv.includes("--live")

const files = (await readdir(HERE))
  .filter((f) => f.endsWith(".mjs") && !f.startsWith("_") && f !== "run-all.mjs")
  .sort()

const run = (file) =>
  new Promise((resolve) => {
    const t0 = Date.now()
    const child = spawn(process.execPath, [new URL(file, HERE).pathname], { stdio: ["ignore", "pipe", "pipe"] })
    let out = ""
    child.stdout.on("data", (d) => (out += d))
    child.stderr.on("data", (d) => (out += d))
    child.on("close", (code) => resolve({ file, code, ms: Date.now() - t0, out }))
  })

const results = []
for (const file of files) {
  if (LIVE.has(file) && !wantLive) {
    console.log(`SKIP  ${file}  (live model; pass --live to include)`)
    continue
  }
  const r = await run(file)
  results.push(r)
  const verdict = r.code === 0 ? "PASS" : "FAIL"
  console.log(`${verdict}  ${file}  (${r.ms}ms)`)
  if (r.code !== 0) {
    console.log("      ---- output ----")
    for (const line of r.out.trimEnd().split("\n")) console.log(`      ${line}`)
  }
}

const failed = results.filter((r) => r.code !== 0)
console.log("")
console.log(`${failed.length === 0 ? "ALL SPIKES PASS" : "SPIKE FAILURES"} — ${results.length - failed.length}/${results.length} passed`)
for (const f of failed) console.log(`  ✗ ${f.file}`)
process.exitCode = failed.length === 0 ? 0 : 1
