/**
 * Multimodal content parts (SPEC.md §1B, §2, §6, §8A, §11).
 * Hermetic: no network, no live LLM — every provider call is an injected fetch.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import {
  attach,
  text as textPart,
  fromBytes,
  fromDataUrl,
  ContentPartError,
  MEDIA_TYPES,
  describePart,
  summarizePart,
  unsupportedPlaceholder,
  partTokens,
  encodePart,
  encodeParts,
  resetUnsupportedWarnings,
  relocationHeader,
  createClient,
  createToolkit,
  defineTool,
  buildMcpServer,
  loadMcp,
  createBuiltinTools,
  openAIMessagesToAnthropic,
  agents,
  type ContentPart,
} from "../dist/index.js"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const MEDIA = path.resolve(HERE, "../../examples/media")
const FIXTURE = path.join(MEDIA, "fixture.png")
/** The COMMITTED golden — never this port's own re-encoding (D11). */
const GOLDEN = fs.readFileSync(path.join(MEDIA, "fixture.png.base64"), "utf8").trim()

const caught = (fn: () => unknown): any => { try { fn(); return undefined } catch (e) { return e } }

const tool = (name: string) => createBuiltinTools().find((t: any) => t.name === name)!

/** A scripted LLM: each call shifts the next canned response and records the body sent. */
function scriptedFetch(responses: any[]) {
  const bodies: any[] = []
  const f = async (_url: any, init: any) => {
    bodies.push(JSON.parse(String(init.body)))
    const next = responses.shift() ?? { choices: [{ message: { content: "" } }], usage: {} }
    return new Response(JSON.stringify(next), { status: 200, headers: { "content-type": "application/json" } })
  }
  return { fetch: f as unknown as typeof fetch, bodies }
}

const openAiToolTurn = (calls: { id: string; name: string }[]) => ({
  choices: [{ message: { role: "assistant", tool_calls: calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: "{}" } })) } }],
  usage: {},
})
const openAiText = (t: string) => ({ choices: [{ message: { role: "assistant", content: t } }], usage: {} })

const anthropicToolTurn = (calls: { id: string; name: string }[]) => ({
  content: calls.map((c) => ({ type: "tool_use", id: c.id, name: c.name, input: {} })),
  stop_reason: "tool_use",
  usage: {},
})
const anthropicText = (t: string) => ({ content: [{ type: "text", text: t }], stop_reason: "end_turn", usage: {} })

// --------------------------------------------------------------------------- //
// §1B — the shared ContentPart model and its edge constructors
// --------------------------------------------------------------------------- //

test("part: both data and url is a typed construction error", () => {
  const e = caught(() => encodeParts([{ type: "image", mimeType: "image/png", data: "x", url: "https://e/x.png" } as ContentPart], { style: "openai", provenance: "attached" }))
  assert.ok(e instanceof ContentPartError)
  assert.equal(e.code, "source-conflict")
  assert.match(e.message, /both data and url/)
})

test("part: neither data nor url is a typed construction error", () => {
  const e = caught(() => encodeParts([{ type: "image", mimeType: "image/png" } as ContentPart], { style: "openai", provenance: "attached" }))
  assert.ok(e instanceof ContentPartError)
  assert.equal(e.code, "source-missing")
})

test("part: base64 of the shared fixture equals the COMMITTED golden", async () => {
  const p: any = await attach(FIXTURE)
  assert.equal(p.type, "image")
  assert.equal(p.mimeType, "image/png")
  assert.equal(p.data, GOLDEN, "base64 must equal examples/media/fixture.png.base64, not our own re-encoding")
})

test("part: a path is read at the edge and never stored", async () => {
  const p: any = await attach(FIXTURE)
  assert.equal("path" in p, false, "a part never carries a filesystem path (§1B)")
  assert.equal("url" in p, false)
  // A persisted transcript must replay with the file gone.
  const replayed = JSON.parse(JSON.stringify(p))
  assert.equal(replayed.data, GOLDEN)
})

test("part: a data: URL is normalised into {mimeType, data} at construction", () => {
  const p: any = fromDataUrl(`data:image/png;base64,${GOLDEN}`)
  assert.deepEqual(p, { type: "image", mimeType: "image/png", data: GOLDEN })
  assert.equal("url" in p, false, "two spellings of the same bytes must not diverge downstream")
})

test("part: an https URL is kept as a url", async () => {
  const p: any = await attach("https://example.test/shot.png")
  assert.deepEqual(p, { type: "image", mimeType: "image/png", url: "https://example.test/shot.png" })
})

