#!/usr/bin/env bash
# One .cljc, two runtimes. Runs the app and the test suite on Clojure (JVM) and
# on cljgo — the latter both as an AOT binary and interpreted — then asserts the
# app output is byte-identical apart from the runtime name.
#
# Asserts on OUTPUT, never on $?. On cljgo exit 0 means nothing threw, not that
# anything ran.
set -uo pipefail
cd "$(dirname "$0")"
OUT=$(mktemp -d)
bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m%s\033[0m\n' "$1"; }
bad()  { printf '  \033[31m%s\033[0m\n' "$1"; }
fail=0

bold "== build (cljgo)"
{ cljgo build && (cd aot-test && cljgo build); } >/dev/null 2>&1 || { bad "cljgo build FAILED"; exit 1; }

bold "== app"
clojure -M -m app.main 2>/dev/null | grep '^{' | tail -1 > "$OUT/jvm.json"
./minimal                2>/dev/null | grep '^{' | tail -1 > "$OUT/aot.json"
cljgo run src/run_app.cljc 2>/dev/null | grep '^{' | tail -1 > "$OUT/interp.json"

# The tmp path and the runtime name legitimately differ; nothing else may.
norm() { sed -e 's/"runtime":"[a-z]*"/"runtime":"R"/' "$1"; }
for m in aot interp; do
  if [ ! -s "$OUT/$m.json" ]; then bad "$m produced no output"; fail=1; continue; fi
  if diff <(norm "$OUT/jvm.json") <(norm "$OUT/$m.json") >/dev/null; then
    ok "jvm == cljgo/$m  ($(wc -c < "$OUT/jvm.json" | tr -d ' ') bytes)"
  else
    bad "jvm != cljgo/$m"; diff <(norm "$OUT/jvm.json") <(norm "$OUT/$m.json") | head; fail=1
  fi
done

bold "== tests (the same src/app/core_test.cljc, three ways)"
run_suite() {  # $1 = label, rest = command
  local label=$1; shift
  local line
  line=$("$@" 2>/dev/null | grep '^tests=' | tail -1)
  if [ -z "$line" ]; then bad "$label: no gate line — suite did not run"; fail=1; return; fi
  case "$line" in
    *" fail=0 error=0") ok "$label  $line" ;;
    *)                  bad "$label  $line"; fail=1 ;;
  esac
  case "$line" in tests=0*) bad "$label: zero tests collected"; fail=1 ;; esac
}
run_suite "jvm         " clojure -M -m app.test-main
run_suite "cljgo/aot   " ./aot-test/minimal-test
run_suite "cljgo/interp" cljgo run src/run_tests.cljc

echo
[ $fail -eq 0 ] && bold "PASS" || bold "FAIL"
exit $fail
