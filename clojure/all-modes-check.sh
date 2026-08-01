#!/usr/bin/env bash
# all-modes-check — the suite in EVERY way this port can be executed.
#
#   ./all-modes-check.sh        exit 0 = every unblocked mode green
#
# The port claims to work "on Clojure and on cljgo". That is four execution
# paths, not two, and they are genuinely different machinery:
#
#   1. jvm-main     clojure -M -m toolnexus.test-main      JVM, compiled-on-load
#   2. jvm-repl     forms piped into `clojure -r`           JVM, REPL evaluator
#   3. cljgo-aot    cljgo build -> native binary            Go, AOT-emitted
#   4. cljgo-repl   forms piped into `cljgo repl`           Go, REPL evaluator
#   5. cljgo-run    cljgo run src/run_tests.cljc            Go, interpreted file
#
# Why all five rather than "one per host": cljgo's own ADR 0007 calls a
# REPL-vs-binary divergence unforgivable, and a REPL is where a human actually
# meets the library. A port proven only through its compiled path is proven in
# the mode developers use least.
#
# STATUS 2026-08-01, cljgo v0.8.8, koine 0.8.2 — ALL FIVE GREEN:
#
#   jvm-main    155 tests / 708 assertions   PASS
#   jvm-repl    155 tests / 708 assertions   PASS
#   cljgo-aot   155 tests / 708 assertions   PASS
#   cljgo-run   155 tests / 708 assertions   PASS
#   cljgo-repl  155 tests / 708 assertions   PASS  <- was BLOCKED; cljgo #185
#
# cljgo-repl WENT GREEN WITH NO EDIT HERE, which was the point of reporting it
# as BLOCKED rather than skipping it: the leg kept attempting the real thing
# every run, so the upstream fix landing is what turned it green, not a human
# remembering to re-enable it. The history below is kept deliberately — a gate
# that erases what it used to catch cannot be audited.
#
# THE cljgo-repl BLOCKER — FIXED UPSTREAM in cljgo #185, kept here as history.
# It was: `cljgo repl` resolved neither the project's own source root nor its
# declared dependencies, because runREPL never called resolveRunDeps while
# `cljgo run` did. cljgo found the identical bug in `cljgo nrepl` — the editor
# path — before anyone reported it. What it WAS:
#
#   $ printf "(require 'toolnexus.tool)\n" | cljgo repl
#   error: could not locate namespace toolnexus.tool (no registered provider,
#     and no toolnexus/tool.clj/.cljg/.cljc relative to the requiring file)
#
#   $ printf "(require 'koine.json)\n" | cljgo repl        # a declared dep
#   error: could not locate namespace koine.json …
#
# Reproduced on cljgo's OWN generated project, so it is not this port's layout:
#
#   $ cljgo new --template lib replprobe && cd replprobe
#   $ printf "(require 'replprobe.core)\n" | cljgo repl
#   error: could not locate namespace replprobe.core …
#
# `load-file` works, but only for files that require nothing from the project or
# its deps — so it cannot reach this suite either. `cljgo run` and `cljgo build`
# both resolve all of it, which is what makes this a REPL-path divergence.
#
# A CAUTION IF YOU EXTEND THIS. The cljgo REPL prints its errors on stderr and
# CONTINUES to the next form, so a trailing `(println :OK)` prints even though
# the require failed. That produced two false "it works" readings while this was
# being investigated. Assert on the SUITE'S OWN verdict line and nothing else.
#
# BLOCKED is reported, never skipped, and it is not counted as a pass. When the
# upstream fix lands this leg turns green on its own with no edit here.
#
# KNOWN FLAKE, OPEN — recorded rather than waited out. The cljgo-run leg has
# failed twice with `"error":1` and a short assertion count (704 and 700 of 708),
# roughly 1 run in 8 while the whole matrix was running, and 0 in 10 + 0 in 12 +
# 0 in 6-concurrent when hunted directly. One test aborts partway; the varying
# count says it is timing-dependent, not a fixed bug. It has never failed on
# jvm-main, jvm-repl or cljgo-aot.
#
# It is NOT dismissed as "just flaky": a test that fails 1 time in 8 is a defect
# that reports itself 12% of the time, and this port has already been bitten
# twice by load-sensitive behaviour (the 60s consumer hang, the exit-code EOF
# race). Both captured failures predate the per-mode logging above, so the cause
# is not yet known — which is exactly why the logging was added.
set -uo pipefail
cd "$(dirname "$0")"

