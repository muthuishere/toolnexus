// Stress harness for the builtin host boundary (ADR 0034) — js port.
// Same five scenarios as the go harness, against the SHIPPED dist builtins.
// Every scenario carries a CONTROL: an assertion that passes because the
// harness never ran measures nothing, which has already happened twice here.
//
//   node spikes/builtin-host-boundary/stress/js/stress.mjs
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { execFileSync } from "node:child_process"
import { createBuiltinTools } from "../../../../js/dist/index.js"

const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-stress-js-"))
const tool = (cfg, name) => createBuiltinTools(cfg).find((t) => t.name === name)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const verdict = (ok) => (ok ? "PASS" : "FAIL")

// The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
// exec-optimising the single command away.
const orphanCommand = (m) => `sleep 0.2; sh -c 'sleep 5; touch ${m}'`
const markers = (d) => (fs.existsSync(d) ? fs.readdirSync(d).filter((f) => f.endsWith(".marker")).length : 0)
const mkdir = (d) => (fs.mkdirSync(d, { recursive: true }), d)

let allPass = true
const report = (line, ok) => {
  allPass = allPass && ok
  console.log(`${line} ${verdict(ok)}`)
}

// --------------------------------------------------------------------------- S1
{
  const bash = tool(undefined, "bash")
  const n = 30
  const md = mkdir(path.join(dir, "s1"))
  const results = await Promise.all(
    Array.from({ length: n }, (_, i) =>
      bash.execute({ command: orphanCommand(path.join(md, `t${i}.marker`)), timeout: 300 }, undefined),
    ),
  )
  await sleep(7000)
  const orphans = markers(md)
  const timedOut = results.filter((r) => r.metadata?.timedOut === true).length
  const killedTree = results.filter((r) => r.metadata?.killedTree === true).length

  const cd = mkdir(path.join(dir, "s1c"))
  await Promise.all(
    Array.from({ length: 3 }, (_, i) =>
      bash.execute({ command: orphanCommand(path.join(cd, `c${i}.marker`)), timeout: 20_000 }, undefined),
    ),
  )
  const control = markers(cd)
  report(
    `S1 concurrent_timeouts n=${n} orphans=${orphans} timedOut=${timedOut} killedTree=${killedTree} control_markers=${control}/3`,
    orphans === 0 && timedOut === n && killedTree === n && control === 3,
  )
}

// --------------------------------------------------------------------------- S2
{
  const bash = tool(undefined, "bash")
  const pairs = 20
  const md = mkdir(path.join(dir, "s2"))
  const jobs = []
  for (let i = 0; i < pairs; i++) {
    jobs.push(
      bash.execute({ command: `echo ok-${i}` }, undefined).then((r) => ({ kind: "ok", i, r })),
      bash
        .execute({ command: orphanCommand(path.join(md, `m${i}.marker`)), timeout: 300 }, undefined)
        .then((r) => ({ kind: "timeout", i, r })),
    )
  }
  const out = await Promise.all(jobs)
  await sleep(7000)
  // Each success must carry ITS OWN output, not a concurrent call's.
  const okCorrect = out.filter((o) => o.kind === "ok" && !o.r.isError && o.r.output.trim() === `ok-${o.i}`).length
  const okWrong = out.filter((o) => o.kind === "ok").length - okCorrect
  const toCorrect = out.filter((o) => o.kind === "timeout" && o.r.isError && o.r.metadata?.timedOut === true).length
  const toWrong = out.filter((o) => o.kind === "timeout").length - toCorrect
  report(
    `S2 mixed_load ok_correct=${okCorrect}/${pairs} ok_wrong=${okWrong} timeout_correct=${toCorrect}/${pairs} timeout_wrong=${toWrong} orphans=${markers(md)}`,
    okCorrect === pairs && okWrong === 0 && toCorrect === pairs && toWrong === 0 && markers(md) === 0,
  )
}

// --------------------------------------------------------------------------- S3
{
  const bash = tool(undefined, "bash")
  // 5 MB on stdout then a sleep past the timeout — the shape that deadlocks
  // when a killed child's pipes are held open by a grandchild.
  const t0 = Date.now()
  const res = await bash.execute(
    { command: `head -c 5000000 /dev/zero | tr '\\0' 'x'; sleep 10`, timeout: 1000 },
    undefined,
  )
  const elapsed = Date.now() - t0
  const control = await bash.execute(
    { command: `head -c 5000000 /dev/zero | tr '\\0' 'x'`, timeout: 30_000 },
    undefined,
  )
  report(
    `S3 big_output_timeout elapsed_ms=${elapsed} is_error=${res.isError} bytes_on_timeout=${res.output.length} control_ok=${!control.isError} control_bytes=${control.output.length}`,
    res.isError && elapsed < 6000 && !control.isError && control.output.length >= 5_000_000,
  )
}

