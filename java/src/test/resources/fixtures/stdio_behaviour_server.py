#!/usr/bin/env python3
"""Pre-2026 stdio MCP server for StdioMcpClientTest (stdlib only).

Tools:
  flood(n)        -> n notifications/message + n unmatched progress lines, then "flooded"
  slow(tag, ms)   -> progress every 20 ms for `ms`, then "done-<tag>" (runs on its own thread)
  pings(n)        -> sends n `ping` requests while the stdin loop is held, then waits for all n
                     replies, then "pinged-<n>" (a reader that writes replies itself deadlocks)
  ask()           -> elicitation/create, then echoes the answer's action/content
  roots()         -> roots/list, then echoes the error message the client sent back
  garbage()       -> a non-JSON line and a blank line on stdout, then "clean"
  die()           -> exits without answering
  hang(ms)        -> sleeps with no progress, then replies (late, after an idle timeout)
  cancels()       -> the notifications/cancelled params received so far
Ignores server/discover (answers -32601) so the client takes the frozen path.
"""
import json
import sys
import threading
import time

lock = threading.Lock()
waiters = {}  # request id -> (event, slot)
hold = threading.Event()  # set while `pings` writes: the stdin loop must not read
hold.set()
cancels = []  # params of every notifications/cancelled received


def send(obj):
    with lock:
        sys.stdout.write(json.dumps(obj) + "\n")
        sys.stdout.flush()


def text(rid, s):
    send({"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": s}]}})


def ask_client(method, params):
    rid = "srv-%d-%f" % (threading.get_ident(), time.time())
    ev = threading.Event()
    waiters[rid] = [ev, None]
    send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    ev.wait(10)
    return waiters.pop(rid)[1]


def call(rid, name, args, token):
    if name == "flood":
        n = int(args.get("n", 1000))
        with lock:
            for i in range(n):
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/message",
                                             "params": {"level": "info", "data": "x" * 200}}) + "\n")
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/progress",
                                             "params": {"progressToken": "nobody", "progress": i}}) + "\n")
            sys.stdout.flush()
        text(rid, "flooded")
    elif name == "slow":
        end = time.time() + int(args.get("ms", 200)) / 1000.0
        i = 0
        while time.time() < end:
            time.sleep(0.02)
            i += 1
            send({"jsonrpc": "2.0", "method": "notifications/progress",
                  "params": {"progressToken": token, "progress": i}})
        text(rid, "done-" + str(args.get("tag")))
    elif name == "pings":
        n = int(args.get("n", 5000))
        evs = []
        hold.clear()
        with lock:
            for i in range(n):
                pid = "ping-%d" % i
                ev = threading.Event()
                waiters[pid] = [ev, None]
                evs.append(pid)
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": pid, "method": "ping"}) + "\n")
            sys.stdout.flush()
        hold.set()
        ok = all(waiters[p][0].wait(10) for p in evs)
        for p in evs:
            waiters.pop(p, None)
        text(rid, "pinged-%d" % n if ok else "missing replies")
    elif name == "ask":
        r = ask_client("elicitation/create", {"message": "name?", "requestedSchema": {
            "type": "object", "properties": {"name": {"type": "string"}}}})
        res = (r or {}).get("result") or {}
        text(rid, "%s:%s" % (res.get("action"), json.dumps(res.get("content"), sort_keys=True)))
    elif name == "roots":
        r = ask_client("roots/list", {})
        text(rid, json.dumps((r or {}).get("error"), sort_keys=True))
    elif name == "garbage":
        with lock:
            sys.stdout.write("this is not json\n\n{\"broken\": \n")
            sys.stdout.flush()
        text(rid, "clean")
    elif name == "hang":
        time.sleep(int(args.get("ms", 500)) / 1000.0)
        text(rid, "late")  # a reply the client gave up on: must be dropped quietly
    elif name == "cancels":
        text(rid, json.dumps(cancels, sort_keys=True))
    elif name == "die":
        sys.stdout.flush()
        import os
        os._exit(3)
    else:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "unknown tool " + name}})


TOOLS = [{"name": n, "inputSchema": {"type": "object"}} for n in
         ["flood", "slow", "pings", "ask", "roots", "garbage", "die", "hang", "cancels"]]

for line in sys.stdin:
    hold.wait()
    line = line.strip()
    if not line:
        continue
    msg = json.loads(line)
    method = msg.get("method")
    rid = msg.get("id")
    if method == "notifications/cancelled":
        cancels.append(msg.get("params"))
        continue
    if method is None:  # a reply to one of our requests
        w = waiters.get(rid)
        if w:
            w[1] = msg
            w[0].set()
        continue
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": rid, "result": {
            "protocolVersion": msg["params"]["protocolVersion"],
            "capabilities": {"tools": {}}, "serverInfo": {"name": "behaviour", "version": "1"}}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
    elif method == "tools/call":
        p = msg["params"]
        token = (p.get("_meta") or {}).get("progressToken")
        threading.Thread(target=call, args=(rid, p["name"], p.get("arguments") or {}, token), daemon=True).start()
    elif rid is not None:
        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})
