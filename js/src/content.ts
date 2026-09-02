/**
 * Multimodal content parts (SPEC.md §1B, §8A).
 *
 * One `ContentPart` union — `text | image | file | audio` — is how non-text data enters
 * the loop (a prompt), comes back from a tool (`ToolResult.parts`), and survives MCP.
 * A part is **bytes-or-URL, never a path**: a path does not survive a persisted and
 * replayed transcript, nor the MCP / A2A process boundary, so the edge constructors
 * (`attach`, `text`) normalise a path / native bytes / a `data:` URL at construction.
 *
 * Emission lives here too, not in `adapters.ts` (which is tool-*schema* only): each
 * `(style, part.type)` pair has either a defined block shape or an explicit refusal, and
 * the encoded block is checked against a **positive allowlist** before it can reach the
 * wire — because an unknown block type upstream returns HTTP 200 with the content
 * silently discarded, which is the exact bug this module exists to remove.
 */
import fs from "node:fs/promises"
import path from "node:path"

export type ContentPart =
  | { type: "text"; text: string }
  | { type: "image"; mimeType: string; data?: string; url?: string }
  | { type: "file"; mimeType: string; data?: string; url?: string; name?: string }
  | { type: "audio"; mimeType: string; data?: string; url?: string }

/** A prompt is a string or an ordered list of parts — ordering is semantic to a model. */
export type PromptInput = string | ContentPart[]

/** The two provider styles that have a request path (`toGemini` is declarations only). */
export type PartStyle = "openai" | "anthropic"

/** How a part got here. Drives the unsupported-part rule (§8A): the caller asked for an
 *  attached part, so silently changing it is the betrayal; nobody asked for a part an MCP
 *  server volunteered, so failing the run over it is a regression. */
export type PartProvenance = "attached" | "derived"

/** What to do with a part the style cannot represent; overrides provenance uniformly. */
export type UnsupportedPartMode = "error" | "text"

/** Why a part could not be constructed or sent (§1B / §8A). `code` is the machine-readable half. */
export class ContentPartError extends Error {
  constructor(
    message: string,
    readonly code: "source-conflict" | "source-missing" | "unknown-extension" | "too-large" | "unsupported",
  ) {
    super(message)
    this.name = "ContentPartError"
  }
}

/**
 * The media extension table (§6 `read`) — fixed, shared with the edge constructors,
 * identical in every port. No magic-byte sniffing and no platform mime database:
 * `/etc/mime.types` varies per machine and would break cross-port parity.
 */
export const MEDIA_TYPES: Record<string, { mimeType: string; type: "image" | "file" | "audio" }> = {
  png: { mimeType: "image/png", type: "image" },
  jpg: { mimeType: "image/jpeg", type: "image" },
  jpeg: { mimeType: "image/jpeg", type: "image" },
  gif: { mimeType: "image/gif", type: "image" },
  webp: { mimeType: "image/webp", type: "image" },
  pdf: { mimeType: "application/pdf", type: "file" },
  mp3: { mimeType: "audio/mpeg", type: "audio" },
  wav: { mimeType: "audio/wav", type: "audio" },
}

/** Lower-cased extension without the dot, or "" when there is none. */
export function extensionOf(p: string): string {
  const ext = path.extname(p)
  return ext ? ext.slice(1).toLowerCase() : ""
}

/** The table entry for a path/URL, or undefined when the extension is not media. */
export function mediaTypeFor(p: string): { mimeType: string; type: "image" | "file" | "audio" } | undefined {
  return MEDIA_TYPES[extensionOf(p)]
}

/** Part kind for a mime type: `image/*` ⇒ image, `audio/*` ⇒ audio, everything else ⇒ file. */
export function partTypeForMime(mimeType: string): "image" | "file" | "audio" {
  if (mimeType.startsWith("image/")) return "image"
  if (mimeType.startsWith("audio/")) return "audio"
  return "file"
}

/** Decoded byte length of standard, padded base64 — without decoding it (RFC 4648 §4:
 *  `floor(len/4)*3 - padCount`). `<bytes>` in every rendered string is a plain integer, never
 *  a fraction, so this floors the group count rather than assuming `len` is a multiple of 4. */
export function base64Bytes(b64: string): number {
  const n = b64.length
  if (n === 0) return 0
  let pad = 0
  if (b64.endsWith("==")) pad = 2
  else if (b64.endsWith("=")) pad = 1
  return Math.floor(n / 4) * 3 - pad
}

