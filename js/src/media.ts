/**
 * Media generation (SPEC §7G, ADRs 0036 + 0037) — one interface, two faces: typed verbs a host
 * calls and tools (source "media") an LLM calls. Providers are **media clients**: built-in API
 * clients (a2e, ElevenLabs, OpenAI, OpenRouter, fal), CLI clients (Grok, Codex), style clients
 * over the JSON style engine, or any object implementing {@link MediaClient}.
 */
import { spawn } from "node:child_process"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import readline from "node:readline"
import { fileURLToPath } from "node:url"
import type { ContentPart } from "./content.js"
import type { JSONSchema, Tool, ToolContext, ToolResult } from "./types.js"

// ---- verbs -----------------------------------------------------------------------------------

export type MediaVerb = "image" | "video" | "speech" | "music" | "transcribe" | "cloneVoice" | "talk"
export type MediaKind = "image" | "video" | "audio" | "text"

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
    "description": "Generate a short video from a prompt, optionally starting from an image. Can take minutes.",
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
        "description": "URL or local path of the starting image."
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
        "description": "Voice name or id, including a cloned voice."
      }
    ]
  },
  {
    "verb": "music",
    "tool": "generate_music",
    "description": "Generate music from a text prompt and return the audio.",
    "args": [
      {
        "name": "prompt",
        "type": "string",
        "description": "Style, mood and instruments of the music.",
        "required": true
      },
      {
        "name": "seconds",
        "type": "integer",
        "description": "Length in seconds."
      },
      {
        "name": "instrumental",
        "type": "boolean",
        "description": "True for no vocals."
      },
      {
        "name": "lyrics",
        "type": "string",
        "description": "Lyrics to sing."
      }
    ]
  },
  {
    "verb": "transcribe",
    "tool": "transcribe_audio",
    "description": "Transcribe speech in an audio file to text.",
    "args": [
      {
        "name": "audio",
        "type": "string",
        "description": "URL or local path of the audio.",
        "required": true
      },
      {
        "name": "language",
        "type": "string",
        "description": "Language of the speech as an ISO 639-1 code, e.g. en."
      }
    ]
  },
  {
    "verb": "cloneVoice",
    "tool": "clone_voice",
    "description": "Create a cloned voice from audio samples and return its voice id.",
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
        "description": "URLs or local paths of audio samples of the voice.",
        "required": true
      }
    ]
  },
  {
    "verb": "talk",
    "tool": "talking_video",
    "description": "Make the person in an image speak, from speech audio or from text (lip-synced video).",
    "args": [
      {
        "name": "image",
        "type": "string",
        "description": "URL or local path of the face image.",
        "required": true
      },
      {
        "name": "audio",
        "type": "string",
        "description": "URL or local path of the speech audio."
      },
      {
        "name": "text",
        "type": "string",
        "description": "Text to speak when no audio is given."
      },
      {
        "name": "voice",
        "type": "string",
        "description": "Voice for the text."
      }
    ]
  }
]

const VERB_SPEC = new Map(MEDIA_VERBS.map((v) => [v.verb as string, v]))
const VERB_KIND: Record<MediaVerb, MediaKind> = {
  image: "image", video: "video", speech: "audio", music: "audio", transcribe: "text", cloneVoice: "text", talk: "video",
}

// ---- public shapes ---------------------------------------------------------------------------

export interface MediaFile {
  type: "image" | "audio" | "file"
  mimeType: string
  /** `http(s)` URL, or `file://` + `path` for a local file. */
  url?: string
  /** Absolute local path (download mode, or a CLI's own file in url mode). */
  path?: string
  /** Standard padded base64 (inline mode). */
  data?: string
}

export type MediaResultStatus = "completed" | "failed" | "timeout" | "cancelled" | "unavailable" | (string & {})

export interface MediaResult {
  files: MediaFile[]
  text?: string
  jobId?: string
  status: MediaResultStatus
  /** Provider-reported usage only, never estimated (§7G.12 D21). */
  usage?: MediaUsage
  /** Who served the call: the preset id, the HTTP style's name (else "style"), or the client's name (§7G.10b). */
  style?: string
  /** The configured model for the verb of a loaded style, when one was set; never guessed. */
  model?: string
}

/** §7G.12 D21: what the provider reported; absent fields are absent. */
export interface MediaUsage {
  inputTokens?: number
  outputTokens?: number
  costUsd?: number
  raw?: Record<string, unknown>
}

export interface MediaProgress {
  verb: MediaVerb
  client: string
  jobId?: string
  status: string
  elapsedMs: number
}

/** Per-call context (§7G.2). */
export interface MediaContext {
  signal?: AbortSignal
  /** Absolute deadline (epoch ms or Date); the job ends at the earlier of it and `loop.timeoutMs`. */
  deadline?: number | Date
  /** Called for every progress status; when absent the client's `loop.onProgress` is used. */
  progress?: (p: MediaProgress) => void
  /** Function face only: top-level keys overwrite the request body last. */
  providerOptions?: Record<string, unknown>
}

/** Per-call options of the typed verbs (`media.image({ prompt, signal })`). */
export type MediaCallOptions = MediaContext

export interface MediaArgSpec {
  name: string
  required: boolean
}

/** Any media provider (§7G.2). Every verb is optional; `supports(verb)` is the truth. */
export interface MediaClient {
  readonly name: string
  supports(verb: MediaVerb | string): boolean
  /** Accepted canonical args; absent ⇒ every canonical arg of the verb, canonical required. */
  args?(verb: MediaVerb | string): MediaArgSpec[]
  image?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  video?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  speech?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  music?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  transcribe?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  cloneVoice?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
  talk?(args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult>
}

export type MediaErrorStatus = "failed" | "timeout" | "cancelled" | "unavailable"

/** A failed media verb (§7G.2): message, status and the job id when known. */
export class MediaError extends Error {
  readonly status: MediaErrorStatus
  jobId?: string
  constructor(message: string, status: MediaErrorStatus = "failed", jobId?: string) {
    super(message)
    this.name = "MediaError"
    this.status = status
    if (jobId !== undefined) this.jobId = jobId
  }
}

/** The client cannot serve the call now (no credits, quota, no key, CLI missing, ZDR …). */
export class MediaUnavailableError extends MediaError {
  constructor(message: string, jobId?: string) {
    super(message, "unavailable", jobId)
    this.name = "MediaUnavailableError"
  }
}

export type GatherMode = "url" | "download" | "inline"

/** One config shape for every built-in client (§7G.4). */
export interface MediaClientConfig {
  /** Model of the client's primary verb (CLI clients: `-m <model>`). */
  model?: string
  /** Per-verb model override; wins over `model` and the default. */
  models?: Partial<Record<MediaVerb, string>>
  /** API key; default the client's env var, read at call time. Never logged. */
  key?: string
  /** Base URL override (any compatible server). */
  url?: string
  /** CLI argv prefix (a string is one executable, never shell-split). */
  command?: string | string[]
  loop?: { intervalMs?: number; timeoutMs?: number; onProgress?: (p: MediaProgress) => void }
  /**
   * `name`, when set, names saved files `<name><ext>`, then `<name>-2<ext>`, … (first free name);
   * sanitised to `[A-Za-z0-9._-]`, leading `.`/`-` dropped; empty ⇒ the default naming (§7G.10b).
   */
  gather?: { mode?: GatherMode; dir?: string; maxBytes?: number; keep?: boolean; name?: string }
  /** HTTP transport (the same seam as the LLM client's `fetch`). Omit ⇒ global fetch. */
  fetch?: typeof fetch
}

// ---- style format ----------------------------------------------------------------------------

export interface MediaVerbStyle {
  mode: "sync" | "async"
  kind: MediaKind
  start: { method?: string; path: string }
  defaults?: Record<string, unknown>
  args: Record<string, string>
  required?: string[]
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
  name?: string
  baseUrl: string
  headers?: Record<string, string>
  errorEnvelope?: { codePath: string; okValues: unknown[]; messagePath?: string }
  verbs: Partial<Record<MediaVerb, MediaVerbStyle>>
}

/** The a2e.ai preset — `examples/media-styles/a2e.json`, embedded verbatim. */
export const A2E_STYLE: MediaStyle & { _doc?: string } = {
  "name": "a2e",
  "baseUrl": "https://api.a2e.ai/api",
  "headers": {
    "Authorization": "Bearer ${A2E_API_KEY}"
  },
  "verbs": {
    "image": {
      "mode": "async",
      "kind": "image",
      "start": {
        "method": "POST",
        "path": "/v1/userText2Image/start"
      },
      "defaults": {
        "name": "toolnexus",
        "model_type": "a2e"
      },
      "args": {
        "prompt": "prompt",
        "width": "width",
        "height": "height",
        "seed": "seed"
      },
      "idPath": "data.0._id",
      "poll": {
        "path": "/v1/userText2Image/{id}",
        "statusPath": "data.current_status",
        "done": [
          "completed"
        ],
        "failed": [
          "failed",
          "blocked",
          "canceled"
        ],
        "errorPath": "data.failed_message",
        "intervalMs": 3000,
        "timeoutMs": 300000
      },
      "resultPath": "data.image_urls"
    },
    "video": {
      "mode": "async",
      "kind": "video",
      "start": {
        "method": "POST",
        "path": "/v1/userImage2Video/start"
      },
      "defaults": {
        "name": "toolnexus",
        "generation_mode": "image-to-video",
        "negative_prompt": "blurry, distorted, low quality"
      },
      "args": {
        "image": "image_url",
        "prompt": "prompt",
        "seconds": "video_time"
      },
      "idPath": "data._id",
      "poll": {
        "path": "/v1/userImage2Video/{id}",
        "statusPath": "data.current_status",
        "done": [
          "completed"
        ],
        "failed": [
          "failed",
          "blocked",
          "canceled"
        ],
        "errorPath": "data.failed_message",
        "intervalMs": 5000,
        "timeoutMs": 900000
      },
      "resultPath": "data.result_url"
    },
    "speech": {
      "mode": "async",
      "kind": "audio",
      "start": {
        "method": "POST",
        "path": "/v1/seedAudio/start"
      },
      "args": {
        "text": "text_prompt",
        "voice": "references.0.speaker"
      },
      "idPath": "data._id",
      "poll": {
        "path": "/v1/seedAudio/{id}",
        "statusPath": "data.current_status",
        "done": [
          "completed"
        ],
        "failed": [
          "failed"
        ],
        "errorPath": "data.failed_message",
        "intervalMs": 3000,
        "timeoutMs": 300000
      },
      "resultPath": "data.result_url"
    },
    "talk": {
      "mode": "async",
      "kind": "video",
      "start": {
        "method": "POST",
        "path": "/v1/talkingPhoto/start"
      },
      "defaults": {
        "name": "toolnexus"
      },
      "args": {
        "image": "image_url",
        "audio": "audio_url"
      },
      "required": [
        "audio"
      ],
      "idPath": "data._id",
      "poll": {
        "path": "/v1/talkingPhoto/{id}",
        "statusPath": "data.current_status",
        "done": [
          "completed"
        ],
        "failed": [
          "failed",
          "blocked",
          "canceled"
        ],
        "errorPath": "data.failed_message",
        "intervalMs": 5000,
        "timeoutMs": 900000
      },
      "resultPath": "data.result_url"
    }
  },
  "errorEnvelope": {
    "codePath": "code",
    "okValues": [
      0
    ],
    "messagePath": "message"
  },
  "_doc": "a2e.ai preset (ADR 0036/0037). Embedded verbatim by every port as A2EMediaClient (SPEC §7G.9). Live-verified 2026-11-02: image, video, speech. talk is implemented per a2e's docs, UNVERIFIED. Set A2E_API_KEY. Result URLs expire (3-30 days); the built-in client downloads them by default."
} as MediaStyle

/**
 * Built-in CLI presets for {@link loadMediaStyle} (ADR 0042): media through a locally installed,
 * logged-in CLI: the vendor's official CLI in its documented headless mode, signed in by the user; the provider's terms apply. The `*Mock` twins behave the same
 * (name, verbs, args, prompt folding, errors) but never start a process and return a fixed file.
 */
export const MediaStyle = {
  /** The `grok` CLI: image and video. */
  XaiGrokCli: "xai-grok-cli",
  /** The `codex` CLI: image only. */
  OpenAICodexCli: "openai-codex-cli",
  /** Google's Antigravity CLI `agy`: image only. */
  GoogleAgyCli: "google-agy-cli",
  XaiGrokCliMock: "xai-grok-cli-mock",
  OpenAICodexCliMock: "openai-codex-cli-mock",
  GoogleAgyCliMock: "google-agy-cli-mock",
} as const

/** A built-in CLI preset name. */
export type MediaStylePreset = (typeof MediaStyle)[keyof typeof MediaStyle]

const CLI_PRESETS: Record<string, "grok" | "codex" | "agy"> = {
  "xai-grok-cli": "grok", "openai-codex-cli": "codex", "google-agy-cli": "agy",
  "xai-grok-cli-mock": "grok", "openai-codex-cli-mock": "codex", "google-agy-cli-mock": "agy",
}

/**
 * A loaded style (§7G.7): an HTTP style file or a built-in CLI preset, configured and usable as a
 * {@link MediaClient} anywhere one is accepted — `createMedia(loadMediaStyle("xai-grok-cli"))`.
 */
export class LoadedMediaStyle implements MediaClient {
  readonly name: string
  /** The preset name, when this is a built-in CLI preset. */
  readonly preset?: MediaStylePreset
  /** The parsed style, when this is an HTTP style. */
  readonly style?: MediaStyle
  /** The config the style was loaded with. */
  readonly config: MediaClientConfig
  private readonly client: MediaClient

