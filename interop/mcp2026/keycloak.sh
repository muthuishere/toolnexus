#!/usr/bin/env bash
# LIVE OAuth interop against a real local Keycloak (NOT hermetic; never in CI's
# default jobs). Starts keycloak/docker-compose.yml (Keycloak 26.8.1 on
# 127.0.0.1:8180, realm toolnexus), runs the Go SERVER runner in --keycloak
# mode on the fixed 127.0.0.1:7199 (the realm's audience mapper pins
# aud = http://127.0.0.1:7199/mcp), then each CLIENT runner in --keycloak mode.
#
#   KEYCLOAK=1 interop/mcp2026/keycloak.sh                  # all 8 clients
#   KEYCLOAK=1 CLIENTS="golang js python" interop/mcp2026/keycloak.sh
#   KEEP_KEYCLOAK=1 ...   # leave the container running afterwards
set -uo pipefail
[[ -n "${KEYCLOAK:-}" ]] || { echo "live suite: set KEYCLOAK=1 (needs docker)"; exit 0; }
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SKILLS="$ROOT/examples/skills"
CLIENTS=(${CLIENTS:-golang js python java csharp elixir clojure rust})
mkdir -p "$HERE/.build"; BUILD="$(mktemp -d "$HERE/.build/kc.XXXXXX")"; LOGS="$BUILD/logs"; mkdir -p "$LOGS"
# One live run at a time: the server must own the fixed 127.0.0.1:7199.
LOCK="$HERE/.build/keycloak.lock"
for _ in $(seq 1 600); do mkdir "$LOCK" 2>/dev/null && break; sleep 1; done
[[ -d "$LOCK" ]] || exit 1
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
MCP="http://127.0.0.1:7199/mcp"

(cd "$HERE/keycloak" && docker compose up -d) || exit 1
for _ in $(seq 1 120); do curl -sf -o /dev/null http://localhost:8180/realms/toolnexus/.well-known/openid-configuration && break; sleep 2; done
curl -sf -o /dev/null http://localhost:8180/realms/toolnexus/.well-known/openid-configuration || { echo "keycloak not ready"; exit 1; }

if [[ -z "${SKIP_BUILD:-}" ]]; then
  for p in $(printf '%s\n' golang "${CLIENTS[@]}" | sort -u); do
    "$HERE/$p/build" >"$LOGS/build-$p.log" 2>&1 || { echo "build $p FAILED"; tail -20 "$LOGS/build-$p.log"; exit 1; }
  done
fi

rm -f "$BUILD/in-kc"; mkfifo "$BUILD/in-kc"
"$HERE/golang/server" --keycloak "$SKILLS" <"$BUILD/in-kc" >"$BUILD/out-kc" 2>"$LOGS/server-kc.log" &
SPID=$!
exec 9>"$BUILD/in-kc"
cleanup() {
  exec 9>&-; kill "$SPID" 2>/dev/null; rmdir "$LOCK" 2>/dev/null
  [[ -n "${KEEP_KEYCLOAK:-}" ]] || (cd "$HERE/keycloak" && docker compose down -v >/dev/null 2>&1)
}
trap cleanup EXIT
for _ in $(seq 1 100); do [[ -s "$BUILD/out-kc" ]] && break; sleep 0.1; done

fail=0
for c in "${CLIENTS[@]}"; do
  out="$("$HERE/$c/client" --keycloak "$MCP" "$SKILLS" 2>&1)"; rc=$?
  n=$(grep -c '^PASS ' <<<"$out")
  if [[ $rc -eq 0 && $n -ge 2 ]] && ! grep -q '^FAIL ' <<<"$out"; then printf '%-8s %s\n' "$c" "PASS ($n)"; else printf '%-8s %s\n' "$c" "FAIL"; echo "$out" | tail -20; fail=1; fi
done
exit $fail
