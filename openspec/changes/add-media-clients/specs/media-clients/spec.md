## ADDED Requirements

### Requirement: MediaClient is the provider interface
The library SHALL define a `MediaClient` with a `name`, `supports(verb)`, an optional `args(verb)` and an optional method per verb taking `(args, ctx)` with `ctx = {signal, deadline?, progress?, providerOptions?}`, so a user can implement any provider. A failed verb SHALL produce the port's typed media error carrying `message` and `status` (`failed|timeout|cancelled|unavailable`), and `unavailable` SHALL be testable without matching the message.

#### Scenario: Custom client
- **WHEN** a user client that supports only `music` and declares no `args` is passed to `createMedia`
- **THEN** `generate_music` is the only media tool and its schema lists every canonical music arg (case `client_without_args_gets_every_arg`)

#### Scenario: Typed unavailable
- **WHEN** a client fails with an unavailable error
- **THEN** the host can detect `unavailable` through the port's type or sentinel, not by text

### Requirement: createMedia routes and falls through only on unavailable
`createMedia(client | [client])` SHALL try the clients that support a verb in order, return the first outcome that is not `unavailable` (success or any other error), move on only on `unavailable` or a skipped candidate (never dropping args), and when every candidate is unavailable or skipped fail with `media: no client available for "<verb>": ` followed by each message joined by `"; "`.

#### Scenario: Fall-through on unavailable
- **WHEN** case `falls_through_on_unavailable` is replayed
- **THEN** both clients are called in order and the second client's result is returned

#### Scenario: No fall-through on failure
- **WHEN** cases `no_fall_through_on_failed` and `no_fall_through_on_timeout` are replayed
- **THEN** only the first client is called and its error and status are returned

#### Scenario: All unavailable
- **WHEN** case `all_unavailable_joined_in_order` is replayed
- **THEN** the error is `media: no client available for "image": a: no API key (set A_KEY); b: HTTP 402: {}` with status `unavailable`

### Requirement: createMedia reports missing clients
A verb no client supports SHALL fail with `media: no client supports "<verb>"` and no request; an empty client list SHALL fail with `media: no clients`.

#### Scenario: No supporting client
- **WHEN** case `no_client_supports_the_verb` is replayed
- **THEN** no client is called and the error is `media: no client supports "<verb>"`

#### Scenario: Empty list
- **WHEN** `createMedia([])` is called
- **THEN** it fails with `media: no clients`

### Requirement: Account preconditions are unavailable
HTTP 402, HTTP 429 whose body contains `quota`, `credit`, `billing` or `balance`, a built-in API client without a key, a CLI that cannot be started, Grok `zero data retention` or `usage limit`, and Codex `usage limit` or `quota` SHALL yield status `unavailable`; nothing else SHALL.

#### Scenario: Quota versus rate limit
- **WHEN** cases `openai_429_quota_is_unavailable` and `openai_429_rate_limit_is_failed` are replayed
- **THEN** the first is `unavailable` and the second is `failed` with `HTTP 429: Rate limit reached`

#### Scenario: Missing key makes no request
- **WHEN** case `openai_missing_key_unavailable_no_request` is replayed
- **THEN** no request is made and the error names `OPENAI_API_KEY`

### Requirement: One config shape with gather
Every built-in client SHALL accept `{model, models, key, url, command, loop{intervalMs,timeoutMs,onProgress}, gather{mode,dir,maxBytes,keep}, httpClient}` per SPEC §7G.4, read its key env var at call time, and turn each result shape (bytes, base64, data URL, URL, local path) into `MediaFile`s per the gather matrix of SPEC §7G.5; downloads SHALL send no client headers.

#### Scenario: Download by default
- **WHEN** case `fal_image_queue_download` is replayed
- **THEN** the file is saved under `gather.dir` with the pinned name, `url` is `file://` + its path, and the download request carries no `Authorization`

#### Scenario: Bytes in url mode are saved
- **WHEN** case `openai_image_b64_url_mode_still_saves` is replayed
- **THEN** the base64 result is saved to `gather.dir` because it has no URL to keep

#### Scenario: Size cap
- **WHEN** case `gather_max_bytes_exceeded` is replayed
- **THEN** the call fails with `image: file exceeds gather.maxBytes (4 bytes)`

### Requirement: Built-in API clients are pinned
`A2EMediaClient`, `ElevenLabsMediaClient`, `OpenAIMediaClient`, `OpenRouterMediaClient` and `FalMediaClient` SHALL send exactly the requests and parse exactly the responses of SPEC §7G.9, with the pinned default URL, key env var, header, default model and accepted args, and SHALL reject an arg they do not accept before any request.

#### Scenario: Every built-in client case
- **WHEN** every case in `conformance/media/clients.json` is replayed against the fake server
- **THEN** recorded requests, progress, results, errors and tool results match the case exactly

