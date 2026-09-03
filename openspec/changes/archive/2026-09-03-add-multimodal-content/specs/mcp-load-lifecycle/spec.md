## ADDED Requirements

### Requirement: MCP tool results preserve non-text content

When mapping an MCP `CallToolResult` to a `ToolResult`, a port SHALL map every non-text
`content[]` entry to a `ContentPart` on `ToolResult.parts` rather than discarding it: image
content becomes an `image` part; audio content becomes an `audio` part; a resource link becomes a
`file` part carrying its `uri` as `url`; an embedded resource carrying a blob becomes a `file`
part carrying that blob; an embedded resource carrying text SHALL be appended to `output`.
`output` SHALL continue to be the joined text parts, so a text-only MCP tool is byte-identical to
today. A non-text entry SHALL NEVER be dropped silently.

#### Scenario: A screenshot tool's image survives

- **WHEN** an MCP server returns a `CallToolResult` holding one text entry and one image entry
- **THEN** `ToolResult.output` is the text entry, and `ToolResult.parts` holds one `image` part
  carrying the server's base64 and mime type

#### Scenario: A resource link becomes a file part

- **WHEN** an MCP server returns a `resource_link` entry
- **THEN** `ToolResult.parts` holds a `file` part whose `url` is the link's `uri`

#### Scenario: A text-only MCP tool is unchanged

- **WHEN** an MCP server returns a `CallToolResult` holding only text entries
- **THEN** `ToolResult.output` is the joined text exactly as before and `parts` is absent

#### Scenario: An image-only result is not an empty string

- **WHEN** an MCP server returns a single image entry and no text
- **THEN** `ToolResult.parts` holds the image part and `output` names the returned part rather
  than being the empty string

### Requirement: Content parts are collected on every result branch

A port SHALL collect content parts regardless of which branch produces `output`. In particular a
result carrying `structuredContent` SHALL still map its `content[]` entries to parts, and a result
carrying `isError` SHALL still map them, so neither short-circuit reintroduces a silent drop.

#### Scenario: Structured content does not swallow an image

- **WHEN** an MCP server returns both `structuredContent` and an image entry
- **THEN** `output` is the serialised structured content as before
- **AND** `ToolResult.parts` still holds the image part

#### Scenario: An error result keeps its image

- **WHEN** an MCP server returns `isError:true` with a text entry and an image entry
- **THEN** `ToolResult.isError` is true, `output` is the formatted error text, and `parts` holds
  the image part
