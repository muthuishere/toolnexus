## ADDED Requirements

### Requirement: A stdio discover error reply reuses the probe child

Over stdio, when `server/discover` receives a JSON-RPC response (an error, or a result without
`2026-07-28` in `supportedVersions`) within `min(timeout, 5 s)`, the client SHALL continue the frozen
path (§2.F) on the SAME child process, sending `initialize` over the probe's pipes. It MUST NOT start
a second process. When discover gets no reply within `min(timeout, 5 s)`, the client SHALL terminate
the probe child and the frozen path SHALL start its own process with unchanged `command`/`env`/`cwd`.
A child that exits before replying remains a load failure.

#### Scenario: Old server answers -32601 — one process start
- **WHEN** a stdio server answers `server/discover` with JSON-RPC error `-32601` and then serves `initialize`/`tools/list` (fixture `examples/mcp-2026/legacy-stdio/server.mjs`, `DISCOVER_MODE=error`)
- **THEN** the server loads on the frozen path, its `echo` tool returns `echo:hi`, and the fixture's spawn-count file records exactly one start

#### Scenario: Old server ignores discover — respawn
- **WHEN** the same fixture runs with `DISCOVER_MODE=silent` and a server `timeout` of 1500 ms
- **THEN** the probe child is terminated after the discover timeout, the server loads on the frozen path, `echo` returns `echo:hi`, and the spawn-count file records exactly two starts

#### Scenario: 2026 server unaffected
- **WHEN** a stdio server's `server/discover` result lists `2026-07-28`
- **THEN** it is used on the 2026 path, no `initialize` is sent, and one process is started
