#!/usr/bin/env bash
# Compile and execute every extracted Go docs example.
#
# Hermetic: a scratch module with a `replace` directive pointing at THIS repo's
# golang/ port, so the examples compile against local code and a renamed export
# fails the build. Each snippet is its own `package main` in its own directory.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/golang"
WORK="$REPO_ROOT/site/tests/.work/golang"

if [ ! -d "$SNIPPETS" ]; then
	echo "no golang snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

rm -rf "$WORK"
mkdir -p "$WORK"

# A scratch module that resolves `toolnexus` to the local port.
cat > "$WORK/go.mod" <<EOF
module toolnexusdocs

go 1.23.0

require github.com/muthuishere/toolnexus/golang v0.0.0

replace github.com/muthuishere/toolnexus/golang => $REPO_ROOT/golang
EOF

# Reuse the port's resolved dependency versions rather than hitting the network.
if [ -f "$REPO_ROOT/golang/go.sum" ]; then
	cp "$REPO_ROOT/golang/go.sum" "$WORK/go.sum"
fi

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.go; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .go)"
	mkdir -p "$WORK/$name"
	cp "$f" "$WORK/$name/main.go"
done

# One tidy for the whole module, offline where possible.
(cd "$WORK" && GOFLAGS=-mod=mod go mod tidy >/dev/null 2>&1) || true

for f in "$SNIPPETS"/*.go; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .go)"
	if out=$(cd "$WORK" && TOOLNEXUS_REPO="$REPO_ROOT" go run "./$name" 2>&1); then
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
echo "Go: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