  constructor(source: MediaStyle | MediaStylePreset, config: MediaClientConfig = {}) {
    this.config = config
    if (typeof source === "string") {
      const label = CLI_PRESETS[source]
      if (label === undefined) throw new Error(`style: unknown preset "${source}"`)
      this.preset = source
      const mock = { mock: source.endsWith("-mock") }
      this.client = label === "grok" ? new CliMediaClient(GROK_SPEC, config, mock)
        : label === "codex" ? new CliMediaClient(CODEX_SPEC, config, mock)
        : new CliMediaClient(AGY_SPEC, config, mock)
    } else {
      this.style = source
      this.client = new StyleMediaClient(source, config)
    }
    this.name = this.client.name
  }

  supports(verb: MediaVerb | string): boolean { return this.client.supports(verb) }

  /** Who serves calls: the preset id, else the HTTP style's name, else "style" (§7G.10b). */
  get id(): string {
    return this.preset ?? this.style?.name ?? "style"
  }

  /**
   * Can this style run? Cheap and side-effect free (§7G.10b): a CLI preset's command is found and
   * its own login file exists (never read); an HTTP style's config key or header `${VAR}`s are set.
   */
  available(): MediaAvailability {
    if (this.preset === undefined) {
      const id = this.id
      if (this.config.key) return { installed: true, loggedIn: true, reason: `${id}: key from config` }
      const vars = new Set<string>()
      for (const v of Object.values(this.style?.headers ?? {})) for (const m of v.matchAll(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) vars.add(m[1])
      for (const name of [...vars].sort(byCodePoint)) {
        if (!process.env[name]) return { installed: true, loggedIn: false, reason: `${id}: ${name} is not set` }
      }
      return { installed: true, loggedIn: true, reason: `${id}: env present` }
    }
    const label = CLI_PRESETS[this.preset]
    if (this.preset.endsWith("-mock")) {
      if (process.env[MEDIA_MOCK_AVAILABLE_ENV] === "0") {
        return { installed: false, loggedIn: false, reason: `${label}: mock set unavailable by ${MEDIA_MOCK_AVAILABLE_ENV}=0` }
      }
      return { installed: true, loggedIn: true, reason: `${label}: mock` }
    }
    const cmd = this.config.command
    const bin = (Array.isArray(cmd) ? cmd[0] : cmd) || label
    if (!findCommand(bin)) return { installed: false, loggedIn: false, reason: `${label}: ${JSON.stringify(bin)} not found on PATH` }
    const auth = cliAuthFile(label)
    let present = false
    try {
      present = !fs.statSync(auth).isDirectory()
    } catch {
      present = false
    }
    return present
      ? { installed: true, loggedIn: true, reason: `${label}: installed, logged in (${auth} present)` }
      : { installed: true, loggedIn: false, reason: `${label}: installed, not logged in (${auth} missing)` }
  }

  args(verb: MediaVerb | string): MediaArgSpec[] { return acceptedArgs(this.client, verb) }

  private call(verb: MediaVerb, args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult> {
    const fn = (this.client as any)[verb] as ((a: Record<string, unknown>, x: MediaContext) => Promise<MediaResult>) | undefined
    if (typeof fn !== "function") return Promise.reject(new MediaError(`media: no style supports "${verb}"`))
    return fn.call(this.client, args, ctx)
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("image", args, ctx) }
  video(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("video", args, ctx) }
  speech(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("speech", args, ctx) }
  music(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("music", args, ctx) }
  transcribe(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("transcribe", args, ctx) }
  cloneVoice(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("cloneVoice", args, ctx) }
  talk(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.call("talk", args, ctx) }
}

/** The cheap pre-flight answer of {@link LoadedMediaStyle.available} (§7G.10b). */
export interface MediaAvailability {
  installed: boolean
  loggedIn: boolean
  reason: string
}

/** Set to "0", every mock preset reports unavailable. */
export const MEDIA_MOCK_AVAILABLE_ENV = "TOOLNEXUS_MEDIA_MOCK_AVAILABLE"

function homeDir(): string {
  return process.env.HOME || os.homedir()
}

/** The file each CLI writes when logged in (checked for existence only). */
function cliAuthFile(label: string): string {
  if (label === "grok") return path.join(homeDir(), ".grok", "auth.json")
  if (label === "codex") return path.join(process.env.CODEX_HOME || path.join(homeDir(), ".codex"), "auth.json")
  return path.join(homeDir(), ".gemini", "antigravity-cli", "antigravity-oauth-token")
}

/** A name with a path separator is checked as a file; otherwise searched on PATH (executable). */
function findCommand(bin: string): boolean {
  const exec = (p: string) => {
    try {
      if (!fs.statSync(p).isFile()) return false
      fs.accessSync(p, fs.constants.X_OK)
      return true
    } catch {
      return false
    }
  }
  if (bin.includes("/") || bin.includes(path.sep)) return exec(bin)
  const exts = process.platform === "win32" ? (process.env.PATHEXT ?? ".EXE;.CMD;.BAT").split(";") : [""]
  for (const dir of (process.env.PATH ?? "").split(path.delimiter)) {
    if (dir === "") continue
    for (const x of exts) if (exec(path.join(dir, bin + x))) return true
  }
  return false
}

/**
 * Load a style: a built-in CLI preset ({@link MediaStyle}`.XaiGrokCli`, …) or a style JSON file
 * (throws the §7G.7 style errors). `config` configures the client behind it (model, command, loop, gather).
 */
export function loadMediaStyle(pathOrPreset: MediaStylePreset | string, config: MediaClientConfig = {}): LoadedMediaStyle {
  if (Object.prototype.hasOwnProperty.call(CLI_PRESETS, pathOrPreset)) return new LoadedMediaStyle(pathOrPreset as MediaStylePreset, config)
  const style = JSON.parse(fs.readFileSync(pathOrPreset, "utf8")) as MediaStyle
  validateStyle(style)
  return new LoadedMediaStyle(style, config)
}

function validateStyle(style: MediaStyle): void {
  for (const verb of Object.keys(style.verbs ?? {}).sort()) {
    if (!VERB_SPEC.has(verb)) throw new Error(`style: unknown verb "${verb}"`)
    const args = (style.verbs as Record<string, MediaVerbStyle>)[verb]?.args ?? {}
    for (const key of Object.keys(args).sort()) {
      if (args[key] === "") throw new Error(`style: empty path for argument "${key}"`)
    }
  }
}

// ---- dotPath ---------------------------------------------------------------------------------

const isIndex = (seg: string) => /^[0-9]+$/.test(seg)

function readPath(obj: unknown, p: string): unknown {
  if (p === "") return obj
  let cur: any = obj
  for (const seg of p.split(".")) {
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

function writePath(obj: Record<string, unknown>, p: string, value: unknown): void {
  const segs = p.split(".")
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
    if (next === null || next === undefined || typeof next !== "object" || (Array.isArray(next) && !isIndex(segs[i + 1]))) {
      next = isIndex(segs[i + 1]) ? [] : {}
      cur[key] = next
    }
    cur = next
  }
}

// ---- helpers ---------------------------------------------------------------------------------

const MIME: Record<string, string> = {
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", webp: "image/webp", gif: "image/gif",
  mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime",
  mp3: "audio/mpeg", wav: "audio/wav", ogg: "audio/ogg", m4a: "audio/mp4", flac: "audio/flac",
}
const EXT: Record<string, string> = {
  "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif",
  "video/mp4": ".mp4", "video/webm": ".webm", "video/quicktime": ".mov",
  "audio/mpeg": ".mp3", "audio/wav": ".wav", "audio/x-wav": ".wav", "audio/wave": ".wav", "audio/ogg": ".ogg",
  "audio/mp4": ".m4a", "audio/flac": ".flac", "audio/aac": ".aac", "text/plain": ".txt",
}

/** §7G.12 D23: `toolnexus/<version> (js)`, the version read from this package's manifest. */
const USER_AGENT = (() => {
  let version = "0.0.0"
  try {
    version = JSON.parse(fs.readFileSync(fileURLToPath(new URL("../package.json", import.meta.url)), "utf8")).version ?? version
  } catch {
    // keep the fallback
  }
  return `toolnexus/${version} (js)`
})()

/** §7G.12 D22: a URL path's own extension (1–5 ASCII letters/digits), lower-cased, with the dot. */
function urlExt(u: string): string | undefined {
  const e = extOf(u)
  return /^[a-z0-9]{1,5}$/.test(e) ? "." + e : undefined
}
const KIND_DEFAULT_MIME: Record<string, string> = { image: "image/png", video: "video/mp4", audio: "audio/mpeg" }
const KIND_FILE_TYPE: Record<string, MediaFile["type"]> = { image: "image", audio: "audio", video: "file" }

/** Extension of the last path segment of a URL or path (query/fragment stripped), lower-case. */
function extOf(u: string): string {
  let p = u.split("#")[0].split("?")[0]
  const scheme = p.indexOf("://")
  if (scheme >= 0) {
    const slash = p.indexOf("/", scheme + 3)
    p = slash >= 0 ? p.slice(slash) : ""
  }
  const last = p.slice(p.lastIndexOf("/") + 1)
  const dot = last.lastIndexOf(".")
  return dot >= 0 ? last.slice(dot + 1).toLowerCase() : ""
}

const mimeFromExt = (u: string): string | undefined => MIME[extOf(u)]
const mimeFor = (u: string, kind: MediaKind): string => mimeFromExt(u) ?? KIND_DEFAULT_MIME[kind]

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

function statusText(v: unknown): string {
  if (typeof v === "string") return v
  if (typeof v === "number" && Number.isFinite(v)) return String(v)
  return ""
}

/** RFC 3986: keep unreserved A-Z a-z 0-9 - . _ ~, %XX (uppercase) every other UTF-8 byte. */
function pct(s: string): string {
  return encodeURIComponent(s).replace(/[!'()*]/g, (c) => "%" + c.charCodeAt(0).toString(16).toUpperCase())
}

/** First `n` Unicode code points, then "…" when truncated. */
function cap(text: string, n: number): string {
  const cps = Array.from(text)
  return cps.length <= n ? text : cps.slice(0, n).join("") + "…"
}

const positive = (v: unknown): number | undefined => (typeof v === "number" && v > 0 ? v : undefined)

const REDIRECTS = new Set([301, 302, 303, 307, 308])
class TooManyRedirects extends Error {}

function originOf(u: string): string | undefined {
  try {
    return new URL(u).origin
  } catch {
    return undefined
  }
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

const decode = (b: Uint8Array) => new TextDecoder().decode(b)
const b64 = (b: Uint8Array) => Buffer.from(b).toString("base64")
const isHttp = (u: string) => /^https?:\/\//i.test(u)
const deadlineMs = (d: number | Date | undefined): number | undefined => (d instanceof Date ? d.getTime() : typeof d === "number" ? d : undefined)

function present(args: Record<string, unknown> | undefined): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(args ?? {})) if (v !== undefined && v !== null) out[k] = v
  return out
}

function overlay(body: Record<string, unknown>, ctx: MediaContext): Record<string, unknown> {
  for (const [k, v] of Object.entries(ctx.providerOptions ?? {})) body[k] = v
  return body
}

// ---- HTTP job (§7G.8) ------------------------------------------------------------------------

interface HttpRes {
  status: number
  headers: Headers
  bytes: Uint8Array
  over: boolean
}

interface SendOpts {
  json?: unknown
  body?: Uint8Array
  contentType?: string
  /** false ⇒ never send client headers (downloads, input fetches). */
  auth?: boolean
  /** Stop reading the body at limit + 1 bytes. */
  limit?: number
}

/** One job's HTTP: redirects by hand, the origin header rule, the per-request deadline. */
class HttpJob {
  jobId?: string
  private readonly origin: string | undefined
  constructor(
    readonly client: string,
    readonly verb: string,
    baseUrl: string,
    private readonly headers: () => Record<string, string>,
    private readonly doFetch: typeof fetch,
    readonly timeoutMs: number,
    readonly deadline: number,
    readonly signal?: AbortSignal,
  ) {
    this.origin = originOf(baseUrl)
  }

  timedOut(): MediaError {
    return new MediaError(
      this.jobId !== undefined ? `job ${this.jobId} timed out after ${this.timeoutMs}ms` : `${this.verb}: timed out after ${this.timeoutMs}ms`,
      "timeout",
      this.jobId,
    )
  }

  async send(url: string, method: string, opts: SendOpts = {}): Promise<HttpRes> {
    const signal = this.signal
    if (signal?.aborted) throw abortError(signal)
    const remaining = this.deadline - Date.now()
    if (remaining <= 0) throw this.timedOut()
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), remaining)
    const forward = () => ctl.abort()
    signal?.addEventListener("abort", forward, { once: true })
    let body: string | Uint8Array | undefined = opts.json !== undefined ? JSON.stringify(opts.json) : opts.body
    let contentType = opts.json !== undefined ? "application/json" : opts.contentType
    try {
      for (let hops = 0; ; hops++) {
        const h: Record<string, string> = {}
        if (body !== undefined && contentType) h["Content-Type"] = contentType
        if (opts.auth !== false && this.origin !== undefined && originOf(url) === this.origin) Object.assign(h, this.headers())
        if (!Object.keys(h).some((k) => k.toLowerCase() === "user-agent")) h["User-Agent"] = USER_AGENT
        const res = await this.doFetch(url, { method, headers: h, body: body as any, signal: ctl.signal, redirect: "manual" })
        const loc = res.headers?.get?.("location")
        if (REDIRECTS.has(res.status) && loc) {
          await res.body?.cancel().catch(() => {})
          if (hops >= 10) throw new TooManyRedirects()
          url = new URL(loc, url).toString()
          if (res.status <= 303 && method !== "GET" && method !== "HEAD") {
            method = "GET"
            body = undefined
            contentType = undefined
          }
          continue
        }
        const { bytes, over } = await readBody(res, opts.limit)
        return { status: res.status, headers: res.headers, bytes, over }
      }
    } catch (e) {
      if (signal?.aborted) throw abortError(signal)
      if (ctl.signal.aborted) throw this.timedOut()
      if (e instanceof TooManyRedirects) throw new MediaError(`${this.verb}: too many redirects`, "failed", this.jobId)
      if (e instanceof MediaError) throw e
      throw new MediaError(`${this.verb}: ${e instanceof Error ? e.message : String(e)}`, "failed", this.jobId)
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener("abort", forward)
    }
  }

  /** A provider API call: unavailable mapping, then any other non-2xx is `HTTP <s>: <body>`. */
  async api(method: string, url: string, opts: SendOpts = {}): Promise<HttpRes> {
    const res = await this.send(url, method, opts)
    if (res.status < 200 || res.status > 299) {
      const text = decode(res.bytes)
      if (res.status === 402 || (res.status === 429 && /quota|credit|billing|balance/i.test(text))) {
        throw new MediaUnavailableError(`${this.client}: HTTP ${res.status}: ${cap(text, 1000)}`, this.jobId)
      }
      throw new MediaError(`HTTP ${res.status}: ${cap(text, 1000)}`, "failed", this.jobId)
    }
    return res
  }

  async json(method: string, url: string, opts: SendOpts = {}): Promise<{ data: any; res: HttpRes }> {
    const res = await this.api(method, url, opts)
    try {
      return { data: JSON.parse(decode(res.bytes)), res }
    } catch {
      throw new MediaError(`${this.verb}: response is not JSON`, "failed", this.jobId)
    }
  }

  /** §7G.5 download: GET, no client headers, capped at maxBytes. */
  async download(url: string, maxBytes: number): Promise<{ bytes: Uint8Array; contentType?: string }> {
    const res = await this.send(url, "GET", { auth: false, limit: maxBytes })
    if (res.status < 200 || res.status > 299) throw new MediaError(`${this.verb}: download failed: HTTP ${res.status}`, "failed", this.jobId)
    if (res.over) throw new MediaError(`${this.verb}: download failed: file exceeds gather.maxBytes (${maxBytes} bytes)`, "failed", this.jobId)
    return { bytes: res.bytes, contentType: res.headers.get("content-type") ?? undefined }
  }
}

async function readBody(res: Response, limit?: number): Promise<{ bytes: Uint8Array; over: boolean }> {
  if (limit === undefined || !res.body) return { bytes: new Uint8Array(await res.arrayBuffer()), over: false }
  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
    total += value.byteLength
    if (total > limit) {
      await reader.cancel().catch(() => {})
      return { bytes: new Uint8Array(0), over: true }
    }
  }
  return { bytes: new Uint8Array(Buffer.concat(chunks)), over: false }
}

// ---- input references (§7G.1) ----------------------------------------------------------------

interface InputFile {
  bytes: Uint8Array
  filename: string
  contentType: string
}

async function readInput(job: HttpJob, value: string, maxBytes: number): Promise<InputFile> {
  const fail = (reason: string) => new MediaError(`${job.verb}: cannot read "${value}": ${reason}`, "failed")
  let bytes: Uint8Array
  let segPath: string
  if (isHttp(value)) {
    const res = await job.send(value, "GET", { auth: false, limit: maxBytes })
    if (res.status < 200 || res.status > 299) throw fail(`HTTP ${res.status}`)
    if (res.over) throw fail(`file exceeds gather.maxBytes (${maxBytes} bytes)`)
    bytes = res.bytes
    segPath = new URL(value).pathname
  } else {
    let file = value
    try {
      if (/^file:\/\//i.test(value)) file = fileURLToPath(value)
      const st = fs.statSync(file)
      if (st.size > maxBytes) throw new Error(`file exceeds gather.maxBytes (${maxBytes} bytes)`)
      bytes = new Uint8Array(fs.readFileSync(file))
    } catch (e) {
      throw fail(e instanceof Error ? e.message : String(e))
    }
    segPath = file
  }
  const seg = segPath.split("?")[0].split("/").pop() ?? ""
  const filename = seg === "" ? "input" : seg
  return { bytes, filename, contentType: mimeFromExt(filename) ?? "application/octet-stream" }
}

// ---- gather (§7G.5) --------------------------------------------------------------------------

type RawResult =
  | { bytes: Uint8Array; mimeType: string }
  | { base64: string; mimeType: string }
  | { dataUrl: string }
  | { url: string }
  | { path: string }

interface GatherCfg {
  mode: GatherMode
  dir: string
  maxBytes: number
  keep: boolean
  slug: string
}

/** gather.name sanitised (§7G.10b). */
function mediaSlug(name: string | undefined): string {
  return Buffer.from(name ?? "", "utf8").toString("latin1").replace(/[^A-Za-z0-9._-]/g, "-").replace(/^[.-]+/, "")
}

function gatherCfg(config: MediaClientConfig, dflt: GatherMode): GatherCfg {
  const g = config.gather ?? {}
  return {
    mode: g.mode ?? dflt,
    dir: path.resolve(g.dir ?? path.join(os.tmpdir(), "toolnexus-media")),
    maxBytes: positive(g.maxBytes) ?? 104857600,
    keep: g.keep ?? true,
    slug: mediaSlug(g.name),
  }
}

let saveSeq = 0

class Gatherer {
  constructor(
    readonly cfg: GatherCfg,
    readonly client: string,
    readonly verb: string,
    readonly kind: MediaKind,
    readonly job?: HttpJob,
  ) {}

  private tooBig(): MediaError {
    return new MediaError(`${this.verb}: file exceeds gather.maxBytes (${this.cfg.maxBytes} bytes)`, "failed", this.job?.jobId)
  }

  private keepBytes(bytes: Uint8Array, mimeType: string, ext?: string): MediaFile {
    if (bytes.byteLength > this.cfg.maxBytes) throw this.tooBig()
    const type = KIND_FILE_TYPE[this.kind]
    if (this.cfg.mode === "inline") return { type, mimeType, data: b64(bytes) }
    const file = this.target(mimeType, ext)
    fs.writeFileSync(file, bytes)
    return { type, mimeType, path: file, url: "file://" + file }
  }

  private target(mimeType: string, ext?: string): string {
    fs.mkdirSync(this.cfg.dir, { recursive: true })
    const e = ext ?? EXT[mimeType] ?? ".bin"
    if (this.cfg.slug !== "") {
      // First free <slug><ext>, <slug>-2<ext>, …; created exclusively so concurrent gathers never collide.
      for (let n = 1; ; n++) {
        const file = path.join(this.cfg.dir, `${this.cfg.slug}${n > 1 ? `-${n}` : ""}${e}`)
        try {
          fs.closeSync(fs.openSync(file, "wx"))
          return file
        } catch (err) {
          if ((err as NodeJS.ErrnoException).code !== "EEXIST") throw err
        }
      }
    }
    return path.join(this.cfg.dir, `${this.client}-${this.verb}-${Date.now()}-${++saveSeq}${ext ?? EXT[mimeType] ?? ".bin"}`)
  }

  async one(raw: RawResult): Promise<MediaFile> {
    const type = KIND_FILE_TYPE[this.kind]
    if ("bytes" in raw) return this.keepBytes(raw.bytes, raw.mimeType)
    if ("base64" in raw) return this.keepBytes(new Uint8Array(Buffer.from(raw.base64, "base64")), raw.mimeType)
    if ("dataUrl" in raw) {
      const m = /^data:([^;,]*)((?:;[^;,]*)*),(.*)$/s.exec(raw.dataUrl)
      const mime = (m?.[1] || KIND_DEFAULT_MIME[this.kind]).toLowerCase()
      const isB64 = /;base64/i.test(m?.[2] ?? "")
      const payload = m?.[3] ?? ""
      const bytes = isB64 ? Buffer.from(payload, "base64") : Buffer.from(decodeURIComponent(payload), "utf8")
      return this.keepBytes(new Uint8Array(bytes), mime)
    }
    if ("url" in raw) {
      if (this.cfg.mode === "url") return { type, mimeType: mimeFor(raw.url, this.kind), url: raw.url }
      if (!this.job) throw new MediaError(`${this.verb}: cannot download "${raw.url}"`)
      const { bytes, contentType } = await this.job.download(raw.url, this.cfg.maxBytes)
      const ct = contentType?.split(";")[0].trim().toLowerCase()
      const mime = ct && ct !== "application/octet-stream" ? ct : mimeFor(raw.url, this.kind)
      return this.keepBytes(bytes, mime, urlExt(raw.url))
    }
    // Local path (a CLI's own file).
    const src = raw.path
    const mimeType = mimeFor(src, this.kind)
    if (this.cfg.mode === "url") return { type, mimeType, path: src, url: "file://" + src }
    if (fs.statSync(src).size > this.cfg.maxBytes) throw this.tooBig()
    if (this.cfg.mode === "inline") return { type, mimeType, data: fs.readFileSync(src).toString("base64") }
    const file = this.target(mimeType)
    fs.copyFileSync(src, file)
    if (!this.cfg.keep) fs.rmSync(src, { force: true })
    return { type, mimeType, path: file, url: "file://" + file }
  }

  async all(raws: RawResult[]): Promise<MediaFile[]> {
    const out: MediaFile[] = []
    for (const r of raws) out.push(await this.one(r))
    return out
  }
}

// ---- shared per-call plumbing ----------------------------------------------------------------

interface CallEnv {
  started: number
  deadline: number
  timeoutMs: number
  report: (status: string, jobId?: string) => void
}

function callEnv(client: string, verb: string, config: MediaClientConfig, ctx: MediaContext, timeoutMs: number): CallEnv {
  const started = Date.now()
  const ctxDeadline = deadlineMs(ctx.deadline)
  const deadline = Math.min(started + timeoutMs, ctxDeadline ?? Infinity)
  const sink = ctx.progress ?? config.loop?.onProgress
  return {
    started,
    deadline,
    timeoutMs,
    report: (status, jobId) => {
      const p: MediaProgress = { verb: verb as MediaVerb, client, status, elapsedMs: Date.now() - started }
      if (jobId !== undefined) p.jobId = jobId
      sink?.(p)
    },
  }
}

function withJobId<T>(job: { jobId?: string }, run: () => Promise<T>): Promise<T> {
  return run().catch((e) => {
    if (e instanceof MediaError && e.jobId === undefined && job.jobId !== undefined) e.jobId = job.jobId
    throw e
  })
}

// ---- style clients (§7G.7) -------------------------------------------------------------------

interface StyleInternals {
  keyEnv?: string
  gatherMode?: GatherMode
}

/** A media client over one JSON style (the ADR 0036 engine). Named `style.name ?? "style"`. */
export class StyleMediaClient implements MediaClient {
  readonly name: string
  protected readonly style: MediaStyle
  protected readonly config: MediaClientConfig
  private readonly internals: StyleInternals

  constructor(style: MediaStyle, config: MediaClientConfig = {}, internals: StyleInternals = {}) {
    validateStyle(style)
    this.style = style
    this.config = config
    this.internals = internals
    this.name = style.name ?? "style"
  }

  supports(verb: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.style.verbs ?? {}, verb) && VERB_SPEC.has(verb)
  }

  args(verb: string): MediaArgSpec[] {
    const spec = (this.style.verbs as Record<string, MediaVerbStyle | undefined>)[verb]
    const canon = VERB_SPEC.get(verb)
    if (!spec || !canon) return []
    const extra = new Set(spec.required ?? [])
    return canon.args
      .filter((a) => Object.prototype.hasOwnProperty.call(spec.args, a.name))
      .map((a) => ({ name: a.name, required: a.required === true || extra.has(a.name) }))
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("image", args, ctx) }
  video(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("video", args, ctx) }
  speech(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("speech", args, ctx) }
  music(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("music", args, ctx) }
  transcribe(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("transcribe", args, ctx) }
  cloneVoice(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("cloneVoice", args, ctx) }
  talk(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("talk", args, ctx) }

  /** Hook for subclasses: the request body's defaults for a verb. */
  protected defaults(_verb: MediaVerb, spec: MediaVerbStyle): Record<string, unknown> {
    return spec.defaults ? structuredClone(spec.defaults) : {}
  }

  protected baseUrl(): string {
    return (this.config.url ?? this.style.baseUrl).replace(/\/+$/, "")
  }

  private async run(verb: MediaVerb, rawArgs: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult> {
    const style = this.style
    const spec = (style.verbs as Record<string, MediaVerbStyle | undefined>)[verb]
    if (!spec) throw new MediaError(`media: no client supports "${verb}"`)
    let key = this.config.key
    if (this.internals.keyEnv) {
      key = this.config.key || process.env[this.internals.keyEnv]
      if (!key) throw new MediaUnavailableError(`${this.name}: no API key (set ${this.internals.keyEnv})`)
    }
    const args = present(rawArgs)
    const body = this.defaults(verb, spec)
    for (const [k, v] of Object.entries(args)) {
      if (Object.prototype.hasOwnProperty.call(spec.args, k)) writePath(body, spec.args[k], v)
    }
    overlay(body, ctx)

    const headers = (): Record<string, string> => {
      const h: Record<string, string> = {}
      for (const [k, v] of Object.entries(style.headers ?? {})) {
        h[k] = v.replace(/\$\{([A-Za-z0-9_]+)\}/g, (_, n) => (key !== undefined ? key : process.env[n] ?? ""))
      }
      return h
    }
    const loop = this.config.loop ?? {}
    const timeoutMs = positive(loop.timeoutMs) ?? positive(spec.poll?.timeoutMs) ?? 600000
    const env = callEnv(this.name, verb, this.config, ctx, timeoutMs)
    const base = this.baseUrl()
    const job = new HttpJob(this.name, verb, base, headers, this.config.fetch ?? fetch, timeoutMs, env.deadline, ctx.signal)

    const call = async (method: string, p: string, json?: unknown): Promise<unknown> => {
      const { data } = await job.json(method, base + p, json !== undefined ? { json } : {})
      const envl = style.errorEnvelope
      if (envl) {
        const code = readPath(data, envl.codePath)
        if (code !== undefined && !envl.okValues.some((ok) => jsonEqual(ok, code))) {
          const msg = envl.messagePath ? readPath(data, envl.messagePath) : undefined
          throw new MediaError(`API error ${renderScalar(code)}: ${typeof msg === "string" ? msg : ""}`, "failed", job.jobId)
        }
      }
      return data
    }

    return withJobId(job, async () => {
      let final: unknown
      let status = "completed"
      const startResp = await call((spec.start.method ?? "POST").toUpperCase(), spec.start.path, body)
      if (spec.mode === "async") {
        const poll = spec.poll!
        const idPath = spec.idPath ?? ""
        const rawId = readPath(startResp, idPath)
        if (typeof rawId === "string" && rawId !== "") job.jobId = rawId
        else if (typeof rawId === "number" && Number.isInteger(rawId)) job.jobId = String(rawId)
        else throw new MediaError(`${verb}: no job id at ${idPath}`)
        const jobId = job.jobId
        const interval = positive(loop.intervalMs) ?? positive(poll.intervalMs) ?? 5000
        const pollPath = poll.path.split("{id}").join(pct(jobId))
        for (;;) {
          await sleep(interval, ctx.signal)
          const resp = await call((poll.method ?? "GET").toUpperCase(), pollPath)
          const s = statusText(readPath(resp, poll.statusPath))
          env.report(s, jobId)
          if ((poll.done ?? []).includes(s)) {
            final = resp
            status = s
            break
          }
          if ((poll.failed ?? []).includes(s)) {
            const m = poll.errorPath ? readPath(resp, poll.errorPath) : undefined
            throw new MediaError(typeof m === "string" && m !== "" ? `job ${jobId} ${s}: ${m}` : `job ${jobId} ${s}`, "failed", jobId)
          }
          if (Date.now() >= env.deadline) throw job.timedOut()
        }
      } else {
        final = startResp
      }

      const value = readPath(final, spec.resultPath)
      const result: MediaResult = { files: [], status }
      if (spec.kind === "text") {
        if (typeof value === "string") result.text = value
        else if (typeof value === "number") result.text = renderScalar(value)
        else throw new MediaError(`${verb}: no result at ${spec.resultPath}`)
      } else {
        const urls: string[] = []
        flattenStrings(value, urls)
        if (urls.length === 0) throw new MediaError(`${verb}: no result at ${spec.resultPath}`)
        const g = new Gatherer(gatherCfg(this.config, this.internals.gatherMode ?? "url"), this.name, verb, spec.kind, job)
        result.files = await g.all(urls.map((url) => ({ url })))
      }
      if (job.jobId !== undefined) result.jobId = job.jobId
      return result
    })
  }
}

/** Make a media client from a parsed style (§7G.7). */
export function styleMediaClient(style: MediaStyle | LoadedMediaStyle, config: MediaClientConfig = {}): StyleMediaClient {
  if (style instanceof LoadedMediaStyle) {
    if (!style.style) throw new Error(`style: "${style.preset}" is a CLI preset, not an HTTP style`)
    return new StyleMediaClient(style.style, config)
  }
  return new StyleMediaClient(style, config)
}

/** a2e.ai (§7G.9): a style client over the embedded preset, `A2E_API_KEY`, gather `download`. */
export class A2EMediaClient extends StyleMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super(A2E_STYLE, config, { keyEnv: "A2E_API_KEY", gatherMode: "download" })
  }
  protected override defaults(verb: MediaVerb, spec: MediaVerbStyle): Record<string, unknown> {
    const d = super.defaults(verb, spec)
    const model = this.config.models?.[verb] ?? (verb === "image" ? this.config.model : undefined)
    if (verb === "image" && model) d.model_type = model
    return d
  }
}

// ---- built-in API clients (§7G.9) ------------------------------------------------------------

interface ApiDef {
  name: string
  keyEnv: string
  defaultUrl: string
  auth: (key: string) => Record<string, string>
  primary: MediaVerb
  interval: number
  verbs: Partial<Record<MediaVerb, MediaArgSpec[]>>
}

const req = (name: string): MediaArgSpec => ({ name, required: true })
const opt = (name: string): MediaArgSpec => ({ name, required: false })

interface ApiCall {
  verb: MediaVerb
  base: string
  job: HttpJob
  env: CallEnv
  gather: Gatherer
  interval: number
  maxBytes: number
}

/** The shared HTTP base for code-defined API clients (§7G.8 rules, gather, unavailable). */
export abstract class ApiMediaClient implements MediaClient {
  readonly name: string
  protected readonly config: MediaClientConfig
  private readonly def: ApiDef

