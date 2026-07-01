## ADDED Requirements

### Requirement: Builtin tool source

The library SHALL ship a `builtin` tool source that provides a fixed set of default tools. Every
tool from this source SHALL carry `source: "builtin"` and SHALL conform to the existing `Tool`
interface (name, description, inputSchema, source, execute) identically in all four ports
(js/python/golang/java). Tool **names** and **input schemas** SHALL match the opencode reference
exactly, so the four ports remain byte-substitutable.

#### Scenario: Builtin source exposes the default toolset

- **WHEN** a toolkit is assembled with the builtin source enabled
- **THEN** it exposes exactly these tool names: `bash`, `read`, `write`, `edit`, `grep`, `glob`,
  `webfetch`, `question`, `apply_patch`, `todowrite`
- **AND** `websearch` is NOT included (a no-provider stub is not shipped as a builtin; add web search
  as a native/http tool if needed)
- **AND** each tool's `source` field equals `"builtin"`
- **AND** the `skill` tool (its own source) is unaffected

#### Scenario: Names and schemas match opencode

- **WHEN** any builtin tool's inputSchema is emitted
- **THEN** its parameter names, types, and required/optional status match the opencode reference
  tool of the same name (e.g. `edit` takes `path`, `oldString`, `newString`, optional `replaceAll`)

### Requirement: Builtin source is on by default

The builtin tool source SHALL be enabled by default. A toolkit assembled without any builtin-related
configuration SHALL include all default builtin tools.

#### Scenario: Default assembly includes builtins

- **WHEN** a toolkit is built with no `builtins` configuration present
- **THEN** all default builtin tools are present in the toolkit's tool list

### Requirement: Global and per-tool enable/disable

Configuration SHALL control the builtin source at two levels, read from a top-level `builtins` key
(or the `builtins` option). (1) A **whole-source** switch — `builtins: false`, `{disabled: true}`,
or `{enabled: false}` — disables the entire source so **no** builtin tool appears, using MCP's
precedence (`disabled: true` wins; else `enabled: false` disables; otherwise enabled). (2) A
**per-tool** map `builtins.tools` (name→boolean) applied on the all-on baseline: a tool mapped to
`false` SHALL be removed, `true` (or absent) SHALL stay on; unknown names are ignored. A
whole-source-off SHALL short-circuit and the map SHALL NOT be consulted.

#### Scenario: Disabling removes all builtin tools

- **WHEN** configuration sets the builtins toggle off (`builtins: false`, `builtins.disabled = true`,
  or `builtins.enabled = false`)
- **THEN** the assembled toolkit contains none of the builtin tool names
- **AND** MCP tools, the skill tool, and native/http extra tools are unaffected

#### Scenario: Precedence matches MCP semantics

- **WHEN** the builtins config sets `disabled: true`
- **THEN** the source is off regardless of any `enabled` value, matching MCP's `isEnabled` precedence

#### Scenario: Per-tool map disables named tools only

- **WHEN** configuration sets `builtins.tools = { bash: false, write: false }`
- **THEN** `bash` and `write` are absent from the toolkit
- **AND** the other nine builtin tools remain present
- **AND** an unknown name in the map is ignored without error

#### Scenario: Whole-source-off overrides the per-tool map

- **WHEN** configuration sets both `builtins.disabled = true` and a `builtins.tools` map
- **THEN** no builtin tool appears (the map is not consulted)

### Requirement: Builtin source integrates into toolkit assembly

The toolkit SHALL combine the builtin source alongside the existing MCP, skill, and extra-tool
sources, preserving the current first-wins dedupe-by-name behavior so a host-provided tool of the
same name overrides a builtin.

#### Scenario: Name collision keeps first-registered tool

- **WHEN** a host registers an extra tool whose name equals a builtin tool name and the assembly
  order places one before the other
- **THEN** the toolkit keeps the first-registered tool and drops the duplicate, consistent with
  existing dedupe behavior across all four ports

### Requirement: Builtin tools are surfaced via tool schema, not prompt text

Builtin tools SHALL be presented to the model only through the toolkit's tool-schema array
(`toOpenAI`/`toAnthropic`/`toGemini`), exactly like MCP, native, and HTTP tools — NOT injected as
system-prompt text. The client SHALL NOT add a builtins-specific prompt section, matching the
opencode reference where built-in and MCP tools are schema-surfaced and only skills are listed in
the prompt. This preserves cross-language parity and avoids duplicating tool descriptions.

#### Scenario: Builtins appear in the schema array only

- **WHEN** a toolkit with the builtin source enabled emits its provider tool schema
- **THEN** every builtin tool appears in that schema array
- **AND** no builtin-specific section is added to the system prompt text

### Requirement: Skills prompt instructs the model to use the skill tool

The `skillsPrompt()` output SHALL, when at least one skill is available, begin with an instruction
preamble telling the model that skills provide specialized instructions and to use the `skill` tool
to load a skill when a task matches its description, followed by the existing `## Available Skills`
list. This mirrors the opencode reference so listed skills are not missed by default. The preamble
text SHALL be byte-identical across all four ports; when no skills are available the output SHALL
remain the existing empty/"no skills" result with no preamble.

#### Scenario: Preamble precedes the skills list

- **WHEN** `skillsPrompt()` is called and one or more skills have descriptions
- **THEN** the output starts with the instruction preamble (use the skill tool when a task matches a
  skill's description)
- **AND** the `## Available Skills` list of `- **name**: description` entries follows it
- **AND** the preamble string is identical across js/python/golang/java

#### Scenario: No skills means no preamble

- **WHEN** `skillsPrompt()` is called and no skills are available
- **THEN** the output is the existing empty/no-skills result with no instruction preamble

### Requirement: Filesystem and command tools honor safety rules

The command and filesystem tools (`bash`, `write`, `edit`, `apply_patch`) SHALL follow existing repo
secrets rules, since they grant host command execution and filesystem mutation. Environment-expanded values and
command output MUST NOT be logged, and no secret value SHALL be written into spec, test, or example
fixtures. The global toggle SHALL be the supported mechanism for disabling these tools on
locked-down hosts.

#### Scenario: Toggle off removes command and mutation tools

- **WHEN** a host disables the builtin source via the global toggle
- **THEN** `bash`, `write`, `edit`, and `apply_patch` are absent from the toolkit
