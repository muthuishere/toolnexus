---
name: release
description: Cut and verify a toolnexus release across all seven registries, with the guardrails that a green CI run does not give you. Use whenever the user says publish, release, ship it, cut a release, bump the version, or asks whether a release actually landed. Also use to re-publish a single failed registry leg.
license: MIT
compatibility: Requires gh (authenticated), node, git. Read-only checks run anywhere; publishing needs owner authorization.
metadata:
  author: toolnexus
  version: "1.0"
---

# Releasing toolnexus

Publishing is **outward-facing and irreversible**: a version number can never be reused on npm,
PyPI, NuGet, Maven Central, Hex or Clojars. This skill exists because the obvious signals lie in
both directions — a green workflow can ship nothing, and a red workflow can be a complete success.

## The two rules that override intuition

**1. A green run is not evidence a package landed.** ADR-0012 exists because NuGet and Maven have
reported success without publishing. **Always verify from outside, as a consumer would** (step 6).

**2. A red run is not evidence of failure.** Re-dispatching a completed release makes the
already-published legs refuse a duplicate — npm `cannot publish over the previously published
versions`, Clojars `403 redeploying non-snapshots is not allowed`. That is immutability working.
Read the **job list**, not the conclusion.

## Never do these

- **Never publish unless the owner asked in this conversation.** "Is it ready?" is a question, not
  authorization.
- **Never release unmerged code.** The release is cut from `main` after the PR merges.
- **Never bump one port "to unblock".** `preflight` refuses it, and that refusal is the point.
- **Never turn `ENABLE_*` variables off to isolate one registry.** A disabled leg is skipped
  *silently*; forgetting to restore it makes a future release quietly incomplete. Accept a red run
  whose only real job succeeds instead.
- **Never print, log, echo or paste a registry token.** They are use-only env vars read at the
  point of use.

## Steps

### 1. Run the readiness gate

```sh
.claude/skills/release/scripts/check-release-readiness.sh <version>
```

It replicates `release.yml`'s `preflight` locally **and adds the check preflight does not have** —
that the versioned install coordinates in the docs match the release. It checks: six manifests in
lockstep (golang has none — it releases as a tag), docs install coordinates, a dated `## <version>`
changelog section with a fresh `## Unreleased` above it, clean tree on `main` in sync with origin,
the tag being free, all seven `ENABLE_*` gates, and **licensing**.