test("part: an unknown extension is refused BY NAME", async () => {
  const err = await attach("/tmp/thing.xyz").catch((x) => x as ContentPartError)
  assert.ok(err instanceof ContentPartError)
  assert.equal(err.code, "unknown-extension")
  assert.match(err.message, /"xyz"/, "the refusal names the extension")
})

test("part: a Blob is consumed eagerly and stores only mimeType + base64", async () => {
  const blob = new Blob([fs.readFileSync(FIXTURE)], { type: "image/png" })
  const p: any = await attach(blob)
  assert.deepEqual(p, { type: "image", mimeType: "image/png", data: GOLDEN })
  for (const k of ["path", "url", "stream", "blob", "handle", "name"]) {
    assert.equal(k in p, false, `a part never carries a ${k} (§1B: accept broadly, store narrowly)`)
  }
})

test("part: a File takes its mimeType from .type and survives a transcript round-trip", async () => {
  const file = new File([fs.readFileSync(FIXTURE)], "shot.png", { type: "image/png" })
  const p: any = await attach(file)
  assert.equal(p.type, "image")
  assert.equal(p.mimeType, "image/png")
  assert.equal(p.data, GOLDEN)
  assert.deepEqual(JSON.parse(JSON.stringify(p)), p, "an eagerly-read part replays with the File gone")
})

test("part: a File name lands on a file part, and opts.name still wins", async () => {
  const bytes = fs.readFileSync(FIXTURE)
  const doc = new File([bytes], "report.pdf", { type: "application/pdf" })
  const p: any = await attach(doc)
  assert.equal(p.type, "file")
  assert.equal(p.name, "report.pdf")
  assert.equal((await attach(doc, { name: "override.pdf" }) as any).name, "override.pdf")
})

test("part: a Blob with an empty .type falls back to the extension table via the filename", async () => {
  const file = new File([fs.readFileSync(FIXTURE)], "shot.png")
  assert.equal(file.type, "", "precondition: the File was constructed with no type")
  const p: any = await attach(file)
  assert.equal(p.mimeType, "image/png", "mime came from the §6 table, never from sniffing the bytes")
  assert.equal(p.data, GOLDEN)
})

test("part: a typeless Blob with no derivable extension is refused BY NAME", async () => {
  const err = await attach(new File([new Uint8Array([1, 2])], "thing.xyz")).catch((x) => x as ContentPartError)
  assert.ok(err instanceof ContentPartError)
  assert.equal(err.code, "unknown-extension")
  assert.match(err.message, /"xyz"/)
  const bare = await attach(new Blob([new Uint8Array([1, 2])])).catch((x) => x as ContentPartError)
  assert.ok(bare instanceof ContentPartError)
  assert.equal(bare.code, "unknown-extension")
})

test("part: an ArrayBuffer is accepted and base64s to the committed golden", async () => {
  const buf = fs.readFileSync(FIXTURE)
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer
  assert.ok(ab instanceof ArrayBuffer)
  const p: any = await attach(ab, { mimeType: "image/png" })
  assert.deepEqual(p, { type: "image", mimeType: "image/png", data: GOLDEN })
})

test("part: a Buffer (a Uint8Array subclass) still works, offset views included", async () => {
  const buf = fs.readFileSync(FIXTURE)
  assert.ok(Buffer.isBuffer(buf))
  const p: any = await attach(buf, { mimeType: "image/png" })
  assert.equal(p.data, GOLDEN)
  // A Buffer sliced out of a larger pool is a VIEW: honouring byteOffset is the whole test.
  const pooled = Buffer.concat([Buffer.from([0xff, 0xff, 0xff]), buf]).subarray(3)
  assert.equal((await attach(pooled, { mimeType: "image/png" }) as any).data, GOLDEN)
})

test("part: a non-Uint8Array ArrayBufferView is re-viewed, not read as an array of numbers", async () => {
  const view = new DataView(new Uint8Array(fs.readFileSync(FIXTURE)).buffer)
  const p: any = await attach(view, { mimeType: "image/png" })
  assert.equal(p.data, GOLDEN)
})

test("part: bytes with no mimeType are still refused, for every byte spelling", async () => {
  for (const src of [new Uint8Array([1]), new ArrayBuffer(1), Buffer.from([1])]) {
    const err = await attach(src as any).catch((x) => x as ContentPartError)
    assert.ok(err instanceof ContentPartError, "bytes carry no extension to read")
    assert.equal(err.code, "unknown-extension")
  }
})

