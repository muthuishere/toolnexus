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
