// Runs every program under examples/ end to end and checks its exact output, so the docs
// (which copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only);
// 09 needs KEYCLOAK=1 + the keycloak compose stack, 10 needs GITHUB_OAUTH=1 + sec-injected creds.
// The expected outputs are byte-identical to the Go spike's examples_test.go.
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { fileURLToPath } from "node:url"

const root = fileURLToPath(new URL("..", import.meta.url))

export const hermeticExamples: Record<string, string> = {
  "01-connect-http": `supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP`,
  "02-connect-stdio": `tool count: 2
echo: hello over stdio`,
  "03-tool-input": `server asks (elicitation/create): What is your name?
result: Hello, Ada!`,
  "04-skills": `skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh`,
  "05-tool-search": `eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team`,
  "06-oauth-zero-config": `echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true`,
  "07-oauth-web-pause-resume": `(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand`,
  "08-serve-protected": `no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
verify hook: hello billing-service
wrong key rejected: true`,
}

export function runExample(name: string, timeoutMs: number, env: Record<string, string> = {}): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(process.execPath, [`examples/${name}/main.ts`], { cwd: root, timeout: timeoutMs, env: { ...process.env, ...env } }, (err, stdout, stderr) => {
      if (err) reject(new Error(`run ${name}: ${err.message}\nstdout:\n${stdout}\nstderr:\n${stderr}`))
      else resolve(stdout.trim())
    })
  })
}

for (const [name, want] of Object.entries(hermeticExamples)) {
  test(`example ${name}`, async () => assert.equal(await runExample(name, 60_000), want))
}

test("example 09-keycloak", { skip: process.env.KEYCLOAK !== "1" && "set KEYCLOAK=1 with the keycloak compose stack running" }, async (t) => {
  const got = await runExample("09-keycloak", 90_000, { HEADLESS_LOGIN: "1" })
  t.diagnostic(got)
  assert.ok(got.includes("whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true") && got.includes("greet: Hello, Alice!"), got)
})

test("example 10-github", { skip: process.env.GITHUB_OAUTH !== "1" && "set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)" }, async () => {
  const got = await runExample("10-github", 180_000, { BROWSER: "chrome-agent" })
  assert.equal(got, "tools listed: true\nget_me: complete | isError: false")
})
