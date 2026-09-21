// Spike for issues #91/#92, JavaScript port — cross-port divergence check.
// Entirely OFFLINE: a local stub provider returns an OpenRouter-SHAPED 400 with a
// FAKE account id (user_2FAKE...). No credential, no network egress.
//
//   node spike.mjs        (run from this directory; js/dist must be built)

import http from "node:http"
import { createClient, createToolkit } from "../../../../js/dist/index.js"

const FAKE_BODY = JSON.stringify({
	error: { message: "not a valid model ID", code: 400 },
	user_id: "user_2FAKEFAKEFAKEFAKEFAKEFAKE",
})

let hits = 0
const srv = http.createServer((req, res) => {
	hits++
	if (req.url.includes("slow")) {
		setTimeout(() => {
			res.writeHead(200, { "content-type": "application/json" })
			res.end(JSON.stringify({ choices: [{ message: { role: "assistant", content: "hi" } }], usage: { prompt_tokens: 7, completion_tokens: 3, total_tokens: 10 } }))
		}, 2000)
		return
	}
	res.writeHead(400, { "content-type": "application/json" })
	res.end(FAKE_BODY)
})
await new Promise((r) => srv.listen(0, r))
const base = `http://127.0.0.1:${srv.address().port}`
const rule = (s) => console.log(`\n=== ${s} ===`)

const tk = await createToolkit({})

rule("A. provider 400 with retries: 4 (issue #92 part 2 + the praised fail-fast)")
hits = 0
const c = createClient({ baseUrl: base, model: "stub", apiKey: "not-a-real-key", retries: 4 })
const t0 = Date.now()
try {
	const res = await c.run("hello", { toolkit: tk })
	console.log("no error; status:", JSON.stringify(res.status))
} catch (e) {
	console.log("elapsed      :", Date.now() - t0, "ms")
	console.log("HTTP attempts:", hits, " (retries: 4 -> fail-fast on 4xx is WORKING if this is 1)")
	console.log("err          :", e.message)
	console.log("err carries account id?", e.message.includes("user_2FAKE"))
	console.log("partial RunResult attached to the error?", Object.keys(e).length ? Object.keys(e) : "no — plain Error")
}

rule("B. timeoutMs: 1 against a slow provider (issue #92 part 1)")
const c2 = createClient({ baseUrl: base + "/slow", model: "stub", apiKey: "not-a-real-key", timeoutMs: 1 })
try {
	const res = await c2.run("hello", { toolkit: tk })
	console.log("returned (no throw). status:", JSON.stringify(res.status), "turns:", res.turns, "usage:", res.usage)
} catch (e) {
	console.log("THREW, no RunResult at all:", e.constructor.name, "-", e.message)
	console.log("partial result on the error?", Object.keys(e).length ? Object.keys(e) : "no")
}

rule("C. timeoutMs: 1 on the stream path")
try {
	let n = 0
	for await (const ev of c2.stream("hello", { toolkit: tk })) {
		n++
		console.log(`  event ${n}: type=${ev.type}`, ev.error ?? "")
	}
	if (!n) console.log("  (no events)")
} catch (e) {
	console.log("  stream THREW:", e.constructor.name, "-", e.message)
}

srv.close()
process.exit(0)
