# Spike: media clients (ADR 0037) in Go

The question: does the ADR 0037 design hold against real providers and local CLIs? The design is
one `MediaClient` interface with seven optional verbs, small named clients, one config shape, and
`createMedia` routing.

**Verdict: yes, and the CLI side is simpler than the ADR assumed.** It needs no ACP. Both local CLIs
work as a plain headless run that reads a JSON-lines stream.

## What's here (stdlib only)
- `media.go` holds the verbs, `Args`, `File`, `MediaResult` and the `MediaClient` interface, with an
  embeddable `Unsupported` base. It also has `Config{Model,Key,URL,Command,Loop,Gather,HTTP}`,
  the gather helpers `fromBytes`, `fromURL` and `fromLocal`, `ErrUnavailable`, and `CreateMedia`.
  `CreateMedia` routes to the first client that supports a verb, and **falls through only on
  `unavailable`**.
- `api.go` holds `ApiBase` (do + poll; 402, and 429 quota/credit errors, become `ErrUnavailable`)
  plus the ElevenLabs, OpenAI, OpenRouter, Fal and A2E clients.
- `cli.go` holds `CliMediaClient`. It spawns the CLI once in headless JSON mode, parses each line
  with a per-client `Parse` into an `Event{Session,Text,Paths,Err,Status}`, and then finds files.
  `GrokMediaClient` and `CodexMediaClient` are each about 50 lines: the argv, the prompt templates,
  `Parse`, an optional `Folder`, and an `Unavailable` matcher.
- `media_test.go` is hermetic. It covers fall-through on `unavailable` only (a plain failure
  does not fall through), 402 → unavailable, and a replay of the live Grok headless capture
  through the parser: path found, ZDR error classified.
- `main.go` runs one call per subcommand. Keys come only through `sec run <KEY> -- ./out/mc <cmd>`.
  Generated files and raw captures go to `out/` (gitignored).

## Live results (2026-11-03, one call each, cheapest settings)