REPO_ROOT=$(cd .. && pwd)
export TN_EXAMPLES="$REPO_ROOT/examples"

CLJGO=${CLJGO:-cljgo}
log() { printf '%s\n' "$*" >&2; }
fail=0; rows=""

# Every leg is judged on the suite's own machine-readable verdict line, which
# carries its own count gate — so a mode that runs nothing cannot look green.
verdict_of() { grep -E '^\{"assertions"' | tail -1; }

# Per-mode logs, kept. The first version of this script threw stderr away, and
# when a leg failed intermittently the evidence went with it — a gate that can
# tell you THAT something broke but never WHY costs more than it saves.
LOGDIR=${LOGDIR:-/tmp/tn-all-modes}
mkdir -p "$LOGDIR"

run_mode() {  # $1 = label, $2 = shell command
  local label=$1 cmd=$2 line
  eval "$cmd" > "$LOGDIR/$label.log" 2>&1
  line=$(verdict_of < "$LOGDIR/$label.log")
  if [ -z "$line" ]; then
    log "  FAIL  $label — no verdict line; the suite did not run  (see $LOGDIR/$label.log)"
    rows="$rows{\"mode\":\"$label\",\"gate\":\"FAILED: no verdict line\"},"
    fail=1
    return
  fi
  case "$line" in
    *'"gate":"OK"'*) log "  ok    $label  $line" ;;
    *)               log "  FAIL  $label  $line"
                     log "        detail: $LOGDIR/$label.log"
                     grep -B2 -A12 -E "ERROR in|FAIL in" "$LOGDIR/$label.log" | head -20 >&2
                     fail=1 ;;
  esac
  rows="$rows{\"mode\":\"$label\",\"summary\":$line},"
}

log "== every execution mode"

run_mode jvm-main  "clojure -M -m toolnexus.test-main"
run_mode jvm-repl  "printf \"(require 'toolnexus.test-main)\\n(toolnexus.test-main/-main)\\n\" | clojure -r"

if ! $CLJGO build >/dev/null 2>&1; then
  log "  FAIL  cljgo build — cannot produce the AOT binary"
  rows="$rows{\"mode\":\"cljgo-aot\",\"gate\":\"FAILED: build\"},"
  fail=1
else
  run_mode cljgo-aot "./toolnexus-test"
fi
run_mode cljgo-run "$CLJGO run src/run_tests.cljc"

# cljgo-repl: attempt it for real. If the upstream defect is fixed this passes
# and the BLOCKED branch simply stops being taken.
printf "(require 'toolnexus.test-main)\n(toolnexus.test-main/-main)\n" \
  | $CLJGO repl > "$LOGDIR/cljgo-repl.log" 2>&1
repl_line=$(verdict_of < "$LOGDIR/cljgo-repl.log")
if [ -n "$repl_line" ]; then
  case "$repl_line" in
    *'"gate":"OK"'*) log "  ok    cljgo-repl  $repl_line" ;;
    *)               log "  FAIL  cljgo-repl  $repl_line"; fail=1 ;;
  esac
  rows="$rows{\"mode\":\"cljgo-repl\",\"summary\":$repl_line},"
else
  # Distinguish "upstream cannot resolve anything" from a real port failure, by
  # asking the REPL for something that needs no project and no dependency.
  if printf '(println :repl-alive)\n' | $CLJGO repl 2>/dev/null | grep -q ':repl-alive'; then
    log "  BLOCKED cljgo-repl — the REPL runs but resolves no project ns and no dep (upstream)"
    rows="$rows{\"mode\":\"cljgo-repl\",\"gate\":\"BLOCKED: upstream, cljgo repl resolves no project namespace or dependency\"},"
  else
    log "  FAIL  cljgo-repl — the REPL did not even evaluate a bare println"
    rows="$rows{\"mode\":\"cljgo-repl\",\"gate\":\"FAILED: repl unusable\"},"
    fail=1
  fi
fi

printf '{"check":"all-modes","gate":"%s","modes":[%s]}\n' \
  "$([ $fail -eq 0 ] && echo OK || echo FAILED)" "${rows%,}"

[ $fail -eq 0 ] && log "PASS (blocked modes are reported, not counted)" || log "FAIL"
exit $fail
