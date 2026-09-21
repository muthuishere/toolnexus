#!/usr/bin/env bash
# Independent gate for add-judge. Runs every port's own suite, exactly as CI does,
# and reports per-port pass/fail. Agents report on their own work; this does not.
cd "$(dirname "$0")/../../.." || exit 1
ROOT=$(pwd); FAIL=0
run() { # run <name> <dir> <cmd>
  printf '=== %-8s ' "$1"
  out=$(cd "$ROOT/$2" && eval "$3" 2>&1)
  if [ $? -eq 0 ]; then echo "PASS"; else
    echo "FAIL"; FAIL=1
    echo "$out" | tail -60 | sed 's/^/    /'
  fi
}
run golang  golang  "go build ./... && go vet ./... && go test -race ./..."
run js      js      "npm test"
# Homebrew python is externally managed, so the documented `pip install -e` fails
# outside a venv. The venv is this script's business, not the repo's.
[ -d /tmp/tnvenv ] || python3 -m venv /tmp/tnvenv
/tmp/tnvenv/bin/pip install -q -e "$ROOT/python/[test]" >/dev/null 2>&1
run python  python  "/tmp/tnvenv/bin/python -m pytest -q"
run java    java    "./gradlew test --no-daemon -q"
run csharp  csharp  "dotnet test -v q --nologo"
run elixir  elixir  "mix test"
run clojure clojure "clojure -M:test 2>/dev/null || clojure -X:test"
printf '=== %-8s ' parity
if python3 "$ROOT/conformance/check_options_parity.py" >/tmp/parity.out 2>&1; then
  if grep -qi 'landing' /tmp/parity.out; then echo "PASS but LANDING flag still present (Phase 4 must delete it)"; FAIL=1
  else echo "PASS"; fi
else echo "FAIL"; FAIL=1; tail -15 /tmp/parity.out | sed 's/^/    /'; fi
printf '=== %-8s ' openspec
npx --yes @fission-ai/openspec@latest validate add-judge >/tmp/os.out 2>&1 && echo PASS || { echo FAIL; FAIL=1; tail -5 /tmp/os.out | sed 's/^/    /'; }
echo; [ $FAIL -eq 0 ] && echo "ALL GREEN" || echo "NOT GREEN — see failures above"
exit $FAIL
