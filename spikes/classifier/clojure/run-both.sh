#!/usr/bin/env bash
# Run the SAME src/toolnexus/classifier.cljc on both supported hosts and prove
# the four-gate report is byte-identical. No network, no key, no LLM.
set -uo pipefail
cd "$(dirname "$0")"
export TN_CLASSIFIER_FIXTURE="${TN_CLASSIFIER_FIXTURE:-$(cd ../fixture && pwd)}"
OUT=$(mktemp -d)
bold() { printf '\033[1m%s\033[0m\n' "$1"; }
json_line() { grep '^{' "$1" | tail -1; }
strip_host() { json_line "$1" | sed 's/"host":"[a-z-]*"//'; }

bold "== Clojure (JVM)"
clojure -M -m toolnexus.classifier > "$OUT/jvm.json" || { echo "JVM run FAILED"; exit 1; }
json_line "$OUT/jvm.json"

bold "== cljgo (AOT binary)"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
./classifier 2>/dev/null > "$OUT/cljgo-aot.json"

bold "== cljgo (interpreted)"
cljgo run src/run_interpreted.cljc 2>/dev/null > "$OUT/cljgo-run.json" \
  || echo "(interpreted run unavailable)" > "$OUT/cljgo-run.json"

bold "== diff"
fail=0
for f in cljgo-aot cljgo-run; do
  if [ ! -s "$OUT/$f.json" ]; then printf '  \033[33m%s: no output\033[0m\n' "$f"; fail=1; continue; fi
  if diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") >/dev/null; then
    printf '  \033[32mjvm == %s\033[0m  (byte-identical)\n' "$f"
  else
    printf '  \033[31mjvm != %s\033[0m\n' "$f"; diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") | head -20; fail=1
  fi
done
exit $fail
