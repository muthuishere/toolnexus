// Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).
//
// Run: node examples/07-oauth-web-pause-resume/main.ts   (hermetic: everything runs on 127.0.0.1)
//
// A server-side app must never block a request waiting for a login. With PauseAuthorizer the
// call throws a PendingError instead ({id, kind:"authorization", prompt, url, expiresAt},
// the shape of toolnexus SPEC §10 Request). The app sends the browser to pending.url; the
// authorization server redirects back; the app resumes and gets the ORIGINAL call's result.
//
// webHandler packages that whole dance as one node:http listener. The manual version is shown too.
import http from "node:http"
import * as mcp from "../../src/index.ts"

// ---- demo harness: dev authorization server + protected MCP server ----
const as = await mcp.DevAuthServer.start() // DEV/TEST ONLY
as.clients["my-web-app"] = ["http://127.0.0.1/tool", "http://127.0.0.1/callback"]
const srv = mcp.fixtureServer()
srv.auth = new mcp.ServerAuth({ issuer: as.issuer })
const mcpTS = await srv.listen()
// ---- end harness ----

const appServer = http.createServer()
await new Promise<void>((r) => appServer.listen(0, "127.0.0.1", r))
const app = `http://127.0.0.1:${(appServer.address() as { port: number }).port}`

// ---- the web app ----
const c = new mcp.Client(mcpTS.url, {
  auth: new mcp.OAuth({ clientId: "my-web-app", authorizer: new mcp.PauseAuthorizer({ redirect: app + "/tool" }) }),
})

// (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
// with ?code&state, webHandler resumes, and the tool's text is written to the response.
appServer.on("request", c.auth!.webHandler((req) => {
  const text = new URL(req.url!, app).searchParams.get("text") ?? ""
  return c.callTool("echo", { text })
}))
// ---- end web app ----

// A person's browser opens /tool?text=... (mcp.browse follows redirects; the dev TLS is trusted in-process).
const page = await mcp.browse(app + "/tool?text=resumed+after+consent")
console.log("(a) WebHandler:", page.status, page.body)

// (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
const c2 = new mcp.Client(mcpTS.url, {
  auth: new mcp.OAuth({ clientId: "my-web-app", authorizer: new mcp.PauseAuthorizer({ redirect: "http://127.0.0.1/callback" }) }),
})
let p: mcp.Pending | undefined
try {
  await c2.callTool("echo", { text: "resumed by hand" })
} catch (err) {
  p = mcp.pendingOf(err) // undefined when the call failed for another reason
  if (!p) throw err
}
if (!p) throw new Error("expected a pending authorization")
console.log("(b) paused:", p.kind, "-", p.prompt !== "", "- has URL:", p.url !== "")

const callbackUrl = await as.visit(p.url) // the user logs in; you receive the callback URL
const res = await c2.auth!.resume(p.id, callbackUrl)
console.log("(b) resumed:", mcp.text(res))

await mcp.closeServer(appServer)
await mcpTS.close()
await as.close()
