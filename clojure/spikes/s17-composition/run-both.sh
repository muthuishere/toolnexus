#!/usr/bin/env bash
# S17 — run the SAME src/toolnexus/composition.cljc on both supported hosts and prove
# the reports are byte-identical.
#
# cljgo is run BOTH ways on purpose: interpreted (`cljgo run`) and as an AOT
# binary (`cljgo build`). cljgo's own ADR 0007 calls a REPL-vs-binary divergence
# unforgivable, and toolnexus ships binaries, so a spike that only proves one
# mode proves the wrong one.
set -uo pipefail
cd "$(dirname "$0")"

EXAMPLES="${TN_EXAMPLES:-$(cd ../../../examples && pwd)}"
export TN_EXAMPLES="$EXAMPLES"
OUT=$(mktemp -d)
bold() { printf '\033[1m%s\033[0m\n' "$1"; }

bold "== Clojure (JVM)"
clojure -M -m toolnexus.composition > "$OUT/jvm.json"  || { echo "JVM run FAILED"; exit 1; }

bold "== cljgo (AOT binary)"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
"$(cljgo which composition 2>/dev/null || echo ./composition)" > "$OUT/cljgo-aot.json" 2>/dev/null \
  || cljgo build run 2>/dev/null | tail -1 > "$OUT/cljgo-aot.json"

bold "== cljgo (interpreted)"
cljgo run src/run_interpreted.cljc 2>/dev/null | tail -1 > "$OUT/cljgo-run.json" \
  || echo "(interpreted run unavailable)" > "$OUT/cljgo-run.json"

# Take the JSON line only. On cljgo, koine.server's Go backend prints
# "bri: listening on http://localhost:NNNNN" to STDOUT, which is a port number
# and therefore non-deterministic — and it pollutes any program whose output is
# data. Filed as a finding; until it moves to stderr, the report is the last
# line that starts with "{".
json_line() { grep '^{' "$1" | tail -1; }

# :host legitimately differs ("jvm" vs "cljgo"); everything else must not.
strip_host() { json_line "$1" | sed 's/"host":"[a-z-]*",//'; }

bold "== diff"
fail=0
for f in cljgo-aot cljgo-run; do
  if [ ! -s "$OUT/$f.json" ]; then printf '  \033[33m%s: no output\033[0m\n' "$f"; continue; fi
  if diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") >/dev/null; then
    printf '  \033[32mjvm == %s\033[0m  (byte-identical, %s bytes)\n' "$f" "$(wc -c < "$OUT/jvm.json" | tr -d ' ')"
  else
    printf '  \033[31mjvm != %s\033[0m\n' "$f"
    diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") | head -20
    fail=1
  fi
done

echo
echo "reports in $OUT"
exit $fail