#### Scenario: ElevenLabs voice by name
- **WHEN** case `elevenlabs_speech_voice_by_name_case_insensitive` is replayed
- **THEN** the voice list is searched first and speech is requested with the matching `voice_id`

### Requirement: CLI clients run a headless CLI once
`GrokMediaClient` and `CodexMediaClient` SHALL spawn their CLI once with the pinned argv, env and prompt template, read its JSON lines, keep only files of the verb's kind, take the error from a failed tool result, find files by structured path, text scan and per-client folder, copy them out per gather, and kill the process on cancellation or timeout.

#### Scenario: Every CLI case
- **WHEN** every case in `conformance/media/cli.json` is replayed with `fake-cli/fake-cli.sh`
- **THEN** argv, env, progress, results, errors and tool results match the case exactly

#### Scenario: Timeout kills the CLI
- **WHEN** case `grok_timeout_kills_process` is replayed
- **THEN** the call fails with `grok: timed out after 300ms` within 5 seconds

### Requirement: Grok Zero Data Retention is unavailable
Under Grok Zero Data Retention the result SHALL be `unavailable` with the fixed message of SPEC §7G.10, and the client SHALL NOT edit the user's Grok settings.

#### Scenario: Wrong kind under ZDR
- **WHEN** case `grok_video_zdr_wrong_kind_is_unavailable` is replayed
- **THEN** the still image is ignored and the error is the ZDR message with status `unavailable`

### Requirement: Media tool parts never hold a path
A media tool result SHALL emit a file with `data` as a `data` part, a file with an `http(s)` URL as a `url` part, and a local file as a `data` part of its bytes; its output line SHALL be the file's path, else its URL, else `inline <mimeType> <n> bytes`.

#### Scenario: Downloaded file reaches the tool as data
- **WHEN** case `openai_image_b64_download` runs through the tool face
- **THEN** the output names the saved path and the single part carries the image as base64 `data`

### Requirement: Only image media reaches the model
Message assembly SHALL emit only `image` parts of a `source:"media"` tool result to the model (per §8A); video and audio parts SHALL NOT be emitted (no placeholder) and SHALL appear only in `toolCalls[].parts` and `RunResult.files`, the model seeing just the text output.

#### Scenario: Video stays out of the model context
- **WHEN** case `openai_video_poll_then_content` runs through the tool face and the result is assembled into the next LLM request
- **THEN** the request carries the `video ready: …` output and no video part, while `RunResult.files` holds the video (`modelParts` is `[]`)

#### Scenario: Image still reaches the model
- **WHEN** case `openai_image_b64_download` is assembled into the next LLM request
- **THEN** the image part is emitted per §8A (`modelParts` equals `parts`)

### Requirement: fal poll treats COMPLETED as done before error
The fal client SHALL treat `status == "COMPLETED"` as done regardless of an `error` field, SHALL fail with `job <id> failed: <error>` when the status is not COMPLETED and `error` is non-empty, and SHALL keep polling otherwise; when the submit response lacks `status_url`/`response_url` it SHALL use `<url>/<model>/requests/<id>/status` and `<url>/<model>/requests/<id>`.

#### Scenario: Completed with a stray error
- **WHEN** case `fal_completed_ignores_error_field` runs
- **THEN** the result is fetched and the call completes

#### Scenario: Error while in progress
- **WHEN** case `fal_error_field_while_in_progress_is_failed` runs
- **THEN** the call fails with `job req-e failed: worker crashed`

#### Scenario: Missing poll URLs
- **WHEN** case `fal_missing_status_and_response_url_builds_them` runs
- **THEN** the polls go to `/fal-ai/flux/schnell/requests/req-f/status` and `/fal-ai/flux/schnell/requests/req-f` with the key

### Requirement: Kind comes from the verb
The tool output's ready word and every `MediaFile.type` SHALL come from the verb's kind, never from the file; `mimeType` SHALL come from the file.

#### Scenario: PNG under the video verb
- **WHEN** case `kind_word_and_type_from_verb_not_file` runs
- **THEN** the output starts `video ready:` and the file type is `file` with mime `image/png`

### Requirement: Text caps append an ellipsis only when truncating
Every text cap (200, 300, 400, 1000) SHALL count Unicode code points and append `…` (U+2026) only when the text was truncated.

#### Scenario: Over and at the cap
- **WHEN** cases `openrouter_no_image_text_capped_200` and `openrouter_no_image_text_exactly_200_not_capped` run
- **THEN** the first ends with `…` after 200 code points and the second has no `…`

### Requirement: Downloads and input fetches are never unavailable
An HTTP 402 or quota-429 on a result download or an input fetch SHALL be status `failed` with `<verb>: download failed: HTTP <n>` or `<verb>: cannot read "<ref>": HTTP <n>`, never `unavailable`.

