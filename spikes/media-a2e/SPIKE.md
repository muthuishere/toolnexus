# Spike: media generation in Go against a2e (ADR 0036)

Question: does "one engine, two faces (typed verbs + tools), styles as JSON" hold up against a
real provider, and what does the spec need that the docs didn't tell us?

## What's here
- `media.go` — the engine (~300 lines): JSON style → normalised-arg mapping (dot-path set incl.
  `references.0.speaker`) → start → auto-poll (interval/timeout/cancel/progress) → `files[]`.
  Typed verbs `Image/Video/Speech`; `Tools()` exposes `generate_image|video|speech` as real `tn.Tool`s.
- `styles/a2e.json` — the only a2e knowledge. No vendor code.
- `media_test.go` — hermetic fake provider: completes, fails→tool error bytes, timeout, cancel,
  arg mapping. `go test ./...`
- `main.go` — live: `sec run VIDEO_GENERATION_TOKEN=A2E_API_KEY -- go run . [image|video|speech|all]` (spends credits).

## Findings (live, 2026-11-02)
1. **Works first try** for image: 5 polls @3s, completed in ~26 s, JPEG URL.
2. **Docs vs reality**: text2image `start` returns `data` as an **array** (`data.0._id`), image2video
   returns an object (`data._id`). The style needs per-verb `idPath` — confirmed necessary.
3. **Base path**: endpoints live under `/api/v1/...`, not `/v1/...` as the SKILL page implied.
4. **Error envelope**: a2e can answer HTTP 200 with `code != 0`. A style needs an optional
   `errorEnvelope: {codePath, okValue, messagePath}`; the spike hard-codes `code`. → spec field.
5. **Result URLs expire** (host `3days-apac…`). The `download` option is not optional-nice, it's
   needed for anything persisted. → keep in v1.
6. **MIME from extension** was right (`.jpg` → `image/jpeg`), despite `kind: image` default png.
7. Normalised `voice` → `references.0.speaker` proves arg maps must support nested/array paths.

(Video + speech results appended below once the live run finishes.)

## Live run 2 (all)
- image: completed in 29 s (typed verb).
- speech: completed in 33 s → `audio/wav` (MIME from extension was right; `kind: audio` default
  would have said mp3). Link host is `30days-…` — audio lives longer than images.
- video via **tool face** first failed: `HTTP 400 … prompt and negative_prompt must required when
  lora is not set`. The OpenAPI marks `negative_prompt` optional — it isn't. The failure surfaced
  correctly as `ToolResult{isError:true}` (the model would see it and could retry).
  Fix lives entirely in the style (`defaults.negative_prompt`) — **no code change**, which is the
  whole point of styles-as-data. → finding 8: styles need a live smoke per verb; docs lie.
- Side note: `CreateToolkit(Options{})` registers the builtin coding tools (bash, …) by default,
  so `generate_*` sit next to them. Expected per §4A, but worth knowing for a media-only host.
- video rerun (after the style-only fix): **completed in ~238 s** (~4 min, statuses `sent`→`completed`)
  via the tool face → `ToolResult{parts:[{type:"file", mimeType:"video/mp4", url}]}`, then chained
  image→video URL worked. `sent` is a non-terminal status not in the docs' list → the engine must
  treat any status not in `done`/`failed` as "keep polling" (it does).

## Verdict
The design holds against a real provider for all three verbs. Spec additions the spike forced:
per-verb `idPath`, optional `errorEnvelope`, `download` in v1, MIME-from-extension before kind
default, unknown status = keep polling, and a live smoke per style verb before a preset ships.