The licensing check covers what actually drifts: a LICENSE file in every port, `THIRD-PARTY-NOTICES.md`
present, the elixir Hex package still shipping LICENSE in its `files:` list, and **every declared
dependency appearing in the notices** — the real failure mode being a dependency added and never
listed. It matches names only across shipped configurations (test/dev deps are excluded: java
`testImplementation`, elixir `excoveralls`/`ex_doc`). It does **not** verify the license of each
pinned version — that needs a per-ecosystem resolver, and the notices file instead states the rule:
re-verify on add/remove/major-version change, because an upstream can relicense between releases
(`ModelContextProtocol` for C# is Apache-2.0 while the other MCP SDKs are MIT).

Fix every `✗` before continuing.

### 2. Choose the version honestly

Patch for pure fixes. **Minor when observable behavior moves**, even without an API break — a
consumer pinning `~0.13.0` should not silently inherit changed output. If in doubt, ask; it cannot
be undone.

### 3. Bump and roll, as one commit on its own branch

All six manifests to the same version, and `## Unreleased` → `## X.Y.Z — YYYY-MM-DD` with a fresh
empty `## Unreleased` above it. **Update the docs install coordinates in the same commit** —
`README.md`, `java/README.md`, `clojure/README.md`, `elixir/README.md`,
`clojure/examples/*/deps.edn`, `site/src/content/docs/install.mdx`, `quickstart.mdx`,
`api/clojure.mdx`.

Leave historical statements alone: "on Clojars since v0.13.0", "Relay ships in golang/ as of
0.12.0", and `docs/performance-benchmarks.md`'s "Versions measured". Rewriting those falsifies the
record.

### 3b. Know that the release gates on a LIVE model run

`release.yml` has a `live-scenarios` job — **golang only**, behind the `live` build tag — that runs
the harness scenarios against a real model on OpenRouter before **any** registry leg publishes
(every leg `needs: [preflight, live-scenarios]`). The other six ports prove parity hermetically in
CI; this proves the mechanisms actually work against a provider before anything ships.

It needs **`OPENROUTER_API_KEY` in the `prod` environment**. If it is missing the whole release
fails at the gate, before publishing — which is the correct failure, but check it is present before
cutting rather than discovering it mid-release. Run the same suite locally first:

```sh
cd golang && OPENROUTER_API_KEY=… go test -tags live -run TestLive -v ./...
```

The tag keeps these out of normal CI: `go test ./...` never sees them, and the tests skip cleanly
when the key is absent.

### 4. Open the PR and wait for all nine CI jobs

"Docs examples (seven ports)" legitimately takes **13–18 minutes**. It is not hung. Do not merge on
eight of nine.

### 5. Merge, then cut the Release

The GitHub Release **is** the trigger. Its body is the changelog section — extract it, never
hand-write a second account:

```sh
gh release create v<version> --title "v<version>" --notes-file <extracted-section> --target main
```

### 6. Verify every registry from outside

Do this even when the run is green. Do not report a release as done without it.

```sh
npm view toolnexus version
curl -s https://pypi.org/pypi/toolnexus/json | python3 -c 'import sys,json;print(json.load(sys.stdin)["info"]["version"])'
curl -s https://api.nuget.org/v3-flatcontainer/toolnexus/index.json | python3 -c 'import sys,json;print(json.load(sys.stdin)["versions"][-1])'
curl -s https://repo1.maven.org/maven2/io/github/muthuishere/toolnexus/ | grep -oE '0\.[0-9]+\.[0-9]+' | sort -uV | tail -1
curl -s https://clojars.org/api/artifacts/net.clojars.muthuishere/toolnexus | python3 -c 'import sys,json;print(json.load(sys.stdin)["latest_version"])'
curl -s https://hex.pm/api/packages/toolnexus | python3 -c 'import sys,json;print(json.load(sys.stdin)["releases"][0]["version"])'
git ls-remote --tags origin | grep golang/v<version>
```

**Indexing lag is normal and is not failure**: NuGet ~5 min, Maven Central ~15–20 min. Poll before
concluding anything. Clojars is `net.clojars.muthuishere`, **not** `io.github.muthuishere`.

### 7. If a leg failed

Diagnose first — `gh run view <id> --log-failed`. Credential failures (`API key revoked`,
`401`, `403`) are owner fixes; report them and **file an issue** rather than retrying blindly.

To re-publish one leg after the fix: `gh workflow run release.yml --ref main -f version=<version>`.
This re-runs everything. Expect the already-published legs to fail on duplicate rejection and the
run to go red — check that the leg you cared about says `success`, then verify from outside.

## Registry facts worth not re-deriving

| registry | auth | duplicate behavior |
|---|---|---|
| npm, PyPI, NuGet | OIDC Trusted Publishing, no stored secret | npm hard-fails; PyPI/NuGet skip |
| Maven Central | `CENTRAL_USERNAME`/`CENTRAL_PASSWORD` + GPG, `prod` env | rejects |
| Hex | `HEX_API_KEY`, `prod` env, gated by `ENABLE_ELIXIR` (not `ENABLE_HEX`); needs `api:write` | rejects |
| Clojars | `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` (a deploy token) | `403 non-snapshots` |
| Go | none — tag push `golang/vX.Y.Z` | idempotent, skips |

## Report honestly

State which registries are confirmed **from the registry**, which are still indexing, and which
failed and why. A partial release is a partial release; say so, and say what a consumer of the
lagging port gets in the meantime.
