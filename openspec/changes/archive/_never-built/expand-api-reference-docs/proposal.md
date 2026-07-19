## Why

The API Reference shipped in `extend-skill-source` covers only a third of the library — Toolkit,
Skills, MCP, Adapters, Client — and lists symbols as terse one-line signatures with no descriptions.
It **omits** entire subsystems (A2A serve + remote agents, the §10 suspension / human-loop
primitive, built-in tools, native `defineTool`, HTTP tools, conversation memory, streaming, hooks,
observability), and a version reference on the Install page went stale (`0.6.0` after `0.8.0`
shipped). A reference that documents a third of the surface, without explaining what anything does,
is worse than none — it looks complete while hiding the most important primitives.

## What Changes

- **Complete the surface.** The API Reference SHALL document *every* public subsystem, grouped:
  Toolkit · Skills · MCP · Built-in tools · Native tools (`defineTool`) · HTTP tools · A2A (outbound
  agents + inbound `serve`/Agent Card/TaskStore) · Suspension & the human loop (§10: `pending`,
  `Request`, `Answer`, `waitFor`, `RunResult.status`) · Adapters · Client (run/ask/stream) · Memory
  (`ConversationStore`) · Streaming & hooks · Observability (`onMetric`).
- **Detailed descriptions on every aspect.** Each documented symbol SHALL carry a real description:
  what it does, each parameter/field, the return value, and key behavior/semantics (e.g. filter
  semantics, skip reasons, isolation, byte-exact output) — not just a signature. The language-neutral
  prose is shared across ports; only the signatures differ. `site/src/content/docs/api/go.mdx` is the
  reference depth exemplar produced alongside this proposal.
- **Version currency.** Version references in the docs SHALL match the published release; the stale
  Install `0.6.0` is corrected to `0.8.0` (done in this change's first commit) and the mechanism kept
  current on each release.
- **Five-language parity across the whole surface** — every documented symbol names its equivalents
  in all five ports, and a new public symbol is documented for every port that ships it.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `api-reference-docs`: the existing "per-port API reference" and "five-language parity"
  requirements are extended so the reference (a) covers **every** public subsystem, not a subset,
  (b) gives each documented symbol a detailed description (params/returns/behavior), and (c) keeps
  version references matching the published release.

## Impact

- **Docs only:** `site/src/content/docs/api/*.mdx` (combined + five per-port pages) grow to full
  coverage + descriptions; `site/src/content/docs/install.mdx` version fix (already applied).
- **No code changes.** Documents the existing shipped surface (verified against `js/src/*` exports:
  `Client`/`ClientOptions`/`Hooks`/`RunResult`, `serve`/`buildAgentCard`/`agent`/`A2AConfig`/`TaskStore`,
  `pending`/`Request`/`Answer`, `defineTool`/`httpTool`, `ConversationStore`, `onMetric`, `createBuiltinTools`).
- **Deferred:** the user asked to spec now and implement later — this change is the record; the page
  build-out is the apply step.
