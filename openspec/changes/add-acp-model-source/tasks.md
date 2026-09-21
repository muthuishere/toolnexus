# Tasks — add-acp-model-source

The surface, once, so every port implements the same thing:

- open a connection: `initialize` (negotiate protocol version, declare that a model host
  serves no files) · `session/new` (**absolute cwd**, `mcpServers` array) · optional
  `session/set_mode`
- `generate(request)` -> one `session/prompt`, returning the accumulated
  `agent_message_chunk` text
- demultiplex responses from `session/update` notifications by JSON-RPC id
- answer `session/request_permission` with the first permitting option
- serialise turns per session; process lifetime independent of any turn's cancellation;
  idempotent close
- full request per turn **plus the supersedes marker**, assembled by the library

## Spec

- [x] Spec delta at `specs/acp-model-source/spec.md`
- [x] `openspec validate add-acp-model-source --strict`
- [x] `docs/adr/0025` revised — the warm-session claim scoped to real CLI startup cost
- [x] Confirm no `SPEC.md` §0 change is required — confirmed

## Portability gate — settle BEFORE implementing

- [x] `spikes/portability/SPIKE.md` establishes, per port, that a child process can be
      spawned and spoken to over newline-delimited JSON-RPC with no new dependency
- [x] Both Clojure hosts (JVM and cljgo) confirmed — proven on JVM, cljgo interpreted and cljgo AOT
- [x] No port is infeasible — every port already ships newline-JSON-over-pipes machinery for
      MCP local stdio (SPEC §2), so no new dependency is required anywhere

## Per-language parity checklist

Each port is not done without hermetic tests, against a fake ACP server over real pipes,
for: warm session reuse · thought/narration filtering · permission answered not awaited ·
stale-answer prevented by the supersedes marker · turn serialisation · idempotent close.

- [x] `golang/`
- [x] `js/`
- [x] `python/`
- [x] `java/`
- [x] `csharp/`
- [x] `elixir/`
- [x] `clojure/`

## Docs

- [ ] A cookbook page per port
- [x] `CHANGELOG.md` `## Unreleased`

## Deliberately out of scope

- Delta mode (send only the new turn) — opt-in, a later change
- The one-shot CLI model source — issue #97, ADR 0026, its own change
