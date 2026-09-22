// S1 probe (node). naive = what js/src/builtin.ts does today (spawn shell:true + child.kill).
// group = detached:true + process.kill(-pid).
import { spawn } from "node:child_process"
const [mode, marker] = process.argv.slice(2)
const command = `sleep 0.2; sh -c 'sleep 1; touch ${marker}'`
const child = spawn(command, { shell: true, detached: mode === "group" })
setTimeout(() => {
  if (mode === "naive") child.kill("SIGKILL")
  else {
    try { process.kill(-child.pid, "SIGTERM") } catch {}
    setTimeout(() => { try { process.kill(-child.pid, "SIGKILL") } catch {} }, 200)
  }
}, 300)
child.on("close", () => { console.log("killed"); process.exit(0) })
