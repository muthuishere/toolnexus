#!/usr/bin/env python3
"""A fake ACP agent for conformance/acp-client/cases.json (SPEC §8C), stdlib only.

Records every received request as one JSON line ``{"method", "params"}`` to the file
named by ``TN_FAKE_REC``. Flags (env): ``TN_FAKE_TOOLUPDATE`` (replay stream.acpToolUpdate.updates), ``TN_FAKE_DRIP`` (thought + chunks spaced in
time), ``TN_FAKE_NO_CONFIG_OPTION`` (reject session/set_config_option),
``TN_FAKE_REJECT_MODE`` (reject session/set_mode), ``TN_FAKE_EXIT_ON_PROMPT`` (exit without replying to session/prompt),
``TN_FAKE_FS_REQUEST`` (send fs/read_text_file first and require a -32601 reply).
"""
from __future__ import annotations

import json
import os
import sys
import time


def main() -> None:
    out = sys.stdout
    rec_path = os.environ.get("TN_FAKE_REC")
    lines = iter(sys.stdin.readline, "")

    def send(o) -> None:
        out.write(json.dumps(o) + "\n")
        out.flush()

    def update(u) -> None:
        send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s1", "update": u}})

    def text(kind: str, t: str) -> None:
        update({"sessionUpdate": kind, "content": {"type": "text", "text": t}})

    for raw in lines:
        try:
            m = json.loads(raw)
        except ValueError:
            continue
        method, mid, params = m.get("method"), m.get("id"), m.get("params") or {}
        if method and rec_path:
            with open(rec_path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"method": method, "params": params}) + "\n")
        if method == "initialize":
            send({"jsonrpc": "2.0", "id": mid, "result": {"protocolVersion": 1, "agentCapabilities": {}}})
        elif method == "session/new":
            send({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": "s1"}})
        elif method == "session/set_config_option":
            if os.environ.get("TN_FAKE_NO_CONFIG_OPTION") == "1":
                send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "nope"}})
            else:
                send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_model":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_mode":
            if os.environ.get("TN_FAKE_REJECT_MODE") == "1":
                send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": "unknown mode"}})
            else:
                send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/prompt":
            if os.environ.get("TN_FAKE_EXIT_ON_PROMPT") == "1":
                sys.exit(0)
            if os.environ.get("TN_FAKE_DRIP") == "1":
                text("agent_thought_chunk", "pondering")
                for i, chunk in enumerate(["drip-1 ", "drip-2 ", "drip-3"]):
                    if i:
                        time.sleep(0.2)
                    text("agent_message_chunk", chunk)
            elif os.environ.get("TN_FAKE_TOOLUPDATE") == "1":
                cases_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "conformance", "acp-client", "cases.json")
                with open(cases_path, encoding="utf-8") as f:
                    updates = json.load(f)["stream"]["acpToolUpdate"]["updates"]
                for update in updates:
                    send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s1", "update": update}})
            elif os.environ.get("TN_FAKE_FS_REQUEST") == "1":
                send({"jsonrpc": "2.0", "id": 9999, "method": "fs/read_text_file", "params": {"path": "/x"}})
                code = None
                for raw2 in lines:
                    try:
                        r = json.loads(raw2)
                    except ValueError:
                        continue
                    if r.get("id") == 9999:
                        code = (r.get("error") or {}).get("code")
                        break
                text("agent_message_chunk", "ok" if code == -32601 else f"bad reply {code}")
            else:
                text("agent_message_chunk", "hello")
            send({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})
        elif mid is not None:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "unknown"}})


if __name__ == "__main__":
    main()