/** Decoded byte length of a part's payload; a url-only part carries no bytes here. */
export function partBytes(part: ContentPart): number {
  if (part.type === "text") return Buffer.byteLength(part.text, "utf8")
  return part.data ? base64Bytes(part.data) : 0
}

/** §1B token estimate for a part, from its decoded byte count: `max(85, floor(bytes/750))`.
 *  Measured against real providers: image cost is not proportional to bytes, so this is an
 *  explicit estimate, not a count — but it is normative and lives with the part logic so a
 *  budgeting helper (e.g. `agents.estimateTokens`) cannot silently diverge from it. */
export const PART_BYTES_PER_TOKEN = 750
export const MIN_PART_TOKENS = 85
export function partTokens(bytes: number): number {
  return Math.max(MIN_PART_TOKENS, Math.floor(bytes / PART_BYTES_PER_TOKEN))
}

/**
 * How a part appears in a log line or a §9 event: `{type, mimeType, bytes}` — never `data`.
 * Same rule as never-log-headers: part payloads are user content.
 */
export function describePart(part: ContentPart): Record<string, unknown> {
  if (part.type === "text") return { type: "text", bytes: partBytes(part) }
  return { type: part.type, mimeType: part.mimeType, bytes: partBytes(part) }
}

/** A one-line, payload-free rendering of a part — for `output` text and placeholders. */
export function summarizePart(part: ContentPart): string {
  return part.type === "text" ? part.text : `${part.type} (${part.mimeType}, ${partBytes(part)} bytes)`
}

/** Validate the §1B invariant: exactly one of `data` / `url`. */
export function validatePart(part: ContentPart, maxPartBytes?: number): ContentPart {
  if (part.type === "text") return part
  const hasData = part.data !== undefined && part.data !== null
  const hasUrl = part.url !== undefined && part.url !== null
  if (hasData && hasUrl) {
    throw new ContentPartError(`content part "${part.type}" carries both data and url — supply exactly one`, "source-conflict")
  }
  if (!hasData && !hasUrl) {
    throw new ContentPartError(`content part "${part.type}" carries neither data nor url — supply exactly one`, "source-missing")
  }
  if (maxPartBytes !== undefined && hasData) {
    const bytes = base64Bytes(part.data!)
    if (bytes > maxPartBytes) {
      throw new ContentPartError(`content part "${part.type}" is ${bytes} decoded bytes, over the maxPartBytes limit of ${maxPartBytes}`, "too-large")
    }
  }
  return part
}

/** True when a value looks like a `ContentPart` (as opposed to a provider-native block). */
export function isContentPart(v: unknown): v is ContentPart {
  if (!v || typeof v !== "object") return false
  const t = (v as any).type
  if (t === "text") return typeof (v as any).text === "string"
  if (t === "image" || t === "file" || t === "audio") return typeof (v as any).mimeType === "string"
  return false
}

// --------------------------------------------------------------------------- //
// Edge constructors — a path / bytes / a data: URL never enters the part itself.
// --------------------------------------------------------------------------- //

/** A text part. */
export function text(value: string): ContentPart {
  return { type: "text", text: value }
}

/** Options for {@link attach}. */
export interface AttachOptions {
  /** Overrides the extension table; **required** for raw bytes and for an unlisted extension. */
  mimeType?: string
  /** Filename carried on a `file` part (OpenAI's `file.filename`). Defaults to the basename. */
  name?: string
  /** Reject a payload over this many **decoded** bytes (never the +33% base64 string). */
  maxPartBytes?: number
}

/** Everything the edge constructor accepts (§1B): a path / `data:` / `https:` string, native
 *  bytes in any of their JS spellings, or the host's own file object. Whatever comes in, only
 *  `mimeType` + base64 `data` (or a `url`) lands in the part — accept broadly, store narrowly. */
export type AttachSource = string | Uint8Array | ArrayBuffer | ArrayBufferView | Blob

/** A `Blob`/`File` — duck-typed, so a Blob from another realm or a polyfill is accepted too. */
function isBlobLike(v: unknown): v is Blob {
  return !!v && typeof v === "object" && typeof (v as any).arrayBuffer === "function" && !ArrayBuffer.isView(v)
}

