#!/usr/bin/env bash
# MCP 2026-07-28 interop: the Go CLIENT from the client branch (PR #142,
# feat/mcp-2026-07-28, or CLIENT_REF) against the Go SERVER of this checkout
# (MCPHandler on the 2026-07-28 wire + MCPServerAuth + dev AS).
#
#   interop/mcp2026/run.sh                 # client ref = origin/feat/mcp-2026-07-28
#   CLIENT_DIR=../toolnexus-mcp2026 interop/mcp2026/run.sh   # use an existing client checkout
#
# Exit code is the go test exit code. Hermetic: loopback only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLIENT_REF="${CLIENT_REF:-origin/feat/mcp-2026-07-28}"
WORK="$(mktemp -d)"
cleanup() {
  [[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null || true
  if [[ -z "${CLIENT_DIR_GIVEN:-}" && -d "$WORK/client" ]]; then git -C "$ROOT" worktree remove --force "$WORK/client" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

if [[ -n "${CLIENT_DIR:-}" ]]; then
  CLIENT_DIR_GIVEN=1
  CLIENT="$(cd "$CLIENT_DIR" && pwd)"
else
  git -C "$ROOT" worktree add --detach "$WORK/client" "$CLIENT_REF" >/dev/null
  CLIENT="$WORK/client"
fi

# Server: prints {"base","issuer"} then serves until its stdin closes.
mkfifo "$WORK/in"
(cd "$ROOT/golang" && go build -o "$WORK/server" ./internal/interop/mcp2026server)
"$WORK/server" "$ROOT/examples/skills" < "$WORK/in" > "$WORK/out" &
SERVER_PID=$!
exec 3>"$WORK/in"
for _ in $(seq 1 100); do [[ -s "$WORK/out" ]] && break; sleep 0.1; done
BASE="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["base"])' "$WORK/out")"
echo "server: $BASE  client: $CLIENT"

cp "$ROOT/interop/mcp2026/mcp2026_interop_client_test.go" "$CLIENT/golang/"
set +e
(cd "$CLIENT/golang" && TOOLNEXUS_INTEROP_BASE="$BASE" go test -count=1 -race -run 'TestInterop2026Server' -v . )
rc=$?
set -e
rm -f "$CLIENT/golang/mcp2026_interop_client_test.go"
exec 3>&-
exit $rc