test("part: the media extension table is exactly the spec's eight entries", () => {
  assert.deepEqual(Object.keys(MEDIA_TYPES).sort(), ["gif", "jpeg", "jpg", "mp3", "pdf", "png", "wav", "webp"])
})

test("part: maxPartBytes is measured in DECODED bytes and rejects at the edge", () => {
  const bytes = new Uint8Array(2048)
  const err = caught(() => fromBytes(bytes, "image/png", { maxPartBytes: 1024 })) as ContentPartError
  assert.ok(err instanceof ContentPartError)
  assert.equal(err.code, "too-large")
  assert.match(err.message, /1024/)
  assert.match(err.message, /2048/, "the error names both the limit and the actual size")
  // 1024 decoded bytes is ~1368 base64 chars: measuring the string would wrongly reject this.
  assert.ok(fromBytes(new Uint8Array(1024), "image/png", { maxPartBytes: 1024 }))
})

test("part: bytes are never logged — describePart renders {type, mimeType, bytes}", async () => {
  const p = await attach(FIXTURE)
  const d = describePart(p)
  assert.deepEqual(d, { type: "image", mimeType: "image/png", bytes: 82 })
  assert.equal(JSON.stringify(d).includes(GOLDEN.slice(0, 20)), false, "no base64 in an event payload")
})

test("part: a url part (no data) renders <bytes> as 0 in every canonical string", () => {
  const p: ContentPart = { type: "image", mimeType: "image/png", url: "https://e/shot.png" }
  assert.equal(describePart(p).bytes, 0)
  assert.equal(summarizePart(p), "image (image/png, 0 bytes)")
  assert.equal(unsupportedPlaceholder(p), "[unsupported image part (image/png, 0 bytes)]")
})

test("part: <bytes> in the canonical strings is always a plain integer, never a fraction", () => {
  // "AQID" is real padded base64 (3 decoded bytes) — the point is the RENDERED string, not the
  // payload's realism.
  const p: ContentPart = { type: "audio", mimeType: "audio/mpeg", data: "AQID" }
  assert.equal(summarizePart(p), "audio (audio/mpeg, 3 bytes)")
  assert.equal(unsupportedPlaceholder(p), "[unsupported audio part (audio/mpeg, 3 bytes)]")
  assert.match(summarizePart(p), /^\S+ \(\S+, \d+ bytes\)$/, "bytes count has no decimal point")
})

test("tokens: partTokens is exactly max(85, floor(decodedBytes / 750)) (SPEC.md §1B)", () => {
  assert.equal(partTokens(0), 85, "floor(0/750)=0 is still floored up to the 85 minimum")
  assert.equal(partTokens(750 * 85), 85, "exactly at the minimum's byte boundary")
  assert.equal(partTokens(750 * 100), 100)
  assert.equal(partTokens(750 * 100 + 749), 100, "floor, not ceil or round, at the boundary")
  assert.equal(partTokens(750 * 100 + 750), 101)
})

// --------------------------------------------------------------------------- //
// §8A — emission, the positive allowlist, and the provenance rule
// --------------------------------------------------------------------------- //

test("emit: the openai/anthropic block shapes match the §8A table", () => {
  const img: ContentPart = { type: "image", mimeType: "image/png", data: "B64" }
  assert.deepEqual(encodePart(img, "openai"), { type: "image_url", image_url: { url: "data:image/png;base64,B64" } })
  assert.deepEqual(encodePart(img, "anthropic"), { type: "image", source: { type: "base64", media_type: "image/png", data: "B64" } })

  const url: ContentPart = { type: "image", mimeType: "image/png", url: "https://e/x.png" }
  assert.deepEqual(encodePart(url, "openai"), { type: "image_url", image_url: { url: "https://e/x.png" } })
  assert.deepEqual(encodePart(url, "anthropic"), { type: "image", source: { type: "url", url: "https://e/x.png" } })

  const pdf: ContentPart = { type: "file", mimeType: "application/pdf", data: "B64", name: "r.pdf" }
  // file_data REQUIRES the data: prefix — a bare base64 string is a 400.
  assert.deepEqual(encodePart(pdf, "openai"), { type: "file", file: { filename: "r.pdf", file_data: "data:application/pdf;base64,B64" } })

  const mp3: ContentPart = { type: "audio", mimeType: "audio/mpeg", data: "B64" }
  assert.deepEqual(encodePart(mp3, "openai"), { type: "input_audio", input_audio: { data: "B64", format: "mp3" } })
  assert.equal(encodePart(mp3, "anthropic"), undefined, "anthropic defines no audio block — a named refusal")
  assert.equal(encodePart({ type: "file", mimeType: "application/pdf", url: "https://e/r.pdf" }, "openai"), undefined,
    "chat completions has no URL form for a file — a named refusal")
})

