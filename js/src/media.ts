/**
 * Media generation (SPEC §7G, ADR 0036) — one engine, two faces: typed verbs a host calls and
 * tools (source "media") an LLM calls. Providers are styles: JSON data, never code.
 */
import { readFileSync } from "node:fs"
import type { ContentPart } from "./content.js"
import type { JSONSchema, Tool, ToolContext, ToolResult } from "./types.js"

export type MediaVerb = "image" | "video" | "speech" | "transcribe" | "cloneVoice" | "talking"
export type MediaKind = "image" | "video" | "audio" | "text"

export interface MediaVerbStyle {
  mode: "sync" | "async"
  kind: MediaKind
  start: { method?: string; path: string }
  defaults?: Record<string, unknown>
  args: Record<string, string>
  idPath?: string
  poll?: {
    method?: string
    path: string
    statusPath: string
    done?: string[]
    failed?: string[]
    errorPath?: string
    intervalMs?: number
    timeoutMs?: number
  }
  resultPath: string
}

export interface MediaStyle {
  baseUrl: string
  headers?: Record<string, string>
  errorEnvelope?: { codePath: string; okValues: unknown[]; messagePath?: string }
  verbs: Partial<Record<MediaVerb, MediaVerbStyle>>
}

export interface MediaFile {
  type: "image" | "audio" | "file"
  mimeType: string
  url: string
}

export interface MediaResult {
  files: MediaFile[]
  text?: string
  jobId?: string
  status: string
}

export interface MediaProgress {
  verb: MediaVerb
  jobId: string
  status: string
}

export interface MediaOptions {
  onProgress?: (p: MediaProgress) => void
  /** HTTP transport (same seam as the LLM client's `fetch`). Omit ⇒ global fetch. */
  fetch?: typeof fetch
}

/** Per-call options of the function face. */
export interface MediaCallOptions {
  /** Top-level keys overwrite the request body last. Never exposed on the tool face. */
  providerOptions?: Record<string, unknown>
  signal?: AbortSignal
}

export type ImageArgs = { prompt: string; width?: number; height?: number; aspectRatio?: string; seed?: number }
export type VideoArgs = { prompt: string; image?: string; seconds?: number; aspectRatio?: string }
export type SpeechArgs = { text: string; voice?: string }
export type TranscribeArgs = { audio: string }
export type CloneVoiceArgs = { name: string; samples: string[] }
export type TalkingArgs = { image: string; audio: string }

export interface MediaVerbArg {
  name: string
  type: string
  items?: string
  description: string
  required?: boolean
}
export interface MediaVerbSpec {
  verb: MediaVerb
  tool: string
  description: string
  args: MediaVerbArg[]
}

/** Canonical verbs — `conformance/media/verbs.json`, embedded verbatim (byte contract). */
export const MEDIA_VERBS: MediaVerbSpec[] = [
  {
    "verb": "image",
    "tool": "generate_image",
    "description": "Generate an image from a text prompt.",
    "args": [
      {
        "name": "prompt",
        "type": "string",
        "description": "What to draw.",
        "required": true
      },
      {
        "name": "width",
        "type": "integer",
        "description": "Width in pixels."
      },
      {
        "name": "height",
        "type": "integer",
        "description": "Height in pixels."
      },
      {
        "name": "aspectRatio",
        "type": "string",
        "description": "Aspect ratio, e.g. 16:9."
      },
      {
        "name": "seed",
        "type": "integer",
        "description": "Seed for reproducible output."
      }
    ]
  },
  {
    "verb": "video",
    "tool": "generate_video",
    "description": "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
    "args": [
      {
        "name": "prompt",
        "type": "string",
        "description": "What should happen in the video.",
        "required": true
      },
      {
        "name": "image",
        "type": "string",
        "description": "URL of the starting image."
      },
      {
        "name": "seconds",
        "type": "integer",
        "description": "Video length in seconds."
      },
      {
        "name": "aspectRatio",
        "type": "string",
        "description": "Aspect ratio, e.g. 9:16."
      }
    ]
  },
  {
    "verb": "speech",
    "tool": "generate_speech",
    "description": "Speak text aloud and return the audio.",
    "args": [
      {
        "name": "text",
        "type": "string",
        "description": "The text to speak.",
        "required": true
      },
      {
        "name": "voice",
        "type": "string",
        "description": "Voice or speaker id."
      }
    ]
  },
  {
    "verb": "transcribe",
    "tool": "transcribe_audio",
    "description": "Transcribe speech in an audio URL to text.",
    "args": [
      {
        "name": "audio",
        "type": "string",
        "description": "URL of the audio.",
        "required": true
      }
    ]
  },
  {
    "verb": "cloneVoice",
    "tool": "clone_voice",
    "description": "Create a cloned voice from audio sample URLs and return its voice id.",
    "args": [
      {
        "name": "name",
        "type": "string",
        "description": "Name for the new voice.",
        "required": true
      },
      {
        "name": "samples",
        "type": "array",
        "items": "string",
        "description": "URLs of audio samples of the voice.",
        "required": true
      }
    ]
  },
  {
    "verb": "talking",
    "tool": "talking_video",
    "description": "Make the person in an image speak the given audio (lip-synced video).",
    "args": [
      {
        "name": "image",
        "type": "string",
        "description": "URL of the face image.",
        "required": true
      },
      {
        "name": "audio",
        "type": "string",
        "description": "URL of the speech audio.",
        "required": true
      }
    ]
  }
]

