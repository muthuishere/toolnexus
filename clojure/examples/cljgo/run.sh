#!/usr/bin/env bash
# The same six examples on cljgo — AOT binary AND interpreted, because a port
# proven only through its compiled path is proven in the mode developers use least.
set -uo pipefail
cd "$(dirname "$0")"
export TN_EXAMPLES="${TN_EXAMPLES:-$(cd ../../../examples && pwd)}"

command -v cljgo >/dev/null || { echo "cljgo not on PATH — install it or run clj/run.sh instead"; exit 1; }

cljgo build >/dev/null || { echo "cljgo build FAILED"; cljgo build 2>&1 | tail -15; exit 1; }

declare -a NAMES=(demo native_and_http skills persona_memory compaction multimodal)
# The INTERPRETED entries, not the namespace files: `cljgo run` evaluates
# top-level forms and never calls -main, so pointing it at the ns file would
# print the dependency banner, exit 0, and prove nothing.
declare -a SRCS=(src/run_interpreted.cljc src/run_native_and_http.cljc
                 src/run_skills.cljc src/run_persona_memory.cljc
                 src/run_compaction.cljc src/run_multimodal.cljc)
fail=0
for i in "${!NAMES[@]}"; do
  n=${NAMES[$i]}
  out=$(./ex-"$n" 2>&1)
  if [ $? -eq 0 ] && printf '%s' "$out" | tail -1 | grep -q '^OK$'; then
    printf '  \033[32mok  \033[0m %-18s aot\n' "$n"
  else
    printf '  \033[31mFAIL\033[0m %-18s aot\n' "$n"; printf '%s\n' "$out" | tail -12 | sed 's/^/        /'; fail=1
  fi
  out=$(cljgo run "${SRCS[$i]}" 2>&1)
  if [ $? -eq 0 ] && printf '%s' "$out" | tail -1 | grep -q '^OK$'; then
    printf '  \033[32mok  \033[0m %-18s interpreted\n' "$n"
  else
    printf '  \033[31mFAIL\033[0m %-18s interpreted\n' "$n"; printf '%s\n' "$out" | tail -12 | sed 's/^/        /'; fail=1
  fi
done
[ $fail -eq 0 ] && echo "cljgo: all ${#NAMES[@]} examples pass, AOT and interpreted" || { echo "cljgo: FAILED"; exit 1; }
