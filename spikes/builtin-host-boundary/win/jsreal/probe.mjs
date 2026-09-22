// W5 — the REAL js port's builtins on native Windows. The four shipped dist
// files are copied next to this script (builtin.js + its three local imports,
// none of which pull a third-party dependency), so nothing needs npm on target.
//
// Same four questions as the Go probe: does `bash` run, what does a relative
// path resolve against, does a timeout kill the grandchild, and do the listings
// emit `/`.
import fs from "node:fs"
import path from "node:path"
import { createBuiltinTools, builtinShell } from "./builtin.js"

const scratch = process.argv[2] ?? "."
const call = async (tools, name, args) => {
  const tool = tools.find((t) => t.name === name)
  if (!tool) return { output: "<tool absent>", isError: true }
  return tool.execute(args, undefined)
}

console.log(`platform=${process.platform} HOST_CWD=${process.cwd()}`)
try {
  console.log(`SHELL_DETECTED=${JSON.stringify(builtinShell())}`)
} catch (e) {
  console.log(`SHELL_DETECT_ERR=${String(e)}`)
}

const tools = createBuiltinTools({ baseDir: scratch })

// 1 — bash on native Windows
let res = await call(tools, "bash", { command: "echo hello-from-bash-builtin" })
console.log(`BASH_ISERROR=${res.isError} BASH_OUTPUT=${JSON.stringify(res.output)} SHELL=${JSON.stringify(res.metadata?.shell)}`)

// 2 — relative path resolution
res = await call(tools, "write", { path: "relative-probe.txt", content: "landed" })
console.log(`WRITE_ISERROR=${res.isError} WRITE_OUTPUT=${JSON.stringify(res.output)}`)
const inBase = path.join(scratch, "relative-probe.txt")
const inCwd = path.join(process.cwd(), "relative-probe.txt")
console.log(
  `RELATIVE_LANDED_IN=${fs.existsSync(inBase) ? `baseDir (${inBase})` : fs.existsSync(inCwd) ? `host_cwd (${inCwd})` : "nowhere"}`,
)

// 2b — confinement, including the Windows-only device-name case
const confined = createBuiltinTools({ baseDir: scratch, confineToBaseDir: true })
res = await call(confined, "read", { path: "..\\..\\escape.txt" })
console.log(`CONFINE_DOTDOT_ISERROR=${res.isError} OUTPUT=${JSON.stringify(res.output)}`)
res = await call(confined, "write", { path: "CON", content: "x" })
console.log(`CONFINE_DEVICE_ISERROR=${res.isError} OUTPUT=${JSON.stringify(res.output)}`)

// 3 — a timeout must not leave the grandchild running
const marker = path.join(scratch, "js-orphan.marker")
try { fs.rmSync(marker) } catch {}
res = await call(tools, "bash", { command: `child.cmd ${marker}`, timeout: 700 })
console.log(`TIMEOUT_ISERROR=${res.isError} TIMEDOUT=${res.metadata?.timedOut} KILLEDTREE=${res.metadata?.killedTree}`)
await new Promise((r) => setTimeout(r, 9000))
console.log(`ORPHAN=${fs.existsSync(marker) ? "SURVIVED" : "none"}`)

// 4 — separators in a listing, on a Windows filesystem
fs.mkdirSync(path.join(scratch, "tree", "sub"), { recursive: true })
fs.writeFileSync(path.join(scratch, "tree", "sub", "a.txt"), "x\n")
res = await call(tools, "glob", { pattern: "**/*.txt", path: "tree" })
console.log(`GLOB_ISERROR=${res.isError} GLOB_OUTPUT=${JSON.stringify(res.output)}`)
res = await call(tools, "grep", { pattern: "x", path: "tree" })
console.log(`GREP_ISERROR=${res.isError} GREP_OUTPUT=${JSON.stringify(res.output)}`)
