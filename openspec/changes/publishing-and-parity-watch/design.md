## Approach

Publishing is **CI-driven via GitHub Actions, OIDC-first**. A single release workflow publishes
every port when a GitHub Release is cut. Wherever the registry supports **OIDC Trusted Publishing**
(npm, PyPI, and NuGet), CI mints a short-lived token at run time via `permissions: id-token: write`
— **no stored secret**. Only registries without OIDC (Maven Central) use **GitHub Actions secrets**.
No secret is ever committed and no token value passes through an agent. Each publish job is **gated**
(on OIDC config or its secret): if not configured the job skips cleanly, so partial onboarding still
produces a green run and publishes whatever is ready.

## Trigger

`on: release: { types: [published] }` (cut a GitHub Release named `vX.Y.Z`) plus
`workflow_dispatch` with a `version` input as a manual fallback. The tag drives the version; a
pre-flight job asserts all five ports declare the same `X.Y.Z` before any publish runs.

## Phasing

- **Phase 1 (now):** `js/` (npm), `python/` (PyPI), `golang/` (tag) — all OIDC / no stored secret.
- **Phase 2 (later):** `java/` (Maven Central) and `csharp/` (NuGet) — wired but deferred.

## Per-port CI job + auth

| Port | Registry | CI does | Auth | Phase |
|------|----------|---------|------|-------|
| `js/` | npm | `npm ci && npm run build && npm publish` (provenance) | **OIDC** (`id-token: write`) — no stored secret | 1 |
| `python/` | PyPI | build sdist+wheel, `pypa/gh-action-pypi-publish` | **OIDC** trusted publisher — no stored secret | 1 |
| `golang/` | Go module | create+push tag `golang/vX.Y.Z` | none (`GITHUB_TOKEN`) | 1 |
| `java/` | Maven Central | `gradle publish`, GPG-signed | secrets (no OIDC yet): `CENTRAL_USERNAME/PASSWORD`, `GPG_PRIVATE_KEY/PASSPHRASE` | 2 |
| `csharp/` | NuGet | `dotnet pack && dotnet nuget push` | OIDC (preview) or `NUGET_API_KEY` | 2 |

### npm bootstrap (one-time)

npm Trusted Publishing is configured **per existing package**, so the very first publish must be
manual to create the package, then OIDC takes over:
1. `npm login` → `cd js && npm run build && npm publish` (creates `toolnexus@0.1.0`).
2. npmjs.com → the `toolnexus` package → **Trusted Publisher** → GitHub repo `muthuishere/toolnexus`,
   workflow `release.yml`.
3. All later releases publish from CI via OIDC — no token.

(PyPI differs: its **pending publisher** lets OIDC work for the very first publish too — no manual
bootstrap needed.)

## Secret handling

Secrets live only in **GitHub → Settings → Secrets and variables → Actions**. Workflows reference
them as `${{ secrets.NAME }}`; they are masked in logs. Nothing is echoed, and the maintainer adds
them via the GitHub UI or `gh secret set NAME` (which reads stdin/file — the value never reaches an
agent's context).

## Prerequisites (maintainer, one-time)

- Repo pushed to `github.com/muthuishere/toolnexus` (the Go module path + POM url must match the
  real repo — fix the Java POM `url` which currently points at `agentskillsmcp`).
- Registry accounts: npm, PyPI, Sonatype **Central Portal** with the `io.github.muthuishere`
  namespace **verified**, nuget.org. A GPG key for Maven signing (public key published to a keyserver).

## Out of scope

- Changing any runtime behavior in `SPEC.md`.
- Auto-bumping versions (the release tag is the version; ports are bumped in the release PR).
