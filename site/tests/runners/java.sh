#!/usr/bin/env bash
# Compile and execute every extracted Java docs example.
#
# Hermetic: runs against THIS repo's java/ port via Gradle's resolved runtime
# classpath, so a renamed method fails compilation. Each snippet declares
# `public class Example` with a main method and is launched in Java's
# single-file source mode (no separate javac step).
#
# Requires: cd java && ./gradlew jar --no-daemon

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SNIPPETS="$REPO_ROOT/site/tests/snippets/java"
WORK="$REPO_ROOT/site/tests/.work/java"
CP_FILE="$WORK/classpath.txt"

if [ ! -d "$SNIPPETS" ]; then
	echo "no java snippets — run: node site/scripts/extract-snippets.mjs"
	exit 0
fi

mkdir -p "$WORK"

# Resolve the runtime classpath once, via a throwaway init script.
if [ ! -s "$CP_FILE" ]; then
	echo "  (resolving gradle runtime classpath …)"
	cat > "$WORK/cp-init.gradle" <<'EOF'
allprojects {
  tasks.register("printRuntimeCp") {
    doLast { println project.sourceSets.main.runtimeClasspath.asPath }
  }
}
EOF
	(cd "$REPO_ROOT/java" && ./gradlew -q --no-daemon -I "$WORK/cp-init.gradle" printRuntimeCp 2>/dev/null | tail -1) > "$CP_FILE"
fi

CP="$(cat "$CP_FILE")"
if [ -z "$CP" ]; then
	echo "FAIL: could not resolve the java classpath — run: cd java && ./gradlew jar --no-daemon" >&2
	exit 1
fi

pass=0
fail=0
failed_names=()

for f in "$SNIPPETS"/*.java; do
	[ -e "$f" ] || continue
	name="$(basename "$f" .java)"
	rundir="$WORK/$name"
	rm -rf "$rundir"
	mkdir -p "$rundir"
	# Single-file source mode needs the filename to match the public class.
	cp "$f" "$rundir/Example.java"
	if out=$(cd "$REPO_ROOT" && java -cp "$CP" "$rundir/Example.java" 2>&1); then
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
echo "Java: $pass passed, $fail failed"
[ "$fail" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
