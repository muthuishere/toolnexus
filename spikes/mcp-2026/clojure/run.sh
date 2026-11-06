#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest | ./run.sh test | ./run.sh example NN-name
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
test) exec clojure -M:test -m mcp2026.test-main ;;
example) exec clojure -M "examples/$2/main.cljc" ;;
*) exec clojure -M -m mcp2026.checks "$@" ;;
esac
