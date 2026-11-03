// run.sh entry: server | client <url> | selftest
import { fixtureServer } from "./server.ts"
import { runChecks } from "./checks.ts"

const [cmd, arg] = process.argv.slice(2)
switch (cmd) {
  case "server": {
    const port = Number(process.env.PORT || 7101)
    const { url } = await fixtureServer().listen(port)
    console.log(`listening on ${url}`)
    break
  }
  case "client":
    if (!(await runChecks(arg || "http://127.0.0.1:7101/mcp"))) process.exitCode = 1
    break
  case "selftest": {
    const srv = await fixtureServer().listen(0)
    if (!(await runChecks(srv.url))) process.exitCode = 1
    await srv.close()
    break
  }
  default:
    console.log("usage: run.sh server | client <url> | selftest")
    process.exitCode = 2
}
