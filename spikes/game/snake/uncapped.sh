#!/usr/bin/env bash
# The uncapped run. Every result so far hit a 150-move cap, so the snake never got
# long enough to die — which made the score metric blind to `stale`'s 3 self-trapping
# moves. Here games end when the snake dies. 600 is a safety valve, not a target.
set -u
cd "$(dirname "$0")"
export OPENROUTER_API_KEY="$(zsh -ic 'echo $OPENROUTER_API_KEY' 2>/dev/null)"
echo "=== code (no API)"
node run.mjs --arm code --games 3 --max-moves 600 --out uncapped-code 2>&1 | grep -E 'seed [0-9]+:'
for style in prose stale; do
  echo "=== jev/$style"
  node run.mjs --arm jev --style "$style" --games 3 --max-moves 600 \
    --out "uncapped-$style" 2>&1 | grep -E 'seed [0-9]+:|warm'
done
echo "=== done"
