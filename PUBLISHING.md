# Publishing toolnexus

All seven ports publish from **one** GitHub Actions workflow — `.github/workflows/release.yml`.
You cut a GitHub Release and the workflow publishes every enabled port at the release's version.
No tokens are stored for npm / PyPI / NuGet (OIDC Trusted Publishing); Go is a tag push; Maven
Central, Hex and Clojars use stored secrets, because none of those three offers OIDC.

Package names (all live): npm **`toolnexus`**, PyPI **`toolnexus`**, NuGet **`Toolnexus`**,
Maven **`io.github.muthuishere:toolnexus`**, Go module **`github.com/muthuishere/toolnexus/golang`**,
Hex **`toolnexus`**, Clojars **`net.clojars.muthuishere/toolnexus`**.

## Release in one step

1. **Bump every manifest to the same version** (a pre-flight job fails the run otherwise):
   `js/package.json`, `python/pyproject.toml`, `csharp/src/Toolnexus/Toolnexus.csproj` (`<Version>`),
   `java/build.gradle` (`version`), `elixir/mix.exs` (`@version`), `clojure/build.clj` (`(def version …)`).
   Go has no manifest — it releases as a tag.
2. Merge to `main`.
3. **Cut a GitHub Release named `vX.Y.Z`** targeting `main`:
   ```sh
   gh release create v0.5.0 --target main --title "v0.5.0 — <headline>" --notes "..."
   ```
   That triggers `release.yml` (`on: release.published`). Each port publishes **iff** its repo
   variable is `true` (Settings → Secrets and variables → Actions → Variables):
   `ENABLE_NPM`, `ENABLE_PYPI`, `ENABLE_GO`, `ENABLE_NUGET`, `ENABLE_JAVA`, `ENABLE_ELIXIR`,
   `ENABLE_CLOJARS`.
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
| **Hex** | `mix hex.publish --yes` | `prod` environment secret: `HEX_API_KEY`. No OIDC on Hex. |
| **Clojars** | `clojure -T:build deploy` (tools.build + deps-deploy) | `prod` environment secrets: `CLOJARS_USERNAME`, `CLOJARS_PASSWORD` (a Clojars **deploy token**, not the account password). No OIDC on Clojars. |

**Version parity is enforced.** The `preflight` job reads all six manifests and fails the run unless
each equals the release tag — so partial version bumps never publish a mismatched set.

### Clojure / Clojars — the one thing that is not like the others

The group is **`net.clojars.muthuishere`**, not `io.github.muthuishere` as on Maven Central.
Clojars pre-verifies `net.clojars.<user>` for every account; `io.github.<user>` needs a one-time
GitHub verification this group has not been through (koine hit a 403 *"Group
'io.github.muthuishere' doesn't exist"* on deploy, 2026-07-30). It is also the group koine already
publishes under, so the port and its only dependency sit together.

Release tooling (`tools.build` + `deps-deploy`) lives behind the **`:build` alias** in
`clojure/deps.edn` and must stay there. `clojure/deps-purity-check.sh` resolves the *default*
transitive classpath and fails on anything beyond `clojure` + `spec.alpha` + `core.specs.alpha` +
`koine`; moving build tooling into `:deps` would trip that gate — and that gate is what keeps the
port loadable on cljgo, which cannot run Java-carrying libraries at all.

```sh
cd clojure
clojure -T:build jar        # target/toolnexus-<v>.jar + pom (koine is the only third-party dep)
clojure -T:build install    # into ~/.m2, to try it from another project first
clojure -T:build deploy     # Clojars — reads CLOJARS_USERNAME / CLOJARS_PASSWORD from the env
```

The jar is **source-only** — no `compile-clj`. The same `.cljc` has to load on cljgo, so AOT class
files would defeat the point. `*_test.cljc`, `test_main.cljc` and `run_tests.cljc` are excluded:
the tests share `src/` because cljgo compiles one tree, but they are programs, not library code.

## Consumers install

```sh
npm i toolnexus                                   # JS / TypeScript
pip install toolnexus                             # Python
dotnet add package Toolnexus                       # C#
go get github.com/muthuishere/toolnexus/golang    # Go
# Java (Maven): io.github.muthuishere:toolnexus:<version>
# Elixir (mix.exs): {:toolnexus, "~> <version>"}
# Clojure (deps.edn): net.clojars.muthuishere/toolnexus {:mvn/version "<version>"}
```

## Secrets discipline

Registry credentials live **only** as GitHub Actions secrets in the `prod` environment, referenced
as `${{ secrets.* }}` and masked in logs — never committed, printed, or passed through an agent. To
rotate the Maven Central token: regenerate a user token at central.sonatype.com, then update
`CENTRAL_USERNAME` / `CENTRAL_PASSWORD` in the `prod` environment and re-run the failed job
(`gh run rerun <run-id> --failed`). Clojars is the same shape: mint a **deploy token** at
clojars.org → Settings → Deploy Tokens (never the account password), store it as `CLOJARS_PASSWORD`
in the `prod` environment alongside `CLOJARS_USERNAME`, and re-run.

## Pre-publish checklist

- [ ] All six manifests bumped to the same `X.Y.Z`
- [ ] `js`: `npm run build` clean · `python`: `python -m build` clean · `golang`: `go build ./... && go vet ./...` clean · `csharp`: `dotnet build && dotnet test` clean · `java`: `./gradlew build` · `elixir`: `mix test` ·
      `clojure`: `./deps-purity-check.sh` + `clojure -T:build jar` clean
- [ ] README + SPEC version references bumped together
- [ ] CI green on `main` before cutting the Release
