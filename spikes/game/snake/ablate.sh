#!/usr/bin/env bash
# The encoding ablation: one game, one set of seeds, four ways of saying the same
# thing. Everything but the words is held fixed.
set -u
cd "$(dirname "$0")"
export OPENROUTER_API_KEY="$(zsh -ic 'echo $OPENROUTER_API_KEY' 2>/dev/null)"
for style in prose raw labels stale; do
  echo "=== $style"
  node run.mjs --arm jev --style "$style" --games 3 --max-moves 150 \
    --out "style-$style" 2>&1 | grep -E 'seed [0-9]+:|warm'
done
echo "=== done"
