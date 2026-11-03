# Fake media CLI (SPEC §7G.11)

`fake-cli.sh` stands in for `grok` and `codex` in `../cli.json`. It is POSIX `sh` only, so every
port spawns the same script on ubuntu CI and macOS (Windows is not run).

A port's test, per case:

1. writes `case.fixture` (an array of lines) to a temp file `<fixture>`, one line each;
2. makes an empty temp dir `<out>` and a temp dir `{dir}` for `gather.dir`;
3. builds the client with `command = ["/bin/sh", "<abs>/conformance/media/fake-cli/fake-cli.sh",
   "<fixture>", "<out>"]` plus `case.config` (with `{dir}` substituted);
4. runs `createMedia(client)` for `case.verb` / `case.args` (function face and tool face — the tool
   face on a fresh `<out>` so the argv check holds for both);
5. asserts `<out>/argv.txt` equals `case.expectArgv` (`{cwd}` = the line must equal the content of
   `<out>/cwd.txt`, compared after resolving symlinks), `<out>/env-GROK_CONFIG.txt` equals
   `case.expectEnv.GROK_CONFIG` when given, then the result or error as in the other files.

Fixture directives: `#file <relpath> <text>` creates `<out>/<relpath>`; `#exit <n>` exits;
`#hang` execs `sleep 600` (one process — killing the pid ends it, so a port that kills only the
direct child still passes). Every `{out}` in a printed line becomes `<out>`. Any `{out}` in
`case` expectations means the same `<out>`.

Captured shapes: the Grok lines are trimmed from a live `grok -p … --output-format
streaming-json --yolo` run (2026-11-03, `spikes/media-clients`), the Codex lines from a live
`codex exec --json` run.
