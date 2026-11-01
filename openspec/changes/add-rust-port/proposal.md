# Proposal: add-rust-port

## Why

deemwar-spotlight is a native Rust desktop daemon (macOS / Windows / Linux) with a thin
UI. It needs to drive local coding agents over ACP, discover agent skills, and call MCP
servers, in-process and without a Node/Python/Go sidecar. toolnexus exists to make the same
toolkit substitutable across stacks. Rust is the stack for native desktop and system daemons,
and none of the seven ports covers it. Requested by the spotlight session (owner-approved,
2026-11-01).

## What Changes

An eighth port at `rust/`, crate **`toolnexus`** on crates.io, async on **tokio**. It is
built in the consumer's priority order and grows to the full `SPEC.md §0` contract:

1. **ACP client.** Parity with `golang/acp.go`: spawn an agent over stdio JSON-RPC,
   `initialize`, `session/new`, `session/prompt`, streamed `session/update`, and
   `session/request_permission`, answered through a host callback. The default answers the
   first allow option, as Go does, and a permission timeout is a typed error.
2. **Skills** (§0 items 5–6, §3). `**/SKILL.md` discovery, YAML frontmatter, first name
   wins, and a `skill` tool and `skillsPrompt()` that are **byte-identical** to the other
   ports against `examples/skills/`.
3. **MCP client** (§0 items 2–4, §2). `mcp.json` parsing (`mcpServers` | `servers` |
   `mcp`; command string and args), stdio plus streamable HTTP, `${ENV}` header expansion
   (never logged), per-server failure isolation, the tools allowlist, and the idle timeout
   that resets on progress. It is built on the official Rust SDK **`rmcp`**, the same "use
   the official SDK" choice as js, python, go, java and csharp.
4. **Core types plus native tools** (§0 items 1, 7–8). `Tool`, `ToolResult`, `ToolContext`
   (cancellation via `tokio_util::sync::CancellationToken`), `define_tool`, and the
   OpenAI / Anthropic / Gemini schema adapters.
5. **Later:** the toolkit, `http` tools, builtins, the unified client loop and streaming,
   suspension (§10), conversation store, and A2A. These are tracked as unchecked tasks
   until the port reaches `core` tier.

Cross-cutting:
- **CI:** a hermetic `cargo test` job (no network) runs against the shared `examples/`
  fixtures, with no per-language copies.
- **Conformance:** `rust` joins `conformance/options_manifest.json` (file paths and
  `portTiers`). It is added at **`core` tier only once phase 5 lands**. Before then, the
  options checker would fail on a port with no client. Phases 1–4 are verified by shared
  fixture tests instead: a byte-exact `skill` output, tool naming, and config parsing.
- **Publishing:** a crates.io leg in `release.yml`, gated on `ENABLE_CRATES`, using
  crates.io Trusted Publishing (OIDC, scoped to the `prod` environment, no stored token).
  The `Cargo.toml` version is added to `preflight`. There is no publish before phase 5 and
  no publish without the owner.
- **Docs:** `rust/README.md`; the `SPEC.md` and `CLAUDE.md` language lists go from seven
  to eight; CHANGELOG `## Unreleased`.

Out of scope: Swift and Kotlin (may follow later), and the golang-only §4C host
additions. Those join every port, Rust included, when they are ported.

## Capabilities

### New Capabilities
- `rust-port`: the Rust/tokio implementation, delivered ACP → skills → MCP → core types →
  the rest of §0, with the same observable behavior as the other ports.

## Impact

New `rust/` tree, plus CI, release, conformance manifest and docs rows. No existing port's
behavior changes. Risk: `rmcp`'s API may not expose a hook for progress notifications on a
pending call; if it does not, the idle timeout is a named gap until it does.
