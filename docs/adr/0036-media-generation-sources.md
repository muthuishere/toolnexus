# ADR 0036 — Media generation: one engine, typed verbs, tools, styles as data

- Status: **ACCEPTED** (owner, 2026-11-02, after the a2e spike)
- Scope: all eight ports
- Spike: `spikes/media-a2e/` (Go, live against a2e: image, video, speech)
- Change: `openspec/changes/add-media-generation`

## Context

toolnexus can **carry** media (`ContentPart`, §1B; §8A emission) but cannot **make** it. Image,
video, speech, transcription, voice cloning and talking-head generation all reduce to two call
shapes:

1. **sync** — one request, the result is in the response (most TTS, OpenAI images).
2. **async** — start a job → get an id → poll until a terminal status → read result URL(s)
   (a2e, Replicate, fal, Runway, ComfyUI).

**Live conversation (realtime duplex audio) is out of scope** (owner, 2026-11-02): it is a
long-lived stream, not a call that returns a result — neither a tool nor reachable through MCP.

## Prior art (docs checked 2026-11-02)

- **Vercel AI SDK** — `generateImage`, `generateSpeech`, `transcribe`; `experimental_generateVideo`
  with `poll:{intervalMs:5000, timeoutMs:600000}`, webhook alternative, `abortSignal`, URL→bytes
  download capped at 2 GiB. One code adapter package per provider.
- **Replicate** — `Prefer: wait` (≤60 s) then poll `urls.get` every 1–2 s, or webhook; `Cancel-After`.
- **fal** — `subscribe()` blocks and polls; `queue.submit/status/result/cancel`; SSE status; webhooks.
- **ComfyUI** — `POST /prompt` → `/history/{id}` → `/view`; history shape only in `server.py`.

Everyone who ships video converged on *submit → auto-poll → one awaited call*. Nobody exposes it as
an agent tool, and nobody makes providers data instead of code. Those two are our difference.

## Decision

**One engine, two faces, styles as data, in all eight ports.**

```
                 ┌── function face ─▶ media.image({prompt}) / video / speech … (host decides)
media engine ────┤
(style + poll)   └── tool face ─────▶ generate_image / generate_video / … (LLM decides)
```

1. **Typed verbs, not endpoint names.** `createMedia(config)` returns an object with fixed verbs:
   `image`, `video`, `speech`, `transcribe`, `cloneVoice`, `talking`. Arguments are normalised and
   each style maps them to provider fields. Stringly-typed `call("image_to_video", …)` was rejected
   by the owner.
2. **Tool face.** Every verb a style supports is exposed as a provider-independent tool
   (`generate_image`, `generate_video`, `generate_speech`, `transcribe_audio`, `clone_voice`,
   `talking_video`). Switching provider never renames a tool. Media is a tool source, **not a client**.
3. **Styles are JSON.** Vendor presets (`a2e`, …) live in `examples/media-styles/` and are loaded by
   path — no vendor names in the public API (scope ADR). A config may route verbs to different styles.
4. **Automatic polling** with Vercel's defaults (5 s / 10 min), cancel via the port's native
   mechanism, progress callback. Any status not in `done`/`failed` means keep polling.
5. **`RunResult.files`** — the client loop returns every media part produced during a run, in call
   order, tagged with `from` (tool name) and `callId`; `toolCalls[].parts` carries per-call parts.
   `RunResult.text` never contains markup — rendering is the host's job.
6. **Rejected:** a separate media client; per-provider code adapters; injecting image tags into
   text; realtime/live conversation.

## Spike findings that shaped the spec (live, a2e, 2026-11-02)

| # | Finding | Spec consequence |
|---|---|---|
| 1 | text2image `start` returns `data` as an **array**, image2video as an object | per-verb `idPath` with numeric segments |
| 2 | Real paths are `/api/v1/…`, not the documented `/v1/…` | `baseUrl` per style |
| 3 | HTTP 200 with `code != 0` signals errors | optional `errorEnvelope {codePath, okValues, messagePath}` |
| 4 | Result URLs expire (`3days-…`, `30days-…` hosts) | `download` is a planned follow-up, named as a gap |
| 5 | Speech came back `.wav`, not mp3 | MIME from URL extension first, kind default second |
| 6 | Video reported `sent`, a status not in the docs | unknown status ⇒ keep polling |
| 7 | `negative_prompt` documented optional, actually required | fixed in the style alone; every preset verb needs a live smoke |
| 8 | `voice` → `references.0.speaker` | arg map targets are dot-paths that create maps and arrays |

Measured: image ~26–29 s, speech ~33 s, image→video ~238 s, all through one ~300-line engine.

## Not in v1 (named so the omission stays visible)

Download of expiring URLs, local-file upload flows, ComfyUI graph injection (needs a spike against
a real server), webhooks, price quotes, §10 suspend for long jobs, streaming `file` events.

## Consequences

- SPEC §7G (engine + verbs + tool face) and §8 `RunResult.files`.
- One shared conformance fixture (`conformance/media/cases.json`) replayed by a fake server in every port.
- Adding a provider is a reviewed JSON file plus one live smoke per verb, never a release.