test("emit: an attached part the style cannot represent errors before any HTTP call", async () => {
  const cap = scriptedFetch([openAiText("never")])
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch })
  const err = await c.run([textPart("listen"), { type: "audio", mimeType: "audio/mpeg", data: "B64" }], { toolkit: tk }).catch((e) => e)
  assert.ok(err instanceof ContentPartError, `expected ContentPartError, got ${err}`)
  assert.equal(err.code, "unsupported")
  assert.match(err.message, /anthropic/)
  assert.match(err.message, /audio/)
  assert.equal(cap.bodies.length, 0, "no request was sent")
  await tk.close()
})

test("emit: a tool-DERIVED unsupported part degrades to a placeholder and warns once", async () => {
  resetUnsupportedWarnings()
  const warns: string[] = []
  const realWarn = console.warn
  console.warn = (...a: any[]) => { warns.push(a.join(" ")) }
  try {
    const cap = scriptedFetch([anthropicToolTurn([{ id: "u1", name: "clip" }]), anthropicText("heard it"), anthropicToolTurn([{ id: "u2", name: "clip" }]), anthropicText("again")])
    const tk = await createToolkit({ builtins: false })
    tk.register({
      ...defineTool({ name: "clip", description: "d", run: () => "audio clip, 3s" }),
      async execute() { return { output: "audio clip, 3s", isError: false, parts: [{ type: "audio", mimeType: "audio/mpeg", data: "AQID" }] as ContentPart[] } },
    } as any)
    const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch })
    const r1 = await c.run("play it", { toolkit: tk })
    assert.equal(r1.text, "heard it", "the run completed instead of failing")
    const blocks = cap.bodies[1].messages.at(-1).content[0].content
    assert.deepEqual(blocks[0], { type: "text", text: "audio clip, 3s" })
    assert.deepEqual(blocks[1], { type: "text", text: "[unsupported audio part (audio/mpeg, 3 bytes)]" })
    await c.run("play it again", { toolkit: tk })
    assert.equal(warns.filter((w) => w.includes("no block for")).length, 1, "warned at most once")
    await tk.close()
  } finally {
    console.warn = realWarn
  }
})

test("emit: onUnsupportedPart:'error' forces uniform strictness on a derived part", async () => {
  const cap = scriptedFetch([anthropicToolTurn([{ id: "u1", name: "clip" }]), anthropicText("x")])
  const tk = await createToolkit({ builtins: false })
  tk.register({
    ...defineTool({ name: "clip", description: "d", run: () => "x" }),
    async execute() { return { output: "x", isError: false, parts: [{ type: "audio", mimeType: "audio/mpeg", data: "B64" }] as ContentPart[] } },
  } as any)
  const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch, onUnsupportedPart: "error" })
  const err = await c.run("go", { toolkit: tk }).catch((e) => e)
  assert.ok(err instanceof ContentPartError)
  assert.equal(err.code, "unsupported")
  await tk.close()
})

// --------------------------------------------------------------------------- //
// §1B — maxPartBytes is enforced at assembly, following the unsupported-part
// provenance split (attached errors, tool-derived degrades). Not just a
// construction-time convenience: an MCP-derived part never passed through an
// edge constructor, so a limit it can walk around is not a limit.
// --------------------------------------------------------------------------- //

test("emit: maxPartBytes at assembly follows the unsupported-part provenance split", () => {
  const big: ContentPart = { type: "image", mimeType: "image/png", data: GOLDEN } // 82 decoded bytes

  const attachedErr = caught(() => encodeParts([big], { style: "anthropic", provenance: "attached", maxPartBytes: 10 })) as ContentPartError
  assert.ok(attachedErr instanceof ContentPartError, `expected ContentPartError, got ${attachedErr}`)
  assert.equal(attachedErr.code, "too-large")
  assert.match(attachedErr.message, /82/)
  assert.match(attachedErr.message, /10/)

  resetUnsupportedWarnings()
  const derived = encodeParts([big], { style: "anthropic", provenance: "derived", maxPartBytes: 10 })
  assert.deepEqual(
    derived,
    [{ type: "text", text: "[unsupported image part (image/png, 82 bytes)]" }],
    "a tool-derived oversize part degrades to the canonical placeholder instead of failing the run",
  )
})

