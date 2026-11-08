# Design
See ADR 0036 (`docs/adr/0036-media-generation-sources.md`) for options and the spike findings, and
SPEC §7G for the normative engine order. Notes for implementers:

- Embed `conformance/media/verbs.json` as constants; tests load `cases.json`/`schemas.json` from the
  repo (relative path) — never fork copies.
- The fake server is in-process (each port's stdlib HTTP server). Script entries answer requests in
  order; the last repeats. Timings in cases are 1–30 ms; do not add sleeps.
- Cancellation is port-native. The tool face never throws.
- `RunResult.files` is filled by the existing loop from each `ToolResult.parts`, tagged with the
  tool name and the model's tool-call id. No change to what is sent to the LLM.
- Per-port shape is idiomatic (JS `createMedia`, Go `NewMedia`/`LoadMediaStyle`, Python
  `create_media`, Java/C# builders/records, Elixir/Clojure maps, Rust structs + async fns).
