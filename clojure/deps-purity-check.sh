#!/usr/bin/env bash
# deps-purity-check — is koine really the only third-party dependency?
#
#   ./deps-purity-check.sh      exit 0 = pure
#
# "koine is the only third-party dependency" is the port's central rule and it
# had NOTHING enforcing it. A rule with no gate is a comment, and this one is
# easy to break by accident: a `deps.edn` edit while chasing a bug, or a new
# dependency that quietly pulls in three more.
#
# It matters more here than in a normal project, because of S03's measurement:
# 11 of 11 popular Clojure libraries carry Java interop and NONE of them load on
# cljgo — including data.json, which is what a JSON-first port reaches for by
# reflex. So a stray dependency does not degrade the port, it ENDS the cljgo
# half of it. The failure would surface as a cljgo build error far from the
# edit that caused it.
#
# TRANSITIVE, not direct. Reading deps.edn only proves what we asked for; this
# resolves the real classpath, which is what actually loads. koine's own promise
# is zero third-party deps, and this is the check that holds it to that from the
# outside rather than trusting its README.
#
# The allowlist is deliberately tiny and spelled out, so adding to it is a
# visible diff someone has to justify:
#   clojure, spec.alpha, core.specs.alpha   — Clojure itself, shipped as 3 jars
#   koine                                    — the seam
#
# NOT checked here: spikes/s03-dependency-purity, whose deps.edn deliberately
# pulls in eleven JVM-only libraries because they are its SUBJECT — it unzips
# them to scan for interop. That exception is named rather than pattern-matched,
# so a second "exception" cannot appear quietly.
# PROVEN TO FAIL: adding org.clojure/data.json — the exact library a JSON-first
# port reaches for by reflex, and one S03 measured as JVM-only — makes this
# report `FAIL: . depends on more than koine: data.json` and exit 1.
set -uo pipefail
cd "$(dirname "$0")"

ALLOWED="clojure core.specs.alpha koine spec.alpha"

fail=0
check_tree() {   # $1 = directory holding a deps.edn
  local dir=$1
  local jars
  jars=$( (cd "$dir" && clojure -Spath 2>/dev/null) | tr ':' '\n' | grep '\.jar$' \
          | xargs -n1 basename 2>/dev/null \
          | sed -E 's/-[0-9]+\.[0-9]+\.[0-9]+.*\.jar$//' | sort -u )
  if [ -z "$jars" ]; then
    echo "FAIL: $dir — could not resolve a classpath" >&2
    fail=1
    return
  fi
  local bad=""
  for j in $jars; do
    case " $ALLOWED " in
      *" $j "*) ;;
      *) bad="$bad $j" ;;
    esac
  done
  if [ -n "$bad" ]; then
    echo "FAIL: $dir depends on more than koine:$bad" >&2
    fail=1
  else
    echo "  ok   $dir — $(echo "$jars" | tr '\n' ' ')" >&2
  fi
}

echo "== transitive classpath purity" >&2
check_tree .
check_tree examples/minimal
for d in spikes/s1[5-9]* spikes/s2[0-4]*; do
  [ -f "$d/deps.edn" ] && check_tree "$d"
done

printf '{"check":"deps-purity","allowed":"%s","gate":"%s"}\n' \
  "$ALLOWED" "$([ $fail -eq 0 ] && echo OK || echo FAILED)"
exit $fail
