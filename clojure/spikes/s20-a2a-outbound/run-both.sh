#!/usr/bin/env bash
# S20 — run the SAME src/toolnexus/a2aout.cljc on both supported hosts and prove
# the reports are byte-identical.
#
# cljgo is run BOTH ways on purpose: interpreted (`cljgo run`) and as an AOT
# binary (`cljgo build`). cljgo's own ADR 0007 calls a REPL-vs-binary divergence
# unforgivable, and toolnexus ships binaries, so a spike that only proves one
# mode proves the wrong one.
#
# NOTE the `tail -1` on the cljgo runs: on cljgo, koine.server/serve prints
# `bri: listening on http://localhost:<port>` to STDOUT (the JVM prints
# nothing). The report is the LAST line. See README finding 2.
set -uo pipefail
cd "$(dirname "$0")"

# An obvious fake, so the ${ENV} header-expansion path is exercised. Never a
# real credential — and the value is never printed, only whether expansion
# changed the string.
export TN_A2A_TOKEN="${TN_A2A_TOKEN:-YOUR_KEY_HERE}"

OUT=$(mktemp -d)
bold() { printf '\033[1m%s\033[0m\n' "$1"; }

bold "== Clojure (JVM)"
clojure -M -m toolnexus.a2aout | tail -1 > "$OUT/jvm.json" || { echo "JVM run FAILED"; exit 1; }

bold "== cljgo (AOT binary)"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
"$(cljgo which a2aout 2>/dev/null || echo ./a2aout)" 2>/dev/null | tail -1 > "$OUT/cljgo-aot.json"

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
