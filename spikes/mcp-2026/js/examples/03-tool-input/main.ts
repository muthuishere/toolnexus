// Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).
//
// Run: node examples/03-tool-input/main.ts
//
// In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can
// RETURN resultType "input_required" with a set of inputRequests (e.g. an elicitation form).
// The client answers them and re-sends the same call with inputResponses (+ the opaque
// requestState). callTool runs those rounds for you: you only supply a Responder callback.
import * as mcp from "../../src/index.ts"

const ts = await mcp.fixtureServer().listen() // `greet` asks for the user's name
const c = new mcp.Client(ts.url)

// The Responder is called once per input request. key identifies the request inside the
// round; req is the request itself ({ method: "elicitation/create", params: {...} }).
// In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
const askUser: mcp.Responder = async (key, req) => {
  console.log(`server asks (${req.method}): ${req.params?.message}`)
  // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
  return { action: "accept", content: { name: "Ada" } }
}

const res = await c.callTool("greet", {}, askUser)
console.log("result:", mcp.text(res))

await ts.close()
