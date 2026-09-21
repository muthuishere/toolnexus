#!/usr/bin/env python3
"""A scripted fake ACP (Agent Client Protocol) server for hermetic Python tests of
``toolnexus.acp``.

Mirrors ``spikes/acp/fakeagent/main.go``'s scenario set and protocol-handling
*design* (JSON-RPC 2.0, one object per line, over its own stdin/stdout, with
server-initiated requests such as ``session/request_permission`` interleaved on the
same stream) — reimplemented here in Python, stdlib only, for this port's own test
suite. Not a general ACP implementation; a deterministic script selected with
``--scenario``.

Scenarios:
  default    - trivial instant echo: one ``agent_message_chunk`` per turn.
  stale      - stateful per-session history; without the client's
               ``SUPERSEDES-ALL-PRIOR`` marker it answers whichever earlier prompt
               is a substring of the current one (stale); with the marker present
               it answers the fresh text named by the marker.
  hang       - never replies to ``session/prompt`` and never asks permission — used
               to prove the client's own safety-net timeout is what ends the wait.
  permission - sends ``session/request_permission`` mid-turn and blocks until the
               client answers, then echoes the chosen ``optionId`` back in the
               final ``agent_message_chunk``.
  noisy      - interleaves ``agent_thought_chunk`` and ``tool_call``/
               ``tool_call_update`` notifications around the real
               ``agent_message_chunk`` content, split across >= 2 notifications.
  serialize  - records reentrancy (a "busy" flag) if a second ``session/prompt``
               arrives while one is already being handled on this session — proves
               the client serialises turns.

``diagnostics/get`` is a JSON-RPC method that is NOT part of real ACP: it returns
this process's internal counters as the reply ``result``, so a test can assert
things like "session/new happened exactly once" or "no reentrant session/prompt was
observed" without depending on timing.
"""
from __future__ import annotations

import json
import sys
import threading
import time

_STDOUT_LOCK = threading.Lock()


