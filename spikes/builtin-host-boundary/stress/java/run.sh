#!/usr/bin/env bash
# Stress harness for the SHIPPED java builtins. One command: bash run.sh
# Builds the java port's main classes with gradle, then compiles+runs the
# harness AGAINST THOSE CLASSES (not a copy) with javac/java.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
classes="$repo/java/build/classes/java/main"

"$repo/java/gradlew" -p "$repo/java" -q compileJava
[ -d "$classes" ] || { echo "no compiled classes at $classes" >&2; exit 1; }

mkdir -p "$here/out"
javac -d "$here/out" -cp "$classes" "$here/Harness.java"
exec java -cp "$here/out:$classes" Harness "$@"
