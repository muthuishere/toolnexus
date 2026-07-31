#!/usr/bin/env bash
# S24 — run the SAME .cljc clojure.test suite on both supported hosts, prove the
# reports are byte-identical, and prove the COUNTING GATE cannot be fooled.
#
# Three modes for the in-process gate (cljgo's own ADR 0007 calls a
# REPL-vs-binary divergence unforgivable, and toolnexus ships binaries):
#   1. Clojure (JVM)        clojure -M -m toolnexus.harness
#   2. cljgo AOT binary     cljgo build   -> ./harness
#   3. cljgo interpreted    cljgo run src/run_interpreted.cljc
#
# Then the EXTERNAL runner, `cljgo test`, is measured the same way — by its
# printed counts, never by its exit code.
set -uo pipefail
cd "$(dirname "$0")"

# Declared floors. Keep in sync with toolnexus.harness/min-tests|min-assertions.
FLOOR_TESTS=8            # in-process suite: toolnexus.logic-test
FLOOR_ASSERTS=22
CLJGO_TEST_TESTS=10      # `cljgo test` also collects the test/ sentinel: 8+2
CLJGO_TEST_ASSERTS=25    #                                                22+3

OUT=$(mktemp -d)
fail=0
bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m%s\033[0m\n' "$1"; }
bad()  { printf '  \033[31m%s\033[0m\n' "$1"; fail=1; }
warn() { printf '  \033[33m%s\033[0m\n' "$1"; }

# ---------------------------------------------------------------------------
# THE COUNTING GATE, applied to an external runner's stdout.
#
# `cljgo test` reports "Ran N tests containing M assertions." and exits 0 when
# it collected nothing. So: parse the counts, and refuse to call it green
# unless they are non-zero AND at the declared floor. The exit code is not
# consulted for the "did it actually run?" question at all.
#
# Prints "green|red <reason> <tests> <assertions>"; returns 0 for green.
# ---------------------------------------------------------------------------
gate_counts() { # <file> <min-tests> <min-assertions>
  local file="$1" mint="$2" mina="$3" line t a
  line=$(grep -E '^Ran [0-9]+ tests containing [0-9]+ assertions' "$file" | tail -1)
  if [ -z "$line" ]; then echo "red no-count-line 0 0"; return 1; fi
  t=$(printf '%s' "$line" | awk '{print $2}')
  a=$(printf '%s' "$line" | awk '{print $5}')
  if   [ "$t" -eq 0 ] || [ "$a" -eq 0 ]; then echo "red no-tests-collected $t $a"; return 1
  elif [ "$t" -lt "$mint" ] || [ "$a" -lt "$mina" ]; then echo "red below-floor $t $a"; return 1
  else echo "green ok $t $a"; return 0; fi
}

# ---------------------------------------------------------------------------
bold "== 1. in-process gate, three modes"
clojure -M -m toolnexus.harness 2>/dev/null | tail -1 > "$OUT/jvm.json"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
./harness 2>/dev/null | tail -1 > "$OUT/cljgo-aot.json"
cljgo run src/run_interpreted.cljc 2>/dev/null | tail -1 > "$OUT/cljgo-run.json"

for m in jvm cljgo-aot cljgo-run; do
  if grep -q '"gate":{"catches-empty":true,"reports-failures":true,"suite-runs":true,"verdict":"green"}' "$OUT/$m.json"; then
    ok "$m: in-process gate GREEN (suite ran, failures reported, empty case caught)"
  else
    bad "$m: in-process gate not green — $(cat "$OUT/$m.json" | head -c 400)"
  fi
done

# :host legitimately differs ("jvm" vs "cljgo"); nothing else may.
strip_host() { sed 's/"host":"[a-z-]*",//' "$1"; }
bold "== 2. byte-identity across hosts"
for f in cljgo-aot cljgo-run; do
  if [ ! -s "$OUT/$f.json" ]; then bad "$f: no output"; continue; fi
  if diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") >/dev/null; then
    ok "jvm == $f  (byte-identical, $(wc -c < "$OUT/jvm.json" | tr -d ' ') bytes)"
  else
    bad "jvm != $f"; diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") | head -20
  fi
done

# ---------------------------------------------------------------------------
bold "== 3. the external runner: cljgo test"
cljgo test > "$OUT/cljgo-test.txt" 2>&1; ct_exit=$?
read -r v r t a <<<"$(gate_counts "$OUT/cljgo-test.txt" "$CLJGO_TEST_TESTS" "$CLJGO_TEST_ASSERTS")"
if [ "$v" = green ]; then ok "cljgo test: GATE GREEN — $t tests / $a assertions (floor $CLJGO_TEST_TESTS/$CLJGO_TEST_ASSERTS, exit $ct_exit)"
else bad "cljgo test: GATE RED ($r) — $t tests / $a assertions, exit $ct_exit"; fi

TN_FORCE_FAIL=1 cljgo test > "$OUT/cljgo-test-armed.txt" 2>&1; armed_exit=$?
if grep -q '^1 failures' "$OUT/cljgo-test-armed.txt"; then
  ok "cljgo test: armed canary REPORTED as a failure (exit $armed_exit)"
else
  bad "cljgo test: armed canary NOT reported"
fi

cljgo test --compiled > "$OUT/cljgo-test-compiled.txt" 2>&1; comp_exit=$?
if grep -q 'could not locate namespace .*\.cljc' "$OUT/cljgo-test-compiled.txt"; then
  warn "cljgo test --compiled: KNOWN BROKEN on 0.1.0-dev (exit $comp_exit; the ns symbol keeps the .cljc extension) — README FINDING 2"
  warn "  => the AOT test path is covered by mode 2 above (cljgo build + ./harness), not by --compiled/--both"
else
  read -r v r t a <<<"$(gate_counts "$OUT/cljgo-test-compiled.txt" "$CLJGO_TEST_TESTS" "$CLJGO_TEST_ASSERTS")"
  [ "$v" = green ] && ok "cljgo test --compiled: GATE GREEN — $t/$a" || bad "cljgo test --compiled: GATE RED ($r) — $t/$a"
fi

# ---------------------------------------------------------------------------
bold "== 4. the gate catches the EMPTY case (RED here is the PASS)"
( cd empty-project && cljgo test > "$OUT/empty.txt" 2>&1; echo $? > "$OUT/empty.exit" )
empty_exit=$(cat "$OUT/empty.exit")
printf '  empty-project runner said: "%s"  (exit %s)\n' \
  "$(grep -E '^Ran ' "$OUT/empty.txt" | tail -1)" "$empty_exit"
read -r v r t a <<<"$(gate_counts "$OUT/empty.txt" "$CLJGO_TEST_TESTS" "$CLJGO_TEST_ASSERTS")"
if [ "$v" = red ] && [ "$r" = no-tests-collected ] && [ "$empty_exit" = 0 ]; then
  ok "PROVEN: the runner exits 0 on a suite that ran nothing; the gate calls it RED ($r)"
else
  bad "empty case not reproduced: gate said $v/$r, runner exit $empty_exit"
fi

echo
echo "reports in $OUT"
if [ $fail -eq 0 ]; then bold "S24: PASS"; else bold "S24: FAIL"; fi
exit $fail
