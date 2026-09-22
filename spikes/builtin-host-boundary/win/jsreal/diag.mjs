// Why did taskkill report failure? Spawn the same shape the port does, then try
// each kill and print what Windows actually said.
import { spawn, spawnSync } from "node:child_process"
const child = spawn(process.env.COMSPEC, ["/d", "/s", "/c", "child.cmd " + process.argv[2]], {})
console.log("child.pid=" + child.pid)
child.on("close", (c) => console.log("child closed code=" + c))
await new Promise((r) => setTimeout(r, 1500))
for (const args of [["/T", "/PID", String(child.pid)], ["/T", "/F", "/PID", String(child.pid)]]) {
  const r = spawnSync("taskkill", args, { encoding: "utf8" })
  console.log(`taskkill ${args.join(" ")} -> status=${r.status} err=${JSON.stringify(r.error?.message)} out=${JSON.stringify((r.stdout||"") + (r.stderr||""))}`)
}
await new Promise((r) => setTimeout(r, 9000))
import fs from "node:fs"
console.log("ORPHAN=" + (fs.existsSync(process.argv[2]) ? "SURVIVED" : "none"))
