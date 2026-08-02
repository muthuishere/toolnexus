#!/usr/bin/env bash
# S19 — run the SAME src/toolnexus/skills.cljc on both supported hosts and prove
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
clojure -M -m toolnexus.skills > "$OUT/jvm.json"  || { echo "JVM run FAILED"; exit 1; }

bold "== cljgo (AOT binary)"
cljgo build >/dev/null 2>&1 || { echo "cljgo build FAILED"; exit 1; }
# `cljgo build` leaves the binary next to build.cljgo. Assert on OUTPUT, never
# on the exit code (spike brief trap 2).
./skills > "$OUT/cljgo-aot.json" 2>/dev/null

bold "== cljgo (interpreted)"
cljgo run src/run_interpreted.cljc 2>/dev/null | tail -1 > "$OUT/cljgo-run.json" \
  || echo "(interpreted run unavailable)" > "$OUT/cljgo-run.json"

# :host legitimately differs ("jvm" vs "cljgo"); everything else must not.
strip_host() { sed 's/"host":"[a-z-]*",//' "$1"; }

bold "== diff"
fail=0
for f in cljgo-aot cljgo-run; do
  if [ ! -s "$OUT/$f.json" ]; then printf '  \033[33m%s: no output\033[0m\n' "$f"; fail=1; continue; fi
  if diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") >/dev/null; then
    printf '  \033[32mjvm == %s\033[0m  (byte-identical, %s bytes)\n' "$f" "$(wc -c < "$OUT/jvm.json" | tr -d ' ')"
  else
    printf '  \033[31mjvm != %s\033[0m\n' "$f"
    diff <(strip_host "$OUT/jvm.json") <(strip_host "$OUT/$f.json") | head -20
    fail=1
  fi
done

bold "== assertions"
for f in jvm cljgo-aot cljgo-run; do
  [ -s "$OUT/$f.json" ] || continue
  python3 - "$OUT/$f.json" "$f" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
bad=[k for k,v in d["assertions"].items() if v is not True]
out=d["shared"]["skill-output"].encode("utf-8")
# The report counts CHARACTERS; re-measure the real UTF-8 byte length here so a
# non-ASCII fixture could never make the two silently disagree.
byte_ok = len(out)==d["shared"]["output-bytes"]
print(f"  {sys.argv[2]:<10} ok={d['ok']} shared.output-bytes={d['shared']['output-bytes']} "
      f"(utf8={len(out)}) s15-bytes={d['shared']['s15-bytes']} failed={bad or 'none'}")
sys.exit(0 if (d["ok"] and byte_ok) else 1)
PY
  [ $? -eq 0 ] || fail=1
done

echo
echo "reports in $OUT"
exit $fail