// --------------------------------------------------------------------------- S4
{
  const bash = tool(undefined, "bash")
  const childCount = () => {
    try {
      const out = execFileSync("ps", ["-eo", "pid=,ppid="], { encoding: "utf8" })
      return out.split("\n").filter((l) => Number(l.trim().split(/\s+/)[1]) === process.pid).length
    } catch {
      return -1
    }
  }
  const fdCount = () => {
    try {
      return execFileSync("lsof", ["-p", String(process.pid)], { encoding: "utf8" }).split("\n").length
    } catch {
      return -1
    }
  }
  const rounds = []
  for (let r = 0; r < 3; r++) {
    await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        bash.execute({ command: orphanCommand(path.join(dir, `s4-${r}-${i}.marker`)), timeout: 300 }, undefined),
      ),
    )
    await sleep(500)
    rounds.push({ children: childCount(), fds: fdCount(), handles: process._getActiveHandles?.().length ?? -1 })
  }
  // Monotonic growth across identical rounds is the leak signal; one round's
  // absolute numbers say nothing on their own.
  const ok =
    rounds[2].fds <= rounds[0].fds + 8 &&
    rounds[2].children <= rounds[0].children + 2 &&
    rounds[2].handles <= rounds[0].handles + 8
  report(
    `S4 leaks children=${rounds.map((x) => x.children).join("/")} fds=${rounds.map((x) => x.fds).join("/")} handles=${rounds.map((x) => x.handles).join("/")}`,
    ok,
  )
}

// --------------------------------------------------------------------------- S5
{
  const base = mkdir(path.join(dir, "confine"))
  const outside = mkdir(path.join(dir, "outside"))
  mkdir(path.join(base, "sub"))
  fs.writeFileSync(path.join(outside, "secret.txt"), "secret")
  try { fs.symlinkSync(outside, path.join(base, "link")) } catch {}
  for (const f of ["a.txt", "sub/b.txt", "c.txt"]) fs.writeFileSync(path.join(base, f), "x")

  const write = tool({ baseDir: base, confineToBaseDir: true }, "write")
  const legal = [
    "a.txt",
    "sub/b.txt",
    "./c.txt",
    "sub/new-file.txt",
    // `....` is a LITERAL directory name, not a parent reference — the lookalike
    // that catches a checker doing string surgery on dots. Allowing it is right.
    "....//x",
  ]
  const escapes = [
    "../x",
    "sub/../../x",
    path.join(outside, "secret.txt"),
    "link/secret.txt",
    "sub/./../../outside/secret.txt",
    "../../../../../../../../tmp/tn-stress-escaped-js.txt",
  ]

  const attempts = []
  for (let round = 0; round < 50; round++) {
    for (const p of legal) attempts.push(write.execute({ path: p, content: "x" }, undefined).then((r) => ({ p, r, legal: true })))
    for (const p of escapes) attempts.push(write.execute({ path: p, content: "pwned" }, undefined).then((r) => ({ p, r, legal: false })))
  }
  const out = await Promise.all(attempts)
  const allowedEscapes = out.filter((o) => !o.legal && !o.r.isError)
  const refusedLegals = out.filter((o) => o.legal && o.r.isError)

  // CONTROL: the same escapes with confinement OFF must all be allowed —
  // otherwise "zero escapes" could mean the writes were failing anyway.
  const unconfined = tool({ baseDir: base }, "write")
  let controlAllowed = 0
  for (const p of escapes) {
    const r = await unconfined.execute({ path: p, content: "x" }, undefined)
    if (!r.isError) controlAllowed++
  }
  for (const p of [...new Set(allowedEscapes.map((o) => o.p))]) console.log(`   ALLOWED ${JSON.stringify(p)}`)
  for (const p of [...new Set(refusedLegals.map((o) => o.p))]) console.log(`   REFUSED_LEGAL ${JSON.stringify(p)} -> ${JSON.stringify(refusedLegals.find((o) => o.p === p).r.output)}`)
  report(
    `S5 confinement_under_load attempts=${out.length} allowed_escapes=${allowedEscapes.length} refused_legals=${refusedLegals.length} control_allowed_without_confine=${controlAllowed}/${escapes.length}`,
    allowedEscapes.length === 0 && refusedLegals.length === 0 && controlAllowed === escapes.length,
  )
}

fs.rmSync(dir, { recursive: true, force: true })
fs.rmSync("/tmp/tn-stress-escaped-js.txt", { force: true })
console.log(`== js stress ${verdict(allPass)}`)
process.exit(allPass ? 0 : 1)
