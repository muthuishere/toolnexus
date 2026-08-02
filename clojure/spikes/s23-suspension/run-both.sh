#!/usr/bin/env bash
# S23 — run the SAME src/toolnexus/suspension.cljc on both supported hosts and prove
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
clojure -M -m toolnexus.suspension > "$OUT/jvm.json"  || { echo "JVM run FAILED"; exit 1; }

bold "== cljgo (AOT binary)"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
# `tail -1` is load-bearing, not defensive: on cljgo an AOT binary that starts a
# koine.server prints one `bri: listening on http://localhost:<port>` line to
# STDOUT per server — a port number, i.e. exactly the non-determinism the brief
# forbids in a diffable report. The -main line is the last line. See README.
if [ -x ./suspension ]; then
  ./suspension 2>/dev/null | tail -1 > "$OUT/cljgo-aot.json"
else
  cljgo build run 2>/dev/null | tail -1 > "$OUT/cljgo-aot.json"
fi

bold "== cljgo (interpreted)"
cljgo run src/run_interpreted.cljc 2>/dev/null | tail -1 > "$OUT/cljgo-run.json" \
  || echo "(interpreted run unavailable)" > "$OUT/cljgo-run.json"

# :host legitimately differs ("jvm" vs "cljgo"); everything else must not.
strip_host() { sed 's/"host":"[a-z-]*",//' "$1"; }

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
