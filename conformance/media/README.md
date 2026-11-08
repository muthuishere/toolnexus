# Media conformance (SPEC §7G)

Every port replays all of these hermetically (no network, no real CLI, no keys) and must match them.

| file | what | harness |
|---|---|---|
| `verbs.json` | the seven verbs, tool names, descriptions, canonical args. Ports embed it verbatim. | assert the embedded copy |
| `schemas.json` | exact `Tool` list for a partial style | `createMedia(styleMediaClient(style)).tools()` |
| `cases.json` | style engine (JSON styles, `loadMediaStyle` path) | fake HTTP server, `createMedia(styleMediaClient(style, config))` |
| `clients.json` | built-in API clients: a2e, elevenlabs, openai, openrouter, fal; gather; unavailable mapping | fake HTTP server, `createMedia(<Client>(config))` |
| `cli.json` | CLI clients: grok, codex; wrong-kind guard, failed tool result, kill on timeout, spawn failure | `fake-cli/fake-cli.sh` via `/bin/sh` |
| `routing.json` | `createMedia` routing, fall-through on `unavailable` or a skipped candidate (D19), tool schemas from the first supporting client | test-only custom clients |

Each file's `_doc` is the exact harness contract. Shared rules: `{server}` / `{dir}` / `{inputs}` /
`{out}` / `{cwd}` placeholders; `script[]` served in order with the last entry repeating; a field
absent from a case is not asserted; `liveVerified:false` marks request shapes pinned from provider
docs, not from a live call.

The a2e preset `examples/media-styles/a2e.json` is embedded verbatim by every port as
`A2EMediaClient`; a test asserts the copy.

Change a byte here ⇒ change it in all eight ports in the same PR.
