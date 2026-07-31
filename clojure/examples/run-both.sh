#!/usr/bin/env bash
# Run src/toolnexus/demo.cljc on Clojure (JVM) and on cljgo — three ways —
# print what each one said, and fail if they said different things.
#
#   ./run-both.sh
#
# cljgo is run BOTH as an AOT binary and interpreted, on purpose: `cljgo run`
# and `cljgo build` are different code paths, and toolnexus ships binaries, so
# proving only one proves the wrong one.
#
# The assertion is on OUTPUT, never on the exit code. On cljgo, exit 0 means
# nothing threw — not that anything happened.
set -uo pipefail
cd "$(dirname "$0")"

OUT=$(mktemp -d)
bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# The shared cross-language fixtures at the repo root — the same mcp.json and
# skills/ that the JS, Python, Go, Java, C# and Elixir ports run against.
export TN_EXAMPLES="${TN_EXAMPLES:-$(cd ../../examples && pwd)}"

bold "== Clojure (JVM)   clojure -M -m toolnexus.demo"
( cd clojure-app && clojure -M -m toolnexus.demo ) > "$OUT/jvm.txt" 2>"$OUT/jvm.err"
cat "$OUT/jvm.txt"

bold "== cljgo, AOT binary   cljgo build && ./demo"
( cd cljgo-app && cljgo build >/dev/null 2>&1 && ./demo ) > "$OUT/aot.txt" 2>"$OUT/aot.err"
cat "$OUT/aot.txt"

bold "== cljgo, interpreted   cljgo run src/run_interpreted.cljc"
( cd cljgo-app && cljgo run src/run_interpreted.cljc ) > "$OUT/interp.txt" 2>"$OUT/interp.err"
cat "$OUT/interp.txt"

# The runtime's own name is the ONE line allowed to differ. Everything else —
# the tool list, the MCP echo, the skill byte count — must match exactly.
norm() { sed -E 's/^toolnexus demo .*$/toolnexus demo/' "$1"; }

bold "== diff"
fail=0
# An empty (or trivially short) report is a failure even at exit 0.
for f in jvm aot interp; do
  if [ "$(wc -l < "$OUT/$f.txt")" -lt 20 ]; then
    printf '  \033[31m%s produced no report\033[0m\n' "$f"
    sed 's/^/      /' "$OUT/$f.err" | head -10
    fail=1
  fi
done
# The demo must have actually reached all three sources.
grep -q 'Echo: hello from Clojure'    "$OUT/jvm.txt" || { echo "  MCP call never happened"; fail=1; }
grep -q 'bytes of instructions'       "$OUT/jvm.txt" || { echo "  skill call never happened"; fail=1; }
grep -q 'SAME FILE, BOTH RUNTIMES'    "$OUT/jvm.txt" || { echo "  native call never happened"; fail=1; }

if [ "$fail" -eq 0 ]; then
  for f in aot interp; do
    if diff <(norm "$OUT/jvm.txt") <(norm "$OUT/$f.txt") >/dev/null; then
      printf '  \033[32mjvm == cljgo/%s\033[0m  (identical, %s bytes)\n' \
        "$f" "$(wc -c < "$OUT/jvm.txt" | tr -d ' ')"
    else
      printf '  \033[31mjvm != cljgo/%s\033[0m\n' "$f"
      diff <(norm "$OUT/jvm.txt") <(norm "$OUT/$f.txt") | head -20
      fail=1
    fi
  done
fi

echo
echo "reports in $OUT"
exit $fail
