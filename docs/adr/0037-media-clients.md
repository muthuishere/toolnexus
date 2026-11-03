# ADR 0037 — Media clients: one interface, named API and ACP clients, seven verbs

- Status: **ACCEPTED** (owner, 2026-11-03, after the Go spike in `spikes/media-clients/`)
- Builds on: ADR 0036 (engine, tools, `RunResult.files`), PR #123
- Scope: all eight ports
- Supersedes in ADR 0036: "styles are JSON and the user loads them" as the public API, and the
  "no vendor names in the public API" rule **for media clients only**

## Context

ADR 0036 made providers JSON styles loaded by path. The owner found that too heavy for users
(the a2e style is ~100 lines of endpoints, dot-paths and poll rules) and asked for:

- named clients with a small config — key, model, optional url — instead of JSON files;
- local subscription CLIs (Grok, Codex) as media sources over ACP, separate from HTTP APIs;
- a custom client interface for anything not built in;
- the same verbs and tools whichever client is used; tools stay opt-in.

A spike (`spikes/media-acp/`, 2026-11-03) drove `grok agent stdio` for one image: the file arrives
as a local path in the image tool's completed `tool_call_update` (`rawOutput.path`), inside Grok's
own session folder — not as an ACP `image`/`resource_link` block, and not in the working directory.

## Decision

### 1. Seven verbs

| Verb | Tool | Notes |
|---|---|---|
| `media.image` | `generate_image` | |
| `media.video` | `generate_video` | text-to-video and image-to-video |
| `media.speech` | `generate_speech` | TTS; `voice` is a name or id, including cloned voices |
| `media.music` | `generate_music` | **new** |
| `media.transcribe` | `transcribe_audio` | |
| `media.cloneVoice` | `clone_voice` | returns a voice id usable by `speech` |
| `media.talk` | `talking_video` | face image + text or audio → video (was `talking`) |

### 2. `MediaClient` is the interface

Every verb is optional; a client implements what it supports and `supports(verb)` says which.
Each verb receives `ctx = { signal, deadline, progress }` and returns `MediaResult
{files, text?, jobId?, status}` (unchanged from ADR 0036). Port-native shapes: Go interface (plus
an embeddable "unsupported" base), Python Protocol, Java/C# interface with default methods,
Rust trait with defaults, Elixir behaviour with optional callbacks, Clojure map of functions.

### 3. Two shared bases, many small named clients

```
MediaClient
├─ ApiMediaClient    HTTP engine from ADR 0036: polling, deadline, redirect safety, error rules
│   ├─ A2EMediaClient          image, video, speech, talk
│   ├─ ElevenLabsMediaClient   speech, cloneVoice, transcribe, music
│   ├─ OpenAIMediaClient       image, video, speech, transcribe (+ url → any compatible server)
│   ├─ OpenRouterMediaClient   image (many models by id); video/audio only if a spike shows them
│   └─ FalMediaClient          hosted image/video/audio models by id (Kling, Seedance, Veo, Flux…)
├─ CliMediaClient    run a local CLI once headless, read its JSON lines, collect files, kill on cancel
│   ├─ GrokMediaClient         image, video   (`grok -p --output-format streaming-json`, Grok subscription)
│   └─ CodexMediaClient        image          (`codex exec --json`, ChatGPT plan)
└─ user classes implementing MediaClient
```

The JSON style engine stays, but internal: it is how API clients are defined, not what users write.
A named CLI client sets itself up for media internally (media tools only, auto-approve) — no
`profile` option; `model` is passed as the CLI's own model flag. ACP was spiked and dropped: Grok's
headless mode gives the file path in one run, and the `codex-acp` adapter (0.16.0) lagged Codex and
picked models the plan rejected. A generic ACP client is not in v1.

### 4. One config shape for every client