#### Scenario: 402 on download
- **WHEN** case `download_402_is_failed_not_unavailable` runs
- **THEN** the error is `image: download failed: HTTP 402` with status `failed`

#### Scenario: 402 on input fetch
- **WHEN** case `input_fetch_402_is_failed_not_unavailable` runs
- **THEN** the error is `transcribe: cannot read "<ref>": HTTP 402` with status `failed`

### Requirement: Only canonical args are accepted
A client's accepted args SHALL be canonical args only: a style's non-canonical mapped names are not accepted args. A key that is not a canonical arg of the verb SHALL fail the call before any client is tried with `<verb>: argument "<key>" not supported`, status `failed`.

#### Scenario: Style maps a non-canonical name
- **WHEN** case `style_noncanonical_mapped_arg_is_not_accepted` runs
- **THEN** no request is made and the error is `image: argument "negative_prompt" not supported`

#### Scenario: Client without args
- **WHEN** case `client_without_args_rejects_non_canonical_key` runs
- **THEN** the client is not called and the error is `image: argument "foo" not supported`

#### Scenario: Non-canonical key with fallbacks
- **WHEN** case `non_canonical_key_plain_error_even_with_fallbacks` runs
- **THEN** no client is called and the error is `image: argument "zoom" not supported`, not a joined error

### Requirement: A media result without image parts sends plain text
When a media tool result emits no image part, message assembly SHALL send its `output` string as the tool-result content in every message style, including Anthropic, with no empty array and no placeholder.

#### Scenario: Video-only result
- **WHEN** a case whose `modelParts` is `[]` is assembled in the Anthropic style
- **THEN** the tool_result content is the plain output string

### Requirement: models overrides model per verb on every client
`models.<verb>` SHALL override `model` and the default for that verb on every built-in client.

#### Scenario: ElevenLabs transcribe
- **WHEN** case `elevenlabs_transcribe_models_override` runs
- **THEN** the multipart `model_id` is `scribe_v2`

### Requirement: Deadline is timeout, cancel is cancelled
Expiry of the effective deadline (the earlier of `ctx.deadline` and `loop.timeoutMs`) SHALL be status `timeout` with the timed-out message; only an explicit cancel SHALL be `cancelled`.

#### Scenario: Caller deadline
- **WHEN** case `caller_deadline_is_timeout_not_cancelled` runs with a 300 ms ctx deadline
- **THEN** the call returns status `timeout` with `job req-t timed out after …`

### Requirement: Missing-result and maxBytes texts are pinned
API clients SHALL report `<verb>: no result at <path>` and `<verb>: no job id at <path>`; an input over `gather.maxBytes` SHALL be `<verb>: cannot read "<ref>": file exceeds gather.maxBytes (<n> bytes)` and a download over it `<verb>: download failed: file exceeds gather.maxBytes (<n> bytes)`, enforced regardless of an injected HTTP client.

#### Scenario: Transcribe without text
- **WHEN** case `elevenlabs_transcribe_no_text` runs
- **THEN** the error is `transcribe: no result at text`

#### Scenario: fal without request id
- **WHEN** case `fal_no_request_id` runs
- **THEN** the error is `image: no job id at request_id`

#### Scenario: Oversize local input
- **WHEN** case `input_file_exceeds_max_bytes_no_request` runs
- **THEN** no request is made and the error names the file and the cap

### Requirement: Grok raw output is sorted JSON
A Grok failed tool result without a message SHALL produce its error from `rawOutput` as compact JSON with object keys sorted by code point at every depth.

#### Scenario: Multi-key raw output
- **WHEN** case `grok_failed_raw_output_keys_sorted` runs
- **THEN** the error is `grok: no video: {"alpha":{"a":"x","b":2},"zeta":[1,{"c":null,"d":true}]}`

### Requirement: A candidate that cannot take the args is skipped
Before calling any candidate, the first included, the router SHALL check the args against its `args(verb)`. A canonical arg it does not accept SHALL skip it like `unavailable` with reason `<name>: argument "<key>" not supported` (first in sorted code-point order); otherwise a missing required arg SHALL skip it with `<name>: missing required argument "<key>"` (first in canonical order). Every candidate skipped or unavailable SHALL fail with status `unavailable` and `media: no client available for "<verb>": ` plus each reason in candidate order joined by `"; "`.

#### Scenario: First candidate rejects an arg
- **WHEN** case `first_candidate_rejects_arg_falls_to_next` runs (a Grok-like client without `seconds`, then a client with it)
- **THEN** the first client is never called and the second serves the call with `seconds` unchanged

#### Scenario: Every candidate rejects
- **WHEN** case `every_candidate_rejects_an_arg_joined` runs
- **THEN** no client is called and the error is `media: no client available for "image": a: argument "aspectRatio" not supported; b: argument "aspectRatio" not supported`

