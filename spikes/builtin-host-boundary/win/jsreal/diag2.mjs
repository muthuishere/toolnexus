// Replicate the port's exact timing (kill at 700 ms) and print what Windows says.
import { spawn, spawnSync } from "node:child_process"
import fs from "node:fs"
const marker = process.argv[2]
const child = spawn(process.env.COMSPEC, ["/d", "/s", "/c", "child.cmd " + marker], {})
console.log("child.pid=" + child.pid)
child.on("close", (c) => console.log("close code=" + c + " at " + Date.now() % 100000))
await new Promise((r) => setTimeout(r, 700))
let r = spawnSync("taskkill", ["/T", "/F", String(child.pid)], { encoding: "utf8" })
console.log(`at700 taskkill /T /F -> status=${r.status} out=${JSON.stringify((r.stdout||"")+(r.stderr||""))}`)
await new Promise((r2) => setTimeout(r2, 9000))
console.log("ORPHAN=" + (fs.existsSync(marker) ? "SURVIVED" : "none"))
