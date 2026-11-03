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
`createMedia(client | [client])` SHALL try the clients that support a verb in order, return the first outcome that is not `unavailable` (success or any other error), move on only on `unavailable`, drop args the next client does not accept, and when every candidate is unavailable fail with `media: no client available for "<verb>": ` followed by each message joined by `"; "`. No supporting client SHALL fail with `media: no client supports "<verb>"`; an empty list SHALL fail with `media: no clients`.

#### Scenario: Fall-through on unavailable
- **WHEN** case `falls_through_on_unavailable` is replayed
- **THEN** both clients are called in order and the second client's result is returned

#### Scenario: No fall-through on failure
- **WHEN** cases `no_fall_through_on_failed` and `no_fall_through_on_timeout` are replayed
- **THEN** only the first client is called and its error and status are returned

#### Scenario: All unavailable
- **WHEN** case `all_unavailable_joined_in_order` is replayed
- **THEN** the error is `media: no client available for "image": a: no API key (set A_KEY); b: HTTP 402: {}` with status `unavailable`

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
`GrokMediaClient` and `CodexMediaClient` SHALL spawn their CLI once with the pinned argv, env and prompt template, read its JSON lines, keep only files of the verb's kind, take the error from a failed tool result, find files by structured path, text scan and per-client folder, copy them out per gather, and kill the process on cancellation or timeout. Under Grok Zero Data Retention the result SHALL be `unavailable` with the fixed message of SPEC §7G.10, and the client SHALL NOT edit the user's Grok settings.

#### Scenario: Every CLI case
- **WHEN** every case in `conformance/media/cli.json` is replayed with `fake-cli/fake-cli.sh`
- **THEN** argv, env, progress, results, errors and tool results match the case exactly

#### Scenario: Wrong kind under ZDR
- **WHEN** case `grok_video_zdr_wrong_kind_is_unavailable` is replayed
- **THEN** the still image is ignored and the error is the ZDR message with status `unavailable`

#### Scenario: Timeout kills the CLI
- **WHEN** case `grok_timeout_kills_process` is replayed
- **THEN** the call fails with `grok: timed out after 300ms` within 5 seconds

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
