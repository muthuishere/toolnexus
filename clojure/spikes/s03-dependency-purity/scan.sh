#!/usr/bin/env bash
# Purity scanner: a Clojars lib is dual-host (JVM + cljgo) only if its Clojure
# source carries NO Java interop. Reports the interop hits per jar.
set -uo pipefail
cd "$(dirname "$0")"
work=$(mktemp -d)

for jar in $(clojure -Spath 2>/dev/null | tr ':' '\n' | grep '\.jar$'); do
  name=$(basename "$jar")
  case "$name" in clojure-1.*|spec.alpha*|core.specs*) continue;; esac
  d="$work/${name%.jar}"; mkdir -p "$d"
  unzip -qo "$jar" -d "$d" 2>/dev/null

  srcs=$(find "$d" \( -name '*.clj' -o -name '*.cljc' \) 2>/dev/null)
  [ -z "$srcs" ] && { printf '%-42s %s\n' "$name" "— no .clj/.cljc source (compiled/Java only)"; continue; }
  nsrc=$(echo "$srcs" | wc -l | tr -d ' ')

  # Java-interop signals, each independently disqualifying for cljgo
  imports=$(grep -l '(:import\|(import ' $srcs 2>/dev/null | wc -l | tr -d ' ')
  javapkg=$(grep -ho 'java\.[a-z]*\.[A-Z][A-Za-z]*' $srcs 2>/dev/null | sort -u | head -6 | tr '\n' ' ')
  statics=$(grep -hoE '\((String|Integer|Long|Double|Boolean|Character|Math|System|Class|Arrays|Thread)/[a-zA-Z]+' $srcs 2>/dev/null | sort -u | wc -l | tr -d ' ')
  genclass=$(grep -l ':gen-class\|proxy\|definterface\|reify.*java' $srcs 2>/dev/null | wc -l | tr -d ' ')

  if [ "$imports" = "0" ] && [ -z "$javapkg" ] && [ "$statics" = "0" ] && [ "$genclass" = "0" ]; then
    verdict="PURE"
  else
    verdict="JAVA (imports:$imports statics:$statics gen:$genclass) ${javapkg}"
  fi
  printf '%-42s files:%-4s %s\n' "$name" "$nsrc" "$verdict"
done
rm -rf "$work"
