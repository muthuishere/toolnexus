#!/usr/bin/env bash
# Compile + run the Java probe against the already-built library classes.
# Prereq (once): cd java && ./gradlew build --no-daemon
set -u
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../../../.."
classes="$root/java/build/classes/java/main"
[ -d "$classes" ] || { echo "java: SKIPPED (run \`cd java && ./gradlew build\` first)"; exit 0; }
cp="$classes:$(find "$HOME/.gradle/caches/modules-2" -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | tr '\n' ':')"
out="$(mktemp -d)"
javac -nowarn -cp "$cp" -d "$out" "$here/Probe.java" || { echo "java: PROBE DID NOT COMPILE"; exit 0; }
java -cp "$out:$cp" Probe
