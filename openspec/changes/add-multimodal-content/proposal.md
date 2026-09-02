## Why

toolnexus is **text-only at every seam**, in all seven ports. You cannot hand the loop an image
or a PDF (`run(prompt: string)`), a tool cannot return one (`ToolResult.output: string`), and —
the part that is a bug rather than a gap — every port's MCP adapter filters a `CallToolResult`'s
`content[]` down to text parts and **silently discards** `ImageContent`, `AudioContent`,
`EmbeddedResource` and `ResourceLink`. A screenshot tool, a chart tool, or Playwright MCP returns
an empty string today, with no error and no log line. Every mainstream model toolnexus targets
has been multimodal for years; this is the most basic missing capability in the library.

## What Changes

- **New `ContentPart` model** — `text` / `image` / `file` / `audio`, carrying inline base64 +
  `mimeType` or a `url`, byte-identical in all seven ports. The API edge additionally accepts a
  path, native bytes, or a `data:` URL and normalises at construction; **a part never holds a
  path**.
- **Loop input accepts parts** — `run()` takes a string *or* a list of parts, parts first-
  positional so text/image ordering is preserved. The string path stays byte-identical. **Not
  breaking.**
- **`ToolResult` can carry parts** — verified non-breaking in all seven ports (C# must append the
  parameter *after* `Metadata`; it is a positional record).
- **MCP stops dropping content** — image / audio / embedded-resource / **resource-link** parts
  become `ContentPart`s. Includes fixing the `structuredContent` short-circuit that bypasses
  content mapping entirely in every port. **BREAKING** for anyone relying on the silent drop
  (`SPEC.md:218`).
- **Provider emission in `client.*` message assembly** for the `openai` and `anthropic` styles,
  guarded by a **positive allowlist** on the encoded block — a part that produces no allowlisted
  block cannot reach the wire.
- **The tool-result asymmetry is pinned** — non-text parts ride natively where the style has a
  shape (`anthropic`), and are relocated into a single synthetic `user` message for `openai`,
  which hard-400s on an image in a `tool` message. The synthetic message never enters the
  canonical transcript.
- **§11 translate gets one specified rule** — text parts concatenate, non-text parts translate.
  This *unifies six ports that already pass arrays through*, rather than deleting one. **Behaviour
  change** to the documented `SPEC.md:1612`.
- **Builtin `read` handles media** via a fixed extension table; also fixes a live Python bug where
  reading binary **raises** an unwrapped `UnicodeDecodeError` into the loop rather than returning
  an error result.
- **`serve()` (MCP inbound) emits non-text content blocks** rather than one text block.
- **Unsupported parts are handled by provenance** — a part the caller *attached* errors loudly; a
  part *derived from a tool result* degrades to a named placeholder with a warn-once, so a server
  volunteering audio cannot fail a run that succeeds today.

**Out of scope, tracked and named**: Gemini request emission (no port has a Gemini request path —
`ClientStyle` is `openai | anthropic`), A2A message parts, skill resources as parts, provider
`fileId` upload, and model image *output*.

## Capabilities

### New Capabilities
- `multimodal-content`: the `ContentPart` model, parts on loop input, parts on `ToolResult`,
  provider emission with a positive allowlist, the provenance-based unsupported-part rule, and
  the tool-result relocation rule.

### Modified Capabilities
- `tool-translation`: inbound translation preserves content parts instead of flattening an array
  `content` to text.
- `mcp-load-lifecycle`: MCP tool results map non-text `content[]` entries to `ContentPart`s
  rather than discarding them, on every branch.
- `builtin-tools`: `read` returns a content part for a recognised media file.
- `mcp-inbound`: `tools/call` returns the tool's parts as MCP content blocks.

## Impact

- **Contract**: `SPEC.md` §0 (`Tool`/`ToolResult`), §2 (MCP mapping, line 218), §5, §6 (`read`,
  line 466), §7 (loop entry), §7B (serve), §9 (events), §11 (line 1612 + the new relocation rule).
- **Code, all seven ports**: `client.*` (loop entry, message assembly, tool-result turns),
  `types.*`, `mcp.*`, `translate.*`, `builtin.*`, `mcp_serve.*`. Note `adapters.*` is **not**
  touched — it is tool-schema only in every port.
- **Ports**: js, python, golang, java, csharp, elixir, clojure — parity checklist per task.
- **Dependencies**: none new. Clojure's `koine` already ships `read-bytes` + base64 `encode`
  written for MCP blocks.
- **Users**: additive for the string path; the MCP drop and the §11 rule are behaviour changes
  needing a `CHANGELOG.md` entry that names both.