  protected constructor(def: ApiDef, config: MediaClientConfig = {}) {
    this.def = def
    this.name = def.name
    this.config = config
  }

  supports(verb: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.def.verbs, verb)
  }

  args(verb: string): MediaArgSpec[] {
    return (this.def.verbs as Record<string, MediaArgSpec[] | undefined>)[verb] ?? []
  }

  /** The model for a verb: `models[verb]`, else `model` for the primary verb, else the default. */
  protected model(verb: MediaVerb, dflt: string): string {
    return this.config.models?.[verb] ?? (verb === this.def.primary ? this.config.model : undefined) ?? dflt
  }

  protected begin(verb: MediaVerb, ctx: MediaContext): ApiCall {
    const key = this.config.key || process.env[this.def.keyEnv]
    if (!key) throw new MediaUnavailableError(`${this.name}: no API key (set ${this.def.keyEnv})`)
    const loop = this.config.loop ?? {}
    const timeoutMs = positive(loop.timeoutMs) ?? 600000
    const env = callEnv(this.name, verb, this.config, ctx, timeoutMs)
    const base = (this.config.url ?? this.def.defaultUrl).replace(/\/+$/, "")
    const auth = this.def.auth(key)
    const job = new HttpJob(this.name, verb, base, () => ({ ...auth }), this.config.fetch ?? fetch, timeoutMs, env.deadline, ctx.signal)
    const g = gatherCfg(this.config, "download")
    return {
      verb, base, job, env,
      gather: new Gatherer(g, this.name, verb, VERB_KIND[verb], job),
      interval: positive(loop.intervalMs) ?? this.def.interval,
      maxBytes: g.maxBytes,
    }
  }

