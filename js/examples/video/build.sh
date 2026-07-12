#!/usr/bin/env bash
# build.sh — produce the finished demo video from its parts.
#
#   1. record   the LazyVim screen capture   (vhs demo.tape  -> demo.mp4)
#   2. narrate  the voice-over via ElevenLabs (vo.txt         -> vo.mp3)
#   3. mux      voice over the screen capture (               -> demo-final.mp4)
#
# The voice-over is ~68 s by design and the screen capture ~67 s, so they line up.
# The last frame (the "Done." payoff) is held to cover any remainder.
#
# Requires: vhs, ffmpeg, curl, and $ELEVENLABS_API_KEY in the environment.
# The key is read from the environment at call time and never printed.
set -euo pipefail
cd "$(dirname "$0")"

VOICE_ID="${VOICE_ID:-EXAVITQu4vr4xnSDxMaL}"   # Sarah (owner may override: VOICE_ID=... ./build.sh)
MODEL_ID="${MODEL_ID:-eleven_multilingual_v2}"

echo "▸ 1/3  recording screen (vhs)…"
rm -f demo.ts                                  # start from a clean buffer every take
node maketape.mjs
vhs demo.tape                                  # -> demo.mp4

echo "▸ 2/3  synthesizing voice-over (ElevenLabs)…"
# $ELEVENLABS_API_KEY is consumed by curl directly; its value never enters a log.
: "${ELEVENLABS_API_KEY:?set ELEVENLABS_API_KEY in the environment}"
python3 - <<'PY' > vo-payload.json
import json, pathlib
text = pathlib.Path("vo.txt").read_text().strip()
print(json.dumps({"text": text, "model_id": "PLACEHOLDER_MODEL"}))
PY
# swap the model id in without exposing anything sensitive
sed -i '' "s/PLACEHOLDER_MODEL/${MODEL_ID}/" vo-payload.json
curl -sS -X POST "https://api.elevenlabs.io/v1/text-to-speech/${VOICE_ID}" \
  -H "xi-api-key: ${ELEVENLABS_API_KEY}" \
  -H "Content-Type: application/json" \
  -d @vo-payload.json \
  --output vo.mp3
rm -f vo-payload.json

echo "▸ 3/3  muxing…"
VDUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 demo.mp4)
ADUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 vo.mp3)
PAD=$(python3 -c "print(max(0.5, ${ADUR} - ${VDUR} + 0.5))")
ffmpeg -y -v error -i demo.mp4 -i vo.mp3 \
  -filter_complex "[0:v]tpad=stop_mode=clone:stop_duration=${PAD}[v]" \
  -map "[v]" -map "1:a" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest \
  demo-final.mp4

echo "✅ demo-final.mp4  (video ${VDUR}s · voice ${ADUR}s)"
