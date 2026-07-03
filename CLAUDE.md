# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What we're trying to build

**toolnexus** — a small, **vendor-neutral** library that gives *any* LLM the dynamic
capabilities [opencode](https://github.com/anomalyco/opencode) has, ported **byte-identically
across five languages** (`js/`, `python/`, `golang/`, `java/`, `csharp/`):

1. **Dynamic MCP servers** — read an `mcp.json`, connect to every server (local stdio +
   remote streamable-HTTP), expose each server tool as a uniform `Tool`.
2. **Dynamic agent skills** — glob a `skills/` folder (`**/SKILL.md`) and expose one `skill`
   tool that loads a skill's instructions + resources on demand (progressive disclosure).

The insight: MCP tools, agent skills, your own functions (`@tool`), remote HTTP endpoints, the
built-in shell/file tools, and remote **A2A agents** are *the same thing* to an LLM — a named,
described, schema'd callable. toolnexus unifies all these tool sources behind one `Tool`
interface, emits the schema in **OpenAI / Anthropic / Gemini** formats, and ships a **unified
client** with a built-in tool-calling loop (system prompt, skills injection, parallel + chained
tool calls, hooks, streaming, retries, conversation memory, observability metrics).

It is also an **inbound** endpoint: `toolkit.serve(addr, …)` exposes the toolkit to the outside
world — today as a remote **A2A agent** (Agent Card + JSON-RPC over the client loop; `SPEC.md §7B`).

The whole point of the ports is **parity**: the same `examples/` fixtures must produce the
same behavior in every language. That is the product. Protecting it is the prime directive
below.

## Repo layout

| Path | What |
|------|------|
| `SPEC.md` | **The shared contract.** The one-page conformance spec (§0) every port must satisfy, plus reference detail (§1–§9). Source of truth for behavior. |
| `js/` | TypeScript port — `toolnexus` (npm). MCP SDK: `@modelcontextprotocol/sdk` (same as opencode). |
| `python/` | Python port — `toolnexus` (PyPI), Python ≥ 3.11. MCP SDK: `mcp`. |
| `golang/` | Go port — `github.com/muthuishere/toolnexus/golang`, Go 1.23. MCP SDK: `mark3labs/mcp-go`. Also ships the `toolnexus` CLI under `cmd/`. |
| `java/` | Java port — `io.github.muthuishere:toolnexus`, Java 21. MCP SDK: official `io.modelcontextprotocol.sdk:mcp`. |
| `csharp/` | C# port — `Toolnexus` (NuGet), .NET. MCP SDK: `ModelContextProtocol`. |
| `examples/` | **Shared cross-language fixtures** — `mcp.json` + `skills/hello-world/`. Every port runs against these; outputs must match. |
| `openspec/` | **Spec-driven change workflow (OpenSpec).** `changes/` = active proposals (`proposal.md` + spec deltas + `tasks.md`), `specs/` = canonical capability specs, `changes/archive/` = shipped changes. Run the `openspec` CLI from the repo root. |
| `.github/workflows/ci.yml` | Runs all five suites (JS/Python/Go/Java/C#), hermetically (no network, no live LLM). |

## The prime directive: spec-driven, five-language parity

`SPEC.md` is the contract; the five ports are implementations of it. Two rules govern every change:

1. **Behavior is defined in the spec before it is written in code.** `SPEC.md §0` is the
   conformance contract — if a change alters observable behavior (tool naming, config parsing,
   the `skill` tool's byte-exact output, adapter mapping, the client loop), it changes `SPEC.md`
   *first*, then the code. Any change of real substance goes through an **OpenSpec change** first
   (below) — the proposal's spec deltas are where intended behavior is pinned before code.
2. **A behavior change lands in all five ports, or it is not done.** Do not ship a capability
   in `js/` and leave `python/`/`golang/`/`java/`/`csharp/` behind without explicitly saying so
   and tracking it in an in-progress spec. The ports are meant to be substitutable; silent drift
   is the one bug this repo exists to prevent.

> "Correct" is defined by §0: run a port against the shared `examples/` (same `mcp.json` + skill)
> and its outputs must match the others. When in doubt, make the spec authoritative and bring
> code to it — not the reverse.

## Required Workflow — OpenSpec

**Every change of substance runs through [OpenSpec](https://github.com/Fission-AI/OpenSpec).**
Slash commands (Claude Code): `/opsx:propose`, `/opsx:apply`, `/opsx:archive` (plus
`/opsx:explore`, `/opsx:sync`). CLI: `openspec` — use `npx @fission-ai/openspec@latest …` until
it is installed globally.

1. **Propose** — `/opsx:propose "<idea>"` scaffolds a change at `openspec/changes/<name>/`:
   `proposal.md` (why + what), `design.md` (how), `tasks.md` (checklist), and **spec deltas** at
   `specs/<capability>/spec.md`. This is where intended behavior is pinned, *before* code. If the
   change moves the cross-language contract, edit `SPEC.md` in the same change.
2. **Validate** — `openspec validate <name>` (catches malformed deltas / missing scenarios).
3. **Apply across the ports** — `/opsx:apply`. Implement the tasks in **every affected language**
   and tick them off. Each behavior change carries a **per-language parity checklist** (js /
   python / golang / java / csharp) in `tasks.md`; if a pass covers only a subset, the rest stay
   as unchecked tasks — never let parity drift silently.
4. **Verify** — run the narrowest useful suite per touched port (see Commands) and check parity
   against the shared `examples/`. Call out anything unverified.
5. **Open the PR** with the change folder + code in one diff, so reviewers see intent and
   implementation together.
6. **Archive on merge** — once the PR **merges**, run `/opsx:archive` (or
   `openspec archive <name>`). This folds the spec deltas into `openspec/specs/` and moves the
   change to `openspec/changes/archive/`. **Archive only after merge** — never when the PR is
   merely opened, so `openspec/specs/` always describes shipped behavior, not work under review.

Default to a change for: a new tool source, a change to MCP/skill discovery or naming, the
`skill` tool output, adapter (OpenAI/Anthropic/Gemini) mapping, the client loop (hooks,
streaming, resilience, memory), the Go CLI surface, publishing, or a new language port. Keep
ceremony minimal for typo-only fixes; tightly scoped bug fixes should still update the capability
spec that owns the behavior.

### How the two spec layers relate

- **`SPEC.md`** — the hand-maintained **cross-language conformance contract**, the byte-level
  porting obligation (`§0` is the one-page contract). Source of truth for "are the four ports
  identical."
- **`openspec/specs/`** — the **capability specs** that accrue from archived changes: the
  growing, validated record of *what each capability does*, in requirement/scenario form.
- A behavior change updates **both** — the spec delta in the OpenSpec change, and the relevant
  `SPEC.md` section when the cross-language contract itself moves.

### Spec delta format (the part that bites)

- Requirement: `### Requirement: <name>`, SHALL/MUST wording.
- Scenario: **exactly four** hashes — `#### Scenario: <name>` — with `- **WHEN** …` / `- **THEN** …`.
  Three hashes or bullets fail silently at archive time.
- Every requirement needs ≥ 1 scenario. A `MODIFIED` requirement must paste the **full** updated
  block, not a fragment.

## Common Commands

Each port is self-contained and hermetic — `cd` into its directory first. CI runs exactly these.

| Port | Build | Test |
|------|-------|------|
| `js/` | `npm install && npm run build` | `npm test` (builds, then `node --test`) · run a demo: `npm run example` |
| `python/` | `pip install -e ".[test]"` | `python -m pytest -q` |
| `golang/` | `go build ./...` && `go vet ./...` | `go test -race ./...` |
| `java/` | `./gradlew build --no-daemon` | `./gradlew test --no-daemon` |
| `csharp/` | `dotnet build` | `dotnet test` |

All five ports are currently at version `0.4.0`. Publishing (npm / PyPI / NuGet / Maven Central /
Go module tag) is done **manually** — key-based auto-deploy is intentionally not wired (see
in-progress specs). Never bake registry tokens into code, config, or CI; they are use-only env vars.

## Coding conventions

- **Match the local style of each port.** Idiomatic TS, idiomatic Python, idiomatic Go,
  idiomatic Java, idiomatic C# — not a transliteration of one language into another. Same
  *behavior*, native *shape*.
- **The `examples/` fixtures are shared and authoritative** — don't fork per-language copies;
  if a fixture must change, change it once and re-verify every port.
- **Secrets are use-only.** Remote MCP `headers` values expand `${ENV_VAR}` from the environment
  at call time and are **never logged**. Never write a real key into a spec, test, example, or
  comment — read it from the environment; use an obvious fake (`YOUR_KEY_HERE`) for placeholders.
- **Conventional commits**: `feat:`, `fix:`, `docs:`, `test:`, `chore:`, `ci:` — scope by port
  where it helps, e.g. `feat(go client): …`, `test(js): …`. Do **not** add `Co-authored-by:`.
- Touch only what the task needs; note unrelated issues rather than fixing them inline.

## Documentation map

| Document | Purpose |
|----------|---------|
| `CLAUDE.md` | Project guidance for Claude Code (this file) |
| `SPEC.md` | The shared cross-language contract — start here for any behavior question |
| `README.md` | End-user pitch + "zero to agent in 3 steps" |
| `js/README.md`, `python/README.md`, `golang/README.md` + `golang/GUIDE.md`, `java/README.md`, `csharp/README.md` | Per-language end-user docs |
| `openspec/changes/` | Active OpenSpec change proposals — feature/change work starts here (`/opsx:propose`) |
| `openspec/specs/` | Canonical capability specs (accrue from archived changes) |
| `PUBLISHING.md` | How each port is published |

Consult `SPEC.md`, `openspec/`, and the relevant port's README before making behavior changes.
Run `/opsx:propose` to start any non-trivial change.