test("emit: an oversize ATTACHED part errors 'too-large' before any HTTP call", async () => {
  const cap = scriptedFetch([openAiText("never")])
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch, maxPartBytes: 10 })
  const err = await c.run([textPart("look"), { type: "image", mimeType: "image/png", data: GOLDEN }], { toolkit: tk }).catch((e) => e)
  assert.ok(err instanceof ContentPartError, `expected ContentPartError, got ${err}`)
  assert.equal(err.code, "too-large")
  assert.equal(cap.bodies.length, 0, "no request was sent")
  await tk.close()
})

test("emit: a tool-DERIVED oversize part degrades at assembly and warns once", async () => {
  resetUnsupportedWarnings()
  const warns: string[] = []
  const realWarn = console.warn
  console.warn = (...a: any[]) => { warns.push(a.join(" ")) }
  try {
    const cap = scriptedFetch([anthropicToolTurn([{ id: "u1", name: "shot" }]), anthropicText("saw it"), anthropicToolTurn([{ id: "u2", name: "shot" }]), anthropicText("again")])
    const tk = await createToolkit({ builtins: false })
    tk.register({
      ...defineTool({ name: "shot", description: "d", run: () => "a screenshot" }),
      async execute() { return { output: "a screenshot", isError: false, parts: [{ type: "image", mimeType: "image/png", data: GOLDEN }] as ContentPart[] } },
    } as any)
    // A remote MCP/tool server can hand back an oversize image; a limit that fails the whole
    // run over it is not a limit the caller can rely on — it must degrade instead (§1B).
    const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch, maxPartBytes: 10 })
    const r1 = await c.run("take it", { toolkit: tk })
    assert.equal(r1.text, "saw it", "the run completed instead of failing on a tool-derived oversize part")
    const blocks = cap.bodies[1].messages.at(-1).content[0].content
    assert.deepEqual(blocks[0], { type: "text", text: "a screenshot" })
    assert.deepEqual(blocks[1], { type: "text", text: "[unsupported image part (image/png, 82 bytes)]" })
    await c.run("take it again", { toolkit: tk })
    assert.equal(warns.filter((w) => w.includes("over the maxPartBytes limit")).length, 1, "warned at most once")
    await tk.close()
  } finally {
    console.warn = realWarn
  }
})

// --------------------------------------------------------------------------- //
// §7 — the loop accepts parts, and the string path does not move
// --------------------------------------------------------------------------- //

test("loop: a string prompt is byte-identical to the pre-0.17 user message", async () => {
  const cap = scriptedFetch([openAiText("hi back")])
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: cap.fetch })
  const r = await c.run("hello", { toolkit: tk })
  assert.deepEqual(cap.bodies[0].messages, [{ role: "user", content: "hello" }])
  assert.equal(r.messages[0].content, "hello", "the transcript keeps the plain string too")
  await tk.close()
})

test("loop: [text, image, text] reaches the wire as three blocks in order", async () => {
  const cap = scriptedFetch([openAiText("ok")])
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: cap.fetch })
  const img = await attach(FIXTURE)
  await c.run([textPart("before"), img, textPart("after")], { toolkit: tk })
  const content = cap.bodies[0].messages[0].content
  assert.equal(content.length, 3)
  assert.deepEqual(content[0], { type: "text", text: "before" })
  assert.equal(content[1].type, "image_url")
  assert.equal(content[1].image_url.url, `data:image/png;base64,${GOLDEN}`)
  assert.deepEqual(content[2], { type: "text", text: "after" })
  await tk.close()
})

test("loop: an all-text parts array still reaches anthropic as text blocks in order", async () => {
  const cap = scriptedFetch([anthropicText("ok")])
  const tk = await createToolkit({ builtins: false })
  const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch })
  await c.run([textPart("a"), textPart("b")], { toolkit: tk })
  assert.deepEqual(cap.bodies[0].messages[0].content, [{ type: "text", text: "a" }, { type: "text", text: "b" }])
  await tk.close()
})

// --------------------------------------------------------------------------- //
// §8A — the tool-result relocation rule
// --------------------------------------------------------------------------- //

async function shotToolkit(names: string[]) {
  const tk = await createToolkit({ builtins: false })
  for (const n of names) {
    tk.register({
      ...defineTool({ name: n, description: "d", run: () => "" }),
      async execute() {
        return { output: `${n}: screenshot, 1280x720 png`, isError: false, parts: [{ type: "image", mimeType: "image/png", data: GOLDEN }] as ContentPart[] }
      },
    } as any)
  }
  return tk
}

