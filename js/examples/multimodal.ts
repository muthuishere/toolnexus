/**
 * Live multimodal example (SPEC §1B / §8A) against OpenRouter. Run after `npm run build`:
 *   OPENROUTER_API_KEY=... node --experimental-strip-types examples/multimodal.ts
 *
 * Two things are proved per provider style, and neither of them trusts the model's prose:
 *
 *  1. **The attached image actually arrived** — the SAME request is sent twice, once without the
 *     image and once with it, and the PROMPT-TOKEN DELTA is reported. A model asked to name
 *     colours will happily name four colours it never received, so the answer text is evidence of
 *     nothing; a few thousand extra prompt tokens is. (The quadrant colours are reported too, but
 *     only as a secondary signal.)
 *  2. **The §8A relocation rule works end to end** — a tool returns an image in `ToolResult.parts`,
 *     the model calls it, and the run completes with the image relocated onto the wire (native
 *     `tool_result.content` for anthropic; one synthetic `user` message for openai).
 *
 * The key is read from the environment and never printed.
 */
import { createToolkit, defineTool, createClient, attach, type ContentPart } from "../dist/index.js"

const FIXTURE = new URL("../../examples/media/fixture.png", import.meta.url).pathname
const QUADRANTS = ["red", "green", "blue", "white"]
const ASK = "This image has four quadrants. Name the colour of each quadrant: top-left, top-right, bottom-left, bottom-right. Answer with just the four colour words."

const KEY = process.env.OPENROUTER_API_KEY
if (!KEY) {
  console.log("(no OPENROUTER_API_KEY — skipping live multimodal check)")
  process.exit(0)
}

const image = await attach(FIXTURE)
console.log(`fixture: ${FIXTURE} (${image.type}, ${(image as any).mimeType})`)

// A toolkit with no tools — phase 1 measures the prompt, not a schema.
const bare = await createToolkit({})

// A tool that hands the model an image back through `ToolResult.parts` (§8A relocation).
const withTool = await createToolkit({})
withTool.register(
  defineTool({
    name: "get_test_image",
    description: "Return the 8x8 four-quadrant test image.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    run: () => ({ output: "test image attached", isError: false, parts: [image] as ContentPart[] }),
  }),
)

// "style" here is the MODEL FAMILY, which is what the aggregated RESULT lines compare. The wire
// is OpenAI-shaped in both cases: OpenRouter fronts every model behind one OpenAI-compatible
// endpoint, so an anthropic-family model is reached with `image_url` blocks, not `source{}`.
const STYLES: { style: "openai" | "anthropic"; model: string }[] = [
  { style: "openai", model: "openai/gpt-4o-mini" },
  { style: "anthropic", model: "anthropic/claude-haiku-4.5" },
]

const lines: string[] = []

for (const { style, model } of STYLES) {
  const client = createClient({
    baseUrl: "https://openrouter.ai/api/v1",
    style: "openai", // OpenRouter's endpoint is OpenAI-compatible for every model it fronts
    model,
    apiKey: KEY,
    systemPrompt: "Answer in as few words as possible.",
    maxTurns: 4,
    requestParams: { max_tokens: 40 },
  })

  // --- 1. token-delta proof -------------------------------------------------
  const textOnly = await client.run(ASK, { toolkit: bare })
  const withImage = await client.run([{ type: "text", text: ASK }, image], { toolkit: bare })
  const ptokText = textOnly.usage.promptTokens
  const ptokImage = withImage.usage.promptTokens
  const delta = ptokImage - ptokText
  const answer = withImage.text.toLowerCase()
  const colours = QUADRANTS.filter((c) => answer.includes(c)).length

  console.log(`\n[${style} / ${model}]`)
  console.log(`  no image : ${ptokText} prompt tokens`)
  console.log(`  + image  : ${ptokImage} prompt tokens (delta ${delta >= 0 ? "+" : ""}${delta})`)
  console.log(`  answer   : ${withImage.text.trim().replace(/\s+/g, " ")}`)
  if (delta < 100) {
    console.log("  ⚠️  delta is tiny — the image was accepted (HTTP 200) but not carried to the model.")
  }

  // --- 2. §8A relocation, end to end ---------------------------------------
  let relocation = "ok"
  try {
    const res = await client.run("Call get_test_image, then name the four quadrant colours.", { toolkit: withTool })
    const called = res.toolCalls.some((c) => c.name === "get_test_image")
    relocation = called && res.status === "done" ? "ok" : `no (called=${called} status=${res.status})`
    console.log(`  relocation: tools=${res.toolCalls.map((c) => c.name).join(",") || "(none)"} status=${res.status}`)
    console.log(`  relocated answer: ${res.text.trim().replace(/\s+/g, " ")}`)
  } catch (e) {
    relocation = `err(${e instanceof Error ? e.message.slice(0, 60) : String(e)})`
  }

  lines.push(
    `RESULT js style=${style} model=${model} ptok_text=${ptokText} ptok_image=${ptokImage} ` +
      `delta=${delta >= 0 ? "+" : ""}${delta} colours=${colours}/4 relocation=${relocation}`,
  )
}

await bare.close()
await withTool.close()

console.log("")
for (const l of lines) console.log(l)
