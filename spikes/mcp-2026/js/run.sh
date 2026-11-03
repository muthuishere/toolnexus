#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest | ./run.sh test
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
test) shift; exec node --test --test-concurrency=4 "$@" test/*.test.ts ;;
*) exec node src/cli.ts "$@" ;;
esac