| client | verb | works? | time | how the file came back |
|---|---|---|---|---|
| ElevenLabs | speech, default voice | **yes** | 1.9 s | **bytes** in the HTTP body (mp3 22 kHz/32 kbps) |
| ElevenLabs | speech, voice `"muthu english"` | **yes** | 1.9 s | bytes; the name was resolved to an id with a free `GET /v2/voices?search=` |
| OpenAI | image (`gpt-image-1-mini`, quality low, 1024²) | **yes** (after the owner's credit top-up) | 9.3 s | **base64** in `data[].b64_json` (PNG 1024²) |
| OpenRouter | image (`google/gemini-2.5-flash-image`, chat + `modalities:[image,text]`) | **yes** (after credits) | 6.5 s | **data URL** in `choices[].message.images[].image_url.url` (PNG 1024²) |
| fal | image (`fal-ai/flux/schnell`, 4 steps, square) | **yes** | 4.1 s (1 s in queue) | **URL** in `images[].url`, then downloaded |
| Codex CLI (`codex exec --json`) | image | **yes** (two runs) | 30 s / 77 s | **local path** in the final agent text only: `~/.codex/generated_images/<thread_id>/exec-<uuid>.png`. No event carries it, so the client also globs that folder |
| Grok CLI (`grok -p … --output-format streaming-json --yolo`) | video | **no: account (ZDR)**, now `status=unavailable` | 37 s, $0.041 | see the Grok section below |
| Grok over ACP (earlier, same account) | video | **no: ZDR** | 30–41 s | same tool results as headless |
| A2E · OpenAI video · fal video | – | compile only | – | URL / bytes (`/videos/{id}/content`) / URL |

**Grok video, which is still blocked here.** Both the ACP run and the headless run on this machine
got this from the video tool: `"Video generation tools are unavailable under zero data retention
(ZDR). To enable, either turn off /privacy mode or supply a user-hosted storage bucket"`. That
happened after the owner's fix as well, so the fix has not reached this machine's grok login yet,
or ZDR is set on the team or org. `~/.grok/config.toml` has no privacy override. The client now
reports this cleanly as `unavailable`, so `createMedia` would try the next video client. Per the
coordinator's instruction, I made only one more video call. Where the video will arrive is
therefore **inferred, not observed**. Grok made a still first (`image_gen`), and its path came back
as `rawOutput.path` in the completed `tool_call_update`. The `reference_to_video` result is the same
kind of tool update, under `~/.grok/sessions/<cwd>/<session>/` (presumably `videos/`). The parser
takes any `rawOutput.path` and the verb-kind filter keeps only `video/*`, so no code change should
be needed. Re-run `./out/mc grok-video` once ZDR is off to confirm.

## Why ACP was dropped
- **Codex:** `@zed-industries/codex-acp` 0.16.0 is behind the server. It defaulted to `gpt-5.5`,
  rejected `gpt-5.4-mini` ("not supported when using Codex with a ChatGPT account"), and failed to
  decode the models list (`unknown variant max`). `codex exec --json` works with the installed
  CLI 0.160.
- **Grok:** headless `--output-format streaming-json` emits the **same** `tool_call_update` objects
  (with `rawOutput.path` or `rawOutput.message`) as ACP, plus a final `end` line with `sessionId` and
  `stopReason`. One headless run gives the file path reliably, so ACP adds a JSON-RPC handshake,
  permission answering, and vendor-notification noise for no gain.
- **What headless loses:** a mid-run `session/cancel` (we kill the process instead, which is fine for
  one-shot media) and permission prompts (`--yolo` auto-approves, which is what a media client wants).

## Findings
1. **Results come back four ways**: bytes (ElevenLabs, OpenAI video content), base64 (OpenAI image),
   a data URL (OpenRouter), a URL (fal, A2E), or a local path (Grok, Codex). For anything but a URL,
   `gather.mode=url` is meaningless, so the spike falls back to `download`. For a local path, `url`
   means `file://` into storage the CLI owns, with no lifetime guarantee.
2. **A CLI run can "succeed" with the wrong media.** Grok's video attempt makes a still first and
   then ends with `end_turn` even though the video tool failed. The base must keep only files of the
   verb's kind and fail when none remain.
3. **The real error is in the tool result**, not the turn: `tool_call_update.status=failed`,
   `rawOutput.message`. The base surfaces it, and a per-client matcher turns account preconditions
   into `unavailable`.
4. **`unavailable` is a real category.** Three of the first six live calls hit account preconditions:
   OpenAI quota (429), OpenRouter's $1 minimum for media (402), and Grok ZDR. A host wants to fall
   through on these and not on a bad prompt. The fall-through test pins that.
5. **Finding files is per client.** Grok reports the path in a structured tool result. Codex only
   names it in the reply text plus a per-thread folder. The base does a generic absolute-media-path
   scan of the text, a per-client `Parse`, and an optional per-client `Folder`.
6. **ElevenLabs voice by name** costs one free list call. Its JSON is snake_case (`voice_id`).
   An untagged Go struct silently gave an empty id, a parity trap worth a test in every port.
7. **Cost and speed:** fal is the fastest (about 4 s). OpenAI `gpt-image-1-mini` at low quality is
   about 9 s. A Grok headless run is about 37 s at about $0.04 reported (the model detours: it reads
   its `imagine` skill and makes a still first). Codex is 30–77 s, with about 81k input tokens per
   image on the subscription.
8. **Config actually needed.** API clients need `key` (with an env default), `model` and `url`;
   `loop.intervalMs` only matters for fal, A2E and OpenAI video. CLI clients need `command` and
   `model` (passed as `-m`), plus internal env and prompts. `Loop.OnProgress` was enough for both
   (poll statuses and CLI events).

## Final changes for ADR 0037
1. **§3 tree:** replace `AcpMediaClient` with **`CliMediaClient`**: run the CLI once headless, parse
   JSON lines, find files. The named clients become `GrokMediaClient` (`grok -p --output-format
   streaming-json --yolo`) and `CodexMediaClient` (`codex exec --json`). Drop "ACP" from the names,
   and remove codex-acp from the design. A generic ACP client is not in v1. Add it later only if an
   agent exists that has no headless mode.
2. **§3 CLI base rules:**
   - keep only files of the verb's kind, and fail when none remain;
   - take the error from a failed tool result;
   - per-client `parse` plus an optional `folder`, plus a generic path scan of the final text;
   - copy files out of the CLI's storage under `gather.download` (the default).
3. **§2 / §5 status:** add `unavailable` beside `completed|failed|timeout|cancelled` for account
   preconditions (402, quota 429, privacy/ZDR, usage limits). **`createMedia` tries each supporting
   client in order and moves on only when a client is `unavailable`.** Any other failure returns as is.
4. **§4 config:** CLI clients take `model` and `command` only. Their env and prompts are internal (no
   `profile`). State the gather matrix: bytes, base64 and data URL go to download/inline; a URL can
   stay a URL; a local path becomes `file://` or is copied.
5. **v1 order:** ElevenLabs, OpenAI, OpenRouter, fal and Codex are proven live. Grok image is proven
   (earlier spike plus the headless still). **Grok video needs ZDR off on the account**, so document
   it and re-run one video before the client ships. A2E is proven in #123.
6. **Open → closed:** the "Codex returns bytes or a path?" question is answered: a path, in text only.

## Re-run
```
go build -o out/mc . && go test ./...
sec run ELEVENLABS_API_KEY -- ./out/mc elevenlabs-speech "muthu english"
sec run OPENAI_API_KEY -- ./out/mc openai-image
sec run OPENROUTER_API_KEY -- ./out/mc openrouter-image
sec run FAL_API_KEY -- ./out/mc fal-image
./out/mc codex-image
./out/mc grok-video        # needs ZDR off
```