/** Read one style file (JSON). */
export function loadMediaStyle(path: string): MediaStyle {
  return JSON.parse(readFileSync(path, "utf8")) as MediaStyle
}

// ---- dotPath ---------------------------------------------------------------------------------

const isIndex = (seg: string) => /^[0-9]+$/.test(seg)

function readPath(obj: unknown, path: string): unknown {
  let cur: any = obj
  for (const seg of path.split(".")) {
    if (cur === null || cur === undefined || typeof cur !== "object") return undefined
    if (Array.isArray(cur)) {
      if (!isIndex(seg)) return undefined
      cur = cur[Number(seg)]
    } else {
      if (!Object.prototype.hasOwnProperty.call(cur, seg)) return undefined
      cur = cur[seg]
    }
  }
  return cur
}

function writePath(obj: Record<string, unknown>, path: string, value: unknown): void {
  const segs = path.split(".")
  let cur: any = obj
  for (let i = 0; i < segs.length; i++) {
    const seg = segs[i]
    const key: string | number = Array.isArray(cur) && isIndex(seg) ? Number(seg) : seg
    if (Array.isArray(cur) && typeof key === "number") while (cur.length < key) cur.push(null)
    if (i === segs.length - 1) {
      cur[key] = value
      return
    }
    let next = cur[key]
    if (next === null || next === undefined || typeof next !== "object") {
      next = isIndex(segs[i + 1]) ? [] : {}
      cur[key] = next
    }
    cur = next
  }
}

// ---- helpers ---------------------------------------------------------------------------------

function expandEnv(v: string): string {
  return v.replace(/\$\{([A-Za-z0-9_]+)\}/g, (_, name) => process.env[name] ?? "")
}

const MIME: Record<string, string> = {
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif",
  mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime",
  mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg", m4a: "audio/mp4", flac: "audio/flac",
}
const KIND_DEFAULT_MIME: Record<string, string> = { image: "image/png", video: "video/mp4", audio: "audio/mpeg" }
const KIND_FILE_TYPE: Record<string, MediaFile["type"]> = { image: "image", audio: "audio", video: "file" }

function mimeFor(url: string, kind: MediaKind): string {
  let path = url.split("#")[0].split("?")[0]
  const scheme = path.indexOf("://")
  if (scheme >= 0) {
    const slash = path.indexOf("/", scheme + 3)
    path = slash >= 0 ? path.slice(slash) : ""
  }
  const last = path.slice(path.lastIndexOf("/") + 1)
  const dot = last.lastIndexOf(".")
  const ext = dot >= 0 ? last.slice(dot + 1).toLowerCase() : ""
  return MIME[ext] ?? KIND_DEFAULT_MIME[kind]
}

function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== typeof b || a === null || b === null || typeof a !== "object") return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  if (Array.isArray(a)) {
    const bb = b as unknown[]
    return a.length === bb.length && a.every((x, i) => jsonEqual(x, bb[i]))
  }
  const ka = Object.keys(a as object), kb = Object.keys(b as object)
  return ka.length === kb.length && ka.every((k) => jsonEqual((a as any)[k], (b as any)[k]))
}

function renderScalar(v: unknown): string {
  if (typeof v === "string") return v
  if (typeof v === "number" && Number.isInteger(v)) return String(v)
  return JSON.stringify(v)
}