test("relocate: anthropic gets the image INSIDE tool_result, and no synthetic user message", async () => {
  const cap = scriptedFetch([anthropicToolTurn([{ id: "u1", name: "shot" }]), anthropicText("a red square")])
  const tk = await shotToolkit(["shot"])
  const c = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap.fetch })
  const r = await c.run("what's on screen?", { toolkit: tk })
  const sent = cap.bodies[1].messages
  const resultTurn = sent.at(-1)
  assert.equal(resultTurn.role, "user")
  assert.equal(resultTurn.content.length, 1, "exactly one tool_result block — nothing was relocated")
  const block = resultTurn.content[0]
  assert.equal(block.tool_use_id, "u1", "the image stays keyed to its tool_use_id")
  assert.deepEqual(block.content[0], { type: "text", text: "shot: screenshot, 1280x720 png" })
  assert.deepEqual(block.content[1], { type: "image", source: { type: "base64", media_type: "image/png", data: GOLDEN } })
  assert.equal("parts" in block, false, "the canonical `parts` key never reaches the wire")
  assert.equal(r.text, "a red square")
  await tk.close()
})

test("relocate: openai gets ONE synthetic user message carrying both images, in tool-call order", async () => {
  const cap = scriptedFetch([openAiToolTurn([{ id: "c1", name: "one" }, { id: "c2", name: "two" }]), openAiText("two squares")])
  const tk = await shotToolkit(["one", "two"])
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: cap.fetch })
  const r = await c.run("grab both", { toolkit: tk })
  const sent = cap.bodies[1].messages
  const tools = sent.filter((m: any) => m.role === "tool")
  assert.equal(tools.length, 2)
  for (const m of tools) {
    assert.equal(typeof m.content, "string", "an openai tool message carries text only — an image there is a hard 400")
    assert.equal("parts" in m, false)
  }
  const synthetic = sent.at(-1)
  assert.equal(synthetic.role, "user")
  assert.equal(sent.filter((m: any) => m.role === "user").length, 2, "exactly ONE synthetic user message was added")
  assert.deepEqual(synthetic.content.map((b: any) => b.type), ["text", "image_url", "text", "image_url"])
  assert.equal(synthetic.content[0].text, relocationHeader("one", "c1"))
  assert.equal(synthetic.content[2].text, relocationHeader("two", "c2"))
  assert.equal(synthetic.content[1].image_url.url, `data:image/png;base64,${GOLDEN}`)

  // …and it is an adapter artifact only.
  assert.equal(
    r.messages.filter((m: any) => m.role === "user").length, 1,
    "the synthetic message is never written to the canonical transcript",
  )
  assert.equal(r.text, "two squares")
  await tk.close()
})

test("relocate: the transcript keeps parts, so a later anthropic turn sees no openai residue", async () => {
  const cap = scriptedFetch([openAiToolTurn([{ id: "c1", name: "shot" }]), openAiText("done")])
  const tk = await shotToolkit(["shot"])
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: cap.fetch })
  const r = await c.run("shot it", { toolkit: tk })
  const toolMsg = r.messages.find((m: any) => m.role === "tool") as any
  assert.equal(toolMsg.content, "shot: screenshot, 1280x720 png", "the transcript's tool text is `output`")
  assert.equal(toolMsg.parts[0].data, GOLDEN, "the part rides the canonical transcript, provider-neutral")

  // Replay the same transcript against anthropic: the image is emitted natively.
  const cap2 = scriptedFetch([anthropicText("still fine")])
  const c2 = createClient({ baseUrl: "http://never.invalid", style: "anthropic", model: "m", apiKey: "k", fetch: cap2.fetch })
  const tk2 = await createToolkit({ builtins: false })
  await c2.run("and now?", { toolkit: tk2, history: r.messages })
  assert.equal(JSON.stringify(cap2.bodies[0].messages).includes("Output of tool"), false, "no OpenAI-shaped residue")
  await tk.close(); await tk2.close()
})

test("suspension: parts do not collide with metadata.pending (§10)", async () => {
  const cap = scriptedFetch([openAiToolTurn([{ id: "c1", name: "login" }]), openAiText("x")])
  const tk = await createToolkit({ builtins: false })
  tk.register({
    ...defineTool({ name: "login", description: "d", run: () => "" }),
    async execute() {
      return {
        output: "Approve at the link", isError: true,
        parts: [{ type: "image", mimeType: "image/png", data: GOLDEN }] as ContentPart[],
        metadata: { pending: { id: "p1", kind: "authorization", prompt: "Approve at the link" } },
      }
    },
  } as any)
  const c = createClient({ baseUrl: "http://never.invalid", style: "openai", model: "m", apiKey: "k", fetch: cap.fetch })
  const r = await c.run("log in", { toolkit: tk })
  assert.equal(r.status, "pending")
  assert.equal(r.pending?.kind, "authorization")
  await tk.close()
})

