# Per-symbol API reference pages, tested against the codebase

## Why

Today's API Reference is one 805-line combined page (`api/index.mdx`) plus six per-port stubs of
~70 lines each that mostly link back to it. That shape has three problems:

1. **Nothing is addressable.** There is no URL for "how do I call `createToolkit`". A reader
   searching for one symbol lands on a page listing forty of them.
2. **No when/why.** Entries are signature dumps. They show the shape of a call and never the
   reason to reach for it, or what to reach for instead.
3. **Nothing is tested.** Not one snippet in the docs site is compiled or executed. The current
   `api-reference-docs` spec still says "five ports" and predates the Elixir port — the docs have
   already drifted from the code they describe, which is the exact failure mode this repo exists
   to prevent.

## What changes

- **One page per top-level entry point, per port.** URL shape `/api/<lang>/<group>/<member>` —
  e.g. `/api/javascript/toolkit/create`, `/api/go/client/create`. The surface is the top-level
  public API defined by `SPEC.md` §1–§11, not every exported symbol: `SPEC.md` is the contract, so
  it is also the coverage boundary. Data shapes (`ToolkitOptions`, `RunResult`, `SkillDef`, …) are
  documented as field tables on the page of the entry point that consumes them, not as their own
  URLs.
- **Every page carries: signature, when to use it, why (and what to use instead), three worked
  examples, and its five sibling-language equivalents** as links to their own pages.
- **Examples come from this repo's real code** — `examples/` fixtures, the per-port `examples/`
  programs, and port test suites — not invented snippets.
- **Every example is compiled and executed in CI**, hermetically, against the shared `examples/`
  fixtures with a mock LLM. A docs example that no longer builds fails the build.
- **A coverage gate** derives the required page set from the manifest and fails if any entry point
  in `SPEC.md`'s surface lacks a page in any of the six ports.
- The combined `api/index.mdx` stays as the cross-language index and becomes a directory of links.

## Impact

- Affected specs: `api-reference-docs` (both requirements superseded — five ports → six, one page
  per port → one page per entry point, plus new testing and coverage requirements).
- Affected code: `site/` only — new `site/scripts/inventory/`, `site/scripts/` gates, new
  `site/src/content/docs/api/<lang>/**` pages, sidebar config, and a new `docs-examples` CI job.
  **No port behavior changes**, so the six implementations are untouched.
