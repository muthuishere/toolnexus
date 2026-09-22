// O1 / js — does moving from spawn(cmd,{shell:true}) to an explicit argv prefix
// preserve today's behaviour?
//
// ARM_TODAY   spawn(command, { shell: true })        <- js/src/builtin.ts:163
// ARM_ARGV    spawn("sh", ["-c", command])           <- the candidate
// ARM_CONTROL spawn("echo", [command])               <- MUST differ on every shape.
//
// The control arm exists so a row of SAME means the comparator can tell things
// apart. If ARM_CONTROL ever reports SAME the probe is broken, not the fix.
import { spawn } from "node:child_process"

const shapes = {
  quotes: `echo "a  b" 'c$NOPE'`,
  pipe: `printf 'x\\ny\\n' | grep y`,
  redirect: `echo hi > /dev/stderr`,
  andand: `true && echo second`,
  multiline: "echo one\necho two\nif true; then echo three; fi",
  nonzero: `echo before; exit 7`,
  varexp: `V=1; echo $V$HOME_NOPE`,
}

function run(spec) {
  return new Promise((resolve) => {
    const child = spawn(spec.file, spec.args, spec.opts)
    let out = ""
    child.stdout?.on("data", (d) => (out += d.toString()))
    child.stderr?.on("data", (d) => (out += d.toString()))
    child.on("error", (e) => resolve({ out: `ERR:${e.message}`, code: null }))
    child.on("close", (code) => resolve({ out, code }))
  })
}

const arms = (command) => ({
  ARM_TODAY: { file: command, args: undefined, opts: { shell: true } },
  ARM_ARGV: { file: "sh", args: ["-c", command], opts: {} },
  ARM_CONTROL: { file: "echo", args: [command], opts: {} },
})

const j = (r) => JSON.stringify({ out: r.out, code: r.code })

console.log("== O1 js: shell:true vs explicit argv ==")
let sameCount = 0
let controlSame = 0
for (const [name, command] of Object.entries(shapes)) {
  const a = arms(command)
  const today = await run(a.ARM_TODAY)
  const argv = await run(a.ARM_ARGV)
  const control = await run(a.ARM_CONTROL)
  const eq = j(today) === j(argv)
  const ceq = j(today) === j(control)
  if (eq) sameCount++
  if (ceq) controlSame++
  console.log(
    `${name.padEnd(10)} today_vs_argv=${eq ? "SAME" : "DIFFER"}  control_vs_today=${ceq ? "SAME(PROBE BROKEN)" : "DIFFER(ok)"}`,
  )
  if (!eq) console.log(`  today=${j(today)}\n  argv =${j(argv)}`)
}
console.log(`SUMMARY shapes=${Object.keys(shapes).length} same=${sameCount} control_false_positives=${controlSame}`)

// Second measurement: is shell:true's interpreter actually /bin/sh on this host?
// NOTE the leading `:;` — without a second command the shell execs `ps` and
// replaces itself, so `comm` reports `ps` and the measurement is meaningless.
const probeCmd = ":; ps -o comm= -p $$"
const who = await run({ file: probeCmd, args: undefined, opts: { shell: true } })
console.log(`SHELL_TRUE_INTERPRETER=${JSON.stringify(who.out.trim())}`)
const argvWho = await run({ file: "sh", args: ["-c", probeCmd], opts: {} })
console.log(`ARGV_INTERPRETER=${JSON.stringify(argvWho.out.trim())}`)