// --------------------------------------------------------------------------- //
// §2 — MCP tool results preserve non-text content
// --------------------------------------------------------------------------- //

function writeStdioServer(bodyJs: string) {
  const p = path.resolve(HERE, "../._mmsrv_" + Math.random().toString(36).slice(2) + ".mjs")
  fs.writeFileSync(p, bodyJs)
  return p
}

const MEDIA_SERVER = (b64: string) => `
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js"
const B = ${JSON.stringify(b64)}
const schema = { type: "object", properties: {} }
const names = ["shot", "textonly", "linky", "structured", "boom", "imageonly", "blobby"]
const server = new Server({ name: "media", version: "0" }, { capabilities: { tools: {} } })
server.setRequestHandler(ListToolsRequestSchema, () => ({ tools: names.map((n) => ({ name: n, description: n, inputSchema: schema })) }))
server.setRequestHandler(CallToolRequestSchema, (req) => {
  switch (req.params.name) {
    case "shot": return { content: [{ type: "text", text: "here" }, { type: "image", data: B, mimeType: "image/png" }] }
    case "textonly": return { content: [{ type: "text", text: "a" }, { type: "text", text: "b" }] }
    case "linky": return { content: [{ type: "resource_link", uri: "https://e/r.pdf", name: "r.pdf", mimeType: "application/pdf" }] }
    case "structured": return { structuredContent: { ok: 1 }, content: [{ type: "image", data: B, mimeType: "image/png" }] }
    case "boom": return { isError: true, content: [{ type: "text", text: "it broke" }, { type: "image", data: B, mimeType: "image/png" }] }
    case "imageonly": return { content: [{ type: "image", data: B, mimeType: "image/png" }] }
    case "blobby": return { content: [{ type: "resource", resource: { uri: "file:///r.bin", mimeType: "application/octet-stream", blob: B } }, { type: "resource", resource: { uri: "file:///n.txt", mimeType: "text/plain", text: "note" } }] }
  }
  throw new Error("unknown")
})
await server.connect(new StdioServerTransport())
`

test("mcp: every non-text content entry becomes a part, on every branch", async () => {
  const script = writeStdioServer(MEDIA_SERVER(GOLDEN))
  try {
    const mcp = await loadMcp({ media: { command: ["node", script] } })
    const t = (n: string) => mcp.tools.find((x: any) => x.name === `media_${n}`)!

    const shot = await t("shot").execute({})
    assert.equal(shot.output, "here", "output is still the joined text")
    assert.deepEqual(shot.parts, [{ type: "image", mimeType: "image/png", data: GOLDEN }])

    const only = await t("textonly").execute({})
    assert.equal(only.output, "a\nb")
    assert.equal(only.parts, undefined, "a text-only MCP tool is byte-identical to before")

    const link = await t("linky").execute({})
    assert.equal((link.parts as any)[0].type, "file")
    assert.equal((link.parts as any)[0].url, "https://e/r.pdf", "resource_link ⇒ file{url}")

    const st = await t("structured").execute({})
    assert.equal(st.output, JSON.stringify({ ok: 1 }), "structuredContent still wins the output")
    assert.equal((st.parts as any)[0].type, "image", "the structuredContent short-circuit no longer swallows the image")

    const bad = await t("boom").execute({})
    assert.equal(bad.isError, true)
    assert.equal(bad.output, "it broke")
    assert.equal((bad.parts as any)[0].type, "image", "the error branch keeps its image too")

    const img = await t("imageonly").execute({})
    assert.equal(img.output, "image (image/png, 82 bytes)", "an image-only result names the part, it is not the empty string")
    assert.equal((img.parts as any).length, 1)

    const blob = await t("blobby").execute({})
    assert.equal(blob.output, "note", "an embedded resource carrying TEXT is appended to output")
    assert.deepEqual((blob.parts as any)[0], { type: "file", mimeType: "application/octet-stream", data: GOLDEN, name: "file:///r.bin" })

    await mcp.close()
  } finally {
    fs.unlinkSync(script)
  }
})

// --------------------------------------------------------------------------- //
// §7C — MCP inbound: a served tool's parts become MCP content blocks
// --------------------------------------------------------------------------- //

