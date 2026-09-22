#!/usr/bin/env bash
# ONE command for the elixir arm:  ./run.sh
# No network, no LLM key. Needs only `elixir` on PATH.
set -u
cd "$(dirname "$0")"
echo "== elixir host: $(uname -s) $(uname -m)  $(elixir --version | tail -1)"
for p in p1_shell.exs p2_basedir.exs p3_confine.exs p3b_safe_relative.exs; do
  echo; echo "---- $p"
  elixir "$p"
done

echo; echo "---- p4_kill.exs (O4: does the kill reach the grandchild?)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for mode in control naive tree taskkill portclose ownerdie setm portinfo; do
  marker="$TMP/$mode.marker"; rm -f "$marker"
  extra=$(elixir p4_kill.exs "$mode" "$marker" "$TMP/$mode.pid" 2>&1 | grep -E 'DESCENDANTS|PGID|alive|NOKILL|PORT_' | tr '\n' ' ')
  sleep 2
  if [ -f "$marker" ]; then v=ORPHAN_SURVIVED; else v=killed_whole_job; fi
  printf '%-10s %-17s %s\n' "$mode" "$v" "$extra"
done