#### Scenario: Skips and unavailable join in order
- **WHEN** case `skipped_and_unavailable_reasons_join_in_order` runs
- **THEN** only `fal` is called and the error joins `grok: argument "seconds" not supported`, `fal: HTTP 402: {}` and `talky: missing required argument "image"` in that order

#### Scenario: Single client missing a required arg
- **WHEN** case `client_declared_required_arg` runs
- **THEN** the error is `media: no client available for "talk": a: missing required argument "audio"` with status `unavailable`

#### Scenario: Skipped fallback
- **WHEN** case `fall_through_skips_candidate_missing_required_arg` runs
- **THEN** client b is never called and client c serves the call

#### Scenario: Skipped fallback joins the error
- **WHEN** case `fall_through_missing_required_arg_joins_unavailable` runs
- **THEN** the error is `media: no client available for "talk": a: HTTP 402: {}; b: missing required argument "audio"`

### Requirement: Run files carry the local path
When gather has a local file for a `MediaFile` it SHALL set `path` (absolute), and the matching `RunResult.files` entry SHALL carry the same `path` in every port; parts and anything sent to a model SHALL never contain it.

#### Scenario: Downloaded file
- **WHEN** a `clients.json` or `cli.json` case marked `runFilePath: true` runs
- **THEN** each `RunResult.files` entry built from its tool call has `path` equal to the saved file's absolute path

#### Scenario: URL kept
- **WHEN** case `fal_image_url_mode_env_key_size_seed` (`runFilePath: false`) runs
- **THEN** no `RunResult.files` entry has a `path`

### Requirement: Media usage is reported only as the provider gives it
`MediaResult.usage` and the tool's `metadata.usage` SHALL be set only from provider fields: OpenAI images `usage.input_tokens`/`output_tokens` as `inputTokens`/`outputTokens`; OpenRouter `usage.prompt_tokens`/`completion_tokens`/`cost` as `inputTokens`/`outputTokens`/`costUsd`; Grok CLI `costUsdTicks` as `raw.costUsdTicks`, never converted. Other clients SHALL set no `usage`. There SHALL be no run-level sum in v1.

#### Scenario: OpenAI image usage
- **WHEN** case `openai_image_usage` runs
- **THEN** the result and tool metadata carry `usage` `{inputTokens: 9, outputTokens: 272}`

#### Scenario: OpenRouter cost
- **WHEN** case `openrouter_image_usage_cost` runs
- **THEN** `usage` is `{inputTokens: 7, outputTokens: 1290, costUsd: 0.0387}`

#### Scenario: Grok ticks stay raw
- **WHEN** case `grok_usage_cost_ticks_raw` runs
- **THEN** `usage` is `{raw: {costUsdTicks: 351342400}}` and `total_cost_usd` is ignored

#### Scenario: No usage
- **WHEN** a case marked `noUsage: true` runs
- **THEN** neither the result nor the tool metadata has a `usage` key

### Requirement: Saved files get an extension from the URL or the mime type
A saved file's extension SHALL be the source URL path's extension when it has one, else the §7G.5 mime table (including `audio/x-wav`, `audio/wave` ⇒ `.wav`, `audio/aac` ⇒ `.aac`, `text/plain` ⇒ `.txt`), and `.bin` only for an unknown mime type.

#### Scenario: URL extension wins
- **WHEN** case `download_ext_from_url_extension` runs
- **THEN** the saved file ends in `.jpeg`

#### Scenario: Wav variant
- **WHEN** case `download_ext_wav_from_x_wav_mime` runs
- **THEN** the saved file ends in `.wav`, not `.bin`

#### Scenario: Unknown mime
- **WHEN** case `download_ext_unknown_mime_is_bin` runs
- **THEN** the saved file ends in `.bin`

### Requirement: Media requests send a User-Agent
Every media HTTP request (start, poll, download, input fetch) SHALL send `User-Agent: toolnexus/<version> (<port>)` unless the client or style headers set a `User-Agent`, which SHALL then be sent unchanged.

#### Scenario: Default on every request
- **WHEN** case `user_agent_on_start_poll_and_download` runs
- **THEN** the start, poll and download requests all carry a `User-Agent` matching `toolnexus/<version> (<port>)`

#### Scenario: Style override
- **WHEN** case `style_user_agent_header_overrides` runs
- **THEN** every request carries `User-Agent: my-app/2.0`

### Requirement: CLI working dir is a realpath
A CLI client SHALL pass the realpath of its temp dir as the working dir and `--cwd`.

#### Scenario: macOS temp dir
- **WHEN** any `cli.json` case runs on macOS
- **THEN** the `--cwd` argv value equals the fake's `pwd -P` byte for byte