test("mcp inbound: a tool's image part becomes an MCP image block after the text part", async () => {
  const tools = [
    {
      ...defineTool({ name: "shot", description: "d", run: () => "" }),
      async execute() { return { output: "screenshot", isError: false, parts: [{ type: "image", mimeType: "image/png", data: GOLDEN }] } },
    },
  ] as any[]
  const server: any = buildMcpServer(tools, { name: "s" })
  const handler = (server as any)._requestHandlers.get("tools/call")
  const res = await handler({ method: "tools/call", params: { name: "shot", arguments: {} } }, { signal: undefined })
  assert.equal(res.isError, false)
  assert.deepEqual(res.content[0], { type: "text", text: "screenshot" })
  assert.deepEqual(res.content[1], { type: "image", data: GOLDEN, mimeType: "image/png" })
})

// --------------------------------------------------------------------------- //
// §6 — read returns a part for recognised media, an error for undecodable bytes
// --------------------------------------------------------------------------- //

test("read: a PNG comes back as an image part with a describing output", async () => {
  const r: any = await tool("read").execute({ path: FIXTURE })
  assert.equal(r.isError, false)
  assert.match(r.output, /fixture\.png/)
  assert.match(r.output, /image\/png/)
  assert.equal(r.parts.length, 1)
  assert.equal(r.parts[0].type, "image")
  assert.equal(r.parts[0].data, GOLDEN)
})

test("read: a text file with offset/limit is unchanged and carries no parts", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-mm-"))
  const p = path.join(dir, "a.md")
  fs.writeFileSync(p, "one\ntwo\nthree\nfour")
  const r: any = await tool("read").execute({ path: p, offset: 2, limit: 2 })
  assert.equal(r.output, "two\nthree")
  assert.equal(r.parts, undefined)
  assert.equal((await tool("read").execute({ path: p })).output, "one\ntwo\nthree\nfour")
})

test("read: undecodable bytes are an isError RESULT, never a raised exception", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "tn-mm-"))
  const p = path.join(dir, "blob.bin")
  fs.writeFileSync(p, Buffer.from([0xff, 0xfe, 0xfd, 0x80, 0x81]))
  const r: any = await tool("read").execute({ path: p })
  assert.equal(r.isError, true)
  assert.match(r.output, /blob\.bin/, "the error names the file")
})

// --------------------------------------------------------------------------- //
// §11 — inbound translation keeps non-text parts
// --------------------------------------------------------------------------- //

test("translate: an image part in a content array survives, in the order given", () => {
  const { messages } = openAIMessagesToAnthropic([
    { role: "user", content: [{ type: "text", text: "what is this?" }, { type: "image", mimeType: "image/png", data: GOLDEN }] },
  ])
  assert.equal(messages.length, 1)
  assert.deepEqual(messages[0].content, [
    { type: "text", text: "what is this?" },
    { type: "image", source: { type: "base64", media_type: "image/png", data: GOLDEN } },
  ])
})

test("translate: a native openai image_url block translates too", () => {
  const { messages } = openAIMessagesToAnthropic([
    { role: "user", content: [{ type: "text", text: "hi" }, { type: "image_url", image_url: { url: `data:image/png;base64,${GOLDEN}` } }] },
  ])
  assert.deepEqual(messages[0].content[1], { type: "image", source: { type: "base64", media_type: "image/png", data: GOLDEN } })
})

test("translate: an all-text content array still concatenates, as before", () => {
  const { messages } = openAIMessagesToAnthropic([{ role: "user", content: [{ type: "text", text: "a" }, { type: "text", text: "b" }] }])
  assert.deepEqual(messages, [{ role: "user", content: "ab" }])
})

// --------------------------------------------------------------------------- //
// §1B — parts are not free to the compactor
// --------------------------------------------------------------------------- //

test("tokens: a media part is charged from its byte length, not its mimeType string", () => {
  const big = "A".repeat(Math.ceil((2 * 1024 * 1024 * 4) / 3)) // ~2 MB decoded
  const withImage = [{ role: "user", content: [{ type: "image", mimeType: "image/png", data: big }] }]
  const withoutImage = [{ role: "user", content: [{ type: "text", text: "image/png" }] }]
  const charged = agents.estimateTokens(withImage as any)
  assert.ok(charged > 1000, `a 2 MB image must not be ~free to the compactor (got ${charged})`)
  assert.ok(charged > agents.estimateTokens(withoutImage as any) * 100, "charging the mimeType string would price it at ~3 tokens")
  // …and it is an estimate derived from bytes, not the base64 verbatim.
  assert.ok(charged < 20_000, `charging the base64 string verbatim would be ~700k tokens (got ${charged})`)
})