/** Normalise any native byte spelling to a `Uint8Array` view over the same bytes — an
 *  `ArrayBufferView` that is not a `Uint8Array` must be re-viewed, not passed to
 *  `Buffer.from`, which would read it as an array of numbers. */
function toBytes(source: Uint8Array | ArrayBuffer | ArrayBufferView): Uint8Array {
  if (source instanceof Uint8Array) return source
  if (ArrayBuffer.isView(source)) return new Uint8Array(source.buffer, source.byteOffset, source.byteLength)
  return new Uint8Array(source)
}

/** The typed §1B refusal for a name/path whose extension is not in the fixed table. */
function unknownExtension(nameOrPath: string): ContentPartError {
  const ext = extensionOf(nameOrPath)
  return new ContentPartError(
    `no mime type for extension "${ext || "(none)"}" — pass an explicit mimeType (mime is never sniffed)`,
    "unknown-extension",
  )
}

/**
 * The edge constructor: hand it whatever you actually have and get a path-free part.
 *
 * | given | becomes |
 * |---|---|
 * | a filesystem path | bytes read now, base64d now; mime from the fixed extension table |
 * | native bytes (`Uint8Array`/`Buffer`/`ArrayBuffer`/any `ArrayBufferView`) | base64d now; `mimeType` required |
 * | a `Blob` / `File` | read to bytes **now**; mime from `blob.type`, else the extension table via `File.name` |
 * | `data:<mime>;base64,<b64>` | parsed into `{mimeType, data}` — never stored as a url |
 * | an `http(s):` URL | kept as `url` |
 */
