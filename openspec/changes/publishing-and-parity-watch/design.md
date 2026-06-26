## Approach

Publishing is **manual by design** for now — there are no registry credentials in CI, and PyPI
in particular is uploaded by hand. The goal of this change is not automation; it is to make each
port installable at a matching version and to write the steps down so any maintainer can cut a
release reproducibly.

## Per-port mechanics

| Port | Artifact | Publish (token via env, never committed) |
|------|----------|------------------------------------------|
| `js/` | npm package `toolnexus` | `npm publish` with `NODE_AUTH_TOKEN` / `~/.npmrc` auth in the environment |
| `python/` | PyPI `toolnexus` | build sdist+wheel, `twine upload` with `TWINE_PASSWORD` from env |
| `golang/` | Go module `github.com/muthuishere/toolnexus/golang` | tag `golang/vX.Y.Z` and push; `go get` resolves the tag — no registry account needed |
| `java/` | Maven Central `io.github.muthuishere:toolnexus` | Gradle publish to Sonatype with signing key + token from env |

## Version parity

A release is a single decision: bump all four ports to `X.Y.Z`, then publish each. The version
lives in `js/package.json`, `python/pyproject.toml`, the Go tag, and `java/build.gradle`. A
pre-release check confirms the four agree before any publish runs.

## Secret handling

Every token is read from the environment at the point the publish command consumes it (the same
discipline the library already applies to MCP `${ENV_VAR}` header expansion). Nothing is echoed,
logged, or written to a tracked file. Placeholders in docs use obvious fakes
(`YOUR_TOKEN_HERE`).

## Out of scope

- CI-driven / key-based auto-publish (explicitly deferred — no CI credentials).
- Changing any runtime behavior in `SPEC.md`.
