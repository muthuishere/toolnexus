# Media generation — one engine, typed verbs, tools, styles as data

## Why
toolnexus carries media (§1B) but cannot make it. Users want image, video, speech, transcription,
voice cloning and talking-head generation from "a common configuration people can just use".
ADR 0036 (ACCEPTED 2026-11-02) settled the design; the Go spike `spikes/media-a2e/` proved it live
against a2e (image ~29 s, speech ~33 s, image→video ~238 s) and surfaced 8 facts the provider docs
got wrong, all now in the contract.

## What changes
- SPEC §0 item 14 and new **§7G**: `loadMediaStyle`, `createMedia(styles, {onProgress})`, verbs
  `image|video|speech|transcribe|cloneVoice|talking` returning `MediaResult`, `generate(verb,args)`,
  `media.tools()` (source `"media"`), `createToolkit({ media })`.
- Styles are JSON: sync or async (start → auto-poll → result), dot-paths, `${ENV}` headers,
  optional `errorEnvelope`. Presets in `examples/media-styles/` (a2e first). No vendor in any port.
- §8 `RunResult.files` and `toolCalls[].parts`.
- `conformance/media/` — `verbs.json`, `cases.json` (19 engine cases), `schemas.json`.

## Not in this change
Download of expiring URLs, local uploads, ComfyUI graph injection, webhooks, quotes, §10 suspend
for long jobs, streaming `file` events, realtime/live conversation (rejected).
