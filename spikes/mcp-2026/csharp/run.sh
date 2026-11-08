#!/usr/bin/env bash
# Usage: ./run.sh server | ./run.sh client [url] | ./run.sh selftest
set -euo pipefail
cd "$(dirname "$0")"
dotnet build src/Spike -c Release -v q -nologo >/dev/null
exec dotnet src/Spike/bin/Release/net10.0/spike.dll "$@"
