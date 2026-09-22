#!/usr/bin/env bash
# One documented command:  bash run.sh   (optionally: bash run.sh o3)
# .NET SDK only — no NuGet package beyond the BCL, no network, no LLM key.
set -u
cd "$(dirname "$0")"
OUT=$(mktemp -d)
dotnet build -v q --nologo -o "$OUT" >/dev/null || { dotnet build -v q --nologo -o "$OUT"; exit 1; }
dotnet "$OUT/probe.dll" "${1:-all}"
rm -rf "$OUT"
