#!/usr/bin/env bash
# S1 runner (POSIX hosts). For each port, run the probe in `naive` mode (what the
# port ships today) and in the fixed mode, and report whether the GRANDCHILD
# outlived the kill.
#
# The command is `sleep 1; touch $MARKER` with a 300 ms timeout. The marker can
# only appear if `sleep` survived — so MARKER present ⇒ orphan survived.
set -u
cd "$(dirname "$0")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

probe() { # name mode cmd...
  local name=$1 mode=$2; shift 2
  local marker="$TMP/$name-$mode.marker"
  rm -f "$marker"
  local extra=""
  extra=$("$@" "$mode" "$marker" "$TMP/$name-$mode.pid" 2>&1 | grep -E 'DESCENDANTS|REACHABLE|PGID|START_ERR' | tr '\n' ' ')
  sleep 2
  if [ -f "$marker" ]; then
    printf '%-10s %-9s ORPHAN_SURVIVED  %s\n' "$name" "$mode" "$extra"
  else
    printf '%-10s %-9s killed_whole_job %s\n' "$name" "$mode" "$extra"
  fi
}

echo "== s1: does the timeout kill reach the grandchild? (host: $(uname -s) $(uname -m))"

if command -v go >/dev/null; then
  go build -o "$TMP/probe-go" probe.go 2>/dev/null || go run probe.go naive /dev/null >/dev/null 2>&1
  probe go naive "$TMP/probe-go"; probe go group "$TMP/probe-go"
fi
if command -v node >/dev/null; then
  probe node naive node probe.mjs; probe node group node probe.mjs
fi
if command -v python3 >/dev/null; then
  probe python naive python3 probe.py; probe python group python3 probe.py
fi
if command -v javac >/dev/null; then
  javac -d "$TMP" Probe.java 2>/dev/null
  probe java naive java -cp "$TMP" Probe; probe java tree java -cp "$TMP" Probe
fi
if command -v dotnet >/dev/null; then
  (cd probe-cs && dotnet build -v q --nologo -o "$TMP/cs" >/dev/null 2>&1)
  probe csharp naive dotnet "$TMP/cs/probe.dll"; probe csharp tree dotnet "$TMP/cs/probe.dll"
fi
if command -v elixir >/dev/null; then
  probe elixir naive elixir probe.exs; probe elixir tree elixir probe.exs
fi
if command -v clojure >/dev/null; then
  probe clojure naive ./clj/run-clj.sh
  probe clojure postwalk ./clj/run-clj.sh
  probe clojure pgroup ./clj/run-clj.sh
fi
