#!/usr/bin/env python3
"""A fake ACP agent for verifying the in-process-client recipes without a vendor binary.

initialize -> session/new (remembers the first http mcpServers entry) -> on session/prompt:
asks session/request_permission, calls tools/list + tools/call add(17, 25) on the served MCP
URL (with the entry's headers), streams the answer as two agent_message_chunks, stopReason end_turn.
Stdlib only. Records what it received in $FAKE_ACP_LOG (optional) for the run script to check.
"""
import json, os, sys, urllib.request

mcp = None
next_id = 1000
log_path = os.environ.get("FAKE_ACP_LOG")


def log(**kv):
    if log_path:
        with open(log_path, "a") as f:
            f.write(json.dumps(kv) + "\n")


def send(msg):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", **msg}) + "\n")
    sys.stdout.flush()


def read():
    line = sys.stdin.readline()
    if not line:
        sys.exit(0)
    return json.loads(line)


def mcp_call(method, params):
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    for h in mcp.get("headers") or []:
        headers[h["name"]] = h["value"]
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    with urllib.request.urlopen(urllib.request.Request(mcp["url"], body, headers)) as r:
        text = r.read().decode()
    if text.lstrip().startswith("{"):
        return json.loads(text)["result"]
    for line in text.splitlines():  # SSE framing
        if line.startswith("data:"):
            return json.loads(line[5:])["result"]
    raise RuntimeError("no MCP result: " + text)


def chunk(sid, text):
    send({"method": "session/update", "params": {"sessionId": sid, "update": {
        "sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}}}})


while True:
    msg = read()
    method, mid, params = msg.get("method"), msg.get("id"), msg.get("params") or {}
    if method == "initialize":
        log(event="initialize", clientCapabilities=params.get("clientCapabilities"))
        send({"id": mid, "result": {"protocolVersion": 1, "agentCapabilities": {}, "authMethods": []}})
    elif method == "session/new":
        servers = params.get("mcpServers") or []
        mcp = next((s for s in servers if s.get("type") == "http"), None)
        log(event="session/new", mcpServers=len(servers), meta=params.get("_meta"))
        send({"id": mid, "result": {"sessionId": "fake-1"}})
    elif method == "session/prompt":
        sid = params["sessionId"]
        next_id += 1
        send({"id": next_id, "method": "session/request_permission", "params": {"sessionId": sid,
              "toolCall": {"toolCallId": "c1", "title": "toolnexus add"},
              "options": [{"optionId": "ok", "name": "Allow", "kind": "allow_once"},
                          {"optionId": "no", "name": "Reject", "kind": "reject_once"}]}})
        reply = read()  # the client's permission answer
        log(event="permission", outcome=reply.get("result", {}).get("outcome"))
        answer = "no MCP server"
        if mcp:
            names = [t["name"] for t in mcp_call("tools/list", {})["tools"]]
            res = mcp_call("tools/call", {"name": "add", "arguments": {"a": 17, "b": 25}})
            answer = res["content"][0]["text"]
            log(event="mcp", tools=names, result=answer)
        chunk(sid, "The sum is ")
        chunk(sid, answer + ".")
        send({"id": mid, "result": {"stopReason": "end_turn"}})
    elif mid is not None and method:  # any other request: say we do not implement it
        send({"id": mid, "error": {"code": -32601, "message": "not implemented: " + method}})
