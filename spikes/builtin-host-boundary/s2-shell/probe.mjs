// S2 probe (node) — what does `spawn(command, {shell:true})` actually run?
// That is what js/src/builtin.ts:163 does today, so this is the shipped
// behaviour, not a reimplementation. On Windows `shell:true` means
// `%COMSPEC% /d /s /c`, i.e. cmd.exe — a different language from `sh`.
import { spawn } from "node:child_process"

const probes = [
  ["posix_var", "echo $HOME"],
  ["bashism", "[[ -d . ]] && echo bashism-ok"],
  ["cmd_var", "echo %USERPROFILE%"],
  ["which_self", "echo $0"],
]

function run(cmd) {
  return new Promise((resolve) => {
    const child = spawn(cmd, { shell: true })
    let out = ""
    child.stdout.on("data", (d) => (out += d))
    child.stderr.on("data", (d) => (out += d))
    child.on("close", (code) => resolve({ code, out: out.trim() }))
    child.on("error", (e) => resolve({ code: -1, out: String(e) }))
  })
}

console.log(`platform=${process.platform} COMSPEC=${process.env.COMSPEC ?? "<unset>"}`)
for (const [name, cmd] of probes) {
  const { code, out } = await run(cmd)
  console.log(`${name}: exit=${code} out=${JSON.stringify(out)}`)
}