export async function attach(source: AttachSource, opts: AttachOptions = {}): Promise<ContentPart> {
  if (typeof source === "string") {
    if (source.startsWith("data:")) return fromDataUrl(source, opts)
    if (/^https?:\/\//i.test(source)) return fromUrl(source, opts)
    return fromPath(source, opts)
  }
  if (isBlobLike(source)) return fromBlob(source, opts)
  if (!opts.mimeType) {
    throw new ContentPartError("attach(bytes) requires an explicit mimeType — bytes carry no extension to read", "unknown-extension")
  }
  return fromBytes(toBytes(source), opts.mimeType, opts)
}

/**
 * Build a part from a `Blob` or `File`, consuming it **eagerly** — a part holding a half-read
 * stream would not survive the transcript boundary any better than a path does (§1B).
 *
 * Mime comes from `blob.type` when it is non-empty, else from the fixed extension table applied
 * to `File.name`; an explicit `opts.mimeType` beats both. A `File`'s name is carried onto a
 * `file` part unless `opts.name` overrides it.
 */
export async function fromBlob(blob: Blob, opts: AttachOptions = {}): Promise<ContentPart> {
  const name = typeof (blob as any).name === "string" ? String((blob as any).name) : undefined
  const mimeType = opts.mimeType || (blob.type || undefined) || (name ? mediaTypeFor(name)?.mimeType : undefined)
  if (!mimeType) throw unknownExtension(name ?? "")
  const bytes = new Uint8Array(await blob.arrayBuffer())
  return finish(partTypeForMime(mimeType), mimeType, { data: Buffer.from(bytes).toString("base64") }, name ? { name, ...opts } : opts)
}

/** Build a part from in-memory bytes. `mimeType` is required — bytes carry no extension. */
export function fromBytes(bytes: Uint8Array | ArrayBuffer | ArrayBufferView, mimeType: string, opts: AttachOptions = {}): ContentPart {
  const data = Buffer.from(toBytes(bytes)).toString("base64")
  return finish(partTypeForMime(mimeType), mimeType, { data }, opts)
}

/** Read a file now and base64 it now, so the part never carries the path (§1B). */
export async function fromPath(p: string, opts: AttachOptions = {}): Promise<ContentPart> {
  const mimeType = opts.mimeType ?? mediaTypeFor(p)?.mimeType
  if (!mimeType) throw unknownExtension(p)
  const bytes = await fs.readFile(p)
  return finish(partTypeForMime(mimeType), mimeType, { data: bytes.toString("base64") }, { name: path.basename(p), ...opts })
}

/** Parse `data:<mime>;base64,<b64>` into `{mimeType, data}`, so two spellings of the same
 *  bytes cannot diverge downstream. */
export function fromDataUrl(url: string, opts: AttachOptions = {}): ContentPart {
  const m = /^data:([^;,]*)(;base64)?,(.*)$/s.exec(url)
  if (!m) throw new ContentPartError("malformed data: URL", "source-missing")
  const mimeType = opts.mimeType ?? (m[1] || "application/octet-stream")
  const data = m[2] ? m[3] : Buffer.from(decodeURIComponent(m[3]), "utf8").toString("base64")
  return finish(partTypeForMime(mimeType), mimeType, { data }, opts)
}

/** Keep an `http(s):` URL as a url part; mime comes from the table or an explicit override. */
export function fromUrl(url: string, opts: AttachOptions = {}): ContentPart {
  const mimeType = opts.mimeType ?? mediaTypeFor(new URL(url).pathname)?.mimeType
  if (!mimeType) throw unknownExtension(new URL(url).pathname)
  return finish(partTypeForMime(mimeType), mimeType, { url }, opts)
}

function finish(
  type: "image" | "file" | "audio",
  mimeType: string,
  source: { data?: string; url?: string },
  opts: AttachOptions,
): ContentPart {
  const part: any = { type, mimeType, ...source }
  if (type === "file" && opts.name) part.name = opts.name
  return validatePart(part as ContentPart, opts.maxPartBytes)
}

// --------------------------------------------------------------------------- //
// §8A emission — the positive allowlist, and the unsupported-part rule.
// --------------------------------------------------------------------------- //

/** The ONLY block types that may reach each style's wire. A part encoding to anything
 *  else never leaves this module (map-and-hope is how content gets silently discarded). */
const ALLOWLIST: Record<PartStyle, ReadonlySet<string>> = {
  openai: new Set(["text", "image_url", "file", "input_audio"]),
  anthropic: new Set(["text", "image", "document"]),
}

/** OpenAI's `input_audio.format` is a bare format name, not a mime type. */
const AUDIO_FORMATS: Record<string, string> = { "audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/wav": "wav", "audio/x-wav": "wav" }

const dataUrl = (mimeType: string, data: string) => `data:${mimeType};base64,${data}`

/**
 * Encode one part into its provider block, or `undefined` when the style defines no shape
 * for it. Refusals are explicit, never a fall-through: `openai × file+url` (Chat Completions
 * has no URL form for a file) and `anthropic × audio` (the provider defines no audio block).
 */
export function encodePart(part: ContentPart, style: PartStyle): Record<string, any> | undefined {
  if (part.type === "text") return { type: "text", text: part.text }
  if (style === "openai") {
    switch (part.type) {
      case "image":
        return { type: "image_url", image_url: { url: part.data ? dataUrl(part.mimeType, part.data) : part.url } }
      case "file":
        // `file_data` REQUIRES the `data:<mime>;base64,` prefix — a bare base64 string is a 400.
        if (!part.data) return undefined
        return { type: "file", file: { filename: part.name ?? "file", file_data: dataUrl(part.mimeType, part.data) } }
      case "audio":
        if (!part.data) return undefined
        return { type: "input_audio", input_audio: { data: part.data, format: AUDIO_FORMATS[part.mimeType] ?? part.mimeType.replace(/^audio\//, "") } }
    }
  }
  switch (part.type) {
    case "image":
      return part.data
        ? { type: "image", source: { type: "base64", media_type: part.mimeType, data: part.data } }
        : { type: "image", source: { type: "url", url: part.url } }
    case "file":
      return part.data
        ? { type: "document", source: { type: "base64", media_type: part.mimeType, data: part.data } }
        : { type: "document", source: { type: "url", url: part.url } }
    case "audio":
      return undefined // Anthropic defines no audio block — a named refusal, not an oversight.
  }
}

/** Context for {@link encodeParts}: which wire, who asked for the part, and the override. */
export interface EncodeOptions {
  style: PartStyle
  provenance: PartProvenance
  onUnsupportedPart?: UnsupportedPartMode
  maxPartBytes?: number
}

const warnedUnsupported = new Set<string>()

/** Test seam: forget which unsupported (style, type) pairs have already been warned about. */
export function resetUnsupportedWarnings(): void {
  warnedUnsupported.clear()
}

/** The text a degraded (non-failing) unsupported part leaves behind — never silence. */
export function unsupportedPlaceholder(part: ContentPart): string {
  return `[unsupported ${part.type} part (${(part as any).mimeType}, ${partBytes(part)} bytes)]`
}

/** Warn once per (style, type, reason) — the same latch the unsupported-block path uses,
 *  keyed separately (by `reason`, not by the message text) so an oversize warning and a
 *  no-block warning never suppress each other, and a differently-sized oversize part of the
 *  same type doesn't re-trigger the latch. */
function warnUnsupportedOnce(style: PartStyle, type: string, reason: "unsupported" | "too-large", message: string): void {
  const key = `${style}:${type}:${reason}`
  if (warnedUnsupported.has(key)) return
  warnedUnsupported.add(key)
  console.warn(`[toolnexus] ${message}`)
}

/**
 * Encode a list of parts for one style, applying the §8A rules:
 * an **attached** part the style cannot represent raises before any HTTP call; a **derived**
 * part degrades to a text placeholder and warns once. `onUnsupportedPart` overrides both.
 * Every emitted block is asserted against the style's allowlist.
 *
 * `maxPartBytes` (§1B) is enforced here too, at assembly, over EVERY part regardless of
 * provenance — a part that arrived from an MCP server never passed through an edge
 * constructor, so a limit it can walk around is not a limit. Going over follows the exact
 * same provenance split as an unsupported block: an attached part errors, a derived one
 * degrades to the placeholder so a remote server still cannot fail the run.
 */
export function encodeParts(parts: readonly ContentPart[], opts: EncodeOptions): Record<string, any>[] {
  const mode: UnsupportedPartMode = opts.onUnsupportedPart ?? (opts.provenance === "attached" ? "error" : "text")
  const out: Record<string, any>[] = []
  for (const raw of parts) {
    const part = validatePart(raw)
    if (opts.maxPartBytes !== undefined && part.type !== "text") {
      const bytes = partBytes(part)
      if (bytes > opts.maxPartBytes) {
        if (mode === "error") {
          throw new ContentPartError(
            `content part "${part.type}" is ${bytes} decoded bytes, over the maxPartBytes limit of ${opts.maxPartBytes}`,
            "too-large",
          )
        }
        warnUnsupportedOnce(
          opts.style,
          part.type,
          "too-large",
          `content part "${part.type}" is ${bytes} decoded bytes, over the maxPartBytes limit of ${opts.maxPartBytes} — sending a text placeholder`,
        )
        out.push({ type: "text", text: unsupportedPlaceholder(part) })
        continue
      }
    }
    const block = encodePart(part, opts.style)
    if (block && ALLOWLIST[opts.style].has(String(block.type))) {
      out.push(block)
      continue
    }
    if (mode === "error") {
      throw new ContentPartError(
        `provider style "${opts.style}" defines no block for a "${part.type}" content part` +
          (part.type !== "text" ? ` (${part.mimeType})` : ""),
        "unsupported",
      )
    }
    warnUnsupportedOnce(opts.style, part.type, "unsupported", `provider style "${opts.style}" has no block for a "${part.type}" part — sending a text placeholder`)
    out.push({ type: "text", text: unsupportedPlaceholder(part) })
  }
  return out
}

/**
 * Read an inbound OpenAI-shaped content block back into a `ContentPart` (§11). Accepts both
 * a `ContentPart` written literally and the provider-native block the same part encodes to,
 * so a caller's own OpenAI messages translate as faithfully as ours do.
 */
export function inboundPart(block: unknown): ContentPart | undefined {
  if (isContentPart(block)) return block
  if (!block || typeof block !== "object") return undefined
  const b = block as any
  if (b.type === "image_url" && b.image_url?.url) {
    const url = String(b.image_url.url)
    try {
      return url.startsWith("data:") ? fromDataUrl(url) : { type: "image", mimeType: mediaTypeFor(new URL(url).pathname)?.mimeType ?? "image/*", url }
    } catch {
      return undefined
    }
  }
  if (b.type === "file" && typeof b.file?.file_data === "string") {
    const part = fromDataUrl(String(b.file.file_data)) as any
    part.type = "file"
    if (b.file.filename) part.name = String(b.file.filename)
    return part as ContentPart
  }
  if (b.type === "input_audio" && typeof b.input_audio?.data === "string") {
    const fmt = String(b.input_audio.format ?? "")
    const mimeType = fmt === "mp3" ? "audio/mpeg" : fmt === "wav" ? "audio/wav" : `audio/${fmt || "*"}`
    return { type: "audio", mimeType, data: String(b.input_audio.data) }
  }
  return undefined
}