function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? Object.assign(new Error("The operation was aborted"), { name: "AbortError" })
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(abortError(signal))
    const onAbort = () => {
      clearTimeout(t)
      reject(abortError(signal!))
    }
    const t = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort)
      resolve()
    }, ms)
    signal?.addEventListener("abort", onAbort, { once: true })
  })
}

function flattenStrings(v: unknown, out: string[]): void {
  if (typeof v === "string") {
    if (v !== "") out.push(v)
  } else if (Array.isArray(v)) {
    for (const x of v) flattenStrings(x, out)
  }
}

// ---- engine ----------------------------------------------------------------------------------

export class Media {
  private readonly styles: MediaStyle[]
  constructor(styles: MediaStyle | MediaStyle[], private readonly opts: MediaOptions = {}) {
    this.styles = Array.isArray(styles) ? styles : [styles]
  }

  private resolve(verb: string): { style: MediaStyle; spec: MediaVerbStyle } | undefined {
    for (const style of this.styles) {
      const spec = (style.verbs as Record<string, MediaVerbStyle | undefined>)[verb]
      if (spec) return { style, spec }
    }
    return undefined
  }

  /** Generic entry point: run `verb` with normalised `args` (§7G engine). */
  async generate(verb: MediaVerb | string, args: Record<string, unknown>, callOpts: MediaCallOptions = {}): Promise<MediaResult> {
    const resolved = this.resolve(verb)
    if (!resolved) throw new Error(`media: no style supports "${verb}"`)
    const { style, spec } = resolved
    const present = Object.entries(args ?? {}).filter(([, v]) => v !== undefined && v !== null)
    for (const key of present.map(([k]) => k).sort()) {
      if (!Object.prototype.hasOwnProperty.call(spec.args, key)) {
        throw new Error(`${verb}: argument "${key}" not supported by this style`)
      }
    }
    const body: Record<string, unknown> = spec.defaults ? structuredClone(spec.defaults) : {}
    for (const [key, value] of present) writePath(body, spec.args[key], value)
    for (const [k, v] of Object.entries(callOpts.providerOptions ?? {})) body[k] = v

    const base = style.baseUrl.replace(/\/+$/, "")
    const signal = callOpts.signal
    const styleHeaders = (): Record<string, string> => {
      const h: Record<string, string> = {}
      for (const [k, v] of Object.entries(style.headers ?? {})) h[k] = expandEnv(v)
      return h
    }
    const doFetch = this.opts.fetch ?? fetch
    const call = async (method: string, path: string, json?: unknown): Promise<unknown> => {
      if (signal?.aborted) throw abortError(signal)
      const headers: Record<string, string> = json !== undefined ? { "Content-Type": "application/json", ...styleHeaders() } : styleHeaders()
      let res: Response, text: string
      try {
        res = await doFetch(base + path, { method, headers, body: json !== undefined ? JSON.stringify(json) : undefined, signal })
        text = await res.text()
      } catch (e) {
        if (signal?.aborted) throw abortError(signal)
        throw new Error(`${verb}: ${e instanceof Error ? e.message : String(e)}`)
      }
      if (res.status < 200 || res.status > 299) throw new Error(`HTTP ${res.status}: ${text}`)
      let data: unknown
      try {
        data = JSON.parse(text)
      } catch {
        throw new Error(`${verb}: response is not JSON`)
      }
      const env = style.errorEnvelope
      if (env) {
        const code = readPath(data, env.codePath)
        if (code !== undefined && !env.okValues.some((ok) => jsonEqual(ok, code))) {
          const msg = env.messagePath ? readPath(data, env.messagePath) : undefined
          throw new Error(`API error ${renderScalar(code)}: ${typeof msg === "string" ? msg : ""}`)
        }
      }
      return data
    }

    let final: unknown
    let status = "completed"
    let jobId: string | undefined
    const startResp = await call((spec.start.method ?? "POST").toUpperCase(), spec.start.path, body)
    if (spec.mode === "async") {
      const poll = spec.poll!
      const idPath = spec.idPath ?? ""
      const rawId = readPath(startResp, idPath)
      if (typeof rawId === "string") jobId = rawId
      else if (typeof rawId === "number" && Number.isInteger(rawId)) jobId = String(rawId)
      else throw new Error(`${verb}: no job id at ${idPath}`)
      const interval = poll.intervalMs ?? 5000
      const timeout = poll.timeoutMs ?? 600000
      const began = Date.now()
      for (;;) {
        await sleep(interval, signal)
        const resp = await call((poll.method ?? "GET").toUpperCase(), poll.path.split("{id}").join(jobId))
        const st = readPath(resp, poll.statusPath)
        const s = typeof st === "string" ? st : ""
        this.opts.onProgress?.({ verb: verb as MediaVerb, jobId, status: s })
        if ((poll.done ?? []).includes(s)) {
          final = resp
          status = s
          break
        }
        if ((poll.failed ?? []).includes(s)) {
          const m = poll.errorPath ? readPath(resp, poll.errorPath) : undefined
          throw new Error(typeof m === "string" && m !== "" ? `job ${jobId} ${s}: ${m}` : `job ${jobId} ${s}`)
        }
        if (Date.now() - began >= timeout) throw new Error(`job ${jobId} timed out after ${timeout}ms`)
      }
    } else {
      final = startResp
    }

    const value = readPath(final, spec.resultPath)
    const result: MediaResult = { files: [], status }
    if (spec.kind === "text") {
      if (typeof value === "string") result.text = value
      else if (typeof value === "number") result.text = renderScalar(value)
      else throw new Error(`${verb}: no result at ${spec.resultPath}`)
    } else {
      const urls: string[] = []
      flattenStrings(value, urls)
      if (urls.length === 0) throw new Error(`${verb}: no result at ${spec.resultPath}`)
      result.files = urls.map((url) => ({ type: KIND_FILE_TYPE[spec.kind], mimeType: mimeFor(url, spec.kind), url }))
    }
    if (jobId !== undefined) result.jobId = jobId
    return result
  }

