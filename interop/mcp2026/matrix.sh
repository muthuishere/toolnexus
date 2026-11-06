#!/usr/bin/env bash
# MCP 2026-07-28 interop matrix: every port's CLIENT runner against every
# port's SERVER runner (contract: interop/mcp2026/README.md). Hermetic:
# loopback only, the test-only dev authorization server of each server port.
#
#   interop/mcp2026/matrix.sh                       # all 8 x 8
#   CLIENTS="golang js" SERVERS="python" interop/mcp2026/matrix.sh
#   SKIP_BUILD=1 interop/mcp2026/matrix.sh          # reuse interop/mcp2026/.build
#
# Prints a grid of passed/total scenarios per pair (X = some failed) and
# writes it to interop/mcp2026/.build/run.*/grid.txt; failure output goes to
# that run dir's logs/. Exit 1 if any pair failed.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SKILLS="$ROOT/examples/skills"
ALL="golang js python java csharp elixir clojure rust"
CLIENTS=(${CLIENTS:-$ALL})
SERVERS=(${SERVERS:-$ALL})
SCENARIOS=11
mkdir -p "$HERE/.build"; BUILD="$(mktemp -d "$HERE/.build/run.XXXXXX")"; LOGS="$BUILD/logs"; mkdir -p "$LOGS"
TIMEOUT="${PAIR_TIMEOUT:-180}"

# timeout(1) is coreutils; macOS has gtimeout at best.
tmo() { if command -v timeout >/dev/null; then timeout "$@"; elif command -v gtimeout >/dev/null; then gtimeout "$@"; else shift; "$@"; fi; }

if [[ -z "${SKIP_BUILD:-}" ]]; then
  for p in $(printf '%s\n' "${CLIENTS[@]}" "${SERVERS[@]}" | sort -u); do
    echo "build $p"
    "$HERE/$p/build" >"$LOGS/build-$p.log" 2>&1 || { echo "build $p FAILED (see $LOGS/build-$p.log)"; tail -20 "$LOGS/build-$p.log"; exit 1; }
  done
fi

declare -A BASE FIFO
pids=()
cleanup() {
  for s in "${SERVERS[@]}"; do [[ -n "${FIFO[$s]:-}" ]] && eval "exec ${FD[$s]}>&-" 2>/dev/null; done
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null; pkill -P "$p" 2>/dev/null; done
}
declare -A FD
trap cleanup EXIT

fd=10
for s in "${SERVERS[@]}"; do
  FIFO[$s]="$BUILD/in-$s"; rm -f "${FIFO[$s]}"; mkfifo "${FIFO[$s]}"
  "$HERE/$s/server" "$SKILLS" <"${FIFO[$s]}" >"$BUILD/out-$s" 2>"$LOGS/server-$s.log" &
  pids+=($!)
  eval "exec $fd>\"${FIFO[$s]}\""; FD[$s]=$fd; fd=$((fd + 1))
done
for s in "${SERVERS[@]}"; do
  for _ in $(seq 1 600); do [[ -s "$BUILD/out-$s" ]] && break; sleep 0.1; done
  BASE[$s]="$(python3 -c 'import json,sys;print(json.loads(open(sys.argv[1]).readline())["base"])' "$BUILD/out-$s" 2>/dev/null)"
  if [[ -z "${BASE[$s]}" ]]; then echo "server $s did not start:"; tail -20 "$LOGS/server-$s.log"; exit 1; fi
  echo "server $s: ${BASE[$s]}"
done

{
  printf '%-14s' 'client\server'; for s in "${SERVERS[@]}"; do printf '%-9s' "$s"; done; echo
  for c in "${CLIENTS[@]}"; do
    printf '%-14s' "$c"
    for s in "${SERVERS[@]}"; do
      out="$(tmo "$TIMEOUT" "$HERE/$c/client" "${BASE[$s]}" "$SKILLS" 2>&1)"; rc=$?
      n=$(grep -c '^PASS ' <<<"$out"); f=$(grep -c '^FAIL ' <<<"$out")
      if [[ $rc -eq 0 && $n -eq $SCENARIOS && $f -eq 0 ]]; then cell="$n/$SCENARIOS"; else cell="X$n/$SCENARIOS"; echo "$out" >"$LOGS/fail-$c-x-$s.log"; fi
      printf '%-9s' "$cell"
    done
    echo
  done
} | tee "$BUILD/grid.txt"
echo "logs: $LOGS"
for f in "$LOGS"/fail-*.log; do [[ -e "$f" ]] || continue; echo "--- $(basename "$f")"; grep '^FAIL' "$f" || tail -15 "$f"; done
! ls "$LOGS"/fail-*.log >/dev/null 2>&1
