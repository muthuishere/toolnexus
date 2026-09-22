#!/usr/bin/env bash
# All four python probes. No network, no LLM key, stdlib only (no pip install).
#   bash spikes/builtin-host-boundary/ports/python/run.sh
set -u
cd "$(dirname "$0")"
for p in p1_shell.py p2_basedir.py p3_confine.py p4_kill.py; do
  echo "----- $p -----"
  python3 "$p"
done
