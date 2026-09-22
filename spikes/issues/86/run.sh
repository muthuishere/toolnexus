#!/usr/bin/env bash
# Offline reproduction for issue #86. No network, no API key, no cost.
set -u
cd "$(dirname "$0")"
PORT="${SPIKE86_PORT:-8686}"
export SPIKE86_BASE="http://127.0.0.1:${PORT}"
export SPIKE86_LOG="$PWD/requests.ndjson"
: > "$SPIKE86_LOG"

python3 mock_llm.py "$PORT" >/dev/null 2>&1 &
MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT
for _ in $(seq 40); do
  curl -s -o /dev/null -m 1 -X POST "$SPIKE86_BASE/ping" -d '{}' && break
  sleep 0.1
done
: > "$SPIKE86_LOG"   # drop the readiness ping

echo "--- go (issue says: already correct) ---"
( cd go && go run . )

echo "--- js (issue says: fixed on jev via Toolkit.empty()) ---"
node js/probe.mjs

echo "--- python (issue says: toolkit mandatory) ---"
# needs the `mcp` package on the path: python3 -m venv .venv && .venv/bin/pip install mcp
# then: SPIKE86_PY=.venv/bin/python ./run.sh
"${SPIKE86_PY:-python3}" py/probe.py

echo "--- java (the issue's table omits java entirely) ---"
java/run-java-probe.sh

echo "--- csharp (issue says: needs an overload without Toolkit, or Toolkit?) ---"
( cd cs && dotnet run -v q --nologo 2>/dev/null )

echo "--- elixir (issue says: needs an arity that omits the toolkit) ---"
( cd ../../../elixir && mix run "$OLDPWD/probe.exs" ) 2>&1 | grep "^ex:"

echo "--- clojure (issue says: :toolkit mandatory) ---"
( cd ../../../clojure && clojure -M "$OLDPWD/clj/probe.clj" ) 2>&1 | grep -v '^Downloading\|^Cloning'

echo
echo "--- wire bodies: does any request carry a \"tools\" key? ---"
python3 - <<'PY'
import json, os
for i, line in enumerate(open(os.environ["SPIKE86_LOG"]), 1):
    b = json.loads(line)["body"]
    print(f'{i}: tools_key={"tools" in b}  tool_choice_key={"tool_choice" in b}  keys={sorted(b)}')
PY