  /** Poll until `check` says done; `check` returns the status text and an optional failure. */
  protected async pollUntil(c: ApiCall, ctx: MediaContext, check: () => Promise<{ status: string; done: boolean; error?: string }>): Promise<void> {
    for (;;) {
      await sleep(c.interval, ctx.signal)
      const r = await check()
      c.env.report(r.status, c.job.jobId)
      if (r.done) return
      if (r.error !== undefined) throw new MediaError(r.error, "failed", c.job.jobId)
      if (Date.now() >= c.env.deadline) throw c.job.timedOut()
    }
  }

  protected run(c: ApiCall, fn: () => Promise<MediaResult>): Promise<MediaResult> {
    return withJobId(c.job, fn)
  }
}

/** Multipart form body (fields, then files, in the given order). */
type FormEntry = { name: string; value: string } | { name: string; file: InputFile }

function multipart(entries: FormEntry[]): { body: Uint8Array; contentType: string } {
  const boundary = "toolnexus-" + Math.random().toString(16).slice(2) + Date.now().toString(16)
  const chunks: Buffer[] = []
  for (const e of entries) {
    if ("value" in e) {
      chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${e.name}"\r\n\r\n${e.value}\r\n`))
    } else {
      chunks.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${e.name}"; filename="${e.file.filename.replace(/"/g, "%22")}"\r\nContent-Type: ${e.file.contentType}\r\n\r\n`))
      chunks.push(Buffer.from(e.file.bytes))
      chunks.push(Buffer.from("\r\n"))
    }
  }
  chunks.push(Buffer.from(`--${boundary}--\r\n`))
  return { body: new Uint8Array(Buffer.concat(chunks)), contentType: `multipart/form-data; boundary=${boundary}` }
}

/** providerOptions on a multipart body: replace a field of the same name, else append. */
function overlayForm(entries: FormEntry[], ctx: MediaContext): FormEntry[] {
  for (const [k, v] of Object.entries(ctx.providerOptions ?? {})) {
    const value = typeof v === "string" ? v : JSON.stringify(v)
    const i = entries.findIndex((e) => e.name === k && "value" in e)
    if (i >= 0) entries[i] = { name: k, value }
    else entries.push({ name: k, value })
  }
  return entries
}

/** ElevenLabs (§7G.9): speech (live-verified), music, transcribe, cloneVoice. */
export class ElevenLabsMediaClient extends ApiMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super({
      name: "elevenlabs", keyEnv: "ELEVENLABS_API_KEY", defaultUrl: "https://api.elevenlabs.io",
      auth: (k) => ({ "xi-api-key": k }), primary: "speech", interval: 5000,
      verbs: {
        speech: [req("text"), opt("voice")],
        music: [req("prompt"), opt("seconds"), opt("instrumental")],
        transcribe: [req("audio"), opt("language")],
        cloneVoice: [req("name"), req("samples")],
      },
    }, config)
  }

  speech(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("speech", ctx)
    return this.run(c, async () => {
      let id = "JBFqnCBsd6RMkjVDRZzb"
      if (args.voice !== undefined && args.voice !== null) {
        const voice = String(args.voice)
        const { data } = await c.job.json("GET", `${c.base}/v2/voices?page_size=100&search=${pct(voice)}`)
        const voices: any[] = Array.isArray(data?.voices) ? data.voices : []
        const lower = voice.toLowerCase()
        const hit = voices.find((v) => (typeof v?.name === "string" && v.name.toLowerCase() === lower) || v?.voice_id === voice)
        if (hit && typeof hit.voice_id === "string") id = hit.voice_id
        else if (voice.length === 20 && !/\s/.test(voice)) id = voice
        else throw new MediaError(`speech: voice "${voice}" not found`)
      }
      const body = overlay({ text: args.text, model_id: this.model("speech", "eleven_flash_v2_5") }, ctx)
      const res = await c.job.api("POST", `${c.base}/v1/text-to-speech/${pct(id)}?output_format=mp3_22050_32`, { json: body })
      const files = [await c.gather.one({ bytes: res.bytes, mimeType: "audio/mpeg" })]
      const result: MediaResult = { files, status: "completed" }
      const rid = res.headers.get("request-id")
      if (rid) result.jobId = rid
      return result
    })
  }

  music(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("music", ctx)
    return this.run(c, async () => {
      const seconds = typeof args.seconds === "number" ? args.seconds : 10
      const body: Record<string, unknown> = { prompt: args.prompt, music_length_ms: seconds * 1000 }
      if (args.instrumental === true) body.force_instrumental = true
      overlay(body, ctx)
      const res = await c.job.api("POST", `${c.base}/v1/music?output_format=mp3_44100_128`, { json: body })
      return { files: [await c.gather.one({ bytes: res.bytes, mimeType: "audio/mpeg" })], status: "completed" }
    })
  }

  transcribe(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("transcribe", ctx)
    return this.run(c, async () => {
      const file = await readInput(c.job, String(args.audio), c.maxBytes)
      const entries: FormEntry[] = [{ name: "model_id", value: this.config.models?.transcribe ?? "scribe_v1" }]
      if (args.language !== undefined && args.language !== null) entries.push({ name: "language_code", value: String(args.language) })
      entries.push({ name: "file", file })
      const { data } = await c.job.json("POST", `${c.base}/v1/speech-to-text`, multipart(overlayForm(entries, ctx)))
      return textResult(data?.text, "transcribe", "text")
    })
  }

  cloneVoice(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("cloneVoice", ctx)
    return this.run(c, async () => {
      const entries: FormEntry[] = [{ name: "name", value: String(args.name) }]
      const samples = Array.isArray(args.samples) ? args.samples : [args.samples]
      for (const s of samples) entries.push({ name: "files", file: await readInput(c.job, String(s), c.maxBytes) })
      const { data } = await c.job.json("POST", `${c.base}/v1/voices/add`, multipart(overlayForm(entries, ctx)))
      return textResult(data?.voice_id, "cloneVoice", "voice_id")
    })
  }
}

function textResult(v: unknown, verb: string, at: string): MediaResult {
  if (typeof v === "string") return { files: [], text: v, status: "completed" }
  if (typeof v === "number") return { files: [], text: renderScalar(v), status: "completed" }
  throw new MediaError(`${verb}: no result at ${at}`)
}

const multipartOpts = (m: { body: Uint8Array; contentType: string }): SendOpts => ({ body: m.body, contentType: m.contentType })

/** OpenAI (§7G.9): image (live-verified), video, speech, transcribe; `url` ⇒ any compatible server. */
export class OpenAIMediaClient extends ApiMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super({
      name: "openai", keyEnv: "OPENAI_API_KEY", defaultUrl: "https://api.openai.com/v1",
      auth: (k) => ({ Authorization: `Bearer ${k}` }), primary: "image", interval: 5000,
      verbs: {
        image: [req("prompt"), opt("width"), opt("height")],
        video: [req("prompt"), opt("seconds")],
        speech: [req("text"), opt("voice")],
        transcribe: [req("audio"), opt("language")],
      },
    }, config)
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("image", ctx)
    return this.run(c, async () => {
      const model = this.model("image", "gpt-image-1-mini")
      const w = typeof args.width === "number" ? args.width : undefined
      const h = typeof args.height === "number" ? args.height : undefined
      const size = w === undefined && h === undefined ? "1024x1024" : `${w ?? h}x${h ?? w}`
      const body: Record<string, unknown> = { model, prompt: args.prompt, n: 1, size }
      if (model.startsWith("gpt-image")) body.quality = "low"
      const { data } = await c.job.json("POST", `${c.base}/images/generations`, { json: overlay(body, ctx) })
      const fmt = data?.output_format
      const mime = fmt === "jpeg" ? "image/jpeg" : fmt === "webp" ? "image/webp" : "image/png"
      const raws: RawResult[] = []
      for (const e of Array.isArray(data?.data) ? data.data : []) {
        if (typeof e?.b64_json === "string" && e.b64_json !== "") raws.push({ base64: e.b64_json, mimeType: mime })
        else if (typeof e?.url === "string" && e.url !== "") raws.push({ url: e.url })
      }
      if (raws.length === 0) throw new MediaError("image: no result at data")
      const result: MediaResult = { files: await c.gather.all(raws), status: "completed" }
      const usage = reportedUsage({ inputTokens: data?.usage?.input_tokens, outputTokens: data?.usage?.output_tokens })
      if (usage) result.usage = usage
      return result
    })
  }

  speech(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("speech", ctx)
    return this.run(c, async () => {
      const body = overlay({ model: this.model("speech", "gpt-4o-mini-tts"), input: args.text, voice: args.voice ?? "alloy" }, ctx)
      const res = await c.job.api("POST", `${c.base}/audio/speech`, { json: body })
      return { files: [await c.gather.one({ bytes: res.bytes, mimeType: "audio/mpeg" })], status: "completed" }
    })
  }

  transcribe(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("transcribe", ctx)
    return this.run(c, async () => {
      const file = await readInput(c.job, String(args.audio), c.maxBytes)
      const entries: FormEntry[] = [{ name: "model", value: this.model("transcribe", "gpt-4o-mini-transcribe") }]
      if (args.language !== undefined && args.language !== null) entries.push({ name: "language", value: String(args.language) })
      entries.push({ name: "file", file })
      const { data } = await c.job.json("POST", `${c.base}/audio/transcriptions`, multipartOpts(multipart(overlayForm(entries, ctx))))
      return textResult(data?.text, "transcribe", "text")
    })
  }

  video(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("video", ctx)
    return this.run(c, async () => {
      const seconds = args.seconds ?? 4
      const body = overlay({ model: this.model("video", "sora-2"), prompt: args.prompt, seconds: String(seconds) }, ctx)
      const { data } = await c.job.json("POST", `${c.base}/videos`, { json: body })
      const id = data?.id
      if (typeof id === "string" && id !== "") c.job.jobId = id
      else if (typeof id === "number" && Number.isInteger(id)) c.job.jobId = String(id)
      else throw new MediaError("video: no job id at id")
      const jobId = c.job.jobId
      await this.pollUntil(c, ctx, async () => {
        const { data: p } = await c.job.json("GET", `${c.base}/videos/${pct(jobId)}`)
        const s = statusText(p?.status)
        if (s === "failed") {
          const m = p?.error?.message
          return { status: s, done: false, error: typeof m === "string" && m !== "" ? `job ${jobId} failed: ${m}` : `job ${jobId} failed` }
        }
        return { status: s, done: s === "completed" }
      })
      const res = await c.job.api("GET", `${c.base}/videos/${pct(jobId)}/content`)
      return { files: [await c.gather.one({ bytes: res.bytes, mimeType: "video/mp4" })], jobId, status: "completed" }
    })
  }
}

/** §7G.12 D21: keep only the numeric fields the provider returned; none ⇒ no usage. */
function reportedUsage(fields: Partial<Record<"inputTokens" | "outputTokens" | "costUsd", unknown>>): MediaUsage | undefined {
  const out: MediaUsage = {}
  for (const [k, v] of Object.entries(fields)) if (typeof v === "number" && Number.isFinite(v)) (out as any)[k] = v
  return Object.keys(out).length ? out : undefined
}

/** OpenRouter (§7G.9): image through chat completions (live-verified). */
export class OpenRouterMediaClient extends ApiMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super({
      name: "openrouter", keyEnv: "OPENROUTER_API_KEY", defaultUrl: "https://openrouter.ai/api/v1",
      auth: (k) => ({ Authorization: `Bearer ${k}` }), primary: "image", interval: 5000,
      verbs: { image: [req("prompt")] },
    }, config)
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const c = this.begin("image", ctx)
    return this.run(c, async () => {
      const body = overlay({
        model: this.model("image", "google/gemini-2.5-flash-image"),
        modalities: ["image", "text"],
        messages: [{ role: "user", content: args.prompt }],
      }, ctx)
      const { data } = await c.job.json("POST", `${c.base}/chat/completions`, { json: body })
      let text = ""
      const raws: RawResult[] = []
      for (const ch of Array.isArray(data?.choices) ? data.choices : []) {
        const msg = ch?.message
        if (typeof msg?.content === "string") text += msg.content
        for (const im of Array.isArray(msg?.images) ? msg.images : []) {
          const u = im?.image_url?.url
          if (typeof u !== "string" || u === "") continue
          raws.push(/^data:/i.test(u) ? { dataUrl: u } : { url: u })
        }
      }
      if (raws.length === 0) throw new MediaError(`image: no image in response${text !== "" ? ": " + cap(text, 200) : ""}`)
      const result: MediaResult = { files: await c.gather.all(raws), status: "completed" }
      if (text !== "") result.text = text
      if (typeof data?.id === "string" && data.id !== "") result.jobId = data.id
      const usage = reportedUsage({ inputTokens: data?.usage?.prompt_tokens, outputTokens: data?.usage?.completion_tokens, costUsd: data?.usage?.cost })
      if (usage) result.usage = usage
      return result
    })
  }
}

/** fal (§7G.9): the queue API for any hosted model id — image (live-verified), video. */
export class FalMediaClient extends ApiMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super({
      name: "fal", keyEnv: "FAL_API_KEY", defaultUrl: "https://queue.fal.run",
      auth: (k) => ({ Authorization: `Key ${k}` }), primary: "image", interval: 500,
      verbs: {
        image: [req("prompt"), opt("width"), opt("height"), opt("seed")],
        video: [req("prompt"), opt("image"), opt("seconds"), opt("aspectRatio")],
      },
    }, config)
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const model = this.model("image", "fal-ai/flux/schnell")
    const input: Record<string, unknown> = { prompt: args.prompt, num_images: 1 }
    if (model.includes("schnell")) {
      input.image_size = "square"
      input.num_inference_steps = 4
    }
    const w = typeof args.width === "number" ? args.width : undefined
    const h = typeof args.height === "number" ? args.height : undefined
    if (w !== undefined || h !== undefined) input.image_size = { width: w ?? h, height: h ?? w }
    if (args.seed !== undefined) input.seed = args.seed
    return this.queue("image", model, input, ctx)
  }

  video(args: Record<string, unknown>, ctx: MediaContext = {}): Promise<MediaResult> {
    const model = this.config.models?.video ??
      (args.image !== undefined ? "fal-ai/kling-video/v2.1/standard/image-to-video" : "fal-ai/kling-video/v2.1/standard/text-to-video")
    const input: Record<string, unknown> = { prompt: args.prompt, duration: String(args.seconds ?? 5) }
    if (args.image !== undefined) input.image_url = args.image
    if (args.aspectRatio !== undefined) input.aspect_ratio = args.aspectRatio
    return this.queue("video", model, input, ctx)
  }

  private queue(verb: MediaVerb, model: string, input: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult> {
    const c = this.begin(verb, ctx)
    return this.run(c, async () => {
      const { data } = await c.job.json("POST", `${c.base}/${model.replace(/^\/+/, "")}`, { json: overlay(input, ctx) })
      const id = data?.request_id
      if (typeof id === "string" && id !== "") c.job.jobId = id
      else if (typeof id === "number" && Number.isInteger(id)) c.job.jobId = String(id)
      else throw new MediaError(`${verb}: no job id at request_id`)
      const jobId = c.job.jobId
      const statusUrl = typeof data?.status_url === "string" ? data.status_url : `${c.base}/${model}/requests/${pct(jobId)}/status`
      const responseUrl = typeof data?.response_url === "string" ? data.response_url : `${c.base}/${model}/requests/${pct(jobId)}`
      await this.pollUntil(c, ctx, async () => {
        const { data: p } = await c.job.json("GET", statusUrl)
        const s = statusText(p?.status)
        if (s === "COMPLETED") return { status: s, done: true }
        const err = p?.error
        if (err !== undefined && err !== null && err !== "") {
          return { status: s, done: false, error: `job ${jobId} failed: ${typeof err === "string" ? err : JSON.stringify(err)}` }
        }
        return { status: s, done: false }
      })
      const { data: r } = await c.job.json("GET", responseUrl)
      const urls: string[] = []
      for (const im of Array.isArray(r?.images) ? r.images : []) if (typeof im?.url === "string" && im.url !== "") urls.push(im.url)
      for (const k of ["image", "video", "audio", "audio_file"]) {
        const u = r?.[k]?.url
        if (typeof u === "string" && u !== "") urls.push(u)
      }
      if (urls.length === 0) throw new MediaError(`${verb}: no result in response`)
      return { files: await c.gather.all(urls.map((url) => ({ url }))), jobId, status: "completed" }
    })
  }
}

// ---- CLI clients (§7G.10) --------------------------------------------------------------------

/** What one JSON line of a CLI's stdout means. */
export interface CliEvent {
  session?: string
  text?: string
  paths?: string[]
  error?: string
  status?: string
  /** Provider-reported usage (§7G.12 D21). */
  usage?: MediaUsage
}

/** How a {@link CliMediaClient} drives one CLI. */
export interface CliSpec {
  name: string
  /** Default argv prefix when `config.command` is absent. */
  command: string
  /** Prompt template per supported verb; `{prompt}` is replaced. */
  templates: Partial<Record<MediaVerb, string>>
  /** Client argv after the command, for one run. */
  argv(prompt: string, cwd: string, model?: string): string[]
  /** Fixed env added to the process env. */
  env?: Record<string, string>
  parse(obj: Record<string, unknown>, line: string): CliEvent
  /** A folder whose entries are candidate files once the session is known. */
  folder?(session: string): string | undefined
  /** An unavailable message for the last error, or undefined (⇒ failed). */
  unavailable?(error: string, verb: MediaVerb): string | undefined
}

const CLI_PATH_RE = /\/[^\s"'()[\]]+\.(png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)/gi

function isFile(p: string): boolean {
  try {
    return fs.statSync(p).isFile()
  } catch {
    return false
  }
}

/** Runs a local subscription CLI once, headless, and collects the files it made (§7G.10). */
export class CliMediaClient implements MediaClient {
  readonly name: string
  protected readonly config: MediaClientConfig
  private readonly spec: CliSpec
  /** Never start the CLI; write a fixed file instead (§7G CLI mock presets). */
  private readonly mock: boolean

  constructor(spec: CliSpec, config: MediaClientConfig = {}, opts: { mock?: boolean } = {}) {
    this.spec = spec
    this.name = spec.name
    this.config = config
    this.mock = opts.mock === true
  }

  supports(verb: string): boolean {
    return Object.prototype.hasOwnProperty.call(this.spec.templates, verb)
  }

  args(verb: string): MediaArgSpec[] {
    if (!this.supports(verb)) return []
    return verb === "video"
      ? [req("prompt"), opt("seconds"), opt("aspectRatio")]
      : [req("prompt"), opt("width"), opt("height"), opt("aspectRatio")]
  }

  image(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("image", args, ctx) }
  video(args: Record<string, unknown>, ctx: MediaContext = {}) { return this.run("video", args, ctx) }

  private async run(verb: MediaVerb, args: Record<string, unknown>, ctx: MediaContext): Promise<MediaResult> {
    const template = this.spec.templates[verb]
    if (template === undefined) throw new MediaError(`media: no client supports "${verb}"`)
    const name = this.name
    const cmd = Array.isArray(this.config.command) ? this.config.command : [this.config.command ?? this.spec.command]
    const timeoutMs = positive(this.config.loop?.timeoutMs) ?? 600000
    const env = callEnv(name, verb, this.config, ctx, timeoutMs)
    if (ctx.signal?.aborted) throw abortError(ctx.signal)
    const prompt = template.split("{prompt}").join(foldMediaPrompt(verb, args))
    if (this.mock) return this.mockRun(verb, prompt)
    const cwd = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), "toolnexus-cli-")))
    try {
      const child = spawn(cmd[0], [...cmd.slice(1), ...this.spec.argv(prompt, cwd, this.config.model)], {
        cwd,
        env: { ...process.env, ...(this.spec.env ?? {}) },
        stdio: ["ignore", "pipe", "ignore"],
        detached: process.platform !== "win32",
      })
      const started = await new Promise<boolean>((resolve) => {
        child.once("spawn", () => resolve(true))
        child.once("error", () => resolve(false))
      })
      if (!started) throw new MediaUnavailableError(`${name}: cannot start "${cmd[0]}"`)

      let session: string | undefined
      let usage: MediaUsage | undefined
      let text = ""
      const paths: string[] = []
      let lastError: string | undefined
      const rl = readline.createInterface({ input: child.stdout!, crlfDelay: Infinity })
      rl.on("line", (line) => {
        let obj: unknown
        try {
          obj = JSON.parse(line)
        } catch {
          return
        }
        if (obj === null || typeof obj !== "object" || Array.isArray(obj)) return
        const ev = this.spec.parse(obj as Record<string, unknown>, line)
        if (ev.session !== undefined) session = ev.session
        if (ev.usage !== undefined) usage = ev.usage
        if (ev.text !== undefined) text += ev.text
        if (ev.paths) paths.push(...ev.paths)
        if (ev.error !== undefined) lastError = ev.error
        if (ev.status !== undefined) env.report(ev.status, session)
      })

      let why: "timeout" | "cancelled" | undefined
      const kill = () => {
        try {
          if (child.pid !== undefined && process.platform !== "win32") process.kill(-child.pid, "SIGKILL")
          else child.kill("SIGKILL")
        } catch {
          child.kill("SIGKILL")
        }
      }
      const timer = setTimeout(() => { why ??= "timeout"; kill() }, Math.max(0, env.deadline - Date.now()))
      const onAbort = () => { why ??= "cancelled"; kill() }
      ctx.signal?.addEventListener("abort", onAbort, { once: true })
      const rlClosed = new Promise<void>((r) => rl.once("close", () => r()))
      let exitCode: number
      try {
        exitCode = await new Promise<number>((resolve) => child.once("close", (code) => resolve(code ?? 0)))
        await rlClosed
      } finally {
        clearTimeout(timer)
        ctx.signal?.removeEventListener("abort", onAbort)
      }
      if (why === "cancelled") throw abortError(ctx.signal!)
      if (why === "timeout") throw new MediaError(`${name}: timed out after ${timeoutMs}ms`, "timeout", session)

      const seen = new Set<string>()
      const candidates: string[] = []
      const add = (p: string) => {
        if (!seen.has(p)) {
          seen.add(p)
          candidates.push(p)
        }
      }
      paths.forEach(add)
      for (const m of text.matchAll(CLI_PATH_RE)) add(m[0])
      if (this.spec.folder && session !== undefined) {
        const dir = this.spec.folder(session)
        if (dir) {
          let entries: string[] = []
          try {
            entries = fs.readdirSync(dir).sort()
          } catch {
            entries = []
          }
          for (const e of entries) add(path.join(dir, e))
        }
      }
      const prefix = verb === "image" ? "image/" : "video/"
      const kept = candidates.filter((p) => isFile(p) && (mimeFromExt(p) ?? "").startsWith(prefix))
      if (kept.length > 0) {
        const g = new Gatherer(gatherCfg(this.config, "download"), name, verb, VERB_KIND[verb])
        const result: MediaResult = { files: await g.all(kept.map((p) => ({ path: p }))), status: "completed" }
        if (text !== "") result.text = text
        if (session !== undefined) result.jobId = session
        if (usage !== undefined) result.usage = usage
        return result
      }
      if (lastError !== undefined) {
        const un = this.spec.unavailable?.(lastError, verb)
        if (un !== undefined) throw new MediaUnavailableError(un, session)
        throw new MediaError(`${name}: no ${verb}: ${cap(lastError, 400)}`, "failed", session)
      }
      throw new MediaError(
        `${name}: no ${verb} file in the run (exit code ${exitCode})${text !== "" ? ": " + cap(text, 300) : ""}`,
        "failed",
        session,
      )
    } finally {
      fs.rmSync(cwd, { recursive: true, force: true })
    }
  }

  /**
   * The CLI mock (§7G CLI mock presets): no process. Writes `<gather.dir>/<client>-mock-<verb>.<png|mp4>`
   * (gather.dir default `<tmp>/toolnexus-media`) holding `toolnexus mock <verb>` and gathers it like a
   * real CLI file; text = the exact prompt the CLI would have received; jobId "mock".
   */
  private async mockRun(verb: MediaVerb, prompt: string): Promise<MediaResult> {
    const dir = this.config.gather?.dir || path.join(os.tmpdir(), "toolnexus-media")
    let file: string
    try {
      fs.mkdirSync(dir, { recursive: true })
      file = path.resolve(dir, `${this.name}-mock-${verb}${verb === "video" ? ".mp4" : ".png"}`)
      fs.writeFileSync(file, `toolnexus mock ${verb}`)
    } catch (e) {
      throw new MediaError(`${this.name}: ${(e as Error).message}`)
    }
    const g = new Gatherer(gatherCfg(this.config, "download"), this.name, verb, VERB_KIND[verb])
    return { files: await g.all([{ path: file }]), text: prompt, jobId: "mock", status: "completed" }
  }
}

const CLI_HINTS: Record<string, [string, string]> = {
  width: ["width ", " px"],
  height: ["height ", " px"],
  seconds: ["about ", " seconds long"],
  aspectRatio: ["aspect ratio ", ""],
}

/**
 * The prompt a CLI style receives (§7G CLI hint folding): `prompt`, then the present size hints in
 * canonical verb order as ` (h1, h2)`; nothing appended when none is present.
 */
export function foldMediaPrompt(verb: MediaVerb | string, args: Record<string, unknown>): string {
  const prompt = args.prompt === undefined || args.prompt === null ? "" : renderScalar(args.prompt)
  const hints: string[] = []
  for (const a of VERB_SPEC.get(verb)?.args ?? []) {
    const h = CLI_HINTS[a.name]
    const v = args[a.name]
    if (h && v !== undefined && v !== null) hints.push(h[0] + renderScalar(v) + h[1])
  }
  return hints.length === 0 ? prompt : `${prompt} (${hints.join(", ")})`
}

const str = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined)

const GROK_SPEC: CliSpec = {
  name: "grok",
  command: "grok",
  templates: {
    image: "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path.",
    video: "Generate exactly one video with your video generation tool: {prompt}. Do not write code. Reply only with the file path.",
  },
  argv: (prompt, cwd, model) => [
    "-p", prompt, "--cwd", cwd, "--output-format", "streaming-json", "--yolo", "--no-auto-update",
    ...(model ? ["-m", model] : []),
  ],
  env: { GROK_CONFIG: '{"features":{"image_gen":true,"video_gen":true}}' },
  parse(o) {
    switch (o.type) {
      case "text":
        return { text: str(o.data) ?? "" }
      case "tool_call":
        return { status: `tool ${str(o.toolName) ?? ""}` }
      case "tool_call_update": {
        const s = str(o.status)
        if (!s) return {}
        const ev: CliEvent = { status: `tool ${s}` }
        const raw = o.rawOutput as any
        if (s === "completed") {
          const p = raw?.path
          if (typeof p === "string" && p !== "") ev.paths = [p]
        } else if (s === "failed") {
          ev.error = typeof raw?.message === "string" ? raw.message : JSON.stringify(sortKeys(raw ?? null))
        }
        return ev
      }
      case "end": {
        const ev: CliEvent = { session: str(o.sessionId), status: `end ${str(o.stopReason) ?? ""}` }
        // D21: tokens + total_cost_usd map to canonical fields; total_cost_usd_ticks is kept raw, never converted.
        const u = o.usage as any
        const usage = reportedUsage({ inputTokens: u?.input_tokens, outputTokens: u?.output_tokens, costUsd: o.total_cost_usd }) ?? {}
        if (typeof o.total_cost_usd_ticks === "number" && Number.isFinite(o.total_cost_usd_ticks)) usage.raw = { total_cost_usd_ticks: o.total_cost_usd_ticks }
        if (Object.keys(usage).length) ev.usage = usage
        return ev
      }
      case "error":
        return { error: str(o.message) ?? JSON.stringify(o) }
      default:
        return {}
    }
  },
  unavailable(error, verb) {
    const l = error.toLowerCase()
    if (l.includes("zero data retention")) {
      return `grok: ${verb} blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)`
    }
    if (l.includes("usage limit")) return `grok: ${cap(error, 400)}`
    return undefined
  },
}

/** Grok CLI (§7G.10): image, video via `grok -p … --output-format streaming-json`. */
export class GrokMediaClient extends CliMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super(GROK_SPEC, config)
  }
}

const CODEX_SPEC: CliSpec = {
  name: "codex",
  command: "codex",
  templates: {
    image: "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved.",
  },
  argv: (prompt, _cwd, model) => ["exec", "--json", "--skip-git-repo-check", "-s", "workspace-write", ...(model ? ["-m", model] : []), prompt],
  parse(o, line) {
    const ev: CliEvent = {}
    if (typeof o.type === "string") ev.status = o.type
    if (o.type === "thread.started") ev.session = str(o.thread_id)
    else if (o.type === "item.completed") {
      const item = o.item as any
      if (item?.type === "agent_message" && typeof item.text === "string") ev.text = item.text
    } else if (o.type === "error" || o.type === "turn.failed") {
      ev.error = str(o.message) ?? str((o.error as any)?.message) ?? line
    }
    return ev
  },
  folder(session) {
    const home = process.env.CODEX_HOME || path.join(os.homedir(), ".codex")
    return path.join(home, "generated_images", session)
  },
  unavailable(error) {
    const l = error.toLowerCase()
    return l.includes("usage limit") || l.includes("quota") ? `codex: ${cap(error, 400)}` : undefined
  },
}

/** Codex CLI (§7G.10): image via `codex exec --json` (ChatGPT plan). */
export class CodexMediaClient extends CliMediaClient {
  constructor(config: MediaClientConfig = {}) {
    super(CODEX_SPEC, config)
  }
}

const AGY_SPEC: CliSpec = {
  name: "agy",
  command: "agy",
  templates: {
    image: "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved.",
  },
  argv: (prompt, _cwd, model) => ["-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions", ...(model ? ["--model", model] : [])],
  parse(o) {
    switch (o.event) {
      case "init":
        return { session: str(o.conversation_id), status: "init" }
      case "step_update": {
        const su = o.step_update as any
        const t = str(su?.tool_name)
        const st = str(su?.state)
        return t && st ? { status: `tool ${t} ${st}` } : {}
      }
      case "result": {
        const r = o.result as any
        const ev: CliEvent = { session: str(r?.conversation_id), text: str(r?.response), status: `result ${str(r?.status) ?? ""}` }
        const low = (ev.text ?? "").toLowerCase()
        if (r?.status === "SUCCESS" && (low.includes("quota") || low.includes("resource_exhausted"))) {
          // Live: agy reports an exhausted image quota in a SUCCESS result's text.
          ev.error = ev.text
        }
        if (r?.status !== "SUCCESS") {
          ev.error = (str(r?.error) || undefined) ?? (str(r?.error?.message) || undefined) ?? (ev.text || undefined)
            ?? JSON.stringify(sortKeys(r ?? null))
        }
        const usage = reportedUsage({ inputTokens: r?.usage?.input_tokens, outputTokens: r?.usage?.output_tokens })
        if (usage) ev.usage = usage
        return ev
      }
      case "error":
        return { error: str(o.message) }
      default:
        return {}
    }
  },
  folder: (session) => path.join(os.homedir(), ".gemini", "antigravity-cli", "brain", session),
  unavailable(error) {
    const l = error.toLowerCase()
    return l.includes("quota") || l.includes("usage limit") ? `agy: ${cap(error, 400)}` : undefined
  },
}

// ---- createMedia: routing + tools (§7G.3, §7G.6) ----------------------------------------------

type VerbInput<T> = T & MediaCallOptions
export type ImageArgs = { prompt: string; width?: number; height?: number; aspectRatio?: string; seed?: number }
export type VideoArgs = { prompt: string; image?: string; seconds?: number; aspectRatio?: string }
export type SpeechArgs = { text: string; voice?: string }
export type MusicArgs = { prompt: string; seconds?: number; instrumental?: boolean; lyrics?: string }
export type TranscribeArgs = { audio: string; language?: string }
export type CloneVoiceArgs = { name: string; samples: string[] }
export type TalkArgs = { image: string; audio?: string; text?: string; voice?: string }

function acceptedArgs(client: MediaClient, verb: string): MediaArgSpec[] {
  if (typeof client.args === "function") return client.args(verb)
  return (VERB_SPEC.get(verb)?.args ?? []).map((a) => ({ name: a.name, required: a.required === true }))
}

const isUnavailable = (e: unknown) => e instanceof MediaUnavailableError || (e instanceof MediaError && e.status === "unavailable")

/** Code-point order (plain `<` compares UTF-16 units, which misorders astral characters). */
function byCodePoint(a: string, b: string): number {
  const x = Array.from(a), y = Array.from(b)
  for (let i = 0; i < Math.min(x.length, y.length); i++) {
    const d = x[i].codePointAt(0)! - y[i].codePointAt(0)!
    if (d) return d
  }
  return x.length - y.length
}

/** D15: object keys sorted by code point at every depth; array order kept. */
function sortKeys(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(sortKeys)
  if (v && typeof v === "object") {
    const keys = Object.keys(v).sort(byCodePoint)
    return Object.fromEntries(keys.map((k) => [k, sortKeys((v as any)[k])]))
  }
  return v
}

/** The seven verbs over an ordered list of clients; falls through only on `unavailable`. */
export class Media {
  readonly clients: MediaClient[]

  constructor(clients: MediaClient | MediaClient[]) {
    const list = Array.isArray(clients) ? clients : [clients]
    if (list.length === 0) throw new MediaError("media: no clients")
    this.clients = list
  }

  supports(verb: MediaVerb | string): boolean {
    return this.clients.some((c) => c.supports(verb))
  }

  /** Every verb some source supports, in canonical verb order. */
  verbs(): MediaVerb[] {
    return MEDIA_VERBS.filter((v) => this.supports(v.verb)).map((v) => v.verb)
  }

  /** "style" when every source is a loaded style, else "client". */
  private noun(): string {
    return this.clients.every((c) => c instanceof LoadedMediaStyle) ? "style" : "client"
  }

  /** Generic entry point: route `verb` with canonical `args`. */
  async generate(verb: MediaVerb | string, args: Record<string, unknown> = {}, ctx: MediaContext = {}): Promise<MediaResult> {
    return (await this.route(verb, args, ctx)).result
  }

  private async route(verb: string, rawArgs: Record<string, unknown>, ctx: MediaContext): Promise<{ result: MediaResult; client: string }> {
    const candidates = this.clients.filter((c) => c.supports(verb))
    if (candidates.length === 0) throw new MediaError(`media: no ${this.noun()} supports "${verb}"`)
    const args = present(rawArgs)
    const canon = (VERB_SPEC.get(verb)?.args ?? []).map((a) => a.name)
    // D7/D19: a key no client can ever take fails the call at once.
    for (const key of Object.keys(args).sort(byCodePoint)) {
      if (!canon.includes(key)) throw new MediaError(`${verb}: argument "${key}" not supported`)
    }

    const messages: string[] = []
    for (const c of candidates) {
      // D19: a candidate that cannot take these args is skipped like `unavailable` — first included.
      const acc = acceptedArgs(c, verb)
      const ok = new Set(acc.map((a) => a.name))
      const bad = Object.keys(args).sort(byCodePoint).find((k) => !ok.has(k))
      if (bad !== undefined) {
        messages.push(`${c.name}: argument "${bad}" not supported`)
        continue
      }
      const requiredNames = acc.filter((a) => a.required).map((a) => a.name)
      const order = [...canon.filter((n) => requiredNames.includes(n)), ...requiredNames.filter((n) => !canon.includes(n))]
      const miss = order.find((key) => !(key in args))
      if (miss !== undefined) {
        messages.push(`${c.name}: missing required argument "${miss}"`)
        continue
      }
      const callArgs = args
      const fn = (c as any)[verb] as ((a: Record<string, unknown>, x: MediaContext) => Promise<MediaResult>) | undefined
      if (typeof fn !== "function") throw new MediaError(`media: client "${c.name}" has no ${verb} method`)
      try {
        const result = await fn.call(c, callArgs, ctx)
        if (result.style === undefined) result.style = c instanceof LoadedMediaStyle ? c.id : c.name
        if (result.model === undefined && c instanceof LoadedMediaStyle) {
          const model = c.config.models?.[verb as MediaVerb] || c.config.model
          if (model) result.model = model
        }
        return { result, client: c.name }
      } catch (e) {
        if (!isUnavailable(e)) throw e
        messages.push((e as Error).message)
      }
    }
    throw new MediaUnavailableError(`media: no client available for "${verb}": ${messages.join("; ")}`)
  }

  private typed(verb: MediaVerb, input: object): Promise<MediaResult> {
    const { providerOptions, signal, deadline, progress, ...args } = input as Record<string, any>
    const ctx: MediaContext = {}
    if (providerOptions !== undefined) ctx.providerOptions = providerOptions
    if (signal !== undefined) ctx.signal = signal
    if (deadline !== undefined) ctx.deadline = deadline
    if (progress !== undefined) ctx.progress = progress
    return this.generate(verb, args, ctx)
  }

  image(input: VerbInput<ImageArgs>) { return this.typed("image", input) }
  video(input: VerbInput<VideoArgs>) { return this.typed("video", input) }
  speech(input: VerbInput<SpeechArgs>) { return this.typed("speech", input) }
  music(input: VerbInput<MusicArgs>) { return this.typed("music", input) }
  transcribe(input: VerbInput<TranscribeArgs>) { return this.typed("transcribe", input) }
  cloneVoice(input: VerbInput<CloneVoiceArgs>) { return this.typed("cloneVoice", input) }
  talk(input: VerbInput<TalkArgs>) { return this.typed("talk", input) }

  /** One Tool (source "media") per verb any client supports, in `verbs.json` order (§7G.6). */
  tools(): Tool[] {
    const out: Tool[] = []
    for (const v of MEDIA_VERBS) {
      const first = this.clients.find((c) => c.supports(v.verb))
      if (!first) continue
      const acc = new Map(acceptedArgs(first, v.verb).map((a) => [a.name, a]))
      const mapped = v.args.filter((a) => acc.has(a.name))
      const properties: Record<string, unknown> = {}
      for (const a of mapped) {
        properties[a.name] = a.type === "array"
          ? { type: "array", items: { type: a.items }, description: a.description }
          : { type: a.type, description: a.description }
      }
      const inputSchema: JSONSchema = { type: "object", properties, required: mapped.filter((a) => acc.get(a.name)!.required).map((a) => a.name) }
      const verb = v.verb
      out.push({
        name: v.tool,
        description: v.description,
        inputSchema,
        source: "media",
        concurrencySafe: () => true,
        execute: async (args: Record<string, unknown>, ctx?: ToolContext): Promise<ToolResult> => {
          try {
            const { result: r, client } = await this.route(verb, args ?? {}, { signal: ctx?.signal })
            const metadata: Record<string, unknown> = { source: "media", verb, client }
            if (r.jobId !== undefined) metadata.jobId = r.jobId
            metadata.status = r.status
            if (r.usage !== undefined) metadata.usage = r.usage
            if (VERB_KIND[verb] === "text") return { isError: false, output: r.text ?? "", metadata }
            return { isError: false, output: toolOutput(verb, r.files), parts: r.files.map(filePart), metadata }
          } catch (e) {
            return { isError: true, output: e instanceof Error ? e.message : String(e) }
          }
        },
      })
    }
    return out
  }
}

const WORD: Record<MediaFile["type"], string> = { image: "image", file: "video", audio: "audio" }

function toolOutput(verb: MediaVerb, files: MediaFile[]): string {
  const word = files.length ? WORD[files[0].type] : VERB_KIND[verb] === "audio" ? "audio" : VERB_KIND[verb]
  const lines = files.map((f) => f.path ?? f.url ?? `inline ${f.mimeType} ${f.data ? Buffer.from(f.data, "base64").byteLength : 0} bytes`)
  return `${word} ready: ` + lines.join("\n")
}

/** §7G.6: a part never holds a path — local files are read now as data. */
function filePart(f: MediaFile): ContentPart {
  const part = partOf(f)
  if (f.path !== undefined) PART_PATH.set(part, f.path)
  return part
}

function partOf(f: MediaFile): ContentPart {
  if (f.data !== undefined) return { type: f.type, mimeType: f.mimeType, data: f.data }
  if (f.url !== undefined && isHttp(f.url)) return { type: f.type, mimeType: f.mimeType, url: f.url }
  const local = f.path ?? (f.url?.startsWith("file://") ? f.url.slice("file://".length) : undefined)
  return { type: f.type, mimeType: f.mimeType, data: local !== undefined ? fs.readFileSync(local).toString("base64") : "" }
}

/** §7G.12 D20: the local path behind a media tool's part, kept off the part itself (§1B). */
const PART_PATH = new WeakMap<ContentPart, string>()

/** The saved file's absolute path for a part a media tool produced, if it has one (D20). */
export function mediaPartPath(part: ContentPart): string | undefined {
  return PART_PATH.get(part)
}

/** Route the seven verbs over one client or an ordered list (§7G.3). */
export function createMedia(clients: MediaClient | MediaClient[]): Media {
  return new Media(clients)
}

/** One-shot sugar: `createMedia(loadMediaStyle(style, config)).generate(verb, args)`. */
export function generateMedia(
  style: MediaStylePreset | string,
  verb: MediaVerb | string,
  args: Record<string, unknown> = {},
  config: MediaClientConfig = {},
): Promise<MediaResult> {
  return createMedia(loadMediaStyle(style, config)).generate(verb, args)
}
