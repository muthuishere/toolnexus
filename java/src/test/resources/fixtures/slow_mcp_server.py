"""Hermetic stdio MCP server (stdlib only) for McpToolCallIdleTimeoutTest.

Old protocol only: server/discover gets -32601, so the client takes the SDK fallback path,
the one callWithIdleTimeout guards. Each tools/call runs on its own thread, so a call left
hanging (timed out / cancelled) never delays the next one.

Tools (all args in milliseconds):
  fast                       reply at once
  sleep     {ms}             reply after ms
  progress  {steps, every}   send `steps` progress notifications `every` ms apart, then reply
"""
import json
import sys
import threading
import time

lock = threading.Lock()


def send(obj):
    with lock:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def call(mid, name, args, token):
    if name == "sleep":
        time.sleep(args.get("ms", 0) / 1000.0)
    elif name == "progress":
        for i in range(int(args.get("steps", 0))):
            time.sleep(args.get("every", 0) / 1000.0)
            send({"jsonrpc": "2.0", "method": "notifications/progress",
                  "params": {"progressToken": token, "progress": i + 1}})
    send({"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": name + " done"}]}})


TOOLS = [{"name": n, "inputSchema": {"type": "object"}} for n in ("fast", "sleep", "progress")]

for line in sys.stdin:
    if not line.strip():
        continue
    m = json.loads(line)
    mid, method = m.get("id"), m.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": m["params"]["protocolVersion"], "capabilities": {"tools": {}},
            "serverInfo": {"name": "slow", "version": "1"}}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        p = m["params"]
        token = (p.get("_meta") or {}).get("progressToken")
        threading.Thread(target=call, args=(mid, p["name"], p.get("arguments") or {}, token), daemon=True).start()
    elif mid is not None:
        send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found"}})
