## Why

The toolnexus core (dynamic MCP + skills + native + HTTP tool sources, unified client) is
implemented and at parity across `js/`, `python/`, `golang/`, and `java/` at version `0.1.0`,
but none of the ports are installable from their native registries yet. Until they are, "use it
in your language" is not actually true, and there is no codified rule that keeps the four ports
publishing in lockstep.

## What Changes

- Publish each port to its native registry **via GitHub Actions CI**: npm (`js/`), PyPI
  (`python/`), a tagged Go module (`golang/`), Maven Central (`java/`), and NuGet (`csharp/`).
- Establish **version parity**: one release publishes all ports at one matching version; a
  pre-flight job enforces it.
- Keep publishing credential-safe: tokens live only as **GitHub Actions secrets**, referenced as
  `${{ secrets.* }}` and masked in logs — never committed, printed, or passed through an agent.
  Each publish job is gated on its secret so partial onboarding still yields a green run.
- Document the CI release flow + the secrets table in `PUBLISHING.md`.

No breaking changes to the library contract (`SPEC.md`) — this change is about distribution and
release governance, not runtime behavior.

## Capabilities

### New Capabilities
- `release-publishing`: how toolnexus ports are versioned, published to their registries, and
  kept at matching versions without committing secrets.

### Modified Capabilities
<!-- none — no runtime behavior in SPEC.md changes -->

## Impact

- New/updated: `PUBLISHING.md`, per-port packaging metadata (`js/package.json`,
  `python/pyproject.toml`, `golang` module tag, `java/build.gradle`).
- Release process / CI; no application code paths.
- Dependencies: registry accounts (npm, PyPI, Sonatype/Maven Central) and their tokens, held
  only in the publisher's environment.
