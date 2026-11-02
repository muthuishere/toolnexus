# Media conformance (SPEC §7G)

- `verbs.json` — canonical verbs, tool names, descriptions, normalised args. Ports embed it verbatim.
- `cases.json` — engine cases. Each port's test starts an in-process fake HTTP server, substitutes
  `{server}`, sets `env`, serves `script[]` in order (last entry repeats), and asserts the recorded
  requests, progress statuses, the `MediaResult` or exact error string, and the tool-face `ToolResult`.
- `schemas.json` — exact `Tool` list (order, names, descriptions, `inputSchema`) for a partial style.

Change a byte here ⇒ change it in all eight ports in the same PR.
