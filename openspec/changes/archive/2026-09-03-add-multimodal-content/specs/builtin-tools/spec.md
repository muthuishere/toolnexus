## ADDED Requirements

### Requirement: read returns a content part for a recognised media file

The `read` builtin SHALL return a `ContentPart` when the target file's extension is in a fixed,
spec-listed table (`png`, `jpg`, `jpeg`, `gif`, `webp`, `pdf`, `mp3`, `wav`). In that case
`output` SHALL be a one-line description naming the file and its mime type, and `parts` SHALL hold
one part carrying the file's bytes as base64 with the mime type from the table. For every other
extension the existing UTF-8 text behavior — including `offset`/`limit` windowing — SHALL be
unchanged. The port SHALL NOT sniff magic bytes and SHALL NOT resolve mime types through a
platform mime database, whose contents vary per machine and would break cross-port parity.

#### Scenario: Reading a PNG yields an image part

- **WHEN** `read` is called on a `.png` file
- **THEN** `output` is a one-line description naming the file and `image/png`
- **AND** `parts` holds one `image` part carrying the file's base64 bytes

#### Scenario: Reading a text file is unchanged

- **WHEN** `read` is called on a `.md` file with `offset` and `limit`
- **THEN** the output is that line window exactly as before and `parts` is absent

### Requirement: read returns an error result for undecodable bytes, never a raised exception

When `read` targets a file that is neither in the media extension table nor decodable as UTF-8,
the port SHALL return `ToolResult{isError:true}` naming the file. A decoding failure SHALL NOT
propagate as an unhandled exception out of `execute` into the client loop.

#### Scenario: An unrecognised binary yields an error result

- **WHEN** `read` is called on a `.bin` file whose bytes are not valid UTF-8
- **THEN** an `isError:true` result naming the file is returned
- **AND** no exception escapes the tool into the loop
