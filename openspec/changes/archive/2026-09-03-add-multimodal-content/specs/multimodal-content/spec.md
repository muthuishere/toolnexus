## ADDED Requirements

### Requirement: A shared ContentPart model

Every port SHALL define one `ContentPart` union with exactly four variants — `text`, `image`,
`file`, `audio` — and identical field names across all seven ports, using `mimeType` as the mime
field. A non-text part SHALL carry a `mimeType` and exactly one of `data` (standard base64,
padded, no line breaks) or `url`. A part carrying both or neither SHALL be rejected at
construction with a typed error. A part SHALL NOT carry a filesystem path.

#### Scenario: A part with both data and url is rejected

- **WHEN** a caller constructs an `image` part supplying both `data` and `url`
- **THEN** construction fails with a typed error naming the conflict, and no request is sent

#### Scenario: Base64 encoding matches the committed golden in every port

- **WHEN** each port encodes `examples/media/fixture.png` into an `image` part
- **THEN** the part's `data` equals the committed `examples/media/fixture.png.base64` golden

### Requirement: The API edge accepts paths, bytes and data URLs

Each port SHALL provide named edge constructors that normalise into a path-free part: a
filesystem path SHALL be read and base64-encoded at construction; native byte input SHALL be
base64-encoded at construction; a `data:` URL supplied as `url` SHALL be parsed into `mimeType`
plus `data`; an `https:` URL SHALL be retained as `url`. The mime type for a path SHALL come from
the fixed extension table and SHALL NOT be sniffed from file contents or resolved through a
platform mime database.

#### Scenario: A path is read at the edge and never stored

- **WHEN** a caller builds a part from `./shot.png`
- **THEN** the resulting part carries `mimeType:"image/png"` and base64 `data`
- **AND** the part contains no path field, so a persisted transcript replays without the file

#### Scenario: A data URL is normalised at construction

- **WHEN** a caller supplies `url: "data:image/png;base64,<b64>"`
- **THEN** the part is stored as `{mimeType:"image/png", data:"<b64>"}` with no `url`

#### Scenario: A native file or stream object is accepted

- **WHEN** a caller passes the host language's own file or stream object — a `File`/`Blob` in js,
  a binary file-like object in python, an `io.Reader` in golang, a `java.io.File` or
  `InputStream` in java, a `FileInfo` or `Stream` in csharp
- **THEN** its bytes are read and base64-encoded at construction
- **AND** the resulting part holds only `mimeType` and `data`, with no handle, stream or path

#### Scenario: A stream is consumed eagerly

- **WHEN** a caller builds a part from a readable stream and the run is persisted and replayed
- **THEN** the replay uses the bytes captured at construction, because a part never holds an
  unread stream

#### Scenario: An unknown extension is refused by name

- **WHEN** a caller builds a part from a file whose extension is not in the fixed table and gives
  no explicit mime type
- **THEN** construction fails with a typed error naming the extension

### Requirement: The loop accepts content parts as input

`run()` SHALL accept either a string prompt or a list of `ContentPart` in its **first** prompt
position, preserving the caller's ordering. Given a string, the assembled user message SHALL be
**byte-identical** to what the port produces today.

#### Scenario: The string path is unchanged

- **WHEN** a caller passes a plain string prompt
- **THEN** the assembled user message is `{role:"user", content:<the string>}`, exactly as before

#### Scenario: Ordering is preserved

- **WHEN** a caller passes `[text, image, text]`
- **THEN** the provider request carries three blocks in that order

### Requirement: A tool may return content parts

`ToolResult` SHALL gain an optional `parts` field. `output` SHALL remain required and SHALL remain
what the transcript, compaction, token counting, and text-only providers see. A `ToolResult`
setting no `parts` SHALL behave byte-identically to today.

#### Scenario: A tool returning an image also returns describing text

- **WHEN** a tool returns `output:"screenshot, 1280x720 png"` and one `image` part
- **THEN** the transcript's tool-result text is the `output` string, and the image is delivered
  to the provider on the same exchange

#### Scenario: parts does not collide with suspension

- **WHEN** a `ToolResult` carries both `parts` and `metadata.pending`
- **THEN** the run suspends per §10 exactly as it would without `parts`

### Requirement: Non-text tool-result parts are emitted natively or relocated

