#!/usr/bin/env bash
# cljgo-gate — the toolnexus Clojure port as a DOWNSTREAM GATE for cljgo CI.
#
#   ./cljgo-gate.sh          exit 0 = pass, non-zero = fail
#
# One command; the exit code is the verdict; one line of JSON on stdout is the
# summary. Everything human-readable goes to stderr, so a CI step can do
# `./cljgo-gate.sh > summary.json` and still show the log.
#
# WHY THIS EXISTS. cljgo's own suite structurally cannot reach this: cljgo's
# sources are .cljg and its process tests are Go-level, so nothing in it
# exercises a .cljc consumer hammering subprocess pipes, HTTP and parallel
# futures on both hosts. Two silent-truncation bugs (v0.8.4) were caught only by
# CI runners happening to be slower than a laptop. This is a different KIND of
# coverage, not more of the same.
#
# TWO LEGS, BOTH BLOCKING: the cljgo AOT binary and cljgo interpreted. The JVM
# leg is toolnexus' business and runs in toolnexus CI, not here — but the two
# cljgo legs must AGREE, because REPL-vs-binary divergence is cljgo's own
# release blocker (its ADR 0007) and is the failure this gate is best placed to
# catch.
#
# ZERO COLLECTED TESTS IS A FAILURE, LOUDLY. `Ran 0 tests, 0 failures` exits 0
# and looks green forever; cljgo shipped v0.8.2 partly because that had been
# green for weeks. The suite carries its own count gate (a floor, not an exact
# count) and this script refuses a leg that produces no verdict line at all.
#
# PROVEN TO FAIL, 2026-08-01 — a gate nobody has watched fail is decoration:
#   * suites forced empty      -> exit 1, "FAILED: zero tests collected" on both legs
#   * interpreted leg reporting 153 where the binary reports 154
#                              -> exit 1, "divergence":"aot != interp"
#     ...and note BOTH legs said "gate":"OK" on their own. Only the cross-leg
#     comparison caught it. That is the case this gate exists for.
#
# WIRING IT (cljgo CI). Pin by SHA, never a floating branch — a gate that
# follows toolnexus' main turns our refactors into your build breaks, and the
# first time that happens someone deletes the gate:
#
#   - uses: actions/checkout@v4
#     with: { repository: muthuishere/toolnexus, ref: <SHA>, path: tn }
#   - run: cd tn/clojure && ./cljgo-gate.sh > $GITHUB_STEP_SUMMARY.json
#
# Cost: ~7s of test work. The cljgo build of this project dominates (~2s here,
# more on a cold Maven cache), so cache ~/.m2 or you will feel it every push.

# IF THE BUILD STEP FAILS WITH `clojure.tools.build.api`, THAT IS cljgo #176 —
# NOT a problem with this project's dependencies. cljgo's build path pulls in
# tools.build when a `build.clj` is present at the repo root, and misreports the
# failure as the consumer's. This tree has NO build.clj anywhere (verified: 0
# matches; it uses deps.edn + build.cljgo, tools.build is not involved), which
# has a consequence worth stating against our own green result: a tree that
# never enters that path CANNOT vouch for the #176 fix in either direction. Do
# not read this gate passing as evidence about #176.
set -uo pipefail
cd "$(dirname "$0")"

REPO_ROOT=$(cd .. && pwd)
export TN_EXAMPLES="${TN_EXAMPLES:-$REPO_ROOT/examples}"

log()  { printf '%s\n' "$*" >&2; }
fail=0
legs=""

if [ ! -d "$TN_EXAMPLES" ]; then
  log "FATAL: TN_EXAMPLES does not exist: $TN_EXAMPLES"
  printf '{"gate":"FAILED: fixtures missing","legs":[]}\n'
  exit 2
fi

log "== build (cljgo)"
if ! cljgo build >&2; then
  log "FATAL: cljgo build failed"
  printf '{"gate":"FAILED: build","legs":[]}\n'
  exit 2
fi

# Runs one leg. $1 = label, rest = command.
# Asserts on the suite's OWN verdict line, never on $? — on cljgo exit 0 means
# nothing threw, not that anything ran.
run_leg() {
  local label=$1; shift
  local line
  line=$("$@" 2>/dev/null | grep '^{"assertions"' | tail -1)
  if [ -z "$line" ]; then
    log "  FAIL $label — no verdict line; the suite did not run"
    legs="$legs{\"leg\":\"$label\",\"gate\":\"FAILED: no verdict line\"},"
    fail=1
    return
  fi
  case "$line" in
    *'"gate":"OK"'*) log "  ok   $label  $line" ;;
    *)               log "  FAIL $label  $line"; fail=1 ;;
  esac
  legs="$legs{\"leg\":\"$label\",\"summary\":$line},"
  printf '%s' "$line" > "/tmp/cljgo-gate-$label.json"
}

log "== legs"
run_leg aot    ./toolnexus-test
run_leg interp cljgo run src/run_tests.cljc

# The two cljgo legs must agree. A binary that passes while the interpreter
# fails (or vice versa) is cljgo's release blocker, and a gate that ran both and
# never compared them would miss precisely the thing it is best placed to see.
divergence=""
if [ -s /tmp/cljgo-gate-aot.json ] && [ -s /tmp/cljgo-gate-interp.json ]; then
  if ! diff -q /tmp/cljgo-gate-aot.json /tmp/cljgo-gate-interp.json >/dev/null; then
    log "  FAIL aot != interp — AOT binary and interpreter disagree"
    diff /tmp/cljgo-gate-aot.json /tmp/cljgo-gate-interp.json >&2
    divergence='"aot != interp"'
    fail=1
  else
    log "  ok   aot == interp"
  fi
fi

gate="OK"
[ $fail -ne 0 ] && gate="FAILED"
printf '{"gate":"%s","divergence":%s,"legs":[%s]}\n' \
  "$gate" "${divergence:-null}" "${legs%,}"

[ $fail -eq 0 ] && log "PASS" || log "FAIL"
exit $fail
