## MODIFIED Requirements

### Requirement: Media styles are data
The library SHALL load a media style from JSON with `loadMediaStyle(path)` and SHALL turn a parsed style into a `MediaClient` with `styleMediaClient(style, config?)`. A style is the advanced path for providers not built in; provider presets SHALL live under `examples/media-styles/`, and the a2e preset SHALL be the byte source of the built-in `A2EMediaClient`. A style declaring a verb outside the seven of SPEC §7G.1 SHALL fail at load with `style: unknown verb "<verb>"`.

#### Scenario: Load a preset
- **WHEN** a host calls `createMedia(styleMediaClient(loadMediaStyle("examples/media-styles/a2e.json")))` with `A2E_API_KEY` set
- **THEN** the media supports exactly the verbs the file declares (image, video, speech, talk)

#### Scenario: Old verb name
- **WHEN** case `style_old_talking_verb_is_a_style_error` is replayed
- **THEN** loading fails with `style: unknown verb "talking"` and no request is made

### Requirement: Typed verbs return a MediaResult
`createMedia` SHALL return an object with the verbs `image`, `video`, `speech`, `music`, `transcribe`, `cloneVoice`, `talk` and a generic `generate(verb, args, ctx?)`, each returning `{files, text?, jobId?, status}`, following SPEC §7G. For a style client the engine order of SPEC §7G.7 SHALL apply unchanged.

#### Scenario: Sync image
- **WHEN** case `sync_image_urls_mime_from_extension` is replayed
- **THEN** the request body and the three files with their mime types match `cases.json` exactly

#### Scenario: Async job with undocumented status
- **WHEN** case `async_speech_array_id_nested_arg_unknown_status` is replayed
- **THEN** the engine keeps polling through `sent`, reports progress `processing, sent, completed`, and returns jobId `j1`

#### Scenario: Talk replaces talking
- **WHEN** case `style_talk_verb` is replayed
- **THEN** `media.talk` returns the video file and the tool is `talking_video`

### Requirement: Verbs are exposed as provider-independent tools
`media.tools()` SHALL return one `Tool` with source `"media"` per verb any client supports, in canonical order, with names and descriptions from `verbs.json` and an `inputSchema` built from the first supporting client's accepted args; executing one SHALL never throw, and a successful result's metadata SHALL name the serving `client`.

#### Scenario: Schemas for a partial style
- **WHEN** the `schemas.json` style is loaded
- **THEN** the tools, their order and their `inputSchema` equal `schemas.json` `tools`

#### Scenario: Tool result
- **WHEN** any case in `cases.json`, `clients.json`, `cli.json` or `routing.json` is executed through the tool face
- **THEN** the `ToolResult` equals the case's `tool` (output or outputPattern, isError, parts, metadata)

### Requirement: Media tools are concurrency-safe
Every tool returned by `media.tools()` SHALL be concurrency-safe for every argument set under §8 *Tool scheduling* (it carries an always-true `concurrencySafe` predicate, idiomatic name per port), because each call is an independent remote job or CLI run; the media calls of one model response SHALL therefore run concurrently up to `maxToolConcurrency` rather than serialised.

#### Scenario: All seven media tools are safe
- **WHEN** clients supporting all seven verbs are combined and `media.tools()` is built
- **THEN** each of the seven tools is concurrency-safe for empty arguments

#### Scenario: Two media calls in one response
- **WHEN** a model response calls `generate_image` twice
- **THEN** both calls run in the same concurrent batch, and results keep the emitted order
