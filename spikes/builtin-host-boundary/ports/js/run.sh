#!/usr/bin/env bash
# All four js probes. No network, no LLM key, no npm install (node stdlib only).
#   bash spikes/builtin-host-boundary/ports/js/run.sh
set -u
cd "$(dirname "$0")"
for p in p1-shell.mjs p2-basedir.mjs p3-confine.mjs p4-kill.mjs; do
  echo "----- $p -----"
  node "$p"
done
