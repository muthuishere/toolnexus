# Media clients — one interface, named API and CLI clients, seven verbs

## Why
ADR 0036 (PR #123) made every media provider a JSON style the user loads by path. The owner found
that too heavy (the a2e style is ~100 lines of endpoints, dot-paths and poll rules) and asked for
named clients with a small config, local subscription CLIs (Grok, Codex) as media sources, a custom
client interface, and the same verbs and tools whichever client serves them. ADR 0037 (ACCEPTED
2026-11-03) settled the design; the Go spike `spikes/media-clients/` proved ElevenLabs speech,
OpenAI image, OpenRouter image, fal image, Codex image and Grok image/video live, and surfaced the
`unavailable` status, the CLI wrong-kind guard and the four ways results come back.

PR #123's API is unreleased, so it is reshaped here before any release.

## What changes
- SPEC §0 item 14 and **§7G rewritten** (§7G.1–§7G.13, incl. §7G.12 edge rules D1–D17):
  - seven verbs `image|video|speech|music|transcribe|cloneVoice|talk` — `music` is new, `talk`
    replaces `talking` (tool name `talking_video` unchanged), tool `generate_music` is new;
    canonical args gain `language` (transcribe), `text`/`voice` (talk), music's
    `prompt|seconds|instrumental|lyrics`; input args accept URL, `file://` or a local path.
  - `MediaClient` interface (optional verbs, `supports`, optional `args`), `ctx {signal, deadline,
    progress, providerOptions}`, typed media errors, new status **`unavailable`**.
  - `createMedia(client | [client])` routing: first supporting client, fall-through **only** on
    `unavailable`, joined message when all are unavailable, `media: no client supports …`.
  - one config shape `{model, models, key, url, command, loop, gather, httpClient}`;
    `gather {mode url|download|inline, dir, maxBytes, keep}` with a pinned matrix per result shape.
  - built-in API clients `A2EMediaClient`, `ElevenLabsMediaClient`, `OpenAIMediaClient`,
    `OpenRouterMediaClient`, `FalMediaClient`, each with pinned endpoints, bodies, parsing, defaults.
  - `CliMediaClient` base + `GrokMediaClient`, `CodexMediaClient` (headless JSON lines, wrong-kind
    guard, failed-tool errors, file finders, copy-out, kill on cancel/timeout, ZDR message).
  - the JSON style engine becomes `styleMediaClient(style, config?)`; `loadMediaStyle` stays public.
  - tool metadata gains `client`; downloaded/inline files become `data` parts (§1B: a part never
    holds a path).
- `conformance/media/`: `verbs.json` (7 verbs), `schemas.json` (talk), `cases.json` (34 → 44),
  new `clients.json` (47), `cli.json` (18) with the portable `fake-cli/fake-cli.sh`, and
  `routing.json` (12 cases + 1 schema case + empty-list check).
- `examples/media-styles/a2e.json`: `name`, the `talk` verb (unverified), embedded by every port.
- CHANGELOG `## Unreleased` entry rewritten for media clients.

## Not in this change
Provider-side cancellation, webhooks, price quotes, §10 suspension for long jobs, streaming
`file` events, ComfyUI, a generic ACP media client, Replicate and other direct providers
(Gemini, Grok API, Seedance/Kling direct, Runway), OpenRouter video/audio, image editing.

## Impact
All eight ports (`js`, `python`, `golang`, `java`, `csharp`, `elixir`, `clojure`, `rust`): the
media module is reshaped (breaking against the unreleased #123 API only). Docs: the media guide,
per-port API reference, cookbook recipe, and the `grok-video-zdr` section.
