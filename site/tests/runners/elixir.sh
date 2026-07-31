#!/usr/bin/env bash
# Compile and execute every extracted Elixir docs example.
#
# Hermetic: a scratch mix project with a `path:` dependency on THIS repo's elixir/
# port, so the examples run against local code and a renamed function fails the run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/elixir"
WORK="$REPO_ROOT/site/tests/.work/elixir"

if [ ! -d "$SNIPPETS" ]; then
	echo "no elixir snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

mkdir -p "$WORK"
cat > "$WORK/mix.exs" <<EOF
defmodule ToolnexusDocs.MixProject do
  use Mix.Project

  def project do
    [app: :toolnexus_docs, version: "0.0.1", elixir: "~> 1.16", deps: deps()]
  end

  def application, do: [extra_applications: [:logger]]

  defp deps do
    [{:toolnexus, path: "$REPO_ROOT/elixir"}]
  end
end
EOF

# Resolve + compile the dependency once.
(cd "$WORK" && MIX_ENV=dev mix deps.get >/dev/null 2>&1 && MIX_ENV=dev mix compile >/dev/null 2>&1) || {
	echo "FAIL: could not compile the scratch mix project" >&2
	(cd "$WORK" && mix compile 2>&1 | tail -20) >&2
	exit 1
}

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.exs; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .exs)"
	if out=$(cd "$WORK" && mix run --no-start "$f" 2>&1); then
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
echo "Elixir: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
