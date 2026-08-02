#!/usr/bin/env bash
# The five examples on Clojure (JVM). Each must print OK on its last line.
set -uo pipefail
cd "$(dirname "$0")"
export TN_EXAMPLES="${TN_EXAMPLES:-$(cd ../../../examples && pwd)}"

EXAMPLES=(toolnexus.demo examples.native-and-http examples.skills
          examples.persona-memory examples.compaction)
fail=0
for ns in "${EXAMPLES[@]}"; do
  out=$(clojure -M -m "$ns" 2>&1)
  if [ $? -eq 0 ] && printf '%s' "$out" | tail -1 | grep -q '^OK$'; then
    printf '  \033[32mok  \033[0m %s\n' "$ns"
  else
    printf '  \033[31mFAIL\033[0m %s\n' "$ns"
    printf '%s\n' "$out" | tail -12 | sed 's/^/        /'
    fail=1
  fi
done
[ $fail -eq 0 ] && echo "clj: all ${#EXAMPLES[@]} examples pass" || { echo "clj: FAILED"; exit 1; }
