# Publishing toolnexus

All five ports publish from **one** GitHub Actions workflow — `.github/workflows/release.yml`.
You cut a GitHub Release and the workflow publishes every enabled port at the release's version.
No tokens are stored for npm / PyPI / NuGet (OIDC Trusted Publishing); Go is a tag push; only Maven
Central uses stored secrets.

Package names (all live): npm **`toolnexus`**, PyPI **`toolnexus`**, NuGet **`Toolnexus`**,
Maven **`io.github.muthuishere:toolnexus`**, Go module **`github.com/muthuishere/toolnexus/golang`**.

## Release in one step

1. **Bump every manifest to the same version** (a pre-flight job fails the run otherwise):
   `js/package.json`, `python/pyproject.toml`, `csharp/src/Toolnexus/Toolnexus.csproj` (`<Version>`),
   `java/build.gradle` (`version`). Go has no manifest — it releases as a tag.
2. Merge to `main`.
3. **Cut a GitHub Release named `vX.Y.Z`** targeting `main`:
   ```sh
   gh release create v0.5.0 --target main --title "v0.5.0 — <headline>" --notes "..."
   ```
   That triggers `release.yml` (`on: release.published`). Each port publishes **iff** its repo
   variable is `true` (Settings → Secrets and variables → Actions → Variables):
   `ENABLE_NPM`, `ENABLE_PYPI`, `ENABLE_GO`, `ENABLE_NUGET`, `ENABLE_JAVA` — all currently on.
4. Watch it: `gh run watch <run-id> --exit-status`.

Dry run / manual: `release.yml` also has a `workflow_dispatch` with a `version` input. (Note: npm and
PyPI reject re-publishing an existing version, so only dispatch a version you have not released.)

## How each port authenticates

| Port | Mechanism | Secret / config |
|------|-----------|-----------------|
| **npm** | OIDC Trusted Publishing (provenance) | none — the npm Trusted Publisher is scoped to the `prod` GitHub environment. Needs npm ≥ 11.5.1 (the job upgrades it). |
| **PyPI** | OIDC Trusted Publishing (`pypa/gh-action-pypi-publish`) | none — a PyPI Trusted Publisher scoped to `prod`. |
| **NuGet** | OIDC (`NuGet/login@v1` mints a short-lived key) | none — a NuGet Trusted Publishing policy scoped to `prod`. |
| **Go** | tag push `golang/vX.Y.Z` | none — `contents: write` in the workflow. Idempotent (skips if the tag exists). |
| **Maven Central** | Gradle `publishAndReleaseToMavenCentral` | `prod` environment secrets: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` (Sonatype Central Portal token), `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. No OIDC available for Central. |

**Version parity is enforced.** The `preflight` job reads all four manifests and fails the run unless
each equals the release tag — so partial version bumps never publish a mismatched set.

## Consumers install

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
dotnet add package Toolnexus                       # C#
go get github.com/muthuishere/toolnexus/golang    # Go
# Java (Maven): io.github.muthuishere:toolnexus:<version>
```

## Secrets discipline

Registry credentials live **only** as GitHub Actions secrets in the `prod` environment, referenced
as `${{ secrets.* }}` and masked in logs — never committed, printed, or passed through an agent. To
rotate the Maven Central token: regenerate a user token at central.sonatype.com, then update
`CENTRAL_USERNAME` / `CENTRAL_PASSWORD` in the `prod` environment and re-run the failed job
(`gh run rerun <run-id> --failed`).

## Pre-publish checklist

- [ ] All four manifests bumped to the same `X.Y.Z`
- [ ] `js`: `npm run build` clean · `python`: `python -m build` clean · `golang`: `go build ./... && go vet ./...` clean · `csharp`: `dotnet build && dotnet test` clean · `java`: `./gradlew build`
- [ ] README + SPEC version references bumped together
- [ ] CI green on `main` before cutting the Release