A port SHALL emit a tool result's non-text parts natively where the provider style defines a
shape for them inside its tool-result element. For the `anthropic` style they SHALL be blocks in
`tool_result.content`. For the `openai` style, whose `tool` message rejects an image, the tool
message SHALL carry `output` plus text parts only, and all non-text parts from all tool results
answering one assistant turn SHALL be relocated in tool-call order into a **single** synthetic
`user` message emitted immediately after the last tool message, each preceded by a text part
`Output of tool <name> (<tool_call_id>):`. The synthetic message SHALL be an adapter artifact
only and SHALL NOT be written to the canonical transcript, the `ConversationStore`, or
`translate` output.

#### Scenario: Anthropic receives the image inside the tool result

- **WHEN** a tool returns an image part and the style is `anthropic`
- **THEN** the image block appears inside `tool_result.content` keyed to its `tool_use_id`
- **AND** no synthetic user message is emitted

#### Scenario: OpenAI receives one synthetic user message

- **WHEN** two tools answering one assistant turn each return an image and the style is `openai`
- **THEN** two `tool` messages carry only their `output` text
- **AND** exactly one following `user` message carries both images in tool-call order, each
  preceded by its `Output of tool <name> (<tool_call_id>):` text part

#### Scenario: The synthetic message never persists

- **WHEN** a run that relocated parts for `openai` completes
- **THEN** `RunResult.messages` and the saved conversation contain no synthetic user message, so
  a later turn against `anthropic` sees no OpenAI-shaped residue

### Requirement: Emission is guarded by a positive allowlist

For each provider style and part type a port SHALL have either a defined block shape or an
explicit refusal, and SHALL assert the encoded block against that allowlist before sending. A
part that produced no allowlisted block SHALL NOT reach the wire. `anthropic` SHALL name `audio`
as a refusal, as the provider defines no audio block.

#### Scenario: An unmapped part never reaches the wire

- **WHEN** a part encodes to a block that is not in the style's allowlist
- **THEN** the request is not sent, and the failure names the part type and the style

### Requirement: Unsupported parts are handled by provenance

A part the caller **attached** that the style cannot represent SHALL raise a typed error at
request assembly, before any HTTP call. A part **derived from a tool or MCP result** that the
style cannot represent SHALL be replaced by a text placeholder naming its type and mime type,
with a warning emitted at most once, and SHALL NOT fail the run. A client option
`onUnsupportedPart` with values `"error"` and `"text"` SHALL override both behaviors uniformly.
A part SHALL NEVER be dropped silently.

#### Scenario: An attached audio part to anthropic errors

- **WHEN** a caller attaches an `audio` part and the style is `anthropic`
- **THEN** a typed error names the part type and the style, and no HTTP request is made

#### Scenario: MCP-derived audio degrades instead of failing the run

- **WHEN** an MCP tool returns an `audio` part and the style is `anthropic`
- **THEN** the run completes with a text placeholder naming the type and mime type
- **AND** a warning is emitted at most once

#### Scenario: The override forces uniform strictness

- **WHEN** `onUnsupportedPart:"error"` is set and an MCP tool returns an unrepresentable part
- **THEN** the run fails with the typed error rather than degrading

### Requirement: Part bytes are never logged, and parts are charged for

Wherever a port renders a part at all — an observability event, a log line, an error message,
debug output — it SHALL render `{type, mimeType, bytes}` and a part's `data` SHALL NEVER appear.
A port MAY add a `parts` descriptor to the `llm` event; it is not required to, because that event
feeds a bounded-cardinality metrics registry. Token
estimation SHALL charge a non-trivial per-part estimate derived from byte length, never the
length of the `mimeType` string. `maxPartBytes`, when set, SHALL be measured in **decoded** bytes
and enforced in the edge constructors.

#### Scenario: A rendered part never carries its bytes

- **WHEN** a run carrying a 2 MB image renders that part into any event, log line or error
- **THEN** the rendering is `{type:"image", mimeType:"image/png", bytes:2097152}` and contains no
  base64 payload

#### Scenario: A part is not free to the compactor

- **WHEN** token estimation runs over a transcript holding a 2 MB image part
- **THEN** the estimate charged for that part is derived from its byte length, so the compactor
  can evict it

#### Scenario: An oversized part is rejected at the edge

- **WHEN** `maxPartBytes` is 1048576 and a caller builds a part from 2 MB of decoded bytes
- **THEN** construction fails with a typed error naming both the limit and the actual size
