# legacy-stdio — one spawn for a fast discover error (SPEC §2.2a)

`server.mjs` is a pre-2026 stdio MCP server that appends `start <pid>` to `$SPAWN_COUNT_FILE` on
every process start. Every port's test loads it with `command: node`, `args: [<abs path>/server.mjs]`
and `env: {SPAWN_COUNT_FILE: <tmp file>, DISCOVER_MODE: ...}` and asserts:

| `DISCOVER_MODE` | client behaviour | starts in the file | tools |
|---|---|---|---|
| `error` (default) | discover gets `-32601` fast; the SAME child continues into `initialize` | exactly **1** | `<server>_echo`, call `{"text":"hi"}` ⇒ `echo:hi` |
| `silent` | discover times out (`min(timeout, 5 s)`); probe child terminated, frozen path respawns | exactly **2** | same |

Use a short server `timeout` (e.g. 1500 ms) for the `silent` case so the test stays fast.
