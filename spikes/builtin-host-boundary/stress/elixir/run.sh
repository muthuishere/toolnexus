#!/usr/bin/env bash
# Stress harness for the SHIPPED Elixir builtins (spikes/builtin-host-boundary/STRESS-SPEC).
#
#   bash run.sh
#
# It compiles the shipped elixir/ port (deps must already be fetched: the run itself
# is hermetic — no network, no keys) and runs stress.exs against the real
# Toolnexus.Builtin.load/1 tools. Nothing here reimplements builtin logic.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
port="$(cd "$here/../../../../elixir" && pwd)"

( cd "$port" && MIX_ENV=dev mix compile >/dev/null )

paths=()
for ebin in "$port"/_build/dev/lib/*/ebin; do paths+=(-pa "$ebin"); done

exec elixir "${paths[@]}" "$here/stress.exs"
