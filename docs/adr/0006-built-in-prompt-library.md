# ADR 0006 — Built-in prompt library: shipped system-prompt blocks + task-prompt cheatsheet

- **Status:** Rejected as shipped API surface (2026-07-14) — freezing prose blocks
  byte-identical across five ports is a permanent parity obligation (this ADR's own QPB2
  admits it) for content that drifts with model generations far faster than APIs do, and it
  is the highest-maintenance kind of surface this repo has. The cheatsheet value, if wanted,
  returns as **documentation** (a docs-site page or `examples/` folder consumers copy from),
  not as a `§4B` contract. Revisit only if a consumer asks for programmatic blocks by name.
- **Date:** 2026-07-14
- **Driver:** toolnexus ships built-in **tools** (`§4A`) so an agent can *act* with zero wiring —
  but it ships no built-in **prompts**, so every consumer hand-writes the system prompt, the
  tool-use discipline, the "you have a `task` tool, delegate" guidance, the output-format rules,
  from scratch and slightly differently. A shipped, named, composable prompt library — a
  **prompt cheatsheet** — is the missing battery. Modeled on VS Code Copilot's two shipped shapes:
  **task prompts** (`extensions/copilot/assets/prompts/plan.prompt.md` — frontmatter
  `name/description/agent/argument-hint` + a body) and **default instruction blocks** (model-aware
  system-prompt building blocks, e.g. `OpenAIReminderInstructions`,
  `extensions/copilot/src/extension/prompts/node/agent/openai/defaultOpenAIPrompt.tsx:138-145`,
  composed from `getEditingReminder` in `defaultAgentInstructions.tsx`).
- **Honesty note:** reference-architecture-verified, design-forward. New capability, no existing
  workaround to cite. toolnexus facts are line-cited; the proposal is toolnexus's to trim or drop.
