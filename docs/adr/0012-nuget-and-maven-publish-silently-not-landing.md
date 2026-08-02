# ADR 0012 — NuGet and Maven Central publishes report success but do not land

**Status:** OPEN DEFECT — found 2026-07-30 while publishing v0.12.0. Not caused by that
release; it has been failing since **0.11.0**.
**Severity:** High for the C# and Java ports — the last two releases are unavailable to
users despite green CI and a green release workflow.
**Unaffected:** npm, PyPI, Hex, and the Go tag all publish correctly.

## Symptom

`release.yml` reports **success on all six publish jobs**, and the job logs are positive:

- NuGet: `Successfully created package 'Toolnexus.0.12.0.nupkg'` → `Created https://www.nuget.org/api/v2/package/` → `Your package was pushed.`
- Maven: `publishAndReleaseToMavenCentral` runs `:publishAllPublicationsToMavenCentralRepository` **and** `:releaseRepository`, then `BUILD SUCCESSFUL`.

But neither artifact reaches its registry.

## Evidence (measured 2026-07-30, right after the v0.12.0 run)

| Registry | Latest live | 0.11.0? | 0.12.0? |
|---|---|---|---|
| npm | **0.12.0** | ✅ | ✅ |
| PyPI | **0.12.0** | ✅ | ✅ |
| Hex | **0.12.0** | ✅ | ✅ |
| Go tag | `golang/v0.12.0` | ✅ | ✅ |
| **NuGet** | **0.10.0** | ❌ | ❌ |
| **Maven Central** | **0.10.0** | ❌ | ❌ |

```
# NuGet flatcontainer versions — note the gap after 0.10.0
[... '0.9.3', '0.9.4', '0.9.5', '0.10.0']
# NuGet registration index upper bound: 0.10.0   (so not merely unlisted — absent)

# Maven Central maven-metadata.xml — same gap
[... <version>0.9.4</version>, <version>0.9.5</version>, <version>0.10.0</version>]
```

**0.11.0 shipped 2026-07-26 — four days before this measurement.** That rules out indexing
lag as the explanation: NuGet indexing is minutes and Central sync is tens of minutes, not
days. Both registries stopped landing at the same version boundary (0.10.0 → 0.11.0), which
suggests a common cause introduced around the 0.11.0 release rather than two coincidental
failures.

## What is NOT the cause

- **Not the version bump or the manifests.** The `preflight` job verifies all five manifests
  match the release tag and it passed; both jobs packed artifacts stamped `0.12.0`.
- **Not auth method.** NuGet uses OIDC and Maven uses `prod` environment secrets, while the
  three that work span both (npm/PyPI OIDC, Hex secret). No clean split.
- **Not this release's code.** 0.11.0 predates it and is equally absent.

## Leading hypotheses (untested)

1. **Asynchronous validation failure after the job exits.** Both registries accept an upload
   synchronously and validate afterwards. A push that returns 2xx can still be rejected later
   (NuGet package validation; Central Portal deployment validation). The workflow would report
   success either way, because it never polls for the terminal state. This fits the evidence
   best and would explain both registries at once.
2. **Central Portal deployment left in a non-published state.** `releaseRepository` succeeding
   locally does not guarantee the Portal deployment reached `PUBLISHED`; it can sit in
   `VALIDATING`/`PENDING` or fail validation out of band.
3. **A silently swallowed credential problem** that still returns a 2xx upload.

## Why the workflow did not catch it

`release.yml` treats "the push command exited 0" as "published". For registries with
**asynchronous** acceptance that is not the same claim. There is no post-publish verification
step, so two consecutive releases went out believing they had shipped six ports when they had
shipped four.

## Proposed fix

1. **Add a post-publish verification job** that polls each registry for the released version
   and fails the run if it does not appear within a bounded window — the cheap, high-value
   fix, and it would have caught this on 0.11.0:
   - npm `registry.npmjs.org/toolnexus` · PyPI `pypi.org/pypi/toolnexus/json`
   - Hex `hex.pm/api/packages/toolnexus`
   - NuGet `api.nuget.org/v3-flatcontainer/toolnexus/index.json`
   - Maven `repo1.maven.org/maven2/io/github/muthuishere/toolnexus/maven-metadata.xml`
     (allow a longer window here — Central sync is genuinely slow)
2. **Poll the terminal state at the source** rather than trusting the upload: the Central
   Portal deployment status API, and NuGet's validation status.
3. **Investigate the two missing versions.** Check the Central Portal for stuck/failed
   deployments of 0.11.0 and 0.12.0, and the NuGet account for rejected pushes — the rejection
   reason is the actual root cause and is only visible there.
4. **Re-publish once fixed.** 0.11.0 and 0.12.0 were never consumed on these two registries, so
   the version numbers are still free — no immutability problem to work around. This is
   recoverable.

## Consequence for users, stated plainly

`Toolnexus` (NuGet) and `io.github.muthuishere:toolnexus` (Maven Central) are at **0.10.0**.
Anyone following the README's `0.12.0` coordinate will fail to resolve. The C# and Java ports
have shipped no release since 0.10.0, and the release notes for 0.11.0 and 0.12.0 overstate
availability for those two ports until this is fixed.
