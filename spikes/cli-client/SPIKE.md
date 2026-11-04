# Spike: CLI client styles (ADR 0037)

**Question:** can a one-shot agent CLI the user already pays for act as a tool-calling toolnexus
model, selected by a style name, with no per-vendor code?

**Answer: yes.** All five CLIs installed on the spike machine made a real tool call through the
toolnexus loop and answered with data that only the tool could supply.

## What is here

| file | what |
|---|---|
| `cli.go` | `CreateCliClient(CliOptions{Style, Model, Command, …})` over `toolnexus.CreateInProcessClient`; embedded styles; argv templating; envelope render; the six-step tolerant parser |
| `styles/*.json` | the seven built-in styles (data, meant to be shared byte-identically by every port) |
| `preamble.txt` | the pinned seven-line preamble (the same text as `add-acp-tool-calling`, PR #114) |
| `fake-cli.sh` | a hermetic fake CLI for the tests |
| `cli_test.go` | parser cases, style validation, unknown-style error, model rules, `Command` override, prompt via stdin, arg and file, system prompt in the request, a tool-call round trip, and the pinned errors (exit, empty, timeout, out file) |
| `main.go` | live runner: `go run . [style…]` |
| `live-results.json` | the recorded live run |

`go test ./...` passes in under a second, with no network.

## Live run (2026-11-04, macOS, each CLI's own default model)

Task: the toolkit holds one native tool, `lookup_order`, whose answer (tracking `ZX-7731-QP`)
exists nowhere else. System prompt: "You are a support assistant. Answer in one short sentence."
Prompt: "What is the tracking number for order A-1042?" A pass means: `lookup_order` was called by
the toolnexus loop, and the final text contains `ZX-7731-QP`.

| style | version (`--help` checked) | result | total | per invocation | prompt bytes (turn 1 / turn 2) |
|---|---|---|---|---|---|
| claude | 2.1.318 | PASS | 6.4 s | 3.8 s + 2.6 s | 1223 / 1511 |
| codex | codex-cli 0.160.0 | PASS | 11.8 s | 6.8 s + 5.0 s | 1223 / 1510 |
| copilot | 1.0.98 | PASS | 37.5 s | 23.2 s + 14.3 s | 1223 / 1544 |
| devin | 2026.8.18 | PASS | 28.7 s | 14.9 s + 13.8 s | 1223 / 1511 |
| opencode | v2.0.23 | PASS | 5.1 s | 2.9 s + 2.2 s | 1223 / 1510 |
| gemini | — | not installed, style UNVERIFIED | | | |
| goose | — | not installed, style UNVERIFIED | | | |

Every CLI used two invocations: one tool-call envelope, then one content envelope.

## Facts found

1. **The fence step of the parser is needed.** Devin wrapped its final answer in a ```` ```json ````
   fence despite "no markdown fences" in the preamble. The other four replied with bare JSON.
2. **Copilot adds trailing blank lines** to stdout; trimming before parsing is needed.
3. **Codex's stdout is progress, not the answer.** The final message has to come from
   `--output-last-message <file>`, so styles need an `out` answer source.
4. **Claude Code's own tools must be switched off** (`--tools ""`), or it may act on the request
   itself. The documented way is `--tools ""`; the other CLIs have no one-flag equivalent
   (Copilot only has allow/deny lists, Devin a permission mode), and every reply in this run
   was a bare envelope, with no sign of a tool of its own (not proven: the CLIs' own logs were not
   inspected).
5. **On this machine `claude` is a shell function** that adds `--dangerously-skip-permissions`.
   Child processes never see shell functions, so the client resolves the real binary on `PATH`.
   Users with a wrapper should know their flags are not applied.
6. **Start-up cost dominates.** Copilot spends about 14–23 s per call; this transport suits short
   loops, not chatty ones.
7. **No CLI doc was wrong for the flags used.** Every flag in the five verified styles matched the
   installed CLI's `--help`. (Issue #97's suggested Copilot flags `--log-level none` still exist,
   but `--silent` is the documented way to get only the answer.)

## Open questions for the owner

1. **Style directory.** Shared styles need one home that every port embeds: `styles/cli/` at the
   repo root, or `conformance/cli-styles/`? (Embedding from outside a package needs a copy step
   in Go, Rust and Elixir, as the media fixtures already do.)
2. **Agent tools.** Should every style switch off the CLI's own tools where a flag exists
   (Claude: yes; Codex: `--sandbox read-only`), or leave that to the user?
3. **Default timeout.** 600 s per invocation is generous; Copilot's worst call here was 23 s. Keep
   600 s or lower it to 300 s?
4. **Unverified styles.** Ship `gemini` and `goose` marked UNVERIFIED, or leave them out until
   someone verifies them on a machine that has them?
5. **Model names.** The style passes `model` through unchanged (`opencode` wants
   `provider/model`). Is pass-through enough, or should a style document its model format?
6. **Port order.** Go first, then the other seven, as for media?
