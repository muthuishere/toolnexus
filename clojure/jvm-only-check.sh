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
#   2. A SANDBOX plus a POISON, which are different guarantees and it takes both.
#
#      The sandbox is koine's method and it is stricter than what this script
#      started with: `env -i` so NOTHING is inherited, and a PATH built to hold
#      only what a JVM Clojure user actually has. Then it ASSERTS the absence of
#      both `cljgo` AND `go` before running anything — koine's first attempt at
#      this missed a cljgo sitting in /opt/homebrew/bin, and so did mine, which
#      is why absence is now verified rather than arranged.
#
#      `go` matters as much as `cljgo`: cljgo emits Go and shells out to the Go
#      toolchain, so a machine with Go present could mask a dependency that a
#      plain JVM user would hit.
#
#      The poison stays because absence and non-invocation are different claims.
#      A poisoned `cljgo` first on PATH turns "nothing found it" into "nothing
#      even tried" — and on this machine cljgo sits in the SAME DIRECTORY as
#      `clojure`, so it cannot be removed by stripping a path entry.
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

# A PATH holding only what a JVM Clojure user has — and it must be built from
# INDIVIDUAL BINARIES, not their directories. Putting `dirname $(command -v
# clojure)` on PATH leaks whatever else lives there: on this machine that is
# /opt/homebrew/bin, which holds BOTH cljgo and go. The absence check below
# caught exactly that, which is the argument for verifying rather than
# arranging.
# clojure + java: the JVM user's runtime, which is the thing under test.
# node + npx: NOT part of that, and included deliberately rather than quietly.
# The MCP stdio tests launch a REAL server (@modelcontextprotocol/server-everything)
# and an MCP stdio server needs its own runtime the same way a Postgres test
# needs Postgres. Excluding node does not test "JVM without cljgo", it tests
# "JVM without the fixture", and the two stdio tests correctly FAILED —
# "stdio server did not start — this test measured NOTHING" — rather than
# skipping quietly. That guard doing its job in a brand-new environment is why
# they are added here instead of the assertion being softened.
for tool in clojure clj java node npx; do
  src=$(command -v "$tool" 2>/dev/null) && ln -sf "$src" "$BIN/$tool"
done
SANDBOX_PATH="$BIN:/usr/bin:/bin"

# ABSENCE IS VERIFIED, NEVER ASSUMED. koine's first attempt at this missed a
# cljgo in /opt/homebrew/bin, and so did an earlier version of this script —
# `cljgo` shares a directory with `clojure`, so it survives naive PATH edits.
# The poison is expected here; a REAL cljgo is a failed sandbox.
probe=$(env -i PATH="$SANDBOX_PATH" HOME="$HOME" sh -c 'command -v cljgo; echo "--"; command -v go' 2>/dev/null)
real_cljgo=$(printf '%s' "$probe" | sed -n '1p')
found_go=$(printf '%s' "$probe" | sed -n '3p')
if [ "$real_cljgo" != "$BIN/cljgo" ]; then
  echo "FAIL: sandbox leaked a real cljgo at: ${real_cljgo:-<none>}" >&2
  printf '{"check":"jvm-only","gate":"FAILED: sandbox leaked cljgo"}\n'
  exit 2
fi
if [ -n "$found_go" ]; then
  echo "FAIL: sandbox leaked the Go toolchain at: $found_go" >&2
  echo "      cljgo emits Go and shells out to it, so Go being present could" >&2
  echo "      mask a dependency a plain JVM user would hit." >&2
  printf '{"check":"jvm-only","gate":"FAILED: sandbox leaked go"}\n'
  exit 2
fi
echo "  ok   sandbox: cljgo ABSENT (poison only), go ABSENT" >&2

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

out=$(cd "$WORK" && env -i PATH="$SANDBOX_PATH" HOME="$HOME" TN_EXAMPLES="$TN_EXAMPLES" \
        clojure -M -m toolnexus.test-main 2>&1)
line=$(printf '%s' "$out" | grep -E '^\{"assertions"' | tail -1)

if printf '%s' "$out" | grep -q POISON; then
  echo "FAIL: the JVM path invoked cljgo:" >&2
  printf '%s\n' "$out" | grep POISON >&2
  fail=1
fi

if [ -z "$line" ]; then
  printf '%s\n' "$out" | grep -B2 -A8 -E "FAIL in|ERROR in" | head -24 >&2
  echo "FAIL: no verdict line — the suite did not run without cljgo" >&2
  printf '%s\n' "$out" | tail -20 >&2
  fail=1
else
  case "$line" in
    *'"gate":"OK"'*) echo "  ok   JVM-only, cljgo poisoned  $line" >&2 ;;
    *)               echo "  FAIL JVM-only  $line" >&2
                     printf '%s\n' "$out" | grep -B1 -A6 -E "FAIL in|ERROR in" | head -24 >&2
                     fail=1 ;;
  esac
fi

printf '{"check":"jvm-only","gate":"%s","summary":%s}\n' \
  "$([ $fail -eq 0 ] && echo OK || echo FAILED)" "${line:-null}"
exit $fail
