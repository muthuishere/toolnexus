#!/usr/bin/env bash
# Release readiness gate for toolnexus. Read-only: it never publishes, tags, or edits.
#
# Replicates release.yml's `preflight` manifest check LOCALLY, and adds the check
# preflight does NOT have — that the versioned install coordinates in the docs match
# the version being released. That gap shipped 0.14.0 with Java/Clojure install
# snippets two minor versions behind.
#
# Usage:  ./check-release-readiness.sh 0.15.0
# Exit:   0 = ready, 1 = blocked (reasons printed)

set -uo pipefail
V="${1:-}"
[ -z "$V" ] && { echo "usage: $0 <version>   e.g. $0 0.15.0"; exit 2; }
cd "$(git rev-parse --show-toplevel)" || exit 2

fail=0
note() { printf '  %s\n' "$1"; }
bad()  { printf '  ✗ %s\n' "$1"; fail=1; }
ok()   { printf '  ✓ %s\n' "$1"; }

echo "== 1. Manifest parity (same check release.yml preflight runs) =="
JS=$(node -p "require('./js/package.json').version" 2>/dev/null)
PY=$(grep -E '^version' python/pyproject.toml | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
CS=$(grep -oE '<Version>[^<]+</Version>' csharp/src/Toolnexus/Toolnexus.csproj | head -1 | sed -E 's/<\/?Version>//g')
JAVA=$(grep -E "^version" java/build.gradle | head -1 | sed -E "s/.*'([^']+)'.*/\1/")
EX=$(grep -E '@version "' elixir/mix.exs | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
CLJ=$(grep -E '^\(def version ' clojure/build.clj | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
for pair in "js:$JS" "python:$PY" "csharp:$CS" "java:$JAVA" "elixir:$EX" "clojure:$CLJ"; do
  n="${pair%%:*}"; v="${pair#*:}"
  [ "$v" = "$V" ] && ok "$n = $v" || bad "$n = ${v:-<unreadable>}, expected $V"
done
note "(golang has no manifest — it releases as the tag golang/v$V)"

echo
echo "== 2. Docs install coordinates (preflight does NOT check these) =="
# Exact coordinate forms only — a broad version grep matches unrelated things like
# serverInfo `version: "0.1.0"` defaults. Historical prose ("since v0.13.0",
# "as of 0.12.0", "Versions measured") is intentionally NOT checked: rewriting it
# would falsify the record.
MINOR="${V%.*}"                      # 0.14.0 -> 0.14, for the elixir `~>` form
DOCS=$(git ls-files '*.md' '*.mdx' '*.edn' | grep -vE "^(CHANGELOG|docs/adr/|docs/spikes/|openspec/)")
stale=""
for f in $DOCS; do
  # a) elixir:   {:toolnexus, "~> 0.14"}
  while IFS= read -r hit; do
    echo "$hit" | grep -q '<version>' && continue      # PUBLISHING.md templates
    echo "$hit" | grep -q "~> $MINOR\"" || stale="$stale\n  $f: $hit"
  done < <(grep -n '{:toolnexus, "~>' "$f" 2>/dev/null)
  # b) maven:    io.github.muthuishere:toolnexus:0.14.0
  while IFS= read -r hit; do
    echo "$hit" | grep -q ":toolnexus:$V" || stale="$stale\n  $f: $hit"
  done < <(grep -n 'io\.github\.muthuishere:toolnexus:[0-9]' "$f" 2>/dev/null)
  # c) clojars:  net.clojars.muthuishere/toolnexus {:mvn/version "0.14.0"}
  while IFS= read -r hit; do
    echo "$hit" | grep -q '"<version>"' && continue    # PUBLISHING.md templates
    echo "$hit" | grep -q "\"$V\"" || stale="$stale\n  $f: $hit"
  done < <(grep -n 'muthuishere/toolnexus {:mvn/version' "$f" 2>/dev/null)
  # d) maven xml: <version>..</version> in a file that installs the java artifact
  if grep -q 'artifactId>toolnexus<' "$f" 2>/dev/null; then
    while IFS= read -r hit; do
      echo "$hit" | grep -q ">$V<" || stale="$stale\n  $f: $hit"
    done < <(grep -n '<version>[0-9]' "$f" 2>/dev/null)
  fi
done
if [ -n "$stale" ]; then
  bad "install coordinates not at $V:"; printf "$stale\n" | sed 's/^/    /'
else
  ok "every live install coordinate reads $V (maven, clojars, elixir ~> $MINOR)"
fi

echo
echo "== 3. CHANGELOG =="
if grep -qE "^## $V — [0-9]{4}-[0-9]{2}-[0-9]{2}$" CHANGELOG.md; then
  ok "has a dated '## $V' section"
else
  bad "no '## $V — YYYY-MM-DD' section (roll '## Unreleased' down and add a fresh empty one above)"
fi
grep -qE "^## Unreleased" CHANGELOG.md && ok "'## Unreleased' still present for the next cycle" \
  || bad "no '## Unreleased' heading left at the top"

echo
echo "== 4. Git state =="
BR=$(git rev-parse --abbrev-ref HEAD)
[ -z "$(git status --porcelain --untracked-files=no)" ] && ok "working tree clean" || bad "uncommitted changes — commit or stash before releasing"
if [ "$BR" = "main" ]; then
  git fetch -q origin main 2>/dev/null
  [ "$(git rev-list --count origin/main..HEAD)" = "0" ] && ok "on main, in sync with origin" \
    || bad "on main but ahead of origin — push first; never release unmerged code"
else
  bad "on '$BR', not main — the release must be cut from merged code"
fi
git ls-remote --tags origin 2>/dev/null | grep -q "refs/tags/v$V$" \
  && bad "tag v$V ALREADY EXISTS — a version can never be reused on any registry" \
  || ok "tag v$V is free"

echo
echo "== 5. Publish gates (a leg that is off is skipped SILENTLY) =="
if command -v gh >/dev/null 2>&1; then
  for v in ENABLE_NPM ENABLE_PYPI ENABLE_GO ENABLE_NUGET ENABLE_JAVA ENABLE_ELIXIR ENABLE_CLOJARS; do
    val=$(gh variable list 2>/dev/null | awk -v k="$v" '$1==k{print $2}')
    [ "$val" = "true" ] && ok "$v=true" || note "  ! $v=${val:-unset} — this registry will NOT publish"
  done
else
  note "gh not available — check the ENABLE_* variables by hand"
fi

echo
if [ "$fail" = "0" ]; then
  echo "READY for $V. Cutting the release is still an owner decision."
else
  echo "BLOCKED — fix the ✗ items above."
fi
exit $fail
