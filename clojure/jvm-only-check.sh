#!/usr/bin/env bash
# jvm-only-check — does this port work for someone who has ONLY Clojure?
#
#   ./jvm-only-check.sh        exit 0 = no cljgo needed on the JVM path
#
# A dual-host library has an obvious failure mode: it quietly requires BOTH
# hosts. Someone with a plain JDK and the Clojure CLI, who has never heard of
# cljgo, must be able to use this like any other Clojure library — otherwise
# "runs on Clojure and cljgo" really means "needs cljgo".
#
# HOW IT PROVES IT, rather than asserting it. Two things at once:
#
#   1. A COPY of the source with every cljgo artifact deleted — no build.cljgo,
#      no build.lock.edn, no compiled binary, no .cpcache. Only src/ and
#      deps.edn, which is what a consumer gets from a git clone or a jar.
#
#   2. A POISONED `cljgo` FIRST ON PATH: a script that prints POISON and exits
#      127 if anything invokes it. Removing cljgo from PATH is not enough here,
#      because on this machine it lives in the same directory as `clojure`
#      itself — and "I did not call it" is not the same as "it was not called".
#      The poison turns a silent dependency into a loud failure.
#
# If the suite passes with the poison in place and no POISON line appears, then
# nothing on the JVM path shells out to cljgo, and nothing needs its artifacts.
#
# MEASURED 2026-08-01, koine 0.8.2: 155 tests / 708 assertions, 0 failures, no
# POISON line. The example (examples/minimal) also runs and passes this way,
# with app/main.cljg sitting in the same directory being correctly ignored —
# the JVM never looks for that extension.
#
# The reverse direction is NOT this script's job: a cljgo-only user needs no
# JDK, which all-modes-check.sh covers by running the AOT binary and both cljgo
# evaluators.
set -uo pipefail
cd "$(dirname "$0")"

SRC=$(pwd)
REPO_ROOT=$(cd .. && pwd)
export TN_EXAMPLES="$REPO_ROOT/examples"

WORK=$(mktemp -d)
BIN="$WORK/poison-bin"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$BIN"

cat > "$BIN/cljgo" <<'POISON'
#!/bin/sh
echo "POISON: something invoked cljgo — args: $*" >&2
exit 127
POISON
chmod +x "$BIN/cljgo"

# The consumer's view: sources and deps.edn, nothing cljgo ever produced.
cp -r "$SRC/src" "$SRC/deps.edn" "$WORK/"
rm -rf "$WORK/.cpcache" "$WORK/build.lock.edn" "$WORK/build.cljgo"

fail=0

# Sanity: the poison must actually fire when cljgo IS called. A poison that
# never triggers would make this whole check vacuous.
#
# Captured to a variable first, deliberately. Piping straight into grep under
# `set -o pipefail` reports the POISON's own exit 127 as the pipeline status, so
# the check failed while the poison was working perfectly — the sanity check was
# testing the exit code when it meant to test the match. It caught itself, which
# is the argument for having it.
poison_out=$( (cd "$WORK" && PATH="$BIN:$PATH" cljgo version) 2>&1 || true )
if ! printf '%s' "$poison_out" | grep -q POISON; then
  echo "FAIL: the poison did not fire — this check would prove nothing" >&2
  printf '{"check":"jvm-only","gate":"FAILED: poison inert"}\n'
  exit 2
fi

out=$(cd "$WORK" && PATH="$BIN:$PATH" clojure -M -m toolnexus.test-main 2>&1)
line=$(printf '%s' "$out" | grep -E '^\{"assertions"' | tail -1)

if printf '%s' "$out" | grep -q POISON; then
  echo "FAIL: the JVM path invoked cljgo:" >&2
  printf '%s\n' "$out" | grep POISON >&2
  fail=1
fi

if [ -z "$line" ]; then
  echo "FAIL: no verdict line — the suite did not run without cljgo" >&2
  printf '%s\n' "$out" | tail -20 >&2
  fail=1
else
  case "$line" in
    *'"gate":"OK"'*) echo "  ok   JVM-only, cljgo poisoned  $line" >&2 ;;
    *)               echo "  FAIL JVM-only  $line" >&2; fail=1 ;;
  esac
fi

printf '{"check":"jvm-only","gate":"%s","summary":%s}\n' \
  "$([ $fail -eq 0 ] && echo OK || echo FAILED)" "${line:-null}"
exit $fail
