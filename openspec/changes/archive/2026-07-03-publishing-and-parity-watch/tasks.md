## 1. Prerequisites (maintainer, manual)

- [x] 1.1 Push repo to `github.com/muthuishere/toolnexus`; fix Java POM `url` to match
- [x] 1.2 npm: one-time `npm login` + manual first publish of `toolnexus@0.1.0`, then add GitHub `release.yml` as a **Trusted Publisher** on the npm package
- [x] 1.3 PyPI: configure a **Trusted Publisher** (pending publisher works pre-first-release) — no token
- [x] 1.4 (No GitHub secrets needed for Phase 1 — all OIDC / tag based)

## 2. Release workflow (CI) — Phase 1: npm + PyPI + Go

- [x] 2.1 Add `.github/workflows/release.yml` — `on: release.published` + `workflow_dispatch(version)`
- [x] 2.2 Pre-flight job: assert the Phase-1 ports declare the same version as the tag
- [x] 2.3 npm publish job — `permissions: id-token: write`, `npm publish` via OIDC (provenance)
- [x] 2.4 PyPI publish job — `pypa/gh-action-pypi-publish` via OIDC trusted publishing
- [x] 2.5 Go tag job: push `golang/vX.Y.Z`
- [x] 2.6 Each job skips cleanly if not yet configured (partial onboarding → still green)

## 3. Verify (Phase 1)

- [x] 3.1 Dry-run via `workflow_dispatch` on a pre-release version; confirm gating + version check
- [x] 3.2 Cut `v0.1.0` release; confirm js/python/go resolve from their registries at `0.1.0`

## 4. Phase 2 (later) — Java + C#

- [x] 4.1 Java → Maven Central: GPG key + Central Portal token as GitHub secrets; `gradle publish` job
- [x] 4.2 C# → NuGet: OIDC trusted publishing (or `NUGET_API_KEY`); `dotnet nuget push` job

## 5. Document

- [x] 5.1 Replace manual steps in `PUBLISHING.md` with the CI flow + auth table (OIDC-first)
- [x] 5.2 Confirm no token value appears in any committed file, workflow, or log
