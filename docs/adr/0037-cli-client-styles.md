# ADR 0037 — Agent CLIs as tool-calling clients: ACP first, one-shot CLI as the fallback, chosen by style

- Status: **PROPOSED** (2026-11-04)
- Spike: `spikes/cli-client/` (Go; one-shot path live-verified against 5 CLIs, ACP-with-toolkit path
  against 6 agents; see `SPIKE.md` and `acp-results.json`)
- Revised 2026-11-04: ACP is primary (see "ACP primary" below); owner decisions recorded.
- Supersedes for this transport: issue #97 (CLI-backed model source)

## Context

Many users already pay for a coding-agent subscription: Claude Code, Codex (ChatGPT), GitHub
Copilot, opencode, Devin, Gemini CLI, goose. Each ships a CLI with a one-shot mode that reads one
prompt and prints one answer. The owner wants those subscriptions usable as the model behind a
normal toolnexus client, with real tool calling, in every port, with the common case being one
line of configuration.

toolnexus already has the seam: an in-process client (§8 "In-process models") takes a `generate`
function and gives the full client — loop, hooks, memory, suspension, metrics, scheduling. What is
missing is a `generate` that runs a CLI once, and a way to get tool calls out of a CLI that has no
tool-call channel.

## Decision

1. **One constructor, selected by style name.** `createCliClient({ style, command, model?, …every
   in-process client option })` (idiomatic per port, e.g. Go `CreateCliClient(CliOptions{Style:
   CliStyles.Claude, Command: "/usr/local/bin/claude", Model: "sonnet"})`). It returns an ordinary in-process client. **`style` is a real enum, never a string**
   (owner 2026-11-04: "no string"): spelled `CliStyles.Claude`, `CliStyles.Codex`, … in every port (Go: a `CliStyles` struct of typed `CliStyle` values), then TS/Java/C#/Rust
   `enum CliStyles`, Python `Enum`, Elixir/Clojure a closed set of atoms/keywords checked at construction.
   Members: Claude, Codex, Copilot, Opencode, Devin, Gemini, Goose, Custom. The lowercase name is
   used only internally to find the shared style file; no API accepts a style as free text.
2. **Built-in styles are shared data.** One JSON file per CLI (`claude`, `codex`, `copilot`,
   `opencode`, `devin`, `gemini`, `goose`) in one shared directory, embedded byte-identically by
   every port. A style holds **no binary**: it is the argv template, the prompt mode
   (`stdin` | `arg` | `file`), the answer source (`stdout` | `out` file), the model flag as an
   optional group, and a `verified` note (which CLI version's `--help` it was checked against, or
   `UNVERIFIED`).
3. **`command` is required** (path or name). Nothing is embedded or defaulted: the library never
   decides which executable runs. A style describes a CLI *family's* shape, so `style: "claude"`
   drives any claude-compatible binary at any path, and `style: "codex"` any codex-compatible one.
   A missing command fails with `cli: Command is required (path or name of the CLI binary)`.
   (Owner, 2026-11-04: "we dont want the cli to be embedded let them give manually".)
   Why: toolnexus then never ships, picks or launches a vendor's product by itself. The user
   chooses and runs their own installed CLI under their own subscription, which keeps us out of
   vendor terms disputes and lets anyone (a vendor, a fork, a wrapper) bring a compatible CLI.
4. **Escape hatches.** `style: "custom"` with an explicit `args` template, `prompt` and `answer`;
   or a user style file via `loadCliStyle(path | json)`. A missing style fails at construction.
5. **Argv template.** Placeholders `{{prompt}}`, `{{file}}` (temp file holding the prompt),
   `{{out}}` (temp file the CLI writes its answer to), `{{model}}`. An element may be an optional
   group (array), kept only when a model is set. The child is spawned with the argv directly,
   never through a shell. With prompt mode `stdin` the prompt is written to stdin; otherwise stdin
   is empty and closed at once.
