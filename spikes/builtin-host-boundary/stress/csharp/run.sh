#!/usr/bin/env bash
# Stress harness for the SHIPPED C# builtin host boundary (spikes/builtin-host-boundary/STRESS-SPEC).
# One command:  bash run.sh
set -euo pipefail
cd "$(dirname "$0")"
dotnet run -c Release --project Stress.csproj -- "$@"
