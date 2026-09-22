#!/usr/bin/env bash
# One documented command:  bash run.sh   (optionally: bash run.sh o3)
# JDK only — no network, no dependency, no LLM key.
set -u
cd "$(dirname "$0")"
OUT=$(mktemp -d)
javac -d "$OUT" Probe.java || exit 1
java -cp "$OUT" Probe "${1:-all}"
rm -rf "$OUT"