6. **Tool calling through our own envelope.** Each model turn renders `PREAMBLE + "\nREQUEST:\n" +
   {"messages", "tools"}`: the OpenAI-shaped request, unmodified, under the seven-line preamble
   designed for ACP tool calling (change `add-acp-tool-calling`, PR #114). The reply is parsed by
   the same tolerant six-step parser (fences, prose around JSON, `choices[0].message` and
   `message` wrappers, string or object `arguments`; anything that is not an envelope passes
   through as content). Tool calls go back to the toolnexus loop, which executes them, so hooks,
   MCP, skills, builtins, suspension and tool scheduling all apply. The CLI never runs our tools.
7. **System prompt, skills and every client option** behave as on any in-process client. The
   system prompt and the skills prompt arrive as the first `messages` entry inside the envelope,
   not as a CLI flag. Memory (`ask`), max turns, hooks, metrics, `waitFor`, tool tiers and tool
   scheduling work unchanged. `usage` is zero (a CLI reports none), and that is documented.
8. **Model selection.** `model` fills `{{model}}`. A model set on a style without `{{model}}` is a
   construction error (`cli: style "goose" takes no model`); `{{model}}` outside an optional group
   with no model set is also an error.
9. **Streaming.** Refused, exactly as for any in-process `generate`: a one-shot CLI returns a
   whole answer, and a single-chunk stream would be a fake one.
10. **Errors and timeouts.** Pinned messages: `cli: command not found: <bin>`, `cli: timed out
    after <ms>ms` (default 300000 ms — owner 2026-11-04; the whole process group is killed), `cli: exit <code>:
    <stderr, capped at 1000 code points>`, `cli: no output file`, `cli: empty reply`. Failures are
    final — no retries — as for every in-process model. Temp files are removed on every path.
    `env` values expand `${VAR}` and are never logged. The library never reads, stores or proxies
    a CLI's credentials: the CLI uses its own login.

## ACP primary (revision, 2026-11-04)

The first draft rejected ACP. The second spike reverses that: when an agent speaks ACP, the user's
**own toolkit** (MCP servers from `mcp.json`, skills, `defineTool` functions) runs inside the agent
as ordinary MCP tools, with the agent's native tool calling instead of our JSON envelope. Live:
claude, copilot, devin, gemini and opencode passed all three checks (MCP, skill, native) over ACP;
codex passed through the one-shot fallback.

11. **Transport choice.** `CliStyles.X` selects ACP when the style declares an ACP launch (`acp`
    argv, e.g. `["acp"]`, `["--acp"]`, or an adapter command) **and** the given binary supports
    it; otherwise the one-shot CLI client above is used. The same `CreateCliClient(CliOptions{Style:
    CliStyles.X, Command: …})` call covers both; the user never picks a transport by string.
    A style without an ACP launch (codex today: its `codex-acp` adapter cannot reach current
    account models) is CLI-only.
12. **The toolkit goes in as MCP.** On session open the toolkit is served with `Toolkit.Serve`'s MCP
    profile (SPEC §7C) on `127.0.0.1:<ephemeral>/mcp` and listed in `session/new` `mcpServers`:
    - agent advertises `agentCapabilities.mcpCapabilities.http` →
      `{"type":"http","name":"toolnexus","url":"http://127.0.0.1:<port>/mcp","headers":[…]}`;
    - otherwise → `{"name":"toolnexus","command":<bridge>,"args":[…,"<url>"],"env":[]}`, a stdio
      ⇄ HTTP bridge (no `type` field);
    - a style may declare a **project MCP file** for agents that ignore session `mcpServers`
      (devin: `.mcp.json` in a fresh temp cwd). The server, the bridge and any temp dir are closed
      with the session. The served endpoint gets a per-session bearer token (spike gap: none yet).
13. **Permissions.** The ACP permission hook auto-approves only tools served by our toolkit server
    and rejects every other request (the agent's own shell, edit, fetch). Matching is by the
    request's `toolCallId` against the tool our server is about to run, not by title text (titles
    differ per agent and devin sends none).
14. **In ACP mode the agent executes the tools**, so toolnexus hooks, tool scheduling and
    suspension apply at the MCP server boundary (`OnCall`), not in the client loop. The one-shot
    fallback keeps the full loop (decision 6).
15. The `golang/acp.go` `session/new` sends `"mcpServers": []` today; wiring decision 12 into the
    library is the first Go task.

## Owner decisions, 2026-11-04 (accepted)

- **Switch off each CLI's own tools where a flag or mode exists** (claude `--tools ""`, codex
  `--sandbox read-only` / ACP mode `read-only`); where none exists, the permission hook rejects
  the agent's own tools. Styles record which applies.
- **Default timeout 300 s** per invocation / prompt (was 600 s in decision 10).
- **gemini and goose ship marked `UNVERIFIED`** when not runnable on the verifying machine.
  (gemini was since verified over ACP with `--acp --skip-trust`; goose remains unverified.)
- **Model passed through unchanged.** Each style documents its model format: opencode wants
  `provider/model`; over ACP the id goes to `session/set_config_option` (`configId: "model"`),
  falling back to `session/set_model`.
- **Port order: Go first**, then the other seven ports.
- **Style is an enum `CliStyles.X`, never a string; `Command` is required; there is no default
  binary.** (Restated from decisions 1 and 3.)

## Owner ruling, 2026-11-04: vendor names in CLI styles

The standing rule (ADR 0021 era, memory "scope: tool-calling, not vendor clients") was that no
vendor name appears in the public API; ACP presets and `CreateDevinClient` were rejected on that
ground. **On 2026-11-04 the owner revised it for CLI styles only:** style names such as `"claude"`
or `"codex"` are allowed as data identifiers passed to the one generic constructor, and the
style files ship inside the library. The rule still holds everywhere else: there is no per-vendor
function, type, option or code path, and adding a CLI is adding a JSON file.

## Alternatives considered

- **Raw argv only (no styles).** Rejected by the owner: it pushes every user to rediscover each
  CLI's flags (prompt mode, answer file, how to switch off the CLI's own tools), which is exactly
  what the spike found to be the hard part. Kept as the `custom` escape hatch.
- **ACP transport only.** First rejected (adapters, warm sessions), then adopted as primary by the
  revision above once the spike showed the toolkit-as-MCP route works on five agents. Not
  ACP-only: codex shows an agent can have a working CLI and a broken ACP adapter at the same time.
- **Merging or depending on PR #114.** Rejected: #114 is an ACP change that is 116 commits behind
  and conflicts with the ACP host hooks. Only its envelope design (preamble + parser) is reused,
  re-implemented for the CLI transport. #114 is left open as it is.
- **Plain text only, no tool calling.** Considered and withdrawn by the owner: a model that
  cannot call the toolkit is not a toolnexus client.
- **Per-vendor constructors.** Rejected: the revision above allows names as data, not as API.

## Consequences

- A new SPEC section and conformance cases (argv rendering, envelope parsing, a tool-call round
  trip and the pinned errors, run against a hermetic fake CLI script) before any port work.
- Styles drift as CLIs change flags. Each style records the version it was verified against;
  re-verification is a data change.
- An `arg`-mode prompt is one argv element, capped at 128 KiB on Linux (`MAX_ARG_STRLEN`). Styles
  prefer `stdin` or `file` where the CLI supports it.
- Every turn re-sends the whole conversation and pays the CLI's start-up time (2–23 s per call in
  the spike).
