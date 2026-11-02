#!/usr/bin/env bash
# Compile and execute every extracted Rust docs example.
#
# Hermetic: a scratch crate whose `toolnexus` dependency is a `path` to THIS repo's
# rust/ port, so the examples compile against local code and a renamed item fails
# the build. Each snippet is a complete program (`fn main`) and becomes one cargo
# example of that crate, so the dependency graph is compiled once for all of them.
# The port's Cargo.lock is reused, so versions match what the port is tested with.
#
#   RUST_SNIPPETS=<dir>   snippets to run (default site/tests/snippets/rust)
#   RUST_DOCS_WORK=<dir>  scratch crate (default site/tests/.work/rust)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="${RUST_SNIPPETS:-$REPO_ROOT/site/tests/snippets/rust}"
WORK="${RUST_DOCS_WORK:-$REPO_ROOT/site/tests/.work/rust}"

if [ ! -d "$SNIPPETS" ]; then
	echo "no rust snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

mkdir -p "$WORK"
# Keep target/ between runs (incremental), but never a stale example.
rm -rf "$WORK/examples" "$WORK/src"
mkdir -p "$WORK/examples" "$WORK/src"
: > "$WORK/src/lib.rs"

# The dependencies a snippet may use: the port itself plus what its own examples
# and tests use. Nothing else, so a snippet stays copy-pasteable into a new crate
# with the same [dependencies].
cat > "$WORK/Cargo.toml" <<EOF
[package]
name = "toolnexus-docs"
version = "0.0.0"
edition = "2024"
publish = false

[dependencies]
toolnexus = { path = "$REPO_ROOT/rust" }
tokio = { version = "1", features = ["full"] }
tokio-util = "0.7"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
futures-util = "0.3"

[workspace]
EOF
[ -f "$WORK/Cargo.lock" ] || cp "$REPO_ROOT/rust/Cargo.lock" "$WORK/Cargo.lock"

names=()
for f in "$SNIPPETS"/*.rs; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .rs)"
	cp "$f" "$WORK/examples/$name.rs"
	names+=("$name")
done
if [ ${#names[@]} -eq 0 ]; then
	echo "Rust: 0 snippets"
	exit 0
fi

# One build for every example; --keep-going so one broken snippet does not hide
# the others. Per-snippet errors are attributed from the log below.
LOG="$WORK/build.log"
(cd "$WORK" && cargo build --quiet --examples --keep-going --message-format short) >"$LOG" 2>&1 || true

pass=0
fail=0
failed_names=()
for name in "${names[@]}"; do
	bin="${CARGO_TARGET_DIR:-$WORK/target}/debug/examples/$name"
	if [ ! -x "$bin" ] || grep -q "examples/$name.rs:.*error" "$LOG"; then
		fail=$((fail + 1))
		failed_names+=("$name")
		printf '  \033[31mFAIL\033[0m %s (does not compile)\n' "$name"
		grep "examples/$name.rs:" "$LOG" | grep -i 'error' | sed 's/^/        /' | head -10
		continue
	fi
	if out=$(cd "$WORK" && TOOLNEXUS_REPO="$REPO_ROOT" timeout 120 "$bin" 2>&1); then
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
echo "Rust: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
