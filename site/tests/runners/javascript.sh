#!/usr/bin/env bash
# Compile and execute every extracted JavaScript docs example.
#
# Hermetic: no network, no live LLM. The snippets import "toolnexus", which is
# resolved by symlinking the local js/ port into a scratch node_modules — so the
# examples run against THIS repo's code, and a renamed export breaks the build.
#
# Requires: cd js && npm install && npm run build   (produces js/dist)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/javascript"
WORK="$REPO_ROOT/site/tests/.work/javascript"

if [ ! -d "$SNIPPETS" ]; then
	echo "no javascript snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

if [ ! -f "$REPO_ROOT/js/dist/index.js" ]; then
	echo "FAIL: js/dist missing — run: cd js && npm install && npm run build" >&2
	exit 1
fi

rm -rf "$WORK"
mkdir -p "$WORK/node_modules"
ln -s "$REPO_ROOT/js" "$WORK/node_modules/toolnexus"
# Type-only imports need the package's own types; module resolution follows the symlink.
echo '{ "name": "docs-examples", "type": "module", "private": true }' > "$WORK/package.json"

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.ts; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .ts)"
	cp "$f" "$WORK/$name.ts"
	if out=$(cd "$WORK" && node --experimental-strip-types "$name.ts" 2>&1); then
		pass=$((pass + 1))
		printf '  \033[32mPASS\033[0m %-40s %s\n' "$name" "$(echo "$out" | tail -1)"
	else
		fail=$((fail + 1))
		failed_names+=("$name")
		printf '  \033[31mFAIL\033[0m %s\n' "$name"
		echo "$out" | sed 's/^/        /' | head -15
	fi
done

echo ""
echo "JavaScript: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
