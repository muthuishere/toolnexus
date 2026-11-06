#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest
set -euo pipefail
cd "$(dirname "$0")"
./gradlew -q --console=plain installDist >/dev/null
exec build/install/mcp2026/bin/mcp2026 "$@"
