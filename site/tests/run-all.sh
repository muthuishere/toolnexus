#!/usr/bin/env bash
# Extract every `test`-tagged example out of the API Reference pages and run it in
# all seven ports. Hermetic — no network, no live LLM.
#
# The clojure runner is the odd one out on purpose: it runs each example FOUR ways
# (JVM main + JVM repl + cljgo run + cljgo AOT), because that port's claim is that
# one source tree behaves identically on two hosts, and a single-mode runner cannot
# see that claim break.
#
#   bash site/tests/run-all.sh              # all seven
#   bash site/tests/run-all.sh javascript   # one port
#
# A docs example that no longer compiles or no longer produces its asserted result
# fails here, which is the point: the reference cannot drift from the code.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ONLY="${1:-}"

echo "==> extracting snippets"
node "$REPO_ROOT/site/scripts/extract-snippets.mjs"
echo ""

declare -a LANGS=(javascript python golang java csharp elixir clojure)
declare -a FAILED=()

for lang in "${LANGS[@]}"; do
	if [ -n "$ONLY" ] && [ "$ONLY" != "$lang" ]; then continue; fi
	echo "==> $lang"
	if bash "$REPO_ROOT/site/tests/runners/$lang.sh"; then
		:
	else
		FAILED+=("$lang")
	fi
	echo ""
done

if [ ${#FAILED[@]} -gt 0 ]; then
	echo "SUITE FAILED in: ${FAILED[*]}"
	exit 1
fi

echo "SUITE PASSED — every documented example compiles and runs."