  private typed(verb: MediaVerb, input: object & MediaCallOptions): Promise<MediaResult> {
    const { providerOptions, signal, ...args } = input as Record<string, unknown> & MediaCallOptions
    return this.generate(verb, args, { providerOptions, signal })
  }

  image(input: ImageArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("image", input)
  }
  video(input: VideoArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("video", input)
  }
  speech(input: SpeechArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("speech", input)
  }
  transcribe(input: TranscribeArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("transcribe", input)
  }
  cloneVoice(input: CloneVoiceArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("cloneVoice", input)
  }
  talking(input: TalkingArgs & MediaCallOptions): Promise<MediaResult> {
    return this.typed("talking", input)
  }

  /** One Tool (source "media") per supported verb, in canonical order (§7G tool face). */
  tools(): Tool[] {
    const out: Tool[] = []
    for (const v of MEDIA_VERBS) {
      const resolved = this.resolve(v.verb)
      if (!resolved) continue
      const mapped = v.args.filter((a) => Object.prototype.hasOwnProperty.call(resolved.spec.args, a.name))
      const properties: Record<string, unknown> = {}
      for (const a of mapped) {
        properties[a.name] = a.type === "array"
          ? { type: "array", items: { type: a.items }, description: a.description }
          : { type: a.type, description: a.description }
      }
      const inputSchema: JSONSchema = { type: "object", properties, required: mapped.filter((a) => a.required).map((a) => a.name) }
      const kind = resolved.spec.kind
      const mode = resolved.spec.mode
      out.push({
        name: v.tool,
        description: v.description,
        inputSchema,
        source: "media",
        execute: async (args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> => {
          try {
            const r = await this.generate(v.verb, args ?? {}, { signal: ctx?.signal })
            const metadata: Record<string, unknown> = { source: "media", verb: v.verb }
            if (mode === "async" && r.jobId !== undefined) metadata.jobId = r.jobId
            metadata.status = r.status
            if (kind === "text") return { isError: false, output: r.text ?? "", metadata }
            const parts: ContentPart[] = r.files.map((f) => ({ type: f.type, mimeType: f.mimeType, url: f.url }))
            return { isError: false, output: `${kind} ready: ` + r.files.map((f) => f.url).join("\n"), parts, metadata }
          } catch (e) {
            return { isError: true, output: e instanceof Error ? e.message : String(e) }
          }
        },
      })
    }
    return out
  }
}

/** Build a media engine over one style or a list (first style declaring a verb wins). */
export function createMedia(styles: MediaStyle | MediaStyle[], opts: MediaOptions = {}): Media {
  return new Media(styles, opts)
}
