#!/usr/bin/env bash
# `examples/src/toolnexus/` is a REAL COPY of the library source, not a symlink —
# the two example projects (clj/ and cljgo/) both point their `src` symlink at it,
# so one tree serves both hosts. That copy is what a consumer actually compiles.
#
# Nothing kept it in sync. Adding a namespace to `src/toolnexus/` and forgetting
# the mirror produced, in CI, four example failures reading
#
#   Could not locate toolnexus/content__init.class, toolnexus/content.clj
#   or toolnexus/content.cljc on classpath
#
# — a message that names the missing file but not the reason, three layers below
# the actual mistake. This check states the reason.
set -uo pipefail
cd "$(dirname "$0")"

missing=() stale=()
while IFS= read -r rel; do
  a="src/$rel"; b="examples/src/$rel"
  if   [ ! -f "$b" ];                  then missing+=("$rel")
  elif ! diff -q "$a" "$b" >/dev/null; then stale+=("$rel")
  fi
done < <(cd src && find toolnexus -name '*.cljc' ! -name '*_test.cljc' ! -name 'test_main.cljc' | sort)

if [ ${#missing[@]} -eq 0 ] && [ ${#stale[@]} -eq 0 ]; then
  echo '  ok   examples/src mirrors src/toolnexus'
  echo '{"check":"examples-mirror","gate":"OK","missing":0,"stale":0}'
  exit 0
fi

for f in "${missing[@]:-}"; do [ -n "$f" ] && echo "  MISSING  examples/src/$f"; done
for f in "${stale[@]:-}";   do [ -n "$f" ] && echo "  STALE    examples/src/$f"; done
cat <<'MSG'

  examples/src/toolnexus/ is a copy of the library source that both example
  projects compile. Sync it:

      cd clojure && cp src/toolnexus/<file>.cljc examples/src/toolnexus/<file>.cljc

MSG
echo "{\"check\":\"examples-mirror\",\"gate\":\"FAILED\",\"missing\":${#missing[@]},\"stale\":${#stale[@]}}"
exit 1
