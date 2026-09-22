#!/usr/bin/env bash
# ONE command:  bash run.sh
# Stress harness for the SHIPPED clojure builtins (spikes/builtin-host-boundary).
# Hermetic: no network, no keys. Whole run ~90s on the JVM host.
set -u
cd "$(dirname "$0")"

TMP=$(mktemp -d /tmp/tn-stress-clj.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/markers" "$TMP/base/sub" "$TMP/out"
printf 'A\n'      > "$TMP/base/a.txt"
printf 'B\n'      > "$TMP/base/sub/b.txt"
printf 'C\n'      > "$TMP/base/c.txt"
printf 'secret\n' > "$TMP/out/secret.txt"
ln -s "$TMP/out" "$TMP/base/link"

echo "################ host: jvm"
clojure -M -m stress "$TMP/markers" "$TMP/base" 2>&1 | grep -v '^WARNING'

if command -v cljgo >/dev/null 2>&1; then
  echo
  echo "################ host: cljgo"
  cljgo build 2>&1 | grep -v '^cljgo deps:\|^  pruned\|^cljgo build:'
  ./tn-stress "$TMP/markers" "$TMP/base" 2>&1
else
  echo "cljgo NOT INSTALLED — cljgo host not measured"
fi