def _send(obj: dict) -> None:
    line = json.dumps(obj)
    with _STDOUT_LOCK:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def main() -> None:
    args = sys.argv[1:]
    scenario = "default"
    if "--scenario" in args:
        scenario = args[args.index("--scenario") + 1]

    state_lock = threading.Lock()
    state = {
        "session_new_count": 0,
        "prompt_count": 0,
        "busy": False,
        "reentrant_detected": False,
        "history": [],
        "last_option_id": None,
        "received_cwd": None,
        "received_mcp_servers": None,
    }
    next_srv_id = [0]
    perm_replies: dict[str, dict] = {}
    perm_cv = threading.Condition()

    def send_request(method: str, params: dict) -> str:
        next_srv_id[0] += 1
        rid = f"srv-{next_srv_id[0]}"
        _send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        return rid

    def send_notification(method: str, params: dict) -> None:
        _send({"jsonrpc": "2.0", "method": method, "params": params})

    def reply(msg_id, result) -> None:
        _send({"jsonrpc": "2.0", "id": msg_id, "result": result})

    def wait_for_perm_reply(rid: str) -> dict:
        with perm_cv:
            while rid not in perm_replies:
                perm_cv.wait()
            return perm_replies.pop(rid)

    def send_message_chunk(session_id: str, text: str) -> None:
        send_notification(
            "session/update",
            {
                "sessionId": session_id,
                "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}},
            },
        )

    def handle_prompt(msg_id, session_id: str, user_text: str) -> None:
        with state_lock:
            state["prompt_count"] += 1
            state["history"].append(user_text)
            history_snapshot = list(state["history"])

        if scenario == "hang":
            # Never reply, never ask permission. The client's own safety-net
            # timeout is the only thing that ever ends its wait.
            return

        if scenario == "permission":
            rid = send_request(
                "session/request_permission",
                {
                    "sessionId": session_id,
                    "options": [
                        {"optionId": "reject", "kind": "reject_once", "name": "Reject"},
                        {"optionId": "allow-once", "kind": "allow_once", "name": "Allow"},
                    ],
                },
            )
            answer = wait_for_perm_reply(rid)
            outcome = (answer.get("result") or {}).get("outcome") or {}
            option_id = outcome.get("optionId")
            with state_lock:
                state["last_option_id"] = option_id
            send_message_chunk(session_id, f"PERMITTED:{option_id}")
            reply(msg_id, {"stopReason": "end_turn"})
            return

        if scenario == "noisy":
            send_notification(
                "session/update",
                {
                    "sessionId": session_id,
                    "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "Let me think about this... "}},
                },
            )
            send_notification(
                "session/update",
                {"sessionId": session_id, "update": {"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "reading files", "status": "in_progress"}},
            )
            send_message_chunk(session_id, '{"answer":')
            send_notification(
                "session/update",
                {"sessionId": session_id, "update": {"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed"}},
            )
            send_notification(
                "session/update",
                {
                    "sessionId": session_id,
                    "update": {"sessionUpdate": "agent_thought_chunk", "content": {"type": "text", "text": "now double-checking the number... "}},
                },
            )
            send_message_chunk(session_id, f'"{user_text}"}}')
            reply(msg_id, {"stopReason": "end_turn"})
            return

        if scenario == "stale":
            marker = "SUPERSEDES-ALL-PRIOR:"
            idx = user_text.find(marker)
            if idx >= 0:
                fresh = user_text[idx + len(marker):].strip()
                answer = f"FRESH-ANSWER-TO:{fresh}"
            else:
                matched = history_snapshot[0] if history_snapshot else ""
                for h in history_snapshot:
                    if h and h in user_text:
                        matched = h
                        break
                answer = f"STALE-ANSWER-TO:{matched}"
            send_message_chunk(session_id, answer)
            reply(msg_id, {"stopReason": "end_turn"})
            return

        if scenario == "serialize":
            with state_lock:
                if state["busy"]:
                    state["reentrant_detected"] = True
                state["busy"] = True
            time.sleep(0.15)
            send_message_chunk(session_id, f"echo:{user_text}")
            with state_lock:
                state["busy"] = False
            reply(msg_id, {"stopReason": "end_turn"})
            return

        # default
        send_message_chunk(session_id, f"echo:{user_text}")
        reply(msg_id, {"stopReason": "end_turn"})

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = msg.get("method")
        msg_id = msg.get("id")

        if not method and msg_id is not None:
            # A reply to one of THIS process's server-initiated requests
            # (session/request_permission).
            with perm_cv:
                perm_replies[msg_id] = msg
                perm_cv.notify_all()
            continue

        if method == "initialize":
            reply(msg_id, {"protocolVersion": 1, "agentCapabilities": {"loadSession": False}})
        elif method == "session/new":
            params = msg.get("params") or {}
            with state_lock:
                state["session_new_count"] += 1
                count = state["session_new_count"]
                state["received_cwd"] = params.get("cwd")
                state["received_mcp_servers"] = params.get("mcpServers")
            reply(
                msg_id,
                {
                    "sessionId": "sess-1",
                    "sessionNewCount": count,
                    "receivedCwd": params.get("cwd"),
                    "receivedMcpServers": params.get("mcpServers"),
                },
            )
        elif method == "session/set_mode":
            reply(msg_id, {})
        elif method == "session/prompt":
            params = msg.get("params") or {}
            session_id = params.get("sessionId")
            text = "".join(p.get("text", "") for p in (params.get("prompt") or []))
            threading.Thread(target=handle_prompt, args=(msg_id, session_id, text), daemon=True).start()
        elif method == "session/cancel":
            reply(msg_id, {})
        elif method == "diagnostics/get":
            with state_lock:
                reply(msg_id, dict(state))
        else:
            if msg_id is not None:
                reply(msg_id, {})


if __name__ == "__main__":
    main()