- **Design principle (reuse, don't rebuild):** a prompt asset is *a skill body without a tool* —
  frontmatter + markdown. toolnexus already parses exactly that (`parseFrontmatter`,
  `js/src/skill.ts:90`, `SPEC.md §3`) and already composes a system string from parts
  (`system() = [systemPrompt, skillsPrompt()].join("\n\n")`, `client.ts:424-425`, `SPEC.md §8`).
  This ADR ships **content** through the machinery that exists; it does **not** introduce a
  prompt-rendering framework (no prompt-tsx, no `PromptSizing` — that stays out of scope, see
  Non-goals). Kept as **plain data** so it is byte-identical across all five ports.

## Context

Where a consumer wires an agent today:

```ts
new Client({ systemPrompt: "You are a coding agent. Be careful. Use tools. ...", ... })
```

— that string is theirs to invent, per app, per language, and it drifts. Copilot instead
composes the system prompt from **named, reusable blocks** (role + reminders + tool guidance) and
ships **task prompts** users invoke by name (`/plan`, `/explain`). toolnexus has the assembler
(`system()`) and the parser (`§3`) but ships **no library of blocks or task prompts** to feed
them. This ADR adds that library as a new shipped source `§4B`, beside built-in tools `§4A`.

Priority order: **PB1 system-prompt block catalog → PB2 task-prompt templates (the cheatsheet) →
PB3 model-aware variants.**

---

## Gap PB1 — A catalog of composable system-prompt blocks (priority 1)

### Motivation
Every agent needs the same handful of prose blocks: a role, tool-use discipline, an output
contract, a delegation note (once `task` from ADR 0005 exists), a safety line. Shipping them named
and composable means a consumer writes `blocks: ["role.coding", "tooluse.default", "delegate.note"]`
instead of a wall of hand-rolled text — and every toolnexus agent reads consistently. Copilot ships
exactly these as `defaultAgentInstructions` fragments (`getEditingReminder`, reminder-instruction
classes).

### Proposed API (additive)
```ts
// Shipped as plain data in every port: name → markdown string (byte-identical).
// e.g. "role.coding", "role.research", "role.planner", "tooluse.default",
//      "tooluse.parallel", "delegate.note", "output.concise", "safety.default"
export const BUILTIN_PROMPTS: Record<string, string>

// ClientOptions / system() addition — compose blocks into the system prompt.
interface ClientOptions {
  // Named built-in blocks prepended (in order) before systemPrompt, before skillsPrompt().
  // Unknown names are ignored + warned once (mirrors the §2/§3/§4A unknown-filter rule).
  promptBlocks?: string[]
}
// New assembly order for system() (SPEC §8):
//   [ ...resolve(promptBlocks), systemPrompt ?? "", skillsPrompt() ].filter(Boolean).join("\n\n")
```

A consumer can read a block for reference (`BUILTIN_PROMPTS["tooluse.default"]`) — that *is* the
cheatsheet aspect — or reference it by name and let `system()` inline it.

### Acceptance tests
- `promptBlocks: ["role.coding","tooluse.default"]` with no `systemPrompt` produces a system
  string = those two blocks joined by `\n\n`, byte-identical across all five ports.
- Unknown block name is dropped with one warning; the rest compose.
- `promptBlocks` unset ⇒ `system()` is byte-identical to today.

### Cross-language parity
New `SPEC.md §4B` with the **frozen block set** (names + exact markdown) — this is the parity
surface: the strings must match byte-for-byte across ports, so they live in a shared
`examples/prompt-blocks/` golden and the ADR change adds them to `SPEC.md §4B` verbatim.

---

## Gap PB2 — Task-prompt templates with argument substitution (the cheatsheet) (priority 2)

### Motivation
Copilot ships `*.prompt.md` files — named, parameterized task starters a user invokes
(`/plan <what>`), each targeting an agent and carrying an `argument-hint`
(`plan.prompt.md`). This is the literal "prompt cheatsheet": a shelf of ready task prompts
(`plan`, `review`, `explain`, `test`, `refactor`, `summarize`) that expand a short invocation into
a full, well-formed prompt — optionally routed to a registered agent (ADR 0005 §7D).

### Proposed API (additive)
```ts
// Shipped data: name → template. Same frontmatter vocabulary as a skill/Copilot prompt file.
interface PromptTemplate {
  name: string
  description: string
  argumentHint?: string
  agent?: string            // optional: route to a registered AgentDef (ADR 0005 G2)
  body: string              // markdown with ${arg} / {{arg}} placeholders
}
export const BUILTIN_PROMPT_TEMPLATES: Record<string, PromptTemplate>

// Helper on Client — expand a template and run it (routes to `agent` if set, via ADR 0005 `task`).
class Client {
  prompt(name: string, args?: Record<string, string>, toolkit?: Toolkit): Promise<RunResult>
}
```

Consumers can also load their **own** `*.prompt.md` files through the exact skill discovery path
(`§3` walker + `parseFrontmatter`) — a prompt file is a `SKILL.md` sibling with a `body` and no
tool. Substitution is a single documented rule (missing arg ⇒ empty + warn once) held identical
across ports.

### Acceptance tests
- `client.prompt("explain", { target: "foo.ts" })` expands the shipped `explain` template with the
  arg substituted and runs it; result is a normal `RunResult`.
- A template with `agent: "researcher"` routes through the ADR 0005 `task`/registry path (skipped
  as pending if 0005 isn't landed — this ADR degrades gracefully to "run on the default agent").
- Custom user `*.prompt.md` discovered from a dir participates identically to shipped templates.
- Missing `${arg}` substitutes empty with one warning; extra args ignored.

### Cross-language parity
`SPEC.md §4B` sub-section: the template struct, the frozen shipped set, and the one substitution
rule. Shared fixture `examples/prompt-templates/` with golden expansions.

---

## Gap PB3 — Model-aware block variants (priority 3, optional)

### Motivation
The same instruction reads better tuned per model family — Copilot resolves different default
reminders for OpenAI vs others (`DefaultOpenAIPromptResolver.resolveReminderInstructions`,
`defaultOpenAIPrompt.tsx:133-135`). toolnexus already branches `ClientStyle = "openai" | "anthropic"`
(`client.ts:10`), so a block may ship a per-style variant chosen by the client's style — same
name, style-appropriate wording.

### Proposed API (additive)
```ts
// A block value may be a string OR a per-style map; resolve() picks by the client's ClientStyle.
type PromptBlock = string | Partial<Record<ClientStyle, string>>
export const BUILTIN_PROMPTS: Record<string, PromptBlock>
```

Default: a plain-string block applies to every style (today's behavior). Only blocks that *have* a
per-style map vary. Keep this **small** — most blocks should stay single-string; per-style variants
are the exception, not the rule.

### Acceptance tests
- A single-string block resolves identically under both styles.
- A block with `{ openai, anthropic }` resolves to the matching variant per the client's style.

### Cross-language parity
`SPEC.md §4B`: the `PromptBlock` union + style-resolution rule. Fixture covers both styles.

---

## Non-goals (recorded so minimalism stays a choice)
- **No prompt-tsx / token-budgeted rendering.** Copilot's `PromptSizing`/priority-pruning
  (`OpenAIReminderInstructions.render(state, sizing)`) is a whole framework; toolnexus ships
  prompt **content**, not a renderer. Budget/compaction, if ever wanted, is a *separate* ADR — it
  is deliberately out of scope here.
- **No hidden prompt injection.** Built-in blocks are inert until a consumer names them in
  `promptBlocks` (like built-in *tools* are inert until reached through `toOpenAI()` — `§4A` end).
  Nothing is added to the system prompt implicitly.

## Consumer questions (toolnexus decides)
- **QPB1 (scope):** ship PB1 (blocks) alone first as the useful minimum, or PB1+PB2 together so
  the "cheatsheet" is real from day one? PB2's value depends partly on ADR 0005's registry.
- **QPB2 (frozen set):** which blocks/templates make the v1 frozen set? Suggest starting tiny —
  blocks `{role.coding, role.research, role.planner, tooluse.default, delegate.note, output.concise}`,
  templates `{plan, review, explain, test}` — because every name added to `§4B` is a cross-port
  byte-parity obligation forever.
- **QPB3 (placeholder syntax):** `${arg}` vs `{{arg}}` for PB2 — pick one and freeze it in `§4B`
  (must be identical across ports and not collide with markdown a template body might contain).
- **QPB4 (default-on?):** should any block be composed by default when `promptBlocks` is unset
  (e.g. a `tooluse.default` when tools are present), or is the library strictly opt-in? Recommend
  strictly opt-in to preserve today's byte-identical `system()`.
