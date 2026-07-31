#!/usr/bin/env bash
# Compile and execute every extracted Python docs example.
#
# Hermetic: no network beyond the one editable install, no live LLM. The snippets
# import `toolnexus`, installed from THIS repo's python/ port, so a renamed export
# fails the run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/python"
VENV="$REPO_ROOT/site/tests/.work/python-venv"

if [ ! -d "$SNIPPETS" ]; then
	echo "no python snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

# Build the venv once and reuse it; re-running the suite should be fast.
if [ ! -x "$VENV/bin/python" ]; then
	echo "  (creating venv + installing python/ …)"
	python3 -m venv "$VENV"
	"$VENV/bin/pip" install -q --upgrade pip >/dev/null
	"$VENV/bin/pip" install -q -e "$REPO_ROOT/python" >/dev/null
fi

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.py; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .py)"
	if out=$("$VENV/bin/python" "$f" 2>&1); then
		pass=$((pass + 1))
		printf '  \033[32mPASS\033[0m %-40s %s\n' "$name" "$(echo "$out" | tail -1)"
	else
		fail=$((fail + 1))
		failed_names+=("$name")
		printf '  \033[31mFAIL\033[0m %s\n' "$name"
		echo "$out" | sed 's/^/        /' | tail -15
	fi
done

echo ""
echo "Python: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
