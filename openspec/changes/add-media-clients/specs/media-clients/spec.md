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
`createMedia(client | [client])` SHALL try the clients that support a verb in order, return the first outcome that is not `unavailable` (success or any other error), move on only on `unavailable`, drop args the next client does not accept, and when every candidate is unavailable fail with `media: no client available for "<verb>": ` followed by each message joined by `"; "`.

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
A client's accepted args SHALL be canonical args only: a style's non-canonical mapped names are not accepted args, and any non-canonical key SHALL be rejected `<verb>: argument "<key>" not supported by <name|this style>` for every client, including one without `args()`.

#### Scenario: Style maps a non-canonical name
- **WHEN** case `style_noncanonical_mapped_arg_is_not_accepted` runs
- **THEN** no request is made and the error names `negative_prompt`

#### Scenario: Client without args
- **WHEN** case `client_without_args_rejects_non_canonical_key` runs
- **THEN** the client is not called and the error is `image: argument "foo" not supported by d`

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

### Requirement: Fall-through re-checks required args
Before a fall-through candidate is called its required args SHALL be checked; a missing one SHALL skip it as unavailable with `<name>: missing required argument "<key>"`, joined into the all-unavailable message. On the first candidate a missing required arg SHALL stay a plain `failed` error.

#### Scenario: Skipped fallback
- **WHEN** case `fall_through_skips_candidate_missing_required_arg` runs
- **THEN** client b is never called and client c serves the call

#### Scenario: All unavailable
- **WHEN** case `fall_through_missing_required_arg_joins_unavailable` runs
- **THEN** the error is `media: no client available for "talk": a: HTTP 402: {}; b: missing required argument "audio"`

### Requirement: CLI working dir is a realpath
A CLI client SHALL pass the realpath of its temp dir as the working dir and `--cwd`.

#### Scenario: macOS temp dir
- **WHEN** any `cli.json` case runs on macOS
- **THEN** the `--cwd` argv value equals the fake's `pwd -P` byte for byte
