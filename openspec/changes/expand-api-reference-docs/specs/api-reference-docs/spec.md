## ADDED Requirements

### Requirement: API reference covers every public subsystem

The API Reference SHALL document the complete public surface of the library, not a subset. It MUST
cover, at minimum: the toolkit and its options; skills (load, data/provider sources, filter,
inventory); MCP load; built-in tools; native tools (`defineTool`); HTTP tools; A2A both outbound
(remote `agent`s) and inbound (`serve`, the Agent Card, and the `TaskStore`); the suspension /
human-loop primitive (§10: `pending`, `Request`, `Answer`, the `waitFor` resolver, and
`RunResult.status`); the OpenAI/Anthropic/Gemini adapters; the client loop (`run`/`ask`/`stream`);
conversation memory (`ConversationStore`); streaming and hooks; and observability (`onMetric`).

#### Scenario: A2A and suspension are documented

- **WHEN** a reader opens the API Reference
- **THEN** it includes the inbound `serve` API (Agent Card + TaskStore), the outbound remote-agent API, and the §10 suspension surface (`pending`, `Request`, `Answer`, `waitFor`, `RunResult.status`) — none of which may be absent

#### Scenario: Every tool source and client subsystem is present

- **WHEN** a reader looks for built-in tools, native `defineTool`, HTTP tools, conversation memory, streaming, hooks, or observability
- **THEN** each has an entry in the API Reference

### Requirement: Each documented symbol carries a detailed description

Every symbol in the API Reference SHALL be described, not merely listed: what it does, each
parameter or field, the return value, and its key behavior or semantics (for example filter
semantics, the typed skip reasons, per-source failure isolation, and byte-exact output rules). A
bare signature with no prose does not satisfy this requirement.

#### Scenario: A symbol entry explains parameters and behavior

- **WHEN** a reader views the entry for a toolkit option or a skill entry point
- **THEN** the entry describes what it does, documents each field/parameter, and states the return value and relevant behavior — not only the signature

### Requirement: Documented version references match the published release

Version numbers shown in the docs (install snippets, examples) SHALL match the currently published
release and be updated when a new version ships.

#### Scenario: Install snippet shows the current version

- **WHEN** the latest published version is 0.8.0
- **THEN** the Install page's pinned-version snippets show 0.8.0, not an earlier version
