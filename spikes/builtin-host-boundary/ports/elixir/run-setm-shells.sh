#!/usr/bin/env bash
# Does the `set -m` wrapper kill the tree under EVERY POSIX interpreter, or only
# bash? Control arm per shell = the same command with no kill at all.
set -u
cd "$(dirname "$0")"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for s in "sh:/bin/sh" "dash:/bin/dash" "zsh:/bin/zsh"; do
  name=${s%%:*}; path=${s#*:}
  [ -x "$path" ] || { echo "$name: not installed, SKIPPED"; continue; }
  for mode in control setm; do
    m="$TMP/$name-$mode.marker"; rm -f "$m"
    x=$(elixir p7_setm_shells.exs "$name" "$path" "$mode" "$m" "$TMP/$name.pid" 2>&1 | tr '\n' ' ')
    sleep 2
    if [ -f "$m" ]; then v=ORPHAN_SURVIVED; else v=killed_whole_job; fi
    printf '%-6s %-8s %-17s %s\n' "$name" "$mode" "$v" "$x"
  done
done
