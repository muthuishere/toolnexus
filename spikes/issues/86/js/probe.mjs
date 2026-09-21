// Spike for issue #86 (JS): is a toolkit-less ask possible on this branch?
// Claim under test: "Fixed in JS on the jev branch: a synchronous Toolkit.empty()".
import { createClient, createToolkit, Toolkit } from "../../../../js/dist/index.js"

const base = process.env.SPIKE86_BASE
const client = createClient({ baseUrl: base, style: "openai", model: "mock", apiKey: "not-a-real-key" })

// 1. Does a synchronous Toolkit.empty() exist at all?
console.log("js: typeof Toolkit.empty =", typeof Toolkit?.empty)

// 2. The call the issue says should work: no toolkit.
try {
  const r = await client.ask("write me a haiku", {})
  console.log("js: no-toolkit ask OK, text=", JSON.stringify(r.text))
} catch (e) {
  console.log("js: no-toolkit ask FAILED:", e.constructor.name + ": " + e.message)
}

// 3. Not even a ctx at all (the shape a completion user reaches for first).
try {
  const r = await client.ask("write me a haiku")
  console.log("js: bare ask OK, text=", JSON.stringify(r.text))
} catch (e) {
  console.log("js: bare ask FAILED:", e.constructor.name + ": " + e.message)
}

// 4. The workaround that DOES exist today: async, but it does express "no tools".
const tk = await createToolkit({ builtins: false })
const r = await client.ask("write me a haiku", { toolkit: tk })
console.log("js: createToolkit({builtins:false}) OK, tools=", tk.tools().length, "text=", JSON.stringify(r.text))
