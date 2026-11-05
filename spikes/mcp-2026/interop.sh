#!/usr/bin/env bash
# 8x8 interop matrix: every port's client against every port's server (MCP 2026-07-28 spike).
# Usage: ./interop.sh            (hermetic, localhost only)
set -u
cd "$(dirname "$0")"
LANGS=(js python golang java csharp elixir clojure rust)
declare -A PORT=([js]=7101 [python]=7102 [golang]=7103 [java]=7104 [csharp]=7105 [elixir]=7106 [clojure]=7107 [rust]=7108)
LOG=$(mktemp -d)
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; pkill -P "$p" 2>/dev/null; done; }
trap cleanup EXIT

for l in "${LANGS[@]}"; do
  (cd "$l" && PORT=${PORT[$l]} exec ./run.sh server) >"$LOG/server-$l.log" 2>&1 &
  pids+=($!)
done
for l in "${LANGS[@]}"; do
  for _ in $(seq 1 180); do
    curl -s -o /dev/null -X POST "http://127.0.0.1:${PORT[$l]}/mcp" && break
    sleep 1
  done
done

printf '%-9s' "client\\srv"; for s in "${LANGS[@]}"; do printf '%-8s' "$s"; done; echo
fail=0
for c in "${LANGS[@]}"; do
  printf '%-9s' "$c"
  for s in "${LANGS[@]}"; do
    out=$(cd "$c" && timeout 120 ./run.sh client "http://127.0.0.1:${PORT[$s]}/mcp" 2>&1)
    n=$(grep -c '^PASS' <<<"$out"); f=$(grep -c '^FAIL' <<<"$out")
    if [ "$n" -ge 8 ] && [ "$f" -eq 0 ]; then printf '%-8s' "$n/8"; else printf '%-8s' "X$n"; fail=1; echo "$out" >"$LOG/fail-$c-$s.log"; fi
  done
  echo
done
echo "logs: $LOG"
exit $fail
