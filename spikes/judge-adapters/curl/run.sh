#!/usr/bin/env bash
# Where should the role go? Posts each payload to the System One endpoint.
# Needs TYPESAFE_API_KEY in the environment (used, never printed).
set -euo pipefail
cd "$(dirname "$0")"
for f in good_*.json bad_*.json; do
  printf '%-30s ' "$f"
  curl -s -m 30 https://api.typesafe.ai/v1/systemone \
    -H 'Content-Type: application/json' -H "Authorization: Bearer ${TYPESAFE_API_KEY:?set it}" -d @"$f" |
    jq -c '{a: (.answers | map_values(.noul)), in: .usage.input_tokens}'
done
