## Why

Today toolnexus unifies four tool sources (MCP, skills, native `@tool`, remote HTTP) but ships
**no ready-to-use tools of its own**. A skill can say "run `psql …`", but nothing in the toolkit
can actually execute it — the host must hand-register a native function first. opencode, our
reference, ships a default toolset (`bash`, `read`, `write`, `edit`, `grep`, `glob`, `webfetch`,
etc.) that is on by default, so an agent can *act* the moment it boots. We want that same
out-of-the-box capability, ported byte-identically across all four ports, so a toolnexus agent is
useful with zero custom tools.

## What Changes

- Introduce a **fifth tool source: `builtin`** — a set of default tools that ship with the library,
  each carrying `source: "builtin"`, matching opencode's exact tool **names** and **input schemas**.
- Port opencode's default tools: `bash`, `read`, `write`, `edit`, `grep`, `glob`, `webfetch`,
  `question`, `apply_patch`, `todowrite` (10). (`skill` already exists as its own source and is
  unchanged.) `websearch` is **deliberately excluded** — without a provider it would be a permanent
  `isError` stub; add web search as a native/http tool if wanted.
- The builtin source is **on by default**, controlled via a top-level `builtins` key at two levels:
  a **whole-source** switch (`false` / `{disabled:true}` / `{enabled:false}` ⇒ all off, MCP precedence)
  and a **per-tool** map `builtins.tools` (name→bool on the all-on baseline, e.g. `{bash:false}`).
  Whole-source-off short-circuits the map.
- Wire the builtin source into toolkit assembly in **all five ports** (js/python/golang/java/**csharp**),
  after MCP + skill and before `extraTools`, keeping the existing first-wins dedupe-by-name behavior.
  (The repo grew a C# port; parity now spans five languages — the prime directive forbids leaving it behind.)
- Add a new **§ Built-in tools** section to `SPEC.md` pinning each tool's name, parameters, behavior,
  and the enable/disable contract, so the ports stay substitutable.
- **Prompt surfacing (so tools aren't missed by default):** builtin/MCP/native/HTTP tools are
  surfaced only through the tool-schema array (matching opencode — no builtins prompt section). Fix
  the one real gap: prepend opencode's instruction preamble to `skillsPrompt()` ("Use the skill tool
  to load a skill when a task matches its description") so listed skills are actually used. This
  touches existing SPEC.md §3/§8 skills behavior and lands in all four ports.

Non-goals (explicitly deferred): opencode's per-tool `permissions` ruleset (allow/deny/ask +
wildcards), per-tool disable granularity, and the shared-`edit`-action nuance. This change is the
source + the single toggle; finer control is a later change.

## Capabilities

### New Capabilities
- `builtin-tools`: A default set of library-provided tools (opencode-parity names + schemas)
  exposed as the `builtin` tool source, on by default, with a single global enable/disable toggle,
  integrated into toolkit assembly across all four ports.

### Modified Capabilities
<!-- No archived capability specs exist yet (openspec/specs/ is empty); toolkit assembly and
     config parsing changes are captured under the new builtin-tools capability and SPEC.md. -->

## Impact

- **SPEC.md**: new § for built-in tools (cross-language conformance contract) + toolkit-assembly note.
- **js/**, **python/**, **golang/**, **java/**: new builtin tool source module (one tool per opencode
  tool), toolkit-assembly wiring, config parsing for the `builtins` toggle, and per-port tests.
- **examples/**: optionally a fixture exercising a builtin tool (e.g. `read`) for cross-port parity.
- **Dependencies**: no new runtime deps expected — tools use each port's stdlib (filesystem, process,
  HTTP). `bash` shells out per-runtime; `webfetch` uses the platform HTTP client.
- **Security**: `bash` grants host command execution and `write`/`edit`/`apply_patch` mutate the
  filesystem — the global toggle is the intended off-switch for locked-down hosts; secrets handling
  (never log command output containing env-expanded values) follows existing repo rules.
