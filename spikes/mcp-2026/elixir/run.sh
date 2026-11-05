#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest
set -euo pipefail
cd "$(dirname "$0")"
[ -d deps ] || mix deps.get >/dev/null
mix compile >/dev/null
exec mix run --no-compile -e 'Mcp2026.CLI.main(System.argv())' -- "$@"
