# Conformance checks

Cross-port guards that run in CI, independent of any single language suite.

## Option parity (`check_options_parity.py`)

Asserts every logical `ClientOptions` / `ToolkitOptions` field in `options_manifest.json`
exists in all six ports (matched by a normalized alias, so `onError`/`on_error`/`OnError` and
the `fetch`/`http_transport`/`HTTPClient` http-client aliases all count as one logical option).

This guards the class of silent drift where an option ships in five ports but not the sixth —
e.g. `disableTools`/`disableSkills` sat in five ports while the JS reference lacked them,
undetected until found by hand. **Add an option => add a row to the manifest for all six ports.**

A `core`-tier port that has no implementation of a whole group (Rust has no classifier yet) is listed
under that group's `unported` key with the reason. It is printed as debt on every run, never as a
pass, and is refused for a `full`-tier port or a port that also lists a file for the group.

```sh
python3 conformance/check_options_parity.py   # exit 0 = parity holds
```

## Skills byte-parity (`skills_parity/run.sh`)

Builds one edge-case skills corpus (folded/literal YAML, a colon in a plain scalar, quoted and
numeric values, malformed and nameless frontmatter, CRLF, a Unicode directory, a symlinked
directory, nested duplicates, more than the 10-file sample cap, and `examples/skills/hello-world`).
It runs the Go reference and the Rust port over it and requires identical output: the system-prompt
catalog, every `skill` tool output (including not-found) and the skip list. Breaking one character
of Rust's output fails it. To add a port, add a dump command for it.

```sh
conformance/skills_parity/run.sh   # needs go, cargo, python3
```

## Serve MCP profile wire (`serve_mcp/cases.json`)

Pins the `toolkit.serve` MCP profile's HTTP wire (SPEC §7C) — today: `GET /mcp` ⇒ 405 with
`Allow: POST`, answered at once. Each port starts `serve` with an `mcp` profile, sends every case's
request over real HTTP and asserts status and the listed headers. Replayed by
`golang/serve_mcp_conformance_test.go`, `js/test/serve-mcp-conformance.test.ts`,
`python/tests/test_mcp_serve.py`, `java/.../ServeMcpConformanceTest.java`,
`csharp/tests/Toolnexus.Tests/ServeMcpConformanceTests.cs`, `elixir/test/mcp_serve_test.exs`,
`clojure/src/toolnexus/serve_test.cljc` and `rust/tests/mcp_serve.rs`.
