// Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
//
// GitHub supports neither DCR nor CIMD, so you register an OAuth App once
// (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
// enable "expiring user tokens" if you want refresh) and pass its client id + secret.
//
//   sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- node examples/10-github/main.ts
//
// Credentials are read from the environment and never printed; neither is any token.
// BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
// otherwise the system browser opens.
import { execFile } from "node:child_process"
import { promisify } from "node:util"
import * as mcp from "../../src/index.ts"

const id = process.env.GITHUB_MCP_CLIENT_ID ?? ""
const secret = process.env.GITHUB_MCP_CLIENT_SECRET ?? ""
if (!id || !secret) {
  console.log("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...")
  process.exit(0)
}

const login = new mcp.LoopbackAuthorizer() // system browser + http://127.0.0.1:<random>/callback
if (process.env.BROWSER === "chrome-agent") login.open = (u) => promisify(execFile)("chrome-agent", ["goto", u])

const c = new mcp.Client("https://api.githubcopilot.com/mcp/", {
  auth: new mcp.OAuth({
    clientId: id,
    clientSecret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
    authorizer: login,
  }),
})

const tools = await c.tools()
console.log("tools listed:", tools.length > 0)

const res = await c.callTool("get_me", {}) // read-only: who am I
console.log("get_me:", res.resultType, "| isError:", res.isError === true) // content withheld on purpose
