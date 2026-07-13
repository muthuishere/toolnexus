#!/usr/bin/env python3
"""Shared stdio MCP server used by EVERY framework in this benchmark.

A minimal, dependency-free (stdlib only) MCP server over the stdio transport
(newline-delimited JSON-RPC 2.0). It is deliberately a *raw* implementation so
it interoperates with every client in this benchmark regardless of which MCP
SDK / protocol version they negotiate:

  * toolnexus Python / LangGraph / Google-ADK  -> `mcp` (Python) client
  * toolnexus Go                               -> mark3labs/mcp-go client
  * toolnexus Java / C#                         -> official MCP SDKs

It accepts ANY protocol version by echoing back whatever the client asks for in
`initialize`, so version negotiation never fails. Tool-discovery cost is thus
identical across frameworks (same binary, same wire).

Tools:
  * get_weather(city) -> a canned weather string
  * add(a, b)         -> a + b
  * echo(text)        -> text
"""
from __future__ import annotations

import json
import sys

TOOLS = [
    {
        "name": "get_weather",
        "description": "Get the current weather for a city.",
        "inputSchema": {
            "type": "object",
            "properties": {"city": {"type": "string"}},
            "required": ["city"],
        },
    },
    {
        "name": "add",
        "description": "Add two integers.",
        "inputSchema": {
            "type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"],
        },
    },
    {
        "name": "echo",
        "description": "Echo the given text back.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
]


def call_tool(name, args):
    if name == "get_weather":
        return f"The weather in {args.get('city')} is Sunny, 22C."
    if name == "add":
        return str(int(args.get("a", 0)) + int(args.get("b", 0)))
    if name == "echo":
        return str(args.get("text", ""))
    raise ValueError(f"unknown tool: {name}")


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def handle(msg):
    method = msg.get("method")
    mid = msg.get("id")
    # Notifications (no id) get no response.
    if method == "initialize":
        params = msg.get("params") or {}
        # Echo the client's requested protocol version so negotiation never fails.
        version = params.get("protocolVersion", "2025-06-18")
        return {
            "jsonrpc": "2.0", "id": mid,
            "result": {
                "protocolVersion": version,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "bench-tools", "version": "1.0.0"},
            },
        }
    if method in ("notifications/initialized", "initialized"):
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}}
    if method == "tools/call":
        params = msg.get("params") or {}
        try:
            out = call_tool(params.get("name"), params.get("arguments") or {})
            return {"jsonrpc": "2.0", "id": mid,
                    "result": {"content": [{"type": "text", "text": out}],
                               "isError": False}}
        except Exception as e:  # noqa: BLE001
            return {"jsonrpc": "2.0", "id": mid,
                    "result": {"content": [{"type": "text", "text": str(e)}],
                               "isError": True}}
    if mid is not None:
        return {"jsonrpc": "2.0", "id": mid,
                "error": {"code": -32601, "message": f"method not found: {method}"}}
    return None


def main():
    import os
    _logf = os.environ.get("MCP_LOG")
    _log = open(_logf, "a") if _logf else None

    def _dbg(*a):
        if _log:
            _log.write(" ".join(str(x) for x in a) + "\n")
            _log.flush()

    _dbg("=== server start ===")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        _dbg("RECV", line)
        try:
            msg = json.loads(line)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(msg, list):  # batch
            for m in msg:
                resp = handle(m)
                if resp is not None:
                    send(resp)
            continue
        resp = handle(msg)
        _dbg("RESP", json.dumps(resp) if resp else "(none)")
        if resp is not None:
            send(resp)
    _dbg("=== stdin EOF ===")


if __name__ == "__main__":
    main()
