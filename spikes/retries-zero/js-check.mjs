// Gate item 1, JS: does `retries?: number` (js/src/client.ts:36, used at :674
// `const retries = this.opts.retries ?? 2`) already distinguish "unset" from
// "explicit 0"?  `??` only falls through on null/undefined, so an explicit 0
// survives.  Prove it by driving a real Client whose transport always fails,
// with retries explicitly set to 0, and counting fetch invocations.

import { Client, createToolkit } from "../../js/dist/index.js"

const toolkit = await createToolkit({})

let calls = 0
const failingFetch = async () => {
  calls++
  return new Response("boom", { status: 500 })
}

const client = new Client({
  baseUrl: "https://example.invalid",
  style: "openai",
  model: "test-model",
  apiKey: "x",
  retries: 0, // explicit zero
  fetch: failingFetch,
})

try {
  await client.run({ prompt: "hi" }, { toolkit })
} catch (e) {
  // expected: the 500 surfaces as a thrown/returned error after the single attempt
}

console.log(`fetch calls with retries:0 -> ${calls}`)
if (calls !== 1) {
  console.error(`FAIL: expected exactly 1 call (no retry), got ${calls}`)
  process.exit(1)
}

// Now omit `retries` entirely and confirm the *documented* default (2 retries
// => 3 total attempts) still applies -- i.e. explicit-0 and unset are NOT
// the same code path.
calls = 0
const client2 = new Client({
  baseUrl: "https://example.invalid",
  style: "openai",
  model: "test-model",
  apiKey: "x",
  // no retries key at all
  fetch: failingFetch,
})
try {
  await client2.run({ prompt: "hi" }, { toolkit })
} catch (e) {}
console.log(`fetch calls with retries UNSET -> ${calls}`)
if (calls !== 3) {
  console.error(`FAIL: expected exactly 3 calls (default 2 retries), got ${calls}`)
  process.exit(1)
}

console.log("JS VERDICT: retries:0 !== unset. `??` already distinguishes them. No -1 sentinel needed.")
