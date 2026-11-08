## ADDED Requirements

### Requirement: Media styles are data
The library SHALL load a media style from JSON and SHALL contain no provider-specific code or names; provider presets SHALL live under `examples/media-styles/`.

#### Scenario: Load a preset
- **WHEN** a host calls `loadMediaStyle("examples/media-styles/a2e.json")` with `A2E_API_KEY` set
- **THEN** `createMedia(style)` supports the verbs the file declares and no a2e code exists in any port

### Requirement: Typed verbs return a MediaResult
`createMedia` SHALL return an object with the verbs `image`, `video`, `speech`, `transcribe`, `cloneVoice`, `talking` and a generic `generate(verb, args)`, each returning `{files, text?, jobId?, status}`, following SPEC §7G engine order.

#### Scenario: Sync image
- **WHEN** case `sync_image_urls_mime_from_extension` is replayed
- **THEN** the request body and the three files with their mime types match `cases.json` exactly

#### Scenario: Async job with undocumented status
- **WHEN** case `async_speech_array_id_nested_arg_unknown_status` is replayed
- **THEN** the engine keeps polling through `sent`, reports progress `processing, sent, completed`, and returns jobId `j1`

### Requirement: Engine errors are byte-fixed
Every engine error SHALL use the exact message forms in SPEC §7G.

#### Scenario: Failure, timeout and envelope
- **WHEN** the cases `async_failed_with_message`, `async_failed_without_message`, `async_timeout`, `http_error_on_start`, `error_envelope_on_200`, `unsupported_arg_no_request`, `no_result`, `no_job_id`, `non_json_response`, `unknown_verb` are replayed
- **THEN** each produces exactly its `error` string, and the unsupported-arg and unknown-verb cases make no request

### Requirement: Verbs are exposed as provider-independent tools
`media.tools()` SHALL return one `Tool` with source `"media"` per supported verb in canonical order, with names, descriptions and schemas from `verbs.json` filtered to the args the style maps; executing one SHALL never throw.

#### Scenario: Schemas for a partial style
- **WHEN** `schemas.json` style is loaded
- **THEN** the tools, their order and their `inputSchema` equal `schemas.json` `tools`

#### Scenario: Tool result
- **WHEN** any case is executed through the tool face
- **THEN** the `ToolResult` equals the case's `tool` (output, isError, parts, metadata)

### Requirement: Run results carry produced files
`RunResult` SHALL include `files` (every non-text part produced by tool calls in the run, in call order, each tagged with `from` and `callId`, `[]` when none) and `toolCalls[].parts` when a tool returned parts; `text` SHALL NOT receive markup.

#### Scenario: Agent generates an image
- **WHEN** a scripted LLM calls `generate_image` once and then answers "done"
- **THEN** `RunResult.files` holds that image part with `from: "generate_image"` and the call id, and `text` is "done"

#### Scenario: No media
- **WHEN** a run calls no media-producing tool
- **THEN** `RunResult.files` is `[]` and every other field is unchanged

### Requirement: Media tools are concurrency-safe
Every tool returned by `media.tools()` SHALL be concurrency-safe for every argument set under §8 *Tool scheduling* (it carries an always-true `concurrencySafe` predicate, idiomatic name per port), because each call is an independent remote job; the media calls of one model response SHALL therefore run concurrently up to `maxToolConcurrency` rather than serialised.

#### Scenario: All six media tools are safe
- **WHEN** a style maps all six verbs and `media.tools()` is built
- **THEN** each of the six tools is concurrency-safe for empty arguments

#### Scenario: Two media calls in one response
- **WHEN** a model response calls `generate_image` twice
- **THEN** both calls run in the same concurrent batch, and results keep the emitted order

### Requirement: Media requests are bounded and safe
The media engine SHALL never send style headers to an origin (scheme, host, port) other than the origin of the request built from `baseUrl`; on a cross-origin redirect it SHALL re-issue the request without style headers, or SHALL not follow redirects and report the 3xx as `HTTP <status>: <body>`. Every HTTP request (start and each poll) SHALL be bounded by the time remaining until the job deadline (`timeoutMs` from the start; sync verbs use `poll.timeoutMs` when given, else 600000), failing with `job <id> timed out after <ms>ms`, or `<verb>: timed out after <ms>ms` when no job id exists yet. No port SHALL offer a separate create-time timeout option.

#### Scenario: Cross-origin redirect drops style headers
- **WHEN** server A answers the start request with a redirect to server B on a different port and the style sets `Authorization`
- **THEN** server B never receives `Authorization` or any other style header

#### Scenario: A hung server is bounded by the deadline
- **WHEN** a sync verb's server accepts the request and never answers, with a deadline of 200 ms
- **THEN** the call fails with `<verb>: timed out after 200ms` shortly after 200 ms instead of hanging

### Requirement: Style edge rules are pinned
The engine SHALL treat `intervalMs`/`timeoutMs` that are absent or `<= 0` as 5000/600000; SHALL render a numeric poll status like the envelope code (integral ⇒ integer text, else shortest round-trip) for progress, errors and matching against `done`/`failed`; SHALL read an empty dotPath as the whole value; SHALL reject an `args` entry mapped to `""` with `style: empty path for argument "<key>"`; SHALL percent-encode the job id in the poll path per RFC 3986 unreserved characters; SHALL cap an echoed error body at 1000 code points followed by `…` when truncated; and SHALL replace an array with an object when a dotPath writes through it with a non-numeric segment.

#### Scenario: Edge cases replay
- **WHEN** the cases `zero_interval_and_timeout_use_defaults`, `numeric_poll_status_done`, `numeric_poll_status_failed_fraction`, `empty_result_path_is_whole_body`, `empty_arg_path_is_a_style_error`, `job_id_percent_encoded_in_poll_path`, `error_body_capped_at_1000_code_points`, `error_body_exactly_1000_not_truncated` and `dotset_through_array_with_word_segment` are replayed
- **THEN** each produces exactly its expected requests, progress, result or error

### Requirement: Run files carry part names
Every entry of `RunResult.files` SHALL carry the part's `name` when the producing part had one, and SHALL collect non-text parts from every tool source (media, MCP, built-in file reads), not only media tools.

#### Scenario: Named MCP resource
- **WHEN** an MCP tool returns a `resource_link` with a name during a run
- **THEN** that run's `files` holds a `file` entry with that `name`, `from` and `callId`
