## Decisions

### D1 — command normalization
- `command` string ⇒ argv `[command, ...args]`; array ⇒ `command ++ args`.
- `args` absent ⇒ `[]`. `args` not an array of strings ⇒ error.
- Empty string, empty array, or a non-string array element ⇒ error.
- A remote server (url) ignores `args`. Errors: `mcp server "<name>": invalid command` /
  `mcp server "<name>": invalid args`. Error text names server+field; exact wording is per-port.
- No shell: the argv goes straight to the process spawner, as today.

### D2 — file store format
- File `<root>/<id>.json`, body `{"version":1,"messages":[...]}`. Writer whitespace is not normative;
  readers accept any JSON with `version` 1. Unknown version ⇒ error.
- id: `^[A-Za-z0-9._-]{1,128}$` and not `.`/`..`; otherwise every op errors (no traversal).
- root: required, non-empty; created (mode 0700 where the OS supports it) on first save. Never a
  default home directory.
- save: write `<root>/.<id>.<random>.tmp` (0600), fsync, rename over `<id>.json`. Durable against
  process crash: a reader sees the old or the new file, never a torn one. Power-loss durability of
  the rename is best-effort (parent dir fsync where supported) and documented as such.
- get: missing ⇒ none; unreadable/corrupt/unknown version ⇒ error (never silently none).
- list: entries `{id, updatedAt}` for every `*.json` with a valid id, `updatedAt` = file mtime,
  ordered newest first, ties by id ascending. Temp files and invalid names are skipped. Does not
  parse file bodies (cheap).
- delete: removes the file; missing ⇒ no error.
- concurrency: saves for the same id are serialized within a process; across processes last
  rename wins (documented).
