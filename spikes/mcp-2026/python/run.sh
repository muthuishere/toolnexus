#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest | ./run.sh test [pytest args]
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv && .venv/bin/pip -q install -r requirements.txt
fi
case "${1:-}" in
test) shift; exec .venv/bin/python -m pytest -q "$@" ;;
*) exec .venv/bin/python -m mcp2026 "$@" ;;
esac