| field | API clients | ACP clients |
|---|---|---|
| `model` | ✓ | ✓ (passed to the CLI's own model setting) |
| `key` | ✓, defaults to the provider's env var | – |
| `url` | ✓ optional override | – |
| `command` | – | optional, when the CLI is not on PATH |
| `loop` | `{intervalMs, timeoutMs, onProgress}` | `{timeoutMs, onProgress}` |
| `gather` | `{mode: url\|download\|inline, dir, maxBytes, keep}` | same; default `download` |
| `httpClient` | ✓ | – |

`gather.download` closes ADR 0036's "expiring URLs" gap. ACP clients default to `download`
because the file lives in the CLI's session folder.

### 5. Composition and tools

`createMedia(client | client[])` routes each verb to the first client that supports it and
returns the seven verbs. `createToolkit({ media })` is opt-in and registers tools only for the
supported verbs (existing enable/disable lists still apply). `RunResult.files` is unchanged.

### 6. Scope rule change

Provider names now appear in the public API as client class names. Accepted cost: a provider
change or addition is a library release in eight ports. Mitigation: built-in clients ship only
after a live spike; everything else goes through the custom interface.

## v1 list and order

Each client lands only after a small live spike, smallest first:

1. A2E (done in #123) · 2. ElevenLabs · 3. OpenAI · 4. OpenRouter · 5. Fal (key `FAL_API_KEY`) ·
6. Grok CLI (image, video) · 7. Codex CLI (image).

Not built in: Replicate (fal is cheaper and faster for the models we want; Replicate's strength,
hosting your own fine-tunes, is not needed) — reachable through a custom client.

Later, if asked: Gemini direct, Grok API, Seedance/Kling direct, Runway, ComfyUI (needs a server).

## Open

- How `transcribe`/`cloneVoice` fit providers that lack them — `supports` returns false; tools omit them.
- `music` arguments (`prompt`, `seconds`, `instrumental`, `lyrics`) need pinning from real APIs.
- Grok session-folder lifetime and how subscription usage is charged.
- PR #123's six-verb, JSON-style API is unreleased; it is reshaped on this branch before any release.

## Grok video and privacy (what we learned, 2026-11-03)

Grok Build's video tools obey the account's **coding-data setting**, not the API console:

- `console.x.ai` is the pay-per-call **API** console (keys, credits). It does **not** control the
  Grok CLI. The CLI uses the SuperGrok subscription login; personal accounts are a "team of one"
  (`zdr_team` in Grok's logs).
- Inside the CLI, `/privacy` opens **Settings > Coding data, retention, and training**.
  **Opt out** = Zero Data Retention (ZDR) on: video tools fail with "Video generation tools are
  unavailable under zero data retention (ZDR)" (the API's own error: "Zero Data Retention teams
  must provide output.upload_url"). **Opt in** = xAI may retain and train on CLI coding data;
  video works. Restart `grok` after changing it.
- xAI documents a third way — a user bucket in `~/.grok/managed_config.toml`
  (`[tools.zdr_video_output_s3]`, see docs.x.ai/build/settings/zdr-video-storage). We tried it on a
  personal account with a valid Hetzner Object Storage bucket, in `managed_config.toml` (with
  `features.managed_config = false`, since console sync otherwise rewrites that file) and in
  `config.toml`. Grok 1.0.70 still refused within 0.4 s; the source (xai-org/grok-build,
  `media_tool_config.rs`) reads the table from the merged config, so the refusal comes from the
  account side. Reddit reports the same on a one-person team
  (r/GrokBuild "Grok Build Can't Create Videos Says ZDR Enabled (It's Not)"), unresolved.
  The bucket must be internet-reachable (xAI uploads to it), so a Mac-only MinIO cannot work.
- Verified after **Opt in**: one video through `GrokMediaClient` in 53 s (still image, then
  `image_to_video`); the `.mp4` path arrives in the completed tool result, same as images, and the
  client copies it out of `~/.grok/sessions/...`.

`GrokMediaClient` never edits the user's Grok settings. Under ZDR it returns `unavailable` with
`grok: video blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video
storage (docs/media#grok-video-zdr)`, and `createMedia` falls through to the next video client.
The media docs page gets a section with exactly these steps.

## Spike results (spikes/media-clients, 2026-11-03)

| Client | Verb | Result | Time | How the file came back |
|---|---|---|---|---|
| ElevenLabs | speech (incl. a cloned voice by name) | works | 1.9 s | mp3 bytes in the body |
| OpenAI | image (`gpt-image-1-mini`, low) | works | 9.3 s | base64 `data[].b64_json` |
| OpenRouter | image (`gemini-2.5-flash-image`) | works | 6.5 s | data URL in `message.images[]` |
| fal | image (`flux/schnell`) | works | 4.1 s | URL, then downloaded |
| Codex CLI | image | works | 30–77 s | local path only in the reply text (`~/.codex/generated_images/…`) |
| Grok CLI | image / video | works (video after Opt in) | ~24 s / 53 s | local path in the completed tool result (`rawOutput.path`) |
| A2E | image, video, speech | works (PR #123) | 26 s – 4 min | URL (expires 3–30 days) |

Consequences for the design:

1. **`unavailable` status.** No credits (HTTP 402), quota (429), ZDR or plan limits return
   `unavailable`; `createMedia` falls through to the next client only on that. Any other error
   returns as is.
2. **Wrong-kind guard.** A CLI turn can end normally with the wrong media (Grok produced only a
   still image when video was blocked); a run with no file of the verb's kind fails, with the
   error taken from the failed tool result.
3. **Per-client file finder** on the CLI base (Grok: tool result path; Codex: paths in text plus
   its images folder).
4. **`gather` per result kind.** Bytes, base64 and data URLs have no URL to keep, so `url` mode
   saves them; URLs follow the mode; CLI local paths are copied out by default because the CLI
   owns them.
