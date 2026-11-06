#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest | ./run.sh stress | ./run.sh fuzz [time]
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
stress)
  printf '| scenario | ops | throughput | p50 | p99 | errors | goroutines | heap / notes |\n|---|---|---|---|---|---|---|---|\n'
  go test -race -count=1 -run 'Stress' -v . | sed -n 's/^STRESS|\(.*\)$/| \1 |/p' | sed 's/|/ | /g; s/^ | /| /; s/ |  | $/ |/'
  ;;
fuzz)
  go test -race -run '^$' -fuzz FuzzServerHandler -fuzztime "${2:-30s}" .
  ;;
*) exec go run ./cmd/spike "$@" ;;
esac
