#!/usr/bin/env bash
# ONE command for the clojure arm:  ./run.sh
# Runs EVERY section on BOTH hosts — the JVM (clojure -M) and cljgo (cljgo run)
# — from the same .cljc with no reader conditionals. No network, no LLM key.
# First cljgo run needs `cljgo build` once to pin build.lock.edn; done here.
set -u
cd "$(dirname "$0")"
[ -f build.lock.edn ] || cljgo build >/dev/null 2>&1

jvm()   { clojure -M -m probe "$@" 2>&1 | grep -v '^WARNING'; }
cljg()  { cljgo run run_cljgo.cljc "$@" 2>&1 | grep -v '^cljgo deps:\|^  pruned'; }

mk() { # scratch base + outside dir + symlink
  local d; d=$(mktemp -d)
  mkdir -p "$d/base/sub" "$d/out"
  echo secret > "$d/out/secret.txt"
  ln -s "$d/out" "$d/base/link"
  printf '%s' "$d"
}

for host in jvm cljgo; do
  echo; echo "################ host: $host"
  for sec in shell; do
    echo "---- $sec"; $( [ $host = jvm ] && echo jvm || echo cljg ) $sec
  done
  d=$(mk); echo "---- basedir"
  $( [ $host = jvm ] && echo jvm || echo cljg ) basedir "$d/base"
  echo "---- confine"
  $( [ $host = jvm ] && echo jvm || echo cljg ) confine "$d/base" "$d/out"
  echo "---- fidelity (does the set -m wrapper move \`output\`?)"
  $( [ $host = jvm ] && echo jvm || echo cljg ) fidelity "$d/pid"
  rm -rf "$d"
done

echo; echo "################ O4 — does the kill reach the grandchild?"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for host in jvm cljgo; do
  for mode in control naive postwalk setm setm-quiet setm-dash spawn-pgid; do
    m="$TMP/$host-$mode.marker"; rm -f "$m"
    if [ $host = jvm ]; then x=$(jvm kill "$mode" "$m" "$TMP/$host-$mode.pid" | grep -E 'REACHABLE|PGID|KILLGROUP|SPAWN_FIRST|OUT=' | tr '\n' ' ')
    else x=$(cljg kill "$mode" "$m" "$TMP/$host-$mode.pid" | grep -E 'REACHABLE|PGID|KILLGROUP|SPAWN_FIRST|OUT=' | tr '\n' ' '); fi
    sleep 2
    if [ -f "$m" ]; then v=ORPHAN_SURVIVED; else v=killed_whole_job; fi
    printf '%-6s %-11s %-17s %s\n' "$host" "$mode" "$v" "$x"
  done
done
