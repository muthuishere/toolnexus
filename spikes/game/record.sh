#!/usr/bin/env bash
# Record one monitor to mp4 via windowctl (which owns the monitor numbering, so
# there is no avfoundation device-index guessing). Frame loop -> ffmpeg image2.
#   ./record.sh <monitor> <out.mp4> [fps]     # Ctrl-C or `touch STOP` to end
set -euo pipefail
MON="${1:-1}"; OUT="${2:-/tmp/run.mp4}"; FPS="${3:-4}"
DIR=$(mktemp -d); trap 'rm -rf "$DIR"' EXIT
echo "recording monitor $MON -> $OUT (${FPS}fps); touch $DIR/STOP or Ctrl-C to end" >&2
n=0
while [ ! -f "$DIR/STOP" ] && [ ! -f "./STOP" ]; do
  windowctl screenshot --monitor "$MON" --out "$(printf '%s/f%06d.png' "$DIR" "$n")" >/dev/null 2>&1 || break
  n=$((n+1)); sleep "$(python3 -c "print(max(0,1/$FPS-0.18))")"
done
echo "captured $n frames" >&2
ffmpeg -y -framerate "$FPS" -i "$DIR/f%06d.png" -c:v libx264 -pix_fmt yuv420p -vf "scale=1280:-2" "$OUT" 2>&1 | tail -2
ls -lh "$OUT"
